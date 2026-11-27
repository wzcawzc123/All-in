package com.gaeul.allin;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 App 内 WebView 执行豆包官方活动页的签到。
 *
 * <p>豆包活动接口带 PC 客户端安全 SDK 签名，直接复制 HTTP 请求会被判为
 * {@code common invalid param}。这里让官方页面自己发请求，App 只读取页面状态并点击
 * 「立即签到」，不接触 Cookie 之外的任何鉴权材料，也不伪造签名。</p>
 */
final class DoubaoCheckinPage implements DoubaoCheckin.Page {
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/147.0.7727.149 Safari/537.36";

    private static final String PROBE_JS =
            "(function(){var b=document.body?document.body.innerText||'':'';"
                    + "var bs=Array.prototype.slice.call(document.querySelectorAll('button'))"
                    + ".map(function(x){return (x.innerText||'').trim()}).filter(Boolean);"
                    + "return JSON.stringify({url:location.href,ready:document.readyState,"
                    + "text:b.slice(0,5000),buttons:bs.slice(0,100)});})()";

    private static final String CLICK_JS =
            "(function(){var bs=Array.prototype.slice.call(document.querySelectorAll('button'));"
                    + "for(var i=0;i<bs.length;i++){var t=(bs[i].innerText||'').trim();"
                    + "if(t.indexOf('立即签到')>=0||t==='去签到'||t==='签到'){bs[i].click();return 'clicked';}}"
                    + "return 'missing';})()";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile boolean closed;
    private Call activeOpen;
    private Call activeClick;

    DoubaoCheckinPage(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
    }

    @Override
    public synchronized DoubaoCheckin.State open(JSONObject account) throws Exception {
        ensureBackground();
        String cookie = DoubaoAuth.normalizeCookie(DoubaoAuth.cookieOf(account));
        Call call = new Call();
        activeOpen = call;
        main.post(() -> start(cookie, call));
        return call.await(45, "豆包活动页加载超时", this);
    }

    @Override
    public synchronized DoubaoCheckin.State click() throws Exception {
        ensureBackground();
        if (webView == null) throw new IOException("豆包活动页尚未加载");
        Call call = new Call();
        activeClick = call;
        main.post(() -> {
            try {
                if (closed || webView == null) {
                    call.fail(new IOException("豆包活动页已关闭"));
                    return;
                }
                webView.evaluateJavascript(CLICK_JS, value -> {
                    if ("missing".equals(unquote(value))) {
                        probe(call);
                    } else {
                        scheduleClickProbe(0, call);
                    }
                });
                main.postDelayed(() -> {
                    if (!call.done.get()) probe(call);
                }, 14000);
            } catch (Throwable failure) {
                call.fail(failure);
            }
        });
        return call.await(20, "豆包签到按钮点击超时", this);
    }

    @Override
    public synchronized DoubaoCheckin.State observe() throws Exception {
        ensureBackground();
        if (webView == null) throw new IOException("豆包活动页尚未加载");
        Call call = new Call();
        main.post(() -> probe(call));
        return call.await(12, "豆包活动页状态读取超时", this);
    }

    @Override
    public void close() {
        closed = true;
        main.post(() -> destroy());
    }

