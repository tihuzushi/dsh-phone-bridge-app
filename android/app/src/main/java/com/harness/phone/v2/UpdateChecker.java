package com.harness.phone.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual update check against GitHub Releases, download from the China mirror.
 *
 * The version is still read from the repository, because that is the single
 * source of truth. The APK itself is not fetched from GitHub: release pages and
 * release assets are rate-limited from mainland China, so the download button
 * opens the mirrored copy instead. The mirror URL is taken from the release
 * notes (each release carries one) and falls back to a short link that always
 * points at the newest build.
 *
 * Nothing is downloaded inside the app: the system browser handles the transfer.
 */
final class UpdateChecker {

    private static final String REPO = "tihuzushi/dsh-phone-bridge-app";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";

    /** Always serves the current build; used when a release has no mirror link. */
    private static final String MIRROR_FALLBACK = "https://www.hanhaichem.cn/dsh.apk";

    private static final Pattern APK_URL =
            Pattern.compile("(https?://[^\\s)<>\\]\"']+\\.apk)", Pattern.CASE_INSENSITIVE);

    private UpdateChecker() {
    }

    static String currentVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "未知" : info.versionName;
        } catch (Exception error) {
            return "未知";
        }
    }

    static void check(Activity activity, Button trigger) {
        if (trigger != null) {
            trigger.setEnabled(false);
            trigger.setText("检查中…");
        }
        String current = currentVersion(activity);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            String tag = null;
            String notes = null;
            String page = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "dsh-phone-bridge-app/" + current);
                int code = connection.getResponseCode();
                if (code == 404) {
                    error = "还没有发布版本";
                } else if (code != 200) {
                    error = "GitHub 返回 " + code;
                } else {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line).append('\n');
                    }
                    JSONObject release = new JSONObject(body.toString());
                    tag = release.optString("tag_name", "").trim();
                    notes = release.optString("body", "").trim();
                    page = release.optString("html_url", RELEASES_URL).trim();
                }
                connection.disconnect();
            } catch (Exception failure) {
                error = failure.getClass().getSimpleName();
            }

            final String finalError = error;
            final String finalTag = tag;
            final String finalNotes = notes;
            final String finalPage = page;
            main.post(() -> {
                if (trigger != null) {
                    trigger.setEnabled(true);
                    trigger.setText("检查更新");
                }
                if (activity.isFinishing()) return;
                if (finalError != null) {
                    Toast.makeText(activity, "检查更新失败：" + finalError, Toast.LENGTH_LONG).show();
                    return;
                }
                String latest = normalize(finalTag);
                if (latest == null) {
                    Toast.makeText(activity, "线上版本号无法识别：" + finalTag, Toast.LENGTH_LONG).show();
                    return;
                }
                if (compare(latest, normalize(current)) <= 0) {
                    Toast.makeText(activity, "已经是最新版本 " + current, Toast.LENGTH_SHORT).show();
                    return;
                }
                showDialog(activity, current, finalTag, finalNotes, downloadUrlFor(finalNotes, finalPage));
            });
        }, "update-check").start();
    }

    /**
     * Prefer the mirror link the release notes carry; fall back to the short
     * link that always points at the newest build.
     */
    static String downloadUrlFor(String notes, String page) {
        String mirrored = firstMirrorUrl(notes);
        if (mirrored != null) return mirrored;
        if (MIRROR_FALLBACK != null && !MIRROR_FALLBACK.isEmpty()) return MIRROR_FALLBACK;
        return page == null || page.isEmpty() ? RELEASES_URL : page;
    }

    private static String firstMirrorUrl(String notes) {
        if (notes == null) return null;
        Matcher matcher = APK_URL.matcher(notes);
        String first = null;
        while (matcher.find()) {
            String url = matcher.group(1);
            if (url.contains("hanhaichem.cn")) return url;
            if (first == null) first = url;
        }
        return first;
    }

    private static void showDialog(Activity activity, String current, String tag, String notes, String downloadUrl) {
        StringBuilder message = new StringBuilder();
        message.append("当前版本 ").append(current).append("，最新 ").append(tag).append("\n\n");
        if (notes == null || notes.isEmpty()) {
            message.append("这次发布没有写更新日志。");
        } else {
            message.append(notes.length() > 1200 ? notes.substring(0, 1200) + "…" : notes);
        }
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + tag)
                .setMessage(message.toString())
                .setNeutralButton("复制下载链接", (dialog, which) -> copy(activity, downloadUrl))
                .setNegativeButton("稍后", null)
                .setPositiveButton("直接下载", (dialog, which) -> open(activity, downloadUrl))
                .show();
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_LONG).show();
        }
    }

    private static void copy(Activity activity, String url) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("DSH Phone Bridge", url));
            Toast.makeText(activity, "下载链接已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** "android-2.4.0" / "v2.4.0" / "2.4.0" -> "2.4.0". */
    private static String normalize(String tag) {
        if (tag == null) return null;
        String value = tag.trim();
        int dash = value.lastIndexOf('-');
        if (dash >= 0 && value.substring(dash + 1).matches("\\d+(\\.\\d+)*")) value = value.substring(dash + 1);
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value.matches("\\d+(\\.\\d+)*") ? value : null;
    }

    private static int compare(String left, String right) {
        if (right == null) return 1;
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
