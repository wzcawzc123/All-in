package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;

/**
 * Verifies Codex SSE failure telemetry & conversion safety across:
 * 1) Chat streaming (/v1/chat/completions, stream=true)
 * 2) Messages streaming (/v1/messages, stream=true)
 * 3) Native Responses streaming & non-streaming (/v1/responses, stream=true/false)
 * 4) Non-streaming upstream SSE aggregation (/v1/chat/completions & /v1/messages, stream=false)
 * and verifies complete secret redaction across logs, /api/sessions, CSV and post-restart ledger reload.
 */
public final class CodexDiagnosticsAndRedactionHttpTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final String SYN_ACCESS = "codex-access-SECRET-TOKEN-111";
    private static final String SYN_REFRESH = "codex-refresh-SECRET-TOKEN-222";
    private static final String SYN_SK = "sk-codex-secret-key-333444555";
    private static final String SYN_JWT = "eyJcodexHdr123.eyJcodexPayload456.sig789";

    private static final class FaultStream extends InputStream {
        final byte[] prefix;
        final Throwable fault;
        int pos;
        FaultStream(String prefix, Throwable fault) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
            this.fault = fault;
        }
        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 255);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (pos < prefix.length) {
                int n = Math.min(len, prefix.length - pos);
                System.arraycopy(prefix, pos, b, off, n);
                pos += n;
                return n;
            }
            if (fault == null) return -1;
            if (fault instanceof IOException) throw (IOException) fault;
            throw new IOException(fault);
        }
    }

    private static class MockCodexConn extends HttpURLConnection {
        final int status;
        final String contentType;
        final InputStream bodyStream;
        MockCodexConn(String url, int status, InputStream bodyStream) throws IOException {
            this(url, status, status >= 400 ? "application/json" : "text/event-stream", bodyStream);
        }
        MockCodexConn(String url, int status, String contentType, InputStream bodyStream) throws IOException {
            super(new URL(url));
            this.status = status;
            this.contentType = contentType;
            this.bodyStream = bodyStream;
        }
        @Override public void connect() {}
        @Override public void disconnect() { try { if (bodyStream != null) bodyStream.close(); } catch (IOException ignored) {} }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return contentType; }
        @Override public InputStream getInputStream() throws IOException { return bodyStream; }
        @Override public InputStream getErrorStream() { return status >= 400 ? bodyStream : null; }
    }

    private static final class HttpGateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;
        HttpGateway(GatewayServer inner) throws Exception {
            super("127.0.0.1", 0);
            this.inner = inner;
            start(SOCKET_READ_TIMEOUT, true);
        }
        @Override public Response serve(IHTTPSession s) { return inner.serve(s); }
        @Override public void close() { inner.stop(); stop(); }
    }

    private static final class Reply { int status; String body; String requestId; }

    private static Reply call(int port, String path, String auth, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        if (auth != null) conn.setRequestProperty("Authorization", "Bearer " + auth);
        if (path.startsWith("/api/")) conn.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        Reply r = new Reply();
        r.status = conn.getResponseCode();
        r.requestId = conn.getHeaderField("X-AllIn-Request-Id");
        try (InputStream is = r.status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            r.body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
        return r;
    }

    private static GatewayServer createGateway(Path dir, GatewayServer.VendorConnectionFactory factory) throws Exception {
        GatewayServer srv = RoutingSetupOfflineTest.server(new JSONObject());
        RoutingAuditOfflineTest.file(srv, dir.resolve("config.json").toFile());
        JSONObject acc = new JSONObject()
                .put("id", "codex-acc-1")
                .put("name", "CodexTest")
                .put("source", "oauth")
                .put("authMode", "chatgpt")
                .put("access", SYN_ACCESS)
                .put("refresh", SYN_REFRESH)
                .put("idToken", SYN_JWT)
                .put("accountId", "acct-test-codex")
                .put("expires", System.currentTimeMillis() + 3600_000L)
                .put("enabled", true);
        JSONObject codex = new JSONObject()
                .put("id", "codex")
                .put("name", "Codex")
                .put("protocol", "codex")
                .put("enabled", true)
                .put("baseUrl", "https://chatgpt.com/backend-api/codex")
                .put("keys", new JSONArray().put(SYN_SK))
                .put("models", new JSONArray().put("gpt-5"))
                .put("accounts", new JSONArray().put(acc));
        JSONObject cfg = new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("port", 0).put("authKey", "GW-TEST-KEY-777"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(codex))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("groups", new JSONArray());
        java.lang.reflect.Field cfgField = GatewayServer.class.getDeclaredField("configJson");
        cfgField.setAccessible(true);
        cfgField.set(srv, cfg);
        java.lang.reflect.Field vf = GatewayServer.class.getDeclaredField("vendorConnectionFactory");
        vf.setAccessible(true);
        vf.set(srv, factory);
        srv.initPersistentLiveCounters(dir.toFile());
        return srv;
    }

    private static JSONObject rowByRequestId(int port, String requestId) throws Exception {
        Reply r = call(port, "/api/sessions?limit=50", null, null);
        check(r.status == 200, "sessions status=" + r.status);
        JSONArray list = new JSONObject(r.body).getJSONArray("sessions");
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (requestId.equals(item.optString("requestId"))) return item;
        }
        throw new AssertionError("Missing session row for requestId=" + requestId + " in " + r.body);
    }

    private static JSONObject rowSettledByRequestId(int port, String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 1500L;
        JSONObject last = null;
        while (true) {
            try {
                last = rowByRequestId(port, requestId);
                if (!"unknown".equals(last.optString("deliveryStatus"))) return last;
            } catch (AssertionError notYet) {
                last = null;
            }
            if (System.currentTimeMillis() >= deadline) {
                if (last != null) return last;
                return rowByRequestId(port, requestId);
            }
            Thread.sleep(15L);
        }
    }

    private static String csvLineForRequestId(String csv, String requestId) {
        for (String line : csv.split("\n")) {
            if (line.contains("," + requestId + ",")) return line;
        }
        throw new AssertionError("Missing CSV line for requestId=" + requestId);
    }

    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        Path dir = Files.createTempDirectory("codex-diag-test-");
        UsageLedger.init(dir.toFile());
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        GatewayServer.diagnosticLogHook = logs::add;

        final String[] mode = {"tls"};
        final AtomicInteger hits = new AtomicInteger(0);
        String deltaPrefix = "event: response.created\n"
                + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello \"}\n\n";

        GatewayServer.VendorConnectionFactory factory = urlStr -> {
            hits.incrementAndGet();
            switch (mode[0]) {
                case "tls":
                    return new MockCodexConn(urlStr, 200, new FaultStream(deltaPrefix, new SSLException("synthetic TLS read abort")));
                case "timeout":
                    return new MockCodexConn(urlStr, 200, new FaultStream(deltaPrefix, new java.net.SocketTimeoutException("synthetic read timed out")));
                case "eof":
                    return new MockCodexConn(urlStr, 200, new FaultStream(deltaPrefix, null));
                case "explicit_err_secret": {
                    String errEvt = deltaPrefix
                            + "event: response.failed\n"
                            + "data: {\"type\":\"response.failed\",\"error\":{\"code\":\"" + SYN_SK + "\",\"param\":\"" + SYN_JWT + "\",\"message\":\"upstream fail Bearer " + SYN_ACCESS + " and refresh=" + SYN_REFRESH + "\"}}\n\n";
                    return new MockCodexConn(urlStr, 200, new FaultStream(errEvt, null));
                }
                case "conv_invalid": {
                    String badEvt = deltaPrefix
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"wrong_status\",\"output\":[]}}\n\n";
                    return new MockCodexConn(urlStr, 200, new FaultStream(badEvt, null));
                }
                case "http400_secret": {
                    String badHttp = "{\"error\":{\"message\":\"rejected token " + SYN_ACCESS + " with key " + SYN_SK + "\",\"code\":\"" + SYN_JWT + "\",\"param\":\"Bearer " + SYN_REFRESH + "\"}}";
                    return new MockCodexConn(urlStr, 400, new FaultStream(badHttp, null));
                }
                case "ok_completed": {
                    String okEvt = deltaPrefix
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello world\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":3,\"total_tokens\":8}}}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, new FaultStream(okEvt, null));
                }
                case "valid_failed": {
                    String failEvt = deltaPrefix
                            + "event: response.failed\n"
                            + "data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\",\"status\":\"failed\",\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"upstream rate limited\"}}}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, new FaultStream(failEvt, null));
                }
                case "contradictory_completed_err": {
                    String badCompleted = deltaPrefix
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"error\":{\"code\":\"server_error\",\"message\":\"contradictory failure\"},\"output\":[]}}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, new FaultStream(badCompleted, null));
                }
                default:
                    throw new IOException("unknown mode: " + mode[0]);
            }
        };

        try {
            GatewayServer srv = createGateway(dir, factory);
            try (HttpGateway gw = new HttpGateway(srv)) {
                int port = gw.getListeningPort();
                String chatStreamReq = new JSONObject().put("model", "codex/gpt-5").put("stream", true)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
                String chatSyncReq = new JSONObject().put("model", "codex/gpt-5").put("stream", false)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
                String msgStreamReq = new JSONObject().put("model", "codex/gpt-5").put("stream", true).put("max_tokens", 64)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
                String msgSyncReq = new JSONObject().put("model", "codex/gpt-5").put("stream", false).put("max_tokens", 64)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
                String nativeRespStream = new JSONObject().put("model", "codex/gpt-5").put("stream", true)
                        .put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
                String nativeRespSync = new JSONObject().put("model", "codex/gpt-5").put("stream", false)
                        .put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();

                // 1) Chat stream=true TLS break -> tls_error, tlsError=true, lastSseEvent=response.output_text.delta, terminalSeen=false, NO replay
                mode[0] = "tls"; hits.set(0);
                Reply cTls = call(port, "/v1/chat/completions", "GW-TEST-KEY-777", chatStreamReq);
                check(hits.get() == 1 && cTls.body.contains("TLS"), "Chat TLS break failed: hits=" + hits.get() + " body=" + cTls.body);
                JSONObject r1 = rowByRequestId(port, cTls.requestId);
                check("tls_error".equals(r1.optString("failureReason")) && r1.optBoolean("tlsError") && !r1.optBoolean("upstreamExplicitError")
                        && "response.output_text.delta".equals(r1.optString("lastSseEvent")) && Boolean.FALSE.equals(r1.opt("terminalSeen"))
                        && r1.optInt("upstreamStatus") == 200, "Chat stream TLS row mismatch: " + r1);

                // 2) Messages stream=true TLS break & read_timeout -> exact reason & lastSseEvent, NOT "error" / upstream_explicit_error
                mode[0] = "tls"; hits.set(0);
                Reply mTls = call(port, "/v1/messages", "GW-TEST-KEY-777", msgStreamReq);
                check(hits.get() == 1 && mTls.body.contains("error"), "Messages TLS break failed: " + mTls.body);
                JSONObject r2 = rowByRequestId(port, mTls.requestId);
                check("tls_error".equals(r2.optString("failureReason")) && r2.optBoolean("tlsError")
                        && "response.output_text.delta".equals(r2.optString("lastSseEvent")) && Boolean.FALSE.equals(r2.opt("terminalSeen")),
                        "Messages stream TLS row mismatch: " + r2);

                mode[0] = "timeout"; hits.set(0);
                Reply mTo = call(port, "/v1/messages", "GW-TEST-KEY-777", msgStreamReq);
                JSONObject r2t = rowByRequestId(port, mTo.requestId);
                check(hits.get() == 1 && "read_timeout".equals(r2t.optString("failureReason")) && r2t.optBoolean("readTimeout")
                        && "response.output_text.delta".equals(r2t.optString("lastSseEvent")),
                        "Messages stream timeout mismatch: " + r2t);

                // 3) Native Responses stream=true TLS + stream=false conv_invalid
                mode[0] = "tls"; hits.set(0);
                Reply nTls = call(port, "/v1/responses", "GW-TEST-KEY-777", nativeRespStream);
                check(hits.get() == 1 && nTls.body.contains("response.failed"), "Native stream TLS failed: " + nTls.body);
                JSONObject r3 = rowByRequestId(port, nTls.requestId);
                check("tls_error".equals(r3.optString("failureReason")) && r3.optBoolean("tlsError")
                        && "response.output_text.delta".equals(r3.optString("lastSseEvent")),
                        "Native stream TLS mismatch: " + r3);

                mode[0] = "conv_invalid"; hits.set(0);
                Reply nConv = call(port, "/v1/responses", "GW-TEST-KEY-777", nativeRespSync);
                check(hits.get() == 1 && nConv.status >= 400, "Native sync conv_invalid status=" + nConv.status);
                JSONObject r3c = rowByRequestId(port, nConv.requestId);
                check("conversion_validation_failed".equals(r3c.optString("failureReason")) && r3c.optBoolean("conversionValidationFailed")
                        && "response.completed".equals(r3c.optString("lastSseEvent")),
                        "Native sync conv_invalid mismatch: " + r3c);

                // 4) Non-streaming (stream=false) Codex SSE aggregation paths (Chat + Messages): TLS, timeout, EOF, explicit error+secrets, conv_invalid
                for (String path : new String[]{"/v1/chat/completions", "/v1/messages"}) {
                    String reqBody = "/v1/messages".equals(path) ? msgSyncReq : chatSyncReq;

                    mode[0] = "tls"; hits.set(0);
                    Reply rt = call(port, path, "GW-TEST-KEY-777", reqBody);
                    check(hits.get() == 1 && rt.status >= 400, path + " sync tls status=" + rt.status);
                    JSONObject rowTls = rowByRequestId(port, rt.requestId);
                    check("tls_error".equals(rowTls.optString("failureReason")) && rowTls.optBoolean("tlsError")
                            && "response.output_text.delta".equals(rowTls.optString("lastSseEvent")) && Boolean.FALSE.equals(rowTls.opt("terminalSeen"))
                            && rowTls.optInt("upstreamStatus") == 200, path + " sync TLS row=" + rowTls);

                    mode[0] = "timeout"; hits.set(0);
                    Reply rto = call(port, path, "GW-TEST-KEY-777", reqBody);
                    check(hits.get() == 1 && rto.status >= 400, path + " sync timeout status=" + rto.status);
                    JSONObject rowTo = rowByRequestId(port, rto.requestId);
                    check("read_timeout".equals(rowTo.optString("failureReason")) && rowTo.optBoolean("readTimeout"),
                            path + " sync timeout row=" + rowTo);

                    mode[0] = "eof"; hits.set(0);
                    Reply reof = call(port, path, "GW-TEST-KEY-777", reqBody);
                    check(hits.get() == 1 && reof.status >= 400, path + " sync eof status=" + reof.status);
                    JSONObject rowEof = rowByRequestId(port, reof.requestId);
                    check("premature_eof".equals(rowEof.optString("failureReason")) && rowEof.optBoolean("prematureEof")
                            && "response.output_text.delta".equals(rowEof.optString("lastSseEvent")),
                            path + " sync eof row=" + rowEof);

                    mode[0] = "conv_invalid"; hits.set(0);
                    Reply rcv = call(port, path, "GW-TEST-KEY-777", reqBody);
                    check(hits.get() == 1 && rcv.status >= 400, path + " sync conv status=" + rcv.status);
                    JSONObject rowCv = rowByRequestId(port, rcv.requestId);
                    check("conversion_validation_failed".equals(rowCv.optString("failureReason")) && rowCv.optBoolean("conversionValidationFailed"),
                            path + " sync conv row=" + rowCv);
                }

                // 5) Secret redaction across Codex SSE explicit error & HTTP 400 error
                mode[0] = "explicit_err_secret"; hits.set(0);
                Reply rSseSec = call(port, "/v1/chat/completions", "GW-TEST-KEY-777", chatSyncReq);
                check(hits.get() == 1 && rSseSec.status >= 400, "SSE explicit error status=" + rSseSec.status);
                JSONObject rowSecSse = rowByRequestId(port, rSseSec.requestId);
                check("upstream_explicit_error".equals(rowSecSse.optString("failureReason")) && rowSecSse.optBoolean("upstreamExplicitError")
                        && "response.failed".equals(rowSecSse.optString("lastSseEvent"))
                        && Boolean.TRUE.equals(rowSecSse.opt("terminalSeen")),
                        "SSE explicit error telemetry mismatch: " + rowSecSse);

                mode[0] = "http400_secret"; hits.set(0);
                Reply rHttpSec = call(port, "/v1/chat/completions", "GW-TEST-KEY-777", chatSyncReq);
                check(hits.get() == 1 && rHttpSec.status == 400, "HTTP 400 status=" + rHttpSec.status);

                // 6) Success preserves real upstream 'response.completed' (NOT overwritten by converter '[DONE]')
                // and distinguishes conversionStatus / deliveryStatus across Chat, Messages, Native Responses (stream & sync)
                String[] okPaths = {
                    "/v1/chat/completions", "/v1/messages", "/v1/responses",
                    "/v1/chat/completions", "/v1/messages", "/v1/responses"
                };
                String[] okPayloads = {
                    chatStreamReq, msgStreamReq, nativeRespStream,
                    chatSyncReq, msgSyncReq, nativeRespSync
                };
                for (int i = 0; i < okPaths.length; i++) {
                    mode[0] = "ok_completed"; hits.set(0);
                    Reply okR = call(port, okPaths[i], "GW-TEST-KEY-777", okPayloads[i]);
                    check(hits.get() == 1 && okR.status == 200, "ok_completed failed idx=" + i + " st=" + okR.status);
                    JSONObject sr = rowSettledByRequestId(port, okR.requestId);
                    check("response.completed".equals(sr.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(sr.opt("terminalSeen"))
                            && "delivered".equals(sr.optString("deliveryStatus"))
                            && !sr.optString("conversionStatus").isEmpty(),
                            "ok_completed session mismatch idx=" + i + " row=" + sr);
                    String expectedConv = (i == 2 || i == 5) ? "native" : "ok";
                    check(expectedConv.equals(sr.optString("conversionStatus")),
                            "ok_completed conversionStatus mismatch idx=" + i + " row=" + sr);
                    String csvAllOk = UsageLedger.csv(1);
                    String csvLineOk = csvLineForRequestId(csvAllOk, okR.requestId);
                    check(csvLineOk.contains(",200,response.completed,true,,false,false,false,false,false," + expectedConv + ",delivered"),
                            "ok_completed CSV mismatch idx=" + i + " line=" + csvLineOk);
                }

                // 7) Valid response.failed -> terminalSeen=true, lastSseEvent=response.failed, failureReason=upstream_explicit_error, NO replay
                for (int i = 0; i < okPaths.length; i++) {
                    mode[0] = "valid_failed"; hits.set(0);
                    Reply fR = call(port, okPaths[i], "GW-TEST-KEY-777", okPayloads[i]);
                    boolean failedDelivered = fR.status >= 400 || fR.body.contains("rate_limit") || fR.body.contains("failed") || fR.body.contains("error");
                    check(hits.get() == 1 && failedDelivered, "valid_failed not reported as failed idx=" + i + " body=" + fR.body);
                    JSONObject srF = rowByRequestId(port, fR.requestId);
                    check("response.failed".equals(srF.optString("lastSseEvent"))
                            && Boolean.TRUE.equals(srF.opt("terminalSeen"))
                            && "upstream_explicit_error".equals(srF.optString("failureReason"))
                            && srF.optBoolean("upstreamExplicitError")
                            && "failed".equals(srF.optString("deliveryStatus")),
                            "valid_failed session mismatch idx=" + i + " row=" + srF);
                    String csvLineF = csvLineForRequestId(UsageLedger.csv(1), fR.requestId);
                    check(csvLineF.contains(",200,response.failed,true,upstream_explicit_error,false,false,false,true,false,")
                            && csvLineF.endsWith(",failed"),
                            "valid_failed CSV mismatch idx=" + i + " line=" + csvLineF);
                }

                // 8) Contradictory completed + non-null error MUST NEVER return HTTP 200 or mark success
                for (int i = 0; i < okPaths.length; i++) {
                    mode[0] = "contradictory_completed_err"; hits.set(0);
                    Reply cR = call(port, okPaths[i], "GW-TEST-KEY-777", okPayloads[i]);
                    boolean rejected = (i < 3) ? (cR.body.contains("error") || cR.body.contains("failed")) : (cR.status >= 400);
                    check(hits.get() == 1 && rejected, "contradictory completed+error allowed success! idx=" + i + " st=" + cR.status + " body=" + cR.body);
                    JSONObject srC = rowByRequestId(port, cR.requestId);
                    check("conversion_validation_failed".equals(srC.optString("failureReason"))
                            && srC.optBoolean("conversionValidationFailed")
                            && "failed".equals(srC.optString("conversionStatus"))
                            && "failed".equals(srC.optString("deliveryStatus"))
                            && !Boolean.TRUE.equals(srC.opt("terminalSeen")),
                            "contradictory completed+error session mismatch idx=" + i + " row=" + srC);
                    String csvLineC = csvLineForRequestId(UsageLedger.csv(1), cR.requestId);
                    check(csvLineC.contains(",conversion_validation_failed,false,false,false,false,true,failed,failed"),
                            "contradictory completed+error CSV mismatch idx=" + i + " line=" + csvLineC);
                }

                // Verify ALL secrets scrubbed from logs, /api/sessions, and CSV
                String allLogs = String.join("\n", logs);
                Reply allSess = call(port, "/api/sessions?limit=50", null, null);
                String csv = UsageLedger.csv(1);
                for (String sec : new String[]{SYN_ACCESS, SYN_REFRESH, SYN_SK, SYN_JWT}) {
                    check(!allLogs.contains(sec), "Secret leaked in diagnostic logs: " + sec);
                    check(!allSess.body.contains(sec), "Secret leaked in /api/sessions: " + sec);
                    check(!csv.contains(sec), "Secret leaked in CSV export: " + sec);
                }
                check(csv.contains(",tls_error,false,true,false,false,false"), "CSV missing Codex tls_error");
                check(csv.contains(",read_timeout,true,false,false,false,false"), "CSV missing Codex read_timeout");
                check(csv.contains(",premature_eof,false,false,true,false,false"), "CSV missing Codex premature_eof");
                check(csv.contains(",upstream_explicit_error,false,false,false,true,false"), "CSV missing Codex explicit error");
                check(csv.contains(",conversion_validation_failed,false,false,false,false,true"), "CSV missing Codex conversion failure");
            }

            // 6) Restart UsageLedger and verify telemetry survived & zero secrets on reload
            UsageLedger.init(dir.toFile());
            GatewayServer srv2 = createGateway(dir, factory);
            try (HttpGateway gw2 = new HttpGateway(srv2)) {
                Reply reloaded = call(gw2.getListeningPort(), "/api/sessions?limit=50&failedOnly=1", null, null);
                for (String sec : new String[]{SYN_ACCESS, SYN_REFRESH, SYN_SK, SYN_JWT}) {
                    check(!reloaded.body.contains(sec), "Secret survived restart in JSONL/sessions: " + sec);
                }
                JSONArray arr = new JSONObject(reloaded.body).getJSONArray("sessions");
                check(arr.length() >= 10, "expected >=10 persisted Codex rows, got " + arr.length());
            }
            // 7) Comprehensive delivery settlement verification (stream Messages, native Anthropic, non-stream JSON, pre-terminal cancel, last-frame write fail, late callback after freeze)
            verifyDeliverySettlementPaths();
        } finally {
            GatewayServer.diagnosticLogHook = null;
            java.lang.reflect.Method close = UsageLedger.class.getDeclaredMethod("closeWriter");
            close.setAccessible(true);
            close.invoke(null);
            try (java.util.stream.Stream<Path> w = Files.walk(dir)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        System.out.println("CodexDiagnosticsAndRedactionHttpTest PASS checks=" + checks);
    }

    private static final class FailingBodyOutputStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final String triggerSubstring;
        private final boolean failAfterHeadersAtBytes;
        private boolean headersPassed;
        private int bodyBytesSeen;
        FailingBodyOutputStream(String triggerSubstring, boolean failAfterHeadersAtBytes) {
            this.triggerSubstring = triggerSubstring;
            this.failAfterHeadersAtBytes = failAfterHeadersAtBytes;
        }
        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            buf.write(b, off, len);
            String all = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            if (!headersPassed) {
                int idx = all.indexOf("\r\n\r\n");
                if (idx >= 0) {
                    headersPassed = true;
                    bodyBytesSeen = all.length() - (idx + 4);
                }
            } else {
                bodyBytesSeen += len;
            }
            if (headersPassed && triggerSubstring != null) {
                int hdrEnd = all.indexOf("\r\n\r\n");
                String bodyPart = hdrEnd >= 0 ? all.substring(hdrEnd + 4) : "";
                if (bodyPart.contains(triggerSubstring)) {
                    throw new IOException("simulated final frame write failure");
                }
            }
            if (headersPassed && failAfterHeadersAtBytes && bodyBytesSeen > 12) {
                throw new IOException("simulated fixed-length body write failure");
            }
        }
    }

    private static String sendAndCapture(NanoHTTPD.Response resp, OutputStream out) throws Exception {
        java.lang.reflect.Method sendMethod = NanoHTTPD.Response.class.getDeclaredMethod("send", OutputStream.class);
        sendMethod.setAccessible(true);
        try {
            sendMethod.invoke(resp, out);
            return "";
        } catch (java.lang.reflect.InvocationTargetException ite) {
            return ite.getCause() == null ? ite.toString() : ite.getCause().toString();
        }
    }

    private static GatewayServer createDeliveryTestGateway(Path dir, GatewayServer.VendorConnectionFactory factory, String protocol) throws Exception {
        GatewayServer srv = RoutingSetupOfflineTest.server(new JSONObject());
        RoutingAuditOfflineTest.file(srv, dir.resolve("config.json").toFile());
        JSONObject prov = new JSONObject()
                .put("id", "deliv-prov")
                .put("name", "DelivTest")
                .put("protocol", protocol)
                .put("enabled", true)
                .put("baseUrl", "https://api.example.invalid/v1")
                .put("keys", new JSONArray().put("sk-deliv-test-99999"))
                .put("models", new JSONArray().put("m1"));
        JSONObject cfg = new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("port", 0).put("authKey", "GW-TEST-KEY-777"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("resilience", new JSONObject().put("patienceMillis", 0).put("heartbeat", false))
                .put("providers", new JSONArray().put(prov))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("groups", new JSONArray());
        java.lang.reflect.Field cfgField = GatewayServer.class.getDeclaredField("configJson");
        cfgField.setAccessible(true);
        cfgField.set(srv, cfg);
        java.lang.reflect.Field vf = GatewayServer.class.getDeclaredField("vendorConnectionFactory");
        vf.setAccessible(true);
        vf.set(srv, factory);
        srv.initPersistentLiveCounters(dir.toFile());
        return srv;
    }

    private static void verifyDeliverySettlementPaths() throws Exception {
        Path d = Files.createTempDirectory("delivery-settlement-test-");
        try {
            UsageLedger.init(d.toFile());
            String openAiOkSse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello \"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                    + "data: [DONE]\n\n";
            String openAiJson = "{\"id\":\"c2\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"sync ok reply\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}";
            String anthOkSse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n"
                    + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                    + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
                    + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}\n\n"
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

            final String[] pMode = {"openai_sse_ok"};
            java.util.concurrent.CountDownLatch abortGate = new java.util.concurrent.CountDownLatch(1);
            GatewayServer.VendorConnectionFactory f = url -> {
                if ("openai_sse_ok".equals(pMode[0])) return new MockCodexConn(url, 200, new FaultStream(openAiOkSse, null));
                if ("openai_json_ok".equals(pMode[0])) return new MockCodexConn(url, 200, "application/json", new FaultStream(openAiJson, null));
                if ("openai_sse_slow".equals(pMode[0])) {
                    InputStream blocking = new InputStream() {
                        final byte[] first = ("data: {\"id\":\"cs\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"partial\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8);
                        int p = 0;
                        @Override public int read() throws IOException {
                            if (p < first.length) return first[p++] & 255;
                            try { abortGate.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                            throw new IOException("upstream read aborted on close");
                        }
                        @Override public void close() { abortGate.countDown(); }
                    };
                    return new MockCodexConn(url, 200, blocking);
                }
                return new MockCodexConn(url, 200, new FaultStream(anthOkSse, null));
            };

            String msgStreamBody = new JSONObject().put("model", "deliv-prov/m1").put("stream", true).put("max_tokens", 32)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
            String msgSyncBody = new JSONObject().put("model", "deliv-prov/m1").put("stream", false).put("max_tokens", 32)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();

            // A) OpenAI -> Anthropic Messages streaming + Non-streaming JSON
            GatewayServer srv1 = createDeliveryTestGateway(d, f, "openai");
            String ridMsgOk, ridMsgLastFail, ridMsgPreCancel, ridSyncPreSend, ridSyncFail, ridLateFreeze;
            try (HttpGateway gw = new HttpGateway(srv1)) {
                int port = gw.getListeningPort();

                // 1) Normal complete streaming Messages -> terminalSeen=true, conversionStatus=ok, deliveryStatus=delivered
                pMode[0] = "openai_sse_ok";
                Reply rOk = call(port, "/v1/messages", "GW-TEST-KEY-777", msgStreamBody);
                ridMsgOk = rOk.requestId;
                check(rOk.status == 200 && rOk.body.contains("message_stop"), "Messages stream normal failed: " + rOk.body);
                JSONObject rowOk = rowByRequestId(port, ridMsgOk);
                check("ok".equals(rowOk.optString("conversionStatus")) && "delivered".equals(rowOk.optString("deliveryStatus"))
                        && Boolean.TRUE.equals(rowOk.opt("terminalSeen")), "Messages normal delivery mismatch: " + rowOk);

                // 2) Pre-terminal cancel streaming Messages -> terminalSeen=false, conversionStatus=upstream_aborted, deliveryStatus=cancelled, status=499
                pMode[0] = "openai_sse_slow";
                NanoHTTPD.Response respCancel = srv1.serve(ResponsesGatewayOfflineTest.session(msgStreamBody, "GW-TEST-KEY-777", "/v1/messages"));
                ridMsgPreCancel = respCancel.getHeader("X-AllIn-Request-Id");
                String cancelErr = sendAndCapture(respCancel, new FailingBodyOutputStream("partial", false));
                abortGate.countDown();
                check(!cancelErr.isEmpty() || Boolean.FALSE.equals(rowByRequestId(port, ridMsgPreCancel).opt("terminalSeen")),
                        "expected client cancel failure before terminal");
                JSONObject rowCancel = rowByRequestId(port, ridMsgPreCancel);
                check(!Boolean.TRUE.equals(rowCancel.opt("terminalSeen"))
                        && "upstream_aborted".equals(rowCancel.optString("conversionStatus"))
                        && ("cancelled".equals(rowCancel.optString("deliveryStatus")) || "failed".equals(rowCancel.optString("deliveryStatus")))
                        && rowCancel.optInt("statusCode") >= 400, "Messages pre-terminal cancel mismatch: " + rowCancel);

                // 3) Final-frame write failure on streaming Messages (after upstream completed message_stop)
                pMode[0] = "openai_sse_ok";
                NanoHTTPD.Response respLastFail = srv1.serve(ResponsesGatewayOfflineTest.session(msgStreamBody, "GW-TEST-KEY-777", "/v1/messages"));
                ridMsgLastFail = respLastFail.getHeader("X-AllIn-Request-Id");
                String lastErr = sendAndCapture(respLastFail, new FailingBodyOutputStream("message_stop", false));
                JSONObject rowLastFail = rowByRequestId(port, ridMsgLastFail);
                check(Boolean.TRUE.equals(rowLastFail.opt("terminalSeen"))
                        && "ok".equals(rowLastFail.optString("conversionStatus"))
                        && ("failed".equals(rowLastFail.optString("deliveryStatus")) || "cancelled".equals(rowLastFail.optString("deliveryStatus")))
                        && !"delivered".equals(rowLastFail.optString("deliveryStatus"))
                        && "downstream_delivery".equals(rowLastFail.optString("failurePhase")),
                        "Messages final-frame write fail must NOT mark delivered: " + rowLastFail);

                // 4) Non-streaming HTTP 200 BEFORE send() -> deliveryStatus MUST stay "unknown", NEVER "delivered";
                //    after normal send() -> transitions to "delivered"; after failing send() -> transitions to "failed"/"cancelled".
                pMode[0] = "openai_json_ok";
                NanoHTTPD.Response respSyncPre = srv1.serve(ResponsesGatewayOfflineTest.session(msgSyncBody, "GW-TEST-KEY-777", "/v1/messages"));
                ridSyncPreSend = respSyncPre.getHeader("X-AllIn-Request-Id");
                JSONObject rowPreSend = rowByRequestId(port, ridSyncPreSend);
                check("ok".equals(rowPreSend.optString("conversionStatus")) && "unknown".equals(rowPreSend.optString("deliveryStatus")),
                        "Non-streaming BEFORE send() MUST be deliveryStatus=unknown, got: " + rowPreSend);
                ByteArrayOutputStream okSyncOut = new ByteArrayOutputStream();
                check(sendAndCapture(respSyncPre, okSyncOut).isEmpty(), "Non-streaming healthy send() failed");
                JSONObject rowAfterSend = rowByRequestId(port, ridSyncPreSend);
                check("delivered".equals(rowAfterSend.optString("deliveryStatus")),
                        "Non-streaming AFTER healthy send() must become delivered: " + rowAfterSend);

                NanoHTTPD.Response respSyncFail = srv1.serve(ResponsesGatewayOfflineTest.session(msgSyncBody, "GW-TEST-KEY-777", "/v1/messages"));
                ridSyncFail = respSyncFail.getHeader("X-AllIn-Request-Id");
                JSONObject rowSyncPre = rowByRequestId(port, ridSyncFail);
                check("unknown".equals(rowSyncPre.optString("deliveryStatus")),
                        "Non-streaming second req BEFORE send() must be unknown: " + rowSyncPre);
                String syncErr = sendAndCapture(respSyncFail, new FailingBodyOutputStream(null, true));
                JSONObject rowSyncFail = rowByRequestId(port, ridSyncFail);
                check("ok".equals(rowSyncFail.optString("conversionStatus"))
                        && ("failed".equals(rowSyncFail.optString("deliveryStatus")) || "cancelled".equals(rowSyncFail.optString("deliveryStatus")))
                        && !"delivered".equals(rowSyncFail.optString("deliveryStatus"))
                        && "downstream_delivery".equals(rowSyncFail.optString("failurePhase")),
                        "Non-streaming write failure must NOT retain delivered: " + rowSyncFail);

                // 5) Late write failure arriving AFTER RequestDiagnostics.freeze + UsageLedger.record:
                UsageLedger.Row lateRow = new UsageLedger.Row();
                ridLateFreeze = UUID.randomUUID().toString();
                lateRow.requestId = ridLateFreeze;
                lateRow.attempt = 1;
                lateRow.terminal = true;
                lateRow.prov = "deliv-prov";
                lateRow.model = "m1";
                lateRow.req = "deliv-prov/m1";
                lateRow.status = 200;
                lateRow.bridgeStatus = "ok";
                lateRow.deliveryStatus = "unknown";
                lateRow.sseTerminal = Boolean.TRUE;
                lateRow.lastSseEvent = "response.completed";
                RequestDiagnostics.freeze(lateRow);
                UsageLedger.record(lateRow);
                JSONObject beforeLateCb = rowByRequestId(port, ridLateFreeze);
                check("unknown".equals(beforeLateCb.optString("deliveryStatus")), "pre-callback frozen row must be unknown: " + beforeLateCb);
                UsageLedger.settleDelivery(lateRow, false, 502, "Late write failure after freeze",
                        "downstream_delivery", "downstream", "downstream_write_failed");
                JSONObject afterLateCb = rowByRequestId(port, ridLateFreeze);
                check("failed".equals(afterLateCb.optString("deliveryStatus")) && afterLateCb.optInt("statusCode") == 502
                        && "downstream_delivery".equals(afterLateCb.optString("failurePhase")),
                        "late callback after freeze did not update live session: " + afterLateCb);
            }

            // B) Native Anthropic Messages streaming final-frame write failure
            GatewayServer srvNative = createDeliveryTestGateway(d, f, "anthropic");
            String ridNativeLastFail;
            try (HttpGateway gwNative = new HttpGateway(srvNative)) {
                int nPort = gwNative.getListeningPort();
                pMode[0] = "anthropic_sse_ok";
                NanoHTTPD.Response nFail = srvNative.serve(ResponsesGatewayOfflineTest.session(msgStreamBody, "GW-TEST-KEY-777", "/v1/messages"));
                ridNativeLastFail = nFail.getHeader("X-AllIn-Request-Id");
                String nErr = sendAndCapture(nFail, new FailingBodyOutputStream("message_stop", false));
                JSONObject nRow = rowByRequestId(nPort, ridNativeLastFail);
                check(Boolean.TRUE.equals(nRow.opt("terminalSeen"))
                        && "native".equals(nRow.optString("conversionStatus"))
                        && ("failed".equals(nRow.optString("deliveryStatus")) || "cancelled".equals(nRow.optString("deliveryStatus")))
                        && !"delivered".equals(nRow.optString("deliveryStatus")),
                        "Native Anthropic final-frame write fail mismatch: " + nRow);
            }

            // C) Restart UsageLedger and verify disk JSONL reload + CSV match live outcomes cleanly
            String csvBefore = UsageLedger.csv(1);
            check(csvLineForRequestId(csvBefore, ridMsgOk).endsWith(",ok,delivered"), "CSV normal Messages delivered");
            check(csvLineForRequestId(csvBefore, ridMsgLastFail).contains(",ok,") && !csvLineForRequestId(csvBefore, ridMsgLastFail).endsWith(",delivered"),
                    "CSV Messages final-frame fail must NOT end with ,delivered: " + csvLineForRequestId(csvBefore, ridMsgLastFail));
            check(csvLineForRequestId(csvBefore, ridLateFreeze).endsWith(",ok,failed"),
                    "CSV late freeze fail mismatch: " + csvLineForRequestId(csvBefore, ridLateFreeze));

            UsageLedger.init(d.toFile());
            GatewayServer srvReload = createDeliveryTestGateway(d, f, "openai");
            try (HttpGateway gwReload = new HttpGateway(srvReload)) {
                int rPort = gwReload.getListeningPort();
                check("delivered".equals(rowByRequestId(rPort, ridMsgOk).optString("deliveryStatus")), "reloaded normal Messages delivered");
                check("delivered".equals(rowByRequestId(rPort, ridSyncPreSend).optString("deliveryStatus")), "reloaded non-stream delivered");
                check(!"delivered".equals(rowByRequestId(rPort, ridMsgPreCancel).optString("deliveryStatus")), "reloaded pre-terminal cancel not delivered");
                check(!"delivered".equals(rowByRequestId(rPort, ridMsgLastFail).optString("deliveryStatus")), "reloaded last-frame fail not delivered");
                check(!"delivered".equals(rowByRequestId(rPort, ridSyncFail).optString("deliveryStatus")), "reloaded sync write fail not delivered");
                check("failed".equals(rowByRequestId(rPort, ridLateFreeze).optString("deliveryStatus"))
                        && rowByRequestId(rPort, ridLateFreeze).optInt("statusCode") == 502,
                        "reloaded late-freeze callback reverted on disk!");
                check(!"delivered".equals(rowByRequestId(rPort, ridNativeLastFail).optString("deliveryStatus"))
                        && "native".equals(rowByRequestId(rPort, ridNativeLastFail).optString("conversionStatus")),
                        "reloaded native Anthropic last-frame fail mismatch");
            }

            // D) Item 4: UsageLedger.settleDelivery retries failed patch writes without dropping across restarts
            Path dRetry = Files.createTempDirectory("ledger-patch-retry-");
            try {
                UsageLedger.init(dRetry.toFile());
                UsageLedger.Row retryRow = new UsageLedger.Row();
                retryRow.requestId = "patch-retry-1";
                retryRow.attempt = 1;
                retryRow.terminal = true;
                retryRow.prov = "p";
                retryRow.model = "m";
                retryRow.req = "p/m";
                retryRow.status = 200;
                retryRow.bridgeStatus = "ok";
                retryRow.deliveryStatus = "unknown";
                retryRow.t = System.currentTimeMillis();
                UsageLedger.record(retryRow);

                java.lang.reflect.Field writerF = UsageLedger.class.getDeclaredField("writer");
                writerF.setAccessible(true);
                java.io.Writer prevW = (java.io.Writer) writerF.get(null);
                if (prevW != null) prevW.close();
                writerF.set(null, new java.io.OutputStreamWriter(new OutputStream() {
                    @Override public void write(int b) throws IOException { throw new IOException("disk write fail"); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("disk write fail"); }
                    @Override public void flush() throws IOException { throw new IOException("disk flush fail"); }
                }, StandardCharsets.UTF_8));
                UsageLedger.rollbackHook = (file, len) -> {
                    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                        raf.setLength(len);
                        return raf.length() == len;
                    }
                };
                UsageLedger.settleDelivery(retryRow, false, 502, "Downstream fail while disk down",
                        "downstream_delivery", "downstream", "downstream_write_failed");
                check("failed".equals(retryRow.deliveryStatus) && retryRow.status == 502,
                        "Item 4: in-memory settleDelivery must still update row");

                // Restore healthy disk (closeWriter via next openWriter), flush via record/flushPending, then restart
                UsageLedger.rollbackHook = null;
                UsageLedger.flushPending();
                UsageLedger.init(dRetry.toFile());
                JSONArray reloadedRetry = UsageLedger.recentRequests(20);
                JSONObject foundRetry = null;
                for (int i = 0; i < reloadedRetry.length(); i++) {
                    if ("patch-retry-1".equals(reloadedRetry.getJSONObject(i).optString("requestId"))) {
                        foundRetry = reloadedRetry.getJSONObject(i);
                    }
                }
                check(foundRetry != null && "failed".equals(foundRetry.optString("deliveryStatus"))
                        && foundRetry.optInt("statusCode") == 502,
                        "Item 4: retried delivery patch MUST survive restart: " + foundRetry);

                // E) Item 5: > 4096 delivery patches must NOT evict old patches back to "unknown"
                Path dMany = Files.createTempDirectory("ledger-patch-4600-");
                try {
                    UsageLedger.init(dMany.toFile());
                    long baseT = System.currentTimeMillis() - 600000L;
                    int totalRows = 4500;
                    for (int i = 0; i < totalRows; i++) {
                        UsageLedger.Row rr = new UsageLedger.Row();
                        rr.requestId = "many-" + i;
                        rr.attempt = 1;
                        rr.terminal = true;
                        rr.prov = "p";
                        rr.model = "m";
                        rr.req = "p/m";
                        rr.status = 200;
                        rr.bridgeStatus = "ok";
                        rr.deliveryStatus = "unknown";
                        rr.t = baseT + i * 100L;
                        UsageLedger.record(rr);
                        if (i == 0 || i == 10 || i == 2500) {
                            UsageLedger.settleDelivery(rr, false, 502, "old fail " + i,
                                    "downstream_delivery", "downstream", "downstream_write_failed");
                        } else {
                            UsageLedger.settleDelivery(rr, true, 200, null, null, null, null);
                        }
                    }
                    UsageLedger.init(dMany.toFile());
                    // Verify summary counts ALL 3 failures (failed == 3) and CSV retains oldest rows' settled states!
                    JSONObject sum = UsageLedger.summary(1, "provider");
                    check(sum.getJSONObject("totals").optLong("requests") == totalRows
                            && sum.getJSONObject("totals").optLong("failed") == 3L,
                            "Item 5: old failed patches must NOT be evicted in summary! totals=" + sum.getJSONObject("totals"));
                    // Also check recentRequests with provider filter/window covering oldest if limit=1000 vs direct totals/heatmap
                    JSONObject hm = UsageLedger.heatmap();
                    check(hm.getJSONObject("totals").optLong("requests") == totalRows
                            && hm.getJSONObject("totals").optLong("failed") == 3L,
                            "Item 5: old failed patches must NOT be evicted in heatmap! totals=" + hm.getJSONObject("totals"));
                    // Specifically verify the very first 1000 rows via visitRows (totalsSince) & verify delivered old row via summary/csv window
                    String csvMany = UsageLedger.csv(1);
                    check(csvLineForRequestId(csvMany, "many-4499").endsWith(",ok,delivered"),
                            "Item 5: newest row delivered in CSV");
                    // Also test oldest row deliveryStatus directly in a 5000-row scan via recentRequests-equivalent cutoff:
                    // Move newest 3550 out of 1-day window? Easier: put 4500 rows, query oldest with from..through via UsageLedger.summary on narrow window, or verify oldest row via recentRequests if older rows have later t?
                    // Test with oldest rows' timestamps within recent 1000 or direct check: insert 4500 rows with req 0..999 newer or test delivered->no-unknown via temporary file with 4500 patches + oldest row having t newest:
                    UsageLedger.Row newestWaitOldPatch = new UsageLedger.Row();
                    newestWaitOldPatch.requestId = "many-early-patch-newest-row";
                    newestWaitOldPatch.attempt = 1;
                    newestWaitOldPatch.terminal = true;
                    newestWaitOldPatch.prov = "p2";
                    newestWaitOldPatch.model = "m";
                    newestWaitOldPatch.req = "p2/m";
                    newestWaitOldPatch.status = 200;
                    newestWaitOldPatch.bridgeStatus = "ok";
                    newestWaitOldPatch.deliveryStatus = "unknown";
                    newestWaitOldPatch.t = System.currentTimeMillis();
                    UsageLedger.record(newestWaitOldPatch);
                    UsageLedger.settleDelivery(newestWaitOldPatch, true, 200, null, null, null, null);
                    // Now append 4500 MORE patches AFTER that patch!
                    for (int i = totalRows; i < totalRows + 4500; i++) {
                        UsageLedger.Row rr = new UsageLedger.Row();
                        rr.requestId = "many-" + i;
                        rr.attempt = 1;
                        rr.terminal = true;
                        rr.prov = "p";
                        rr.model = "m";
                        rr.req = "p/m";
                        rr.status = 200;
                        rr.bridgeStatus = "ok";
                        rr.deliveryStatus = "unknown";
                        rr.t = baseT + i * 10L;
                        UsageLedger.record(rr);
                        UsageLedger.settleDelivery(rr, true, 200, null, null, null, null);
                    }
                    UsageLedger.init(dMany.toFile());
                    JSONArray p2Rows = UsageLedger.recentRequests(50, false, "p2");
                    check(p2Rows.length() == 1 && "delivered".equals(p2Rows.getJSONObject(0).optString("deliveryStatus")),
                            "Item 5: patch followed by 4500 newer patches must NOT revert to unknown: " + p2Rows);
                } finally {
                    java.lang.reflect.Method closeMany = UsageLedger.class.getDeclaredMethod("closeWriter");
                    closeMany.setAccessible(true);
                    closeMany.invoke(null);
                    try (java.util.stream.Stream<Path> w = Files.walk(dMany)) {
                        w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                    }
                }

                // F) Item 6: downstream_delivery patch MUST NOT overwrite already-recorded upstream failure phase/cause/reason/status
                UsageLedger.init(dRetry.toFile());
                UsageLedger.Row upFailRow = new UsageLedger.Row();
                upFailRow.requestId = "upstream-fail-preserved-1";
                upFailRow.attempt = 1;
                upFailRow.terminal = true;
                upFailRow.prov = "codex";
                upFailRow.model = "gpt-5";
                upFailRow.req = "codex/gpt-5";
                upFailRow.status = 502;
                upFailRow.err = "Upstream TLS abort";
                upFailRow.failurePhase = "read_response_body";
                upFailRow.transportKind = "tls";
                upFailRow.failureReason = "tls_error";
                upFailRow.deliveryStatus = "unknown";
                upFailRow.t = System.currentTimeMillis();
                UsageLedger.record(upFailRow);
                UsageLedger.settleDelivery(upFailRow, false, 499, "Downstream stream cancelled",
                        "downstream_delivery", "cancelled", "downstream_cancelled");
                check(upFailRow.status == 502
                        && "read_response_body".equals(upFailRow.failurePhase)
                        && "tls".equals(upFailRow.transportKind)
                        && "tls_error".equals(upFailRow.failureReason)
                        && "cancelled".equals(upFailRow.deliveryStatus),
                        "Item 6: live upstream failure metadata overwritten by downstream_delivery patch! row=" + upFailRow.failurePhase + "/" + upFailRow.transportKind + "/" + upFailRow.failureReason);
                UsageLedger.init(dRetry.toFile());
                JSONArray reloadedUp = UsageLedger.recentRequests(20);
                JSONObject foundUp = null;
                for (int i = 0; i < reloadedUp.length(); i++) {
                    if ("upstream-fail-preserved-1".equals(reloadedUp.getJSONObject(i).optString("requestId"))) {
                        foundUp = reloadedUp.getJSONObject(i);
                    }
                }
                check(foundUp != null
                        && foundUp.optInt("statusCode") == 502
                        && "read_response_body".equals(foundUp.optString("failurePhase"))
                        && "tls".equals(foundUp.optString("transportKind"))
                        && "tls_error".equals(foundUp.optString("failureReason"))
                        && Boolean.TRUE.equals(foundUp.optBoolean("tlsError")),
                        "Item 6: reloaded upstream TLS failure metadata overwritten on disk! row=" + foundUp);

                // G) #8 & #9: uncertain append rollback failure STILL queues DeliveryPatch + storageState().pendingPatches/complete
                //    AND >4096 colliding-hashCode requestIds preserve every DeliveryPatch across restart
                UsageLedger.init(dRetry.toFile());
                UsageLedger.Row uncPatchRow = new UsageLedger.Row();
                uncPatchRow.requestId = "uncertain-patch-1";
                uncPatchRow.attempt = 1;
                uncPatchRow.terminal = true;
                uncPatchRow.prov = "p";
                uncPatchRow.model = "m";
                uncPatchRow.req = "p/m";
                uncPatchRow.status = 200;
                uncPatchRow.bridgeStatus = "ok";
                uncPatchRow.deliveryStatus = "unknown";
                uncPatchRow.t = System.currentTimeMillis();
                UsageLedger.record(uncPatchRow);
                java.lang.reflect.Field wField = UsageLedger.class.getDeclaredField("writer");
                wField.setAccessible(true);
                java.io.Writer prevWriter = (java.io.Writer) wField.get(null);
                if (prevWriter != null) prevWriter.close();
                wField.set(null, new java.io.OutputStreamWriter(new OutputStream() {
                    @Override public void write(int b) throws IOException { throw new IOException("truncated write"); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("truncated write"); }
                    @Override public void flush() throws IOException { throw new IOException("truncated flush"); }
                }, StandardCharsets.UTF_8));
                UsageLedger.rollbackHook = (file, len) -> false; // simulate rollback failure -> uncertainWrites++
                UsageLedger.settleDelivery(uncPatchRow, false, 502, "Write fail + rollback fail",
                        "downstream_delivery", "downstream", "downstream_write_failed");
                java.lang.reflect.Method stateM = UsageLedger.class.getDeclaredMethod("storageState");
                stateM.setAccessible(true);
                JSONObject stBeforeFlush = (JSONObject) stateM.invoke(null);
                check(stBeforeFlush.optInt("pendingPatches") >= 1 && !stBeforeFlush.optBoolean("complete"),
                        "DeepReview #8: failed rollback on patch MUST retain pendingPatches and mark complete=false: " + stBeforeFlush);
                UsageLedger.rollbackHook = null;
                UsageLedger.flushPending();
                JSONObject stAfterFlush = (JSONObject) stateM.invoke(null);
                check(stAfterFlush.optInt("pendingPatches") == 0,
                        "DeepReview #8: pendingPatches should drain after flushPending: " + stAfterFlush);
                UsageLedger.init(dRetry.toFile());
                boolean foundUncPatch = false;
                JSONArray uncList = UsageLedger.recentRequests(50);
                for (int i = 0; i < uncList.length(); i++) {
                    JSONObject item = uncList.getJSONObject(i);
                    if ("uncertain-patch-1".equals(item.optString("requestId"))) {
                        foundUncPatch = "failed".equals(item.optString("deliveryStatus")) && item.optInt("statusCode") == 502;
                    }
                }
                check(foundUncPatch, "DeepReview #8: DeliveryPatch lost after rollback-fail restart!");

                // #9: 4200 keys with IDENTICAL String.hashCode() (Aa/BB 13-pair = 8192 possibilities, same hashCode)
                Path dHash = Files.createTempDirectory("ledger-hash-collide-");
                try {
                    UsageLedger.init(dHash.toFile());
                    long tBase = System.currentTimeMillis() - 300000L;
                    int expectedHash = 0;
                    for (int i = 0; i < 4200; i++) {
                        StringBuilder keySb = new StringBuilder();
                        for (int b = 0; b < 13; b++) keySb.append(((i >> b) & 1) == 0 ? "Aa" : "BB");
                        String kId = keySb.toString();
                        if (i == 0) expectedHash = kId.hashCode();
                        else check(kId.hashCode() == expectedHash, "Hash fixture mismatch");
                        UsageLedger.Row hr = new UsageLedger.Row();
                        hr.requestId = kId;
                        hr.attempt = 1;
                        hr.terminal = true;
                        hr.prov = (i == 0) ? "collide-first" : "collide-p";
                        hr.model = "m";
                        hr.req = hr.prov + "/m";
                        hr.status = 200;
                        hr.bridgeStatus = "ok";
                        hr.deliveryStatus = "unknown";
                        hr.t = tBase + i * 10L;
                        UsageLedger.record(hr);
                        UsageLedger.settleDelivery(hr, i > 5, i <= 5 ? 502 : 200,
                                i <= 5 ? "collide fail" : null,
                                i <= 5 ? "downstream_delivery" : null,
                                i <= 5 ? "downstream" : null,
                                i <= 5 ? "downstream_write_failed" : null);
                    }
                    UsageLedger.init(dHash.toFile());
                    JSONObject sumHash = UsageLedger.summary(1, "provider");
                    check(sumHash.getJSONObject("totals").optLong("failed") == 6L
                            && sumHash.getJSONObject("totals").optLong("requests") == 4200L,
                            "DeepReview #9: colliding hashCode >4096 lost patches: " + sumHash.getJSONObject("totals"));
                    JSONArray firstArr = UsageLedger.recentRequests(10, false, "collide-first");
                    check(firstArr.length() == 1 && "failed".equals(firstArr.getJSONObject(0).optString("deliveryStatus")),
                            "DeepReview #9: earliest colliding row lost patch: " + firstArr);

                    // #9b: When bucket depth limit is reached and a leaf still overflows, query/export/sessions
                    //      MUST explicitly flag incomplete results while keeping bounded limits untouched
                    Path dOv = Files.createTempDirectory("ledger-depth-overflow-");
                    try {
                        UsageLedger.patchCacheLimit = 4;
                        UsageLedger.patchBucketDepthLimit = 1;
                        UsageLedger.init(dOv.toFile());
                        long ovBase = System.currentTimeMillis() - 60_000L;
                        for (int i = 0; i < 64; i++) {
                            StringBuilder ovKey = new StringBuilder();
                            for (int b = 0; b < 13; b++) ovKey.append(((i >> b) & 1) == 0 ? "Aa" : "BB");
                            UsageLedger.Row orow = new UsageLedger.Row();
                            orow.requestId = ovKey.toString();
                            orow.attempt = 1;
                            orow.terminal = true;
                            orow.prov = "ov-p";
                            orow.model = "m";
                            orow.req = "ov-p/m";
                            orow.status = 200;
                            orow.bridgeStatus = "ok";
                            orow.deliveryStatus = "unknown";
                            orow.t = ovBase + i * 10L;
                            UsageLedger.record(orow);
                            UsageLedger.settleDelivery(orow, true, 200, null, null, null, null);
                        }
                        UsageLedger.init(dOv.toFile());
                        JSONObject ovSummary = UsageLedger.summary(1, "provider");
                        JSONObject ovStorage = ovSummary.getJSONObject("storage");
                        check(!ovStorage.optBoolean("complete", true) && ovStorage.optBoolean("patchBucketOverflow", false)
                                && !ovSummary.optString("warning", "").isEmpty(),
                                "DeepReview #9b summary MUST mark incomplete on bucket depth overflow: " + ovSummary);
                        JSONObject ovHeat = UsageLedger.heatmap();
                        JSONObject ovHeatSt = ovHeat.getJSONObject("storage");
                        check(!ovHeatSt.optBoolean("complete", true) && ovHeatSt.optBoolean("patchBucketOverflow", false)
                                && !ovHeat.optString("warning", "").isEmpty(),
                                "DeepReview #9b heatmap MUST mark incomplete on bucket depth overflow: " + ovHeat);
                        String ovCsv = UsageLedger.csv(1);
                        check(ovCsv.contains("Incomplete patch resolution"),
                                "DeepReview #9b csv MUST note incomplete patch resolution");
                        GatewayServer ovSrv = RoutingSetupOfflineTest.server(new JSONObject().put("setup", new JSONObject().put("done", true)));
                        Reply ovSess = new Reply();
                        NanoHTTPD.Response ovSessResp = ovSrv.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/api/sessions", "GET", "", null));
                        ovSess.status = ovSessResp.getStatus().getRequestStatus();
                        ovSess.body = new String(ovSessResp.getData().readAllBytes(), StandardCharsets.UTF_8);
                        ovSessResp.close();
                        JSONObject ovSessJson = new JSONObject(ovSess.body);
                        check(ovSess.status == 200 && !ovSessJson.optBoolean("storageComplete", true)
                                && ovSessJson.optBoolean("patchBucketOverflow", false)
                                && !ovSessJson.optString("warning", "").isEmpty(),
                                "DeepReview #9b /api/sessions MUST mark incomplete: " + ovSess.body);
                    } finally {
                        UsageLedger.patchCacheLimit = UsageLedger.MAX_PATCH_CACHE;
                        UsageLedger.patchBucketDepthLimit = UsageLedger.MAX_PATCH_BUCKET_DEPTH;
                        java.lang.reflect.Method closeOv = UsageLedger.class.getDeclaredMethod("closeWriter");
                        closeOv.setAccessible(true);
                        closeOv.invoke(null);
                        try (java.util.stream.Stream<Path> w = Files.walk(dOv)) {
                            w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                        }
                    }
                } finally {
                    java.lang.reflect.Method closeH = UsageLedger.class.getDeclaredMethod("closeWriter");
                    closeH.setAccessible(true);
                    closeH.invoke(null);
                    try (java.util.stream.Stream<Path> w = Files.walk(dHash)) {
                        w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                    }
                }

                // H) DeepReview #1, #2, #6, #7: Responses projection settlement, genericAnthropic cancel,
                //    WorkBuddy malformed frame + missing tool id, and Cline 3xx redirect blocked
                verifyDeepReviewEndToEndHttp(dRetry);
            } finally {
                UsageLedger.rollbackHook = null;
                java.lang.reflect.Method closeRetry = UsageLedger.class.getDeclaredMethod("closeWriter");
                closeRetry.setAccessible(true);
                closeRetry.invoke(null);
                try (java.util.stream.Stream<Path> w = Files.walk(dRetry)) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } finally {
            java.lang.reflect.Method close = UsageLedger.class.getDeclaredMethod("closeWriter");
            close.setAccessible(true);
            close.invoke(null);
            try (java.util.stream.Stream<Path> w = Files.walk(d)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    private static void verifyDeepReviewEndToEndHttp(Path scratchDir) throws Exception {
        Path hDir = Files.createTempDirectory(scratchDir, "deep-review-http-");
        UsageLedger.init(hDir.toFile());
        final String[] caseMode = {"resp_chat_sync_bad"};
        final List<HttpURLConnection> seenConns = Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.CountDownLatch hangEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch hangDisconnected = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<RequestContext> liveAnthCtx = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicInteger clineCalls = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        final ThreadLocal<RequestContext> reqCtxTlRef = (ThreadLocal<RequestContext>) ((java.util.concurrent.Callable<Object>) () -> {
            java.lang.reflect.Field f = GatewayServer.class.getDeclaredField("REQUEST_CONTEXT");
            f.setAccessible(true);
            return f.get(null);
        }).call();

        GatewayServer.VendorConnectionFactory vf = urlStr -> {
            switch (caseMode[0]) {
                case "resp_chat_sync_bad": {
                    String badChat = "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"mystery_finish\"}]}";
                    return new MockCodexConn(urlStr, 200, "application/json", new FaultStream(badChat, null));
                }
                case "resp_chat_sync_ok": {
                    String okChat = "{\"id\":\"c2\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok-resp\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}";
                    return new MockCodexConn(urlStr, 200, "application/json", new FaultStream(okChat, null));
                }
                case "resp_chat_stream_bad": {
                    String badSse = "data: {\"id\":\"c3\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}\n\n"
                            + "data: {\"id\":\"c3\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"mystery_finish\"}]}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, "text/event-stream", new FaultStream(badSse, null));
                }
                case "anth_hang": {
                    InputStream hangStream = new InputStream() {
                        volatile boolean closed;
                        @Override public int read() throws IOException { return read(new byte[1], 0, 1); }
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            hangEntered.countDown();
                            try {
                                if (!hangDisconnected.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new IOException("disconnect timeout");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            throw new IOException("socket closed on cancel");
                        }
                        @Override public void close() { closed = true; hangDisconnected.countDown(); }
                    };
                    MockCodexConn conn = new MockCodexConn(urlStr, 200, "application/json", hangStream) {
                        @Override public void disconnect() { super.disconnect(); hangDisconnected.countDown(); }
                    };
                    liveAnthCtx.set(reqCtxTlRef.get());
                    seenConns.add(conn);
                    return conn;
                }
                case "wb_malformed": {
                    String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"first\"}}]}\n\n"
                            + "data: {corrupt-json-midframe\n\n"
                            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, "text/event-stream", new FaultStream(sse, null));
                }
                case "wb_missing_tool_id": {
                    String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}\n\n"
                            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockCodexConn(urlStr, 200, "text/event-stream", new FaultStream(sse, null));
                }
                case "cline_302": {
                    clineCalls.incrementAndGet();
                    return new MockCodexConn(urlStr, 302, "text/plain", new FaultStream("Redirect", null)) {
                        @Override public String getHeaderField(String name) {
                            if ("Location".equalsIgnoreCase(name)) return "https://evil.example/steal";
                            return super.getHeaderField(name);
                        }
                    };
                }
                default:
                    throw new IllegalStateException(caseMode[0]);
            }
        };

        GatewayServer srv = RoutingSetupOfflineTest.server(new JSONObject());
        RoutingAuditOfflineTest.file(srv, hDir.resolve("config.json").toFile());
        JSONObject openai = new JSONObject()
                .put("id", "openai").put("name", "OpenAI").put("protocol", "openai").put("enabled", true)
                .put("baseUrl", "https://api.openai.com/v1").put("keys", new JSONArray().put("sk-test-openai"))
                .put("models", new JSONArray().put("gpt-4o"));
        JSONObject anth = new JSONObject()
                .put("id", "anthropic").put("name", "Anthropic").put("protocol", "anthropic").put("enabled", true)
                .put("baseUrl", "https://api.anthropic.com/v1").put("keys", new JSONArray().put("sk-ant-test"))
                .put("models", new JSONArray().put("claude-sonnet"));
        JSONObject wb = new JSONObject()
                .put("id", "workbuddy").put("name", "WorkBuddy").put("protocol", "workbuddy").put("enabled", true)
                .put("models", new JSONArray().put("glm-5.3"))
                .put("accounts", new JSONArray().put(new JSONObject()
                        .put("id", "wb1").put("type", "oauth").put("access", "wb-acc").put("refresh", "wb-ref")
                        .put("expires", System.currentTimeMillis() + 3600_000L).put("enabled", true)));
        JSONObject cline = new JSONObject()
                .put("id", "cline").put("name", "Cline").put("protocol", "cline").put("enabled", true)
                .put("models", new JSONArray().put("cline/test"))
                .put("accounts", new JSONArray().put(new JSONObject()
                        .put("id", "cl1").put("type", "oauth").put("token", "cline-acc").put("access", "cline-acc").put("refresh", "cline-ref")
                        .put("expires", System.currentTimeMillis() + 3600_000L).put("enabled", true)));
        JSONObject cfg = new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("port", 0).put("authKey", "GW-TEST-KEY-777"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("guard", new JSONObject().put("enabled", true).put("requestTimeoutSeconds", 15).put("connectTimeoutSeconds", 10))
                .put("providers", new JSONArray().put(openai).put(anth).put(wb).put(cline))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("groups", new JSONArray());
        java.lang.reflect.Field cfgField = GatewayServer.class.getDeclaredField("configJson");
        cfgField.setAccessible(true);
        cfgField.set(srv, cfg);
        java.lang.reflect.Field vfField = GatewayServer.class.getDeclaredField("vendorConnectionFactory");
        vfField.setAccessible(true);
        vfField.set(srv, vf);
        srv.initPersistentLiveCounters(hDir.toFile());

        try (HttpGateway gw = new HttpGateway(srv)) {
            int port = gw.getListeningPort();

            // #1a: Non-streaming Chat -> /v1/responses projection failure MUST record 502 conversion_validation_failed (not 200)
            caseMode[0] = "resp_chat_sync_bad";
            String respSyncChat = "{\"model\":\"openai/gpt-4o\",\"stream\":false,\"input\":\"hi\"}";
            Reply rBadSync = call(port, "/v1/responses", "GW-TEST-KEY-777", respSyncChat);
            check(rBadSync.status == 502, "#1a expected 502 on bad Chat->Responses sync, got " + rBadSync.status);
            JSONObject rBadRow = rowSettledByRequestId(port, rBadSync.requestId);
            check(rBadRow.optInt("statusCode") == 502 && rBadRow.optInt("upstreamStatus") == 200
                    && "failed".equals(rBadRow.optString("conversionStatus"))
                    && "conversion".equals(rBadRow.optString("failurePhase"))
                    && "conversion_validation_failed".equals(rBadRow.optString("failureReason")),
                    "#1a ledger row mismatch: " + rBadRow);

            // #1b: Non-streaming Chat -> /v1/responses valid projection preserves DeliveryTrackedStream (reaches delivered)
            caseMode[0] = "resp_chat_sync_ok";
            Reply rOkSync = call(port, "/v1/responses", "GW-TEST-KEY-777", respSyncChat);
            check(rOkSync.status == 200 && rOkSync.body.contains("ok-resp"), "#1b expected 200 projected response: " + rOkSync.body);
            JSONObject rOkRow = rowSettledByRequestId(port, rOkSync.requestId);
            check("ok".equals(rOkRow.optString("conversionStatus"))
                    && "delivered".equals(rOkRow.optString("deliveryStatus")),
                    "#1b expected deliveryStatus=delivered (not unknown!), got: " + rOkRow);

            // #1c: Streaming Chat -> /v1/responses projection failure via CompletionForwardStream sets conversion failure, preserves upstream & delivery
            caseMode[0] = "resp_chat_stream_bad";
            String respStreamChat = "{\"model\":\"openai/gpt-4o\",\"stream\":true,\"input\":\"hi\"}";
            Reply rBadStr = call(port, "/v1/responses", "GW-TEST-KEY-777", respStreamChat);
            check(rBadStr.status == 200 && rBadStr.body.contains("response.failed"), "#1c expected response.failed SSE: " + rBadStr.body);
            JSONObject rBadStrRow = rowSettledByRequestId(port, rBadStr.requestId);
            check("failed".equals(rBadStrRow.optString("conversionStatus"))
                    && "conversion".equals(rBadStrRow.optString("failurePhase"))
                    && "conversion_validation_failed".equals(rBadStrRow.optString("failureReason"))
                    && "delivered".equals(rBadStrRow.optString("deliveryStatus")),
                    "#1c streaming projection failure mismatch: " + rBadStrRow);

            // #2: Generic Anthropic attaches connection + respects guard timeout + disconnects on RequestContext cancel
            caseMode[0] = "anth_hang";
            seenConns.clear();
            liveAnthCtx.set(null);
            Thread worker = new Thread(() -> {
                try {
                    srv.serve(ResponsesGatewayOfflineTest.session("{\"model\":\"anthropic/claude-sonnet\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "GW-TEST-KEY-777", "/v1/chat/completions"));
                } catch (Throwable ignored) {
                }
            });
            worker.start();
            check(hangEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "#2 genericAnthropic read never started");
            check(!seenConns.isEmpty() && seenConns.get(0).getReadTimeout() <= 15000,
                    "#2 readTimeout was hardcoded 15min instead of guard timeout: " + (seenConns.isEmpty() ? -1 : seenConns.get(0).getReadTimeout()));
            check(liveAnthCtx.get() != null, "#2 active RequestContext missing");
            liveAnthCtx.get().cancel("client_closed");
            check(hangDisconnected.await(1, java.util.concurrent.TimeUnit.SECONDS),
                    "#2 RequestContext.cancel did NOT disconnect genericAnthropic connection!");
            worker.join(2000L);
            check(!worker.isAlive(), "#2 worker thread stayed blocked after cancel!");

            // #6a: WorkBuddy non-streaming malformed mid-stream JSON MUST return 502 (not silent 200 truncation)
            caseMode[0] = "wb_malformed";
            Reply wbMal = call(port, "/v1/chat/completions", "GW-TEST-KEY-777",
                    "{\"model\":\"workbuddy/glm-5.3\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            check(wbMal.status == 502, "#6a malformed WorkBuddy intermediate frame must fail 502, got " + wbMal.status);

            // #6b: WorkBuddy non-streaming tool call without id MUST fail 502
            caseMode[0] = "wb_missing_tool_id";
            Reply wbNoId = call(port, "/v1/chat/completions", "GW-TEST-KEY-777",
                    "{\"model\":\"workbuddy/glm-5.3\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            check(wbNoId.status == 502, "#6b WorkBuddy missing tool id must fail 502, got " + wbNoId.status);

            // #7: Cline 3xx redirect MUST NOT be followed and MUST return 502 once without replay
            caseMode[0] = "cline_302";
            clineCalls.set(0);
            Reply clRedir = call(port, "/v1/chat/completions", "GW-TEST-KEY-777",
                    "{\"model\":\"cline/cline/test\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            check(clRedir.status == 502 && clineCalls.get() == 1,
                    "#7 Cline 302 must fail 502 cleanly in 1 attempt, got status=" + clRedir.status + " calls=" + clineCalls.get() + " body=" + clRedir.body);
        }
    }
}
