package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A real gateway client must receive first content while the upstream is deliberately unfinished. */
public final class CatPawIncrementalHttpTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static String frame(String text,String reason,boolean finished){JSONObject p=new JSONObject().put("type","text");if(text!=null)p.put("text",text);if(reason!=null)p.put("reasoningContent",reason);return "data: "+new JSONObject().put("message",new JSONObject().put("model","test-model").put("finished",finished).put("content",new JSONArray().put(p)))+"\n\n";}
    static final class Tracked extends FilterInputStream {final AtomicBoolean closed=new AtomicBoolean();Tracked(InputStream in){super(in);}public void close()throws IOException{closed.set(true);super.close();}}
    static final class Delayed implements CatPawStream.Transport,AutoCloseable {
        final CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();final Tracked input;final PipedOutputStream output;Thread writer;volatile RequestContext request;volatile String prompt;final String first,second;
        Delayed(String a,String b)throws Exception{PipedInputStream pipe=new PipedInputStream(32768);input=new Tracked(pipe);output=new PipedOutputStream(pipe);first=a;second=b;}
        public CatPawStream.Reply post(String path,JSONObject body,Map<String,String> headers,RequestContext ctx)throws Exception{
            calls.incrementAndGet();request=ctx;check("CatX".equals(body.optString("source", "CatX")),"source retained");
            if(path.endsWith("/round")){prompt=body.getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text");return reply("{\"code\":0}",ctx);}
            if(path.endsWith("/event"))return reply("{}",ctx);
            writer=new Thread(()->{try{output.write(first.getBytes(StandardCharsets.UTF_8));output.flush();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("client never received first delta");output.write(second.getBytes(StandardCharsets.UTF_8));output.flush();}catch(IOException expectedOnCancel){}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{try{output.close();}catch(IOException ignored){}done.countDown();}},"catpaw-delayed-source");writer.setDaemon(true);writer.start();return new CatPawStream.Reply(200,input,null,ctx);
        }
        static CatPawStream.Reply reply(String s,RequestContext ctx){return new CatPawStream.Reply(200,new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)),null,ctx);}
        public void close()throws Exception{release.countDown();input.close();output.close();if(writer!=null){writer.join(3000);check(!writer.isAlive(),"producer stopped");}}
    }
    static JSONObject config(){JSONObject p=VendorPolicyGatewayHttpTest.provider("catpaw","catpaw","https://example.invalid","auto");p.put("accounts",new JSONArray().put(new JSONObject().put("id","synthetic-a").put("token","synthetic-token").put("enabled",true)));return VendorPolicyGatewayHttpTest.config(p);}
    static HttpURLConnection client(VendorPolicyGatewayHttpTest.Gateway gateway,String path,boolean anthropic,String content,boolean withTool)throws Exception{JSONObject body=VendorPolicyGatewayHttpTest.body("catpaw/auto",true);body.getJSONArray("messages").getJSONObject(0).put("content",content);if (withTool) {
            JSONObject schema = new JSONObject().put("type", "object");
            JSONObject tool = new JSONObject().put("name", "lookup");
            if (anthropic) tool.put("input_schema", schema);
            else tool = new JSONObject().put("type", "function").put("function", tool.put("parameters", schema));
            body.put("tools", new JSONArray().put(tool));
        }
        if(anthropic)body.put("max_tokens",100);HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(3000);c.setReadTimeout(4000);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer fixture-auth");c.setRequestProperty("Accept-Encoding","identity");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}return c;}
    static void delayedGateway(boolean anthropic, boolean withTool)throws Exception {
        try(VendorPolicyGatewayHttpTest.Upstream unused=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(500,"application/json","{}"));VendorPolicyGatewayHttpTest.Gateway gateway=new VendorPolicyGatewayHttpTest.Gateway(config(),unused,false);Delayed producer=new Delayed(frame("HELLO",null,false),frame("HELLO",null,false)+frame("HELLO WORLD",null,true)+"data: [DONE]\n\n")){
            gateway.gateway.setCatPawStreamForTests(producer);
            gateway.gateway.setCatPawTransportForTests((url, body, headers, timeout, accept) -> {
                throw new AssertionError("unexpected buffered CatPaw request");
            });
            HttpURLConnection c=client(gateway,anthropic?"/v1/messages":"/v1/chat/completions",anthropic,"hello",withTool);
            try{check(c.getResponseCode()==200,"HTTP success");check(c.getHeaderField("Transfer-Encoding").equalsIgnoreCase("chunked"),"not fixed-length buffered SSE");check("incremental".equals(c.getHeaderField("X-All-In-Stream-Mode")),"actual incremental path declared");BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));StringBuilder received=new StringBuilder();String line;boolean first=false;
                while((line=reader.readLine())!=null){received.append(line).append('\n');if(line.contains("HELLO")){first=true;break;}}
                check(first,"first delta received");check(producer.done.getCount()==1&&!producer.input.closed.get(),"first bytes arrived before upstream end, not manufactured after buffering");check(producer.calls.get()==3,"one round/event/turn only");if(withTool)check(producer.prompt.contains("lookup"),"tool declaration reaches upstream");producer.release.countDown();while((line=reader.readLine())!=null)received.append(line).append('\n');String text=received.toString();check(text.contains(" WORLD"),"cumulative suffix forwarded");check(text.contains(anthropic?"message_stop":"[DONE]"),"terminal forwarded");check(producer.input.closed.get(),"upstream closed after done");
                if(!anthropic){StringBuilder assembled=new StringBuilder();int pieces=0;for(String l:text.split("\n"))if(l.startsWith("data: ")&&!l.contains("[DONE]")){JSONObject j=new JSONObject(l.substring(6));JSONArray a=j.optJSONArray("choices");if(a==null||a.length()==0)continue;String d=a.getJSONObject(0).getJSONObject("delta").optString("content","");if(!d.isEmpty()){assembled.append(d);pieces++;}}check(assembled.toString().equals("HELLO WORLD"),"no repeated cumulative prefixes");check(pieces==2,"duplicate cumulative frame emits no fake chunk");}
            }finally{c.disconnect();producer.release.countDown();}
        }
    }
    static String read(CatPawStream stream)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}
    static CatPawStream direct(String wire,RequestContext context,AtomicInteger calls)throws Exception{return CatPawStream.open("synthetic-token","auto",new JSONArray().put(new JSONObject().put("role","user").put("content","test")),null,"catpaw/auto",context,(path,body,h,r)->{calls.incrementAndGet();return new CatPawStream.Reply(200,new ByteArrayInputStream((path.endsWith("/round")?"{\"code\":0}":path.endsWith("/event")?"{}":wire).getBytes(StandardCharsets.UTF_8)),null,r);});}
    static void edges()throws Exception {
        for(String wire:new String[]{"data: {\"error\":{\"code\":429}}\n\n","data: {\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"当前余额不足以完成本次操作\"}]}}\n\n"}){
            AtomicInteger calls=new AtomicInteger();try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000)){try{direct(wire,ctx,calls);throw new AssertionError("early rejection became success");}catch(CatPawAuth.Failure f){check(f.httpStatus==429||f.httpStatus==402,"early precise status");check(calls.get()==3,"early rejection not replayed");}}
        }
        for(String tail:new String[]{"data: {\"error\":{\"code\":429}}\n\n","data: {bad json}\n\n","","data: {\"message\":"})try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("start",null,false)+tail,ctx,new AtomicInteger())){String text=read(stream);check(text.contains("\"error\""),"late malformed/truncated error surfaced");check(!text.contains("\"finish_reason\":\"stop\""),"incomplete stream never reports successful stop");}
        try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("A", "reason",false)+frame("A😀B","reason more",true)+"data: [DONE]\n\n",ctx,new AtomicInteger())){String text=read(stream);check(text.contains("😀B")&&text.contains(" more"),"UTF8 and reasoning cumulative deltas");}
        String optionalNull="data: "+new JSONObject().put("message",new JSONObject().put("model","test-model").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",JSONObject.NULL).put("reasoningContent",JSONObject.NULL))))+"\n\n";
        try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("KEEP","THINK",false)+frame("KE","TH",false)+optionalNull+frame("KEEPEND","THINKMORE",true)+"data: [DONE]\n\n",ctx,new AtomicInteger())) {String text=read(stream);check(!text.contains("\"error\""),"null optional fields and stale cumulative prefixes are not invented failures");check(text.contains("END")&&text.contains("MORE"),"text and reasoning continue after empty metadata");}
        try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("complete",null,true),ctx,new AtomicInteger())){check(read(stream).contains("[DONE]"),"clean EOF with true vendor terminal supported");}
        try(RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("quote 当前余额不足以完成本次操作",null,true)+"data: [DONE]\n\n",ctx,new AtomicInteger())){check(!read(stream).contains("\"error\""),"real model quotation not quota gate");}
        try(Delayed p=new Delayed(frame("first",null,false),frame("first rest",null,true)+"data: [DONE]\n\n");RequestContext ctx=new RequestContext("test",NetworkPolicy.capture(),5000)){
            CatPawStream stream=CatPawStream.open("synthetic-token","auto",new JSONArray().put(new JSONObject().put("role","user").put("content","test")),null,"m",ctx,p);ctx.onClose(stream::close);ctx.cancel("client cancel");check(p.input.closed.get(),"cancellation closes live upstream");check(p.calls.get()==3,"cancel does not replay");stream.close();
        }
    }
    static void privacy()throws Exception {
        JSONObject cfg=config().put("privacy",new JSONObject().put("enabled",true));
        try(VendorPolicyGatewayHttpTest.Upstream unused=new VendorPolicyGatewayHttpTest.Upstream((r,n)->new VendorPolicyGatewayHttpTest.Reply(500,"application/json","{}"));VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(cfg,unused,false)) {
            final String[] mask={null};g.gateway.setCatPawStreamForTests((path,body,headers,request)->{
                if(path.endsWith("/round")){String prompt=body.getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text");java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{\\{MASK_EMAIL_[^}]+}}").matcher(prompt);check(m.find(),"privacy request masked before network");mask[0]=m.group();return Delayed.reply("{\"code\":0}",request);}
                if(path.endsWith("/event"))return Delayed.reply("{}",request);
                String token=mask[0];String wire=frame("Hello "+token.substring(0,token.length()/2),null,false)+frame("Hello "+token+"!",null,true)+"data: [DONE]\n\n";
                return new CatPawStream.Reply(200,new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),null,request);
            });
            JSONObject body=VendorPolicyGatewayHttpTest.body("catpaw/auto",true);body.getJSONArray("messages").getJSONObject(0).put("content","Hello alice@example.com");VendorPolicyGatewayHttpTest.Result r=VendorPolicyGatewayHttpTest.post(g,"/v1/chat/completions",body);
            check(r.status==200,"masked stream HTTP");check(r.body.contains("alice@example.com"),"split privacy placeholder restored");check(!r.body.contains("MASK_EMAIL"),"privacy placeholders not leaked");check(r.body.contains("[DONE]"),"masked stream terminal");
        }
    }
    /** The producer keeps the HTTP body open after an explicit business-complete frame. */
    static final class HoldOpen extends InputStream {
        final ByteArrayInputStream prefix;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger tailReads = new AtomicInteger();
        HoldOpen(String wire) { prefix = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)); }
        public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 255; }
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (prefix.available() > 0) return prefix.read(b, off, len);
            tailReads.incrementAndGet();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test source intentionally held open"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return -1;
        }
        public void close() { closed.set(true); release.countDown(); }
    }
    static void finishedWithoutDisconnect(String path, boolean withTool, String terminalKind) throws Exception {
        String last;
        if ("metadata".equals(terminalKind)) last = "data: {\"message\":{\"finished\":true}}\n\n";
        else if ("outer".equals(terminalKind)) last = "data: {\"finished\":true,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"HELLO FINAL\"}]}}\n\n";
        else last = frame("HELLO FINAL", null, true);
        String initial = "metadata".equals(terminalKind) ? frame("HELLO FINAL", null, false) : frame("HELLO", null, false);
        HoldOpen source = new HoldOpen(initial + last);
        AtomicInteger sent = new AtomicInteger();
        try (VendorPolicyGatewayHttpTest.Upstream unused = new VendorPolicyGatewayHttpTest.Upstream((r,n) -> new VendorPolicyGatewayHttpTest.Reply(500,"application/json","{}"));
             VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config(), unused, false)) {
            gateway.gateway.setCatPawStreamForTests((route,body,headers,request) -> {
                sent.incrementAndGet();
                if (route.endsWith("/round")) return Delayed.reply("{\"code\":0}", request);
                if (route.endsWith("/event")) return Delayed.reply("{}", request);
                return new CatPawStream.Reply(200, source, null, request);
            });
            gateway.gateway.setCatPawTransportForTests((url,body,headers,timeout,accept) -> { throw new AssertionError("unexpected buffered replay"); });
            boolean anthropic = "/v1/messages".equals(path), responses = "/v1/responses".equals(path);
            JSONObject request = responses ? new JSONObject().put("model","catpaw/auto").put("stream",true).put("input","hi") : VendorPolicyGatewayHttpTest.body("catpaw/auto",true);
            if (anthropic) request.put("max_tokens",100);
            if (withTool) {
                JSONObject tool = new JSONObject().put("name","lookup");
                if (anthropic) tool.put("input_schema",new JSONObject().put("type","object"));
                else if (responses) tool.put("type","function").put("parameters",new JSONObject().put("type","object"));
                else tool = new JSONObject().put("type","function").put("function",tool.put("parameters",new JSONObject().put("type","object")));
                request.put("tools",new JSONArray().put(tool));
            }
            HttpURLConnection client = (HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();
            client.setRequestMethod("POST");client.setDoOutput(true);client.setConnectTimeout(2000);client.setReadTimeout(1800);
            client.setRequestProperty("Content-Type","application/json");client.setRequestProperty("Authorization","Bearer fixture-auth");
            try {
                try(OutputStream body = client.getOutputStream()) { body.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
                check(client.getResponseCode()==200,"finished-with-open-upstream HTTP succeeds");
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
                StringBuilder wire = new StringBuilder();
                try { for(String line;(line=reader.readLine())!=null;) wire.append(line).append('\n'); }
                catch(SocketTimeoutException stuck) {
                    System.out.println("CATPAW_TERMINAL_REPRO path="+path+" marker="+terminalKind+" source still open="+!source.closed.get()+" received="+wire.toString().replace('\n',' '));
                    throw new AssertionError("completed CatPaw message still waits for upstream EOF/DONE",stuck);
                }
                String output = wire.toString(), end = anthropic ? "event: message_stop" : responses ? "event: response.completed" : "data: [DONE]";
                check(output.contains("HELLO") && output.contains("FINAL"),"last business content retained before terminal");
                check(output.indexOf(end)>=0 && output.indexOf(end)==output.lastIndexOf(end),"exactly one client terminal before upstream EOF");
                check(source.closed.get() && source.tailReads.get()==0,"business terminal closes upstream without reading held transport tail");
                check(sent.get()==3,"one round/event/turn; no replay or fallback");
                for(String line:output.split("\n")) if(line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                    JSONObject event=new JSONObject(line.substring(6));
                    check(event.isNull("error"),"finished reply has no non-null error object");
                    JSONObject response=event.optJSONObject("response");
                    if(response!=null)check(response.isNull("error"),"successful Responses object may carry only error:null");
                }
                check(!output.contains("event: error")&&!output.contains("event: response.failed"),"finished reply emits no failure event");
                System.out.println("FINISHED_OPEN_PASS path="+path+" marker="+terminalKind+" tools="+withTool+" terminal=1 upstream_tail_reads=0 posts="+sent.get());
            } finally { client.disconnect(); source.close(); }
        }
    }
    static void terminalSafety() throws Exception {
        for (int variant = 0; variant < 6; variant++) {
            JSONObject message = new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","LOOKS COMPLETE")));
            JSONObject frame = new JSONObject().put("message",message);
            if (variant==0) message.put("finished",false);
            else if (variant==1) message.put("finished","true");
            else if (variant==2) message.put("finished",1);
            else if (variant==3) { message.put("finished",true); frame.put("finished",false); }
            else if (variant==4) frame=new JSONObject().put("finished",true); // Heartbeat metadata is not an assistant message.
            else message.put("type","user").put("finished",true); // Echoed user messages cannot finish an assistant reply.
            HoldOpen source=new HoldOpen(CatPawIncrementalHttpTest.frame("LOOKS COMPLETE",null,false)+"data: "+frame+"\n\n");
            AtomicInteger sent=new AtomicInteger();
            ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"catpaw-nonterminal-reader");t.setDaemon(true);return t;});
            try(RequestContext context=new RequestContext("test",NetworkPolicy.capture(),5000)) {
                CatPawStream stream=CatPawStream.open("synthetic-token","auto",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")),null,"m",context,(path,body,headers,request)->{
                    sent.incrementAndGet();return path.endsWith("/turn") ? new CatPawStream.Reply(200,source,null,request) : Delayed.reply(path.endsWith("/round")?"{\"code\":0}":"{}",request);
                });
                Future<String> result=executor.submit(()->read(stream));
                try { result.get(200,TimeUnit.MILLISECONDS);throw new AssertionError("nonterminal flag prematurely completed reply"); }
                catch(TimeoutException expected) { check(!source.closed.get(),"nonterminal flags do not close upstream"); }
                source.release.countDown();String output=result.get(2,TimeUnit.SECONDS);
                check(output.contains("\"error\"") && !output.contains("\"finish_reason\":\"stop\""),"nonterminal EOF remains an error, never success");
                check(sent.get()==3,"nonterminal error never replays upstream");stream.close();
            } finally {source.close();executor.shutdownNow();}
        }
        for(String field:new String[]{"text","reasoningContent"}) {
            String escaped=(field.equals("text")?"start":"think")+(char)92+"uD83D";
            String part=field.equals("text")?"\"text\":\""+escaped+"\"":"\"text\":\"start\",\"reasoningContent\":\""+escaped+"\"";
            String broken="data: {\"message\":{\"content\":[{\"type\":\"text\","+part+"}],\"finished\":true}}\n\n";
            try(RequestContext context=new RequestContext("test",NetworkPolicy.capture(),5000);CatPawStream stream=direct(frame("start","think",false)+broken,context,new AtomicInteger())) {
                String output=read(stream);check(output.contains("\"error\"")&&!output.contains("\"finish_reason\":\"stop\""),"incomplete final Unicode is not accepted as successful terminal");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})
            for(String kind:new String[]{"message","metadata","outer"})
                for(boolean tools:new boolean[]{false,true}) finishedWithoutDisconnect(path,tools,kind);
        terminalSafety();delayedGateway(false,false);delayedGateway(true,false);delayedGateway(false,true);delayedGateway(true,true);edges();privacy();
        System.out.println("CatPawIncrementalHttpTest PASS: "+checks+" checks (incremental content and explicit terminal without upstream disconnect)");
    }
}
