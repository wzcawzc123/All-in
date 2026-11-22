package com.gaeul.allin;

import com.gaeul.allin.json.*;

/** Contracts for the Codex semantic reader rather than the incidental client field set. */
public final class CodexChatProjectionOfflineTest {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }
    static JSONObject chat() { return CodexClientCompatibilityOfflineTest.chat(false); }
    static JSONObject convert(JSONObject b) throws Exception { return CodexChatProjection.convert(b, "m").body; }
    static void acceptedStoreAndExtensions() throws Exception {
        JSONObject expected = convert(chat());
        for (Object store : new Object[]{false, JSONObject.NULL}) {
            JSONObject b = chat().put("store", store); String before = b.toString();
            check(convert(b).toString().equals(expected.toString()), "store false/null not a semantic change");
            check(before.equals(b.toString()), "store input detached");
        }
        JSONObject b = chat().put("sdk_client_tag", "title-generation").put("seed", 12).put("prompt_cache_retention", "24h");
        b.getJSONArray("messages").getJSONObject(1).put("name", "user-name").put("providerOptions", new JSONObject().put("opaque", true));
        b.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).put("providerMetadata", new JSONObject().put("scope", "client"));
        b.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("providerOptions", new JSONObject().put("opaque", true));
        CodexChatProjection.Result r = CodexChatProjection.convert(b, "m");
        check(r.omittedExtensions && r.body.toString().equals(expected.toString()), "SDK incidental metadata projected, semantics exact");
        check(r.body.toString().contains("cache_control"), "business schema/argument field not stripped");
        for (Object stop : new Object[]{JSONObject.NULL, "", new JSONArray()})
            check(!CodexChatProjection.convert(chat().put("stop", stop), "m").stopAdvisory, "absent/empty stop does not reject");
        check(CodexChatProjection.convert(chat().put("stop", new JSONArray().put("END")), "m").stopAdvisory, "unsupported stop gets explicit advisory, not a guarantee");
        JSONObject nullable = chat().put("max_tokens", JSONObject.NULL).put("max_completion_tokens", JSONObject.NULL)
                .put("response_format", JSONObject.NULL).put("tool_choice", JSONObject.NULL);
        check(convert(nullable).toString().equals(expected.toString()), "nullable optional SDK fields mean omitted, not invalid limits");
        check(convert(chat().put("previous_response_id", "").put("context_management", new JSONArray()).put("background", false)).toString().equals(expected.toString()), "inactive SDK state defaults are omitted, active controls still rejected");
        CodexChatProjection.Result adaptive = CodexChatProjection.convert(chat().put("thinking", new JSONObject().put("type", "adaptive")), "m");
        check(adaptive.omittedExtensions && adaptive.body.toString().equals(expected.toString()), "adaptive hint follows target default without spurious rejection");
        JSONObject thought = chat(); thought.getJSONArray("messages").getJSONObject(2).put("reasoning_content", "display-only prior thought");
        CodexChatProjection.Result thinking = CodexChatProjection.convert(thought, "m");
        check(thinking.omittedExtensions && thinking.body.toString().equals(expected.toString()), "unsealed Chat reasoning omitted like Magpie, without changing conversation/tool history");
        JSONObject bookkeeping = chat().put("metadata", new JSONObject().put("trace", new JSONObject().put("client", "sdk")).put("cache_control", "bookkeeping-value"));
        CodexChatProjection.Result bookkeepingResult = CodexChatProjection.convert(bookkeeping, "m");
        check(bookkeepingResult.omittedExtensions && bookkeepingResult.body.toString().equals(expected.toString()), "client bookkeeping metadata is projected out like Magpie, business payloads remain separate");
    }
    static void effortVariants() throws Exception {
        check(convert(chat().put("reasoning", new JSONObject().put("effort", "high"))).getJSONObject("reasoning").getString("effort").equals("high"), "reasoning.effort");
        check(convert(chat().put("output_config", new JSONObject().put("effort", "medium"))).getJSONObject("reasoning").getString("effort").equals("medium"), "output_config.effort");
        int[] budgets = {1,4096,4097,12000,12001,24000,24001};
        String[] levels = {"low","low","medium","medium","high","high","xhigh"};
        for (int i=0;i<budgets.length;i++) check(convert(chat().put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",budgets[i])))
                .getJSONObject("reasoning").getString("effort").equals(levels[i]), "Magpie effortOfBudget boundary " + budgets[i]);
        check(convert(chat().put("reasoning_effort","low").put("reasoning",new JSONObject().put("effort","high")))
                .getJSONObject("reasoning").getString("effort").equals("low"), "explicit effort wins, as Magpie chatEffort");
    }
    static void toolResultPartsAndPhase() throws Exception {
        JSONObject b=chat(); JSONObject tool=b.getJSONArray("messages").getJSONObject(3);
        tool.put("content", new JSONArray().put(new JSONObject().put("type","text").put("text",CodexClientCompatibilityOfflineTest.OUTPUT))
                .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","https://example.invalid/result.png").put("detail","low"))));
        JSONObject assistant=b.getJSONArray("messages").getJSONObject(2);assistant.put("content","Tool commentary").put("phase","commentary");
        JSONObject out=convert(b);JSONArray input=out.getJSONArray("input");
        check(input.getJSONObject(1).getString("phase").equals("commentary"), "assistant phase attached to message, not function call");
        check(input.getJSONObject(2).getString("arguments").equals(CodexClientCompatibilityOfflineTest.ARGS), "arguments exact after phase");
        JSONArray output=input.getJSONObject(3).getJSONArray("output");
        check(output.getJSONObject(0).getString("text").equals(CodexClientCompatibilityOfflineTest.OUTPUT), "tool business text exact");
        check(output.getJSONObject(1).getString("image_url").equals("https://example.invalid/result.png"), "tool result image retained as native output part");
        JSONObject flat=new JSONObject().put("name","lookup").put("input_schema",CodexClientCompatibilityOfflineTest.schema()).put("strict",true);
        out=convert(chat().put("tools",new JSONArray().put(flat)));
        check(out.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters").toString().equals(CodexClientCompatibilityOfflineTest.schema().toString()), "alternate tool shape schema retained");
        check(out.getJSONArray("tools").getJSONObject(0).getBoolean("strict"), "alternate explicit strict retained");
        JSONObject plain = chat(); plain.getJSONArray("messages").getJSONObject(3).put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", ""))
                .put(new JSONObject().put("type", "text").put("text", CodexClientCompatibilityOfflineTest.OUTPUT))
                .put(new JSONObject().put("type", "text").put("text", "")));
        check(convert(plain).getJSONArray("input").getJSONObject(2).getString("output").equals("\n" + CodexClientCompatibilityOfflineTest.OUTPUT + "\n"), "plain result arrays join with exact blank boundaries, as Magpie text output");
    }
    static void nativeProjection() throws Exception {
        JSONObject message=new JSONObject().put("type","message").put("role","assistant").put("id","old-message").put("status","completed")
                .put("phase","final_answer").put("internal_chat_message_metadata_passthrough",new JSONObject().put("turn_id","turn-original"))
                .put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","Previous reply")));
        message.getJSONArray("content").getJSONObject(0).put("annotations", new JSONArray().put(new JSONObject().put("type", "url_citation").put("url", "https://example.invalid")))
                .put("logprobs", new JSONArray());
        JSONObject call=new JSONObject().put("type","function_call").put("call_id","call-original").put("name","lookup")
                .put("arguments",CodexClientCompatibilityOfflineTest.ARGS).put("encrypted_function_args",new JSONArray());
        JSONObject req=new JSONObject().put("model","m").put("input",new JSONArray().put(message).put(call)
                .put(new JSONObject().put("type","function_call_output").put("call_id","call-original").put("output",CodexClientCompatibilityOfflineTest.OUTPUT))
                .put(new JSONObject().put("type","configuration_update").put("reasoning",new JSONObject().put("effort","low"))));
        String before=req.toString();
        JSONObject projected=ResponsesBridge.projection(CodexClientCompatibility.responsesForProjection(req));
        check(projected.has("messages"), "native phase/metadata no longer blocks preliminary projection");
        JSONObject nativeBody=CodexRequestPolicy.prepareFinalOutbound(req,CodexRequestPolicy.OutputConstraints.none()).body;
        check(nativeBody.getJSONArray("input").getJSONObject(0).getString("phase").equals("final_answer"), "phase retained on final native request");
        check(nativeBody.getJSONArray("input").getJSONObject(0).has("internal_chat_message_metadata_passthrough"), "native metadata retained");
        JSONObject originalPart = nativeBody.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0);
        check(originalPart.getJSONArray("logprobs").length() == 0 && originalPart.getJSONArray("annotations").length() == 1, "native content metadata retained, not globally stripped to appease projection");
        check(nativeBody.getJSONArray("input").getJSONObject(1).has("encrypted_function_args"), "native call metadata retained");
        check(nativeBody.getJSONArray("input").getJSONObject(3).getString("type").equals("configuration_update"), "native configuration item retained");
        check(before.equals(req.toString()), "shadow never mutates original/native history");
        try {ResponsesBridge.projection(req);throw new AssertionError("non-Codex shared projection globally relaxed");}
        catch(IllegalArgumentException expected){check(expected.getMessage().contains("phase"),"shared strict phase behavior unchanged");}
    }
    static void invalidControls() throws Exception {
        JSONObject[] bad={chat().put("store",true),chat().put("store","false"),chat().put("n",2),chat().put("previous_response_id","r"),
                chat().put("conversation",new JSONObject().put("id","c")),chat().put("background",true),chat().put("reasoning",1),
                chat().put("stop",new JSONArray().put(1)),chat().put("response_format",true),chat().put("tool_choice",true),
                chat().put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",1.5))};
        for(JSONObject b:bad)try {convert(b);throw new AssertionError("invalid semantic control accepted");}
        catch(IllegalArgumentException expected){check(!expected.getMessage().isEmpty(),"explicit control/type error");}
        for(Object phase:new Object[]{1,"analysis","arbitrary"}) {
            JSONObject b=chat();b.getJSONArray("messages").getJSONObject(2).put("content","x").put("phase",phase);
            try{convert(b);throw new AssertionError("invalid phase");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("phase"),"phase validated");}
        }
    }
    public static void main(String[] args)throws Exception {
        acceptedStoreAndExtensions();effortVariants();toolResultPartsAndPhase();nativeProjection();invalidControls();
        System.out.println("CodexChatProjectionOfflineTest passed: "+checks+" checks");
    }
}
