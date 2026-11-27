package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 豆包网页工作模式的登录态与公共协议常量。
 *
 * <p>仅使用 {@code www.doubao.com} 的网页接口：Cookie 里的 {@code sessionid} 就是账号身份，
 * 没有 refresh token；过期只能重新扫码。所有请求都落在用户自己的账号上，
 * 消耗的是该账号在豆包客户端里的额度/积分，不存在共享池。</p>
 */
public final class DoubaoAuth {
    public static final String ORIGIN = "https://www.doubao.com";
    public static final String CHAT_PATH = "/chat/completion";
    public static final String PREFERENCE_PATH = "/samantha/user/preference/get";
    public static final String AID = "582478";
    public static final String LOCAL_APP_ID = "582478";
    public static final String PC_VERSION = "2.37.16";
    public static final String RUNTIME_VERSION = "3.46.10";
    public static final String CHROMIUM_VERSION = "147.0.7727.149";
    public static final String VERSION_CODE = "20800";
    /** 与已安装豆包桌面端 2.37.16 的 UA 保持一致；移动端 UA 会被风控当成另一条链路。 */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.149 Safari/537.36 "
            + "SamanthaDoubao/2.37.16";
    public static final int MAX_COOKIE = 8192;
    public static final int MAX_BODY = 1048576;

    private DoubaoAuth() {}

    /** 一个账号的全部网页身份字段；deviceId/webId 缺失时由 Cookie 稳定派生。 */
    public static final class Credential {
        public final String cookie, deviceId, webId, localAppId;
        public Credential(String cookie, String deviceId, String webId, String localAppId) {
            this.cookie = cookie; this.deviceId = deviceId; this.webId = webId; this.localAppId = localAppId;
        }
        public static Credential fromAccount(JSONObject account, String fallbackCookie) throws IOException {
            String cookie = account == null ? fallbackCookie : account.optString("cookie", "");
            if (cookie == null || cookie.trim().isEmpty()) cookie = account == null ? "" : account.optString("token", "");
            if (cookie == null || cookie.trim().isEmpty()) cookie = fallbackCookie;
            cookie = normalizeCookie(cookie);
            String device = account == null ? "" : account.optString("deviceId", "").trim();
            if (!validDevice(device)) device = deriveDeviceId(cookie);
            String web = account == null ? "" : account.optString("webId", "").trim();
            if (!validDevice(web)) web = device;
            String appId = account == null ? "" : account.optString("localAppId", "").trim();
            if (!appId.matches("[0-9]{3,20}")) appId = LOCAL_APP_ID;
            return new Credential(cookie, device, web, appId);
        }
    }

    public interface Transport { Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException; }

    public static final class Response implements Closeable {
        public final int status;
        public final String contentType;
        public final InputStream body;
        public Response(int status, String contentType, InputStream body) {
            this.status = status; this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? new ByteArrayInputStream(new byte[0]) : body;
        }
        @Override public void close() throws IOException { body.close(); }
    }

    /** 登录态错误与上游错误的唯一出口，调用方只据此映射到 401/429/502。 */
    public static final class Failure extends IOException {
        public final int status;
        public final boolean authentication;
        Failure(String message, int status, boolean authentication) {
            super(message); this.status = status; this.authentication = authentication;
        }
    }

    public static String normalizeCookie(String raw) throws IOException {
        if (raw == null) throw new Failure("豆包 Cookie 为空", 400, false);
        String value = raw.trim();
        if (value.startsWith("{")) {
            try {
                JSONObject object = new JSONObject(value);
                value = object.optString("cookie", object.optString("token", ""));
            } catch (Exception invalid) {
                throw new Failure("豆包 Cookie 格式无效", 400, false);
            }
        }
        if (value.regionMatches(true, 0, "cookie:", 0, 7)) value = value.substring(7).trim();
        value = value.replace('\r', ';').replace('\n', ';');
        while (value.contains(";;")) value = value.replace(";;", ";");
        value = value.trim();
        if (value.endsWith(";")) value = value.substring(0, value.length() - 1).trim();
        if (value.length() < 8 || value.length() > MAX_COOKIE)
            throw new Failure("豆包 Cookie 长度异常", 400, false);
        if (value.matches("(?s).*[\\p{Cntrl}].*"))
            throw new Failure("豆包 Cookie 含非法控制字符", 400, false);
        if (!value.matches("(?s).*(?:^|;)\\s*sessionid=[^;\\s]{8,}.*"))
            throw new Failure("豆包 Cookie 缺少有效 sessionid，请重新登录后再复制", 400, false);
        return value;
    }

    public static String cookieOf(JSONObject account) {
        if (account == null) return "";
        String value = account.optString("cookie", "");
        if (value != null && !value.trim().isEmpty()) return value;
        return account.optString("token", "");
    }

    public static boolean validDevice(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{6,64}");
    }

