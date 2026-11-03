package com.harness.phone.v2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tsnetmobile.Client;

/**
 * Process-wide owner of the embedded userspace Tailscale node. No Android
 * VpnService is used; tsnet keeps its identity under filesDir/tsnet.
 */
public final class TsnetManager {
    public interface StatusCallback {
        void onStatus(String json, Exception error);
    }

    private static TsnetManager instance;
    private static volatile String cachedStatus = "{\"state\":\"Stopped\"}";

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Client client;
    private boolean starting;

    private TsnetManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized TsnetManager get(Context context) {
        if (instance == null) instance = new TsnetManager(context);
        return instance;
    }

    public String cachedStatus() {
        return cachedStatus;
    }

    private void cache(String json) {
        if (json != null && !json.isEmpty()) cachedStatus = json;
    }

    private synchronized Client client() throws Exception {
        if (client == null) {
            File dir = new File(context.getFilesDir(), "tsnet");
            client = new Client(dir.getAbsolutePath(), "deepseek-harness-2");
        }
        return client;
    }

    /** Start/resume the saved node. An empty key uses saved identity or asks for interactive login. */
    public void start(StatusCallback callback) {
        worker.execute(() -> {
            Exception failure = null;
            try {
                Log.d("TsnetManager", "starting embedded client");
                client().start("");
                Log.d("TsnetManager", "embedded client start returned");
            } catch (Exception error) {
                failure = error;
                Log.e("TsnetManager", "embedded client start failed", error);
            }
            final Exception result = failure;
            main.post(() -> {
                if (result != null && callback != null) callback.onStatus(null, result);
                else status(callback);
            });
        });
    }

    public void status(StatusCallback callback) {
        worker.execute(() -> {
            String json = null;
            Exception failure = null;
            try {
                json = client().statusJSON();
            } catch (Exception error) {
                failure = error;
                Log.e("TsnetManager", "status failed", error);
            }
            final String value = json;
            final Exception error = failure;
            if (value != null) cache(value);
            main.post(() -> {
                if (callback != null) callback.onStatus(value, error);
            });
        });
    }

    public void proxyUrl(String target, ProxyCallback callback) {
        worker.execute(() -> {
            String url = null;
            Exception failure = null;
            try {
                Log.d("TsnetManager", "proxy start target=" + target);
                url = client().proxyURL(target);
                Log.d("TsnetManager", "proxy ready url=" + url);
            } catch (Exception error) {
                failure = error;
                Log.e("TsnetManager", "proxy failed", error);
            }
            final String value = url;
            final Exception error = failure;
            main.post(() -> callback.onProxy(value, error));
        });
    }

    public void logout(StatusCallback callback) {
        worker.execute(() -> {
            Exception failure = null;
            try {
                if (client != null) client.logout();
                client = null;
            } catch (Exception error) {
                failure = error;
            }
            final Exception result = failure;
            main.post(() -> {
                if (result != null && callback != null) callback.onStatus(null, result);
                else status(callback);
            });
        });
    }

    public void shutdown() {
        worker.execute(() -> {
            try {
                if (client != null) client.stop();
            } catch (Exception ignored) {
            }
        });
    }

    public interface ProxyCallback {
        void onProxy(String url, Exception error);
    }
}
