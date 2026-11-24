package com.gaeul.allin;
import com.gaeul.allin.json.*;

/** WorkBuddy stream-to-nonstream status, synthetic upstream and no vendor calls. */
public final class WorkBuddyCompletionGatewayHttpTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static String chunk(JSONObject delta,String reason){return "data: "+new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",reason==null?JSONObject.NULL:reason)))+"\n\n";}
 static void run(String scenario)throws Exception {
  String input=chunk(new JSONObject().put("reasoning_content","only thought"),null);
  if(scenario.equals("length"))input+=chunk(new JSONObject(),"length");
  else if(scenario.equals("unknown"))input+=chunk(new JSONObject(),"new_reason");
  else if(scenario.equals("error"))input+="data: {\"error\":{\"message\":\"synthetic\"}}\n\n";
  else if(scenario.equals("tool-length"))input+=chunk(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",0).put("id","t").put("function",new JSONObject().put("name","f").put("arguments","{}")))),"length");
  input+="data: [DONE]\n\n";final String source=input;
  try(VendorPolicyGatewayHttpTest.Upstream upstream=new VendorPolicyGatewayHttpTest.Upstream((req,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",source));
      VendorPolicyGatewayHttpTest.Gateway g=new VendorPolicyGatewayHttpTest.Gateway(WorkBuddyCompatibilityGatewayHttpTest.config(),upstream,false)){
   DeepSeekHarnessGatewayHttpTest.Result result=DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),"/v1/chat/completions",WorkBuddyCompatibilityGatewayHttpTest.request("/v1/chat/completions",false));
   check(upstream.requests.size()==1,"no replay "+scenario);
   if(scenario.equals("length")||scenario.equals("tool-length")){
    check(result.status==200,"status "+scenario+" "+result.body);JSONObject c=new JSONObject(result.body).getJSONArray("choices").getJSONObject(0);
    check("length".equals(c.getString("finish_reason")),"length preserved "+scenario);
    JSONObject m=c.getJSONObject("message");check("".equals(m.getString("content"))&&"only thought".equals(m.getString("reasoning_content")),"no invented visible text");
    if(scenario.equals("tool-length"))check(m.getJSONArray("tool_calls").length()==1,"tool kept");
   }else check(result.status==502,"invalid completion should fail "+scenario+" "+result.status+" "+result.body);
  }
 }
 static JSONObject call(String id, String name, String args) {
  JSONObject fn = new JSONObject();
  if (name != null) fn.put("name", name);
  if (args != null) fn.put("arguments", args);
  JSONObject call = new JSONObject().put("index", 0).put("function", fn);
  if (id != null) call.put("id", id);
  return new JSONObject().put("tool_calls", new JSONArray().put(call));
 }
 static void terminalTools(boolean stream, String scenario) throws Exception {
  boolean valid = "split".equals(scenario);
  String source = (valid
      ? chunk(call("call_fixture", "get_", null), null)
          + chunk(call("", "status", null), null) + chunk(call(null, "", "{}"), null)
      : "missing".equals(scenario) ? chunk(call("call_fixture", null, "{}"), null) : "")
      + chunk(new JSONObject().put("tool_calls", new JSONArray()), "tool_calls") + "data: [DONE]\n\n";
  try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((req,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",source));
       VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(WorkBuddyCompatibilityGatewayHttpTest.config(),upstream,false)) {
   String path = "/v1/chat/completions";
   DeepSeekHarnessGatewayHttpTest.Result result = DeepSeekHarnessGatewayHttpTest.post(g.getListeningPort(),path,WorkBuddyCompatibilityGatewayHttpTest.request(path,stream));
   check(upstream.requests.size()==1,"no replay " + scenario);
   if (!valid) {
    check(result.status==(stream?200:502) && result.body.contains(ChatToolCompletion.ERROR) && !result.body.contains("[DONE]"), "explicit incomplete tool result " + result.status + " " + result.body);
   } else if (stream) {
    check(result.status==200 && result.body.contains("get_") && result.body.contains("status") && result.body.contains("[DONE]"), "valid continuation reaches terminal " + result.body);
   } else {
    JSONObject tool = new JSONObject(result.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0);
    check(result.status==200 && "call_fixture".equals(tool.optString("id")) && "get_status".equals(tool.getJSONObject("function").optString("name")), "aggregate keeps id and name fragments");
   }
  }
 }
 static void waitForCsv(String expected) throws Exception {
  for (int i=0;i<100;i++) {
   if (UsageLedger.csv(1).contains(expected)) return;
   Thread.sleep(25);
  }
  throw new AssertionError("Missing " + expected + " in " + UsageLedger.csv(1));
 }
 static void failureEvidence() throws Exception {
  String path="/v1/chat/completions";
  try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream(
          (req,n)->new VendorPolicyGatewayHttpTest.Reply(200,"text/event-stream",chunk(new JSONObject().put("content","partial"),null)));
       VendorPolicyGatewayHttpTest.Gateway g = new VendorPolicyGatewayHttpTest.Gateway(WorkBuddyCompatibilityGatewayHttpTest.config(),upstream,false)) {
   UsageLedger.init(g.dir.toFile());
   try {
   VendorPolicyGatewayHttpTest.Result result=VendorPolicyGatewayHttpTest.post(g,path,WorkBuddyCompatibilityGatewayHttpTest.request(path,true));
   check(result.status==200 && !result.body.contains("[DONE]"),"upstream EOF must not manufacture completion");
   check(upstream.requests.size()==1,"upstream EOF must not replay");
   waitForCsv(",read_response_body,eof");
   g.gateway.setVendorConnectionsForTests(url -> new java.net.HttpURLConnection(new java.net.URL(url)) {
    public void connect() {}
    public void disconnect() {}
    public boolean usingProxy(){return false;}
    public java.io.OutputStream getOutputStream(){return new java.io.ByteArrayOutputStream();}
    public int getResponseCode() throws java.io.IOException {throw new java.net.SocketTimeoutException();}
   });
   result=VendorPolicyGatewayHttpTest.post(g,path,WorkBuddyCompatibilityGatewayHttpTest.request(path,true));
   check(result.status==502 && upstream.requests.size()==1,"timeout returns 502 without another real send");
   waitForCsv(",await_response_headers,timeout");
   check(UsageLedger.recentRequests(20).toString().contains("await_response_headers"),"recent requests show diagnostic");
   UsageLedger.init(g.dir.toFile());
   check(UsageLedger.csv(1).contains(",await_response_headers,timeout"),"diagnostics survive ledger reopen");
   check(WorkBuddyFailure.parse(new JSONObject().put("phase","await_response_headers").put("cause","untrusted text"))==null,"unknown cause rejected");
   } finally {
    java.lang.reflect.Method close=UsageLedger.class.getDeclaredMethod("closeWriter");
    close.setAccessible(true);
    close.invoke(null);
   }
  }
 }
 public static void main(String[] args) throws Exception {
  for(String s : new String[]{"length","tool-length","missing","unknown","error"}) run(s);
  for(boolean stream : new boolean[]{false,true}) for(String s : new String[]{"split","missing","empty"}) terminalTools(stream,s);
  failureEvidence();
  System.out.println("WorkBuddyCompletionGatewayHttpTest PASS");
 }
}
