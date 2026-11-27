package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线契约测试：不联网、不碰 Android API，只验证豆包工作模式的请求体、14 模型目录与 SSE 解码。
 * 运行：java -cp ... com.gaeul.allin.DoubaoWorkOfflineTest
 */
public final class DoubaoWorkOfflineTest {
    public static void main(String[] args) throws Exception {
        cookieNormalization();
        modelCatalog();
        requestBody();
        sseDecoding();
        chatRoundTrip();
        conversationReuse();
        toolBridge();
        authFailure();
        System.out.println("DoubaoWorkOfflineTest OK");
    }

    private static void cookieNormalization() throws Exception {
        String raw = "ttwid=abc; sessionid=1234567890abcdef; passport_csrf_token=xyz";
        String cookie = DoubaoAuth.normalizeCookie(raw);
        require(cookie.equals(raw), "cookie should be preserved");
        require(DoubaoAuth.normalizeCookie("Cookie: " + raw).equals(raw), "Cookie prefix should be stripped");
        try { DoubaoAuth.normalizeCookie("ttwid=abc"); throw new AssertionError("missing sessionid must fail"); }
        catch (DoubaoAuth.Failure expected) { require(expected.status == 400, "bad cookie status"); }
        String device = DoubaoAuth.deriveDeviceId(cookie);
        require(device.length() == 12 && device.matches("[0-9a-f]+"), "derived device id shape");
        require(device.equals(DoubaoAuth.deriveDeviceId(cookie)), "device id must be stable");
        require("582478".equals(DoubaoAuth.AID), "desktop aid");
        require("2.37.16".equals(DoubaoAuth.PC_VERSION), "desktop pc version");
        String url = DoubaoAuth.chatUrl(DoubaoAuth.Credential.fromAccount(
                new JSONObject().put("cookie", cookie), ""));
        require(url.contains("aid=582478") && url.contains("channel=win"), "desktop aid/channel query");
        require(url.contains("doubao_device_platform=desktop") && url.contains("pc_version=2.37.16"),
                "desktop platform/version query");
    }

    private static void modelCatalog() {
        require(DoubaoProtocol.models().size() == 14, "14 work-mode models");
        String[][] expected = {
                {"auto", "9", "0"},
                {"doubao-2.1-lite", "seed-lite-7b", "0.01"},
                {"doubao-2.1-pro", "5", "0.65"},
                {"deepseek-v4-flash", "person-deepseek-v4-1-flash-261017", "0.01"},
                {"glm-5.4", "person-glm-5-4", "0.80"},
                {"qwen4-max", "person-qwen4-max", "0.47"},
                {"kimi-k3.1-pro", "person-kimi-k3-1-pro-261111", "0.95"},
                {"kimi-k3.1-pro-fast", "person-kimi-k3-1-pro-highspeed-261111", "1.44"},
                {"kimi-k3", "person-kimi-k3-261111", "1.48"},
                {"glm-5.3", "person-glm-5-3-261111", "0.80"},
                {"glm-5.3-flash", "person-glm-5-3-flash-261111", "0.08"},
                {"deepseek-v4-pro", "person-deepseek-v4-pro-ga-260813", "0.46"},
                {"qwen3.8-max", "person-qwen3-8-max", "0.74"},
                {"qwen3.8-flash", "person-qwen3-8-flash", "0.05"}
        };
        int index = 0;
        for (DoubaoProtocol.Model actual : DoubaoProtocol.models()) {
            String[] row = expected[index++];
            require(actual.id.equals(row[0]), "model id #" + index);
            require(actual.key.equals(row[1]), "model key " + actual.id);
            require(Math.abs(DoubaoProtocol.multiplier(actual) - Double.parseDouble(row[2])) < 0.001,
                    "model rate " + actual.id);
        }
        DoubaoProtocol.Model lite = DoubaoProtocol.resolve("doubao-2.1-lite");
        DoubaoProtocol.Model glm54 = DoubaoProtocol.resolve("glm-5.4");
        DoubaoProtocol.Model qwen4 = DoubaoProtocol.resolve("qwen4-max");
        DoubaoProtocol.Model auto = DoubaoProtocol.resolve("auto");
        require(lite != null && lite.key.equals("seed-lite-7b"), "lite key");
        require(lite.contextWindow == 256000 && lite.maxOutput == 32768, "lite context");
        require(glm54 != null && glm54.key.equals("person-glm-5-4"), "GLM 5.4 key");
        require(Math.abs(DoubaoProtocol.multiplier(glm54) - 0.80) < 0.001, "GLM 5.4 rate");
        require(qwen4 != null && qwen4.key.equals("person-qwen4-max"), "Qwen 4 Max key");
        require(Math.abs(DoubaoProtocol.multiplier(qwen4) - 0.47) < 0.001, "Qwen 4 Max rate");
        require(auto.defaultEffort == 8 && auto.key.equals("9"), "auto default effort");
        require(DoubaoProtocol.effort("high", glm54) == 5, "GLM high -> 5");
        require(DoubaoProtocol.effort("max", glm54) == 7, "GLM max -> 7");
        require(DoubaoProtocol.effort("medium", glm54) == 5, "GLM missing medium -> 5");
        require(DoubaoProtocol.effort("xhigh", qwen4) == 6, "Qwen xhigh -> 6");
        require(DoubaoProtocol.effort("high", auto) == 5, "auto high -> 5");
    }

