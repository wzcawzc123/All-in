package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;

/**
 * 离线契约测试：不联网、不启动 Android WebView，只验证豆包签到状态机。
 * 运行：com.gaeul.allin.DoubaoCheckinOfflineTest
 */
public final class DoubaoCheckinOfflineTest {
    public static void main(String[] args) throws Exception {
        supportedProviders();
        pageParsing();
        statusMapping();
        checkinSuccess();
        checkinAlreadyDone();
        checkinFailure();
        checkinUnknown();
        checkinVerification();
        checkinAuth();
        checkinCaptcha();
        System.out.println("DoubaoCheckinOfflineTest OK");
    }

    private static void supportedProviders() throws Exception {
        require(CheckinCenter.SUPPORTED.contains("doubao"), "doubao must be registered");
        JSONObject account = account();
        require(CheckinCenter.eligible("doubao", account), "cookie account should be eligible");
        require(!CheckinCenter.eligible("doubao", new JSONObject(account.toString()).put("type", "api")),
                "api accounts are not cookie-checkin eligible");
        require(!CheckinCenter.eligible("doubao", new JSONObject().put("id", "d1")),
                "empty cookie must not be eligible");
    }

    private static void pageParsing() {
        DoubaoCheckin.State sign = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/pc/agent-explore\",\"text\":\"做任务 领额度\",\"buttons\":[\"立即签到\"]}");
        require(sign.loaded && sign.signIn && !sign.checkedIn, "parse available sign-in");

