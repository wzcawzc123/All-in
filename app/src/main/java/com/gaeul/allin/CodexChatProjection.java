package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Codex-only semantic reader, following Magpie's readChat -> buildResponses separation.
 * Project protocol fields into a fresh standard Chat object; never prune business JSON.
 * The shared bridge then validates/builds the standard Responses representation.
 */
final class CodexChatProjection {
    static final class Result {
        final JSONObject body;
        final boolean omittedExtensions;
        final boolean stopAdvisory;
        Result(JSONObject body, boolean omitted, boolean stop) {
            this.body = body; this.omittedExtensions = omitted; this.stopAdvisory = stop;
        }
    }
    private static final Set<String> TOP = keys("model", "messages", "stream", "stream_options", "user", "safety_identifier",
            "max_tokens", "max_completion_tokens", "temperature", "top_p", "parallel_tool_calls", "metadata",
            "prompt_cache_key", "service_tier", "reasoning_effort", "tools", "tool_choice", "response_format", "n",
            "store", "reasoning", "output_config", "thinking", "stop");
    private static Set<String> keys(String... names) { return new HashSet<String>(Arrays.asList(names)); }
    private static boolean extras(JSONObject object, Set<String> allowed) {
        for (Iterator<String> it = object.keys(); it.hasNext();) if (!allowed.contains(it.next())) return true;
        return false;
    }
    private static JSONObject fields(JSONObject source, String... names) {
        JSONObject out = new JSONObject();
        for (String name : names) if (source.has(name)) out.put(name, source.opt(name));
        return out;
    }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid Codex Chat " + field);
    }
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw invalid(key);
        return (String) value;
    }
    static Result convert(JSONObject request, String model) throws Exception {
        JSONObject raw = CodexClientCompatibility.chat(request);
        // Known stateful/functional controls must not be reclassified as incidental metadata.
        for (String key : new String[]{"previous_response_id", "conversation", "prompt", "background", "max_tool_calls", "context_management"})
            if (raw.has(key) && !raw.isNull(key)
                    && !("background".equals(key) && Boolean.FALSE.equals(raw.opt(key)))
                    && !("previous_response_id".equals(key) && "".equals(raw.opt(key)))
                    && !("context_management".equals(key) && raw.opt(key) instanceof JSONArray && ((JSONArray)raw.opt(key)).length() == 0))
                throw new IllegalArgumentException("Codex Chat cannot preserve " + key + "; provide complete conversation input");
        if (raw.has("store") && !raw.isNull("store")) {
            if (!(raw.opt("store") instanceof Boolean)) throw invalid("store: expected boolean or null");
            if (raw.getBoolean("store")) throw new IllegalArgumentException("Codex does not support store=true");
        }
        boolean omitted = extras(raw, TOP), stopAdvisory = false;
        JSONObject chat = fields(raw, "model", "max_tokens", "max_completion_tokens", "response_format", "tool_choice");
        for (String nullable : new String[]{"max_tokens", "max_completion_tokens", "response_format", "tool_choice"})
            if (chat.has(nullable) && chat.isNull(nullable)) chat.remove(nullable);
        if (chat.has("response_format") && !(chat.opt("response_format") instanceof JSONObject)) throw invalid("response_format");
        if (chat.has("tool_choice")) {
            Object choice = chat.opt("tool_choice");
            if (choice instanceof String) {
                if (!keys("auto", "none", "required").contains(choice)) throw invalid("tool_choice");
            } else if (choice instanceof JSONObject) {
                JSONObject named = (JSONObject) choice;
                if (!"function".equals(named.opt("type")) || named.optJSONObject("function") == null) throw invalid("tool_choice");
                string(named.getJSONObject("function"), "name");
            } else throw invalid("tool_choice");
        }
        for (String key : new String[]{"stream", "parallel_tool_calls"}) if (raw.has(key) && !raw.isNull(key)) {
            if (!(raw.opt(key) instanceof Boolean)) throw invalid(key + ": expected boolean");
            chat.put(key, raw.opt(key));
        }
        for (String key : new String[]{"user", "safety_identifier", "prompt_cache_key", "service_tier"}) if (raw.has(key) && !raw.isNull(key)) {
            if (!(raw.opt(key) instanceof String)) throw invalid(key + ": expected string");
            chat.put(key, raw.opt(key));
        }
        for (String key : new String[]{"temperature", "top_p"}) if (raw.has(key) && !raw.isNull(key)) {
            if (!(raw.opt(key) instanceof Number)) throw invalid(key + ": expected number");
            chat.put(key, raw.opt(key));
        }
        if (raw.has("metadata") && !raw.isNull("metadata")) {
            if (!(raw.opt("metadata") instanceof JSONObject)) throw invalid("metadata: expected object");
            // Like Magpie's Chat reader, client bookkeeping metadata is not model input.
            // Codex cannot persist it (store=false); retain it at ingress, not in the backend body.
            omitted = true;
        }
        if (raw.has("n") && !raw.isNull("n")) {
            Object n = raw.opt("n");
            if (!(n instanceof Number) || ((Number)n).doubleValue() != 1) throw invalid("n: Responses supports one choice");
            chat.put("n", 1);
        }
        if (raw.has("stream_options") && !raw.isNull("stream_options")) {
            JSONObject options = raw.optJSONObject("stream_options");
            if (options == null) throw invalid("stream_options: expected object");
            JSONObject projected = fields(options, "include_usage", "include_obfuscation");
            omitted |= extras(options, keys("include_usage", "include_obfuscation"));
            for (String key : new String[]{"include_usage", "include_obfuscation"})
                if (projected.has(key) && !(projected.opt(key) instanceof Boolean)) throw invalid("stream_options." + key);
            chat.put("stream_options", projected);
        }
        String effort = effort(raw);
        if (effort != null) chat.put("reasoning_effort", effort);
        else if (raw.has("thinking") && !raw.isNull("thinking")) omitted = true;
        if (raw.has("stop") && !raw.isNull("stop")) {
            Object stop = raw.opt("stop");
            if (stop instanceof String) stopAdvisory = !((String) stop).isEmpty();
            else if (stop instanceof JSONArray) {
                JSONArray values = (JSONArray) stop;
                for (int i = 0; i < values.length(); i++) {
                    if (!(values.opt(i) instanceof String)) throw invalid("stop: expected strings");
                    stopAdvisory |= !values.getString(i).isEmpty();
                }
            } else throw invalid("stop: expected string, array or null");
        }
        JSONArray messages = raw.optJSONArray("messages");
        if (messages == null) throw invalid("messages: required array");
        JSONArray turns = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) throw invalid("messages." + i);
            String role = string(message, "role");
            if (!keys("developer", "system", "user", "assistant", "tool").contains(role)) throw invalid("role " + role);
            JSONObject turn = fields(message, "role", "tool_call_id");
            omitted |= extras(message, keys("role", "content", "tool_calls", "tool_call_id", "reasoning_content", "reasoning", "phase"));
            if (message.has("phase") && !message.isNull("phase")) validatePhase(message);
            Object content = message.opt("content");
            if (message.has("phase") && !message.isNull("phase")
                    && !(content instanceof String || content instanceof JSONArray && ((JSONArray)content).length() > 0))
                throw invalid("phase requires assistant message content; use complete native input");
            if (content == null || content == JSONObject.NULL) turn.put("content", JSONObject.NULL);
            else if (content instanceof String) turn.put("content", content);
            else if (content instanceof JSONArray) {
                JSONArray parts = new JSONArray();
                for (int j = 0; j < ((JSONArray) content).length(); j++) {
                    JSONObject part = ((JSONArray) content).optJSONObject(j);
                    if (part == null) throw invalid("content part");
                    String type = string(part, "type");
                    JSONObject projected;
                    if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type)) {
                        if (!(part.opt("text") instanceof String)) throw invalid("content.text");
                        projected = fields(part, "text").put("type", "text");
                        omitted |= extras(part, keys("type", "text"));
                    } else if ("image_url".equals(type)) {
                        projected = fields(part, "type", "image_url");
                        JSONObject image = projected.optJSONObject("image_url");
                        if (image != null) {
                            omitted |= extras(image, keys("url", "detail"));
                            projected.put("image_url", fields(image, "url", "detail"));
                        }
                        ImageContent.chatUrl(projected, false);
                        omitted |= extras(part, keys("type", "image_url"));
                    } else throw new IllegalArgumentException("Codex Chat content type cannot be represented: " + type);
                    parts.put(projected);
                }
                if ("tool".equals(role)) {
                    StringBuilder text = new StringBuilder(); int textParts = 0;
                    for (int j = 0; j < parts.length(); j++) if ("text".equals(parts.getJSONObject(j).getString("type"))) {
                        if (textParts++ > 0) text.append('\n'); text.append(parts.getJSONObject(j).getString("text"));
                    }
                    turn.put("content", text.toString()); // Native tool-output parts are restored below.
                } else turn.put("content", parts);
            } else throw invalid("content");
            Object reasoning = message.has("reasoning_content") ? message.opt("reasoning_content") : message.opt("reasoning");
            if (reasoning != null && reasoning != JSONObject.NULL) {
                if (!(reasoning instanceof String) || !"assistant".equals(role)) throw invalid("reasoning_content");
                // Magpie builds Codex history with sealed native reasoning only. Plain Chat
                // reasoning is display metadata, not trusted Responses reasoning input.
                omitted = true;
            }
            if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls == null) throw invalid("tool_calls");
                JSONArray projected = new JSONArray();
                for (int j = 0; j < calls.length(); j++) {
                    JSONObject call = calls.optJSONObject(j);
                    if (call == null || !"function".equals(call.optString("type"))) throw invalid("tool_call type");
                    JSONObject fn = call.optJSONObject("function");
                    if (fn == null) throw invalid("tool_call function");
                    projected.put(fields(call, "id", "type").put("function", fields(fn, "name", "arguments")));
                    omitted |= extras(call, keys("id", "type", "function")) || extras(fn, keys("name", "arguments"));
                }
                turn.put("tool_calls", projected);
            }
            turns.put(turn);
        }
        chat.put("messages", turns);
        if (raw.has("tools") && !raw.isNull("tools")) {
            JSONArray tools = raw.optJSONArray("tools");
            if (tools == null) throw invalid("tools");
            JSONArray projected = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null) throw invalid("tools." + i);
                JSONObject fn = tool.optJSONObject("function");
                if (fn == null && tool.has("name") && tool.has("input_schema"))
                    fn = fields(tool, "name", "description", "strict").put("parameters", tool.opt("input_schema"));
                else if (!"function".equals(tool.optString("type"))) throw invalid("tool type");
                if (fn == null) throw invalid("function tool");
                omitted |= extras(fn, keys("name", "description", "parameters", "strict"));
                JSONObject function = fields(fn, "name", "description", "parameters", "strict");
                string(function, "name");
                if (function.has("parameters") && !(function.opt("parameters") instanceof JSONObject)) throw invalid("tool parameters");
                if (function.has("strict") && !(function.opt("strict") instanceof Boolean)) throw invalid("tool strict");
                projected.put(new JSONObject().put("type", "function").put("function", function));
            }
            chat.put("tools", projected);
        }
        JSONObject responses = ResponsesBridge.chatToResponses(chat, model);
        restoreNativeHistory(raw, turns, responses);
        return new Result(responses, omitted, stopAdvisory);
    }
    static void validatePhase(JSONObject message) {
        Object phase = message.opt("phase");
        if (phase == null || phase == JSONObject.NULL) return;
        if (!"assistant".equals(message.opt("role")) || !("commentary".equals(phase) || "final_answer".equals(phase)))
            throw invalid("assistant phase");
    }
    private static String effort(JSONObject raw) {
        if (raw.has("reasoning_effort") && !raw.isNull("reasoning_effort")) {
            if (!(raw.opt("reasoning_effort") instanceof String)) throw invalid("reasoning_effort");
            return raw.getString("reasoning_effort");
        }
        for (String key : new String[]{"reasoning", "output_config"}) if (raw.has(key) && !raw.isNull(key)) {
            JSONObject value = raw.optJSONObject(key);
            if (value == null) throw invalid(key);
            if (value.has("effort") && !value.isNull("effort")) {
                if (!(value.opt("effort") instanceof String)) throw invalid(key + ".effort");
                return value.getString("effort");
            }
        }
        if (raw.has("thinking") && !raw.isNull("thinking")) {
            JSONObject thinking = raw.optJSONObject("thinking");
            if (thinking == null) throw invalid("thinking");
            if ("disabled".equals(thinking.opt("type"))) return "none";
            if ("adaptive".equals(thinking.opt("type"))) return null; // Magpie leaves adaptive hints to the target's default.
            if ("enabled".equals(thinking.opt("type"))) {
                Object value = thinking.opt("budget_tokens");
                if (!(value instanceof Number) || ((Number)value).doubleValue() <= 0 || ((Number)value).doubleValue() != Math.floor(((Number)value).doubleValue())) throw invalid("thinking.budget_tokens");
                long budget = ((Number)value).longValue();
                return budget <= 4096 ? "low" : budget <= 12000 ? "medium" : budget <= 24000 ? "high" : "xhigh";
            }
            throw invalid("thinking.type");
        }
        return null;
    }
    private static void restoreNativeHistory(JSONObject raw, JSONArray turns, JSONObject responses) {
        JSONArray input = responses.getJSONArray("input"), source = raw.getJSONArray("messages");
        int at = 0;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.getJSONObject(i), message = source.getJSONObject(i);
            String role = turn.getString("role");
            if (turn.has("reasoning_content")) at++;
            if (("system".equals(role) || "developer".equals(role)) && at == 0 && turn.opt("content") instanceof String) continue;
            if ("tool".equals(role)) {
                JSONArray parts = message.optJSONArray("content");
                if (parts != null) {
                    JSONArray result = new JSONArray(); boolean hasImage = false;
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.getJSONObject(j);
                        if ("text".equals(part.optString("type")) || "input_text".equals(part.optString("type")) || "output_text".equals(part.optString("type")))
                            result.put(new JSONObject().put("type", "input_text").put("text", part.getString("text")));
                        else {
                            hasImage = true;
                            Object image = part.opt("image_url");
                            JSONObject value = new JSONObject().put("type", "input_image");
                            if (image instanceof String) value.put("image_url", image);
                            else { JSONObject url = (JSONObject) image; value.put("image_url", url.getString("url")); if (url.has("detail")) value.put("detail", url.opt("detail")); }
                            result.put(value);
                        }
                    }
                    if (hasImage) input.getJSONObject(at).put("output", result);
                }
                at++; continue;
            }
            Object content = turn.opt("content");
            if (content instanceof String || content instanceof JSONArray && ((JSONArray)content).length() > 0) {
                if (message.has("phase") && !message.isNull("phase")) input.getJSONObject(at).put("phase", message.opt("phase"));
                at++;
            }
            JSONArray calls = turn.optJSONArray("tool_calls");
            if (calls != null) at += calls.length();
        }
        if (at != input.length()) throw new IllegalStateException("Codex Chat projection/history mismatch");
    }
}
