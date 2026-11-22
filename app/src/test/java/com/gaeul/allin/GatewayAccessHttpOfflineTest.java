package com.gaeul.allin;
import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
public final class GatewayAccessHttpOfflineTest {
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 public static void main(String[] args)throws Exception {
  JSONObject p=new JSONObject().put("id","vendor").put("protocol","openai").put("models",new JSONArray().put("m")).put("keys",new JSONArray().put("fake-key"));
  JSONObject cfg=new JSONObject().put("server",new JSONObject().put("authKey","fixture")).put("setup",new JSONObject().put("done",true)).put("providers",new JSONArray().put(p));
  GatewayServer s=RoutingSetupOfflineTest.server(cfg);
  check(RoutingSetupOfflineTest.status(s,"192.168.1.2","/api/gateway-access","GET","","fixture")==401,"inference key cannot manage");
  NanoHTTPD.Response r=s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/gateway-access","GET","",null));
  check(r.getStatus().getRequestStatus()==200 && "no-store".equals(r.getHeader("Cache-Control")),"status and no-store");
  JSONObject body=new JSONObject(new String(r.getData().readAllBytes(),"UTF-8"));
  check(body.getBoolean("ok")&&body.getJSONArray("models").length()==1&&!body.toString().contains("fake-key"),"safe body");
  p.put("enabled",false);
  r=s.serve(RoutingSetupOfflineTest.request("127.0.0.1","/api/gateway-access","GET","",null));
  body=new JSONObject(new String(r.getData().readAllBytes(),"UTF-8"));
  check(body.getJSONArray("models").length()==0,"fresh configuration");
  check(RoutingSetupOfflineTest.status(s,"127.0.0.1","/api/gateway-access","POST","",null)==405,"read only");
  privacySettingsPreserveSavedRules(s, cfg);
  System.out.println("GatewayAccessHttpOfflineTest PASS");
 }

 static void privacySettingsPreserveSavedRules(GatewayServer server, JSONObject config) throws Exception {
  java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("privacy-settings-");
  java.nio.file.Path file = dir.resolve("config.json");
  java.lang.reflect.Field configFile = GatewayServer.class.getDeclaredField("configFile");
  configFile.setAccessible(true);
  configFile.set(server, file.toFile());
  try {
   java.nio.file.Files.writeString(file, config.toString());
   JSONObject valid = new JSONObject().put("customPatterns", new JSONArray().put("fixture-[0-9]+"));
   try (NanoHTTPD.Response response = privacyRequest(server, valid)) {
    check(response.getStatus().getRequestStatus() == 200, "valid privacy settings saved");
    check(responseJson(response).getBoolean("ok"), "valid settings acknowledged");
   }
   byte[] saved = java.nio.file.Files.readAllBytes(file);
   JSONObject invalid = new JSONObject().put("enabled", false).put("customPatterns", new JSONArray().put("["));
   try (NanoHTTPD.Response response = privacyRequest(server, invalid)) {
    check(response.getStatus().getRequestStatus() == 400, "invalid regex rejected before save");
    check(responseJson(response).getJSONObject("error").getString("message").contains("正则"), "regex error is actionable");
   }
   check(java.util.Arrays.equals(saved, java.nio.file.Files.readAllBytes(file)), "invalid patch preserves durable bytes");
   try (NanoHTTPD.Response response = privacyRequest(server, null)) {
    JSONObject privacy = responseJson(response).getJSONObject("privacy");
    check(privacy.getBoolean("enabled"), "invalid patch preserves active switch");
    PrivacyMasker.Config active = PrivacyMasker.Config.fromJson(privacy);
    check(!PrivacyMasker.maskText("fixture-123", new PrivacyMasker.Session(), active).contains("fixture-123"),
      "previously saved regex still masks");
   }
  } finally {
   java.nio.file.Files.deleteIfExists(file);
   java.nio.file.Files.deleteIfExists(dir);
  }
 }

 static NanoHTTPD.Response privacyRequest(GatewayServer server, JSONObject patch) {
  return server.serve(RoutingSetupOfflineTest.request("127.0.0.1", "/api/privacy", patch == null ? "GET" : "POST",
    patch == null ? "" : new JSONObject().put("privacy", patch).toString(), null));
 }

 static JSONObject responseJson(NanoHTTPD.Response response) throws Exception {
  return new JSONObject(new String(response.getData().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
 }
}
