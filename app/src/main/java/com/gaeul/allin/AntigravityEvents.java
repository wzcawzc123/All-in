package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads Code Assist data lines and standalone JSON errors appended to the stream. */
final class AntigravityEvents implements AutoCloseable {
    private final BoundedSseReader reader;
    private String pending;

    AntigravityEvents(InputStream upstream) {
        reader = new BoundedSseReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
    }

    JSONObject next() throws IOException {
        String line;
        while ((line = takeLine()) != null) {
            if (line.startsWith("data:")) {
                JSONObject event = object(line.substring(5).trim());
                if (event != null) return event;
            } else if (line.trim().startsWith("{")) {
                JSONObject event = bareObject(line);
                if (event != null && error(event) != null) return event;
            }
        }
        return null;
    }

    private String takeLine() throws IOException {
        if (pending == null) return reader.readLine();
        String line = pending;
        pending = null;
        return line;
    }

    private JSONObject bareObject(String line) throws IOException {
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        while (line != null) {
            if (body.length() + line.length() + 1 > BoundedSseReader.MAX_EVENT_CHARS)
                throw new IOException("Antigravity error frame exceeded the stream limit");
            body.append(line).append('\n');
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') quoted = false;
                } else if (c == '"') quoted = true;
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
            }
            if (quoted || depth <= 0) return object(body.toString());
            if (depth > 64) throw new IOException("Antigravity error frame exceeds the nesting limit");
            line = reader.readLine();
            if (line != null && (line.startsWith("data:") || line.startsWith("event:") || line.startsWith(":"))) {
                pending = line;
                break;
            }
        }
        return null;
    }

    private static JSONObject object(String text) {
        if (!text.trim().startsWith("{")) return null;
        try {
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            return value instanceof JSONObject && parser.nextClean() == 0 ? (JSONObject) value : null;
        } catch (Exception invalid) { return null; }
    }

    static String error(JSONObject event) {
        JSONObject response = event.optJSONObject("response");
        Object value = event.opt("error");
        if (value == null && response != null) value = response.opt("error");
        String type = event.optString("type", "");
        if (value == null && !"error".equals(type) && !"response.failed".equals(type)) return null;
        String fallback = "Antigravity reported an upstream error";
        if (value instanceof JSONObject) {
            JSONObject detail = (JSONObject) value;
            String message = detail.optString("message", detail.optString("type", fallback));
            String code = detail.optString("code", "");
            String status = detail.optString("status", "");
            String facts = code.matches("[0-9]{1,5}") ? " code=" + code : "";
            if (status.matches("[A-Z_]{1,48}")) facts += " status=" + status;
            return (message.isEmpty() ? fallback : message) + facts;
        }
        return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
    }

    @Override public void close() {
        try { reader.close(); } catch (IOException ignored) { /* Result already determined. */ }
    }
}
