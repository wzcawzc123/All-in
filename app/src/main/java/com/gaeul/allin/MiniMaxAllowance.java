package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;

/** Read-only MiniMax Code credits, independent of an optional CN M Plan. */
final class MiniMaxAllowance {
    private MiniMaxAllowance() { }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("interrupted");
    }
    private static JSONObject checked(JSONObject result) throws Exception {
        if (result == null || result.optInt("_status", 0) != 200) throw new IOException("MiniMax account unavailable");
        JSONObject body = result.optJSONObject("_body");
        if (body == null) throw new IOException("MiniMax account response invalid");
        JSONObject status = body.optJSONObject("statusInfo"), base = body.optJSONObject("base_resp");
        if ((status != null && status.optInt("code", -1) != 0)
                || (base != null && base.optInt("status_code", -1) != 0)) throw new IOException("MiniMax account refused");
        return body;
    }
    private static JSONObject data(JSONObject body) {
        JSONObject d = body.optJSONObject("data");
        return d == null ? body : d;
    }
    private static String extractDisplayName(JSONObject u, String uid) {
        if (u != null) {
            for (String key : new String[]{"userEmail", "email", "userMail", "user_email", "phone", "mobile", "name", "userName", "user_name"}) {
                String v = u.optString(key, "").trim();
                if (!v.isEmpty() && v.length() <= 80 && !v.matches("(?s).*[\\x00-\\x1f\\x7f].*")) {
                    if ("phone".equals(key) || "mobile".equals(key)) {
                        String m = RaccoonAccountService.maskPhone(v);
                        if (!m.isEmpty()) return m;
                    } else {
                        return v;
                    }
                }
            }
        }
        return uid == null ? "" : uid;
    }

    static JSONObject fetch(String access, String group, MiniMaxAuth.AllowanceConnectionFactory factory) throws Exception {
        if (access == null || access.isEmpty()) throw new IOException("MiniMax access unavailable");
        long now = System.currentTimeMillis();
        interrupted();
        JSONObject identity = checked(MiniMaxAuth.signedAllowanceGet("/v1/api/user/info", access, "", now, factory));
        JSONObject u = data(identity).optJSONObject("userInfo");
        if (u == null) u = data(identity).optJSONObject("user_info");
        String uid = u == null ? "" : u.optString("realUserID", u.optString("real_user_id", ""));
        if (!uid.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("MiniMax account identity unavailable");
        String displayName = extractDisplayName(u, uid);
        JSONArray details = new JSONArray();
        HashSet<String> pageBodies = new HashSet<>();
        int expected = -1;
        for (int page = 1; page <= 10; page++) {
            interrupted();
            String path = "/minimax-cloud/api/v1/credit/details?page_number=" + page + "&page_size=20&include_expired=true";
            JSONObject ledger = data(checked(MiniMaxAuth.signedAllowanceGet(path, access, uid, System.currentTimeMillis(), factory)));
            Object total = ledger.opt("total_count");
            JSONArray rows = ledger.optJSONArray("details");
            if (!(total instanceof Number) || ((Number) total).doubleValue() != ((Number) total).intValue()
                    || ((Number) total).intValue() < 0 || rows == null || rows.length() > 20)
                throw new IOException("MiniMax credits incomplete");
            int count = ((Number) total).intValue();
            if (expected >= 0 && count != expected) throw new IOException("MiniMax credits changed during pagination");
            expected = count;
            if (rows.length() > 0 && !pageBodies.add(rows.toString())) throw new IOException("MiniMax repeated credit page");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) throw new IOException("MiniMax credit row invalid");
                details.put(row);
            }
            if (details.length() > expected) throw new IOException("MiniMax credit count invalid");
            if (details.length() == expected) {
                JSONObject out = fromCredits(details, now);
                // Plan windows are additional metadata, never a reason to lose known credits.
                if (group != null && !group.isEmpty()) {
                    interrupted();
                    try {
                        JSONObject plan = ProviderAllowance.fromMiniMax(MiniMaxAuth.fetchAllowance(access, group, factory), now);
                        if (plan != null) {
                            out.put("windows", plan.getJSONArray("windows"));
                            if (plan.has("modelPools")) out.put("modelPools", plan.opt("modelPools"));
                        }
                    } catch (java.io.InterruptedIOException e) { if (Thread.currentThread().isInterrupted()) throw e; }
                      catch (Exception ignored) { }
                }
                if (!displayName.isEmpty()) out.put("_displayName", displayName);
                return out;
            }
            if (rows.length() == 0) throw new IOException("MiniMax credit page missing");
        }
        throw new IOException("MiniMax credits exceed bounded pagination");
    }
    private static BigDecimal amount(Object raw, boolean clampNegative) throws IOException {
        if (!(raw instanceof String) && !(raw instanceof Number)) throw new IOException("MiniMax credit amount unknown");
        String s = String.valueOf(raw).replace(",", "").trim();
        if (!s.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new IOException("MiniMax credit amount invalid");
        BigDecimal n = new BigDecimal(s);
        if (n.signum() < 0) {
            if (!clampNegative) throw new IOException("MiniMax credit grant invalid");
            n = BigDecimal.ZERO;
        }
        return n;
    }
    static JSONObject fromCredits(JSONArray rows, long now) throws Exception {
        if (rows == null) throw new IOException("MiniMax credit rows missing");
        BigDecimal sum = BigDecimal.ZERO,total=BigDecimal.ZERO,expiring=BigDecimal.ZERO;long soonest=0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw new IOException("MiniMax credit row invalid");
            Object type = row.opt("credit_type");
            if (!(type instanceof Number)) throw new IOException("MiniMax credit type unknown");
            int kind = ((Number) type).intValue();
            if (kind != 1 && kind != 2) continue;
            Object expires = row.opt("expire_at_ms");
            String expiry = expires == null ? "" : String.valueOf(expires);
            if (!expiry.matches("[0-9]{1,16}")) throw new IOException("MiniMax credit expiry unknown");
            long at = Long.parseLong(expiry);
            if (at <= now) continue;
            BigDecimal granted = amount(row.opt("granted_amount"), false);
            BigDecimal left = amount(row.opt("remaining_amount"), true).min(granted);
            sum = sum.add(left);total=total.add(granted);
            if(left.signum()>0){if(soonest==0||at<soonest){soonest=at;expiring=left;}else if(at==soonest)expiring=expiring.add(left);}
        }
        double remaining = sum.doubleValue();
        if (!Double.isFinite(remaining)) throw new IOException("MiniMax credit balance invalid");
        JSONObject out=new JSONObject().put("kind", "allowance").put("scope", "account").put("unit", "credits")
                .put("remaining", remaining).put("windows", new JSONArray()).put("observedAt", now);
        double cap=total.doubleValue();if(Double.isFinite(cap)&&cap>0)out.put("total",cap);
        if(soonest>now&&Double.isFinite(expiring.doubleValue()))out.put("runsOutAt",soonest).put("runsOut",expiring.doubleValue());
        return out;
    }
}
