package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 双豆包供应商迁移契约测试：不联网、不碰 Android API。
 * 覆盖：旧网页版 doubao 迁到 doubao-web、工作模式账号保留、两套并存时不重复、默认目录不完整时不改动。
 */
public final class DoubaoDualProviderOfflineTest {
    public static void main(String[] args) throws Exception {
        legacyWebMigrates();
        workAccountPreserved();
        bothProvidersAlign();
        incompleteDefaultsDoNothing();
        webDetection();
        shippedAssets();
        legacyFlashIdMigrates();
        v4AddsMimo();
        System.out.println("DoubaoDualProviderOfflineTest OK");
    }

    private static void legacyWebMigrates() {
        JSONObject defaults = defaults();
        JSONObject config = config();
        JSONObject legacy = provider("doubao", "doubao-web", DoubaoWeb.MODELS);
        legacy.put("accounts", new JSONArray().put(new JSONObject().put("cookie", "sessionid=abc; ttwid=xyz")));
        legacy.put("keys", new JSONArray().put(new JSONObject().put("secret", "k1")));
        config.getJSONArray("providers").put(legacy);

        require(GatewayServer.migrateDoubaoCatalog(config, defaults), "legacy web migration should run");
        JSONObject web = find(config, "doubao-web");
        JSONObject work = find(config, "doubao");
        require(web != null && work != null, "both providers after legacy migration");
        require(web.optString("protocol").equals("doubao-web"), "legacy provider becomes doubao-web");
        require(web.optJSONArray("accounts").length() == 1, "legacy web cookie preserved");
        require(web.optJSONArray("accounts").optJSONObject(0).optString("cookie").contains("sessionid=abc"),
                "legacy cookie content preserved");
        require(web.optJSONArray("keys").length() == 1, "legacy web key preserved");
        require(web.optJSONArray("models").length() == 3, "web has three models");
        require(work.optString("protocol").equals("doubao"), "fresh work provider uses doubao");
        require(work.optJSONArray("models").length() == 16, "fresh work provider has 16 models");
        require(work.optJSONArray("accounts").length() == 0, "fresh work provider starts empty");
        require(ids(config).equals(java.util.Arrays.asList("doubao-web", "doubao")),
                "no duplicate provider ids: " + ids(config));
    }

    private static void workAccountPreserved() {
        JSONObject defaults = defaults();
        JSONObject config = config();
        JSONObject work = provider("doubao", "doubao", DoubaoProtocol.ids().toArray(new String[0]));
        work.put("accounts", new JSONArray().put(new JSONObject().put("cookie", "sessionid=work; ttwid=xyz")));
        config.getJSONArray("providers").put(work);

        require(GatewayServer.migrateDoubaoCatalog(config, defaults), "work migration should run");
        JSONObject savedWork = find(config, "doubao");
        JSONObject web = find(config, "doubao-web");
        require(savedWork.optJSONArray("accounts").length() == 1, "work account preserved");
        require(savedWork.optJSONArray("accounts").optJSONObject(0).optString("cookie").contains("sessionid=work"),
                "work cookie content preserved");
        require(savedWork.optJSONArray("models").length() == 16, "work models aligned to 16");
        require(web != null && web.optJSONArray("models").length() == 3, "web added with three models");
        require(ids(config).equals(java.util.Arrays.asList("doubao", "doubao-web")),
                "no duplicate provider ids: " + ids(config));
    }

