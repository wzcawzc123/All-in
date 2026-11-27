package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONException;
import com.gaeul.allin.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 豆包网页版上游客户端：POST https://www.doubao.com/chat/completion。
 *
 * 契约来源是三套已跑通的实现（doubao2api 的 Go 版 + 另一份 Python 版）交叉核对，
 * 下面每条「实测结论」都别凭感觉改：
 *
 *  1. 不实现任何签名。a_bogus / msToken 在本端点不被校验。
 *  2. 设备 query 必须成套自洽，砍参数会触发风控滑块 710022004。
 *  3. 鉴权只需 8 个必需 cookie（sessionid/sessionid_ss/sid_tt/sid_guard/
 *     uid_tt/uid_tt_ss/ttwid/passport_csrf_token），d_ticket、s_v_web_id 可选。
 *  4. 正文片段可能来自 CHUNK_DELTA 或 STREAM_CHUNK(patch_object:1)，二者不重叠，
 *     拼接即权威全文；end_type:1 的 msg_finish_attr.brief 只是会话列表预览串
 *     （会吃换行、长回答截断）→ 绝不用于覆盖正文。
 *  5. end_type:1 = 正文已完整（此时即可发 finish_reason）；end_type:3 = 流真正结束。
 *     两者实测相差 2~3 秒，故在 end_type:1 额外发 body_end。
 *  6. 错误有三支：HTTP 非 200、event: gateway-error、event: STREAM_ERROR。
 */
final class DoubaoWeb {
    static final String HOST = "www.doubao.com";
    static final String ORIGIN = "https://" + HOST;
    static final String COMPLETION_PATH = "/chat/completion";

    // ── 设备面常量 ────────────────────────────────────────────────────────────
    // fp = verify_<device_id>、tea_uuid = device_id 是强约束，三者不一致会被风控
    // 判成异常设备。设备 query 必须整套给出，缺项同样会触发 710022004。
    static final String DEVICE_ID = "854749611058841";
    static final String WEB_ID = "7696568307852379690";
    static final String PC_VERSION = "2.32.5";
    static final String CHROMIUM_VERSION = "147.0.7727.149";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + CHROMIUM_VERSION.substring(0, CHROMIUM_VERSION.indexOf('.'))
            + " Safari/537.36 SamanthaDoubao/" + PC_VERSION;
    static final String BOT_ID = "7338286299411103781";

    /** 每进程一个即可，服务端只当会话标识。 */
    private static final String WEB_TAB_ID = UUID.randomUUID().toString().replace("-", "");

    public static final String DEFAULT_MODEL = "doubao";
    /**
     * 豆包网页对话接口真实只有三个模式，底层同一个 Seed 模型（llm_model_type=38）：
     * 快速/思考/专家 的区别是是否注入 CoT，靠 option.use_deep_think 与 use_auto_cot 控制。
     * 实测 model_item_key 被服务端忽略，所以第三方模型（DeepSeek/GLM/Qwen/Kimi）
     * 不是这个接口的参数——那是工作任务模式的聚合能力，见 note。
     */
    public static final String[] MODELS = {
            "doubao", "doubao-think", "doubao-expert"
    };

    /** 鉴权最小集：这 8 个凑齐才认为登录成功。 */
    static final String[] REQUIRED_COOKIES = {
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard",
            "uid_tt", "uid_tt_ss", "ttwid", "passport_csrf_token"
    };
    /** 手动导入场景可能带上的、实测非必需的 cookie，有则一起带上。 */
    static final String[] OPTIONAL_COOKIES = {"d_ticket", "s_v_web_id"};

    /**
     * 一个工作模式模型档。工作模式固定 mode_id=3 / conversation_mode=2 / agent_mode=1，
     * 各模型之间只有 model_item_key、item_id、记忆档位不同。
     */
    static final class Mode {
        final String modelItemKey;   // 三处必须一致（init_ext / model_config / aggregate_params）
        final String display;
        final String itemId;
        final String memoryProfile;  // 256k / 300k_230
        final String totalWindow;    // 256000 / 300000
        /** 0=快速 1=思考 3=专家。这是豆包网页唯一真正改变推理行为的开关。 */
        final int needDeepThink;
        Mode(String modelItemKey, String display, String itemId, String memoryProfile, String totalWindow) {
            this(modelItemKey, display, itemId, memoryProfile, totalWindow, 0);
        }
        Mode(String modelItemKey, String display, String itemId, String memoryProfile, String totalWindow, int needDeepThink) {
            this.modelItemKey = modelItemKey;
            this.display = display;
            this.itemId = itemId;
            this.memoryProfile = memoryProfile;
            this.totalWindow = totalWindow;
            this.needDeepThink = needDeepThink;
        }
    }

