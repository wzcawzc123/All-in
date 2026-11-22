package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.gaeul.allin.GatewayStreamingHttpTest.responses;
import static com.gaeul.allin.VendorPolicyGatewayHttpTest.check;

public final class ResponsesStreamAggregationHttpTest {
    private static final String LIMIT = "Responses stream exceeds the gateway aggregation limit";

    public static void main(String[] args) throws Exception {
        longTextAndTool();
        boundedEntries(false);
        boundedEntries(true);
        boundedHttp();
        System.out.println("ResponsesStreamAggregationHttpTest PASS");
    }

    private static String item(String event, int output, JSONObject value) {
        return responses(event, new JSONObject().put("output_index", output).put("item", value));
    }

    private static String delta(String event, String text, int output) {
        return responses(event, new JSONObject().put("output_index", output).put("delta", text));
    }

    private static JSONObject message(String text) {
        return new JSONObject().put("type", "message").put("content", new JSONArray()
                .put(new JSONObject().put("type", "output_text").put("text", text)));
    }

    private static String complete(JSONArray output) {
        return responses("response.completed", new JSONObject().put("response",
                new JSONObject().put("status", "completed").put("output", output)));
    }

    private static void longTextAndTool() throws Exception {
        String text = "回复-🌙-".repeat(12_000);
        String arguments = "{\"payload\":\"" + "bulk_".repeat(15_000) + "\"}";
        JSONObject early = new JSONObject().put("type", "function_call").put("arguments", "");
        JSONObject finalCall = new JSONObject(early.toString()).put("call_id", "call_late")
                .put("name", "lookup").put("arguments", arguments);
        StringBuilder wire = new StringBuilder(item("response.output_item.added", 1, early));
        // Use boundaries that do not split the surrogate pair inside each repeated unit.
        int textStep = "回复-🌙-".length() * 8;
        for (int i = 0; i < text.length(); i += textStep)
            wire.append(delta("response.output_text.delta", text.substring(i, Math.min(i + textStep, text.length())), 0));
        for (int i = 0; i < arguments.length() - 2; i += 23)
            wire.append(delta("response.function_call_arguments.delta", arguments.substring(i, Math.min(i + 23, arguments.length() - 2)), 1));
        wire.append(item("response.output_item.done", 1, finalCall));
        wire.append(complete(new JSONArray().put(message(text + "TAIL")).put(finalCall)));
        String converted = convert(wire.toString());
        StringBuilder actualText = new StringBuilder(), actualArgs = new StringBuilder();
        StringBuilder ids = new StringBuilder(), names = new StringBuilder();
        for (String line : converted.split("\n")) {
            if (!line.startsWith("data: {")) continue;
            JSONObject chunk = new JSONObject(line.substring(6));
            JSONObject data = chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
            actualText.append(data.optString("content", ""));
            JSONArray calls = data.optJSONArray("tool_calls");
            if (calls != null) {
                JSONObject call = calls.getJSONObject(0), fn = call.getJSONObject("function");
                ids.append(call.optString("id", ""));
                names.append(fn.optString("name", ""));
                actualArgs.append(fn.optString("arguments", ""));
            }
        }
        check(actualText.toString().equals(text + "TAIL"), "final snapshot adds only missing text suffix");
        check(actualArgs.toString().equals(arguments) && ids.toString().equals("call_late") && names.toString().equals("lookup"),
                "argument deltas match exactly and late tool identity appears once");
        check(converted.contains("\"finish_reason\":\"tool_calls\"") && converted.contains("[DONE]"), "tool finish preserved");
    }

    private static void boundedEntries(boolean tools) throws Exception {
        StringBuilder wire = new StringBuilder();
        JSONObject call = new JSONObject().put("type", "function_call").put("arguments", "");
        for (int i = 0; i <= ResponsesBridge.CHAT_STREAM_MAX_ENTRIES; i++) {
            wire.append(tools ? item("response.output_item.added", i, call)
                    : delta("response.output_text.delta", "x", i));
        }
        String actual = convert(wire.toString());
        check(actual.contains(LIMIT) && !actual.contains("[DONE]"), "entry overflow must terminate with failure");
    }

    private static void boundedHttp() throws Exception {
        List<UsageLedger.Row> rows = Collections.synchronizedList(new ArrayList<>());
        UsageLedger.init(Files.createTempDirectory("responses-aggregation-").toFile());
        UsageLedger.setHooks(rows::add);
        String frame = delta("response.output_text.delta", "x".repeat(64 * 1024), 0);
        String within = frame.repeat(ResponsesBridge.CHAT_STREAM_MAX_CHARS / (64 * 1024));
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, n) ->
                new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", within + (n == 1
                        ? complete(new JSONArray()) : delta("response.output_text.delta", "y", 0))))) {
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(VendorPolicyGatewayHttpTest.provider("first", "responses",
                    "http://127.0.0.1:" + upstream.server.getLocalPort() + "/first", "model"));
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(cfg, upstream, false)) {
                JSONObject body = VendorPolicyGatewayHttpTest.body("first/model", true);
                VendorPolicyGatewayHttpTest.Result accepted = VendorPolicyGatewayHttpTest.post(gateway, "/v1/chat/completions", body);
                check(accepted.status == 200 && accepted.body.contains("[DONE]") && !accepted.body.contains(LIMIT), "exact limit stays usable");
                VendorPolicyGatewayHttpTest.Result rejected = VendorPolicyGatewayHttpTest.post(gateway, "/v1/chat/completions", body);
                check(rejected.status == 200 && rejected.body.contains(LIMIT) && !rejected.body.contains("[DONE]"), "one extra character fails the committed stream");
                check(upstream.requests.size() == 2, "one send per request");
                for (int i = 0; i < 40 && !hasFailure(rows); i++) Thread.sleep(25);
                check(hasFailure(rows), "overflow recorded as failure");
            }
        } finally {
            UsageLedger.setHooks(null);
        }
    }

    private static boolean hasFailure(List<UsageLedger.Row> rows) {
        synchronized (rows) { return rows.stream().anyMatch(row -> row.status == 502); }
    }

    private static String convert(String wire) throws Exception {
        try (java.io.InputStream source = ResponsesBridge.responsesStreamToChat(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), "m")) {
            return ResponsesGatewayOfflineTest.read(source);
        }
    }
}
