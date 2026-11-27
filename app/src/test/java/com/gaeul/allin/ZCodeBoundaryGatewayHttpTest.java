package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loopback Gateway regression: real gift transport and plan HTTP, synthetic credentials only. */
public final class ZCodeBoundaryGatewayHttpTest {
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static final class Reply { int status; String body; }
    static Reply post(int port, String path, boolean stream) throws Exception {
        JSONObject b = new JSONObject().put("model", ZCodeGiftUsageHttpTest.MODEL).put("stream", stream);
        if (path.endsWith("/responses")) b.put("input", "hello").put("max_output_tokens", 64);
        else b.put("max_tokens", 64).put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer fixture-gateway"); c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("anthropic-version", "2023-06-01"); c.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream out = c.getOutputStream()) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        try { Reply r = new Reply(); r.status = c.getResponseCode(); InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : ZCodeGiftUsageHttpTest.read(in); if (in != null) in.close(); return r;
        } finally { c.disconnect(); }
    }
    static void run(boolean gift, String path, boolean stream, boolean truncated) throws Exception {
        String payload = gift ? (stream ? ZCodeGiftUsageHttpTest.stream("full", !truncated) : ZCodeGiftUsageHttpTest.json("full"))
                : (stream ? VendorPolicyGatewayHttpTest.SSE : VendorPolicyGatewayHttpTest.CHAT);
        if (!gift && stream && truncated) payload = payload.substring(0, payload.indexOf("data: {", 6));
        if (!stream && truncated) payload = payload.substring(0, payload.length()-8);
        final String response = payload;
        try (VendorPolicyGatewayHttpTest.Upstream u = new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
            VendorPolicyGatewayHttpTest.Reply r = new VendorPolicyGatewayHttpTest.Reply(200, stream ? "text/event-stream" : "application/json", response);
            return truncated ? r.truncated(50) : r;
        })) {
            ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests();
            ZCodeGift.setFixtureBase("http://127.0.0.1:" + u.server.getLocalPort());
            JSONObject cfg = ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
            if (!gift) cfg.getJSONArray("providers").getJSONObject(0).remove("accounts");
            ZCodeGiftBusyGatewayHttpTest.Transport transport = new ZCodeGiftBusyGatewayHttpTest.Transport();
            ZCodeGiftUsageHttpTest.Gateway g = new ZCodeGiftUsageHttpTest.Gateway(cfg, transport);
            g.gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:" + u.server.getLocalPort() + new URL(url).getFile()));
            g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            try {
                Reply r = post(g.getListeningPort(), path, stream); ZCodeGiftUsageHttpTest.idle(g);
                String label = (gift ? "gift" : "plan") + " " + path + " stream=" + stream + " truncated=" + truncated;
                check(u.requests.size() == 1, label + " replay count=" + u.requests.size());
                if (!truncated) check(r.status == 200 && r.body.contains(gift || stream ? "hello" : "ok"), label + " status=" + r.status + " body=" + r.body);
                else if (!stream) check(r.status == 502, label + " expected 502 got " + r.status + " " + r.body);
                else {
                    check(r.status == 200, label + " expected committed SSE 200 got " + r.status);
                    check(r.body.contains("error"), label + " missing SSE error: " + r.body);
                    check(!r.body.contains("[DONE]") && !r.body.contains("event: message_stop") && !r.body.contains("response.completed"), label + " false completion: " + r.body);
                }
            } finally { g.stop(); ZCodeGift.setFixtureBase(null); ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); }
        }
    }
    static void checkSubscriptionGate() throws Exception {
        for (int scenario : new int[]{200, 503, 204}) {
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger paid = new java.util.concurrent.atomic.AtomicInteger();
            try (VendorPolicyGatewayHttpTest.Upstream u = new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
                if (request.path.endsWith("/api/biz/subscription/list")) {
                    reads.incrementAndGet();
                    int status = scenario == 503 ? 503 : 200;
                    String reply = scenario == 200 ? "{\"data\":[{\"status\":\"VALID\"}]}" : "{\"data\":[]}";
                    return new VendorPolicyGatewayHttpTest.Reply(status, "application/json", reply);
                }
                paid.incrementAndGet();
                return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", VendorPolicyGatewayHttpTest.CHAT);
            })) {
                JSONObject config = ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
                config.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                        .put("id", "zcode-test-session").put("token", "fixture-key");
                ZCodeGiftBusyGatewayHttpTest.Transport gift = new ZCodeGiftBusyGatewayHttpTest.Transport();
                gift.allowed = false;
                ZCodeGiftUsageHttpTest.Gateway gateway = new ZCodeGiftUsageHttpTest.Gateway(config, gift);
                gateway.gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:" + u.server.getLocalPort() + new URL(url).getFile()));
                gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                try {
                    for (int i = 0; i < 2; i++) {
                        Reply reply = post(gateway.getListeningPort(), "/v1/responses", false);
                        int expected = scenario == 204 ? 402 : 200;
                        check(reply.status == expected, "subscription state " + scenario + " actual status " + reply.status + " reads=" + reads + " paid=" + paid + " gift=" + gift.opens + " body=" + reply.body);
                    }
                    check(reads.get() == 1 && paid.get() == (scenario == 204 ? 0 : 2), "subscription state " + scenario + " exact plan reads and paid requests " + reads + "/" + paid);
                } finally { gateway.stop(); }
            }
        }
        System.out.println("ZCode plan gate PASS: confirmed, absent and unavailable subscriptions");
    }

    static JSONObject project(String id, String name, Object type) {
        return new JSONObject().put("projectId", id).put("projectName", name).put("projectType", type);
    }

    static JSONObject org(String id, String name, JSONObject... projects) {
        JSONArray list = new JSONArray();
        for (JSONObject project : projects) list.put(project);
        return new JSONObject().put("organizationId", id).put("organizationName", name).put("projects", list);
    }

    static void personalProjectAndList() throws Exception {
        JSONObject ordinary = org("ordinary", "other", project("first", "alternate", 1), project("second", "other", 1));
        JSONObject personal = org("personal", "默认机构", project("team", "默认项目", 2),
                project("alt", "alternate", 1), project("own", "默认项目一", 1), project("other-own", "默认项目二", 1));
        JSONObject teamOnly = org("team-only", "默认机构", project("seat", "默认项目", "2"));
        JSONObject input = new JSONObject().put("organizations", new JSONArray().put(ordinary).put(teamOnly).put(personal));
        check(Arrays.equals(ZCodeAuth.personalProject(input), new String[]{"personal", "own"}), "first personal default wins");
        input.put("organizations", new JSONArray().put(teamOnly).put(ordinary));
        check(Arrays.equals(ZCodeAuth.personalProject(input), new String[]{"ordinary", "first"}), "first eligible personal fallback");
        input.put("organizations", new JSONArray().put(teamOnly));
        check(ZCodeAuth.personalProject(input) == null, "team-only is not eligible for personal sign-in");
        check(ZCodeAuth.parsePersonalList("[]").length() == 0, "bare list");
        check(ZCodeAuth.parsePersonalList("{\"code\":0,\"data\":[]}").length() == 0, "success envelope");
        for (String bad : new String[]{"{\"code\":403,\"msg\":\"permission denied\",\"data\":[]}", "{}", "[] []"}) {
            try {
                ZCodeAuth.parsePersonalList(bad);
                throw new AssertionError("failed or incomplete key listing accepted");
            } catch (IOException expected) { }
        }
        System.out.println("ZCode personal project/list PASS (8 checks)");
    }

    static void checkNoPlanUntilConfirmedActive() throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
        String[] responses = {"{\"data\":[]}", "{\"code\":503,\"data\":[]}", "{\"data\":[{\"status\":\"VALID\"}]}", "{\"data\":null}"};
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, index) -> {
            check(request.path.equals("/api/biz/subscription/list"), "personal entitlement endpoint");
            check("fixture-plan-key".equals(request.headers.get("Authorization")), "personal entitlement uses selected key");
            return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", responses[index - 1]);
        })) {
            ZCodePlanGate gate = new ZCodePlanGate(now::get);
            GatewayServer.VendorConnectionFactory loopback = url -> NetworkPolicy.openHttp(
                    "http://127.0.0.1:" + upstream.server.getLocalPort() + new URL(url).getPath());
            ZCodePlanGate.State[] expected = {ZCodePlanGate.State.NO_PLAN, ZCodePlanGate.State.NO_PLAN,
                    ZCodePlanGate.State.ACTIVE, ZCodePlanGate.State.UNKNOWN};
            for (ZCodePlanGate.State state : expected) {
                check(gate.state("fixture-account", "bigmodel", "fixture-plan-key", loopback) == state,
                        "unexpected personal entitlement state, expected " + state);
                now.addAndGet(61_000_000_000L);
            }
            check(upstream.requests.size() == expected.length, "one real loopback metadata read per expired entry");
        }
        System.out.println("ZCode personal plan transitions PASS (4 reads)");
    }

    static void personalToolRoundTrip(boolean longCase) throws Exception {
        int toolCount = longCase ? 35 : 1;
        String argument = longCase ? String.join("", Collections.nCopies(256, "行|quote=\"|slash=\\\n")) : "short";
        String toolResult = "result|" + argument + "|end";
        JSONObject params = new JSONObject().put("value", argument);
        JSONObject toolCall = new JSONObject().put("index", 0).put("id", "fixture-personal-call").put("type", "function")
                .put("function", new JSONObject().put("name", "fixture_tool_0").put("arguments", params.toString()));
        JSONObject first = new JSONObject(VendorPolicyGatewayHttpTest.CHAT);
        JSONObject choice = first.getJSONArray("choices").getJSONObject(0);
        choice.put("finish_reason", "tool_calls");
        choice.getJSONObject("message").put("content", JSONObject.NULL).put("tool_calls", new JSONArray().put(toolCall));
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, index) -> {
            if (request.path.endsWith("/api/biz/subscription/list")) {
                return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", "{\"data\":[{\"status\":\"VALID\"}]}");
            }
            int round = sends.incrementAndGet();
            check(request.path.endsWith("/chat/completions"), "personal plan Chat endpoint");
            check("Bearer fixture-key".equals(request.headers.get("Authorization")), "personal plan uses selected key");
            check(request.body.getJSONArray("tools").length() == toolCount, "all tool definitions delivered");
            if (round == 2) {
                JSONArray history = request.body.getJSONArray("messages");
                JSONObject assistant = history.getJSONObject(history.length() - 2);
                JSONObject result = history.getJSONObject(history.length() - 1);
                JSONObject passedCall = assistant.getJSONArray("tool_calls").getJSONObject(0);
                check("fixture-personal-call".equals(passedCall.getString("id")), "tool call id round-trips");
                check("fixture_tool_0".equals(passedCall.getJSONObject("function").getString("name")), "tool name round-trips");
                check(argument.equals(new JSONObject(passedCall.getJSONObject("function").getString("arguments")).getString("value")), "tool argument round-trips");
                check("fixture-personal-call".equals(result.getString("tool_call_id")), "tool result id round-trips");
                check(toolResult.equals(result.getString("content")), "tool result round-trips");
            }
            return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", round == 1 ? first.toString() : VendorPolicyGatewayHttpTest.CHAT);
        })) {
            JSONObject cfg = ZCodeGiftUsageHttpTest.config(upstream.server.getLocalPort());
            cfg.getJSONObject("server").put("authKey", "fixture-auth");
            cfg.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                    .put("id", "fixture-personal").put("token", "fixture-key").put("site", "bigmodel");
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(cfg, upstream, false)) {
                ZCodeGiftBusyGatewayHttpTest.Transport gift = new ZCodeGiftBusyGatewayHttpTest.Transport();
                gift.allowed = false;
                gateway.gateway.setGiftTransportForTest(gift);
                JSONArray tools = new JSONArray();
                for (int i = 0; i < toolCount; i++) {
                    JSONObject schema = new JSONObject().put("type", "object").put("properties",
                            new JSONObject().put("value", new JSONObject().put("type", "string")));
                    tools.put(new JSONObject().put("type", "function").put("function",
                            new JSONObject().put("name", "fixture_tool_" + i).put("parameters", schema)));
                }
                JSONObject request = VendorPolicyGatewayHttpTest.body(ZCodeGiftUsageHttpTest.MODEL, false).put("tools", tools);
                String path = "/v1/chat/completions";
                VendorPolicyGatewayHttpTest.Result reply = VendorPolicyGatewayHttpTest.post(gateway, path, request);
                check(reply.status == 200, "first tool turn succeeds: " + reply.status);
                JSONObject responseChoice = new JSONObject(reply.body).getJSONArray("choices").getJSONObject(0);
                check("tool_calls".equals(responseChoice.getString("finish_reason")), "first turn terminal reason");
                JSONObject assistant = responseChoice.getJSONObject("message");
                JSONObject received = assistant.getJSONArray("tool_calls").getJSONObject(0);
                check(argument.equals(new JSONObject(received.getJSONObject("function").getString("arguments")).getString("value")), "client receives complete tool argument");
                request.getJSONArray("messages").put(assistant).put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", received.getString("id")).put("content", toolResult));
                VendorPolicyGatewayHttpTest.Result finalReply = VendorPolicyGatewayHttpTest.post(gateway, path, request);
                check(finalReply.status == 200, "second tool turn succeeds: " + finalReply.status);
                JSONObject finalChoice = new JSONObject(finalReply.body).getJSONArray("choices").getJSONObject(0);
                check("stop".equals(finalChoice.getString("finish_reason")) && "ok".equals(finalChoice.getJSONObject("message").getString("content")), "final answer and terminal reason");
                check(sends.get() == 2 && upstream.requests.size() == 3 && gift.opens.get() == 0,
                        "one plan read, two paid loopback requests and zero Gift sends");
            }
        }
        System.out.println("ZCode personal tool round trip PASS (" + toolCount + " tools, "
                + argument.getBytes(StandardCharsets.UTF_8).length + " argument bytes)");
    }

    static JSONObject responsesBody(boolean stream) {
        return new JSONObject().put("model", ZCodeGiftUsageHttpTest.MODEL).put("stream", stream)
                .put("input", "hello").put("max_output_tokens", 4096)
                .put("reasoning", new JSONObject().put("effort", "low"));
    }

    static JSONObject completed(String wire, boolean stream) {
        if (!stream) return new JSONObject(wire);
        for (String line : wire.split("\n")) {
            if (!line.startsWith("data: ")) continue;
            JSONObject event = new JSONObject(line.substring(6));
            if ("response.completed".equals(event.optString("type"))) return event.getJSONObject("response");
        }
        throw new AssertionError("missing response.completed");
    }

    static void giftResponsesToolRoundTrip(boolean stream, int count) throws Exception {
        String model = ZCodeGiftUsageHttpTest.MODEL;
        String arg = count == 1 ? "short" : String.join("", Collections.nCopies(256, "行|quote=\"|slash=\\n"));
        String result = "result|" + arg + "|end";
        JSONObject input = new JSONObject().put("value", arg);
        JSONObject block = new JSONObject().put("type", "tool_use").put("id", "gift-call")
                .put("name", "fixture_0").put("input", input);
        String toolJson = new JSONObject(ZCodeGiftUsageHttpTest.json("full"))
                .put("stop_reason", "tool_use").put("content", new JSONArray().put(block)).toString();
        String toolStream = ZCodeGiftUsageHttpTest.stream("full", false).split("event: content_block_start", 2)[0]
                + ZCodeGiftUsageHttpTest.event("content_block_start", ",\"index\":0,\"content_block\":" + new JSONObject(block.toString()).put("input", new JSONObject()))
                + ZCodeGiftUsageHttpTest.event("content_block_delta", ",\"index\":0,\"delta\":" + new JSONObject().put("type", "input_json_delta").put("partial_json", input.toString()))
                + ZCodeGiftUsageHttpTest.event("content_block_stop", ",\"index\":0")
                + ZCodeGiftUsageHttpTest.event("message_delta", ",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":7}")
                + ZCodeGiftUsageHttpTest.event("message_stop", "");
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream((request, round) -> {
            check(request.path.contains("/messages"), "Gift upstream uses its native Messages endpoint");
            check(request.body.getJSONArray("tools").length() == count, "declared tools reach Gift");
            if (round == 2) {
                JSONArray messages = request.body.getJSONArray("messages");
                JSONObject returned = messages.getJSONObject(messages.length()-1).getJSONArray("content").getJSONObject(0);
                check("tool_result".equals(returned.optString("type")) && "gift-call".equals(returned.optString("tool_use_id")), "result keeps call identity");
                check(result.equals(returned.optString("content")), "result arrives unchanged");
            }
            return new VendorPolicyGatewayHttpTest.Reply(200, stream ? "text/event-stream" : "application/json",
                    round == 1 ? (stream ? toolStream : toolJson) : (stream ? ZCodeGiftUsageHttpTest.stream("full", true) : ZCodeGiftUsageHttpTest.json("full")));
        })) {
            ZCodeGift.setFixtureBase("http://127.0.0.1:" + up.server.getLocalPort());
            ZCodeGiftBusyGatewayHttpTest.Transport gift = new ZCodeGiftBusyGatewayHttpTest.Transport();
            JSONObject cfg = ZCodeGiftUsageHttpTest.config(up.server.getLocalPort());
            cfg.getJSONObject("server").put("authKey", "fixture-auth");
            try (VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(cfg, up, false)) {
                g.gateway.setGiftTransportForTest(gift);
                g.gateway.setVendorConnectionsForTests(url -> { throw new AssertionError("Gift must not call paid transport"); });
                JSONArray tools = new JSONArray();
                for (int j = 0; j < count; j++) tools.put(new JSONObject().put("type", "function").put("name", "fixture_"+j)
                        .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject().put("value", new JSONObject().put("type", "string")))));
                JSONObject body = responsesBody(stream).put("tools", tools);
                VendorPolicyGatewayHttpTest.Result first = VendorPolicyGatewayHttpTest.post(g, "/v1/responses", body);
                check(first.status == 200, "first Gift tool request succeeds");
                JSONArray items = completed(first.body, stream).getJSONArray("output");
                JSONObject call = null;
                for (int j = 0; j < items.length(); j++) if ("function_call".equals(items.getJSONObject(j).optString("type"))) call = items.getJSONObject(j);
                check(call != null && "fixture_0".equals(call.optString("name")) && "gift-call".equals(call.optString("call_id")), "client receives named Gift call with ID");
                check(arg.equals(new JSONObject(call.getString("arguments")).getString("value")), "tool argument arrives unchanged");
                body.put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"))
                        .put(new JSONObject().put("type", "function_call").put("name", call.getString("name")).put("call_id", call.getString("call_id")).put("arguments", call.getString("arguments")))
                        .put(new JSONObject().put("type", "function_call_output").put("call_id", call.getString("call_id")).put("output", result)));
                VendorPolicyGatewayHttpTest.Result second = VendorPolicyGatewayHttpTest.post(g, "/v1/responses", body);
                check(second.status == 200 && "completed".equals(completed(second.body, stream).optString("status")) && second.body.contains("hello"), "second Gift turn completes");
                check(gift.opens.get() == 2 && up.requests.size() == 2, "exactly two Gift sends");
            } finally { ZCodeGift.setFixtureBase(null); ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); }
        }
        System.out.println("Responses Gift tool round trip PASS (" + count + " tools, stream=" + stream + ")");
    }

    static void giftResponsesAdmission() throws Exception {
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream((r, n) -> {
            throw new AssertionError("unexpected upstream request");
        })) {
            JSONObject config = ZCodeGiftUsageHttpTest.config(up.server.getLocalPort());
            config.getJSONObject("server").put("authKey", "fixture-auth");
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config, up, false)) {
                gateway.gateway.setVendorConnectionsForTests(url -> { throw new AssertionError("unexpected paid request"); });
                gateway.gateway.setGiftTransportForTest(new GatewayServer.GiftTransport() {
                    public JSONObject summary(String jwt, String device) {
                        reads.incrementAndGet();
                        return new JSONObject().put("ok", false).put("status", 429).put("retryAfterMs", 3000);
                    }
                    public ZCodeGift.Stream open(String jwt, String device, String site, String model, JSONObject body, boolean dressed) {
                        throw new AssertionError("unknown balance must block Gift inference");
                    }
                });
                for (JSONObject body : Arrays.asList(responsesBody(false).put("store", true),
                        responsesBody(true).put("max_tool_calls", 1))) {
                    VendorPolicyGatewayHttpTest.Result refused = VendorPolicyGatewayHttpTest.post(gateway, "/v1/responses", body);
                    check(refused.status == 400 && reads.get() == 0, "native-only fields rejected before balance lookup");
                }
                for (boolean stream : new boolean[]{false, true}) {
                    VendorPolicyGatewayHttpTest.Result refused = VendorPolicyGatewayHttpTest.post(gateway, "/v1/responses", responsesBody(stream));
                    check(refused.status == 429 && refused.body.contains("未改用付费 Plan"), "unknown Gift balance reported before HTTP stream commit");
                }
                check(reads.get() == 2 && up.requests.isEmpty(), "no inference when balance is uncertain");
            }
        }
        System.out.println("Responses Gift admission PASS");
    }

    static void giftResponsesStreamProjectionDiagnostics() throws Exception {
        java.nio.file.Path ledgerDir = java.nio.file.Files.createTempDirectory("zcode-gift-resp-proj-");
        UsageLedger.init(ledgerDir.toFile());
        final String[] upstreamMode = {"ok"};
        try {
            try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream((request, round) -> {
                String mode = upstreamMode[0];
                if ("ok".equals(mode)) {
                    return new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", ZCodeGiftUsageHttpTest.stream("full", true));
                }
                if ("dup_tool_id".equals(mode)) {
                    String b0 = new JSONObject().put("type", "tool_use").put("id", "dup-call-1").put("name", "fixture_0").put("input", new JSONObject()).toString();
                    String b1 = new JSONObject().put("type", "tool_use").put("id", "dup-call-1").put("name", "fixture_1").put("input", new JSONObject()).toString();
                    String sse = ZCodeGiftUsageHttpTest.event("message_start", ",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + ZCodeGiftUsageHttpTest.UPSTREAM + "\",\"content\":[],\"usage\":{\"input_tokens\":5}}")
                            + ZCodeGiftUsageHttpTest.event("content_block_start", ",\"index\":0,\"content_block\":" + b0)
                            + ZCodeGiftUsageHttpTest.event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}")
                            + ZCodeGiftUsageHttpTest.event("content_block_stop", ",\"index\":0")
                            + ZCodeGiftUsageHttpTest.event("content_block_start", ",\"index\":1,\"content_block\":" + b1)
                            + ZCodeGiftUsageHttpTest.event("content_block_delta", ",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"y\\\":2}\"}")
                            + ZCodeGiftUsageHttpTest.event("content_block_stop", ",\"index\":1")
                            + ZCodeGiftUsageHttpTest.event("message_delta", ",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}")
                            + ZCodeGiftUsageHttpTest.event("message_stop", "");
                    return new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", sse);
                }
                if ("upstream_error".equals(mode)) {
                    String sse = ZCodeGiftUsageHttpTest.event("message_start", ",\"message\":{\"id\":\"m2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + ZCodeGiftUsageHttpTest.UPSTREAM + "\",\"content\":[]}")
                            + "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"upstream gift busy\"}}\n\n";
                    return new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", sse);
                }
                if ("upstream_eof".equals(mode)) {
                    return new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", ZCodeGiftUsageHttpTest.stream("full", false));
                }
                throw new IllegalStateException("unknown mode " + mode);
            })) {
                ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests();
                ZCodeGift.setFixtureBase("http://127.0.0.1:" + up.server.getLocalPort());
                JSONObject cfg = ZCodeGiftUsageHttpTest.config(up.server.getLocalPort());
                cfg.getJSONObject("server").put("authKey", "fixture-auth-8ch");
                ZCodeGiftBusyGatewayHttpTest.Transport transport = new ZCodeGiftBusyGatewayHttpTest.Transport();
                ZCodeGiftUsageHttpTest.Gateway g = new ZCodeGiftUsageHttpTest.Gateway(cfg, transport);
                g.gateway.setVendorConnectionsForTests(url -> { throw new AssertionError("Gift must not call paid transport"); });
                g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                try {
                    int port = g.getListeningPort();
                    String reqJson = responsesBody(true).toString();
                    java.lang.reflect.Method sendM = NanoHTTPD.Response.class.getDeclaredMethod("send", OutputStream.class);
                    sendM.setAccessible(true);

                    // helper to fetch latest row by requestId
                    java.util.function.Function<String, JSONObject> rowOf = rid -> {
                        JSONArray arr = UsageLedger.recentRequests(30);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            if (rid.equals(o.optString("requestId"))) return o;
                        }
                        throw new AssertionError("missing ledger row for " + rid);
                    };

                    // 1) Normal success -> response.completed, message_stop, terminalSeen=true, conversionStatus=ok, deliveryStatus=delivered
                    upstreamMode[0] = "ok";
                    NanoHTTPD.Response rOk = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridOk = rOk.getHeader("X-AllIn-Request-Id");
                    ByteArrayOutputStream okOut = new ByteArrayOutputStream();
                    sendM.invoke(rOk, okOut); rOk.close();
                    String okBody = okOut.toString("UTF-8");
                    check(okBody.contains("response.completed"), "Gift Resp ok missing response.completed: " + okBody);
                    JSONObject rowOk = rowOf.apply(ridOk);
                    check(rowOk.optInt("statusCode") == 200 && rowOk.optInt("upstreamStatus") == 200
                            && "message_stop".equals(rowOk.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(rowOk.opt("terminalSeen"))
                            && "ok".equals(rowOk.optString("conversionStatus"))
                            && "delivered".equals(rowOk.optString("deliveryStatus")),
                            "Gift Resp ok ledger mismatch: " + rowOk);

                    // 2a) Native Messages ended normally (message_stop), final Responses projection failed, response.failed delivered
                    upstreamMode[0] = "dup_tool_id";
                    NanoHTTPD.Response rProj = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridProj = rProj.getHeader("X-AllIn-Request-Id");
                    ByteArrayOutputStream projOut = new ByteArrayOutputStream();
                    sendM.invoke(rProj, projOut); rProj.close();
                    String projBody = projOut.toString("UTF-8");
                    check(projBody.contains("response.failed") && !projBody.contains("response.completed"),
                            "Gift Resp proj fail must emit response.failed: " + projBody);
                    JSONObject rowProj = rowOf.apply(ridProj);
                    check(rowProj.optInt("statusCode") == 502 && rowProj.optInt("upstreamStatus") == 200
                            && "message_stop".equals(rowProj.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(rowProj.opt("terminalSeen"))
                            && "failed".equals(rowProj.optString("conversionStatus"))
                            && "conversion".equals(rowProj.optString("failurePhase"))
                            && "conversion_validation_failed".equals(rowProj.optString("failureReason"))
                            && Boolean.TRUE.equals(rowProj.optBoolean("conversionValidationFailed"))
                            && "delivered".equals(rowProj.optString("deliveryStatus")),
                            "Gift Resp proj fail (response.failed delivered) mismatch: " + rowProj);

                    // 2b) Projection failed AND downstream write of response.failed fails -> deliveryStatus=failed, conversion attribution retained
                    NanoHTTPD.Response rProjDrop = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridProjDrop = rProjDrop.getHeader("X-AllIn-Request-Id");
                    OutputStream failingProjOut = new OutputStream() {
                        final ByteArrayOutputStream seen = new ByteArrayOutputStream();
                        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                        @Override public void write(byte[] b, int off, int len) throws IOException {
                            seen.write(b, off, len);
                            if (seen.toString("UTF-8").contains("response.failed")) throw new IOException("client dropped response.failed");
                        }
                    };
                    try { sendM.invoke(rProjDrop, failingProjOut); } catch (Exception ignored) {}
                    rProjDrop.close();
                    JSONObject rowProjDrop = rowOf.apply(ridProjDrop);
                    check(rowProjDrop.optInt("statusCode") == 502
                            && "message_stop".equals(rowProjDrop.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(rowProjDrop.opt("terminalSeen"))
                            && "failed".equals(rowProjDrop.optString("conversionStatus"))
                            && "conversion".equals(rowProjDrop.optString("failurePhase"))
                            && "conversion_validation_failed".equals(rowProjDrop.optString("failureReason"))
                            && "failed".equals(rowProjDrop.optString("deliveryStatus")),
                            "Gift Resp proj fail + write drop mismatch: " + rowProjDrop);

                    // 3a) Upstream failed first (event: error before message_stop) -> preserves upstream attribution, NOT overwritten as conversion failure
                    upstreamMode[0] = "upstream_error";
                    NanoHTTPD.Response rUpErr = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridUpErr = rUpErr.getHeader("X-AllIn-Request-Id");
                    ByteArrayOutputStream upErrOut = new ByteArrayOutputStream();
                    sendM.invoke(rUpErr, upErrOut); rUpErr.close();
                    check(upErrOut.toString("UTF-8").contains("response.failed"), "Gift Resp upstream error must emit response.failed");
                    JSONObject rowUpErr = rowOf.apply(ridUpErr);
                    check(rowUpErr.optInt("statusCode") == 502
                            && !Boolean.TRUE.equals(rowUpErr.opt("terminalSeen"))
                            && "read_response_body".equals(rowUpErr.optString("failurePhase"))
                            && "upstream_explicit_error".equals(rowUpErr.optString("failureReason"))
                            && "upstream_aborted".equals(rowUpErr.optString("conversionStatus"))
                            && "delivered".equals(rowUpErr.optString("deliveryStatus")),
                            "Gift Resp upstream explicit error mismatch: " + rowUpErr);

                    // 3b) Upstream failed first (premature EOF before message_stop) -> preserves premature_eof, NOT conversion failure
                    upstreamMode[0] = "upstream_eof";
                    NanoHTTPD.Response rUpEof = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridUpEof = rUpEof.getHeader("X-AllIn-Request-Id");
                    ByteArrayOutputStream upEofOut = new ByteArrayOutputStream();
                    sendM.invoke(rUpEof, upEofOut); rUpEof.close();
                    check(upEofOut.toString("UTF-8").contains("response.failed"), "Gift Resp upstream EOF must emit response.failed");
                    JSONObject rowUpEof = rowOf.apply(ridUpEof);
                    check(rowUpEof.optInt("statusCode") == 502
                            && !Boolean.TRUE.equals(rowUpEof.opt("terminalSeen"))
                            && "read_response_body".equals(rowUpEof.optString("failurePhase"))
                            && "premature_eof".equals(rowUpEof.optString("failureReason"))
                            && "upstream_aborted".equals(rowUpEof.optString("conversionStatus"))
                            && "delivered".equals(rowUpEof.optString("deliveryStatus")),
                            "Gift Resp upstream premature EOF mismatch: " + rowUpEof);

                    // 4) Native Messages ok + projection ok, downstream final frame write fails -> downstream_delivery, NOT delivered
                    upstreamMode[0] = "ok";
                    NanoHTTPD.Response rDownFail = g.gateway.serve(ResponsesGatewayOfflineTest.session(reqJson, "fixture-auth-8ch", "/v1/responses"));
                    String ridDownFail = rDownFail.getHeader("X-AllIn-Request-Id");
                    OutputStream failingCompletedOut = new OutputStream() {
                        final ByteArrayOutputStream seen = new ByteArrayOutputStream();
                        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                        @Override public void write(byte[] b, int off, int len) throws IOException {
                            seen.write(b, off, len);
                            if (seen.toString("UTF-8").contains("response.completed")) throw new IOException("client dropped final completed frame");
                        }
                    };
                    try { sendM.invoke(rDownFail, failingCompletedOut); } catch (Exception ignored) {}
                    rDownFail.close();
                    JSONObject rowDownFail = rowOf.apply(ridDownFail);
                    check("message_stop".equals(rowDownFail.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(rowDownFail.opt("terminalSeen"))
                            && "ok".equals(rowDownFail.optString("conversionStatus"))
                            && "downstream_delivery".equals(rowDownFail.optString("failurePhase"))
                            && !"delivered".equals(rowDownFail.optString("deliveryStatus")),
                            "Gift Resp downstream write fail mismatch: " + rowDownFail);

                    // Verify persisted ledger + reload keeps exact values across all scenarios
                    String csv = UsageLedger.csv(1);
                    check(csv.contains(ridProj) && csv.contains("conversion_validation_failed"), "CSV missing Gift projection failure");
                    UsageLedger.init(ledgerDir.toFile());
                    JSONObject reloadedProj = rowOf.apply(ridProj);
                    check("failed".equals(reloadedProj.optString("conversionStatus"))
                            && "conversion_validation_failed".equals(reloadedProj.optString("failureReason"))
                            && "delivered".equals(reloadedProj.optString("deliveryStatus")),
                            "Reloaded Gift projection failure mismatch: " + reloadedProj);
                    JSONObject reloadedUpErr = rowOf.apply(ridUpErr);
                    check("upstream_explicit_error".equals(reloadedUpErr.optString("failureReason"))
                            && "upstream_aborted".equals(reloadedUpErr.optString("conversionStatus"))
                            && "delivered".equals(reloadedUpErr.optString("deliveryStatus")),
                            "Reloaded Gift upstream error mismatch: " + reloadedUpErr);
                    check(up.requests.size() == 6 && transport.opens.get() == 6,
                            "expected exact 6 single-send Gift attempts without replay: up=" + up.requests.size() + " opens=" + transport.opens.get());
                } finally { g.stop(); ZCodeGift.setFixtureBase(null); ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); }
            }
        } finally {
            GatewayStreamReadOfflineTest.closeLedger();
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(ledgerDir)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("ZCode Gift streaming Responses ledger projection diagnostics PASS (all 6 scenarios)");
    }

    public static void main(String[] args) throws Exception {
        personalProjectAndList();
        checkNoPlanUntilConfirmedActive();
        checkSubscriptionGate();
        personalToolRoundTrip(false);
        personalToolRoundTrip(true);
        giftResponsesAdmission();
        giftResponsesToolRoundTrip(false, 1);
        giftResponsesToolRoundTrip(true, 35);
        giftResponsesStreamProjectionDiagnostics();
        List<String> failures = new ArrayList<>();
        for (boolean gift : new boolean[]{true, false}) for (String path : new String[]{"/v1/chat/completions", "/v1/messages", "/v1/responses"})
            for (boolean stream : new boolean[]{false, true}) for (boolean truncated : new boolean[]{false, true}) {
                String label = (gift ? "gift" : "plan") + " " + path + " stream=" + stream + " truncated=" + truncated;
                try { run(gift, path, stream, truncated); System.out.println("PASS " + label); }
                catch (Throwable e) { failures.add(label + ": " + e); System.out.println("FAIL " + label + ": " + e); }
            }
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " failures: " + failures);
    }
}
