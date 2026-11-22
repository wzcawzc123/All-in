package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Domestic MiniMax Code media adapter, based on plugin 0.2.9 (MIT). */
final class MiniMaxMedia {
    static final String PROVIDER = "minimax-code";
    static final String IMAGE = "MiniMax-Image";
    static final String[] VIDEOS = {"MiniMax-H3", "MiniMax-H3-Max", "MiniMax-Hailuo-2.3"};
    static final String LIST = "/minimax-cloud/api/v1/connectors/connected-tools/list";
    static final String CALL = "/minimax-cloud/api/v1/connectors/tools/call";
    private static final String MATRIX = "connector__matrix__";
    private static final Pattern SIZE = Pattern.compile("(\\d{1,7})\\s*[x×]\\s*(\\d{1,7})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK = Pattern.compile("[A-Za-z0-9_-]{1,160}");

    interface Transport {
        JSONObject post(String path, JSONObject body, int timeout) throws Exception;
        void upload(String url, JSONObject fields, String part, String mime, byte[] data) throws Exception;
        void pause(long millis) throws Exception;
    }

    static final class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }

    private final Transport transport;
    MiniMaxMedia(Transport transport) { this.transport = transport; }

    static boolean isVideo(String model) { return Arrays.asList(VIDEOS).contains(model); }
    static boolean isModel(String model) { return IMAGE.equals(model) || isVideo(model); }
    static String rawModel(String value) { return value.startsWith(PROVIDER + "/") ? value.substring(PROVIDER.length() + 1) : value; }
    static boolean available(JSONObject account) {
        return account != null && account.optBoolean("enabled", true) && !account.optBoolean("off", false)
                && !"off".equalsIgnoreCase(account.optString("status"))
                && !account.optString("refresh").isEmpty()
                && !account.optString("access", account.optString("token", "")).isEmpty();
    }

    static String string(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    private static String text(JSONObject value, String... keys) {
        for (String key : keys) {
            String v = string(value.opt(key));
            if (!v.isEmpty()) return v;
        }
        return "";
    }
    private static Object first(JSONObject value, String... keys) {
        for (String key : keys) if (value.has(key) && !value.isNull(key)) return value.opt(key);
        return null;
    }
    private static long now() { return System.currentTimeMillis() / 1000; }

    private JSONArray catalog() throws Exception {
        JSONObject listing = transport.post(LIST, new JSONObject(), 30000);
        JSONArray tools = listing.optJSONArray("tools");
        if (tools == null || !(listing.opt("partial") instanceof Boolean)
                || listing.optJSONArray("provider_failures") == null) throw upstream("Invalid MiniMax tool catalog");
        return tools;
    }

    private JSONObject find(String name) throws Exception {
        JSONArray tools = catalog();
        JSONObject selected = null;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject entry = tools.optJSONObject(i);
            if (entry == null) throw upstream("Invalid MiniMax tool catalog entry");
            String runtime = entry.optString("runtime_tool_name"), providerTool = entry.optString("provider_tool_name");
            boolean uploadAlias = "upload_temp_url".equals(name) && (runtime.endsWith("__upload_temp_url")
                    || "upload_temp_url".equals(providerTool) || providerTool.endsWith("_upload_temp_url"));
            if (!(MATRIX + name).equals(runtime) && !uploadAlias) continue;
            if (selected != null || !runtime.matches("[A-Za-z0-9_-]{1,160}")
                    || !providerTool.matches("[A-Za-z0-9_.:-]{1,160}")
                    || !entry.optString("provider").matches("[A-Za-z0-9_.:-]{1,160}")
                    || !(entry.opt("input_schema_json") instanceof String)) throw upstream("Invalid MiniMax media tool route");
            selected = entry;
        }
        if (selected == null) throw upstream("MiniMax media tool is unavailable on this account: " + name);
        return selected;
    }

