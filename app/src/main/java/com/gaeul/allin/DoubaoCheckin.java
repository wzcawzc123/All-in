package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.IOException;

/**
 * 豆包工作电脑版「做任务 领额度」每日签到。
 *
 * <p>豆包活动接口由 PC 客户端的安全 SDK 签名，纯 HTTP 复制会得到
 * {@code 710010202 common invalid param}；因此这里不伪造签名，而是通过 App 内 WebView
 * 加载官方活动页，读取页面状态并点击官方「立即签到」按钮。测试只依赖 {@link Page} 接口。</p>
 */
public final class DoubaoCheckin {
    public static final String PAGE_URL = "https://www.doubao.com/pc/agent-explore?enter_method=fixed_entry";

    private DoubaoCheckin() {}

    /** 页面驱动接口：生产环境是 WebView，离线测试注入假的页面状态。 */
    public interface Page extends AutoCloseable {
        State open(JSONObject account) throws Exception;

        State click() throws Exception;

        State observe() throws Exception;

        @Override
        default void close() {}
    }

    /** WebView 页面探测结果。 */
    static final class State {
        boolean loaded;
        boolean checkedIn;
        boolean signIn;
        boolean loginRequired;
        boolean captcha;
        boolean success;
        boolean failure;
        String url = "";
        String text = "";
        String message = "";

        static State parse(String raw) {
            State state = new State();
            if (raw == null || raw.trim().isEmpty()) return state;
            try {
                JSONObject value = new JSONObject(raw);
                state.url = value.optString("url", "");
                state.text = value.optString("text", "");
                JSONArray buttons = value.optJSONArray("buttons");
                boolean exactSign = false, exactDone = false, exactLogin = false;
                if (buttons != null) {
                    for (int i = 0; i < buttons.length(); i++) {
                        String label = buttons.optString(i, "").trim();
                        if (label.contains("立即签到") || "去签到".equals(label) || "签到".equals(label))
                            exactSign = true;
                        if (label.contains("已签到")) exactDone = true;
                        if ("登录".equals(label) || "立即登录".equals(label) || "去登录".equals(label)
                                || "登录/注册".equals(label)) exactLogin = true;
                    }
                }
                String text = state.text == null ? "" : state.text;
                state.loaded = state.url.contains("doubao.com") && (!text.trim().isEmpty() || (buttons != null && buttons.length() > 0));
                state.checkedIn = exactDone || text.contains("今日已签到") || text.contains("今天已签到") || text.contains("已连续签到");
                state.signIn = exactSign && !state.checkedIn;
                state.loginRequired = !state.signIn && !state.checkedIn
                        && (state.url.contains("login") || state.url.contains("passport")
                        || text.contains("扫码登录") || text.contains("手机号登录") || text.contains("请先登录")
                        || text.contains("登录后") || exactLogin);
                state.captcha = text.contains("安全验证") || text.contains("人机验证")
                        || text.contains("拖动滑块") || text.contains("验证码");
                state.success = text.contains("签到成功") || text.contains("签到奖励已到账");
                state.failure = text.contains("签到失败") || text.contains("签到未成功");
                if (state.failure) {
                    int at = text.indexOf("签到失败");
                    if (at < 0) at = text.indexOf("签到未成功");
                    int end = Math.min(text.length(), at + 80);
                    state.message = text.substring(Math.max(0, at), end).replace('\n', ' ').trim();
                }
                return state;
            } catch (Exception ignored) {
                return state;
            }
        }
    }

    public static JSONObject status(JSONObject account, Page page) throws Exception {
        if (page == null) throw new IOException("doubao page required");
        State state;
        try {
            state = page.open(account);
        } catch (Exception failure) {
            return failed("豆包活动页加载失败：" + safeMessage(failure.getMessage()), true, false, null);
        }
        return statusOf(state);
    }

