package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic-only regression witnesses for the 53 audit. No real account or remote traffic. */
public final class Audit53RegressionTest {
    static int checks;
    static final List<String> failures=new ArrayList<>();
    interface Case { void run() throws Exception; }
    static void check(boolean ok,String label){checks++;if(!ok){failures.add(label);System.out.println("FAIL "+label);}}
    static void run(String label,Case c){try{c.run();}catch(Throwable e){failures.add(label+": "+e.getClass().getSimpleName());System.out.println("FAIL "+label+": "+e.getClass().getSimpleName());}}
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {
        Method m=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(o instanceof Class?null:o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    static JSONObject cfg(GatewayServer g)throws Exception{return ResponsesGatewayOfflineTest.config(g);}
    static GatewayServer gateway()throws Exception{
        GatewayServer g=RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","SYNTHETIC_INFERENCE"))
                .put("privacy",new JSONObject().put("enabled",false)).put("providers",new JSONArray()));
        field(GatewayServer.class,"codexFlows").set(g,new java.util.concurrent.ConcurrentHashMap<String,CodexAuth.Flow>());
        field(GatewayServer.class,"antigravityFlows").set(g,new java.util.concurrent.ConcurrentHashMap<String,AntigravityAuth.Flow>());
        return g;
    }
    static NanoHTTPD.IHTTPSession session(String uri,String method,String body,String remote,Map<String,List<String>> params)throws Exception{
        byte[] data=body.getBytes(StandardCharsets.UTF_8);InputStream in=new ByteArrayInputStream(data);
        Map<String,String> h=new HashMap<>();h.put("host","127.0.0.1:3425");h.put("content-type","application/json");h.put("content-length",Integer.toString(data.length));h.put("x-all-in-management",GatewayManagementCredential.get());h.put("authorization","Bearer SYNTHETIC_INFERENCE");
        return (NanoHTTPD.IHTTPSession)java.lang.reflect.Proxy.newProxyInstance(Audit53RegressionTest.class.getClassLoader(),new Class[]{NanoHTTPD.IHTTPSession.class},(p,m,a)->{
            switch(m.getName()){case "getUri":return uri;case "getMethod":return NanoHTTPD.Method.valueOf(method);case "getHeaders":return h;case "getInputStream":return in;case "getParameters":return params;case "getParms":return Collections.emptyMap();case "getRemoteIpAddress":return remote;default:return null;}
        });
    }
    static NanoHTTPD.Response request(GatewayServer g,String path,String method,String body)throws Exception{return g.serve(session(path,method,body,"127.0.0.1",Collections.emptyMap()));}
    static String body(NanoHTTPD.Response r)throws Exception{try{return ResponsesGatewayOfflineTest.read(r.getData());}finally{r.close();}}
    static final AtomicInteger oauthSends=new AtomicInteger();
    static final String ATTACK="<script>window.__audit53=1</script>&\"'";
    static void noRemoteTransport(){
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL u)throws IOException{return openConnection(u,java.net.Proxy.NO_PROXY);}
            protected URLConnection openConnection(URL u,java.net.Proxy p)throws IOException{
                if(!u.getHost().equals("auth.openai.com")||!u.getPath().equals("/oauth/token"))throw new IOException("Synthetic HTTPS denied");
                return new HttpURLConnection(u){
                    public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
                    public OutputStream getOutputStream(){oauthSends.incrementAndGet();return new ByteArrayOutputStream();}
                    public int getResponseCode(){return 400;}
                    public InputStream getErrorStream(){return new ByteArrayInputStream(ATTACK.getBytes(StandardCharsets.UTF_8));}
                };
            }
        });
    }
    @SuppressWarnings("unchecked") static Map<String,CodexAuth.Flow> codex(GatewayServer g)throws Exception{return (Map<String,CodexAuth.Flow>)field(GatewayServer.class,"codexFlows").get(g);}
    @SuppressWarnings("unchecked") static Map<String,AntigravityAuth.Flow> ag(GatewayServer g)throws Exception{return (Map<String,AntigravityAuth.Flow>)field(GatewayServer.class,"antigravityFlows").get(g);}
    static void flows(GatewayServer g)throws Exception{
        for(int i=1;i<=2;i++){
            CodexAuth.Flow f=new CodexAuth.Flow();f.flowId="c"+i;f.state="cs"+i;f.verifier="cv"+i;f.createdAt=System.currentTimeMillis();codex(g).put(f.flowId,f);
            AntigravityAuth.Flow a=new AntigravityAuth.Flow();a.flowId="a"+i;a.state="as"+i;a.verifier="av"+i;a.redirect="http://127.0.0.1/callback";ag(g).put(a.flowId,a);
        }
    }
    static void security()throws Exception{
        GatewayServer g=gateway();flows(g);
        Map<String,List<String>> p=new HashMap<>();p.put("code",Collections.singletonList("SYNTHETIC_CODE"));p.put("state",Collections.singletonList("cs1"));
        NanoHTTPD.Response r=g.serve(session("/auth/callback","GET","","127.0.0.1",p));String html=body(r);
        check(html.contains("&lt;script&gt;")&&!html.contains("<script>"),"S1 actual Codex callback escapes upstream error HTML");
        int before=oauthSends.get();r=g.serve(session("/auth/callback","GET","","192.0.2.1",p));
        check(r.getStatus().getRequestStatus()==403,"S1 non-loopback callback denied");body(r);
        check(oauthSends.get()==before,"S1 LAN callback never exchanges code");
        p.put("state",Collections.singletonList("wrong"));before=oauthSends.get();body(g.serve(session("/auth/callback","GET","","127.0.0.1",p)));
        check(oauthSends.get()==before,"S1 unknown state never exchanges code");
        for(String provider:new String[]{"codex","antigravity"}){
            String prefix=provider.equals("codex")?"c":"a";
            for(String mode:new String[]{"missing","mismatch","absent","expired","done"}){
                g=gateway();flows(g);
                if(provider.equals("antigravity"))NetworkPolicy.block();else NetworkPolicy.configure(null);
                String id=mode.equals("missing")?"unknown":mode.equals("absent")?"":prefix+"1";
                String code="SYNTHETIC_CODE";
                if(mode.equals("mismatch"))code="http://127.0.0.1/callback?code=SYNTHETIC_CODE&state="+prefix+"s2";
                if(mode.equals("expired")){codex(g).get("c1").createdAt=1;ag(g).get("a1").createdAt=1;}
                if(mode.equals("done")){codex(g).get("c1").status="done";ag(g).get("a1").status="done";}
                before=oauthSends.get();r=request(g,"/api/auth/"+provider+"/submit-code","POST",new JSONObject().put("flowId",id).put("code",code).toString());
                check(r.getStatus().getRequestStatus()==400,"M5 "+provider+" rejects "+mode+" flow");body(r);
                check(oauthSends.get()==before,"M5 "+provider+" no exchange for "+mode);
                if(!mode.equals("done"))check("pending".equals(ag(g).get("a2").status)&&"pending".equals(codex(g).get("c2").status),"M5 unrelated flow untouched "+provider+" "+mode);
            }
        }
        NetworkPolicy.block();
        try{r=g.serve(session("/api/antigravity/flap","GET","","127.0.0.1",Collections.singletonMap("host",Collections.singletonList("10.0.0.1"))));check(r.getStatus().getRequestStatus()==400,"M6 private IP diagnostic rejected");body(r);}
        finally{NetworkPolicy.configure(null);}
    }
    static void routingAndState()throws Exception{
        for(String text:new String[]{"超出配额","账户超出上限，余额不足","请求频率超出限流阈值"}){
            boolean over=(Boolean)call(GatewayServer.class,"overflowed",new Class[]{int.class,String.class},400,text);
            boolean retry=(Boolean)call(GatewayServer.class,"retryable",new Class[]{int.class,String.class},400,text);
            check(!over,"M4 quota/rate is not context overflow: "+text);check(retry,"M4 rejection remains eligible: "+text);
        }
        for(String text:new String[]{"上下文超长","超出上下文窗口","context_length_exceeded","prompt is too long"})
            check((Boolean)call(GatewayServer.class,"overflowed",new Class[]{int.class,String.class},400,text),"M4 true overflow still refused: "+text);
        GatewayServer g=gateway();
        NanoHTTPD.Response r=request(g,"/api/auth/no-such-provider/remove","POST","{\"id\":\"missing\"}");JSONObject removed=new JSONObject(body(r));
        check(removed.has("removed")&&!removed.optBoolean("removed",true),"M7 idempotent remove reports removed:false");
        for(String path:new String[]{"/api/usagebogus","/api/usage/no-such-view"}){r=request(g,path,"GET","");check(r.getStatus().getRequestStatus()==404,"L16 no accidental usage prefix: "+path);body(r);}
        @SuppressWarnings("unchecked") ThreadLocal<String> origin=(ThreadLocal<String>)field(GatewayServer.class,"CURRENT_ORIGIN").get(null);
        origin.set("http://previous.invalid");field(GatewayServer.class,"backupRetired").set(g,true);
        r=request(g,"/api/config","GET","");check(r.getHeader("Access-Control-Allow-Origin")==null,"L11 early error never reflects stale Origin");body(r);check(origin.get()==null,"L11 caller thread Origin removed");
        g=gateway();field(GatewayServer.class,"watchdogState").set(g,new JSONObject().put("lastBeatAt",1).put("unfreezeCount",2));
        final GatewayServer server=g;CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread t=new Thread(()->{started.countDown();try{call(server,"buildWatchdogStatus",new Class[0]);}catch(Throwable e){error.set(e);}finally{done.countDown();}});
        synchronized(g){t.start();started.await(1,TimeUnit.SECONDS);check(!done.await(80,TimeUnit.MILLISECONDS),"L12 watchdog reader honors state write lock");}
        t.join(2000);check(!t.isAlive()&&error.get()==null,"L12 snapshot completes after unlock");
        Class<?> ac=Class.forName("com.gaeul.allin.GatewayServer$Attempt"),tc=Class.forName("com.gaeul.allin.GatewayServer$RouteTarget");
        Constructor<?> ctor=ac.getDeclaredConstructor();ctor.setAccessible(true);Object attempt=ctor.newInstance();ctor=tc.getDeclaredConstructor();ctor.setAccessible(true);Object target=ctor.newInstance();
        field(ac,"status").set(attempt,429);field(ac,"body").set(attempt,null);field(ac,"response").set(attempt,NanoHTTPD.newFixedLengthResponse("synthetic"));field(tc,"providerId").set(target,"trae-cn");
        boolean safe=true;try{call(g,"visibleCooldown",new Class[]{ac,tc,ContextGuard.Result.class},attempt,target,null);}catch(NullPointerException e){safe=false;}
        check(safe,"L24 empty Trae error body does not throw");
    }
    static void framing()throws Exception{
        long initial=BoundedSseReader.activeBytes();check(initial==0,"L1 starts with zero framing reservations");
        String full=String.join("",Collections.nCopies(BoundedSseReader.MAX_LINE_CHARS,"x"))+"\n";
        List<BoundedSseReader> readers=new ArrayList<>();
        try{
            for(int i=0;i<8;i++){BoundedSseReader br=new BoundedSseReader(new StringReader(full));readers.add(br);check(br.readLine().length()==BoundedSseReader.MAX_LINE_CHARS,"L1 max legal line "+i);}
            check(BoundedSseReader.activeBytes()==BoundedSseReader.MAX_ACTIVE_BYTES,"L1 all active budget accounted");
            BoundedSseReader extra=new BoundedSseReader(new StringReader("x\n"));boolean rejected=false;try{extra.readLine();}catch(IOException e){rejected=true;}finally{extra.close();}
            check(rejected&&BoundedSseReader.activeBytes()==BoundedSseReader.MAX_ACTIVE_BYTES,"L1 oversubscription fails closed without leak");
        }finally{for(BoundedSseReader br:readers){br.close();br.close();}}
        check(BoundedSseReader.activeBytes()==0,"L1 repeated close releases exactly once");
        try(BoundedSseReader br=new BoundedSseReader(new StringReader("data: a\r\ndata: b\r\n\r\nlast\n"))){check(br.readEvent().data.equals("a\nb"),"L1 multiline CRLF event unchanged");check(br.readLine().equals("last"),"L1 event/line mixed read");check(br.readEvent()==null&&BoundedSseReader.activeBytes()==0,"L1 mixed EOF releases reservations");}
        String data = "data: [DONE]";
        for (String ending : new String[]{"", String.valueOf((char)10), String.valueOf((char)13)}) {
            try (BoundedSseReader br = new BoundedSseReader(new StringReader(data + ending))) {
                try { br.readEvent(); throw new AssertionError("truncated event was dispatched"); }
                catch (EOFException expected) { check(BoundedSseReader.activeBytes() == 0, "truncation releases reservations"); }
            }
        }
        try(BoundedSseReader br=new BoundedSseReader(new Reader(){int n;public int read(char[] b,int o,int l)throws IOException{if(n++>0)throw new IOException("synthetic read failure");b[o]='x';return 1;}public void close(){}})){
            boolean failed=false;try{br.readLine();}catch(IOException e){failed=true;}check(failed&&BoundedSseReader.activeBytes()==0,"L1 source failure releases reservation");
        }
    }
    static void dns()throws Exception{
        BoundedDns.Resolver old=BoundedDns.resolver;CountDownLatch entered=new CountDownLatch(4),release=new CountDownLatch(1),exited=new CountDownLatch(4);
        ExecutorService callers=Executors.newFixedThreadPool(4);List<Future<Boolean>> results=new ArrayList<>();
        try{
            BoundedDns.resolver=h->{entered.countDown();try{while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}}finally{exited.countDown();}return new InetAddress[]{InetAddress.getLoopbackAddress()};};
            for(int i=0;i<4;i++)results.add(callers.submit(()->{try{BoundedDns.resolve("synthetic.invalid",System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(180));return false;}catch(SocketTimeoutException e){return true;}}));
            check(entered.await(2,TimeUnit.SECONDS),"M1 all four uninterruptible resolvers actually entered");
            for(Future<Boolean> r:results)check(r.get(2,TimeUnit.SECONDS),"M1 caller deadline enforced despite ignored interrupt");
            check(BoundedDns.active()==4,"M1 hung resolvers keep four original slots");
            boolean denied=false;try{BoundedDns.resolve("extra.invalid",System.nanoTime()+TimeUnit.SECONDS.toNanos(1));}catch(IOException e){denied=e.getMessage().contains("concurrency");}
            check(denied&&BoundedDns.active()==4,"M1 saturation fails closed without replacement threads");
            check(BoundedDns.resolve("127.0.0.1",System.nanoTime()+TimeUnit.SECONDS.toNanos(1))[0].isLoopbackAddress(),"M1 numeric address bypasses saturated DNS");
            try{JSONObject d=(JSONObject)call(BoundedDns.class,"diagnostics",new Class[0]);check(d.optInt("active")==4&&d.optBoolean("saturated")&&d.optLong("timeouts")>=4&&d.optLong("rejections")>=1,"M1 bounded saturation diagnostics expose actual state");}catch(NoSuchMethodException e){check(false,"M1 diagnostics missing");}
        }finally{release.countDown();exited.await(2,TimeUnit.SECONDS);callers.shutdownNow();callers.awaitTermination(2,TimeUnit.SECONDS);BoundedDns.resolver=old;}
        long wait=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(BoundedDns.active()!=0&&System.nanoTime()<wait)java.util.concurrent.locks.LockSupport.parkNanos(1000000);
        check(BoundedDns.active()==0,"M1 slots recover AFTER platform resolver returns");
        AtomicInteger calls=new AtomicInteger();BoundedDns.resolver=h->{calls.incrementAndGet();return new InetAddress[]{InetAddress.getLoopbackAddress()};};
        try{boolean timed=false;try{BoundedDns.resolve("expired.invalid",System.nanoTime()-1);}catch(SocketTimeoutException e){timed=true;}check(timed,"M1 expired request rejected");
            InetAddress[] recovered=BoundedDns.resolve("recovered.invalid",System.nanoTime()+TimeUnit.SECONDS.toNanos(1));check(recovered.length==1,"M1 post-release valid lookup succeeds");
        }finally{BoundedDns.resolver=old;}
    }
    public static void main(String[] args)throws Exception{
        TestPrivacyScope.initialize();noRemoteTransport();Path root=Files.createTempDirectory("audit53-");UsageLedger.init(root.toFile());
        try{run("security",Audit53RegressionTest::security);run("routing/state",Audit53RegressionTest::routingAndState);run("framing",Audit53RegressionTest::framing);run("dns",Audit53RegressionTest::dns);
            System.out.println("Audit53RegressionTest checks="+checks+" failures="+failures.size());if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        }finally{NetworkPolicy.configure(null);GatewayStreamReadOfflineTest.closeLedger();try(java.util.stream.Stream<Path> s=Files.walk(root)){for(Path p:(Iterable<Path>)s.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
}
