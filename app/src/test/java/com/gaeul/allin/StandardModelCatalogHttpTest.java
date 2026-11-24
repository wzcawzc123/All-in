package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;


import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class StandardModelCatalogHttpTest {
    static void assertTrue(boolean v) { if(!v)throw new AssertionError(); }
    static void assertFalse(boolean v) { assertTrue(!v); }
    static void assertEquals(Object a,Object b) { if(!java.util.Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final Thread thread;
        volatile Throwable failure;
        Server(String... replies) throws Exception {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(3000);
            thread = new Thread(() -> {
                try {
                    for (String reply : replies) {
                        try (Socket client = socket.accept()) {
                            client.setSoTimeout(3000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                            StringBuilder request = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                            requests.add(request.toString());
                            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                            client.getOutputStream().write(bytes);
                            client.getOutputStream().flush();
                        }
                    }
                } catch (Throwable t) { if (!socket.isClosed()) failure = t; }
            });
            thread.start();
        }
        String base() { return "http://127.0.0.1:" + socket.getLocalPort() + "/v1?existing=yes"; }
        public void close() throws Exception { socket.close(); thread.join(4000); if (failure != null) throw new AssertionError(failure); }
    }
    private static String response(int code, String body, String headers) {
        return "HTTP/1.1 " + code + " Test\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n" + headers + "\r\n" + body;
    }
    private static String ok(String body) { return response(200, body, ""); }
    private static List<String> fetch(Server server, JSONObject meta) {
        return StandardModelCatalog.fetch("anthropic", server.base(), "secret", meta,
                url -> (HttpURLConnection) new URL(url).openConnection());
    }
     public void twoPagesDedupeAndEncodeCursor() throws Exception {
        try (Server server = new Server(ok("{\"data\":[{\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a +/\"}"),
                ok("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"has_more\":false}"))) {
            assertEquals(Arrays.asList("a", "b"), fetch(server, new JSONObject()));
            assertTrue(server.requests.get(1).startsWith("GET /v1/models?existing=yes&after_id=a+%2B%2F "));
            assertTrue(server.requests.get(0).toLowerCase(Locale.ROOT).contains("x-api-key: secret"));
            assertTrue(server.requests.get(0).toLowerCase(Locale.ROOT).contains("anthropic-version: 2023-06-01"));
        }
    }
     public void secondPageFailureKeepsMeta() throws Exception {
        try (Server server = new Server(ok("{\"data\":[\"a\"],\"has_more\":true,\"last_id\":\"a\"}"), response(500, "", ""))) {
            JSONObject meta = new JSONObject().put("existing", "value");
            assertTrue(fetch(server, meta).isEmpty());
            assertEquals("value", meta.getString("existing"));
            assertEquals(1, meta.length());
        }
    }
     public void rejectsCursorLoopAndBadSchema() throws Exception {
        String page = ok("{\"data\":[\"a\"],\"has_more\":true,\"last_id\":\"a\"}");
        try (Server server = new Server(page, page)) { assertTrue(fetch(server, new JSONObject()).isEmpty()); }
        try (Server server = new Server(ok("{\"data\":[{\"id\":123}],\"has_more\":false}"))) {
            assertTrue(fetch(server, new JSONObject()).isEmpty());
        }
    }
     public void doesNotFollowRedirect() throws Exception {
        try (Server target = new Server(); Server server = new Server(response(302, "", "Location: " + target.base() + "\r\n"))) {
            assertTrue(fetch(server, new JSONObject()).isEmpty());
            assertTrue(target.requests.isEmpty());
        }
    }
    public void customRelayCompatibilityAndDiagnostics() throws Exception {
        JSONObject customBase = new JSONObject().put("id", "custom-relay").put("name", "Relay")
                .put("type", "custom-api").put("protocol", "openai").put("authMode", "auto")
                .put("keys", new com.gaeul.allin.json.JSONArray().put("k1"))
                .put("models", new com.gaeul.allin.json.JSONArray())
                .put("headers", new JSONObject()).put("enabled", true);
        // 1. has_more: null (JSONObject.NULL) must succeed instead of dropping the catalog.
        try (Server server = new Server(ok("{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"claude-4\"}],\"has_more\":null}"))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertEquals(Arrays.asList("gpt-5", "claude-4"), res.models);
            assertEquals("", res.error);
        }
        // 2. Both data and models present on custom relay: prefers data array.
        try (Server server = new Server(ok("{\"data\":[{\"id\":\"m-a\"}],\"models\":[]}"))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertEquals(Arrays.asList("m-a"), res.models);
        }
        // 3. Top-level array response on custom relay.
        try (Server server = new Server(ok("[{\"id\":\"top-1\"},\"top-2\"]"))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertEquals(Arrays.asList("top-1", "top-2"), res.models);
        }
        // 4. Clear non-secret diagnostic messages for 302 / 401 / 404.
        try (Server server = new Server(response(302, "", "Location: https://other.invalid/v1/models\r\n"))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertTrue(res.models.isEmpty() && res.error.contains("重定向") && res.error.contains("302") && !res.error.contains("other.invalid"));
        }
        try (Server server = new Server(response(401, "{\"error\":\"secret-k1\"}", ""))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertTrue(res.models.isEmpty() && res.error.contains("401") && !res.error.contains("secret-k1"));
        }
        try (Server server = new Server(response(404, "", ""))) {
            JSONObject custom = new JSONObject(customBase.toString()).put("baseUrl", "http://127.0.0.1:" + server.socket.getLocalPort() + "/v1");
            StandardModelCatalog.Result res = StandardModelCatalog.fetchCustomDetailed(custom, "k1", new JSONObject(),
                    url -> (HttpURLConnection) new URL(url).openConnection());
            assertTrue(res.models.isEmpty() && res.error.contains("404"));
        }
    }
    public static void main(String[] args) throws Exception {
        StandardModelCatalogHttpTest t = new StandardModelCatalogHttpTest();
        t.twoPagesDedupeAndEncodeCursor(); t.secondPageFailureKeepsMeta();
        t.rejectsCursorLoopAndBadSchema(); t.doesNotFollowRedirect();
        t.customRelayCompatibilityAndDiagnostics();
        System.out.println("StandardModelCatalogHttpTest PASS");
    }
}
