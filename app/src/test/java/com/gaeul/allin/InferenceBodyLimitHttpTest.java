package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Synthetic upstream replies through the real HTTP gateway and routing loop. */
public final class InferenceBodyLimitHttpTest {
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    private static final int ERROR_LIMIT = 64 * 1024;
    private static final String LIMIT_MESSAGE = "Upstream response body exceeds the gateway limit";
    private static final String RESPONSES = "{\"id\":\"r\",\"status\":\"completed\",\"output\":[]}";
    private static final String ERROR = "{\"error\":{\"message\":\"fixture upstream rejected request\",\"code\":\"fixture_error\"}}";

    public static void main(String[] args) throws Exception {
        run("openai", "/v1/chat/completions", "/first/chat/completions", VendorPolicyGatewayHttpTest.CHAT);
        run("anthropic", "/v1/messages", "/first/messages", VendorPolicyGatewayHttpTest.ANTH);
        run("responses", "/v1/responses", "/first/responses", RESPONSES);
        run("responses", "/v1/chat/completions", "/first/responses", RESPONSES);
        codexError();
        nativeErrors();
        System.out.println("InferenceBodyLimitHttpTest PASS");
    }

    private static void run(String protocol, String ingress, String upstreamPath, String json) throws Exception {
        String atLimit = pad(json, JSON_LIMIT);
        String overLimit = pad(json, JSON_LIMIT + 1);
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
            if (!upstreamPath.equals(request.path)) return reply(200, VendorPolicyGatewayHttpTest.CHAT);
            return reply(200, n == 1 ? atLimit : overLimit);
        }); VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                config("first", protocol, "http://127.0.0.1:" + upstream.server.getLocalPort() + "/first"), upstream, false)) {
            JSONObject body = ingress.endsWith("/responses")
                    ? new JSONObject().put("model", "group/limits").put("input", "hello").put("stream", false)
                    : VendorPolicyGatewayHttpTest.body("group/limits", false);
            VendorPolicyGatewayHttpTest.Result accepted = VendorPolicyGatewayHttpTest.post(gateway, ingress, body);
            check(accepted.status == 200, protocol + " valid JSON at limit: " + accepted.status);
            check(new JSONObject(accepted.body).optJSONObject("error") == null, "success remains valid JSON");
            check(upstream.requests.size() == 1 && upstreamPath.equals(upstream.requests.get(0).path), "expected upstream route");

            VendorPolicyGatewayHttpTest.Result rejected = VendorPolicyGatewayHttpTest.post(gateway, ingress, body);
            checkLimit(rejected);
            check(upstream.requests.size() == 2 && upstreamPath.equals(upstream.requests.get(1).path),
                    "one send for oversized response and no backup send: " + protocol + ": " + upstream.requests.size());
            System.out.println("PASS " + protocol + " " + ingress + " at-limit / over-limit and no replay");
        }
    }

    private static void codexError() throws Exception {
        for (boolean oversized : new boolean[]{true, false}) {
            String error = pad(ERROR, oversized ? ERROR_LIMIT + 1 : ERROR_LIMIT);
            try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, n) ->
                    request.path.equals("/backend-api/codex/responses") ? reply(422, error)
                        : reply(200, VendorPolicyGatewayHttpTest.CHAT));
                 VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                    config("codex", "codex", "http://127.0.0.1/first"), upstream, false)) {
                JSONObject body = new JSONObject().put("model", oversized ? "group/limits" : "codex/model")
                        .put("input", "hello").put("store", false);
                VendorPolicyGatewayHttpTest.Result response = VendorPolicyGatewayHttpTest.post(gateway, "/v1/responses", body);
                check(upstream.requests.size() == 1 && upstream.requests.get(0).path.equals("/backend-api/codex/responses"),
                        "one Codex send and no backup send; response=" + response.status);
                if (oversized) {
                    checkLimit(response);
                } else {
                    check(response.status == 422, "preserve Codex error status at limit: " + response.status);
                    check("fixture_error".equals(new JSONObject(response.body).getJSONObject("error").getString("code")),
                            "Codex error envelope preserved");
                }
            }
        }
        System.out.println("PASS Codex error envelope at limit / over limit and no replay");
    }

    private static void nativeErrors() throws Exception {
        JSONObject codex = config("codex", "codex", "http://127.0.0.1/first");
        JSONObject copilot = NativeResponsesAliasHttpTest.config();
        copilot.getJSONObject("server").put("authKey", "fixture-auth");
        nativeOverLimit(codex, "/backend-api/codex/responses", "model", 422, pad(ERROR, ERROR_LIMIT + 1));
        nativeOverLimit(copilot, "/responses", "github-copilot/m", 422, pad(ERROR, ERROR_LIMIT + 1));
        nativeOverLimit(copilot, "/responses", "github-copilot/m", 200, pad(RESPONSES, JSON_LIMIT + 1));
        System.out.println("PASS native Codex error and Copilot success/error body limits");
    }

    private static void nativeOverLimit(JSONObject config, String ingress, String model, int status, String response)
            throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, n) ->
                reply(status, response));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config, upstream, false)) {
            JSONObject body = new JSONObject().put("model", model).put("input", "hello").put("stream", false);
            VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway, ingress, body);
            check(result.status == 502, ingress + " " + model + " HTTP " + result.status + " body=" + result.body);
            checkLimit(result);
            check(upstream.requests.size() == 1, "native oversized response must have exactly one upstream send");
        }
    }

    private static JSONObject config(String id, String protocol, String base) {
        JSONObject primary = VendorPolicyGatewayHttpTest.provider(id, protocol, base, "model");
        if ("codex".equals(id)) {
            primary.remove("keys");
            JSONObject account = new JSONObject().put("id", "fixture-codex").put("enabled", true)
                    .put("access", "fixture-only").put("token", "fixture-only").put("expires", System.currentTimeMillis() + 3_600_000L);
            primary.put("accounts", new JSONArray().put(account));
        }
        JSONObject config = VendorPolicyGatewayHttpTest.config(primary);
        config.getJSONArray("providers").put(VendorPolicyGatewayHttpTest.provider(
                "backup", "openai", "http://127.0.0.1/backup", "model"));
        config.put("groups", new JSONArray().put(new JSONObject().put("id", "limits").put("strategy", "order")
                .put("targets", new JSONArray().put(id + "/model").put("backup/model"))));
        return config;
    }

    private static String pad(String json, int bytes) {
        return json + " ".repeat(bytes - json.getBytes(StandardCharsets.UTF_8).length);
    }

    private static VendorPolicyGatewayHttpTest.Reply reply(int status, String body) {
        return new VendorPolicyGatewayHttpTest.Reply(status, "application/json", body);
    }

    private static void checkLimit(VendorPolicyGatewayHttpTest.Result response) {
        check(response.status == 502, "oversized body must return 502: " + response.status);
        check(LIMIT_MESSAGE.equals(new JSONObject(response.body).getJSONObject("error").getString("message")),
                "oversized upstream content must be discarded");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
