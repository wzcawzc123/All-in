package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real HTTP upstream, controlled credentials, no production accounts or public inference. */
public final class CustomProviderHttpTest {
    static int checks;
    static void check(boolean v,String message) { checks++; if(!v) throw new AssertionError(message); }
    static JSONObject provider(String protocol,String auth,String base) {
        return CustomProvider.validate(new JSONObject().put("id","custom-test").put("name","Test <relay>")
                .put("type","custom-api").put("baseUrl",base).put("protocol",protocol).put("authMode",auth).put("enabled",true)
                .put("keys",new JSONArray().put("fixture-custom-key")).put("models",new JSONArray().put("org/model"))
                .put("headers",new JSONObject().put("User-Agent","Fixture-Allowed/1.0").put("X-Allowlist","fixture-header-secret")));
    }
    static JSONObject config(JSONObject p) {return new JSONObject().put("server",new JSONObject().put("authKey","fixture-inference").put("port",3425))
            .put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p)).put("groups",new JSONArray())
            .put("guard",new JSONObject().put("enabled",false));}
    static NanoHTTPD.Response call(GatewayServer g,String path,String method,JSONObject body,boolean management) {
        Map<String,String> headers=management?GatewayAccessBoundaryOfflineTest.management():new HashMap<>(Map.of("host","127.0.0.1:3425","authorization","Bearer fixture-inference"));
        return g.serve(GatewayAccessBoundaryOfflineTest.session("127.0.0.1",path,method,body==null?"":body.toString(),headers));
    }
    static JSONObject view(GatewayServer g) throws Exception {return new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/config","GET",null,true)));}
    static int code(NanoHTTPD.Response r) {return GatewayAccessBoundaryOfflineTest.code(r);}
    static JSONObject ingress(String protocol,boolean stream,boolean toolResult) {
        JSONObject q=new JSONObject().put("model","custom-test/org/model").put("stream",stream);
        if("responses".equals(protocol)) {
            q.put("input",toolResult?new JSONArray().put(new JSONObject().put("type","function_call").put("call_id","call_1").put("name","sum").put("arguments","{}"))
                    .put(new JSONObject().put("type","function_call_output").put("call_id","call_1").put("output","9193")):"hello");
            q.put("max_output_tokens",32);
        } else if("anthropic".equals(protocol)) {
            q.put("max_tokens",32).put("messages",toolResult?new JSONArray().put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","call_1").put("name","sum").put("input",new JSONObject()))))
                    .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","call_1").put("content","9193")))):
                    new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        } else {
            q.put("messages",toolResult?new JSONArray().put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","call_1").put("type","function").put("function",new JSONObject().put("name","sum").put("arguments","{}")))))
                    .put(new JSONObject().put("role","tool").put("tool_call_id","call_1").put("content","9193")):
                    new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));
        }
        return q;
    }
    static String path(String proto) {return "responses".equals(proto)?"/v1/responses":"anthropic".equals(proto)?"/v1/messages":"/v1/chat/completions";}
    static final class Peer implements AutoCloseable {
        final HttpServer server; final ExecutorService executor=Executors.newCachedThreadPool();
        volatile JSONObject received; volatile Map<String,List<String>> headers; volatile String path;
        volatile int status=200; volatile String catalog="{\"data\":[{\"id\":\"org/model\"}]}"; volatile Runnable hook;
        final AtomicInteger calls=new AtomicInteger();
        Peer(String protocol) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{try {
                calls.incrementAndGet();path=exchange.getRequestURI().toString();headers=new HashMap<>(exchange.getRequestHeaders());
                String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(hook!=null) hook.run();
                boolean models="GET".equals(exchange.getRequestMethod()); received=models?new JSONObject():new JSONObject(raw);
                String reply; boolean stream=received.optBoolean("stream",false);
                if(models) reply=catalog;
                else if(status!=200) reply="{\"error\":{\"message\":\"fixture-header-secret denied\",\"type\":\"invalid_request_error\"}}";
                else if("anthropic".equals(protocol)) {
                    reply="{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"org/model\",\"content\":[{\"type\":\"text\",\"text\":\"PASS9193\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}";
                    if(stream) reply="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"org/model\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"PASS9193\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
                } else if("responses".equals(protocol)) {
                    JSONObject completed=new JSONObject("{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"org/model\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"PASS9193\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"total_tokens\":3}}");
                    reply=completed.toString();
                    if(stream) reply="event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\",\"output\":[]}}\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"PASS9193\"}\n\nevent: response.completed\ndata: "+new JSONObject().put("type","response.completed").put("response",completed)+"\n\n";
                } else {
                    reply="{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"model\":\"org/model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"PASS9193\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
                    if(stream) reply="data: {\"id\":\"chat_1\",\"object\":\"chat.completion.chunk\",\"model\":\"org/model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"PASS9193\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"chat_1\",\"object\":\"chat.completion.chunk\",\"model\":\"org/model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n";
                }
                byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
                if(status==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/stolen");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
        }
        String base() {return "http://127.0.0.1:"+server.getAddress().getPort()+"/relay/v1";}
        String header(String name) {for(Map.Entry<String,List<String>> e:headers.entrySet())if(name.equalsIgnoreCase(e.getKey()))return String.join(",",e.getValue());return "";}
        public void close() {server.stop(0);executor.shutdownNow();}
    }
    static void matrix() throws Exception {
        for(String upstream:new String[]{"openai","responses","anthropic"}) for(String client:new String[]{"openai","responses","anthropic"}) for(boolean stream:new boolean[]{false,true}) {
            try(Peer peer=new Peer(upstream)) {
                GatewayServer g=RoutingSetupOfflineTest.server(config(provider(upstream,"auto",peer.base())));
                g.setVendorConnectionsForTests(url->(HttpURLConnection)new URL(url).openConnection());
                NanoHTTPD.Response r=call(g,path(client),"POST",ingress(client,stream,true),false);int status=r.getStatus().getRequestStatus();String result=GatewayAccessBoundaryOfflineTest.body(r);
                check(status==200,upstream+" -> "+client+" stream="+stream+" status="+status+" "+result);
                check(result.contains("PASS9193"),"answer lost "+upstream+" "+client);
                check(peer.received.toString().contains("9193"),"tool result lost");
                check("org/model".equals(peer.received.optString("model")),"nested model ID lost");
                check(peer.path.equals("/relay/v1/"+("anthropic".equals(upstream)?"messages":"responses".equals(upstream)?"responses":"chat/completions")),"wrong endpoint "+peer.path);
                check("Fixture-Allowed/1.0".equals(peer.header("User-Agent")),"UA override ignored");
                check("fixture-header-secret".equals(peer.header("X-Allowlist")),"header missing");
                check("anthropic".equals(upstream)?"fixture-custom-key".equals(peer.header("x-api-key")):"Bearer fixture-custom-key".equals(peer.header("Authorization")),"auth mismatch");
                check(peer.calls.get()==1,"request replayed");
                if(stream&&"openai".equals(client))check(result.split("data: \\[DONE\\]",-1).length-1==1,"DONE count not one");
            }
        }
    }
    static void management() throws Exception {
        Path dir=Files.createTempDirectory("custom-api-http-");
        try(Peer peer=new Peer("openai")) {
            JSONObject baseline=config(new JSONObject().put("id","openai").put("protocol","openai").put("keys",new JSONArray().put("original-fixture-key")).put("models",new JSONArray().put("old")));
            GatewayServer g=RoutingSetupOfflineTest.server(baseline);RoutingAuditOfflineTest.file(g,dir.resolve("config.json").toFile());
            g.setVendorConnectionsForTests(url->(HttpURLConnection)new URL(url).openConnection());
            JSONObject p=provider("openai","auto",peer.base());p.remove("type");p.remove("status");
            JSONObject save=new JSONObject().put("action","save").put("provider",p);
            check(code(call(g,"/api/providers/custom","POST",save,false))==401,"inference credential managed provider");
            check(code(call(g,"/api/providers/custom","POST",save,true))==200,"creation failed");
            JSONObject view=view(g),redacted=view.getJSONArray("providers").getJSONObject(1);
            check(!view.toString().contains("fixture-header-secret")&&!view.toString().contains("fixture-custom-key"),"header/key leaked in config");
            JSONObject edit=new JSONObject(redacted.toString());edit.remove("type");edit.remove("status");edit.remove("modelAliases");edit.put("name","Renamed");
            String token=view.getJSONObject("providerStateTokens").getString("custom-test");
            check(code(call(g,"/api/providers/custom","POST",new JSONObject().put("action","save").put("provider",edit).put("expectedStateToken",token),true))==200,"redacted edit failed");
            JSONObject disk=new JSONObject(Files.readString(dir.resolve("config.json")));
            check(disk.getJSONArray("providers").getJSONObject(1).getJSONObject("headers").getString("X-Allowlist").equals("fixture-header-secret"),"header ref corrupted");
            check(disk.getJSONArray("providers").getJSONObject(0).similar(baseline.getJSONArray("providers").getJSONObject(0)),"built-in provider mutated");
            check(code(call(g,"/api/providers/custom","POST",new JSONObject().put("action","save").put("provider",edit).put("expectedStateToken",token),true))==409,"stale update accepted");
            JSONObject keyView=view(g);String keyToken=keyView.getJSONObject("providerStateTokens").getString("custom-test");
            byte[] keyBefore=Files.readAllBytes(dir.resolve("config.json"));
            JSONObject invalidKeys=new JSONObject().put("providerId","custom-test").put("expectedStateToken",keyToken).put("keys",new JSONArray().put("bad"+(char)10+"key"));
            check(code(call(g,"/api/providers/keys","POST",invalidKeys,true))==400,"generic key endpoint allowed CR/LF");
            check(Arrays.equals(keyBefore,Files.readAllBytes(dir.resolve("config.json"))),"invalid key write mutated config");
            check(code(call(g,"/api/auth/custom-test/sync-models","GET",null,true))==200,"sync failed");
            check(peer.path.equals("/relay/v1/models")&&peer.header("X-Allowlist").equals("fixture-header-secret"),"catalog didn't use custom endpoint/headers");
            peer.catalog="{\"data\":[{\"id\":12}]}";byte[] before=Files.readAllBytes(dir.resolve("config.json"));
            check(code(call(g,"/api/auth/custom-test/sync-models","GET",null,true))==502,"invalid catalog succeeded");
            check(Arrays.equals(before,Files.readAllBytes(dir.resolve("config.json"))),"failed catalog replaced state");
            peer.catalog="{\"data\":[{\"id\":\"new-model\"}]}";
            peer.hook=()->{try{ResponsesGatewayOfflineTest.config(g).getJSONArray("providers").getJSONObject(1).getJSONObject("headers").put("X-New","changed");}catch(Exception e){throw new RuntimeException(e);}};
            check(code(call(g,"/api/auth/custom-test/sync-models","GET",null,true))==409,"concurrent header edit lost");peer.hook=null;
            view=view(g);token=view.getJSONObject("providerStateTokens").getString("custom-test");
            check(code(call(g,"/api/providers/custom","POST",new JSONObject().put("action","remove").put("providerId","custom-test").put("expectedStateToken",token),true))==200,"remove failed");
            check(view(g).getJSONArray("providers").length()==1,"provider removal failed");
        }finally{try(java.util.stream.Stream<Path> walk=Files.walk(dir)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
    static void headersAndRejections() throws Exception {
        for(String auth:new String[]{"none","headers"})for(String protocol:new String[]{"openai","responses","anthropic"})try(Peer peer=new Peer(protocol)) {
            JSONObject p=provider(protocol,auth,peer.base()).put("keys",new JSONArray());if("headers".equals(auth))p.getJSONObject("headers").put("Authorization","Custom fixture-auth");
            GatewayServer g=RoutingSetupOfflineTest.server(config(p));g.setVendorConnectionsForTests(url->(HttpURLConnection)new URL(url).openConnection());
            NanoHTTPD.Response r=call(g,path(protocol),"POST",ingress(protocol,false,false),false);String result=GatewayAccessBoundaryOfflineTest.body(r);
            check(r.getStatus().getRequestStatus()==200,"nonstandard auth rejected "+auth+" "+protocol+" "+result);
            check(peer.header("Authorization").equals("headers".equals(auth)?"Custom fixture-auth":""),"unexpected auth credential");
            check(peer.header("x-api-key").isEmpty(),"synthetic credential sent");
            peer.status=302;int before=peer.calls.get();r=call(g,path(protocol),"POST",ingress(protocol,false,false),false);result=GatewayAccessBoundaryOfflineTest.body(r);
            check(r.getStatus().getRequestStatus()>=400,"redirect became success");check(peer.calls.get()==before+1,"redirect/failure replayed");
            check(!result.contains("fixture-header-secret"),"upstream reflected header secret");
        }
        JSONObject p=provider("openai","auto","https://example.invalid/v1");
        for(String name:new String[]{"Host","Content-Length","Transfer-Encoding","Connection","Accept-Encoding","X-All-In-Management","Proxy-Authorization","bad name",(char)10+"X-Test"}) {
            JSONObject copy=new JSONObject(p.toString());copy.getJSONObject("headers").put(name,"x");boolean rejected=false;try{CustomProvider.validate(copy);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"dangerous header accepted "+name);
        }
        for(String value:new String[]{"\r\nInjected: yes","x\n","\tbad","你好"}) {JSONObject copy=new JSONObject(p.toString());copy.getJSONObject("headers").put("X-Test",value);boolean rejected=false;try{CustomProvider.validate(copy);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid value accepted");}
        check(CustomProvider.base("https://example.invalid").equals("https://example.invalid/v1"),"root not normalized");
        check(CustomProvider.base("https://example.invalid/api/v4/chat/completions/").equals("https://example.invalid/api/v4"),"custom path changed");
        check(CustomProvider.self(provider("openai","none","http://127.0.0.1:3425/v1"),3425),"self loop not rejected");
    }
    static void relayIntegrationRegressions() throws Exception {
        Path dir=Files.createTempDirectory("custom-relay-reg-");
        try(Peer respPeer=new Peer("responses"); Peer openaiPeer=new Peer("openai"); Peer anthPeer=new Peer("anthropic")) {
            JSONObject cfg=new JSONObject()
                    .put("server",new JSONObject().put("authKey","fixture-inference").put("port",3425))
                    .put("setup",new JSONObject().put("done",true))
                    .put("routing",new JSONObject().put("groupsEnabled",true).put("bareModelFallback",true))
                    .put("guard",new JSONObject().put("enabled",false))
                    .put("privacy",new JSONObject().put("enabled",false))
                    .put("middleware",new JSONObject().put("paramOverrideByProvider",new JSONObject()
                            .put("custom-openai-mw",new JSONObject().put("set",new JSONObject().put("temperature",0.25)))))
                    .put("groups",new JSONArray()
                            .put(new JSONObject().put("id","live-alias-grp").put("enabled",true).put("strategy","order").put("targets",new JSONArray().put("m-openai")))
                            .put(new JSONObject().put("id","dead-unselected-grp").put("enabled",true).put("strategy","order").put("targets",new JSONArray().put("custom-hdr-empty/m-unselected"))))
                    .put("providers",new JSONArray()
                            .put(provider("responses","auto",respPeer.base()).put("id","custom-resp-ping").put("name","RespPing").put("models",new JSONArray().put("m-resp")))
                            .put(provider("openai","auto",openaiPeer.base()).put("id","custom-openai-mw").put("name","OpenAiMw").put("models",new JSONArray().put("m-openai")))
                            .put(provider("openai","auto",openaiPeer.base()).put("id","custom-disabled").put("name","Disabled").put("enabled",false).put("models",new JSONArray().put("m-disabled")))
                            .put(provider("openai","headers",openaiPeer.base()).put("id","custom-hdr-empty").put("name","HdrEmpty").put("keys",new JSONArray()).put("models",new JSONArray()).put("catalogModels",new JSONArray().put("m-unselected")).put("headers",new JSONObject().put("Authorization","Bearer fixture-hdr-tok")))
                            .put(provider("anthropic","headers",anthPeer.base()).put("id","custom-anth-short").put("name","AnthShort").put("keys",new JSONArray()).put("models",new JSONArray().put("m-anth")).put("headers",new JSONObject().put("X-Flag","1").put("X-Kind","error").put("X-Allowlist","fixture-header-secret").put("Authorization","Bearer fixture-secret-tok")))
                            .put(provider("responses","auto","http://127.0.0.1:1/v1").put("id","custom-resp-refused").put("name","RespRefused").put("models",new JSONArray().put("m-refused"))));
            GatewayServer g=RoutingSetupOfflineTest.server(cfg);
            RoutingAuditOfflineTest.file(g,dir.resolve("config.json").toFile());
            Files.write(dir.resolve("config.json"),cfg.toString().getBytes(StandardCharsets.UTF_8));
            g.setVendorConnectionsForTests(url->(HttpURLConnection)new URL(url).openConnection());

            // 1. /api/test-model on custom Responses provider must return ok=true with real reply
            JSONObject pingResp=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/test-model","POST",new JSONObject().put("model","m-resp"),true)));
            check(pingResp.optBoolean("ok") && pingResp.optString("reply").contains("PASS9193"),"custom responses /api/test-model failed: "+pingResp);

            // 2. noCandidateReason with readable custom aliases & headers authMode
            JSONObject disResp=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/test-model","POST",new JSONObject().put("model","m-disabled"),true)));
            check("provider_disabled".equals(disResp.optString("reason")),"disabled readable alias wrong reason: "+disResp);
            JSONObject unselResp=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/test-model","POST",new JSONObject().put("model","m-unselected"),true)));
            check("model_not_listed".equals(unselResp.optString("reason")),"headers unselected readable alias wrong reason: "+unselResp);

            // 3. groupHasLiveTarget with readable alias & unselected custom target
            JSONObject v1Models=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/v1/models","GET",null,false)));
            String dataStr=v1Models.getJSONArray("data").toString();
            check(dataStr.contains("group/live-alias-grp"),"readable alias group hidden from /v1/models data: "+v1Models);
            check(!dataStr.contains("group/dead-unselected-grp"),"unselected custom group exposed in /v1/models data: "+v1Models);

            // 4. /api/exposed accepts readable custom alias
            JSONObject expOut=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/exposed","POST",new JSONObject().put("enabled",true).put("items",new JSONArray().put("m-openai")),true)));
            check(expOut.optInt("count")==1 && expOut.getJSONArray("items").getString(0).equals("custom-openai-mw/m-openai"),"/api/exposed dropped readable alias: "+expOut);

            // 5. Provider-scoped middleware applies when called via readable alias
            NanoHTTPD.Response mwCall=call(g,"/v1/chat/completions","POST",ingress("openai",false,false).put("model","m-openai"),false);
            check(code(mwCall)==200 && Math.abs(openaiPeer.received.optDouble("temperature",-1)-0.25)<0.001,"provider-scoped middleware missed readable alias: "+openaiPeer.received);

            // 6. Short non-sensitive headers do not corrupt upstream JSON errors, while Bearer token is still scrubbed
            anthPeer.status=403;
            NanoHTTPD.Response anthErr=call(g,"/v1/chat/completions","POST",ingress("openai",false,false).put("model","m-anth"),false);
            String anthErrBody=GatewayAccessBoundaryOfflineTest.body(anthErr);
            check(code(anthErr)==403 && !anthErrBody.contains("Unstructured upstream error") && !anthErrBody.contains("fixture-header-secret") && !anthErrBody.contains("fixture-secret-tok"),
                    "short header corrupted error or leaked secret: "+anthErrBody);

            // 7. Multi-turn /v1/responses echo with phase & output_text.logprobs passes through to custom Responses provider intact
            JSONObject phaseTurn=new JSONObject().put("model","m-resp").put("stream",false).put("input",new JSONArray()
                    .put(new JSONObject().put("type","message").put("role","user").put("content","hi"))
                    .put(new JSONObject().put("type","message").put("id","msg_prev").put("role","assistant").put("status","completed").put("phase","final_answer")
                            .put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","prev").put("annotations",new JSONArray()).put("logprobs",new JSONArray()))))
                    .put(new JSONObject().put("type","message").put("role","user").put("content","next")));
            NanoHTTPD.Response phaseRes=call(g,"/v1/responses","POST",phaseTurn,false);
            check(code(phaseRes)==200 && "final_answer".equals(respPeer.received.getJSONArray("input").getJSONObject(1).optString("phase")),
                    "custom responses multi-turn phase echo failed: "+code(phaseRes)+" "+GatewayAccessBoundaryOfflineTest.body(phaseRes));

            // 8. ResponsesBridge.responsesResponseToChat reads reasoning.content as well as summary
            JSONObject reasoningOnly=new JSONObject().put("id","resp_r").put("status","completed").put("output",new JSONArray()
                    .put(new JSONObject().put("type","reasoning").put("content",new JSONArray().put(new JSONObject().put("type","reasoning_text").put("text","step-1"))))
                    .put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","ans")))));
            JSONObject chatFromResp=ResponsesBridge.responsesResponseToChat(reasoningOnly,"m-resp");
            check("step-1".equals(chatFromResp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("reasoning_content")),
                    "responsesResponseToChat dropped reasoning.content: "+chatFromResp);

            // 9. Transport error classification on custom Responses provider
            JSONObject refPing=new JSONObject(GatewayAccessBoundaryOfflineTest.body(call(g,"/api/test-model","POST",new JSONObject().put("model","m-refused"),true)));
            check(!refPing.optBoolean("ok") && !refPing.optString("transportKind").isEmpty(),"custom responses transportKind missing: "+refPing);
        } finally {
            try(java.util.stream.Stream<Path> walk=Files.walk(dir)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        }
    }
    public static void main(String[] args)throws Exception {headersAndRejections();management();matrix();relayIntegrationRegressions();System.out.println("CustomProviderHttpTest PASS "+checks+" assertions; 18 protocol/SSE/tool-result paths, 6 auth paths, management/CAS/catalog/security, 9 relay integration regressions");}
}