    private JSONObject invoke(JSONObject entry, JSONObject args, int timeout) throws Exception {
        JSONObject reply = transport.post(CALL, new JSONObject().put("provider", entry.getString("provider"))
                .put("provider_tool_name", entry.getString("provider_tool_name"))
                .put("runtime_tool_name", entry.getString("runtime_tool_name")).put("arguments_json", args.toString()), timeout);
        if (Boolean.TRUE.equals(reply.opt("is_error"))) {
            String code = reply.optString("error_code", "");
            if (code.matches("[0-9]{1,8}")) checkCode(new JSONObject().put("code", code), "code");
            throw upstream("MiniMax media tool refused: " + entry.getString("provider_tool_name"));
        }
        if (reply.has("is_error") && !Boolean.FALSE.equals(reply.opt("is_error")))
            throw upstream("Invalid MiniMax media tool result flag");
        JSONObject result = strictObject(reply.optString("result_json"));
        checkCode(result, "code");
        return result;
    }

    static JSONObject strictObject(String input) throws Failure {
        try {
            new BoundedStrictJson(input).run();
            return new JSONObject(input);
        } catch (Exception invalid) { throw upstream("Invalid MiniMax JSON response"); }
    }

    static void checkCode(JSONObject body, String field) throws Failure {
        if (!body.has(field)) return;
        String raw = String.valueOf(body.opt(field));
        if (!raw.matches("[0-9]{1,8}")) throw upstream("Invalid MiniMax business status");
        int code = Integer.parseInt(raw);
        if (code == 0) return;
        int status = code == 402 || code == 1008 || code == 2053 ? 402
                : Arrays.asList(429,1002,1039,1041,1045,2045,2056,2062).contains(code) ? 429 : 502;
        throw new Failure(status, "MiniMax media request rejected (code=" + code + ")");
    }

    static Failure upstream(String message) { return new Failure(502, message); }
    private static Failure invalid(String message) { return new Failure(400, message); }

    JSONObject image(JSONObject input, boolean edit) throws Exception {
        String prompt = requiredPrompt(input);
        int count = integer(input.opt("n"), 1, "n");
        if (count < 1 || count > 10) throw invalid("n must be an integer from 1 to 10");
        if (!input.isNull("mask")) throw invalid("MiniMax Image does not support mask edits");
        String format = input.optString("response_format", "url");
        if (!"url".equals(format)) throw invalid("MiniMax image response_format must be url");
        JSONObject item = new JSONObject().put("prompt", prompt).put("n", count);
        String ratio = text(input, "aspect_ratio");
        if (ratio.isEmpty()) ratio = aspect(input.optString("size"));
        if (!ratio.isEmpty()) item.put("aspect_ratio", ratio);
        String tier = area(input);
        if (!tier.isEmpty()) item.put("area", tier);
        JSONArray refs = references(input.opt("reference_images"));
        if (refs.length() == 0) {
            Object one = first(input, "image", "image_url");
            if (one != null) refs.put(reference(one));
        }
        if (edit && refs.length() == 0) throw invalid("Image edits need a reference image");
        if (refs.length() > 0) item.put("reference_images", uploadReferences(refs));
        JSONObject result = invoke(find("generate_image"), new JSONObject().put("requests", new JSONArray().put(item)), 600000);
        JSONArray items = result.optJSONArray("success_items");
        if (items == null || items.length() == 0) {
            JSONArray failures = result.optJSONArray("failed_items");
            JSONObject failure = failures == null ? null : failures.optJSONObject(0);
            if (failure != null) {
                checkCode(failure, "code");
                for (String field : new String[]{"error_code", "err_code"}) {
                    if (failure.optString(field, "").matches("[0-9]{1,8}")) checkCode(failure, field);
                }
                throw upstream("MiniMax image generation returned failed items without a recognized code (count="
                        + failures.length() + ")");
            }
            throw upstream("MiniMax image generation returned no successful item");
        }
        if (items.length() > count) throw upstream("MiniMax Image returned invalid results");
        JSONArray output = new JSONArray();
        for (int i = 0; i < items.length(); i++) output.put(new JSONObject().put("url", asset(items.getJSONObject(i).optString("node_id"))));
        return new JSONObject().put("created", now()).put("data", output);
    }

