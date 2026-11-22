package com.gaeul.allin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Only the dedicated test AVD; synthetic false bridge plus actual Activity recreation. */
final class PanelThemeStartupRuntime {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    public static final class FalseBridge {
        @JavascriptInterface public boolean isSystemDark(){return false;}
        @JavascriptInterface public void applySystemBars(boolean dark){}
    }
    private static String evaluate(Instrumentation i,WebView v,String js)throws Exception {
        CountDownLatch done=new CountDownLatch(1);final String[] result={null};
        i.runOnMainSync(()->v.evaluateJavascript(js,value->{result[0]=value;done.countDown();}));
        if(!done.await(12,TimeUnit.SECONDS))throw new AssertionError("WebView evaluation timeout");
        return result[0];
    }
    private static JSONObject state(Instrumentation i,WebView v)throws Exception {
        String result=evaluate(i,v,"JSON.stringify({theme:document.documentElement.getAttribute('data-theme'),mode:document.documentElement.getAttribute('data-theme-mode'),saved:localStorage.getItem('allin_theme'),ready:typeof window.__onSystemThemeChanged==='function',seed:window.__allinInitialSystemDark})");
        return new JSONObject(new JSONArray("["+result+"]").getString(0));
    }
    private static JSONObject waitPage(Instrumentation i,WebView v)throws Exception {
        for(int attempt=0;attempt<80;attempt++) {
            String value=evaluate(i,v,"document.readyState==='complete' && typeof window.__onSystemThemeChanged==='function'");
            if("true".equals(value))return state(i,v);
            Thread.sleep(100);
        }
        throw new AssertionError("Actual panel never became ready");
    }
    private static WebView view(Activity a)throws Exception {Field f=MainActivity.class.getDeclaredField("webView");f.setAccessible(true);return (WebView)f.get(a);}
    private static Activity open(Instrumentation i){return i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    static String run(Instrumentation i)throws Exception {
        checks=0;
        InputStream asset=i.getTargetContext().getAssets().open("dashboard.html");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=asset.read(buffer))!=-1)bytes.write(buffer,0,n);asset.close();String html=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        int at=html.indexOf("<script>"),end=html.indexOf("</script>",at);check(at>0&&end>at,"installed APK theme head found");String head=html.substring(at,end+9);
        final WebView[] fixture={null};
        try {
            i.runOnMainSync(()->{fixture[0]=new WebView(i.getTargetContext());fixture[0].getSettings().setJavaScriptEnabled(true);fixture[0].addJavascriptInterface(new FalseBridge(),"AllInNative");});
            for(boolean dark:new boolean[]{true,false}){
                String page=PanelThemePage.seed("<html><head>"+head+"</head><body><script>window.firstDocumentTheme=document.documentElement.getAttribute('data-theme');requestAnimationFrame(function(){window.firstFrameTheme=document.documentElement.getAttribute('data-theme');});</script></body></html>",dark);
                CountDownLatch loaded=new CountDownLatch(1);i.runOnMainSync(()->{fixture[0].setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(WebView v,String url){loaded.countDown();}});fixture[0].loadDataWithBaseURL("https://theme-fixture.invalid/",page,"text/html","UTF-8",null);});
                check(loaded.await(15,TimeUnit.SECONDS),"synthetic Android WebView loaded");
                String result=evaluate(i,fixture[0],"JSON.stringify({first:window.firstDocumentTheme,current:document.documentElement.getAttribute('data-theme')})");JSONObject state=new JSONObject(new JSONArray("["+result+"]").getString(0));String expected=dark?"dark":"light";
                check(expected.equals(state.getString("first")),"first document theme with false native bridge "+expected);
                check(expected.equals(state.getString("current")),"synthetic final theme "+expected);
            }
        } finally {if(fixture[0]!=null)i.runOnMainSync(()->fixture[0].destroy());}
        Activity activity=open(i);String original=null;boolean captured=false;
        try {
            WebView web=view(activity);JSONObject initial=waitPage(i,web);original=initial.optString("saved",null);captured=true;
            evaluate(i,web,"setThemeMode('auto');true");final Activity first=activity;i.runOnMainSync(first::finish);
            activity=open(i);web=view(activity);JSONObject fresh=waitPage(i,web);
            boolean dark=(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            check("auto".equals(fresh.getString("mode")),"actual recreated Activity keeps auto preference");
            check((dark?"dark":"light").equals(fresh.getString("theme")),"actual recreated Activity follows native configuration");
            check(fresh.isNull("seed"),"startup snapshot cleared after actual page readiness");
            evaluate(i,web,"setThemeMode('light');true");check("light".equals(state(i,web).getString("theme")),"manual light retained");
            evaluate(i,web,"setThemeMode('dark');true");check("dark".equals(state(i,web).getString("theme")),"manual dark retained");
            evaluate(i,web,"setThemeMode('auto');true");check((dark?"dark":"light").equals(state(i,web).getString("theme")),"back to auto follows real system");
            JSONObject number=new JSONObject("{\"min\":-9007199254740991,\"max\":9007199254740991}");check(number.getLong("min")==-9007199254740991L&&number.getLong("max")==9007199254740991L,"numeric boundary on actual Android classloader");
        } finally {
            if(activity!=null){WebView web=view(activity);if(captured){String restore=original==null?"localStorage.removeItem('allin_theme');setThemeMode('auto');localStorage.removeItem('allin_theme');":"setThemeMode("+JSONObject.quote(original)+");";evaluate(i,web,restore+"true");}final Activity last=activity;i.runOnMainSync(last::finish);}
        }
        return "PanelThemeStartupRuntime passed "+checks+" Android checks; synthetic early-false bridge and actual Activity recreation; original preference restored";
    }
}
