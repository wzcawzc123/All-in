package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded diagnostic facts only. No request content, headers, tool names, credentials or retry decisions. */
final class CallEvidence {
    static final long MAX_WAIT_MS=21600000L;
    static final Set<String> PATHS=new HashSet<>(Arrays.asList("/api/test-model","/v1/chat/completions","/v1/messages","/v1/responses","/responses"));
    static final Set<String> EFFORTS=new HashSet<>(Arrays.asList("none","minimal","low","medium","high","xhigh","max","extra-high","extra-low"));
    static final Set<String> SOURCES=new HashSet<>(Arrays.asList("not_sent","upstream","send_unknown","gateway_cooldown"));
    static final Set<String> STATUSES=new HashSet<>(Arrays.asList("RESOURCE_EXHAUSTED","PERMISSION_DENIED","UNAUTHENTICATED","INVALID_ARGUMENT","NOT_FOUND","UNAVAILABLE","INTERNAL","DEADLINE_EXCEEDED","FAILED_PRECONDITION","ABORTED","CANCELLED","UNKNOWN","OUT_OF_RANGE","UNIMPLEMENTED","DATA_LOSS"));
    static final Set<String> REASONS=new HashSet<>(Arrays.asList("ACCESS_TOKEN_SCOPE_INSUFFICIENT","RATE_LIMIT_EXCEEDED","QUOTA_EXCEEDED","SERVICE_DISABLED","CONSUMER_INVALID","LOCATION_POLICY_VIOLATED","USER_PROJECT_DENIED","ACCOUNT_STATE_INVALID"));
    private CallEvidence(){}
    static String id(String s){return s!=null&&s.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")?s:null;}
    static final class Entry {
        final String path,method,requestId;
        Entry(String path,String method,String requestId){this.path=path;this.method=method;this.requestId=requestId;}
    }
    static Entry entry(String path,String method,String requestId){
        return PATHS.contains(path)&&"POST".equals(method)&&id(requestId)!=null?new Entry(path,method,requestId):null;
    }
    static String accountRef(String provider,JSONObject account){
        String id=account==null?"":account.optString("id","");
        if(id.isEmpty()||id.length()>256)return null;
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((provider+(char)0+id).getBytes(StandardCharsets.UTF_8));
            StringBuilder h=new StringBuilder("acct-");for(int i=0;i<12;i++)h.append(String.format(Locale.ROOT,"%02x",digest[i]&255));return h.toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    static final class Snapshot {
        private final String encoded;
        private Snapshot(JSONObject safe){encoded=safe.toString();}
        JSONObject json(){return new JSONObject(encoded);}
        static Snapshot parse(JSONObject input){
            if(input==null||input.optInt("schemaVersion")!=1)return null;
            String path=input.optString("path"),method=input.optString("method");
            if(!PATHS.contains(path)||!"POST".equals(method))return null;
            JSONObject safe=new JSONObject().put("schemaVersion",1).put("path",path).put("method",method)
                    .put("entry","/api/test-model".equals(path)?"panel-test":"model-api");
            for(String k:new String[]{"requestId","cooldownOriginRequestId"})safe.put(k,nullable(id(input.optString(k,null))));
            String ref=input.optString("accountRef","");safe.put("accountRef",ref.matches("acct-[a-f0-9]{24}")?ref:JSONObject.NULL);
            String model=input.optString("sentModel","");
            boolean modelSafe=model.matches("[a-zA-Z0-9][a-zA-Z0-9_.:/+-]{0,159}")&&!model.startsWith("eyJ")&&!model.startsWith("sk-");
            safe.put("sentModel",modelSafe?model:JSONObject.NULL);
            for(String k:new String[]{"messageCount","toolCount"})safe.put(k,number(input,k,1000000));
            safe.put("physicalSends",number(input,"physicalSends",8));
            safe.put("upstreamStatus",httpStatus(input,"upstreamStatus"));
            safe.put("cooldownOriginStatus",httpStatus(input,"cooldownOriginStatus"));
            safe.put("upstreamRetryAfterSeconds",number(input,"upstreamRetryAfterSeconds",21600));
            safe.put("cooldownOriginRetryAfterSeconds",number(input,"cooldownOriginRetryAfterSeconds",21600));
            String originStatus=input.optString("cooldownOriginGoogleStatus","");safe.put("cooldownOriginGoogleStatus",STATUSES.contains(originStatus)?originStatus:JSONObject.NULL);
            safe.put("localCooldownRemainingMs",number(input,"localCooldownRemainingMs",MAX_WAIT_MS));
            for(String k:new String[]{"systemPresent","stream"})safe.put(k,input.opt(k) instanceof Boolean?input.opt(k):JSONObject.NULL);
            String effort=input.optString("effort","");safe.put("effort",EFFORTS.contains(effort)?effort:JSONObject.NULL);
            String source=input.optString("source","");safe.put("source",SOURCES.contains(source)?source:JSONObject.NULL);
            String status=input.optString("googleStatus","");safe.put("googleStatus",STATUSES.contains(status)?status:JSONObject.NULL);
            String reason=input.optString("googleReason","");safe.put("googleReason",REASONS.contains(reason)?reason:JSONObject.NULL);
            safe.put("shapeStage","normalized_request");
            return new Snapshot(safe);
        }
    }
    private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
    private static Object httpStatus(JSONObject input,String key){Object n=number(input,key,599);return n instanceof Number&&((Number)n).longValue()>=100?n:JSONObject.NULL;}
    private static Object number(JSONObject j,String k,long max){
        Object n=j.opt(k);if(!(n instanceof Number))return JSONObject.NULL;
        double d=((Number)n).doubleValue();long value=((Number)n).longValue();return d==value&&value>=0&&value<=max?value:JSONObject.NULL;
    }
    static final class Builder {
        private final JSONObject facts;
        private Snapshot frozen;
        Builder(Entry entry,String provider,JSONObject account){facts=new JSONObject().put("schemaVersion",1).put("path",entry.path).put("method",entry.method)
                .put("requestId",entry.requestId).put("accountRef",nullable(accountRef(provider,account))).put("physicalSends",0).put("source","not_sent");}
        synchronized void shape(JSONObject payload,boolean stream){
            if(frozen!=null)return;
            JSONArray messages=payload.optJSONArray("messages"),tools=payload.optJSONArray("tools");boolean system=payload.has("system");
            if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&("system".equals(m.optString("role"))||"developer".equals(m.optString("role"))))system=true;}
            String effort=payload.optString("reasoning_effort","");JSONObject reasoning=payload.optJSONObject("reasoning");if(effort.isEmpty()&&reasoning!=null)effort=reasoning.optString("effort","");
            facts.put("messageCount",messages==null?0:messages.length()).put("toolCount",tools==null?0:tools.length())
                    .put("systemPresent",system).put("stream",stream).put("effort",effort.toLowerCase(Locale.ROOT));
        }
        synchronized void dispatched(String model,int sends){
            if(frozen!=null)return;
            int total=Math.min(8,facts.optInt("physicalSends")+Math.max(0,sends));facts.put("physicalSends",total);
            if(sends>0){
                facts.put("sentModel",model).put("source","send_unknown");
                for(String k:new String[]{"upstreamStatus","upstreamRetryAfterSeconds","googleStatus","googleReason"})facts.put(k,JSONObject.NULL);
            }
        }
        synchronized void reply(int status,String retry,String body){
            if(frozen!=null)return;
            String seconds=ProviderRetryPolicy.safeRetryAfter(retry);
            facts.put("upstreamStatus",status).put("upstreamRetryAfterSeconds",seconds==null?JSONObject.NULL:Long.valueOf(seconds)).put("source","upstream")
                    .put("googleStatus",JSONObject.NULL).put("googleReason",JSONObject.NULL);
            if(body!=null)try{
                JSONObject error=new JSONObject(body).optJSONObject("error");if(error==null)return;
                String google=error.optString("status","");if(STATUSES.contains(google))facts.put("googleStatus",google);
                JSONArray details=error.optJSONArray("details");if(details!=null)for(int i=0;i<Math.min(16,details.length());i++){
                    JSONObject d=details.optJSONObject(i);if(d!=null&&"type.googleapis.com/google.rpc.ErrorInfo".equals(d.optString("@type"))&&REASONS.contains(d.optString("reason"))){facts.put("googleReason",d.optString("reason"));break;}
                }
            }catch(Exception ignored){}
        }
        synchronized void cooldown(long remaining,Snapshot origin){
            if(frozen!=null)return;
            facts.put("source","gateway_cooldown").put("localCooldownRemainingMs",Math.min(MAX_WAIT_MS,Math.max(0,remaining)));
            if(origin!=null){JSONObject o=origin.json();facts.put("cooldownOriginRequestId",o.opt("requestId")).put("cooldownOriginStatus",o.opt("upstreamStatus"))
                    .put("cooldownOriginRetryAfterSeconds",o.opt("upstreamRetryAfterSeconds")).put("cooldownOriginGoogleStatus",o.opt("googleStatus"));}
        }
        synchronized Snapshot snapshot(){if(frozen==null)frozen=Snapshot.parse(facts);return frozen;}
    }
    static void freeze(UsageLedger.Row row){if(row==null)return;if(row.callEvidence==null&&row.callObservation!=null)row.callEvidence=row.callObservation.snapshot();row.callObservation=null;}
}
