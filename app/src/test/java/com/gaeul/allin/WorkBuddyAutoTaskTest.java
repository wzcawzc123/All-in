package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WorkBuddyAutoTaskTest {
    private static int checks = 0;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL: " + msg + " expected=" + expected + " actual=" + actual);
        }
    }

    private static final class FakeConn extends HttpURLConnection {
        private final int status;
        private final byte[] respBytes;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        FakeConn(String url, int status, String body) throws IOException {
            super(new URL(url));
            this.status = status;
            this.respBytes = body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public int getResponseCode() { return status; }
        @Override public OutputStream getOutputStream() { return written; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(respBytes); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(respBytes); }
    }

    public void eligibilityFiltersProviderDisabledAccountDisabledGlobalEnterpriseApiKey() {
        JSONObject personal = new JSONObject().put("type", "oauth").put("access", "tok_ok").put("uid", "u1");
        JSONObject disabled = new JSONObject().put("type", "oauth").put("access", "tok_ok").put("uid", "u1").put("enabled", false);
        JSONObject apiKey = new JSONObject().put("type", "api").put("access", "ck_secret").put("uid", "u2");
        JSONObject enterprise = new JSONObject().put("type", "oauth").put("access", "tok_ent").put("enterpriseId", "ent_1");
        check(WorkBuddyAutoTask.isEligible("workbuddy", personal), "personal workbuddy eligible");
        check(!WorkBuddyAutoTask.isEligible(new WorkBuddyAutoTask.AccountEntry("workbuddy", "a1", "HX", personal, false)), "provider disabled ineligible");
        check(!WorkBuddyAutoTask.isEligible("workbuddy", disabled), "disabled account ineligible");
        check(!WorkBuddyAutoTask.isEligible("workbuddy-ai", personal), "global ineligible");
        check(!WorkBuddyAutoTask.isEligible("workbuddy", apiKey), "apiKey ineligible");
        check(!WorkBuddyAutoTask.isEligible("workbuddy", enterprise), "enterprise ineligible");
    }

    public void perAccountFingerprintsAreDeterministicAndDistinct() {
        String a1 = WorkBuddyAutoTask.deriveHex36("uid_alpha", "machine");
        String a2 = WorkBuddyAutoTask.deriveHex36("uid_alpha", "machine");
        String b1 = WorkBuddyAutoTask.deriveHex36("uid_beta", "machine");
        checkEq(36, a1.length(), "hex36 len");
        checkEq(a1, a2, "hex36 deterministic");
        check(!a1.equals(b1), "hex36 distinct");
        String u1 = WorkBuddyAutoTask.deriveUuid("uid_alpha", "mp-machine");
        String u2 = WorkBuddyAutoTask.deriveUuid("uid_beta", "mp-machine");
        check(!u1.equals(u2), "uuid distinct");
        checkEq(36, u1.length(), "uuid len");
    }

    public void claimOnlyAndTier2GateWorkCorrectly() throws Exception {
        List<String> calls = new ArrayList<>();
        WorkBuddyAutoTask.Client client = new WorkBuddyAutoTask.Client(url -> {
            calls.add(url);
            if (url.contains("/claim")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":100,\"energy\":5}}");
            }
            if (url.contains("/v2/chat/completions")) {
                return new FakeConn(url, 200, "data: {\"id\":\"cmb-0123456789abcdef0123456789abcdef\"}\n\n");
            }
            if (url.contains("/v2/activity/growth/tasks")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"skill_1\",\"current\":1,\"target\":1,\"accept_status\":\"completed\"}]}}");
            }
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
        });
        client.reportGapMs = 0;
        client.pollGapMs = 0;
        client.expertGapMs = 0;

        JSONObject acc = new JSONObject().put("type", "oauth").put("access", "tok_ok").put("uid", "u1").put("nickname", "HX");

        JSONObject dynTask = new JSONObject().put("task_code", "custom_future_task").put("title", "新活动")
                .put("claimable", true).put("claimed", false).put("locked", false);
        JSONObject r1 = WorkBuddyAutoTask.runSingleTask(client, acc, dynTask, false, true);
        checkEq("done", r1.optString("status"), "claimOnly done");
        checkEq(100L, r1.optLong("credit"), "claimOnly credit");
        check(calls.stream().noneMatch(u -> u.contains("/v2/report")), "claimOnly never reports");

        calls.clear();
        JSONObject skillTask = new JSONObject().put("task_code", "skill_1").put("title", "尝鲜热门技能")
                .put("claimable", false).put("claimed", false).put("locked", false);
        JSONObject r2 = WorkBuddyAutoTask.runSingleTask(client, acc, skillTask, false, false);
        checkEq("skipped", r2.optString("status"), "tier2 skipped when off");
        check(calls.isEmpty(), "tier2 off makes zero calls");

        JSONObject r3 = WorkBuddyAutoTask.runSingleTask(client, acc, skillTask, true, false);
        checkEq("done", r3.optString("status"), "tier2 done when on");
        checkEq(100L, r3.optLong("credit"), "tier2 credit");
        check(calls.stream().anyMatch(u -> u.contains("/v2/chat/completions")), "tier2 calls chat");
        check(calls.stream().anyMatch(u -> u.contains("/v2/report")), "tier2 calls report");
        check(calls.stream().anyMatch(u -> u.contains("/claim")), "tier2 calls claim");
    }

    public void claimValidationRejectsNullAndWrongTypesAllowsZeroDelta() throws Exception {
        JSONObject acc = new JSONObject().put("type", "oauth").put("access", "tok_ok").put("uid", "u1").put("nickname", "HX");
        String[] badPayloads = {
                "<html>login</html>",
                "{\"code\":0,\"data\":{}}",
                "{\"code\":0,\"data\":{\"credit\":null}}",
                "{\"code\":0,\"data\":{\"credit\":\"abc\"}}",
                "{\"code\":0,\"data\":{\"already_claimed\":\"yes\"}}"
        };
        for (String payload : badPayloads) {
            WorkBuddyAutoTask.Client c = new WorkBuddyAutoTask.Client(url -> new FakeConn(url, 200, payload));
            boolean threw = false;
            try { c.claimRewardWeb(acc, "chat_5"); } catch (IOException expected) { threw = true; }
            check(threw, "invalid claim payload must throw: " + payload);
        }

        // Valid zero-delta claims
        WorkBuddyAutoTask.Client cAlready = new WorkBuddyAutoTask.Client(
                url -> new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":true}}"));
        WorkBuddyAutoTask.ClaimResult cr1 = cAlready.claimRewardWeb(acc, "chat_5");
        check(cr1.alreadyClaimed && cr1.credit == 0 && cr1.energy == 0, "already_claimed=true accepted");

        WorkBuddyAutoTask.Client cZero = new WorkBuddyAutoTask.Client(
                url -> new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":0,\"energy\":0}}"));
        WorkBuddyAutoTask.ClaimResult cr2 = cZero.claimRewardWeb(acc, "chat_5");
        check(!cr2.alreadyClaimed && cr2.credit == 0 && cr2.energy == 0, "valid zero-delta claim accepted");
    }

    public void credentialSwapStopsSubsequentRequests() throws Exception {
        final String[] liveAccess = {"tok_v1"};
        List<String> calls = new ArrayList<>();
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth").put("access", liveAccess[0]).put("uid", "u1");
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, url -> {
            calls.add(url);
            if (url.contains("/v2/activity/growth/tasks")) {
                // Swap credential right after initial task list fetch
                liveAccess[0] = "tok_v2_swapped";
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"automation_1\",\"current\":0,\"target\":1,\"accept_status\":\"not_accepted\"}]}}");
            }
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
        });
        mgr.startJob("run", "acc1", false);
        waitUntilIdle(mgr);
        check(calls.stream().noneMatch(u -> u.contains("/accept") || u.contains("/v2/report") || u.contains("/claim")),
                "credential swap must prevent subsequent accept/report/claim");
        mgr.shutdown();
    }

    public void stopOnNetworkSnapshotFailureAndBlockingReadRecoversCleanly() throws Exception {
        final boolean[] throwNet = {true};
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth").put("access", "tok").put("uid", "u1");
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() {
                if (throwNet[0]) throw new RuntimeException("network snapshot unavailable");
                return NetworkPolicy.capture();
            }
        };

        CountDownLatch enteredRead = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, url -> {
            if (url.contains("/v2/activity/growth/tasks") && !throwNet[0]) {
                return new HttpURLConnection(new URL(url)) {
                    @Override public void disconnect() { disconnected.countDown(); }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                    @Override public int getResponseCode() { return 200; }
                    @Override public InputStream getInputStream() {
                        enteredRead.countDown();
                        return new InputStream() {
                            @Override public int read() throws IOException {
                                try {
                                    disconnected.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                throw new java.io.InterruptedIOException("socket disconnected");
                            }
                        };
                    }
                };
            }
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[]}}");
        });

        // 1. networkSnapshot() throws -> running must clear and allow restart
        mgr.startJob("run", "acc1", false);
        waitUntilIdle(mgr);
        check(!mgr.snapshot().getJSONObject("job").optBoolean("running", true), "running cleared after networkSnapshot failure");

        // 2. Restart succeeds, then stopJob() during blocking read disconnects socket and clears running
        throwNet[0] = false;
        JSONObject restarted = mgr.startJob("run", "acc1", false);
        check(restarted.optBoolean("ok", false), "manager can restart after failure");
        check(enteredRead.await(3, TimeUnit.SECONDS), "entered blocking read");
        mgr.stopJob();
        check(disconnected.await(3, TimeUnit.SECONDS), "stopJob disconnected active connection");
        waitUntilIdle(mgr);
        JSONObject job = mgr.snapshot().getJSONObject("job");
        check(!job.optBoolean("running", true), "running cleared after stopJob");
        check(job.optString("message", "").contains("结果未知"), "honest unknown-outcome message on in-flight stop");
        mgr.shutdown();
    }

    private static void waitUntilIdle(WorkBuddyAutoTask.Manager mgr) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (!mgr.snapshot().getJSONObject("job").optBoolean("running", false)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Manager did not return to idle");
    }

    public void hostContractExposesNoTokenRefreshMutation() {
        for (java.lang.reflect.Method m : WorkBuddyAutoTask.AccountHost.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            check(!name.contains("refresh") && !name.contains("token"),
                    "AccountHost must not expose token refresh: " + m.getName());
        }
    }

    /**
     * Scenario 1: Disabling the account after first_buddy's initial /v2/report
     * must block subsequent buddy/agreement and buddy/first writes.
     */
    public void firstBuddyDisablingMidTaskBlocksAgreementAndFirstWrites() throws Exception {
        final boolean[] enabled = {true};
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth")
                        .put("access", "tok_live").put("uid", "u1").put("enabled", enabled[0]);
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, url -> {
            calls.add(url);
            if (url.contains("/v2/activity/growth/tasks/accept")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
            }
            if (url.contains("/v2/activity/growth/tasks")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"first_buddy\",\"title\":\"领养第一只 Buddy\",\"current\":0,\"target\":1,\"accept_status\":\"accepted\",\"claimed\":false,\"claimable\":false}]}}");
            }
            if (url.contains("/v2/report")) {
                // Disable account right after first_buddy's initial report completes
                enabled[0] = false;
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
            }
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
        });
        mgr.startJob("run", "acc1", false);
        waitUntilIdle(mgr);
        check(calls.stream().anyMatch(u -> u.contains("/v2/report")), "first_buddy initial report ran");
        check(calls.stream().noneMatch(u -> u.contains("/activity/growth/buddy/agreement")),
                "disabled account must NOT send buddy/agreement write");
        check(calls.stream().noneMatch(u -> u.contains("/activity/growth/buddy/first")),
                "disabled account must NOT send buddy/first write");
        mgr.shutdown();
    }

    /**
     * Scenario 2: Clicking stop after claim POST is sent while disconnect throws plain IOException
     * must report unknown outcome ('结果未知，请刷新核对') and preserve previously confirmed credits.
     */
    public void stopDuringClaimPostPlainIoExceptionReportsUnknownAndKeepsConfirmedCredit() throws Exception {
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth").put("access", "tok").put("uid", "u1");
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        CountDownLatch enteredSecondClaim = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, url -> {
            if (url.contains("/v2/activity/growth/tasks")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":["
                        + "{\"task_code\":\"chat_5\",\"title\":\"对话活跃 ×5\",\"current\":5,\"target\":5,\"claimable\":true,\"claimed\":false},"
                        + "{\"task_code\":\"automation_1\",\"title\":\"创建定时任务\",\"current\":1,\"target\":1,\"claimable\":true,\"claimed\":false}"
                        + "]}}");
            }
            if (url.contains("/chat_5/claim")) {
                return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":100,\"energy\":5}}");
            }
            if (url.contains("/automation_1/claim")) {
                return new HttpURLConnection(new URL(url)) {
                    @Override public void disconnect() { disconnected.countDown(); }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    @Override public int getResponseCode() throws IOException {
                        enteredSecondClaim.countDown();
                        try { disconnected.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        // Plain IOException (NOT InterruptedIOException), as HttpURLConnection.disconnect() often throws
                        throw new IOException("Socket closed");
                    }
                };
            }
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{}}");
        });
        mgr.startJob("claim_only", "acc1", false);
        check(enteredSecondClaim.await(3, TimeUnit.SECONDS), "entered second claim POST");
        mgr.stopJob();
        check(disconnected.await(3, TimeUnit.SECONDS), "stopJob disconnected claim POST");
        waitUntilIdle(mgr);
        JSONObject job = mgr.snapshot().getJSONObject("job");
        String msg = job.optString("message", "");
        check(msg.contains("结果未知，请刷新核对"), "plain IOException on stopped claim POST must report unknown outcome: " + msg);
        check(job.optLong("claimedCredit", -1) == 100L && msg.contains("+100 积分"),
                "confirmed credit from first claim must be preserved: " + job);
        mgr.shutdown();
    }

    /**
     * Scenario 3: HTTP 200 with code=0 and already_claimed=false but NO credit/energy field
     * must NOT mark claimed=true unless upstream re-read confirms claimed=true.
     */
    public void claimHttp200AlreadyClaimedFalseWithoutRewardStaysUnconfirmedUntilReRead() throws Exception {
        JSONObject acc = new JSONObject().put("type", "oauth").put("access", "tok").put("uid", "u1");
        WorkBuddyAutoTask.Client cAmbiguous = new WorkBuddyAutoTask.Client(
                url -> new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false}}"));
        boolean threwUnconfirmed = false;
        try {
            cAmbiguous.claimRewardWeb(acc, "chat_5");
        } catch (WorkBuddyAutoTask.ClaimUnconfirmedException expected) {
            threwUnconfirmed = true;
        }
        check(threwUnconfirmed, "already_claimed=false without credit/energy must throw ClaimUnconfirmedException");

        // runSingleTask when re-read still shows unclaimed -> must stay awaiting / claimed=false
        WorkBuddyAutoTask.Client cStillPending = new WorkBuddyAutoTask.Client(url -> {
            if (url.contains("/claim")) return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false}}");
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"chat_5\",\"accept_status\":\"accepted\",\"current\":5,\"target\":5}]}}");
        });
        JSONObject resPending = WorkBuddyAutoTask.runSingleTask(cStillPending, acc,
                new JSONObject().put("task_code", "chat_5").put("title", "对话活跃").put("claimable", true), false, true, () -> true);
        check(!resPending.optBoolean("claimed", true) && "awaiting".equals(resPending.optString("status")),
                "ambiguous claim without upstream confirmation must stay awaiting/unclaimed: " + resPending);

        // runSingleTask when re-read confirms claimed -> marked claimed=true
        WorkBuddyAutoTask.Client cConfirmedOnReRead = new WorkBuddyAutoTask.Client(url -> {
            if (url.contains("/claim")) return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"already_claimed\":false}}");
            return new FakeConn(url, 200, "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"chat_5\",\"accept_status\":\"claimed\",\"current\":5,\"target\":5}]}}");
        });
        JSONObject resConfirmed = WorkBuddyAutoTask.runSingleTask(cConfirmedOnReRead, acc,
                new JSONObject().put("task_code", "chat_5").put("title", "对话活跃").put("claimable", true), false, true, () -> true);
        check(resConfirmed.optBoolean("claimed", false) && "done".equals(resConfirmed.optString("status")),
                "ambiguous claim confirmed by upstream re-read becomes done/claimed: " + resConfirmed);
    }

    /** Verifies Tier 2 SSE requestId extraction aligns with workbuddy2api-panel desktop.go (first matching cmb-/32hex id). */
    public void tier2SseExtractsFirstValidServerRequestId() throws Exception {
        JSONObject acc = new JSONObject().put("type", "oauth").put("access", "tok").put("uid", "u1").put("domain", "copilot.tencent.com");
        String validId = "cmb-0123456789abcdef0123456789abcdef";
        String sse = "data: {\"id\":\"msg-ignored\",\"choices\":[]}\n\n"
                + "data: {\"id\":\"" + validId + "\",\"choices\":[{\"delta\":{\"content\":\"2\"}}]}\n\n";
        WorkBuddyAutoTask.Client client = new WorkBuddyAutoTask.Client(url -> new FakeConn(url, 200, sse));
        String[] pair = client.desktopChatWithExpert(acc, "ex_test");
        check(pair != null && pair.length == 2 && validId.equals(pair[1]), "extracts first matching server requestId without waiting for [DONE]");
    }

    /** Multi-account batch: disabling account A after list/accept must only skip A and still run account B. */
    public void multiAccountDisablingFirstAccountStillRunsSecondAccount() throws Exception {
        final boolean[] accAEnabled = {true};
        List<String> claimUids = Collections.synchronizedList(new ArrayList<>());
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                List<WorkBuddyAutoTask.AccountEntry> out = new ArrayList<>();
                JSONObject a = new JSONObject().put("id", "accA").put("type", "oauth").put("access", "tokA").put("uid", "uidA").put("enabled", accAEnabled[0]);
                JSONObject b = new JSONObject().put("id", "accB").put("type", "oauth").put("access", "tokB").put("uid", "uidB").put("enabled", true);
                out.add(new WorkBuddyAutoTask.AccountEntry("workbuddy", "accA", "AccountA", a, true));
                out.add(new WorkBuddyAutoTask.AccountEntry("workbuddy", "accB", "AccountB", b, true));
                return out;
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, reqUrl -> new HttpURLConnection(new URL(reqUrl)) {
            private final String body = reqUrl.contains("/claim")
                    ? "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":100,\"energy\":0}}"
                    : "{\"code\":0,\"data\":{\"tasks\":[{\"task_code\":\"chat_5\",\"title\":\"对话活跃 ×5\",\"current\":5,\"target\":5,\"accept_status\":\"accepted\"}]}}";
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public int getResponseCode() {
                String uid = getRequestProperty("X-User-Id");
                if (reqUrl.contains("/v2/activity/growth/tasks") && "uidA".equals(uid)) {
                    accAEnabled[0] = false;
                }
                if (reqUrl.contains("/claim")) {
                    claimUids.add(uid);
                }
                return 200;
            }
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        mgr.startJob("claim_only", "", false);
        waitUntilIdle(mgr);
        check(!claimUids.contains("uidA"), "disabled account A must not send claim");
        check(claimUids.contains("uidB"), "healthy account B must still run and claim after account A disabled");
        check(mgr.snapshot().getJSONObject("job").optLong("claimedCredit", 0) == 100L, "account B credit recorded");
        mgr.shutdown();
    }

    /**
     * Regression 1: Triggering concurrent refresh() while a claim POST is waiting for response
     * must NOT hijack stopJob(): stopJob() must disconnect the job's own claim connection,
     * report unknown outcome ('结果未知'), preserve previously confirmed credits, and return to idle.
     */
    public void concurrentRefreshDuringBlockedClaimDoesNotHijackStop() throws Exception {
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth").put("access", "tok").put("uid", "u1");
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        CountDownLatch enteredBlockedClaim = new CountDownLatch(1);
        CountDownLatch claimDisconnected = new CountDownLatch(1);
        CountDownLatch enteredConcurrentRefresh = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        final boolean[] refreshDisconnectedByStop = {false};
        final int[] listCalls = {0};

        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, reqUrl -> {
            if (reqUrl.contains("/v2/activity/growth/tasks")) {
                int callNum;
                synchronized (listCalls) { callNum = ++listCalls[0]; }
                // Calls 1 & 2 are the job's own listMergedTasks (normal + mp); call 3 is the concurrent refresh("")
                if (callNum >= 3) {
                    return new HttpURLConnection(new URL(reqUrl)) {
                        @Override public void disconnect() {
                            if (releaseRefresh.getCount() > 0) refreshDisconnectedByStop[0] = true;
                        }
                        @Override public boolean usingProxy() { return false; }
                        @Override public void connect() {}
                        @Override public int getResponseCode() throws IOException {
                            enteredConcurrentRefresh.countDown();
                            try { releaseRefresh.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            return 200;
                        }
                        @Override public InputStream getInputStream() {
                            return new ByteArrayInputStream("{\"code\":0,\"data\":{\"tasks\":[]}}".getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
                return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{\"tasks\":["
                        + "{\"task_code\":\"chat_5\",\"title\":\"对话活跃 ×5\",\"current\":5,\"target\":5,\"accept_status\":\"accepted\"},"
                        + "{\"task_code\":\"automation_1\",\"title\":\"创建定时任务\",\"current\":1,\"target\":1,\"accept_status\":\"accepted\"}"
                        + "]}}");
            }
            if (reqUrl.contains("/chat_5/claim")) {
                return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":100,\"energy\":0}}");
            }
            if (reqUrl.contains("/automation_1/claim")) {
                return new HttpURLConnection(new URL(reqUrl)) {
                    @Override public void disconnect() { claimDisconnected.countDown(); }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    @Override public int getResponseCode() throws IOException {
                        enteredBlockedClaim.countDown();
                        try { claimDisconnected.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        throw new IOException("Socket closed");
                    }
                };
            }
            return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{}}");
        });

        mgr.startJob("claim_only", "acc1", false);
        check(enteredBlockedClaim.await(3, TimeUnit.SECONDS), "entered second claim POST");

        Thread refreshThread = new Thread(() -> mgr.refresh(""));
        refreshThread.start();
        check(enteredConcurrentRefresh.await(3, TimeUnit.SECONDS), "concurrent refresh entered HTTP wait");

        mgr.stopJob();
        check(claimDisconnected.await(3, TimeUnit.SECONDS), "stopJob must disconnect the job's own claim connection");
        waitUntilIdle(mgr);
        check(!refreshDisconnectedByStop[0], "stopJob must NOT disconnect the concurrent refresh connection");

        releaseRefresh.countDown();
        refreshThread.join(3000);

        JSONObject job = mgr.snapshot().getJSONObject("job");
        String msg = job.optString("message", "");
        check(!job.optBoolean("running", true), "job returned to idle after stop");
        check(msg.contains("结果未知"), "stopped in-flight claim reports unknown outcome: " + msg);
        check(job.optLong("claimedCredit", 0) == 100L && msg.contains("+100 积分"),
                "confirmed credit from first claim preserved: " + job);
        mgr.shutdown();
    }

    /**
     * Regression 2: Plain SocketTimeoutException on first claim (without user stop)
     * must mark first claim as unknown outcome (no auto-retry), NOT treat as user stop,
     * and continue executing the second independent claim task.
     */
    public void socketTimeoutOnFirstClaimMarksUnknownAndContinuesSecondTask() throws Exception {
        WorkBuddyAutoTask.AccountHost host = new WorkBuddyAutoTask.AccountHost() {
            @Override public List<WorkBuddyAutoTask.AccountEntry> listWorkBuddyAccounts() {
                JSONObject a = new JSONObject().put("id", "acc1").put("type", "oauth").put("access", "tok").put("uid", "u1");
                return Collections.singletonList(new WorkBuddyAutoTask.AccountEntry("workbuddy", "acc1", "HX", a, true));
            }
            @Override public NetworkPolicy.Snapshot networkSnapshot() { return NetworkPolicy.capture(); }
        };
        final int[] firstClaimCalls = {0};
        final int[] secondClaimCalls = {0};
        WorkBuddyAutoTask.Manager mgr = new WorkBuddyAutoTask.Manager(host, reqUrl -> {
            if (reqUrl.contains("/v2/activity/growth/tasks")) {
                return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{\"tasks\":["
                        + "{\"task_code\":\"chat_5\",\"title\":\"对话活跃 ×5\",\"current\":5,\"target\":5,\"accept_status\":\"accepted\"},"
                        + "{\"task_code\":\"automation_1\",\"title\":\"创建定时任务\",\"current\":1,\"target\":1,\"accept_status\":\"accepted\"}"
                        + "]}}");
            }
            if (reqUrl.contains("/chat_5/claim")) {
                firstClaimCalls[0]++;
                return new HttpURLConnection(new URL(reqUrl)) {
                    @Override public void disconnect() {}
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    @Override public int getResponseCode() throws IOException {
                        throw new java.net.SocketTimeoutException("Read timed out");
                    }
                };
            }
            if (reqUrl.contains("/automation_1/claim")) {
                secondClaimCalls[0]++;
                return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{\"already_claimed\":false,\"credit\":200,\"energy\":5}}");
            }
            return new FakeConn(reqUrl, 200, "{\"code\":0,\"data\":{}}");
        });

        mgr.startJob("claim_only", "acc1", false);
        waitUntilIdle(mgr);
        JSONObject job = mgr.snapshot().getJSONObject("job");
        String msg = job.optString("message", "");
        check(firstClaimCalls[0] == 1, "timed-out first claim must NOT be auto-retried, calls=" + firstClaimCalls[0]);
        check(secondClaimCalls[0] == 1, "second independent claim must still execute after first SocketTimeoutException");
        check(job.optLong("claimedCredit", 0) == 200L, "second claim credit recorded: " + job);
        check(!msg.contains("手动停止"), "SocketTimeoutException must not be reported as manual stop: " + msg);
        check(msg.contains("结果未知") && msg.contains("+200 积分"),
                "final status must report unknown outcome for timed-out task and confirmed credit: " + msg);
        mgr.shutdown();
    }

    public static void main(String[] args) throws Exception {
        WorkBuddyAutoTaskTest t = new WorkBuddyAutoTaskTest();
        t.eligibilityFiltersProviderDisabledAccountDisabledGlobalEnterpriseApiKey();
        t.perAccountFingerprintsAreDeterministicAndDistinct();
        t.claimOnlyAndTier2GateWorkCorrectly();
        t.claimValidationRejectsNullAndWrongTypesAllowsZeroDelta();
        t.credentialSwapStopsSubsequentRequests();
        t.stopOnNetworkSnapshotFailureAndBlockingReadRecoversCleanly();
        t.hostContractExposesNoTokenRefreshMutation();
        t.firstBuddyDisablingMidTaskBlocksAgreementAndFirstWrites();
        t.stopDuringClaimPostPlainIoExceptionReportsUnknownAndKeepsConfirmedCredit();
        t.claimHttp200AlreadyClaimedFalseWithoutRewardStaysUnconfirmedUntilReRead();
        t.tier2SseExtractsFirstValidServerRequestId();
        t.multiAccountDisablingFirstAccountStillRunsSecondAccount();
        t.concurrentRefreshDuringBlockedClaimDoesNotHijackStop();
        t.socketTimeoutOnFirstClaimMarksUnknownAndContinuesSecondTask();
        System.out.println("WorkBuddyAutoTaskTest PASS checks=" + checks + " failures=0");
    }
}
