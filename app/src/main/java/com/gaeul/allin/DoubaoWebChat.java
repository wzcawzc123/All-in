package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONException;
import com.gaeul.allin.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把豆包网页协议适配成增量 OpenAI chunk。
 *
 * 与 DeepSeekWebChat 同构：run() 收全量、stream() 用管道吐 SSE。
 * 差别在于豆包的信封只收单条 text，所以多轮历史由 buildPrompt() 拼进一条 prompt，
 * 每次请求新建会话（conversation_id 不跨请求保存）。
 */
final class DoubaoWebChat {
    interface Completion { void done(boolean success, int status, String error); }

    static final class Result { String text = ""; String reasoning = ""; }

    private final String cookie;
    private final DoubaoWeb.Transport transport;
    private volatile boolean cancelled;

    DoubaoWebChat(String cookie, DoubaoWeb.Transport transport) throws IOException {
        if (transport == null) throw new IOException("transport required");
        // cookie 里允许空格（分隔符），但绝不允许换行，否则就是 header 注入面
        if (cookie == null || cookie.trim().isEmpty()
                || cookie.indexOf('\n') >= 0 || cookie.indexOf('\r') >= 0) {
            throw new IOException("Invalid doubao cookie");
        }
        this.cookie = cookie;
        this.transport = transport;
    }

    void cancel() { cancelled = true; }

    void validate(JSONObject request) throws IOException {
        String model = request.optString("model", DoubaoWeb.DEFAULT_MODEL);
        if (!DoubaoWeb.isKnownModel(model)) throw new IOException("Invalid model for Doubao web: " + model);
        JSONArray messages = request.optJSONArray("messages");
        if (messages == null || messages.length() == 0) throw new IOException("messages required");
    }

