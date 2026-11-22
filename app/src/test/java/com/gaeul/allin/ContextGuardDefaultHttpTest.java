package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Real loopback HTTP: no vendor credentials; verifies bytes actually reach the upstream. */
public final class ContextGuardDefaultHttpTest {
    private static int checks, requests;
    private static final String SYSTEM = repeat("x", 2100000);
    private static final String TOOL = repeat("tool-data-", 21000) + "TAIL_DEFAULT_123";
    private static final String CHAT_SSE = "data: {\"id\":\"r\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"r\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";

    public static void main(String[] args) throws Exception {
        JSONObject defaults = new JSONObject(new String(Files.readAllBytes(Paths.get(
                Files.exists(Paths.get("app/src/main/assets/default_config.json"))
                        ? "app/src/main/assets/default_config.json" : "src/main/assets/default_config.json")), StandardCharsets.UTF_8));
        check(defaults.getJSONObject("guard").getInt("maxBodyBytes") == 0, "fresh asset unlimited");
        for (int mode = 0; mode < 3; mode++) {
            for (String path : new String[]{"/v1/chat/completions", "/v1/responses", "/v1/messages"}) {
                for (boolean stream : new boolean[]{false, true}) run(path, stream, mode, defaults, false);
            }
        }
        for (String path : new String[]{"/v1/chat/completions", "/v1/responses", "/v1/messages"})
            run(path, false, 0, defaults, true);
        System.out.println("ContextGuardDefaultHttpTest PASS checks=" + checks + " actualHTTP=" + requests);
    }

    private static void run(String path, boolean stream, int mode, JSONObject defaults, boolean limited) throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((r, n) ->
                new VendorPolicyGatewayHttpTest.Reply(200, stream ? "text/event-stream" : "application/json",
                        stream ? CHAT_SSE : VendorPolicyGatewayHttpTest.CHAT))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider(
                    "mock", "openai", "http://127.0.0.1:" + upstream.server.getLocalPort() + "/v1", "m"));
            if (mode == 1) cfg.put("guard", new JSONObject().put("enabled", true));
            if (mode == 2) cfg.put("guard", defaults.getJSONObject("guard"));
            if (limited) cfg.put("guard", new JSONObject().put("enabled", true).put("maxBodyBytes", 1945600));
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(cfg, upstream, false)) {
                JSONObject body = request(path, stream);
                String before = body.toString();
                check(before.getBytes(StandardCharsets.UTF_8).length > 1945600, "fixture beyond old limit");
                VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway, path, body);
                requests++;
                if (limited) {
                    check(result.status == 413, "explicit cap rejects: " + path + " status=" + result.status);
                    check(result.body.contains("budget_exceeded"), "budget rejection reported");
                    check(upstream.requests.isEmpty(), "rejected cap has zero upstream sends");
                } else {
                    check(result.status == 200, "unlimited: " + path + " stream=" + stream + " mode=" + mode + " status=" + result.status + " " + result.body);
                    check(result.body.contains("ok") && !result.body.contains("budget_exceeded"), "successful reply, no budget error");
                    check(upstream.requests.size() == 1, "one upstream send");
                    JSONArray messages = upstream.requests.get(0).body.getJSONArray("messages");
                    check(messages.length() == 5, "complete system and tool history received");
                    check(SYSTEM.equals(contentText(messages.getJSONObject(0).get("content"))), "full oversized system received");
                    check(TOOL.equals(contentText(messages.getJSONObject(3).get("content"))), "full tool result including tail received");
                    check("finish".equals(contentText(messages.getJSONObject(4).get("content"))), "latest user received");
                    JSONObject stats = gateway.gateway.lastGuardStats;
                    check(stats == null || (!stats.optBoolean("budgetExceeded") && stats.optString("error", "").isEmpty()
                            && stats.optInt("dropped") == 0 && stats.optInt("truncated") == 0), "no budget removal in actual gateway");
                    if (stream) check(result.body.contains(path.endsWith("/responses") ? "response.completed"
                            : path.endsWith("/messages") ? "message_stop" : "[DONE]"), "SSE terminal received");
                }
                check(before.equals(body.toString()), "client input unchanged");
            }
        }
    }

    private static JSONObject request(String path, boolean stream) throws Exception {
        JSONObject out = new JSONObject().put("model", "mock/m").put("stream", stream).put("max_tokens", 64);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM))
                .put(new JSONObject().put("role", "user").put("content", "start"))
                .put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", new JSONArray()
                        .put(new JSONObject().put("id", "call123").put("type", "function").put("function", new JSONObject().put("name", "test").put("arguments", "{}")))))
                .put(new JSONObject().put("role", "tool").put("tool_call_id", "call123").put("content", TOOL))
                .put(new JSONObject().put("role", "user").put("content", "finish"));
        out.put("messages", messages);
        if (path.endsWith("/responses")) return ResponsesBridge.chatToResponses(out, "mock/m");
        if (path.endsWith("/messages")) return ZCodeAnthropic.toAnthropicStructural(out);
        return out;
    }

    private static String contentText(Object content) {
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) throw new AssertionError("unexpected text content representation");
        StringBuilder out = new StringBuilder();
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            check("text".equals(part.optString("type")), "only text blocks expected");
            out.append(part.getString("text"));
        }
        return out.toString();
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
