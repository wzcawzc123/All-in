package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs the actual management, access inventory and OpenAI list routes with synthetic accounts. */
public final class CodexCatalogReconnectHttpTest {
    private static final String STATIC_KEY = "sk-independent-fixture-key-0000";
    private static final String SYNC = "/api/auth/codex/sync-models";
    private static final String CATALOG = "{\"models\":[{\"slug\":\"fresh-model\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject account(String id, String access) {
        return new JSONObject().put("id", id).put("type", "oauth").put("authMode", "chatgpt")
                .put("accountId", id).put("access", access).put("token", access).put("enabled", true);
    }

    private static JSONObject provider(CodexApiKeyPoolHttpTest.Fixture fixture) throws Exception {
        return CodexApiKeyPoolHttpTest.provider(fixture.memory());
    }

    private static final class Reply {
        final int status;
        final JSONObject body;
        Reply(int status, JSONObject body) { this.status = status; this.body = body; }
    }

    private static Reply request(CodexApiKeyPoolHttpTest.Fixture f, String path, JSONObject body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + f.getListeningPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        conn.setRequestProperty("Authorization", "Bearer fixture-inference-auth");
        try {
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = conn.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            try (InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                return new Reply(status, new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } finally { conn.disconnect(); }
    }

    private static String login(CodexApiKeyPoolHttpTest.Fixture f, String owner, String token) throws Exception {
        JSONObject raw = new JSONObject().put("account_id", owner).put("access_token", token);
        Reply reply = request(f, "/api/auth/codex/import", new JSONObject().put("raw", raw.toString()));
        check(reply.status == 200 && reply.body.optBoolean("ok"), "login must persist independently of catalog availability");
        return reply.body.getString("keyId");
    }

    private static void remove(CodexApiKeyPoolHttpTest.Fixture f, String id) throws Exception {
        JSONObject config = request(f, "/api/config", null).body;
        String state = config.getJSONObject("providerStateTokens").getString("codex");
        Reply reply = request(f, "/api/auth/codex/remove", new JSONObject().put("id", id).put("expectedStateToken", state));
        check(reply.status == 200 && reply.body.optBoolean("removed"), "account removed via the real management route");
    }

    private static boolean listed(CodexApiKeyPoolHttpTest.Fixture f, String path, String field, String id) throws Exception {
        Reply reply = request(f, path, null);
        check(reply.status == 200, "inventory readable");
        JSONArray models = reply.body.getJSONArray(field);
        for (int i = 0; i < models.length(); i++) {
            if (id.equals(models.getJSONObject(i).getString("id"))) return true;
        }
        return false;
    }

    private static void reconnect() throws Exception {
        JSONArray seed = new JSONArray().put(account("owner", "first-access"));
        try (CodexApiKeyPoolHttpTest.Fixture f = new CodexApiKeyPoolHttpTest.Fixture(seed)) {
            List<CatalogReadIntegrityHttpTest.Fake> upstream = new ArrayList<>();
            f.inner.setCodexCatalogForTests(url -> {
                CatalogReadIntegrityHttpTest.Fake response = new CatalogReadIntegrityHttpTest.Fake(200, CATALOG);
                upstream.add(response);
                return response;
            });
            check(request(f, SYNC, null).status == 200, "initial pool has confirmed directory");
            check("owner".equals(login(f, "owner", "second-access")), "same owner keeps account identity");
            JSONArray keys = provider(f).getJSONArray("keys");
            check(keys.length() == 1 && "second-access".equals(keys.getString(0)), "relogin replaces previous owned mirror");
            remove(f, "owner");
            check(provider(f).getJSONArray("keys").length() == 0, "delete clears owned mirror");
            check(!listed(f, "/v1/models", "data", "codex/fresh-model"), "deleted account leaves Agent list");
            check(!listed(f, "/api/gateway-access", "models", "codex/fresh-model"), "deleted account leaves access inventory");

            String id = login(f, "owner", "third-access");
            check(!"owner".equals(id), "deleted account gets a new local identity");
            check(upstream.size() == 2 && upstream.get(1).getRequestProperty("Authorization").endsWith("third-access"),
                    "reconnect under a new pool syncs using the new account despite a nonempty old catalog");
            check(GatewayAccessCatalog.poolFingerprint(provider(f)).equals(provider(f).getString("accessCatalogPool")),
                    "live directory belongs to the new account pool");
            check(listed(f, "/v1/models", "data", "codex/fresh-model"), "Agent gets confirmed Codex model");
            check(listed(f, "/api/gateway-access", "models", "codex/fresh-model"), "access page gets confirmed Codex model");
            check(!listed(f, "/v1/models", "data", "codex/hidden"), "hidden upstream model stays hidden");
            check(CodexApiKeyPoolHttpTest.provider(f.disk()).similar(provider(f)), "published pool and disk agree");

            byte[] prior = Files.readAllBytes(f.configFile.toPath());
            f.inner.setCodexCatalogForTests(url -> new CatalogReadIntegrityHttpTest.Fake(503, "{}"));
            check(request(f, SYNC, null).status == 502, "failed directory cannot masquerade as a live sync");
            check(Arrays.equals(prior, Files.readAllBytes(f.configFile.toPath())), "upstream error preserves confirmed catalog and credentials");
            check(listed(f, "/api/gateway-access", "models", "codex/fresh-model"), "last confirmed directory remains usable");
        }
    }

    private static void mixedAccounts() throws Exception {
        JSONArray accounts = new JSONArray().put(CodexApiKeyPoolHttpTest.keyAccount("api", STATIC_KEY, "static"))
                .put(account("disabled", "disabled-access").put("enabled", false))
                .put(account("expired", "expired-access").put("expires", 1L))
                .put(account("rejected", "still-valid-shape"))
                .put(account("working", "working-access").put("access", ""));
        try (CodexApiKeyPoolHttpTest.Fixture f = new CodexApiKeyPoolHttpTest.Fixture(accounts)) {
            List<CatalogReadIntegrityHttpTest.Fake> upstream = new ArrayList<>();
            f.inner.setCodexCatalogForTests(url -> {
                CatalogReadIntegrityHttpTest.Fake response = upstream.isEmpty()
                        ? new CatalogReadIntegrityHttpTest.Fake(401, "{}") : new CatalogReadIntegrityHttpTest.Fake(200, CATALOG);
                upstream.add(response);
                return response;
            });
            Reply reply = request(f, SYNC, null);
            check(reply.status == 200 && reply.body.getInt("count") == 1, "later usable subscription syncs in mixed pool");
            check(upstream.size() == 2, "skip static, disabled and expired credentials; retry a rejected account");
            check("Bearer still-valid-shape".equals(upstream.get(0).getRequestProperty("Authorization"))
                    && "Bearer working-access".equals(upstream.get(1).getRequestProperty("Authorization")), "correct OAuth accounts sent in order");
            check(provider(f).getJSONArray("accounts").length() == 5, "all original accounts survive sync");
        }
    }

    private static void orphanAndLegacyKey() throws Exception {
        try (CodexApiKeyPoolHttpTest.Fixture f = new CodexApiKeyPoolHttpTest.Fixture()) {
            JSONObject p = provider(f);
            p.getJSONArray("keys").put("unowned-old-subscription-token");
            f.inner.setCodexCatalogForTests(url -> { throw new AssertionError("no subscription request expected"); });
            check(request(f, SYNC, null).status == 401, "orphan key cannot initiate subscription catalog I/O");
            check(!listed(f, "/v1/models", "data", "codex/fixture-model"), "orphan key does not advertise models to Agent");
            check(!listed(f, "/api/gateway-access", "models", "codex/fixture-model"), "orphan key does not advertise models in panel");
            check(request(f, "/api/config", null).body.getInt("codexIndependentKeyCount") == 0, "orphan is not a configured Key");
            JSONObject chat = new JSONObject().put("model", "codex/fixture-model").put("messages",
                    new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping")));
            Reply denied = request(f, "/v1/chat/completions", chat);
            check(denied.status == 400 && denied.body.getJSONObject("error").getString("message").startsWith("No available API Key"),
                    "an orphan cannot route a previously known model");
            p.getJSONArray("keys").put(STATIC_KEY);
            check(request(f, "/api/config", null).body.getInt("codexIndependentKeyCount") == 1, "independent Key stays visible");
            check(listed(f, "/v1/models", "data", "codex/fixture-model"), "independent API key retains configured Agent catalog");
            f.inner.setCodexCatalogForTests(url -> new CatalogReadIntegrityHttpTest.Fake(200, CATALOG));
            String id = login(f, "owner", "access");
            remove(f, id);
            check(provider(f).getJSONArray("keys").length() == 2, "delete preserves independent and historically unclassified keys");
            check(provider(f).getJSONArray("keys").getString(1).equals(STATIC_KEY), "independent key survives unchanged");
        }
    }

    private static void concurrentRemoval() throws Exception {
        JSONArray accounts = new JSONArray().put(account("owner", "access"));
        try (CodexApiKeyPoolHttpTest.Fixture f = new CodexApiKeyPoolHttpTest.Fixture(accounts)) {
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            f.inner.setCodexCatalogForTests(url -> {
                reached.countDown();
                if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("catalog test timed out");
                return new CatalogReadIntegrityHttpTest.Fake(200, CATALOG);
            });
            FutureTask<Reply> refresh = new FutureTask<>(() -> request(f, SYNC, null));
            Thread worker = new Thread(refresh, "codex-catalog-test");
            worker.start();
            try {
                check(reached.await(5, TimeUnit.SECONDS), "catalog I/O is in flight");
                remove(f, "owner");
            } finally { proceed.countDown(); }
            check(refresh.get(10, TimeUnit.SECONDS).status == 409, "removed account cannot commit its delayed catalog");
            check(provider(f).getJSONArray("models").getString(0).equals("fixture-model"), "delayed response did not overwrite catalog");
            check(provider(f).getJSONArray("accounts").length() == 0, "delayed response did not resurrect account");
        }
    }

    public static void main(String[] args) throws Exception {
        reconnect();
        mixedAccounts();
        orphanAndLegacyKey();
        concurrentRemoval();
        System.out.println("CodexCatalogReconnectHttpTest passed " + checks + " checks");
    }
}
