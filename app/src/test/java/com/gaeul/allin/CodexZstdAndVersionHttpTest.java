package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies Codex client version upgrade to 0.169.1 (no Codex Desktop toggle)
 * and POST /backend-api/codex/responses zstd level-3 / 2 MiB window / no-CRC compression.
 */
public final class CodexZstdAndVersionHttpTest {
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    static final class RawUpstream implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final Thread thread;
        final String replyBody;
        final String replyType;
        volatile String requestLine;
        volatile Map<String, String> headers;
        volatile byte[] rawBody;
        volatile Throwable error;

        RawUpstream(String replyType, String replyBody) throws Exception {
            this.replyType = replyType;
            this.replyBody = replyBody;
            this.thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(5000);
                        InputStream in = s.getInputStream();
                        ByteArrayOutputStream head = new ByteArrayOutputStream();
                        int state = 0, c;
                        while (state < 4 && (c = in.read()) != -1) {
                            head.write(c);
                            state = state == 0 && c == 13 ? 1 : state == 1 && c == 10 ? 2 : state == 2 && c == 13 ? 3 : state == 3 && c == 10 ? 4 : 0;
                        }
                        String[] lines = head.toString("UTF-8").split("\r\n");
                        requestLine = lines[0];
                        Map<String, String> h = new HashMap<>();
                        for (int i = 1; i < lines.length; i++) {
                            int colon = lines[i].indexOf(':');
                            if (colon > 0) h.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
                        }
                        int len = h.containsKey("content-length") ? Integer.parseInt(h.get("content-length")) : 0;
                        byte[] b = new byte[len];
                        int n = 0;
                        while (n < len) {
                            int r = in.read(b, n, len - n);
                            if (r < 0) throw new EOFException();
                            n += r;
                        }
                        headers = h;
                        rawBody = b;
                        byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + replyType
                                + "\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        s.getOutputStream().write(out);
                        s.getOutputStream().flush();
                    } catch (Throwable e) {
                        if (!server.isClosed()) error = e;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        public void close() throws Exception {
            server.close();
            thread.join(2000);
            if (error != null) throw new AssertionError(error);
        }
    }

    static long parseZstdFrameWindowAndVerifyNoCrc(byte[] frame) {
        check(frame.length >= 6, "zstd frame too short: " + frame.length);
        // RFC 8878 Magic_Number = 0xFD2FB528 little-endian: 28 B5 2F FD
        check((frame[0] & 0xFF) == 0x28 && (frame[1] & 0xFF) == 0xB5
                && (frame[2] & 0xFF) == 0x2F && (frame[3] & 0xFF) == 0xFD, "invalid zstd magic");
        int fhd = frame[4] & 0xFF;
        boolean singleSegment = (fhd & 0x20) != 0;
        boolean checksumFlag = (fhd & 0x04) != 0;
        check(!checksumFlag, "Codex zstd must disable frame CRC checksum");
        if (singleSegment) {
            return -1L; // Single-segment frames use Frame_Content_Size as window size.
        }
        int wd = frame[5] & 0xFF;
        int exponent = wd >>> 3;
        int mantissa = wd & 0x07;
        long windowBase = 1L << (10 + exponent);
        long windowAdd = (windowBase / 8L) * mantissa;
        return windowBase + windowAdd;
    }

