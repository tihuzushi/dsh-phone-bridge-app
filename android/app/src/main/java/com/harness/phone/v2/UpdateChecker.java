package com.harness.phone.v2;

import android.app.Activity;
import android.app.AlertDialog;
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

/**
 * Manual update check against GitHub Releases.
 *
 * The APK is not downloaded inside the app: on "去下载" the release page opens in
 * the system browser, where the user can read the notes and take the asset.
 */
final class UpdateChecker {

    private static final String REPO = "tihuzushi/dsh-phone-bridge-app";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";

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
                showDialog(activity, current, finalTag, finalNotes, finalPage);
            });
        }, "update-check").start();
    }

    private static void showDialog(Activity activity, String current, String tag, String notes, String page) {
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
                .setNegativeButton("稍后", null)
                .setPositiveButton("去下载", (dialog, which) -> {
                    try {
                        String target = page == null || page.isEmpty() ? RELEASES_URL : page;
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                    } catch (Exception error) {
                        Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
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
