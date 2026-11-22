package com.gaeul.allin;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PanelRefreshRuntime {
    static String run(Instrumentation test) throws Exception {
        Context target = test.getTargetContext();
        if (!target.getSharedPreferences("magpie_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("gateway_enabled", false).commit()) {
            throw new AssertionError("isolated gateway setting was not saved");
        }
        Intent launch = new Intent().setClassName(target.getPackageName(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) test.startActivitySync(launch);
        try {
            Field webView = MainActivity.class.getDeclaredField("webView");
            webView.setAccessible(true);
            WebView view = (WebView) webView.get(activity);
            awaitScript(test, view, "typeof window.appUpdateResume === 'function'");
            test.runOnMainSync(() -> {
                android.webkit.WebSettings settings = view.getSettings();
                if (settings.getAllowFileAccess() || settings.getAllowContentAccess()
                        || settings.getAllowFileAccessFromFileURLs() || settings.getAllowUniversalAccessFromFileURLs())
                    throw new AssertionError("WebView allows broad access to device files or content providers");
            });
            evaluate(test, view, "window.fixtureIcon = false; const iconProbe = new Image();"
                    + "iconProbe.onload = () => window.fixtureIcon = iconProbe.naturalWidth > 0;"
                    + "iconProbe.src = 'icon.png'");
            awaitScript(test, view, "window.fixtureIcon && typeof window.AllInNative.requestAsync === 'function'");
            // Let the initial onResume/onPageFinished callbacks drain before measuring a new resume.
            Thread.sleep(650);
            evaluate(test, view, "window.fixtureResumeCount=0; window.fixtureConfigCount=0; window.fixtureGatewayState=null;"
                    + "window.appUpdateResume=()=>window.fixtureResumeCount++; window.loadConfig=()=>window.fixtureConfigCount++;"
                    + "window.__setGatewayState=(running,port)=>window.fixtureGatewayState=[running,port]");
            Field running = GatewayService.class.getDeclaredField("running");
            Field current = GatewayService.class.getDeclaredField("current");
            running.setAccessible(true);
            current.setAccessible(true);
            Object previous = current.get(null);
            GatewayService service = new GatewayService();
            running.setBoolean(service, true);
            // Exercise the running state without opening a listener.
            current.set(null, service);
            try {
                test.runOnMainSync(activity::onResume);
                try {
                    awaitScript(test, view, "window.fixtureResumeCount === 1 && window.fixtureConfigCount >= 1"
                            + " && window.fixtureGatewayState?.join(',') === 'true,3425'");
                } catch (AssertionError failure) {
                    throw new AssertionError("resume observations: " + evaluate(test, view,
                            "[window.fixtureResumeCount,window.fixtureConfigCount,window.fixtureGatewayState]"), failure);
                }
            } finally {
                current.set(null, previous);
            }

            Method post = MainActivity.class.getDeclaredMethod("postPanelRefresh", WebView.class, Runnable.class);
            post.setAccessible(true);
            CountDownLatch stale = new CountDownLatch(1);
            test.runOnMainSync(() -> {
                try {
                    activity.onResume();
                    post.invoke(activity, view, (Runnable) stale::countDown);
                    activity.finish();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            if (stale.await(800, TimeUnit.MILLISECONDS)) throw new AssertionError("refresh ran after finish");
            test.waitForIdleSync();
            if (!activity.isDestroyed() || webView.get(activity) != null || view.getParent() != null)
                throw new AssertionError("activity and panel view did not detach");
            return "ANDROID_PANEL_REFRESH_PASS normal resume, finish before delayed refresh, activity destroyed";
        } finally {
            test.runOnMainSync(activity::finish);
        }
    }

    static void awaitScript(Instrumentation test, WebView view, String script) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            if ("true".equals(evaluate(test, view, script))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("WebView condition did not become true: " + script);
    }

    static String evaluate(Instrumentation test, WebView view, String script) throws Exception {
        CountDownLatch reply = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        test.runOnMainSync(() -> view.evaluateJavascript(script, value -> {
            result.set(value);
            reply.countDown();
        }));
        if (!reply.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView script timed out");
        return result.get();
    }
}
