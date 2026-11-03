package com.harness.phone.v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Keeps the embedded userspace Tailscale node alive while the app is in the
 * background. It is still not an Android VPN service: no VpnService or tun
 * interface is created.
 */
public class TsnetService extends Service {
    private static final String CHANNEL = "harness_tsnet";
    private static final int NOTIFICATION_ID = 2202;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            TsnetManager.get(TsnetService.this).status((json, error) -> {
                if (!polling) return;
                if (error == null && json != null) updateNotification(json);
                handler.postDelayed(poll, 2000);
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "HARNESS Tailscale", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持内置 Tailscale 用户态节点在线");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground("正在连接 Tailscale…");
        polling = true;
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 1000);
        TsnetManager.get(this).start((json, error) -> {
            if (error != null) {
                startInForeground("Tailscale 连接失败");
                return;
            }
            if (json != null) updateNotification(json);
        });
        return START_STICKY;
    }

    private void updateNotification(String json) {
        String state = "";
        String account = "";
        try {
            JSONObject object = new JSONObject(json);
            state = object.optString("state", "");
            account = object.optString("account", "");
        } catch (Exception ignored) {
        }
        if ("Running".equals(state)) {
            startInForeground(account.isEmpty() ? "Tailscale 已连接" : "Tailscale 已连接 · " + account);
        } else {
            startInForeground("等待 Tailscale 登录…");
        }
    }

    private void startInForeground(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setContentTitle("DSH Phone Assistant")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        polling = false;
        handler.removeCallbacks(poll);
        TsnetManager.get(this).shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
