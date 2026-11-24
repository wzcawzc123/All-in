package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONException;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Bounded, all-or-nothing retrieval of standard vendor model catalogs. */
final class StandardModelCatalog {
    interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    static final class Result {
        final List<String> models;
        final String error;
        Result(List<String> models, String error) {
            this.models = models == null ? Collections.<String>emptyList() : models;
            this.error = error == null ? "" : error;
        }
    }
    private static final class CatalogFailure extends IOException {
        CatalogFailure(String safeMessage) { super(safeMessage); }
    }
    private static final int MAX_PAGES = 20, MAX_MODELS = 10000, MAX_BODY = 2 * 1024 * 1024;

    static List<String> fetch(String provider, String baseUrl, String token, JSONObject metaOut,
                              ConnectionFactory factory) {
        return fetchDetailed(provider, baseUrl, token, metaOut, factory, null).models;
    }
    static List<String> fetchCustom(JSONObject custom, String token, JSONObject metaOut, ConnectionFactory factory) {
        return fetchCustomDetailed(custom, token, metaOut, factory).models;
    }
    static Result fetchCustomDetailed(JSONObject custom, String token, JSONObject metaOut, ConnectionFactory factory) {
        return fetchDetailed(custom.optString("protocol"), custom.optString("baseUrl"), token, metaOut, factory, custom);
    }
    private static Result fetchDetailed(String provider, String baseUrl, String token, JSONObject metaOut,
                                        ConnectionFactory factory, JSONObject custom) {
        try {
            URI base = new URI(baseUrl == null ? "" : baseUrl.trim());
            if (base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null
                    || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme())))
                throw new CatalogFailure("API 根地址格式无效，请检查 HTTP(S) 地址");
            String path = base.getRawPath();
            if (path == null) path = "";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (custom == null && path.endsWith("/v1")) path = path.substring(0, path.length() - 3);
            String query = base.getRawQuery();
            // A configured cursor makes the catalog incomplete and must never be silently overwritten.
            if (query != null) for (String part : query.split("&")) {
                String key = part.split("=", 2)[0];
                if ("after_id".equals(java.net.URLDecoder.decode(key, "UTF-8")))
                    throw new CatalogFailure("目录地址不可预置分页参数 after_id");
            }
            String endpoint = base.getScheme() + "://" + base.getRawAuthority() + path + (custom == null ? "/v1/models" : "/models")
                    + (query == null || query.isEmpty() ? "" : "?" + query);
            LinkedHashSet<String> models = new LinkedHashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            int total = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = endpoint + (cursor == null ? "" : (endpoint.contains("?") ? "&" : "?")
                        + "after_id=" + URLEncoder.encode(cursor, "UTF-8"));
                HttpURLConnection conn = null;
                try {
                    conn = factory.open(url);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("User-Agent", "MagpiePocket/1.0");
                    if (custom != null) CustomProvider.apply(conn, custom, token, false);
                    else if ("anthropic".equals(provider)) {
                        conn.setRequestProperty("x-api-key", token);
                        conn.setRequestProperty("anthropic-version", "2023-06-01");
                    } else conn.setRequestProperty("Authorization", "Bearer " + token);
                    int status = conn.getResponseCode();
                    if (status >= 300 && status < 400)
                        throw new CatalogFailure("上游返回重定向（HTTP " + status + "），未自动跟随，请改填最终可直连的 API 根地址");
                    if (status == 401 || status == 403)
                        throw new CatalogFailure("上游拒绝访问（HTTP " + status + "），请检查 API Key、认证方式或防火墙拦截");
                    if (status == 404)
                        throw new CatalogFailure("上游未找到模型目录接口（HTTP 404），请检查 API 根地址是否需要补充或调整 /v1");
                    if (status < 200 || status >= 300)
                        throw new CatalogFailure("上游模型目录接口返回 HTTP " + status);
                    byte[] buffer = new byte[8192];
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (n > MAX_BODY - body.size()) throw new CatalogFailure("上游模型目录响应体积过大");
                            body.write(buffer, 0, n);
                        }
                    }
                    String text = new String(body.toByteArray(), StandardCharsets.UTF_8).trim();
                    JSONObject root = null;
                    JSONArray data;
                    if (custom != null && text.startsWith("[")) {
                        data = new JSONArray(text);
                    } else {
                        root = new JSONObject(text);
                        boolean hasData = root.has("data") && !root.isNull("data");
                        boolean hasModels = root.has("models") && !root.isNull("models");
                        if (custom == null) {
                            if (hasData == hasModels) throw new CatalogFailure("上游模型目录结构无效");
                            Object raw = root.opt(hasData ? "data" : "models");
                            if (!(raw instanceof JSONArray)) throw new CatalogFailure("上游模型目录数组格式无效");
                            data = (JSONArray) raw;
                        } else {
                            Object raw = hasData && (root.opt("data") instanceof JSONArray) ? root.opt("data")
                                    : hasModels && (root.opt("models") instanceof JSONArray) ? root.opt("models") : null;
                            if (!(raw instanceof JSONArray))
                                throw new CatalogFailure("上游返回体不含有效的 data/models 模型数组");
                            data = (JSONArray) raw;
                        }
                    }
                    if (data.length() > MAX_MODELS - total) throw new CatalogFailure("上游模型数量超过上限");
                    total += data.length();
                    for (int i = 0; i < data.length(); i++) {
                        Object item = data.opt(i);
                        Object id;
                        if (item instanceof JSONObject) {
                            JSONObject model = (JSONObject) item;
                            id = model.opt("id");
                            if (id == null || JSONObject.NULL.equals(id) || "".equals(id)) id = model.opt("name");
                        } else id = item;
                        if (!(id instanceof String) || ((String) id).trim().isEmpty())
                            throw new CatalogFailure("上游模型目录包含无效的模型标识");
                        models.add(((String) id).trim());
                    }
                    if (root == null) return new Result(new ArrayList<>(models), "");
                    Object more = root.opt("has_more");
                    if ((more == null || JSONObject.NULL.equals(more)) && (custom != null || !"anthropic".equals(provider)))
                        return new Result(new ArrayList<>(models), "");
                    if (!(more instanceof Boolean)) throw new CatalogFailure("上游分页标记 has_more 格式无效");
                    if (!((Boolean) more)) return new Result(new ArrayList<>(models), "");
                    Object next = root.opt("last_id");
                    if (!(next instanceof String) || ((String) next).trim().isEmpty()
                            || !cursors.add((String) next)) throw new CatalogFailure("上游分页游标 last_id 无效或循环");
                    cursor = (String) next;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            throw new CatalogFailure("上游模型目录分页过多");
        } catch (CatalogFailure safe) {
            return new Result(new ArrayList<String>(), safe.getMessage());
        } catch (SocketTimeoutException timeout) {
            return new Result(new ArrayList<String>(), "连接或读取上游超时，请检查手机网络或代理设置");
        } catch (UnknownHostException dns) {
            return new Result(new ArrayList<String>(), "无法解析上游域名，请检查联网权限、DNS 或地址拼写");
        } catch (ConnectException connect) {
            return new Result(new ArrayList<String>(), "无法连接到上游服务器，请检查地址、端口或网络");
        } catch (SSLException ssl) {
            return new Result(new ArrayList<String>(), "上游 HTTPS/TLS 握手失败，请检查证书或系统时间");
        } catch (JSONException json) {
            return new Result(new ArrayList<String>(), "上游返回的不是有效 JSON 模型目录");
        } catch (Exception failure) {
            return new Result(new ArrayList<String>(), "读取上游模型目录失败，请检查网络或配置");
        }
    }
}
