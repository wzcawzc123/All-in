package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK-shaped requests and actual multi-turn phase replay through both HTTP sides. */
public final class CodexChatProjectionHttpTest {
    static int checks, requests;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static JSONObject sdk(boolean stream,String purpose){
        JSONObject b=CodexClientCompatibilityHttpTest.body("/v1/chat/completions","text",stream,true).put("store",false)
                .put("stop",JSONObject.NULL).put("reasoning",new JSONObject().put("effort","low"))
                .put("sdk_request_purpose",purpose).put("prompt_cache_retention","24h")
                .put("stream_options",new JSONObject().put("include_usage",true).put("vendor_trace",true));
        b.remove("reasoning_effort");
        b.getJSONArray("messages").getJSONObject(1).put("name","client-user");
        b.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("providerOptions",new JSONObject().put("tag","sdk"));
        return b;
    }
    static String phases(String phase){return CodexClientCompatibilityHttpTest.events(false)
            .replace("\"role\":\"assistant\"","\"role\":\"assistant\",\"phase\":\""+phase+"\"")
            .replace("\"text\":\"CODEX_COMPAT_OK\"","\"text\":\"CODEX_COMPAT_OK\",\"annotations\":[],\"logprobs\":[]");}
    static JSONObject finalResponse(String text,boolean stream){
        if(!stream)return new JSONObject(text);
        for(String line:text.split("\n"))if(line.startsWith("data:")){
            String raw=line.substring(5).trim();if(raw.equals("[DONE]"))continue;
            JSONObject e=new JSONObject(raw);if(e.optString("type").equals("response.completed"))return e.getJSONObject("response");
        }
        throw new AssertionError("missing native terminal");
    }
    static final class WarningReply { CodexClientCompatibilityHttpTest.Reply reply; String warning; }
    static WarningReply postWithWarning(CodexClientCompatibilityHttpTest.Front front, String path, JSONObject body) throws Exception {
        java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+front.getListeningPort()+path).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer secret");c.setRequestProperty("Content-Type","application/json");
        byte[] data=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);
        try(OutputStream out=c.getOutputStream()){out.write(data);}
        WarningReply wrapped=new WarningReply();wrapped.reply=new CodexClientCompatibilityHttpTest.Reply();wrapped.reply.status=c.getResponseCode();
        wrapped.warning=c.getHeaderField("X-AllIn-Compatibility-Warning");
        try(InputStream in=wrapped.reply.status<400?c.getInputStream():c.getErrorStream()){wrapped.reply.text=ResponsesGatewayOfflineTest.read(in);}
        finally{c.disconnect();}
        return wrapped;
    }
    static void sdkTurns()throws Exception{
        for(boolean stream:new boolean[]{false,true})for(String purpose:new String[]{"automatic-title","agent-first-turn","agent-next-turn"})
            try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,CodexClientCompatibilityHttpTest.events(false))){
                GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());JSONObject b=sdk(stream,purpose);
                if(purpose.equals("agent-next-turn"))b.getJSONArray("messages").put(new JSONObject().put("role","assistant").put("content","Previous reply").put("phase","final_answer"))
                        .put(new JSONObject().put("role","user").put("content","Continue"));
                try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                    WarningReply wrapped=postWithWarning(f,"/v1/chat/completions",b);
                    CodexClientCompatibilityHttpTest.output(wrapped.reply,"/v1/chat/completions","text",stream);
                    check(wrapped.warning!=null&&wrapped.warning.contains("codex-client-extension-fields-not-forwarded"),"actual HTTP compatibility warning emitted, not just a parser flag");
                }
                check(u.count.get()==1,"SDK one dispatch, no retry");
                check(!u.body.has("sdk_request_purpose")&&!u.body.has("prompt_cache_retention")&&!u.body.getBoolean("store"),"incidental SDK fields not sent/store remains false");
                check(u.body.getJSONObject("reasoning").getString("effort").equals("low"),"SDK effort preserved");
                CodexClientCompatibilityHttpTest.finalWire(u.body,"/v1/chat/completions","text");
                if(purpose.equals("agent-next-turn")){
                    JSONArray input=u.body.getJSONArray("input");check(input.getJSONObject(input.length()-2).getString("phase").equals("final_answer"),"Chat replay phase retained");
                }
                requests++;
            }
    }
    static void actualNativeReplay()throws Exception{
        for(String phase:new String[]{"commentary","final_answer"})for(boolean stream:new boolean[]{false,true})
            try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,phases(phase))){
                GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                JSONObject b=CodexClientCompatibilityHttpTest.body("/v1/responses","text",stream,true).put("store",false);
                try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                    CodexClientCompatibilityHttpTest.Reply first=CodexClientCompatibilityHttpTest.post(f,"/v1/responses",b);
                    CodexClientCompatibilityHttpTest.output(first,"/v1/responses","text",stream);
                    JSONObject item=finalResponse(first.text,stream).getJSONArray("output").getJSONObject(0);
                    check(item.getString("phase").equals(phase),"real returned native output contains phase");
                    item.put("internal_chat_message_metadata_passthrough",new JSONObject().put("turn_id","native-turn"));
                    b.getJSONArray("input").put(item).put(new JSONObject().put("type","message").put("role","user")
                            .put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text","Continue"))));
                    CodexClientCompatibilityHttpTest.Reply second=CodexClientCompatibilityHttpTest.post(f,"/v1/responses",b);
                    CodexClientCompatibilityHttpTest.output(second,"/v1/responses","text",stream);
                    check(u.count.get()==2,"native two turns, no repeat dispatch");
                    JSONObject actual=u.body.getJSONArray("input").getJSONObject(2);
                    check(actual.getString("phase").equals(phase)&&actual.getString("id").equals(item.getString("id"))
                            &&actual.getString("status").equals("completed"),"phase/id/status preserved in final native request");
                    check(actual.getJSONObject("internal_chat_message_metadata_passthrough").getString("turn_id").equals("native-turn"),"native metadata not deleted to appease shadow validator");
                }
                requests+=2;
            }
    }
    static void sdkToolRoundtrip()throws Exception{
        for(boolean stream:new boolean[]{false,true}){
            JSONObject b=sdk(stream,"agent-tools");
            try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,CodexClientCompatibilityHttpTest.events(true))){
                GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                    CodexClientCompatibilityHttpTest.output(CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b),"/v1/chat/completions","call",stream);
                }
                check(u.count.get()==1,"SDK tool call one dispatch");requests++;
            }
            b=CodexClientCompatibilityHttpTest.body("/v1/chat/completions","result",stream,true).put("store",false).put("sdk_request_purpose","tool-result");
            try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,CodexClientCompatibilityHttpTest.events(false))){
                GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                    CodexClientCompatibilityHttpTest.output(CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b),"/v1/chat/completions","result",stream);
                }
                CodexClientCompatibilityHttpTest.finalWire(u.body,"/v1/chat/completions","result");check(u.count.get()==1,"SDK result one dispatch");requests++;
            }
        }
    }
    static void rejectedControlsHaveZeroDispatch()throws Exception{
        for(JSONObject b:new JSONObject[]{sdk(false,"bad").put("store",true),sdk(false,"bad").put("n",2),sdk(false,"bad").put("conversation","server-state"),
                sdk(false,"bad").put("store","false")}){
            GatewayServer g=CodexRequestPolicyOfflineTest.gateway(1);AtomicInteger opens=new AtomicInteger();g.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw new IOException("must not dispatch");});
            try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                CodexClientCompatibilityHttpTest.Reply r=CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b);check(r.status==400,"known state/type control explicitly rejected");
            }
            check(opens.get()==0,"rejected controls never dispatch");
        }
    }
    static void realLan() throws Exception {
        String base=System.getenv("ALLIN_TEST_LAN_BASE"),key=System.getenv("ALLIN_TEST_LAN_KEY");
        if(base==null||key==null||key.isEmpty())throw new IllegalArgumentException("Explicit real-LAN test environment is required");
        GatewayServer g=CodexRequestPolicyOfflineTest.gateway(1);JSONObject config=ResponsesGatewayOfflineTest.config(g);
        JSONObject provider=config.getJSONArray("providers").getJSONObject(0);
        provider.put("models",new JSONArray().put("gpt-6-luna")).put("keys",new JSONArray().put(key));
        provider.getJSONArray("accounts").getJSONObject(0).put("access",key);
        g.setVendorConnectionsForTests(url->{
            if(!url.startsWith(CodexAuth.CODEX_BASE))throw new IOException("Live test only permits the Codex target");
            return NetworkPolicy.openHttp(base+"/backend-api/codex/responses");
        });
        try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)) {
            for(boolean stream:new boolean[]{false,true}) {
                JSONObject b=sdk(stream,stream?"agent-first-turn":"automatic-title").put("model","codex/gpt-6-luna");b.remove("tools");
                String marker=stream?"SEMANTIC_LAN_STREAM_OK":"SEMANTIC_LAN_TITLE_OK";
                b.getJSONArray("messages").getJSONObject(1).put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","Reply only "+marker+".")));
                CodexClientCompatibilityHttpTest.Reply r=CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b);
                check(r.status==200,"real SDK Chat status "+r.status+" "+r.text);
                if(!stream)check(new JSONObject(r.text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").equals(marker),"real title response exact");
                else {
                    StringBuilder content=new StringBuilder();int stops=0,done=0;
                    for(String line:r.text.split("\n"))if(line.startsWith("data:")) {
                        String raw=line.substring(5).trim();if(raw.equals("[DONE]")){done++;continue;}
                        JSONObject event=new JSONObject(raw);check(!event.has("error"),"real SSE no error");JSONArray choices=event.optJSONArray("choices");
                        for(int i=0;choices!=null&&i<choices.length();i++){
                            JSONObject c=choices.getJSONObject(i);if(c.has("finish_reason")&&!c.isNull("finish_reason"))stops++;
                            JSONObject d=c.optJSONObject("delta");if(d!=null)content.append(d.optString("content",""));
                        }
                    }
                    check(content.toString().equals(marker)&&stops==1&&done==1,"real SSE content/single stop/DONE");
                }
                System.out.println("REAL_LAN_PASS "+(stream?"SDK_CHAT_SSE":"SDK_CHAT_TITLE")+" status=200 marker="+marker);
            }
            JSONObject nativeBody=new JSONObject().put("model","codex/gpt-6-luna").put("stream",false).put("store",false).put("reasoning",new JSONObject().put("effort","low"))
                    .put("input",new JSONArray().put(new JSONObject().put("type","message").put("role","user")
                            .put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text","Reply only SEMANTIC_LAN_NATIVE_1.")))));
            CodexClientCompatibilityHttpTest.Reply first=CodexClientCompatibilityHttpTest.post(f,"/v1/responses",nativeBody);
            check(first.status==200,"real native first status "+first.status+" "+first.text);JSONObject response=new JSONObject(first.text);
            check(response.getString("status").equals("completed"),"real native first terminal");
            JSONArray items=response.getJSONArray("output");boolean phase=false;
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(item.has("phase"))phase=true;nativeBody.getJSONArray("input").put(item);}
            check(phase,"real upstream returned phase; no synthetic field substituted");
            nativeBody.getJSONArray("input").put(new JSONObject().put("type","message").put("role","user")
                    .put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text","Reply only SEMANTIC_LAN_NATIVE_2."))));
            CodexClientCompatibilityHttpTest.Reply second=CodexClientCompatibilityHttpTest.post(f,"/v1/responses",nativeBody);
            check(second.status==200,"real native replay status "+second.status+" "+second.text);response=new JSONObject(second.text);
            check(response.getString("status").equals("completed")&&response.getJSONArray("output").toString().contains("SEMANTIC_LAN_NATIVE_2"),"real native phase replay exact marker/terminal");
            System.out.println("REAL_LAN_PASS RESPONSES_ORIGINAL_PHASE_REPLAY two_statuses=200 marker=SEMANTIC_LAN_NATIVE_2");
            JSONObject toolSchema=new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("cache_control",new JSONObject().put("type","string").put("enum",new JSONArray().put("business-value")))
                    .put("value",new JSONObject().put("type","string").put("enum",new JSONArray().put("SEMANTIC_LAN_TOOL"))))
                    .put("required",new JSONArray().put("cache_control").put("value")).put("additionalProperties",false);
            JSONObject b=sdk(false,"agent-tools").put("model","codex/gpt-6-luna");
            b.put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                    .put("name","compatibility_echo").put("description","Synthetic echo. No commands, files or external actions.").put("parameters",toolSchema).put("strict",true)
                    .put("providerOptions",new JSONObject().put("tag","sdk")))));
            b.put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",new JSONArray().put(new JSONObject().put("type","text")
                    .put("text","Call the requested echo exactly once; after its result reply only the cache_control.marker value from the tool output."))))
                    .put(new JSONObject().put("role","user").put("content","Call compatibility_echo with cache_control=business-value and value=SEMANTIC_LAN_TOOL.")));
            CodexClientCompatibilityHttpTest.Reply call=CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b);
            check(call.status==200,"real tool request status "+call.status+" "+call.text);JSONObject choice=new JSONObject(call.text).getJSONArray("choices").getJSONObject(0),message=choice.getJSONObject("message");
            check(choice.getString("finish_reason").equals("tool_calls"),"real tool finish");JSONObject tool=message.getJSONArray("tool_calls").getJSONObject(0);
            JSONObject args=new JSONObject(tool.getJSONObject("function").getString("arguments"));
            check(args.getString("cache_control").equals("business-value")&&args.getString("value").equals("SEMANTIC_LAN_TOOL"),"real business args exact");
            if(!message.has("reasoning_content"))message.put("reasoning_content","Synthetic display-only Chat thinking summary for compatibility verification.");
            b.getJSONArray("messages").put(message).put(new JSONObject().put("role","tool").put("tool_call_id",tool.getString("id"))
                    .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",new JSONObject().put("cache_control",new JSONObject().put("marker","SEMANTIC_LAN_TOOL_DONE")).toString()))));
            CodexClientCompatibilityHttpTest.Reply result=CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",b);
            check(result.status==200,"real tool result status "+result.status+" "+result.text);choice=new JSONObject(result.text).getJSONArray("choices").getJSONObject(0);
            check(choice.getString("finish_reason").equals("stop")&&choice.getJSONObject("message").getString("content").equals("SEMANTIC_LAN_TOOL_DONE"),"real tool result array/business marker exact");
            System.out.println("REAL_LAN_PASS SDK_TOOL_AND_ARRAY_RESULT two_statuses=200 marker=SEMANTIC_LAN_TOOL_DONE");
        }
        System.out.println("CodexChatProjection REAL_LAN_SUCCESS: 6 real requests through current JVM gateway -> installed phone native relay -> Codex; this is not an actual desktop UI or new APK device test");
    }
    static void nullableLimits() throws Exception {
        for(String path:new String[]{"/v1/chat/completions","/v1/responses"})
            try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,CodexClientCompatibilityHttpTest.events(false))){
                GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
                JSONObject b=CodexClientCompatibilityHttpTest.body(path,"text",false,true);
                if(path.endsWith("chat/completions"))b.put("max_tokens",JSONObject.NULL).put("max_completion_tokens",JSONObject.NULL);
                else b.put("max_output_tokens",JSONObject.NULL);
                try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                    CodexClientCompatibilityHttpTest.output(CodexClientCompatibilityHttpTest.post(f,path,b),path,"text",false);
                }
                check(u.count.get()==1&&!u.body.has("max_output_tokens")&&!u.body.has("max_completion_tokens"),"nullable optional limits omitted, no spurious local rejection");
                requests++;
            }
        GatewayServer g=CodexRequestPolicyOfflineTest.gateway(1);ResponsesGatewayOfflineTest.config(g).getJSONObject("guard").put("maxOutputTokens",12);
        AtomicInteger opens=new AtomicInteger();g.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw new IOException("hard Guard must not dispatch");});
        try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
            CodexClientCompatibilityHttpTest.Reply r=CodexClientCompatibilityHttpTest.post(f,"/v1/chat/completions",sdk(false,"guard").put("max_tokens",JSONObject.NULL));
            check(r.status==400&&r.text.contains("Context Guard"),"nullable client limit does not disable configured hard Guard");
        }
        check(opens.get()==0,"hard Guard zero dispatch");
    }
    static void advisoryWarningOnActualResponse() throws Exception {
        try(CodexRequestPolicyOfflineTest.Upstream u=new CodexRequestPolicyOfflineTest.Upstream(false,200,CodexClientCompatibilityHttpTest.events(false))){
            GatewayServer g=CodexRequestPolicyOfflineTest.gateway(u.server.getLocalPort());
            try(CodexClientCompatibilityHttpTest.Front f=new CodexClientCompatibilityHttpTest.Front(g)){
                WarningReply r=postWithWarning(f,"/v1/chat/completions",sdk(false,"stop-advisory").put("stop",new JSONArray().put("END")));
                CodexClientCompatibilityHttpTest.output(r.reply,"/v1/chat/completions","text",false);
                check(r.warning!=null&&r.warning.contains("codex-stop-not-guaranteed"),"unsupported stop advisory measured on actual HTTP response");
            }
            check(u.count.get()==1&&!u.body.has("stop"),"Codex stop not forwarded, no repeat dispatch");requests++;
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--real-lan")){realLan();return;}
        sdkTurns();actualNativeReplay();sdkToolRoundtrip();rejectedControlsHaveZeroDispatch();nullableLimits();advisoryWarningOnActualResponse();
        check(requests==21,"all SDK/native replay/null/advisory matrix requests executed");
        System.out.println("CodexChatProjectionHttpTest passed: "+requests+" successful HTTP requests, "+checks+" own checks; shared output/wire assertions also executed");
    }
}