    public static JSONObject checkin(JSONObject account, Page page) throws Exception {
        if (page == null) throw new IOException("doubao page required");
        State before;
        try {
            before = page.open(account);
        } catch (Exception failure) {
            return failed("豆包活动页加载失败：" + safeMessage(failure.getMessage()), true, true, null);
        }
        if (!before.loaded) return failed("豆包活动页未完整加载", true, true, before);
        if (before.loginRequired) return authRequired();
        if (before.checkedIn) {
            return new JSONObject().put("outcome", "done").put("verified", true)
                    .put("evidenceType", "official_page").put("claimPossible", false)
                    .put("pagesRead", 1).put("message", "今日已签到，无需重复签到");
        }
        if (!before.signIn) {
            return new JSONObject().put("outcome", "inactive").put("verified", false)
                    .put("evidenceType", "official_page").put("claimPossible", false)
                    .put("pagesRead", 1).put("message", "活动页未出现每日签到任务，可能未开放或账号不参与");
        }

        State after;
        try {
            after = page.click();
        } catch (Exception failure) {
            return unknown("点击签到后页面未返回结果；今天不会重复发送", true);
        }
        if (after == null) return unknown("点击签到后页面未返回结果；今天不会重复发送", true);
        if (after.loginRequired) return authRequired();
        if (after.captcha) {
            return new JSONObject().put("outcome", "captcha").put("verified", false)
                    .put("claimPossible", false).put("pagesRead", 1)
                    .put("message", "豆包活动页要求安全验证，请在豆包客户端完成验证后重试");
        }
        if (after.checkedIn || after.success) return claimed(after);
        if (after.failure) {
            return failed(after.message.isEmpty() ? "豆包签到失败，请稍后重试" : after.message, true, true, after);
        }

        // 点击后可能还有延迟刷新，再读一次，避免把已成功误判成未知。
        State confirm;
        try {
            confirm = page.observe();
        } catch (Exception failure) {
            confirm = after;
        }
        if (confirm != null) {
            if (confirm.checkedIn || confirm.success) return claimed(confirm);
            if (confirm.failure) return failed(confirm.message.isEmpty() ? "豆包签到失败，请稍后重试" : confirm.message, true, true, confirm);
            if (confirm.captcha) {
                return new JSONObject().put("outcome", "captcha").put("verified", false)
                        .put("claimPossible", false).put("pagesRead", 1)
                        .put("message", "豆包活动页要求安全验证，请在豆包客户端完成验证后重试");
            }
        }
        return unknown("签到请求已发出，活动页未确认结果；今天不会重复发送", true);
    }

    private static JSONObject statusOf(State state) {
        if (state == null || !state.loaded) return failed("豆包活动页未完整加载", true, false, state);
        if (state.loginRequired) return authRequired();
        if (state.captcha) {
            return new JSONObject().put("outcome", "captcha").put("verified", false)
                    .put("claimPossible", false).put("pagesRead", 1)
                    .put("message", "豆包活动页要求安全验证，请在豆包客户端完成验证后重试");
        }
        if (state.checkedIn) {
            return new JSONObject().put("outcome", "done").put("verified", true)
                    .put("evidenceType", "official_page").put("pagesRead", 1)
                    .put("message", "今日已签到，无需重复签到");
        }
        if (state.signIn) {
            return new JSONObject().put("outcome", "not_checked").put("verified", true)
                    .put("evidenceType", "official_page").put("pagesRead", 1)
                    .put("message", "今日尚未签到，可以点击签到");
        }
        return new JSONObject().put("outcome", "inactive").put("verified", false)
                .put("evidenceType", "official_page").put("claimPossible", false).put("pagesRead", 1)
                .put("message", "活动页未出现每日签到任务，可能未开放或账号不参与");
    }

    private static JSONObject claimed(State state) {
        return new JSONObject().put("outcome", "claimed").put("verified", true)
                .put("evidenceType", state.checkedIn ? "official_page" : "official_toast")
                .put("claimPossible", false).put("pagesRead", 1)
                .put("message", "豆包签到成功，奖励以官方活动页为准");
    }

    private static JSONObject authRequired() {
        return new JSONObject().put("outcome", "auth_required").put("verified", false)
                .put("claimPossible", false).put("pagesRead", 1)
                .put("message", "豆包登录态无效，请重新登录后再签到");
    }

    private static JSONObject failed(String message, boolean retry, boolean claimPossible, State state) {
        JSONObject result = new JSONObject().put("outcome", "failed").put("verified", false)
                .put("safeToRetry", retry).put("claimPossible", claimPossible).put("pagesRead", 1)
                .put("message", message == null || message.isEmpty() ? "豆包签到失败，请稍后重试" : message);
        if (state != null && state.loaded) result.put("evidenceType", "official_page");
        return result;
    }

    private static JSONObject unknown(String message, boolean claimPossible) {
        return new JSONObject().put("outcome", "unknown").put("verified", false)
                .put("claimPossible", claimPossible).put("retryBlocked", true).put("pagesRead", 1)
                .put("message", message);
    }

    private static String safeMessage(String message) {
        if (message == null) return "未知错误";
        String value = message.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
