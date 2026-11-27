package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/** Read-only inventory; it never selects a route or refreshes credentials. */
final class GatewayAccessCatalog {
    interface GiftSnapshot { JSONObject get(String jwt, String device); }
    private static boolean present(JSONObject a, String field) { return !a.optString(field, "").trim().isEmpty(); }
    private static boolean enabled(JSONObject a) { return a != null && a.optBoolean("enabled", true); }
    private static boolean qoderEnabled(JSONObject a) { return enabled(a) && !a.optBoolean("off", false) && !"off".equalsIgnoreCase(a.optString("status", "")); }
    private static boolean contains(JSONArray a, String v) { for (int i=0; a!=null && i<a.length(); i++) if (v.equals(a.optString(i,""))) return true; return false; }
    static boolean hasAccountCatalog(JSONArray accounts) {
        for (int i=0; accounts!=null && i<accounts.length(); i++) {
            JSONObject a=accounts.optJSONObject(i);
            if (a!=null && a.optJSONArray("models")!=null) return true;
        }
        return false;
    }
    private static String fingerprint(String value) {
        try {
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String accountIdentity(JSONObject account) {
        for(String field:new String[]{"id","accountId","account_id","uid","userId","user_id"}) {
            String value=account.optString(field,"").trim();
            if(!value.isEmpty()) return "id:"+fingerprint(field+"\0"+value);
        }
        for(String field:new String[]{"refresh","refreshToken","token","access","jwt","apiKey","key"}) {
            String value=account.optString(field,"").trim();
            if(!value.isEmpty()) return "credential:"+fingerprint(value);
        }
        return "unidentified";
    }
    static String poolFingerprint(JSONObject p) {
        java.util.TreeSet<String> identities=new java.util.TreeSet<>();
        JSONArray accounts=p.optJSONArray("accounts"), keys=p.optJSONArray("keys");
        for(int i=0;accounts!=null && i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if(account!=null) identities.add("account:"+accountIdentity(account));
        }
        for(int i=0;keys!=null && i<keys.length();i++) {
            Object item=keys.opt(i); String key=item instanceof String?(String)item:item instanceof JSONObject?((JSONObject)item).optString("key",""):"";
            key=key.trim(); if(key.isEmpty()) continue;
            String owner=null;
            for(int j=0;accounts!=null && j<accounts.length();j++) {
                JSONObject account=accounts.optJSONObject(j); if(account==null) continue;
                for(String field:new String[]{"token","access","apiKey","key"}) if(key.equals(account.optString(field,""))) { owner=accountIdentity(account); break; }
                if(owner!=null) break;
            }
            identities.add(owner==null?"key:"+fingerprint(key):"account:"+owner);
        }
        StringBuilder input=new StringBuilder("pool-v2\0").append(p.optString("id","")).append('\0');
        for(String identity:identities) input.append(identity).append('\0');
        return "v2:"+fingerprint(input.toString());
    }
    static void preserveAccountScope(JSONObject oldConfig, JSONObject newConfig) throws com.gaeul.allin.json.JSONException {
        JSONArray old=oldConfig==null?null:oldConfig.optJSONArray("providers"), next=newConfig.optJSONArray("providers");
        for(int i=0;next!=null && i<next.length();i++) {
            JSONObject p=next.optJSONObject(i); if(p==null) continue;
            boolean scoped=p.optBoolean("accessAccountCatalogScoped",false) || hasAccountCatalog(p.optJSONArray("accounts"));
            for(int j=0;old!=null && j<old.length();j++) {
                JSONObject prior=old.optJSONObject(j);
                if(prior!=null && p.optString("id").equals(prior.optString("id"))) scoped |= prior.optBoolean("accessAccountCatalogScoped",false) || hasAccountCatalog(prior.optJSONArray("accounts"));
            }
            if(scoped) p.put("accessAccountCatalogScoped",true);
        }
    }
    private static boolean supportsAccountModel(JSONObject a, String model, boolean scoped) {
        return !scoped || contains(a.optJSONArray("models"), model);
    }
    private static boolean usableKey(JSONObject p, String model) {
        if(CustomProvider.isCustom(p)) return CustomProvider.credentialsReady(p);
        JSONArray keys=p.optJSONArray("keys"), accounts=p.optJSONArray("accounts");
        boolean scoped=p.optBoolean("accessAccountCatalogScoped", false) || hasAccountCatalog(accounts);
        for (CommercialKeyPool.Credential c : CommercialKeyPool.list(keys)) {
            boolean matched=false, active=false;
            for (int i=0; accounts!=null && i<accounts.length(); i++) {
                JSONObject a=accounts.optJSONObject(i);
                if (a!=null && (c.secret().equals(a.optString("token", "")) || c.secret().equals(a.optString("access", "")))) { matched=true; if (enabled(a) && supportsAccountModel(a,model,scoped)) active=true; }
            }
            if ((!matched && !scoped) || active) return true;
        }
        return false;
    }
    static boolean loomyAccountUsable(JSONObject account, String model, boolean scoped, long now) {
        return qoderEnabled(account) && present(account, "token")
                && (account.optLong("expiresAt", 0) <= 0 || account.optLong("expiresAt", 0) > now)
                && supportsAccountModel(account, model, scoped);
    }
    static boolean codexCredential(JSONObject provider, String model, long now) {
        boolean scoped = provider.optBoolean("accessAccountCatalogScoped", false)
                || hasAccountCatalog(provider.optJSONArray("accounts"));
        for (JSONObject account : AccountPoolPolicy.routingAccounts(provider)) {
            if (model != null && !supportsAccountModel(account, model, scoped)) continue;
            if ("legacy-key".equals(account.optString("source")) || "apikey".equalsIgnoreCase(account.optString("authMode"))
                    || CodexAuth.catalogAccount(account, now)) return true;
        }
        return false;
    }

    private static boolean accountUsable(JSONObject p, String model, long now) {
        String id=p.optString("id"), protocol=p.optString("protocol", "openai");
        JSONArray accounts=p.optJSONArray("accounts");
        boolean workbuddy="workbuddy".equals(id) || "workbuddy-ai".equals(id);
        boolean ownCatalog=hasAccountCatalog(accounts) || p.optJSONObject("legacyKeyCatalog")!=null;
        if (p.has("accessCatalogPool") && !p.optString("accessCatalogPool").equals(poolFingerprint(p))
                && !(workbuddy && ownCatalog)) return false;
        if ("codex".equals(id)) return codexCredential(p, model, now);
        if(workbuddy) {
            boolean scoped=p.optBoolean("accessAccountCatalogScoped",false) || ownCatalog;
            for(JSONObject account:AccountPoolPolicy.routingAccounts(p)) {
                if(!supportsAccountModel(account,model,scoped))continue;
                try {WorkBuddyLogin.enterpriseValue(account);}catch(java.io.IOException invalid){continue;}
                if("oauth".equals(account.optString("type")) && account.optLong("expires",0)>0 && account.optLong("expires")<=now
                        && (!present(account,"refresh") || (account.optLong("refreshExpiresAt",0)>0 && account.optLong("refreshExpiresAt")<=now)))continue;
                return true;
            }
            return false;
        }
        if ("loomy".equals(id)) {
            boolean scoped=p.optBoolean("accessAccountCatalogScoped", false) || hasAccountCatalog(accounts);
            for (int i=0; accounts!=null && i<accounts.length(); i++)
                if (loomyAccountUsable(accounts.optJSONObject(i), model, scoped, now)) return true;
            return false;
        }
        if ("trae-cn".equals(id) || "qoder_cn".equals(id)) {
            for (int i=0; accounts!=null && i<accounts.length(); i++) {
                JSONObject a=accounts.optJSONObject(i);
                if (!qoderEnabled(a) || !present(a,"id") || !present(a,"token")) continue;
                JSONArray models=a.optJSONArray("models");
                if ("qoder_cn".equals(id) && accounts.length()==1 && models==null) models=p.optJSONArray("models");
                if (!contains(models,model)) continue;
                JSONObject all=a.optJSONObject("modelMeta");
                if ("qoder_cn".equals(id) && a.optJSONArray("models")==null && all==null && accounts.length()==1) all=p.optJSONObject("modelMeta");
                JSONObject meta=all==null?null:all.optJSONObject(model);
                if (meta==null) continue;
                if ("qoder_cn".equals(id) && (!model.equals(meta.optString("key", "")) || (a.optLong("expiresAt",0)>0 && a.optLong("expiresAt",0)<=now))) continue;
                if ("trae-cn".equals(id) && meta.has("base")) continue;
                return true;
            }
            return false;
        }
        if ("deepseek-web".equals(protocol) && !java.util.Arrays.asList(DeepSeekWeb.MODELS).contains(model)) return false;
        if ("doubao".equals(protocol)) {
            if (DoubaoProtocol.resolve(model) == null) return false;
            boolean scoped=p.optBoolean("accessAccountCatalogScoped",false) || hasAccountCatalog(accounts);
            for (int i=0; accounts!=null && i<accounts.length(); i++) {
                JSONObject a=accounts.optJSONObject(i);
                if (qoderEnabled(a) && DoubaoAuth.cookieOf(a).trim().length()>0
                        && supportsAccountModel(a,model,scoped)) return true;
            }
            return false;
        }
        if ("doubao-web".equals(protocol)) {
            if (!java.util.Arrays.asList(DoubaoWeb.MODELS).contains(model)) return false;
            boolean scoped=p.optBoolean("accessAccountCatalogScoped",false) || hasAccountCatalog(accounts);
            for (int i=0; accounts!=null && i<accounts.length(); i++) {
                JSONObject a=accounts.optJSONObject(i);
                if (qoderEnabled(a) && DoubaoAuth.cookieOf(a).trim().length()>0
                        && supportsAccountModel(a,model,scoped)) return true;
            }
            return false;
        }
        if ("deepseek-web".equals(protocol) || "qoder".equals(protocol) || "catpaw".equals(protocol) || "antigravity".equals(protocol) || "copilot".equals(protocol) || "minimax".equals(protocol) || "cline".equals(protocol)) {
            for (int i=0; accounts!=null && i<accounts.length(); i++) {
                JSONObject a=accounts.optJSONObject(i); if (!enabled(a) || !supportsAccountModel(a,model,p.optBoolean("accessAccountCatalogScoped", false) || hasAccountCatalog(accounts))) continue;
                if ("deepseek-web".equals(protocol) && (present(a,"access") || present(a,"token"))) return true;
                if (("copilot".equals(protocol) || "minimax".equals(protocol)) && (present(a,"refresh") || present(a,"token"))) return true;
                if (!"deepseek-web".equals(protocol) && !"copilot".equals(protocol) && !"minimax".equals(protocol) && present(a,"token")) return true;
            }
            return "cline".equals(protocol) && usableKey(p,model);
        }
        return usableKey(p,model);
    }
    static JSONObject codexImage(JSONObject config, long now, boolean enforceExposure) {
        if (enforceExposure && !MiniMaxMediaInput.modelExposed(config.optJSONObject("exposed"), CodexImages.REF)) return null;
        JSONArray providers = config.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject provider = providers.optJSONObject(i);
            if (provider != null && CodexImages.PROVIDER.equals(provider.optString("id"))) {
                return CodexImages.pick(provider, now) == null ? null : CodexImages.catalogEntry(provider);
            }
        }
        return null;
    }

    static java.util.List<JSONObject> miniMaxMedia(JSONObject config) {
        java.util.List<JSONObject> result = new java.util.ArrayList<>();
        JSONArray providers = config.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject provider = providers.optJSONObject(i);
            if (!enabled(provider) || !MiniMaxMedia.PROVIDER.equals(provider.optString("id"))) continue;
            JSONArray accounts = provider.optJSONArray("accounts");
            for (int j = 0; accounts != null && j < accounts.length(); j++) {
                if (!MiniMaxMedia.available(accounts.optJSONObject(j))) continue;
                addMiniMaxMedia(result, config, MiniMaxMedia.IMAGE, "image");
                for (String video : MiniMaxMedia.VIDEOS) addMiniMaxMedia(result, config, video, "video");
                return result;
            }
        }
        return result;
    }

