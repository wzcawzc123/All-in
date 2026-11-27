package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Verifies MiniMax refresh fence/unsent recovery, WorkBuddy allowance 401 tightening,
 * Codex refresh error classification (codex_signin_gone parity), and Raccoon 429/4xx classification.
 */
public class AccountAuthHardeningTest {
    private static int checks = 0;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL: " + msg + " expected=" + expected + " actual=" + actual);
        }
    }

    private static final class MockConn extends HttpURLConnection {
        private final int status;
        private final String body;
        private final String wwwAuth;
        private final IOException openOutputErr;
        private final IOException readErr;
        MockConn(String url, int status, String body, String wwwAuth, IOException openOutputErr, IOException readErr) throws IOException {
            super(new URL(url));
            this.status = status;
            this.body = body == null ? "" : body;
            this.wwwAuth = wwwAuth;
            this.openOutputErr = openOutputErr;
            this.readErr = readErr;
        }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public String getHeaderField(String name) {
            if ("WWW-Authenticate".equalsIgnoreCase(name)) return wwwAuth;
            return null;
        }
        @Override public OutputStream getOutputStream() throws IOException {
            if (openOutputErr != null) throw openOutputErr;
            return new ByteArrayOutputStream();
        }
        @Override public int getResponseCode() throws IOException {
            if (readErr != null) throw readErr;
            return status;
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static MiniMaxRefreshJournal testJournal(File dir) {
        return new MiniMaxRefreshJournal(dir) {
            @Override void syncDirectory() {}
        };
    }

    public void miniMaxUnsentPostSendInvalidGrantAndRestart() throws Exception {
        File dir = Files.createTempDirectory("mm-journal-test").toFile();
        try {
            MiniMaxRefreshJournal journal = testJournal(dir);
            MiniMaxLifecycle lifecycle = new MiniMaxLifecycle(() -> 1_000_000L);
            MiniMaxLifecycle.Credentials init = new MiniMaxLifecycle.Credentials("acc_1", "rt_1", 500_000L);
            lifecycle.register("mm1", init);

            // 1. Provably unsent (connect fails before write): journal pending fence abandoned, lifecycle NOT poisoned
            MiniMaxLifecycle.Lease lease1 = lifecycle.acquire("mm1");
            boolean unsentCaught = false;
            try {
                lifecycle.refresh(lease1, false, 600_000L, old -> {
                    MiniMaxRefreshJournal.Ticket tk = journal.begin("mm1", old.refresh);
                    try {
                        MiniMaxAuth.refreshChecked(old.refresh, u -> new MockConn(u, 0, "", null, new ConnectException("offline"), null));
                        throw new AssertionError("expected unsent failure");
                    } catch (MiniMaxAuth.RefreshFailure rf) {
                        check(rf.definitelyNotSent(), "connect failure must be NOT_SENT");
                        check(journal.abandonUnsent(tk), "unsent fence must be abandoned");
                        throw rf;
                    }
                }, (id, gen, old, next) -> true);
            } catch (MiniMaxAuth.RefreshFailure rf) {
                unsentCaught = rf.definitelyNotSent();
            }
            check(unsentCaught, "unsent RefreshFailure propagated");
            checkEq(0, journal.stats().pending, "journal has 0 pending fences after unsent abandon");

            // 2. Recovery after unsent succeeds and persists rotated credentials
            MiniMaxLifecycle.Lease lease2 = lifecycle.acquire("mm1");
            final MiniMaxRefreshJournal.Ticket[] okTicket = new MiniMaxRefreshJournal.Ticket[1];
            MiniMaxLifecycle.Lease refreshed = lifecycle.refresh(lease2, false, 600_000L, old -> {
                okTicket[0] = journal.begin("mm1", old.refresh);
                MiniMaxAuth.SignIn si = MiniMaxAuth.refreshChecked(old.refresh, u -> new MockConn(u, 200,
                        "{\"access_token\":\"acc_2\",\"refresh_token\":\"rt_2\",\"expires_in\":3600}", null, null, null));
                return new MiniMaxLifecycle.Credentials(si.access, si.refresh, si.expires);
            }, (id, gen, old, next) -> {
                check(journal.complete(okTicket[0]), "journal complete succeeds");
                return true;
            });
            checkEq("acc_2", refreshed.credentials.access, "recovered access_2");
            checkEq("rt_2", refreshed.credentials.refresh, "recovered refresh_2");
            check(!journal.abandonUnsent(okTicket[0]), "spent ticket cannot be abandoned");

            // Verify spent fence survives restart
            MiniMaxRefreshJournal restarted = testJournal(dir);
            boolean spentBlocked = false;
            try { restarted.begin("mm1", "rt_1"); } catch (MiniMaxRefreshJournal.Blocked b) { spentBlocked = true; }
            check(spentBlocked, "spent rt_1 stays blocked after restart");

            // 3. Post-send disconnect: NOT unsent -> journal keeps pending fence across restart & lifecycle poisoned
            MiniMaxLifecycle.Lease lease3 = lifecycle.acquire("mm1");
            boolean postSendCaught = false;
            try {
                lifecycle.refresh(lease3, true, 600_000L, old -> {
                    journal.begin("mm1", old.refresh);
                    MiniMaxAuth.refreshChecked(old.refresh, u -> new MockConn(u, 0, "", null, null, new IOException("reset after send")));
                    throw new AssertionError("expected post-send failure");
                }, (id, gen, old, next) -> true);
            } catch (MiniMaxAuth.RefreshFailure rf) {
                postSendCaught = !rf.definitelyNotSent() && rf.kind == MiniMaxAuth.RefreshFailure.Kind.OUTCOME_UNKNOWN;
            }
            check(postSendCaught, "post-send disconnect classified OUTCOME_UNKNOWN");
            boolean poisoned = false;
            try { lifecycle.acquire("mm1"); } catch (MiniMaxLifecycle.Unavailable u) { poisoned = true; }
            check(poisoned, "entry poisoned after post-send disconnect");
            boolean restartBlocked = false;
            try { testJournal(dir).begin("mm1", "rt_2"); } catch (MiniMaxRefreshJournal.Blocked b) { restartBlocked = true; }
            check(restartBlocked, "pending rt_2 stays blocked across restart");

            // 4. Explicit HTTP 400 invalid_grant classified as EXPLICIT_REJECT + signedOut
            boolean invalidGrant = false;
            try {
                MiniMaxAuth.refreshChecked("rt_3", u -> new MockConn(u, 400, "{\"error\":\"invalid_grant\"}", null, null, null));
            } catch (MiniMaxAuth.RefreshFailure rf) {
                invalidGrant = rf.kind == MiniMaxAuth.RefreshFailure.Kind.EXPLICIT_REJECT && rf.signedOut && !rf.definitelyNotSent();
            }
            check(invalidGrant, "HTTP 400 invalid_grant classified signedOut");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
    }

    public void workBuddyAllowanceTightensBare401() throws Exception {
        JSONObject acc = new JSONObject().put("type", "oauth").put("access", "tok").put("uid", "u1");
        // Bare 401 without WWW-Authenticate: invalid_token -> unavailable, NOT sign_in_required
        boolean bareHandled = false;
        try {
            WorkBuddyAllowance.fetch("workbuddy", acc, u -> new MockConn(u, 401, "{}", null, null, null));
        } catch (WorkBuddyAllowance.Failure f) {
            bareHandled = "unavailable".equals(f.kind) && !f.invalidToken;
        }
        check(bareHandled, "WorkBuddy bare 401 must not trigger sign_in_required");

        // 401 with WWW-Authenticate: Bearer error="invalid_token" -> sign_in_required + invalidToken=true
        boolean explicitHandled = false;
        try {
            WorkBuddyAllowance.fetch("workbuddy", acc, u -> new MockConn(u, 401, "{}", "Bearer error=\"invalid_token\"", null, null));
        } catch (WorkBuddyAllowance.Failure f) {
            explicitHandled = "sign_in_required".equals(f.kind) && f.invalidToken;
        }
        check(explicitHandled, "WorkBuddy invalid_token 401 triggers sign_in_required");
    }

    public void codexAndRaccoonErrorClassification() {
        // Codex parity with codex_signin_gone.go
        check(CodexAuth.RefreshFailure.isRefreshSignInGone(400, "refresh_token_expired"), "codex expired");
        check(CodexAuth.RefreshFailure.isRefreshSignInGone(400, "refresh_token_reused"), "codex reused");
        check(CodexAuth.RefreshFailure.isRefreshSignInGone(401, "refresh_token_invalidated"), "codex invalidated");
        check(CodexAuth.RefreshFailure.isRefreshSignInGone(400, "invalid_grant"), "codex 400 invalid_grant");
        check(CodexAuth.RefreshFailure.isRefreshSignInGone(401, "token_revoked"), "codex 401 with code");
        check(!CodexAuth.RefreshFailure.isRefreshSignInGone(401, ""), "codex bare 401 proxy page is NOT gone");
        check(!CodexAuth.RefreshFailure.isRefreshSignInGone(429, "rate_limit"), "codex 429 is NOT gone");
        check(!CodexAuth.RefreshFailure.isRefreshSignInGone(500, "server_error"), "codex 500 is NOT gone");
        checkEq("refresh_token_reused", CodexAuth.RefreshFailure.parseErrorCode("{\"error\":{\"code\":\"refresh_token_reused\"}}"), "codex parse nested error.code");

        // Raccoon tightening: 429/408/403/500 are NOT expired; 401 and auth biz codes ARE expired
        check(!RaccoonRefreshService.isHttpAuthExpired(429, new JSONObject()), "raccoon 429 not expired");
        check(!RaccoonRefreshService.isHttpAuthExpired(408, new JSONObject()), "raccoon 408 not expired");
        check(!RaccoonRefreshService.isHttpAuthExpired(403, new JSONObject()), "raccoon 403 not expired");
        check(!RaccoonRefreshService.isHttpAuthExpired(500, new JSONObject()), "raccoon 500 not expired");
        check(RaccoonRefreshService.isHttpAuthExpired(401, new JSONObject()), "raccoon 401 expired");
        check(RaccoonRefreshService.isHttpAuthExpired(400, new JSONObject().put("code", 200001)), "raccoon biz 200001 expired");
        check(RaccoonRefreshService.isHttpAuthExpired(400, new JSONObject().put("error", "invalid_grant")), "raccoon 400 invalid_grant expired");
    }

    public void workBuddyNativeMessagesInvalidTokenRecoversOnceAndBare401DoesNot() throws Exception {
        // 1. Explicit 401 + WWW-Authenticate: invalid_token triggers one coordinated refresh and retry
        JSONObject acc = new JSONObject()
                .put("id", "wb-native-1").put("uid", "uid-wb-1").put("name", "WB")
                .put("type", "oauth").put("access", "old-native-tok").put("token", "old-native-tok")
                .put("refresh", "wb-refresh-1").put("expires", System.currentTimeMillis() + 3600_000L)
                .put("domain", "copilot.tencent.com").put("enabled", true);
        JSONObject prov = new JSONObject().put("id", "workbuddy").put("name", "WorkBuddy").put("enabled", true)
                .put("models", new com.gaeul.allin.json.JSONArray().put("claude-sonnet-4-5"))
                .put("accounts", new com.gaeul.allin.json.JSONArray().put(acc));
        JSONObject cfg = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("guard", new JSONObject().put("enabled", false))
                .put("providers", new com.gaeul.allin.json.JSONArray().put(prov));
        GatewayServer srv = RoutingSetupOfflineTest.server(cfg);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wb-native-401-");
        ResponsesGatewayOfflineTest.set(srv, "configFile", dir.resolve("config.json").toFile());
        int[] refreshes = {0};
        int[] calls = {0};
        String sseOk = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-5\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        srv.setTokenRefreshForTests((before, force) -> {
            refreshes[0]++;
            return new JSONObject(before.toString())
                    .put("access", "renewed-native-tok").put("token", "renewed-native-tok")
                    .put("refresh", "wb-refresh-2").put("expires", System.currentTimeMillis() + 7200_000L);
        });
        srv.setVendorConnectionsForTests(u -> new HttpURLConnection(new URL(u)) {
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public int getResponseCode() {
                calls[0]++;
                return "Bearer renewed-native-tok".equals(getRequestProperty("Authorization")) ? 200 : 401;
            }
            @Override public String getContentType() {
                return "Bearer renewed-native-tok".equals(getRequestProperty("Authorization"))
                        ? "text/event-stream; charset=UTF-8" : "application/json";
            }
            @Override public java.util.Map<String, java.util.List<String>> getHeaderFields() {
                java.util.Map<String, java.util.List<String>> m = new java.util.LinkedHashMap<>();
                if (!"Bearer renewed-native-tok".equals(getRequestProperty("Authorization"))) {
                    m.put("WWW-Authenticate", java.util.Collections.singletonList("Bearer error=\"invalid_token\""));
                }
                return m;
            }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(sseOk.getBytes(StandardCharsets.UTF_8)); }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream("{\"error\":\"invalid_token\"}".getBytes(StandardCharsets.UTF_8)); }
        });
        String chatReq = new JSONObject().put("model", "workbuddy/claude-sonnet-4-5").put("stream", false)
                .put("messages", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
        fi.iki.elonen.NanoHTTPD.Response resp = srv.serve(ResponsesGatewayOfflineTest.session(chatReq, "fixture-auth", "/v1/chat/completions"));
        check(resp.getStatus().getRequestStatus() == 200, "WorkBuddy native Messages recovers after explicit invalid_token: " + resp.getStatus());
        checkEq(1, refreshes[0], "WorkBuddy native Messages refreshes exactly once");
        checkEq(2, calls[0], "WorkBuddy native Messages retries once after refresh");

        // 2. Bare 401 (without WWW-Authenticate: invalid_token) must NOT refresh
        int[] bareRefreshes = {0};
        int[] bareCalls = {0};
        GatewayServer bareSrv = RoutingSetupOfflineTest.server(new JSONObject(cfg.toString()));
        ResponsesGatewayOfflineTest.set(bareSrv, "configFile", dir.resolve("config-bare.json").toFile());
        bareSrv.setTokenRefreshForTests((before, force) -> { bareRefreshes[0]++; return before; });
        bareSrv.setVendorConnectionsForTests(u -> new HttpURLConnection(new URL(u)) {
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public int getResponseCode() { bareCalls[0]++; return 401; }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream("{\"error\":\"proxy_401\"}".getBytes(StandardCharsets.UTF_8)); }
        });
        fi.iki.elonen.NanoHTTPD.Response bareResp = bareSrv.serve(ResponsesGatewayOfflineTest.session(chatReq, "fixture-auth", "/v1/chat/completions"));
        check(bareResp.getStatus().getRequestStatus() == 401, "WorkBuddy native Messages bare 401 returns 401");
        checkEq(0, bareRefreshes[0], "WorkBuddy native Messages bare 401 must NOT trigger refresh");
        checkEq(1, bareCalls[0], "WorkBuddy native Messages bare 401 must NOT retry");
    }

    public void codexSignInGoneSurfacesAuthExpiredHintWithoutTransientFalsePositive() throws Exception {
        // End-to-end GatewayServer.forwardCodex verification for RefreshFailure.signInGone (401 vs 502)
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("codex-sign-in-gone-");
        String secretRefresh = "codex-secret-rt-999888";
        JSONObject acc = new JSONObject()
                .put("id", "codex-e2e-1").put("name", "CodexE2E").put("enabled", true)
                .put("type", "oauth").put("authMode", "chatgpt")
                .put("access", "codex-expired-access-111").put("refresh", secretRefresh)
                .put("accountId", "acct-e2e").put("expires", System.currentTimeMillis() - 60_000L);
        JSONObject prov = new JSONObject().put("id", "codex").put("name", "Codex").put("protocol", "codex")
                .put("enabled", true).put("baseUrl", CodexAuth.CODEX_BASE)
                .put("models", new com.gaeul.allin.json.JSONArray().put("gpt-5"))
                .put("accounts", new com.gaeul.allin.json.JSONArray().put(acc));
        JSONObject cfg = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth"))
                .put("guard", new JSONObject().put("enabled", false))
                .put("failover", new JSONObject().put("enabled", false))
                .put("providers", new com.gaeul.allin.json.JSONArray().put(prov));
        String req = new JSONObject().put("model", "codex/gpt-5").put("stream", false)
                .put("messages", new com.gaeul.allin.json.JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();

        // 1. Permanent sign-in-gone (400 refresh_token_expired) -> Gateway returns 401 with re-login hint, no secret leaked
        CodexAuth.refreshConnectionFactoryForTests = u -> new MockConn(u, 400,
                "{\"error\":{\"code\":\"refresh_token_expired\",\"message\":\"token " + secretRefresh + " expired\"}}", null, null, null);
        try {
            GatewayServer srvGone = RoutingSetupOfflineTest.server(new JSONObject(cfg.toString()));
            ResponsesGatewayOfflineTest.set(srvGone, "configFile", dir.resolve("cfg-gone.json").toFile());
            fi.iki.elonen.NanoHTTPD.Response rGone = srvGone.serve(ResponsesGatewayOfflineTest.session(req, "fixture-auth", "/v1/chat/completions"));
            String bGone = ResponsesGatewayOfflineTest.read(rGone.getData());
            check(rGone.getStatus().getRequestStatus() == 401, "Codex signInGone must return 401, got " + rGone.getStatus() + " " + bGone);
            check(bGone.contains("重新登录") && bGone.contains("refresh_token_expired"), "Codex signInGone response includes re-login hint & code: " + bGone);
            check(!bGone.contains(secretRefresh), "Codex signInGone response must not leak refresh token");

            // 2. Transient 503 during refresh -> Gateway keeps 502 and does NOT report sign-in gone
            CodexAuth.refreshConnectionFactoryForTests = u -> new MockConn(u, 503, "{\"error\":\"temporarily_unavailable\"}", null, null, null);
            GatewayServer srv503 = RoutingSetupOfflineTest.server(new JSONObject(cfg.toString()));
            ResponsesGatewayOfflineTest.set(srv503, "configFile", dir.resolve("cfg-503.json").toFile());
            fi.iki.elonen.NanoHTTPD.Response r503 = srv503.serve(ResponsesGatewayOfflineTest.session(req, "fixture-auth", "/v1/chat/completions"));
            String b503 = ResponsesGatewayOfflineTest.read(r503.getData());
            check(r503.getStatus().getRequestStatus() == 502, "Codex transient 503 refresh must stay 502, got " + r503.getStatus() + " " + b503);
            check(!b503.contains("重新登录"), "Codex transient 503 must not falsely claim sign-in gone: " + b503);
        } finally {
            CodexAuth.refreshConnectionFactoryForTests = null;
        }
    }

    public static void main(String[] args) throws Exception {
        AccountAuthHardeningTest t = new AccountAuthHardeningTest();
        t.miniMaxUnsentPostSendInvalidGrantAndRestart();
        t.workBuddyAllowanceTightensBare401();
        t.codexAndRaccoonErrorClassification();
        t.workBuddyNativeMessagesInvalidTokenRecoversOnceAndBare401DoesNot();
        t.codexSignInGoneSurfacesAuthExpiredHintWithoutTransientFalsePositive();
        System.out.println("AccountAuthHardeningTest PASS checks=" + checks + " failures=0");
    }
}
