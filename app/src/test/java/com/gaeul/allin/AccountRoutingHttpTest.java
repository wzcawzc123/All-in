package com.gaeul.allin;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual loopback HTTP with four synthetic API keys; no vendor endpoints. */
public final class AccountRoutingHttpTest {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static final class Upstream extends NanoHTTPD implements AutoCloseable {
        final Map<String,Integer> hits=new HashMap<>();
        Upstream()throws Exception{super("127.0.0.1",0);start(5000,true);}
        public synchronized Response serve(IHTTPSession session){
            try{session.parseBody(new HashMap<String,String>());}catch(Exception e){throw new AssertionError(e);}
            String key=session.getHeaders().get("authorization");hits.put(key,hits.getOrDefault(key,0)+1);
            String body="{\"id\":\"synthetic\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":250,\"completion_tokens\":250}}";
            return newFixedLengthResponse(Response.Status.OK,"application/json",body);
        }
        public void close(){stop();}
    }
    static final class Gateway extends NanoHTTPD implements AutoCloseable {
        final GatewayServer gateway;
        Gateway(JSONObject cfg)throws Exception{super("127.0.0.1",0);gateway=RoutingSetupOfflineTest.server(cfg);start(5000,true);}
        public Response serve(IHTTPSession s){return gateway.serve(s);}
        public void close(){gateway.stop();stop();}
    }
    public static void main(String[] args)throws Exception {
        try(Upstream up=new Upstream()){
            JSONArray keys=new JSONArray();for(int i=0;i<4;i++)keys.put("synthetic-routing-key-"+i);
            JSONObject provider=new JSONObject().put("id","synthetic").put("protocol","openai").put("baseUrl","http://127.0.0.1:"+up.getListeningPort()+"/v1").put("models",new JSONArray().put("m")).put("keys",keys).put("accountRouting","usage");
            JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("server",new JSONObject().put("authKey","fixture-auth")).put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray().put(provider));
            try(Gateway gateway=new Gateway(cfg)){
                for(int n=0;n<20;n++){
                    HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+"/v1/chat/completions").openConnection();
                    c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer fixture-auth");c.setRequestProperty("Content-Type","application/json");
                    byte[] body=new JSONObject().put("model","synthetic/m").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic"))).toString().getBytes(StandardCharsets.UTF_8);
                    try(OutputStream os=c.getOutputStream()){os.write(body);}int status=c.getResponseCode();String text=ResponsesGatewayOfflineTest.read(status<400?c.getInputStream():c.getErrorStream());c.disconnect();
                    check(status==200&&text.contains("ok"),"request "+n+" completes: "+status+" "+text);
                }
                synchronized(up){check(up.hits.size()==4,"all four physical keys actually used");for(int count:up.hits.values())check(count==5,"equal known token loads distribute 5/5/5/5");}
                // XunE: gate.leave 在响应交付后的异步回调里执行，客户端读完字节时服务端可能
                // 还没跑完 finishContext；上游 1.2.158 加重该路径后窗口变宽。有界等待仍能抓住真泄漏。
                int waited=0; while(gateway.gateway.activeForwardRequests()!=0&&waited<3000){Thread.sleep(50);waited+=50;}
                check(gateway.gateway.activeForwardRequests()==0,"all request lifetimes released (waited "+waited+"ms)");
            }
        }
        System.out.println("AccountRoutingHttpTest PASS: "+checks+" checks");
    }
}
