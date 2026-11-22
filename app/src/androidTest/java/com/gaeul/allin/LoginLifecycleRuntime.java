package com.gaeul.allin;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class LoginLifecycleRuntime {
    static String run(Instrumentation test) throws Exception {
        Context app = test.getTargetContext();
        try (ServerSocket fixture = GatewayServer.openLoopbackCallbackPort(GatewayService.GATEWAY_PORT)) {
            // Codex initialization makes a local request; the fixture holds the response open.
            Intent intent = new Intent(app, LoginActivity.class).putExtra("provider", "codex")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            fixture.setSoTimeout(4000);
            LoginActivity activity = (LoginActivity) test.startActivitySync(intent);
            try (Socket held = fixture.accept()) {
                held.setSoTimeout(4000);
                Field webView = LoginActivity.class.getDeclaredField("webView");
                webView.setAccessible(true);
                WebView view = (WebView) webView.get(activity);
                Method start = LoginActivity.class.getDeclaredMethod("startLoginTask", Runnable.class);
                start.setAccessible(true);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch interrupted = new CountDownLatch(1);
                start.invoke(activity, (Runnable) () -> {
                    entered.countDown();
                    try { Thread.sleep(60_000); }
                    catch (InterruptedException expected) { interrupted.countDown(); }
                });
                if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("login worker not started");
                String request = GatewayServer.readCallbackRequestLine(held);
                if (request == null || !request.startsWith("GET /api/auth/codex/init"))
                    throw new AssertionError("login did not reach the fixture");
                FutureTask<Void> finished = new FutureTask<>(() -> {
                    while (held.getInputStream().read() != -1) { }
                    return null;
                });
                Thread acceptEof = new Thread(finished); acceptEof.setDaemon(true); acceptEof.start();
                test.runOnMainSync(activity::finish);
                finished.get(3, TimeUnit.SECONDS);
                if (!interrupted.await(1, TimeUnit.SECONDS)) throw new AssertionError("login worker did not stop");
                test.waitForIdleSync();
                if (!activity.isDestroyed() || webView.get(activity) != null || view.getParent() != null)
                    throw new AssertionError("closed login WebView is still attached");
                return "ANDROID_LOGIN_LIFECYCLE_PASS sleeping worker interrupted, pending local socket closed, WebView detached";
            } finally { test.runOnMainSync(activity::finish); }
        }
    }
}
