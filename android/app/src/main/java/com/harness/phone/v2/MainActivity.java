package com.harness.phone.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String TAG = "HarnessPhone";
    private static final String PREFS = "harness_phone";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_CODE = "code";

    private static final int BG = 0xFF151517;
    private static final int SURFACE = 0xFF1C1C20;
    private static final int SURFACE_2 = 0xFF26262C;
    private static final int BORDER = 0xFF303036;
    private static final int TEXT = 0xFFECECF1;
    private static final int MUTED = 0xFF9B9BA6;
    private static final int FAINT = 0xFF6F6F7A;
    private static final int ACCENT = 0xFF4D6BFE;
    private static final int ACCENT_2 = 0xFF7D3CFF;
    private static final int DANGER = 0xFFFF7A7A;
    private static final int OK = 0xFF57D89A;

    private static final int REQ_FILE = 7001;
    private static final int REQ_CONNECTION = 7002;
    private static final boolean WEBVIEW_DEBUG = false;

    // Automatic connection pipeline. Every path into the app (cold start,
    // resume, shell callback, watchdog, user tap) goes through the same
    // single-flight state machine so the phone never starts two connections
    // at once and never restarts a node that is already coming up.
    private static final int CONNECT_IDLE = 0;
    private static final int CONNECT_NODE = 1;     // bringing the embedded node up
    private static final int CONNECT_RUNNING = 2;  // waiting for the node to be Running
    private static final int CONNECT_PROXY = 3;    // building the local proxy
    private static final int CONNECT_LOADING = 4;  // loading the desktop page
    private static final int CONNECT_READY = 5;

    // Poll the node state quickly at first: the node is often up in a few
    // hundred milliseconds, and a flat 700 ms poll threw that away.
    private static final long[] CONNECT_POLL_BACKOFF = {120, 220, 350, 550, 800, 1100};
    private static final int CONNECT_MAX_POLLS = 60;
    // A wrong configuration is not a reason to give up: the phone keeps trying
    // in the background, so fixing the setting on either side is enough to get
    // back online. The backoff grows but stays short.
    private static final long CONNECT_RETRY_MIN_MS = 1500;
    private static final long CONNECT_RETRY_MAX_MS = 8000;
    private static final int CONNECT_PAIR_PROBE_TIMEOUT_MS = 4000;
    private static final long CONNECT_WATCHDOG_MS = 2500;

    private static final String KEY_TS_TARGET = "ts_target";
    private static final String KEY_TS_CODE = "ts_pair_code";
    private static final String KEY_THEME = "theme_pref";
    private static final String KEY_DOWNLOAD_INDEX = "download_index";
    private static final String KEY_UI_LANGUAGE = "ui_language";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_USE_TSNET = "ts_use_tsnet";

    private FrameLayout root;
    private WebView webView;
    private LinearLayout setupView;
    private LinearLayout errorView;
    private TextView statusView;
    private TextView errorTitle;
    private TextView errorDetail;
    private EditText hostInput;
    private EditText codeInput;
    private Button connectButton;
    private TextView bubble;
    private SharedPreferences prefs;
    private final Map<String, String> downloadedUris = new ConcurrentHashMap<>();
    private final Map<String, String> downloadedMimes = new ConcurrentHashMap<>();

    private String host = "";
    private int port = 8765;
    private String pairingCode = "";
    private boolean pageReady = false;
    private boolean firstReady = true;
    private boolean tsAuthOpened = false;
    private boolean pendingAutoConnect = false;
    private boolean activityResumed = false;
    private boolean offlineShellActive = false;
    private long lastJsResponse = 0;
    private long lastAutoReconnect = 0;

    // --- automatic connection state -----------------------------------------
    private final Handler connectHandler = new Handler(Looper.getMainLooper());
    private int connectStage = CONNECT_IDLE;
    private boolean connectBusy = false;
    private int connectPoll = 0;
    private int connectFailures = 0;
    private boolean connectBlocked = false;
    private int connectRetry = 0;
    private long retryNotBefore = 0;
    private boolean manualConnect = false;
    private long connectStartedAt = 0;
    private long lastConnectKick = 0;
    private String connectTarget = "";
    private String connectCode = "";
    private String proxyTarget = "";
    private String proxyUrl = "";
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable shellAutoTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (activityResumed && !pageReady && webView != null
                        && (errorView == null || errorView.getVisibility() != View.VISIBLE)) {
                    // Watchdog only: the pipeline itself is single flight, so
                    // this can never start a second connection.
                    beginAutoConnect("watchdog", false);
                }
            } finally {
                watchdogHandler.postDelayed(this, 1000);
            }
        }
    };
    private final Runnable livenessCheck = new Runnable() {
        @Override
        public void run() {
            try {
                if (activityResumed && pageReady && webView != null && !isOfflineShell()) {
                    long now = System.currentTimeMillis();
                    if (lastJsResponse > 0 && now - lastJsResponse > 8000) {
                        autoReconnect("页面无响应");
                    } else {
                        webView.evaluateJavascript("'pong'", value -> {
                            if (value != null) lastJsResponse = System.currentTimeMillis();
                        });
                    }
                }
            } finally {
                watchdogHandler.postDelayed(this, 2000);
            }
        }
    };
    private String offlineMessage = "";
    private int readyAttempts = 0;
    private ValueCallback<Uri[]> fileCallback;
    private float bubbleDownX;
    private float bubbleDownY;
    private float bubbleStartX;
    private float bubbleStartY;
    private boolean bubbleDragged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadDownloadIndex();
        configureWindow();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildWebView();
        buildSetupView();
        buildErrorView();
        buildBubble();
        setContentView(root);
        lastJsResponse = System.currentTimeMillis();
        watchdogHandler.postDelayed(livenessCheck, 2000);
        watchdogHandler.postDelayed(shellAutoTick, 1000);

        setupView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        bubble.setVisibility(View.GONE);

        host = prefs.getString(KEY_HOST, "");
        port = prefs.getInt(KEY_PORT, 8765);
        pairingCode = prefs.getString(KEY_CODE, "");

        String tsTarget = prefs.getString(KEY_TS_TARGET, "");
        String tsCode = prefs.getString(KEY_TS_CODE, "");
        boolean useTsnet = prefs.getBoolean(KEY_USE_TSNET, false)
                && !TextUtils.isEmpty(tsTarget) && !TextUtils.isEmpty(tsCode);
        if (useTsnet) {
            pairingCode = tsCode;
            hostInput.setText(tsTarget);
            codeInput.setText(tsCode);
        } else if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairingCode)) {
            hostInput.setText(host + ":" + port);
            codeInput.setText(pairingCode);
        }
        boolean hasConfig = useTsnet || (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairingCode));
        loadOfflineShell(hasConfig ? "正在连接电脑…" : "");
        if (hasConfig) {
            connectBlocked = false;
            connectFailures = 0;
            // Do not wait for the shell to call back: every millisecond of the
            // shell round trip is dead time the user sits through.
            connectHandler.postDelayed(() -> beginAutoConnect("startup", false), 0);
        }
        pendingAutoConnect = false;
    }

    private void runPendingAutoConnect() {
        if (!pendingAutoConnect) return;
        pendingAutoConnect = false;
        autoConnectSaved();
    }

    private void autoConnectSaved() {
        String tsTarget = prefs.getString(KEY_TS_TARGET, "");
        String tsCode = prefs.getString(KEY_TS_CODE, "");
        if (prefs.getBoolean(KEY_USE_TSNET, false)
                && !TextUtils.isEmpty(tsTarget) && !TextUtils.isEmpty(tsCode)) {
            startTsnetConnect(false);
        } else if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairingCode)) {
            startConnect(false);
        }
    }

    private void attemptShellAutoConnect() {
        beginAutoConnect("shell", false);
    }

    // ---------------------------------------------------------------------
    // Automatic connection pipeline
    //
    // Cold start used to wait for the offline shell to call back, then tick
    // once a second, and restart the embedded node every time a start took
    // longer than twelve seconds. The phone therefore sat on a blank shell
    // for seconds and could fight itself. The pipeline below is single
    // flight, starts at once, reuses a node that is already up, and keeps a
    // cached proxy so a reconnect does not rebuild it.
    // ---------------------------------------------------------------------

    /** Start (or restart) the automatic connection. */
    private void beginAutoConnect(String reason, boolean force) {
        if (webView == null) return;
        if (pageReady && !force) return;
        if (connectBlocked && !force) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastConnectKick < CONNECT_WATCHDOG_MS) return;
        if (connectBusy && !force) return;
        // A pending retry owns the schedule; do not start an extra attempt in
        // the middle of its backoff.
        if (!force && now < retryNotBefore) return;
        lastConnectKick = now;
        connectBusy = true;
        connectPoll = 0;
        connectStartedAt = now;
        connectHandler.removeCallbacksAndMessages(null);
        if (force) {
            // A user asked for it: give the pipeline a clean slate.
            connectBlocked = false;
            connectFailures = 0;
            if (!"retry".equals(reason)) {
                connectRetry = 0;
                retryNotBefore = 0;
            }
        }
        if (!"manual".equals(reason) && !"setup".equals(reason)) manualConnect = false;

        String tsTarget = prefs.getString(KEY_TS_TARGET, "");
        String tsCode = prefs.getString(KEY_TS_CODE, "");
        boolean useTsnet = prefs.getBoolean(KEY_USE_TSNET, false)
                && !TextUtils.isEmpty(tsTarget) && !TextUtils.isEmpty(tsCode);
        if (useTsnet) {
            connectTarget = tsTarget;
            connectCode = tsCode;
            Log.d(TAG, "auto connect via tsnet: " + reason + " -> " + tsTarget);
            startTsnetPipeline();
            return;
        }
        if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairingCode)) {
            connectTarget = host.contains(":") ? host : host + ":" + port;
            connectCode = pairingCode;
            Log.d(TAG, "auto connect direct: " + reason + " -> " + connectTarget);
            startDirectPipeline();
            return;
        }
        connectBusy = false;
        connectStage = CONNECT_IDLE;
        // Nothing is configured yet: leave a visible error alone, the user has
        // to decide what to do next.
        if (errorView == null || errorView.getVisibility() != View.VISIBLE) showSetup();
    }

    /** LAN / Tailscale-IP direct path: check the bridge before touching the UI. */
    private void startDirectPipeline() {
        connectStage = CONNECT_LOADING;
        setStatusInfo("正在连接电脑…");
        updateOfflineShell("正在连接 " + connectTarget + "…");
        probePairing(directBaseUrl(), connectCode);
    }

    /**
     * Ask the bridge whether the pairing code is good before the WebView is
     * pointed at it. A wrong code used to load the bridge's error page into
     * the WebView, which threw away whatever the user was doing in the
     * settings and bounced the app back to the offline home screen.
     */
    private void probePairing(final String base, final String code) {
        new Thread(() -> {
            int status = 0;
            String failure = "";
            long retryAfterMs = 0;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(base + "/pair?code=" + Uri.encode(code));
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_PAIR_PROBE_TIMEOUT_MS);
                connection.setReadTimeout(CONNECT_PAIR_PROBE_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "DSH-Phone-Bridge");
                status = connection.getResponseCode();
                // The bridge throttles wrong codes and says how long to wait.
                String retryAfter = connection.getHeaderField("retry-after");
                if (!TextUtils.isEmpty(retryAfter)) {
                    try {
                        retryAfterMs = Long.parseLong(retryAfter.trim()) * 1000L;
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception error) {
                failure = error.getMessage() == null ? "" : error.getMessage();
            } finally {
                if (connection != null) connection.disconnect();
            }
            final int code0 = status;
            final String detail = failure;
            final long throttle = retryAfterMs;
            connectHandler.post(() -> {
                if (!connectBusy) return;
                if (code0 == 302 || code0 == 301 || code0 == 307 || code0 == 308) {
                    // The code is right: now it is safe to move the WebView.
                    connectRetry = 0;
                    navigateToBridge(base);
                    return;
                }
                if (code0 == 403) {
                    scheduleRetry("配对码不正确，正在重试…", throttle);
                    return;
                }
                if (code0 == 429) {
                    scheduleRetry("配对请求太快，稍后自动重试…", throttle);
                    return;
                }
                if (code0 > 0) {
                    scheduleRetry("电脑返回了 " + code0 + "，正在重试…", 0);
                    return;
                }
                scheduleRetry("连不上电脑，正在重试…" + (detail.isEmpty() ? "" : "（" + detail + "）"), 0);
            });
        }, "hp-pair-probe").start();
    }

    /** Point the WebView at the bridge. Only called once the code checked out. */
    private void navigateToBridge(String base) {
        connectStage = CONNECT_LOADING;
        setStatusInfo("正在打开电脑页面…");
        updateOfflineShell("正在打开电脑页面…");
        preparePageLoad();
        webView.loadUrl(base + "/pair?code=" + Uri.encode(connectCode) + "&hpv=2");
        scheduleReadyCheck(400);
    }

    private String directBaseUrl() {
        String hostPart = connectTarget.contains(":") ? "[" + connectTarget + "]" : connectTarget;
        return "http://" + hostPart + ":" + port;
    }

    /**
     * Keep trying, but never take the screen away from the user: the current
     * page stays exactly where it is and only the status line changes.
     */
    private void scheduleRetry(String message) {
        scheduleRetry(message, 0);
    }

    private void scheduleRetry(String message, long minWaitMs) {
        connectRetry++;
        long wait = Math.min(CONNECT_RETRY_MIN_MS * (1L << Math.min(connectRetry - 1, 4)), CONNECT_RETRY_MAX_MS);
        if (minWaitMs > wait) wait = Math.min(minWaitMs, CONNECT_RETRY_MAX_MS * 2L);
        retryNotBefore = System.currentTimeMillis() + wait;
        connectBusy = false;
        connectStage = CONNECT_IDLE;
        connectHandler.removeCallbacksAndMessages(null);
        Log.w(TAG, "connect retry " + connectRetry + " in " + wait + "ms: " + message);
        if (!isOfflineShell()) {
            // We are sitting on some page the bridge served (its error page,
            // say). Go back to the shell once, so the user can fix the setup.
            loadOfflineShell(message);
        } else {
            // Staying on the current page is the whole point: the settings the
            // user is editing must survive the retry.
            updateOfflineShell(message + "（第 " + connectRetry + " 次）");
        }
        connectHandler.postDelayed(() -> beginAutoConnect("retry", true), wait);
    }

    /** Embedded Tailscale path: node up, then local proxy, then the page. */
    private void startTsnetPipeline() {
        connectStage = CONNECT_NODE;
        setStatusInfo("正在恢复 Tailscale 登录…");
        updateOfflineShell("正在恢复 Tailscale 登录…");
        tsAuthOpened = false;
        try {
            startForegroundService(new Intent(this, TsnetService.class));
        } catch (Exception ignored) {
        }
        // Ask for the state first: the node is often still alive from the
        // foreground service, and then the slow start can be skipped.
        TsnetManager.get(this).status((json, error) -> {
            if (!connectBusy) return;
            if (isTsnetRunning(json)) {
                startProxyStage();
                return;
            }
            TsnetManager.get(this).start((started, startError) -> {
                if (!connectBusy) return;
                if (startError != null) {
                    failConnect("Tailscale 启动失败",
                            startError.getMessage() == null ? "无法启动内置 Tailscale" : startError.getMessage());
                    return;
                }
                pollForRunning(0);
            });
        });
    }

    private void pollForRunning(int index) {
        if (!connectBusy) return;
        connectStage = CONNECT_RUNNING;
        TsnetManager.get(this).status((json, error) -> {
            if (!connectBusy) return;
            if (isTsnetRunning(json)) {
                startProxyStage();
                return;
            }
            String authUrl = tsnetAuthUrl(json);
            if (!TextUtils.isEmpty(authUrl) && !tsAuthOpened) {
                tsAuthOpened = true;
                setStatusInfo("需要登录 Tailscale，正在打开登录页…");
                updateOfflineShell("需要登录 Tailscale，正在打开登录页…");
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                } catch (Exception ignored) {
                }
            }
            connectPoll++;
            if (connectPoll >= CONNECT_MAX_POLLS) {
                failConnect("Tailscale 连接超时", "已保留登录状态，但 Tailscale 节点暂时没有就绪。请稍后重试。");
                return;
            }
            int slot = Math.min(index, CONNECT_POLL_BACKOFF.length - 1);
            connectHandler.postDelayed(() -> pollForRunning(index + 1), CONNECT_POLL_BACKOFF[slot]);
        });
    }

    private void startProxyStage() {
        connectStage = CONNECT_PROXY;
        setStatusInfo("正在建立 Tailscale 本地代理…");
        updateOfflineShell("正在建立 Tailscale 本地代理…");
        if (!TextUtils.isEmpty(proxyUrl) && proxyTarget.equals(connectTarget)) {
            loadDesktopPage(proxyUrl);
            return;
        }
        TsnetManager.get(this).proxyUrl(connectTarget, (url, error) -> {
            if (!connectBusy) return;
            if (error != null || TextUtils.isEmpty(url)) {
                failConnect("Tailscale 代理失败", error == null ? "无法建立本地代理" : error.getMessage());
                return;
            }
            proxyTarget = connectTarget;
            proxyUrl = url;
            loadDesktopPage(url);
        });
    }

    private void loadDesktopPage(String base) {
        connectStage = CONNECT_LOADING;
        setStatusInfo("正在打开电脑页面…");
        updateOfflineShell("正在打开电脑页面…");
        probePairing(base, connectCode);
    }

    private void preparePageLoad() {
        pageReady = false;
        firstReady = true;
        readyAttempts = 0;
        errorView.setVisibility(View.GONE);
        bubble.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    /**
     * A failed step is never fatal: the phone keeps trying in the background.
     * The screen is left alone, only the status line changes.
     */
    private void failConnect(String title, String detail) {
        if (manualConnect) {
            Toast.makeText(this, title + (TextUtils.isEmpty(detail) ? "" : "\n" + detail), Toast.LENGTH_LONG).show();
        }
        scheduleRetry(title + "，正在重试…");
    }

    private void cancelAutoConnect() {
        connectBusy = false;
        connectStage = CONNECT_IDLE;
        connectPoll = 0;
        connectFailures = 0;
        connectHandler.removeCallbacksAndMessages(null);
    }

    private static boolean isTsnetRunning(String json) {
        if (json == null) return false;
        try {
            return "Running".equals(new JSONObject(json).optString("state", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String tsnetAuthUrl(String json) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString("authUrl", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        window.getDecorView().setSystemUiVisibility(0);
    }

    private void setSystemBars(boolean dark) {
        Window window = getWindow();
        int color = dark ? BG : 0xFFFFFFFF;
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (dark) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Toast.makeText(this, "此下载不支持在手机端保存", Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        enqueueDownload(url, fileName, mimeType, userAgent);
    }

    private void enqueueDownload(String url, String fileName, String mimeType, String userAgent) {
        enqueueDownload(url, fileName, mimeType, userAgent, false);
    }

    private void enqueueDownload(String url, String fileName, String mimeType,
                                 String userAgent, boolean sessionLog) {
        if (TextUtils.isEmpty(fileName)) fileName = "dsh-session.zip";
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (TextUtils.isEmpty(fileName)) fileName = "dsh-session.zip";
        if (TextUtils.isEmpty(mimeType)) mimeType = "application/octet-stream";
        final String safeName = fileName;
        final String safeMime = mimeType;
        final String safeAgent = TextUtils.isEmpty(userAgent) ? "HarnessPhone" : userAgent;
        String where = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? "手机「下载」目录 / " + safeName
            : "App 的 Download 目录 / " + safeName;
        Toast.makeText(this, "已开始下载：" + where, Toast.LENGTH_LONG).show();
        Log.d(TAG, "download start " + url + " as " + safeName);
        new Thread(() -> downloadToStorage(url, safeName, safeMime, safeAgent, sessionLog),
                "harness-download").start();
    }

    private void downloadToStorage(String url, String fileName, String mimeType,
                                   String userAgent, boolean sessionLog) {
        HttpURLConnection connection = null;
        Uri pendingUri = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            Log.d(TAG, "download cookie length=" + (cookie == null ? 0 : cookie.length()));
            if (!TextUtils.isEmpty(cookie)) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                pendingUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (pendingUri == null) throw new java.io.IOException("无法创建下载文件");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = getContentResolver().openOutputStream(pendingUri)) {
                    if (output == null) throw new java.io.IOException("无法写入下载文件");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(pendingUri, done, null, null);
                String actualName = fileName;
                try (android.database.Cursor cursor = getContentResolver().query(
                        pendingUri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String display = cursor.getString(0);
                        if (!TextUtils.isEmpty(display)) actualName = display;
                    }
                } catch (Exception ignored) {}
                rememberDownload(fileName, actualName, pendingUri.toString(), mimeType);
                final String where = "手机「下载」目录 / " + actualName;
                runOnUiThread(() -> {
                    Toast.makeText(this, "下载完成：" + where, Toast.LENGTH_LONG).show();
                    if (sessionLog) showDownloadResult(true, where, null);
                });
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建下载目录");
                File target = new File(dir, fileName);
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                rememberDownload(fileName, fileName, HarnessFileProvider.uriFor(target).toString(), mimeType);
                final String where = target.getAbsolutePath();
                runOnUiThread(() -> {
                    Toast.makeText(this, "下载完成：" + where, Toast.LENGTH_LONG).show();
                    if (sessionLog) showDownloadResult(true, where, null);
                });
            }
        } catch (Exception error) {
            Log.e(TAG, "download failed", error);
            if (pendingUri != null) {
                try { getContentResolver().delete(pendingUri, null, null); } catch (Exception ignored) {}
            }
            final String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            runOnUiThread(() -> {
                Toast.makeText(this, "下载失败：" + reason, Toast.LENGTH_LONG).show();
                if (sessionLog) showDownloadResult(false, null, reason);
            });
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void rememberDownload(String requestedName, String actualName, String uri, String mime) {
        String mimeType = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        if (!TextUtils.isEmpty(requestedName)) {
            downloadedUris.put(requestedName, uri);
            downloadedMimes.put(requestedName, mimeType);
        }
        if (!TextUtils.isEmpty(actualName)) {
            downloadedUris.put(actualName, uri);
            downloadedMimes.put(actualName, mimeType);
        }
        saveDownloadIndex();
    }

    private void loadDownloadIndex() {
        try {
            String raw = prefs.getString(KEY_DOWNLOAD_INDEX, "{}");
            JSONObject index = new JSONObject(raw);
            java.util.Iterator<String> keys = index.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject item = index.optJSONObject(name);
                if (item == null) continue;
                String uri = item.optString("uri", "");
                String mime = item.optString("mime", guessMime(name));
                if (!TextUtils.isEmpty(uri)) {
                    downloadedUris.put(name, uri);
                    downloadedMimes.put(name, mime);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveDownloadIndex() {
        try {
            JSONObject index = new JSONObject();
            for (Map.Entry<String, String> entry : downloadedUris.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("uri", entry.getValue());
                item.put("mime", downloadedMimes.get(entry.getKey()));
                index.put(entry.getKey(), item);
            }
            prefs.edit().putString(KEY_DOWNLOAD_INDEX, index.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void showDownloadResult(boolean success, String where, String reason) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(success ? "会话日志下载完成" : "会话日志下载失败")
            .setMessage(success ? "已保存到：\n" + where : "原因：" + reason)
            .setNegativeButton("知道了", null);
        if (success) {
            builder.setPositiveButton("打开下载目录", (dialog, which) -> {
                try {
                    startActivity(new Intent("android.intent.action.VIEW_DOWNLOADS"));
                } catch (Exception ignored) {
                    Toast.makeText(this, "请打开手机的「文件」或「下载」应用查看", Toast.LENGTH_LONG).show();
                }
            });
        }
        builder.show();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable bordered(int color, float radiusDp, int borderColor, float borderDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(borderDp), borderColor);
        return drawable;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1.15f);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private void buildWebView() {
        WebView.setWebContentsDebuggingEnabled(WEBVIEW_DEBUG);
        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(params);
        webView.setVisibility(View.GONE);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        // Android's force-dark / algorithmic darkening re-colours a page it does
        // not understand, and text selection is the first thing it breaks: the
        // highlight gets painted with the system accent while the glyphs stay
        // light, so a selection reads as a solid blue block. The DSH page
        // carries its own themes, so always opt out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        settings.setUserAgentString(settings.getUserAgentString() + " HarnessPhone/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.addJavascriptInterface(new NativeBridge(), "HarnessNative");
        webView.setDownloadListener(this::handleDownload);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    pageReady = false;
                    offlineShellActive = false;
                } else if (url != null && url.startsWith("file:///android_asset/offline/")) {
                    offlineShellActive = true;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || url.startsWith("about:")) return;
                if (url.startsWith("file:///android_asset/offline/")) {
                    offlineShellActive = true;
                    updateOfflineShell(offlineMessage);
                    return;
                }
                offlineShellActive = false;
                readyAttempts = 0;
                scheduleReadyCheck(1200);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Uri uri = request.getUrl();
                    if (uri != null && "file".equals(uri.getScheme())) return;
                    showConnectionError(error.getErrorCode(), String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int code = response.getStatusCode();
                if (code == 401 || code == 403) {
                    if (manualConnect) {
                        showError("配对没有通过", "配对码可能已改变。请回到电脑端桥接窗口核对 6 位配对码，然后重新配对。");
                        return;
                    }
                    scheduleRetry("配对没有通过，正在重试…");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, "console: " + message.message());
                return true;
            }
        });

        root.addView(webView);
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String targetHost = uri.getHost() == null ? "" : uri.getHost();
        // The tsnet reverse proxy is served on loopback. Keep it inside the
        // WebView; only genuinely external hosts should open Edge/Chrome.
        if (targetHost.equalsIgnoreCase("127.0.0.1")
                || targetHost.equalsIgnoreCase("localhost")
                || targetHost.equals("::1")
                || targetHost.equals("[::1]")) {
            return false;
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && targetHost.equalsIgnoreCase(host)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void buildSetupView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scroll.setLayoutParams(scrollParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(22), dp(34), dp(22), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_whale);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(62), dp(62));
        logoParams.topMargin = dp(10);
        column.addView(logo, logoParams);

        TextView title = text("Deepseek Harness", 28, TEXT, true);
        title.setLetterSpacing(0.01f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(14);
        column.addView(title, titleParams);

        TextView subtitle = text("手机端 · 会话查看与指令", 13, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        column.addView(subtitle, subtitleParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(bordered(SURFACE, 20, BORDER, 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(30);
        column.addView(card, cardParams);

        card.addView(label("电脑地址"));
        hostInput = new EditText(this);
        hostInput.setHint("192.168.1.10:8765");
        hostInput.setHintTextColor(FAINT);
        hostInput.setTextColor(TEXT);
        hostInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        hostInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        hostInput.setBackground(bordered(SURFACE_2, 12, BORDER, 1));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = dp(7);
        card.addView(hostInput, fieldParams);

        TextView codeLabel = label("配对码");
        LinearLayout.LayoutParams codeLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLabelParams.topMargin = dp(16);
        card.addView(codeLabel, codeLabelParams);

        codeInput = new EditText(this);
        codeInput.setHint("6 位数字");
        codeInput.setHintTextColor(FAINT);
        codeInput.setTextColor(TEXT);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        codeInput.setSingleLine(true);
        codeInput.setLetterSpacing(0.25f);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setPadding(dp(13), dp(11), dp(13), dp(11));
        codeInput.setBackground(bordered(SURFACE_2, 12, BORDER, 1));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(7);
        card.addView(codeInput, codeParams);

        statusView = text("", 13, DANGER, false);
        statusView.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        card.addView(statusView, statusParams);

        connectButton = new Button(this);
        connectButton.setText("连接电脑");
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        connectButton.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        connectButton.setAllCaps(false);
        connectButton.setGravity(Gravity.CENTER);
        connectButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable buttonBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT_2});
        buttonBg.setCornerRadius(dp(14));
        connectButton.setBackground(ripple(buttonBg));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(18);
        card.addView(connectButton, buttonParams);
        connectButton.setOnClickListener(v -> attemptConnect());

        Button connectionSettings = new Button(this);
        connectionSettings.setText("使用内置 Tailscale 连接（2.0）");
        connectionSettings.setTextColor(TEXT);
        connectionSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        connectionSettings.setAllCaps(false);
        connectionSettings.setPadding(dp(12), 0, dp(12), 0);
        connectionSettings.setBackground(bordered(SURFACE_2, 12, BORDER, 1));
        LinearLayout.LayoutParams connectionSettingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        connectionSettingsParams.topMargin = dp(10);
        card.addView(connectionSettings, connectionSettingsParams);
        connectionSettings.setOnClickListener(v -> openConnectionSettings());

        codeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptConnect();
                return true;
            }
            return false;
        });

        LinearLayout help = new LinearLayout(this);
        help.setOrientation(LinearLayout.VERTICAL);
        help.setPadding(dp(17), dp(15), dp(17), dp(15));
        help.setBackground(rounded(SURFACE, 16));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpParams.topMargin = dp(20);
        column.addView(help, helpParams);

        TextView helpTitle = text("怎么连接", 13, TEXT, true);
        help.addView(helpTitle);
        help.addView(helpLine("1", "电脑端运行 start-bridge.cmd；已设开机自启时会自动启动。"));
        help.addView(helpLine("2", "在桥接窗口或 show-connection.cmd 查看地址和 6 位配对码。"));
        help.addView(helpLine("3", "手机和电脑连同一个 Wi-Fi，点“连接电脑”。"));
        help.addView(helpLine("4", "在外面：两端登录同一个 Tailscale，地址填 100.x.x.x:8765。"));

        TextView privacy = text("局域网直连；外网用 Tailscale 私有网络，不经过中转服务器。", 12, FAINT, false);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = dp(22);
        column.addView(privacy, privacyParams);

        setupView = new LinearLayout(this);
        setupView.setOrientation(LinearLayout.VERTICAL);
        setupView.setBackgroundColor(BG);
        setupView.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupView.setVisibility(View.VISIBLE);
    }

    private TextView label(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setLetterSpacing(0.03f);
        return view;
    }

    private LinearLayout helpLine(String number, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(9);
        row.setLayoutParams(rowParams);

        TextView badge = text(number, 11, ACCENT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(0xFF2A2D3E, 8));
        row.addView(badge, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView body = text(value, 12, MUTED, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.leftMargin = dp(10);
        row.addView(body, bodyParams);
        return row;
    }

    private void buildErrorView() {
        errorView = new LinearLayout(this);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(22), dp(22), dp(22), dp(22));
        errorView.setBackgroundColor(BG);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(bordered(SURFACE, 20, BORDER, 1));
        errorView.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("连接断开", 22, TEXT, true);
        errorTitle = title;
        card.addView(title);
        errorDetail = text("", 13, MUTED, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(10);
        card.addView(errorDetail, detailParams);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        retry.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, ACCENT_2});
        bg.setCornerRadius(dp(13));
        retry.setBackground(ripple(bg));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        retryParams.topMargin = dp(18);
        card.addView(retry, retryParams);
        retry.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            startConnect(false);
        });

        Button repaint = new Button(this);
        repaint.setText("重新配对");
        repaint.setAllCaps(false);
        repaint.setTextColor(TEXT);
        repaint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        repaint.setBackground(ripple(bordered(SURFACE_2, 13, BORDER, 1)));
        LinearLayout.LayoutParams repaintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        repaintParams.topMargin = dp(10);
        card.addView(repaint, repaintParams);
        repaint.setOnClickListener(v -> {
            clearSession();
            showSetup();
        });

        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorView.setVisibility(View.GONE);
    }

    private void buildBubble() {
        bubble = new TextView(this);
        bubble.setText("⋯");
        bubble.setTextColor(TEXT);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(bordered(0xE61C1C20, 12, 0xFF3B3B44, 1));
        bubble.setElevation(dp(6));
        bubble.setAlpha(0.24f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(24), dp(24));
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(3);
        bubble.setLayoutParams(params);
        bubble.setVisibility(View.GONE);

        bubble.setOnClickListener(v -> showMenu());
        bubble.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bubbleDownX = event.getRawX();
                        bubbleDownY = event.getRawY();
                        bubbleStartX = view.getX();
                        bubbleStartY = view.getY();
                        bubbleDragged = false;
                        view.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - bubbleDownX;
                        float dy = event.getRawY() - bubbleDownY;
                        if (Math.abs(dx) > ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()
                                || Math.abs(dy) > ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()) {
                            bubbleDragged = true;
                        }
                        if (bubbleDragged) {
                            float maxX = root.getWidth() - view.getWidth();
                            float maxY = root.getHeight() - view.getHeight();
                            view.setX(Math.max(0, Math.min(maxX, bubbleStartX + dx)));
                            view.setY(Math.max(0, Math.min(maxY, bubbleStartY + dy)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setAlpha(0.24f);
                        if (!bubbleDragged) view.performClick();
                        return true;
                }
                return false;
            }
        });
        // The refresh/re-pair controls live inside the in-page settings card now.
        // Keep the legacy view unparented so older code paths cannot show it.
    }

    private void showMenu() {
        final Dialog dialog = new Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(12));
        card.setBackground(bordered(SURFACE, 18, BORDER, 1));

        TextView title = text("Deepseek Harness", 17, TEXT, true);
        card.addView(title);
        TextView subtitle = text(host + ":" + port, 12, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(3);
        card.addView(subtitle, subtitleParams);

        addDialogAction(card, dialog, "刷新页面", () -> {
            if (pageReady) webView.reload();
            else startConnect(false);
        });
        addDialogAction(card, dialog, "重新配对", () -> {
            clearSession();
            showSetup();
        });

        TextView cancel = text("取消", 14, MUTED, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(10), dp(12), dp(10), dp(8));
        cancel.setOnClickListener(v -> dialog.dismiss());
        card.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            window.setLayout(Math.max(dp(260), root.getWidth() - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addDialogAction(LinearLayout parent, Dialog dialog, String label, Runnable action) {
        TextView row = text(label, 15, TEXT, false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(13), dp(13), dp(13));
        row.setBackground(ripple(rounded(SURFACE_2, 11)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        parent.addView(row, params);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
    }

    private void attemptConnect() {
        String raw = hostInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            setStatus("请填写电脑地址");
            hostInput.requestFocus();
            return;
        }
        if (!raw.contains("://")) {
            raw = "http://" + raw;
        }
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            setStatus("电脑地址格式不正确");
            return;
        }
        String parsedHost = uri.getHost();
        if (TextUtils.isEmpty(parsedHost)) {
            setStatus("电脑地址格式不正确");
            return;
        }
        int parsedPort = uri.getPort() > 0 ? uri.getPort() : 8765;
        if (TextUtils.isEmpty(code) && uri.getQueryParameter("code") != null) {
            code = uri.getQueryParameter("code").trim();
        }
        if (!code.matches("\\d{6}")) {
            setStatus("配对码应为 6 位数字");
            codeInput.requestFocus();
            return;
        }
        host = parsedHost;
        port = parsedPort;
        pairingCode = code;
        saveConfig();
        startConnect(true);
    }

    private void saveConfig() {
        prefs.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putString(KEY_CODE, pairingCode)
                .apply();
    }

    private void startConnect(boolean fromSetup) {
        if (prefs.getBoolean(KEY_USE_TSNET, false)
                && !TextUtils.isEmpty(prefs.getString(KEY_TS_TARGET, ""))
                && !TextUtils.isEmpty(prefs.getString(KEY_TS_CODE, ""))) {
            startTsnetConnect(fromSetup);
            return;
        }
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(pairingCode)) {
            showSetup();
            return;
        }
        hideKeyboard();
        manualConnect = true;
        if (fromSetup) {
            loadOfflineShell("正在连接 " + host + ":" + port + "…");
        }
        beginAutoConnect("setup", true);
    }

    private void startTsnetConnect(boolean fromSetup) {
        final String target = prefs.getString(KEY_TS_TARGET, "");
        final String code = prefs.getString(KEY_TS_CODE, "");
        if (TextUtils.isEmpty(target) || TextUtils.isEmpty(code)) {
            showSetup();
            return;
        }
        hideKeyboard();
        manualConnect = true;
        if (fromSetup) {
            loadOfflineShell("正在恢复 Tailscale 登录…");
            setStatusInfo("正在恢复 Tailscale 登录…");
        }
        beginAutoConnect("setup", true);
    }

    private void openConnectionSettings() {
        startActivityForResult(new Intent(this, ConnectionSettingsActivity.class), REQ_CONNECTION);
    }

    private void startTailscaleLogin() {
        startForegroundService(new Intent(this, TsnetService.class));
        TsnetManager.get(this).start((json, error) -> {
            if (error != null) {
                Toast.makeText(this, "Tailscale 启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            String state = "";
            try {
                state = new JSONObject(json).optString("state", "");
            } catch (Exception ignored) {
            }
            if ("Running".equals(state)) {
                Toast.makeText(this, "Tailscale 已连接", Toast.LENGTH_SHORT).show();
                return;
            }
            pollTailscaleAuth(0);
        });
    }

    private void pollTailscaleAuth(int attempts) {
        if (attempts > 70) return;
        TsnetManager.get(this).status((json, error) -> {
            if (error != null || json == null) {
                getWindow().getDecorView().postDelayed(() -> pollTailscaleAuth(attempts + 1), 850);
                return;
            }
            String state = "";
            String authUrl = "";
            try {
                JSONObject status = new JSONObject(json);
                state = status.optString("state", "");
                authUrl = status.optString("authUrl", "");
            } catch (Exception ignored) {
            }
            if ("Running".equals(state)) {
                Toast.makeText(this, "Tailscale 已连接", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.isEmpty(authUrl)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                } catch (Exception openError) {
                    Toast.makeText(this, "无法打开 Tailscale 登录页", Toast.LENGTH_LONG).show();
                }
                return;
            }
            getWindow().getDecorView().postDelayed(() -> pollTailscaleAuth(attempts + 1), 850);
        });
    }

    private void saveTailscaleConnection(String target, String code) {
        pendingAutoConnect = false;
        String cleanTarget = target == null ? "" : target.trim();
        String cleanCode = code == null ? "" : code.trim();
        if (TextUtils.isEmpty(cleanTarget)) {
            Toast.makeText(this, "请选择设备或输入 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cleanCode.matches("\\d{6}")) {
            Toast.makeText(this, "配对码应为 6 位数字", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putString(KEY_TS_TARGET, cleanTarget)
                .putString(KEY_TS_CODE, cleanCode)
                .putBoolean(KEY_USE_TSNET, true)
                .apply();
        manualConnect = true;
        cancelAutoConnect();
        beginAutoConnect("manual", true);
    }

    private String buildPairUrl() {
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + hostPart + ":" + port + "/pair?code=" + Uri.encode(pairingCode) + "&hpv=2";
    }

    /** Pair URL for an explicit target, which may already carry a port. */
    private String buildPairUrl(String target, String code) {
        String raw = target == null ? "" : target.trim();
        String hostPart = raw;
        int portPart = port;
        int colon = raw.lastIndexOf(':');
        if (colon > 0 && colon < raw.length() - 1) {
            String maybePort = raw.substring(colon + 1);
            boolean numeric = maybePort.length() > 0;
            for (int i = 0; i < maybePort.length(); i++) {
                if (!Character.isDigit(maybePort.charAt(i))) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) {
                hostPart = raw.substring(0, colon);
                portPart = Integer.parseInt(maybePort);
            }
        }
        String safeHost = hostPart.contains(":") ? "[" + hostPart + "]" : hostPart;
        return "http://" + safeHost + ":" + portPart + "/pair?code=" + Uri.encode(code) + "&hpv=2";
    }

    private void scheduleReadyCheck(long delayMs) {
        webView.postDelayed(() -> {
            if (webView.getVisibility() != View.VISIBLE) return;
            if (isOfflineShell()) return;
            webView.evaluateJavascript(
                    "(function(){try{var r=document.getElementById('root');var t=(document.body.innerText||'').trim();if(t.indexOf('配对码不正确')>=0||t.indexOf('配对请求太快')>=0){return 'BADCODE:'+t.length}return String(t.length)}catch(e){return '0'}})()",
                    value -> {
                        String raw = value == null ? "0" : value.replace("\"", "");
                        if (raw.startsWith("BADCODE:")) {
                            // The bridge rejected the code after all: the page
                            // it served is not the desktop client, so treat this
                            // as a failed attempt and keep the user on the
                            // settings instead of showing the error page.
                            scheduleRetry("配对码不正确，正在重试…");
                            return;
                        }
                        int length = 0;
                        try {
                            length = Integer.parseInt(raw);
                        } catch (Exception ignored) {
                        }
                        if (length > 20) {
                            onConnected();
                        } else if (readyAttempts < 6) {
                            readyAttempts++;
                            scheduleReadyCheck(1600);
                        } else {
                            showError("页面没有显示出来",
                                    "电脑端已经连上，但页面内容为空。可能是手机 WebView 版本过旧，或 HARNESS 刚刚重启。请点“重试”，或升级 Android System WebView。");
                        }
                    });
        }, delayMs);
    }

    private void onConnected() {
        if (pageReady) return;
        pageReady = true;
        offlineShellActive = false;
        manualConnect = false;
        connectStage = CONNECT_READY;
        connectBusy = false;
        connectPoll = 0;
        connectFailures = 0;
        connectHandler.removeCallbacksAndMessages(null);
        readyAttempts = 0;
        lastJsResponse = System.currentTimeMillis();
        setConnectionLost(false);
        errorView.setVisibility(View.GONE);
        setupView.setVisibility(View.GONE);
        bubble.setVisibility(View.VISIBLE);
        connectButton.setEnabled(true);
        connectButton.setText("连接电脑");
        setStatus("");
        if (firstReady) {
            firstReady = false;
            webView.postDelayed(() -> webView.clearHistory(), 800);
        }
    }

    private void showSetup() {
        loadOfflineShell("请配置电脑地址和配对码");
    }

    private boolean isOfflineShell() {
        if (webView == null) return false;
        String url = webView.getUrl();
        return url != null && url.startsWith("file:///android_asset/offline/");
    }

    private void loadOfflineShell(String message) {
        offlineMessage = message == null ? "" : message;
        if (webView == null) return;
        pageReady = false;
        offlineShellActive = true;
        tsAuthOpened = false;
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (bubble != null) bubble.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (isOfflineShell()) {
            updateOfflineShell(offlineMessage);
            return;
        }
        webView.loadUrl("file:///android_asset/offline/index.html");
    }

    private void updateOfflineShell(String message) {
        offlineMessage = message == null ? "" : message;
        if (webView == null || !isOfflineShell()) return;
        webView.evaluateJavascript(
                "window.HarnessOffline && window.HarnessOffline.setMessage(" + JSONObject.quote(offlineMessage) + ")",
                null);
    }

    private void setStatus(String value) {
        setStatus(value, DANGER);
    }

    private void setStatusInfo(String value) {
        setStatus(value, MUTED);
    }

    private void setStatus(String value, int color) {
        if (TextUtils.isEmpty(value)) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(value);
            statusView.setTextColor(color);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    private void showConnectionError(int code, String description) {
        String detail;
        switch (code) {
            case android.webkit.WebViewClient.ERROR_HOST_LOOKUP:
                detail = "找不到这个电脑地址。请检查 IP 是否填对，手机和电脑是否在同一个 Wi-Fi。";
                break;
            case android.webkit.WebViewClient.ERROR_CONNECT:
                detail = "电脑拒绝了连接。请确认电脑端的 start-bridge.cmd 窗口仍在运行，防火墙允许 8765 端口。";
                break;
            case android.webkit.WebViewClient.ERROR_TIMEOUT:
                detail = "连接超时。请确认手机和电脑在同一个 Wi-Fi，且电脑端桥接窗口没有关闭。";
                break;
            default:
                detail = "无法打开电脑上的 HARNESS。请确认桥接窗口运行中，且手机与电脑在同一局域网。\n\n" + description;
                break;
        }
        if (manualConnect) {
            showError("无法连接 HARNESS", detail);
            return;
        }
        // A background attempt: say it in the status line and keep going.
        scheduleRetry("无法连接 HARNESS，正在重试…");
    }

    private void showError(String title, String detail) {
        pageReady = false;
        connectBusy = false;
        connectStage = CONNECT_IDLE;
        setSystemBars(true);
        if (bubble != null) bubble.setVisibility(View.GONE);
        StringBuilder message = new StringBuilder();
        if (!TextUtils.isEmpty(title)) message.append(title);
        if (!TextUtils.isEmpty(detail)) {
            if (message.length() > 0) message.append("\n");
            message.append(detail);
        }
        if (isOfflineShell()) {
            // The shell is on screen already: say it there instead of reloading
            // the page, which would throw away the settings being edited.
            updateOfflineShell(message.toString());
            return;
        }
        loadOfflineShell(message.toString());
    }

    private void setConnectionLost(boolean lost) {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.__hpConnectionLost && window.__hpConnectionLost(" + lost + ")", null);
    }

    private void autoReconnect(String reason) {
        if (!activityResumed || !pageReady || webView == null || isOfflineShell()) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoReconnect < 8000) return;
        lastAutoReconnect = now;
        lastJsResponse = now;
        Log.w(TAG, "auto reconnect: " + reason);
        Toast.makeText(this, "连接持续无响应，正在自动重连…", Toast.LENGTH_LONG).show();
        String tsTarget = prefs.getString(KEY_TS_TARGET, "");
        String tsCode = prefs.getString(KEY_TS_CODE, "");
        if (prefs.getBoolean(KEY_USE_TSNET, false)
                && !TextUtils.isEmpty(tsTarget) && !TextUtils.isEmpty(tsCode)) {
            cancelAutoConnect();
            beginAutoConnect("reconnect", true);
        } else if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(pairingCode)) {
            cancelAutoConnect();
            beginAutoConnect("reconnect", true);
        } else {
            loadOfflineShell("连接已中断，请检查电脑桥接");
        }
    }

    private void clearSession() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.clearCache(true);
        webView.clearFormData();
        pageReady = false;
        firstReady = true;
        prefs.edit().clear().apply();
        host = "";
        port = 8765;
        pairingCode = "";
        if (hostInput != null) hostInput.setText("");
        if (codeInput != null) codeInput.setText("");
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONNECTION) {
            if (resultCode == RESULT_OK) startTsnetConnect(true);
            return;
        }
        if (requestCode != REQ_FILE) return;
        if (fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            String dataString = data.getDataString();
            if (dataString != null) {
                results = new Uri[]{Uri.parse(dataString)};
            } else if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                ArrayList<Uri> uris = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
                if (!uris.isEmpty()) results = uris.toArray(new Uri[0]);
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (setupView != null && setupView.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
            return;
        }
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            showSetup();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null) {
            webView.evaluateJavascript("window.__hpSystemThemeChanged && window.__hpSystemThemeChanged()", null);
        }
    }

    private class NativeBridge {
        @JavascriptInterface
        public String systemTheme() {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        }

        @JavascriptInterface
        public void refreshPage() {
            runOnUiThread(() -> {
                if (webView != null) webView.reload();
            });
        }

        @JavascriptInterface
        public void rePair() {
            runOnUiThread(() -> {
                clearSession();
                showSetup();
            });
        }

        @JavascriptInterface
        public void setSystemBarsDark(boolean dark) {
            runOnUiThread(() -> setSystemBars(dark));
        }

        @JavascriptInterface
        public void downloadUrl(String url, String fileName, String mimeType) {
            if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
            runOnUiThread(() -> enqueueDownload(url, fileName, mimeType, null, true));
        }

        @JavascriptInterface
        public void downloadFile(String url, String fileName, String mimeType) {
            if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
            runOnUiThread(() -> {
                String name = sanitizeFileName(fileName);
                if (!TextUtils.isEmpty(name) && resolveDownloadedUri(name) != null) {
                    Toast.makeText(MainActivity.this, "文件已存在，直接打开", Toast.LENGTH_SHORT).show();
                    openDownloadedFile(name);
                    return;
                }
                enqueueDownload(url, fileName, mimeType, null, false);
            });
        }

        @JavascriptInterface
        public void openDownloadsFolder() {
            runOnUiThread(MainActivity.this::openDownloadsFolder);
        }

        @JavascriptInterface
        public String getUiLanguage() {
            return prefs.getString(KEY_UI_LANGUAGE, "zh");
        }

        @JavascriptInterface
        public void setUiLanguage(String language) {
            if ("en".equals(language) || "zh".equals(language)) {
                prefs.edit().putString(KEY_UI_LANGUAGE, language).apply();
            }
        }

        @JavascriptInterface
        public int getFontSize() {
            return prefs.getInt(KEY_FONT_SIZE, 14);
        }

        @JavascriptInterface
        public void setFontSize(int size) {
            int value = Math.max(10, Math.min(28, size));
            prefs.edit().putInt(KEY_FONT_SIZE, value).apply();
        }

        @JavascriptInterface
        public void autoConnectFromShell() {
            runOnUiThread(MainActivity.this::attemptShellAutoConnect);
        }

        @JavascriptInterface
        public String fileState(String fileName) {
            JSONObject state = new JSONObject();
            try {
                String clean = sanitizeFileName(fileName);
                String uri = resolveDownloadedUri(clean);
                state.put("exists", !TextUtils.isEmpty(uri));
                state.put("name", clean);
                state.put("mime", downloadedMimes.get(clean) == null
                        ? guessMime(clean) : downloadedMimes.get(clean));
            } catch (Exception ignored) {
            }
            return state.toString();
        }

        @JavascriptInterface
        public void openFile(String fileName) {
            runOnUiThread(() -> openDownloadedFile(fileName));
        }

        @JavascriptInterface
        public void requestReconnect(String reason) {
            runOnUiThread(() -> autoReconnect(reason == null ? "页面卡顿" : reason));
        }

        @JavascriptInterface
        public void openConnectionSettings() {
            runOnUiThread(MainActivity.this::openConnectionSettings);
        }

        @JavascriptInterface
        public String appVersion() {
            return UpdateChecker.currentVersion(MainActivity.this);
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> UpdateChecker.check(MainActivity.this, null));
        }

        @JavascriptInterface
        public String tsnetStatus() {
            return TsnetManager.get(MainActivity.this).cachedStatus();
        }

        @JavascriptInterface
        public String tsnetConfig() {
            JSONObject config = new JSONObject();
            try {
                config.put("target", prefs.getString(KEY_TS_TARGET, ""));
                config.put("code", prefs.getString(KEY_TS_CODE, ""));
            } catch (Exception ignored) {
            }
            return config.toString();
        }

        @JavascriptInterface
        public void tsnetLogin() {
            runOnUiThread(MainActivity.this::startTailscaleLogin);
        }

        @JavascriptInterface
        public void tsnetLogout() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, TsnetService.class));
                TsnetManager.get(MainActivity.this).logout((json, error) ->
                        Toast.makeText(MainActivity.this, "已退出 Tailscale", Toast.LENGTH_SHORT).show());
            });
        }

        @JavascriptInterface
        public void tsnetConnect(String target, String code) {
            runOnUiThread(() -> saveTailscaleConnection(target, code));
        }

        @JavascriptInterface
        public String getThemePreference() {
            return prefs.getString(KEY_THEME, "system");
        }

        @JavascriptInterface
        public void setThemePreference(String value) {
            String mode = "system";
            if ("light".equals(value) || "dark".equals(value)) mode = value;
            prefs.edit().putString(KEY_THEME, mode).apply();
        }

        @JavascriptInterface
        public String connectionConfig() {
            JSONObject config = new JSONObject();
            try {
                boolean tsMode = prefs.getBoolean(KEY_USE_TSNET, false)
                        && !TextUtils.isEmpty(prefs.getString(KEY_TS_TARGET, ""))
                        && !TextUtils.isEmpty(prefs.getString(KEY_TS_CODE, ""));
                String directHost = prefs.getString(KEY_HOST, "");
                String directTarget = TextUtils.isEmpty(directHost)
                        ? ""
                        : (directHost + ":" + prefs.getInt(KEY_PORT, 8765));
                config.put("mode", tsMode ? "tsnet" : "direct");
                config.put("target", tsMode
                        ? prefs.getString(KEY_TS_TARGET, "")
                        : directTarget);
                config.put("code", tsMode
                        ? prefs.getString(KEY_TS_CODE, "")
                        : prefs.getString(KEY_CODE, ""));
            } catch (Exception ignored) {
            }
            return config.toString();
        }

        @JavascriptInterface
        public void connectDirect(String target, String code) {
            runOnUiThread(() -> connectDirectFromShell(target, code));
        }
    }

    private void connectDirectFromShell(String target, String code) {
        pendingAutoConnect = false;
        String raw = target == null ? "" : target.trim();
        String pairCode = code == null ? "" : code.trim();
        if (TextUtils.isEmpty(raw) || !pairCode.matches("\\d{6}")) {
            Toast.makeText(this, "请填写电脑地址和 6 位配对码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!raw.contains("://")) raw = "http://" + raw;
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception error) {
            Toast.makeText(this, "电脑地址格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(uri.getHost())) {
            Toast.makeText(this, "电脑地址格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        host = uri.getHost();
        port = uri.getPort() > 0 ? uri.getPort() : 8765;
        pairingCode = pairCode;
        prefs.edit().putBoolean(KEY_USE_TSNET, false).apply();
        saveConfig();
        manualConnect = true;
        cancelAutoConnect();
        beginAutoConnect("manual", true);
    }

    private String sanitizeFileName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name;
    }

    private String guessMime(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < fileName.length()) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileName.substring(dot + 1).toLowerCase());
            if (!TextUtils.isEmpty(mime)) return mime;
        }
        return "application/octet-stream";
    }

    private String resolveDownloadedUri(String fileName) {
        String name = sanitizeFileName(fileName);
        if (TextUtils.isEmpty(name)) return null;
        String mapped = downloadedUris.get(name);
        if (!TextUtils.isEmpty(mapped)) return mapped;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{name},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    String mime = cursor.getString(1);
                    Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    rememberDownload(name, name, uri.toString(),
                            TextUtils.isEmpty(mime) ? guessMime(name) : mime);
                    return uri.toString();
                }
            } catch (Exception ignored) {
            }
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            File target = new File(dir, name);
            if (target.isFile()) {
                String uri = HarnessFileProvider.uriFor(target).toString();
                rememberDownload(name, name, uri, guessMime(name));
                return uri;
            }
        }
        return null;
    }

    private void openDownloadsFolder() {
        try {
            startActivity(new Intent("android.intent.action.VIEW_DOWNLOADS"));
        } catch (Exception error) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                                "vnd.android.document/root");
                startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(this, "手机上找不到文件管理器，请手动打开「下载」目录", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openDownloadedFile(String fileName) {
        String name = sanitizeFileName(fileName);
        String uriString = resolveDownloadedUri(name);
        if (TextUtils.isEmpty(uriString)) {
            Toast.makeText(this, "本地还没有这个文件，请先下载", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse(uriString);
        String mime = downloadedMimes.get(name);
        if (TextUtils.isEmpty(mime)) mime = getContentResolver().getType(uri);
        if (TextUtils.isEmpty(mime)) mime = guessMime(name);
        if ("application/vnd.android.package-archive".equals(mime)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许 DSH Phone Assistant 安装未知应用", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                Toast.makeText(this, "请在系统设置中允许安装未知应用", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("", uri));
        try {
            startForegroundService(new Intent(this, FileOpenService.class));
        } catch (Exception ignored) {
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            try {
                startActivity(Intent.createChooser(intent, "打开文件"));
            } catch (Exception ignored) {
                stopService(new Intent(this, FileOpenService.class));
                Toast.makeText(this, "手机上找不到可打开该文件的应用", Toast.LENGTH_LONG).show();
            }
        } catch (Exception error) {
            stopService(new Intent(this, FileOpenService.class));
            Toast.makeText(this, "无法打开文件：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, FileOpenService.class));
        activityResumed = true;
        lastJsResponse = System.currentTimeMillis();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
