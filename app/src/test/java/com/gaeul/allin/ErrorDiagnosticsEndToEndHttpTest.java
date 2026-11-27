package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Targeted regression verifying actionable error diagnostics & call-detail logging:
 * 1) Non-Antigravity providers populate CallEvidence (sentModel, physicalSends, upstreamStatus).
 * 2) Structured JSON errors unpack cleanly without leaking credentials/prompts.
 * 3) Streaming inline SSE error frames and premature EOF preserve concrete phase/transport/frames.
 * 4) Connection refused/network failures record exact phase + transport cause.
 * 5) failedOnly + provider filtering in /api/sessions finds historical failures past healthy traffic.
 * 6) Pre-dispatch rejections appear in gatewayEvents without inflating UsageLedger totals.
 * 7) Diagnostics survive server restart and respect RequestDiagnostics/CallEvidence whitelists.
 */
public final class ErrorDiagnosticsEndToEndHttpTest {
    private static int checks;

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static final class MockUpstream extends NanoHTTPD implements AutoCloseable {
        volatile String mode = "ok";

        MockUpstream() throws Exception {
            super("127.0.0.1", 0);
            start(SOCKET_READ_TIMEOUT, true);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                if ("json400".equals(mode)) {
                    JSONObject body = new JSONObject().put("error", new JSONObject()
                            .put("type", "invalid_request_error")
                            .put("code", "context_length_exceeded")
                            .put("param", "max_tokens")
                            .put("message", "max_tokens 99999 exceeds 8192 for token SYN-UPSTREAM-SECRET-KEY"));
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", body.toString());
                }
                if ("sse_error".equals(mode)) {
                    String sse = "data: {\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                            + "data: {\"error\":{\"code\":\"overloaded_error\",\"message\":\"backend worker saturated\"}}\n\n";
                    return newChunkedResponse(Response.Status.OK, "text/event-stream",
                            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
                }
                if ("sse_eof".equals(mode)) {
                    String sse = "data: {\"id\":\"s2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"half\"}}]}\n\n";
                    return newFixedLengthResponse(Response.Status.OK, "text/event-stream", sse);
                }
                if ("sse_ok".equals(mode)) {
                    String sse = "data: {\"id\":\"s3\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                            + "data: {\"id\":\"s3\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                            + "data: [DONE]\n\n";
                    return newFixedLengthResponse(Response.Status.OK, "text/event-stream", sse);
                }
                if ("sleep_timeout".equals(mode)) {
                    Thread.sleep(450);
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
                }
                if ("bad_body".equals(mode)) {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "not-valid-json-response");
                }
                JSONObject ok = new JSONObject()
                        .put("id", "ok-1")
                        .put("model", "deepseek-reasoner")
                        .put("choices", new JSONArray().put(new JSONObject()
                                .put("index", 0)
                                .put("message", new JSONObject().put("role", "assistant").put("content", "done"))
                                .put("finish_reason", "stop")))
                        .put("usage", new JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15));
                return newFixedLengthResponse(Response.Status.OK, "application/json", ok.toString());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "mock failed");
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static GatewayServer createGateway(Path dir, int openaiPort, int deadPort) throws Exception {
        GatewayServer srv = RoutingSetupOfflineTest.server(new JSONObject());
        RoutingAuditOfflineTest.file(srv, dir.resolve("config.json").toFile());
        JSONObject pOpenai = new JSONObject()
                .put("id", "openai-test")
                .put("name", "OpenAI Test")
                .put("protocol", "openai")
                .put("enabled", true)
                .put("baseUrl", "http://127.0.0.1:" + openaiPort + "/v1")
                .put("keys", new JSONArray().put("SYN-UPSTREAM-SECRET-KEY"))
                .put("models", new JSONArray().put("deepseek-reasoner"));
        JSONObject pDead = new JSONObject()
                .put("id", "dead-prov")
                .put("name", "Dead Prov")
                .put("protocol", "openai")
                .put("enabled", true)
                .put("baseUrl", "http://127.0.0.1:" + deadPort + "/v1")
                .put("keys", new JSONArray().put("SYN-DEAD-KEY"))
                .put("models", new JSONArray().put("ghost-model"));
        JSONObject pTls = new JSONObject()
                .put("id", "tls-prov")
                .put("name", "TLS Prov")
                .put("protocol", "openai")
                .put("enabled", true)
                .put("baseUrl", "https://127.0.0.1:" + openaiPort + "/v1")
                .put("keys", new JSONArray().put("SYN-TLS-KEY"))
                .put("models", new JSONArray().put("tls-model"));
        JSONObject cfg = new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("port", 0).put("authKey", "FIXTURE-GW-AUTH-123456"))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray().put(pOpenai).put(pDead).put(pTls))
                .put("routing", new JSONObject().put("groupsEnabled", false))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "offgroup").put("strategy", "order")
                        .put("targets", new JSONArray().put("openai-test/deepseek-reasoner"))));
        GatewayServer.class.getDeclaredField("configJson").setAccessible(true);
        java.lang.reflect.Field cfgField = GatewayServer.class.getDeclaredField("configJson");
        cfgField.setAccessible(true);
        cfgField.set(srv, cfg);
        java.lang.reflect.Method initMethod = GatewayServer.class.getDeclaredMethod("initPersistentLiveCounters", java.io.File.class);
        initMethod.setAccessible(true);
        initMethod.invoke(srv, dir.toFile());
        return srv;
    }

    private static final class HttpGateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer inner;

        HttpGateway(GatewayServer inner) throws Exception {
            super("127.0.0.1", 0);
            this.inner = inner;
            start(SOCKET_READ_TIMEOUT, true);
        }

        @Override
        public Response serve(IHTTPSession session) {
            return inner.serve(session);
        }

        @Override
        public void close() {
            inner.stop();
            stop();
        }
    }

    private static final class Reply {
        int status;
        String body;
    }

    private static Reply call(HttpGateway gw, String path, String authKey, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + gw.getListeningPort() + path)
                .openConnection(java.net.Proxy.NO_PROXY);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        if (authKey != null) conn.setRequestProperty("Authorization", "Bearer " + authKey);
        if (path.startsWith("/api/")) conn.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Reply r = new Reply();
        r.status = conn.getResponseCode();
        try (InputStream is = r.status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            r.body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        Path dir = Files.createTempDirectory("err-diag-e2e-");
        UsageLedger.init(dir.toFile());
        int deadPort = unusedPort();
        final java.util.List<String> gatewayLogs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        GatewayServer.diagnosticLogHook = gatewayLogs::add;
        try (MockUpstream upstream = new MockUpstream()) {
            GatewayServer srv1 = createGateway(dir, upstream.getListeningPort(), deadPort);
            try (HttpGateway gw = new HttpGateway(srv1)) {
                // 1) Non-Antigravity HTTP 400 JSON error -> CallEvidence + summarized error + scrubbed secret
                upstream.mode = "json400";
                String chatReq = new JSONObject()
                        .put("model", "openai-test/deepseek-reasoner")
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "SECRET_PROMPT_MARKER")))
                        .toString();
                Reply r400 = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", chatReq);
                check(r400.status == 400, "json400 status=" + r400.status);

                // 2) Streaming SSE clean completion -> upstreamStatus=200, lastSseEvent=[DONE], terminalSeen=true
                upstream.mode = "sse_ok";
                String streamReq = new JSONObject()
                        .put("model", "openai-test/deepseek-reasoner")
                        .put("stream", true)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                        .toString();
                Reply rSseOk = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", streamReq);
                check(rSseOk.status == 200 && rSseOk.body.contains("[DONE]"), "sse_ok delivered");

                // 3) Streaming SSE inline error frame
                upstream.mode = "sse_error";
                Reply rSseErr = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", streamReq);
                check(rSseErr.status == 200 && rSseErr.body.contains("overloaded_error"), "sse_error delivered");

                // 4) Streaming premature EOF without finish_reason
                upstream.mode = "sse_eof";
                Reply rSseEof = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", streamReq);
                check(rSseEof.status == 200, "sse_eof opened");

                // 5) Real SocketTimeoutException (read_timeout)
                srv1.setVendorConnectionsForTests(urlStr -> {
                    HttpURLConnection c = NetworkPolicy.openHttp(urlStr);
                    return new java.net.HttpURLConnection(c.getURL()) {
                        @Override public void disconnect() { c.disconnect(); }
                        @Override public boolean usingProxy() { return c.usingProxy(); }
                        @Override public void connect() throws java.io.IOException { c.connect(); }
                        @Override public void setRequestMethod(String m) throws java.net.ProtocolException { c.setRequestMethod(m); }
                        @Override public void setDoOutput(boolean d) { c.setDoOutput(d); }
                        @Override public void setRequestProperty(String k, String v) { c.setRequestProperty(k, v); }
                        @Override public void setConnectTimeout(int t) { c.setConnectTimeout(1000); }
                        @Override public void setReadTimeout(int t) { c.setReadTimeout(120); }
                        @Override public void setFixedLengthStreamingMode(int l) { c.setFixedLengthStreamingMode(l); }
                        @Override public OutputStream getOutputStream() throws java.io.IOException { return c.getOutputStream(); }
                        @Override public int getResponseCode() throws java.io.IOException { return c.getResponseCode(); }
                        @Override public InputStream getInputStream() throws java.io.IOException { return c.getInputStream(); }
                        @Override public InputStream getErrorStream() { return c.getErrorStream(); }
                        @Override public String getHeaderField(String n) { return c.getHeaderField(n); }
                        @Override public String getContentType() { return c.getContentType(); }
                        @Override public java.net.URL getURL() { return c.getURL(); }
                        @Override public long getContentLengthLong() { return c.getContentLengthLong(); }
                    };
                });
                upstream.mode = "sleep_timeout";
                Reply rTimeout = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", chatReq);
                check(rTimeout.status == 502, "read timeout returns 502, got " + rTimeout.status);
                srv1.setVendorConnectionsForTests(null);

                // 6) Real TLS handshake SSLException -> tls-prov https -> plaintext port
                String tlsReq = new JSONObject()
                        .put("model", "tls-prov/tls-model")
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "tls")))
                        .toString();
                Reply rTls = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", tlsReq);
                check(rTls.status == 502, "tls failure returns 502, got " + rTls.status);

                // 7) Dispatched conversion/validation failure (invalid JSON/Anthropic response -> ProtocolResponseException)
                upstream.mode = "bad_body";
                String anthReq = new JSONObject()
                        .put("model", "openai-test/deepseek-reasoner")
                        .put("max_tokens", 16)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "conv")))
                        .toString();
                Reply rConv = call(gw, "/v1/messages", "FIXTURE-GW-AUTH-123456", anthReq);
                check(rConv.status == 502, "conversion failure returns 502, got " + rConv.status);

                // 8) Connection refused on closed port -> phase=open_connection, transport=refused
                String deadReq = new JSONObject()
                        .put("model", "dead-prov/ghost-model")
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping")))
                        .toString();
                Reply rDead = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", deadReq);
                check(rDead.status == 502, "dead port returns 502, got " + rDead.status);

                // 9) Push 14 healthy calls so errors drop out of latest 12
                upstream.mode = "ok";
                for (int i = 0; i < 14; i++) {
                    Reply ok = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", chatReq);
                    check(ok.status == 200, "healthy call " + i);
                }

                // 10) Pre-dispatch rejections (wrong key 401, broken JSON 400, disabled group 403, unknown prov 404, invalid messages conversion 400)
                Reply badAuth = call(gw, "/v1/chat/completions", "WRONG-KEY-999", chatReq);
                check(badAuth.status == 401, "badAuth=401");
                Reply badJson = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456", "{not-json");
                check(badJson.status == 400, "badJson=400");
                Reply offGroup = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456",
                        new JSONObject().put("model", "group/offgroup")
                                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "x"))).toString());
                check(offGroup.status == 403, "offGroup=403");
                Reply unkProv = call(gw, "/v1/chat/completions", "FIXTURE-GW-AUTH-123456",
                        new JSONObject().put("model", "missing-prov/foo")
                                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "x"))).toString());
                check(unkProv.status == 404, "unkProv=404");
                Reply badMsgConv = call(gw, "/v1/messages", "FIXTURE-GW-AUTH-123456",
                        new JSONObject().put("model", "openai-test/deepseek-reasoner").put("messages", "not-array").toString());
                check(badMsgConv.status == 400, "badMsgConv=400");

                // Wait briefly for stream usage commits
                Thread.sleep(150);

                // Check all-sessions for clean SSE telemetry + failedOnly for every error classification
                Reply allSess = call(gw, "/api/sessions?limit=50", "FIXTURE-GW-AUTH-123456", null);
                check(allSess.status == 200, "sessions all HTTP 200");
                boolean foundSseOk = false;
                JSONArray allList = new JSONObject(allSess.body).getJSONArray("sessions");
                for (int i = 0; i < allList.length(); i++) {
                    JSONObject row = allList.getJSONObject(i);
                    if ("[DONE]".equals(row.optString("lastSseEvent")) && Boolean.TRUE.equals(row.opt("terminalSeen"))
                            && row.optInt("upstreamStatus") == 200 && row.optInt("statusCode") == 200) {
                        foundSseOk = true;
                    }
                }
                check(foundSseOk, "found sse_ok with upstreamStatus=200 lastSseEvent=[DONE] terminalSeen=true");

                Reply filtered = call(gw, "/api/sessions?limit=50&failedOnly=1", "FIXTURE-GW-AUTH-123456", null);
                check(filtered.status == 200, "sessions failedOnly HTTP 200");
                JSONObject resp = new JSONObject(filtered.body);
                String rawJson = resp.toString();
                check(!rawJson.contains("SYN-UPSTREAM-SECRET-KEY") && !rawJson.contains("FIXTURE-GW-AUTH-123456")
                        && !rawJson.contains("SECRET_PROMPT_MARKER"), "zero credential/prompt leak");

                JSONArray failedList = resp.getJSONArray("sessions");
                boolean foundJson400 = false, foundSseErr = false, foundSseEof = false, foundDeadRefused = false,
                        foundReadTimeout = false, foundTls = false, foundConvFail = false;
                for (int i = 0; i < failedList.length(); i++) {
                    JSONObject row = failedList.getJSONObject(i);
                    String prov = row.optString("provider", "");
                    String err = row.optString("error", "");
                    String phase = row.optString("failurePhase", "");
                    String tr = row.optString("transportKind", "");
                    String reason = row.optString("failureReason", "");
                    JSONObject ce = row.optJSONObject("callEvidence");
                    if ("openai-test".equals(prov) && row.optInt("statusCode") == 400) {
                        foundJson400 = true;
                        check(ce != null && "deepseek-reasoner".equals(ce.optString("sentModel"))
                                && ce.optInt("physicalSends") == 1 && "upstream".equals(ce.optString("source"))
                                && ce.optInt("upstreamStatus") == 400, "non-AG CallEvidence populated: " + ce);
                        check(row.optInt("upstreamStatus") == 400 && row.optBoolean("upstreamExplicitError"), "upstreamStatus & upstreamExplicitError for 400: " + row);
                        check("await_response_headers".equals(phase) && "http_rejection".equals(tr),
                                "phase/transport recorded for 400: " + phase + "/" + tr);
                        check("context_length_exceeded".equals(row.optString("upstreamErrorCode")), "error code extracted");
                        check(err.contains("context_length_exceeded") && err.contains("max_tokens")
                                && err.contains("[REDACTED]"), "clean JSON error summary with redacted key: " + err);
                    } else if ("openai-test".equals(prov) && err.contains("overloaded_error")) {
                        foundSseErr = true;
                        check("read_response_body".equals(phase), "sse error phase");
                        check(row.optInt("upstreamStatus") == 200 && "error".equals(row.optString("lastSseEvent"))
                                && Boolean.FALSE.equals(row.opt("terminalSeen"))
                                && "upstream_explicit_error".equals(reason) && row.optBoolean("upstreamExplicitError"),
                                "sse_error telemetry: " + row);
                    } else if ("openai-test".equals(prov) && err.contains("events=1")) {
                        foundSseEof = true;
                        check("read_response_body".equals(phase) && "eof".equals(tr), "sse eof phase/transport: " + row);
                        check(row.optInt("upstreamStatus") == 200 && !row.optString("lastSseEvent").isEmpty()
                                && Boolean.FALSE.equals(row.opt("terminalSeen"))
                                && "premature_eof".equals(reason) && row.optBoolean("prematureEof"),
                                "sse_eof telemetry: " + row);
                    } else if ("openai-test".equals(prov) && "read_timeout".equals(reason)) {
                        foundReadTimeout = true;
                        check(row.optBoolean("readTimeout") && "timeout".equals(tr) && row.optInt("upstreamStatus") == 0,
                                "read_timeout telemetry: " + row);
                    } else if ("tls-prov".equals(prov)) {
                        foundTls = true;
                        check("tls".equals(tr) && "tls_error".equals(reason) && row.optBoolean("tlsError") && row.optInt("upstreamStatus") == 0,
                                "tls_error telemetry: " + row);
                    } else if ("openai-test".equals(prov) && "conversion_validation_failed".equals(reason)) {
                        foundConvFail = true;
                        check(row.optBoolean("conversionValidationFailed") && "protocol".equals(tr),
                                "conversion_validation_failed telemetry: " + row);
                    } else if ("dead-prov".equals(prov) && row.optInt("attempt", 1) > 0) {
                        foundDeadRefused = true;
                        check(("open_request_stream".equals(phase) || "open_connection".equals(phase)) && "refused".equals(tr),
                                "connection refused phase/transport: " + phase + "/" + tr);
                    }
                }
                check(foundJson400, "found json400 row in failedOnly");
                check(foundSseErr, "found sse_error row in failedOnly");
                check(foundSseEof, "found sse_eof row in failedOnly");
                check(foundReadTimeout, "found read_timeout row in failedOnly");
                check(foundTls, "found tls_error row in failedOnly");
                check(foundConvFail, "found conversion_validation_failed row in failedOnly");
                check(foundDeadRefused, "found dead port refused row in failedOnly");

                // Gateway logs check: confirm all telemetry tokens were emitted
                String joinedLogs = String.join("\n", gatewayLogs);
                check(joinedLogs.contains("gateway_stream") && joinedLogs.contains("upstreamStatus=200")
                        && joinedLogs.contains("lastSse=[DONE]") && joinedLogs.contains("terminalSeen=true"),
                        "gateway_stream log missing SSE summary: " + joinedLogs);
                check(joinedLogs.contains("reason=read_timeout"), "gateway_failure missing read_timeout");
                check(joinedLogs.contains("reason=tls_error"), "gateway_failure missing tls_error");
                check(joinedLogs.contains("reason=premature_eof"), "gateway_failure missing premature_eof");
                check(joinedLogs.contains("reason=upstream_explicit_error"), "gateway_failure missing upstream_explicit_error");
                check(joinedLogs.contains("reason=conversion_validation_failed"), "gateway_failure/reject missing conversion_validation_failed");
                check(joinedLogs.contains("gateway_reject") && joinedLogs.contains("reason=conversion_validation_failed"),
                        "gateway_reject missing pre-dispatch conversion_validation_failed");

                // Provider filter check
                Reply deadOnly = call(gw, "/api/sessions?limit=20&failedOnly=1&provider=dead-prov", "FIXTURE-GW-AUTH-123456", null);
                JSONArray deadRows = new JSONObject(deadOnly.body).getJSONArray("sessions");
                check(deadRows.length() >= 1 && "dead-prov".equals(deadRows.getJSONObject(0).optString("provider")),
                        "provider filter returned dead-prov");

                // gatewayEvents recorded pre-dispatch rejections without inflating UsageLedger requests
                JSONArray gwEvents = resp.getJSONArray("gatewayEvents");
                check(gwEvents.length() >= 5, "expected >=5 gatewayEvents, got " + gwEvents.length());
                long[] allTime = UsageLedger.allTimeTotals();
                check(allTime[0] == 22, "pre-dispatch rejections must not enter UsageLedger total (expected 22, got " + allTime[0] + ")");

                // CSV exports extended telemetry columns safely
                String csv = UsageLedger.csv(1);
                check(csv.contains("failure_phase,failure_cause,failure_kind,upstream_error_code,upstream_status,last_sse_event,terminal_seen,failure_reason,read_timeout,tls_error,premature_eof,upstream_explicit_error,conversion_validation_failed"), "CSV extended header: " + csv.split("\n")[0]);
                check(csv.contains(",await_response_headers,http_rejection,request,context_length_exceeded,400,,"), "CSV error columns");
                check(csv.contains(",200,[DONE],true,,false,false,false,false,false"), "CSV sse_ok columns");
                check(csv.contains(",premature_eof,false,false,true,false,false"), "CSV premature_eof columns");
                check(csv.contains(",read_timeout,true,false,false,false,false"), "CSV read_timeout columns");
                check(csv.contains(",tls_error,false,true,false,false,false"), "CSV tls_error columns");
                check(csv.contains(",upstream_explicit_error,false,false,false,true,false"), "CSV upstream_explicit_error columns");
                check(csv.contains(",conversion_validation_failed,false,false,false,false,true"), "CSV conversion_validation_failed columns");
                check(csv.contains(",open_request_stream,refused,") || csv.contains(",open_connection,refused,"), "CSV refused columns");
            }

            // Reopen ledger & restart gateway to confirm error fields survive persistence
            UsageLedger.init(dir.toFile());
            try (HttpGateway gw2 = new HttpGateway(createGateway(dir, upstream.getListeningPort(), deadPort))) {
                Reply afterRestart = call(gw2, "/api/sessions?limit=50&failedOnly=1", "FIXTURE-GW-AUTH-123456", null);
                JSONArray reloaded = new JSONObject(afterRestart.body).getJSONArray("sessions");
                check(reloaded.length() >= 6, "reloaded failed rows survived restart");
                boolean persistedSummary = false, persistedSseEof = false, persistedTimeout = false, persistedTls = false;
                for (int i = 0; i < reloaded.length(); i++) {
                    JSONObject r = reloaded.getJSONObject(i);
                    if (r.optString("error").contains("context_length_exceeded") && r.optInt("upstreamStatus") == 400
                            && r.optBoolean("upstreamExplicitError")) {
                        persistedSummary = true;
                    }
                    if ("premature_eof".equals(r.optString("failureReason")) && Boolean.FALSE.equals(r.opt("terminalSeen"))
                            && r.optInt("upstreamStatus") == 200) {
                        persistedSseEof = true;
                    }
                    if ("read_timeout".equals(r.optString("failureReason")) && r.optBoolean("readTimeout")) {
                        persistedTimeout = true;
                    }
                    if ("tls_error".equals(r.optString("failureReason")) && r.optBoolean("tlsError")) {
                        persistedTls = true;
                    }
                }
                check(persistedSummary && persistedSseEof && persistedTimeout && persistedTls,
                        "new diagnostic telemetry fields survived JSONL roundtrip");
            }
        } finally {
            GatewayServer.diagnosticLogHook = null;
            java.lang.reflect.Method close = UsageLedger.class.getDeclaredMethod("closeWriter");
            close.setAccessible(true);
            close.invoke(null);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
        System.out.println("ErrorDiagnosticsEndToEndHttpTest PASS checks=" + checks);
    }
}
