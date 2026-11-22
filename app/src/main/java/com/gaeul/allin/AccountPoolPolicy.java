package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Account identity is provider-scoped; a display name is never an identity. */
final class AccountPoolPolicy {
    private AccountPoolPolicy() {}
    static String newId(String prefix) { return prefix + UUID.randomUUID().toString(); }
    private static String value(JSONObject account, String key) {
        return account == null ? "" : account.optString(key, "").trim();
    }
    private static boolean scope(JSONObject a, JSONObject b) {
        for (String key : new String[]{"site", "domain", "enterpriseId"})
            if (!value(a,key).equals(value(b,key))) return false;
        for (String key : new String[]{"authMode", "type"}) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && !y.isEmpty() && !x.equals(y)) return false;
        }
        return true;
    }
    private static boolean apiKeyAccount(JSONObject account) {
        return "apikey".equalsIgnoreCase(value(account, "authMode"))
                || "apikey".equalsIgnoreCase(value(account, "type"));
    }
    private static String apiKeyMaterial(JSONObject account) {
        for (String key : new String[]{"access", "token", "apiKey", "key"}) {
            String credential = value(account, key);
            if (!credential.isEmpty()) return credential;
        }
        return "";
    }
    static boolean same(JSONObject a, JSONObject b, String provider) {
        if (a == null || b == null || !scope(a,b)) return false;
        // Codex API keys have no verified OAuth owner: labels (including legacy email labels)
        // cannot identify them. Match complete credential material, within the existing scope.
        if ("codex".equals(provider) && (apiKeyAccount(a) || apiKeyAccount(b))) {
            if (!apiKeyAccount(a) || !apiKeyAccount(b)) return false;
            for (String key : new String[]{"accountId", "uid", "subject"}) {
                String x = value(a, key), y = value(b, key);
                if (!x.isEmpty() && !y.isEmpty() && !x.equals(y)) return false;
            }
            String x = apiKeyMaterial(a), y = apiKeyMaterial(b);
            return !x.isEmpty() && x.equals(y);
        }
        String[] identity = "codex".equals(provider)
                ? new String[]{"accountId", "uid", "subject", "githubLogin", "email"}
                : new String[]{"uid", "userId", "userID", "subject", "accountId", "githubLogin", "login", "email"};
        // The most authoritative shared field wins. Never merge a conflicting uid by matching email.
        for (String key : identity) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && !y.isEmpty())
                return ("email".equals(key) || "githubLogin".equals(key) || "login".equals(key)) ? x.equalsIgnoreCase(y) : x.equals(y);
        }
        // With no verified owner, only identical credential material can identify a re-import.
        for (String key : new String[]{"token", "access", "refresh", "jwt"}) {
            String x=value(a,key), y=value(b,key);
            if (!x.isEmpty() && x.equals(y)) return true;
        }
        return false;
    }
    static JSONObject addOrUpdate(JSONArray accounts, JSONObject incoming, String provider) {
        JSONObject found=null;
        for (int i=0;i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if (same(account,incoming,provider)) {
                if (found!=null) throw new IllegalArgumentException("Ambiguous account identity; resolve duplicate records first");
                found=account;
            }
        }
        if (found==null) {
            String id=value(incoming,"id"); boolean duplicate=id.isEmpty();
            for (int i=0;i<accounts.length();i++)
                if (id.equals(value(accounts.optJSONObject(i),"id"))) duplicate=true;
            if (duplicate) incoming.put("id",newId(provider+"_"));
            if (!incoming.has("addedAt")) incoming.put("addedAt",System.currentTimeMillis());
            incoming.put("enabled",true);
            accounts.put(incoming);
            return incoming;
        }
        Iterator<String> names=incoming.keys();
        while (names.hasNext()) {
            String key=names.next();
            if ("id".equals(key) || "addedAt".equals(key)) continue;
            found.put(key,incoming.opt(key));
        }
        if (value(found,"id").isEmpty()) found.put("id",newId(provider+"_"));
        // Called only by explicit sign-in/import, never by token refresh or catalog synchronization.
        found.put("enabled",true).put("updatedAt",System.currentTimeMillis());
        return found;
    }
    /** Eligible persisted accounts plus independent legacy keys (not disabled account mirrors). */
    static java.util.List<JSONObject> routingAccounts(JSONObject provider) {
        java.util.List<JSONObject> result=new java.util.ArrayList<>();
        Set<String> owned=new HashSet<>();
        JSONArray accounts=provider.optJSONArray("accounts");
        for(int i=0;accounts!=null && i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if(account==null)continue;
            for(String field:new String[]{"token","access"}) {String token=value(account,field);if(!token.isEmpty())owned.add(token);}
            String token=value(account,"access");if(token.isEmpty())token=value(account,"token");
            if(AccountGenerations.enabled(account) && !token.isEmpty())result.add(account);
        }
        JSONArray keys=provider.optJSONArray("keys");
        String providerId=provider.optString("id","");
        for(CommercialKeyPool.Credential key : CommercialKeyPool.list(keys)) {
            String token=key.secret();
            // A Codex subscription bearer needs its saved account context. A legacy
            // OpenAI API key is independent and can still be routed without an account.
            if ("codex".equals(providerId) && !token.startsWith("sk-")) continue;
            if(token.isEmpty() || !owned.add(token))continue;
            String id="legacy-key_"+UUID.nameUUIDFromBytes((providerId+"\u0000"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String type="workbuddy".equals(providerId) && !token.startsWith("ck_") && !token.startsWith("pt_")?"token":"api";
            JSONObject slot=new JSONObject().put("id",id).put("token",token).put("type",type).put("source","legacy-key").put("enabled",true);
            JSONObject catalogs=provider.optJSONObject("legacyKeyCatalog");
            JSONObject catalog=catalogs==null?null:catalogs.optJSONObject(id);
            if(catalog!=null && catalog.optJSONArray("models")!=null) {
                slot.put("models",catalog.get("models"));
                if(catalog.has("modelMeta"))slot.put("modelMeta",catalog.get("modelMeta"));
            }
            result.add(slot);
        }
        return result;
    }

    static JSONObject upsert(JSONObject provider, JSONObject incoming, String providerId) {
        JSONArray accounts=provider.optJSONArray("accounts");
        if (accounts==null) { accounts=new JSONArray(); provider.put("accounts",accounts); }
        JSONArray before=new JSONArray(accounts.toString());
        JSONObject saved=addOrUpdate(accounts,incoming,providerId);
        synchronizeKeys(provider,before);
        return saved;
    }
    /** Remove account records and only the key mirrors owned by those records. */
    static int removeAccounts(JSONObject provider, String id) {
        if (provider == null || id == null || id.trim().isEmpty()) throw new IllegalArgumentException("account id required");
        JSONArray before = provider.optJSONArray("accounts"), kept = new JSONArray();
        int removed = 0;
        for (int i=0; before!=null && i<before.length(); i++) {
            Object raw=before.opt(i);
            JSONObject account=raw instanceof JSONObject ? (JSONObject)raw : null;
            if ("all".equals(id) || (account!=null && id.equals(account.optString("id")))) removed++;
            else kept.put(raw);
        }
        if (removed>0) {
            provider.put("accounts",kept);
            synchronizeKeys(provider,before);
        }
        return removed;
    }

    /** Replace only account-owned key mirrors; preserve independent manually configured keys. */
    static void synchronizeKeys(JSONObject provider, JSONArray before) {
        Set<String> previouslyOwned=new HashSet<>();
        for (int i=0;before!=null && i<before.length();i++) {
            JSONObject account=before.optJSONObject(i);
            for (String field:new String[]{"access","token","jwt","accessToken","access_token","apiKey"}) {
                String token=value(account,field);
                if (!token.isEmpty()) previouslyOwned.add(token);
            }
        }
        JSONArray keys=new JSONArray(), old=provider.optJSONArray("keys");
        Set<String> seen=new HashSet<>();
        for (int i=0;old!=null && i<old.length();i++) {
            Object key=old.opt(i);
            if (key instanceof String) {
                String text=((String)key).trim();
                if (!text.isEmpty() && !previouslyOwned.contains(text) && seen.add(text)) keys.put(key);
            } else if (key instanceof JSONObject) {
                Object raw=((JSONObject)key).opt("key");
                if (raw instanceof String && !previouslyOwned.contains(((String)raw).trim())) keys.put(key);
            }
        }
        JSONArray accounts=provider.optJSONArray("accounts");
        for (int i=0;accounts!=null && i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if (!AccountGenerations.enabled(account)) continue;
            String token=value(account,"access"); if (token.isEmpty()) token=value(account,"token");
            if (!token.isEmpty() && seen.add(token)) keys.put(token);
        }
        provider.put("keys",keys);
    }
}
