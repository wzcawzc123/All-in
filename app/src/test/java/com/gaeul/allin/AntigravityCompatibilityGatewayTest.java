package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the real management probe and inference routing with synthetic Code Assist bytes. */
public final class AntigravityCompatibilityGatewayTest {
    static int checks;
    static void check(boolean ok,String label) { checks++; if(!ok)throw new AssertionError(label); }
    static final String ACCESS="SYNTHETIC_ACCESS_56", REFRESH="SYNTHETIC_REFRESH_56";
    static final String MODEL="gemini-3.8-flash";
    static final class Wire implements AntigravityAuth.Transport {
        String body; int status=403,opens; String request;
        Wire(String body) { this.body=body; }
        public AntigravityAuth.Link open(String url,String host,int port,int connect,int read) {
            opens++;
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            byte[] h=("HTTP/1.1 "+status+" Synthetic\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream capture=new ByteArrayOutputStream();
            return new AntigravityAuth.Link() {
                public InputStream in(){return new SequenceInputStream(new ByteArrayInputStream(h),new ByteArrayInputStream(b));}
                public OutputStream out(){return capture;}
                public void close(){request=new String(capture.toByteArray(),StandardCharsets.UTF_8);}
            };
        }
    }
    static String error(String message,String reason) {
        JSONObject detail=new JSONObject().put("@type","type.googleapis.com/google.rpc.ErrorInfo").put("reason",reason)
                .put("metadata",new JSONObject().put("authorization",ACCESS).put("private",REFRESH));
        return new JSONObject().put("error",new JSONObject().put("code",403).put("message",message)
                .put("status","PERMISSION_DENIED").put("details",new JSONArray().put(detail))).toString();
    }
    static GatewayServer server(Path dir, Wire wire)throws Exception {
        JSONObject acc=new JSONObject().put("id","synthetic-ag56").put("email","fixture@example.invalid")
                .put("access",ACCESS).put("token",ACCESS).put("refresh",REFRESH).put("enabled",true)
                .put("project","synthetic-project").put("expires",System.currentTimeMillis()+3600000L);
        JSONObject meta=new JSONObject().put("variantMetadata",new JSONObject().put(MODEL,new JSONObject()
                .put("modelEnum","MODEL_PLACEHOLDER_SYNTHETIC").put("modelProvider","MODEL_PROVIDER_GOOGLE")));
        JSONObject provider=new JSONObject().put("id","antigravity").put("protocol","antigravity")
                .put("models",new JSONArray().put(MODEL)).put("modelMeta",new JSONObject().put(MODEL,meta))
                .put("accounts",new JSONArray().put(acc));
        GatewayServer g=RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","SYNTHETIC_INFERENCE"))
                .put("providers",new JSONArray().put(provider)).put("privacy",new JSONObject().put("enabled",false)));
        RoutingAuditOfflineTest.file(g,dir.resolve("config.json").toFile());
        g.setAntigravityTransportForTests(wire);
        return g;
    }
    static JSONObject probe(GatewayServer g)throws Exception {
        NanoHTTPD.Response r=Audit53RegressionTest.request(g,"/api/test-model","POST",new JSONObject().put("model","antigravity/"+MODEL).toString());
        check(r.getStatus().getRequestStatus()==200,"management probe HTTP envelope");
        return new JSONObject(Audit53RegressionTest.body(r));
    }
    static void toolResultFailure(Path dir) throws Exception {
        String partial = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}}\n\n";
        String detail = new JSONObject().put("response", new JSONObject().put("error", new JSONObject()
                .put("code", 400).put("status", "INVALID_ARGUMENT").put("message", "late tool error " + ACCESS + " " + REFRESH))).toString(2);
        JSONObject tool = new JSONObject().put("name", "lookup").put("input_schema", new JSONObject().put("type", "object"));
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "look up an order"))
                .put(new JSONObject().put("role", "assistant").put("content", new JSONArray().put(new JSONObject()
                        .put("type", "tool_use").put("id", "toolu_fixture").put("name", "lookup").put("input", new JSONObject()))))
                .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject()
                        .put("type", "tool_result").put("tool_use_id", "toolu_fixture").put("content", "19:42"))));
        for (boolean stream : new boolean[]{false, true}) {
            Wire wire = new Wire(partial + detail);
            wire.status = 200;
            GatewayServer g = server(dir, wire);
            try {
                JSONObject request = new JSONObject().put("model", "antigravity/" + MODEL).put("max_tokens", 128)
                        .put("messages", messages).put("tools", new JSONArray().put(tool)).put("stream", stream);
                NanoHTTPD.Response reply = Audit53RegressionTest.request(g, "/v1/messages", "POST", request.toString());
                int status = reply.getStatus().getRequestStatus();
                String text = Audit53RegressionTest.body(reply);
                check(status == (stream ? 200 : 502), "late upstream failure preserves protocol status: actual="+status+" stream="+stream);
                check(text.contains("code=400 status=INVALID_ARGUMENT") && !text.contains(ACCESS) && !text.contains(REFRESH), "late error facts preserved and account secret echoes redacted");
                check(wire.opens == 1 && wire.request.contains("functionResponse"), "tool result transmitted once");
                if (stream) check(text.contains("partial") && text.indexOf("event: error") >= 0
                        && text.indexOf("event: error") == text.lastIndexOf("event: error") && !text.contains("event: message_stop"),
                        "Anthropic result stream emits one error and no success terminal");
            } finally { g.stop(); }
        }
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("antigravity56-gateway-");
        try {
            toolResultFailure(dir);
            for(String mode:new String[]{"scope","region","permission"}) {
                String msg=mode.equals("region")?"Your current account is not eligible for Gemini Code Assist for individuals because it is not currently available in your location.":mode.equals("scope")?"Request had insufficient authentication scopes.":"Permission denied for this operation.";
                String reason=mode.equals("scope")?"ACCESS_TOKEN_SCOPE_INSUFFICIENT":"SYNTHETIC_PERMISSION";
                // ErrorInfo comes after the old 300-character truncation boundary.
                Wire wire=new Wire(error(msg+" "+String.join("",Collections.nCopies(60,"detail "))+ACCESS+REFRESH,reason));
                GatewayServer g=server(dir,wire);
                try {
                    String before=ResponsesGatewayOfflineTest.config(g).toString();
                    JSONObject r=probe(g);
                    check(!r.getBoolean("ok")&&r.getInt("statusCode")==403,"upstream 403 retained");
                    check(!r.getBoolean("executionUnknown"),"explicit rejection is not unknown execution");
                    check(wire.opens==1,"403 reaches one synthetic upstream only");
                    check(r.has("upstreamError")&&r.getJSONObject("upstreamError").getJSONArray("reasons").getString(0).equals(reason),"ErrorInfo survives old truncation boundary");
                    check(!r.toString().contains(ACCESS)&&!r.toString().contains(REFRESH),"probe never leaks account credential echoes");
                    check(!r.getJSONObject("upstreamError").has("metadata"),"arbitrary Google metadata is not exposed");
                    check(before.equals(ResponsesGatewayOfflineTest.config(g).toString()),"probe does not alter account or project");
                    String expected=mode.equals("scope")?"授权范围":mode.equals("region")?"服务资格与地区":"访问权限与资格";
                    check(expected.equals(r.getString("failureLayerLabel")),"specific refusal display: "+mode);
                    String request=wire.request;check(request!=null&&request.contains("aidev_client"),"actual gateway uses updated transport");
                    JSONObject envelope=new JSONObject(request.substring(request.indexOf("\r\n\r\n")+4));
                    check("MODEL_PLACEHOLDER_SYNTHETIC".equals(envelope.getJSONObject("request").getJSONObject("labels").getString("model_enum")),"selected model enum survives real gateway");
                    check(request.indexOf("\"project\"")<request.indexOf("\"requestId\"")&&request.indexOf("\"requestId\"")<request.indexOf("\"request\""),"serialized wire root key order");
                    check(!request.contains("Accept: text/event-stream"),"actual gateway removes legacy Accept");
                    if(mode.equals("region")) {
                        NanoHTTPD.Response reply=Audit53RegressionTest.request(g,"/v1/chat/completions","POST",new JSONObject().put("model","antigravity/"+MODEL)
                                .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","synthetic"))).toString());
                        check(reply.getStatus().getRequestStatus()==403,"client ingress retains 403");
                        String text=Audit53RegressionTest.body(reply);check(!text.contains(ACCESS)&&!text.contains(REFRESH),"client error body redacts both credentials");
                    }
                } finally {g.stop();}
            }
            JSONObject allowed=new JSONObject().put("allowedTiers",new JSONArray().put(new JSONObject().put("id","wrong"))
                    .put(new JSONObject().put("id","standard-tier").put("isDefault",true)));
            JSONObject body=AntigravityProtocol.onboardBody(allowed);
            check("standard-tier".equals(body.getString("tierId")),"onboard uses server default tier");
            check(body.length()==2&&body.getJSONObject("metadata").length()==1,"no old snake_case metadata fields");
            check("free-tier".equals(AntigravityProtocol.onboardBody(null).getString("tierId")),"missing allowed tiers fallback");
            AntigravityAuth.CodeAssistException rejected=AntigravityAuth.codeAssistRefused(403,error("scope","ACCESS_TOKEN_SCOPE_INSUFFICIENT"));
            check(AntigravityAuth.codeAssistStatus(rejected)==403&&!AntigravityAuth.codeAssistBody(rejected).isEmpty(),"project refusal keeps status and structured body");
            JSONObject untouched=new JSONObject().put("sentinel",1);
            AntigravityProtocol.describeRefusal(untouched,403,"not JSON");check(untouched.length()==1,"malformed refusal is not guessed");
            System.out.println("AntigravityCompatibilityGatewayTest PASS checks="+checks);
        } finally {try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
}
