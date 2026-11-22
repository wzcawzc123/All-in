package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Issue #5: schema limits must remain numbers and survive parse/copy/serialization. */
public final class JsonNumericBoundaryOfflineTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static JSONObject schema(){return new JSONObject("{\"type\":\"integer\",\"minimum\":-9007199254740991,\"maximum\":9007199254740991}");}
    public static void main(String[] args) {
        for(String literal:new String[]{"0","-1","2147483647","2147483648","-2147483649","9007199254740991","-9007199254740991","9223372036854775807","-9223372036854775808","9223372036854775808","-9223372036854775809","12345678901234567890123456789012345678901234567890"}) {
            JSONObject obj=new JSONObject("{\"n\":"+literal+"}"); Object n=obj.get("n");
            check(n instanceof Number,"numeric type for "+literal);
            check(new BigInteger(n.toString()).equals(new BigInteger(literal)),"integer fidelity for "+literal);
            check(new JSONObject(obj.toString()).get("n").toString().equals(literal),"serialized number is not quoted or rounded "+literal);
        }
        Object decimal=new JSONObject("{\"n\":12345678901234567890.12345678901234567890}").get("n");
        check(decimal instanceof BigDecimal,"decimal uses arbitrary precision");
        check(((BigDecimal)decimal).compareTo(new BigDecimal("12345678901234567890.12345678901234567890"))==0,"decimal precision retained");
        JSONObject bounds=schema();
        check(bounds.getLong("minimum")==-9007199254740991L && bounds.getLong("maximum")==9007199254740991L,"reported plus/minus safe-integer bounds");
        JSONArray tools=new JSONArray();
        for(int i=0;i<167;i++){
            JSONObject property=i<4?schema():new JSONObject().put("type","integer").put("minimum",0).put("maximum",100);
            JSONObject parameters=new JSONObject().put("type","object").put("properties",new JSONObject().put("start",property));
            tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","fixture_"+i).put("parameters",parameters)));
        }
        JSONObject body=new JSONObject().put("model","trae-cn/deepseek-v4.1-flash").put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","中文工具边界验证"))).put("tools",tools);
        String raw=body.toString(); int padding=208030-raw.getBytes(StandardCharsets.UTF_8).length;check(padding>0,"fixture fits the reported byte size");
        StringBuilder padded=new StringBuilder("{");for(int i=0;i<padding;i++)padded.append(' ');padded.append(raw.substring(1));
        check(padded.toString().getBytes(StandardCharsets.UTF_8).length==208030,"synthetic request has exactly the reported 208030 bytes");
        JSONObject parsed=new JSONObject(padded.toString());check(parsed.getJSONArray("tools").length()==167,"all 167 synthetic tools retained");
        for(int pass=0;pass<3;pass++){
            parsed=new JSONObject(parsed.toString());
            for(int i=0;i<4;i++){
                JSONObject n=parsed.getJSONArray("tools").getJSONObject(i).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("start");
                check(n.getLong("minimum")==-9007199254740991L && n.getLong("maximum")==9007199254740991L,"schema bounds survive copy "+pass+" tool "+i);
            }
        }
        System.out.println("JsonNumericBoundaryOfflineTest passed "+checks+" checks; synthetic 208030-byte/167-tool fixture (not original user payload)");
    }
}
