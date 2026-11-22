package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real client HTTP -> gateway -> synthetic Codex HTTP, with final wire/body/terminal assertions. */
public final class CodexClientCompatibilityHttpTest {
    static int checks, requests;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static final String TEXT = "CODEX_COMPAT_OK";
    static final String ARGS = CodexClientCompatibilityOfflineTest.ARGS;
    static final String OUTPUT = CodexClientCompatibilityOfflineTest.OUTPUT;
    static JSONObject mark() { return CodexClientCompatibilityOfflineTest.mark("1h"); }
    static JSONObject part(String type, String text, boolean hints) {
        JSONObject p = new JSONObject().put("type", type).put("text", text);
        if (hints) p.put("cache_control", mark());
        return p;
    }
    static JSONObject body(String path, String kind, boolean stream, boolean hints) {
        JSONObject body = new JSONObject().put("model", "codex/m").put("stream", stream).put("prompt_cache_key", "original-session");
        boolean chat = path.endsWith("chat/completions"), messages = path.endsWith("messages");
        if (chat) {
            JSONArray turns = new JSONArray().put(new JSONObject().put("role", "system").put("content", new JSONArray()
                    .put(part("text", "first instruction", hints)).put(part("text", "second instruction", hints))))
                    .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(part("text", "hello", hints))));
            if (hints) turns.getJSONObject(1).put("cache_control", mark());
            if (kind.equals("result")) turns.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", new JSONArray()
                    .put(new JSONObject().put("id", "call-original").put("type", "function").put("function", new JSONObject().put("name", "lookup").put("arguments", ARGS)))))
                    .put(new JSONObject().put("role", "tool").put("tool_call_id", "call-original").put("content", OUTPUT));
            body.put("messages", turns);
            JSONObject fn = new JSONObject().put("name", "lookup").put("parameters", CodexClientCompatibilityOfflineTest.schema()).put("strict", true);
            JSONObject tool = new JSONObject().put("type", "function").put("function", fn);
            if (hints) { tool.put("cache_control", mark()); fn.put("cache_control", mark()); body.put("prompt_cache_options", new JSONObject().put("mode", "explicit")); }
            body.put("tools", new JSONArray().put(tool));
        } else if (messages) {
            body.put("max_tokens", 100).put("system", new JSONArray().put(part("text", "first instruction", hints)).put(part("text", "second instruction", hints)));
            JSONArray turns = new JSONArray().put(new JSONObject().put("role", "user").put("content", new JSONArray().put(part("text", "hello", hints))));
            if (kind.equals("result")) turns.put(new JSONObject().put("role", "assistant").put("content", new JSONArray().put(new JSONObject()
                    .put("type", "tool_use").put("id", "call-original").put("name", "lookup").put("input", new JSONObject(ARGS)))))
                    .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "tool_result")
                            .put("tool_use_id", "call-original").put("content", OUTPUT))));
            body.put("messages", turns);
            JSONObject tool = new JSONObject().put("name", "lookup").put("input_schema", CodexClientCompatibilityOfflineTest.schema());
            if (hints) tool.put("cache_control", mark());
            body.put("tools", new JSONArray().put(tool));
        } else {
            JSONArray input = new JSONArray().put(new JSONObject().put("type", "message").put("role", "system")
                    .put("content", new JSONArray().put(part("input_text", "first instruction", hints)).put(part("input_text", "second instruction", hints))))
                    .put(new JSONObject().put("type", "message").put("role", "user").put("content", new JSONArray().put(part("input_text", "hello", hints))));
            if (hints) input.getJSONObject(1).put("cache_control", mark());
            if (kind.equals("result")) input.put(new JSONObject().put("type", "function_call").put("call_id", "call-original").put("name", "lookup").put("arguments", ARGS))
                    .put(new JSONObject().put("type", "function_call_output").put("call_id", "call-original").put("output", OUTPUT));
            body.put("input", input);
            JSONObject tool = new JSONObject().put("type", "function").put("name", "lookup").put("parameters", CodexClientCompatibilityOfflineTest.schema()).put("strict", true);
            if (hints) tool.put("cache_control", mark());
            body.put("tools", new JSONArray().put(tool));
        }
        if (hints) body.put("cache_control", mark());
        return body;
    }
    static String event(String type, JSONObject values) { return "event: " + type + "\ndata: " + values.put("type", type) + "\n\n"; }
    static String events(boolean tool) {
        JSONObject item = tool ? new JSONObject().put("id", "fc1").put("type", "function_call").put("call_id", "call-returned")
                .put("name", "lookup").put("arguments", ARGS).put("status", "completed") : new JSONObject().put("id", "msg1").put("type", "message")
                .put("role", "assistant").put("status", "completed").put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", TEXT)));
        JSONObject initial = new JSONObject(item.toString());
        if (tool) initial.put("arguments", ""); else initial.put("content", new JSONArray());
        initial.put("status", "in_progress");
        String out = event("response.created", new JSONObject().put("response", new JSONObject().put("id", "r1").put("status", "in_progress").put("output", new JSONArray())))
                + event("response.output_item.added", new JSONObject().put("output_index", 0).put("item", initial));
        if (tool) out += event("response.function_call_arguments.delta", new JSONObject().put("item_id", "fc1").put("output_index", 0).put("delta", ARGS))
                + event("response.function_call_arguments.done", new JSONObject().put("item_id", "fc1").put("output_index", 0).put("arguments", ARGS));
        else out += event("response.output_text.delta", new JSONObject().put("item_id", "msg1").put("output_index", 0).put("content_index", 0).put("delta", TEXT))
                + event("response.output_text.done", new JSONObject().put("item_id", "msg1").put("output_index", 0).put("content_index", 0).put("text", TEXT));
        return out + event("response.output_item.done", new JSONObject().put("output_index", 0).put("item", item))
                + event("response.completed", new JSONObject().put("response", new JSONObject().put("id", "r1").put("model", "m").put("status", "completed")
                        .put("output", new JSONArray().put(item)).put("usage", new JSONObject().put("input_tokens", 2).put("output_tokens", 3))));
    }
    static final class Front extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway;
        Front(GatewayServer gateway) throws IOException { super("127.0.0.1", 0); this.gateway = gateway; start(SOCKET_READ_TIMEOUT, true); }
        public Response serve(IHTTPSession session) { return gateway.serve(session); }
        public void close() { stop(); }
    }
    static final class Reply { int status; String text; }
    static Reply post(Front front, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + front.getListeningPort() + path).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer secret"); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Connection", "close");
        c.setRequestProperty("session-id", "original-session"); c.setRequestProperty("thread-id", "original-session");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        Reply reply = new Reply(); reply.status = c.getResponseCode();
        try (InputStream in = reply.status < 400 ? c.getInputStream() : c.getErrorStream()) { reply.text = ResponsesGatewayOfflineTest.read(in); }
        finally { c.disconnect(); }
        return reply;
    }
    static void output(Reply reply, String path, String kind, boolean stream) {
        check(reply.status == 200, path + " " + kind + " status " + reply.status + " " + reply.text);
        boolean tool = kind.equals("call");
        if (!stream) {
            JSONObject result = new JSONObject(reply.text);
            if (path.endsWith("chat/completions")) {
                JSONObject choice = result.getJSONArray("choices").getJSONObject(0), message = choice.getJSONObject("message");
                check(choice.getString("finish_reason").equals(tool ? "tool_calls" : "stop"), "Chat finish");
                check(tool ? message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments").equals(ARGS)
                        : message.getString("content").equals(TEXT), "Chat complete output exact");
            } else if (path.endsWith("messages")) {
                check(result.getString("stop_reason").equals(tool ? "tool_use" : "end_turn"), "Messages finish");
                JSONObject part = result.getJSONArray("content").getJSONObject(0);
                check(tool ? part.getJSONObject("input").toString().equals(ARGS) : part.getString("text").equals(TEXT), "Messages complete output exact");
            } else {
                check(result.getString("status").equals("completed"), "Responses finish");
                JSONObject item = result.getJSONArray("output").getJSONObject(0);
                check(tool ? item.getString("arguments").equals(ARGS) : item.getJSONArray("content").getJSONObject(0).getString("text").equals(TEXT), "Responses complete output exact");
            }
        } else {
            int terminal = 0, done = 0; StringBuilder emitted = new StringBuilder();
            for (String line : reply.text.split("\n")) if (line.startsWith("data:")) {
                String raw = line.substring(5).trim(); if (raw.equals("[DONE]")) { done++; continue; }
                JSONObject e = new JSONObject(raw);
                check(!e.has("error") && !"error".equals(e.optString("type")) && !"response.failed".equals(e.optString("type")), "no success-stream error");
                if (path.endsWith("chat/completions")) {
                    JSONArray choices = e.optJSONArray("choices");
                    for (int i = 0; choices != null && i < choices.length(); i++) {
                        JSONObject choice = choices.getJSONObject(i), delta = choice.optJSONObject("delta");
                        if (choice.has("finish_reason") && !choice.isNull("finish_reason")) terminal++;
                        if (delta != null) {
                            if (!tool) emitted.append(delta.optString("content", ""));
                            JSONArray calls = delta.optJSONArray("tool_calls");
                            for (int j = 0; tool && calls != null && j < calls.length(); j++) emitted.append(calls.getJSONObject(j).getJSONObject("function").optString("arguments", ""));
                        }
                    }
                } else {
                    String type = e.optString("type");
                    if (type.equals(path.endsWith("messages") ? "message_stop" : "response.completed")) terminal++;
                    if (path.endsWith("messages") && type.equals("content_block_delta")) {
                        JSONObject d = e.getJSONObject("delta"); emitted.append(d.optString(tool ? "partial_json" : "text", ""));
                    } else if (type.equals(tool ? "response.function_call_arguments.delta" : "response.output_text.delta")) emitted.append(e.optString("delta"));
                }
            }
            check(terminal == 1, "exactly one successful terminal: " + terminal);
            check(done == (path.endsWith("chat/completions") ? 1 : 0), "correct DONE count");
            check(emitted.toString().equals(tool ? ARGS : TEXT), "streamed output exact: " + emitted);
        }
    }
    static void finalWire(JSONObject wire, String path, String kind) {
        check(!wire.has("cache_control") && !wire.has("prompt_cache_options"), "no unsupported top-level hints on wire");
        check(path.endsWith("messages") ? !wire.has("prompt_cache_key") : wire.getString("prompt_cache_key").equals("original-session"), "existing protocol-specific cache key behavior unchanged");
        check(wire.getBoolean("stream") && !wire.getBoolean("store"), "Codex wire defaults");
        JSONObject parameters = wire.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters");
        check(parameters.toString().equals(CodexClientCompatibilityOfflineTest.schema().toString()), "wire business schema untouched");
        for (int i = 0; i < wire.getJSONArray("input").length(); i++) {
            JSONObject item = wire.getJSONArray("input").getJSONObject(i);
            check(!item.has("cache_control"), "no input hint on wire");
            check(!"system".equals(item.optString("role")), "no Codex-forbidden system role on wire");
            JSONArray content = item.optJSONArray("content");
            for (int j = 0; content != null && j < content.length(); j++) check(!content.getJSONObject(j).has("cache_control"), "no content hint on wire");
        }
        check(!wire.getJSONArray("tools").getJSONObject(0).has("cache_control"), "no tool hint on wire");
        if (kind.equals("result")) {
            JSONObject call = null, result = null;
            for (int i = 0; i < wire.getJSONArray("input").length(); i++) {
                JSONObject item = wire.getJSONArray("input").getJSONObject(i);
                if (item.optString("type").equals("function_call")) call = item;
                if (item.optString("type").equals("function_call_output")) result = item;
            }
            check(call != null && call.getString("call_id").equals("call-original") && call.getString("arguments").equals(ARGS), "wire original call/args exact");
            check(result != null && result.getString("call_id").equals("call-original") && result.getString("output").equals(OUTPUT), "wire tool result exact");
        }
        String instructions = wire.optString("instructions");
        if (path.endsWith("messages")) check(instructions.contains("first instruction") && instructions.indexOf("first instruction") < instructions.indexOf("second instruction"), "Messages instruction order");
        else {
            JSONObject first = wire.getJSONArray("input").getJSONObject(0);
            check(first.getString("role").equals("developer"), "system array mapped to developer");
            JSONArray content = first.getJSONArray("content");
            check(content.getJSONObject(0).getString("text").equals("first instruction") && content.getJSONObject(1).getString("text").equals("second instruction"), "instruction part boundaries/order exact");
        }
    }
    static void matrix() throws Exception {
        for (String path : new String[]{"/v1/chat/completions", "/v1/responses", "/v1/messages"}) for (String kind : new String[]{"text", "call", "result"}) for (boolean stream : new boolean[]{false, true}) {
            JSONObject clean = null;
            for (boolean hints : new boolean[]{false, true}) try (CodexRequestPolicyOfflineTest.Upstream u = new CodexRequestPolicyOfflineTest.Upstream(false, 200, events(kind.equals("call")))) {
                GatewayServer g = CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                JSONObject body = body(path, kind, stream, hints); String original = body.toString();
                try (Front front = new Front(g)) { output(post(front, path, body), path, kind, stream); }
                requests++;
                check(original.equals(body.toString()), "client payload unmodified");
                check(u.count.get() == 1, "one upstream dispatch, no resend"); finalWire(u.body, path, kind);
                check(u.headers.get("session-id").equals("original-session") && u.headers.get("thread-id").equals("original-session"), "wire session headers unchanged");
                if (!hints) clean = u.body;
                else check(clean.toString().equals(u.body.toString()), "clean/cached final wire bodies exactly equal for " + path + " " + kind);
            }
        }
        check(requests == 36, "full protocol/stream/tool/cache matrix executed");
    }
    static void invalidBeforeDispatch() throws Exception {
        for (int where = 0; where < 4; where++) {
            GatewayServer g = CodexRequestPolicyOfflineTest.gateway(1); AtomicInteger opens = new AtomicInteger();
            g.setVendorConnectionsForTests(url -> { opens.incrementAndGet(); throw new IOException("must not dispatch"); });
            JSONObject body = body("/v1/chat/completions", "text", false, false);
            JSONObject owner = where == 0 ? body : where == 1 ? body.getJSONArray("messages").getJSONObject(0) : where == 2 ? body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0) : body.getJSONArray("tools").getJSONObject(0);
            owner.put("cache_control", new JSONObject().put("type", "future_semantic"));
            try (Front front = new Front(g)) { Reply r = post(front, "/v1/chat/completions", body); check(r.status == 400 && r.text.contains("cache_control"), "invalid hint explicit error"); }
            check(opens.get() == 0, "invalid hint zero upstream opens");
        }
    }
    static void routingAndIsolation() throws Exception {
        for (String reference : new String[]{"m", "alias", "group/only", "group/mixed"})
            try (CodexRequestPolicyOfflineTest.Upstream u = new CodexRequestPolicyOfflineTest.Upstream(false, 200, events(false))) {
                GatewayServer g = CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                JSONObject config = ResponsesGatewayOfflineTest.config(g);
                config.put("routing", new JSONObject().put("groupsEnabled", true));
                if (reference.equals("alias")) config.put("middleware", new JSONObject().put("modelMap", new JSONObject().put("alias", "codex/m")));
                if (reference.startsWith("group/")) {
                    JSONArray targets = new JSONArray().put("codex/m");
                    if (reference.equals("group/mixed")) {
                        JSONObject other = new JSONObject().put("id", "other").put("enabled", true).put("protocol", "openai")
                                .put("baseUrl", "http://127.0.0.1:1/v1").put("models", new JSONArray().put("m")).put("keys", new JSONArray().put("fixture-only"));
                        config.getJSONArray("providers").put(other); targets.put("other/m");
                    }
                    config.put("groups", new JSONArray().put(new JSONObject().put("id", reference.substring(6)).put("targets", targets).put("strategy", "ordered")));
                }
                AtomicInteger otherOpens = new AtomicInteger();
                g.setVendorConnectionsForTests(url -> {
                    if (!url.startsWith(CodexAuth.CODEX_BASE)) { otherOpens.incrementAndGet(); throw new IOException("other provider must not get adapted metadata"); }
                    return NetworkPolicy.openHttp("http://127.0.0.1:" + u.server.getLocalPort() + new URL(url).getFile());
                });
                JSONObject body = body("/v1/responses", "text", false, true).put("model", reference);
                try (Front front = new Front(g)) { output(post(front, "/v1/responses", body), "/v1/responses", "text", false); }
                check(u.count.get() == 1 && otherOpens.get() == 0, "Codex aliases/group metadata adaptation has no other dispatch: " + reference);
                finalWire(u.body, "/v1/responses", "text");
                requests++;
            }
        // An unrelated Chat route keeps the original metadata and role; it never enters Codex's adapter.
        String reply = new JSONObject().put("id", "other").put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("message", new JSONObject().put("role", "assistant").put("content", TEXT)).put("finish_reason", "stop"))).toString();
        try (CodexRequestPolicyOfflineTest.Upstream u = new CodexRequestPolicyOfflineTest.Upstream(false, 200, reply)) {
            GatewayServer g = ResponsesGatewayOfflineTest.gateway(u.server.getLocalPort());
            ResponsesGatewayOfflineTest.config(g).getJSONObject("guard").put("maxOutputTokens", 0);
            JSONObject body = body("/v1/chat/completions", "text", false, true).put("model", "mock/m");
            try (Front front = new Front(g)) { output(post(front, "/v1/chat/completions", body), "/v1/chat/completions", "text", false); }
            check(u.count.get() == 1 && u.body.getJSONObject("cache_control").toString().equals(mark().toString()), "non-Codex top metadata not stripped");
            JSONObject system = u.body.getJSONArray("messages").getJSONObject(0);
            check(system.getString("role").equals("system") && system.getJSONArray("content").getJSONObject(0).has("cache_control"), "non-Codex role/content metadata preserved");
            check(u.body.getJSONArray("tools").getJSONObject(0).has("cache_control"), "non-Codex tools metadata preserved");
            requests++;
        }
        GatewayServer unrelated = ResponsesGatewayOfflineTest.gateway(1);
        ResponsesGatewayOfflineTest.config(unrelated).getJSONObject("guard").put("maxOutputTokens", 0);
        AtomicInteger opens = new AtomicInteger();
        unrelated.setVendorConnectionsForTests(url -> { opens.incrementAndGet(); throw new IOException("strict non-Codex projection must not dispatch"); });
        JSONObject body = body("/v1/responses", "text", false, true).put("model", "mock/m");
        try (Front front = new Front(unrelated)) { Reply r = post(front, "/v1/responses", body); check(r.status == 400 && r.text.contains("Unsupported field: cache_control"), "non-Codex Responses strict behavior unchanged"); }
        check(opens.get() == 0, "non-Codex malformed projection no dispatch");
        GatewayServer mixed = CodexRequestPolicyOfflineTest.gateway(1);
        JSONObject config = ResponsesGatewayOfflineTest.config(mixed);
        config.getJSONArray("providers").put(new JSONObject().put("id", "other").put("enabled", true).put("protocol", "openai")
                .put("baseUrl", "http://127.0.0.1:1/v1").put("models", new JSONArray().put("m")).put("keys", new JSONArray().put("fixture-only")));
        config.put("routing", new JSONObject().put("groupsEnabled", true)).put("groups", new JSONArray().put(new JSONObject()
                .put("id", "other-first").put("strategy", "ordered").put("targets", new JSONArray().put("other/m").put("codex/m"))));
        AtomicInteger mixedOpens = new AtomicInteger();
        mixed.setVendorConnectionsForTests(url -> { mixedOpens.incrementAndGet(); throw new IOException("no new fallback/reorder"); });
        try (Front front = new Front(mixed)) { Reply r = post(front, "/v1/responses", body("/v1/responses", "text", false, true).put("model", "group/other-first"));
            check(r.status == 400 && r.text.contains("Unsupported field: cache_control"), "mixed pool retains original local-rejection/fallback policy"); }
        check(mixedOpens.get() == 0, "compatibility adds no fallback/reorder dispatch");
    }
    public static void main(String[] args) throws Exception {
        matrix(); invalidBeforeDispatch(); routingAndIsolation();
        System.out.println("CodexClientCompatibilityHttpTest passed: " + requests + " successful real-HTTP requests, " + checks + " checks");
    }
}