    /** 把 OpenAI 的多轮 messages 拼成豆包信封能收的单条 text。 */
    private static String buildPrompt(JSONObject request) throws IOException {
        JSONArray messages = request.optJSONArray("messages");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String content = contentOf(m);
            if (content.isEmpty()) continue;
            if ("system".equals(role) || "developer".equals(role)) sb.append("System: ");
            else if ("user".equals(role)) sb.append("User: ");
            else if ("assistant".equals(role)) sb.append("Assistant: ");
            else if ("tool".equals(role)) sb.append("Tool result: ");
            sb.append(content).append("\n\n");
        }
        String prompt = sb.toString().trim();
        if (prompt.isEmpty()) throw new IOException("empty prompt");
        return prompt;
    }

    /** content 可能是字符串，也可能是多模态块数组；这里只取文本部分。 */
    private static String contentOf(JSONObject message) {
        Object raw = message.opt("content");
        if (raw instanceof String) return (String) raw;
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) continue;
                if ("text".equals(part.optString("type", ""))) {
                    String t = part.optString("text", "");
                    if (!t.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(t); }
                }
            }
            return sb.toString();
        }
        return "";
    }

    Result run(JSONObject request, DoubaoWeb.Listener listener) throws IOException {
        validate(request);
        DoubaoWeb.Mode mode = DoubaoWeb.resolveModel(
                request.optString("model", DoubaoWeb.DEFAULT_MODEL));
        String prompt = buildPrompt(request);
        JSONObject envelope = DoubaoWeb.buildEnvelope(prompt, mode, "");
        byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);
        String url = DoubaoWeb.ORIGIN + DoubaoWeb.COMPLETION_PATH + "?" + DoubaoWeb.buildQuery();

        DoubaoWeb.Response resp = transport.request("POST", url, DoubaoWeb.headers(cookie), body);
        try {
            if (resp.status < 200 || resp.status >= 300) {
                String errBody = readAll(resp.body, 400);
                int gw = DoubaoWeb.gatewayStatus(0, resp.status);
                throw new Upstream(gw, resp.status, errBody, false,
                        DoubaoWeb.safeErrorMessage(gw));
            }
            return parseStream(resp.body, listener);
        } finally {
            resp.close();
        }
    }

    private Result parseStream(InputStream in, DoubaoWeb.Listener listener) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        DoubaoWeb.SseFrame frame = new DoubaoWeb.SseFrame();
        DoubaoWeb.StreamState st = new DoubaoWeb.StreamState();
        String line;
        while ((line = br.readLine()) != null) {
            if (!frame.feed(line)) continue;
            String ev = frame.eventName();
            JSONObject data = DoubaoWeb.parseData(frame.dataText());
            frame.reset();
            for (DoubaoWeb.Event e : DoubaoWeb.handleEvent(ev, data, st)) {
                if (e.kind == DoubaoWeb.EV_ERROR) {
                    int code = DoubaoWeb.errorCodeOf(e.text);
                    int gw = DoubaoWeb.gatewayStatus(code, 0);
                    throw new Upstream(gw, 0, e.text, false, e.text);
                }
                if (e.kind == DoubaoWeb.EV_DELTA && listener != null && e.text != null) {
                    listener.onText(e.text);
                } else if (e.kind == DoubaoWeb.EV_REASONING && listener != null && e.text != null) {
                    listener.onReasoning(e.text);
                }
                // EV_META / EV_BODY_END / EV_DONE 这里不区分：正文在 end_type:1 已完整，
                // 下游的 finish_reason 由 stream() 统一在收尾时发。
            }
        }
        Result r = new Result();
        r.text = st.assembled();
        return r;
    }

    /** 上游非 200 或流内错误统一走这里。 */
    static final class Upstream extends DoubaoWeb.UpstreamException {
        Upstream(int gatewayStatus, int upstreamStatus, String body, boolean truncated, String message) {
            super(gatewayStatus, upstreamStatus, body, truncated, message);
        }
    }

    // ── OpenAI chunk 组装 ─────────────────────────────────────────────────────
    static String chunk(String model, String field, String value, boolean finish) throws IOException {
        try {
            JSONObject delta = new JSONObject();
            if (field != null) delta.put(field, value);
            JSONObject choice = new JSONObject().put("index", 0)
                    .put("delta", delta).put("finish_reason", finish ? "stop" : JSONObject.NULL);
            return "data: " + new JSONObject()
                    .put("object", "chat.completion.chunk")
                    .put("model", model)
                    .put("choices", new JSONArray().put(choice))
                    .toString() + "\n\n";
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static String safeErrorMessage(int status) { return DoubaoWeb.safeErrorMessage(status); }

    JSONObject errorDetail(int status) {
        String type;
        switch (status) {
            case 401: type = "authentication_error"; break;
            case 403: type = "permission_error"; break;
            case 429: type = "rate_limit_error"; break;
            default: type = "upstream_error";
        }
        try {
            return new JSONObject()
                    .put("type", type)
                    .put("code", status)
                    .put("message", DoubaoWeb.safeErrorMessage(status));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * 流式：后台线程跑上游，把每个 delta 写成 OpenAI chunk 经管道吐出，
     * 交给网关既有的 streamForwardResponse 直接转发。
     */
    InputStream stream(final JSONObject request, final String model, final Completion completion)
            throws IOException {
        validate(request);
        final PipedInputStream input = new PipedInputStream(32768);
        final PipedOutputStream output = new PipedOutputStream(input);
        final NetworkPolicy.Snapshot snapshot = NetworkPolicy.capture();
        Thread worker = new Thread(() -> {
            try (NetworkPolicy.Scope scope = NetworkPolicy.use(snapshot)) {
                boolean success = false;
                int status = 502;
                String error = "";
                try {
                    DoubaoWeb.Listener listener = new DoubaoWeb.Listener() {
                        private void send(String field, String text) throws IOException {
                            if (cancelled) throw new IOException("Cancelled");
                            output.write(chunk(model, field, text, false).getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }
                        @Override public void onText(String text) throws IOException { send("content", text); }
                        @Override public void onReasoning(String text) throws IOException { send("reasoning_content", text); }
                    };
                    run(request, listener);
                    if (cancelled) throw new IOException("Cancelled");
                    output.write(chunk(model, null, null, true).getBytes(StandardCharsets.UTF_8));
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    success = true;
                } catch (Exception e) {
                    DoubaoWeb.UpstreamException failure =
                            e instanceof DoubaoWeb.UpstreamException ? (DoubaoWeb.UpstreamException) e : null;
                    if (failure != null) status = failure.gatewayStatus;
                    error = cancelled ? "Client cancelled" : DoubaoWeb.safeErrorMessage(status);
                    if (!cancelled) {
                        try {
                            output.write(("data: " + new JSONObject().put("error", errorDetail(status)) + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        } catch (IOException ignored) { }
                    }
                } finally {
                    try { output.close(); } catch (IOException ignored) { }
                    completion.done(success, success ? 200 : status, error);
                }
            }
        }, "doubao-web-stream");
        worker.setDaemon(true);
        worker.start();
        return new FilterInputStream(input) {
            @Override public void close() throws IOException {
                cancel();
                worker.interrupt();
                super.close();
            }
        };
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (b.size() < limit && (n = in.read(buf)) > 0) {
            int remain = limit - b.size();
            b.write(buf, 0, Math.min(n, remain));
        }
        return b.toString(StandardCharsets.UTF_8.name());
    }

    /** 默认传输：走系统网络策略，与其它网页供应商一致。 */
    static final class HttpTransport implements DoubaoWeb.Transport {
        @Override
        public DoubaoWeb.Response request(String method, String url, Map<String, String> headers, byte[] body)
                throws IOException {
            final HttpURLConnection c = NetworkPolicy.openHttp(url);
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            try {
                if (body != null) {
                    c.setDoOutput(true);
                    try (OutputStream o = c.getOutputStream()) { o.write(body); }
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) in = new ByteArrayInputStream(new byte[0]);
                return new DoubaoWeb.Response(status, c.getContentType(), new FilterInputStream(in) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { c.disconnect(); }
                    }
                });
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
        }
    }
}
