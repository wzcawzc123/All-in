package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** POST transport for held inference. Call.cancel closes a blocked exchange without joining its reader. */
final class HeldHttpConnection extends HttpURLConnection {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build();

    private final Proxy proxy;
    private final Headers.Builder headers = new Headers.Builder();
    private final Object callLock = new Object();
    private final RequestContext context;
    private ByteArrayOutputStream body;
    private boolean cancelled;
    private Call call;
    private volatile Response response;
    private IOException failure;

    HeldHttpConnection(URL url, Proxy proxy, RequestContext context) {
        super(url);
        this.proxy = proxy;
        this.context = context;
        setInstanceFollowRedirects(false);
    }

    @Override public void setRequestProperty(String name, String value) { headers.set(name, value); }
    @Override public void addRequestProperty(String name, String value) { headers.add(name, value); }
    @Override public String getRequestProperty(String name) { return headers.get(name); }
    @Override public Map<String, List<String>> getRequestProperties() { return headers.build().toMultimap(); }

    @Override public OutputStream getOutputStream() throws IOException {
        if (!getDoOutput() || connected) throw new ProtocolException("Output is unavailable");
        synchronized (callLock) {
            context.check();
            if (cancelled) throw new IOException("Request cancelled");
            if (body == null) body = new ByteArrayOutputStream();
            return body;
        }
    }

    private Call prepareCall() throws IOException {
        ByteArrayOutputStream data;
        synchronized (callLock) { data = body; body = null; }
        long declared = fixedContentLengthLong >= 0 ? fixedContentLengthLong : fixedContentLength;
        RequestBody payload = new InferenceBody(data, context);
        if (declared >= 0 && payload.contentLength() != declared) throw new ProtocolException("Declared request length mismatch");
        int remaining = (int) Math.max(1, context.remainingMillis());
        OkHttpClient client = CLIENT.newBuilder().proxy(proxy)
                .connectTimeout(capped(getConnectTimeout(), remaining), TimeUnit.MILLISECONDS)
                .readTimeout(capped(getReadTimeout(), remaining), TimeUnit.MILLISECONDS)
                .writeTimeout(remaining, TimeUnit.MILLISECONDS).build();
        Request request = new Request.Builder().url(url).headers(headers.build()).method(method, payload).build();
        synchronized (callLock) {
            context.check();
            if (cancelled) throw new IOException("Request cancelled");
            call = client.newCall(request);
            return call;
        }
    }

    private static final class InferenceBody extends RequestBody {
        private ByteArrayOutputStream pending;
        private final RequestContext context;
        private final long size;
        InferenceBody(ByteArrayOutputStream content, RequestContext context) {
            this.pending = content == null ? new ByteArrayOutputStream(0) : content;
            this.size = pending.size();
            this.context = context;
        }
        @Override public okhttp3.MediaType contentType() { return null; }
        @Override public long contentLength() { return size; }
        @Override public boolean isOneShot() { return true; }
        @Override public void writeTo(BufferedSink sink) throws IOException {
            ByteArrayOutputStream content = pending;
            pending = null;
            if (content == null) throw new ProtocolException("Inference body already sent");
            context.check();
            content.writeTo(sink.outputStream());
        }
    }

    private static int capped(int configured, int remaining) {
        return configured == 0 ? remaining : Math.min(configured, remaining);
    }

    @Override public synchronized void connect() throws IOException {
        if (failure != null) throw failure;
        if (connected) return;
        connected = true;
        try {
            Response received = prepareCall().execute();
            synchronized (callLock) {
                if (!cancelled) { response = received; return; }
            }
            received.close();
            throw new IOException("Request cancelled");
        } catch (IOException error) {
            failure = error;
            throw error;
        }
    }

    @Override public int getResponseCode() throws IOException { connect(); return response.code(); }
    @Override public String getResponseMessage() throws IOException { connect(); return response.message(); }
    @Override public InputStream getInputStream() throws IOException {
        if (getResponseCode() >= 400) throw new IOException("Upstream HTTP " + response.code());
        return response.body().byteStream();
    }
    @Override public InputStream getErrorStream() {
        try { return getResponseCode() >= 400 ? response.body().byteStream() : null; }
        catch (IOException unavailable) { return null; }
    }
    @Override public String getHeaderField(String name) {
        try { connect(); return response.header(name); }
        catch (IOException unavailable) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try { connect(); return response.headers().toMultimap(); }
        catch (IOException unavailable) { return java.util.Collections.emptyMap(); }
    }
    @Override public boolean usingProxy() { return proxy != null && proxy.type() != Proxy.Type.DIRECT; }

    @Override public void disconnect() {
        Call current;
        synchronized (callLock) { cancelled = true; body = null; current = call; call = null; }
        if (current != null) current.cancel();
        context.detach(this);
    }
}
