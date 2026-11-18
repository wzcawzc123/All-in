package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** Provider-local ordering. No credentials, network calls, replay decisions or background loops here.
 * References must be stable opaque account/key identities. Unknown is never exhausted.
 * Quota proportions and expiry pressure follow Magpie's usage/pace split; token fallback uses
 * the existing bounded, atomic recent-usage scheduler (including in-flight reservations).
 */
final class AccountRoutingPolicy {
    static final long FRESH_MS=120000L;
    private final RecentUsageScheduler recent;
    private final LinkedHashMap<String,Reading> readings=new LinkedHashMap<>();
    private static final class Reading {
        final String fingerprint; final JSONObject allowance; final long at;
        Reading(String f,JSONObject a,long t){fingerprint=f;allowance=a;at=t;}
    }
    static final class Seat {
        final String ref, fingerprint;
        Seat(String ref,String fingerprint){this.ref=ref;this.fingerprint=fingerprint;}
    }
    AccountRoutingPolicy(){recent=new RecentUsageScheduler();}
    AccountRoutingPolicy(RecentUsageScheduler.Clock clock){recent=new RecentUsageScheduler(clock);}
    static boolean valid(String mode){return "rotate".equals(mode)||"usage".equals(mode)||"pace".equals(mode);}
    static String mode(JSONObject provider){String m=provider==null?"rotate":provider.optString("accountRouting","rotate");return valid(m)?m:"rotate";}
    synchronized void observe(Seat seat,JSONObject allowance,long now){
        if(allowance==null){readings.remove(seat.ref);return;}
        if(readings.size()>=512&&!readings.containsKey(seat.ref))readings.remove(readings.keySet().iterator().next());
        readings.put(seat.ref,new Reading(seat.fingerprint,new JSONObject(allowance.toString()),now));
    }
    /** XunE: 该账号是否「明确」额度耗尽。
     *
     *  只有读到新鲜读数、且 remaining<=0（或百分比为0）才返回 true。
     *  读不到、读数过期、没有读数，一律返回 false —— 未知绝不等于耗尽，
     *  否则所有读不到余额的供应商会被永久跳过。
     *  额度充值/领取后，下一次 observe 写入正数即自动恢复参与。 */
    synchronized boolean depleted(Seat seat, String model, long now) {
        JSONObject a = fresh(seat, now);
        if (a == null) return false;                       // 未知或过期 → 不算耗尽
        // 直接看原始数值：nonnegative() 会把负数吞成 null，这里必须能识别负数余额。
        Object raw = a.opt("remaining");
        if (raw instanceof Number) {
            double n = ((Number) raw).doubleValue();
            if (Double.isFinite(n)) return n <= 0;         // 明确 0 或负 → 耗尽
        }
        Double pct = remainingPercent(a, model, now);      // 只有百分比时按百分比
        return pct != null && pct <= 0;
    }

