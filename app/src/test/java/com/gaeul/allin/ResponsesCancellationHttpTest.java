package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gaeul.allin.VendorPolicyGatewayHttpTest.check;

/** Use the platform HTTP connection to a local peer that receives the POST but holds its headers. */
public final class ResponsesCancellationHttpTest {

    private static final class HeldUpstream implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        final CountDownLatch posted = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();
        final Thread worker;
        volatile Throwable failure;

        HeldUpstream() throws Exception {
            worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    String first = line(input);
                    check(first.startsWith("POST /first/responses "), "actual Responses upstream path");
                    int length = 0;
                    for (String header; !(header = line(input)).isEmpty();) {
                        if (header.regionMatches(true, 0, "Content-Length:", 0, 15))
                            length = Integer.parseInt(header.substring(15).trim());
                    }
                    check(length > 0 && input.readNBytes(length).length == length, "complete upstream request delivered");
                    sends.incrementAndGet();
                    posted.countDown();
                    check(input.read() == -1, "cancel must close the waiting upstream connection");
                    disconnected.countDown();
                } catch (Throwable error) { failure = error; }
            }, "responses-held-upstream");
            worker.setDaemon(true);
            worker.start();
        }

        String base() { return "http://127.0.0.1:" + server.getLocalPort() + "/first"; }

        void checkNoReconnect() throws Exception {
            server.setSoTimeout(180);
            try (Socket unexpected = server.accept()) {
                throw new AssertionError("upstream connection reopened after cancellation");
            } catch (java.net.SocketTimeoutException expected) { }
        }

        @Override public void close() throws Exception {
            server.close();
            worker.join(9000);
            check(!worker.isAlive(), "upstream worker stopped");
        }
    }

    private static String line(InputStream input) throws Exception {
        StringBuilder text = new StringBuilder();
        int ch;
        while ((ch = input.read()) >= 0 && ch != '\n') text.append((char) ch);
        if (ch < 0) throw new java.io.EOFException("incomplete fixture header");
        return text.toString().trim();
    }

    private static RequestContext active(GatewayServer gateway) throws Exception {
        java.lang.reflect.Field field = GatewayServer.class.getDeclaredField("activeContexts");
        field.setAccessible(true);
        synchronized (gateway) {
            @SuppressWarnings("unchecked") Set<RequestContext> requests = (Set<RequestContext>) field.get(gateway);
            if (requests == null || requests.isEmpty()) return null;
            check(requests.size() == 1, "one active gateway request");
            return requests.iterator().next();
        }
    }

    private static JSONObject body(String path) {
        return path.equals("/v1/responses")
                ? new JSONObject().put("model", "group/g").put("input", "hello")
                : VendorPolicyGatewayHttpTest.body("group/g", false);
    }

    private static void run(String path, boolean deadline, boolean commercial) throws Exception {
        try (HeldUpstream primary = new HeldUpstream();
             VendorPolicyGatewayHttpTest.Upstream backup = new VendorPolicyGatewayHttpTest.Upstream((request, n) ->
                     new VendorPolicyGatewayHttpTest.Reply(200, "application/json", VendorPolicyGatewayHttpTest.CHAT))) {
            JSONObject provider = VendorPolicyGatewayHttpTest.provider("first", "responses", primary.base(), "model");
            if (!commercial) provider.put("type", "subscription");
            JSONObject cfg = VendorPolicyGatewayHttpTest.config(provider);
            cfg.getJSONObject("failover").put("requestBudgetMs", deadline ? 1250 : 15000);
            cfg.put("guard", new JSONObject().put("requestTimeoutSeconds", 8));
            cfg.getJSONArray("providers").put(VendorPolicyGatewayHttpTest.provider("backup", "openai",
                    "http://127.0.0.1:" + backup.server.getLocalPort() + "/backup", "model"));
            cfg.put("groups", new JSONArray().put(new JSONObject().put("id", "g").put("strategy", "order")
                    .put("targets", new JSONArray().put("first/model").put("backup/model"))));
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(cfg, backup, false)) {
                ExecutorService threads = Executors.newFixedThreadPool(2);
                try {
                    Future<VendorPolicyGatewayHttpTest.Result> pending = threads.submit(() ->
                            VendorPolicyGatewayHttpTest.post(gateway, path, body(path)));
                    check(primary.posted.await(4, TimeUnit.SECONDS), "fixture saw primary POST: " + primary.failure);
                    RequestContext context = active(gateway.gateway);
                    check(context != null && !pending.isDone(), "request must be waiting for headers");
                    Future<?> signal = deadline ? null : threads.submit(() -> context.cancel("test cancellation"));
                    check(primary.disconnected.await(4, TimeUnit.SECONDS), "cancel did not close upstream: " + primary.failure);
                    VendorPolicyGatewayHttpTest.Result result = pending.get(4, TimeUnit.SECONDS);
                    if (signal != null) signal.get(4, TimeUnit.SECONDS);
                    check(result.status == (deadline ? 504 : 499), "expected cancellation status: " + result.status + " " + result.body);
                    primary.checkNoReconnect();
                    check(primary.sends.get() == 1 && backup.requests.isEmpty(), "no repeated or backup send");
                    for (int i = 0; i < 40 && active(gateway.gateway) != null; i++) Thread.sleep(25);
                    check(active(gateway.gateway) == null, "request context released");
                    System.out.println("PASS " + path + " " + (deadline ? "deadline" : "cancel") + " commercial=" + commercial);
                } finally {
                    threads.shutdownNow();
                    gateway.gateway.stop();
                }
            }
        }
    }

    private static void streamCompletes(String path, boolean commercial) throws Exception {
        JSONObject response = new JSONObject().put("status", "completed").put("output", new JSONArray());
        String sse = GatewayStreamingHttpTest.responses("response.output_text.delta",
                new JSONObject().put("output_index", 0).put("content_index", 0).put("delta", "fixture-answer"))
                + GatewayStreamingHttpTest.responses("response.completed", new JSONObject().put("response", response));
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((request, n) ->
                     new VendorPolicyGatewayHttpTest.Reply(200, "text/event-stream", sse))) {
            JSONObject provider = VendorPolicyGatewayHttpTest.provider("first", "responses",
                    "http://127.0.0.1:" + upstream.server.getLocalPort() + "/first", "model");
            if (!commercial) provider.put("type", "subscription");
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(
                    VendorPolicyGatewayHttpTest.config(provider), upstream, false)) {
                JSONObject body = body(path).put("model", "first/model").put("stream", true);
                VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway, path, body);
                String terminal = path.equals("/v1/responses") ? "response.completed" : "[DONE]";
                check(result.status == 200 && result.body.contains("fixture-answer") && result.body.contains(terminal),
                        "stream handoff keeps the full response body");
                for (int i = 0; i < 40 && active(gateway.gateway) != null; i++) Thread.sleep(25);
                check(active(gateway.gateway) == null && upstream.requests.size() == 1, "stream releases request on completion");
                gateway.gateway.stop();
                System.out.println("PASS " + path + " stream completion commercial=" + commercial);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        for (String path : new String[]{"/v1/responses", "/v1/chat/completions"}) {
            run(path, true, false);
            run(path, false, false);
            run(path, true, true);
            streamCompletes(path, false);
            streamCompletes(path, true);
        }
        System.out.println("ResponsesCancellationHttpTest PASS");
    }
}
