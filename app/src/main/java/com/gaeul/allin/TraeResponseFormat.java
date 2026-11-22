package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Trae has no native response_format. Ask in the system message and validate completed output. */
final class TraeResponseFormat {
    static final int MAX_CONTENT = 1024 * 1024;
    private static final String[] KEYWORDS = {"type", "title", "description", "properties", "required",
            "additionalProperties", "items", "$defs", "$ref", "enum", "const", "anyOf", "minItems", "maxItems",
            "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"};
    private static final String[] TYPES = {"object", "array", "string", "integer", "number", "boolean", "null"};
    private final JSONObject schema;

    private TraeResponseFormat(JSONObject schema) { this.schema = schema; }

    static void projectMessagesFormat(JSONObject request, JSONObject chat) throws IOException {
        JSONObject config = request.optJSONObject("output_config");
        if (config == null || config.isNull("format")) return;
        JSONObject format = config.optJSONObject("format");
        if (format == null || !"json_schema".equals(format.opt("type")) || format.optJSONObject("schema") == null)
            throw bad("invalid Messages output_config.format");
        keys(format, "type", "schema");
        chat.put("response_format", new JSONObject().put("type", "json_schema").put("json_schema",
                new JSONObject().put("name", "output").put("schema", format.optJSONObject("schema"))));
    }

    static TraeResponseFormat from(JSONObject request) throws IOException {
        if (request.isNull("response_format")) return null;
        JSONObject format = request.optJSONObject("response_format");
        if (format == null) throw bad("invalid response_format");
        String type = format.optString("type");
        if ("text".equals(type) || "json_object".equals(type)) {
            keys(format, "type");
            return "text".equals(type) ? null : new TraeResponseFormat(null);
        }
        if (!"json_schema".equals(type)) throw bad("unsupported response_format type");
        keys(format, "type", "json_schema");
        JSONObject envelope = format.optJSONObject("json_schema");
        if (envelope == null) throw bad("missing response_format json_schema");
        keys(envelope, "name", "description", "schema", "strict");
        if (!envelope.optString("name").matches("[A-Za-z0-9_-]{1,64}")
                || !(envelope.opt("name") instanceof String)
                || (envelope.has("description") && !(envelope.opt("description") instanceof String))
                || (envelope.has("strict") && !(envelope.opt("strict") instanceof Boolean))) {
            throw bad("invalid response_format json_schema metadata");
        }
        JSONObject schema = envelope.optJSONObject("schema");
        if (schema == null || schema.toString().length() > 64 * 1024 || !"object".equals(schema.opt("type")))
            throw bad("response_format requires a bounded object schema");
        checkSchema(schema, schema, 0, new int[]{1024});
        return new TraeResponseFormat(schema);
    }

    String instruction(boolean tools) {
        String text = "For the final answer, return exactly one valid JSON object. No Markdown, code fences or surrounding text.";
        if (tools) text += " Tool calls may precede the final answer.";
        if (schema != null) text += " The final answer must match this JSON Schema:\n" + schema;
        return text;
    }

