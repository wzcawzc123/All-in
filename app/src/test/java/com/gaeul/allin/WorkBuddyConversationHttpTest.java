package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Real WorkBuddy native transport with synthetic accounts; no production accounts or endpoints. */
public final class WorkBuddyConversationHttpTest {
    static int checks;
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static String key(String account){return "ck_synthetic_session_"+account;}
    static JSONObject config(String mode) {
        JSONObject config=WorkBuddyNativeGatewayHttpTest.config("workbuddy","openai","https://copilot.tencent.com/v2","claude-opus-5.5");
        String incarnation=UUID.randomUUID().toString();
        JSONArray accounts=new JSONArray();for(String id:new String[]{"A","B","C","D"})accounts.put(new JSONObject().put("id",id+"-"+incarnation).put("uid",id).put("token",key(id)).put("type","api").put("enabled",true));
        JSONObject provider=config.getJSONArray("providers").getJSONObject(0);
        provider.put("accounts",accounts).put("keys",new JSONArray()).put("accountRouting",mode);
        config.getJSONObject("failover").put("maxTargets",3).put("maxAttempts",8);
        return config;
    }
    static String post(int port,String path,String conversation,boolean stream,boolean tools,boolean metadata)throws Exception {
        JSONObject input;
        if(path.endsWith("messages"))input=WorkBuddyNativeGatewayHttpTest.anthropic(stream);
        else if(path.endsWith("responses"))input=new JSONObject().put("model","workbuddy/claude-opus-5.5").put("stream",stream).put("max_output_tokens",2048)
                .put("input",new JSONArray().put(new JSONObject().put("role","user").put("content","controlled session test")));
        else input=WorkBuddyNativeGatewayHttpTest.chat(stream);
        if(tools && !path.endsWith("responses")) {
            if(path.endsWith("messages")) {
                input.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","toolu_native").put("name","Read").put("input",new JSONObject().put("path","fixture")))))
                        .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","toolu_native").put("content","9193"))));
            } else {
                input.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","toolu_native").put("type","function").put("function",new JSONObject().put("name","Read").put("arguments","{\"path\":\"fixture\"}")))))
                        .put(new JSONObject().put("role","tool").put("tool_call_id","toolu_native").put("content","9193"));
            }
        }
        if(metadata)input.put("metadata",new JSONObject().put("user_id",new JSONObject().put("session_id",conversation).toString()));
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer gateway-fixture");
        if(!metadata)connection.setRequestProperty("session-id",conversation);
        try(OutputStream out=connection.getOutputStream()){out.write(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        int status=connection.getResponseCode();String body=ResponsesGatewayOfflineTest.read(status<400?connection.getInputStream():connection.getErrorStream());connection.disconnect();
        check(status==200,"WorkBuddy "+path+" status "+status+" "+body);return body;
    }
    static void run(String path,boolean stream,String mode,boolean metadata)throws Exception {
        Map<String,Integer> hits=new HashMap<>();Set<String> limited=new HashSet<>(Arrays.asList(key("A"),key("B")));
        try(WorkBuddyNativeGatewayHttpTest.MockUpstream upstream=new WorkBuddyNativeGatewayHttpTest.MockUpstream((request,count)->{
            String used=request.headers.get("X-API-Key");hits.put(used,hits.getOrDefault(used,0)+1);
            if(limited.contains(used))return new WorkBuddyNativeGatewayHttpTest.Reply(429,"application/json","{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"synthetic account limited\"}}");
            return new WorkBuddyNativeGatewayHttpTest.Reply(200,"text/event-stream",WorkBuddyNativeGatewayHttpTest.BASE);
        });WorkBuddyNativeGatewayHttpTest.GatewayHarness gateway=new WorkBuddyNativeGatewayHttpTest.GatewayHarness(config(mode),upstream)) {
            post(gateway.port(),path,"workbuddy-session",stream,false,metadata);
            check(hits.getOrDefault(key("A"),0)==1&&hits.getOrDefault(key("B"),0)==1&&hits.getOrDefault(key("C"),0)==1,"native WorkBuddy A/B rejected then C succeeds");
            for(int i=0;i<4;i++)post(gateway.port(),path,"workbuddy-session",stream,true,metadata);
            check(hits.getOrDefault(key("A"),0)==1&&hits.getOrDefault(key("B"),0)==1&&hits.getOrDefault(key("C"),0)==5&&hits.getOrDefault(key("D"),0)==0,"native WorkBuddy续聊工具实际只发C "+path+" "+mode);
            limited.add(key("C"));post(gateway.port(),path,"workbuddy-session",stream,false,metadata);
            int c=hits.getOrDefault(key("C"),0);post(gateway.port(),path,"workbuddy-session",stream,true,metadata);
            check(hits.getOrDefault(key("A"),0)==1&&hits.getOrDefault(key("B"),0)==1&&hits.getOrDefault(key("C"),0)==c&&hits.getOrDefault(key("D"),0)==2,"native WorkBuddy限制后重新绑定D "+path+" "+mode);
            check(upstream.requests.stream().allMatch(request->"/v1/messages".equals(request.path)),"real native WorkBuddy endpoint used, not generic chat mock");
        }
    }
    public static void main(String[] args)throws Exception {
        for(String mode:new String[]{"rotate","usage","pace"})for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})for(boolean stream:new boolean[]{false,true})run(path,stream,mode,path.endsWith("messages"));
        System.out.println("WorkBuddyConversationHttpTest PASS: "+checks+" checks; native upstream account sends counted");
    }
}
