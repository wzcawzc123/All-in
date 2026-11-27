package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 豆包工作模式 /chat/completion 的请求体与 SSE 解码。
 *
 * <p>来源是对 {@code dsh-connect-doubaowork} 与 {@code doubao2api} 已实抓协议的移植：
 * 工作模式会把模型目录里的 {@code model_item_key} 原样上送，豆包再按该账号的额度路由到
 * 豆包 / DeepSeek / Kimi / GLM。这里只构造文本对话；工具调用由上层
 * {@link DeepSeekToolBridge} 以提示词桥接，豆包侧仍是一段普通文本。</p>
 */
public final class DoubaoProtocol {
    private DoubaoProtocol() {}

    public static final String BOT_ID = "7338286299411103781";
    public static final String MODE_ID = "3";
    /** 豆包 2.1 与 300k 档模型的默认档案；请求体按每个模型自身的值上送。 */
    public static final String MEMORY_PROFILE = "300k_230";
    public static final String TOTAL_WINDOW = "300000";

    public static final class Model {
        public final String id, name, key, rate, desc;
        /** 客户端 model_extra_params 原值：memory_profile 可以为空，不能拿 300k 一刀切。 */
        public final String memoryProfile, totalWindow;
        public final int contextWindow, maxOutput, defaultEffort;
        /** 该模型客户端实际开放的推理档位（3 低 / 4 中 / 5 高 / 6 极高 / 7 最高 / 8 自动）。 */
        public final int[] efforts;
        Model(String id, String name, String key, String rate, int contextWindow, int maxOutput,
              String memoryProfile, String totalWindow, int defaultEffort, int[] efforts, String desc) {
            this.id = id; this.name = name; this.key = key; this.rate = rate;
            this.contextWindow = contextWindow; this.maxOutput = maxOutput;
            this.memoryProfile = memoryProfile; this.totalWindow = totalWindow;
            this.defaultEffort = defaultEffort; this.efforts = efforts; this.desc = desc;
        }
        public boolean supportsEffort(int effort) {
            for (int value : efforts) if (value == effort) return true;
            return false;
        }
    }

    private static final int[] EFFORT_256K = {8, 3, 4, 5};
    private static final int[] EFFORT_300K = {3, 4, 5, 6, 7};
    private static final int[] EFFORT_GLM = {3, 5, 7};
    private static final int[] EFFORT_QWEN = {3, 5, 6};
    private static final int[] EFFORT_MIMO = {3, 4, 5};

