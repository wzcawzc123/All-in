package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** User-owned API endpoints only. No built-in login or vendor fingerprint is changed. */
final class CustomProvider {
    static final String TYPE = "custom-api";
    static boolean isCustom(JSONObject p) { return p != null && TYPE.equals(p.optString("type")); }
    static String text(JSONObject p, String field) {
        Object value=p.opt(field);
        if(!(value instanceof String)) throw new IllegalArgumentException(field+" 必须是字符串");
        return ((String)value).trim();
    }
    static String base(String raw) {
        try {
            if(raw==null || raw.length()>2048) throw new Exception();
            URI u=new URI(raw.trim());
            if(u.getHost()==null || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getFragment()!=null
                    || !("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))
                    || u.getPort()==0 || u.getPort()>65535) throw new Exception();
            String path=u.getRawPath()==null?"":u.getRawPath();
            while(path.endsWith("/")) path=path.substring(0,path.length()-1);
            for(String suffix:new String[]{"/chat/completions","/responses","/messages","/models"})
                if(path.endsWith(suffix)) { path=path.substring(0,path.length()-suffix.length()); break; }
            if(path.isEmpty()) path="/v1";
            if(path.contains("/../") || path.endsWith("/..") || path.contains("/./")) throw new Exception();
            return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getRawAuthority()+path;
        } catch(Exception e) { throw new IllegalArgumentException("API 地址须为 HTTP(S) 根地址，不可含凭据、查询参数或片段"); }
    }
    static JSONObject headers(JSONObject raw, String auth) {
        if(raw==null || raw.length()>32) throw new IllegalArgumentException("请求头必须是对象，最多 32 个");
        JSONObject out=new JSONObject(); Set<String> seen=new HashSet<>(); int size=0;
        for(Iterator<String> it=raw.keys();it.hasNext();) {
            String original=it.next(), name=original.trim(), lower=name.toLowerCase(Locale.ROOT);
            for(char c:original.toCharArray()) if(c<32 || c>126) throw new IllegalArgumentException("请求头名称不可含控制字符或非 ASCII");
            if(name.length()>128 || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !seen.add(lower))
                throw new IllegalArgumentException("请求头名称无效或大小写重复");
            if(Arrays.asList("host","connection","content-length","content-type","content-encoding","accept-encoding",
                    "transfer-encoding","te","trailer","upgrade","keep-alive","expect").contains(lower)
                    || lower.startsWith("proxy-") || lower.startsWith("sec-") || lower.startsWith("x-all-in-")
                    || lower.startsWith("access-control-")) throw new IllegalArgumentException("不可自定义传输或网关安全请求头："+name);
            Object v=raw.opt(original); if(!(v instanceof String)) throw new IllegalArgumentException("请求头值必须为字符串");
            String value=((String)v).trim();
            // Validate BEFORE trim: a pasted CR/LF must never turn into an accepted header.
            for(char c:((String)v).toCharArray()) if(c<32 || c>126) throw new IllegalArgumentException("请求头值只允许单行 ASCII；JSON 请压成一行");
            if(value.length()>4096 || (size+=name.length()+value.length())>16384) throw new IllegalArgumentException("请求头超过大小限制");
            if(!"headers".equals(auth) && Arrays.asList("authorization","x-api-key","api-key","x-goog-api-key","cookie").contains(lower))
                throw new IllegalArgumentException("认证请求头请选择「仅自定义请求头」模式");
            out.put(name,value);
        }
        return out;
    }
    static JSONObject validate(JSONObject raw) {
        JSONObject p=new JSONObject(raw.toString());
        String id=text(p,"id"); if(!id.matches("custom-[a-z0-9][a-z0-9-]{0,55}")) throw new IllegalArgumentException("自定义 ID 须以 custom- 开头，使用小写字母、数字或连字符");
        String name=text(p,"name"); if(name.isEmpty() || name.length()>80) throw new IllegalArgumentException("供应商名称须为 1—80 字符");
        String protocol=text(p,"protocol"); if(!Arrays.asList("openai","responses","anthropic").contains(protocol)) throw new IllegalArgumentException("不支持的上游协议");
        String auth=text(p,"authMode"); if(!Arrays.asList("auto","headers","none").contains(auth)) throw new IllegalArgumentException("不支持的认证方式");
        if(!(p.opt("enabled") instanceof Boolean) || !(p.opt("keys") instanceof JSONArray) || !(p.opt("models") instanceof JSONArray)) throw new IllegalArgumentException("供应商字段格式无效");
        JSONArray keys=p.getJSONArray("keys"); if(keys.length()>32) throw new IllegalArgumentException("最多 32 把 API Key");
        for(int i=0;i<keys.length();i++) {
            Object k=keys.opt(i); if(!(k instanceof String)) throw new IllegalArgumentException("自定义供应商 Key 须为字符串");
            String v=(String)k; if(v.length()>16384) throw new IllegalArgumentException("API Key 过长");
            for(char c:v.toCharArray()) if(c<32 || c>126) throw new IllegalArgumentException("API Key 格式无效");
            keys.put(i,v.trim());
        }
        JSONArray models=p.getJSONArray("models"), cleaned=new JSONArray(); Set<String> ids=new LinkedHashSet<>();
        if(models.length()>2000) throw new IllegalArgumentException("模型最多 2000 个");
        for(int i=0;i<models.length();i++) {
            Object item=models.opt(i); if(!(item instanceof String)) throw new IllegalArgumentException("模型 ID 须为字符串");
            String m=((String)item).trim(); if(m.isEmpty() || m.length()>256 || m.startsWith("/") || m.indexOf('?')>=0 || m.indexOf('#')>=0) throw new IllegalArgumentException("模型 ID 无效");
            for(char c:m.toCharArray()) if(c<=32 || c==127) throw new IllegalArgumentException("模型 ID 不可含空白或控制字符");
            if(ids.add(m)) cleaned.put(m);
        }
        if(p.has("catalogModels")) {
            if(!(p.opt("catalogModels") instanceof JSONArray)) throw new IllegalArgumentException("模型候选须为数组");
            JSONObject catalog=new JSONObject(p.toString());catalog.remove("catalogModels");catalog.remove("modelAliases");catalog.put("models",p.getJSONArray("catalogModels"));
            p.put("catalogModels",validate(catalog).getJSONArray("models"));
        }
        if(p.has("modelAliases")) {
            JSONObject aliases=p.optJSONObject("modelAliases");if(aliases==null||aliases.length()>4000)throw new IllegalArgumentException("模型别名格式无效");
            for(String m:aliases.keySet()) {Object alias=aliases.opt(m);if(m.isEmpty()||m.length()>256||!(alias instanceof String)||((String)alias).length()>320||((String)alias).isEmpty())throw new IllegalArgumentException("模型别名无效");
                for(char c:((String)alias).toCharArray())if(c<=32||c==127)throw new IllegalArgumentException("模型别名不可含空白或控制字符");}
        }
        p.put("id",id).put("name",name).put("baseUrl",base(text(p,"baseUrl"))).put("models",cleaned)
                .put("headers",headers(p.optJSONObject("headers"),auth)).put("type",TYPE).put("status","passthrough");
        if(p.has("accounts") && (!(p.opt("accounts") instanceof JSONArray) || p.getJSONArray("accounts").length()!=0)) throw new IllegalArgumentException("自定义 API 不支持订阅账号");
        return p;
    }
    static boolean selected(JSONObject p,String model) {
        JSONArray models=p.optJSONArray("models");
        for(int i=0;models!=null&&i<models.length();i++)if(model.equals(models.optString(i)))return true;
        return false;
    }
    static boolean credentialsReady(JSONObject p) { return !"auto".equals(p.optString("authMode")) || !CommercialKeyPool.list(p.optJSONArray("keys")).isEmpty(); }
    static boolean ready(JSONObject p) {
        if(!isCustom(p)) return false;
        try { validate(p); return credentialsReady(p); }
        catch(Exception invalid) { return false; }
    }
    static JSONArray routeKeys(JSONObject p) {
        if(!isCustom(p) || "auto".equals(p.optString("authMode"))) return p.optJSONArray("keys");
        // An ephemeral limiter/affinity seat, NOT an API key. Never persisted or sent as auth.
        try {
            StringBuilder s=new StringBuilder(p.optString("id")).append('\0').append(p.optString("authMode"));
            JSONObject h=p.optJSONObject("headers"); List<String> names=new ArrayList<>(h.keySet()); Collections.sort(names);
            for(String n:names) s.append('\0').append(n).append('\0').append(h.getString(n));
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder("custom-seat:"); for(byte b:digest) key.append(String.format(Locale.ROOT,"%02x",b&255));
            return new JSONArray().put(key.toString());
        } catch(Exception e) { return new JSONArray(); }
    }
    static void apply(HttpURLConnection conn, JSONObject p, String key, boolean stream) {
        if(!isCustom(p)) return;
        JSONObject valid=validate(p);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent","AllIn-CustomAPI/1.0 (Android)");
        conn.setRequestProperty("Accept",stream?"text/event-stream":"application/json");
        if("anthropic".equals(valid.getString("protocol"))) conn.setRequestProperty("anthropic-version","2023-06-01");
        if("auto".equals(valid.getString("authMode"))) {
            if("anthropic".equals(valid.getString("protocol"))) conn.setRequestProperty("x-api-key",key);
            else conn.setRequestProperty("Authorization","Bearer "+key);
        }
        JSONObject h=valid.getJSONObject("headers");
        for(Iterator<String> it=h.keys();it.hasNext();) { String name=it.next(); conn.setRequestProperty(name,h.getString(name)); }
    }
    static boolean self(JSONObject p, int port) {
        try {
            URI u=new URI(base(p.optString("baseUrl"))); int target=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
            if(target!=port) return false; String host=u.getHost().replace("[","").replace("]","");
            if("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host) || "0.0.0.0".equals(host) || "::".equals(host)) return true;
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()) { Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();
                while(addresses.hasMoreElements()) if(host.equalsIgnoreCase(addresses.nextElement().getHostAddress())) return true; }
        } catch(Exception e) { return true; }
        return false;
    }
}
