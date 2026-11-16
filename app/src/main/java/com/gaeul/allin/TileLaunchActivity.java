package com.gaeul.allin;

import android.app.Activity;
import android.os.Bundle;

/**
 * 磁贴启动兜底：当直接从 TileService 申请前台服务被系统拒绝（后台启动限制）时，
 * 由一个透明 Activity 把进程短暂带到前台再申请，Activity 自身不可见、立即结束。
 */
public class TileLaunchActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { GatewayService.requestHealthCheck(this, "tileFallback"); }
        catch (Throwable ignored) {}
        finish();
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }
}
