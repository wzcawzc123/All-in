package com.gaeul.allin;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;

/**
 * Codex POST /backend-api/codex/responses request-body zstd compression.
 * Mirrors Magpie internal/provider/codex_zstd.go: level 3, 2 MiB window cap
 * (windowLog=21), checksum disabled, applied only to ChatGPT Codex /responses
 * turns and never double-compressed.
 */
final class CodexZstd {
    static final int LEVEL = 3;
    static final int WINDOW_LOG = 21; // 2 << 20 = 2 MiB
    static final boolean CHECKSUM = false;

    static final class WireBody {
        final byte[] bytes;
        final boolean compressed;

        WireBody(byte[] bytes, boolean compressed) {
            this.bytes = bytes;
            this.compressed = compressed;
        }
    }

    private CodexZstd() {}

    static boolean isCodexResponsesUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.trim().isEmpty()) return false;
        try {
            URI u = new URI(targetUrl.trim());
            String host = u.getHost();
            String path = u.getPath();
            return host != null && "chatgpt.com".equalsIgnoreCase(host)
                    && "/backend-api/codex/responses".equals(path);
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean shouldCompress(String method, String targetUrl, String authMode, String existingContentEncoding) {
        if (!"POST".equalsIgnoreCase(method)) return false;
        if ("apikey".equalsIgnoreCase(authMode)) return false;
        if (!isCodexResponsesUrl(targetUrl)) return false;
        if (existingContentEncoding != null) {
            String trimmed = existingContentEncoding.trim();
            if (!trimmed.isEmpty() && !"identity".equalsIgnoreCase(trimmed)) return false;
        }
        return true;
    }

    static byte[] compress(byte[] plain) throws IOException {
        byte[] input = plain == null ? new byte[0] : plain;
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(LEVEL);
            ctx.setWindowLog(WINDOW_LOG);
            ctx.setChecksum(CHECKSUM);
            return ctx.compress(input);
        } catch (RuntimeException failure) {
            throw new IOException("Codex zstd compression failed", failure);
        }
    }

    static WireBody prepare(String method, String targetUrl, String authMode,
                            String existingContentEncoding, byte[] plain) throws IOException {
        byte[] input = plain == null ? new byte[0] : plain;
        if (!shouldCompress(method, targetUrl, authMode, existingContentEncoding)) {
            return new WireBody(input, false);
        }
        try {
            return new WireBody(compress(input), true);
        } catch (LinkageError unavailable) {
            // Fallback to plain JSON only if native JNI library cannot load on an unsupported ABI.
            return new WireBody(input, false);
        }
    }

    static byte[] decompress(byte[] compressed, int maxBytes) throws IOException {
        if (compressed == null || compressed.length == 0) return new byte[0];
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes < 0");
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("zstd decompressed payload exceeds limit");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (RuntimeException failure) {
            throw new IOException("Codex zstd decompression failed", failure);
        }
    }
}
