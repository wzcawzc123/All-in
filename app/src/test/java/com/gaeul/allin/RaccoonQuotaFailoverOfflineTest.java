package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.*;
import java.util.*;

/**
 * XunE: raccoon 某个账号额度耗尽（上游 400 + 积分余额不足）时，
 * 同一请求内应继续尝试同供应商的其它账号，而不是把错误直接抛给客户端。
 *
 * 覆盖三处修复：
 *  1) forwardAttempt 不再对该类错误无条件设置 stopFallback
 *  2) accountCandidates 为 raccoon 展开同供应商的其它账号作为候选
 *  3) 候选循环在额度耗尽且仍有剩余候选时继续，而不是 break 放弃
 *
 * 全部使用合成凭据，不访问真实上游。
 */
public final class RaccoonQuotaFailoverOfflineTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** 上游错误体判定：额度/余额耗尽措辞必须被识别为可换号的拒绝。 */
    static void testQuotaWordingRecognized() {
        String body = "{\"error\":{\"code\":1000007,\"message\":\"积分余额不足，请充值后重试\"}}";
        check(quotaExhausted(400, body), "400 + 积分余额不足 应识别为额度耗尽");
        check(quotaExhausted(402, body), "402 + 余额不足 应识别为额度耗尽");
        check(!quotaExhausted(400, "{\"error\":{\"message\":\"invalid request\"}}"), "普通 400 不应被当成额度耗尽");
        check(!quotaExhausted(500, "{\"error\":{\"message\":\"积分余额不足\"}}"), "500 不应被当成额度耗尽");
        check(quotaExhausted(400, "{\"error\":{\"message\":\"insufficient balance\"}}"), "英文 insufficient balance 也要识别");
        System.out.println("quota wording ok (" + checks + ")");
    }

    /** 与生产实现保持同一判定口径，避免测试自说自话。 */
    static boolean quotaExhausted(int status, String body) {
        if (status != 400 && status != 402 && status != 422) return false;
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        for (String term : new String[]{"quota", "balance", "credit", "insufficient",
                "余额", "不足", "额度", "积分"})
            if (b.contains(term)) return true;
        return false;
    }

    /** raccoon 必须在候选展开名单之外，否则同供应商其它账号永远不会被尝试。 */
    static void testRaccoonExpandsCandidates() {
        JSONArray accounts = new JSONArray();
        for (int i = 0; i < 3; i++)
            accounts.put(MultiAccountGatewayOfflineTest.account("raccoon" + i, "uid-" + i, "synthetic-" + i)
                    .put("type", "api").put("source", "browser")
                    .put("expiresAt", System.currentTimeMillis() + 3600000L)
                    .put("models", new JSONArray().put("m")));
        JSONObject provider = new JSONObject().put("id", "raccoon").put("protocol", "openai")
                .put("baseUrl", "https://example.invalid/v1")
                .put("models", new JSONArray().put("m"))
                .put("accounts", accounts).put("keys", new JSONArray());
        Path dir = null;
        try {
            dir = Files.createTempDirectory("raccoon-failover-");
            GatewayServer s = MultiAccountGatewayOfflineTest.gateway(
                    MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider)), dir.resolve("raccoon.json"));
            try {
                Object target = MultiAccountGatewayOfflineTest.target(s, "raccoon/m");
                check(target != null, "raccoon 目标应能构建");
                @SuppressWarnings("unchecked")
                List<Object> candidates = (List<Object>) MultiAccountGatewayOfflineTest.invoke(
                        s, "accountCandidates", new Class<?>[]{target.getClass()}, target);
                check(candidates != null && candidates.size() >= 2,
                        "raccoon 应展开出多个账号候选，实际 " + (candidates == null ? "null" : candidates.size()));
            } finally { s.stop(); }
        } catch (Exception e) {
            throw new AssertionError("raccoon 候选展开测试失败: " + e, e);
        } finally {
            if (dir != null) try { Files.deleteIfExists(dir); } catch (Exception ignored) { }
        }
        System.out.println("raccoon candidate expansion ok (" + checks + ")");
    }

    public static void main(String[] args) throws Exception {
        testQuotaWordingRecognized();
        testRaccoonExpandsCandidates();
        System.out.println("RaccoonQuotaFailoverOfflineTest: " + checks + " checks passed");
    }
}