    // 工作模式固定字段（豆包工作模式：mode_id=3、conversation_mode=2、agent_mode=1）
    static final String MODE_ID = "3";
    static final int CONVERSATION_MODE = 2;
    static final int AGENT_MODE = 1;
    static final String REASONING_EFFORT = "4";
    static final String LOCAL_APP_ID = "582478";
    /**
     * 任务模式的 agent_task_param.device_name 会被豆包当成一台"云电脑"登记到账号下
     * （实测用机型名 PHB110 后，豆包 App 里真出现了这台设备并提示"前往 PHB110 继续对话"）。
     * 所以这里用中性固定值，避免每台设备各建一个工作环境。
     */
    static final String DEVICE_NAME = "Windows";

    /** 工作模式实测模型表；清单来自客户端缓存 mode_list，不是接口下发，豆包调模型时需同步维护。 */
    static final Map<String, Mode> MODELS_MAP;
    static final Map<String, String> MODEL_ALIASES;
    static {
        Map<String, Mode> modes = new LinkedHashMap<>();
        // model_item_key 统一给 "0"：服务端本来就不看它，保持结构完整避免参数校验失败。
        // 三个档位的差别只在 needDeepThink（→ option.use_deep_think / use_auto_cot）。
        modes.put("doubao", new Mode("0", "豆包 快速", "0", "256k", "256000", 0));
        modes.put("doubao-think", new Mode("0", "豆包 思考", "0", "256k", "256000", 1));
        modes.put("doubao-expert", new Mode("0", "豆包 专家", "0", "256k", "256000", 3));
        MODELS_MAP = Collections.unmodifiableMap(modes);

        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("auto", "doubao");
        aliases.put("fast", "doubao");
        aliases.put("think", "doubao-think");
        aliases.put("expert", "doubao-expert");
        aliases.put("doubao-pro", "doubao");
        MODEL_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /** 工作模式的 agent_task_param 需要设备名；拿不到就退回固定值。 */
    private static String resolveDeviceName() {
        try {
            String model = android.os.Build.MODEL;
            if (model != null && !model.trim().isEmpty()) return model.trim();
        } catch (Throwable ignored) { }
        return "Windows";
    }

    /** 风控里不能自动重试的错误码。 */
    static final int CODE_RISK_SLIDER = 710022004;
    static final int CODE_RISK_BLOCKED = 710012000;
    static final int CODE_RATE_LIMITED = 710022002;

    // ── 传输 ──────────────────────────────────────────────────────────────────
    interface Transport {
        Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    static final class Response {
        final int status;
        final String contentType;
        final InputStream body;
        Response(int status, String contentType, InputStream body) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body;
        }
        void close() { if (body != null) try { body.close(); } catch (IOException ignored) {} }
    }

    interface Listener {
        void onText(String delta) throws IOException;
        void onReasoning(String delta) throws IOException;
    }

    /** 上游错误，带网关侧状态与分类。DoubaoWebChat 会包一层带网关状态的子类，故不加 final。 */
    static class UpstreamException extends IOException {
        final int gatewayStatus;
        final int upstreamStatus;
        final String responseBody;
        final boolean bodyTruncated;
        UpstreamException(int gatewayStatus, int upstreamStatus, String body, boolean truncated, String message) {
            super(message);
            this.gatewayStatus = gatewayStatus;
            this.upstreamStatus = upstreamStatus;
            this.responseBody = body == null ? "" : body;
            this.bodyTruncated = truncated;
        }
    }

    // ── 模型解析 ──────────────────────────────────────────────────────────────
    static Mode resolveModel(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) return MODELS_MAP.get(DEFAULT_MODEL);
        String key = name.trim().toLowerCase(Locale.ROOT);
        Mode direct = MODELS_MAP.get(key);
        if (direct != null) return direct;
        String canon = MODEL_ALIASES.get(key);
        if (canon != null) return MODELS_MAP.get(canon);
        // 允许直接传 model_item_key（例如 seed-lite-7b）
        for (Mode m : MODELS_MAP.values()) if (m.modelItemKey.equals(name.trim())) return m;
        throw new IOException("unknown model: " + name);
    }

