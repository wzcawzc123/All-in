package com.gaeul.allin;

import com.gaeul.allin.json.*;
import fi.iki.elonen.NanoHTTPD;
import java.nio.file.*;
import java.util.*;

/** Measure the real returned directory and actual transport calls, not merely a routing branch. */
public final class CustomModelSelectionHttpTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject userFields(JSONObject p){JSONObject copy=new JSONObject(p.toString());copy.remove("type");copy.remove("status");copy.remove("modelAliases");copy.remove("modelMeta");return copy;}
    static JSONObject dir(GatewayServer g)throws Exception{return new JSONObject(GatewayAccessBoundaryOfflineTest.body(CustomProviderHttpTest.call(g,"/v1/models","GET",null,false)));}
    static int call(GatewayServer g,String path,JSONObject body,boolean admin){return CustomProviderHttpTest.code(CustomProviderHttpTest.call(g,path,"POST",body,admin));}
    static JSONObject preview(GatewayServer g,JSONObject p,String token)throws Exception{
        JSONObject q=new JSONObject().put("provider",userFields(p));if(token!=null)q.put("expectedStateToken",token);
        NanoHTTPD.Response r=CustomProviderHttpTest.call(g,"/api/providers/custom/catalog","POST",q,true);
        String raw=GatewayAccessBoundaryOfflineTest.body(r);check(r.getStatus().getRequestStatus()==200,"preview failed "+raw);return new JSONObject(raw);
    }
    static String token(GatewayServer g,String id)throws Exception{return CustomProviderHttpTest.view(g).getJSONObject("providerStateTokens").getString(id);}
    static JSONObject current(GatewayServer g,String id)throws Exception{for(Object raw:ResponsesGatewayOfflineTest.config(g).getJSONArray("providers")){JSONObject p=(JSONObject)raw;if(id.equals(p.optString("id")))return p;}throw new AssertionError("missing provider");}
    static void save(GatewayServer g,JSONObject p,String token){JSONObject q=new JSONObject().put("action","save").put("provider",userFields(p));if(token!=null)q.put("expectedStateToken",token);check(call(g,"/api/providers/custom",q,true)==200,"save failed");}
    public static void main(String[] args)throws Exception{
        JSONObject scoped=CustomProviderHttpTest.provider("openai","none","https://example.invalid/v1").put("models",new JSONArray().put("zcode/special").put("group/special").put("org/model")).put("catalogModels",new JSONArray().put("zcode/unselected"));
        JSONObject scopedCfg=CustomProviderHttpTest.config(scoped);scopedCfg.getJSONArray("providers").put(new JSONObject().put("id","zcode").put("models",new JSONArray()));
        CustomModelNames scopedNames=new CustomModelNames(scopedCfg);
        check(!scopedNames.publicId(scoped,"zcode/special").startsWith("zcode/"),"alias resembles vendor auth route");
        check(!scopedNames.publicId(scoped,"group/special").startsWith("group/"),"alias resembles routing group");
        check(scopedNames.canonical(scopedNames.publicId(scoped,"zcode/special")).equals("custom-test/zcode/special"),"namespaced upstream id not preserved");
        check(scopedNames.publicId(scoped,"org/model").equals("org/model"),"ordinary nested model id changed");
        check(scopedNames.canonical("zcode/unselected").equals("zcode/unselected"),"candidate catalog hijacked an explicit vendor address");
        Path temp=Files.createTempDirectory("custom-model-selection-");
        try(CustomProviderHttpTest.Peer peer=new CustomProviderHttpTest.Peer("openai");CustomProviderHttpTest.Peer second=new CustomProviderHttpTest.Peer("openai")){
            JSONObject builtin=new JSONObject().put("id","openai").put("protocol","openai").put("baseUrl",peer.base()).put("enabled",true).put("keys",new JSONArray().put("builtin-fixture-key")).put("models",new JSONArray().put("builtin-only"));
            JSONObject cfg=CustomProviderHttpTest.config(builtin).put("routing",new JSONObject().put("bareModelFallback",true).put("groupsEnabled",true));
            GatewayServer g=RoutingSetupOfflineTest.server(cfg);RoutingAuditOfflineTest.file(g,temp.resolve("config.json").toFile());Files.write(temp.resolve("config.json"),cfg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            g.setVendorConnectionsForTests(url->(java.net.HttpURLConnection)new java.net.URL(url).openConnection());
            String id="custom-7263352e-123b-4a86-a2da-6550b2dc";
            String a="GPT-6.2-Sol",b="GPT-6.2-Astra",c="GPT-6.1-Terra";
            peer.catalog=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("id",a)).put(new JSONObject().put("id",b)).put(new JSONObject().put("id",c))).toString();
            JSONObject draft=CustomProviderHttpTest.provider("openai","auto",peer.base()).put("id",id).put("name","测试").put("models",new JSONArray()).put("catalogModels",new JSONArray());
            byte[] disk=Files.readAllBytes(temp.resolve("config.json"));String memory=ResponsesGatewayOfflineTest.config(g).toString();
            check(call(g,"/api/providers/custom/catalog",new JSONObject().put("provider",userFields(draft)),false)==401,"inference token queried custom catalog");
            JSONObject fetched=preview(g,draft,null);check(!fetched.getBoolean("saved")&&fetched.getJSONArray("models").length()==3,"preview response not read-only candidates");
            check(Arrays.equals(disk,Files.readAllBytes(temp.resolve("config.json")))&&memory.equals(ResponsesGatewayOfflineTest.config(g).toString()),"unsaved preview mutated config");
            check(dir(g).getJSONArray("data").length()==1,"unselected preview entered pool");check(peer.calls.get()==1,"preview sent inference or retried");
            draft.put("catalogModels",fetched.getJSONArray("models")).put("models",new JSONArray().put(a));save(g,draft,null);
            JSONObject models=dir(g);JSONArray data=models.getJSONArray("data");check(data.length()==2,"directory includes unchecked models");JSONObject named=data.getJSONObject(1);
            check(named.getString("id").equals(a)&&named.getString("display_name").equals(a),"returned ID still obscures model name");check(!named.toString().contains("custom-7263352e"),"public row exposed random identity");
            NanoHTTPD.Response accessible=CustomProviderHttpTest.call(g,"/api/gateway-access","GET",null,true);JSONObject access=new JSONObject(GatewayAccessBoundaryOfflineTest.body(accessible));
            check(access.getJSONArray("models").toString().contains(a)&&!access.getJSONArray("models").toString().contains(b),"gateway-access ignored selection");
            int before=peer.calls.get();
            for(String rejected:new String[]{b,id+"/"+b,c,id+"/"+c})for(String proto:new String[]{"openai","responses","anthropic"}){
                JSONObject q=CustomProviderHttpTest.ingress(proto,false,false).put("model",rejected);int status=call(g,CustomProviderHttpTest.path(proto),q,false);check(status>=400&&status<500,"unchecked model accepted "+rejected+" "+proto);
            }
            check(peer.calls.get()==before,"unchecked model sent a transport call via bare fallback");
            for(String accepted:new String[]{a,id+"/"+a}){
                NanoHTTPD.Response r=CustomProviderHttpTest.call(g,"/v1/chat/completions","POST",CustomProviderHttpTest.ingress("openai",false,true).put("model",accepted),false);
                String body=GatewayAccessBoundaryOfflineTest.body(r);check(r.getStatus().getRequestStatus()==200&&body.contains("PASS9193"),"readable or old ID failed");check(peer.received.getString("model").equals(a),"upstream received alias instead of true model");
            }
            check(peer.calls.get()==before+2,"accepted model sent multiple calls");
            JSONObject view=CustomProviderHttpTest.view(g),held=view.getJSONArray("providers").getJSONObject(1);String state=token(g,id);disk=Files.readAllBytes(temp.resolve("config.json"));
            fetched=preview(g,held,state);check(Arrays.equals(disk,Files.readAllBytes(temp.resolve("config.json"))),"existing preview saved or replaced private references");check(!fetched.toString().contains("fixture-custom-key")&&!fetched.toString().contains("fixture-header-secret"),"preview leaked credentials");
            peer.catalog="{\"data\":[{\"id\":\"new-unselected\"}]}";
            int status=CustomProviderHttpTest.code(CustomProviderHttpTest.call(g,"/api/auth/"+id+"/sync-models","GET",null,true));check(status==200,"candidate sync failed");check(current(g,id).getJSONArray("models").toString().equals(new JSONArray().put(a).toString()),"legacy sync added unselected model");
            check(dir(g).getJSONArray("data").length()==2,"synced candidate entered pool");
            peer.catalog="{\"data\":[{\"id\":12}]}";disk=Files.readAllBytes(temp.resolve("config.json"));
            NanoHTTPD.Response bad=CustomProviderHttpTest.call(g,"/api/providers/custom/catalog","POST",new JSONObject().put("provider",userFields(CustomProviderHttpTest.view(g).getJSONArray("providers").getJSONObject(1))).put("expectedStateToken",token(g,id)),true);
            check(CustomProviderHttpTest.code(bad)==502&&Arrays.equals(disk,Files.readAllBytes(temp.resolve("config.json"))),"invalid preview replaced state");
            JSONObject two=CustomProviderHttpTest.provider("openai","auto",second.base()).put("id","custom-second").put("name","第二中转").put("models",new JSONArray().put(a));save(g,two,null);
            CustomModelNames names=new CustomModelNames(ResponsesGatewayOfflineTest.config(g));String secondAlias=names.publicId(current(g,"custom-second"),a);
            check(secondAlias.startsWith(a+"@")&&secondAlias.length()<a.length()+25&&!secondAlias.contains("custom-"),"duplicate model alias is not model-first/short");check(names.publicId(current(g,id),a).equals(a),"duplicate stole first model's stable ID");
            NanoHTTPD.Response r=CustomProviderHttpTest.call(g,"/v1/chat/completions","POST",CustomProviderHttpTest.ingress("openai",false,false).put("model",secondAlias),false);String body=GatewayAccessBoundaryOfflineTest.body(r);check(r.getStatus().getRequestStatus()==200&&second.received.getString("model").equals(a),"duplicate alias went to wrong provider");
            two=userFields(CustomProviderHttpTest.view(g).getJSONArray("providers").getJSONObject(2));two.put("name","换个名称");save(g,two,token(g,"custom-second"));
            check(new CustomModelNames(ResponsesGatewayOfflineTest.config(g)).publicId(current(g,"custom-second"),a).equals(secondAlias),"renaming provider changed stable model ID");
            JSONObject only=userFields(CustomProviderHttpTest.view(g).getJSONArray("providers").getJSONObject(1));only.put("models",new JSONArray());save(g,only,token(g,id));
            check(dir(g).getJSONArray("data").length()==2,"deselect-all still exposed first provider models");before=peer.calls.get();check(call(g,"/v1/chat/completions",CustomProviderHttpTest.ingress("openai",false,false).put("model",a),false)>=400,"retired bare ID rerouted to another provider");check(peer.calls.get()==before,"retired bare ID hit transport");
            JSONObject full=ResponsesGatewayOfflineTest.config(g);full.getJSONArray("groups").put(new JSONObject().put("id","unchecked").put("targets",new JSONArray().put(id+"/"+a)));
            JSONObject groups=GatewayAccessCatalog.build(full,System.currentTimeMillis(),null);check(!groups.toString().contains("group/unchecked"),"empty custom pool left group callable");
            check(current(g,"openai").similar(builtin),"built-in provider changed");
            System.out.println("CustomModelSelectionHttpTest PASS "+checks+" assertions; actual directory names, preview read-only, checked-only pool, no rejected transport calls, alias/legacy routing and stable duplicates");
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});}}
    }
}