    static void verifyVersionAndPolicyMatrix() throws Exception {
        check("0.169.1".equals(CodexAuth.CODEX_VERSION), "CODEX_VERSION=" + CodexAuth.CODEX_VERSION);
        check(CodexAuth.USER_AGENT.startsWith("codex_cli_rs/0.169.1 "), "USER_AGENT=" + CodexAuth.USER_AGENT);
        check("codex_cli_rs".equals(CodexAuth.ORIGINATOR), "ORIGINATOR=" + CodexAuth.ORIGINATOR);

        check(CodexZstd.shouldCompress("POST", CodexAuth.CODEX_RESPONSES, "chatgpt", null), "ChatGPT /responses compressed");
        check(CodexZstd.shouldCompress("POST", CodexAuth.CODEX_RESPONSES, "", "identity"), "empty authMode + identity compressed");
        check(!CodexZstd.shouldCompress("POST", CodexAuth.CODEX_BASE + "/responses/compact", "chatgpt", null), "/responses/compact never compressed");
        check(!CodexZstd.shouldCompress("POST", CodexAuth.CODEX_BASE + "/images/generations", "chatgpt", null), "/images/generations never compressed");
        check(!CodexZstd.shouldCompress("GET", CodexAuth.CODEX_MODELS, "chatgpt", null), "GET /models never compressed");
        check(!CodexZstd.shouldCompress("POST", "https://api.openai.com/v1/responses", "chatgpt", null), "api.openai.com never compressed");
        check(!CodexZstd.shouldCompress("POST", CodexAuth.CODEX_RESPONSES, "apikey", null), "apikey mode never compressed");
        check(!CodexZstd.shouldCompress("POST", CodexAuth.CODEX_RESPONSES, "chatgpt", "zstd"), "already zstd never double-compressed");
        check(!CodexZstd.shouldCompress("POST", CodexAuth.CODEX_RESPONSES, "chatgpt", "gzip"), "already gzip never double-compressed");

        // Short body compression & roundtrip
        byte[] small = "{\"model\":\"gpt-5\",\"input\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        byte[] smallZ = CodexZstd.compress(small);
        parseZstdFrameWindowAndVerifyNoCrc(smallZ);
        check(new String(CodexZstd.decompress(smallZ, 1024 * 1024), StandardCharsets.UTF_8).equals(new String(small, StandardCharsets.UTF_8)),
                "small roundtrip exact");

        // Large (> 3 MiB) body: verify high compression ratio, exact roundtrip, no CRC, and window <= 2 MiB.
        StringBuilder sb = new StringBuilder(3_500_000);
        sb.append("{\"model\":\"gpt-5\",\"input\":\"");
        for (int i = 0; i < 60000; i++) {
            sb.append("AllIn-Codex-Context-Block-").append(i % 97).append("-Payload-Text-0123456789 ");
        }
        sb.append("\"}");
        byte[] large = sb.toString().getBytes(StandardCharsets.UTF_8);
        check(large.length > 3 * 1024 * 1024, "large test payload > 3 MiB: " + large.length);
        byte[] largeZ = CodexZstd.compress(large);
        long windowSize = parseZstdFrameWindowAndVerifyNoCrc(largeZ);
        check(windowSize > 0 && windowSize <= (2L << 20), "windowSize must be <= 2 MiB, got " + windowSize);
        check(largeZ.length < large.length / 10, "expected strong compression ratio: " + largeZ.length + " vs " + large.length);
        byte[] restored = CodexZstd.decompress(largeZ, large.length + 1024);
        check(restored.length == large.length && java.util.Arrays.equals(restored, large), "large roundtrip exact");
    }

