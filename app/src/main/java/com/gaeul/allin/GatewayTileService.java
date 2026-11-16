package com.gaeul.allin;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.io.File;
import java.io.FileWriter;

/**
 * 快捷设置磁贴：下拉面板一键启停网关（与 FlClash 等同类应用的 QS_TILE 做法一致）。
 *
 * 硬约束：
 * 1) 开关的唯一真相是 magpie_prefs.gateway_enabled，必须先写它再请求启动，
 *    否则 onStartCommand 里的 isGatewayEnabled 检查会让刚起来的服务立刻 stopSelf。
 * 2) 服务启停是异步的，点完立即刷新会读到旧状态（表现为"按了没反应"）。
 *    因此点击后按 400ms 轮询真实运行状态，并且在 getQsTile 为空时主动请求监听回填。
 * 3) 只有当 GatewayService 明确记录到启动失败（startError 非空）时才走 Activity 兜底，
 *    避免"启动稍慢"被误判成失败而把面板收起。
 */
public class GatewayTileService extends TileService {
    private static final String PREFS = "magpie_prefs";
    private static final String KEY_ENABLED = "gateway_enabled";
    private static final long POLL_MS = 400L;
    private static final int MAX_POLLS = 15;
    private static final String LOG = "tile.log";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean fallbackUsed;

    @Override public void onTileAdded() {
        super.onTileAdded();
        log("onTileAdded");
        updateTile();
    }

    @Override public void onStartListening() {
        super.onStartListening();
        boolean enabled = GatewayService.isGatewayEnabled(this);
        boolean running = GatewayService.isRunning();
        log("onStartListening enabled=" + enabled + " running=" + running);
        updateTile();
    }

    @Override public void onStopListening() {
        log("onStopListening");
        super.onStopListening();
    }

    @Override public void onDestroy() {
        log("onDestroy (gen=" + generation + ")");
        generation++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        final boolean enable = !GatewayService.isGatewayEnabled(this);
        log("onClick -> enable=" + enable + " runningBefore=" + GatewayService.isRunning());
        fallbackUsed = false;
        setGatewayEnabled(this, enable);
        final int gen = ++generation;
        handler.post(new Runnable() {
            @Override public void run() { updateTile(); }
        });
        poll(enable, gen, 0);
    }

    /** 磁贴与面板共用同一份状态。 */
    static void setGatewayEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            GatewayService.requestHealthCheck(app, "quicksettings");
        } else {
            KeepAliveScheduler.cancel(app);
            try { app.stopService(new Intent(app, GatewayService.class)); }
            catch (Throwable ignored) {}
        }
    }

    private void poll(final boolean enable, final int gen, final int attempt) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != generation) { log("poll abort gen=" + gen + " now=" + generation); return; }
                boolean running = GatewayService.isRunning();
                String err = GatewayService.lastStartError();
                log("poll#" + attempt + " enable=" + enable + " running=" + running + " err=" + err);
                updateTile();
                if (running == enable) return;
                if (enable) {
                    // 旧实例可能还在优雅退出，把请求投进去会被丢弃；这里按时重试强制启动。
                    if (attempt == 2 || attempt == 6 || attempt == 10) {
                        log("forceStart attempt=" + attempt);
                        GatewayService.forceStart(GatewayTileService.this);
                    }
                    err = GatewayService.lastStartError();
                    if (!fallbackUsed && attempt >= 3 && err != null && !err.isEmpty()) {
                        fallbackUsed = true;
                        log("start error recorded (" + err + "), launching fallback activity");
                        launchFallback();
                    }
                } else if (attempt == 3) {
                    try { stopService(new Intent(GatewayTileService.this, GatewayService.class)); }
                    catch (Throwable ignored) {}
                }
                if (attempt < MAX_POLLS) poll(enable, gen, attempt + 1);
            }
        }, POLL_MS);
    }

    private void launchFallback() {
        Intent intent = new Intent(this, TileLaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, flags));
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Throwable t) { log("fallback failed " + t.getClass().getSimpleName()); }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        boolean enabled = GatewayService.isGatewayEnabled(this);
        boolean running = GatewayService.isRunning();
        if (tile == null) {
            log("updateTile skipped: tile==null (enabled=" + enabled + " running=" + running + ")");
            // 面板不在监听态时主动请求一次监听，由 onStartListening 回填真实状态。
            try {
                TileService.requestListeningState(this,
                        new ComponentName(this, GatewayTileService.class));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            tile.setState(enabled && running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_gateway));
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(enabled && running ? "运行中 · 3425" : (enabled ? "启动中…" : "已停止"));
            }
            tile.updateTile();
            log("updateTile state=" + (enabled && running ? "ACTIVE" : "INACTIVE"));
        } catch (Throwable t) {
            log("updateTile threw " + t.getClass().getSimpleName());
        }
    }

    private void log(String s) {
        try {
            File f = new File(getFilesDir(), LOG);
            if (f.length() > 128 * 1024) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }
}
