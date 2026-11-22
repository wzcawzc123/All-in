package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PanelThemePageOfflineTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        String page = "<!doctype html><html><head><meta charset=\"UTF-8\"><script>original();</script></head><body>中文面板</body></html>";
        for (boolean dark : new boolean[]{true,false}) {
            String rendered = PanelThemePage.seed(page,dark);
            String script = "\n<script>window.__allinInitialSystemDark=" + dark + ";</script>\n";
            check(rendered.indexOf(script) == page.indexOf("<head>") + 6, "snapshot is before original head scripts");
            check(rendered.replace(script, "").equals(page), "all original HTML is preserved byte-for-byte in UTF-8");
            check(rendered.indexOf(script) == rendered.lastIndexOf(script), "one snapshot per render");
            InputStream stream = PanelThemePage.readAndSeed(new ByteArrayInputStream(page.getBytes(StandardCharsets.UTF_8)),dark);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); byte[] buffer=new byte[1024]; int n;
            while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);
            check(new String(bytes.toByteArray(),StandardCharsets.UTF_8).equals(rendered), "UTF-8 stream and string projection agree");
        }
        final boolean[] closed={false};
        PanelThemePage.readAndSeed(new ByteArrayInputStream(page.getBytes(StandardCharsets.UTF_8)){
            @Override public void close() throws IOException {closed[0]=true;super.close();}
        },true);
        check(closed[0],"asset source closed");
        try { PanelThemePage.seed("<body>not the local panel</body>",true); throw new AssertionError("missing head accepted"); }
        catch(IllegalArgumentException expected){checks++;}
        try { PanelThemePage.readAndSeed(new ByteArrayInputStream(new byte[2*1024*1024+1]),true); throw new AssertionError("oversized asset accepted"); }
        catch(IOException expected){checks++;}
        check(!PanelThemePage.seed(page,true).contains("AllInNative"),"snapshot does not expose a privileged bridge");
        System.out.println("PanelThemePageOfflineTest passed "+checks+" checks");
    }
}
