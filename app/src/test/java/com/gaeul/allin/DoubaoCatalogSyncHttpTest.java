package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 真实环回 HTTP 测试：豆包工作模式的模型同步接口和 /v1/models 输出。
 * 不联网；只验证网关自己的目录、账号可用性和展示名。
 */
public final class DoubaoCatalogSyncHttpTest {
    private static final String AUTH = "fixture-auth";

    public static void main(String[] args) throws Exception {
        JSONObject cfg = VendorPolicyGatewayHttpTest.config(
                VendorPolicyGatewayHttpTest.provider("doubao", "doubao", "https://www.doubao.com", "auto"));
        cfg.put("setup", new JSONObject().put("done", true));
        cfg.getJSONArray("providers").getJSONObject(0).put("accounts", new JSONArray().put(
                new JSONObject().put("id", "acc-1").put("name", "fixture").put("cookie", "sessionid=fixture; ttwid=fixture")));

        try (VendorPolicyGatewayHttpTest.Upstream upstream =
                     new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
                         throw new AssertionError("model sync must not send an upstream request");
                     });
             VendorPolicyGatewayHttpTest.Gateway gateway =
                     new VendorPolicyGatewayHttpTest.Gateway(cfg, upstream, false)) {
            JSONObject sync = postJson(gateway, "/api/auth/doubao/sync-models", new JSONObject());
            check(sync.optBoolean("ok"), "sync ok: " + sync);
            check(sync.optInt("count", 0) == 16, "sync count is 16, got " + sync.optInt("count", 0));
            JSONArray models = sync.optJSONArray("models");
            check(models != null && models.length() == 16, "sync returns 16 model ids");
            check(contains(models, "deepseek-v4.1-flash"), "sync includes DeepSeek V4.1 Flash id");
            check(contains(models, "mimo-v2.6-pro") && contains(models, "mimo-v2.6-flash"), "sync includes MiMo ids");
            check(!contains(models, "deepseek-v4-flash"), "legacy flash id is not advertised");

            JSONObject list = getJson(gateway, "/v1/models");
            JSONArray data = list.optJSONArray("data");
            JSONObject flashModel = null, mimoModel = null;
            int doubaoCount = 0, missingDisplay = 0;
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.startsWith("doubao/")) {
                    doubaoCount++;
                    if (m.optString("display_name", "").isEmpty()) missingDisplay++;
                }
                if ("doubao/deepseek-v4.1-flash".equals(id)) flashModel = m;
                if ("doubao/mimo-v2.6-pro".equals(id)) mimoModel = m;
                check(!"doubao/deepseek-v4-flash".equals(id), "legacy flash id is not exposed by /v1/models");
            }
            check(doubaoCount == 16, "/v1/models exposes 16 doubao models, got " + doubaoCount);
            check(missingDisplay == 0, "all doubao models have display_name, missing " + missingDisplay);
            check(flashModel != null, "/v1/models exposes DeepSeek V4.1 Flash");
            check("DeepSeek V4.1 Flash".equals(flashModel.optString("name")),
                    "display name matches client menu: " + flashModel.optString("name"));
            check("DeepSeek V4.1 Flash".equals(flashModel.optString("display_name")),
                    "display_name matches client menu");
            check(mimoModel != null && "MiMo V2.6 Pro".equals(mimoModel.optString("display_name")),
                    "/v1/models exposes MiMo V2.6 Pro with display name");
            check(upstream.requests.isEmpty(), "no upstream request during catalog sync");
        }
        System.out.println("DoubaoCatalogSyncHttpTest OK (16 models)");
    }

    private static JSONObject postJson(VendorPolicyGatewayHttpTest.Gateway gateway, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + gateway.getListeningPort() + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + AUTH);
        c.setRequestProperty("x-all-in-management", GatewayManagementCredential.get());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = c.getOutputStream()) { out.write(bytes); }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) try (InputStream input = in) {
            byte[] buffer = new byte[2048];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        c.disconnect();
        check(status == 200, "POST " + path + " status " + status + " body " + out);
        return new JSONObject(out.toString("UTF-8"));
    }

    private static JSONObject getJson(VendorPolicyGatewayHttpTest.Gateway gateway, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + gateway.getListeningPort() + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + AUTH);
        c.setRequestProperty("Accept-Encoding", "identity");
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) try (InputStream input = in) {
            byte[] buffer = new byte[2048];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        c.disconnect();
        check(status == 200, "GET " + path + " status " + status + " body " + out);
        return new JSONObject(out.toString("UTF-8"));
    }

    private static boolean contains(JSONArray values, String wanted) {
        for (int i = 0; values != null && i < values.length(); i++) {
            if (wanted.equals(values.optString(i, ""))) return true;
        }
        return false;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