    private static void bothProvidersAlign() {
        JSONObject defaults = defaults();
        JSONObject config = config();
        JSONObject work = provider("doubao", "doubao", new String[]{"auto"});
        JSONObject web = provider("doubao-web", "doubao-web", new String[]{"doubao"});
        work.put("accounts", new JSONArray().put(new JSONObject().put("cookie", "sessionid=work")));
        web.put("accounts", new JSONArray().put(new JSONObject().put("cookie", "sessionid=web")));
        config.getJSONArray("providers").put(work);
        config.getJSONArray("providers").put(web);

        require(GatewayServer.migrateDoubaoCatalog(config, defaults), "already-dual migration should run");
        JSONObject savedWork = find(config, "doubao");
        JSONObject savedWeb = find(config, "doubao-web");
        require(savedWork.optJSONArray("models").length() == 16, "work models aligned");
        require(savedWeb.optJSONArray("models").length() == 3, "web models aligned");
        require(savedWork.optJSONArray("accounts").length() == 1, "work account kept");
        require(savedWeb.optJSONArray("accounts").length() == 1, "web account kept");
        require(ids(config).equals(java.util.Arrays.asList("doubao", "doubao-web")),
                "no duplicate provider ids: " + ids(config));
    }

    private static void incompleteDefaultsDoNothing() {
        JSONObject defaults = defaults();
        JSONObject config = config();
        JSONObject work = provider("doubao", "doubao", new String[]{"auto"});
        config.getJSONArray("providers").put(work);
        String before = config.toString();

        JSONObject missingWeb = new JSONObject(defaults.toString());
        JSONArray provs = missingWeb.getJSONArray("providers");
        for (int i = provs.length() - 1; i >= 0; i--) {
            if ("doubao-web".equals(provs.getJSONObject(i).optString("id"))) provs.remove(i);
        }
        require(!GatewayServer.migrateDoubaoCatalog(config, missingWeb), "incomplete defaults reject migration");
        require(before.equals(config.toString()), "config untouched when defaults incomplete");
    }

    private static void webDetection() {
        require(isWeb(provider("doubao", "doubao", new String[]{"doubao", "doubao-think"})), "doubao-think detected");
        JSONObject noteOnly = provider("doubao", "doubao", new String[]{"doubao"});
        noteOnly.put("note", "llm_model_type=38");
        require(isWeb(noteOnly), "legacy note detected");
        require(!isWeb(provider("doubao", "doubao", DoubaoProtocol.ids().toArray(new String[0]))),
                "work catalog is not web");
    }

    private static boolean isWeb(JSONObject provider) {
        // 迁移内部判定与这里保持一致：protocol、doubao-think/doubao-expert 或 note=llm_model_type=38。
        if ("doubao-web".equals(provider.optString("protocol"))) return true;
        JSONArray models = provider.optJSONArray("models");
        for (int i = 0; models != null && i < models.length(); i++) {
            String id = models.optString(i, "");
            if ("doubao-think".equals(id) || "doubao-expert".equals(id)) return true;
        }
        return provider.optString("note", "").contains("llm_model_type=38");
    }

    private static JSONObject defaults() {
        JSONObject root = new JSONObject();
        JSONArray providers = new JSONArray();
        providers.put(provider("doubao-web", "doubao-web", DoubaoWeb.MODELS));
        String[] workModels = DoubaoProtocol.ids().toArray(new String[0]);
        providers.put(provider("doubao", "doubao", workModels));
        root.put("providers", providers);
        return root;
    }

    private static JSONObject config() {
        return new JSONObject().put("providers", new JSONArray());
    }

    private static JSONObject provider(String id, String protocol, String[] models) {
        JSONArray list = new JSONArray();
        for (String model : models) list.put(model);
        return new JSONObject()
                .put("id", id)
                .put("name", id)
                .put("protocol", protocol)
                .put("models", list)
                .put("accounts", new JSONArray())
                .put("keys", new JSONArray());
    }

