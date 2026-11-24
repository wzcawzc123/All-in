package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.sun.net.httpserver.HttpServer;
import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Regression tests for the code-review fixes. Offline / loopback only. */
public final class CodeReviewFixesOfflineTest {
    private static int checks;

    private static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void testPbkdf2FallbackMatchesJdk() throws Exception {
        char[] password = "allin-backup-密码-2026!".toCharArray();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        int iterations = 10000;
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        byte[] jdkKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        byte[] fallbackKey = EncryptedConfigBackup.pbkdf2HmacSha256(password, salt, iterations, 32);
        check(Arrays.equals(jdkKey, fallbackKey), "PBKDF2-HMAC-SHA256 fallback must match JDK byte-for-byte");
    }

    private static void testPrivacyMaskerModernKeysAndJsonIntegrity() throws Exception {
        PrivacyMasker.KeyProvider key = () -> new byte[32];
        PrivacyMasker.Session session = new PrivacyMasker.Session(key, (k, type, val) ->
                java.security.MessageDigest.getInstance("SHA-256").digest((type + "\0" + val).getBytes(StandardCharsets.UTF_8)));
        PrivacyMasker.Config cfg = new PrivacyMasker.Config();
        String openaiProj = "sk-proj-AbCdEf1234567890_xyz-9876543210";
        String anthropicKey = "sk-ant-api03-AbCdEf1234567890_xyz-9876543210";
        String masked = PrivacyMasker.maskText("keys: " + openaiProj + " and " + anthropicKey, session, cfg);
        check(!masked.contains(openaiProj) && !masked.contains(anthropicKey), "modern sk-proj/sk-ant keys must be masked: " + masked);
        check(PrivacyMasker.unmaskText(masked, session).equals("keys: " + openaiProj + " and " + anthropicKey), "fast-path unmaskText roundtrip");

        // Non-standard key compatibility (longest prefix wins)
        PrivacyMasker.Session customSession = new PrivacyMasker.Session();
        customSession.placeholderToReal.put("{{MASK_X}}", "A");
        customSession.placeholderToReal.put("{{MASK_X}}suffix", "B");
        check(PrivacyMasker.unmaskText("{{MASK_X}}suffix {{MASK_X}}", customSession).equals("B A"), "non-standard unmask compatibility");

        // Structured JSON masking preserves base64 images and protocol fields; unmask handles quotes/backslashes/newlines
        PrivacyMasker.Config quoteCfg = new PrivacyMasker.Config();
        quoteCfg.customPatterns.add("SECRET_\"LINE\"\\\\END");
        PrivacyMasker.Session jsonSession = new PrivacyMasker.Session(key, (k, type, val) ->
                java.security.MessageDigest.getInstance("SHA-256").digest((type + "\0" + val).getBytes(StandardCharsets.UTF_8)));
        String fakeImg = "data:image/png;base64,iVBOR13812345678w0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        JSONObject req = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "phone 13812345678 and SECRET_\"LINE\"\\END"))
                                .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", fakeImg))))));
        GatewayServer.maskJsonValue(req, jsonSession, quoteCfg, null);
        JSONArray parts = req.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        check(fakeImg.equals(parts.getJSONObject(1).getJSONObject("image_url").getString("url")), "base64 image data URI must not be mutated by privacy mask");
        String maskedText = parts.getJSONObject(0).getString("text");
        check(!maskedText.contains("13812345678") && !maskedText.contains("SECRET_"), "user text must be masked");
        JSONObject reply = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("content", maskedText))));
        GatewayServer.unmaskJson(reply, jsonSession);
        JSONObject reparsed = new JSONObject(reply.toString());
        check(reparsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                .equals("phone 13812345678 and SECRET_\"LINE\"\\END"), "unmaskJson must preserve valid JSON when restoring quotes/backslashes");
    }

    private static void testResponsesReasoningTextDeltaStream() throws Exception {
        String raw = ""
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}\n\n"
                + "data: {\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"step 1 \"}\n\n"
                + "data: {\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"step 2\"}\n\n"
                + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"content\":[{\"type\":\"reasoning_text\",\"text\":\"step 1 step 2\"}]}}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"delta\":\"done\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"content\":[{\"type\":\"reasoning_text\",\"text\":\"step 1 step 2\"}]},"
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}}\n\n";
        String chat = readAll(ResponsesBridge.responsesStreamToChat(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), "m"));
        check(chat.contains("step 1 ") && chat.contains("step 2") && chat.contains("done") && chat.contains("data: [DONE]"),
                "reasoning_text.delta stream should complete cleanly: " + chat);
        check(!chat.contains("\"error\""), "reasoning_text.delta must not fail final reconciliation: " + chat);
    }

    private static void testMiddlewareArraySystemAndDeepClone() throws Exception {
        JSONObject mw = new JSONObject()
                .put("systemPrompt", "GLOBAL_RULES")
                .put("systemPromptMode", "prepend")
                .put("paramOverride", new JSONObject()
                        .put("set", new JSONObject().put("extra_body", new JSONObject().put("flag", true)))
                        .put("maxTokensCap", 0));
        JSONObject payload = new JSONObject()
                .put("max_tokens", 1024)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "ORIGINAL_SYS").put("cache_control", new JSONObject().put("type", "ephemeral")))))
                        .put(new JSONObject().put("role", "user").put("content", "hi")));
        Middleware.applySystemPrompt(payload, "openai", mw);
        JSONArray sysContent = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        check(sysContent.length() == 2, "array system content must preserve original blocks");
        check("GLOBAL_RULES".equals(sysContent.getJSONObject(0).getString("text")), "prepended prompt missing");
        check("ORIGINAL_SYS".equals(sysContent.getJSONObject(1).getString("text")), "original system prompt lost");
        check(sysContent.getJSONObject(1).getJSONObject("cache_control").getString("type").equals("ephemeral"), "cache_control lost");

        Middleware.applyParamOverride(payload, "openai", mw);
        check(payload.getInt("max_tokens") == 1024, "maxTokensCap <= 0 must be ignored");
        payload.getJSONObject("extra_body").put("flag", false);
        check(mw.getJSONObject("paramOverride").getJSONObject("set").getJSONObject("extra_body").getBoolean("flag"),
                "mutating payload must not mutate middleware config object");
    }

    private static void testCustomProviderSelfLoopHardening() {
        for (String bad : new String[]{
                "http://127.0.0.1:3425/v1",
                "http://[::1]:3425/v1",
                "http://[0:0:0:0:0:0:0:1]:3425/v1",
                "http://[::ffff:127.0.0.1]:3425/v1"
        }) {
            JSONObject p = CustomProvider.validate(new JSONObject().put("id", "custom-loop").put("name", "Loop")
                    .put("type", "custom-api").put("baseUrl", bad).put("protocol", "openai").put("authMode", "none")
                    .put("enabled", true).put("keys", new JSONArray()).put("models", new JSONArray().put("m"))
                    .put("headers", new JSONObject()));
            check(CustomProvider.self(p, 3425), "self-loop address must be blocked: " + bad);
        }
        // Shorthand/numeric IPv4 forms must be rejected either by base() URI validation or by self()
        for (String shorthand : new String[]{"http://127.1:3425/v1", "http://2130706433:3425/v1", "http://0x7f000001:3425/v1"}) {
            boolean blocked = false;
            try {
                JSONObject p = CustomProvider.validate(new JSONObject().put("id", "custom-loop").put("name", "Loop")
                        .put("type", "custom-api").put("baseUrl", shorthand).put("protocol", "openai").put("authMode", "none")
                        .put("enabled", true).put("keys", new JSONArray()).put("models", new JSONArray().put("m"))
                        .put("headers", new JSONObject()));
                blocked = CustomProvider.self(p, 3425);
            } catch (IllegalArgumentException rejectedByBase) {
                blocked = true;
            }
            check(blocked, "shorthand loopback must be rejected: " + shorthand);
        }
        JSONObject safe = CustomProvider.validate(new JSONObject().put("id", "custom-ext").put("name", "Ext")
                .put("type", "custom-api").put("baseUrl", "https://example.invalid:3425/v1").put("protocol", "openai").put("authMode", "none")
                .put("enabled", true).put("keys", new JSONArray()).put("models", new JSONArray().put("m"))
                .put("headers", new JSONObject()));
        check(!CustomProvider.self(safe, 3425), "external hostname must not be blocked or fail DNS in self()");
    }

    private static void testContextGuardUnlimitedFastPath() {
        JSONObject payload = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "hello world")));
        ContextGuard.Config cfg = new ContextGuard.Config();
        ContextGuard.Result res = ContextGuard.fit(payload, cfg);
        check(res.error == null && !res.changed && !res.budgetExceeded && res.originalBytes > 0 && res.finalBytes == res.originalBytes,
                "unlimited fast path should preserve all metadata");
        JSONObject bad = new JSONObject().put("messages", "not-an-array");
        check("invalid_messages".equals(ContextGuard.fit(bad, cfg).error), "unlimited fast path must still reject invalid messages");
    }

    private static void testHeldWorkersConcurrencyAndEarlyCors() throws Exception {
        // 1. Early 401 on heartbeat route still emits Access-Control-Allow-Origin
        JSONObject cfg = new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("server", new JSONObject().put("authKey", "fixture-auth-key-1234"))
                .put("resilience", new JSONObject().put("heartbeat", true).put("patienceMillis", 0))
                .put("privacy", new JSONObject().put("enabled", false))
                .put("guard", new JSONObject().put("enabled", false))
                .put("providers", new JSONArray());
        GatewayServer gCors = RoutingSetupOfflineTest.server(cfg);
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "127.0.0.1:3425");
        headers.put("origin", "http://127.0.0.1:3425");
        headers.put("authorization", "Bearer wrong-key");
        NanoHTTPD.Response unauth = gCors.serve(GatewayAccessBoundaryOfflineTest.session(
                "127.0.0.1", "/v1/chat/completions", "POST", "{\"model\":\"x\",\"stream\":true,\"messages\":[]}", headers));
        check(unauth.getStatus().getRequestStatus() == 401, "expected 401");
        check("http://127.0.0.1:3425".equals(unauth.getHeader("access-control-allow-origin")),
                "early heartbeat 401 must retain CORS header, got: " + unauth.getHeader("access-control-allow-origin"));
        unauth.close();

        // 2. >4 concurrent streaming requests with heartbeat=true must run concurrently without queueing/503
        int concurrency = 8;
        CountDownLatch allArrived = new CountDownLatch(concurrency);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService upstreamPool = Executors.newCachedThreadPool();
        upstream.setExecutor(upstreamPool);
        upstream.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                allArrived.countDown();
                // Wait until all 8 requests simultaneously reach upstream (proves >4 workers ran concurrently!)
                boolean synced = allArrived.await(5, TimeUnit.SECONDS);
                String sse = synced
                        ? "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK8\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        : "data: {\"error\":{\"message\":\"blocked by thread pool\"}}\n\n";
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                exchange.close();
            }
        });
        upstream.start();
        try {
            JSONObject streamCfg = new JSONObject()
                    .put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", "fixture-auth"))
                    .put("resilience", new JSONObject().put("heartbeat", true).put("patienceMillis", 0))
                    .put("privacy", new JSONObject().put("enabled", false))
                    .put("guard", new JSONObject().put("enabled", false))
                    .put("providers", new JSONArray().put(new JSONObject()
                            .put("id", "paced").put("protocol", "openai")
                            .put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                            .put("models", new JSONArray().put("m1"))
                            .put("keys", new JSONArray().put("fixture-k1"))));
            try (OutboundRoutingHttpTest.Gateway gw = new OutboundRoutingHttpTest.Gateway(streamCfg)) {
                ExecutorService clients = Executors.newFixedThreadPool(concurrency);
                AtomicInteger okCount = new AtomicInteger();
                try {
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < concurrency; i++) {
                        final int idx = i;
                        futures.add(clients.submit(() -> {
                            try {
                                // Stagger body reads slightly so IngressMemoryBudget's 4 active-reader semaphore
                                // is never contended simultaneously, while all 8 streams overlap on heldWorkers.
                                Thread.sleep(idx * 40L);
                                HttpURLConnection c = OutboundRoutingHttpTest.connection(gw, "paced/m1", true, 15000);
                                try {
                                    int code = c.getResponseCode();
                                    String body = readAll(code == 200 ? c.getInputStream() : c.getErrorStream());
                                    if (code == 200 && body.contains("OK8") && body.contains("[DONE]")) {
                                        okCount.incrementAndGet();
                                    } else {
                                        throw new AssertionError("unexpected stream response: " + code + " " + body);
                                    }
                                } finally {
                                    c.disconnect();
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }));
                    }
                    for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
                } finally {
                    clients.shutdownNow();
                }
                check(okCount.get() == concurrency, "all " + concurrency + " concurrent heartbeat streams must succeed simultaneously, got " + okCount.get());
                Field f = GatewayServer.class.getDeclaredField("activeContexts");
                f.setAccessible(true);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < end) {
                    synchronized (gw.inner) {
                        Set<?> active = (Set<?>) f.get(gw.inner);
                        if (active == null || active.isEmpty()) break;
                    }
                    Thread.sleep(20);
                }
                synchronized (gw.inner) {
                    Set<?> active = (Set<?>) f.get(gw.inner);
                    check(active == null || active.isEmpty(), "activeContexts leaked after concurrent held streams");
                }
            }
        } finally {
            upstream.stop(0);
            upstreamPool.shutdownNow();
        }
    }

    private static void testPrivacyMaskJsonContextualExemptions() throws Exception {
        PrivacyMasker.KeyProvider key = () -> new byte[32];
        PrivacyMasker.Session session = new PrivacyMasker.Session(key, (k, type, val) ->
                java.security.MessageDigest.getInstance("SHA-256").digest((type + "\0" + val).getBytes(StandardCharsets.UTF_8)));
        PrivacyMasker.Config cfg = new PrivacyMasker.Config();

        StringBuilder longSecretSb = new StringBuilder("sk-proj-");
        for (int i = 0; i < 140; i++) longSecretSb.append('A');
        String longSecret = longSecretSb.toString();
        String validDataImg = "data:image/png;base64,iVBOR13812345678w0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        String validBase64Body = "iVBOR13812345678w0KGgoAAAANSUhEUg==";

        JSONObject chatReq = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("metadata", new JSONObject()
                        .put("id", "alice@example.com")
                        .put("data", longSecret)
                        .put("role", "13812345678")
                        .put("type", "bob@example.com"))
                .put("messages", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", "data:image/png;base64,plain@example.com"))
                        .put(new JSONObject()
                                .put("role", "assistant")
                                .put("tool_calls", new JSONArray().put(new JSONObject()
                                        .put("id", "call_13812345678")
                                        .put("type", "function")
                                        .put("function", new JSONObject()
                                                .put("name", "lookup")
                                                .put("arguments", "{\"id\":\"eve@example.com\"}")))))
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", new JSONArray()
                                        .put(new JSONObject()
                                                .put("type", "text")
                                                .put("text", "data:image/png;base64,carol@example.com")
                                                .put("data", longSecret))
                                        .put(new JSONObject()
                                                .put("type", "image_url")
                                                .put("image_url", new JSONObject().put("url", "data:image/png;base64,dave@example.com")))
                                        .put(new JSONObject()
                                                .put("type", "image_url")
                                                .put("image_url", new JSONObject().put("url", validDataImg).put("detail", "high")))
                                        .put(new JSONObject()
                                                .put("type", "image")
                                                .put("source", new JSONObject()
                                                        .put("type", "base64")
                                                        .put("media_type", "image/png")
                                                        .put("data", validBase64Body))))));

        GatewayServer.maskJsonValue(chatReq, session, cfg, null);

        // Ordinary metadata fields with protocol-like names MUST be masked
        JSONObject meta = chatReq.getJSONObject("metadata");
        check(!meta.getString("id").contains("alice@example.com"), "metadata.id email must be masked: " + meta.getString("id"));
        check(!meta.getString("data").contains("sk-proj-"), "metadata.data long secret must be masked: " + meta.getString("data"));
        check(!meta.getString("role").contains("13812345678"), "metadata.role phone must be masked: " + meta.getString("role"));
        check(!meta.getString("type").contains("bob@example.com"), "metadata.type email must be masked: " + meta.getString("type"));
        check("alice@example.com".equals(PrivacyMasker.unmaskText(meta.getString("id"), session)), "metadata.id must unmask cleanly");

        // Plain string content & non-image text/forged data URI MUST be masked
        JSONArray msgs = chatReq.getJSONArray("messages");
        check(!msgs.getJSONObject(0).getString("content").contains("plain@example.com"),
                "plain string content starting with data:image/ must still be masked");
        JSONObject toolCall = msgs.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0);
        check("call_13812345678".equals(toolCall.getString("id")), "protocol tool_calls[0].id must be preserved");
        check(!toolCall.getJSONObject("function").getString("arguments").contains("eve@example.com"),
                "tool call arguments must be masked");

        JSONArray parts = msgs.getJSONObject(2).getJSONArray("content");
        check(!parts.getJSONObject(0).getString("text").contains("carol@example.com"), "text part email must be masked");
        check(!parts.getJSONObject(0).getString("data").contains("sk-proj-"), "non-media part data field must be masked");
        check(!parts.getJSONObject(1).getJSONObject("image_url").getString("url").contains("dave@example.com"),
                "forged non-base64 data:image URI must be masked");
        check(validDataImg.equals(parts.getJSONObject(2).getJSONObject("image_url").getString("url")),
                "valid image_url data URI must be preserved");
        check(validBase64Body.equals(parts.getJSONObject(3).getJSONObject("source").getString("data")),
                "valid image.source base64 data must be preserved");
        check("openai/gpt-4o".equals(chatReq.getString("model")), "root model must be preserved");

        // Responses API input[] contextual exemptions
        JSONObject respReq = new JSONObject()
                .put("model", "gpt-5.4")
                .put("metadata", new JSONObject().put("data", longSecret))
                .put("input", new JSONArray().put(new JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", "call_13812345678")
                        .put("output", new JSONArray()
                                .put(new JSONObject().put("type", "input_image").put("image_url", validDataImg))
                                .put(new JSONObject().put("type", "input_text").put("text", "contact frank@example.com")))));
        GatewayServer.maskJsonValue(respReq, session, cfg, null);
        check(!respReq.getJSONObject("metadata").getString("data").contains("sk-proj-"), "responses metadata.data must be masked");
        JSONObject inputItem = respReq.getJSONArray("input").getJSONObject(0);
        check("call_13812345678".equals(inputItem.getString("call_id")), "responses input call_id must be preserved");
        check(validDataImg.equals(inputItem.getJSONArray("output").getJSONObject(0).getString("image_url")),
                "responses input_image data URI must be preserved");
        check(!inputItem.getJSONArray("output").getJSONObject(1).getString("text").contains("frank@example.com"),
                "responses input_text email must be masked");

        // Sensitive JSON keys & numeric values in ordinary data/schema MUST be rejected (aligned with NativeAnthropicPolicy)
        JSONObject emailKeyMeta = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                .put("metadata", new JSONObject().put("alice@example.com", "plain"));
        boolean rejected = false;
        try { GatewayServer.maskJsonValue(emailKeyMeta, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "metadata email JSON key must be rejected");

        JSONObject rootEmailKey = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("leak@example.com", "plain")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")));
        rejected = false;
        try { GatewayServer.maskJsonValue(rootEmailKey, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "root unknown email JSON key must be rejected");

        JSONObject schemaDefaultNum = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                .put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function",
                        new JSONObject().put("name", "lookup").put("parameters",
                                new JSONObject().put("type", "object").put("properties",
                                        new JSONObject().put("phone", new JSONObject().put("type", "integer").put("default", 13812345678L)))))));
        rejected = false;
        try { GatewayServer.maskJsonValue(schemaDefaultNum, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "tool schema numeric default phone must be rejected");

        JSONObject schemaEnumDouble = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                .put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function",
                        new JSONObject().put("name", "lookup").put("parameters",
                                new JSONObject().put("type", "object").put("properties",
                                        new JSONObject().put("phone", new JSONObject().put("type", "number")
                                                .put("enum", new JSONArray().put(13812345678.0d))))))));
        rejected = false;
        try { GatewayServer.maskJsonValue(schemaEnumDouble, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "tool schema numeric enum phone (double) must be rejected");

        JSONObject metaPhoneNum = new JSONObject()
                .put("model", "openai/gpt-4o")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                .put("metadata", new JSONObject().put("phone", 13812345678L));
        rejected = false;
        try { GatewayServer.maskJsonValue(metaPhoneNum, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "metadata numeric phone value must be rejected");

        // Normal protocol numeric parameters & boundary scientific numbers (<=64 expanded chars) must remain allowed
        JSONObject normalNums = new JSONObject("{\"model\":\"openai/gpt-4o\",\"temperature\":0.7,\"top_p\":1.0,\"max_tokens\":4096,\"seed\":42,"
                + "\"metadata\":{\"retryCount\":3,\"ts\":1700000000,\"maxPos\":1e63,\"minNeg\":1e-62},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        GatewayServer.maskJsonValue(normalNums, session, cfg, null);
        check(normalNums.getInt("max_tokens") == 4096 && normalNums.getJSONObject("metadata").getInt("retryCount") == 3,
                "normal protocol and boundary numeric values (1e63 / 1e-62) must not be falsely rejected");

        // Scientific-notation phone must still be detected and rejected
        JSONObject sciPhone = new JSONObject("{\"model\":\"openai/gpt-4o\",\"metadata\":{\"p\":1.3812345678e10},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        rejected = false;
        try { GatewayServer.maskJsonValue(sciPhone, session, cfg, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "scientific-notation phone 1.3812345678e10 must be rejected");

        // Extreme positive/negative exponents & boundary overflow (>64 expanded chars) must fail fast without huge allocation
        String[] extremeLiterals = {"1e64", "1e-63", "1e1000", "1e1000000000", "1e-1000000000", "0e1000000000", "10e2147483640"};
        for (String lit : extremeLiterals) {
            JSONObject ext = new JSONObject("{\"model\":\"openai/gpt-4o\",\"metadata\":{\"v\":" + lit + "},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            rejected = false;
            try { GatewayServer.maskJsonValue(ext, session, cfg, null); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "over-limit scientific literal " + lit + " must be rejected fast without expansion");
        }

        // End-to-end HTTP verification against a LIVE reachable loopback upstream proving actual outbound count == 0 on all rejections
        AtomicInteger upstreamCalls = new AtomicInteger();
        HttpServer liveUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService livePool = Executors.newCachedThreadPool();
        liveUpstream.setExecutor(livePool);
        liveUpstream.createContext("/", exchange -> {
            try {
                upstreamCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] okBody = "{\"id\":\"chat_priv_ok\",\"object\":\"chat.completion\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, okBody.length);
                exchange.getResponseBody().write(okBody);
            } finally {
                exchange.close();
            }
        });
        liveUpstream.start();
        try {
            JSONObject gwCfg = new JSONObject()
                    .put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", "fixture-auth-priv"))
                    .put("privacy", new JSONObject().put("enabled", true).put("maskEmail", true).put("maskPhone", true))
                    .put("guard", new JSONObject().put("enabled", false))
                    .put("providers", new JSONArray().put(new JSONObject()
                            .put("id", "openai").put("protocol", "openai")
                            .put("baseUrl", "http://127.0.0.1:" + liveUpstream.getAddress().getPort() + "/v1")
                            .put("models", new JSONArray().put("gpt-4o")).put("keys", new JSONArray().put("sk-fixture"))));
            GatewayServer gwPriv = RoutingSetupOfflineTest.server(gwCfg);
            gwPriv.setVendorConnectionsForTests(url -> (HttpURLConnection) new URL(url).openConnection());
            Map<String, String> authHeaders = new HashMap<>();
            authHeaders.put("host", "127.0.0.1:3425");
            authHeaders.put("authorization", "Bearer fixture-auth-priv");

            // Baseline: valid request with boundary numbers (1e63 / 1e-62) reaches live upstream (calls == 1)
            String validBody = "{\"model\":\"openai/gpt-4o\",\"temperature\":0.7,\"max_tokens\":64,\"metadata\":{\"ok\":1,\"b1\":1e63,\"b2\":1e-62},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            NanoHTTPD.Response okRes = gwPriv.serve(GatewayAccessBoundaryOfflineTest.session(
                    "127.0.0.1", "/v1/chat/completions", "POST", validBody, authHeaders));
            check(okRes.getStatus().getRequestStatus() == 200 && upstreamCalls.get() == 1,
                    "baseline valid request must reach live upstream once, got status=" + okRes.getStatus().getRequestStatus() + " calls=" + upstreamCalls.get());
            okRes.close();

            int beforeRejects = upstreamCalls.get();

            // 1. Metadata email key -> HTTP 400, 0 outbound calls
            NanoHTTPD.Response chatKeyReject = gwPriv.serve(GatewayAccessBoundaryOfflineTest.session(
                    "127.0.0.1", "/v1/chat/completions", "POST", emailKeyMeta.toString(), authHeaders));
            check(chatKeyReject.getStatus().getRequestStatus() == 400,
                    "Chat ingress must return HTTP 400 on sensitive metadata JSON key, got: " + chatKeyReject.getStatus().getRequestStatus());
            chatKeyReject.close();

            // 2. Responses tool schema numeric enum phone -> HTTP 400, 0 outbound calls
            JSONObject respNumRejectBody = new JSONObject()
                    .put("model", "openai/gpt-4o")
                    .put("input", "hello")
                    .put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("name", "lookup")
                            .put("parameters", new JSONObject().put("type", "object").put("properties",
                                    new JSONObject().put("phone", new JSONObject().put("type", "integer").put("enum", new JSONArray().put(13812345678L)))))));
            NanoHTTPD.Response respNumReject = gwPriv.serve(GatewayAccessBoundaryOfflineTest.session(
                    "127.0.0.1", "/v1/responses", "POST", respNumRejectBody.toString(), authHeaders));
            check(respNumReject.getStatus().getRequestStatus() == 400,
                    "Responses ingress must return HTTP 400 on sensitive numeric enum in tool schema, got: " + respNumReject.getStatus().getRequestStatus());
            respNumReject.close();

            // 3. Extreme positive/negative/overflow scientific numbers via HTTP -> HTTP 400, 0 outbound calls, zero OOM
            for (String lit : new String[]{"1e64", "1e-63", "1e1000", "1e1000000000", "1e-1000000000"}) {
                String chatExt = "{\"model\":\"openai/gpt-4o\",\"metadata\":{\"x\":" + lit + "},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
                NanoHTTPD.Response rChat = gwPriv.serve(GatewayAccessBoundaryOfflineTest.session(
                        "127.0.0.1", "/v1/chat/completions", "POST", chatExt, authHeaders));
                check(rChat.getStatus().getRequestStatus() == 400, "Chat must return 400 for " + lit + ", got " + rChat.getStatus().getRequestStatus());
                rChat.close();

                String respExt = "{\"model\":\"openai/gpt-4o\",\"input\":\"hi\",\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"parameters\":{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"number\",\"default\":" + lit + "}}}}]}";
                NanoHTTPD.Response rResp = gwPriv.serve(GatewayAccessBoundaryOfflineTest.session(
                        "127.0.0.1", "/v1/responses", "POST", respExt, authHeaders));
                check(rResp.getStatus().getRequestStatus() == 400, "Responses must return 400 for " + lit + ", got " + rResp.getStatus().getRequestStatus());
                rResp.close();
            }

            check(upstreamCalls.get() == beforeRejects,
                    "all rejected privacy/extreme-number requests must make 0 outbound calls, got delta=" + (upstreamCalls.get() - beforeRejects));
        } finally {
            liveUpstream.stop(0);
            livePool.shutdownNow();
        }
    }

    private static void testBridgeRequestRegistryFastCancelRaceAndCleanup() throws Exception {
        BridgeRequestRegistry reg = new BridgeRequestRegistry();
        long gen = reg.generation();

        // 1. Fast-cancel startup race: cancelById arrives AFTER register() but BEFORE worker runs
        LocalBridge.Call raceCall = new LocalBridge.Call(10000);
        check(reg.register("b_race", raceCall, gen), "pre-submit register must succeed");
        check(reg.activeCount() == 1 && reg.idCount() == 1, "registry must track pre-submitted call");

        CountDownLatch workerRelease = new CountDownLatch(1);
        AtomicInteger transportStarted = new AtomicInteger();
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = single.submit(() -> {
                workerRelease.await(5, TimeUnit.SECONDS);
                try {
                    if (raceCall.cancelled()) return "cancelled";
                    transportStarted.incrementAndGet();
                    return "started";
                } finally {
                    reg.release("b_race", raceCall);
                }
            });
            // Cancel immediately while worker is still blocked before execution
            check(reg.cancelById("b_race"), "fast cancel before worker start must hit registered call");
            check(raceCall.cancelled(), "registered call must be marked cancelled immediately");
            check(reg.activeCount() == 0 && reg.idCount() == 0, "cancelById must detach entry immediately");

            workerRelease.countDown();
            String outcome = future.get(5, TimeUnit.SECONDS);
            check("cancelled".equals(outcome) && transportStarted.get() == 0,
                    "worker must observe cancellation and never start transport: outcome=" + outcome + ", started=" + transportStarted.get());
            check(reg.activeCount() == 0 && reg.idCount() == 0, "registry must be empty after worker finally release");
        } finally {
            single.shutdownNow();
        }

        // 2. RejectedExecutionException cleanup
        LocalBridge.Call rejCall = new LocalBridge.Call(10000);
        check(reg.register("b_rej", rejCall, gen), "register before rejection");
        reg.release("b_rej", rejCall);
        check(reg.activeCount() == 0 && reg.idCount() == 0, "release after rejection must clear registry");

        // 3. Duplicate reqId overwrite cancels older call; stale release must not evict newer call
        LocalBridge.Call oldCall = new LocalBridge.Call(10000);
        LocalBridge.Call newCall = new LocalBridge.Call(10000);
        check(reg.register("dup", oldCall, gen), "register first dup");
        check(reg.register("dup", newCall, gen), "register second dup");
        check(oldCall.cancelled() && !newCall.cancelled(), "duplicate reqId must cancel previous call only");
        reg.release("dup", oldCall);
        check(reg.activeCount() == 1 && reg.idCount() == 1, "stale release must not evict newer call");
        check(reg.cancelById("dup") && newCall.cancelled(), "cancelById must still cancel newer call");

        // 4. Navigation cancelAll(false) bumps generation; destroy cancelAll(true) permanently blocks register
        LocalBridge.Call navCall = new LocalBridge.Call(10000);
        check(reg.register("nav", navCall, gen), "register before nav");
        reg.cancelAll(false);
        check(navCall.cancelled() && reg.generation() == gen + 1, "cancelAll(false) must cancel and bump generation");
        LocalBridge.Call staleGenCall = new LocalBridge.Call(10000);
        check(!reg.register("stale", staleGenCall, gen) && staleGenCall.cancelled(), "stale generation must be rejected");
        LocalBridge.Call freshCall = new LocalBridge.Call(10000);
        check(reg.register("fresh", freshCall, gen + 1), "fresh generation must succeed");
        reg.cancelAll(true);
        check(freshCall.cancelled(), "destroy must cancel active calls");
        LocalBridge.Call afterDestroy = new LocalBridge.Call(10000);
        check(!reg.register("dead", afterDestroy, reg.generation()) && afterDestroy.cancelled(),
                "register after destroy must be rejected");
    }

    public static void main(String[] args) throws Exception {
        NetworkPolicy.configure(null);
        testPbkdf2FallbackMatchesJdk();
        testPrivacyMaskerModernKeysAndJsonIntegrity();
        testPrivacyMaskJsonContextualExemptions();
        testBridgeRequestRegistryFastCancelRaceAndCleanup();
        testResponsesReasoningTextDeltaStream();
        testMiddlewareArraySystemAndDeepClone();
        testCustomProviderSelfLoopHardening();
        testContextGuardUnlimitedFastPath();
        testHeldWorkersConcurrencyAndEarlyCors();
        System.out.println("CodeReviewFixesOfflineTest PASS (" + checks + " checks)");
    }
}
