package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Android loopback HTTP; completed vendor frames are followed by a deliberately held body. */
final class CatPawTerminalRuntime {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final class Held extends InputStream {
        final ByteArrayInputStream prefix;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger tailReads = new AtomicInteger();
        Held(String text) { prefix = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        public int read() throws IOException { byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255; }
        public int read(byte[] bytes,int off,int len) throws IOException {
            if(len==0)return 0;if(prefix.available()>0)return prefix.read(bytes,off,len);
            tailReads.incrementAndGet();
            try { if(!release.await(6,TimeUnit.SECONDS))throw new IOException("Android fixture remains open"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            return -1;
        }
        public void close(){closed.set(true);release.countDown();}
    }
    private static String frame(String text, boolean finished) throws Exception {
        return "data: "+new JSONObject().put("message",new JSONObject().put("model","GLM-5.3-FlashX").put("finished",finished)
                .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text))))+"\n\n";
    }
    static String run(Context target) throws Exception {
        checks=0;File root=new File(target.getFilesDir(),"catpaw-terminal-"+UUID.randomUUID());check(root.mkdir(),"isolated fixture directory");
        Context context=new ContextWrapper(target){@Override public File getFilesDir(){return root;}@Override public Context getApplicationContext(){return this;}};
        String key="fixture-catpaw-terminal";
        JSONObject provider=new JSONObject().put("id","catpaw").put("enabled",true).put("protocol","catpaw")
                .put("models",new JSONArray().put("glm-5.3-flashx"))
                .put("accounts",new JSONArray().put(new JSONObject().put("id","synthetic-catpaw").put("token","synthetic-token").put("enabled",true)));
        AtomicConfigFile.write(new File(root,"config.json"),new JSONObject().put("schemaVersion",4).put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey",key)).put("providers",new JSONArray().put(provider)).toString());
        long maxMillis=0;int completed=0;
        try {
            for(String path:new String[]{"/v1/chat/completions","/v1/messages","/v1/responses"})
                for(String kind:new String[]{"message","metadata","outer"}) {
                    String last="metadata".equals(kind)?"data: {\"message\":{\"finished\":true}}\n\n"
                            :"outer".equals(kind)?"data: {\"finished\":true,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ANDROID_CATPAW_COMPLETE\"}]}}\n\n"
                            :frame("ANDROID_CATPAW_COMPLETE",true);
                    Held source=new Held(frame("ANDROID_CATPAW_COMPLETE",false)+last);AtomicInteger posts=new AtomicInteger();
                    GatewayServer gateway=new GatewayServer(context,GatewayServer.ListenPlan.damaged(0));
                    gateway.setCatPawStreamForTests((route,body,headers,request)->{
                        posts.incrementAndGet();InputStream data=route.endsWith("/turn")?source:new ByteArrayInputStream((route.endsWith("/round")?"{\"code\":0}":"{}").getBytes(StandardCharsets.UTF_8));
                        return new CatPawStream.Reply(200,data,null,request);
                    });
                    gateway.setCatPawTransportForTests((url,body,headers,timeout,accept)->{throw new AssertionError("buffered request unexpectedly replayed");});
                    HttpURLConnection client=null;
                    try {
                        gateway.start(5000,true);client=(HttpURLConnection)new URL("http://127.0.0.1:"+gateway.getListeningPort()+path).openConnection();
                        client.setConnectTimeout(2000);client.setReadTimeout(2500);client.setRequestMethod("POST");client.setDoOutput(true);
                        client.setRequestProperty("Authorization","Bearer "+key);client.setRequestProperty("Content-Type","application/json");
                        JSONObject body=new JSONObject().put("model","catpaw/glm-5.3-flashx").put("stream",true);
                        if(path.equals("/v1/responses"))body.put("input","hi");
                        else body.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hi")));
                        if(path.equals("/v1/messages"))body.put("max_tokens",128);
                        long start=SystemClock.elapsedRealtime();try(OutputStream write=client.getOutputStream()){write.write(body.toString().getBytes(StandardCharsets.UTF_8));}
                        check(client.getResponseCode()==200,"Android HTTP succeeds: "+path+" / "+kind);
                        StringBuilder output=new StringBuilder();try(BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8))){for(String line;(line=reader.readLine())!=null;)output.append(line).append('\n');}
                        long elapsed=SystemClock.elapsedRealtime()-start;maxMillis=Math.max(maxMillis,elapsed);
                        String wire=output.toString(),end=path.equals("/v1/messages")?"event: message_stop":path.equals("/v1/responses")?"event: response.completed":"data: [DONE]";
                        check(wire.contains("ANDROID_CATPAW_COMPLETE"),"complete business text preserved");
                        check(wire.indexOf(end)>=0 && wire.indexOf(end)==wire.lastIndexOf(end),"one real Android client terminal");
                        check(source.closed.get() && source.tailReads.get()==0,"no upstream EOF required on Android");
                        check(posts.get()==3,"one round/event/turn, no replay");check(elapsed<2500,"completion is not a timeout");
                        completed++;
                    } finally {if(client!=null)client.disconnect();source.close();gateway.stop();}
                }
            return "ANDROID_CATPAW_TERMINAL_PASS cases="+completed+" checks="+checks+" maxMillis="+maxMillis+"; original screenshot model, three protocols; held upstream tail never read";
        } finally {delete(root);}
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
}