    private static final Map<String, Model> MODELS = new LinkedHashMap<>();
    static {
        // 2026-11-27 本机豆包客户端 2.37.16 工作模式菜单实抓（16 项，含倍率/上下文/推理档位）。
        add(new Model("auto", "自动", "9", "小额免费", 256000, 32768,
                "", "256000", 8, EFFORT_256K,
                "由豆包按任务自动选模型；小额免费，实际计费以客户端为准"));
        add(new Model("doubao-2.1-lite", "豆包 2.1 Lite", "seed-lite-7b", "限时优惠 0.01x", 256000, 32768,
                "256k", "256000", 8, EFFORT_256K,
                "轻量豆包模型，适合日常问答与低成本长文"));
        add(new Model("doubao-2.1-pro", "豆包 2.1 Pro", "5", "0.65x", 256000, 32768,
                "", "256000", 5, new int[]{3, 4, 5},
                "豆包旗舰模型，复杂推理与长任务"));
        add(new Model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "person-deepseek-v4-1-flash-261017", "限时优惠 0.01x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_300K,
                "豆包聚合的 DeepSeek 轻量旗舰；当前限时优惠"));
        add(new Model("glm-5.4", "GLM 5.4", "person-glm-5-4", "新上线 0.80x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_GLM,
                "豆包聚合的智谱 GLM 5.4；新上线模型"));
        add(new Model("qwen4-max", "Qwen 4 Max", "person-qwen4-max", "新上线 0.47x", 300000, 65536,
                "300k_230", "300000", 5, EFFORT_QWEN,
                "豆包聚合的通义千问 Qwen 4 Max；新上线模型"));
        add(new Model("kimi-k3.1-pro", "Kimi K3.1 Pro", "person-kimi-k3-1-pro-261111", "0.95x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_300K,
                "豆包聚合的 Kimi K3.1 Pro"));
        add(new Model("kimi-k3.1-pro-fast", "Kimi K3.1 Pro 高速", "person-kimi-k3-1-pro-highspeed-261111", "1.44x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_300K,
                "豆包聚合的 Kimi K3.1 Pro 高速通道"));
        add(new Model("kimi-k3", "Kimi K3", "person-kimi-k3-261111", "1.48x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_300K,
                "豆包聚合的 Kimi K3"));
        add(new Model("glm-5.3", "GLM 5.3", "person-glm-5-3-261111", "0.80x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_GLM,
                "豆包聚合的智谱 GLM 5.3；上游曾报错，是否可用以账号实际返回为准"));
        add(new Model("glm-5.3-flash", "GLM 5.3 Flash", "person-glm-5-3-flash-261111", "0.08x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_GLM,
                "豆包聚合的智谱 GLM 5.3 Flash；上游曾报错，是否可用以账号实际返回为准"));
        add(new Model("deepseek-v4-pro", "DeepSeek V4 Pro", "person-deepseek-v4-pro-ga-260813", "0.46x", 300000, 65536,
                "300k_230", "300000", 7, EFFORT_300K,
                "豆包聚合的 DeepSeek V4 Pro"));
        add(new Model("qwen3.8-max", "Qwen 3.8 Max", "person-qwen3-8-max", "0.74x", 300000, 65536,
                "300k_230", "300000", 5, EFFORT_QWEN,
                "豆包聚合的通义千问 Qwen 3.8 Max"));
        add(new Model("qwen3.8-flash", "Qwen 3.8 Flash", "person-qwen3-8-flash", "0.05x", 300000, 65536,
                "300k_230", "300000", 5, EFFORT_QWEN,
                "豆包聚合的通义千问 Qwen 3.8 Flash"));
        add(new Model("mimo-v2.6-pro", "MiMo V2.6 Pro", "person-mimo-v2-6-pro", "0.07x", 300000, 65536,
                "300k_230", "300000", 5, EFFORT_MIMO,
                "豆包聚合的小米 MiMo V2.6 Pro"));
        add(new Model("mimo-v2.6-flash", "MiMo V2.6 Flash", "person-mimo-v2-6-flash", "0.03x", 300000, 65536,
                "300k_230", "300000", 5, EFFORT_MIMO,
                "豆包聚合的小米 MiMo V2.6 Flash"));
    }

    private static void add(Model model) { MODELS.put(model.id, model); }

    public static Model resolve(String id) {
        if (id == null) return null;
        Model direct = MODELS.get(id);
        if (direct != null) return direct;
        if ("doubao-seed-2.1-lite".equals(id)) return MODELS.get("doubao-2.1-lite");
        if ("doubao-seed-2.1-pro".equals(id)) return MODELS.get("doubao-2.1-pro");
        // 兼容 v3 目录里的旧 id：客户端和旧配置可能还发 deepseek-v4-flash。
        if ("deepseek-v4-flash".equals(id)) return MODELS.get("deepseek-v4.1-flash");
        if ("qwen-4-max".equals(id)) return MODELS.get("qwen4-max");
        if ("qwen-3.8-max".equals(id)) return MODELS.get("qwen3.8-max");
        if ("qwen-3.8-flash".equals(id)) return MODELS.get("qwen3.8-flash");
        return null;
    }

    public static List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(MODELS.keySet()));
    }

    public static List<Model> models() {
        return Collections.unmodifiableList(new ArrayList<>(MODELS.values()));
    }

    /**
     * OpenAI 的 reasoning_effort 到豆包客户端实际档位的映射。
     *
     * <p>豆包不是 0..4：客户端用 3/4/5/6/7 表示低/中/高/极高/最高，自动模型另有 8。
     * GLM 只开放 3/5/7，Qwen 只开放 3/5/6，所以不能把 high 固定成 4；这里按模型
     * 的 option_list 取最近档位，缺档时向上取，避免把“高”降成“低”。</p>
     */
    public static int effort(String value, Model model) {
        if (model == null) return 5;
        String raw = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty() || "default".equals(raw) || "auto".equals(raw)) return model.defaultEffort;
        if (raw.matches("[0-9]+")) {
            int requested;
            try { requested = Integer.parseInt(raw); }
            catch (NumberFormatException impossible) { return model.defaultEffort; }
            if (model.supportsEffort(requested)) return requested;
            if (requested <= 3) return 3;
            if (requested >= 7) return model.supportsEffort(7) ? 7 : model.defaultEffort;
            return nearestEffort(model, requested);
        }
        if ("none".equals(raw) || "off".equals(raw) || "minimal".equals(raw) || "low".equals(raw)) {
            return model.supportsEffort(3) ? 3 : model.defaultEffort;
        }
        if ("medium".equals(raw)) {
            if (model.supportsEffort(4)) return 4;
            return nearestEffort(model, 4);
        }
        if ("high".equals(raw)) {
            if (model.supportsEffort(5)) return 5;
            return nearestEffort(model, 5);
        }
        if ("xhigh".equals(raw) || "extra_high".equals(raw) || "very_high".equals(raw)) {
            if (model.supportsEffort(6)) return 6;
            return model.supportsEffort(7) ? 7 : nearestEffort(model, 6);
        }
        if ("max".equals(raw) || "maximum".equals(raw)) {
            return model.supportsEffort(7) ? 7 : nearestEffort(model, 7);
        }
        return model.defaultEffort;
    }

    private static int nearestEffort(Model model, int requested) {
        int best = model.defaultEffort;
        int distance = Integer.MAX_VALUE;
        for (int candidate : model.efforts) {
            int current = Math.abs(candidate - requested);
            // 同距离时取更高档：high 在 GLM 的 3/5/7 里应该落到 5，而不是 3。
            if (current < distance || (current == distance && candidate > best)) {
                best = candidate;
                distance = current;
            }
        }
        return best;
    }

    /** 推理档位的 OpenAI 风格名字，用于 dashboard 展示。 */
    public static String effortName(int effort) {
        switch (effort) {
            case 3: return "low";
            case 4: return "medium";
            case 5: return "high";
            case 6: return "xhigh";
            case 7: return "max";
            case 8: return "auto";
            default: return Integer.toString(effort);
        }
    }

    /** 从客户端标签（如“限时优惠 0.01x”“小额免费”）解析出展示倍率。 */
    public static double multiplier(Model model) {
        if (model == null || model.rate == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)x").matcher(model.rate);
        if (matcher.find()) {
            try { return Double.parseDouble(matcher.group(1)); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    public static String contentText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder out = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object part = parts.opt(i);
                if (part instanceof JSONObject) {
                    JSONObject object = (JSONObject) part;
                    String type = object.optString("type", "");
                    if (type.isEmpty() || "text".equals(type)) out.append(object.optString("text", ""));
                } else if (part instanceof String) {
                    out.append((String) part);
                }
            }
            return out.toString();
        }
        return "";
    }

    /** 无工具时的中文角色拼接；豆包工作模式实际只收最后一条用户文本，历史要自带。 */
    public static String flatten(JSONArray messages) {
        if (messages == null || messages.length() == 0) return "";
        String onlyUser = null;
        int users = 0;
        boolean hasOther = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "");
            String text = contentText(message.opt("content")).trim();
            if (text.isEmpty()) continue;
            if ("user".equals(role)) { users++; onlyUser = text; }
            else hasOther = true;
        }
        if (users == 1 && !hasOther && onlyUser != null) return onlyUser;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "");
            String text = contentText(message.opt("content")).trim();
            if (text.isEmpty()) continue;
            String label;
            if ("system".equals(role) || "developer".equals(role)) label = "系统";
            else if ("user".equals(role)) label = "用户";
            else if ("assistant".equals(role)) label = "助手";
            else if ("tool".equals(role)) label = "工具结果";
            else label = role;
            if (out.length() > 0) out.append("\n\n");
            out.append("[").append(label).append("]\n").append(text);
        }
        return out.toString();
    }

    public static String firstUserText(JSONArray messages) {
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"user".equals(message.optString("role", ""))) continue;
            String text = contentText(message.opt("content")).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    /**
     * 构造 /chat/completion 的完整请求体。{@code conversationId} 为空时新建会话，
     * 非空时复用同一会话（SSE_ACK 里拿到的 id）。
     */
    public static JSONObject build(String text, Model model, String conversationId, int reasoningEffort,
                                   String deviceId, String localAppId) {
        if (model == null) throw new IllegalArgumentException("model required");
        String msgId = uuid();
        String localConversation = "local_" + randomDigits(16);

        JSONObject block = new JSONObject();
        block.put("block_type", 10000);
        JSONObject content = new JSONObject();
        JSONObject textBlock = new JSONObject();
        textBlock.put("text", text == null ? "" : text);
        textBlock.put("icon_url", "");
        textBlock.put("icon_url_dark", "");
        textBlock.put("summary", "");
        content.put("text_block", textBlock);
        content.put("pc_event_block", "");
        block.put("content", content);
        block.put("block_id", uuid());
        block.put("parent_id", "");
        block.put("meta_info", new JSONArray());
        block.put("append_fields", new JSONArray());

        JSONObject message = new JSONObject();
        message.put("local_message_id", msgId);
        message.put("content_block", new JSONArray().put(block));
        message.put("message_status", 0);

        JSONArray permissions = new JSONArray();
        for (String name : new String[]{"ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"}) {
            permissions.put(new JSONObject().put("permission_name", name).put("status", 1));
        }
        JSONObject clientMeta = new JSONObject();
        clientMeta.put("local_conversation_id", localConversation);
        clientMeta.put("conversation_id", conversationId == null ? "" : conversationId);
        clientMeta.put("bot_id", BOT_ID);
        clientMeta.put("last_section_id", "");
        clientMeta.put("last_message_index", JSONObject.NULL);
        clientMeta.put("local_permissions", permissions);

        JSONObject taskParam = new JSONObject();
        taskParam.put("runtime_type", 2);
        taskParam.put("sandbox_auth_type", 1);
        taskParam.put("device_name", "USER");
        taskParam.put("folder_name", "");
        taskParam.put("local_app_id", localAppId == null ? DoubaoAuth.LOCAL_APP_ID : localAppId);
        taskParam.put("local_device_id", deviceId == null ? "" : deviceId);
        taskParam.put("workspace", "");

        JSONObject initExt = new JSONObject();
        initExt.put("model_item_key", model.key);
        initExt.put("reasoning_effort", Integer.toString(reasoningEffort));
        initExt.put("mode_id", MODE_ID);
        initExt.put("agent_task_param", taskParam.toString());

        JSONObject clientOption = new JSONObject();
        clientOption.put("enable_sandbox", false);
        clientOption.put("os", "Windows");
        clientOption.put("shared_folder_path", new JSONArray());
        clientOption.put("agent_workspace", new JSONObject().put("agent_workspace", "").put("local_skill_paths", new JSONArray()));
        clientOption.put("client_env_id", uuid());
        clientOption.put("sandbox_id", "sandbox-" + uuid());
        clientOption.put("sandbox_auth_type", 1);

        JSONObject taskInput = new JSONObject();
        taskInput.put("agents_md", new JSONObject().put("files", new JSONArray()).put("state", 2));
        taskInput.put("schema_version", 1);
        taskInput.put("home_dir", "");
        taskInput.put("project_context", new JSONObject());
        taskInput.put("localConnectors", new JSONArray());

        JSONObject generalTask = new JSONObject();
        generalTask.put("action", 0);
        generalTask.put("thread_local_message_id", new JSONArray().put(msgId));
        generalTask.put("client_option", clientOption);
        generalTask.put("runtime_type", 2);
        generalTask.put("agent_task_param", taskParam);
        generalTask.put("agent_task_param_change", new JSONObject()
                .put("runtime_changed", false).put("device_changed", false).put("sandbox_auth_type_changed", false));
        generalTask.put("need_modify_conversation", false);
        generalTask.put("task_input_json", taskInput.toString());

        JSONObject option = new JSONObject();
        option.put("create_time_ms", System.currentTimeMillis());
        option.put("answer_with_suggest", false);
        option.put("agent_mode", 1);
        option.put("need_deep_think", 10001);
        option.put("unique_key", uuid());
        option.put("start_seq", 0);
        option.put("need_create_conversation", conversationId == null || conversationId.isEmpty());
        option.put("conversation_init_option", new JSONObject().put("need_ack_conversation", true));
        option.put("sse_recv_event_options", new JSONObject().put("support_chunk_delta", true));
        option.put("support_lazy_fetch_stream", true);
        option.put("is_old_user", true);
        option.put("message_storage_type", 0);
        option.put("conversation_mode", 2);
        option.put("conversation_init_ext", initExt);
        option.put("model_config", new JSONObject()
                .put("model_item_key", model.key)
                .put("model_extra_params", new JSONObject()
                        .put("memory_profile", model.memoryProfile == null ? "" : model.memoryProfile)
                        .put("total_window_size", model.totalWindow)
                )
                .put("reasoning_effort", reasoningEffort));
        JSONObject aggregate = new JSONObject();
        aggregate.put("mention_skill_list", "[]");
        aggregate.put("mention_plugin_list", "[]");
        aggregate.put("mention_ext", "[{}]");
        aggregate.put("conversation_mode", "2");
        aggregate.put("mode_id", MODE_ID);
        aggregate.put("model_item_key", model.key);
        aggregate.put("agent_mode", "1");
        aggregate.put("reasoning_effort", Integer.toString(reasoningEffort));
        aggregate.put("provider_id", "");
        option.put("aggregate_params", aggregate);
        option.put("general_task_param", generalTask);

        JSONObject ext = new JSONObject();
        ext.put("general_task_param", generalTask.toString());
        ext.put("use_deep_think", "10001");
        ext.put("agent_mode", "1");
        ext.put("is_finish", "1");
        ext.put("conversation_init_option", "{\"need_ack_conversation\":true}");
        ext.put("commerce_credit_config_enable", "0");
        option.put("ext", ext);

        JSONObject body = new JSONObject();
        body.put("client_meta", clientMeta);
        body.put("messages", new JSONArray().put(message));
        body.put("option", option);
        body.put("user_context", new JSONArray());
        return body;
    }

    public interface Sink {
        void onText(String delta) throws IOException;
        void onReasoning(String delta) throws IOException;
        void onConversation(String conversationId, String sectionId) throws IOException;
        void onFinish(int endType, String brief) throws IOException;
    }

    public static final class StreamException extends IOException {
        public final String code;
        StreamException(String message, String code) { super(message); this.code = code == null ? "" : code; }
    }

    /**
     * 解析豆包 SSE。正文优先 CHUNK_DELTA，之后不再用 STREAM_CHUNK 的 text_block 兜底，
     * 否则同一个 token 会被发两遍。思考过程只透传标题级 thinking_block。
     */
    public static void pump(InputStream in, Sink sink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = "";
        StringBuilder data = new StringBuilder();
        String lastReasoning = "";
        boolean sawChunkDelta = false;
        int endType = -1;
        String brief = "";
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                Dispatch dispatch = dispatch(event, data.toString(), sink, lastReasoning, sawChunkDelta);
                lastReasoning = dispatch.lastReasoning;
                sawChunkDelta = dispatch.sawChunkDelta;
                if (dispatch.endType >= 0) endType = dispatch.endType;
                if (dispatch.brief != null && !dispatch.brief.isEmpty()) brief = dispatch.brief;
                event = ""; data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
            // ':' comment frames and unknown fields are ignored.
        }
        Dispatch dispatch = dispatch(event, data.toString(), sink, lastReasoning, sawChunkDelta);
        if (dispatch.endType >= 0) endType = dispatch.endType;
        if (dispatch.brief != null && !dispatch.brief.isEmpty()) brief = dispatch.brief;
        sink.onFinish(endType, brief);
    }

    private static final class Dispatch {
        String lastReasoning = "";
        boolean sawChunkDelta;
        int endType = -1;
        String brief = "";
    }

    private static Dispatch dispatch(String event, String payload, Sink sink, String lastReasoning,
                                     boolean sawChunkDelta) throws IOException {
        Dispatch result = new Dispatch();
        result.lastReasoning = lastReasoning;
        result.sawChunkDelta = sawChunkDelta;
        if (payload == null || payload.isEmpty()) return result;
        if ("[DONE]".equals(payload)) return result;
        JSONObject value;
        try { value = new JSONObject(payload); }
        catch (Exception invalid) { return result; }

        if (event != null && event.toUpperCase(java.util.Locale.ROOT).contains("ERROR")) {
            String code = "";
            Object raw = value.opt("code");
            if (raw == null || raw == JSONObject.NULL) raw = value.opt("error_code");
            if (raw != null && raw != JSONObject.NULL) code = String.valueOf(raw);
            JSONObject error = value.optJSONObject("error");
            String message = error != null ? error.optString("message", "") : value.optString("message", "");
            if (message.isEmpty()) message = value.optString("error_msg", "");
            if (message.isEmpty()) message = value.optString("msg", "");
            throw new StreamException(message.isEmpty() ? "豆包流式响应返回错误" : message, code);
        }
        JSONObject errorObject = value.optJSONObject("error");
        String businessCode = value.optString("code", "0");
        if ("0".equals(businessCode)) businessCode = value.optString("error_code", "0");
        if (errorObject != null && !"0".equals(businessCode))
            throw new StreamException(errorObject.optString("message", "豆包流式响应返回错误"), businessCode);

        if ("CHUNK_DELTA".equals(event)) {
            String delta = value.optString("text", "");
            if (!delta.isEmpty()) {
                result.sawChunkDelta = true;
                sink.onText(delta);
            }
            return result;
        }

        if ("STREAM_CHUNK".equals(event)) {
            JSONArray ops = value.optJSONArray("patch_op");
            for (int i = 0; ops != null && i < ops.length(); i++) {
                JSONObject op = ops.optJSONObject(i);
                JSONObject patch = op == null ? null : op.optJSONObject("patch_value");
                JSONArray blocks = patch == null ? null : patch.optJSONArray("content_block");
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block == null) continue;
                    int type = block.optInt("block_type", 0);
                    JSONObject content = block.optJSONObject("content");
                    if (content == null) continue;
                    if (type == 10000 && !result.sawChunkDelta) {
                        JSONObject textBlock = content.optJSONObject("text_block");
                        String text = textBlock == null ? "" : textBlock.optString("text", "");
                        if (!text.isEmpty()) sink.onText(text);
                    } else if (type == 10040) {
                        JSONObject think = content.optJSONObject("thinking_block");
                        String title = think == null ? "" : think.optString("streaming_title", "");
                        if (title.isEmpty() && think != null) title = think.optString("finish_title", "");
                        if (!title.isEmpty() && !title.equals(result.lastReasoning)) {
                            if (title.startsWith(result.lastReasoning) && !result.lastReasoning.isEmpty())
                                sink.onReasoning(title.substring(result.lastReasoning.length()));
                            else sink.onReasoning(title);
                            result.lastReasoning = title;
                        }
                    }
                }
            }
            return result;
        }

        if ("SSE_ACK".equals(event)) {
            JSONObject meta = value.optJSONObject("ack_client_meta");
            if (meta == null) {
                String rawMeta = value.optString("ack_client_meta", "");
                if (!rawMeta.isEmpty() && rawMeta.startsWith("{")) {
                    try { meta = new JSONObject(rawMeta); } catch (Exception ignored) {}
                }
            }
            if (meta != null) {
                String conversation = meta.optString("conversation_id", "");
                String section = meta.optString("section_id", "");
                if (!conversation.isEmpty()) sink.onConversation(conversation, section);
            }
            return result;
        }

        if ("SSE_REPLY_END".equals(event)) {
            result.endType = value.optInt("end_type", -1);
            JSONObject attr = value.optJSONObject("msg_finish_attr");
            result.brief = attr == null ? "" : attr.optString("brief", "");
            return result;
        }
        return result;
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    private static String randomDigits(int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append((int) (Math.random() * 10));
        return out.toString();
    }
}
