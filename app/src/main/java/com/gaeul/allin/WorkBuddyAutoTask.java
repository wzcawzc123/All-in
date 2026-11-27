package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Pure-Java Android mobile gateway runner for WorkBuddy (CN personal OAuth) growth tasks.
 * Isolated from chat/routing paths; strictly manual-triggered, single-thread sequential,
 * cancellable, and host-allowlisted (copilot.tencent.com / www.workbuddy.cn / www.codebuddy.cn).
 */
final class WorkBuddyAutoTask {
    static final String ORIGIN_CHAT = "https://copilot.tencent.com";
    static final String ORIGIN_WEB = "https://www.workbuddy.cn";
    static final String ORIGIN_BILLING = "https://www.codebuddy.cn";

    private static final String TASKS_LIST_PATH = "/v2/activity/growth/tasks";
    private static final String TASKS_ACCEPT_PATH = "/v2/activity/growth/tasks/accept";
    private static final String BUDDY_AGREEMENT_PATH = "/activity/growth/buddy/agreement";
    private static final String BUDDY_FIRST_PATH = "/activity/growth/buddy/first";
    private static final String REPORT_PATH = "/v2/report";
    private static final String APPEARANCE_SET_PATH = "/v2/user-asset/appearance/set";
    private static final String MARKET_EXPERT_LIST_PATH = "/portal/operation-platform/market/expert/list";
    private static final String CHAT_COMPLETIONS_PATH = "/v2/chat/completions";

    private static final String DESKTOP_VERSION_CN = "5.11.3";
    private static final String DESKTOP_COMMIT_CN = "ecace0f7028166695994300182295329b8b95449";
    private static final long DESKTOP_RELEASE_DATE_CN = 1794502169764L;
    private static final String DESKTOP_UA_CN = "WorkBuddy/5.11.3 WorkBuddy/5.11.3 CLI/2.176.0";
    private static final String MP_PLATFORM = "miniprogram";
    private static final String LIGHTHOUSE_EXPERT_ID = "ex_2cvvUZQhDyeJ";

