package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A trusted local page gets the native theme snapshot before any head script runs. */
final class PanelThemePage {
    private PanelThemePage() {}
    static String seed(String html, boolean dark) {
        int head = html.indexOf("<head>");
        if (head < 0) throw new IllegalArgumentException("Local panel head is missing");
        int at = head + "<head>".length();
        String script = "\n<script>window.__allinInitialSystemDark=" + (dark ? "true" : "false") + ";</script>\n";
        return html.substring(0, at) + script + html.substring(at);
    }
    static InputStream readAndSeed(InputStream input, boolean dark) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream source = input) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("Local panel exceeds theme bootstrap limit");
                bytes.write(buffer, 0, count);
            }
        }
        return new ByteArrayInputStream(seed(new String(bytes.toByteArray(), StandardCharsets.UTF_8), dark).getBytes(StandardCharsets.UTF_8));
    }
}
