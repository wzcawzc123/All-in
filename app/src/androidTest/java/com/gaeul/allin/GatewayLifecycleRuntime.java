package com.gaeul.allin;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

import com.gaeul.allin.json.JSONObject;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class GatewayLifecycleRuntime {
    private static Field field(String name) throws Exception {
        Field value = GatewayService.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static GatewayService awaitOwner(Field current, GatewayService previous) throws Exception {
        long until = SystemClock.elapsedRealtime() + 2200;
        do {
            GatewayService owner = (GatewayService) current.get(null);
            if (owner != previous) return owner;
            Thread.sleep(15);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("service lifecycle did not advance while engine work was blocked");
    }

    private static GatewayServer awaitHealthy(Field server, GatewayService owner) throws Exception {
        long until = SystemClock.elapsedRealtime() + 16000;
        do {
            GatewayServer candidate = (GatewayServer) server.get(owner);
            if (candidate != null && GatewayService.isRunning() && HealthProbe.probe(candidate.healthInstanceId()).ok)
                return candidate;
            Thread.sleep(35);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("new listener did not become healthy");
    }

    private static void awaitMain(Handler main) throws Exception {
        CountDownLatch heartbeat = new CountDownLatch(1);
        main.post(heartbeat::countDown);
        require(heartbeat.await(2, TimeUnit.SECONDS), "main looper stalled on the engine monitor");
    }

    static String run(Instrumentation tests) throws Exception {
        Context target = tests.getTargetContext();
        require(target.getSharedPreferences("magpie_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("gateway_enabled", true).commit(), "isolated gateway setting");
        Intent launch = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) tests.startActivitySync(launch);
        Intent service = new Intent(target, GatewayService.class);
        Field current = field("current"), server = field("server"), disposed = field("disposed");
        Object engine = field("ENGINE_SERIAL").get(null);
        AtomicInteger pending = (AtomicInteger) field("PENDING_STOPS").get(null);
        Handler main = new Handler(Looper.getMainLooper());
        try {
            GatewayService first = awaitOwner(current, null);
            require(first != null, "initial service was not created");
            GatewayServer old = awaitHealthy(server, first);
            checkPanelRoutes(tests, target, activity);
            GatewayService next;
            synchronized (engine) {
                require(target.stopService(service), "initial service was not stopped");
                require(awaitOwner(current, first) == null && disposed.getBoolean(first), "old service was detached");
                awaitMain(main);
                require(pending.get() == 1 && old.isAlive() && !GatewayService.isRunning(),
                        "old listener retained until serialized stop, detached from health");
                target.startForegroundService(service);
                next = awaitOwner(current, null);
                awaitMain(main);
                require(next != null && next != first && GatewayService.isForegroundStarted(), "replacement reaches foreground");
                require(server.get(next) == null && !GatewayService.isRunning(), "replacement cannot bind while old service is stopping");
                try {
                    GatewayService.backupBackend(target).readConfig();
                    throw new AssertionError("backup crossed a pending stop");
                } catch (IllegalStateException expected) {
                    require("gateway stopping".equals(expected.getMessage()), "backup must reject pending stop");
                }
                try {
                    GatewayService.accountTransferBackend(target).readConfig();
                    throw new AssertionError("account transfer crossed a pending stop");
                } catch (java.io.IOException expected) {
                    require(expected.getMessage().contains("正在停止"), "account transfer must reject pending stop");
                }
            }
            GatewayServer fresh = awaitHealthy(server, next);
            require(!old.isAlive() && pending.get() == 0 && !old.healthInstanceId().equals(fresh.healthInstanceId()),
                    "old listener stopped and replacement owns a fresh identity");
            require(GatewayService.backupBackend(target).readConfig().length > 0, "backup becomes available after the stop");
            require(GatewayService.accountTransferBackend(target).readConfig().length > 0,
                    "account transfer becomes available after the stop");
            restoreAcrossRestart(target, service, current, server, pending, main, next, fresh);
            return "ANDROID_GATEWAY_LIFECYCLE_PASS main loop responsive, listener serialized, transfer gated, in-flight restore recovered";
        } finally {
            target.stopService(service);
            tests.runOnMainSync(activity::finish);
        }
    }

    private static void checkPanelRoutes(Instrumentation tests, Context context, MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("webView");
        field.setAccessible(true);
        WebView panel = (WebView) field.get(activity);
        PanelRefreshRuntime.awaitScript(tests, panel, "typeof window.AllInNative?.requestAsync === 'function'");
        PanelRefreshRuntime.evaluate(tests, panel, "window.fixtureBridgeStatus = 0; fetch('http://127.0.0.1:3425/api/status')"
                + ".then(r => window.fixtureBridgeStatus = r.status).catch(() => window.fixtureBridgeStatus = -1); void 0");
        PanelRefreshRuntime.awaitScript(tests, panel, "window.fixtureBridgeStatus === 200");
        for (String asset : new String[]{"dashboard.html", "icon.png"}) {
            HttpURLConnection http = (HttpURLConnection) new URL("http://127.0.0.1:3425/" + asset).openConnection();
            http.setRequestProperty("Accept-Encoding", "identity");
            http.setConnectTimeout(2000);
            http.setReadTimeout(4000);
            try (InputStream network = http.getInputStream()) {
                byte[] expected = GatewayServer.readFileBytes(context.getAssets().open(asset));
                require(http.getResponseCode() == 200 && http.getContentLength() == expected.length
                        && Arrays.equals(expected, GatewayServer.readFileBytes(network)), "HTTP asset length or bytes changed for " + asset + " (declared=" + http.getContentLength() + ", expected=" + expected.length + ")");
            } finally { http.disconnect(); }
        }
    }

    private static void restoreAcrossRestart(Context target, Intent service, Field current, Field server,
                                             AtomicInteger pending, Handler main, GatewayService owner,
                                             GatewayServer listener) throws Exception {
        BackupCoordinator.Backend backup = GatewayService.backupBackend(target);
        byte[] prior = backup.readConfig();
        JSONObject settings = new JSONObject(new String(prior, StandardCharsets.UTF_8));
        settings.getJSONObject("server").put("loopbackOnly", true);
        byte[] replacement = settings.toString().getBytes(StandardCharsets.UTF_8);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(prior);
        FutureTask<Void> restore = new FutureTask<>(() -> {
            backup.restoreIfUnchanged(hash, replacement, null);
            return null;
        });
        Thread thread = new Thread(restore, "fixture-backup-restore");
        thread.setDaemon(true);
        GatewayService next;
        // Pause the restore inside the production transaction while it owns the engine lock.
        synchronized (listener) {
            thread.start();
            long until = SystemClock.elapsedRealtime() + 2200;
            boolean reading = false;
            do {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getMethodName().equals("backupDurableConfig") && thread.getState() == Thread.State.BLOCKED) {
                        reading = true;
                        break;
                    }
                }
                if (!reading) Thread.sleep(15);
            } while (!reading && SystemClock.elapsedRealtime() < until);
            require(reading, "restore did not reach the synchronized durable read");
            require(target.stopService(service) && awaitOwner(current, owner) == null, "restore owner was not detached");
            target.startForegroundService(service);
            next = awaitOwner(current, null);
            awaitMain(main);
            require(next != null && server.get(next) == null && pending.get() == 1 && !restore.isDone(),
                    "replacement must wait for the durable restore");
        }
        restore.get(12, TimeUnit.SECONDS);
        GatewayServer restored = awaitHealthy(server, next);
        require(restored.listenPlan().loopback() && !listener.isAlive() && pending.get() == 0,
                "new listener must use the restored config after the old listener stops");
        JSONObject disk = new JSONObject(new String(backup.readConfig(), StandardCharsets.UTF_8));
        require(disk.getJSONObject("server").getBoolean("loopbackOnly"), "committed restore was not retained");
    }
}
