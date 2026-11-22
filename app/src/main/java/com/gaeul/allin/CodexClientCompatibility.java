package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.Iterator;

/**
 * Codex-specific protocol metadata, not a general JSON sanitizer. The shared bridges remain strict.
 * Only known cache hints at protocol-owned paths are omitted for the ChatGPT Responses backend.
 * Tool schemas, argument strings, result payloads and arbitrary business objects are never walked.
 */
final class CodexClientCompatibility {
    private CodexClientCompatibility() { }

    /** A read-only ingress hint; never selects/rotates accounts or changes routing order. */
    static boolean mayRouteToCodex(JSONObject config, String model) {
        if (config == null || model == null) return false;
        JSONObject middleware = config.optJSONObject("middleware");
        JSONObject modelMap = middleware == null ? null : middleware.optJSONObject("modelMap");
        String mapped = modelMap == null ? model : modelMap.optString(model, model);
        if (mapped != null && mapped.startsWith("codex/")) return true;
        if (mapped != null && mapped.startsWith("group/")) {
            JSONArray groups = config.optJSONArray("groups");
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null || !mapped.substring(6).equals(group.optString("id"))) continue;
                JSONArray targets = group.optJSONArray("targets");
                for (int j = 0; targets != null && j < targets.length(); j++)
                    if (targets.optString(j, "").startsWith("codex/")) return true;
            }
            return false;
        }
        // The gateway supports old bare names by finding the first matching catalog entry.
        if (mapped != null && mapped.indexOf('/') < 0) {
            JSONArray providers = config.optJSONArray("providers");
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject provider = providers.optJSONObject(i);
                JSONArray models = provider == null ? null : provider.optJSONArray("models");
                for (int j = 0; models != null && j < models.length(); j++)
                    if (mapped.equals(models.optString(j))) return "codex".equals(provider.optString("id"));
            }
        }
        return false;
    }

    static JSONObject chat(JSONObject request) {
        JSONObject body = detached(request);
        cacheHint(body, "request");
        explicitCacheOptions(body);
        JSONArray messages = body.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue; // The existing bridge reports invalid shapes.
            String path = "messages." + i;
            cacheHint(message, path);
            instructionRole(message);
            contentHints(message.optJSONArray("content"), path + ".content");
        }
        JSONArray tools = body.optJSONArray("tools");
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            cacheHint(tool, "tools." + i);
            JSONObject function = tool.optJSONObject("function");
            if ("function".equals(tool.optString("type")) && function != null)
                cacheHint(function, "tools." + i + ".function");
        }
        return body;
    }

    /** Applied immediately before the final Codex policy/serialization, including native requests. */
    static JSONObject responses(JSONObject request) {
        JSONObject body = detached(request);
        cacheHint(body, "request");
        responseTools(body.optJSONArray("tools"), "tools", 0);
        JSONArray input = body.optJSONArray("input");
        for (int i = 0; input != null && i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null) continue;
            String path = "input." + i;
            cacheHint(item, path);
            String type = item.optString("type", "");
            if ("message".equals(type) || type.isEmpty() && item.has("role")) {
                instructionRole(item);
                CodexChatProjection.validatePhase(item);
                contentHints(item.optJSONArray("content"), path + ".content");
            }
            if ("additional_tools".equals(type) || "tool_search_output".equals(type))
                responseTools(item.optJSONArray("tools"), path + ".tools", 0);
        }
        return body;
    }

    /** Shadow input for generic Chat middleware/Guard; the final Codex request uses the original. */
    static JSONObject responsesForProjection(JSONObject request) {
        JSONObject body = responses(request);
        JSONArray input = body.optJSONArray("input");
        if (input != null) {
            JSONArray projected = new JSONArray();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.optJSONObject(i);
                if (item == null) { projected.put(input.opt(i)); continue; }
                String type = item.optString("type", "message");
                if ("configuration_update".equals(type) || "additional_tools".equals(type)
                        || "tool_search_output".equals(type) || "item_reference".equals(type)) continue;
                if ("message".equals(type)) {
                    CodexChatProjection.validatePhase(item);
                    JSONObject message = projectFields(item, "type", "role", "content", "id", "status");
                    projectContent(message, "content");
                    projected.put(message);
                } else if ("function_call".equals(type)) {
                    projected.put(projectFields(item, "type", "id", "call_id", "name", "arguments", "status"));
                } else if ("function_call_output".equals(type)) {
                    projected.put(projectFields(item, "type", "id", "call_id", "output", "status"));
                } else if ("reasoning".equals(type)) {
                    JSONObject reasoning = projectFields(item, "type", "id", "summary", "content", "encrypted_content", "status");
                    projectContent(reasoning, "summary"); projectContent(reasoning, "content");
                    projected.put(reasoning);
                } else projected.put(item); // Unsupported semantic item kinds still fail explicitly.
            }
            body.put("input", projected);
        }
        JSONArray tools = body.optJSONArray("tools");
        if (tools != null) {
            JSONArray projected = new JSONArray();
            projectionTools(tools, projected, 0); body.put("tools", projected);
        }
        return body;
    }

    private static void projectContent(JSONObject owner, String field) {
        JSONArray content = owner.optJSONArray(field);
        if (content == null) return;
        JSONArray projected = new JSONArray();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) { projected.put(content.opt(i)); continue; }
            String type = part.optString("type");
            if ("input_text".equals(type) || "output_text".equals(type) || "summary_text".equals(type) || "reasoning_text".equals(type))
                projected.put(projectFields(part, "type", "text"));
            else if ("input_image".equals(type)) projected.put(projectFields(part, "type", "image_url", "detail"));
            else if ("refusal".equals(type)) projected.put(projectFields(part, "type", "refusal"));
            else projected.put(part); // Preserve unsupported semantic kinds for an explicit error.
        }
        owner.put(field, projected);
    }

    private static JSONObject projectFields(JSONObject value, String... names) {
        JSONObject out = new JSONObject();
        for (String name : names) if (value.has(name)) out.put(name, value.opt(name));
        return out;
    }

    private static void projectionTools(JSONArray tools, JSONArray out, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Codex tool namespace nesting is too deep");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) { out.put(tools.opt(i)); continue; }
            if ("namespace".equals(tool.optString("type"))) {
                JSONArray nested = tool.optJSONArray("tools");
                if (nested == null) throw new IllegalArgumentException("Invalid Codex tool namespace");
                projectionTools(nested, out, depth + 1);
            } else if ("function".equals(tool.optString("type")))
                out.put(projectFields(tool, "type", "name", "description", "parameters", "strict"));
            else out.put(tool);
        }
    }

    private static void instructionRole(JSONObject message) {
        // Codex rejects input messages whose role is system. Its high-priority instruction
        // role is developer. Map only the protocol role, retaining every part and its order;
        // leading string instructions still use the bridge's existing instructions field.
        if ("system".equals(message.opt("role"))) message.put("role", "developer");
    }

    private static JSONObject detached(JSONObject request) {
        if (request == null) throw new IllegalArgumentException("Codex request is required");
        return new JSONObject(request.toString());
    }

    private static void contentHints(JSONArray parts, String path) {
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null) cacheHint(part, path + "." + i);
        }
    }

    private static void responseTools(JSONArray tools, String path, int depth) {
        if (tools == null) return;
        if (depth > 64) throw new IllegalArgumentException("Codex tool namespace nesting is too deep");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            cacheHint(tool, path + "." + i);
            if ("namespace".equals(tool.optString("type")))
                responseTools(tool.optJSONArray("tools"), path + "." + i + ".tools", depth + 1);
        }
    }

    private static void cacheHint(JSONObject owner, String path) {
        if (!owner.has("cache_control")) return;
        Object value = owner.opt("cache_control");
        if (value != JSONObject.NULL) {
            if (!(value instanceof JSONObject)) throw invalid(path + ".cache_control");
            JSONObject mark = (JSONObject) value;
            for (Iterator<String> keys = mark.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!"type".equals(key) && !"ttl".equals(key)) throw invalid(path + ".cache_control");
            }
            if (!"ephemeral".equals(mark.opt("type"))) throw invalid(path + ".cache_control");
            if (mark.has("ttl") && !("5m".equals(mark.opt("ttl")) || "1h".equals(mark.opt("ttl"))))
                throw invalid(path + ".cache_control");
        }
        // This backend's cache follows its session headers, not Anthropic cache breakpoints.
        owner.remove("cache_control");
    }

    private static void explicitCacheOptions(JSONObject body) {
        if (!body.has("prompt_cache_options")) return;
        Object value = body.opt("prompt_cache_options");
        if (value != JSONObject.NULL) {
            if (!(value instanceof JSONObject)) throw invalid("prompt_cache_options");
            JSONObject options = (JSONObject) value;
            if (options.length() != 1 || !"explicit".equals(options.opt("mode"))) throw invalid("prompt_cache_options");
        }
        // pi-ai's explicit cache option is already removed by the final Codex policy; Chat
        // needs it removed before the strict Chat -> Responses bridge, rather than after it.
        body.remove("prompt_cache_options");
    }

    private static IllegalArgumentException invalid(String path) {
        return new IllegalArgumentException("Invalid Codex cache hint at " + path);
    }
}
