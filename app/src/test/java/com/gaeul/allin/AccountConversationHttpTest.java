package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Counts actual loopback upstream sends, not just the preferred-account branch. */
public final class AccountConversationHttpTest {
    static int checks;
    static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final Map<String,Integer> hits=new LinkedHashMap<>(), statuses=new HashMap<>();
        volatile boolean streamFailure;
        Upstream() throws Exception {super("127.0.0.1",0);start(5000,true);}
        synchronized int count(String account) {return hits.getOrDefault("Bearer fixture-"+account,0);}
        public synchronized Response serve(IHTTPSession session) {
            Map<String,String> bodies=new HashMap<>();
            try {session.parseBody(bodies);} catch(Exception invalid) {throw new AssertionError(invalid);}
            String auth=session.getHeaders().get("authorization");hits.put(auth,hits.getOrDefault(auth,0)+1);
            int status=statuses.getOrDefault(auth,200);
            if(status!=200) {
                Response response=newFixedLengthResponse(Response.Status.lookup(status),"application/json",new JSONObject().put("error",new JSONObject().put("code",status==429?"rate_limit_error":status==402?"insufficient_quota":status==401?"authentication_error":"server_error").put("message","synthetic refusal")).toString());
                response.addHeader("Retry-After","3600");return response;
            }
            JSONObject input=new JSONObject(bodies.get("postData"));
            JSONObject message=new JSONObject().put("role","assistant").put("content","account="+auth.substring("Bearer fixture-".length()));
            if(input.optBoolean("stream",false)) {
                String data="data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"stream-ok\"},\"finish_reason\":null}]}\n\n";
                data+=streamFailure?"event: error\ndata: {\"error\":{\"code\":\"upstream_error\",\"message\":\"synthetic stream failure\"}}\n\n"
                        :"data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                return newFixedLengthResponse(Response.Status.OK,"text/event-stream",data);
            }
            JSONArray choices=new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason","stop"));
            return newFixedLengthResponse(Response.Status.OK,"application/json",new JSONObject().put("id","fixture").put("choices",choices).toString());
        }
        public void close(){stop();}
    }
    static JSONObject config(Upstream up,String mode,int size) {
        JSONArray accounts=new JSONArray();for(int i=0;i<size;i++) {String id=String.valueOf((char)('A'+i));accounts.put(new JSONObject().put("id",id).put("uid",id).put("token","fixture-"+id).put("enabled",true));}
        JSONObject provider=new JSONObject().put("id","fixture-pool").put("protocol","openai").put("baseUrl","http://127.0.0.1:"+up.getListeningPort()+"/v1")
                .put("models",new JSONArray().put("m")).put("accounts",accounts).put("keys",new JSONArray()).put("accountRouting",mode);
        return new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth"))
                .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(provider));
    }
    static String post(AccountRoutingHttpTest.Gateway gateway,String session,boolean stream,boolean tools,boolean metadata) throws Exception {
        JSONObject input=new JSONObject().put("model","fixture-pool/m").put("stream",stream);
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","same first message"));
        if(tools) {
            JSONObject function=new JSONObject().put("name","calculate").put("arguments","{}");
            messages.put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-1").put("type","function").put("function",function))));
            messages.put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content","9193"));
        }
        input.put("messages",messages);
        if(metadata)input.put("metadata",new JSONObject().put("user_id",new JSONObject().put("session_id",session).toString()));
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+"/v1/chat/completions").openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer fixture-auth");c.setRequestProperty("Content-Type","application/json");
        if(!metadata && session!=null)c.setRequestProperty("x-session-id",session);
        try(OutputStream out=c.getOutputStream()){out.write(input.toString().getBytes(StandardCharsets.UTF_8));}
        int status=c.getResponseCode();String text=ResponsesGatewayOfflineTest.read(status<400?c.getInputStream():c.getErrorStream());c.disconnect();
        return status+":"+text;
    }
    static void refusedThenSticky(String mode) throws Exception {
        try(Upstream up=new Upstream()) {
            up.statuses.put("Bearer fixture-A",429);up.statuses.put("Bearer fixture-B",429);
            try(AccountRoutingHttpTest.Gateway gateway=new AccountRoutingHttpTest.Gateway(config(up,mode,4))) {
                check(post(gateway,"conversation-1",false,false,false).contains("account=C"),mode+" first finds C");
                check(up.count("A")==1 && up.count("B")==1 && up.count("C")==1,mode+" first sends A/B/C exactly once");
                for(int i=0;i<4;i++)check(post(gateway,"conversation-1",false,true,false).contains("account=C"),mode+" tools retain C");
                check(post(gateway,"conversation-1",true,false,false).startsWith("200:"),mode+" SSE succeeds");
                check(up.count("A")==1 && up.count("B")==1 && up.count("C")==6 && up.count("D")==0,mode+" no extra failed-account sends across six turns");
                up.statuses.put("Bearer fixture-C",429);
                check(post(gateway,"conversation-1",false,false,false).contains("account=D"),mode+" limited C changes to D");
                int c=up.count("C");for(int i=0;i<3;i++)check(post(gateway,"conversation-1",false,true,false).contains("account=D"),mode+" tools retain replacement D");
                check(up.count("A")==1 && up.count("B")==1 && up.count("C")==c,mode+" no renewed A/B/C sends after rebind");
                check(post(gateway,"conversation-new",false,false,false).contains("account=D"),mode+" new conversation skips still-cooling accounts");
                check(up.count("A")==1 && up.count("B")==1 && up.count("C")==c,mode+" new conversation does not probe known cooldowns");
                up.statuses.put("Bearer fixture-D",500);int d=up.count("D");
                check(post(gateway,"conversation-1",false,false,false).startsWith("500:"),mode+" ordinary server error is terminal");
                check(up.count("D")==d+1 && up.count("C")==c,mode+" ordinary error does not change account");
                up.statuses.remove("Bearer fixture-D");check(post(gateway,"conversation-1",false,false,false).contains("account=D"),mode+" ordinary error preserves binding");
            }
        }
    }
    static void identityAndNewSession() throws Exception {
        try(Upstream up=new Upstream();AccountRoutingHttpTest.Gateway gateway=new AccountRoutingHttpTest.Gateway(config(up,"rotate",3))) {
            check(post(gateway,"one",false,false,false).contains("account=A"),"first conversation A");
            for(int i=0;i<3;i++)check(post(gateway,"one",false,true,false).contains("account=A"),"same session stays A");
            check(post(gateway,"two",false,false,false).contains("account=B"),"new conversation alone advances rotation");
            check(post(gateway,"three",false,false,true).contains("account=C"),"explicit metadata starts C");
            check(post(gateway,"three",false,true,true).contains("account=C"),"metadata tools stay C");
            check(post(gateway,null,false,false,false).contains("account=A"),"unidentified request keeps request rotation");
            check(post(gateway,null,false,false,false).contains("account=B"),"identical prompt cannot imply same conversation");
            JSONObject provider=ResponsesGatewayOfflineTest.config(gateway.gateway).getJSONArray("providers").getJSONObject(0);
            provider.getJSONArray("accounts").getJSONObject(0).put("enabled",false);
            check(post(gateway,"one",false,false,false).contains("account=C"),"disabled binding reselects an eligible account");
            int a=up.count("A");check(post(gateway,"one",false,true,false).contains("account=C"),"replacement sticks");
            check(up.count("A")==a,"disabled account never sent again");
        }
    }
    static void rotationAfterFallback() throws Exception {
        try(Upstream upstream=new Upstream()) {
            upstream.statuses.put("Bearer fixture-A",429);upstream.statuses.put("Bearer fixture-B",429);
            try(AccountRoutingHttpTest.Gateway gateway=new AccountRoutingHttpTest.Gateway(config(upstream,"rotate",4))) {
                check(post(gateway,"first-conversation",false,false,false).contains("account=C"),"first dialogue finds C after A/B rejection");
                check(post(gateway,"first-conversation",false,true,false).contains("account=C"),"old dialogue remains C");
                check(post(gateway,"new-conversation",false,false,false).contains("account=D"),"new dialogue rotates AFTER the actual successful C to D");
                check(post(gateway,"first-conversation",false,true,false).contains("account=C"),"old C does not rewind the new-dialogue allocation");
                check(upstream.count("A")==1&&upstream.count("B")==1&&upstream.count("C")==3&&upstream.count("D")==1,"actual cursor/history sends A1 B1 C3 D1");
            }
        }
    }
    static void independentKeysStayBound() throws Exception {
        try(Upstream upstream=new Upstream()) {
            JSONObject cfg=config(upstream,"rotate",1),provider=cfg.getJSONArray("providers").getJSONObject(0);
            provider.getJSONArray("accounts").getJSONObject(0).put("enabled",false);
            provider.put("keys",new JSONArray().put("fixture-A").put("fixture-C").put("fixture-D"));
            try(AccountRoutingHttpTest.Gateway gateway=new AccountRoutingHttpTest.Gateway(cfg)) {
                check(post(gateway,"legacy-session",false,false,false).contains("account=C"),"independent key starts C, disabled mirror A excluded");
                check(post(gateway,"legacy-session",false,true,false).contains("account=C"),"independent key remains bound during tool history");
                check(post(gateway,"legacy-new",false,false,false).contains("account=D"),"new dialogue rotates independent keys");
                check(upstream.count("A")==0&&upstream.count("C")==2&&upstream.count("D")==1,"actual independent-key sends preserve disabled-mirror isolation");
            }
        }
    }
    static void identities() {
        Map<String,String> headers=new HashMap<>();headers.put("thread-id","thread-1");
        check(!ConversationSession.header(headers).isEmpty(),"Codex thread header supported");
        headers.put("x-session-id","parent");String one=ConversationSession.header(headers);headers.put("thread-id","thread-2");
        check(!one.equals(ConversationSession.header(headers)),"threads within one session isolated");
        check(ConversationSession.body(new JSONObject().put("metadata",new JSONObject().put("user_id","ordinary-user"))).isEmpty(),"user identity is not a conversation");
        check(ConversationSession.body(new JSONObject().put("prompt_cache_key","shared-key")).isEmpty(),"cache key is not a conversation");
        headers.clear();headers.put("x-session-id","bad\r\nheader");check(ConversationSession.header(headers).isEmpty(),"control characters rejected");
        SessionAffinity.Clock clock=new SessionAffinity.Clock(){long time;public long nowMillis(){return time;}};
        SessionAffinity affinity=new SessionAffinity(clock,2,100,10);SessionAffinity.Key key=affinity.key(true,"s","scope","p","m");
        SessionAffinity.Target target=new SessionAffinity.Target("p","o","m","account:A");SessionAffinity.Lease lease=affinity.acquire(key);check(lease.renew(target),"owner binds successful target");
        check(affinity.acquire(key).renew(target),"bound target renews successful delivery");
        java.util.concurrent.atomic.AtomicLong time=new java.util.concurrent.atomic.AtomicLong();
        SessionAffinity slow=new SessionAffinity(time::get,2,1000,10);SessionAffinity.Key slowKey=slow.key(true,"slow","scope","p","m");
        SessionAffinity.Lease owner=slow.acquire(slowKey,200);time.set(100);check(owner.bind(target),"a slow first success binds after the original reservation window");
        check(slow.acquire(slowKey).preferred.equals(target),"slow successful owner is retained");
    }
    public static void main(String[] args) throws Exception {
        identities();for(String mode:new String[]{"rotate","usage","pace"})refusedThenSticky(mode);identityAndNewSession();rotationAfterFallback();independentKeysStayBound();
        System.out.println("AccountConversationHttpTest PASS: "+checks+" checks; actual upstream sends counted");
    }
}
