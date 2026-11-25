package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * XunE: 额度门控——账号额度明确为 0 时跳过不调用，余额恢复后自动重新参与。
 *
 * 关键约束（决定实现是否安全）：
 *  1. 「读不到额度」永远不等于「额度为 0」。未知必须照常参与，
 *     否则所有读不到余额的供应商会被永久跳过。
 *  2. 读数有新鲜期（120s）。过期读数不能用来做跳过决策。
 *  3. 全部账号都耗尽时要回退到「照常尝试」，交给上游报错，
 *     不能让请求在本地直接失败。
 */
public final class QuotaGateOfflineTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static AccountRoutingPolicy policy() throws Exception {
        Constructor<AccountRoutingPolicy> c = AccountRoutingPolicy.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    static AccountRoutingPolicy.Seat seat(String ref, String fp) throws Exception {
        Constructor<AccountRoutingPolicy.Seat> c =
                AccountRoutingPolicy.Seat.class.getDeclaredConstructor(String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(ref, fp);
    }

    static boolean depleted(AccountRoutingPolicy p, AccountRoutingPolicy.Seat s, long now) throws Exception {
        Method m = AccountRoutingPolicy.class.getDeclaredMethod(
                "depleted", AccountRoutingPolicy.Seat.class, String.class, long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(p, s, "m", now);
    }

    static JSONObject allowance(long remaining, Long total, boolean withPercent) {
        JSONObject a = new JSONObject().put("kind", "allowance").put("scope", "account")
                .put("unit", "credits").put("remaining", remaining);
        if (total != null) a.put("total", total);
        if (withPercent && total != null && total > 0) {
            JSONArray w = new JSONArray();
            w.put(new JSONObject().put("name", "Plan credits").put("remaining", remaining)
                    .put("total", total).put("remainingPercent", 100.0 * remaining / total));
            a.put("windows", w);
        }
        return a;
    }

    public static void main(String[] args) throws Exception {
        long now = 1_000_000L;

        // 1) 额度明确为 0 → 跳过
        AccountRoutingPolicy p = policy();
        AccountRoutingPolicy.Seat s = seat("loomy/account/a", "fp-a");
        p.observe(s, allowance(0, 5000L, true), now);
        check(depleted(p, s, now), "remaining=0 应判定为耗尽");

        // 2) 白名单供应商在 402 时上报 remaining=0 也要跳过
        AccountRoutingPolicy p2 = policy();
        AccountRoutingPolicy.Seat s2 = seat("raccoon/account/b", "fp-b");
        p2.observe(s2, allowance(0, null, false), now);
        check(depleted(p2, s2, now), "只有 remaining=0 无 total 也应判定耗尽");

        // 3) 有余额 → 不跳过
        AccountRoutingPolicy p3 = policy();
        AccountRoutingPolicy.Seat s3 = seat("loomy/account/c", "fp-c");
        p3.observe(s3, allowance(153, 5000L, true), now);
        check(!depleted(p3, s3, now), "remaining=153 不应被跳过");

        // 4) 没读到额度（从未 observe）→ 绝不能跳过
        AccountRoutingPolicy p4 = policy();
        AccountRoutingPolicy.Seat s4 = seat("zcode/account/d", "fp-d");
        check(!depleted(p4, s4, now), "未知额度不能当作耗尽");

        // 5) 读数过期（超过 120s 新鲜期）→ 不能用来跳过
        AccountRoutingPolicy p5 = policy();
        AccountRoutingPolicy.Seat s5 = seat("loomy/account/e", "fp-e");
        p5.observe(s5, allowance(0, 5000L, true), now);
        check(depleted(p5, s5, now), "刚读到 0 时应跳过");
        check(!depleted(p5, s5, now + 121_000L), "读数过期后不能再据此跳过");

        // 6) 领取了新额度 → 自动恢复参与
        AccountRoutingPolicy p6 = policy();
        AccountRoutingPolicy.Seat s6 = seat("loomy/account/f", "fp-f");
        p6.observe(s6, allowance(0, 5000L, true), now);
        check(depleted(p6, s6, now), "先耗尽");
        p6.observe(s6, allowance(5000L, 5000L, true), now + 1000L);
        check(!depleted(p6, s6, now + 2000L), "领到新额度后必须恢复");

        // 7) 负值/异常不得误判为「有余额」
        AccountRoutingPolicy p7 = policy();
        AccountRoutingPolicy.Seat s7 = seat("x/account/g", "fp-g");
        p7.observe(s7, new JSONObject().put("kind", "allowance").put("scope", "account")
                .put("unit", "credits").put("remaining", -5), now);
        check(depleted(p7, s7, now), "负数余额视为耗尽");

        System.out.println("QuotaGateOfflineTest: " + checks + " checks passed");
    }
}
