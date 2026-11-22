package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses real Android TCP on both sides; a client reset must free a held worker while its upstream is still silent. */
final class HeldCancellationRuntime {
    private static final String CRLF = "\r\n";
    private static final String KEY = "fixture-android-held-cancellation";

    static String run(Context target) throws Exception {
        File root = new File(target.getFilesDir(), "held-cancel-" + UUID.randomUUID());
        check(root.mkdir(), "fixture directory");
        Context context = new ContextWrapper(target) {
            @Override public File getFilesDir() { return root; }
            @Override public Context getApplicationContext() { return this; }
        };
        ExecutorService handlers = Executors.newFixedThreadPool(6);
        try (ServerSocket upstream = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            BlockingQueue<Exchange> received = new LinkedBlockingQueue<>();
            AtomicInteger sends = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            handlers.execute(() -> {
                try {
                    while (!upstream.isClosed()) {
                        Socket socket = upstream.accept();
                        try { handlers.execute(() -> respond(socket, received, sends, errors)); }
                        catch (RejectedExecutionException stopping) { socket.close(); }
                    }
                } catch (IOException stopped) { if (!upstream.isClosed()) errors.incrementAndGet(); }
            });
            JSONObject config = new JSONObject().put("schemaVersion", 4)
                    .put("setup", new JSONObject().put("done", true))
                    .put("server", new JSONObject().put("authKey", KEY))
                    .put("resilience", new JSONObject().put("heartbeat", true).put("patienceMillis", 0))
                    .put("providers", new JSONArray().put(new JSONObject().put("id", "held-android").put("type", "subscription")
                            .put("enabled", true).put("protocol", "openai").put("keys", new JSONArray().put("test-only"))
                            .put("models", new JSONArray().put("m")).put("baseUrl", "http://127.0.0.1:" + upstream.getLocalPort() + "/v1")));
            AtomicConfigFile.write(new File(root, "config.json"), config.toString());
            GatewayServer gateway = new GatewayServer(context, GatewayServer.ListenPlan.damaged(0));
            List<Socket> clients = new ArrayList<>();
            List<Exchange> exchanges = new ArrayList<>();
            try {
                gateway.start(10000, true);
                for (int i = 0; i < 4; i++) {
                    Socket client = openClient(gateway.getListeningPort(), "held-" + i, true);
                    clients.add(client);
                    Exchange exchange = received.poll(5, TimeUnit.SECONDS);
                    check(exchange != null && exchange.tag.equals("held-" + i), "upstream request missing");
                    exchanges.add(exchange);
                    check(awaitLine(client, "held-" + i), "first event missing");
                }
                Socket waiting = openClient(gateway.getListeningPort(), "held-4", true);
                clients.add(waiting);
                check(awaitLine(waiting, ": heartbeat") && sends.get() == 4, "fifth must heartbeat while queued, no upstream send");
                try (Socket ordinary = openClient(gateway.getListeningPort(), "ordinary", false)) {
                    ordinary.setSoTimeout(2000);
                    check(awaitLine(ordinary, "ordinary") && sends.get() == 5, "non-stream completion blocked by held capacity");
                }
                long start = SystemClock.elapsedRealtime();
                clients.get(0).setSoLinger(true, 0);
                clients.get(0).close();
                check(exchanges.get(0).peerClosed.await(7, TimeUnit.SECONDS), "cancelled upstream remained open while silent");
                Exchange next = received.poll(2, TimeUnit.SECONDS);
                check(next != null && next.tag.equals("held-4") && sends.get() == 6, "freed worker did not dispatch the fifth request once");
                exchanges.add(next);
                long ms = SystemClock.elapsedRealtime() - start;
                check(awaitLine(waiting, "held-4"), "queued business event missing after cancellation");
                for (Exchange exchange : exchanges.subList(1, exchanges.size())) exchange.finish.countDown();
                for (Socket survivor : clients.subList(1, clients.size())) check(awaitLine(survivor, "data: [DONE]"), "surviving stream lacked final event");
                long deadline = SystemClock.elapsedRealtime() + 3000;
                while (gateway.activeForwardRequests() != 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(15);
                check(gateway.activeForwardRequests() == 0 && sends.get() == 6 && errors.get() == 0, "requests leaked, replayed or failed upstream");
                return "ANDROID_HELD_CANCEL_PASS upstream closed and fifth dispatched in " + ms + " ms; 6 POSTs, ordinary and four surviving streams completed";
            } finally {
                for (Exchange exchange : exchanges) exchange.finish.countDown();
                for (Socket client : clients) client.close();
                gateway.stop();
            }
        } finally { handlers.shutdownNow(); }
    }

    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }

    private static Socket openClient(int port, String tag, boolean stream) throws IOException {
        Socket client = new Socket("127.0.0.1", port);
        client.setSoTimeout(9000);
        byte[] body = bytes(new JSONObject().put("model", "held-android/m").put("stream", stream)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", tag))).toString());
        OutputStream out = client.getOutputStream();
        out.write(bytes("POST /v1/chat/completions HTTP/1.1" + CRLF + "Host: 127.0.0.1:" + port + CRLF
                + "Connection: close" + CRLF + "Authorization: Bearer " + KEY + CRLF + "Content-Type: application/json" + CRLF
                + "Content-Length: " + body.length + CRLF + CRLF));
        out.write(body);
        out.flush();
        return client;
    }

    private static byte[] bytes(String data) { return data.getBytes(StandardCharsets.UTF_8); }

    private static boolean awaitLine(Socket socket, String fragment) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = in.read()) >= 0;) {
            if (line.size() >= 64 * 1024) throw new IOException("Fixture line overflow");
            line.write(b);
            if (b == 10) {
                if (line.toString("UTF-8").contains(fragment)) return true;
                line.reset();
            }
        }
        return line.toString("UTF-8").contains(fragment);
    }

    private static final class Exchange {
        final String tag;
        final CountDownLatch peerClosed = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        Exchange(String tag) { this.tag = tag; }
    }

    private static void respond(Socket accepted, BlockingQueue<Exchange> received, AtomicInteger sends, AtomicInteger errors) {
        Exchange exchange = null;
        try (Socket socket = accepted) {
            socket.setSoTimeout(3000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String line = requestLine(in);
            if (!line.startsWith("POST /v1/chat/completions ")) throw new IOException("fixture route");
            int size = 0;
            while (!(line = requestLine(in)).isEmpty())
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) size = Integer.parseInt(line.substring(15).trim());
            if (size <= 0 || size > 65536) throw new IOException("fixture length");
            byte[] raw = new byte[size]; new DataInputStream(in).readFully(raw);
            JSONObject request = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String tag = request.getJSONArray("messages").getJSONObject(0).getString("content");
            sends.incrementAndGet();
            OutputStream out = socket.getOutputStream();
            if (!request.optBoolean("stream")) {
                String json = new JSONObject().put("id", "fixture").put("choices", new JSONArray().put(new JSONObject()
                        .put("index", 0).put("message", new JSONObject().put("role", "assistant").put("content", tag))
                        .put("finish_reason", "stop"))).toString();
                out.write(bytes("HTTP/1.1 200 OK" + CRLF + "Content-Type: application/json" + CRLF + "Connection: close" + CRLF
                        + "Content-Length: " + (bytes(json).length + CRLF.length()) + CRLF + CRLF + json + CRLF));
                out.flush();
                return;
            }
            exchange = new Exchange(tag);
            out.write(bytes("HTTP/1.1 200 OK" + CRLF + "Content-Type: text/event-stream" + CRLF
                    + "Connection: close" + CRLF + "Transfer-Encoding: chunked" + CRLF + CRLF));
            chunk(out, "data: " + new JSONObject().put("id", "fixture").put("choices", new JSONArray().put(new JSONObject()
                    .put("index", 0).put("delta", new JSONObject().put("content", tag)))) + "\n\n");
            received.add(exchange);
            socket.setSoTimeout(80);
            while (exchange.finish.getCount() != 0) {
                try {
                    if (in.read() != -1) throw new IOException("unexpected extra bytes");
                    exchange.peerClosed.countDown();
                    return;
                } catch (SocketTimeoutException stillWaiting) { }
            }
            chunk(out, "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            out.write(bytes("0" + CRLF + CRLF)); out.flush();
        } catch (SocketException closed) {
            if (exchange != null) exchange.peerClosed.countDown(); else errors.incrementAndGet();
        } catch (Exception failure) { errors.incrementAndGet(); }
    }

    private static String requestLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1;) {
            if (b == 10) return line.toString("ISO-8859-1");
            if (b != 13) line.write(b);
            if (line.size() > 8192) break;
        }
        throw new EOFException("fixture header");
    }

    private static void chunk(OutputStream out, String event) throws IOException {
        byte[] bytes = bytes(event);
        out.write(bytes(Integer.toHexString(bytes.length) + CRLF)); out.write(bytes); out.write(bytes(CRLF)); out.flush();
    }
}