    private static JSONObject find(JSONObject config, String id) {
        JSONArray providers = config.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    private static List<String> ids(JSONObject config) {
        List<String> out = new ArrayList<String>();
        JSONArray providers = config.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null) out.add(p.optString("id"));
        }
        return out;
    }

    private static void shippedAssets() throws Exception {
        File dashboard = firstExisting("app/src/main/assets/dashboard.html", "src/main/assets/dashboard.html");
        require(dashboard != null, "dashboard.html missing");
        String html = new String(Files.readAllBytes(dashboard.toPath()), StandardCharsets.UTF_8);
        require(html.contains("data-provider-id=\"doubao\""), "work card present");
        require(html.contains("data-provider-id=\"doubao-web\""), "web card present");
        require(html.contains("promptImportDoubaoAccount('doubao')"), "work cookie import wired");
        require(html.contains("promptImportDoubaoAccount('doubao-web')"), "web cookie import wired");
        require(html.contains("id=\"models-tags-doubao-web\""), "web model pool present");
        require(html.contains("'doubao', 'doubao-web'"), "provider card ids include both");

        File config = firstExisting("app/src/main/assets/default_config.json", "src/main/assets/default_config.json");
        require(config != null, "default_config.json missing");
        JSONObject shipped = new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
        JSONObject work = find(shipped, "doubao");
        JSONObject web = find(shipped, "doubao-web");
        require(work != null && work.optJSONArray("models").length() == 16, "shipped work config has 16 models");
        require(web != null && web.optJSONArray("models").length() == 3, "shipped web config has three models");
        require(work.optString("protocol").equals("doubao"), "shipped work protocol");
        require(web.optString("protocol").equals("doubao-web"), "shipped web protocol");
    }

    private static void legacyFlashIdMigrates() {
        JSONObject config = config();
        JSONObject work = provider("doubao", "doubao", new String[]{"auto", "deepseek-v4-flash"});
        work.put("accounts", new JSONArray().put(new JSONObject().put("cookie", "sessionid=keep")));
        JSONObject meta = new JSONObject();
        meta.put("deepseek-v4-flash", new JSONObject().put("name", "DeepSeek V4.1 Flash").put("multiplier", 0.01));
        work.put("modelMeta", meta);
        config.getJSONArray("providers").put(work);

        require(GatewayServer.migrateDoubaoLegacyFlashId(config), "v3 legacy flash id should migrate");
        JSONObject saved = find(config, "doubao");
        require(saved.optJSONArray("accounts").length() == 1, "account kept during flash rename");
        require(saved.optJSONArray("accounts").optJSONObject(0).optString("cookie").contains("sessionid=keep"),
                "cookie kept during flash rename");
        require(hasModel(saved, "deepseek-v4.1-flash"), "canonical flash id present");
        require(!hasModel(saved, "deepseek-v4-flash"), "legacy flash id removed");
        require(saved.optJSONObject("modelMeta").has("deepseek-v4.1-flash"), "modelMeta moved to canonical id");
        require(!saved.optJSONObject("modelMeta").has("deepseek-v4-flash"), "legacy modelMeta removed");

        JSONObject already = provider("doubao", "doubao", new String[]{"deepseek-v4.1-flash"});
        JSONObject clean = config();
        clean.getJSONArray("providers").put(already);
        require(!GatewayServer.migrateDoubaoLegacyFlashId(clean), "canonical-only catalog is a no-op");
    }

    private static void v4AddsMimo() {
        String[] oldIds = DoubaoProtocol.ids().subList(0, 14).toArray(new String[0]);
        JSONObject work = provider("doubao", "doubao", oldIds);
        JSONObject config = config();
        config.getJSONArray("providers").put(work);
        require(GatewayServer.migrateDoubaoV4(config, defaults()), "v4 catalog migration should run");
        JSONObject saved = find(config, "doubao");
        require(saved.optJSONArray("models").length() == 16, "v4 adds two MiMo models");
        require(hasModel(saved, "mimo-v2.6-pro") && hasModel(saved, "mimo-v2.6-flash"), "MiMo models present");
    }

    private static boolean hasModel(JSONObject provider, String id) {
        JSONArray models = provider.optJSONArray("models");
        for (int i = 0; models != null && i < models.length(); i++) {
            if (id.equals(models.optString(i, ""))) return true;
        }
        return false;
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