    JSONObject startVideo(String model, JSONObject input) throws Exception {
        String prompt = requiredPrompt(input);
        int seconds = duration(model, input.opt("seconds"));
        JSONObject args = new JSONObject().put("model", model).put("prompt", prompt)
                .put("duration", seconds).put("resolution", resolution(model, input.optString("size")));
        String ratio = text(input, "ratio");
        if (ratio.isEmpty()) ratio = aspect(input.optString("size"));
        if (!ratio.isEmpty() && !"auto".equals(ratio)) args.put("ratio", ratio);
        Object first = first(input, "input_reference", "input_image");
        Object last = first(input, "last_frame", "last_frame_image");
        JSONObject start = first == null ? null : reference(first);
        JSONObject end = last == null ? null : reference(last);
        JSONArray refs = references(input.opt("reference_images"));
        if (end != null && (start == null || VIDEOS[2].equals(model)))
            throw invalid("Last frame requires a first frame and an H3 model");
        if (refs.length() > 0 && (!VIDEOS[0].equals(model) || start != null))
            throw invalid("Reference image collections require MiniMax-H3 without keyframes");
        if (start != null) args.put("input_image", uploadReference(start)).put("reference_type", "first_frame");
        if (end != null) args.put("last_frame_image", uploadReference(end));
        if (refs.length() > 0) args.put("reference_images", uploadReferences(refs));
        JSONObject result = invoke(find("submit_video_generation"), args, 55000);
        String task = text(result, "task_id", "taskId", "id");
        if (!validTask(task)) throw upstream("MiniMax returned no usable video task ID");
        return new JSONObject().put("id", task).put("object", "video").put("created_at", now())
                .put("status", "queued").put("progress", 0).put("model", model).put("seconds", Integer.toString(seconds));
    }

    JSONObject query(String model, String task) throws Exception {
        return invoke(find("query_video_generation"), new JSONObject().put("model", model).put("task_id", task), 55000);
    }

    static boolean validTask(String value) { return TASK.matcher(value).matches(); }

    static JSONObject status(JSONObject result, String id, String model) {
        String raw = text(result, "status", "task_status", "state", "taskStatus").toLowerCase(Locale.ROOT);
        String status = "queued";
        int progress = Math.max(0, Math.min(100, result.optInt("progress", 0)));
        if (Arrays.asList("succeeded", "success", "completed", "done").contains(raw)) { status = "completed"; progress = 100; }
        else if (Arrays.asList("failed", "error", "cancelled", "canceled", "expired").contains(raw)) status = "failed";
        else if (Arrays.asList("running", "processing", "in_progress", "generating").contains(raw)) {
            status = "in_progress";
            if (progress <= 0 || progress >= 100) progress = 50;
        }
        JSONObject video = new JSONObject().put("id", id).put("object", "video").put("model", model)
                .put("status", status).put("progress", progress).put("created_at", result.optLong("created_at", now()));
        if (result.opt("duration") instanceof Number) video.put("seconds", String.valueOf(result.opt("duration")));
        if ("failed".equals(status)) video.put("error", new JSONObject().put("code", "failed").put("message", "MiniMax video generation failed"));
        return video;
    }

    String videoAsset(JSONObject result) throws Exception {
        String node = result.optString("node_id");
        JSONArray items = result.optJSONArray("success_items");
        if (node.isEmpty() && items != null && items.optJSONObject(0) != null) node = items.getJSONObject(0).optString("node_id");
        return asset(node);
    }

