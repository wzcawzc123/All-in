package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.file.*;
import java.util.*;

/**
 * XunE: 通用「额度/余额耗尽自动换号」。
 *
 * 各供应商在额度耗尽时返回的状态码与措辞都不一样，且各自有「请求已发送即终止」
 * 的专属规则。本测试锁定统一判据：只要是明确拒绝且未执行（非 UNKNOWN_EXECUTION）
 * 的额度类错误，就必须允许在同一请求内换到同供应商的其它账号。
 *
 * 全部使用合成凭据，不访问真实上游。
 */
public final class QuotaFailoverOfflineTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** 反射调用 GatewayServer 的私有静态判据，避免为了测试放宽生产代码可见性。 */
    static boolean quotaExhausted(int status, String body) {
        try {
            java.lang.reflect.Method m = GatewayServer.class.getDeclaredMethod(
                    "quotaExhausted", int.class, String.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, status, body);
        } catch (Exception e) {
            throw new AssertionError("无法调用 quotaExhausted: " + e, e);
        }
    }

    /** 真实观测到的各供应商额度耗尽报文，都必须被识别。 */
    static void testKnownProviderFormats() {
        // raccoon: 400 + 积分余额不足
        check(quotaExhausted(400,
                "{\"error\":{\"code\":1000007,\"message\":\"积分余额不足，请充值后重试\"}}"), "raccoon 400");
        // zcode: 429 + 1113 余额不足或无可用资源包
        check(quotaExhausted(429,
                "{\"error\":{\"code\":\"1113\",\"message\":\"余额不足或无可用资源包,请充值。\"}}"), "zcode 429/1113");
        // catpaw: 402 + 妙手余额不足
        check(quotaExhausted(402, "妙手余额不足，未得到模型回复"), "catpaw 402");
        // workbuddy: 429 + 额度已用尽 code 14018
        check(quotaExhausted(429,
                "{\"error\":{\"data\":{\"msg\":\"额度已用尽，请购买加量包\",\"code\":14018}}}"), "workbuddy 429/14018");
        // minimax: 402 + insufficient_balance_error
        check(quotaExhausted(402,
                "{\"error\":{\"type\":\"insufficient_balance_error\"}}"), "minimax 402");
        // 通用英文
        check(quotaExhausted(403, "{\"message\":\"insufficient balance\"}"), "generic english");
        System.out.println("known provider formats ok (" + checks + ")");
    }

    /** 不能误伤：非额度类错误、执行结果未知、非拒绝类状态码都必须保持原行为。 */
    static void testMustNotOverreach() {
        check(!quotaExhausted(400, "{\"message\":\"invalid request\"}"), "普通 400 不算额度");
        check(!quotaExhausted(400, "{\"message\":\"context length exceeded\"}"), "超长不算额度");
        check(!quotaExhausted(401, "{\"message\":\"invalid api key\"}"), "鉴权失败不算额度");
        check(!quotaExhausted(500, "{\"message\":\"quota\"}"), "5xx 不算额度耗尽");
        check(!quotaExhausted(200, "ok"), "200 不算");
        System.out.println("must-not-overreach ok (" + checks + ")");
    }

    /** 多账号供应商必须能把同供应商其它账号展开为候选，否则换号无从谈起。 */
    static void testMultiAccountProvidersExpand() throws Exception {
        String[][] cases = {
                {"raccoon", "openai", "m"},
                {"loomy", "openai", "m"},
                {"workbuddy", "openai", "m"},
                {"zcode", "openai", "m"},
        };
        for (String[] c : cases) {
            String id = c[0], protocol = c[1], model = c[2];
            JSONArray accounts = new JSONArray();
            for (int i = 0; i < 3; i++)
                accounts.put(MultiAccountGatewayOfflineTest.account(id + i, "uid-" + i, "synthetic-" + i)
                        .put("type", "api").put("source", "browser")
                        .put("expiresAt", System.currentTimeMillis() + 3600000L)
                        .put("models", new JSONArray().put(model)));
            JSONObject provider = new JSONObject().put("id", id).put("protocol", protocol)
                    .put("baseUrl", "https://example.invalid/v1")
                    .put("models", new JSONArray().put(model))
                    .put("accounts", accounts).put("keys", new JSONArray());
            Path dir = Files.createTempDirectory("quota-failover-" + id + "-");
            GatewayServer s = null;
            try {
                s = MultiAccountGatewayOfflineTest.gateway(
                        MultiAccountGatewayOfflineTest.config(new JSONArray().put(provider)), dir.resolve(id + ".json"));
                Object target = MultiAccountGatewayOfflineTest.target(s, id + "/" + model);
                check(target != null, id + " 目标构建失败");
                @SuppressWarnings("unchecked")
                List<Object> candidates = (List<Object>) MultiAccountGatewayOfflineTest.invoke(
                        s, "accountCandidates", new Class<?>[]{target.getClass()}, target);
                check(candidates != null && candidates.size() >= 2,
                        id + " 应展开多个账号候选，实际 " + (candidates == null ? "null" : candidates.size()));
            } finally {
                if (s != null) s.stop();
                try { Files.deleteIfExists(dir); } catch (Exception ignored) { }
            }
        }
        System.out.println("multi-account expansion ok (" + checks + ")");
    }

    public static void main(String[] args) throws Exception {
        testKnownProviderFormats();
        testMustNotOverreach();
        testMultiAccountProvidersExpand();
        System.out.println("QuotaFailoverOfflineTest: " + checks + " checks passed");
    }
}
