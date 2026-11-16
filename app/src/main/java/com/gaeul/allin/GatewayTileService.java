package com.gaeul.allin;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：下拉面板一键启停网关（与 FlClash 等同类应用的 QS_TILE 做法一致）。
 *
 * 两条硬约束：
 * 1) 开关的唯一真相是 magpie_prefs.gateway_enabled，必须先写它再请求启动，
 *    否则 onStartCommand 里的 isGatewayEnabled 检查会让刚起来的服务立刻 stopSelf。
 * 2) 服务启动/停止是异步的，点完立刻刷新会读到旧状态（表现为"按了没反应"）。
 *    因此点击后按固定节奏轮询实际状态，必要时用透明 Activity 兜底前台申请。
 */
public class GatewayTileService extends TileService {
    private static final String PREFS = "magpie_prefs";
    private static final String KEY_ENABLED = "gateway_enabled";
    private static final long POLL_MS = 400L;
    private static final int MAX_POLLS = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;

    @Override public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override public void onDestroy() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        final boolean enable = !GatewayService.isGatewayEnabled(this);
        setGatewayEnabled(this, enable);
        final int gen = ++generation;
        updateTile();
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
                if (gen != generation) return;
                boolean running = GatewayService.isRunning();
                updateTile();
                if (running == enable) return;
                // 启动迟迟不生效：可能是后台启动前台服务被系统拒绝，改用透明 Activity 兜底。
                if (enable && attempt == 3) launchFallback();
                if (!enable && attempt == 3) {
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
        } catch (Throwable ignored) {}
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = GatewayService.isGatewayEnabled(this);
        boolean running = GatewayService.isRunning();
        tile.setState(enabled && running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        try { tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_gateway)); }
        catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                tile.setSubtitle(enabled && running ? "运行中 · 3425" : (enabled ? "启动中…" : "已停止"));
            } catch (Throwable ignored) {}
        }
        tile.updateTile();
    }
}