    private JSONObject fresh(Seat seat,long now){
        Reading r=readings.get(seat.ref);
        if(r==null||!r.fingerprint.equals(seat.fingerprint)||now<r.at||now-r.at>FRESH_MS)return null;
        return r.allowance;
    }
    private static Double nonnegative(Object value){
        if(!(value instanceof Number))return null;
        double n=((Number)value).doubleValue();return Double.isFinite(n)&&n>=0?n:null;
    }
    /** Numeric seconds/ms and strictly parsed vendor dates; never a credential expiry. */
    static long time(Object raw){
        if(raw instanceof Number){double n=((Number)raw).doubleValue();if(!Double.isFinite(n)||n<=0||n>Long.MAX_VALUE/2d)return 0;return (long)(n<1e12?n*1000:n);}
        if(!(raw instanceof String))return 0;
        String s=((String)raw).trim();if(s.length()>64)return 0;
        if(s.matches("[0-9]{10,13}"))try{return time(Long.valueOf(s));}catch(Exception e){return 0;}
        String[] formats={"yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd HH:mm:ss","yyyy-MM-dd"};
        for(String f:formats){SimpleDateFormat parser=new SimpleDateFormat(f,Locale.US);parser.setLenient(false);parser.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));ParsePosition pos=new ParsePosition(0);Date d=parser.parse(s,pos);if(d!=null&&pos.getIndex()==s.length())return d.getTime();}
        return 0;
    }
    static Double remainingPercent(JSONObject a,String model,long now){
        if(a==null)return null;
        JSONArray windows=windows(a,model); Double least=null;
        Double whole=nonnegative(a.opt("total")),remaining=nonnegative(a.opt("remaining"));
        if(whole!=null&&whole>0&&remaining!=null&&remaining<=whole)least=100*remaining/whole;
        for(int i=0;windows!=null&&i<windows.length();i++){
            JSONObject w=windows.optJSONObject(i);if(w==null||w.optBoolean("aside",false)||!applies(w,model))continue;
            long end=time(w.opt("resetAt"));if(end>0&&end<=now)continue;
            Double n=nonnegative(w.opt("remainingPercent"));
            if(n==null){Double r=nonnegative(w.opt("remaining")),t=nonnegative(w.opt("total"));if(r!=null&&t!=null&&t>0&&r<=t)n=100*r/t;}
            if(n!=null&&n<=100)least=least==null?n:Math.min(least,n);
        }
        return least;
    }
    private static boolean applies(JSONObject w,String model){return !"model".equals(w.optString("scope"))||model.equals(w.optString("name"));}
    private static JSONArray windows(JSONObject a,String model){
        JSONArray pools=a.optJSONArray("modelPools");
        for(int i=0;pools!=null&&i<pools.length();i++){JSONObject p=pools.optJSONObject(i);if(p!=null&&model.toLowerCase(Locale.ROOT).startsWith(p.optString("name").toLowerCase(Locale.ROOT)))return p.optJSONArray("windows");}
        return a.optJSONArray("windows");
    }
    /** Largest share lost per hour. A stated expiring amount only counts that amount.
     * Unknown amounts never become a made-up expiry. Resets use the stated remainder.
     */
    static double pressure(JSONObject a,String model,long now){
        if(a==null)return -1;
        JSONArray ws=windows(a,model);double best=-1;
        Double whole=nonnegative(a.opt("total")),expiring=nonnegative(a.opt("runsOut"));long expiresAt=time(a.opt("runsOutAt"));
        if(whole!=null&&whole>0&&expiring!=null&&expiring>0&&expiresAt>now)best=Math.min(100,100*expiring/whole)/Math.max(1,(expiresAt-now)/3600000d);
        for(int i=0;ws!=null&&i<ws.length();i++){
            JSONObject w=ws.optJSONObject(i);if(w==null||w.optBoolean("aside",false)||!applies(w,model))continue;
            Double total=nonnegative(w.opt("total")),left=nonnegative(w.opt("remaining")),pct=nonnegative(w.opt("remainingPercent"));
            if(pct==null&&total!=null&&total>0&&left!=null&&left<=total)pct=100*left/total;
            if(pct==null||pct>100||pct<=0)continue;
            long reset=time(w.opt("resetAt")),expires=time(w.opt("runsOutAt"));
            Double amount=nonnegative(w.opt("runsOut"));
            if(expires>now&&amount!=null&&amount>0&&total!=null&&total>0){double share=Math.min(pct,100*amount/total);best=Math.max(best,share/Math.max(1,(expires-now)/3600000d));}
            // A short throughput window is a limit, not expiring credits. Only spend a
            // short one by its reset when the provider actually calls it a daily budget.
            String name=w.optString("name","").toLowerCase(Locale.ROOT);
            boolean throughput=name.contains("hour")||name.equals("interval")||name.equals("primary_window")||name.equals("five_hour");
            if(reset>now&&!throughput&&!(expires==reset&&amount!=null))best=Math.max(best,pct/Math.max(1,(reset-now)/3600000d));
        }
        return best;
    }
    /** Atomic reservation also spreads concurrent first requests before any response exists. */
    synchronized RecentUsageScheduler.Lease select(String mode,List<Seat> seats,String model,long now){
        if(seats.isEmpty())return null;
        List<String> all=new ArrayList<>(),ranked=new ArrayList<>();for(Seat s:seats)all.add(s.ref);
        if("pace".equals(mode)){
            double best=-1;
            for(Seat s:seats){double p=pressure(fresh(s,now),model,now);if(p>best){best=p;ranked.clear();}if(p>=0&&Double.compare(p,best)==0)ranked.add(s.ref);}
        }
        if(ranked.isEmpty()){
            // Compare quotas only when every candidate has a fresh reading in this unit.
            // With incomplete coverage use the same token metric for all candidates.
            double most=-1;boolean complete=true;
            for(Seat s:seats){Double n=remainingPercent(fresh(s,now),model,now);if(n==null){complete=false;break;}most=Math.max(most,n);}
            if(complete)for(Seat s:seats){Double n=remainingPercent(fresh(s,now),model,now);if(n>=most-0.5)ranked.add(s.ref);}
        }
        return recent.pickAndReserve(ranked.isEmpty()?all:ranked);
    }
    synchronized String basis(List<Seat> seats,String model,long now,String mode){
        if("rotate".equals(mode))return "requests";
        if("pace".equals(mode))for(Seat s:seats)if(pressure(fresh(s,now),model,now)>=0)return "expiry";
        boolean all=!seats.isEmpty();for(Seat s:seats)if(remainingPercent(fresh(s,now),model,now)==null)all=false;
        return all?"allowance":"recent_tokens";
    }
}