    private static void requestBody() {
        JSONObject system = new JSONObject().put("role", "system").put("content", "你是助手");
        JSONArray history = new JSONArray().put(system).put(new JSONObject().put("role", "user").put("content", "你好"));
        String flat = DoubaoProtocol.flatten(history);
        require(flat.contains("你是助手") && flat.contains("[用户]"), "system message must survive flatten");
        DoubaoProtocol.Model model = DoubaoProtocol.resolve("deepseek-v4-flash");
        require(model != null && model.key.startsWith("person-deepseek-v4"), "model key");
        JSONObject body = DoubaoProtocol.build("你好，豆包", model, "", DoubaoProtocol.effort("high", model),
                "abc123def456", "582478");
        JSONObject option = body.getJSONObject("option");
        require(option.optBoolean("need_create_conversation"), "first turn creates conversation");
        require(option.getJSONObject("model_config").optString("model_item_key").equals(model.key), "model key in body");
        require(option.getJSONObject("model_config").getJSONObject("model_extra_params")
                .optString("memory_profile").equals("300k_230"), "DeepSeek uses 300k profile");
        require(option.getJSONObject("aggregate_params").optString("provider_id").isEmpty(), "provider_id must be empty");
        require(option.getJSONObject("general_task_param").optInt("action") == 0, "work task action");
        require(!option.getJSONObject("ext").optString("general_task_param").isEmpty(), "ext task param must be a JSON string");
        JSONObject message = body.getJSONArray("messages").getJSONObject(0);
        require(message.getJSONArray("content_block").getJSONObject(0)
                .getJSONObject("content").getJSONObject("text_block").optString("text").equals("你好，豆包"), "prompt text");

        DoubaoProtocol.Model auto = DoubaoProtocol.resolve("auto");
        JSONObject autoBody = DoubaoProtocol.build("自动", auto, "", DoubaoProtocol.effort("auto", auto),
                "abc123def456", "582478");
        JSONObject autoExtra = autoBody.getJSONObject("option").getJSONObject("model_config")
                .getJSONObject("model_extra_params");
        require(autoExtra.optString("memory_profile").isEmpty(), "auto sends empty memory_profile");
        require(autoExtra.optString("total_window_size").equals("256000"), "auto uses 256k window");

        JSONObject resumed = DoubaoProtocol.build("继续", model, "conv_1", DoubaoProtocol.effort("high", model),
                "abc123def456", "582478");
        JSONObject resumedOption = resumed.getJSONObject("option");
        require(!resumedOption.optBoolean("need_create_conversation"), "resumed conversation must not create");
        require(resumed.getJSONObject("client_meta").optString("conversation_id").equals("conv_1"), "conversation id");
        require(resumedOption.getJSONObject("model_config").optInt("reasoning_effort") == 5, "reasoning effort");
    }

