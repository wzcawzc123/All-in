package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Regression contracts for the defects FIRST reproduced using the old release on Android. */
public final class Audit52RegressionTest {
    static int checks;
    static void check(boolean value,String what){checks++;if(!value)throw new AssertionError(what);}
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static Object get(Class<?> c,String name)throws Exception{return field(c,name).get(null);}
    static UsageLedger.Row row(int i){UsageLedger.Row r=new UsageLedger.Row();r.requestId="audit-"+i;r.t=System.currentTimeMillis()-100;r.prov="synthetic";r.model="fixture";r.status=200;r.usageSource="known";r.in=3;r.out=2;return r;}
    static File area(Path root,String name)throws Exception{return Files.createDirectory(root.resolve(name)).toFile();}
    static String consume(InputStream in)throws Exception{try(InputStream source=in){return ResponsesGatewayOfflineTest.read(source);}}
    static void sse(Path dir)throws Exception{
        UsageLedger.init(area(dir,"sse"));List<UsageLedger.Row> rows=new ArrayList<>();UsageLedger.setHooks(rows::add);
        String finish=GatewayStreamReadOfflineTest.done();byte[] bytes=finish.getBytes(StandardCharsets.UTF_8);
        InputStream bad=new InputStream(){int p;public int read()throws IOException{if(p==bytes.length)throw new IOException("synthetic bad tail");return bytes[p++]&255;}public int read(byte[] b,int off,int len)throws IOException{if(p==bytes.length)throw new IOException("synthetic bad tail");int n=Math.min(len,bytes.length-p);System.arraycopy(bytes,p,b,off,n);p+=n;return n;}};
        UsageLedger.Row r=row(1);String wire=consume(GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),bad,new GatewayStreamReadOfflineTest.Connection(),null,r));
        check(wire.contains("Upstream stream read failed"),"late read failure delivered");check(r.failed()&&r.in==3&&r.out==2&&rows.size()==1,"late read failure records once without losing usage");
        for(String newline:new String[]{"\n","\r\n"}){
            String source=": heartbeat"+newline+newline+"data: {\"choices\":"+newline+"data: [{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"+newline+newline+"data: [DONE]"+newline+newline;
            wire=consume(GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),new GatewayStreamReadOfflineTest.Connection(),null,null));
            check(wire.contains("[DONE]")&&!wire.contains("error"),"valid multiline, heartbeat, framed terminal and newline style");
            for(String line:wire.split("\n"))if(line.startsWith("data: {")&&!line.contains("error"))check(new JSONObject(line.substring(6)).getJSONArray("choices").getJSONObject(0).optString("finish_reason").equals("stop"),"joined multiline JSON emitted correctly");
        }
        for(boolean failure:new boolean[]{false,true}){
            rows.clear();r=row(10+(failure?1:0));InputStream data=GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),new ByteArrayInputStream(finish.getBytes(StandardCharsets.UTF_8)),new GatewayStreamReadOfflineTest.Connection(),null,r);
            AllInTrackedResponse tracked=new AllInTrackedResponse(NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,"text/event-stream",data),()->{});tracked.setRequestMethod(NanoHTTPD.Method.GET);
            Method send=AllInTrackedResponse.class.getDeclaredMethod("send",OutputStream.class);send.setAccessible(true);
            send.invoke(tracked,new ByteArrayOutputStream(){public void flush()throws IOException{if(failure)throw new IOException("synthetic downstream flush failure");}});tracked.close();
            check(rows.size()==1&&r.failed()==failure&&r.in+r.out==5,"ordinary stream final downstream result and usage match");
        }
        final long[] consumed={0};InputStream giant=new InputStream(){public int read(){if(consumed[0]++>=64L*1024*1024)return -1;return 'x';}public int read(byte[] b,int o,int n){int m=(int)Math.min(n,64L*1024*1024-consumed[0]);if(m<=0)return -1;Arrays.fill(b,o,o+m,(byte)'x');consumed[0]+=m;return m;}};
        wire=consume(GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),giant,new GatewayStreamReadOfflineTest.Connection(),null,null));
        check(wire.contains("framing size or memory limit"),"oversized no-newline SSE gives deterministic size error");check(consumed[0]<1024*1024,"bounded reader rejects before reading entire giant line");
        String line="data: "+String.join("",Collections.nCopies(1024,"x"))+"\n";
        List<InputStream> frames=new ArrayList<>();for(int i=0;i<600;i++)frames.add(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
        InputStream event=new java.io.SequenceInputStream(Collections.enumeration(frames));
        wire=consume(GatewayStreamReadOfflineTest.stream(ResponsesGatewayOfflineTest.gateway(1),event,new GatewayStreamReadOfflineTest.Connection(),null,null));
        check(wire.contains("framing size or memory limit"),"multi-data accumulation has an event cap");check(BoundedSseReader.activeBytes()==0,"all framing reservations released after errors and close");
    }
    static void ledger(Path dir)throws Exception{
        File healthy=area(dir,"healthy");UsageLedger.init(healthy);
        for(int i=0;i<6000;i++)UsageLedger.record(row(i));
        int retained=0;for(Object rows:((Map<?,?>)get(UsageLedger.class,"DAYS")).values())retained+=((List<?>)rows).size();
        check(retained<=UsageLedger.MAX_RETAINED_ROWS&&UsageLedger.retainedBytes<=UsageLedger.MAX_RETAINED_BYTES,"healthy retained detail rows AND bytes bounded");
        check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==6000,"detail eviction never removes healthy persisted totals");
        File bad=area(dir,"failed"),block=new File(bad,"usage");Files.write(block.toPath(),new byte[]{1});UsageLedger.init(bad);
        for(int i=0;i<2000;i++)UsageLedger.record(row(i));
        check(((List<?>)get(UsageLedger.class,"unpersisted")).size()<=UsageLedger.MAX_PENDING_ROWS&&UsageLedger.pendingBytes<=UsageLedger.MAX_PENDING_BYTES,"failed pending rows AND bytes bounded");
        check(UsageLedger.droppedPendingRows==2000-UsageLedger.MAX_PENDING_ROWS,"dropped detail count exact");
        check(UsageLedger.heatmap().getJSONObject("totals").getLong("requests")==2000,"failed detailed overflow retains hourly aggregate");
        check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==2000,"summary agrees with independently retained lost aggregate");
        block.delete();block.mkdir();UsageLedger.record(row(2001));
        check(((List<?>)get(UsageLedger.class,"unpersisted")).isEmpty(),"confirmed recovery drains pending queue");
        check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==2001,"recovered records not charged twice");
        UsageLedger.init(bad);check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==2001,"persisted lost aggregates survive restart without duplicate rows");
        check(UsageLedger.droppedPendingRows==1488,"lost-detail diagnostic survives restart");
        File rotation=area(dir,"rotation");UsageLedger.init(rotation);long limit=UsageLedger.maxFileBytes;UsageLedger.maxFileBytes=800;
        try{for(int i=0;i<20;i++)UsageLedger.record(row(i));File[] shards=new File(rotation,"usage").listFiles((f,n)->n.startsWith("ledger-")&&n.endsWith(".jsonl"));check(shards.length>1,"threshold creates actual new shard files");for(File f:shards)check(f.length()<=1600,"shard growth bounded by threshold plus one row");check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==20,"all shard records exactly once");UsageLedger.init(rotation);check(UsageLedger.summary(1,"provider").getJSONObject("totals").getLong("requests")==20,"shards readable after restart");}finally{UsageLedger.maxFileBytes=limit;}
        UsageLedger.Row old=row(99);old.t=System.currentTimeMillis()-91L*86400000;UsageLedger.record(old);check(UsageLedger.summary(95,"provider").getJSONObject("totals").getLong("requests")==21,"older than 90-day detail cache remains queryable from disk");
    }
    static void checkin()throws Exception{
        for(String mutation:new String[]{"disable","delete","replace","epoch"}){
            final JSONObject[] cfg={new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id","loomy").put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("enabled",true).put("token","SYNTHETIC")))))};
            final Object lock=new Object();final AtomicLong epoch=new AtomicLong(1);CountDownLatch persisted=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicBoolean paused=new AtomicBoolean();AtomicInteger sends=new AtomicInteger();
            CheckinCenter.Host host=new CheckinCenter.Host(){public Object lock(){return lock;}public JSONObject config(){return cfg[0];}public boolean save(JSONObject next){cfg[0]=next;persisted.countDown();return true;}public long accountEpoch(String p,String id){return epoch.get();}public boolean retired(){if(persisted.getCount()==0&&!Thread.holdsLock(lock)&&paused.compareAndSet(false,true))try{resume.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return false;}};
            CheckinCenter c=new CheckinCenter(host,(p,a)->{sends.incrementAndGet();return new JSONObject().put("outcome","available");});AtomicReference<Throwable> error=new AtomicReference<>();
            Thread worker=new Thread(()->{try{c.runOne("loomy","a",false);}catch(Throwable e){error.set(e);}});worker.start();check(persisted.await(2,TimeUnit.SECONDS),"intent written before send");
            synchronized(lock){JSONObject p=cfg[0].getJSONArray("providers").getJSONObject(0),a=p.getJSONArray("accounts").getJSONObject(0);if(mutation.equals("disable"))a.put("enabled",false);else if(mutation.equals("delete"))p.put("accounts",new JSONArray());else if(mutation.equals("replace"))a.put("token","SYNTHETIC_REPLACEMENT");else epoch.incrementAndGet();}
            resume.countDown();worker.join(3000);c.close();check(!worker.isAlive()&&sends.get()==0,"pre-send invalidation prevents actual transport: "+mutation);check(cfg[0].getJSONObject("checkin").getJSONObject("records").length()==1,"daily fence not erased on invalidation");
        }
    }
    static void calibration()throws Exception{
        Method key=GatewayServer.class.getDeclaredMethod("calibrationKey",JSONObject.class);key.setAccessible(true);
        JSONObject p=new JSONObject().put("id","zcode").put("enabled",true).put("keys",new JSONArray().put(new JSONObject().put("key","SYNTHETIC").put("off",false)));
        check("SYNTHETIC".equals(key.invoke(null,p)),"object key yields raw secret not object JSON");p.getJSONArray("keys").getJSONObject(0).put("off",true);check("".equals(key.invoke(null,p)),"off object key skipped");
        p.put("keys",new JSONArray().put("SYNTHETIC")).put("accounts",new JSONArray().put(new JSONObject().put("id","a").put("token","SYNTHETIC").put("type","api").put("enabled",false)));check("".equals(key.invoke(null,p)),"disabled account mirror skipped");p.put("accounts",new JSONArray()).put("enabled",false);check("".equals(key.invoke(null,p)),"disabled provider skipped");
    }
    static void dns()throws Exception{
        BoundedDns.Resolver old=BoundedDns.resolver;
        try{BoundedDns.resolver=h->{try{Thread.sleep(350);}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new UnknownHostException("synthetic");};long at=System.nanoTime();boolean refused=false;try{NetworkPolicy.parse(new JSONObject().put("mode","direct")).connectTunnel("audit.invalid",443,50,50);}catch(SocketTimeoutException expected){refused=true;}check(refused&&(System.nanoTime()-at)<TimeUnit.MILLISECONDS.toNanos(250),"DNS bounded by connect budget");}
        finally{BoundedDns.resolver=old;}
        for(String ip:new String[]{"192.0.2.7","2001:db8::7","127.0.0.1"}){
            final InetAddress actual=InetAddress.getByAddress(InetAddress.getByName(ip).getAddress());Socket original=new Socket(){public InetAddress getInetAddress(){return actual;}};
            NumericPeerSocket view=new NumericPeerSocket(original);check(Arrays.equals(actual.getAddress(),view.getInetAddress().getAddress())&&actual.isLoopbackAddress()==view.getInetAddress().isLoopbackAddress(),"numeric peer identity and privileges: "+ip);check(view.getInetAddress().getHostName().equals(view.getInetAddress().getHostAddress()),"numeric peer name no PTR");view.close();check(original.isClosed(),"numeric socket closes real delegate");
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("audit52-regression-");try{sse(root);ledger(root);checkin();calibration();dns();System.out.println("Audit52RegressionTest PASS checks="+checks+" maxHeap="+Runtime.getRuntime().maxMemory());}
        finally{UsageLedger.setHooks(null);GatewayStreamReadOfflineTest.closeLedger();try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
}
