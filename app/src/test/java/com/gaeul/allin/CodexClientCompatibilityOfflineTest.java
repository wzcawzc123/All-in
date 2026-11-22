package com.gaeul.allin;

import com.gaeul.allin.json.*;

/** Codex-only client metadata compatibility; business schemas and strings are not metadata. */
public final class CodexClientCompatibilityOfflineTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static JSONObject mark(String ttl) {
        JSONObject out = new JSONObject().put("type", "ephemeral");
        if (ttl != null) out.put("ttl", ttl);
        return out;
    }
    static final String TEXT = "one\n中文 and cache_control are real text";
    static final String ARGS = "{\"cache_control\":{\"type\":\"business-value\"},\"n\":1}";
    static final String OUTPUT = "tool output: " + ARGS;
    static JSONObject schema() {
        return new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("cache_control", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("type", new JSONObject().put("type", "string")))));
    }
    static JSONObject chat(boolean hints) {
        JSONObject part = new JSONObject().put("type", "text").put("text", TEXT);
        JSONObject image = new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                .put("url", "https://example.invalid/test.png").put("detail", "low"));
        JSONObject user = new JSONObject().put("role", "user").put("content", new JSONArray().put(part).put(image));
        JSONObject fn = new JSONObject().put("name", "lookup").put("parameters", schema()).put("strict", true);
        JSONObject tool = new JSONObject().put("type", "function").put("function", fn);
        JSONObject body = new JSONObject().put("model", "codex/m").put("prompt_cache_key", "session-original")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", "instructions"))
                        .put(user).put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                                .put("tool_calls", new JSONArray().put(new JSONObject().put("type", "function").put("id", "call-original")
                                        .put("function", new JSONObject().put("name", "lookup").put("arguments", ARGS)))))
                        .put(new JSONObject().put("role", "tool").put("tool_call_id", "call-original").put("content", OUTPUT)))
                .put("tools", new JSONArray().put(tool));
        if (hints) {
            body.put("cache_control", mark("1h")).put("prompt_cache_options", new JSONObject().put("mode", "explicit"));
            user.put("cache_control", mark(null)); part.put("cache_control", mark("5m")); image.put("cache_control", mark(null));
            tool.put("cache_control", mark(null)); fn.put("cache_control", mark("1h"));
        }
        return body;
    }
    static void chatContract() throws Exception {
        JSONObject in = chat(true); String before = in.toString();
        JSONObject got = CodexAuth.chatToResponses(in, "m");
        JSONObject expected = CodexAuth.chatToResponses(chat(false), "m");
        check(got.toString().equals(expected.toString()), "cached Chat has exactly the clean converted body");
        check(in.toString().equals(before), "Chat caller object unmodified");
        check(got.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").equals(TEXT), "text exact");
        check(got.toString().contains("input_image"), "image retained");
        check(got.getJSONArray("input").getJSONObject(1).getString("call_id").equals("call-original"), "call ID retained");
        check(got.getJSONArray("input").getJSONObject(1).getString("arguments").equals(ARGS), "arguments exact");
        check(got.getJSONArray("input").getJSONObject(2).getString("output").equals(OUTPUT), "tool output exact");
        check(got.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters").toString().equals(schema().toString()), "schema business property retained");
        check(got.getJSONArray("tools").getJSONObject(0).getBoolean("strict"), "explicit strict retained");
        check(got.getString("prompt_cache_key").equals("session-original"), "cache key unchanged");
        JSONObject wire = CodexRequestPolicy.prepareFinalOutbound(got, CodexRequestPolicy.OutputConstraints.none()).body;
        check(wire.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters").toString().equals(schema().toString()), "final policy schema retained");
        check(wire.getJSONArray("input").getJSONObject(1).getString("arguments").equals(ARGS), "final policy arguments retained");
    }
    static JSONObject nativeBody(boolean hints) {
        JSONObject content = new JSONObject().put("type", "input_text").put("text", TEXT);
        JSONObject message = new JSONObject().put("type", "message").put("role", "user").put("content", new JSONArray().put(content));
        JSONObject fn = new JSONObject().put("type", "function").put("name", "lookup").put("parameters", schema()).put("strict", true);
        JSONObject namespace = new JSONObject().put("type", "namespace").put("name", "space").put("tools", new JSONArray().put(fn));
        JSONObject inputTools = new JSONObject().put("type", "additional_tools").put("tools", new JSONArray().put(new JSONObject(fn.toString())));
        JSONObject body = new JSONObject().put("model", "m").put("prompt_cache_key", "session-original")
                .put("tools", new JSONArray().put(namespace)).put("input", new JSONArray()
                        .put(new JSONObject().put("type", "reasoning").put("encrypted_content", "cipher-original").put("summary", new JSONArray()))
                        .put(message).put(new JSONObject().put("type", "function_call").put("call_id", "call-original").put("name", "lookup").put("arguments", ARGS))
                        .put(new JSONObject().put("type", "function_call_output").put("call_id", "call-original").put("output", OUTPUT)).put(inputTools));
        if (hints) {
            body.put("cache_control", mark(null)); message.put("cache_control", mark(null)); content.put("cache_control", mark("1h"));
            fn.put("cache_control", mark(null)); namespace.put("cache_control", mark(null));
            inputTools.getJSONArray("tools").getJSONObject(0).put("cache_control", mark("5m"));
        }
        return body;
    }
    static void nativeContract() {
        JSONObject in = nativeBody(true); String before = in.toString();
        JSONObject got = CodexRequestPolicy.prepareFinalOutbound(in, CodexRequestPolicy.OutputConstraints.none()).body;
        JSONObject expected = CodexRequestPolicy.prepareFinalOutbound(nativeBody(false), CodexRequestPolicy.OutputConstraints.none()).body;
        check(got.toString().equals(expected.toString()), "cached native has exactly the clean final body");
        check(in.toString().equals(before), "native caller object unmodified");
        check(got.toString().contains("cipher-original"), "native sealed reasoning retained");
        JSONObject output = new JSONObject(ARGS);
        JSONObject structured = new JSONObject().put("model", "m").put("input", new JSONArray().put(new JSONObject()
                .put("type", "function_call_output").put("call_id", "id").put("output", output)));
        JSONObject result = CodexRequestPolicy.prepareFinalOutbound(structured, CodexRequestPolicy.OutputConstraints.none()).body;
        check(result.getJSONArray("input").getJSONObject(0).getJSONObject("output").toString().equals(ARGS), "native business output object not walked");
    }
    static void boundaries() throws Exception {
        for (Object v : new Object[]{JSONObject.NULL, mark(null), mark("5m"), mark("1h")}) {
            JSONObject body = chat(false).put("cache_control", v);
            check(CodexAuth.chatToResponses(body, "m").toString().equals(CodexAuth.chatToResponses(chat(false), "m").toString()), "known nullable cache hint compatible");
        }
        for (Object v : new Object[]{true, "ephemeral", 1, new JSONArray(), new JSONObject(), new JSONObject().put("type", "other"),
                mark("24h"), mark(null).put("unknown_semantic", true)}) {
            JSONObject body = chat(false).put("cache_control", v);
            try { CodexAuth.chatToResponses(body, "m"); throw new AssertionError("invalid hint accepted: " + v); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("cache_control"), "invalid hint explicit"); }
            body = nativeBody(false).put("cache_control", v);
            try { CodexRequestPolicy.prepareFinalOutbound(body, CodexRequestPolicy.OutputConstraints.none()); throw new AssertionError("invalid native hint accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("cache_control"), "invalid native hint explicit"); }
        }
        for (String mode : new String[]{"explicit", "future_semantic"}) {
            JSONObject body = chat(false).put("prompt_cache_options", new JSONObject().put("mode", mode));
            if ("explicit".equals(mode)) check(!CodexAuth.chatToResponses(body, "m").has("prompt_cache_options"), "pi explicit cache options removed before strict conversion");
            else try { CodexAuth.chatToResponses(body, "m"); throw new AssertionError("unknown options ignored"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("prompt_cache_options"), "unknown options explicit"); }
        }
        try { ResponsesBridge.chatToResponses(chat(true), "m"); throw new AssertionError("shared bridge was globally relaxed"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Unsupported field"), "non-Codex strict behavior retained"); }
        try { CodexAuth.chatToResponses(chat(false).put("previous_response_id", "r"), "m"); throw new AssertionError("stateful control ignored"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("previous_response_id"), "known stateful control explicitly rejected"); }
        check(!CodexAuth.chatToResponses(chat(false), "m").toString().isEmpty(), "clean Chat still supported");
    }
    static void instructionRoles() throws Exception {
        JSONObject first = new JSONObject().put("role", "system").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "first").put("cache_control", mark("1h")))
                .put(new JSONObject().put("type", "text").put("text", "second")));
        JSONObject late = new JSONObject().put("role", "system").put("content", "late instruction");
        JSONObject body = new JSONObject().put("messages", new JSONArray().put(first)
                .put(new JSONObject().put("role", "user").put("content", "user")).put(late));
        String before = body.toString();
        JSONArray input = CodexAuth.chatToResponses(body, "m").getJSONArray("input");
        check(input.length() == 3 && input.getJSONObject(0).getString("role").equals("developer")
                && input.getJSONObject(2).getString("role").equals("developer"), "both leading array and late system messages use supported instruction role");
        check(input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text").equals("first")
                && input.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("text").equals("second"), "instruction part order/boundaries exact");
        check(input.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text").equals("late instruction"), "late instruction remains in place");
        check(before.equals(body.toString()), "instruction-role adaptation detached");
        JSONObject nativeInput = new JSONObject().put("input", new JSONArray().put(new JSONObject().put("type", "message")
                .put("role", "system").put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", "x")))));
        JSONObject got = CodexRequestPolicy.prepareFinalOutbound(nativeInput, CodexRequestPolicy.OutputConstraints.none()).body;
        check(got.getJSONArray("input").getJSONObject(0).getString("role").equals("developer"), "native role adapted");
        JSONObject metadata = new JSONObject().put("type", "function_call_output").put("call_id", "x")
                .put("output", new JSONObject().put("role", "system").put("cache_control", "business"));
        got = CodexRequestPolicy.prepareFinalOutbound(new JSONObject().put("input", new JSONArray().put(metadata)), CodexRequestPolicy.OutputConstraints.none()).body;
        check(got.getJSONArray("input").getJSONObject(0).getJSONObject("output").getString("role").equals("system"), "business role not rewritten");
    }
    public static void main(String[] args) throws Exception {
        chatContract(); nativeContract(); boundaries(); instructionRoles();
        System.out.println("CodexClientCompatibilityOfflineTest passed: " + checks + " checks");
    }
}
