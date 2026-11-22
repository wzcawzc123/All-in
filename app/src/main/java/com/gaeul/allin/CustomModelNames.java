package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Public model ids are readable labels; persisted provider ids remain private routing identities. */
final class CustomModelNames {
    private final Map<String,JSONObject> byProvider=new LinkedHashMap<>();
    private final Map<String,String> inbound=new LinkedHashMap<>();
    private final Set<String> used=new HashSet<>();
    private final Set<String> exactReserved=new HashSet<>();
    private final Map<String,String> unselected=new LinkedHashMap<>();
    private final Set<String> builtinBare=new HashSet<>();
    private final Set<String> namespaces=new HashSet<>(Collections.singleton("group"));

    CustomModelNames(JSONObject cfg) {
        JSONArray providers=cfg==null?null:cfg.optJSONArray("providers");
        JSONArray groups=cfg==null?null:cfg.optJSONArray("groups");
        for(int i=0;groups!=null&&i<groups.length();i++) {JSONObject g=groups.optJSONObject(i);if(g!=null)exactReserved.add("group/"+g.optString("id"));}
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(p==null)continue;String id=p.optString("id");namespaces.add(id);
            JSONArray models=p.optJSONArray("models");
            for(int j=0;models!=null&&j<models.length();j++) {
                String m=models.optString(j);exactReserved.add(id+"/"+m);
                if(!CustomProvider.isCustom(p)) {used.add(m);builtinBare.add(m);}
            }
        }
        used.addAll(exactReserved);
        // Existing aliases own their label even when unchecked, preserving history and preventing
        // a later provider from silently taking an old client's previously configured model id.
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(!CustomProvider.isCustom(p))continue;
            JSONObject aliases=p.optJSONObject("modelAliases");
            JSONObject own=aliases==null?new JSONObject():new JSONObject(aliases.toString());
            byProvider.put(p.optString("id"),own);
            for(String model:own.keySet()) {
                Object raw=own.opt(model);
                if(!(raw instanceof String)||!validAlias((String)raw)||exactReserved.contains(raw)) throw new IllegalArgumentException("自定义模型别名无效或占用了既有调用地址");
                String alias=(String)raw,ref=p.optString("id")+"/"+model;
                if(inbound.containsKey(alias)&&!ref.equals(inbound.get(alias)))throw new IllegalArgumentException("自定义模型别名重复");
                inbound.put(alias,ref);used.add(alias);
            }
        }
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(!CustomProvider.isCustom(p))continue;
            JSONObject own=byProvider.get(p.optString("id"));JSONArray models=p.optJSONArray("models");
            for(int j=0;models!=null&&j<models.length();j++) {
                String model=models.optString(j);if(own.has(model))continue;
                int slash=model.indexOf('/');boolean reservedNamespace=slash>0&&namespaces.contains(model.substring(0,slash));
                // Do not resemble group/vendor routes: their ingress may run special auth before
                // generic alias resolution. The upstream model itself still stays byte-for-byte.
                String readable=reservedNamespace?model.replace('/','~'):model;
                String alias=readable;
                if(used.contains(alias)||reservedNamespace) {
                    String stem=readable+"@"+shortName(p);alias=stem;
                    if(used.contains(alias)) alias=stem+"-"+shortHash(p.optString("id"));
                    for(int k=2;used.contains(alias);k++)alias=stem+"-"+shortHash(p.optString("id"))+"-"+k;
                }
                own.put(model,alias);used.add(alias);inbound.put(alias,p.optString("id")+"/"+model);
            }
        }
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(!CustomProvider.isCustom(p))continue;JSONArray catalog=p.optJSONArray("catalogModels");
            for(int j=0;catalog!=null&&j<catalog.length();j++) {
                String m=catalog.optString(j);int slash=m.indexOf('/');
                if(slash>0&&namespaces.contains(m.substring(0,slash)))continue; // explicit built-in/legacy routes retain their identity
                if(!inbound.containsKey(m)&&!builtinBare.contains(m))unselected.putIfAbsent(m,p.optString("id")+"/"+m);
            }
        }
    }
    private static boolean validAlias(String alias) {
        if(alias.isEmpty()||alias.length()>320)return false;
        for(char c:alias.toCharArray())if(c<=32||c==127)return false;
        return true;
    }
    private static String shortName(JSONObject p) {
        String s=p.optString("name").replaceAll("[^\\p{L}\\p{N}_-]","-").replaceAll("-+","-");
        while(s.startsWith("-"))s=s.substring(1);while(s.endsWith("-"))s=s.substring(0,s.length()-1);
        if(s.isEmpty())s="relay";
        if(s.length()>12)s=s.substring(0,12);
        return s;
    }
    private static String shortHash(String value) {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(int i=0;i<4;i++)s.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    String publicId(JSONObject p,String model) {
        JSONObject own=byProvider.get(p.optString("id"));return own==null?p.optString("id")+"/"+model:own.optString(model,p.optString("id")+"/"+model);
    }
    String canonical(String value) {return inbound.containsKey(value)?inbound.get(value):unselected.getOrDefault(value,value);}
    void persist(JSONObject cfg) {
        JSONArray providers=cfg.optJSONArray("providers");
        for(int i=0;providers!=null&&i<providers.length();i++) {
            JSONObject p=providers.optJSONObject(i);if(!CustomProvider.isCustom(p))continue;
            JSONObject own=byProvider.get(p.optString("id"));if(own!=null)p.put("modelAliases",new JSONObject(own.toString()));
        }
    }
}