    private static final Pattern SERVER_REQUEST_ID = Pattern.compile("^(cmb-)?[0-9a-f]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "copilot.tencent.com", "www.workbuddy.cn", "www.codebuddy.cn")));

    private static final Set<String> MP_TASK_CODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Sequential_Tasks_1", "Sequential_Tasks_2", "Sequential_Tasks_3",
            "Sequential_Tasks_4", "Sequential_Tasks_5", "Sequential_Tasks_6", "Sequential_Tasks_7",
            "wb_wechat_oa_subscribe_task")));

    private static final Set<String> MANUAL_ONLY_CODES = Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(
            "Expert_Philanthropy")));

    static final class TaskSpec {
        final String code;
        final String title;
        final int tier; // 1 = pure API zero cost, 2 = real chat short message
        final boolean mp;
        final int defaultCredit;
        final int defaultEnergy;

        TaskSpec(String code, String title, int tier, boolean mp, int defaultCredit, int defaultEnergy) {
            this.code = code;
            this.title = title;
            this.tier = tier;
            this.mp = mp;
            this.defaultCredit = defaultCredit;
            this.defaultEnergy = defaultEnergy;
        }
    }

    static final List<TaskSpec> CATALOG = Collections.unmodifiableList(Arrays.asList(
            // Tier 1: Pure API zero credit cost
            new TaskSpec("first_buddy", "领养第一只 Buddy", 1, false, 300, 8),
            new TaskSpec("chat_5", "对话活跃 ×5", 1, false, 100, 0),
            new TaskSpec("RichMeow_Chat", "桌面端对话 1 次", 1, false, 100, 5),
            new TaskSpec("Buddy_App", "进入 Buddy 应用", 1, false, 100, 5),
            new TaskSpec("automation_1", "创建定时任务", 1, false, 100, 5),
            new TaskSpec("Library_read", "体验资料库", 1, false, 100, 5),
            new TaskSpec("template_5", "使用 5 个模板", 1, false, 100, 5),
            new TaskSpec("playbook_prompt", "灵感案例做同款", 1, false, 100, 5),
            new TaskSpec("Hp_Appearance", "更换主题皮肤", 1, false, 100, 5),
            new TaskSpec("Sequential_Tasks_1", "小程序首次对话", 1, true, 100, 5),
            new TaskSpec("Sequential_Tasks_2", "小程序选专家对话", 1, true, 200, 5),
            new TaskSpec("Sequential_Tasks_3", "小程序 5 次对话", 1, true, 300, 5),
            // Tier 2: Requires real /v2/chat/completions short message (opt-in)
            new TaskSpec("expert_5", "体验 5 位平台专家", 2, false, 100, 5),
            new TaskSpec("Expert_team_use_3", "体验 3 个专家团", 2, false, 100, 5),
            new TaskSpec("skill_1", "尝鲜热门技能", 2, false, 100, 5),
            new TaskSpec("Expert_lighthouse", "体验轻量云专家", 2, false, 100, 5)
    ));

    private static final Map<String, TaskSpec> CATALOG_MAP;
    static {
        Map<String, TaskSpec> m = new LinkedHashMap<>();
        for (TaskSpec s : CATALOG) m.put(s.code, s);
        CATALOG_MAP = Collections.unmodifiableMap(m);
    }

    static TaskSpec specOf(String code) {
        return code == null ? null : CATALOG_MAP.get(code.trim());
    }

    static boolean isMPTask(String code) {
        return code != null && MP_TASK_CODES.contains(code.trim());
    }

    static boolean isManualOnly(String code) {
        return code != null && MANUAL_ONLY_CODES.contains(code.trim());
    }

    /** Returns empty string if eligible, or a Chinese reason if ineligible. */
    static String ineligibilityReason(String provider, JSONObject account) {
        return ineligibilityReason(provider, account, true);
    }

    static String ineligibilityReason(String provider, JSONObject account, boolean providerEnabled) {
        if (!providerEnabled) return "WorkBuddy 供应商已停用";
        if ("workbuddy-ai".equals(provider)) return "国际版账号不支持成长任务写入";
        if (!"workbuddy".equals(provider)) return "非 WorkBuddy 国内版账号";
        if (account == null) return "账号凭证缺失";
        if (!account.optBoolean("enabled", true)) return "账号已停用";
        if (!WorkBuddyAllowance.eligible(account)) return "API Key 账号不支持个人成长任务";
        try {
            if (!WorkBuddyLogin.enterpriseValue(account).isEmpty()) return "企业版账号无个人成长任务";
        } catch (Exception ignored) {
            return "账号企业标识异常";
        }
        String key = account.optString("access", account.optString("token", "")).trim();
        if (key.isEmpty()) return "缺少有效 OAuth 登录凭证";
        return "";
    }

    static boolean isEligible(String provider, JSONObject account) {
        return ineligibilityReason(provider, account, true).isEmpty();
    }

    static boolean isEligible(AccountEntry entry) {
        return entry != null && ineligibilityReason(entry.provider, entry.account, entry.providerEnabled).isEmpty();
    }

    // -------------------------------------------------------------------------
    // HTTP & Protocol Client
    // -------------------------------------------------------------------------

    static final class ApiError extends IOException {
        final int httpStatus;
        final int apiCode;
        final boolean rateLimited;

        ApiError(int httpStatus, int apiCode, String message) {
            super(message == null || message.isEmpty() ? ("HTTP " + httpStatus) : message);
            this.httpStatus = httpStatus;
            this.apiCode = apiCode;
            this.rateLimited = httpStatus == 429;
        }
    }

    static final class ClaimResult {
        final long credit;
        final long energy;
        final boolean alreadyClaimed;

        ClaimResult(long credit, long energy, boolean alreadyClaimed) {
            this.credit = credit;
            this.energy = energy;
            this.alreadyClaimed = alreadyClaimed;
        }
    }

    /** Thrown before opening/sending a new upstream write when stopped or account state changed. */
    static final class PreDispatchAbortException extends IOException {
        PreDispatchAbortException(String message) {
            super(message);
        }
    }

    /** Thrown when an upstream write was dispatched but disconnected/interrupted before confirmation. */
    static final class WriteOutcomeUncertainException extends IOException {
        final boolean byStop;

        WriteOutcomeUncertainException(boolean byStop, String message, Throwable cause) {
            super(message, cause);
            this.byStop = byStop;
        }
    }

    /** Thrown when a claim HTTP 200 lacks both already_claimed=true and numeric reward confirmation. */
    static final class ClaimUnconfirmedException extends IOException {
        ClaimUnconfirmedException(String message) {
            super(message);
        }
    }

    static final class MarketExpert {
        final String expertId;
        final String expertType;
        final String displayNameZh;
        final String professionZh;
        final String version;
        final String firstCategory;

        MarketExpert(String expertId, String expertType, String displayNameZh,
                     String professionZh, String version, String firstCategory) {
            this.expertId = expertId == null ? "" : expertId;
            this.expertType = (expertType == null || expertType.isEmpty()) ? "agent" : expertType;
            this.displayNameZh = displayNameZh == null ? "" : displayNameZh;
            this.professionZh = professionZh == null ? "" : professionZh;
            this.version = (version == null || version.isEmpty()) ? "1.0.0" : version;
            this.firstCategory = (firstCategory == null || firstCategory.isEmpty()) ? "expert-all" : firstCategory;
        }
    }

    static final class Client {
        private final WorkBuddyLogin.ConnectionFactory factory;
        private final ThreadLocal<Boolean> jobThread = new ThreadLocal<>();
        private volatile HttpURLConnection jobActiveConn;
        private volatile HttpURLConnection jobCancelledConn;
        private final ThreadLocal<ContinueCheck> writeGuard = new ThreadLocal<>();
        // Overridable timings for unit tests
        long reportGapMs = 1050L;
        long templateGapMs = 300L;
        long appearanceGapMs = 1500L;
        long expertGapMs = 4000L;
        long mpChatGapMs = 15000L;
        long pollGapMs = 2500L;

        Client(WorkBuddyLogin.ConnectionFactory factory) {
            this.factory = factory;
        }

        void enterJobThread() {
            jobThread.set(Boolean.TRUE);
        }

        void leaveJobThread() {
            jobThread.remove();
        }

        private boolean isJobThread() {
            return Boolean.TRUE.equals(jobThread.get());
        }

        ContinueCheck pushWriteGuard(ContinueCheck guard) {
            ContinueCheck prev = writeGuard.get();
            writeGuard.set(guard);
            return prev;
        }

        void popWriteGuard(ContinueCheck prev) {
            if (prev == null) writeGuard.remove();
            else writeGuard.set(prev);
        }

        private void checkWriteGuard() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new PreDispatchAbortException("stopped_before_write");
            }
            ContinueCheck g = writeGuard.get();
            if (g != null && !g.mayContinue()) {
                throw new PreDispatchAbortException("stopped_or_account_changed_before_write");
            }
        }

        void cancelActiveConnection() {
            HttpURLConnection c = jobActiveConn;
            if (c != null) {
                jobCancelledConn = c;
                try { c.disconnect(); } catch (Exception ignored) { }
            }
        }

        private boolean isStopCancellation(HttpURLConnection conn, IOException ioe) {
            if (conn != null && conn == jobCancelledConn) return true;
            if (Thread.currentThread().isInterrupted()) return true;
            return (ioe instanceof InterruptedIOException) && !(ioe instanceof java.net.SocketTimeoutException);
        }

        private HttpURLConnection openCheckedForWrite(String url) throws IOException {
            checkWriteGuard();
            return openChecked(url);
        }

        private HttpURLConnection openChecked(String url) throws IOException {
            URI u;
            try {
                u = URI.create(url);
            } catch (Exception bad) {
                throw new IOException("Invalid task URL");
            }
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getPort() != -1
                    || u.getHost() == null || !ALLOWED_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))) {
                throw new IOException("Disallowed WorkBuddy task host");
            }
            HttpURLConnection conn;
            try {
                conn = factory.open(url);
            } catch (IOException io) {
                throw io;
            } catch (Exception e) {
                throw new IOException(e.getMessage() == null ? "connection failed" : e.getMessage(), e);
            }
            conn.setInstanceFollowRedirects(false);
            return conn;
        }

        private String tokenOf(JSONObject account) throws IOException {
            String key = account == null ? "" : account.optString("access", account.optString("token", "")).trim();
            if (key.isEmpty()) throw new IOException("缺少有效 OAuth Token");
            return key;
        }

        private String uidOf(JSONObject account) {
            return account == null ? "" : account.optString("uid", "").trim();
        }

        private String nicknameOf(JSONObject account) {
            if (account == null) return "";
            String n = account.optString("nickname", "").trim();
            return n.isEmpty() ? account.optString("name", "").trim() : n;
        }

        private String domainOf(JSONObject account) {
            String d = account == null ? "" : account.optString("domain", "").trim();
            return d.isEmpty() ? "copilot.tencent.com" : d;
        }

        private void applyBillingHeaders(HttpURLConnection conn, JSONObject account, boolean mp) throws IOException {
            String key = tokenOf(account);
            String uid = uidOf(account);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", DESKTOP_UA_CN);
            conn.setRequestProperty("X-Product", "SaaS");
            conn.setRequestProperty("X-Domain", domainOf(account));
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            if (mp) {
                conn.setRequestProperty("X-Client-Platform", MP_PLATFORM);
            }
        }

        private JSONObject doJson(HttpURLConnection conn, String method, byte[] body, int timeoutMs) throws IOException {
            return doJson(conn, method, body, timeoutMs, !"GET".equalsIgnoreCase(method));
        }

        private JSONObject doJson(HttpURLConnection conn, String method, byte[] body, int timeoutMs, boolean isWrite) throws IOException {
            if (isWrite) checkWriteGuard();
            boolean trackJob = isJobThread();
            if (trackJob) jobActiveConn = conn;
            boolean writeDispatched = false;
            try {
                conn.setRequestMethod(method);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(body.length);
                    if (isWrite) writeDispatched = true;
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(body);
                    }
                } else if (isWrite) {
                    writeDispatched = true;
                }
                int status = conn.getResponseCode();
                InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
                byte[] raw = readBounded(stream, 524288, timeoutMs);
                String text = new String(raw, StandardCharsets.UTF_8).trim();
                JSONObject json = null;
                if (text.startsWith("{")) {
                    try { json = new JSONObject(text); } catch (Exception ignored) { }
                }
                if (status < 200 || status >= 300) {
                    String msg = json != null ? json.optString("msg", json.optString("message", "")) : "";
                    int code = json != null ? json.optInt("code", status) : status;
                    throw new ApiError(status, code, msg.isEmpty() ? ("HTTP " + status) : msg);
                }
                if (json == null) {
                    throw new ApiError(status, -1, "上游返回非 JSON 响应");
                }
                if (json.has("code") && !json.isNull("code")) {
                    Object rawCode = json.opt("code");
                    boolean zero = (rawCode instanceof Number && ((Number) rawCode).intValue() == 0)
                            || "0".equals(String.valueOf(rawCode).trim());
                    if (!zero) {
                        int code = json.optInt("code", -1);
                        String msg = json.optString("msg", json.optString("message", "code=" + rawCode));
                        throw new ApiError(status, code, msg);
                    }
                }
                return json;
            } catch (ApiError apiErr) {
                throw apiErr;
            } catch (IOException ioe) {
                if (writeDispatched) {
                    boolean byStop = isStopCancellation(conn, ioe);
                    throw new WriteOutcomeUncertainException(byStop, "请求已发出但未收到确认，结果未知，请刷新核对", ioe);
                }
                throw ioe;
            } finally {
                if (trackJob && jobActiveConn == conn) jobActiveConn = null;
                conn.disconnect();
            }
        }

        private static byte[] readBounded(InputStream in, int maxBytes, int timeoutMs) throws IOException {
            if (in == null) return new byte[0];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + Math.max(timeoutMs, 5000) * 1_000_000L;
            try (InputStream stream = in) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
                    if (out.size() + n > maxBytes || System.nanoTime() > deadline) {
                        throw new IOException("response size or time limit exceeded");
                    }
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        }

        List<JSONObject> listTasks(JSONObject account, boolean mp) throws IOException {
            HttpURLConnection conn = openChecked(ORIGIN_CHAT + TASKS_LIST_PATH);
            applyBillingHeaders(conn, account, mp);
            JSONObject env = doJson(conn, "GET", null, 10000);
            JSONObject data = env.optJSONObject("data");
            JSONArray arr = data != null ? data.optJSONArray("tasks") : env.optJSONArray("tasks");
            return parseTaskList(arr, mp);
        }

        /** Merges default and MP task lists, deduplicating by task_code. */
        List<JSONObject> listMergedTasks(JSONObject account) throws IOException {
            List<JSONObject> base = listTasks(account, false);
            List<JSONObject> mpList = Collections.emptyList();
            try {
                mpList = listTasks(account, true);
            } catch (Exception ignored) {
                // MP list is additive; keep base tasks if MP list temporarily fails
            }
            Map<String, JSONObject> merged = new LinkedHashMap<>();
            for (JSONObject t : base) {
                String code = t.optString("task_code", "");
                if (!code.isEmpty()) merged.put(code, t);
            }
            for (JSONObject t : mpList) {
                String code = t.optString("task_code", "");
                if (!code.isEmpty() && !merged.containsKey(code)) {
                    merged.put(code, t);
                }
            }
            return new ArrayList<>(merged.values());
        }

        void acceptTasks(JSONObject account, List<String> codes, boolean mp) throws IOException {
            if (codes == null || codes.isEmpty()) return;
            JSONArray arr = new JSONArray();
            for (String c : codes) arr.put(c);
            byte[] payload = new JSONObject().put("task_codes", arr).toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + TASKS_ACCEPT_PATH);
            applyBillingHeaders(conn, account, mp);
            doJson(conn, "POST", payload, 10000);
        }

        ClaimResult claimRewardWeb(JSONObject account, String taskCode) throws IOException {
            String encoded = URLEncoder.encode(taskCode, "UTF-8").replace("+", "%20");
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_WEB + "/activity/growth/tasks/" + encoded + "/claim");
            String key = tokenOf(account);
            String uid = uidOf(account);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Origin", ORIGIN_WEB);
            conn.setRequestProperty("Referer", ORIGIN_WEB + "/profile/growth-center");
            conn.setRequestProperty("x-client-platform", "web");
            conn.setRequestProperty("User-Agent", DESKTOP_UA_CN);
            conn.setRequestProperty("X-Domain", domainOf(account));
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            JSONObject env = doJson(conn, "POST", "{}".getBytes(StandardCharsets.UTF_8), 10000);
            return parseClaim(env);
        }

        ClaimResult claimRewardMP(JSONObject account, String taskCode) throws IOException {
            String encoded = URLEncoder.encode(taskCode, "UTF-8").replace("+", "%20");
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + "/activity/growth/tasks/" + encoded + "/claim");
            applyBillingHeaders(conn, account, true);
            try {
                JSONObject env = doJson(conn, "POST", "{}".getBytes(StandardCharsets.UTF_8), 10000);
                return parseClaim(env);
            } catch (ApiError err) {
                if (err.httpStatus == 400) {
                    return claimRewardWeb(account, taskCode);
                }
                throw err;
            }
        }

        ClaimResult claimReward(JSONObject account, String taskCode) throws IOException {
            return isMPTask(taskCode) ? claimRewardMP(account, taskCode) : claimRewardWeb(account, taskCode);
        }

        private static ClaimResult parseClaim(JSONObject env) throws IOException {
            if (env == null) throw new ClaimUnconfirmedException("领奖响应为空");
            JSONObject data = env.optJSONObject("data");
            JSONObject src = data != null ? data : env;
            boolean already = false;
            boolean hasNumericReward = false;
            long credit = 0;
            long energy = 0;

            if (src.has("already_claimed")) {
                Object v = src.opt("already_claimed");
                if (v == null || v == JSONObject.NULL || !(v instanceof Boolean)) {
                    throw new IOException("领奖响应 already_claimed 字段类型异常");
                }
                already = (Boolean) v;
            }
            if (src.has("credit")) {
                Object v = src.opt("credit");
                if (v == null || v == JSONObject.NULL || !(v instanceof Number)) {
                    throw new IOException("领奖响应 credit 字段类型异常");
                }
                double d = ((Number) v).doubleValue();
                if (!Double.isFinite(d) || d < 0) throw new IOException("领奖响应 credit 数值无效");
                credit = ((Number) v).longValue();
                hasNumericReward = true;
            }
            if (src.has("energy")) {
                Object v = src.opt("energy");
                if (v == null || v == JSONObject.NULL || !(v instanceof Number)) {
                    throw new IOException("领奖响应 energy 字段类型异常");
                }
                double d = ((Number) v).doubleValue();
                if (!Double.isFinite(d) || d < 0) throw new IOException("领奖响应 energy 数值无效");
                energy = ((Number) v).longValue();
                hasNumericReward = true;
            }
            if (!already && !hasNumericReward) {
                throw new ClaimUnconfirmedException("领奖响应缺少积分/能量确认字段，结果待确认");
            }
            return new ClaimResult(already ? 0 : credit, already ? 0 : energy, already);
        }

        void buddyAgreement(JSONObject account) throws IOException {
            byte[] payload = new JSONObject().put("agree", true).toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + BUDDY_AGREEMENT_PATH);
            applyBillingHeaders(conn, account, false);
            doJson(conn, "POST", payload, 10000);
        }

        void buddyFirst(JSONObject account) throws IOException {
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + BUDDY_FIRST_PATH);
            applyBillingHeaders(conn, account, false);
            doJson(conn, "POST", payload, 10000);
        }

        void setAppearanceTheme(JSONObject account, String resourceKey) throws IOException {
            byte[] payload = new JSONObject()
                    .put("kind", "theme")
                    .put("resource_key", resourceKey)
                    .toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + APPEARANCE_SET_PATH);
            applyBillingHeaders(conn, account, false);
            doJson(conn, "POST", payload, 10000);
        }

        void reportDesktopEvents(JSONObject account, List<JSONObject> events) throws IOException {
            if (events == null || events.isEmpty()) return;
            JSONObject fp = desktopFingerprint(account);
            JSONArray arr = new JSONArray();
            for (JSONObject ev : events) {
                JSONObject merged = new JSONObject(fp.toString());
                for (java.util.Iterator<String> it = ev.keys(); it.hasNext(); ) {
                    String k = it.next();
                    merged.put(k, ev.opt(k));
                }
                arr.put(merged);
            }
            byte[] payload = arr.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_CHAT + REPORT_PATH);
            String key = tokenOf(account);
            String uid = uidOf(account);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", DESKTOP_UA_CN);
            conn.setRequestProperty("X-Domain", domainOf(account));
            conn.setRequestProperty("X-Product", "SaaS");
            conn.setRequestProperty("X-Request-ID", newHex32());
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            doJson(conn, "POST", payload, 10000);
        }

        void reportChatActivity(JSONObject account, String conversationId) throws IOException {
            String rid = newHex32();
            JSONObject ev = new JSONObject()
                    .put("eventCode", "chat_request_send")
                    .put("mode", "craft")
                    .put("conversationId", conversationId)
                    .put("requestId", rid)
                    .put("inputLength", 16)
                    .put("requestModelId", "fast-model")
                    .put("requestModelName", "fast-model")
                    .put("isPlan", false)
                    .put("isAutoExecuteTerminal", false)
                    .put("isAutoModify", false)
                    .put("codebaseEnable", false)
                    .put("isMaxMode", false)
                    .put("source", "LOCAL")
                    .put("mentionContexts", new JSONArray())
                    .put("knowledgeId", new JSONArray())
                    .put("knowledgeName", new JSONArray())
                    .put("codebaseId", "")
                    .put("mentionContextCount", 0)
                    .put("command", "")
                    .put("inputAttemptId", "")
                    .put("recommendId", "")
                    .put("skillId", "")
                    .put("traceId", rid)
                    .put("rootRequestId", rid)
                    .put("parentConversationId", conversationId)
                    .put("agentName", "cli")
                    .put("agentType", "main")
                    .put("vcsType", "unknown")
                    .put("vcsRepo", "")
                    .put("vcsBranchName", "")
                    .put("vcsRevId", "");
            reportDesktopEvents(account, Collections.singletonList(ev));
        }

        void reportWebEvent(JSONObject account, String eventCode, String pageUrl,
                            String elementId, String elementName) throws IOException {
            String uid = uidOf(account);
            JSONObject ev = new JSONObject()
                    .put("eventCode", eventCode)
                    .put("timestamp", System.currentTimeMillis())
                    .put("reportDelay", 0)
                    .put("pageURL", pageUrl)
                    .put("elementId", elementId)
                    .put("elementName", elementName)
                    .put("os", "Win32")
                    .put("arch", "")
                    .put("osVersion", "10.0")
                    .put("userAgent", DESKTOP_UA_CN)
                    .put("machineId", deriveHex36(uid, "webmachine"))
                    .put("userId", uid)
                    .put("userNickname", nicknameOf(account))
                    .put("enterpriseId", "");
            byte[] payload = new JSONArray().put(ev).toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_WEB + REPORT_PATH);
            conn.setRequestProperty("Authorization", "Bearer " + tokenOf(account));
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", DESKTOP_UA_CN);
            conn.setRequestProperty("X-Request-ID", newHex32());
            conn.setRequestProperty("X-Product", "SaaS");
            conn.setRequestProperty("X-Domain", "www.workbuddy.cn");
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            doJson(conn, "POST", payload, 10000);
        }

        void reportMPEvents(JSONObject account, List<JSONObject> events) throws IOException {
            if (events == null || events.isEmpty()) return;
            String uid = uidOf(account);
            JSONObject base = new JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("ideType", "WorkBuddy_MP")
                    .put("ideVersion", "2.4.0")
                    .put("extName", "workbuddy-mp")
                    .put("extVersion", "2.4.0")
                    .put("product", "SaaS")
                    .put("ideName", "wx_app_cloud")
                    .put("platform", "mini_program")
                    .put("os", "windows")
                    .put("osVersion", "11")
                    .put("arch", "x64")
                    .put("machineId", deriveUuid(uid, "mp-machine"))
                    .put("timezone", "Asia/Shanghai")
                    .put("userId", uid)
                    .put("userNickname", nicknameOf(account));
            JSONArray arr = new JSONArray();
            for (JSONObject ev : events) {
                JSONObject merged = new JSONObject(base.toString());
                for (java.util.Iterator<String> it = ev.keys(); it.hasNext(); ) {
                    String k = it.next();
                    merged.put(k, ev.opt(k));
                }
                arr.put(merged);
            }
            byte[] payload = arr.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openCheckedForWrite(ORIGIN_BILLING + REPORT_PATH);
            conn.setRequestProperty("Authorization", "Bearer " + tokenOf(account));
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            conn.setRequestProperty("X-Client-Product", "workbuddy-mp");
            conn.setRequestProperty("X-Client-Version", "2.4.0");
            conn.setRequestProperty("X-Client-Platform", "mp-weixin");
            conn.setRequestProperty("X-Platform", "wechatmp");
            doJson(conn, "POST", payload, 10000);
        }

        List<MarketExpert> marketExpertList(JSONObject account, String expertType) throws IOException {
            JSONObject reqBody = new JSONObject()
                    .put("page", 1)
                    .put("page_size", 20)
                    .put("sort_by", "reco_rank")
                    .put("sort_order", "desc");
            if (expertType != null && !expertType.isEmpty()) {
                reqBody.put("expert_type", expertType);
            }
            byte[] payload = reqBody.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = openChecked(ORIGIN_CHAT + MARKET_EXPERT_LIST_PATH);
            String uid = uidOf(account);
            conn.setRequestProperty("Authorization", "Bearer " + tokenOf(account));
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", DESKTOP_UA_CN);
            conn.setRequestProperty("X-Domain", ORIGIN_CHAT);
            conn.setRequestProperty("X-Product", "SaaS");
            if (!uid.isEmpty()) conn.setRequestProperty("X-User-Id", uid);
            JSONObject env = doJson(conn, "POST", payload, 10000, false);
            JSONObject data = env.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("experts") : env.optJSONArray("experts");
            List<MarketExpert> out = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject row = list.optJSONObject(i);
                    if (row == null) continue;
                    String id = row.optString("expert_id", "").trim();
                    if (id.isEmpty()) continue;
                    JSONArray cats = row.optJSONArray("categories");
                    String cat = (cats != null && cats.length() > 0) ? cats.optString(0, "expert-all") : "expert-all";
                    out.add(new MarketExpert(
                            id,
                            row.optString("expert_type", "agent"),
                            row.optString("display_name_zh", ""),
                            row.optString("profession_zh", ""),
                            row.optString("version", "1.0.0"),
                            cat
                    ));
                }
            }
            return out;
        }

        /** Sends a minimal real chat SSE request to obtain an upstream server requestId (Tier 2). */
        String[] desktopChatWithExpert(JSONObject account, String expertId) throws IOException {
            checkWriteGuard();
            String conversationId = UUID.randomUUID().toString();
            JSONObject body = new JSONObject()
                    .put("model", "fast-model")
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", "You are a helpful assistant. 当前处于中文环境，使用简体中文回答。"))
                            .put(new JSONObject().put("role", "user").put("content", "1+1等于几？直接回答。")))
                    .put("agent", "cli")
                    .put("temperature", 1)
                    .put("stream", true)
                    .put("stream_options", new JSONObject().put("include_usage", true));
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String url = ORIGIN_CHAT + CHAT_COMPLETIONS_PATH;
            Map<String, String> headers = WorkBuddyPolicy.headers(
                    "workbuddy", url, tokenOf(account), account, conversationId, null, body);
            if (headers.isEmpty()) throw new IOException("无法构造对话鉴权头");
            HttpURLConnection conn = openCheckedForWrite(url);
            boolean trackJob = isJobThread();
            if (trackJob) jobActiveConn = conn;
            boolean writeDispatched = false;
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(25000);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream");
                if (expertId != null && !expertId.isEmpty()) {
                    conn.setRequestProperty("X-Expert-Id", expertId);
                }
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                writeDispatched = true;
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    byte[] err = readBounded(conn.getErrorStream(), 4096, 5000);
                    throw new ApiError(status, status, "chat HTTP " + status + ": " + new String(err, StandardCharsets.UTF_8));
                }
                String serverReqId = extractRequestIdFromSse(conn.getInputStream());
                if (serverReqId == null || serverReqId.isEmpty()) {
                    throw new IOException("SSE 中未找到服务端 requestId");
                }
                return new String[]{conversationId, serverReqId};
            } catch (ApiError apiErr) {
                throw apiErr;
            } catch (IOException ioe) {
                if (writeDispatched) {
                    boolean byStop = isStopCancellation(conn, ioe);
                    throw new WriteOutcomeUncertainException(byStop, "对话请求已发出但未收到确认，结果未知，请刷新核对", ioe);
                }
                throw ioe;
            } finally {
                if (trackJob && jobActiveConn == conn) jobActiveConn = null;
                conn.disconnect();
            }
        }

        /**
         * Matches workbuddy2api-panel DesktopChatWithExpert (desktop.go:605-642):
         * returns the first valid upstream server requestId (cmb-32hex or 32hex) from the SSE stream.
         */
        private static String extractRequestIdFromSse(InputStream in) throws IOException {
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.nanoTime() + 25_000_000_000L;
            int searchFrom = 0;
            try (InputStream stream = in) {
                int n;
                while ((n = stream.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
                    if (System.nanoTime() > deadline || sb.length() > 524288) break;
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    while (true) {
                        int idx = sb.indexOf("\"id\":\"", searchFrom);
                        if (idx < 0) break;
                        int start = idx + 6;
                        int end = sb.indexOf("\"", start);
                        if (end < 0) break;
                        String cand = sb.substring(start, end);
                        if (SERVER_REQUEST_ID.matcher(cand).matches()) {
                            return cand;
                        }
                        searchFrom = end + 1;
                    }
                }
            }
            return "";
        }

        private JSONObject desktopFingerprint(JSONObject account) {
            long now = System.currentTimeMillis();
            String uid = uidOf(account);
            String nick = nicknameOf(account);
            return new JSONObject()
                    .put("timezone", "Asia/Shanghai")
                    .put("reportDelay", 2000)
                    .put("userId", uid)
                    .put("username", nick)
                    .put("userNickname", nick)
                    .put("product", "SaaS")
                    .put("releaseDate", DESKTOP_RELEASE_DATE_CN)
                    .put("commit", DESKTOP_COMMIT_CN)
                    .put("ideName", "WorkBuddy")
                    .put("ideType", "WorkBuddy")
                    .put("ideVersion", DESKTOP_VERSION_CN)
                    .put("machineId", deriveHex36(uid, "machine"))
                    .put("sessionId", deriveHex36(uid, "session"))
                    .put("qimei36", deriveHex36(uid, "qimei"))
                    .put("extName", "workbuddy-desktop")
                    .put("extVersion", DESKTOP_VERSION_CN)
                    .put("os", "win32")
                    .put("arch", "x64")
                    .put("osVersion", "10.0.28000")
                    .put("cpuModel", "11th Gen Intel(R) Core(TM) i5-11320H @ 3.20GHz")
                    .put("cpuCores", 8)
                    .put("memorySize", 16)
                    .put("timestamp", now)
                    .put("presentAt", now);
        }

        static List<JSONObject> parseTaskList(JSONArray arr, boolean mp) {
            List<JSONObject> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                String code = row.optString("task_code", "").trim();
                if (code.isEmpty()) code = row.optString("code", "").trim();
                if (code.isEmpty()) continue;
                long cur = row.optLong("current", 0);
                long tgt = row.optLong("target", 0);
                JSONObject progress = row.optJSONObject("progress");
                if (progress != null) {
                    cur = progress.optLong("current", cur);
                    tgt = progress.optLong("target", tgt);
                }
                String acceptStatus = row.optString("accept_status", "").trim();
                String status = row.optString("status", "").trim();
                boolean claimed = "claimed".equalsIgnoreCase(acceptStatus);
                boolean locked = row.optBoolean("locked", false);
                boolean reached = (tgt > 0 && cur >= tgt)
                        || "completed".equalsIgnoreCase(acceptStatus)
                        || "complete".equalsIgnoreCase(status);
                boolean claimable = !claimed && !locked && reached;

                TaskSpec spec = specOf(code);
                String title = row.optString("title", "").trim();
                if (title.isEmpty() && spec != null) title = spec.title;
                if (title.isEmpty()) title = code;

                long credit = row.optLong("reward_credit", spec != null ? spec.defaultCredit : 0);
                long energy = row.optLong("reward_energy", spec != null ? spec.defaultEnergy : 0);

                JSONObject item = new JSONObject()
                        .put("task_code", code)
                        .put("title", title)
                        .put("credit", credit)
                        .put("energy", energy)
                        .put("current", cur)
                        .put("target", tgt)
                        .put("accept_status", acceptStatus)
                        .put("status", status)
                        .put("locked", locked)
                        .put("claimed", claimed)
                        .put("claimable", claimable)
                        .put("mp", mp || isMPTask(code))
                        .put("tier", spec != null ? spec.tier : 0)
                        .put("manual_only", isManualOnly(code))
                        .put("automated", spec != null);
                out.add(item);
            }
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // Event Builders
    // -------------------------------------------------------------------------

    private static List<JSONObject> desktopChatSequence(String conversationId, String requestId,
                                                        String messageId, String modelId, String modelName) {
        long now = System.currentTimeMillis();
        List<JSONObject> list = new ArrayList<>();
        list.add(new JSONObject()
                .put("eventCode", "agent_task_created")
                .put("source", "LOCAL").put("name", "working").put("task_target", "local").put("mode", "craft")
                .put("requestModelId", modelId).put("requestModelName", modelName)
                .put("has_repo", false).put("repo_type", "none").put("workspace_type", "empty")
                .put("has_connector", false).put("connector_types", new JSONArray())
                .put("has_mention", false).put("mention_types", new JSONArray())
                .put("has_template", false).put("action", "").put("template_name", "")
                .put("has_expert", false).put("expert_id", "").put("expert_name", "").put("expert_industry_id", "")
                .put("has_skill", false).put("skill_names", new JSONArray())
                .put("conversationId", conversationId).put("messageId", messageId)
                .put("buddyId", "").put("buddyName", ""));
        list.add(new JSONObject()
                .put("eventCode", "chat_message_send")
                .put("messageId", messageId + "-assistant").put("historyCount", 0)
                .put("isContextTruncated", false).put("currentStepCount", 1)
                .put("traceId", requestId).put("rootRequestId", requestId)
                .put("parentConversationId", conversationId)
                .put("agentName", "cli").put("agentType", "main"));
        list.add(new JSONObject()
                .put("eventCode", "chat_request_send")
                .put("inputLength", 24).put("isPlan", false).put("isAutoExecuteTerminal", false)
                .put("isAutoModify", false).put("codebaseEnable", false).put("maxToken", 0)
                .put("maxSteps", 500).put("temperature", 0).put("maxRetries", 0)
                .put("mentionContexts", new JSONArray()).put("knowledgeId", new JSONArray()).put("knowledgeName", new JSONArray())
                .put("codebaseId", "").put("mentionContextCount", 0).put("command", "")
                .put("recommendId", "").put("skillId", "").put("skillCount", 0).put("totalCount", 0)
                .put("traceId", requestId).put("rootRequestId", requestId)
                .put("parentConversationId", conversationId)
                .put("agentName", "cli").put("agentType", "main")
                .put("codebuddy.session_id", conversationId)
                .put("codebuddy.conversation_request_id", requestId));
        list.add(new JSONObject()
                .put("eventCode", "chat_message_response")
                .put("messageId", messageId + "-assistant").put("responseModelId", modelId)
                .put("inputToken", 120).put("outputToken", 80).put("totalToken", 200)
                .put("cachedTokens", 0).put("cachedWriteTokens", 0).put("cachedMissTokens", 0)
                .put("isSuccessful", true).put("messageErrorCode", "").put("finishReason", "stop")
                .put("firstTokenAt", now).put("traceId", requestId)
                .put("conversationId", conversationId)
                .put("rootRequestId", requestId).put("parentConversationId", conversationId)
                .put("agentName", "cli").put("agentType", "main")
                .put("codebuddy.session_id", conversationId)
                .put("codebuddy.conversation_request_id", requestId));
        list.add(new JSONObject()
                .put("eventCode", "chat_message_status")
                .put("messageId", messageId + "-assistant").put("messageErrorCode", "0")
                .put("traceId", requestId).put("rootRequestId", requestId)
                .put("parentConversationId", conversationId)
                .put("agentName", "cli").put("agentType", "main"));
        list.add(new JSONObject()
                .put("eventCode", "chat_request_response")
                .put("mode", "craft").put("toolCallCount", 0)
                .put("inputToken", 120).put("outputToken", 80).put("totalToken", 200)
                .put("cachedTokens", 0).put("cachedWriteTokens", 0).put("cachedMissTokens", 0)
                .put("isSuccessful", true).put("messageErrorCode", "").put("finishReason", "stop")
                .put("rootRequestId", requestId).put("parentConversationId", conversationId));
        return list;
    }

    private static List<JSONObject> desktopBuddyAppSequence(String buddyId, String buddyName) {
        List<JSONObject> out = new ArrayList<>();
        out.add(new JSONObject().put("eventCode", "buddyapp_discover_click").put("mode", "LOCAL").put("buddyId", buddyId).put("buddyName", buddyName));
        out.add(new JSONObject().put("eventCode", "buddyapp_show").put("mode", "LOCAL").put("buddyId", buddyId).put("buddyName", buddyName).put("elementId", buddyId).put("elementName", buddyName).put("position", 2));
        out.add(new JSONObject().put("eventCode", "buddyapp_enter_click").put("mode", "LOCAL").put("buddyId", buddyId).put("buddyName", buddyName).put("elementId", buddyId).put("elementName", buddyName).put("position", 2).put("isFirstPage", true));
        out.add(new JSONObject().put("eventCode", "buddyapp_auth_confirm_click").put("mode", "LOCAL").put("buddyId", buddyId).put("buddyName", buddyName).put("elementId", buddyId).put("elementName", buddyName));
        out.add(new JSONObject().put("eventCode", "buddyapp_bindaccount_skip_click").put("mode", "LOCAL").put("buddyId", buddyId).put("buddyName", buddyName).put("elementId", buddyId).put("elementName", buddyName));
        return out;
    }

    private static JSONObject desktopAutomationCreateEvent(String name) {
        return new JSONObject()
                .put("eventCode", "automated_task_create_suc")
                .put("name", name)
                .put("source", "manually")
                .put("modelId", "fast-model")
                .put("modelIsThinking", true)
                .put("connectorCount", 0)
                .put("skills", "")
                .put("skillCount", 0)
                .put("scheduleType", "once")
                .put("mode", "LOCAL");
    }

    private static List<JSONObject> desktopTemplateUseSequence(String conv, String req, String tplId, String tplName) {
        List<JSONObject> events = desktopChatSequence(conv, req, "msg-" + tplId, "fast-model", "fast-model");
        events.add(new JSONObject()
                .put("eventCode", "agent_task_created_with_template")
                .put("mode", "working").put("isCustomModel", false)
                .put("id", tplId).put("name", tplName).put("requestId", req));
        events.add(new JSONObject()
                .put("eventCode", "template_used")
                .put("template_id", tplId).put("task_mode", "working"));
        return events;
    }

    private static List<JSONObject> desktopPlaybookPromptSequence(String conv, String req, String caseId, String caseName) {
        List<JSONObject> events = desktopChatSequence(conv, req, "msg-pb", "fast-model", "fast-model");
        events.add(new JSONObject()
                .put("eventCode", "web_element_click").put("pageName", "playbook_detail")
                .put("elementId", "playbook_ctaClick").put("elementName", caseName).put("source", "discover"));
        events.add(new JSONObject()
                .put("eventCode", "playbook_cta_click").put("source", "discover").put("position", 0)
                .put("id", caseId).put("name", caseName).put("type", "document").put("categoryId", "").put("categoryName", ""));
        events.add(new JSONObject()
                .put("eventCode", "playbook_prompt_send").put("conversationId", conv).put("requestId", req)
                .put("id", caseId).put("name", caseName).put("type", "document").put("categoryId", "").put("categoryName", ""));
        return events;
    }

    private static List<JSONObject> desktopExpertSummonSequence(MarketExpert e) {
        List<JSONObject> out = new ArrayList<>();
        out.add(new JSONObject()
                .put("eventCode", "web_element_click").put("source", e.expertId)
                .put("type", e.firstCategory).put("version", e.version)
                .put("elementId", "expert_summon_click").put("elementName", "立即召唤")
                .put("pageURL", "/C:/Program%20Files/WorkBuddy/resources/app.asar/renderer/index.html"));
        out.add(new JSONObject()
                .put("eventCode", "expert_summon_click").put("id", e.expertId).put("name", e.displayNameZh)
                .put("expertTitle", e.professionZh).put("type", "expert-all").put("position", 0)
                .put("expertType", e.expertType).put("version", e.version).put("mode", "LOCAL"));
        out.add(new JSONObject()
                .put("eventCode", "expert_summoned").put("id", e.expertId).put("name", e.displayNameZh)
                .put("expertTitle", e.professionZh).put("type", "expert-all"));
        return out;
    }

    private static JSONObject desktopExpertActualUse(MarketExpert e, String conv, String req, String mode, boolean lighthouse) {
        String tail = req.length() > 8 ? req.substring(req.length() - 8) : req;
        JSONObject ev = new JSONObject()
                .put("eventCode", "expert_actual_use")
                .put("id", e.expertId).put("name", e.displayNameZh).put("expertTitle", e.professionZh)
                .put("type", lighthouse ? "" : e.firstCategory)
                .put("expertType", e.expertType).put("source", "builtin").put("version", e.version)
                .put("cost", lighthouse ? 0 : 9000).put("characterCount", 14)
                .put("conversationId", conv).put("requestId", req).put("messageId", "msg-" + tail)
                .put("requestModelId", "fast-model").put("requestModelName", "fast-model")
                .put("mode", mode);
        return ev;
    }

    private static JSONObject miniChatSendEvent(String conversationId) {
        String rid = newHex32();
        String tail = rid.substring(rid.length() - 8);
        return new JSONObject()
                .put("eventCode", "chat_request_send")
                .put("inputLength", 14).put("isPlan", false).put("isAutoExecuteTerminal", false)
                .put("isAutoModify", false).put("codebaseEnable", false).put("maxToken", 0)
                .put("maxSteps", 500).put("temperature", 0).put("maxRetries", 0)
                .put("mentionContexts", new JSONArray()).put("knowledgeId", new JSONArray()).put("knowledgeName", new JSONArray())
                .put("codebaseId", "").put("mentionContextCount", 0).put("command", "")
                .put("recommendId", "").put("skillId", "").put("skillCount", 0).put("totalCount", 0)
                .put("traceId", rid).put("rootRequestId", rid)
                .put("parentConversationId", conversationId).put("conversationId", conversationId)
                .put("messageId", "msg-" + tail)
                .put("agentName", "mp").put("agentType", "main")
                .put("codebuddy.session_id", conversationId)
                .put("codebuddy.conversation_request_id", rid);
    }

    private static JSONObject miniExpertUseEvent(MarketExpert e) {
        String title = !e.displayNameZh.isEmpty() ? e.displayNameZh : (!e.professionZh.isEmpty() ? e.professionZh : e.expertId);
        return new JSONObject()
                .put("eventCode", "expert_actual_use")
                .put("reportDelay", 0)
                .put("extVersion", "2.2.8")
                .put("source", "mini_program")
                .put("id", e.expertId)
                .put("name", e.expertId)
                .put("expertTitle", title)
                .put("type", "send_message")
                .put("characterCount", 12)
                .put("expertType", e.expertType);
    }

    private static String newHex32() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return toHex(b);
    }

    static String deriveHex36(String uid, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] sum = md.digest((salt + ":" + (uid == null ? "" : uid)).getBytes(StandardCharsets.UTF_8));
            byte[] slice = Arrays.copyOf(sum, 18);
            return toHex(slice);
        } catch (Exception e) {
            return newHex32() + "0000";
        }
    }

    static String deriveUuid(String uid, String salt) {
        String h = deriveHex36(uid, salt);
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16)
                + "-a" + h.substring(17, 20) + "-" + h.substring(20, 32);
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    private static void sleepCancelable(long ms) throws InterruptedException {
        if (ms <= 0) return;
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long rem = end - System.currentTimeMillis();
            if (rem <= 0) return;
            Thread.sleep(Math.min(rem, 500L));
        }
    }

    // -------------------------------------------------------------------------
    // Single-Account Execution
    // -------------------------------------------------------------------------

    interface ProgressListener {
        void onStep(String taskCode, String taskTitle, String detail);
        void onClaimed(long credit, long energy);
    }

    interface ContinueCheck {
        boolean mayContinue();
    }

    static JSONObject runSingleTask(Client client, JSONObject account, JSONObject taskObj,
                                    boolean includeTier2, boolean claimOnly) throws Exception {
        return runSingleTask(client, account, taskObj, includeTier2, claimOnly, () -> true);
    }

    static JSONObject runSingleTask(Client client, JSONObject account, JSONObject taskObj,
                                    boolean includeTier2, boolean claimOnly, ContinueCheck guard) throws Exception {
        ContinueCheck check = guard == null ? () -> true : guard;
        ContinueCheck prevGuard = client.pushWriteGuard(check);
        try {
            String code = taskObj.optString("task_code", "");
            String title = taskObj.optString("title", code);
            JSONObject out = new JSONObject().put("task_code", code).put("title", title);
            if (!check.mayContinue()) {
                throw new PreDispatchAbortException("stopped_or_account_changed");
            }
            if (taskObj.optBoolean("locked", false)) {
                return out.put("status", "skipped").put("message", "尚未解锁");
            }
            if (taskObj.optBoolean("claimed", false)) {
                return out.put("status", "done").put("claimed", true).put("message", "已领取");
            }
            if (isManualOnly(code)) {
                return out.put("status", "skipped").put("message", "需人工操作，不自动执行");
            }
            // Claim immediately if already reached (except first_buddy which grants via buddy/first)
            if (taskObj.optBoolean("claimable", false) && !"first_buddy".equals(code)) {
                return claimAndConfirm(client, account, code, out, "", check);
            }
            if (claimOnly) {
                return out.put("status", "skipped").put("message", "未达标（仅领奖模式跳过）");
            }
            TaskSpec spec = specOf(code);
            if (spec == null) {
                return out.put("status", "skipped").put("message", "暂无自动执行动作");
            }
            if (spec.tier == 2 && !includeTier2) {
                return out.put("status", "skipped").put("message", "Tier 2 真实短对话开关未开启");
            }

            if (!check.mayContinue()) throw new PreDispatchAbortException("stopped_or_account_changed");
            String actionMsg = executeAction(client, account, code, taskObj);
            if ("first_buddy".equals(code)) {
                sleepCancelable(client.pollGapMs);
                if (!check.mayContinue()) throw new PreDispatchAbortException("stopped_or_account_changed");
                JSONObject after = findTask(client.listTasks(account, false), code);
                boolean done = after != null && after.optBoolean("claimed", false);
                return out.put("status", done ? "done" : "awaiting").put("claimed", done)
                        .put("credit", done ? 300 : 0).put("energy", done ? 8 : 0)
                        .put("message", actionMsg);
            }

            // Poll up to 2 rounds for async scoring, then claim
            boolean mp = isMPTask(code);
            JSONObject after = null;
            for (int r = 0; r < 2; r++) {
                sleepCancelable(client.pollGapMs);
                if (!check.mayContinue()) throw new PreDispatchAbortException("stopped_or_account_changed");
                try {
                    after = findTask(client.listTasks(account, mp), code);
                    if (after != null && (after.optBoolean("claimed", false) || after.optBoolean("claimable", false))) {
                        break;
                    }
                } catch (Exception ignored) { }
            }
            if (after != null && after.optBoolean("claimed", false)) {
                return out.put("status", "done").put("claimed", true).put("message", actionMsg + " · 已入账");
            }
            if (after != null && after.optBoolean("claimable", false)) {
                return claimAndConfirm(client, account, code, out, actionMsg, check);
            }
            return out.put("status", "awaiting").put("claimed", false).put("message", actionMsg + " · 等待上游计分");
        } finally {
            client.popWriteGuard(prevGuard);
        }
    }

    private static JSONObject claimAndConfirm(Client client, JSONObject account, String code,
                                              JSONObject out, String prefix, ContinueCheck check) throws Exception {
        if (!check.mayContinue()) throw new PreDispatchAbortException("stopped_or_account_changed");
        String sep = (prefix == null || prefix.isEmpty()) ? "" : (prefix + " · ");
        try {
            ClaimResult cr = client.claimReward(account, code);
            String msg = cr.credit > 0
                    ? (sep + "已领奖 +" + cr.credit + " 积分")
                    : (cr.alreadyClaimed ? (sep + "已重复领取确认") : (sep + "已确认领奖"));
            return out.put("status", "done").put("claimed", true)
                    .put("credit", cr.credit).put("energy", cr.energy)
                    .put("message", msg);
        } catch (ClaimUnconfirmedException unconfirmed) {
            // Re-read upstream state once if allowed; only mark claimed=true if upstream confirms it.
            if (check.mayContinue()) {
                try {
                    JSONObject after = findTask(client.listTasks(account, isMPTask(code)), code);
                    if (after != null && after.optBoolean("claimed", false)) {
                        return out.put("status", "done").put("claimed", true)
                                .put("credit", 0).put("energy", 0)
                                .put("message", sep + "已回读确认领奖");
                    }
                } catch (Exception ignored) { }
            }
            return out.put("status", "awaiting").put("claimed", false)
                    .put("credit", 0).put("energy", 0)
                    .put("message", sep + "领奖结果待确认，请刷新核对");
        }
    }

    private static JSONObject findTask(List<JSONObject> list, String code) {
        for (JSONObject t : list) {
            if (code.equals(t.optString("task_code", ""))) return t;
        }
        return null;
    }

    private static String executeAction(Client client, JSONObject account, String code, JSONObject taskObj) throws Exception {
        switch (code) {
            case "first_buddy": {
                client.reportChatActivity(account, UUID.randomUUID().toString());
                sleepCancelable(client.reportGapMs);
                client.buddyAgreement(account);
                try {
                    client.buddyFirst(account);
                    return "已领养第一只 Buddy";
                } catch (ApiError err) {
                    if (err.httpStatus == 400 && err.getMessage() != null && err.getMessage().contains("first_buddy")) {
                        return "前置已上报，等待活跃门槛生效";
                    }
                    throw err;
                }
            }
            case "chat_5": {
                long target = Math.max(1, taskObj.optLong("target", 5));
                long need = Math.max(0, target - taskObj.optLong("current", 0));
                for (long i = 0; i < need; i++) {
                    client.reportChatActivity(account, UUID.randomUUID().toString());
                    if (i < need - 1) sleepCancelable(client.reportGapMs);
                }
                return "已补报 " + need + " 条对话活跃";
            }
            case "RichMeow_Chat": {
                String conv = UUID.randomUUID().toString();
                String req = newHex32();
                client.reportDesktopEvents(account, desktopChatSequence(conv, req, newHex32(), "fast-model", "fast-model"));
                return "已上报桌面对话事件链";
            }
            case "Buddy_App": {
                client.reportDesktopEvents(account, desktopBuddyAppSequence("cb_y5Dy46tPQGGWtueMxXbe", "企鹅教师助手"));
                return "已上报 Buddy 应用事件链";
            }
            case "automation_1": {
                client.reportDesktopEvents(account, Collections.singletonList(desktopAutomationCreateEvent("自动化")));
                return "已上报定时任务创建事件";
            }
            case "Library_read": {
                client.reportWebEvent(account, "web_element_click",
                        "https://www.workbuddy.cn/space/d/o0KWYeynteVv06UnAZqIFm",
                        "library_doc_intro_click", "WorkBuddy资料库介绍");
                return "已上报资料库阅读事件";
            }
            case "template_5": {
                String[][] tpls = {{"1", "深度研究"}, {"2", "周报生成"}, {"3", "竞品分析"}, {"4", "活动策划"}, {"5", "代码评审"}};
                for (int i = 0; i < tpls.length; i++) {
                    client.reportDesktopEvents(account, desktopTemplateUseSequence(
                            UUID.randomUUID().toString(), newHex32(), tpls[i][0], tpls[i][1]));
                    if (i < tpls.length - 1) sleepCancelable(client.templateGapMs);
                }
                return "已上报 5 组模板使用事件";
            }
            case "playbook_prompt": {
                client.reportDesktopEvents(account, desktopPlaybookPromptSequence(
                        UUID.randomUUID().toString(), newHex32(), "pm-gtm-launch-plan", "新产品上市 GTM 发布计划一页纸"));
                return "已上报灵感案例发送事件";
            }
            case "Hp_Appearance": {
                String themeKey = "theme-tkmw7j";
                client.setAppearanceTheme(account, themeKey);
                sleepCancelable(client.appearanceGapMs);
                client.reportDesktopEvents(account, Collections.singletonList(new JSONObject()
                        .put("eventCode", "appearance_skin_apply")
                        .put("action", "apply").put("source", "settings_close")
                        .put("id", themeKey).put("vipLevel", 0).put("series", "").put("type", "unknown")));
                return "已切换主题并上报皮肤生效";
            }
            case "Sequential_Tasks_1": {
                client.acceptTasks(account, Collections.singletonList(code), true);
                client.reportMPEvents(account, Collections.singletonList(miniChatSendEvent(UUID.randomUUID().toString())));
                return "已上报小程序首次对话";
            }
            case "Sequential_Tasks_2": {
                List<MarketExpert> experts = client.marketExpertList(account, "");
                if (experts.isEmpty()) throw new IOException("专家市场列表为空");
                client.acceptTasks(account, Collections.singletonList(code), true);
                client.reportMPEvents(account, Collections.singletonList(miniExpertUseEvent(experts.get(0))));
                return "已上报小程序专家对话";
            }
            case "Sequential_Tasks_3": {
                client.acceptTasks(account, Collections.singletonList(code), true);
                long target = Math.max(1, taskObj.optLong("target", 5));
                long need = Math.max(1, target - taskObj.optLong("current", 0));
                for (long i = 0; i < need; i++) {
                    client.reportMPEvents(account, Collections.singletonList(miniChatSendEvent(UUID.randomUUID().toString())));
                    if (i < need - 1) sleepCancelable(client.mpChatGapMs);
                }
                return "已上报小程序对话 " + need + " 次";
            }
            case "expert_5": {
                return runExpertBatch(client, account, "agent", 5);
            }
            case "Expert_team_use_3": {
                return runExpertBatch(client, account, "team", 3);
            }
            case "skill_1": {
                String[] chat = client.desktopChatWithExpert(account, "");
                String conv = chat[0], req = chat[1];
                String tail = req.length() > 8 ? req.substring(req.length() - 8) : req;
                String msgId = "msg-" + tail;
                List<JSONObject> events = desktopChatSequence(conv, req, msgId, "fast-model", "fast-model");
                for (JSONObject ev : events) {
                    if ("chat_message_response".equals(ev.optString("eventCode"))) {
                        ev.put("finishReason", "tool_calls");
                    }
                }
                events.add(new JSONObject()
                        .put("eventCode", "skill_info")
                        .put("id", "润泽小馆·日报撰写")
                        .put("skillId", "skill_2097350077599879168")
                        .put("skillVersion", "1.0.0")
                        .put("toolStatus", "success")
                        .put("fileCount", 56)
                        .put("source", "workbuddy-desktop")
                        .put("conversationId", conv).put("requestId", req).put("messageId", msgId)
                        .put("requestModelId", "fast-model").put("requestModelName", "fast-model")
                        .put("traceId", req));
                client.reportDesktopEvents(account, events);
                return "已完成真实短对话并上报技能加载";
            }
            case "Expert_lighthouse": {
                MarketExpert lh = new MarketExpert(LIGHTHOUSE_EXPERT_ID, "agent", "腾讯轻量云专家", "腾讯轻量云专家", "1.0.2", "expert-all");
                try {
                    for (MarketExpert e : client.marketExpertList(account, "agent")) {
                        if (LIGHTHOUSE_EXPERT_ID.equals(e.expertId)) { lh = e; break; }
                    }
                } catch (Exception ignored) { }
                client.reportDesktopEvents(account, desktopExpertSummonSequence(lh));
                String[] chat = client.desktopChatWithExpert(account, LIGHTHOUSE_EXPERT_ID);
                String conv = chat[0], req = chat[1];
                String tail = req.length() > 8 ? req.substring(req.length() - 8) : req;
                List<JSONObject> events = desktopChatSequence(conv, req, "msg-" + tail, "fast-model", "fast-model");
                for (JSONObject ev : events) {
                    if ("agent_task_created".equals(ev.optString("eventCode"))) {
                        ev.put("has_expert", true).put("expert_id", lh.expertId)
                                .put("expert_name", lh.displayNameZh).put("expert_industry_id", "");
                    }
                }
                events.add(desktopExpertActualUse(lh, conv, req, "LOCAL", true));
                client.reportDesktopEvents(account, events);
                return "已完成轻量云专家真实对话与使用上报";
            }
            default:
                throw new IOException("不支持的自动任务: " + code);
        }
    }

    private static String runExpertBatch(Client client, JSONObject account, String expertType, int count) throws Exception {
        List<MarketExpert> experts = client.marketExpertList(account, expertType);
        if (experts.isEmpty()) throw new IOException("专家市场列表为空");
        int ok = 0;
        for (int i = 0; i < experts.size() && ok < count; i++) {
            MarketExpert e = experts.get(i);
            try {
                client.reportDesktopEvents(account, desktopExpertSummonSequence(e));
                String[] chat = client.desktopChatWithExpert(account, e.expertId);
                String conv = chat[0], req = chat[1];
                String tail = req.length() > 8 ? req.substring(req.length() - 8) : req;
                List<JSONObject> events = desktopChatSequence(conv, req, "msg-" + tail, "fast-model", "fast-model");
                events.add(desktopExpertActualUse(e, conv, req, "craft", false));
                client.reportDesktopEvents(account, events);
                ok++;
                if (ok < count && i < experts.size() - 1) sleepCancelable(client.expertGapMs);
            } catch (InterruptedException | PreDispatchAbortException | WriteOutcomeUncertainException | InterruptedIOException stop) {
                throw stop;
            } catch (Exception ignored) {
                // continue to next expert
            }
        }
        if (ok < count) {
            throw new IOException("专家链仅成功 " + ok + "/" + count + " 位");
        }
        return "已完成 " + ok + " 位真实专家短对话与上报";
    }

    // -------------------------------------------------------------------------
    // Bounded Single-Thread Job Manager for GatewayServer
    // -------------------------------------------------------------------------

    /**
     * Read-only host view: intentionally exposes NO token-refresh method so growth tasks
     * can NEVER rotate one-use refresh tokens or trip GatewayServer's write-ahead refresh fence.
     */
    interface AccountHost {
        List<AccountEntry> listWorkBuddyAccounts();
        NetworkPolicy.Snapshot networkSnapshot();
    }

    static final class AccountEntry {
        final String provider;
        final String accountId;
        final String displayName;
        final JSONObject account;
        final boolean providerEnabled;

        AccountEntry(String provider, String accountId, String displayName, JSONObject account) {
            this(provider, accountId, displayName, account, true);
        }

        AccountEntry(String provider, String accountId, String displayName, JSONObject account, boolean providerEnabled) {
            this.provider = provider;
            this.accountId = accountId;
            this.displayName = displayName;
            this.account = account;
            this.providerEnabled = providerEnabled;
        }
    }

    static final class Manager {
        private final AccountHost host;
        private final Client client;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "workbuddy-autotask");
            t.setDaemon(true);
            return t;
        });

        private final Object lock = new Object();
        private Future<?> activeFuture;
        private boolean running;
        private boolean jobStarted;
        private long jobSeq;
        private boolean cancelRequested;
        private boolean shutdownFlag;
        private String mode = "run";
        private boolean includeTier2;
        private String currentAccountId = "";
        private String currentAccountName = "";
        private String currentTaskCode = "";
        private String currentTaskTitle = "";
        private int completedAccounts;
        private int totalAccounts;
        private long claimedCredit;
        private long claimedEnergy;
        private String statusMessage = "";
        private long updatedAt;
        private final Map<String, JSONObject> accountCache = new LinkedHashMap<>();
        private final List<String> recentLogs = new ArrayList<>();

        Manager(AccountHost host, WorkBuddyLogin.ConnectionFactory factory) {
            this.host = host;
            this.client = new Client(factory);
        }

        Client clientForTests() {
            return client;
        }

        private void appendLog(String line) {
            synchronized (lock) {
                recentLogs.add(line);
                while (recentLogs.size() > 30) recentLogs.remove(0);
                updatedAt = System.currentTimeMillis();
            }
        }

        JSONObject snapshot() {
            List<AccountEntry> entries = host.listWorkBuddyAccounts();
            JSONArray eligibleArr = new JSONArray();
            JSONArray ineligibleArr = new JSONArray();
            long totalClaimableCredit = 0;
            int totalPendingTasks = 0;

            synchronized (lock) {
                for (AccountEntry e : entries) {
                    String reason = ineligibilityReason(e.provider, e.account, e.providerEnabled);
                    if (!reason.isEmpty()) {
                        ineligibleArr.put(new JSONObject()
                                .put("accountId", e.accountId)
                                .put("provider", e.provider)
                                .put("displayName", e.displayName)
                                .put("reason", reason));
                        continue;
                    }
                    JSONObject cached = accountCache.get(e.accountId);
                    JSONObject item;
                    if (cached != null) {
                        item = new JSONObject(cached.toString());
                        item.put("displayName", e.displayName);
                    } else {
                        item = new JSONObject()
                                .put("accountId", e.accountId)
                                .put("provider", e.provider)
                                .put("displayName", e.displayName)
                                .put("loaded", false)
                                .put("tasks", new JSONArray())
                                .put("completedCount", 0)
                                .put("totalCount", CATALOG.size())
                                .put("claimableCount", 0)
                                .put("claimableCredit", 0)
                                .put("potentialCredit", 0);
                    }
                    totalClaimableCredit += item.optLong("potentialCredit", 0);
                    totalPendingTasks += item.optInt("claimableCount", 0);
                    eligibleArr.put(item);
                }

                JSONObject job = new JSONObject()
                        .put("running", running)
                        .put("cancelRequested", cancelRequested)
                        .put("mode", mode)
                        .put("includeTier2", includeTier2)
                        .put("currentAccountId", currentAccountId)
                        .put("currentAccountName", currentAccountName)
                        .put("currentTaskCode", currentTaskCode)
                        .put("currentTaskTitle", currentTaskTitle)
                        .put("completedAccounts", completedAccounts)
                        .put("totalAccounts", totalAccounts)
                        .put("claimedCredit", claimedCredit)
                        .put("claimedEnergy", claimedEnergy)
                        .put("message", statusMessage)
                        .put("updatedAt", updatedAt)
                        .put("logs", new JSONArray(recentLogs));

                return new JSONObject()
                        .put("ok", true)
                        .put("eligibleCount", eligibleArr.length())
                        .put("totalPotentialCredit", totalClaimableCredit)
                        .put("totalClaimableTasks", totalPendingTasks)
                        .put("accounts", eligibleArr)
                        .put("ineligible", ineligibleArr)
                        .put("job", job);
            }
        }

        JSONObject refresh(String targetAccountId) {
            List<AccountEntry> entries = host.listWorkBuddyAccounts();
            NetworkPolicy.Snapshot net = host.networkSnapshot();
            try (NetworkPolicy.Scope scope = NetworkPolicy.use(net)) {
                for (AccountEntry e : entries) {
                    if (!isEligible(e)) continue;
                    if (targetAccountId != null && !targetAccountId.isEmpty() && !targetAccountId.equals(e.accountId)) {
                        continue;
                    }
                    refreshOne(e);
                }
            }
            return snapshot();
        }

        private JSONObject refreshOne(AccountEntry e) {
            try {
                // Strictly read-only credential usage: never trigger OAuth refresh or consume one-use refresh tokens.
                List<JSONObject> tasks = client.listMergedTasks(e.account);
                JSONObject summary = summarizeAccount(e, tasks, "");
                synchronized (lock) {
                    accountCache.put(e.accountId, summary);
                }
                return summary;
            } catch (ApiError apiErr) {
                String msg = apiErr.httpStatus == 401
                        ? "当前访问凭据已过期（未触发刷新，不会影响账号在线状态），请先在首页刷新额度或重新登录"
                        : (apiErr.getMessage() == null ? "读取任务失败" : apiErr.getMessage());
                JSONObject summary = summarizeAccount(e, Collections.emptyList(), msg);
                synchronized (lock) {
                    accountCache.put(e.accountId, summary);
                }
                return summary;
            } catch (Exception err) {
                JSONObject summary = summarizeAccount(e, Collections.emptyList(),
                        err.getMessage() == null ? "读取任务失败" : err.getMessage());
                synchronized (lock) {
                    accountCache.put(e.accountId, summary);
                }
                return summary;
            }
        }

        private static JSONObject summarizeAccount(AccountEntry e, List<JSONObject> tasks, String error) {
            int completed = 0;
            int total = 0;
            int claimableCount = 0;
            long claimableCredit = 0;
            long potentialCredit = 0;
            JSONArray taskArr = new JSONArray();
            for (JSONObject t : tasks) {
                taskArr.put(t);
                if (t.optBoolean("locked", false)) continue;
                total++;
                boolean claimed = t.optBoolean("claimed", false);
                boolean claimable = t.optBoolean("claimable", false);
                long credit = t.optLong("credit", 0);
                if (claimed) {
                    completed++;
                } else {
                    if (claimable) {
                        claimableCount++;
                        claimableCredit += credit;
                    }
                    if (!t.optBoolean("manual_only", false) && (claimable || t.optBoolean("automated", false))) {
                        potentialCredit += credit;
                    }
                }
            }
            return new JSONObject()
                    .put("accountId", e.accountId)
                    .put("provider", e.provider)
                    .put("displayName", e.displayName)
                    .put("loaded", error.isEmpty())
                    .put("error", error)
                    .put("tasks", taskArr)
                    .put("completedCount", completed)
                    .put("totalCount", Math.max(total, completed))
                    .put("claimableCount", claimableCount)
                    .put("claimableCredit", claimableCredit)
                    .put("potentialCredit", potentialCredit)
                    .put("refreshedAt", System.currentTimeMillis());
        }

        JSONObject startJob(String reqMode, String targetAccountId, boolean reqIncludeTier2) {
            String normalizedMode = "claim_only".equals(reqMode) ? "claim_only" : "run";
            List<AccountEntry> entries = host.listWorkBuddyAccounts();
            List<AccountEntry> targets = new ArrayList<>();
            for (AccountEntry e : entries) {
                if (!isEligible(e)) continue;
                if (targetAccountId != null && !targetAccountId.isEmpty() && !targetAccountId.equals(e.accountId)) {
                    continue;
                }
                targets.add(e);
            }
            if (targets.isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "没有可执行的 WorkBuddy 国内个人 OAuth 账号");
            }
            synchronized (lock) {
                if (shutdownFlag) {
                    return new JSONObject().put("ok", false).put("error", "任务服务已停止");
                }
                if (running) {
                    return new JSONObject().put("ok", false).put("busy", true).put("error", "已有任务正在执行中，请等待完成或先点击停止");
                }
                running = true;
                jobStarted = false;
                final long mySeq = ++jobSeq;
                cancelRequested = false;
                mode = normalizedMode;
                includeTier2 = reqIncludeTier2;
                completedAccounts = 0;
                totalAccounts = targets.size();
                claimedCredit = 0;
                claimedEnergy = 0;
                currentAccountId = targets.get(0).accountId;
                currentAccountName = targets.get(0).displayName;
                currentTaskCode = "";
                currentTaskTitle = "准备中…";
                statusMessage = "已开始执行 (" + targets.size() + " 个账号)";
                recentLogs.clear();
                updatedAt = System.currentTimeMillis();
                try {
                    activeFuture = executor.submit(() -> executeJob(mySeq, targets, normalizedMode, reqIncludeTier2));
                } catch (RuntimeException rejected) {
                    running = false;
                    statusMessage = "任务提交失败";
                    return new JSONObject().put("ok", false).put("error", "任务执行器不可用");
                }
            }
            return snapshot();
        }

        JSONObject stopJob() {
            synchronized (lock) {
                cancelRequested = true;
                if (running) {
                    if (activeFuture != null) activeFuture.cancel(true);
                    if (!jobStarted) {
                        // Cancelled while still queued before executeJob entered: clear running immediately
                        // so the manager never gets stuck in running=true and can start new jobs cleanly.
                        running = false;
                        currentTaskCode = "";
                        currentTaskTitle = "";
                        statusMessage = "已停止（任务尚未开始）";
                        updatedAt = System.currentTimeMillis();
                    } else {
                        statusMessage = "正在停止（在途请求结果未知，停止后请刷新核对）…";
                    }
                }
            }
            client.cancelActiveConnection();
            return snapshot();
        }

        void shutdown() {
            synchronized (lock) {
                shutdownFlag = true;
                cancelRequested = true;
                if (activeFuture != null) activeFuture.cancel(true);
                running = false;
                currentTaskCode = "";
                currentTaskTitle = "";
                statusMessage = "网关已停止或配置已恢复，任务已终止";
                updatedAt = System.currentTimeMillis();
            }
            client.cancelActiveConnection();
            executor.shutdownNow();
        }

        private AccountEntry resolveLiveEligibleAccount(String accountId) {
            if (accountId == null || accountId.isEmpty()) return null;
            for (AccountEntry e : host.listWorkBuddyAccounts()) {
                if (accountId.equals(e.accountId) && isEligible(e)) {
                    return e;
                }
            }
            return null;
        }

        private boolean matchesLiveAccount(String accountId, String expectedAccess, String expectedUid) {
            AccountEntry live = resolveLiveEligibleAccount(accountId);
            if (live == null || live.account == null) return false;
            String curAccess = live.account.optString("access", live.account.optString("token", "")).trim();
            String curUid = live.account.optString("uid", "").trim();
            return expectedAccess.equals(curAccess) && expectedUid.equals(curUid);
        }

        private void executeJob(long mySeq, List<AccountEntry> targets, String jobMode, boolean jobIncludeTier2) {
            synchronized (lock) {
                if (cancelRequested || shutdownFlag || mySeq != jobSeq) {
                    if (mySeq == jobSeq) {
                        running = false;
                        currentTaskCode = "";
                        currentTaskTitle = "";
                        statusMessage = "已停止（任务尚未开始）";
                        updatedAt = System.currentTimeMillis();
                    }
                    return;
                }
                jobStarted = true;
            }
            boolean claimOnly = "claim_only".equals(jobMode);
            String fatalError = "";
            boolean inFlightStopped = false;
            boolean hasUncertainOutcome = false;
            client.enterJobThread();
            try {
                NetworkPolicy.Snapshot net = host.networkSnapshot();
                try (NetworkPolicy.Scope scope = NetworkPolicy.use(net)) {
                    for (int i = 0; i < targets.size(); i++) {
                        if (isCancelled()) break;
                        AccountEntry queued = targets.get(i);
                        AccountEntry entry = resolveLiveEligibleAccount(queued.accountId);
                        if (entry == null) {
                            appendLog(queued.displayName + "：账号已删除或停用，已跳过");
                            synchronized (lock) { completedAccounts = i + 1; }
                            continue;
                        }
                        JSONObject fresh = eSnapshot(entry);
                        final String expectedAccess = fresh.optString("access", fresh.optString("token", "")).trim();
                        final String expectedUid = fresh.optString("uid", "").trim();
                        final ContinueCheck accountGuard = () -> !isCancelled() && matchesLiveAccount(entry.accountId, expectedAccess, expectedUid);

                        setProgress(entry.accountId, entry.displayName, "", "读取任务列表…", i, targets.size());
                        List<JSONObject> tasks;
                        try {
                            tasks = client.listMergedTasks(fresh);
                        } catch (InterruptedIOException ie) {
                            if ((ie instanceof java.net.SocketTimeoutException) && !isCancelled()) {
                                appendLog(entry.displayName + "：读取任务超时（" + safeMsg(ie) + "）");
                                synchronized (lock) { completedAccounts = i + 1; }
                                continue;
                            }
                            inFlightStopped = true;
                            break;
                        } catch (ApiError apiErr) {
                            String msg = apiErr.httpStatus == 401 ? "当前访问凭据已过期（未触发刷新）" : safeMsg(apiErr);
                            appendLog(entry.displayName + "：读取任务失败（" + msg + "）");
                            synchronized (lock) { completedAccounts = i + 1; }
                            continue;
                        } catch (Exception err) {
                            appendLog(entry.displayName + "：读取任务失败（" + safeMsg(err) + "）");
                            synchronized (lock) { completedAccounts = i + 1; }
                            continue;
                        }
                        if (!accountGuard.mayContinue()) {
                            if (isCancelled()) break;
                            appendLog(entry.displayName + "：账号状态或凭据已变更，已跳过当前账号");
                            synchronized (lock) { completedAccounts = i + 1; }
                            continue;
                        }

                        // Batch accept pending tasks in run mode
                        ContinueCheck prevGuard = client.pushWriteGuard(accountGuard);
                        try {
                            if (!claimOnly) {
                                List<String> pendingNormal = new ArrayList<>();
                                List<String> pendingMp = new ArrayList<>();
                                for (JSONObject t : tasks) {
                                    if (t.optBoolean("locked", false) || t.optBoolean("claimed", false)) continue;
                                    String st = t.optString("accept_status", "");
                                    if (st.isEmpty() || "not_accepted".equalsIgnoreCase(st)) {
                                        String c = t.optString("task_code", "");
                                        if (isMPTask(c)) pendingMp.add(c);
                                        else pendingNormal.add(c);
                                    }
                                }
                                try {
                                    if (!pendingNormal.isEmpty() && accountGuard.mayContinue()) client.acceptTasks(fresh, pendingNormal, false);
                                    if (!pendingMp.isEmpty() && accountGuard.mayContinue()) client.acceptTasks(fresh, pendingMp, true);
                                } catch (PreDispatchAbortException pre) {
                                    if (isCancelled()) break;
                                    appendLog(entry.displayName + "：账号已停用、删除或换号，已跳过当前账号");
                                    synchronized (lock) { completedAccounts = i + 1; }
                                    continue;
                                } catch (WriteOutcomeUncertainException ue) {
                                    if (ue.byStop || isCancelled()) {
                                        inFlightStopped = true;
                                        appendLog(entry.displayName + " · 领取任务：已中止（请求已发出但未收到确认，结果未知，请刷新核对）");
                                        break;
                                    }
                                    hasUncertainOutcome = true;
                                    appendLog(entry.displayName + " · 领取任务：结果未知，请刷新核对（" + safeMsg(ue.getCause() != null ? ue.getCause() : ue) + "）");
                                } catch (InterruptedIOException ie) {
                                    if ((ie instanceof java.net.SocketTimeoutException) && !isCancelled()) {
                                        hasUncertainOutcome = true;
                                        appendLog(entry.displayName + " · 领取任务：超时结果未知，请刷新核对（" + safeMsg(ie) + "）");
                                    } else {
                                        inFlightStopped = true;
                                        break;
                                    }
                                } catch (Exception ignored) { }
                            }

                            // Order tasks by catalog order, followed by any dynamic claimable tasks
                            List<JSONObject> ordered = orderTasks(tasks);
                            for (JSONObject t : ordered) {
                                if (!accountGuard.mayContinue()) {
                                    if (!isCancelled()) appendLog(entry.displayName + "：账号已停用、删除或换号，已停止后续动作");
                                    break;
                                }
                                String code = t.optString("task_code", "");
                                String title = t.optString("title", code);
                                if (t.optBoolean("claimed", false) || t.optBoolean("locked", false) || isManualOnly(code)) {
                                    continue;
                                }
                                if (claimOnly && !t.optBoolean("claimable", false)) continue;
                                TaskSpec spec = specOf(code);
                                if (!claimOnly && !t.optBoolean("claimable", false)) {
                                    if (spec == null) continue;
                                    if (spec.tier == 2 && !jobIncludeTier2) continue;
                                }
                                setProgress(entry.accountId, entry.displayName, code, title, i, targets.size());
                                try {
                                    JSONObject res = runSingleTask(client, fresh, t, jobIncludeTier2, claimOnly, accountGuard);
                                    long c = res.optLong("credit", 0);
                                    long e = res.optLong("energy", 0);
                                    if (c > 0 || e > 0) {
                                        synchronized (lock) {
                                            claimedCredit += c;
                                            claimedEnergy += e;
                                        }
                                    }
                                    appendLog(entry.displayName + " · " + title + "：" + res.optString("message", res.optString("status", "")));
                                } catch (PreDispatchAbortException pre) {
                                    if (!isCancelled()) appendLog(entry.displayName + " · " + title + "：账号已停用、删除或换号，已停止后续请求");
                                    break;
                                } catch (WriteOutcomeUncertainException ue) {
                                    if (ue.byStop || isCancelled()) {
                                        inFlightStopped = true;
                                        appendLog(entry.displayName + " · " + title + "：已中止（请求已发出但未收到确认，结果未知，请刷新核对）");
                                        if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
                                        break;
                                    }
                                    hasUncertainOutcome = true;
                                    appendLog(entry.displayName + " · " + title + "：结果未知，请刷新核对（" + safeMsg(ue.getCause() != null ? ue.getCause() : ue) + "）");
                                } catch (InterruptedException ie) {
                                    appendLog(entry.displayName + " · " + title + "：已在等待间隙停止");
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (InterruptedIOException ie) {
                                    if ((ie instanceof java.net.SocketTimeoutException) && !isCancelled()) {
                                        hasUncertainOutcome = true;
                                        appendLog(entry.displayName + " · " + title + "：超时结果未知，请刷新核对（" + safeMsg(ie) + "）");
                                        continue;
                                    }
                                    inFlightStopped = true;
                                    appendLog(entry.displayName + " · " + title + "：已中止（若请求已发出则结果未知，请刷新核对）");
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception err) {
                                    if (isCancelled()) {
                                        inFlightStopped = true;
                                        appendLog(entry.displayName + " · " + title + "：已中止（若请求已发出则结果未知，请刷新核对）");
                                        break;
                                    }
                                    appendLog(entry.displayName + " · " + title + "：失败（" + safeMsg(err) + "）");
                                }
                            }
                        } finally {
                            client.popWriteGuard(prevGuard);
                        }

                        // Refresh account cache after processing only if account still unchanged and not cancelled
                        if (accountGuard.mayContinue()) {
                            refreshOne(entry);
                        }
                        synchronized (lock) {
                            completedAccounts = i + 1;
                        }
                    }
                }
            } catch (Throwable err) {
                fatalError = safeMsg(err);
                appendLog("执行异常终止：" + fatalError);
            } finally {
                client.leaveJobThread();
                synchronized (lock) {
                    if (mySeq == jobSeq) {
                        boolean stopped = cancelRequested || shutdownFlag || Thread.currentThread().isInterrupted();
                        running = false;
                        currentTaskCode = "";
                        currentTaskTitle = "";
                        if (!fatalError.isEmpty()) {
                            statusMessage = "执行失败：" + fatalError;
                        } else if (stopped) {
                            statusMessage = (inFlightStopped || hasUncertainOutcome)
                                    ? "已手动停止（在途动作结果未知，请刷新核对；本次已确认领取 +" + claimedCredit + " 积分" + (claimedEnergy > 0 ? " +" + claimedEnergy + " 能量" : "") + "）"
                                    : "已手动停止（本次累计领取 +" + claimedCredit + " 积分" + (claimedEnergy > 0 ? " +" + claimedEnergy + " 能量" : "") + "）";
                        } else if (hasUncertainOutcome) {
                            statusMessage = "执行完成（部分动作结果未知，请刷新核对；本次已确认领取 +" + claimedCredit + " 积分" + (claimedEnergy > 0 ? " +" + claimedEnergy + " 能量" : "") + "）";
                        } else {
                            statusMessage = "执行完成（本次累计领取 +" + claimedCredit + " 积分" + (claimedEnergy > 0 ? " +" + claimedEnergy + " 能量" : "") + "）";
                        }
                        updatedAt = System.currentTimeMillis();
                    }
                }
            }
        }

        private boolean isCancelled() {
            synchronized (lock) {
                return cancelRequested || Thread.currentThread().isInterrupted();
            }
        }

        private void setProgress(String accId, String accName, String code, String title, int done, int total) {
            synchronized (lock) {
                currentAccountId = accId;
                currentAccountName = accName;
                currentTaskCode = code;
                currentTaskTitle = title;
                completedAccounts = done;
                totalAccounts = total;
                statusMessage = "正在执行 · " + accName + " (" + (done + 1) + "/" + total + ") · " + title;
                updatedAt = System.currentTimeMillis();
            }
        }

        private static List<JSONObject> orderTasks(List<JSONObject> tasks) {
            Map<String, JSONObject> byCode = new LinkedHashMap<>();
            for (JSONObject t : tasks) byCode.put(t.optString("task_code", ""), t);
            List<JSONObject> out = new ArrayList<>();
            for (TaskSpec s : CATALOG) {
                JSONObject hit = byCode.remove(s.code);
                if (hit != null) out.add(hit);
            }
            out.addAll(byCode.values());
            return out;
        }

        private static JSONObject eSnapshot(AccountEntry entry) {
            return entry == null || entry.account == null ? new JSONObject() : new JSONObject(entry.account.toString());
        }

        private static String safeMsg(Throwable t) {
            String m = t == null ? "" : t.getMessage();
            if (m == null || m.trim().isEmpty()) return "网络或上游异常";
            m = m.trim();
            return m.length() > 90 ? m.substring(0, 90) + "…" : m;
        }
    }
}
