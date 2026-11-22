package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.nio.charset.StandardCharsets;

public final class ConversationHistoryOfflineTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static JSONObject message(String role,String content){return new JSONObject().put("role",role).put("content",content);}
    public static void main(String[] args) throws Exception {
        ConversationHistory history=new ConversationHistory();JSONArray first=new JSONArray().put(message("system","old dynamic instructions")).put(message("user","identical first prompt"));
        ConversationHistory.Context a=history.start(first,"scope");check(a!=null,"first request gets an independent identity");
        JSONObject reply=message("assistant","reply A");history.delivered(a,reply);
        JSONArray follow=new JSONArray().put(message("system","new dynamic instructions")).put(message("user","identical first prompt")).put(reply).put(message("user","continue"));
        check(history.start(follow,"scope").identity.equals(a.identity),"complete delivered history continues despite dynamic system instructions");
        check(!history.start(follow,"other-caller").identity.equals(a.identity),"caller/model/protocol scopes isolated");
        ConversationHistory.Context b=history.start(first,"scope");check(!a.identity.equals(b.identity),"same first prompt alone does not merge new dialogs");history.delivered(b,reply);
        check(history.start(follow,"scope")==null,"independent identical completed histories become ambiguous and bypass");
        history.clear();check(!history.start(follow,"scope").identity.equals(a.identity),"reset cannot resurrect prior lineage");
        JSONArray image=new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","https://fixture.invalid/image")))));
        check(history.start(image,"scope")==null,"unsupported media history is not guessed");
        ConversationHistory.Capture capture=new ConversationHistory.Capture();String stream="data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);capture.write(bytes,0,bytes.length);check(capture.assistant().optString("content").equals("hello world"),"capture reconstructs exact delivered Chat deltas");
        ConversationHistory.Capture toolCapture=new ConversationHistory.Capture();
        String toolStream="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-real-shape\",\"function\":{\"name\":\"terminal\",\"arguments\":\"\"}}]}}]}\n\n"
                +"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"function\":{\"name\":\"\",\"arguments\":\"{\\\"command\\\":\\\"printf 9193\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
        bytes=toolStream.getBytes(StandardCharsets.UTF_8);toolCapture.write(bytes,0,bytes.length);
        JSONObject call=toolCapture.assistant().getJSONArray("tool_calls").getJSONObject(0);
        check(call.getString("id").equals("call-real-shape"),"empty later tool id cannot erase received identity");
        check(call.getJSONObject("function").getString("name").equals("terminal"),"actual WorkBuddy empty name patch cannot erase received tool name");
        check(call.getJSONObject("function").getString("arguments").equals("{\"command\":\"printf 9193\"}"),"real parameter delta remains exactly intact");
        ConversationHistory race=new ConversationHistory();ConversationHistory.Context owner=race.start(first,"race");
        race.reserve(owner,toolCapture.assistant());JSONArray early=new JSONArray(first.toString()).put(toolCapture.assistant()).put(message("tool","9193").put("tool_call_id","call-real-shape"));
        ConversationHistory.Context waiting=race.start(early,"race");check(waiting.identity.equals(owner.identity)&&waiting.matchedPending,"tool return arriving before final write discovers its pending owner");
        race.delivered(owner,toolCapture.assistant());race.abandon(owner);check(race.start(early,"race").identity.equals(owner.identity),"successful final delivery keeps provisional linkage");
        race.clear();owner=race.start(first,"race");race.reserve(owner,toolCapture.assistant());race.abandon(owner);check(!race.start(early,"race").identity.equals(owner.identity),"failed provisional linkage cannot resurrect a prior conversation");
        ConversationHistory.Capture error=new ConversationHistory.Capture();bytes="event: error\ndata: {\"error\":{\"message\":\"partial failure\"}}\n\n".getBytes(StandardCharsets.UTF_8);error.write(bytes,0,bytes.length);check(error.assistant()==null,"error stream cannot establish history lineage");
        ConversationHistory.Capture large=new ConversationHistory.Capture();large.write(new byte[ConversationHistory.MAX_BYTES+1],0,ConversationHistory.MAX_BYTES+1);check(large.assistant()==null,"capture overflow bypasses without altering output");
        java.lang.reflect.Field entries=ConversationHistory.class.getDeclaredField("entries");entries.setAccessible(true);
        history=new ConversationHistory();a=history.start(first,"scope");history.delivered(a,reply);check(!entries.get(history).toString().contains("identical first prompt")&&!entries.get(history).toString().contains("reply A"),"index retains no prompt/reply plaintext");
        System.out.println("ConversationHistoryOfflineTest PASS: "+checks+" checks");
    }
}