    static GatewayServer gatewayWithAuthMode(int port, String authMode) throws Exception {
        JSONObject account = new JSONObject().put("id", "c1").put("access", "apikey".equals(authMode) ? "sk-test-codex" : "tok")
                .put("token", "apikey".equals(authMode) ? "sk-test-codex" : "tok")
                .put("accountId", "apikey".equals(authMode) ? "" : "acct-1")
                .put("authMode", authMode).put("enabled", true);
        JSONObject provider = new JSONObject().put("id", "codex").put("protocol", "codex").put("enabled", true)
                .put("models", new JSONArray().put("m")).put("accounts", new JSONArray().put(account));
        JSONObject cfg = new JSONObject().put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "secret"))
                .put("providers", new JSONArray().put(provider)).put("groups", new JSONArray())
                .put("privacy", new JSONObject().put("enabled", false))
                .put("guard", new JSONObject().put("enabled", false));
        GatewayServer g = RoutingSetupOfflineTest.server(cfg);
        g.setVendorConnectionsForTests(u -> NetworkPolicy.openHttp("http://127.0.0.1:" + port + new java.net.URL(u).getFile()));
        return g;
    }

    static void verifyEndToEndWireBehavior() throws Exception {
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n";

        // 1. /v1/chat/completions -> ChatGPT Codex: version 0.169.1 + UA 0.169.1 + Content-Encoding: zstd
        try (RawUpstream up = new RawUpstream("text/event-stream", sse)) {
            GatewayServer g = gatewayWithAuthMode(up.server.getLocalPort(), "chatgpt");
            JSONObject chat = new JSONObject().put("model", "codex/m").put("stream", false)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello codex zstd")));
            NanoHTTPD.Response resp = g.serve(ResponsesGatewayOfflineTest.session(chat.toString(), "secret", "/v1/chat/completions"));
            String out = ResponsesGatewayOfflineTest.read(resp.getData());
            check(resp.getStatus().getRequestStatus() == 200, "chat status " + out);
            check("0.169.1".equals(up.headers.get("version")), "chat version header: " + up.headers);
            check(up.headers.get("user-agent") != null && up.headers.get("user-agent").contains("codex_cli_rs/0.169.1 "), "chat UA: " + up.headers);
            check("zstd".equals(up.headers.get("content-encoding")), "chat must send Content-Encoding: zstd, got " + up.headers);
            parseZstdFrameWindowAndVerifyNoCrc(up.rawBody);
            JSONObject decoded = new JSONObject(new String(CodexZstd.decompress(up.rawBody, 1024 * 1024), StandardCharsets.UTF_8));
            check("m".equals(decoded.optString("model")), "decoded model mismatch: " + decoded);
        }

        // 2. /backend-api/codex/responses -> ChatGPT Codex: zstd compressed + 0.169.1
        try (RawUpstream up = new RawUpstream("text/event-stream", sse)) {
            GatewayServer g = gatewayWithAuthMode(up.server.getLocalPort(), "chatgpt");
            JSONObject req = new JSONObject().put("model", "m").put("stream", true).put("store", false)
                    .put("instructions", "").put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")));
            NanoHTTPD.Response resp = g.serve(ResponsesGatewayOfflineTest.session(req.toString(), "secret", "/backend-api/codex/responses"));
            String out = ResponsesGatewayOfflineTest.read(resp.getData());
            check(resp.getStatus().getRequestStatus() == 200, "native responses status " + out);
            check("0.169.1".equals(up.headers.get("version")), "native version header: " + up.headers);
            check(up.headers.get("user-agent") != null && up.headers.get("user-agent").contains("codex_cli_rs/0.169.1 "), "native UA: " + up.headers);
            check("zstd".equals(up.headers.get("content-encoding")), "native /responses must use zstd: " + up.headers);
            parseZstdFrameWindowAndVerifyNoCrc(up.rawBody);
            JSONObject decoded = new JSONObject(new String(CodexZstd.decompress(up.rawBody, 1024 * 1024), StandardCharsets.UTF_8));
            check("m".equals(decoded.optString("model")), "native decoded model: " + decoded);
        }

        // 3. /backend-api/codex/responses/compact -> NEVER zstd compressed, still 0.169.1
        try (RawUpstream up = new RawUpstream("application/json", "{\"id\":\"cmp_1\",\"output\":[]}")) {
            GatewayServer g = gatewayWithAuthMode(up.server.getLocalPort(), "chatgpt");
            JSONObject req = new JSONObject().put("model", "m").put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "compact me")));
            NanoHTTPD.Response resp = g.serve(ResponsesGatewayOfflineTest.session(req.toString(), "secret", "/backend-api/codex/responses/compact"));
            String out = ResponsesGatewayOfflineTest.read(resp.getData());
            check(resp.getStatus().getRequestStatus() == 200, "compact status " + out);
            check("0.169.1".equals(up.headers.get("version")), "compact version header: " + up.headers);
            check(up.headers.get("content-encoding") == null, "compact must NOT send Content-Encoding: " + up.headers);
            JSONObject plain = new JSONObject(new String(up.rawBody, StandardCharsets.UTF_8));
            check("m".equals(plain.optString("model")), "compact plain JSON intact");
        }

        // 4. apikey mode -> NEVER zstd compressed, still 0.169.1
        try (RawUpstream up = new RawUpstream("text/event-stream", sse)) {
            GatewayServer g = gatewayWithAuthMode(up.server.getLocalPort(), "apikey");
            JSONObject chat = new JSONObject().put("model", "codex/m").put("stream", false)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello apikey")));
            NanoHTTPD.Response resp = g.serve(ResponsesGatewayOfflineTest.session(chat.toString(), "secret", "/v1/chat/completions"));
            String out = ResponsesGatewayOfflineTest.read(resp.getData());
            check(resp.getStatus().getRequestStatus() == 200, "apikey status " + out);
            check("0.169.1".equals(up.headers.get("version")), "apikey version header: " + up.headers);
            check(up.headers.get("content-encoding") == null, "apikey must NOT send Content-Encoding: " + up.headers);
            JSONObject plain = new JSONObject(new String(up.rawBody, StandardCharsets.UTF_8));
            check("m".equals(plain.optString("model")), "apikey plain JSON intact");
        }

        // 5. CodexAuth.syncModels -> client_version=0.169.1 + version: 0.169.1 + UA 0.169.1
        try (RawUpstream up = new RawUpstream("application/json", "{\"models\":[{\"slug\":\"gpt-5.6-luna\",\"visibility\":\"list\"}]}")) {
            CodexAuth.SignInInfo info = new CodexAuth.SignInInfo();
            info.accessToken = "tok";
            info.accountId = "acct-1";
            info.authMode = "chatgpt";
            List<String> models = CodexAuth.syncModelsStrict(info,
                    u -> NetworkPolicy.openHttp("http://127.0.0.1:" + up.server.getLocalPort() + new java.net.URL(u).getFile()));
            check(models.size() == 1 && "gpt-5.6-luna".equals(models.get(0)), "models=" + models);
            check(up.requestLine.contains("client_version=0.169.1"), "models query=" + up.requestLine);
            check("0.169.1".equals(up.headers.get("version")), "models version header=" + up.headers);
            check(up.headers.get("user-agent") != null && up.headers.get("user-agent").contains("codex_cli_rs/0.169.1 "), "models UA=" + up.headers);
        }
    }

    public static void main(String[] args) throws Exception {
        verifyVersionAndPolicyMatrix();
        verifyEndToEndWireBehavior();
        System.out.println("CodexZstdAndVersionHttpTest passed");
    }
}
