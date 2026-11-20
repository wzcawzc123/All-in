package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * zcode gift 多账号轮换回归：合成账号 + 注入 GiftTransport，不联网。
 *
 * <p>覆盖 XunE 补丁的核心语义：请求按配置顺序逐个 jwt 账号试，
 * 额度花完 / 模型不支持 / 额度查询失败 / 上游拒绝都自动换下一个；
 * 只有所有账号都明确花完才返回 null 让网关走付费 Coding Plan。
 */
public final class ZCodeGiftFailoverHttpTest {
    static final String MODEL = "zcode/glm-5.3-flash";
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    /** 按 jwt 定行为的 transport：summary 模式 0=有额度 1=花完 2=查询失败 3=不含该模型。 */
    static final class MultiTransport implements GatewayServer.GiftTransport {
        final Map<String, Integer> summaryMode = new HashMap<String, Integer>();
        final Map<String, Integer> openStatus = new HashMap<String, Integer>();
        final List<String> summaryJwts = Collections.synchronizedList(new ArrayList<String>());
        final List<String> openJwts = Collections.synchronizedList(new ArrayList<String>());
        MultiTransport(String... jwts) {
            for (String jwt : jwts) { summaryMode.put(jwt, 0); openStatus.put(jwt, 200); }
        }
        MultiTransport mode(String jwt, int summary, int open) {
            summaryMode.put(jwt, summary); openStatus.put(jwt, open); return this;
        }
        public JSONObject summary(String jwt, String device) {
            summaryJwts.add(jwt);
            int mode = summaryMode.containsKey(jwt) ? summaryMode.get(jwt) : 1;
            if (mode == 2) return new JSONObject().put("ok", false).put("error", "fixture summary failure");
            JSONArray models = new JSONArray();
            if (mode == 0) models.put("GLM-5.3-Flash");
            if (mode == 3) models.put("OTHER-MODEL");
            return new JSONObject().put("ok", true).put("complete", true).put("models", models);
        }
        public ZCodeGift.Stream open(String jwt, String device, String site, String model, JSONObject body, boolean dressed) {
            openJwts.add(jwt);
            int code = openStatus.containsKey(jwt) ? openStatus.get(jwt) : 500;
            ZCodeGift.Stream out = new ZCodeGift.Stream();
            out.status = code;
            if (code == 200) {
                String text = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"GLM-5.3-Flash\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";
                out.is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            } else {
                out.source = "upstream";
                out.errorBody = "{\"error\":{\"message\":\"fixture upstream " + code + "\"}}";
            }
            return out;
        }
    }

    static final class Gateway extends NanoHTTPD {
        final GatewayServer gateway;
        Gateway(JSONObject cfg, GatewayServer.GiftTransport t) throws Exception {
            super("127.0.0.1", 0);
            gateway = RoutingSetupOfflineTest.server(cfg);
            gateway.setGiftTransportForTest(t);
        }
        @Override public Response serve(IHTTPSession s) { return gateway.serve(s); }
        @Override protected boolean useGzipWhenAccepted(Response r) { return gateway.useGzipWhenAccepted(r); }
    }

    static JSONObject config(String... jwts) {
        JSONArray accounts = new JSONArray();
        for (String jwt : jwts) accounts.put(new JSONObject().put("jwt", jwt).put("device", "device-" + jwt));
        return new JSONObject().put("server", new JSONObject().put("authKey", "fixture-gateway"))
                .put("providers", new JSONArray().put(new JSONObject().put("id", "zcode").put("protocol", "openai")
                        .put("baseUrl", "http://127.0.0.1:9/v1")
                        .put("keys", new JSONArray().put("fixture-key"))
                        .put("accounts", accounts)
                        .put("models", new JSONArray().put("glm-5.3-flash"))));
    }

