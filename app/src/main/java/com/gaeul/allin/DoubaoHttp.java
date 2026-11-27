package com.gaeul.allin;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Map;

/** 豆包网页请求的真正 HTTP 连接；与协议解析分开，离线测试可以注入假的 Transport。 */
public final class DoubaoHttp {
    private DoubaoHttp() {}

    public static final class HttpTransport implements DoubaoAuth.Transport {
        @Override
        public DoubaoAuth.Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
            final HttpURLConnection connection = NetworkPolicy.openHttp(url);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(180000);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            try {
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                int status = connection.getResponseCode();
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (in == null) in = new ByteArrayInputStream(new byte[0]);
                return new DoubaoAuth.Response(status, connection.getContentType(),
                        new FilterInputStream(in) {
                            @Override public void close() throws IOException {
                                try { super.close(); } finally { connection.disconnect(); }
                            }
                        });
            } catch (IOException failure) {
                connection.disconnect();
                throw failure;
            }
        }
    }
}
