package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Verifies real-time multi-protocol token/request/privacy counters (Scheme A),
 * persistence across gateway restarts matching UsageLedger totals (Scheme B),
 * zero RequestContext/ConversationHistory retention in UsageLedger cache,
 * consistent early-failure request counting across restarts, and off-hot-path
 * coalesced live-counter persistence.
 */
public final class LiveCountersPersistenceHttpTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static final class ReplyConnection extends HttpURLConnection {
        final byte[] reply;
        final String mime;
        ReplyConnection(String text, String mime) throws IOException {
            super(new URL("http://127.0.0.1/synthetic"));
            this.reply = text.getBytes(StandardCharsets.UTF_8);
            this.mime = mime;
        }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return new ByteArrayInputStream(reply); }
        public String getContentType() { return mime; }
        public String getHeaderField(String name) { return "content-type".equalsIgnoreCase(name) ? mime : null; }
    }

    static String openAiReply(boolean stream, int prompt, int completion) {
        JSONObject msg = new JSONObject().put("role", "assistant").put("content", "OK");
        JSONObject choice = new JSONObject().put("index", 0).put(stream ? "delta" : "message", msg).put("finish_reason", "stop");
        JSONObject usage = new JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion);
        JSONObject body = new JSONObject().put("id", "synthetic-live").put("object", stream ? "chat.completion.chunk" : "chat.completion")
                .put("model", "synthetic").put("choices", new JSONArray().put(choice)).put("usage", usage);
        return stream ? "data: " + body + "\n\ndata: [DONE]\n\n" : body.toString();
    }

    static JSONObject configJson() {
        JSONObject provider = new JSONObject().put("id", "deepseek").put("protocol", "openai").put("enabled", true)
                .put("keys", new JSONArray().put("synthetic-only")).put("models", new JSONArray().put("synthetic"))
                .put("baseUrl", "http://127.0.0.1/synthetic");
        JSONObject agAcc = new JSONObject().put("id", "synthetic-ag").put("email", "fixture@example.invalid")
                .put("access", AntigravityCompatibilityGatewayTest.ACCESS).put("token", AntigravityCompatibilityGatewayTest.ACCESS)
                .put("refresh", AntigravityCompatibilityGatewayTest.REFRESH).put("enabled", true)
                .put("project", "synthetic-project").put("expires", System.currentTimeMillis() + 3600000L);
        JSONObject meta = new JSONObject().put("variantMetadata", new JSONObject().put(AntigravityCompatibilityGatewayTest.MODEL,
                new JSONObject().put("modelEnum", "MODEL_PLACEHOLDER_SYNTHETIC").put("modelProvider", "MODEL_PROVIDER_GOOGLE")));
        JSONObject ag = new JSONObject().put("id", "antigravity").put("protocol", "antigravity")
                .put("models", new JSONArray().put(AntigravityCompatibilityGatewayTest.MODEL))
                .put("modelMeta", new JSONObject().put(AntigravityCompatibilityGatewayTest.MODEL, meta))
                .put("accounts", new JSONArray().put(agAcc));
        JSONObject privacy = new JSONObject().put("enabled", true).put("mode", "smart")
                .put("maskEmails", true);
        JSONObject guard = new JSONObject().put("enabled", true).put("maxContextTokens", 180).put("keepRecent", 1).put("reserveTokens", 0);
        return new JSONObject().put("server", new JSONObject().put("authKey", "SYNTHETIC_INFERENCE"))
                .put("setup", new JSONObject().put("done", true))
                .put("privacy", privacy)
                .put("guard", guard)
                .put("providers", new JSONArray().put(provider).put(ag));
    }

    static GatewayServer createServer(Path dir, GatewayServer.VendorConnectionFactory factory, AntigravityAuth.Transport agWire) throws Exception {
        GatewayServer g = RoutingSetupOfflineTest.server(configJson());
        Audit53RegressionTest.field(GatewayServer.class, "totalTokens").set(g, new AtomicLong());
        RoutingAuditOfflineTest.file(g, dir.resolve("config.json").toFile());
        g.setAntigravityTransportForTests(agWire);
        java.lang.reflect.Field f = GatewayServer.class.getDeclaredField("vendorConnectionFactory");
        f.setAccessible(true);
        f.set(g, factory);
        g.initPersistentLiveCounters(dir.toFile());
        return g;
    }

    static JSONObject rawHttpCall(int port, String method, String path, String body) throws Exception {
        long t0 = System.nanoTime();
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod(method);
        String key = path.startsWith("/api/") ? GatewayManagementCredential.get() : "SYNTHETIC_INFERENCE";
        conn.setRequestProperty("Authorization", "Bearer " + key);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        long firstByteMs = -1L;
        String respBody;
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[1024];
                int n = in.read(chunk);
                firstByteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                while (n != -1) {
                    buf.write(chunk, 0, n);
                    n = in.read(chunk);
                }
            }
            respBody = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
        return new JSONObject().put("status", status).put("body", respBody).put("firstByteMs", firstByteMs);
    }

    static String httpCall(int port, String method, String path, String body) throws Exception {
        JSONObject res = rawHttpCall(port, method, path, body);
        int status = res.getInt("status");
        String respBody = res.getString("body");
        check(status == 200, method + " " + path + " expected 200 got " + status + " body=" + respBody);
        return respBody;
    }

    @SuppressWarnings("unchecked")
    static void assertLedgerCacheDropsRequestContext() throws Exception {
        for (java.lang.reflect.Field f : UsageLedger.Row.class.getDeclaredFields()) {
            check(f.getType() != RequestContext.class, "UsageLedger.Row must not reference RequestContext: " + f);
            check(f.getType() != ConversationHistory.Context.class, "UsageLedger.Row must not reference ConversationHistory: " + f);
        }
        java.lang.reflect.Field daysF = UsageLedger.class.getDeclaredField("DAYS");
        daysF.setAccessible(true);
        Map<String, List<UsageLedger.Row>> days = (Map<String, List<UsageLedger.Row>>) daysF.get(null);
        int cachedRows = 0;
        for (List<UsageLedger.Row> bucket : days.values()) {
            for (UsageLedger.Row row : bucket) {
                cachedRows++;
                check(row.liveServer == null, "Cached UsageLedger.Row pinned liveServer");
                check(row.entryCounted == null, "Cached UsageLedger.Row retained entryCounted handle");
            }
        }
        check(cachedRows >= 6, "Expected cached rows in UsageLedger.DAYS, got " + cachedRows);
    }

    static JSONObject postChat(int port, String path, String model, boolean stream, String prompt, int extraHistoryTurns) throws Exception {
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < extraHistoryTurns; i++) {
            msgs.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant")
                    .put("content", "history turn " + i + " repeating filler text to exceed 180 token context limit for guard trim test"));
        }
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        JSONObject req = new JSONObject().put("model", model).put("stream", stream).put("max_tokens", 64);
        if ("/v1/responses".equals(path)) req.put("input", prompt);
        else req.put("messages", msgs);
        String reply = httpCall(port, "POST", path, req.toString());
        check(reply.contains("OK"), path + " missing OK for " + model + " stream=" + stream + ": " + reply);
        return new JSONObject(httpCall(port, "GET", "/api/status", null));
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("live-counters-http-");
        try {
            UsageLedger.init(dir.toFile());
            final String[] nextOpenAi = {openAiReply(false, 120, 30), "application/json"};
            GatewayServer.VendorConnectionFactory openAiFactory = url -> new ReplyConnection(nextOpenAi[0], nextOpenAi[1]);
            AntigravityCompatibilityGatewayTest.Wire agWire = new AntigravityCompatibilityGatewayTest.Wire(CacheAccountingRegressionTest.google(0, true));
            agWire.status = 200;

            GatewayServer g1 = createServer(dir, openAiFactory, agWire);
            NanoHTTPD httpd1 = new NanoHTTPD("127.0.0.1", 0) {
                public Response serve(IHTTPSession s) { return g1.serve(s); }
            };
            httpd1.start(5000, true);
            long maskedAfterSession1;
            long droppedAfterSession1;
            try {
                int port1 = httpd1.getListeningPort();
                // 1) Non-stream OpenAI + privacy email + guard trim (6 extra turns + 1 user = 7 > maxMessages 4)
                JSONObject st1 = postChat(port1, "/v1/chat/completions", "deepseek/synthetic", false, "mail alice@example.com", 6);
                check(st1.getLong("totalRequests") == 1, "req1 count=" + st1);
                check(st1.getLong("totalTokens") == 150, "req1 tokens=" + st1);
                check(st1.getLong("totalMasked") >= 1, "req1 masked=" + st1);
                check(st1.getLong("contextDropped") >= 1, "req1 dropped=" + st1);

                // 2) Streaming OpenAI SSE -> real-time token sync (200 + 50 = 250; cum = 400)
                nextOpenAi[0] = openAiReply(true, 200, 50);
                nextOpenAi[1] = "text/event-stream";
                JSONObject st2 = postChat(port1, "/v1/chat/completions", "deepseek/synthetic", true, "stream hello", 0);
                check(st2.getLong("totalRequests") == 2, "req2 count=" + st2);
                check(st2.getLong("totalTokens") == 400, "req2 tokens=" + st2);

                // 3) Streaming /v1/messages (80 + 20 = 100; cum = 500)
                nextOpenAi[0] = openAiReply(true, 80, 20);
                nextOpenAi[1] = "text/event-stream";
                JSONObject st3 = postChat(port1, "/v1/messages", "deepseek/synthetic", true, "anthropic stream", 0);
                check(st3.getLong("totalRequests") == 3, "req3 count=" + st3);
                check(st3.getLong("totalTokens") == 500, "req3 tokens=" + st3);

                // 4) Non-stream /v1/responses (60 + 40 = 100; cum = 600)
                nextOpenAi[0] = openAiReply(false, 60, 40);
                nextOpenAi[1] = "application/json";
                JSONObject st4 = postChat(port1, "/v1/responses", "deepseek/synthetic", false, "responses hello", 0);
                check(st4.getLong("totalRequests") == 4, "req4 count=" + st4);
                check(st4.getLong("totalTokens") == 600, "req4 tokens=" + st4);

                // 5) Non-stream Antigravity (prompt=1000, candidates+thoughts=15 => 1015, NO double count -> 1615)
                JSONObject st5 = postChat(port1, "/v1/messages", "antigravity/" + AntigravityCompatibilityGatewayTest.MODEL, false, "ag non-stream", 0);
                check(st5.getLong("totalRequests") == 5, "req5 count=" + st5);
                check(st5.getLong("totalTokens") == 1615, "req5 tokens (no double count)=" + st5);

                // 6) Stream Antigravity (+1015 => 2630)
                JSONObject st6 = postChat(port1, "/v1/chat/completions", "antigravity/" + AntigravityCompatibilityGatewayTest.MODEL, true, "ag stream", 0);
                check(st6.getLong("totalRequests") == 6, "req6 count=" + st6);
                check(st6.getLong("totalTokens") == 2630, "req6 tokens=" + st6);

                // 7) Early failures (empty body, malformed JSON, bounded-number 400 on non-exempt key) must NOT inflate totalRequests before or after restart.
                JSONObject badEmpty = rawHttpCall(port1, "POST", "/v1/chat/completions", "");
                JSONObject badJson = rawHttpCall(port1, "POST", "/v1/chat/completions", "{broken-json");
                JSONObject badPrivacyNum = rawHttpCall(port1, "POST", "/v1/chat/completions",
                        "{\"model\":\"deepseek/synthetic\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"custom_score\":1e1000}");
                check(badEmpty.getInt("status") == 400, "empty payload status=" + badEmpty);
                check(badJson.getInt("status") == 400, "malformed json status=" + badJson);
                check(badPrivacyNum.getInt("status") == 400, "extreme number privacy status=" + badPrivacyNum);

                JSONObject stAfterEarlyErrs = new JSONObject(httpCall(port1, "GET", "/api/status", null));
                check(stAfterEarlyErrs.getLong("totalRequests") == 6, "early failure inflated totalRequests=" + stAfterEarlyErrs);

                // Verify UsageLedger static cache retains zero RequestContext / ConversationHistory / server references
                assertLedgerCacheDropsRequestContext();

                long[] all = UsageLedger.allTimeTotals();
                check(all[0] == 6 && all[1] == 2630, "allTimeTotals before restart=" + java.util.Arrays.toString(all));
                maskedAfterSession1 = stAfterEarlyErrs.getLong("totalMasked");
                droppedAfterSession1 = stAfterEarlyErrs.getLong("contextDropped");
            } finally {
                httpd1.stop();
                g1.stop();
            }

            // Simulate process / gateway restart reading same filesDir
            UsageLedger.init(dir.toFile());
            nextOpenAi[0] = openAiReply(false, 90, 10);
            nextOpenAi[1] = "application/json";
            GatewayServer g2 = createServer(dir, openAiFactory, agWire);
            NanoHTTPD httpd2 = new NanoHTTPD("127.0.0.1", 0) {
                public Response serve(IHTTPSession s) { return g2.serve(s); }
            };
            httpd2.start(5000, true);
            long maskedAfterSession2;
            try {
                int port2 = httpd2.getListeningPort();
                JSONObject stBoot = new JSONObject(httpCall(port2, "GET", "/api/status", null));
                check(stBoot.getLong("totalRequests") == 6, "restart preserved totalRequests=" + stBoot);
                check(stBoot.getLong("totalTokens") == 2630, "restart preserved totalTokens=" + stBoot);
                check(stBoot.getLong("totalMasked") == maskedAfterSession1, "restart preserved totalMasked=" + stBoot);
                check(stBoot.getLong("contextDropped") == droppedAfterSession1, "restart preserved contextDropped=" + stBoot);

                JSONObject privBoot = new JSONObject(httpCall(port2, "GET", "/api/privacy", null));
                JSONObject livePriv = privBoot.getJSONObject("live");
                check(livePriv.getLong("totalMasked") == maskedAfterSession1, "privacy totalMasked persisted=" + livePriv);
                check(livePriv.getLong("lastAt") > 0, "privacy lastAt persisted=" + livePriv);

                JSONObject quotaBoot = new JSONObject(httpCall(port2, "GET", "/api/quota", null)).getJSONObject("totals");
                check(quotaBoot.getLong("masked") == maskedAfterSession1, "quota masked persisted=" + quotaBoot);
                check(quotaBoot.getLong("contextDropped") == droppedAfterSession1, "quota dropped persisted=" + quotaBoot);
                check(quotaBoot.getLong("requests") == 6 && quotaBoot.getLong("tokens") == 2630, "quota ledger match=" + quotaBoot);

                // Post-restart live request (+100 tokens -> 2730, reqs -> 7)
                JSONObject stAfter = postChat(port2, "/v1/chat/completions", "deepseek/synthetic", false, "after restart", 0);
                check(stAfter.getLong("totalRequests") == 7, "post-restart req count=" + stAfter);
                check(stAfter.getLong("totalTokens") == 2730, "post-restart token count=" + stAfter);

                // 8) Off-hot-path async coalesced persistence: inject 300ms disk fsync delay & fire 10 concurrent SSE requests
                final AtomicInteger diskWrites = new AtomicInteger(0);
                g2.liveCountersWriterHookForTests = (file, content) -> {
                    diskWrites.incrementAndGet();
                    Thread.sleep(300L);
                    AtomicConfigFile.write(file, content);
                };
                nextOpenAi[0] = openAiReply(true, 10, 5);
                nextOpenAi[1] = "text/event-stream";
                ExecutorService burstPool = Executors.newFixedThreadPool(10);
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<JSONObject>> futures = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    final int idx = i;
                    futures.add(burstPool.submit(() -> {
                        startGate.await();
                        JSONObject req = new JSONObject().put("model", "deepseek/synthetic").put("stream", true)
                                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hit user" + idx + "@example.com")));
                        return rawHttpCall(port2, "POST", "/v1/chat/completions", req.toString());
                    }));
                }
                startGate.countDown();
                long maxFirstByteMs = 0L;
                for (Future<JSONObject> f : futures) {
                    JSONObject resp = f.get(5, TimeUnit.SECONDS);
                    check(resp.getInt("status") == 200, "burst req failed: " + resp);
                    maxFirstByteMs = Math.max(maxFirstByteMs, resp.getLong("firstByteMs"));
                }
                burstPool.shutdownNow();
                check(maxFirstByteMs < 220, "Hot-path TTFT blocked on 300ms fsync: maxFirstByteMs=" + maxFirstByteMs);
                // Allow background coalesced writer cycle to settle
                Thread.sleep(600L);
                check(diskWrites.get() <= 4, "Expected coalesced background writes <= 4, got " + diskWrites.get());

                // Steady-state privacy-off zero-hit requests must NOT schedule additional disk writes
                nextOpenAi[0] = openAiReply(false, 10, 5);
                nextOpenAi[1] = "application/json";
                httpCall(port2, "POST", "/api/privacy", "{\"enabled\":false}");
                httpCall(port2, "POST", "/v1/chat/completions", new JSONObject().put("model", "deepseek/synthetic").put("stream", false)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "transition"))).toString());
                Thread.sleep(550L);
                int writesBeforePlain = diskWrites.get();
                for (int i = 0; i < 5; i++) {
                    httpCall(port2, "POST", "/v1/chat/completions", new JSONObject().put("model", "deepseek/synthetic").put("stream", false)
                            .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "plain text " + i))).toString());
                }
                Thread.sleep(300L);
                check(diskWrites.get() == writesBeforePlain, "Privacy-off zero-hit triggered disk writes: before=" + writesBeforePlain + " after=" + diskWrites.get());

                JSONObject stEnd = new JSONObject(httpCall(port2, "GET", "/api/status", null));
                maskedAfterSession2 = stEnd.getLong("totalMasked");
                check(maskedAfterSession2 == maskedAfterSession1 + 10, "expected +10 masked from burst, got " + maskedAfterSession2);
            } finally {
                httpd2.stop();
                g2.stop(); // Synchronous graceful shutdown flush
            }

            // Verify graceful shutdown flushed all coalesced burst counters & survived restart into g3
            UsageLedger.init(dir.toFile());
            GatewayServer g3 = createServer(dir, openAiFactory, agWire);
            NanoHTTPD httpd3 = new NanoHTTPD("127.0.0.1", 0) {
                public Response serve(IHTTPSession s) { return g3.serve(s); }
            };
            httpd3.start(5000, true);
            try {
                int port3 = httpd3.getListeningPort();
                JSONObject stBoot3 = new JSONObject(httpCall(port3, "GET", "/api/status", null));
                check(stBoot3.getLong("totalRequests") == 23, "g3 totalRequests=" + stBoot3);
                check(stBoot3.getLong("totalMasked") == maskedAfterSession2, "g3 recovered masked=" + stBoot3);

                // 9) Finding 4: Verify UsageLedger cache (DAYS) & unpersisted queue hold ZERO references to RequestContext/ConversationHistory/GatewayServer
                // Force one row into unpersisted queue too, then verify object graph of both DAYS and unpersisted!
                RequestContext synthCtx = new RequestContext("openai", null, 5000L);
                synthCtx.conversationHistory = new ConversationHistory.Context(
                        "conv-id", "scope", new JSONArray().put(new JSONObject().put("role", "user").put("content", "RETENTION_CONVO_BODY_MARKER_XYZ")));
                UsageLedger.Row syntheticQueued = new UsageLedger.Row();
                syntheticQueued.t = System.currentTimeMillis();
                syntheticQueued.prov = "deepseek";
                syntheticQueued.model = "synthetic";
                syntheticQueued.status = 502;
                syntheticQueued.requestId = "req-retention-check";
                syntheticQueued.attempt = 1;
                syntheticQueued.terminal = true;
                syntheticQueued.diagnosticObservation = new RequestDiagnostics.Observation(
                        synthCtx.diagnosticScope, synthCtx, false, 1, "deepseek", "synthetic");
                java.lang.reflect.Field ledgerLockField = UsageLedger.class.getDeclaredField("LOCK");
                ledgerLockField.setAccessible(true);
                java.lang.reflect.Field ledgerWriterField = UsageLedger.class.getDeclaredField("writer");
                ledgerWriterField.setAccessible(true);
                java.lang.reflect.Field ledgerBaseDirField = UsageLedger.class.getDeclaredField("baseDir");
                ledgerBaseDirField.setAccessible(true);
                Object savedBase = ledgerBaseDirField.get(null);
                synchronized (ledgerLockField.get(null)) {
                    Writer savedWriter = (Writer) ledgerWriterField.get(null);
                    if (savedWriter != null) savedWriter.close();
                    ledgerWriterField.set(null, null);
                    ledgerBaseDirField.set(null, null); // forces append() to fail cleanly so row enters unpersisted
                    try {
                        UsageLedger.record(syntheticQueued);
                    } finally {
                        ledgerBaseDirField.set(null, savedBase);
                    }
                }
                assertZeroObjectGraphLeakage();
            } finally {
                httpd3.stop();
                g3.stop();
            }

            // 10) Finding 5: Slow background write > 2s await window must NOT overwrite shutdown flush;
            // overlapping active request increments during stop() are also flushed; async write failure recovers with zero new traffic.
            Path d4 = dir.resolve("slow-disk-test");
            Files.createDirectories(d4);
            UsageLedger.init(d4.toFile());
            GatewayServer gSlow = createServer(d4, openAiFactory, agWire);
            CountDownLatch slowWriteEntered = new CountDownLatch(1);
            List<String> writtenPayloads = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean slowFirstUsed = new AtomicBoolean(false);
            gSlow.liveCountersWriterHookForTests = (file, content) -> {
                if (!slowFirstUsed.getAndSet(true)) {
                    slowWriteEntered.countDown();
                    // Exceeds 2000ms awaitTermination window cleanly without holding liveCountersLock
                    try { Thread.sleep(2400L); } catch (InterruptedException ignored) {
                        Thread.sleep(2400L);
                    }
                }
                writtenPayloads.add(content);
                AtomicConfigFile.write(file, content);
            };
            // Trigger first write -> totalMasked = 1
            java.lang.reflect.Method recMask = GatewayServer.class.getDeclaredMethod("recordPrivacyMask", int.class, boolean.class, boolean.class);
            recMask.setAccessible(true);
            recMask.invoke(gSlow, 1, true, true);
            check(slowWriteEntered.await(5, TimeUnit.SECONDS), "slow background write did not enter");
            // While slow write 1 is stuck inside disk I/O, bump to 2 + stop in background, then bump to 3 during stop overlap
            recMask.invoke(gSlow, 1, true, true);
            CountDownLatch stopDone = new CountDownLatch(1);
            Thread stopThread = new Thread(() -> {
                gSlow.stop();
                stopDone.countDown();
            });
            stopThread.start();
            Thread.sleep(150L);
            recMask.invoke(gSlow, 1, true, true); // concurrent active increment while stop waits on slow disk write
            check(stopDone.await(10, TimeUnit.SECONDS), "gSlow.stop did not finish");
            JSONObject slowSaved = new JSONObject(new String(Files.readAllBytes(d4.resolve("live-counters.json")), StandardCharsets.UTF_8));
            check(slowSaved.getLong("totalMasked") == 3, "shutdown flush overwritten or missed overlap! disk=" + slowSaved + " writes=" + writtenPayloads);

            // Async write failure recovery with ZERO new traffic afterward:
            Path dRetry = dir.resolve("retry-no-traffic-test");
            Files.createDirectories(dRetry);
            UsageLedger.init(dRetry.toFile());
            GatewayServer gRetry = createServer(dRetry, openAiFactory, agWire);
            AtomicInteger retryCalls = new AtomicInteger(0);
            gRetry.liveCountersWriterHookForTests = (file, content) -> {
                if (retryCalls.incrementAndGet() == 1) {
                    throw new IOException("simulated transient disk failure");
                }
                AtomicConfigFile.write(file, content);
            };
            recMask.invoke(gRetry, 4, true, true);
            // Zero new requests arrive; wait up to 2s for scheduled retry to recover disk
            long deadline = System.currentTimeMillis() + 2500L;
            boolean recovered = false;
            while (System.currentTimeMillis() < deadline) {
                if (Files.isRegularFile(dRetry.resolve("live-counters.json"))) {
                    JSONObject rec = new JSONObject(new String(Files.readAllBytes(dRetry.resolve("live-counters.json")), StandardCharsets.UTF_8));
                    if (rec.optLong("totalMasked") == 4) { recovered = true; break; }
                }
                Thread.sleep(80L);
            }
            check(recovered && retryCalls.get() >= 2, "failed async write did not recover automatically with zero new traffic, calls=" + retryCalls.get());
            gRetry.stop();

            // 11) Active request updates counters AFTER stop() initiates cancellation & AFTER previous snapshot;
            // stop() must wait for active request completion before finishing final persistence to disk.
            Path dActive = dir.resolve("active-overlap-stop-test");
            Files.createDirectories(dActive);
            UsageLedger.init(dActive.toFile());
            GatewayServer gActive = createServer(dActive, openAiFactory, agWire);
            // Seed initial persisted snapshot (=5)
            recMask.invoke(gActive, 5, true, true);
            long tSeed = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < tSeed && !Files.isRegularFile(dActive.resolve("live-counters.json"))) {
                Thread.sleep(50L);
            }
            check(Files.isRegularFile(dActive.resolve("live-counters.json")), "initial snapshot missing before stop test");

            RequestContext activeReq = new RequestContext("openai", null, 15000L);
            java.lang.reflect.Method regCtx = GatewayServer.class.getDeclaredMethod("registerContext", RequestContext.class);
            regCtx.setAccessible(true);
            java.lang.reflect.Method finCtx = GatewayServer.class.getDeclaredMethod("finishContext", RequestContext.class);
            finCtx.setAccessible(true);
            java.lang.reflect.Method recDrop = GatewayServer.class.getDeclaredMethod("recordContextDropped", int.class);
            recDrop.setAccessible(true);
            check((Boolean) regCtx.invoke(gActive, activeReq), "registerContext failed");

            CountDownLatch workerCanceled = new CountDownLatch(1);
            CountDownLatch workerDone = new CountDownLatch(1);
            Thread activeWorker = new Thread(() -> {
                try {
                    long end = System.currentTimeMillis() + 5000L;
                    while (!activeReq.isCancelled() && System.currentTimeMillis() < end) {
                        Thread.sleep(15L);
                    }
                    workerCanceled.countDown();
                    // Simulate request wrapping up AFTER stop() already sent cancellation & after snapshot #1
                    Thread.sleep(220L);
                    recMask.invoke(gActive, 7, true, true);
                    recDrop.invoke(gActive, 3);
                    finCtx.invoke(gActive, activeReq);
                } catch (Throwable ignored) {
                } finally {
                    workerDone.countDown();
                }
            });
            activeWorker.start();
            gActive.stop();
            check(workerCanceled.getCount() == 0 && workerDone.await(5, TimeUnit.SECONDS), "stop() returned before active worker finished");

            JSONObject afterStopFile = new JSONObject(new String(Files.readAllBytes(dActive.resolve("live-counters.json")), StandardCharsets.UTF_8));
            check(afterStopFile.getLong("totalMasked") == 12 && afterStopFile.getLong("contextDropped") == 3,
                    "active request increment after last snapshot missing from persisted file: " + afterStopFile);
            GatewayServer gReopened = createServer(dActive, openAiFactory, agWire);
            check(gReopened.totalMasked.get() == 12 && gReopened.totalContextDropped.get() == 3,
                    "reopened server missed persisted active counters: masked=" + gReopened.totalMasked.get() + " dropped=" + gReopened.totalContextDropped.get());
            gReopened.stop();

            System.out.println("LiveCountersPersistenceHttpTest PASS checks=" + checks);
        } finally {
            UsageLedger.setHooks(null);
        }
    }

    private static void assertZeroObjectGraphLeakage() throws Exception {
        java.lang.reflect.Field daysField = UsageLedger.class.getDeclaredField("DAYS");
        daysField.setAccessible(true);
        java.lang.reflect.Field unpersistedField = UsageLedger.class.getDeclaredField("unpersisted");
        unpersistedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<UsageLedger.Row>> days = (Map<String, List<UsageLedger.Row>>) daysField.get(null);
        @SuppressWarnings("unchecked")
        List<UsageLedger.Row> unpersisted = (List<UsageLedger.Row>) unpersistedField.get(null);

        List<UsageLedger.Row> allRows = new ArrayList<>();
        for (List<UsageLedger.Row> list : days.values()) allRows.addAll(list);
        check(!allRows.isEmpty(), "expected cached rows in UsageLedger.DAYS");
        check(!unpersisted.isEmpty(), "expected queued row in UsageLedger.unpersisted");
        allRows.addAll(unpersisted);

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Object> queue = new ArrayDeque<>();
        for (UsageLedger.Row row : allRows) {
            check(row.diagnosticObservation == null, "Row kept non-null diagnosticObservation");
            check(row.callObservation == null, "Row kept non-null callObservation");
            check(row.activeSecrets == null, "Row kept non-null activeSecrets");
            check(row.liveServer == null, "Row kept non-null liveServer");
            queue.add(row);
        }
        while (!queue.isEmpty()) {
            Object cur = queue.poll();
            if (cur == null || visited.containsKey(cur)) continue;
            visited.put(cur, Boolean.TRUE);
            Class<?> cls = cur.getClass();
            check(!(cur instanceof RequestContext), "UsageLedger object graph retains RequestContext");
            check(!(cur instanceof ConversationHistory), "UsageLedger object graph retains ConversationHistory");
            check(!(cur instanceof ConversationHistory.Context), "UsageLedger object graph retains ConversationHistory.Context");
            check(!(cur instanceof GatewayServer), "UsageLedger object graph retains GatewayServer");
            check(!(cur instanceof RequestDiagnostics.Observation), "UsageLedger retains RequestDiagnostics.Observation");
            if (cur instanceof String) {
                check(!((String) cur).contains("RETENTION_CONVO_BODY_MARKER_XYZ"), "Conversation text leaked into cached ledger graph");
                continue;
            }
            if (cls.isPrimitive() || cls == Integer.class || cls == Long.class || cls == Boolean.class
                    || cls == Double.class || cls == Float.class || cls == Short.class || cls == Byte.class || cls == Character.class) {
                continue;
            }
            if (cls.isArray()) {
                if (!cls.getComponentType().isPrimitive()) {
                    for (Object elem : (Object[]) cur) if (elem != null && !visited.containsKey(elem)) queue.add(elem);
                }
                continue;
            }
            if (!cls.getName().startsWith("com.gaeul.allin.") && !(cur instanceof Collection) && !(cur instanceof Map)) continue;
            if (cur instanceof Collection) {
                for (Object elem : (Collection<?>) cur) if (elem != null && !visited.containsKey(elem)) queue.add(elem);
            } else if (cur instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) cur).entrySet()) {
                    if (e.getKey() != null && !visited.containsKey(e.getKey())) queue.add(e.getKey());
                    if (e.getValue() != null && !visited.containsKey(e.getValue())) queue.add(e.getValue());
                }
            } else {
                for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object v = f.get(cur);
                        if (v != null && !visited.containsKey(v)) queue.add(v);
                    }
                }
            }
        }
    }
}
