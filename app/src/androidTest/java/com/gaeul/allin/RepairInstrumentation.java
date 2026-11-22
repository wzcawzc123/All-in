package com.gaeul.allin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs on Android's actual class loader and socket stack, without accounts or vendor traffic. */
public final class RepairInstrumentation extends Instrumentation {
    private int checks;
    private String etaInputText;
    private boolean catPawTerminalOnly;
    private boolean heldCancellationOnly;
    private boolean audit52, qoderNamesOnly, qoderCheckinOnly, codexTlsOnly, panelRefreshOnly, gatewayLifecycleOnly, loginLifecycleOnly, themeStartupOnly;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);themeStartupOnly=arguments!=null&&"true".equals(arguments.getString("themeStartupOnly"));etaInputText=arguments==null?null:arguments.getString("etaInputText");catPawTerminalOnly=arguments!=null&&"true".equals(arguments.getString("catPawTerminalOnly"));heldCancellationOnly=arguments!=null&&"true".equals(arguments.getString("heldCancellationOnly"));loginLifecycleOnly=arguments!=null&&"true".equals(arguments.getString("loginLifecycleOnly"));gatewayLifecycleOnly=arguments!=null&&"true".equals(arguments.getString("gatewayLifecycleOnly"));panelRefreshOnly=arguments!=null&&"true".equals(arguments.getString("panelRefreshOnly"));codexTlsOnly=arguments!=null&&"true".equals(arguments.getString("codexTlsOnly"));qoderCheckinOnly=arguments!=null&&"true".equals(arguments.getString("qoderCheckin"));audit52=arguments!=null&&arguments.getString("audit52","").equals("true");qoderNamesOnly=arguments!=null&&arguments.getString("qoderNames","").equals("true");start();}
    private void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private Object call(String type,String method,Class<?>[] types,Object... args)throws Exception {
        Method m=Class.forName("com.gaeul.allin."+type).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private void accountRoutingRuntime()throws Exception {
        long now=System.currentTimeMillis();AccountRoutingPolicy policy=new AccountRoutingPolicy();
        java.util.List<AccountRoutingPolicy.Seat> seats=new java.util.ArrayList<>();java.util.List<RecentUsageScheduler.Lease> leases=new java.util.ArrayList<>();java.util.Set<String> picked=new java.util.HashSet<>();
        for(int i=0;i<4;i++)seats.add(new AccountRoutingPolicy.Seat("android/account/"+i,"fixture-"+i));
        for(int i=0;i<4;i++){RecentUsageScheduler.Lease lease=policy.select("usage",seats,"m",now);leases.add(lease);picked.add(lease.target);}
        check(picked.size()==4,"Android concurrent reservations spread over four accounts");for(RecentUsageScheduler.Lease lease:leases)lease.cancel();
        JSONObject window=new JSONObject().put("name","Credits").put("total",100).put("remaining",80).put("remainingPercent",80).put("runsOut",20).put("runsOutAt",now+3600000);
        JSONObject allowance=new JSONObject().put("unit","credits").put("windows",new JSONArray().put(window));policy.observe(seats.get(2),allowance,now);
        RecentUsageScheduler.Lease urgent=policy.select("pace",seats,"m",now);check(urgent.target.equals(seats.get(2).ref),"Android expiry pressure picks actual expiring credits");urgent.cancel();
        check(policy.basis(seats,"m",now+AccountRoutingPolicy.FRESH_MS+1,"pace").equals("recent_tokens"),"Android stale expiry falls back");
        check(AccountRoutingPolicy.time("2026-11-17 12:00:00")==AccountRoutingPolicy.time("2026-11-17T04:00:00Z"),"Android UTC+8 parser");
        check(AccountRoutingPolicy.time("2026-02-31T00:00:00Z")==0,"Android invalid date rejected");
        JSONObject summary=new JSONObject().put("Packages",new JSONArray().put(new JSONObject().put("PackageCode","fixture-gift")));
        JSONObject merged=WorkBuddyAllowance.withExpiry(allowance,summary,new JSONArray().put(new JSONObject().put("PackageCode","fixture-gift").put("CycleCapacityRemainPrecise",20).put("DeductionEndTime",now+3600000)),now);
        check(merged.getJSONArray("windows").getJSONObject(0).getDouble("runsOut")==20,"Android expiring portion not the full balance");
    }
    private void jsonRuntime()throws Exception {
        check(!JSONObject.class.equals(Class.forName("org.json.JSONObject")),"App JSON must not be Android framework JSON");
        JSONObject a=new JSONObject("{\"a\":[{\"b\":null},2],\"c\":true}"),b=new JSONObject("{\"c\":true,\"a\":[{\"b\":null},2]}");
        check((Boolean)call("ConfigurationView","equivalent",new Class[]{Object.class,Object.class},a,b),"Nested JSON equivalence on Android");
        check(!(Boolean)call("ConfigurationView","equivalent",new Class[]{Object.class,Object.class},a,new JSONObject("{\"a\":[2,{\"b\":null}],\"c\":true}")),"Array order remains significant");
        JSONObject branch=new JSONObject().put("type","object").put("properties",new JSONObject().put("字段",new JSONObject().put("type","string")));
        JSONObject schema=new JSONObject().put("anyOf",new JSONArray().put(branch).put(new JSONObject(branch.toString())));
        JSONObject body=new JSONObject().put("messages",new JSONArray()).put("tools",new JSONArray().put(new JSONObject().put("type","function")
                .put("function",new JSONObject().put("name","read").put("parameters",schema))));
        JSONObject fitted=WorkBuddyChatPolicy.prepare(body,null).body().getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters");
        check("string".equals(fitted.getJSONObject("properties").getJSONObject("字段").getString("type")),"Duplicate schema properties merge using private JSON similar");
        String sse="event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[],\"usage\":{\"input_tokens\":1}}}\n\n"
                +"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"vendor_extra\":{\"ok\":true}},\"usage\":{\"output_tokens\":3}}\n\n"
                +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        JSONObject aggregate=(JSONObject)call("WorkBuddyNativeAggregator","aggregate",new Class[]{InputStream.class},new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        check(aggregate.getJSONObject("usage").getInt("input_tokens")==1 && aggregate.getJSONObject("usage").getInt("output_tokens")==3,"Native SSE usage aggregates without framework linkage errors");
        check(aggregate.getJSONObject("vendor_extra").getBoolean("ok"),"Unknown message delta preserved");
        for(double remaining:new double[]{0.4,12.5}) {
            Number value=(Number)call("ZCodeGift","bucketNumber",new Class[]{JSONObject.class,String.class},new JSONObject().put("remaining_units",remaining),"remaining_units");
            check(value!=null && Math.abs(value.doubleValue()-remaining)<0.0000001,"Fractional gift balance retained on Android");
        }
        JSONObject args=new JSONObject().put("中文","值");
        JSONObject fn=new JSONObject().put("name",new JSONObject().put("n","read")).put("arguments",args);
        String objectId=QoderProtocol.callID(0,0,new JSONObject().put("function",fn));
        fn.put("arguments",args.toString());
        check(objectId.equals(QoderProtocol.callID(0,0,new JSONObject().put("function",fn))),"Qoder object args and serialized args have the same call id");
        check(new LocalBridge.Result(200,"{\"ok\":true,\"id\":\"saved\"}","").accountSaved(),"Android recognizes confirmed save");
        for(int status:new int[]{200,400,401,409,500})check(!new LocalBridge.Result(status,"{\"ok\":false}","").accountSaved(),"Android never reports a failed save as success");
    }
    private static final class Reply {
        int status;String headers,body;
    }
    private Reply http(int port,String method,String path,String body,boolean authorized,long declared)throws Exception {
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(10000);
            String head=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+(declared>=0?declared:payload.length)+"\r\n";
            if(authorized)head+="X-All-In-Management: "+GatewayManagementCredential.get()+"\r\n";
            OutputStream out=socket.getOutputStream();out.write((head+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(payload);out.flush();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=socket.getInputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("Oversized local fixture response");bytes.write(buffer,0,n);}
            byte[] raw=bytes.toByteArray();String latin=new String(raw,StandardCharsets.ISO_8859_1);int split=latin.indexOf("\r\n\r\n");
            if(split<0)throw new IOException("Incomplete local response");Reply result=new Reply();result.headers=latin.substring(0,split);
            result.status=Integer.parseInt(result.headers.split(" ",3)[1]);result.body=new String(raw,split+4,raw.length-split-4,StandardCharsets.UTF_8);return result;
        }
    }
    private void gateway(Context target)throws Exception {
        File dir=new File(target.getFilesDir(),"repair-runtime-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("Fixture directory");
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}};
        GatewayServer server=null;
        try {
            server=new GatewayServer(isolated,0);server.start(5000,true);int port=server.getListeningPort();
            check(port>0,"Real Android gateway starts on an isolated ephemeral port");
            check(!GatewayManagementCredential.get().isEmpty(),"Android management credential created privately");
            check(http(port,"GET","/","",false,-1).status==200,"Dashboard GET loads actual APK assets");
            Reply head=http(port,"HEAD","/","",false,-1);check(head.status==200 && head.body.isEmpty(),"Dashboard HEAD sends no body; status="+head.status+" bytes="+head.body.length()+" headers="+head.headers);
            check(http(port,"GET","/provider-icons/openai.svg","",false,-1).status==200,"Icon GET works");
            head=http(port,"HEAD","/provider-icons/openai.svg","",false,-1);check(head.status==200 && head.body.isEmpty(),"Icon HEAD works");
            Reply notice=http(port,"GET","/licenses.html","",false,-1);
            check(notice.status==200 && notice.body.contains("SST") && notice.body.contains("magpie-community contributors"),"Bundled license originals are readable over the real gateway");
            Reply noticeHead=http(port,"HEAD","/licenses.html","",false,-1);
            check(noticeHead.status==200 && noticeHead.body.isEmpty(),"License HEAD is also body-free");
            check((Boolean)call("MainActivity","isTrustedPanelResource",new Class[]{String.class},"file:///android_asset/licenses.html"),"Offline license asset is explicitly whitelisted");
            Reply rejected=http(port,"POST","/","",false,1024*1024);check(rejected.status==405 && rejected.headers.toLowerCase(java.util.Locale.ROOT).contains("allow: get, head"),"Static POST rejects before waiting for a declared 1 MiB body");
            check(http(port,"GET","/api/config","",false,-1).status==401,"Unauthenticated management blocked on Android");
            for(String token:new String[]{"ck_android_fixture_A","ck_android_fixture_B"}) {
                Reply added=http(port,"POST","/api/auth/workbuddy/add",new JSONObject().put("name","同名账号").put("token",token).toString(),true,-1);
                check(added.status==200 && new JSONObject(added.body).getBoolean("ok") && new JSONObject(added.body).has("id"),"Same-name account saved through Android HTTP");
            }
            JSONObject stored=new JSONObject(read(new File(dir,"config.json")));JSONObject workbuddy=null;
            JSONArray providers=stored.getJSONArray("providers");for(int i=0;i<providers.length();i++)if("workbuddy".equals(providers.getJSONObject(i).optString("id")))workbuddy=providers.getJSONObject(i);
            check(workbuddy!=null && workbuddy.getJSONArray("accounts").length()==2,"Both Android accounts persisted");
            Method build=GatewayServer.class.getDeclaredMethod("buildTarget",String.class);build.setAccessible(true);java.util.Set<String> selected=new java.util.HashSet<>();
            for(int i=0;i<4;i++){Object route=build.invoke(server,"workbuddy/android-model");java.lang.reflect.Field field=route.getClass().getDeclaredField("apiKey");field.setAccessible(true);selected.add((String)field.get(route));}
            check(selected.size()==2,"Actual Android selector rotates both stored accounts");
            String view=http(port,"GET","/api/config","",true,-1).body;check(!view.contains("ck_android_fixture_"),"Android configuration response masks tokens");
            Reply third=http(port,"POST","/api/auth/workbuddy/add",new JSONObject().put("name","新增测试账号").put("token","ck_android_fixture_C").toString(),true,-1);
            check(third.status==200,"Android adds an account after an older full-config snapshot");
            Reply stale=http(port,"POST","/api/config",view,true,-1);check(stale.status==409,"Android stale full-config POST is rejected");
            JSONObject current=new JSONObject(read(new File(dir,"config.json")));JSONObject wp=null;for(int i=0;i<current.getJSONArray("providers").length();i++){JSONObject p=current.getJSONArray("providers").getJSONObject(i);if("workbuddy".equals(p.optString("id")))wp=p;}
            check(wp!=null&&wp.getJSONArray("accounts").length()==3,"Android stale save cannot erase the new account");
            Reply removed=http(port,"POST","/api/auth/workbuddy/remove",new JSONObject().put("id",new JSONObject(third.body).getString("id")).toString(),true,-1);
            check(removed.status==200,"Only the extra synthetic Android account is removed");
            server.stop();server=null;server=new GatewayServer(isolated,0);
            selected.clear();for(int i=0;i<2;i++){Object route=build.invoke(server,"workbuddy/android-model");java.lang.reflect.Field field=route.getClass().getDeclaredField("apiKey");field.setAccessible(true);selected.add((String)field.get(route));}
            check(selected.size()==2,"Restart reload preserves and rotates both accounts");
            check(IngressMemoryBudget.usedBytes()==0,"Android ingress allocations released");
        } finally {if(server!=null)server.stop();delete(dir);}
    }
    private void accountManagementRuntime(Context target)throws Exception {
        File dir=new File(target.getFilesDir(),"account-management-"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("Fixture directory");
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}};
        GatewayServer server=null;
        String[] ids={"antigravity","minimax-code","trae-cn","deepseek_web","workbuddy","workbuddy-ai","raccoon","loomy","catpaw","github-copilot","cline","codex","qoder_cn","zcode","openai","anthropic","deepseek","siliconflow","openrouter","tencent"};
        try {
            JSONObject root;try(InputStream in=target.getAssets().open("default_config.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);root=new JSONObject(out.toString("UTF-8"));}
            root.put("checkin",new JSONObject().put("autoEnabled",false));root.put("setup",new JSONObject().put("done",true));
            for(String id:ids) {
                JSONObject p=null;for(int i=0;i<root.getJSONArray("providers").length();i++){JSONObject item=root.getJSONArray("providers").getJSONObject(i);if(id.equals(item.optString("id")))p=item;}
                if(p==null)throw new AssertionError("missing fixture provider "+id);
                JSONArray a=new JSONArray(),keys=new JSONArray();for(String suffix:new String[]{"one","two"}){String token="synthetic-android-"+id+"-"+suffix;a.put(new JSONObject().put("id",id+"-"+suffix).put("name","同名账号").put("email",suffix+"@example.invalid").put("token",token).put("access",token).put("enabled",true));keys.put(token);}
                p.put("accounts",a).put("keys",keys).put("enabled",true);
            }
            AtomicConfigFile.write(new File(dir,"config.json"),root.toString(2));server=new GatewayServer(isolated,0);server.start(5000,true);int port=server.getListeningPort();
            for(String id:ids) {
                String path="/api/auth/"+id+"/";
                Reply single=http(port,"POST",path+"remove",new JSONObject().put("id",id+"-one").toString(),true,-1);
                check(single.status==200&&new JSONObject(single.body).optInt("removedCount")==1,"Android single removal "+id);
                JSONObject view=new JSONObject(http(port,"GET","/api/config","",true,-1).body);
                String state=view.getJSONObject("providerStateTokens").getString(id);
                Reply label=http(port,"POST",path+"label",new JSONObject().put("id",id+"-two").put("label","备用账号").put("expectedStateToken",state).toString(),true,-1);
                check(label.status==200&&"备用账号".equals(new JSONObject(label.body).optString("label")),"Android label persistence "+id);
                Reply stale=http(port,"POST",path+"remove",new JSONObject().put("id","all").put("expectedStateToken",state).toString(),true,-1);
                check(stale.status==409,"Android stale destructive confirmation rejected "+id);
                Reply clear=http(port,"POST",path+"remove",new JSONObject().put("id","all").toString(),true,-1);
                check(clear.status==200&&new JSONObject(clear.body).optInt("removedCount")==1&&new JSONObject(clear.body).optInt("remainingAccounts")==0,"Android actual last account removal "+id);
                JSONObject disk=new JSONObject(read(new File(dir,"config.json"))),p=null;for(int i=0;i<disk.getJSONArray("providers").length();i++){JSONObject item=disk.getJSONArray("providers").getJSONObject(i);if(id.equals(item.optString("id")))p=item;}
                check(p!=null&&p.getJSONArray("accounts").length()==0&&p.getJSONArray("keys").length()==0,"Android durable empty accounts AND mirrors "+id);
                Reply repeat=http(port,"POST",path+"remove",new JSONObject().put("id","all").toString(),true,-1);check(repeat.status==200&&!new JSONObject(repeat.body).optBoolean("removed",true),"Android repeated clear is not a new removal "+id);
            }
            check(!new JSONObject(read(new File(dir,"config.json"))).getJSONObject("checkin").getBoolean("autoEnabled"),"Android synthetic test did not enable automatic claims");
        } finally {if(server!=null)server.stop();delete(dir);}
    }
    private void advisoryRuntime() throws Exception {
        JSONObject account=new JSONObject().put("id","fixture-a").put("enabled",true).put("token","synthetic-android-old").put("refresh","synthetic-android-refresh");
        JSONObject base=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id","workbuddy").put("enabled",true).put("accounts",new JSONArray().put(account))));
        AccountGenerations generations=new AccountGenerations();generations.observe(base);long first=generations.revision("workbuddy","id:fixture-a");
        JSONObject copy=new JSONObject(base.toString()).put("privacy",new JSONObject().put("enabled",true));generations.observe(copy);
        check(first>0 && first==generations.revision("workbuddy","id:fixture-a"),"Android configuration copy/settings preserve account generation");
        copy.getJSONArray("providers").getJSONObject(0).put("accounts",new JSONArray());generations.observe(copy);
        check(generations.revision("workbuddy","id:fixture-a")==0,"Android deleted account has no live generation");generations.observe(base);
        check(generations.revision("workbuddy","id:fixture-a")!=first,"Android same-byte re-add is a new incarnation");
        for(String provider:new String[]{"workbuddy","workbuddy-ai"}) {
            JSONObject config=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id",provider).put("accounts",new JSONArray()).put("keys",new JSONArray())));
            JSONObject input=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id",provider).put("keys",new JSONArray().put(new JSONObject().put("key","ck_android_import_fixture").put("enabled",true)))));
            AccountTransferCodec.Document document=AccountTransferCodec.parse(input.toString().getBytes(StandardCharsets.UTF_8),provider);
            JSONObject merged=AccountTransferPlan.merge(config,document,java.util.Collections.singleton(0),false,System.currentTimeMillis()).config;
            JSONObject p=merged.getJSONArray("providers").getJSONObject(0);
            check(AccountPoolPolicy.routingAccounts(p).size()==1,"Android imported object key becomes a usable route: "+provider);
            p.getJSONArray("keys").getJSONObject(0).put("off",true);check(AccountPoolPolicy.routingAccounts(p).isEmpty(),"Android disabled object key stays excluded");
        }
        check("中文响应".equals(BoundedResponse.readUtf8(new ByteArrayInputStream("中文响应".getBytes(StandardCharsets.UTF_8)),128,1000)),"Android bounded response preserves UTF-8");
        final java.util.concurrent.atomic.AtomicBoolean bodyClosed=new java.util.concurrent.atomic.AtomicBoolean();
        InputStream tooLarge=new ByteArrayInputStream(new byte[128]){@Override public void close(){bodyClosed.set(true);}};
        boolean refused=false;try{BoundedResponse.readUtf8(tooLarge,32,1000);}catch(IOException expected){refused=true;}
        check(refused&&bodyClosed.get(),"Android oversized ordinary response is rejected and closed");
        try(InputStream chunks=new LocalBridge.ChunkedIn(new ByteArrayInputStream("2;fixture=yes\r\n{}\r\n0\r\nX-Test: yes\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4];int n;while((n=chunks.read(b))!=-1)out.write(b,0,n);
            check("{}".equals(out.toString("UTF-8")),"Android strict chunk decoder accepts extensions and trailers");
        }
        for(String damaged:new String[]{"2\r\nx","2\r\n{}\r\n","0\r\nX-Test: incomplete\r\n"}) {
            boolean rejected=false;try(InputStream in=new LocalBridge.ChunkedIn(new ByteArrayInputStream(damaged.getBytes(StandardCharsets.US_ASCII)))){while(in.read()!=-1){}}catch(IOException expected){rejected=true;}
            check(rejected,"Android truncated chunk/trailer is not successful EOF");
        }
        for(String mode:new String[]{"manual","page","destroy"}) {
            BridgeStreamOwner owner=new BridgeStreamOwner();BridgeStreamOwner.Ticket ticket=owner.begin("android-fixture",2000);
            try(java.net.ServerSocket listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));Socket client=new Socket()) {
                ticket.call.attach(client);client.connect(new java.net.InetSocketAddress("127.0.0.1",listener.getLocalPort()),1000);
                try(Socket peer=listener.accept()) {
                    peer.setSoTimeout(1500);check(owner.begin("duplicate",2000)==null,"Android owner prevents second active stream");
                    java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
                    Thread reader=new Thread(()->{reading.countDown();try{client.getInputStream().read();}catch(IOException expected){}finally{ended.set(true);}},"repair-socket-reader");reader.start();
                    if(!reading.await(1,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("reader not ready");
                    if("manual".equals(mode))owner.cancel(ticket.id);else if("page".equals(mode))owner.cancelAll();else owner.close();
                    check(peer.getInputStream().read()==-1,"Android "+mode+" cancellation closes the actual owned connection");reader.join(1500);
                    check(!reader.isAlive()&&ended.get()&&!owner.accepts(ticket),"Android cancellation unblocks I/O and rejects late callbacks");
                }
            } finally {owner.close();}
        }
    }
    private void gzipRuntime()throws Exception {
        byte[] text="data: 中文\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream z=new java.util.zip.GZIPOutputStream(compressed)){z.write(text);}byte[] gzip=compressed.toByteArray();
        Method decoder=LocalBridge.class.getDeclaredMethod("streamBody",InputStream.class,QoderAuth.Head.class,String.class);decoder.setAccessible(true);
        for(String kind:new String[]{"fixed-valid","chunk-valid","fixed-short","chunk-missing","chunk-trailer"}) {
            boolean fixed=kind.startsWith("fixed"),valid=kind.endsWith("valid");ByteArrayOutputStream wire=new ByteArrayOutputStream();
            if(fixed)wire.write(gzip);else{wire.write((Integer.toHexString(gzip.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));wire.write(gzip);wire.write("\r\n".getBytes(StandardCharsets.US_ASCII));if(kind.equals("chunk-valid"))wire.write("0\r\nX-Test: yes\r\n\r\n".getBytes(StandardCharsets.US_ASCII));if(kind.equals("chunk-trailer"))wire.write("0\r\nX: unfinished\r\n".getBytes(StandardCharsets.US_ASCII));}
            QoderAuth.Head head=new QoderAuth.Head(200,fixed?gzip.length+(kind.equals("fixed-short")?10:0):-1,!fixed,"gzip");boolean rejected=false;
            try(InputStream in=(InputStream)decoder.invoke(null,new ByteArrayInputStream(wire.toByteArray()),head,"POST")) {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[64];int n;while((n=in.read(b))!=-1)out.write(b,0,n);if(valid)check(java.util.Arrays.equals(text,out.toByteArray()),"Android valid gzip payload exact: "+kind);}
            catch(IOException expected){rejected=true;}catch(java.lang.reflect.InvocationTargetException expected){if(expected.getCause() instanceof IOException)rejected=true;else throw expected;}
            check(valid?!rejected:rejected,"Android gzip enforces HTTP boundary: "+kind);
        }
    }
    private void asyncRuntime()throws Exception {
        final java.util.concurrent.atomic.AtomicReference<MainActivity> created=new java.util.concurrent.atomic.AtomicReference<>();runOnMainSync(()->created.set(new MainActivity()));MainActivity owner=created.get();java.lang.reflect.Field page=MainActivity.class.getDeclaredField("currentPageUrl");page.setAccessible(true);page.set(owner,"file:///android_asset/dashboard.html");
        Class<?> bridgeClass=Class.forName("com.gaeul.allin.MainActivity$NativeBridge");java.lang.reflect.Constructor<?> ctor=bridgeClass.getDeclaredConstructor(MainActivity.class);ctor.setAccessible(true);Object bridge=ctor.newInstance(owner);
        Method async=bridgeClass.getDeclaredMethod("requestAsync",String.class,String.class,String.class,String.class);async.setAccessible(true);Method cancel=MainActivity.class.getDeclaredMethod("cancelBridgeCalls");cancel.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger sends=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        try(java.net.ServerSocket listener=new java.net.ServerSocket()) {
            listener.setReuseAddress(true);listener.bind(new java.net.InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(650);
            Thread peer=new Thread(()->{try(Socket socket=listener.accept()){sends.incrementAndGet();}catch(java.net.SocketTimeoutException expected){}catch(Throwable t){failure.set(t);}},"android-async-peer");peer.start();Thread worker=null;
            synchronized(owner) {
                async.invoke(bridge,"android-delayed-old-page","POST","/api/privacy","{}");long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(worker==null&&System.nanoTime()<until){for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("AllIn-BridgeRequest")&&t.getState()==Thread.State.BLOCKED){worker=t;break;}Thread.yield();}
                check(worker!=null,"Actual Android requestAsync worker is paused before owning/sending");cancel.invoke(owner);
            }
            worker.join(1500);peer.join(1500);if(failure.get()!=null)throw new AssertionError(failure.get());
            check(!worker.isAlive()&&!peer.isAlive()&&sends.get()==0,"Actual Android old-page asynchronous request sends zero packets");
        }
        java.util.concurrent.CountDownLatch received=new java.util.concurrent.CountDownLatch(1);
        try(java.net.ServerSocket listener=new java.net.ServerSocket()) {
            listener.setReuseAddress(true);listener.bind(new java.net.InetSocketAddress("127.0.0.1",3425));listener.setSoTimeout(2000);
            Thread peer=new Thread(()->{try(Socket socket=listener.accept()){socket.setSoTimeout(2000);int a=-1,b=-1,c=-1,n;InputStream in=socket.getInputStream();while((n=in.read())!=-1){if(a==13&&b==10&&c==13&&n==10)break;a=b;b=c;c=n;}sends.incrementAndGet();socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();received.countDown();}catch(Throwable t){failure.set(t);}},"android-fresh-peer");peer.start();async.invoke(bridge,"android-current-page","GET","/api/health","");
            check(received.await(2,java.util.concurrent.TimeUnit.SECONDS),"New-page actual Android asynchronous request remains usable");peer.join(2000);if(failure.get()!=null)throw new AssertionError(failure.get());check(sends.get()==1&&!peer.isAlive(),"Only one fresh-page Android request was sent");
        } finally {cancel.invoke(owner);}
    }
    private void updatesRuntime(Context target)throws Exception {
        final JSONObject[] memory={new JSONObject()};final long[] time={1000000};final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        JSONObject release=new JSONObject().put("id",1).put("draft",false).put("prerelease",false).put("tag_name","v1.2.45-test").put("html_url","https://github.com/xunianooo/All-in/releases/tag/v1.2.45-test").put("body","测试更新说明").put("assets",new JSONArray());
        AppUpdateCore core=new AppUpdateCore(new AppUpdateCore.Store(){public JSONObject read(){return memory[0];}public void write(JSONObject x){memory[0]=new JSONObject(x.toString());}},(url,limit)->{calls.incrementAndGet();return new AppUpdateCore.Reply(200,release.toString(),0);},()->time[0],50,"1.2.44-test");
        JSONObject first=core.check(false);check(first.getBoolean("showPrompt"),"Android newer release prompts");core.dismiss(first.getString("releaseKey"));check(!core.snapshot(false).getBoolean("showPrompt"),"Android dismissal survives cached checks");check(core.snapshot(true).getBoolean("showPrompt"),"Android manual bypasses dismissal");time[0]+=AppUpdateCore.INTERVAL-1;core.check(false);check(calls.get()==1,"Android cache holds before exact 9-hour boundary");time[0]++;core.check(false);check(calls.get()==2,"Android exact nine hours rechecks");
        android.app.job.JobScheduler jobs=(android.app.job.JobScheduler)target.getSystemService(Context.JOB_SCHEDULER_SERVICE);android.app.job.JobInfo previous=jobs.getPendingJob(AppUpdateManager.JOB_ID);
        // A persisted real update/429 backoff is not a fresh nine-hour fixture. Keep the actual
        // JobScheduler assertion, but give the manager an independent empty state and restore it.
        java.lang.reflect.Field shared=AppUpdateManager.class.getDeclaredField("instance");shared.setAccessible(true);
        Object savedCore=shared.get(null);final JSONObject[] scheduleState={new JSONObject()};
        AppUpdateCore scheduleCore=new AppUpdateCore(new AppUpdateCore.Store(){
            public JSONObject read(){return new JSONObject(scheduleState[0].toString());}
            public void write(JSONObject state){scheduleState[0]=new JSONObject(state.toString());}
        },(url,limit)->{throw new IOException("Scheduling fixture must not fetch");},System::currentTimeMillis,59,"1.2.53-test");
        jobs.cancel(AppUpdateManager.JOB_ID);shared.set(null,scheduleCore);
        try{AppUpdateManager.schedule(target);android.app.job.JobInfo job=jobs.getPendingJob(AppUpdateManager.JOB_ID);check(job!=null&&!job.isPeriodic()&&job.getMinLatencyMillis()>=AppUpdateCore.INTERVAL-1000&&job.getMinLatencyMillis()<=AppUpdateCore.INTERVAL,"Actual Android JobScheduler has a 9-hour due-time job");check(job.isPersisted()&&job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY,"Update job persists and requires network");AppUpdateManager.schedule(target);check(jobs.getAllPendingJobs().stream().filter(j->j.getId()==AppUpdateManager.JOB_ID).count()==1,"Scheduling does not duplicate update jobs");}finally{shared.set(null,savedCore);jobs.cancel(AppUpdateManager.JOB_ID);if(previous!=null)jobs.schedule(previous);}
        final android.content.Intent[] opened={null};Context capture=new ContextWrapper(target){@Override public void startActivity(android.content.Intent i){opened[0]=i;}};check(AppUpdateManager.openBrowser(capture),"Production browser action dispatches");check(opened[0]!=null&&android.content.Intent.ACTION_VIEW.equals(opened[0].getAction())&&AppUpdateCore.DOWNLOAD.equals(opened[0].getDataString()),"Exact trusted GitHub latest download page used");
    }
    private void alarms(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("magpie_prefs",Context.MODE_PRIVATE);
        prefs.edit().putBoolean("gateway_enabled",true).commit();KeepAliveScheduler.cancel(context);
        check(KeepAliveScheduler.schedule(context),"Android AlarmManager accepts the request");
        SharedPreferences alarm=context.getSharedPreferences("magpie_keepalive",Context.MODE_PRIVATE);long requested=alarm.getLong("nextElapsed",0);
        check(requested>0 && KeepAliveScheduler.schedule(context) && requested==alarm.getLong("nextElapsed",0),"Repeated scheduling does not postpone an in-flight alarm");
        prefs.edit().putBoolean("gateway_enabled",false).commit();
        check(!KeepAliveScheduler.schedule(context) && !alarm.contains("nextElapsed"),"Manual stop cancels alarm and does not revive the gateway");
    }
    private static String read(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    /** Test-only draft injection, constrained to the visible LongCat ETA composer, never credential forms. */
    private void setEtaInputText() throws Exception {
        String text=new String(android.util.Base64.decode(etaInputText,android.util.Base64.DEFAULT),StandardCharsets.UTF_8);
        if(!text.matches("[A-Za-z0-9_ .]{1,120}")||!text.contains("ETA_LONGCAT_103_"))throw new SecurityException("Unexpected test draft");
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null||!"io.github.mangi.eta".contentEquals(root.getPackageName()))throw new SecurityException("ETA is not the active application");
        boolean composer=false;java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayDeque<>();nodes.add(root);
        while(!nodes.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo node=nodes.remove();
            if("切换模型，当前为 catpaw/longcat-2.5".contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))composer=true;
            for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.add(child);}
        }
        android.view.accessibility.AccessibilityNodeInfo input=root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
        if(!composer||input==null||input.isPassword()||!"android.widget.EditText".contentEquals(input.getClassName()))throw new SecurityException("Expected ETA composer is not focused");
        Bundle argument=new Bundle();argument.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
        if(!input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,argument))throw new AssertionError("ETA set-text action rejected");
        getUiAutomation().waitForIdle(200,3000);input.refresh();
        if(!text.contentEquals(input.getText()==null?"":input.getText()))throw new AssertionError("ETA draft differs after set-text");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context target=getTargetContext();
            if(!target.getPackageName().endsWith(".repairtest"))throw new SecurityException("Runtime tests require the isolatedTestInstall package");
            if(themeStartupOnly){result.putString("stream",PanelThemeStartupRuntime.run(this)+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if (etaInputText!=null) { setEtaInputText();result.putString("stream","ETA_TEST_INPUT_PASS verified exact draft\n");finish(Activity.RESULT_OK,result);return; }
            if (catPawTerminalOnly) { result.putString("stream",CatPawTerminalRuntime.run(target)+System.lineSeparator());finish(Activity.RESULT_OK,result);return; }
            if (heldCancellationOnly) {
                result.putString("stream", HeldCancellationRuntime.run(target) + System.lineSeparator());
                finish(Activity.RESULT_OK, result);
                return;
            }
            if(loginLifecycleOnly){result.putString("stream",LoginLifecycleRuntime.run(this)+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if(gatewayLifecycleOnly){result.putString("stream",GatewayLifecycleRuntime.run(this)+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if(panelRefreshOnly){result.putString("stream",PanelRefreshRuntime.run(this)+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if(codexTlsOnly){result.putString("stream",CodexTlsRuntime.run()+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if(qoderCheckinOnly){result.putString("stream",QoderCheckinRuntime.run(target)+System.lineSeparator());finish(Activity.RESULT_OK,result);return;}
            if(audit52){result.putString("stream","AUDIT_ANDROID 05_webview "+Audit52WebView.run(this)+"\n");finish(Activity.RESULT_OK,result);return;}
            if(qoderNamesOnly){checks=QoderModelNamesRuntime.run(target);result.putString("stream","ANDROID_QODER_NAMES_PASS checks="+checks+" api="+android.os.Build.VERSION.SDK_INT+"\n");finish(Activity.RESULT_OK,result);return;}
            accountRoutingRuntime();jsonRuntime();advisoryRuntime();gzipRuntime();asyncRuntime();updatesRuntime(target);gateway(target);accountManagementRuntime(target);alarms(target);
            checks += AntigravityCompatibilityRuntime.run();
            checks += CallEvidenceRuntime.run(target);
            checks += AntigravityRequestParityRuntime.run();
            int qoderNamesChecks = QoderModelNamesRuntime.run(target);
            checks += qoderNamesChecks;
            int cacheChecks=CacheAccountingRuntime.run(target)+CacheAccountingRuntime.webView(this);checks+=cacheChecks;
            result.putString("stream","ANDROID_REPAIR_PASS checks="+checks+" qoderNames="+qoderNamesChecks+" cacheChecks="+cacheChecks+" api="+android.os.Build.VERSION.SDK_INT+"\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));result.putString("stream","ANDROID_REPAIR_FAIL checks="+checks+"\n"+trace);finish(Activity.RESULT_CANCELED,result);
        }
    }
}