    private static String stripFence(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int line = json.indexOf('\n');
            if (line >= 0 && json.endsWith("\n```")) {
                String language = json.substring(3, line).trim();
                if (language.isEmpty() || "json".equalsIgnoreCase(language))
                    json = json.substring(line + 1, json.length() - 3).trim();
            }
        }
        return json;
    }

    private static void validate(String json, JSONObject schema) throws InvalidOutput {
        final JSONObject parsed;
        try { parsed = TraeProtocol.strictObject(json); }
        catch (Exception invalid) { throw new InvalidOutput(); }
        int[] remaining = {100000};
        if (schema != null && (!matches(schema, schema, parsed, 0, remaining) || remaining[0] < 0)) throw new InvalidOutput();
    }

    Output output(PrivacyMasker.Session mask, JSONObject middleware) throws IOException {
        JSONObject localSchema = schema == null ? null : TraeProtocol.strictObject(PrivacyMasker.unmaskText(schema.toString(), mask));
        return new Output(localSchema, mask, middleware);
    }

    static final class InvalidOutput extends IOException {
        InvalidOutput() { super("Trae returned invalid structured output for response_format"); }
    }

    private static IOException bad(String message) { return new IOException("Trae protocol: " + message); }

    private static void keys(JSONObject json, String... allowed) throws IOException {
        try { ImageContent.keys(json, allowed); }
        catch (IllegalArgumentException unsupported) { throw bad("unsupported response_format schema field"); }
    }

    private static void checkSchema(JSONObject root, JSONObject schema, int depth, int[] remaining) throws IOException {
        if (depth > 24 || --remaining[0] < 0) throw bad("response_format schema is too complex");
        keys(schema, KEYWORDS);
        if (schema.has("$ref") && reference(root, schema.opt("$ref")) == null)
            throw bad("response_format supports local references to $defs only");
        if (schema.has("$defs")) {
            JSONObject definitions = child(schema, "$defs");
            for (String name : definitions.keySet()) checkSchema(root, child(definitions, name), depth + 1, remaining);
        }
        Object type = schema.opt("type");
        if (type instanceof JSONArray) {
            JSONArray types = (JSONArray) type;
            Set<Object> unique = new HashSet<>();
            for (int i = 0; i < types.length(); i++)
                if (!validType(types.opt(i)) || !unique.add(types.opt(i))) throw bad("invalid response_format schema type");
            if (unique.isEmpty()) throw bad("invalid response_format schema type");
        } else if (schema.has("type") && !validType(type)) throw bad("invalid response_format schema type");
        for (String label : new String[]{"title", "description"})
            if (schema.has(label) && !(schema.opt(label) instanceof String)) throw bad("invalid response_format schema annotation");
        if (schema.has("properties")) {
            JSONObject properties = child(schema, "properties");
            for (String key : properties.keySet()) checkSchema(root, child(properties, key), depth + 1, remaining);
        }
        if (schema.has("required")) {
            JSONArray required = array(schema, "required");
            Set<Object> unique = new HashSet<>();
            for (int i = 0; i < required.length(); i++)
                if (!(required.opt(i) instanceof String) || !unique.add(required.opt(i))) throw bad("invalid response_format required list");
        }
        Object additional = schema.opt("additionalProperties");
        if (additional instanceof JSONObject) checkSchema(root, (JSONObject) additional, depth + 1, remaining);
        else if (schema.has("additionalProperties") && !(additional instanceof Boolean)) throw bad("invalid response_format additionalProperties");
        if (schema.has("items")) checkSchema(root, child(schema, "items"), depth + 1, remaining);
        if (schema.has("enum") && array(schema, "enum").length() == 0) throw bad("empty response_format enum");
        if (schema.has("anyOf")) {
            JSONArray options = array(schema, "anyOf");
            if (options.length() == 0) throw bad("empty response_format anyOf");
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) throw bad("invalid response_format anyOf");
                checkSchema(root, option, depth + 1, remaining);
            }
        }
        for (String keyword : new String[]{"minItems", "maxItems", "minLength", "maxLength"}) {
            if (!schema.has(keyword)) continue;
            BigDecimal limit = number(schema.opt(keyword));
            if (limit == null || limit.signum() < 0 || limit.stripTrailingZeros().scale() > 0)
                throw bad("invalid response_format schema size limit");
        }
        for (String keyword : new String[]{"minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"})
            if (schema.has(keyword) && number(schema.opt(keyword)) == null) throw bad("invalid response_format schema number limit");
    }

    private static JSONObject child(JSONObject schema, String key) throws IOException {
        JSONObject value = schema.optJSONObject(key);
        if (value == null) throw bad("invalid response_format schema object");
        return value;
    }

    private static JSONArray array(JSONObject schema, String key) throws IOException {
        JSONArray value = schema.optJSONArray(key);
        if (value == null) throw bad("invalid response_format schema array");
        return value;
    }

    private static JSONObject reference(JSONObject root, Object value) {
        if (!(value instanceof String) || !((String) value).startsWith("#/$defs/")) return null;
        String token = ((String) value).substring(8);
        if (token.isEmpty() || token.indexOf('/') >= 0 || token.matches(".*~(?![01]).*")) return null;
        JSONObject definitions = root.optJSONObject("$defs");
        return definitions == null ? null : definitions.optJSONObject(token.replace("~1", "/").replace("~0", "~"));
    }

    private static boolean validType(Object type) {
        for (String allowed : TYPES) if (allowed.equals(type)) return true;
        return false;
    }

    private static boolean hasType(Object type, Object value) {
        if (type instanceof JSONArray) {
            JSONArray union = (JSONArray) type;
            for (int i = 0; i < union.length(); i++) if (hasType(union.opt(i), value)) return true;
            return false;
        }
        if ("object".equals(type)) return value instanceof JSONObject;
        if ("array".equals(type)) return value instanceof JSONArray;
        if ("string".equals(type)) return value instanceof String;
        if ("boolean".equals(type)) return value instanceof Boolean;
        if ("null".equals(type)) return value == JSONObject.NULL;
        if ("number".equals(type)) return number(value) != null;
        if ("integer".equals(type)) {
            BigDecimal n = number(value);
            return n != null && n.stripTrailingZeros().scale() <= 0;
        }
        return true;
    }

    private static BigDecimal number(Object value) {
        if (!(value instanceof Number)) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static boolean equal(Object a, Object b, int[] remaining) {
        if (--remaining[0] < 0) return false;
        if (a == b) return true;
        if (a instanceof Number && b instanceof Number) {
            BigDecimal x = number(a), y = number(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (!x.keySet().equals(y.keySet())) return false;
            for (String key : x.keySet()) if (!equal(x.opt(key), y.opt(key), remaining)) return false;
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!equal(x.opt(i), y.opt(i), remaining)) return false;
            return true;
        }
        return a != null && a.equals(b);
    }

    private static boolean within(BigDecimal value, JSONObject schema, String low, String high, boolean exclusive) {
        BigDecimal min = number(schema.opt(low)), max = number(schema.opt(high));
        int margin = exclusive ? 1 : 0;
        return (min == null || value.compareTo(min) >= margin) && (max == null || max.compareTo(value) >= margin);
    }

    private static boolean matches(JSONObject root, JSONObject schema, Object value, int depth, int[] remaining) {
        if (depth > 96 || --remaining[0] < 0 || !hasType(schema.opt("type"), value)) return false;
        if (schema.has("$ref")) {
            JSONObject target = reference(root, schema.opt("$ref"));
            if (target == null || !matches(root, target, value, depth + 1, remaining)) return false;
        }
        if (schema.has("const") && !equal(schema.opt("const"), value, remaining)) return false;
        JSONArray options = schema.optJSONArray("enum");
        if (options != null) {
            boolean found = false;
            for (int i = 0; i < options.length() && !found; i++) found = equal(options.opt(i), value, remaining);
            if (!found) return false;
        }
        JSONArray branches = schema.optJSONArray("anyOf");
        if (branches != null) {
            boolean found = false;
            for (int i = 0; i < branches.length() && !found; i++) found = matches(root, branches.optJSONObject(i), value, depth + 1, remaining);
            if (!found) return false;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value, properties = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            for (int i = 0; required != null && i < required.length(); i++) if (!object.has(required.optString(i))) return false;
            Object additional = schema.opt("additionalProperties");
            for (String key : object.keySet()) {
                JSONObject rule = properties == null ? null : properties.optJSONObject(key);
                if (rule == null && Boolean.FALSE.equals(additional)) return false;
                if (rule == null && additional instanceof JSONObject) rule = (JSONObject) additional;
                if (rule != null && !matches(root, rule, object.opt(key), depth + 1, remaining)) return false;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (!within(BigDecimal.valueOf(array.length()), schema, "minItems", "maxItems", false)) return false;
            JSONObject items = schema.optJSONObject("items");
            for (int i = 0; items != null && i < array.length(); i++) if (!matches(root, items, array.opt(i), depth + 1, remaining)) return false;
        } else if (value instanceof String) {
            String text = (String) value;
            return within(BigDecimal.valueOf(text.codePointCount(0, text.length())), schema, "minLength", "maxLength", false);
        } else if (value instanceof Number) {
            BigDecimal n = number(value);
            return n != null && within(n, schema, "minimum", "maximum", false)
                    && within(n, schema, "exclusiveMinimum", "exclusiveMaximum", true);
        }
        return remaining[0] >= 0;
    }

    /** Hold the JSON until a normal finish; truncated output and tool turns retain their own semantics. */
    static final class Output {
        private final StringBuilder text = new StringBuilder();
        private final JSONObject schema;
        private final PrivacyMasker.Session mask;
        private final JSONObject middleware;

        Output(JSONObject schema, PrivacyMasker.Session mask, JSONObject middleware) {
            this.schema = schema;
            this.mask = mask;
            this.middleware = middleware;
        }

        List<JSONObject> accept(List<JSONObject> chunks) throws IOException {
            List<JSONObject> result = new ArrayList<>(chunks.size() + 1);
            for (JSONObject chunk : chunks) {
                JSONObject choice = chunk.getJSONArray("choices").getJSONObject(0);
                JSONObject delta = choice.getJSONObject("delta");
                if (delta.opt("content") instanceof String) {
                    text.append((String) delta.remove("content"));
                    if (text.length() > MAX_CONTENT) throw new InvalidOutput();
                    if (delta.length() == 0 && choice.isNull("finish_reason")) continue;
                }
                if (delta.has("tool_calls") || !choice.isNull("finish_reason")) {
                    boolean complete = "stop".equals(choice.optString("finish_reason"));
                    String content = complete ? stripFence(text.toString()) : text.toString();
                    if (Middleware.wordGuardEnabled(middleware)) content = Middleware.maskWords(content, middleware);
                    if (content.length() > MAX_CONTENT) throw new InvalidOutput();
                    if (complete) validate(PrivacyMasker.unmaskText(content, mask), schema);
                    addContent(result, chunk, content);
                    text.setLength(0);
                }
                result.add(chunk);
            }
            return result;
        }

        private static void addContent(List<JSONObject> result, JSONObject chunk, String text) {
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + 16384, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                JSONObject answer = new JSONObject(chunk.toString());
                JSONObject choice = answer.getJSONArray("choices").getJSONObject(0);
                choice.put("delta", new JSONObject().put("content", text.substring(start, end)));
                choice.put("finish_reason", JSONObject.NULL);
                result.add(answer);
                start = end;
            }
        }
    }
}
