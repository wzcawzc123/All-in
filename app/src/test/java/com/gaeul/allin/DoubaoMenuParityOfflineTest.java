package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 离线对照测试：把网关内置的豆包工作模式目录，与本机豆包客户端 2.37.16 的
 * mode_list 快照逐项比对。快照只保留菜单展示字段，不含 Cookie、Token 或签名 URL。
 */
public final class DoubaoMenuParityOfflineTest {
    public static void main(String[] args) throws Exception {
        File fixture = args.length > 0 ? new File(args[0])
                : firstExisting("tests/fixtures/doubao-mode-list-20261127.json",
                        "../tests/fixtures/doubao-mode-list-20261127.json");
        require(fixture != null && fixture.isFile(), "Doubao menu fixture missing");
        JSONObject root = new JSONObject(new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8));
        JSONArray models = root.getJSONArray("models");
        require(models.length() == 16, "client menu has 16 work-mode models, got " + models.length());
        require(DoubaoProtocol.models().size() == models.length(), "gateway catalog size must match client menu");

        for (int i = 0; i < models.length(); i++) {
            JSONObject expected = models.getJSONObject(i);
            DoubaoProtocol.Model actual = DoubaoProtocol.models().get(i);
            String name = expected.optString("name", "");
            require(name.equals(actual.name), "menu order/name #" + (i + 1) + ": " + name + " != " + actual.name);
            require(expected.optString("key", "").equals(actual.key), "model key " + name);
            require(expected.optString("rate", "").equals(actual.rate), "model rate " + name);
            require(expected.optString("memory_profile", "").equals(actual.memoryProfile), "memory profile " + name);
            require(expected.optString("total_window_size", "").equals(actual.totalWindow), "total window " + name);
            require(Integer.parseInt(expected.optString("total_window_size", "0")) == actual.contextWindow,
                    "context window " + name);
            require(expected.optInt("default_effort", -1) == actual.defaultEffort, "default effort " + name);
            JSONArray efforts = expected.getJSONArray("efforts");
            require(efforts.length() == actual.efforts.length, "effort count " + name);
            for (int j = 0; j < efforts.length(); j++) {
                require(efforts.optInt(j, -1) == actual.efforts[j], "effort #" + j + " " + name);
            }
        }
        System.out.println("DoubaoMenuParityOfflineTest OK (" + models.length() + " models)");
    }

    private static File firstExisting(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.isFile()) return file;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
