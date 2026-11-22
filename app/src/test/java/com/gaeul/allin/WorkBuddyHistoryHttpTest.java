package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Actual WorkBuddy Chat transport + complete response history, with no session headers. */
public final class WorkBuddyHistoryHttpTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static JSONObject post(int port,JSONArray messages,boolean stream)throws Exception {
        JSONObject request=new JSONObject().put("model","workbuddy/glm-5.3").put("stream",stream).put("messages",messages)
                .put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","calculate").put("parameters",new JSONObject().put("type","object")))));
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/v1/chat/completions").openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(12000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Eta");c.setRequestProperty("Authorization","Bearer gateway-fixture");
        try(OutputStream out=c.getOutputStream()){out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        int status=c.getResponseCode();String text=ResponsesGatewayOfflineTest.read(status<400?c.getInputStream():c.getErrorStream());c.disconnect();
        check(status==200,"unidentified WorkBuddy reply completes: "+status+" "+text);
        if(!stream)return new JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        // The mock emits a single complete business delta. Decode independently of the history capture.
        for(String line:text.split("\n"))if(line.startsWith("data:")){String data=line.substring(5).trim();if(data.equals("[DONE]"))continue;JSONObject chunk=new JSONObject(data);JSONArray choices=chunk.optJSONArray("choices");if(choices==null)continue;JSONObject delta=choices.getJSONObject(0).optJSONObject("delta");if(delta!=null && (delta.has("content")||delta.has("tool_calls")))return new JSONObject(delta.toString()).put("role","assistant");}
        throw new AssertionError("business delta missing");
    }
    static void run(boolean stream)throws Exception {
        Map<String,Integer> hits=new HashMap<>();Set<String> limited=new HashSet<>(Arrays.asList(WorkBuddyConversationHttpTest.key("A"),WorkBuddyConversationHttpTest.key("B")));
        List<UsageLedger.Row> rows=java.util.Collections.synchronizedList(new ArrayList<>());UsageLedger.setHooks(rows::add);
        try(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream=new WorkBuddyNativeGatewayHttpTest.MockUpstream((request,n)->{
            String bearer=request.headers.get("Authorization"),id="?";for(String account:new String[]{"A","B","C","D"})if(("Bearer "+WorkBuddyConversationHttpTest.key(account)).equals(bearer))id=account;
            hits.put(id,hits.getOrDefault(id,0)+1);
            if(limited.contains(WorkBuddyConversationHttpTest.key(id)))return new WorkBuddyNativeGatewayHttpTest.Reply(429,"application/json","{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"fixture refusal\"}}");
            JSONObject delta=new JSONObject().put("role","assistant");
            if(hits.get(id)==1 && id.equals("C"))delta.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("id","call_history_118").put("type","function").put("function",new JSONObject().put("name","calculate").put("arguments","{\"value\":9193}"))));
            else delta.put("content","history-account="+id);
            JSONObject event=new JSONObject().put("id","chatcmpl-history-"+n).put("model","glm-5.3").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL)));
            JSONObject end=new JSONObject(event.toString());end.getJSONArray("choices").getJSONObject(0).put("delta",new JSONObject()).put("finish_reason",delta.has("tool_calls")?"tool_calls":"stop");
            return new WorkBuddyNativeGatewayHttpTest.Reply(200,"text/event-stream","data: "+event+"\n\ndata: "+end+"\n\ndata: [DONE]\n\n");
        })) {
            JSONObject config=WorkBuddyConversationHttpTest.config("rotate");config.getJSONArray("providers").getJSONObject(0).put("models",new JSONArray().put("glm-5.3")).put("modelMeta",new JSONObject());
            try(WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway=new WorkBuddyNativeGatewayHttpTest.GatewayHarness(config,upstream)) {
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","dynamic instructions 0")).put(new JSONObject().put("role","user").put("content","controlled history root"));
                JSONObject first=post(gateway.port(),messages,stream);check(first.optJSONArray("tool_calls")!=null,"first C response contains a real tool call");
                messages.put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call_history_118").put("content","9193"));
                for(int i=0;i<4;i++) {
                    messages.getJSONObject(0).put("content","dynamic instructions "+i);
                    JSONObject answer=post(gateway.port(),messages,stream);check(answer.optString("content").equals("history-account=C"),"complete tool/continuation history keeps C without a session ID");messages.put(answer).put(new JSONObject().put("role","user").put("content","continue "+i));
                }
                check(hits.getOrDefault("A",0)==1&&hits.getOrDefault("B",0)==1&&hits.getOrDefault("C",0)==5&&hits.getOrDefault("D",0)==0,"actual no-ID upstream counts A1 B1 C5 D0");
                limited.add(WorkBuddyConversationHttpTest.key("C"));JSONObject next=post(gateway.port(),messages,stream);check(next.optString("content").equals("history-account=D"),"limited C changes to D");messages.put(next).put(new JSONObject().put("role","user").put("content","keep replacement"));
                int c=hits.getOrDefault("C",0);check(post(gateway.port(),messages,stream).optString("content").equals("history-account=D"),"replacement is retained without explicit IDs");
                check(hits.get("A")==1&&hits.get("B")==1&&hits.get("C")==c&&hits.get("D")==2,"no renewed failed-account transmissions after handoff");
                Set<String> sessions=new HashSet<>();synchronized(rows){for(UsageLedger.Row row:rows)if(row.attempt>0&&"workbuddy".equals(row.prov))sessions.add(row.session);}
                check(sessions.size()==1&&sessions.iterator().next().startsWith("history:"),"ledger exposes a single explicitly history-derived reference across C to D");
            }
        } finally {UsageLedger.setHooks(null);}
    }
    public static void main(String[] args)throws Exception {run(false);run(true);System.out.println("WorkBuddyHistoryHttpTest PASS: "+checks+" checks; real no-session-header HTTP and tool history");}
}
