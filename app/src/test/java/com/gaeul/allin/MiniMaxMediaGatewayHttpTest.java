package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests the public MiniMax media endpoints against a domestic Matrix loopback fixture. */
public final class MiniMaxMediaGatewayHttpTest {
    private static final String IMAGE = "minimax-code/MiniMax-Image";
    private static final String VIDEO = "minimax-code/MiniMax-H3";
    private static final String CDN = "https://cdn.minimax.cn";
    private static final String AUTH = "fixture-auth";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static VendorPolicyGatewayHttpTest.Reply reply(JSONObject body) {
        return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", body.toString());
    }

    private static JSONObject catalog() {
        JSONArray tools = new JSONArray();
        for (String name : new String[]{"generate_image", "get_asset_url", "submit_video_generation",
                "query_video_generation", "upload_temp_url"}) {
            tools.put(new JSONObject().put("provider", "matrix").put("provider_tool_name", name)
                    .put("runtime_tool_name", "connector__matrix__" + name)
                    .put("input_schema_json", "upload_temp_url".equals(name)
                            ? "{\"properties\":{\"content_base64\":{}}}" : "{}"));
        }
        return new JSONObject().put("code", 0).put("tools", tools).put("partial", false)
                .put("provider_failures", new JSONArray());
    }

    private static VendorPolicyGatewayHttpTest.Result send(NanoHTTPD g, String path, String verb,
            byte[] body, String content, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + g.getListeningPort() + path).openConnection();
        c.setRequestMethod(verb);
        c.setReadTimeout(15000);
        c.setConnectTimeout(3000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", content);
                c.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            VendorPolicyGatewayHttpTest.Result result = new VendorPolicyGatewayHttpTest.Result();
            result.status = c.getResponseCode();
            try (java.io.InputStream in = result.status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                result.body = in == null ? "" : ZCodeGiftUsageHttpTest.read(in);
            }
            return result;
        } finally { c.disconnect(); }
    }

    private static VendorPolicyGatewayHttpTest.Result post(NanoHTTPD gateway, String path, JSONObject body) throws Exception {
        return send(gateway, path, "POST", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", AUTH);
    }

    private static void base64Parity() {
        java.util.Random random = new java.util.Random(106);
        for (int size = 0; size < 260; size++) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            String standard = java.util.Base64.getEncoder().encodeToString(data);
            check(standard.equals(MediaBase64.standard(data)), "standard Base64 wire parity");
            check(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(MediaBase64.urlNoPadding(data)), "URL-safe job ID parity");
            check(java.util.Arrays.equals(data, MediaBase64.decodeStandard(standard))
                    && java.util.Arrays.equals(data, MediaBase64.decodeStandard(standard.replace("=", ""))), "padded and unpadded Base64 parity");
        }
        for (String text : new String[]{"=", "Z", "Zg=", "Zg===", "Zm9v=", "=Zm9v", "Zm 9v", "Zm-v"}) {
            boolean rejected = false;
            try { MediaBase64.decodeStandard(text); } catch (IllegalArgumentException invalid) { rejected = true; }
            check(rejected, "invalid Base64 stays rejected");
        }
    }

    public static void main(String[] args) throws Exception {
        base64Parity();
        TestPrivacyScope.initialize();
        AtomicInteger submits = new AtomicInteger(), renders = new AtomicInteger(), uploads = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger(), downloads = new AtomicInteger();
        try (VendorPolicyGatewayHttpTest.Upstream up = new VendorPolicyGatewayHttpTest.Upstream((request, ordinal) -> {
            if ("/video.mp4".equals(request.path)) {
                check(request.headers.get("Authorization") == null, "CDN receives no MiniMax token");
                downloads.incrementAndGet();
                return new VendorPolicyGatewayHttpTest.Reply(200, "video/mp4", "fixture-video");
            }
            check("Bearer fixture-access".equals(request.headers.get("Authorization")), "uses domestic account");
            if (MiniMaxMedia.LIST.equals(request.path)) return reply(catalog());
            check(MiniMaxMedia.CALL.equals(request.path), "uses Matrix tool endpoint");
            String name = request.body.getString("provider_tool_name");
            check(("connector__matrix__" + name).equals(request.body.getString("runtime_tool_name")), "runtime tool ID");
            JSONObject payload = new JSONObject(request.body.getString("arguments_json"));
            JSONObject result = new JSONObject();
            switch (name) {
                case "generate_image":
                    renders.incrementAndGet();
                    JSONObject image = payload.getJSONArray("requests").getJSONObject(0);
                    if ("refuse".equals(image.optString("prompt"))) return reply(new JSONObject().put("base_resp", new JSONObject().put("status_code", 1008)));
                    if (image.optString("prompt").startsWith("batch-refusal-")) {
                        String code = image.optString("prompt").substring("batch-refusal-".length());
                        result.put("success_items", new JSONArray()).put("failed_items", new JSONArray()
                                .put(new JSONObject().put("err_code", code).put("error_msg", "private upstream text")));
                        break;
                    }
                    check(image.optInt("n") == 1, "image count");
                    if ("draw".equals(image.optString("prompt"))) {
                        check("1:1".equals(image.optString("aspect_ratio")) && "1K".equals(image.optString("area")), "image tier and aspect ratio");
                    } else {
                        check("edit".equals(image.optString("prompt")), "edit prompt");
                        check((CDN + "/temporary.png").equals(image.getJSONArray("reference_images").getJSONObject(0).getString("url")), "edit uses uploaded reference");
                    }
                    result.put("success_items", new JSONArray().put(new JSONObject().put("node_id", "image-node")));
                    break;
                case "upload_temp_url":
                    uploads.incrementAndGet();
                    check("/9g=".equals(payload.optString("content_base64")), "multipart bytes survive ingress");
                    result.put("temp_url", CDN + "/temporary.png");
                    break;
                case "submit_video_generation":
                    submits.incrementAndGet();
                    if ("refuse".equals(payload.optString("prompt")))
                        return reply(new JSONObject().put("is_error", false).put("result_json", new JSONObject().put("code", 402).toString()));
                    check("MiniMax-H3".equals(payload.optString("model")) && payload.optInt("duration") == 4
                            && "2K".equals(payload.optString("resolution")) && "16:9".equals(payload.optString("ratio")), "video parameters");
                    check("first_frame".equals(payload.optString("reference_type"))
                            && payload.getJSONObject("input_image").optString("url").endsWith("/first.png")
                            && payload.getJSONObject("last_frame_image").optString("url").endsWith("/last.png"), "video frame references");
                    result.put("task_id", "task_01");
                    break;
                case "query_video_generation":
                    check("task_01".equals(payload.optString("task_id")), "gateway sends original task ID");
                    result.put("task_status", queries.incrementAndGet() == 1 ? "running" : "succeeded")
                            .put("node_id", "video-node").put("duration", 4);
                    break;
                case "get_asset_url":
                    result.put("download_url", CDN + ("image-node".equals(payload.optString("node_id")) ? "/image.png" : "/video.mp4"));
                    break;
                default: throw new AssertionError("Unexpected tool: " + name);
            }
            return reply(new JSONObject().put("is_error", false).put("result_json", result.toString()));
        })) {
            JSONObject account = new JSONObject().put("id", "media-account").put("access", "fixture-access")
                    .put("refresh", "fixture-refresh").put("expires", System.currentTimeMillis() + 3600000);
            JSONObject provider = VendorPolicyGatewayHttpTest.provider(MiniMaxMedia.PROVIDER, "minimax", MiniMaxAuth.API, "MiniMax-M3")
                    .put("accounts", new JSONArray().put(account));
            JSONObject config = VendorPolicyGatewayHttpTest.config(provider).put("setup", new JSONObject().put("done", true));
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config, up, false)) {
                JSONObject prompt = new JSONObject().put("model", IMAGE).put("prompt", "draw").put("size", "1024x1024");
                check(send(gateway, "/v1/videos", "POST", "{}".getBytes(StandardCharsets.UTF_8), "application/json", null).status == 401, "media requires gateway credential");
                check(send(gateway, "/v1/videos", "GET", null, null, AUTH).status == 405, "video creation is POST-only");
                check(up.requests.isEmpty(), "unauthorized or wrong method cannot generate");
                String listing = send(gateway, "/v1/models", "GET", null, null, AUTH).body;
                check(listing.contains(IMAGE) && listing.contains(VIDEO) && listing.contains("MiniMax-Hailuo-2.3"), "four media models discoverable");
                VendorPolicyGatewayHttpTest.Result image = post(gateway, "/v1/images/generations", prompt);
                check(image.status == 200 && image.body.contains(CDN + "/image.png"), "image generation response: " + image.status + " " + image.body);
                VendorPolicyGatewayHttpTest.Result refused = post(gateway, "/v1/images/generations", new JSONObject(prompt.toString()).put("prompt", "refuse"));
                check(refused.status == 402 && renders.get() == 2, "generation refusal is not replayed");
                String[][] batchFailures = {{"1008", "402"}, {"1039", "429"}, {"7777", "502"}, {"nonnumeric", "502"}};
                for (String[] failure : batchFailures) {
                    int beforeFailure = renders.get();
                    String code = failure[0];
                    VendorPolicyGatewayHttpTest.Result response = post(gateway, "/v1/images/generations",
                            new JSONObject(prompt.toString()).put("prompt", "batch-refusal-" + code));
                    check(response.status == Integer.parseInt(failure[1]) && renders.get() == beforeFailure + 1,
                            "image failure maps without replay: " + response.status + " " + response.body);
                    check(!response.body.contains("private upstream text") && ("nonnumeric".equals(code)
                            ? response.body.contains("without a recognized code") : response.body.contains("code=" + code)),
                            "only safe image failure details reach the client");
                }
                String form = "--form\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nedit\r\n"
                        + "--form\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n\u00ff\u00d8\r\n--form--\r\n";
                check(send(gateway, "/v1/images/edits", "POST", form.getBytes(StandardCharsets.ISO_8859_1),
                        "multipart/form-data; boundary=form", AUTH).status == 200 && uploads.get() == 1, "multipart edit");
                JSONObject video = new JSONObject().put("model", VIDEO).put("prompt", "move").put("seconds", 2)
                        .put("size", "1920x1080").put("input_reference", CDN + "/first.png").put("last_frame", CDN + "/last.png");
                VendorPolicyGatewayHttpTest.Result create = post(gateway, "/v1/videos", video);
                check(create.status == 200 && submits.get() == 1, "video submit: " + create.status + " " + create.body);
                String id = new JSONObject(create.body).getString("id");
                check(send(gateway, "/v1/videos/" + id + "x", "GET", null, null, AUTH).status == 404 && queries.get() == 0, "forged video ID rejected before upstream");
                VendorPolicyGatewayHttpTest.Result state = send(gateway, "/v1/videos/" + id, "GET", null, null, AUTH);
                check(state.status == 200 && "in_progress".equals(new JSONObject(state.body).optString("status")), "video progress");
                VendorPolicyGatewayHttpTest.Result mp4 = send(gateway, "/v1/videos/" + id + "/content", "GET", null, null, AUTH);
                check(mp4.status == 200 && "fixture-video".equals(mp4.body) && downloads.get() == 1 && submits.get() == 1, "video download and submit count");
                check(post(gateway, "/v1/videos", new JSONObject(video.toString()).put("prompt", "refuse")).status == 402
                        && submits.get() == 2, "video business code 402 stays visible and is not replayed");
                config.put("exposed", new JSONObject().put("enabled", true).put("items", new JSONArray()));
                int before = up.requests.size();
                check(post(gateway, "/v1/images/generations", prompt).status == 403 && up.requests.size() == before, "media allowlist enforced before upstream");
            }
        }
        System.out.println("MiniMax media HTTP PASS: image, multipart edit, video submit, progress, download, auth and billing boundary");
    }
}
