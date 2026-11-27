package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Account removal and local labels through real production handlers; synthetic records only. */
public final class AccountManagementRegressionTest {
    static int checks;
    static final String[] PROVIDERS={"antigravity","minimax-code","trae-cn","deepseek_web","workbuddy","workbuddy-ai","raccoon","loomy","catpaw","github-copilot","cline","codex","qoder_cn","zcode","openai","anthropic","deepseek","siliconflow","openrouter","tencent"};
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static JSONObject copy(JSONObject o){return new JSONObject(o.toString());}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("uid",id).put("name","同名用户").put("email",id+"@example.invalid").put("token","synthetic-access-"+id).put("access","synthetic-access-"+id).put("refresh","synthetic-refresh-"+id).put("expires",System.currentTimeMillis()+3600000L).put("enabled",true).put("type","oauth").put("models",new JSONArray().put("model-"+id));}
    static JSONObject provider(String id){return new JSONObject().put("id",id).put("name",id).put("enabled",true).put("protocol","openai").put("status","ported").put("accounts",new JSONArray().put(account("one")).put(account("two").put("off",true))).put("keys",new JSONArray().put("synthetic-access-one").put(new JSONObject().put("key","synthetic-access-one")).put("synthetic-access-two").put("synthetic-independent").put(new JSONObject().put("key","synthetic-disabled-independent").put("enabled",false))).put("models",new JSONArray().put("model-one").put("model-two"));}
    static JSONObject current(GatewayServer s)throws Exception{Field f=GatewayServer.class.getDeclaredField("configJson");f.setAccessible(true);return (JSONObject)f.get(s);}
    static JSONObject find(JSONObject root,String id){for(Object x:root.getJSONArray("providers")){if(x instanceof JSONObject&&id.equals(((JSONObject)x).optString("id")))return (JSONObject)x;}throw new AssertionError("provider missing");}
    static final class Fixture implements AutoCloseable {
        final Path dir=Files.createTempDirectory("account-management-");final File file=dir.resolve("config.json").toFile();final GatewayServer server;final String provider;
        Fixture(String id)throws Exception {provider=id;JSONObject root=new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(provider(id)).put(provider("unrelated"))).put("groups",new JSONArray()).put("checkin",new JSONObject().put("autoEnabled",false).put("records",new JSONObject().put(id+":one",new JSONObject().put("date","2026-11-16").put("outcome","unknown"))));server=RoutingSetupOfflineTest.server(root);Field f=GatewayServer.class.getDeclaredField("configFile");f.setAccessible(true);f.set(server,file);AtomicConfigFile.write(file,root.toString(2));}
        JSONObject disk()throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        JSONObject pool()throws Exception{return find(current(server),provider);}
        Reply call(String suffix,JSONObject body)throws Exception{return new Reply(server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/"+provider+"/"+suffix,"POST",body.toString(),null)));}
        JSONObject view()throws Exception{return new Reply(server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/config","GET","",null))).body;}
        public void close()throws Exception{GatewayServer.setConfigWriteHookForTests(null);server.stop();try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}NetworkPolicy.configure(null);}
    }
    static final class Reply {final int status;final JSONObject body;Reply(NanoHTTPD.Response r)throws Exception{status=r.getStatus().getRequestStatus();try(InputStream in=r.getData()){body=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}}
    static Set<String> keys(JSONObject p){Set<String> keys=new HashSet<>();JSONArray a=p.optJSONArray("keys");for(int i=0;a!=null&&i<a.length();i++){Object raw=a.opt(i);if(raw instanceof String)keys.add((String)raw);else if(raw instanceof JSONObject)keys.add(((JSONObject)raw).optString("key"));}return keys;}
    static void remove(String id,boolean all)throws Exception{try(Fixture f=new Fixture(id)){
        JSONObject before=f.disk();Reply r=f.call("remove",new JSONObject().put("id",all?"all":"one"));
        check(r.status==200&&r.body.optBoolean("ok"),id+" removal accepted");check(f.pool().getJSONArray("accounts").length()==(all?0:1),id+" actual account count after "+(all?"clear":"single"));
        Set<String> keys=keys(f.pool());check(!keys.contains("synthetic-access-one"),id+" removed account mirror gone");check(all?!keys.contains("synthetic-access-two"):!f.pool().getJSONArray("accounts").isEmpty(),id+" all/remaining scope");
        check(keys.contains("synthetic-independent")&&keys.contains("synthetic-disabled-independent"),id+" independent keys retained");
        check(find(f.disk(),id).similar(f.pool()),id+" disk equals memory");check(find(before,"unrelated").similar(find(f.disk(),"unrelated")),id+" other provider untouched");check(before.getJSONObject("checkin").similar(f.disk().getJSONObject("checkin")),id+" checkin fences retained");
        if(!all){Reply last=f.call("remove",new JSONObject().put("id","two"));check(last.status==200&&f.pool().getJSONArray("accounts").length()==0,id+" remove last");check(!keys(f.pool()).contains("synthetic-access-two"),id+" last mirror gone");}
    }}
    static void failures(String id)throws Exception{try(Fixture f=new Fixture(id)){
        byte[] before=Files.readAllBytes(f.file.toPath());JSONObject mem=copy(current(f.server));GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic save failure");});
        Reply r;try{r=f.call("remove",new JSONObject().put("id","all"));}finally{GatewayServer.setConfigWriteHookForTests(null);}
        check(r.status==500&&!r.body.optBoolean("ok"),id+" failed clear must not succeed");check(Arrays.equals(before,Files.readAllBytes(f.file.toPath()))&&mem.similar(current(f.server)),id+" failed clear changes neither disk nor memory");
        Reply noOp=f.call("remove",new JSONObject().put("id","absent"));check(noOp.status==200&&noOp.body.optBoolean("ok")&&!noOp.body.optBoolean("removed",true),id+" idempotent missing report");check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),id+" missing account no write");
        check(f.call("remove",new JSONObject().put("id",new JSONObject())).status==400,id+" malformed id rejected");
    }}
    static void label()throws Exception{try(Fixture f=new Fixture("antigravity")){
        JSONObject before=f.disk(),view=f.view();String token=view.getJSONObject("providerStateTokens").getString("antigravity");
        Reply r=f.call("label",new JSONObject().put("id","one").put("label","我的工作账号").put("expectedStateToken",token));check(r.status==200&&r.body.optBoolean("ok"),"label saved");
        JSONObject a=f.pool().getJSONArray("accounts").getJSONObject(0);check("我的工作账号".equals(a.optString("label")),"label visible on correct account");JSONObject expected=copy(before);find(expected,"antigravity").getJSONArray("accounts").getJSONObject(0).put("label","我的工作账号");check(expected.similar(f.disk()),"label changes no identity, credential, flags or fences");
        check(!r.body.toString().contains("synthetic-access")&&!r.body.toString().contains("synthetic-refresh"),"label reply contains no credential");
        check(f.call("label",new JSONObject().put("id","one").put("label","stale").put("expectedStateToken",token)).status==409,"stale label state rejected");
        token=f.view().getJSONObject("providerStateTokens").getString("antigravity");byte[] disk=Files.readAllBytes(f.file.toPath());
        for(Object invalid:new Object[]{new JSONObject(),"x\nspoof","x".repeat(81),"allin-secret-ref:synthetic","synthetic-access-one"})check(f.call("label",new JSONObject().put("id","one").put("label",invalid).put("expectedStateToken",token)).status==400,"invalid/secret label rejected");
        check(Arrays.equals(disk,Files.readAllBytes(f.file.toPath())),"invalid labels no disk mutation");
        GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic save failure");});try{check(f.call("label",new JSONObject().put("id","one").put("label","new").put("expectedStateToken",token)).status==500,"label failed write reported");}finally{GatewayServer.setConfigWriteHookForTests(null);}
        check(Arrays.equals(disk,Files.readAllBytes(f.file.toPath()))&&"我的工作账号".equals(f.pool().getJSONArray("accounts").getJSONObject(0).optString("label")),"label rollback safe");
        check(f.call("label",new JSONObject().put("id","one").put("label","").put("expectedStateToken",token)).status==200,"clear label supported");check(!f.pool().getJSONArray("accounts").getJSONObject(0).has("label"),"empty label restores original identity display");
    }}
    static void refreshThenRemove(String provider)throws Exception {
        try(ConfigConsistencyOfflineTest.Fixture f=new ConfigConsistencyOfflineTest.Fixture()) {
            JSONObject initial=copy(current(f.server));
            find(initial,provider).put("keys",new JSONArray().put("synthetic-old-a").put("synthetic-old-b").put("independent-refresh-test"));
            Method save=GatewayServer.class.getDeclaredMethod("persistConfigStrict",JSONObject.class);save.setAccessible(true);save.invoke(f.server,initial);
            f.server.setTokenRefreshForTests((a,force)->ConfigConsistencyOfflineTest.candidate(a));
            ConfigConsistencyOfflineTest.renew(f,provider,"a");
            Reply r=new Reply(f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/"+provider+"/remove","POST",new JSONObject().put("id","all").toString(),null)));
            check(r.status==200,"post-refresh removal accepted");
            Set<String> remaining=keys(find(current(f.server),provider));
            check(!remaining.contains("synthetic-old-a")&&!remaining.contains("synthetic-new-a")&&!remaining.contains("synthetic-old-b"),provider+" stale rotated mirror remains after deleting all accounts");
            check(remaining.equals(Collections.singleton("independent-refresh-test")),provider+" independent credential preserved after rotation/removal");
        }
    }
    static void stateProtection()throws Exception {
        try(Fixture f=new Fixture("antigravity")) {
            JSONObject view=f.view();String stale=view.getJSONObject("providerStateTokens").getString("antigravity");
            AccountGenerations generations=new AccountGenerations();generations.observe(current(f.server));long revision=generations.revision("antigravity","id:one"),incarnation=generations.incarnation("antigravity","id:one");
            check(f.call("label",new JSONObject().put("id","one").put("label","same owner").put("expectedStateToken",stale)).status==200,"label before CAS test");
            generations.observe(current(f.server));check(generations.revision("antigravity","id:one")==revision&&generations.incarnation("antigravity","id:one")==incarnation,"label must not rotate account generation");
            byte[] before=Files.readAllBytes(f.file.toPath());check(f.call("remove",new JSONObject().put("id","all").put("expectedStateToken",stale)).status==409,"stale destructive confirmation rejected");check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"stale clear writes nothing");
            String fresh=f.view().getJSONObject("providerStateTokens").getString("antigravity");Reply removed=f.call("remove",new JSONObject().put("id","all").put("expectedStateToken",fresh));check(removed.body.optInt("removedCount")==2&&removed.body.optInt("remainingAccounts")==0,"real removal receipt counts");
            before=Files.readAllBytes(f.file.toPath());Reply again=f.call("remove",new JSONObject().put("id","all"));check(again.status==200&&!again.body.optBoolean("removed")&&again.body.optInt("removedCount")==0,"repeated clear is no-op");check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"repeated clear does not save");
        }
    }
    static void accountEnabledToggle(String id)throws Exception {
        try(Fixture f=new Fixture(id)) {
            JSONObject view=f.view();String token=view.getJSONObject("providerStateTokens").getString(id);
            check(f.call("enabled",new JSONObject().put("id","one").put("enabled",false).put("expectedStateToken","stale-token")).status==409,id+" stale state rejected on account toggle");
            Reply disableOne=f.call("enabled",new JSONObject().put("id","one").put("enabled",false).put("expectedStateToken",token));
            check(disableOne.status==200&&disableOne.body.optBoolean("ok")&&!disableOne.body.optBoolean("enabled",true)&&!disableOne.body.optString("stateToken","").isEmpty(),id+" disable account succeeds");
            check(!disableOne.body.toString().contains("synthetic-access")&&!disableOne.body.toString().contains("synthetic-refresh"),id+" toggle reply contains no credential");
            JSONObject pool=f.pool();
            JSONObject acc1=pool.getJSONArray("accounts").getJSONObject(0);
            check(!AccountGenerations.enabled(acc1)&&!acc1.optBoolean("enabled",true),id+" account one marked disabled");
            check(AccountPoolPolicy.routingAccounts(pool).size()==("codex".equals(id)?0:1),id+" disabled account excluded from routing");
            Set<String> k=keys(pool);
            check(!k.contains("synthetic-access-one")&&k.contains("synthetic-independent"),id+" mirror removed while independent keys retained");
            check(find(f.disk(),id).similar(pool),id+" disable persisted to disk");
            check(f.call("enabled",new JSONObject().put("id","one").put("enabled",true).put("expectedStateToken",token)).status==409,id+" consumed token rejected");
            // Re-enable legacy off=true account ("two") using fresh stateToken
            String fresh=disableOne.body.getString("stateToken");
            Reply enableTwo=f.call("enabled",new JSONObject().put("id","two").put("enabled",true).put("expectedStateToken",fresh));
            check(enableTwo.status==200&&enableTwo.body.optBoolean("ok")&&enableTwo.body.optBoolean("enabled",false),id+" legacy off account re-enabled");
            JSONObject acc2=f.pool().getJSONArray("accounts").getJSONObject(1);
            check(AccountGenerations.enabled(acc2)&&!acc2.has("off"),id+" legacy off flag cleared on enable");
            check(AccountPoolPolicy.routingAccounts(f.pool()).size()==("codex".equals(id)?1:2),id+" re-enabled account restored to routing");
            check(keys(f.pool()).contains("synthetic-access-two"),id+" re-enabled mirror restored");
        }
    }
    static void accountEnabledValidation()throws Exception {
        try(Fixture f=new Fixture("catpaw")) {
            byte[] before=Files.readAllBytes(f.file.toPath());
            check(new Reply(f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/catpaw/enabled","GET","",null))).status==405,"GET /enabled rejected");
            for(String bad:new String[]{"{}","bad-json","{\"id\":\"\",\"enabled\":false}","{\"id\":\"one\",\"enabled\":\"false\"}","{\"id\":\"one\",\"enabled\":true,\"extra\":1}"})
                check(new Reply(f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/catpaw/enabled","POST",bad,null))).status==400,"invalid toggle body rejected: "+bad);
            check(f.call("enabled",new JSONObject().put("id","missing").put("enabled",false)).status==404,"missing account rejected");
            check(new Reply(f.server.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/auth/no-such-prov/enabled","POST",new JSONObject().put("id","one").put("enabled",false).toString(),null))).status==404,"missing provider rejected");
            check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"invalid toggle writes nothing to disk");
            GatewayServer.setConfigWriteHookForTests(()->{throw new IllegalStateException("synthetic save failure");});
            try{check(f.call("enabled",new JSONObject().put("id","one").put("enabled",false)).status==500,"failed save reported on toggle");}
            finally{GatewayServer.setConfigWriteHookForTests(null);}
            check(Arrays.equals(before,Files.readAllBytes(f.file.toPath())),"failed toggle changes neither disk nor memory");
            check(AccountGenerations.enabled(f.pool().getJSONArray("accounts").getJSONObject(0)),"account still enabled after failed save");
        }
    }
    interface Test {void run()throws Exception;}
    static int run(String name,Test test){try{test.run();System.out.println("PASS "+name);return 0;}catch(Throwable e){System.out.println("FAIL "+name+": "+e.getClass().getSimpleName()+" "+e.getMessage());return 1;}}
    public static void main(String[] args)throws Exception{int failures=0;boolean names=args.length>0&&"labels".equals(args[0]);if(!names)for(String p:PROVIDERS){failures+=run(p+" single",()->remove(p,false));failures+=run(p+" all",()->remove(p,true));failures+=run(p+" toggle",()->accountEnabledToggle(p));}if(!names)for(String p:new String[]{"antigravity","minimax-code","trae-cn","deepseek_web"})failures+=run(p+" failures",()->failures(p));if(args.length==0||names){failures+=run("labels",AccountManagementRegressionTest::label);failures+=run("state protection",AccountManagementRegressionTest::stateProtection);failures+=run("toggle validation",AccountManagementRegressionTest::accountEnabledValidation);}if(!names)for(String p:new String[]{"antigravity","github-copilot","workbuddy","workbuddy-ai"})failures+=run(p+" refresh then delete",()->refreshThenRemove(p));System.out.println("AccountManagementRegressionTest checks="+checks+" failures="+failures);if(failures>0)throw new AssertionError(failures+" account management scenarios failed");}
}