    private static void addMiniMaxMedia(java.util.List<JSONObject> result, JSONObject config, String model, String kind) {
        String id = MiniMaxMedia.PROVIDER + "/" + model;
        if (MiniMaxMediaInput.modelExposed(config.optJSONObject("exposed"), id)) {
            result.add(new JSONObject().put("id", id).put("name", model.replace('-', ' ')).put("provider", MiniMaxMedia.PROVIDER)
                    .put("providerName", "MiniMax Code").put("kind", "model").put("media_type", kind)
                    .put("modalities", new JSONObject().put("input", new JSONArray().put("text").put("image"))
                            .put("output", new JSONArray().put(kind))));
        }
    }

    static JSONObject build(JSONObject cfg, long now, GiftSnapshot gift) throws com.gaeul.allin.json.JSONException {
        CustomModelNames customNames=new CustomModelNames(cfg);
        JSONObject exposed=cfg.optJSONObject("exposed"); boolean filtered=exposed!=null && exposed.optBoolean("enabled",false);
        JSONArray whitelist=exposed==null?null:exposed.optJSONArray("items");
        LinkedHashMap<String,JSONObject> available=new LinkedHashMap<>(); int unknown=0;
        JSONArray providers=cfg.optJSONArray("providers");
        for (int i=0; providers!=null && i<providers.length(); i++) {
            JSONObject p=providers.optJSONObject(i); if (!enabled(p) || "placeholder".equalsIgnoreCase(p.optString("status", "").trim())) continue;
            if(CustomProvider.isCustom(p) && !CustomProvider.ready(p)) continue;
            String id=p.optString("id",""); if (id.isEmpty()) continue;
            Set<String> candidates=new java.util.LinkedHashSet<>(); JSONArray models=p.optJSONArray("models");
            for (int j=0; models!=null && j<models.length(); j++) { String m=models.optString(j,"").trim(); if (!m.isEmpty()) candidates.add(m); }
            JSONArray as=p.optJSONArray("accounts");
            for (int j=0; as!=null && j<as.length(); j++) {
                JSONObject a=as.optJSONObject(j); if (!enabled(a)) continue;
                JSONArray am=a.optJSONArray("models");
                for(int k=0; am!=null && k<am.length(); k++) { String m=am.optString(k,"").trim(); if(!m.isEmpty()) candidates.add(m); }
            }
            Set<String> gifts=new HashSet<>(); boolean giftOnly=false;
            if ("zcode".equals(id)) {
                JSONObject session=null;
                for(int j=0; as!=null && j<as.length(); j++) { JSONObject a=as.optJSONObject(j); if(enabled(a) && present(a,"jwt")) { session=a; break; } }
                if(session!=null) {
                    JSONObject summary=gift==null?null:gift.get(session.optString("jwt"),session.optString("device",""));
                    gifts.addAll(ZCodeGift.allowedModels(summary)); candidates.addAll(gifts);
                    giftOnly=session.optString("plan","").isEmpty();
                    if(giftOnly && summary==null) unknown++;
                }
            }
            QoderModelIdentity identities=QoderModelIdentity.of(p);
            for (String m:candidates) {
                if (CodexImages.PROVIDER.equals(id) && CodexImages.matches(m)) continue;
                boolean usable="zcode".equals(id) ? (gifts.contains(m) || (!giftOnly && accountUsable(p,m,now))) : accountUsable(p,m,now);
                if (!usable) continue;
                String full=id+"/"+m;
                available.put(full,new JSONObject().put("id",CustomProvider.isCustom(p)?customNames.publicId(p,m):id+"/"+identities.publicId(m)).put("name",identities.name(m)).put("provider",id).put("providerName",p.optString("name",id)).put("kind","model"));
            }
        }
        JSONArray result=new JSONArray();
        for(java.util.Map.Entry<String,JSONObject> e:available.entrySet())
            if(!filtered || contains(whitelist,e.getKey()) || contains(whitelist,e.getValue().optString("id"))) result.put(e.getValue());
        for (JSONObject media : miniMaxMedia(cfg)) result.put(media);
        JSONObject codex = codexImage(cfg, now, true);
        if (codex != null) result.put(codex);
        JSONObject routing=cfg.optJSONObject("routing"); JSONArray groups=cfg.optJSONArray("groups");
        if(routing!=null && routing.optBoolean("groupsEnabled",false)) for(int i=0; groups!=null && i<groups.length(); i++) {
            JSONObject g=groups.optJSONObject(i); if(g==null || !g.optBoolean("enabled",true)) continue;
            String id=g.optString("id",""); if(id.isEmpty()) continue; String full="group/"+id;
            if(filtered && !contains(whitelist,full)) continue;
            JSONArray targets=g.optJSONArray("targets"); boolean live=false;
            if ("manual".equals(g.optString("strategy", "order"))) {
                String pick=g.optString("pick", "");
                live=!pick.isEmpty() && contains(targets,pick) && available.containsKey(QoderModelIdentity.upstreamRef(cfg,customNames.canonical(pick)));
            } else {
                for(int j=0; targets!=null && j<targets.length(); j++) if(available.containsKey(QoderModelIdentity.upstreamRef(cfg,customNames.canonical(targets.optString(j,""))))) { live=true; break; }
            }
            if(live) result.put(new JSONObject().put("id",full).put("name",g.optString("name",id)).put("provider","group").put("providerName","路由组").put("kind","group"));
        }
        return new JSONObject().put("ok",true).put("models",result).put("observedAt",now).put("unconfirmedCatalogs",unknown);
    }
}
