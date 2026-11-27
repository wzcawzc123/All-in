package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorkBuddyAllowanceRegressionTest {
    static int checks;
    static void check(boolean v,String label){checks++;if(!v)throw new AssertionError(label);}
    static JSONObject pkg(Object total,Object used,Object left){return new JSONObject().put("CycleTotalCapacity",total).put("CycleUsedCapacity",used).put("CycleRemainCapacity",left);}
    static JSONObject sum(JSONObject... rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("Packages",a);}
    static JSONObject account(String id){return new JSONObject().put("id",id).put("uid","uid-"+id).put("name",id).put("nickname","nick-"+id).put("type","oauth").put("access","fixture-"+id).put("token","fixture-"+id).put("refresh","refresh-"+id).put("expires",System.currentTimeMillis()+3600000).put("domain","copilot.tencent.com");}
    static class Conn extends HttpURLConnection {
        int status=200;String challenge;JSONObject data;boolean closed;
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Conn(String u,JSONObject d)throws Exception{super(new URL(u));data=d;}
        public void connect(){throw new AssertionError("No real network");}
        public void disconnect(){closed=true;}
        public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return sent;}
        public int getResponseCode(){return status;}
        public String getHeaderField(String k){return "WWW-Authenticate".equals(k)?challenge:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(new JSONObject().put("code",0).put("data",data).toString().getBytes(StandardCharsets.UTF_8));}
    }
    static JSONObject ask(GatewayServer s,String id)throws Exception{return new JSONObject(ResponsesGatewayOfflineTest.read(s.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth",id)).getData()));}
    public static void main(String[] args)throws Exception {
        check(WorkBuddyAllowance.summary(sum(pkg("100","25",null))).getDouble("remaining")==75,"decimal strings and used");
        check(WorkBuddyAllowance.summary(sum(pkg("1.5","1.5","0"))).getDouble("remaining")==0,"zero not unknown");
        check(WorkBuddyAllowance.summary(sum(pkg("100",null,null)))==null,"missing not zero");
        check(WorkBuddyAllowance.summary(sum(pkg("100",null,"101")))==null,"invalid remainder");
        check(WorkBuddyAllowance.summary(sum(pkg("NaN",null,null)))==null,"nonfinite unknown");
        check(WorkBuddyAllowance.summary(sum())==null,"empty not exhausted");
        check(WorkBuddyAllowance.summary(sum(pkg(null,null,"0"))).getDouble("remaining")==0,"empty prepaid zero");
        JSONObject grouped=sum(pkg("100","1","99"),pkg(null,null,"3.5")).put("CreditGroups",new JSONArray().put(pkg("100","1","99").put("Kind","plan_base")).put(pkg("20","2","18").put("Kind","addon")));
        JSONObject allow=WorkBuddyAllowance.summary(grouped);
        check(allow.getDouble("remaining")==120.5,"groups and prepaid no double count");
        check(allow.getJSONArray("windows").length()==3,"separate group windows");
        check(WorkBuddyAllowance.enterprise(new JSONObject().put("limitNum","20").put("credit","2")).getDouble("remaining")==18,"team account meter");
        List<Conn> calls=new ArrayList<>();
        for(String provider:new String[]{"workbuddy","workbuddy-ai"}) {
            JSONObject a=account("a");
            JSONObject got=WorkBuddyAllowance.fetch(provider,a,u->{Conn c=new Conn(u,sum(pkg(100,20,80)));calls.add(c);return c;});
            Conn c=calls.get(calls.size()-1);
            check(c.getURL().toString().equals(WorkBuddyAuth.origin(provider)+WorkBuddyAllowance.SUMMARY),"fixed official summary origin");
            check(c.getRequestMethod().equals("POST") && c.sent.toString("UTF-8").equals("{}"),"read-only meter request");
            check(!c.getInstanceFollowRedirects() && c.closed,"no redirects and connection closed");
            check(c.getRequestProperty("X-User-Id").equals("uid-a") && got.getDouble("remaining")==80,"selected account uid");
        }
        JSONObject team=account("team").put("enterpriseId","e-1");
        WorkBuddyAllowance.fetch("workbuddy",team,u->{Conn c=new Conn(u,new JSONObject().put("limitNum",20).put("credit",1));check(u.endsWith(WorkBuddyAllowance.ENTERPRISE),"enterprise endpoint");calls.add(c);return c;});
        Conn tc=calls.get(calls.size()-1);check("e-1".equals(tc.getRequestProperty("X-Enterprise-Id")) && "e-1".equals(tc.getRequestProperty("X-Tenant-Id")),"dual team headers");
        for(String challenge:new String[]{null,"Bearer error=invalid_token"}) {
            try{WorkBuddyAllowance.fetch("workbuddy",account("a"),u->{Conn c=new Conn(u,null);c.status=401;c.challenge=challenge;return c;});throw new AssertionError("expected refused");}
            catch(WorkBuddyAllowance.Failure e){check(e.invalidToken==(challenge!=null),"renew only explicit invalid-token");}
        }
        JSONObject p=new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(account("a")).put(account("b")).put(account("key").put("type","api")));
        JSONObject cfg=new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p));
        GatewayServer s=RoutingSetupOfflineTest.server(cfg);int[] count={0};
        s.setVendorConnectionsForTests(u->{try{count[0]++;return new Conn(u,sum(pkg(100,0,100))){public int getResponseCode(){return "uid-b".equals(getRequestProperty("X-User-Id"))?503:200;}};}catch(Exception e){throw new IOException(e);}});
        JSONObject result=ask(s,"workbuddy");JSONArray rows=result.getJSONArray("accounts");
        check(count[0]==2,"actual meter for each oauth, never static key");
        check(rows.length()==3 && rows.getJSONObject(0).getJSONObject("allowance").getDouble("remaining")==100,"actual gateway success instead of not_provided");
        check(rows.getJSONObject(0).getJSONObject("allowance").getJSONArray("windows").getJSONObject(0).getDouble("remaining")==100,"safe allowance retains credit windows");
        check(rows.getJSONObject(1).getString("errorKind").equals("unavailable"),"partial error independent");
        check(rows.getJSONObject(2).getString("errorKind").equals("key_not_supported"),"static key explicit unsupported");
        check(!result.toString().contains("fixture-") && !result.toString().contains("refresh-"),"no credentials returned");
        JSONObject rp=new JSONObject().put("id","workbuddy").put("models",new JSONArray()).put("accounts",new JSONArray().put(account("renew")));
        GatewayServer renew=RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(rp)));
        java.nio.file.Path testDir=java.nio.file.Files.createTempDirectory("workbuddy-meter-renew-");
        ResponsesGatewayOfflineTest.set(renew,"configFile",testDir.resolve("config.json").toFile());
        int[] exchanges={0},reads={0};
        renew.setTokenRefreshForTests((before,force)->{exchanges[0]++;return new JSONObject(before.toString()).put("access","renewed").put("token","renewed").put("refresh","new-refresh").put("expires",System.currentTimeMillis()+7200000);});
        renew.setVendorConnectionsForTests(u->{try{return new Conn(u,sum(pkg(10,2,8))){
            public int getResponseCode(){reads[0]++;return "Bearer renewed".equals(getRequestProperty("Authorization"))?200:401;}
            public String getHeaderField(String k){return "Bearer error=invalid_token";}
        };}catch(Exception e){throw new IOException(e);}});
        JSONObject renewed=ask(renew,"workbuddy");
        check(reads[0]==2 && exchanges[0]==1,"one coordinated renewal and one read retry: reads="+reads[0]+", exchanges="+exchanges[0]+", result="+renewed);
        check(renewed.getJSONArray("accounts").length()==1 && renewed.getJSONArray("accounts").getJSONObject(0).getJSONObject("allowance").getDouble("remaining")==8,"renewed account snapshot not discarded");
        renewed=ask(renew,"workbuddy");
        check(exchanges[0]==1 && renewed.getJSONArray("accounts").length()==1,"renewal persisted, no repeated exchange");

        // Regression 1: billingBusy is per-GatewayServer instance (stopped old instance's stuck worker must NOT block new instance)
        testBillingBusyIsolatedAcrossInstances();
        // Regression 2: displayName cache invalidates on same-UID re-login nickname update, UID replacement, and rejects late slow-query writeback
        testDisplayNameCacheInvalidationAndLateWritebackProtection();

        System.out.println("WorkBuddyAllowanceRegressionTest PASS: "+checks+" checks");
    }

    private static void testBillingBusyIsolatedAcrossInstances() throws Exception {
        JSONObject p1 = new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(account("a")));
        GatewayServer oldSrv = RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p1)));
        java.util.concurrent.CountDownLatch enteredOld = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseOld = new java.util.concurrent.CountDownLatch(1);
        oldSrv.setVendorConnectionsForTests(u -> {
            try {
                return new Conn(u, sum(pkg(50, 0, 50))) {
                    @Override public int getResponseCode() {
                        enteredOld.countDown();
                        // Simulate an uninterruptible blocking socket read that stays alive briefly after stop()
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        while (releaseOld.getCount() > 0 && System.nanoTime() < deadline) {
                            try { releaseOld.await(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { /* intentionally ignore interrupt to keep worker alive */ }
                        }
                        return 200;
                    }
                };
            } catch (Exception e) { throw new IOException(e); }
        });

        final int[] oldStatus = {0};
        Thread oldReq = new Thread(() -> {
            try {
                fi.iki.elonen.NanoHTTPD.Response r = oldSrv.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth","workbuddy"));
                oldStatus[0] = r.getStatus().getRequestStatus();
            } catch (Exception ignored) {}
        });
        oldReq.start();
        check(enteredOld.await(3, java.util.concurrent.TimeUnit.SECONDS), "old billing worker entered blocking read");

        // Same instance concurrent call must still be rejected with 409 Provider busy
        fi.iki.elonen.NanoHTTPD.Response sameBusy = oldSrv.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth","workbuddy"));
        check(sameBusy.getStatus().getRequestStatus() == 409, "same instance concurrent billing stays 409 busy");

        // Stop old gateway while its worker thread is STILL blocked
        oldSrv.stop();
        oldReq.join(3000);
        check(oldStatus[0] == 503, "stopped old gateway request returned 503, got " + oldStatus[0]);

        // New gateway instance must succeed (200) immediately even though old worker has NOT exited yet
        JSONObject p2 = new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(account("a")));
        GatewayServer newSrv = RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p2)));
        newSrv.setVendorConnectionsForTests(u -> {
            try { return new Conn(u, sum(pkg(80, 10, 70))); } catch (Exception e) { throw new IOException(e); }
        });
        fi.iki.elonen.NanoHTTPD.Response newResp = newSrv.serve(ProviderBillingGatewayHttpTest.request("127.0.0.1","GET","fixture-auth","workbuddy"));
        String newBody = ResponsesGatewayOfflineTest.read(newResp.getData());
        check(newResp.getStatus().getRequestStatus() == 200, "new gateway instance not blocked by old instance worker: " + newResp.getStatus() + " " + newBody);
        check(new JSONObject(newBody).getJSONArray("accounts").getJSONObject(0).getJSONObject("allowance").getDouble("remaining") == 70, "new instance returned live allowance");

        releaseOld.countDown();
        newSrv.stop();
    }

    private static void testDisplayNameCacheInvalidationAndLateWritebackProtection() throws Exception {
        // 1. Same account ID & UID re-login with updated nickname in config -> must return new nickname immediately
        JSONObject acc = account("wb1").put("uid", "uid-same").put("nickname", "OldNick");
        JSONObject prov = new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(acc));
        JSONObject cfg = new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(prov));
        GatewayServer srv = RoutingSetupOfflineTest.server(cfg);
        srv.setVendorConnectionsForTests(u -> {
            try { return new Conn(u, sum(pkg(100, 0, 100))); } catch (Exception e) { throw new IOException(e); }
        });
        JSONObject r1 = ask(srv, "workbuddy");
        check("OldNick".equals(r1.getJSONArray("accounts").getJSONObject(0).optString("displayName")), "initial nickname cached");

        // Re-login same ID & UID with updated nickname
        acc.put("nickname", "NewNickSameUid");
        JSONObject r2 = ask(srv, "workbuddy");
        check("NewNickSameUid".equals(r2.getJSONArray("accounts").getJSONObject(0).optString("displayName")),
                "same-UID nickname update must invalidate old cache: " + r2);

        // Replace config keeping same account ID but different UID (and no local nickname -> fetches upstream nickname)
        JSONObject replaced = account("wb1").put("uid", "uid-replaced").put("nickname", "");
        prov.put("accounts", new JSONArray().put(replaced));
        srv.setVendorConnectionsForTests(u -> {
            try {
                if (u.contains("/v2/plugin/accounts")) {
                    return new Conn(u, new JSONObject().put("accounts", new JSONArray()
                            .put(new JSONObject().put("uid", "uid-replaced").put("nickname", "ReplacedUidNick"))));
                }
                return new Conn(u, sum(pkg(100, 0, 100)));
            } catch (Exception e) { throw new IOException(e); }
        });
        JSONObject r3 = ask(srv, "workbuddy");
        check("ReplacedUidNick".equals(r3.getJSONArray("accounts").getJSONObject(0).optString("displayName")),
                "UID replacement with same account ID must not show stale nickname: " + r3);

        // 2. Late slow nickname query started BEFORE account update must NOT overwrite cache with stale name
        JSONObject slowAcc = account("wb-slow").put("uid", "uid-slow").put("nickname", "");
        JSONObject slowProv = new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(slowAcc));
        GatewayServer slowSrv = RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(slowProv)));
        java.util.concurrent.CountDownLatch enteredSlowNick = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSlowNick = new java.util.concurrent.CountDownLatch(1);
        final String[] upstreamNick = {"StaleSlowNick"};
        slowSrv.setVendorConnectionsForTests(u -> {
            try {
                if (u.contains("/v2/plugin/accounts")) {
                    final String replyNick = upstreamNick[0];
                    return new Conn(u, new JSONObject().put("accounts", new JSONArray()
                            .put(new JSONObject().put("uid", "StaleSlowNick".equals(replyNick) ? "uid-slow" : "uid-slow-v2").put("nickname", replyNick)))) {
                        @Override public int getResponseCode() {
                            if ("StaleSlowNick".equals(replyNick)) {
                                enteredSlowNick.countDown();
                                try { releaseSlowNick.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                            return 200;
                        }
                    };
                }
                return new Conn(u, sum(pkg(100, 0, 100)));
            } catch (Exception e) { throw new IOException(e); }
        });

        // Start a slow resolveBillingDisplayName call on the old account snapshot
        java.lang.reflect.Method resolveMethod = GatewayServer.class.getDeclaredMethod("resolveBillingDisplayName", String.class, JSONObject.class, boolean.class);
        resolveMethod.setAccessible(true);
        final JSONObject oldSnapshot = new JSONObject(slowAcc.toString());
        final String[] lateReturn = {""};
        Thread slowThread = new Thread(() -> {
            try { lateReturn[0] = (String) resolveMethod.invoke(slowSrv, "workbuddy", oldSnapshot, true); }
            catch (Exception ignored) {}
        });
        slowThread.start();
        check(enteredSlowNick.await(3, java.util.concurrent.TimeUnit.SECONDS), "slow nickname fetch in flight");

        // While slow fetch is in flight, account re-logs in with new UID (or updated nickname)
        slowAcc.put("uid", "uid-slow-v2");
        upstreamNick[0] = "FreshNickV2";

        // Now let the old slow fetch complete
        releaseSlowNick.countDown();
        slowThread.join(3000);
        check(lateReturn[0].isEmpty(), "late slow query after account change must not return stale name: " + lateReturn[0]);

        // Subsequent billing query must see FreshNickV2, never StaleSlowNick
        JSONObject rFresh = ask(slowSrv, "workbuddy");
        check("FreshNickV2".equals(rFresh.getJSONArray("accounts").getJSONObject(0).optString("displayName")),
                "late slow query must not poison cache with stale nickname: " + rFresh);

        // 3. Real addWorkBuddyAccount re-login path: same account ID & UID, empty nickname, credentials-only update.
        // A delayed nickname query started BEFORE re-login must NOT write stale nickname back;
        // the next billing query must re-fetch upstream and return the new nickname.
        JSONObject reloginAcc = account("wb-relogin").put("uid", "uid-relogin-same").put("nickname", "");
        JSONObject reloginProv = new JSONObject().put("id","workbuddy").put("enabled",true).put("models",new JSONArray()).put("accounts",new JSONArray().put(reloginAcc));
        GatewayServer reloginSrv = RoutingSetupOfflineTest.server(new JSONObject().put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(reloginProv)));
        java.nio.file.Path reloginDir = java.nio.file.Files.createTempDirectory("wb-relogin-nick-");
        ResponsesGatewayOfflineTest.set(reloginSrv, "configFile", reloginDir.resolve("config.json").toFile());
        java.util.concurrent.CountDownLatch enteredPreReloginNick = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releasePreReloginNick = new java.util.concurrent.CountDownLatch(1);
        final int[] nickFetches = {0};
        reloginSrv.setVendorConnectionsForTests(u -> {
            try {
                if (u.contains("/v2/plugin/accounts")) {
                    final int callNo;
                    synchronized (nickFetches) { callNo = ++nickFetches[0]; }
                    if (callNo == 1) {
                        return new Conn(u, new JSONObject().put("accounts", new JSONArray()
                                .put(new JSONObject().put("uid", "uid-relogin-same").put("nickname", "PreReloginStaleNick")))) {
                            @Override public int getResponseCode() {
                                enteredPreReloginNick.countDown();
                                try { releasePreReloginNick.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                return 200;
                            }
                        };
                    }
                    return new Conn(u, new JSONObject().put("accounts", new JSONArray()
                            .put(new JSONObject().put("uid", "uid-relogin-same").put("nickname", "PostReloginFreshNick"))));
                }
                return new Conn(u, sum(pkg(100, 0, 100)));
            } catch (Exception e) { throw new IOException(e); }
        });

        final JSONObject preReloginSnapshot = new JSONObject(reloginAcc.toString());
        final String[] preReloginReturn = {""};
        Thread preReloginThread = new Thread(() -> {
            try { preReloginReturn[0] = (String) resolveMethod.invoke(reloginSrv, "workbuddy", preReloginSnapshot, true); }
            catch (Exception ignored) {}
        });
        preReloginThread.start();
        check(enteredPreReloginNick.await(3, java.util.concurrent.TimeUnit.SECONDS), "pre-relogin nickname query in flight");

        // Actual addWorkBuddyAccount re-login save path: same UID, empty nickname, new credentials only
        java.lang.reflect.Method addWbMethod = GatewayServer.class.getDeclaredMethod("addWorkBuddyAccount", String.class, JSONObject.class);
        addWbMethod.setAccessible(true);
        JSONObject incomingRelogin = new JSONObject()
                .put("uid", "uid-relogin-same").put("name", "wb-relogin").put("nickname", "")
                .put("type", "oauth").put("access", "fixture-wb-relogin-v2").put("token", "fixture-wb-relogin-v2")
                .put("refresh", "refresh-wb-relogin-v2").put("expires", System.currentTimeMillis() + 7200000)
                .put("domain", "copilot.tencent.com");
        String savedId = (String) addWbMethod.invoke(reloginSrv, "workbuddy", incomingRelogin);
        check("wb-relogin".equals(savedId), "addWorkBuddyAccount preserved existing account id on same-UID re-login: " + savedId);

        // Now let the pre-relogin delayed nickname query finish -> must be rejected and NOT cached
        releasePreReloginNick.countDown();
        preReloginThread.join(3000);
        check(preReloginReturn[0].isEmpty(), "delayed pre-relogin query must not write or return stale nickname: " + preReloginReturn[0]);

        // Next billing query must re-fetch upstream (nickFetches == 2) and show PostReloginFreshNick
        JSONObject rPostRelogin = ask(reloginSrv, "workbuddy");
        check(nickFetches[0] == 2, "next billing query must re-fetch upstream nickname after re-login, fetches=" + nickFetches[0]);
        check("PostReloginFreshNick".equals(rPostRelogin.getJSONArray("accounts").getJSONObject(0).optString("displayName")),
                "next billing query must display fresh post-relogin nickname: " + rPostRelogin);

        // 4. Growth tasks status endpoint (/api/workbuddy/tasks/status) dual-account TOCTOU race:
        // Block first account's nickname lookup, re-login second account (same ID & UID, empty nickname, new credentials),
        // then unblock. Second account's stale snapshot must NOT poison the shared cache;
        // next billing query must re-fetch upstream with the new credentials and display the fresh nickname.
        JSONObject wb1 = account("wb-dual-1").put("uid", "uid-dual-1").put("nickname", "");
        JSONObject wb2 = account("wb-dual-2").put("uid", "uid-dual-2").put("nickname", "")
                .put("access", "fixture-dual-2-v1").put("token", "fixture-dual-2-v1");
        JSONObject dualProv = new JSONObject().put("id", "workbuddy").put("enabled", true).put("models", new JSONArray())
                .put("accounts", new JSONArray().put(wb1).put(wb2));
        GatewayServer dualSrv = RoutingSetupOfflineTest.server(new JSONObject()
                .put("setup", new JSONObject().put("done", true))
                .put("providers", new JSONArray().put(dualProv)));
        java.nio.file.Path dualDir = java.nio.file.Files.createTempDirectory("wb-dual-tasks-nick-");
        ResponsesGatewayOfflineTest.set(dualSrv, "configFile", dualDir.resolve("config.json").toFile());
        java.util.concurrent.CountDownLatch enteredFirstNick = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstNick = new java.util.concurrent.CountDownLatch(1);
        final int[] secondNewCredNickFetches = {0};
        dualSrv.setVendorConnectionsForTests(u -> {
            try {
                if (u.contains("/v2/plugin/accounts")) {
                    return new Conn(u, new JSONObject()) {
                        private String targetUid = "";
                        private String authHeader = "";
                        @Override public void setRequestProperty(String key, String value) {
                            super.setRequestProperty(key, value);
                            if ("X-User-Id".equalsIgnoreCase(key)) targetUid = value;
                            if ("Authorization".equalsIgnoreCase(key)) authHeader = value;
                        }
                        @Override public int getResponseCode() {
                            if ("uid-dual-1".equals(targetUid)) {
                                enteredFirstNick.countDown();
                                try { releaseFirstNick.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            } else if ("uid-dual-2".equals(targetUid) && "Bearer fixture-dual-2-v2".equals(authHeader)) {
                                synchronized (secondNewCredNickFetches) { secondNewCredNickFetches[0]++; }
                            }
                            return 200;
                        }
                        @Override public InputStream getInputStream() {
                            String nick;
                            if ("uid-dual-1".equals(targetUid)) {
                                nick = "FirstDualNick";
                            } else if ("Bearer fixture-dual-2-v2".equals(authHeader)) {
                                nick = "FreshSecondDualNick";
                            } else {
                                nick = "StaleSecondDualNick";
                            }
                            JSONObject body = new JSONObject().put("code", 0).put("data", new JSONObject().put("accounts",
                                    new JSONArray().put(new JSONObject().put("uid", targetUid).put("nickname", nick))));
                            return new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
                return new Conn(u, sum(pkg(100, 0, 100)));
            } catch (Exception e) { throw new IOException(e); }
        });

        final int[] statusHttp = {0};
        Thread tasksStatusThread = new Thread(() -> {
            try {
                fi.iki.elonen.NanoHTTPD.IHTTPSession statusReq = RoutingSetupOfflineTest.request(
                        "127.0.0.1", "/api/workbuddy/tasks/status", "GET", "", GatewayManagementCredential.get());
                fi.iki.elonen.NanoHTTPD.Response resp = dualSrv.serve(statusReq);
                statusHttp[0] = resp.getStatus().getRequestStatus();
                ResponsesGatewayOfflineTest.read(resp.getData());
            } catch (Exception ignored) {}
        });
        tasksStatusThread.start();
        check(enteredFirstNick.await(3, java.util.concurrent.TimeUnit.SECONDS), "first account nickname lookup blocked in /api/workbuddy/tasks/status");

        // While first account is blocked, re-login second account (same ID & UID, empty nickname, new credentials only)
        JSONObject dualRelogin = new JSONObject()
                .put("uid", "uid-dual-2").put("name", "wb-dual-2").put("nickname", "")
                .put("type", "oauth").put("access", "fixture-dual-2-v2").put("token", "fixture-dual-2-v2")
                .put("refresh", "refresh-dual-2-v2").put("expires", System.currentTimeMillis() + 7200000)
                .put("domain", "copilot.tencent.com");
        String dualSavedId = (String) addWbMethod.invoke(dualSrv, "workbuddy", dualRelogin);
        check("wb-dual-2".equals(dualSavedId), "dual re-login preserved wb-dual-2 id: " + dualSavedId);

        // Unblock first account and wait for /api/workbuddy/tasks/status to finish
        releaseFirstNick.countDown();
        tasksStatusThread.join(3000);
        check(statusHttp[0] == 200, "/api/workbuddy/tasks/status completed with 200, got " + statusHttp[0]);

        // Next billing query must fetch wb-dual-2 upstream using new credentials and show FreshSecondDualNick
        JSONObject dualBilling = ask(dualSrv, "workbuddy");
        JSONArray dualRows = dualBilling.getJSONArray("accounts");
        String secondDisplay = "";
        for (int i = 0; i < dualRows.length(); i++) {
            JSONObject row = dualRows.getJSONObject(i);
            if ("wb-dual-2".equals(row.optString("accountId"))) {
                secondDisplay = row.optString("displayName", "");
            }
        }
        check(secondNewCredNickFetches[0] == 1, "billing must re-fetch wb-dual-2 upstream with new credentials, count=" + secondNewCredNickFetches[0]);
        check("FreshSecondDualNick".equals(secondDisplay), "wb-dual-2 must show fresh nickname after dual-account race: " + dualBilling);

        srv.stop();
        slowSrv.stop();
        reloginSrv.stop();
        dualSrv.stop();
    }
}