        DoubaoCheckin.State done = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/pc/agent-explore\",\"text\":\"今日已签到\",\"buttons\":[\"已签到\"]}");
        require(done.loaded && done.checkedIn && !done.signIn, "parse checked-in state");

        DoubaoCheckin.State failed = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/pc/agent-explore\",\"text\":\"签到失败，请稍后重试\",\"buttons\":[\"立即签到\"]}");
        require(failed.failure && failed.message.contains("签到失败"), "parse failure toast");

        DoubaoCheckin.State login = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/passport/login\",\"text\":\"扫码登录\",\"buttons\":[]}");
        require(login.loaded && login.loginRequired, "parse login page");

        DoubaoCheckin.State loginButton = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/pc/agent-explore\",\"text\":\"登录后使用完整功能\",\"buttons\":[\"登录\"]}");
        require(loginButton.loaded && loginButton.loginRequired, "parse login button");

        DoubaoCheckin.State captcha = DoubaoCheckin.State.parse(
                "{\"url\":\"https://www.doubao.com/pc/agent-explore\",\"text\":\"安全验证 拖动滑块\",\"buttons\":[\"立即签到\"]}");
        require(captcha.captcha, "parse captcha state");

        require(!DoubaoCheckin.State.parse("not json").loaded, "malformed page JSON must be unloaded");
    }

    private static void statusMapping() throws Exception {
        JSONObject available = DoubaoCheckin.status(account(), page(state(false, true), null, null, 0));
        require("not_checked".equals(available.optString("outcome")), "available status");
        require(Boolean.TRUE.equals(available.opt("verified")), "available status is verified");
        require("official_page".equals(available.optString("evidenceType")), "available evidence type");

        JSONObject done = DoubaoCheckin.status(account(), page(state(true, false), null, null, 0));
        require("done".equals(done.optString("outcome")), "done status");
        require(Boolean.TRUE.equals(done.opt("verified")), "done status is verified");

        JSONObject inactive = DoubaoCheckin.status(account(), page(state(false, false), null, null, 0));
        require("inactive".equals(inactive.optString("outcome")), "no task must be inactive");
        require(Boolean.FALSE.equals(inactive.opt("claimPossible")), "inactive cannot claim");

        JSONObject login = DoubaoCheckin.status(account(), page(loginState(), null, null, 0));
        require("auth_required".equals(login.optString("outcome")), "login status");

        JSONObject captcha = DoubaoCheckin.status(account(), page(captchaState(), null, null, 0));
        require("captcha".equals(captcha.optString("outcome")), "captcha status");
    }

    private static void checkinSuccess() throws Exception {
        FakePage page = page(state(false, true), state(false, false, true, false), state(false, false, false, false), 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("claimed".equals(result.optString("outcome")), "successful click must be claimed");
        require(Boolean.TRUE.equals(result.opt("verified")), "successful click must be verified");
        require(page.clicks == 1, "exactly one click");
        require("official_toast".equals(result.optString("evidenceType")), "toast evidence");
    }

    private static void checkinAlreadyDone() throws Exception {
        FakePage page = page(state(true, false), null, null, 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("done".equals(result.optString("outcome")), "already checked in");
        require(Boolean.TRUE.equals(result.opt("verified")), "done is verified");
        require(page.clicks == 0, "must not click when already checked in");
    }

    private static void checkinFailure() throws Exception {
        FakePage page = page(state(false, true), failureState(), null, 1);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("failed".equals(result.optString("outcome")), "failure outcome");
        require(Boolean.TRUE.equals(result.opt("safeToRetry")), "failure can retry");
        require(Boolean.TRUE.equals(result.opt("claimPossible")), "failure keeps claim possible");
        require(result.optString("message").contains("签到失败"), "failure message");
    }

    private static void checkinUnknown() throws Exception {
        FakePage page = page(state(false, true), state(false, true), state(false, true), 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("unknown".equals(result.optString("outcome")), "unconfirmed click must be unknown");
        require(Boolean.TRUE.equals(result.opt("retryBlocked")), "unknown blocks duplicate");
        require(Boolean.FALSE.equals(result.opt("verified")), "unknown is not verified");
    }

    private static void checkinVerification() throws Exception {
        FakePage page = page(state(false, true), state(false, true), state(true, false), 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("claimed".equals(result.optString("outcome")), "delayed status confirmation");
        require("official_page".equals(result.optString("evidenceType")), "status evidence wins");
    }

    private static void checkinAuth() throws Exception {
        FakePage page = page(loginState(), null, null, 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("auth_required".equals(result.optString("outcome")), "auth required");
        require(page.clicks == 0, "must not click logged-out page");
    }

    private static void checkinCaptcha() throws Exception {
        FakePage page = page(state(false, true), captchaState(), null, 0);
        JSONObject result = DoubaoCheckin.checkin(account(), page);
        require("captcha".equals(result.optString("outcome")), "captcha blocks claim");
        require(Boolean.FALSE.equals(result.opt("claimPossible")), "captcha cannot claim");
        require(page.clicks == 1, "one click reached captcha");
    }

    private static JSONObject account() {
        return new JSONObject().put("id", "doubao-test").put("cookie",
                "ttwid=abc; sessionid=1234567890abcdef; passport_csrf_token=xyz");
    }

    private static FakePage page(DoubaoCheckin.State open, DoubaoCheckin.State click,
                                 DoubaoCheckin.State observe, int clicks) {
        return new FakePage(open, click, observe, clicks);
    }

    private static DoubaoCheckin.State state(boolean checkedIn, boolean signIn) {
        DoubaoCheckin.State state = new DoubaoCheckin.State();
        state.loaded = true;
        state.url = DoubaoCheckin.PAGE_URL;
        state.text = checkedIn ? "今日已签到" : "做任务 领额度";
        state.checkedIn = checkedIn;
        state.signIn = signIn;
        return state;
    }

    private static DoubaoCheckin.State state(boolean checkedIn, boolean signIn, boolean success, boolean failure) {
        DoubaoCheckin.State state = state(checkedIn, signIn);
        state.success = success;
        state.failure = failure;
        if (failure) state.message = "签到失败，请稍后重试";
        return state;
    }

    private static DoubaoCheckin.State failureState() {
        return state(false, false, false, true);
    }

    private static DoubaoCheckin.State loginState() {
        DoubaoCheckin.State state = new DoubaoCheckin.State();
        state.loaded = true;
        state.url = "https://www.doubao.com/passport/login";
        state.text = "扫码登录";
        state.loginRequired = true;
        return state;
    }

    private static DoubaoCheckin.State captchaState() {
        DoubaoCheckin.State state = new DoubaoCheckin.State();
        state.loaded = true;
        state.url = DoubaoCheckin.PAGE_URL;
        state.text = "安全验证 拖动滑块";
        state.captcha = true;
        return state;
    }

    private static final class FakePage implements DoubaoCheckin.Page {
        private final DoubaoCheckin.State open, click, observe;
        int clicks;

        FakePage(DoubaoCheckin.State open, DoubaoCheckin.State click, DoubaoCheckin.State observe, int clicks) {
            this.open = open;
            this.click = click;
            this.observe = observe;
            this.clicks = clicks;
        }

        @Override public DoubaoCheckin.State open(JSONObject account) {
            return open;
        }

        @Override public DoubaoCheckin.State click() {
            clicks++;
            return click;
        }

        @Override public DoubaoCheckin.State observe() {
            return observe;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
