package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.util.Map;

/** Explicit conversation identities only. Never infer a conversation from a user/IP/prompt. */
final class ConversationSession {
    private ConversationSession() {}
    private static String safe(Object raw) {
        if (!(raw instanceof String)) return "";
        String value=((String)raw).trim();
        if (value.isEmpty() || value.length()>512) return "";
        for(int i=0;i<value.length();i++) if(value.charAt(i)<0x20 || value.charAt(i)==0x7f) return "";
        return value;
    }
    private static String header(Map<String,String> headers,String... names) {
        if(headers==null) return "";
        for(String name:names) for(Map.Entry<String,String> row:headers.entrySet())
            if(name.equalsIgnoreCase(row.getKey())) {String value=safe(row.getValue());if(!value.isEmpty())return value;}
        return "";
    }
    static String header(Map<String,String> headers) {
        String session=header(headers,"x-magpie-session","x-claude-code-session-id","x-session-id","session_id","x-conversation-id",
                "x-opencode-session-id","x-opencode-session","x-session-affinity","session-id");
        String thread=header(headers,"thread-id","thread_id","x-thread-id");
        String agent=header(headers,"x-claude-code-agent-id");
        if(session.isEmpty()) session=thread;
        if(session.isEmpty()) return "";
        if(thread.isEmpty() && agent.isEmpty()) return session;
        return new com.gaeul.allin.json.JSONArray().put(session).put(thread).put(agent).toString();
    }
    static String body(JSONObject body) {
        if(body==null) return "";
        String session=safe(body.opt("conversation_id"));
        if(session.isEmpty()) session=safe(body.opt("session_id"));
        String thread=safe(body.opt("thread_id"));
        JSONObject metadata=body.optJSONObject("metadata");
        if(metadata!=null) {
            if(session.isEmpty()) session=safe(metadata.opt("session_id"));
            if(thread.isEmpty()) thread=safe(metadata.opt("thread_id"));
            String user=safe(metadata.opt("user_id"));
            if(session.isEmpty() && user.startsWith("{")) try {
                JSONObject identity=new JSONObject(user);session=safe(identity.opt("session_id"));
                if(thread.isEmpty()) thread=safe(identity.opt("thread_id"));
            } catch(Exception ignored) { }
            if(session.isEmpty()) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("^user_.+_session_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$").matcher(user);
                if(m.matches()) session=m.group(1);
            }
        }
        if(session.isEmpty()) session=thread;
        if(session.isEmpty()) return "";
        return thread.isEmpty()?session:new com.gaeul.allin.json.JSONArray().put(session).put(thread).put("").toString();
    }
}