    static boolean isKnownModel(String name) {
        try { return resolveModel(name) != null; } catch (IOException e) { return false; }
    }

    // ── 设备 query ────────────────────────────────────────────────────────────
    // 整套参数一个都不能少：aid/real_aid/device_id/fp/tea_uuid/web_id 互相自洽，
    // 砍掉任何一项都会让服务端判定设备异常并回 710022004。
    private static String q(String k, String v) throws IOException {
        return k + "=" + URLEncoder.encode(v, "UTF-8");
    }

    static String buildQuery() throws IOException {
        StringBuilder sb = new StringBuilder();
        String[][] pairs = {
                {"aid", "582478"},
                {"real_aid", "582478"},
                {"channel", "win"},
                {"chromium_version", CHROMIUM_VERSION},
                {"client_platform", "pc_client"},
                {"device_id", DEVICE_ID},
                {"device_platform", "web"},
                {"doubao_device_platform", "desktop"},
                {"doubao_pc_version", PC_VERSION},
                {"fp", "verify_" + DEVICE_ID},
                {"language", "zh"},
                {"pc_version", PC_VERSION},
                {"pkg_type", "release_version"},
                {"region", "CN"},
                {"runtime", "web"},
                {"runtime_version", "3.41.2"},
                {"samantha_web", "1"},
                {"sys_region", "CN"},
                {"tea_uuid", DEVICE_ID},
                {"tz_name", "Asia/Shanghai"},
                {"use-olympus-account", "1"},
                {"version_code", "20800"},
                {"web_id", WEB_ID},
                {"web_platform", "desktop"},
                {"web_tab_id", WEB_TAB_ID},
        };
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            sb.append(q(pairs[i][0], pairs[i][1]));
        }
        return sb.toString();
    }

    // ── 请求头 ────────────────────────────────────────────────────────────────
    static Map<String, String> headers(String cookie) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        // 抓包是 "str, str"，单值即可
        h.put("agw-js-conv", "str");
        h.put("user-agent", USER_AGENT);
        h.put("accept", "*/*");
        // 免解压，SSE 直读
        h.put("accept-encoding", "identity");
        h.put("accept-language", "zh-CN,zh;q=0.9");
        h.put("origin", ORIGIN);
        h.put("referer", ORIGIN + "/");
        h.put("cookie", cookie);
        h.put("priority", "u=1, i");
        return h;
    }

    // ── 请求信封（五段式）─────────────────────────────────────────────────────
    private static String uuid() { return UUID.randomUUID().toString(); }

    private static JSONArray localPermissions() {
        JSONArray arr = new JSONArray();
        String[] names = {"ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"};
        for (String n : names) {
            try {
                arr.put(new JSONObject().put("permission_name", n).put("status", 1));
            } catch (JSONException ignored) { }
        }
        return arr;
    }

    /**
     * 五段式信封：client_meta / messages / option / user_context / ext。
     * 所有 ext 值都是字符串（服务端按字符串解析），别改成数字。
     */
    /**
     * 工作模式五段式信封（mode_id=3 / conversation_mode=2 / agent_mode=1）。
     * 与对话模式的差别不只是档位：工作模式必须带 agent_task_param 沙箱参数和
     * general_task_param 大对象，且 ext 挂在 option 里面（不是 body 顶层）。
     * 缺这些上游会按对话模式处理，或者直接报参数不合法。
     */
    /** local_conversation_id 用 16 位随机数字，与客户端一致。 */
    private static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    /**
     * 对话模式信封。三档（快速/思考/专家）靠这两处控制，缺一不可：
     *   option.need_deep_think  数字 0/1/3
     *   body.ext.use_deep_think 字符串 "0"/"1"/"3"
     *
     * 注意这是**对话模式**结构（没有 agent_mode / mode_id / general_task_param）。
     * 工作任务模式那套（agent_mode=1 + general_task_param）实测会把模型与思考开关
     * 一起吃掉，永远回落到 Seed 默认档，所以这里不再使用。
     * 结构对照来源：wangchuxiaoji-oss/doubao2api 的 _build_completion_payload，已实测三档区分成立。
     */
    static JSONObject buildEnvelope(String text, Mode mode, String conversationId) throws IOException {
        try {
            int ndt = mode == null ? 0 : mode.needDeepThink;

            JSONObject textBlock = new JSONObject();
            textBlock.put("text", text == null ? "" : text);
            textBlock.put("icon_url", "");
            textBlock.put("icon_url_dark", "");
            textBlock.put("summary", "");

            JSONObject blockContent = new JSONObject();
            blockContent.put("text_block", textBlock);
            blockContent.put("pc_event_block", "");

            JSONObject block = new JSONObject();
            block.put("block_type", 10000);
            block.put("content", blockContent);
            block.put("block_id", uuid());
            block.put("parent_id", "");
            block.put("meta_info", new JSONArray());
            block.put("append_fields", new JSONArray());
            block.put("is_finish", true);
            block.put("patch_type", 2);

            JSONObject message = new JSONObject();
            message.put("local_message_id", uuid());
            message.put("content_block", new JSONArray().put(block));
            message.put("message_status", 0);

            JSONObject clientMeta = new JSONObject();
            clientMeta.put("local_conversation_id", "local_" + randomDigits(16));
            clientMeta.put("conversation_id", conversationId == null ? "" : conversationId);
            clientMeta.put("bot_id", BOT_ID);
            clientMeta.put("last_section_id", "");
            clientMeta.put("last_message_index", 0);

            JSONObject option = new JSONObject();
            option.put("send_message_scene", "");
            option.put("create_time_ms", 0);
            option.put("collect_id", "");
            option.put("is_audio", false);
            option.put("answer_with_suggest", true);
            option.put("tts_switch", false);
            option.put("need_deep_think", ndt);
            option.put("click_clear_context", false);
            option.put("from_suggest", false);
            option.put("is_regen", false);
            option.put("is_replace", false);
            option.put("disable_sse_cache", false);
            option.put("select_text_action", "");
            option.put("resend_for_regen", false);
            option.put("scene_type", 0);
            option.put("unique_key", uuid());
            option.put("start_seq", 0);
            option.put("need_create_conversation", conversationId == null || conversationId.isEmpty());
            option.put("conversation_init_option", new JSONObject().put("need_ack_conversation", true));
            option.put("regen_query_id", new JSONArray());
            option.put("edit_query_id", new JSONArray());
            option.put("regen_instruction", "");
            option.put("no_replace_for_regen", false);
            option.put("message_from", 0);
            option.put("shared_app_name", "");
            option.put("action_bar_skill_id", 0);
            option.put("sse_recv_event_options", new JSONObject().put("support_chunk_delta", true));
            option.put("is_ai_playground", false);

            // ext 是 body 顶层，不是 option 里 —— 放错位置思考开关会静默失效
            JSONObject ext = new JSONObject();
            ext.put("use_deep_think", String.valueOf(ndt));
            ext.put("fp", "verify_" + DEVICE_ID);
            ext.put("use_submit_pipeline", "1");
            ext.put("commerce_credit_config_enable", "0");
            ext.put("sub_conv_firstmet_type", "1");

            JSONObject envelope = new JSONObject();
            envelope.put("client_meta", clientMeta);
            envelope.put("messages", new JSONArray().put(message));
            envelope.put("option", option);
            envelope.put("chat_ability", new JSONObject());
            envelope.put("ext", ext);
            return envelope;
        } catch (JSONException e) {
            throw new IOException("doubao envelope build failed", e);
        }
    }

    // ── SSE 帧累加 ────────────────────────────────────────────────────────────
    /** 规整化事件类型。 */
    static final int EV_META = 0, EV_DELTA = 1, EV_BODY_END = 2, EV_DONE = 3, EV_ERROR = 4, EV_REASONING = 5;

    static final class StreamState {
        String conversationId = "", sectionId = "", questionId = "";
        String answerMessageId = "", modelType = "", logId = "", brief = "";
        boolean briefSet;
        final StringBuilder text = new StringBuilder();
        final Set<String> channels = new LinkedHashSet<>();
        String[] suggestions = new String[0];
        String assembled() { return text.toString(); }
    }

    static final class Event {
        final int kind;
        final String text;
        final StreamState state;
        Event(int kind, String text, StreamState state) {
            this.kind = kind; this.text = text; this.state = state;
        }
    }

    /**
     * 把一行 SSE 喂进帧累加器；帧完整（遇到空行）时返回 true，
     * 结果可从 eventName()/dataText() 取。多行 data 会拼接后再解析。
     */
    static final class SseFrame {
        private String event = "";
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;

        boolean feed(String raw) {
            String line = stripEol(raw);
            if (line.isEmpty()) {
                if (event.isEmpty()) { reset(); return false; }
                return true;
            }
            if (line.startsWith(":")) return false;              // 注释行
            if (line.startsWith("event:")) { event = line.substring(6).trim(); return false; }
            if (line.startsWith("data:")) {
                String chunk = line.substring(5).trim();
                if (hasData) data.append(chunk); else { data.setLength(0); data.append(chunk); hasData = true; }
                return false;
            }
            return false;                                        // id: 等忽略
        }

        String eventName() { return event; }
        String dataText() { return data.toString(); }
        void reset() { event = ""; data.setLength(0); hasData = false; }

        private static String stripEol(String s) {
            int end = s.length();
            while (end > 0) { char c = s.charAt(end - 1); if (c == '\n' || c == '\r') end--; else break; }
            return s.substring(0, end);
        }
    }

    /** 解析 data 文本为对象；非 JSON 时塞进 _raw 保留现场。 */
    static JSONObject parseData(String text) {
        if (text == null || text.isEmpty()) return new JSONObject();
        try {
            Object v = new com.gaeul.allin.json.JSONTokener(text).nextValue();
            if (v instanceof JSONObject) return (JSONObject) v;
        } catch (Exception ignored) { }
        try { return new JSONObject().put("_raw", text); } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * 处理一个上游事件，产出规整化事件列表。
     * 正文两条通道（CHUNK_DELTA 与 STREAM_CHUNK patch_object:1）互不重叠，
     * 故不按内容去重——按内容去重会误伤「哈哈」这类合法重复片段。
     */
    static java.util.List<Event> handleEvent(String ev, JSONObject data, StreamState st) {
        java.util.List<Event> out = new java.util.ArrayList<>();
        if (ev == null) return out;
        switch (ev) {
            case "SSE_HEARTBEAT":
                break;

            case "SSE_ACK": {
                JSONObject ack = data.optJSONObject("ack_client_meta");
                if (ack != null) {
                    String v = ack.optString("conversation_id", "");
                    if (!v.isEmpty()) st.conversationId = v;
                    v = ack.optString("section_id", "");
                    if (!v.isEmpty()) st.sectionId = v;
                }
                JSONArray ql = data.optJSONArray("query_list");
                if (ql != null && ql.length() > 0) {
                    JSONObject first = ql.optJSONObject(0);
                    if (first != null) st.questionId = first.optString("question_id", "");
                }
                out.add(new Event(EV_META, null, st));
                break;
            }

            case "FULL_MSG_NOTIFY": {
                // 用户自己那条落库；顺带抓服务端真实模型号
                JSONObject msg = data.optJSONObject("message");
                if (msg != null) {
                    JSONObject ext = msg.optJSONObject("ext");
                    if (ext != null) {
                        String mt = ext.optString("model_type", "");
                        if (!mt.isEmpty()) st.modelType = mt;
                    }
                }
                break;
            }

            case "STREAM_MSG_NOTIFY": {
                JSONObject content = data.optJSONObject("content");
                JSONObject meta = data.optJSONObject("meta");
                if (meta != null) {
                    String id = meta.optString("message_id", "");
                    if (!id.isEmpty()) st.answerMessageId = id;
                }
                if (content != null) {
                    JSONObject ext = content.optJSONObject("ext");
                    if (ext != null) {
                        String mt = ext.optString("model_type", "");
                        if (!mt.isEmpty()) st.modelType = mt;
                    }
                    JSONArray blocks = content.optJSONArray("content_block");
                    for (int i = 0; blocks != null && i < blocks.length(); i++) {
                        JSONObject blk = blocks.optJSONObject(i);
                        if (blk == null) continue;
                        if (blk.optInt("block_type", 0) == 10040) {
                            String r = thinkingText(blk);
                            if (!r.isEmpty()) out.add(new Event(EV_REASONING, r, st));
                        } else {
                            String t = blockText(blk);
                            if (addText(st, t, "stream_msg_notify")) out.add(new Event(EV_DELTA, t, st));
                        }
                    }
                }
                break;
            }

            case "CHUNK_DELTA": {
                String raw = data.optString("text", "");
                if (addText(st, raw, "chunk_delta")) out.add(new Event(EV_DELTA, raw, st));
                break;
            }

            case "STREAM_CHUNK": {
                JSONArray ops = data.optJSONArray("patch_op");
                for (int i = 0; ops != null && i < ops.length(); i++) {
                    JSONObject op = ops.optJSONObject(i);
                    if (op == null) continue;
                    int obj = op.optInt("patch_object", -1);
                    JSONObject val = op.optJSONObject("patch_value");
                    if (val == null) continue;
                    if (obj == 1) {
                        // block_type 10000=正文 10040=思考，两者的下游字段完全不同
                        JSONArray blocks = val.optJSONArray("content_block");
                        for (int j = 0; blocks != null && j < blocks.length(); j++) {
                            JSONObject blk = blocks.optJSONObject(j);
                            if (blk == null) continue;
                            if (blk.optInt("block_type", 0) == 10040) {
                                String r = thinkingText(blk);
                                if (!r.isEmpty()) out.add(new Event(EV_REASONING, r, st));
                            } else {
                                String t = blockText(blk);
                                if (addText(st, t, "stream_chunk_patch1")) out.add(new Event(EV_DELTA, t, st));
                            }
                        }
                    } else if (obj == 111) {
                        // tts_content 是正文的镜像通道，正文已从别处拿到，直接丢弃
                    }
                    // obj == 50 是 ext（建议 / is_finish / brief），正文不依赖它，忽略
                }
                break;
            }

            case "SSE_REPLY_END": {
                int endType = data.optInt("end_type", -1);
                if (endType == 1) {
                    JSONObject attr = data.optJSONObject("msg_finish_attr");
                    if (attr != null) { st.brief = attr.optString("brief", ""); st.briefSet = true; }
                    // 正文此刻已完整：立刻通知下游可以出 finish_reason，
                    // 不必等建议阶段（实测可省下 2~3 秒）
                    out.add(new Event(EV_BODY_END, null, st));
                } else if (endType == 3) {
                    // brief 只作观测，绝不覆盖拼接出的正文（它会吃换行、截断长回答）
                    out.add(new Event(EV_DONE, null, st));
                }
                break;
            }

            case "STREAM_ERROR": {
                int code = data.optInt("error_code", 0);
                String msg = data.optString("error_msg", "");
                if (msg.isEmpty()) msg = "doubao stream error";
                String detail = msg;
                JSONObject extra = data.optJSONObject("extra");
                if (extra != null) {
                    String dec = extra.optString("decision", "");
                    if (!dec.isEmpty()) {
                        try {
                            JSONObject d = (JSONObject) new com.gaeul.allin.json.JSONTokener(dec).nextValue();
                            detail = String.format(Locale.ROOT, "%s (风控要求验证: type=%s subtype=%s)",
                                    msg, d.optString("type", ""), d.optString("subtype", ""));
                        } catch (Exception ignored) { }
                    }
                }
                out.add(new Event(EV_ERROR, detail + " [code=" + code + "]", st));
                break;
            }

            case "gateway-error": {
                String msg = data.toString();
                String inner = data.optString("message", "");
                if (!inner.isEmpty()) {
                    try {
                        JSONObject o = (JSONObject) new com.gaeul.allin.json.JSONTokener(inner).nextValue();
                        String m = o.optString("message", "");
                        if (!m.isEmpty()) msg = m;
                    } catch (Exception ignored) { }
                }
                if (msg.length() > 300) msg = msg.substring(0, 300);
                out.add(new Event(EV_ERROR, "gateway-error: " + msg, st));
                break;
            }

            default:
                break;
        }
        return out;
    }

    private static String blockText(JSONObject block) {
        if (block == null) return "";
        JSONObject content = block.optJSONObject("content");
        if (content == null) return "";
        JSONObject tb = content.optJSONObject("text_block");
        return tb == null ? "" : tb.optString("text", "");
    }

    /** 10040 是思考块，正文与它必须分开，否则思考内容会混进回答里。 */
    private static String thinkingText(JSONObject block) {
        if (block == null) return "";
        JSONObject content = block.optJSONObject("content");
        if (content == null) return "";
        JSONObject tb = content.optJSONObject("thinking_block");
        if (tb == null) return "";
        for (String f : new String[]{"text", "content", "thinking"}) {
            String v = tb.optString(f, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static boolean addText(StreamState st, String piece, String channel) {
        if (piece == null || piece.isEmpty()) return false;
        st.channels.add(channel);
        st.text.append(piece);
        return true;
    }

    static int errorCodeOf(String message) {
        if (message == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("code=(\\d+)").matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 把上游错误映射成网关对外状态码。 */
    static int gatewayStatus(int code, int httpStatus) {
        if (code == CODE_RISK_SLIDER || code == CODE_RISK_BLOCKED) return 403;
        if (code == CODE_RATE_LIMITED) return 429;
        if (httpStatus == 401 || httpStatus == 403) return 401;
        if (httpStatus == 429) return 429;
        if (httpStatus >= 400 && httpStatus < 500) return httpStatus;
        return 502;
    }

    static String safeErrorMessage(int status) {
        if (status == 401) return "Doubao web authentication failed";
        if (status == 403) return "Doubao web denied access (risk control). Open the official web app and finish the verification, then retry.";
        if (status == 429) return "Doubao web rate limited";
        return "Doubao web upstream failed";
    }

    static String bytesToJson(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    // ── 额度 / 签到 ────────────────────────────────────────────────────────────
    // 两个接口都用同一套设备 query。实测：
    //   quota  /alice/commerce/sale/subscription/quota/summary/  body {} → code=0
    //   checkin /alice/ug/campaign/landing/checkin               body {scene}
    static final String QUOTA_PATH = "/alice/commerce/sale/subscription/quota/summary/";
    static final String CHECKIN_PATH = "/alice/ug/campaign/landing/checkin";
    /** 工作模式「每日签到」活动的 scene，实测值。 */
    static final String CHECKIN_SCENE = "pc_incentive_task_mvp_v3";

    /** 带 Cookie 的简单 POST，读完整响应（这两个接口都是普通 JSON，不是 SSE）。 */
    private static JSONObject postJson(Transport t, String path, String cookie, String bodyText) throws IOException {
        Map<String, String> h = headers(cookie);
        h.put("accept", "application/json");
        h.put("content-type", "application/json");
        h.remove("agw-js-conv");
        String url = ORIGIN + path + "?" + buildQuery();
        Response r = t.request("POST", url, h, bodyText.getBytes(StandardCharsets.UTF_8));
        try {
            String text = readAll(r.body, 512 * 1024);
            int code = r.status;
            JSONObject parsed = null;
            try {
                Object v = new com.gaeul.allin.json.JSONTokener(text).nextValue();
                if (v instanceof JSONObject) parsed = (JSONObject) v;
            } catch (Exception ignored) { }
            if (code < 200 || code >= 300) {
                throw new UpstreamException(gatewayStatus(0, code), code, text, false,
                        "doubao " + path + " http " + code);
            }
            if (parsed == null) throw new UpstreamException(502, code, text, false, "doubao " + path + " non-json");
            return parsed;
        } finally {
            r.close();
        }
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        if (in == null) return "";
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (b.size() < limit && (n = in.read(buf)) > 0) {
            b.write(buf, 0, Math.min(n, limit - b.size()));
        }
        return b.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * 查额度。实测返回里 quota_remaining_amount 是剩余点数，
     * incentive_quota_section 里带活动赠送额度的窗口明细。
     */
    static JSONObject fetchQuota(Transport t, String cookie) throws IOException {
        JSONObject body = postJson(t, QUOTA_PATH, cookie, "{}");
        int code = body.optInt("code", -1);
        if (code == 710012001) throw new UpstreamException(401, 0, body.toString(), false, "doubao login expired");
        if (code != 0) throw new UpstreamException(502, 0, body.toString(), false,
                "doubao quota code " + code);

        JSONObject data = body.optJSONObject("data");
        JSONObject out = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            String remaining = data == null ? "" : data.optString("quota_remaining_amount", "");
            JSONArray windows = new JSONArray();

            JSONArray usage = data == null ? null : data.optJSONArray("quota_blance_usage");
            for (int i = 0; usage != null && i < usage.length(); i++) {
                JSONObject u = usage.optJSONObject(i);
                if (u == null) continue;
                double total = parseAmount(u.opt("total_amount"));
                double used = parseAmount(u.opt("used_amount"));
                if (total <= 0 && used <= 0) continue;
                // 面板按窗口的 remaining 渲染，这里必须给出剩余而不是总量
                windows.put(new JSONObject()
                        .put("name", u.optString("title", "额度"))
                        .put("remaining", Math.max(0, total - used))
                        .put("total", total)
                        .put("used", used)
                        .put("percent", parseAmount(u.opt("used_percent"))));
            }
            // 活动赠送窗口带到期时间，单独列出来便于面板展示
            JSONObject incentive = data == null ? null : data.optJSONObject("incentive_quota_section");
            JSONArray groups = incentive == null ? null : incentive.optJSONArray("window_limit_groups");
            JSONArray expiring = new JSONArray();
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                JSONArray limits = g.optJSONArray("window_limits");
                for (int j = 0; limits != null && j < limits.length(); j++) {
                    JSONObject l = limits.optJSONObject(j);
                    if (l == null) continue;
                    double total = parseAmount(l.opt("total_amount"));
                    double used = parseAmount(l.opt("used_amount"));
                    if (total <= 0 && used <= 0) continue;
                    expiring.put(new JSONObject()
                            .put("name", g.optString("feature_group_name", "活动赠送额度"))
                            .put("remaining", Math.max(0, total - used))
                            .put("total", total)
                            .put("used", used)
                            .put("endTime", l.optLong("end_time", 0L)));
                }
            }

            // unit 必须是面板认可的四类之一（percent/USD/credits/requests），豆包额度按积分算
            out.put("kind", "allowance").put("scope", "account").put("unit", "credits")
                    .put("observedAt", now);
            if (!remaining.isEmpty() && !"0".equals(remaining) || (data != null && data.has("quota_remaining_amount"))) {
                out.put("remaining", parseAmount(remaining));
            }
            out.put("windows", windows);
            if (expiring.length() > 0) out.put("expiringWindows", expiring);
            JSONObject sub = data == null ? null : data.optJSONObject("current_subscription");
            JSONObject display = sub == null ? null : sub.optJSONObject("display");
            if (display != null) {
                out.put("plan", display.optString("short_name", display.optString("product_name", "")));
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("doubao quota parse failed", e);
        }
    }

    /**
     * 每日签到。接口幂等：重复签到返回 idempotent=true，按「今日已签」处理。
     * 返回结构直接对齐 CheckinCenter 的 outcome 约定。
     */
    static JSONObject checkin(Transport t, String cookie) throws IOException {
        JSONObject body = postJson(t, CHECKIN_PATH, cookie,
                "{\"scene\":\"" + CHECKIN_SCENE + "\"}");
        int code = body.optInt("code", -1);
        JSONObject out = new JSONObject();
        try {
            if (code == 710012001) {
                return out.put("outcome", "auth_required").put("message", "豆包登录已失效，请重新登录");
            }
            if (code != 0) {
                String msg = body.optString("msg", body.optString("message", "签到失败"));
                // 活动没开的活动码按 inactive 处理，别当成失败吓人
                if (msg.contains("活动") || msg.contains("未开始") || msg.contains("已结束")) {
                    return out.put("outcome", "inactive").put("message", msg);
                }
                return out.put("outcome", "failed").put("safeToRetry", true).put("message", msg);
            }
            JSONObject data = body.optJSONObject("data");
            boolean idempotent = data != null && data.optBoolean("idempotent", false);
            boolean checkedIn = data != null && data.optBoolean("checked_in", false);
            int reward = data == null ? 0 : data.optInt("reward_amount", 0);
            int days = data == null ? 0 : data.optInt("continuous_days", 0);

            if (idempotent || (checkedIn && reward <= 0)) {
                out.put("outcome", "done").put("verified", true).put("evidenceType", "official_status");
                out.put("message", days > 0 ? ("今日已签到，连续 " + days + " 天") : "今日已签到");
            } else {
                out.put("outcome", "claimed").put("verified", true).put("evidenceType", "official_claim");
                out.put("rewardAmount", reward);
                out.put("message", reward > 0 ? ("签到成功，获得 " + reward + " 积分") : "签到成功");
            }
            if (days > 0) out.put("continuousDays", days);
            return out;
        } catch (JSONException e) {
            throw new IOException("doubao checkin parse failed", e);
        }
    }

    /** 额度字段实测是字符串（"350"），也兼容数字。 */
    private static double parseAmount(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 0;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private DoubaoWeb() { }
}
