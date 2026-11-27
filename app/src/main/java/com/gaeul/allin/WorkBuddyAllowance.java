package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read-only account meter. Fixed origins; no chat, check-in, key guessing or redirects. */
final class WorkBuddyAllowance {
    static final String SUMMARY="/billing/meter/get-user-resource-summary";
    static final String ENTERPRISE="/billing/meter/get-enterprise-user-usage";
    static final String ACCOUNTS="/v2/plugin/accounts";
    static final class Failure extends IOException {
        final String kind; final boolean invalidToken;
        Failure(String kind, boolean invalidToken) { super("WorkBuddy allowance "+kind); this.kind=kind; this.invalidToken=invalidToken; }
    }
    private WorkBuddyAllowance() {}
    static boolean eligible(JSONObject a) {
        String key=a.optString("access",a.optString("token",""));
        return !"api".equals(a.optString("type")) && !key.startsWith("ck_") && !key.startsWith("pt_");
    }
    private static boolean safeDisplay(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.isEmpty() || s.length() > 80) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch == 127) return false;
        }
        return true;
    }

    /** Best-effort read-only nickname lookup via /v2/plugin/accounts; never alters routing or credentials. */
    static String fetchNickname(String provider, JSONObject a, WorkBuddyLogin.ConnectionFactory factory) {
        if (a == null || !eligible(a)) return "";
        String existing = a.optString("nickname", "").trim();
        if (safeDisplay(existing)) return existing;
        HttpURLConnection c = null;
        try {
            String key = a.optString("access", a.optString("token", ""));
            if (key.isEmpty()) return "";
            String url = WorkBuddyAuth.origin(provider) + ACCOUNTS;
            Map<String, String> headers = WorkBuddyPolicy.catalogHeaders(provider, WorkBuddyAuth.origin(provider) + "/v3/config", key, a);
            if (headers.isEmpty()) return "";
            c = factory.open(url);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) return "";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 4000000000L;
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || bytes.size() + n > 262144 || System.nanoTime() > deadline) return "";
                    bytes.write(buffer, 0, n);
                }
            }
            JSONObject env = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONObject data = env.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("accounts") : env.optJSONArray("accounts");
            if (list == null || list.length() == 0) return "";
            String wantUid = a.optString("uid", "").trim();
            String wantEnt = WorkBuddyLogin.enterpriseValue(a);
            JSONObject matched = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row == null) continue;
                String rUid = row.optString("uid", "").trim();
                String rEnt = row.optString("enterpriseId", "").trim();
                if (!wantUid.isEmpty() && wantUid.equals(rUid)) {
                    if (wantEnt.isEmpty() || wantEnt.equals(rEnt)) { matched = row; break; }
                    if (matched == null) matched = row;
                } else if (wantUid.isEmpty() && (row.optBoolean("lastLogin", false) || list.length() == 1)) {
                    matched = row;
                }
            }
            if (matched == null && list.length() == 1) matched = list.optJSONObject(0);
            if (matched == null) return "";
            String nick = matched.optString("nickname", "").trim();
            if (safeDisplay(nick)) return nick;
            String phone = matched.optString("phoneNumber", "").trim();
            if (safeDisplay(phone)) return phone;
            return "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static JSONObject fetch(String provider, JSONObject a, WorkBuddyLogin.ConnectionFactory factory) throws Exception {
        return fetch(provider,a,factory,false);
    }
    static JSONObject fetch(String provider, JSONObject a, WorkBuddyLogin.ConnectionFactory factory,boolean expiryDetails) throws Exception {
        if(!eligible(a)) throw new Failure("key_not_supported",false);
        String path=WorkBuddyLogin.enterpriseValue(a).isEmpty()?SUMMARY:ENTERPRISE;
        String url=WorkBuddyAuth.origin(provider)+path;
        String key=a.optString("access",a.optString("token",""));
        if(key.isEmpty())throw new Failure("sign_in_required",false);
        // Catalog header policy already validates account values and enterprise dual headers.
        Map<String,String> headers=WorkBuddyPolicy.catalogHeaders(provider,WorkBuddyAuth.origin(provider)+"/v3/config",key,a);
        if(headers.isEmpty())throw new Failure("sign_in_required",false);
        HttpURLConnection c=null;
        try {
            c=factory.open(url); c.setInstanceFollowRedirects(false); c.setRequestMethod("POST");
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            c.setRequestProperty("Content-Type","application/json"); c.setDoOutput(true); c.setFixedLengthStreamingMode(2);
            try(OutputStream out=c.getOutputStream()){out.write(new byte[]{'{','}'});}
            int status=c.getResponseCode();
            if(status<200 || status>=300) {
                String challenge=c.getHeaderField("WWW-Authenticate");
                boolean invalidToken=status==401 && challenge!=null && challenge.toLowerCase(java.util.Locale.ROOT).contains("invalid_token");
                throw new Failure(invalidToken?"sign_in_required":"unavailable",invalidToken);
            }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); long deadline=System.nanoTime()+5000000000L;
            try(InputStream in=c.getInputStream()) {
                byte[] buffer=new byte[4096]; int n;
                while((n=in.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Interrupted");
                    if(bytes.size()+n>1048576 || System.nanoTime()>deadline)throw new Failure("unavailable",false);
                    bytes.write(buffer,0,n);
                }
            }
            JSONObject env=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            if(env.optInt("code",-1)!=0)throw new Failure("unavailable",false);
            JSONObject data=env.optJSONObject("data");
            JSONObject allowance=ENTERPRISE.equals(path)?enterprise(data):summary(data);
            if(expiryDetails && SUMMARY.equals(path) && allowance!=null) {
                try { allowance=withExpiry(allowance,data,packages(provider,headers,factory),System.currentTimeMillis(),"workbuddy-ai".equals(provider)); }
                catch(Exception unavailable) { allowance.put("expiryKnown",false); }
            }
            return allowance;
        } finally { if(c!=null)c.disconnect(); }
    }
    private static Double number(Object v) {
        if(!(v instanceof Number) && !(v instanceof String))return null;
        try { double n=Double.parseDouble(String.valueOf(v)); return Double.isFinite(n) && n>=0?n:null; }
        catch(Exception ignored){return null;}
    }
    private static JSONObject base() {
        return new JSONObject().put("kind","allowance").put("scope","account").put("unit","credits")
            .put("observedAt",System.currentTimeMillis()).put("windows",new JSONArray());
    }
    private static JSONObject window(String name,double total,double left) {
        return new JSONObject().put("name",name).put("remaining",left).put("total",total).put("unit","credits")
            .put("remainingPercent",100*left/total);
    }
    static JSONObject summary(JSONObject sum) {
        if(sum==null)return null;
        JSONArray groups=sum.optJSONArray("CreditGroups"), packages=sum.optJSONArray("Packages");
        JSONArray rows=groups!=null && groups.length()>0?groups:packages;
        double total=0,left=0,balance=0; boolean known=false; JSONArray windows=new JSONArray();
        for(int i=0;rows!=null && i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)continue;
            Double t=number(row.opt("CycleTotalCapacity")), u=number(row.opt("CycleUsedCapacity")), r=number(row.opt("CycleRemainCapacity"));
            if(t==null || t<=0)continue;
            if(r==null && u!=null && u<=t)r=t-u;
            if(r==null || r>t || (u!=null && u>t))return null; // unknown is never zero
            if(total+t==Double.POSITIVE_INFINITY || left+r==Double.POSITIVE_INFINITY)return null;
            total+=t; left+=r; known=true;
            if(groups==rows)windows.put(window("plan_base".equals(row.optString("Kind"))?"Plan credits":"Extra credits",t,r));
        }
        // Prepaid balances have no denominator; do not double-count groups + packages.
        for(int i=0;packages!=null && i<packages.length();i++) {
            JSONObject row=packages.optJSONObject(i); if(row==null)continue;
            Object raw=row.opt("CycleTotalCapacity");
            if(raw!=null && raw!=JSONObject.NULL && !"".equals(raw))continue;
            Double r=number(row.opt("CycleRemainCapacity"));
            if(r!=null){balance+=r;known=true;}
        }
        if(!known || !Double.isFinite(left+balance))return null;
        JSONObject out=base().put("remaining",left+balance);
        if(windows.length()>0)out.put("windows",windows);
        else if(total>0)out.optJSONArray("windows").put(window("Credits",total,left));
        if(balance>0 || total==0)out.optJSONArray("windows").put(new JSONObject().put("name","Prepaid credits").put("remaining",balance).put("unit","credits"));
        if(balance==0&&total>0) {
            out.put("total",total);
            for(int i=0;i<out.getJSONArray("windows").length();i++)out.getJSONArray("windows").getJSONObject(i).put("aside",true);
        }
        return out;
    }
    static JSONObject enterprise(JSONObject data) {
        if(data==null)return null;
        Double total=number(data.opt("limitNum")), used=number(data.opt("credit"));
        if(total==null || total<=0 || used==null || used>total)return null;
        double left=total-used; JSONObject out=base().put("remaining",left);
        JSONObject w=window("Team credits",total,left);
        long reset=AccountRoutingPolicy.time(data.opt("cycleResetTime"));if(reset>0)w.put("resetAt",reset);
        out.optJSONArray("windows").put(w); return out;
    }

    private static final String[] PAID={"002_AkiJS3ZHF5","005_maRGyrHhw1","003_FAnt7lcmRT","023_4xbGhMrE6q","026_BaESVICNoi","027_0FCGVA6vSa","043_6oIkxT7Z0S","009_0XmEQc2xOf","038_OhvqZtiPKr","036_lupO5WgNdG"};
    private static final String[] FREE={"001_PqouKr6QWV","008_cfWoLwvjU4","035_ArVxJcGDsm","006_DbXS0lrypC","039_KRcQj7wUat","040_mi9rCYg46x","007_nzdH5h4Nl0","028_NtpWi0jzXs","037_WxOD3MpI2o","029_6wCGEWquYy","030_BjSt89qTvr"};
    private static JSONArray packages(String provider,Map<String,String> headers,WorkBuddyLogin.ConnectionFactory factory)throws Exception {
        JSONArray out=new JSONArray();long deadline=System.nanoTime()+12000000000L;int textBudget=2*1048576;
        for(int kind=0;kind<2;kind++) {
            JSONArray codes=new JSONArray();for(String code:kind==0?PAID:FREE)codes.put("TCACA_code_"+code);
            for(int page=1;page<=10;page++) {
                long left=(deadline-System.nanoTime())/1000000;if(left<=0)throw new Failure("unavailable",false);
                JSONObject body=new JSONObject().put("PackageCodes",codes).put("PageNumber",page).put("PageSize",200).put("Status",new JSONArray().put(0).put(3));
                if(kind==0)body.put("NeedRenewInfo",true);
                else {java.text.SimpleDateFormat df=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US);df.setTimeZone(java.util.TimeZone.getTimeZone("GMT+08:00"));String day=df.format(new java.util.Date());body.put("SlicePeriodStartTime",day+" 00:00:00").put("SlicePeriodEndTime",day+" 23:59:59");}
                String path="/billing/meter/get-user-resource-"+(kind==0?"paid":"free")+"-packages";
                HttpURLConnection c=factory.open(WorkBuddyAuth.origin(provider)+path);
                try {
                    c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout((int)Math.min(4000,left));c.setReadTimeout((int)Math.min(4000,left));
                    for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
                    c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
                    try(OutputStream os=c.getOutputStream()){os.write(bytes);}
                    if(c.getResponseCode()!=200)throw new Failure("unavailable",false);
                    String text=BoundedResponse.readUtf8(c.getInputStream(),1048576,Math.max(1,(deadline-System.nanoTime())/1000000));textBudget-=text.length();if(textBudget<0)throw new Failure("incomplete",false);JSONObject env=new JSONObject(text);
                    JSONObject data=env.optJSONObject("data");if(env.optInt("code",-1)!=0||data==null||data.optJSONArray("Accounts")==null)throw new Failure("unavailable",false);
                    JSONArray rows=data.getJSONArray("Accounts");if(rows.length()>200||out.length()+rows.length()>2000)throw new Failure("incomplete",false);for(int i=0;i<rows.length();i++)out.put(rows.opt(i));
                    if(rows.length()<200||page*200>=data.optInt("TotalCount",Integer.MAX_VALUE))break;
                    if(page==10)throw new Failure("incomplete",false);
                }finally{c.disconnect();}
            }
        }
        return out;
    }
    static JSONObject withExpiry(JSONObject allowance,JSONObject sum,JSONArray packages,long now) {return withExpiry(allowance,sum,packages,now,false);}
    static JSONObject withExpiry(JSONObject allowance,JSONObject sum,JSONArray packages,long now,boolean international) {
        JSONObject out=new JSONObject(allowance.toString());
        java.util.Set<String> counted=new java.util.HashSet<>();JSONArray summarized=sum.optJSONArray("Packages");
        for(int i=0;summarized!=null&&i<summarized.length();i++){JSONObject p=summarized.optJSONObject(i);if(p!=null)counted.add(p.optString("PackageCode"));}
        JSONArray ws=out.optJSONArray("windows");double total=0,left=0;
        for(int i=0;ws!=null&&i<ws.length();i++){JSONObject w=ws.optJSONObject(i);Double t=w==null?null:number(w.opt("total")),r=w==null?null:number(w.opt("remaining"));if(t!=null&&r!=null){total+=t;left+=r;}}
        java.util.Set<String> extra=new java.util.HashSet<>();
        for(String code:new String[]{"028_NtpWi0jzXs","037_WxOD3MpI2o","009_0XmEQc2xOf","038_OhvqZtiPKr","036_lupO5WgNdG","007_nzdH5h4Nl0","029_6wCGEWquYy","030_BjSt89qTvr"})extra.add("TCACA_code_"+code);
        if(international){extra.add("TCACA_code_008_cfWoLwvjU4");extra.add("TCACA_code_006_DbXS0lrypC");}
        double detailTotal=0,detailLeft=0;java.util.Set<JSONObject> includedExtras=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<JSONObject,Boolean>());
        // Extra rewards missing from summary.Packages are real credits too (fixed plugin's
        // listedExtra). Never infer them from TotalCredits or add summary packages twice.
        for(int i=0;packages!=null&&i<packages.length();i++){
            JSONObject p=packages.optJSONObject(i);if(p==null||!extra.contains(p.optString("PackageCode"))||counted.contains(p.optString("PackageCode")))continue;
            long end=AccountRoutingPolicy.time(p.opt("DeductionEndTime"));if(end<=0)end=AccountRoutingPolicy.time(p.opt("ExpiredTime"));if(end<=0)end=AccountRoutingPolicy.time(p.opt("CycleEndTime"));if(end>0&&end<=now)continue;
            boolean slice="4".equals(String.valueOf(p.opt("CapacityType")));JSONArray parts=p.optJSONArray("SlicePeriodUsageDetails");JSONObject values=slice?(parts==null?null:parts.optJSONObject(0)):p;
            Double t=values==null?null:number(values.opt(slice?"SlicePeriodCapacitySizePrecise":"CycleCapacitySizePrecise"));
            Double r=values==null?null:number(values.opt(slice?"SlicePeriodCapacityRemainPrecise":"CycleCapacityRemainPrecise"));
            if(t!=null&&r!=null&&t>0&&r<=t){detailTotal+=t;detailLeft+=r;includedExtras.add(p);}
        }
        if(detailTotal>0&&Double.isFinite(detailTotal)&&Double.isFinite(detailLeft)){
            total+=detailTotal;left+=detailLeft;ws.put(window("Listed extra credits",detailTotal,detailLeft));
            out.put("remaining",out.optDouble("remaining",0)+detailLeft);
        }
        long soonest=0,reset=0;double expiring=0;
        String subscription=sum.optString("SubscriptionPackageCode");
        for(int i=0;packages!=null&&i<packages.length();i++) {
            JSONObject p=packages.optJSONObject(i);if(p==null)continue;
            String code=p.optString("PackageCode");boolean slice="4".equals(String.valueOf(p.opt("CapacityType")));
            if((!subscription.isEmpty()&&subscription.equals(code))||(subscription.isEmpty()&&slice)) {long cycle=AccountRoutingPolicy.time(p.opt("CycleEndTime"));if(cycle>now)reset=cycle;}
            if(slice||"TCACA_code_001_PqouKr6QWV".equals(code))continue;
            Double r=number(p.has("CycleCapacityRemainPrecise")?p.opt("CycleCapacityRemainPrecise"):p.opt("CycleCapacityRemain"));
            long end=AccountRoutingPolicy.time(p.opt("DeductionEndTime"));if(end<=0)end=AccountRoutingPolicy.time(p.opt("ExpiredTime"));if(end<=0)end=AccountRoutingPolicy.time(p.opt("CycleEndTime"));
            if(r==null||r<=0||end<=now||(!counted.contains(code)&&!includedExtras.contains(p)))continue;
            if(soonest==0||end<soonest){soonest=end;expiring=r;}else if(end==soonest)expiring+=r;
        }
        if(total>0&&Double.isFinite(total)&&Double.isFinite(left)) {
            JSONObject all=window("Total credits",total,left);if(reset>now)all.put("resetAt",reset);
            if(soonest>now&&Double.isFinite(expiring))all.put("runsOutAt",soonest).put("runsOut",Math.min(left,expiring));
            JSONArray combined=new JSONArray().put(all);for(int i=0;ws!=null&&i<ws.length();i++)combined.put(ws.getJSONObject(i).put("aside",true));out.put("windows",combined);
        }
        if(out.has("total"))out.put("total",total);
        return out.put("expiryKnown",soonest>now||reset>now);
    }
}
