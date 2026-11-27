package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把豆包工作模式的 SSE 适配成 OpenAI chat.completion（流式与非流式）。
 * 工具调用借用 {@link DeepSeekToolBridge} 的提示词桥；豆包并不知道 tools 字段，
 * 它只看到一段说明和一个 JSON 标记约定，真正执行仍在客户端。
 */
final class DoubaoChat {
    interface Completion { void done(boolean success, int status, String error); }

    static final class UpstreamException extends IOException {
        final int status;
        UpstreamException(String message, int status) { super(message); this.status = status; }
    }

    static final class Result {
        String text = "", reasoning = "", conversationId = "", sectionId = "";
        JSONArray toolCalls = new JSONArray();
    }

    private final DoubaoAuth.Credential credential;
    private final DoubaoAuth.Transport transport;
    private volatile boolean cancelled;

    DoubaoChat(DoubaoAuth.Credential credential, DoubaoAuth.Transport transport) throws IOException {
        if (credential == null) throw new IOException("Doubao credential required");
        if (transport == null) throw new IOException("Doubao transport required");
        this.credential = credential;
        this.transport = transport;
    }

    void cancel() { cancelled = true; }

    void validate(JSONObject request) throws IOException {
        if (request == null) throw new IOException("request required");
        DoubaoProtocol.Model model = DoubaoProtocol.resolve(request.optString("model", ""));
        if (model == null) throw new IOException("Invalid model for Doubao work mode");
        JSONArray messages = request.optJSONArray("messages");
        if (messages == null || messages.length() == 0) throw new IOException("messages required");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) throw new IOException("Invalid messages entry");
            String role = message.optString("role", "");
            if (!"system".equals(role) && !"developer".equals(role) && !"user".equals(role)
                    && !"assistant".equals(role) && !"tool".equals(role))
                throw new IOException("Unsupported messages.role for Doubao work mode");
            Object content = message.opt("content");
            if (content == null || content == JSONObject.NULL) {
                if (!"assistant".equals(role) || message.optJSONArray("tool_calls") == null)
                    throw new IOException("Invalid messages.content");
                continue;
            }
            if (content instanceof String) continue;
            if (content instanceof JSONArray) {
                JSONArray parts = (JSONArray) content;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null || !"text".equals(part.optString("type", "")) || !(part.opt("text") instanceof String))
                        throw new IOException("Doubao work mode is text-only; images and files are unsupported");
                }
                continue;
            }
            throw new IOException("Invalid messages.content");
        }
        try { DeepSeekToolBridge.analyze(request); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage()); }
    }

    /** 会话缓存只减少豆包侧新建会话的数量；不保存任何消息正文。 */
    private static final class Conversation {
        final String id, sectionId;
        final long at;
        Conversation(String id, String sectionId, long at) { this.id = id; this.sectionId = sectionId; this.at = at; }
    }
    private static final int MAX_CONVERSATIONS = 128;
    private static final long CONVERSATION_TTL_MS = 6L * 60 * 60 * 1000;
    private static final LinkedHashMap<String, Conversation> CONVERSATIONS =
            new LinkedHashMap<String, Conversation>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Conversation> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            };

    static synchronized Conversation conversation(String key) {
        Conversation value = CONVERSATIONS.get(key);
        if (value == null) return null;
        if (System.currentTimeMillis() - value.at > CONVERSATION_TTL_MS) {
            CONVERSATIONS.remove(key);
            return null;
        }
        return value;
    }

    static synchronized void rememberConversation(String key, String id, String sectionId) {
        if (id == null || id.isEmpty()) return;
        CONVERSATIONS.put(key, new Conversation(id, sectionId == null ? "" : sectionId, System.currentTimeMillis()));
    }

    static synchronized void forgetConversation(String key) {
        if (key != null) CONVERSATIONS.remove(key);
    }

    /** 测试用：清空进程内会话缓存。 */
    static synchronized void clearConversationCacheForTests() { CONVERSATIONS.clear(); }

    private static String conversationKey(DoubaoAuth.Credential credential, String model, JSONObject request) {
        JSONArray messages = request.optJSONArray("messages");
        String first = DoubaoProtocol.firstUserText(messages);
        if (first.isEmpty()) first = DoubaoProtocol.flatten(messages);
        if (first.length() > 400) first = first.substring(0, 400);
        return DoubaoAuth.sha256Hex(credential.cookie).substring(0, 16) + "|" + model + "|"
                + DoubaoAuth.sha256Hex(first).substring(0, 32);
    }

    Result run(JSONObject request, DoubaoProtocol.Sink listener) throws IOException {
        validate(request);
        DoubaoProtocol.Model model = DoubaoProtocol.resolve(request.optString("model", ""));
        DeepSeekToolBridge.Prepared prepared;
        try { prepared = DeepSeekToolBridge.analyze(request); }
        catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage()); }
        String prompt = prepared.enabled
                ? prepared.buildPrompt()
                : DoubaoProtocol.flatten(request.optJSONArray("messages"));
        if (prompt.trim().isEmpty()) throw new IOException("messages text is empty");

        String key = conversationKey(credential, model.id, request);
        Conversation cached = conversation(key);
        String conversationId = cached == null ? "" : cached.id;
        JSONObject body = DoubaoProtocol.build(prompt, model, conversationId,
                DoubaoProtocol.effort(request.optString("reasoning_effort", ""), model),
                credential.deviceId, credential.localAppId);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        Collector collector = new Collector(listener, key, model.id, prepared);
        try (DoubaoAuth.Response response = transport.request("POST", DoubaoAuth.chatUrl(credential),
                DoubaoAuth.headers(credential), bytes)) {
            if (response.status == 401 || response.status == 403)
                throw new UpstreamException("Doubao authentication failed", 401);
            if (response.status == 402)
                throw new UpstreamException("Doubao quota exhausted", 402);
            if (response.status == 429)
                throw new UpstreamException("Doubao rate limited", 429);
            if (response.status < 200 || response.status >= 300)
                throw new UpstreamException("Doubao upstream HTTP " + response.status, 502);
            if (response.contentType != null && response.contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
                String text = DoubaoAuth.read(response.body, DoubaoAuth.MAX_BODY);
                JSONObject value;
                try { value = new JSONObject(text); }
                catch (Exception invalid) { throw new UpstreamException("Doubao returned invalid JSON", 502); }
                Object code = value.opt("code");
                if (code != null && code != JSONObject.NULL && !"0".equals(String.valueOf(code))) {
                    if ("710012001".equals(String.valueOf(code)))
                        throw new UpstreamException("Doubao authentication failed", 401);
                    throw new UpstreamException("Doubao refused the request", 502);
                }
            } else {
                DoubaoProtocol.pump(response.body, collector);
            }
        } catch (DoubaoProtocol.StreamException stream) {
            if (cached != null) forgetConversation(key);
            int status = 502;
            if ("710012001".equals(stream.code)) status = 401;
            else if ("710022004".equals(stream.code)) status = 429;
            else if (stream.getMessage() != null
                    && stream.getMessage().toLowerCase(java.util.Locale.ROOT).contains("rate limited")) status = 429;
            throw new UpstreamException(stream.getMessage(), status);
        }
        collector.complete();
        if (cancelled) throw new IOException("Cancelled");

        Result result = collector.result;
        if (result.text.isEmpty() && !collector.brief.isEmpty()) result.text = collector.brief;
        else if (!collector.brief.isEmpty() && collector.brief.startsWith(result.text) && collector.brief.length() > result.text.length())
            result.text = collector.brief;
        if (result.text.isEmpty() && result.toolCalls.length() == 0)
            throw new UpstreamException("豆包没有返回可用的回复", 502);
        return result;
    }

    private final class Collector implements DoubaoProtocol.Sink {
        final DoubaoProtocol.Sink down;
        final Result result = new Result();
        final String key;
        final String model;
        final StringBuilder text = new StringBuilder();
        String brief = "";
        int endType = -1;
        boolean finished;
        DeepSeekToolBridge.StreamParser parser;

        Collector(DoubaoProtocol.Sink down, String key, String model, DeepSeekToolBridge.Prepared prepared) {
            this.down = down; this.key = key; this.model = model;
            final Collector self = this;
            parser = prepared.newParser(new DeepSeekToolBridge.Listener() {
                @Override public void onText(String delta) {
                    text.append(delta);
                    if (self.down != null) try { self.down.onText(delta); } catch (IOException e) { throw new UncheckedIOException(e); }
                }
                @Override public void onToolCall(DeepSeekToolBridge.ToolCall call) {
                    try {
                        result.toolCalls.put(new JSONObject().put("id", call.id).put("type", "function")
                                .put("function", new JSONObject().put("name", call.name).put("arguments", call.arguments)));
                    } catch (Exception impossible) { throw new IllegalStateException(impossible); }
                }
            });
        }

        @Override public void onText(String delta) throws IOException {
            try { parser.feed(delta); } catch (UncheckedIOException wrapped) { throw wrapped.getCause(); }
        }

        @Override public void onReasoning(String delta) throws IOException {
            result.reasoning += delta;
            if (down != null) down.onReasoning(delta);
        }

        @Override public void onConversation(String conversationId, String sectionId) throws IOException {
            result.conversationId = conversationId;
            result.sectionId = sectionId;
            rememberConversation(key, conversationId, sectionId);
            if (down != null) down.onConversation(conversationId, sectionId);
        }

        @Override public void onFinish(int endType, String brief) throws IOException {
            if (endType >= 0) this.endType = endType;
            if (brief != null && !brief.isEmpty()) this.brief = brief;
            complete();
            if (down != null) down.onFinish(this.endType, this.brief);
        }

        void complete() {
            if (finished) return;
            try { parser.finish(); } catch (UncheckedIOException wrapped) { throw wrapped; }
            finished = true;
        }
    }

    InputStream stream(final JSONObject request, final String model, final Completion completion) throws IOException {
        validate(request);
        final PipedInputStream input = new PipedInputStream(32768);
        final PipedOutputStream output = new PipedOutputStream(input);
        final NetworkPolicy.Snapshot snapshot = NetworkPolicy.capture();
        Thread worker = new Thread(() -> {
            try (NetworkPolicy.Scope scope = NetworkPolicy.use(snapshot)) {
                boolean success = false; int status = 502; String error = "";
                try {
                    DoubaoProtocol.Sink sink = new DoubaoProtocol.Sink() {
                        private void send(String field, String value) throws IOException {
                            if (cancelled) throw new IOException("Cancelled");
                            output.write(chunk(model, field, value, false).getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }
                        @Override public void onText(String delta) throws IOException { send("content", delta); }
                        @Override public void onReasoning(String delta) throws IOException { send("reasoning_content", delta); }
                        @Override public void onConversation(String conversationId, String sectionId) throws IOException {}
                        @Override public void onFinish(int endType, String brief) throws IOException {}
                    };
                    Result result = run(request, sink);
                    if (cancelled) throw new IOException("Cancelled");
                    for (int i = 0; i < result.toolCalls.length(); i++) {
                        JSONObject call = result.toolCalls.getJSONObject(i);
                        call.put("index", i);
                        JSONObject delta = new JSONObject().put("tool_calls", new JSONArray().put(call));
                        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                                .put("finish_reason", JSONObject.NULL);
                        output.write(("data: " + new JSONObject().put("object", "chat.completion.chunk")
                                .put("model", model).put("choices", new JSONArray().put(choice)).toString() + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    output.write((result.toolCalls.length() > 0
                            ? finishToolChunk(model) + "data: [DONE]\n\n"
                            : chunk(model, null, null, true) + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    success = true;
                } catch (Exception failure) {
                    if (failure instanceof UpstreamException) status = ((UpstreamException) failure).status;
                    error = cancelled ? "Client cancelled" : safeErrorMessage(status);
                    if (!cancelled) try {
                        output.write(errorEvent(status).getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException ignored) {}
                } finally {
                    try { output.close(); } catch (IOException ignored) {}
                    completion.done(success, success ? 200 : status, error);
                }
            }
        }, "doubao-work-stream");
        worker.setDaemon(true);
        worker.start();
        return new java.io.FilterInputStream(input) {
            @Override public void close() throws IOException { cancel(); worker.interrupt(); super.close(); }
        };
    }

    static int safeStatus(UpstreamException failure) {
        if (failure == null) return 502;
        int status = failure.status;
        return status == 401 || status == 402 || status == 429 ? status : 502;
    }

    static String safeErrorMessage(int status) {
        if (status == 401) return "豆包登录态已过期，请重新登录后再试";
        if (status == 402) return "豆包账号额度或积分不足，请到豆包客户端确认";
        if (status == 429) return "豆包请求过于频繁，请稍后重试";
        return "豆包上游请求失败，请稍后重试";
    }

    static String errorEvent(int status) {
        try {
            String type = status == 401 ? "authentication_error" : (status == 429 ? "rate_limit_error" : "upstream_error");
            JSONObject detail = new JSONObject().put("type", type).put("code", status).put("message", safeErrorMessage(status));
            return "data: " + new JSONObject().put("error", detail).toString() + "\n\n";
        } catch (Exception impossible) {
            return "data: {\"error\":{\"type\":\"upstream_error\",\"code\":502}}\n\n";
        }
    }

    static String chunk(String model, String field, String value, boolean finish) throws IOException {
        try {
            JSONObject delta = new JSONObject();
            if (field != null) delta.put(field, value);
            JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                    .put("finish_reason", finish ? "stop" : JSONObject.NULL);
            return "data: " + new JSONObject().put("object", "chat.completion.chunk").put("model", model)
                    .put("choices", new JSONArray().put(choice)).toString() + "\n\n";
        } catch (Exception e) { throw new IOException(e); }
    }

    static String finishToolChunk(String model) {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", new JSONObject()).put("finish_reason", "tool_calls");
        return "data: " + new JSONObject().put("object", "chat.completion.chunk").put("model", model)
                .put("choices", new JSONArray().put(choice)).toString() + "\n\n";
    }
}
