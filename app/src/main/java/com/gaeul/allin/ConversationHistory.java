package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded exact reply-lineage fallback for clients without conversation IDs.
 * First prompts are NEVER keys. Only a history containing a successfully delivered assistant
 * reply may continue a lineage. Identical independent histories are marked ambiguous and bypassed.
 * The index retains HMACs and opaque IDs only, never prompts, responses or credentials.
 */
final class ConversationHistory {
    static final int MAX_MESSAGES=512, MAX_BYTES=1024*1024, CAPACITY=4096;
    static final long TTL=30L*24*60*60*1000;
    static final class Context {
        final String identity, scope; final JSONArray history;
        final String reservation=UUID.randomUUID().toString();final Set<String> provisionalKeys=new HashSet<>();
        boolean matchedPending;
        Context(String id,String scope,JSONArray history){this.identity=id;this.scope=scope;this.history=history;}
    }
    private static final class Entry {
        String identity;long at;String pendingOwner="";
        Entry(String identity,long at){this.identity=identity;this.at=at;}
    }
    private final byte[] secret=new byte[32];
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    ConversationHistory(){new SecureRandom().nextBytes(secret);}
    synchronized void clear(){entries.clear();}
    private String digest(String scope,JSONArray history) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));mac.update((byte)0);
            byte[] bytes=mac.doFinal(stableJson(history).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder(64);
            for(byte b:bytes)result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return result.toString();
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    synchronized Context start(JSONArray messages,String scope) {
        if(messages==null || messages.length()>MAX_MESSAGES)return null;
        long now=System.currentTimeMillis();entries.entrySet().removeIf(e->now-e.getValue().at>TTL || now<e.getValue().at);
        JSONArray history=new JSONArray();String identity=null;boolean ambiguous=false,pending=false;int size=0;
        for(int i=0;i<messages.length();i++) {
            JSONObject original=messages.optJSONObject(i);if(original==null)return null;
            String role=original.optString("role","");if("system".equals(role)||"developer".equals(role))continue;
            JSONObject message=canonical(original);if(message==null)return null;
            size+=message.toString().getBytes(StandardCharsets.UTF_8).length;if(size>MAX_BYTES)return null;history.put(message);
            if("assistant".equals(role)) {
                String lookup=digest(scope,history);Entry known=entries.get(lookup);
                if(known!=null){identity=known.identity;ambiguous=identity.isEmpty();pending=!known.pendingOwner.isEmpty();}
            }
        }
        if(history.length()==0 || ambiguous)return null;
        if(identity==null)identity="lineage:"+UUID.randomUUID();
        Context context=new Context(identity,scope,history);context.matchedPending=pending;return context;
    }
    synchronized void delivered(Context context,JSONObject assistant) {
        if(context==null || assistant==null)return;
        JSONObject normalized=canonical(assistant);if(normalized==null)return;
        JSONArray history=new JSONArray(context.history.toString()).put(normalized);
        if(history.length()>MAX_MESSAGES || history.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)return;
        String key=digest(context.scope,history);long now=System.currentTimeMillis();Entry old=entries.get(key);
        if(old==null) {
            if(entries.size()>=CAPACITY)entries.remove(entries.keySet().iterator().next());
            entries.put(key,new Entry(context.identity,now));
        } else {if(!old.identity.equals(context.identity))old.identity="";old.at=now;old.pendingOwner="";}
    }
    synchronized void reserve(Context context,JSONObject assistant) {
        if(context==null || assistant==null || context.provisionalKeys.size()>=32)return;
        JSONObject normalized=canonical(assistant);if(normalized==null)return;
        JSONArray history=new JSONArray(context.history.toString()).put(normalized);
        if(history.length()>MAX_MESSAGES || stableJson(history).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)return;
        String key=digest(context.scope,history);Entry known=entries.get(key);
        if(known==null){if(entries.size()>=CAPACITY)entries.remove(entries.keySet().iterator().next());known=new Entry(context.identity,System.currentTimeMillis());known.pendingOwner=context.reservation;entries.put(key,known);context.provisionalKeys.add(key);}
        else if(!known.identity.equals(context.identity)){known.identity="";known.pendingOwner="";}
    }
    synchronized void abandon(Context context) {
        if(context==null)return;
        for(String key:context.provisionalKeys){Entry known=entries.get(key);if(known!=null && context.reservation.equals(known.pendingOwner))entries.remove(key);}
        context.provisionalKeys.clear();
    }
    private static String text(Object content) {
        if(content==null || content==JSONObject.NULL)return "";
        if(content instanceof String)return (String)content;
        if(!(content instanceof JSONArray))return null;
        StringBuilder text=new StringBuilder();JSONArray blocks=(JSONArray)content;
        for(int i=0;i<blocks.length();i++) {
            JSONObject block=blocks.optJSONObject(i);if(block==null)return null;
            String type=block.optString("type","");
            if(Arrays.asList("text","input_text","output_text").contains(type))text.append(block.optString("text",""));
            else if(!"tool_use".equals(type) && !"thinking".equals(type))return null;
        }
        return text.toString();
    }
    static String stableJson(Object value) {
        if(value==null || value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(stableJson(object.opt(key)));}return out.append('}').toString();
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(stableJson(array.opt(i)));}return out.append(']').toString();}
        return JSONObject.valueToString(value);
    }
    private static Object ordered(Object value) {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value,result=new JSONObject();List<String> names=new ArrayList<>();object.keys().forEachRemaining(names::add);Collections.sort(names);
            for(String name:names)result.put(name,ordered(object.opt(name)));return result;
        }
        if(value instanceof JSONArray){JSONArray result=new JSONArray(),source=(JSONArray)value;for(int i=0;i<source.length();i++)result.put(ordered(source.opt(i)));return result;}
        return value;
    }
    private static JSONObject canonical(JSONObject message) {
        String role=message.optString("role","");if(!Arrays.asList("user","assistant","tool").contains(role))return null;
        String content=text(message.opt("content"));if(content==null)return null;
        JSONObject result=new JSONObject().put("role",role).put("content",content);
        if("tool".equals(role))result.put("tool_call_id",message.optString("tool_call_id",""));
        JSONArray calls=message.optJSONArray("tool_calls"), blocks=message.optJSONArray("content");
        if(calls==null && blocks!=null) {
            calls=new JSONArray();for(int i=0;i<blocks.length();i++){JSONObject block=blocks.optJSONObject(i);if(block!=null&&"tool_use".equals(block.optString("type")))calls.put(new JSONObject().put("id",block.optString("id")).put("function",new JSONObject().put("name",block.optString("name")).put("arguments",block.optJSONObject("input"))));}
        }
        if(calls!=null && calls.length()>0) {
            JSONArray normalized=new JSONArray();for(int i=0;i<calls.length();i++) {
                JSONObject call=calls.optJSONObject(i), function=call==null?null:call.optJSONObject("function");if(function==null)return null;
                Object args=function.opt("arguments");if(args instanceof String)try {args=new JSONTokener((String)args).nextValue();}catch(Exception ignored) { }
                normalized.put(new JSONObject().put("id",call.optString("id","")).put("name",function.optString("name","")).put("arguments",ordered(args)));
            }result.put("tool_calls",normalized);
        }
        return result;
    }
    static final class Capture {
        static final java.util.regex.Pattern FINISH=java.util.regex.Pattern.compile("\"finish_reason\"\\s*:\\s*\"(?!null)[^\"]+\"");
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean overflow,toolSeen,probeRequested,finished;int probes;String tail="";
        void write(byte[] data,int offset,int length){if(overflow)return;if(bytes.size()+length>MAX_BYTES){overflow=true;bytes.reset();return;}bytes.write(data,offset,length);String scan=tail+new String(data,offset,length,StandardCharsets.UTF_8);toolSeen|=scan.contains("\"tool_calls\"");probeRequested|=(toolSeen && scan.contains("}\"")) || FINISH.matcher(scan).find();tail=scan.substring(Math.max(0,scan.length()-64));}
        void write(int value){if(value<0)return;write(new byte[]{(byte)value},0,1);}
        JSONObject pendingAssistant() {
            if(!probeRequested || overflow || probes>=32)return null;probeRequested=false;probes++;
            JSONObject message=assistant();if(message==null)return null;
            JSONArray calls=message.optJSONArray("tool_calls");
            if(calls==null || calls.length()==0)return finished?message:null;
            for(int i=0;i<calls.length();i++){
                JSONObject call=calls.optJSONObject(i),function=call==null?null:call.optJSONObject("function");
                if(function==null || call.optString("id","").isEmpty() || function.optString("name","").isEmpty())return null;
                try{new JSONObject(function.optString("arguments",""));}catch(Exception incomplete){return null;}
            }
            return message;
        }
        JSONObject assistant() {
            if(overflow)return null;
            String raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            try {return reply(new JSONObject(raw));}catch(Exception notJson) { }
            StringBuilder text=new StringBuilder();TreeMap<Integer,JSONObject> calls=new TreeMap<>();
            for(String line:raw.split("\\r?\\n"))if(line.startsWith("data:")) {
                String data=line.substring(5).trim();if(data.isEmpty()||"[DONE]".equals(data))continue;
                JSONObject event;try{event=new JSONObject(data);}catch(Exception invalid){return null;}
                if(event.has("error")||"error".equals(event.optString("type")))return null;
                if("response.completed".equals(event.optString("type")))return reply(event.optJSONObject("response"));
                JSONArray choices=event.optJSONArray("choices");if(choices==null||choices.length()!=1)continue;
                String finish=choices.getJSONObject(0).optString("finish_reason","");if(!finish.isEmpty() && !"null".equals(finish))finished=true;
                JSONObject delta=choices.getJSONObject(0).optJSONObject("delta");if(delta==null)continue;
                Object piece=delta.opt("content");if(piece instanceof String)text.append(piece);
                JSONArray pieces=delta.optJSONArray("tool_calls");
                for(int i=0;pieces!=null && i<pieces.length();i++) {
                    JSONObject pieceCall=pieces.optJSONObject(i);if(pieceCall==null)return null;int index=pieceCall.optInt("index",i);
                    JSONObject call=calls.get(index);if(call==null){call=new JSONObject().put("id","").put("function",new JSONObject().put("name","").put("arguments",""));calls.put(index,call);}
                    if(!pieceCall.optString("id","").isEmpty())call.put("id",pieceCall.optString("id"));JSONObject f=pieceCall.optJSONObject("function"),saved=call.getJSONObject("function");
                    if(f!=null){if(!f.optString("name","").isEmpty())saved.put("name",f.optString("name"));if(f.has("arguments"))saved.put("arguments",saved.optString("arguments")+f.optString("arguments"));}
                }
            }
            JSONObject assistant=new JSONObject().put("role","assistant").put("content",text.toString());
            if(!calls.isEmpty()){JSONArray array=new JSONArray();for(JSONObject call:calls.values())array.put(call);assistant.put("tool_calls",array);}
            return assistant;
        }
        private static JSONObject reply(JSONObject response) {
            if(response==null||response.has("error"))return null;
            JSONArray choices=response.optJSONArray("choices");if(choices!=null && choices.length()==1)return choices.getJSONObject(0).optJSONObject("message");
            if("assistant".equals(response.optString("role")))return response;
            JSONArray output=response.optJSONArray("output");if(output==null)return null;
            StringBuilder text=new StringBuilder();JSONArray calls=new JSONArray();
            for(int i=0;i<output.length();i++) {
                JSONObject item=output.optJSONObject(i);if(item==null)return null;
                if("message".equals(item.optString("type"))){String part=ConversationHistory.text(item.opt("content"));if(part==null)return null;text.append(part);}
                else if("function_call".equals(item.optString("type")))calls.put(new JSONObject().put("id",item.optString("call_id")).put("function",new JSONObject().put("name",item.optString("name")).put("arguments",item.optString("arguments"))));
            }
            JSONObject message=new JSONObject().put("role","assistant").put("content",text.toString());if(calls.length()>0)message.put("tool_calls",calls);return message;
        }
    }
}