    private void start(String cookie, Call call) {
        try {
            destroy();
            WebView view = new WebView(new ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault));
            webView = view;
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            settings.setUserAgentString(WEB_USER_AGENT);
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            try {
                CookieManager.class.getMethod("setAcceptThirdPartyCookies", WebView.class, boolean.class)
                        .invoke(CookieManager.getInstance(), view, true);
            } catch (Exception ignored) {}
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setWebChromeClient(new WebChromeClient());
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    super.onPageFinished(v, url);
                    main.postDelayed(() -> scheduleOpenProbe(0, call), 1200);
                }

                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (request != null && request.isForMainFrame()) {
                        call.fail(new IOException("豆包活动页网络错误"
                        + (error == null ? "" : "：" + error.getDescription())));
                    }
                }

                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) return true;
                    String scheme = request.getUrl().getScheme();
                    return !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
                }
            });
            setCookies(cookie, () -> {
                if (closed || webView != view) {
                    call.fail(new IOException("豆包活动页已关闭"));
                    return;
                }
                view.loadUrl(DoubaoCheckin.PAGE_URL);
                main.postDelayed(() -> {
                    if (!call.done.get()) call.fail(new IOException("豆包活动页加载超时"));
                }, 42000);
            });
        } catch (Throwable failure) {
            call.fail(failure);
            destroy();
        }
    }

    private void setCookies(String cookie, Runnable after) {
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        List<String> values = new ArrayList<>();
        String[] pairs = cookie == null ? new String[0] : cookie.split(";");
        for (String pair : pairs) {
            String value = pair == null ? "" : pair.trim();
            if (value.isEmpty() || value.indexOf('=') < 0) continue;
            values.add(value + "; Domain=.doubao.com; Path=/; Secure");
        }
        if (values.isEmpty()) {
            main.post(after);
            return;
        }
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger remaining = new AtomicInteger(values.size());
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            main.post(() -> {
                try { manager.flush(); } catch (Throwable ignored) {}
                main.postDelayed(after, 150);
            });
        };
        for (String value : values) {
            try {
                manager.setCookie(DoubaoAuth.ORIGIN, value, (ValueCallback<Boolean>) ok -> {
                    if (remaining.decrementAndGet() == 0) finish.run();
                });
            } catch (Throwable failure) {
                if (remaining.decrementAndGet() == 0) finish.run();
            }
        }
        // Some WebView builds never invoke the callback for rejected cookies.
        main.postDelayed(() -> {
            if (remaining.getAndSet(0) > 0) finish.run();
        }, 1500);
    }

    private void scheduleOpenProbe(int attempt, Call call) {
        if (call.done.get()) return;
        if (!call.done.get()) {
            probeState(state -> {
                if (call.done.get()) return;
                if (state.loaded && (state.signIn || state.checkedIn || state.loginRequired
                        || state.captcha || state.text.contains("每日签到") || state.text.contains("做任务"))) {
                    call.ok(state);
                } else if (attempt < 12) {
                    main.postDelayed(() -> scheduleOpenProbe(attempt + 1, call), 800);
                } else {
                    call.ok(state);
                }
            });
        }
    }

    private void scheduleClickProbe(int attempt, Call call) {
        if (call.done.get()) return;
        main.postDelayed(() -> probeState(state -> {
            if (call.done.get()) return;
            if (state.success || state.failure || state.checkedIn || state.captcha || state.loginRequired) {
                call.ok(state);
            } else if (attempt < 12) {
                scheduleClickProbe(attempt + 1, call);
            } else {
                call.ok(state);
            }
        }), attempt == 0 ? 1200 : 1000);
    }

    private void probe(Call call) {
        probeState(state -> {
            if (!call.done.get()) call.ok(state);
        });
    }

    private void probeState(java.util.function.Consumer<DoubaoCheckin.State> callback) {
        try {
            if (closed || webView == null) {
                callback.accept(new DoubaoCheckin.State());
                return;
            }
            webView.evaluateJavascript(PROBE_JS, value -> callback.accept(
                    DoubaoCheckin.State.parse(unquote(value))));
        } catch (Throwable failure) {
            callback.accept(new DoubaoCheckin.State());
        }
    }

    private static String unquote(String value) {
        if (value == null) return "";
        try {
            return new JSONArray("[" + value + "]").optString(0, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void destroy() {
        if (webView != null) {
            try { webView.stopLoading(); } catch (Throwable ignored) {}
            try { webView.destroy(); } catch (Throwable ignored) {}
            webView = null;
        }
    }

    private static void ensureBackground() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("豆包签到页面不能在主线程等待");
        }
    }

    private static final class Call {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        volatile DoubaoCheckin.State state;
        volatile Throwable error;

        void ok(DoubaoCheckin.State value) {
            if (!done.compareAndSet(false, true)) return;
            state = value;
            latch.countDown();
        }

        void fail(Throwable failure) {
            if (!done.compareAndSet(false, true)) return;
            error = failure;
            latch.countDown();
        }

        DoubaoCheckin.State await(long seconds, String timeoutMessage, DoubaoCheckinPage page) throws Exception {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                done.set(true);
                page.close();
                throw new IOException(timeoutMessage);
            }
            if (error != null) throw new IOException(error.getMessage() == null ? "豆包签到页面失败" : error.getMessage(), error);
            return state;
        }
    }
}