    private static void sseDecoding() throws Exception {
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final String[] conv = {""};
        final int[] end = {-1};
        String sse = "event: SSE_ACK\n"
                + "data: {\"ack_client_meta\":{\"conversation_id\":\"conv_9\",\"section_id\":\"sec_1\"}}\n\n"
                + "event: STREAM_CHUNK\n"
                + "data: {\"patch_op\":[{\"patch_value\":{\"content_block\":[{\"block_type\":10040,\"content\":{\"thinking_block\":{\"streaming_title\":\"先想\",\"finish_title\":\"先想一想\"}}}]}}]}\n\n"
                + "event: CHUNK_DELTA\n"
                + "data: {\"text\":\"你好\"}\n\n"
                + "event: CHUNK_DELTA\n"
                + "data: {\"text\":\"，世界\"}\n\n"
                + "event: SSE_REPLY_END\n"
                + "data: {\"end_type\":1,\"msg_finish_attr\":{\"brief\":\"你好，世界\"}}\n\n";
        DoubaoProtocol.pump(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), new DoubaoProtocol.Sink() {
            @Override public void onText(String delta) { text.append(delta); }
            @Override public void onReasoning(String delta) { reasoning.append(delta); }
            @Override public void onConversation(String conversationId, String sectionId) { conv[0] = conversationId; }
            @Override public void onFinish(int endType, String brief) { end[0] = endType; }
        });
        require(text.toString().equals("你好，世界"), "chunk delta text: " + text);
        require(conv[0].equals("conv_9"), "conversation ack");
        require(end[0] == 1, "reply end");
        require(reasoning.toString().contains("先想"), "thinking title");
    }

    private static void chatRoundTrip() throws Exception {
        DoubaoChat.clearConversationCacheForTests();
        final boolean[] used = {false};
        DoubaoAuth.Transport transport = (method, url, headers, body) -> {
            used[0] = true;
            require(url.contains("/chat/completion?"), "chat url");
            require(headers.get("cookie").contains("sessionid="), "cookie header");
            return new DoubaoAuth.Response(200, "text/event-stream",
                    new ByteArrayInputStream(sse("好的，我在。", "conv_42").getBytes(StandardCharsets.UTF_8)));
        };
        JSONObject request = new JSONObject();
        request.put("model", "deepseek-v4-flash");
        request.put("reasoning_effort", "high");
        request.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "在吗")));
        DoubaoChat chat = new DoubaoChat(credential(), transport);
        DoubaoChat.Result result = chat.run(request, null);
        require(used[0], "transport used");
        require(result.text.equals("好的，我在。"), "result text");
        require(result.conversationId.equals("conv_42"), "conversation id captured");
    }

    private static void conversationReuse() throws Exception {
        DoubaoChat.clearConversationCacheForTests();
        final java.util.List<String> bodies = new java.util.ArrayList<>();
        DoubaoAuth.Transport transport = (method, url, headers, body) -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return new DoubaoAuth.Response(200, "text/event-stream",
                    new ByteArrayInputStream(sse("收到", "conv_same").getBytes(StandardCharsets.UTF_8)));
        };
        JSONObject request = new JSONObject();
        request.put("model", "kimi-k3");
        request.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "同一个问题")));
        DoubaoChat chat = new DoubaoChat(credential(), transport);
        chat.run(request, null);
        chat.run(request, null);
        require(bodies.size() == 2, "two upstream calls");
        require(new JSONObject(bodies.get(0)).getJSONObject("option").optBoolean("need_create_conversation"), "first creates");
        JSONObject second = new JSONObject(bodies.get(1));
        require(!second.getJSONObject("option").optBoolean("need_create_conversation"), "second reuses conversation");
        require(second.getJSONObject("client_meta").optString("conversation_id").equals("conv_same"), "reused id");
    }

    private static void toolBridge() throws Exception {
        DoubaoChat.clearConversationCacheForTests();
        final String[] upstream = {""};
        final String answer = "先查一下。<tool_call>{\"name\":\"get_weather\",\"arguments\":{\"city\":\"佛山\"}}</tool_call>";
        DoubaoAuth.Transport transport = (method, url, headers, body) -> {
            upstream[0] = new String(body, StandardCharsets.UTF_8);
            return new DoubaoAuth.Response(200, "text/event-stream",
                    new ByteArrayInputStream(sse(answer, "conv_tool").getBytes(StandardCharsets.UTF_8)));
        };
        JSONObject tool = new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", "get_weather").put("description", "查询天气")
                        .put("parameters", new JSONObject().put("type", "object")));
        JSONObject request = new JSONObject();
        request.put("model", "doubao-2.1-pro");
        request.put("tools", new JSONArray().put(tool));
        request.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "佛山天气")));
        DoubaoChat.Result result = new DoubaoChat(credential(), transport).run(request, null);
        require(upstream[0].contains("get_weather"), "tool schema must reach the prompt");
        require(result.toolCalls.length() == 1, "one tool call parsed");
        JSONObject call = result.toolCalls.getJSONObject(0);
        require(call.getJSONObject("function").optString("name").equals("get_weather"), "tool name");
        require(call.getJSONObject("function").optString("arguments").contains("佛山"), "tool arguments");
        require(result.text.contains("先查一下"), "text before tool call is kept");
    }

    private static void authFailure() throws Exception {
        DoubaoAuth.Transport transport = (method, url, headers, body) ->
                new DoubaoAuth.Response(401, "application/json",
                        new ByteArrayInputStream("{\"code\":710012001}".getBytes(StandardCharsets.UTF_8)));
        JSONObject request = new JSONObject();
        request.put("model", "glm-5.3");
        request.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")));
        try {
            new DoubaoChat(credential(), transport).run(request, null);
            throw new AssertionError("401 must fail");
        } catch (DoubaoChat.UpstreamException expected) {
            require(expected.status == 401, "401 mapped");
        }
        try {
            DoubaoAuth.validate(transport, credential());
            throw new AssertionError("validate should fail on 401");
        } catch (DoubaoAuth.Failure expected) {
            require(expected.status == 401, "validation 401 mapped");
        }

        DoubaoAuth.Transport expiredJson = (method, url, headers, body) ->
                new DoubaoAuth.Response(200, "application/json",
                        new ByteArrayInputStream("{\"code\":710012001}".getBytes(StandardCharsets.UTF_8)));
        try {
            DoubaoChat chat = new DoubaoChat(credential(), expiredJson);
            chat.run(request, null);
            throw new AssertionError("710012001 must fail");
        } catch (DoubaoChat.UpstreamException expected) {
            require(expected.status == 401, "710012001 mapped to 401");
        }
        try {
            DoubaoAuth.validate(expiredJson, credential());
            throw new AssertionError("validate should fail on 710012001");
        } catch (DoubaoAuth.Failure expected) {
            require(expected.status == 401, "validation 710012001 mapped");
        }

        DoubaoAuth.Transport refusedJson = (method, url, headers, body) ->
                new DoubaoAuth.Response(200, "application/json",
                        new ByteArrayInputStream("{\"code\":12345}".getBytes(StandardCharsets.UTF_8)));
        try {
            DoubaoAuth.validate(refusedJson, credential());
            throw new AssertionError("unknown business code must fail");
        } catch (DoubaoAuth.Failure expected) {
            require(expected.status == 502, "unknown code is upstream 502, not 401");
        }

        DoubaoAuth.Transport rateLimited = (method, url, headers, body) ->
                new DoubaoAuth.Response(200, "text/event-stream",
                        new ByteArrayInputStream(("event: STREAM_ERROR\n"
                                + "data: {\"error_code\":710022004,\"error_msg\":\"rate limited\"}\n\n")
                                .getBytes(StandardCharsets.UTF_8)));
        try {
            DoubaoChat chat = new DoubaoChat(credential(), rateLimited);
            chat.run(request, null);
            throw new AssertionError("rate limit must fail");
        } catch (DoubaoChat.UpstreamException expected) {
            require(expected.status == 429, "710022004 mapped to 429");
        }
    }

    private static DoubaoAuth.Credential credential() throws IOException {
        JSONObject account = new JSONObject().put("cookie",
                "sessionid=1234567890abcdef; ttwid=web; passport_csrf_token=csrf");
        return DoubaoAuth.Credential.fromAccount(account, "");
    }

    private static String sse(String text, String conversation) {
        String quoted = JSONObject.quote(text);
        return "event: SSE_ACK\n"
                + "data: {\"ack_client_meta\":{\"conversation_id\":\"" + conversation + "\",\"section_id\":\"s\"}}\n\n"
                + "event: CHUNK_DELTA\n"
                + "data: {\"text\":" + quoted + "}\n\n"
                + "event: SSE_REPLY_END\n"
                + "data: {\"end_type\":1,\"msg_finish_attr\":{\"brief\":" + quoted + "}}\n\n";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
