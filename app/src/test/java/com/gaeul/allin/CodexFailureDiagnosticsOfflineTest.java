package com.gaeul.allin;

import static com.gaeul.allin.VendorPolicyGatewayHttpTest.*;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;

/** Types/stages only: deliberately secret-like exception messages must not enter the response/log. */
public final class CodexFailureDiagnosticsOfflineTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        String message="Bearer synthetic-exception-secret; private conversation must not be emitted";
        IOException fault=new IOException(message,new SSLHandshakeException(message));
        Method method=GatewayServer.class.getDeclaredMethod("codexFailureTypes",Throwable.class);method.setAccessible(true);
        String types=(String)method.invoke(null,fault);
        check(types.equals("IOException -> SSLHandshakeException"),"exception types extracted without messages");
        check(!types.contains("synthetic")&&!types.contains("Bearer"),"diagnostics no secret-like message");
        JSONObject account=new JSONObject().put("id","codex-fixture").put("enabled",true).put("access","synthetic-access")
                .put("refresh","").put("expires",0).put("accountId","synthetic-account").put("models",new JSONArray().put("fixture"));
        JSONObject p=provider("codex","codex",CodexAuth.CODEX_BASE,"fixture").put("accounts",new JSONArray().put(account)).put("keys",new JSONArray().put("synthetic-access"));
        JSONObject cfg=config(p).put("guard",new JSONObject().put("enabled",false)).put("failover",new JSONObject().put("enabled",false));
        AtomicInteger opens=new AtomicInteger();
        try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",CodexRequestPolicyOfflineTest.EVENTS));Gateway g=new Gateway(cfg,u,false)){
            try{
                g.gateway.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw fault;});
                Result r=post(g,"/v1/chat/completions",new JSONObject().put("model","codex/fixture").put("stream",false)
                        .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture"))));
                check(r.status==502,"network failure status unchanged");
                check(r.body.contains("[open_connection]")&&r.body.contains("SSLHandshakeException"),"safe phase and type reach client");
                check(!r.body.contains("synthetic-exception-secret")&&!r.body.contains("private conversation"),"original exception message not exposed");
                check(u.requests.size()==0&&opens.get()==1,"diagnostic adds no retry or extra vendor send");
            }finally{g.gateway.retireForRestore();}
        }
        tlsReadAfterMessage(cfg, message);
        System.out.println("CodexFailureDiagnosticsOfflineTest PASS checks="+checks);
    }

    private static void tlsReadAfterMessage(JSONObject config, String sensitiveMessage) throws Exception {
        JSONObject part = new JSONObject().put("type", "output_text").put("text", "Model reply prefix");
        JSONObject item = new JSONObject().put("type", "message").put("content", new JSONArray().put(part));
        String wire = GatewayStreamingHttpTest.responses("response.output_item.done",
                new JSONObject().put("output_index", 0).put("item", item));
        AtomicInteger opened = new AtomicInteger(), readsAfterMessage = new AtomicInteger(), disconnected = new AtomicInteger();
        try (Upstream unused = new Upstream((request, n) -> new Reply(500, "text/plain", "unused"));
             Gateway gateway = new Gateway(config, unused, false)) {
            gateway.gateway.setVendorConnectionsForTests(url -> {
                opened.incrementAndGet();
                return new HttpURLConnection(new URL(url)) {
                    @Override public void connect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void disconnect() { disconnected.incrementAndGet(); }
                    @Override public int getResponseCode() { return 200; }
                    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    @Override public InputStream getInputStream() {
                        return new FilterInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))) {
                            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                                int count = super.read(buffer, offset, length);
                                if (count < 0) {
                                    readsAfterMessage.incrementAndGet();
                                    throw new SSLProtocolException(sensitiveMessage);
                                }
                                return count;
                            }
                        };
                    }
                };
            });
            Result result = post(gateway, "/v1/chat/completions", body("codex/fixture", true));
            int errors = 0;
            String reported = "";
            for (String line : result.body.split("\n")) {
                if (!line.startsWith("data: {")) continue;
                JSONObject failure = new JSONObject(line.substring(6)).optJSONObject("error");
                if (failure != null) { errors++; reported = failure.optString("message"); }
            }
            check(result.status == 200 && result.body.contains("Model reply prefix"), "valid message prefix preserved");
            check(errors == 1 && !result.body.contains("[DONE]") && !result.body.contains("\"finish_reason\":\"stop\""),
                    "TLS truncation yields one failure without declaring the response complete");
            check(!result.body.contains(sensitiveMessage), "TLS exception details stay private");
            check(opened.get() == 1 && readsAfterMessage.get() == 1 && unused.requests.isEmpty(), "truncated request is not retried");
            // XunE: disconnect 在响应交付后的异步回调(finishContext→cancel)里执行，客户端读完
            // 字节时服务端可能还没跑完；1.2.158 加重记账后窗口变宽。有界等待仍能抓住真泄漏。
            int waited=0; while(disconnected.get()==0 && waited<3000){Thread.sleep(50);waited+=50;}
            check(disconnected.get() > 0, "failed upstream connection released (waited "+waited+"ms)");
            check(reported.equals("Responses upstream TLS read failed before completion"), "TLS read error classified: " + reported);
        }
    }
}
