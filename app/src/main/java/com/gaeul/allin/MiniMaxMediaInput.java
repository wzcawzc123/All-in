package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class MiniMaxMediaInput {
    static final long MAX_VIDEO_BYTES = 256L * 1024 * 1024;
    private static final Pattern BOUNDARY = Pattern.compile("(?i)(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))");
    private static final Pattern DISPOSITION = Pattern.compile("(?i)^Content-Disposition: *form-data; *name=\"([^\"]+)\"(?:; *filename=\"([^\"]*)\")? *$");
    private MiniMaxMediaInput() {}

    static boolean multipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;");
    }

    static JSONObject parse(String raw, String contentType) throws MiniMaxMedia.Failure {
        try {
            if (!multipart(contentType)) {
                new BoundedStrictJson(raw).run();
                return new JSONObject(raw);
            }
            return parseMultipart(raw, contentType);
        } catch (Exception invalid) {
            if (invalid instanceof MiniMaxMedia.Failure) throw (MiniMaxMedia.Failure) invalid;
            throw new MiniMaxMedia.Failure(400, "Invalid MiniMax media request body");
        }
    }

    // Ingress hands multipart bytes over in ISO-8859-1 for a lossless, bounded parse.
    private static JSONObject parseMultipart(String raw, String type) throws Exception {
        Matcher boundary = BOUNDARY.matcher(type);
        if (!boundary.find()) throw new IllegalArgumentException();
        String token = boundary.group(1) != null ? boundary.group(1) : boundary.group(2);
        if (!token.matches("[A-Za-z0-9'()+_,./:=? -]{1,70}")) throw new IllegalArgumentException();
        String marker = "--" + token;
        JSONObject fields = new JSONObject();
        JSONArray images = new JSONArray();
        int at = 0;
        for (int count = 0; count < 48; count++) {
            if (!raw.startsWith(marker, at)) throw new IllegalArgumentException();
            at += marker.length();
            if (raw.substring(at).equals("--\r\n") || raw.substring(at).equals("--")) {
                if (images.length() > 0) {
                    if (fields.has("reference_images")) throw new IllegalArgumentException();
                    fields.put("reference_images", images);
                }
                return fields;
            }
            if (!raw.startsWith("\r\n", at)) throw new IllegalArgumentException();
            int headersEnd = raw.indexOf("\r\n\r\n", at + 2);
            if (headersEnd < 0 || headersEnd - at > 8192) throw new IllegalArgumentException();
            String name = null, file = null, mime = "";
            for (String header : raw.substring(at + 2, headersEnd).split("\r\n")) {
                Matcher disposition = DISPOSITION.matcher(header);
                if (disposition.matches() && name == null) {
                    name = disposition.group(1);
                    file = disposition.group(2);
                } else if (header.regionMatches(true, 0, "Content-Type:", 0, 13) && mime.isEmpty()) {
                    mime = header.substring(13).trim().toLowerCase(Locale.ROOT);
                } else throw new IllegalArgumentException();
            }
            if (name == null) throw new IllegalArgumentException();
            int from = headersEnd + 4;
            int end = raw.indexOf("\r\n" + marker, from);
            while (end >= 0 && !raw.startsWith("\r\n", end + 2 + marker.length())
                    && !raw.startsWith("--", end + 2 + marker.length())) end = raw.indexOf("\r\n" + marker, end + 1);
            if (end < 0) throw new IllegalArgumentException();
            byte[] data = raw.substring(from, end).getBytes(StandardCharsets.ISO_8859_1);
            if (file != null) {
                if ("mask".equals(name)) throw new MiniMaxMedia.Failure(400, "MiniMax Image does not support mask edits");
                if (data.length == 0 || !mime.matches("image/(jpeg|png|webp|heic|heif)")) throw new IllegalArgumentException();
                String encoded = "data:" + mime + ";base64," + MediaBase64.standard(data);
                if ("image".equals(name) || "image[]".equals(name) || "reference_images".equals(name) || "reference_images[]".equals(name)) {
                    if (images.length() >= 10) throw new IllegalArgumentException();
                    images.put(encoded);
                } else if (name.matches("input_reference|input_image|last_frame|last_frame_image") && !fields.has(name)) {
                    fields.put(name, encoded);
                } else throw new IllegalArgumentException();
            } else {
                if (fields.has(name)) throw new IllegalArgumentException();
                String value = BoundedResponse.utf8(data);
                if ("reference_images".equals(name)) fields.put(name, new JSONArray(value));
                else fields.put(name, value);
            }
            at = end + 2;
        }
        throw new IllegalArgumentException();
    }

    static boolean modelExposed(JSONObject exposed, String id) {
        if (exposed == null || !exposed.optBoolean("enabled", false)) return true;
        JSONArray items = exposed.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) if (id.equals(items.optString(i))) return true;
        return false;
    }

    static String handle(String task, String model, String account, String caller, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String id = model + "::" + task;
        String material = new JSONArray().put("minimax-cn-video/v1").put(id).put(account).put(caller).toString();
        byte[] tag = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        return id + "." + MediaBase64.urlNoPadding(tag);
    }

    static boolean matches(String handle, String task, String model, String account, String caller, String key) throws Exception {
        return !account.isEmpty() && MessageDigest.isEqual(handle.getBytes(StandardCharsets.UTF_8),
                handle(task, model, account, caller, key).getBytes(StandardCharsets.UTF_8));
    }

    // The gateway never fetches a client reference URL itself. Signed upload/downloads use
    // known MiniMax and object-storage hosts, with no authentication headers or redirects.
    static void checkAssetHost(String url) throws Exception {
        if (!MiniMaxMedia.https(url)) throw MiniMaxMedia.upstream("Invalid MiniMax media transfer URL");
        String host = new URI(url).getHost().toLowerCase(Locale.ROOT);
        if (!(host.matches("(?:[a-z0-9-]+\\.)*(?:minimax\\.cn|minimaxi\\.com|minimax\\.io|hailuoai\\.video|minimax-cdn\\.com)")
                || host.matches("[a-z0-9-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com"))) {
            throw MiniMaxMedia.upstream("MiniMax media transfer host is not allowlisted");
        }
    }

    static final class UploadForm {
        final String boundary;
        final byte[] start, end;
        UploadForm(String boundary, byte[] start, byte[] end) {
            this.boundary = boundary;
            this.start = start;
            this.end = end;
        }
    }

    static UploadForm uploadForm(JSONObject fields, String field, String mime, int length) throws MiniMaxMedia.Failure {
        if (!field.matches("[a-zA-Z0-9_.-]{1,80}") || length <= 0 || !mime.matches("image/(jpeg|png|webp|heic|heif)"))
            throw MiniMaxMedia.upstream("Invalid MiniMax signed upload form");
        String boundary = "AllIn" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder prefix = new StringBuilder();
        if (fields != null) for (Iterator<String> it = fields.keys(); it.hasNext();) {
            String name = it.next();
            Object value = fields.opt(name);
            if (!name.matches("[A-Za-z0-9_.-]{1,80}") || !(value instanceof String) || prefix.length() > 128 * 1024)
                throw MiniMaxMedia.upstream("Invalid MiniMax signed upload form");
            prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                    .append(name).append("\"\r\n\r\n").append(value).append("\r\n");
        }
        prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                .append(field).append("\"; filename=\"reference.").append(mime.substring(6)).append("\"\r\nContent-Type: ")
                .append(mime).append("\r\n\r\n");
        return new UploadForm(boundary, prefix.toString().getBytes(StandardCharsets.UTF_8),
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    }
}