    private String asset(String node) throws Exception {
        if (node.isEmpty() || node.length() > 512) throw upstream("MiniMax media returned no asset ID");
        long deadline = System.nanoTime() + 8_000_000_000L;
        String url;
        do {
            JSONObject result = invoke(find("get_asset_url"), new JSONObject().put("node_id", node), 55000);
            url = text(result, "download_url", "url", "temp_url");
            if (!https(url)) throw upstream("MiniMax returned an invalid asset URL");
            if (!url.contains("/visible/") || System.nanoTime() >= deadline) return url;
            transport.pause(Math.min(1000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000L)));
        } while (System.nanoTime() < deadline);
        return url;
    }

    private static JSONArray references(Object raw) throws Failure {
        JSONArray refs = new JSONArray();
        if (raw == null || raw == JSONObject.NULL) return refs;
        if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() > 10) throw invalid("reference_images must contain up to 10 images");
        JSONArray values = (JSONArray) raw;
        for (int i = 0; i < values.length(); i++) refs.put(reference(values.opt(i)));
        return refs;
    }

    private static JSONObject reference(Object raw) throws Failure {
        String url = raw instanceof JSONObject ? ((JSONObject) raw).optString("url").trim() : string(raw);
        if (!https(url) && !url.matches("(?s)data:image/(jpeg|png|webp|heic|heif);base64,[A-Za-z0-9+/=]+"))
            throw invalid("Reference image must be HTTPS or a supported base64 data URL");
        JSONObject value = new JSONObject().put("url", url);
        if (raw instanceof JSONObject) {
            String mime = ((JSONObject) raw).optString("mime_type");
            if (mime.matches("image/(jpeg|png|webp|heic|heif)")) value.put("mime_type", mime);
        }
        return value;
    }

    private JSONArray uploadReferences(JSONArray refs) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < refs.length(); i++) out.put(uploadReference(refs.getJSONObject(i)));
        return out;
    }

    private JSONObject uploadReference(JSONObject ref) throws Exception {
        String url = ref.getString("url");
        if (https(url)) return ref;
        int separator = url.indexOf(',');
        String mime = ref.optString("mime_type", url.substring(5, url.indexOf(';')));
        byte[] data;
        try { data = MediaBase64.decodeStandard(url.substring(separator + 1)); }
        catch (IllegalArgumentException e) { throw invalid("Invalid reference image base64"); }
        if (data.length == 0 || data.length > 64 * 1024 * 1024) throw invalid("Invalid reference image size");
        String filename = "reference." + mime.substring(6);
        JSONObject entry = find("upload_temp_url");
        String schema = entry.optString("input_schema_json");
        JSONObject properties = schema.trim().isEmpty() ? null : strictObject(schema).optJSONObject("properties");
        JSONObject args = new JSONObject();
        putDeclared(properties, args, filename, "filename", "file_name", "name", "fileName");
        putDeclared(properties, args, mime, "mime_type", "mimeType", "content_type", "contentType", "mime");
        putDeclared(properties, args, data.length, "size_bytes", "size", "bytes", "content_length");
        boolean sentData = putDeclared(properties, args, url.substring(separator + 1), "data", "content", "content_base64", "bytes_base64", "file_base64", "base64");
        if (args.length() == 0) {
            if (properties != null && (properties.has("file_path") || properties.has("path") || properties.has("local_path")))
                throw upstream("MiniMax upload requires a local path");
            args.put("filename", filename).put("mime_type", mime);
        }
        JSONObject signed = invoke(entry, args, 55000);
        String temp = text(signed, "temp_url", "tempUrl", "url");
        String host = text(signed, "upload_host", "uploadHost", "upload_url", "uploadUrl");
        if (!https(temp)) throw upstream("MiniMax returned an invalid temporary URL");
        if (data.length > signed.optLong("max_bytes", signed.optLong("maxBytes", Long.MAX_VALUE)))
            throw invalid("Reference image exceeds MiniMax upload limit");
        if (!host.isEmpty()) {
            String part = text(signed, "file_field_name", "fileFieldName");
            transport.upload(host, signed.optJSONObject("fields"), part.isEmpty() ? "file" : part, mime, data);
        } else if (!sentData) throw upstream("MiniMax returned no upload destination");
        return new JSONObject().put("url", temp).put("mime_type", mime);
    }

    private static boolean putDeclared(JSONObject properties, JSONObject args, Object value, String... names) {
        for (String name : names) if (properties != null && properties.has(name)) { args.put(name, value); return true; }
        return false;
    }

    static boolean https(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null
                    && uri.getRawFragment()==null && (uri.getPort()==-1 || uri.getPort()==443);
        } catch (Exception invalid) { return false; }
    }

    private static String requiredPrompt(JSONObject input) throws Failure {
        String prompt = text(input, "prompt");
        if (prompt.isEmpty()) throw invalid("prompt is required");
        return prompt;
    }

    private static int integer(Object raw, int fallback, String field) throws Failure {
        if (raw == null || raw == JSONObject.NULL || "".equals(raw)) return fallback;
        try { return new java.math.BigDecimal(raw.toString()).intValueExact(); }
        catch (RuntimeException e) { throw invalid(field + " must be an integer"); }
    }

    private static String aspect(String size) {
        String s = size.trim();
        if (s.matches("[0-9]{1,7}:[0-9]{1,7}|auto|adaptive")) return s;
        Matcher m = SIZE.matcher(s);
        if (!m.matches()) return "";
        int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
        if (w == 0 || h == 0) return "";
        int a = w, b = h;
        while (b != 0) { int r = a % b; a = b; b = r; }
        return (w/a) + ":" + (h/a);
    }

    private static String area(JSONObject input) {
        String explicit = input.optString("area");
        if (explicit.matches("[124]K")) return explicit;
        switch (input.optString("quality").toLowerCase(Locale.ROOT)) {
            case "low": return "1K";
            case "medium": return "2K";
            case "high": case "xhigh": case "max": return "4K";
            default:
                String size = input.optString("size").trim();
                if (size.matches("[124]K")) return size;
                Matcher m = SIZE.matcher(size);
                if (!m.matches()) return "";
                int length = Math.max(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return length <= 0 ? "" : length <= 1280 ? "1K" : length <= 2560 ? "2K" : "4K";
        }
    }

    private static int duration(String model, Object raw) throws Failure {
        long value = 6;
        if (raw != null && raw != JSONObject.NULL && !"".equals(raw)) {
            try {
                double numeric = Double.parseDouble(raw.toString());
                if (!Double.isFinite(numeric)) throw new NumberFormatException();
                value = Math.round(numeric);
            } catch (RuntimeException invalid) { throw invalid("seconds must be a number"); }
        }
        if (VIDEOS[2].equals(model)) return value >= 10 ? 10 : 6;
        return (int) Math.min(15, Math.max(VIDEOS[0].equals(model) ? 4 : 5, value));
    }

    private static String resolution(String model, String size) {
        String s = size.trim().toUpperCase(Locale.ROOT);
        String[] allowed = VIDEOS[0].equals(model) ? new String[]{"768P", "2K"}
                : VIDEOS[1].equals(model) ? new String[]{"480P", "768P"} : new String[]{"768P", "1080P"};
        String value = s;
        if (!s.matches("480P|768P|1080P|2K")) {
            if (s.matches(".*(480|640).*")) value = "480P";
            else if (s.matches(".*(1080|1920).*")) value = VIDEOS[2].equals(model) ? "1080P" : "2K";
            else if (s.matches(".*(2K|1440|2560).*")) value = "2K";
            else if (s.matches(".*(720|768|1280).*")) value = "768P";
            else return VIDEOS[0].equals(model) ? "2K" : "768P";
        }
        return Arrays.asList(allowed).contains(value) ? value : allowed[1];
    }
}