    static String body() {
        return new JSONObject().put("model", MODEL).put("max_tokens", 64).put("stream", false)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"))).toString();
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    static final class Reply { final int code; final String text; Reply(int c, String t) { code = c; text = t; } }

    static Reply request(Gateway g) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + g.getListeningPort() + "/v1/chat/completions").openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer fixture-gateway");
        try (OutputStream out = c.getOutputStream()) { out.write(body().getBytes(StandardCharsets.UTF_8)); }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Reply(code, in == null ? "" : read(in));
        } finally { c.disconnect(); }
    }

    /** 起一个随机端口网关、发一次请求、关掉。 */
    static Reply execute(GatewayServer.GiftTransport t, String... jwts) throws Exception {
        Gateway g = new Gateway(config(jwts), t);
        try {
            g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            return request(g);
        } finally { g.stop(); }
    }

    interface Body { void run() throws Exception; }
    static List<String> failures = new ArrayList<String>();
    static void runCase(String name, Body body) {
        try { body.run(); System.out.println("  ok: " + name); }
        catch (Throwable t) { failures.add(name + " -> " + t); System.out.println("  FAIL: " + name + " -> " + t); }
    }

    public static void main(String[] args) throws Exception {
        List<UsageLedger.Row> rows = Collections.synchronizedList(new ArrayList<UsageLedger.Row>());
        UsageLedger.setHooks(r -> rows.add(r));
        try {
            // 1. 账号 A 额度花完 → 自动换 B 服务成功。
            runCase("quota-spent account falls through to next account", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 1, 500);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code == 200, "expected 200, got " + r.code + " " + r.text);
                check(r.text.contains("hello"), "served by B carries content: " + r.text);
                check(t.openJwts.equals(Arrays.asList("jwt-b")), "open only on B: " + t.openJwts);
                check(t.summaryJwts.equals(Arrays.asList("jwt-a", "jwt-b")), "summary order A then B: " + t.summaryJwts);
            });
            // 2. 账号 A 的额度查询失败 → 跳过换 B（查询失败不误导付费）。
            runCase("summary failure falls through to next account", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 2, 500);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code == 200, "expected 200, got " + r.code + " " + r.text);
                check(t.openJwts.equals(Arrays.asList("jwt-b")), "open only on B: " + t.openJwts);
            });
            // 3. 账号 A 不含该模型 → 换 B。
            runCase("model-not-covered account falls through to next", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 3, 500);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code == 200, "expected 200, got " + r.code + " " + r.text);
                check(t.openJwts.equals(Arrays.asList("jwt-b")), "open only on B: " + t.openJwts);
            });
            // 4. 账号 A 上游拒绝（429）→ 换 B 成功。
            runCase("upstream rejection falls through to next account", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 0, 429);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code == 200, "expected 200, got " + r.code + " " + r.text);
                check(t.openJwts.equals(Arrays.asList("jwt-a", "jwt-b")), "tried A then B: " + t.openJwts);
            });
            // 5. 两个账号都被上游拒绝 → 返回失败（不循环重放 A）。
            runCase("all accounts rejected returns failure", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 0, 429).mode("jwt-b", 0, 429);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code == 429, "expected 429, got " + r.code + " " + r.text);
                check(t.openJwts.equals(Arrays.asList("jwt-a", "jwt-b")), "each account once: " + t.openJwts);
                check(r.text.contains("upstream"), "failure source is upstream: " + r.text);
            });
            // 6. 两个账号都花完 → gift 让位付费路径（open 一次都不发）。
            runCase("all accounts spent hands off to paid plan", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 1, 500).mode("jwt-b", 1, 500);
                Reply r = execute(t, "jwt-a", "jwt-b");
                check(r.code >= 400, "paid path did not serve gift model: " + r.code + " " + r.text);
                check(t.openJwts.isEmpty(), "no gift send: " + t.openJwts);
                check(t.summaryJwts.equals(Arrays.asList("jwt-a", "jwt-b")), "both accounts checked: " + t.summaryJwts);
            });
            // 7. 单账号回归：原行为——花完直接让位付费路径。
            runCase("single spent account keeps original behavior", () -> {
                MultiTransport t = new MultiTransport("jwt-a").mode("jwt-a", 1, 500);
                Reply r = execute(t, "jwt-a");
                check(r.code >= 400, "paid path did not serve gift model: " + r.code + " " + r.text);
                check(t.openJwts.isEmpty(), "no gift send: " + t.openJwts);
            });
            // 8. 额度恢复后自动回 gift（无粘滞）：先两个都花完，A 恢复额度后 A 服务。
            runCase("recovered account is used again on next request", () -> {
                MultiTransport t = new MultiTransport("jwt-a", "jwt-b").mode("jwt-a", 1, 500).mode("jwt-b", 1, 500);
                Reply spent = execute(t, "jwt-a", "jwt-b");
                check(spent.code >= 400, "both spent hands off: " + spent.code);
                t.mode("jwt-a", 0, 200);
                Reply served = execute(t, "jwt-a", "jwt-b");
                check(served.code == 200, "recovered A serves: " + served.code + " " + served.text);
                check(t.openJwts.equals(Arrays.asList("jwt-a")), "gift reopened on A: " + t.openJwts);
            });
        } finally {
            UsageLedger.setHooks(null);
            ZCodeGift.resetBusyForTests();
            ZCodeGift.resetCooldownForTests();
        }
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("FAILED: " + f);
            throw new AssertionError(failures.size() + " case(s) failed");
        }
        System.out.println("ZCodeGiftFailoverHttpTest PASS");
    }
}