    public static String deriveDeviceId(String cookie) {
        String hex = sha256Hex(cookie == null ? "" : cookie);
        return hex.substring(0, 12);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** X-Flow-Trace 只需保持 04-32hex-16hex-01 的稳定形状，不参与签名。 */
    public static String trace() {
        String raw = UUID.randomUUID().toString().replace("-", "");
        String tail = UUID.randomUUID().toString().replace("-", "");
        return "04-" + raw + "-" + tail.substring(0, 16) + "-01";
    }

    public static String chatUrl(Credential c) {
        return ORIGIN + CHAT_PATH + "?" + commonQuery(c) + "&web_tab_id=" + uuid();
    }

    public static String preferenceUrl(Credential c) {
        return ORIGIN + PREFERENCE_PATH + "?" + commonQuery(c) + "&web_tab_id=" + uuid();
    }

    private static String commonQuery(Credential c) {
        StringBuilder q = new StringBuilder();
        param(q, "aid", AID);
        param(q, "real_aid", AID);
        param(q, "channel", "win");
        param(q, "client_platform", "pc_client");
        param(q, "device_id", c.deviceId);
        param(q, "device_platform", "web");
        param(q, "doubao_device_platform", "desktop");
        param(q, "doubao_pc_version", PC_VERSION);
        param(q, "pc_version", PC_VERSION);
        param(q, "language", "zh");
        param(q, "pkg_type", "release_version");
        param(q, "region", "CN");
        param(q, "runtime", "web");
        param(q, "runtime_version", RUNTIME_VERSION);
        param(q, "samantha_web", "1");
        param(q, "sys_region", "CN");
        param(q, "tea_uuid", c.webId);
        param(q, "use-olympus-account", "1");
        param(q, "version_code", VERSION_CODE);
        param(q, "web_id", c.webId);
        param(q, "web_platform", "desktop");
        param(q, "chromium_version", CHROMIUM_VERSION);
        param(q, "fp", "verify_" + c.deviceId);
        return q.toString();
    }

    private static void param(StringBuilder q, String key, String value) {
        if (q.length() > 0) q.append('&');
        try { q.append(URLEncoder.encode(key, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8")); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static Map<String, String> headers(Credential c) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("accept", "*/*");
        h.put("accept-language", "zh-CN,zh;q=0.9");
        h.put("content-type", "application/json;charset=UTF-8");
        h.put("origin", ORIGIN);
        h.put("referer", ORIGIN + "/chat/");
        h.put("user-agent", USER_AGENT);
        h.put("cookie", c.cookie);
        h.put("agw-js-conv", "str, str");
        h.put("last-event-id", "undefined");
        h.put("x-flow-trace", trace());
        h.put("x-requested-with", "XMLHttpRequest");
        return h;
    }

    public static String read(InputStream in, int limit) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buffer.size() + n > limit) throw new IOException("Doubao response too large");
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 只读校验：preference/get 是网页客户端登录后第一个请求，返回 code=0 即会话有效。
     * 不修改账号、不签到、不领取任何奖励。
     */
    public static JSONObject validate(Transport transport, Credential credential) throws IOException {
        if (transport == null) throw new IOException("transport required");
        JSONObject empty = new JSONObject();
        byte[] body = empty.toString().getBytes(StandardCharsets.UTF_8);
        try (Response response = transport.request("POST", preferenceUrl(credential), headers(credential), body)) {
            if (response.status == 401 || response.status == 403)
                throw new Failure("豆包登录态已失效，请重新登录", 401, true);
            if (response.status == 429)
                throw new Failure("豆包校验请求过于频繁，请稍后重试", 429, false);
            if (response.status < 200 || response.status >= 300)
                throw new Failure("豆包登录态校验失败（HTTP " + response.status + "）", 502, false);
            String text = read(response.body, MAX_BODY);
            JSONObject value;
            try { value = new JSONObject(text); }
            catch (Exception invalid) { throw new Failure("豆包登录态校验返回非 JSON", 502, false); }
            Object code = value.opt("code");
            if (code != null && code != JSONObject.NULL && !"0".equals(String.valueOf(code))) {
                String codeText = String.valueOf(code);
                // 710012001 是豆包登录态过期；其余业务码是上游拒绝，不能一律当 401。
                if ("710012001".equals(codeText))
                    throw new Failure("豆包登录态已失效，请重新登录", 401, true);
                throw new Failure("豆包登录态校验失败（code=" + codeText + "）", 502, false);
            }
            Object success = value.opt("success");
            if (success instanceof Boolean && !((Boolean) success)) {
                Object error = value.opt("error");
                String message = error instanceof JSONObject ? ((JSONObject) error).optString("message", "") : "";
                throw new Failure(message.isEmpty() ? "豆包登录态无效" : message, 401, true);
            }
            JSONObject data = value.optJSONObject("data");
            return data == null ? value : data;
        }
    }
}
