package com.harness.phone.v2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class ConnectionSettingsActivity extends Activity {
    private static final String PREFS = "harness_phone";
    private static final String KEY_TARGET = "ts_target";
    private static final String KEY_CODE = "ts_pair_code";
    private static final String KEY_MANUAL = "ts_manual";

    private static final int BG = 0xFF151517;
    private static final int SURFACE = 0xFF1C1C20;
    private static final int SURFACE_2 = 0xFF26262C;
    private static final int BORDER = 0xFF303036;
    private static final int TEXT = 0xFFECECF1;
    private static final int MUTED = 0xFF9B9BA6;
    private static final int FAINT = 0xFF6F6F7A;
    private static final int ACCENT = 0xFF4D6BFE;
    private static final int OK = 0xFF57D89A;
    private static final int DANGER = 0xFFFF7A7A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TsnetManager manager;

    private TextView accountStatus;
    private TextView deviceHint;
    private RadioGroup deviceGroup;
    private LinearLayout deviceList;
    private CheckBox manualCheck;
    private EditText manualInput;
    private EditText codeInput;
    private Button loginButton;
    private Button logoutButton;
    private Button saveButton;
    private Button checkUpdateButton;
    private String authUrl = "";
    private boolean authOpened = false;
    private boolean tailscaleReady = false;
    private String lastDevicesJson = "";
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            manager.status((json, error) -> {
                if (isFinishing()) return;
                if (error != null) {
                    accountStatus.setText("Tailscale 状态读取失败：" + error.getMessage());
                } else {
                    handleStatus(json);
                }
                handler.postDelayed(poll, 1200);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        manager = TsnetManager.get(this);
        startForegroundService(new Intent(this, TsnetService.class));
        configureWindow();
        setContentView(buildUi());
        manager.start((json, error) -> {
            if (error != null) accountStatus.setText("Tailscale 启动失败：" + error.getMessage());
            else handleStatus(json);
        });
        polling = true;
        handler.postDelayed(poll, 700);
    }

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(0);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(header);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setTextColor(TEXT);
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setPadding(0, 0, dp(8), 0);
        header.addView(back, new LinearLayout.LayoutParams(dp(38), dp(44)));
        back.setOnClickListener(v -> finish());

        TextView title = text("连接设置", 24, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView intro = text("使用 App 内置的 Tailscale 用户态节点，不占用系统 VPN。首次登录后会自动保存账号。", 13, MUTED, false);
        intro.setLineSpacing(dp(2), 1.15f);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introParams.topMargin = dp(10);
        column.addView(intro, introParams);

        LinearLayout accountCard = card();
        column.addView(accountCard, cardParams());
        accountCard.addView(text("TAILSCALE 账号", 11, FAINT, true));
        accountStatus = text("正在启动…", 14, MUTED, false);
        accountStatus.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(9);
        accountCard.addView(accountStatus, statusParams);

        LinearLayout accountButtons = new LinearLayout(this);
        accountButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams accountButtonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        accountButtonsParams.topMargin = dp(12);
        accountCard.addView(accountButtons, accountButtonsParams);

        loginButton = secondaryButton("打开 Tailscale 登录页");
        accountButtons.addView(loginButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        loginButton.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(authUrl)) openAuthUrl();
            else {
                manager.start((json, error) -> {
                    if (error != null) toast("启动失败：" + error.getMessage());
                    else handleStatus(json);
                });
            }
        });

        logoutButton = secondaryButton("退出登录");
        logoutButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        logoutParams.leftMargin = dp(8);
        accountButtons.addView(logoutButton, logoutParams);
        logoutButton.setOnClickListener(v -> {
            stopService(new Intent(this, TsnetService.class));
            manager.logout((json, error) -> {
                authUrl = "";
                authOpened = false;
                lastDevicesJson = "";
                deviceGroup.removeAllViews();
                deviceHint.setText("登录后会自动从 Tailscale 账号读取设备。");
                accountStatus.setText("已退出登录");
                toast("已退出 Tailscale");
            });
        });

        LinearLayout connectionCard = card();
        LinearLayout.LayoutParams connectionParams = cardParams();
        column.addView(connectionCard, connectionParams);
        connectionCard.addView(text("连接设备", 16, TEXT, true));
        deviceHint = text("登录后会自动从 Tailscale 账号读取设备。", 12, MUTED, false);
        deviceHint.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        connectionCard.addView(deviceHint, hintParams);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(8);
        connectionCard.addView(deviceList, listParams);

        deviceGroup = new RadioGroup(this);
        deviceGroup.setOrientation(RadioGroup.VERTICAL);
        deviceList.addView(deviceGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        manualCheck = new CheckBox(this);
        manualCheck.setText("手动输入 IP（备用）");
        manualCheck.setTextColor(TEXT);
        manualCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        manualCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams manualCheckParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manualCheckParams.topMargin = dp(12);
        connectionCard.addView(manualCheck, manualCheckParams);

        manualInput = new EditText(this);
        manualInput.setHint("例如 100.64.0.1 或 192.168.1.10:8765");
        manualInput.setHintTextColor(FAINT);
        manualInput.setTextColor(TEXT);
        manualInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        manualInput.setSingleLine(true);
        manualInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        manualInput.setBackground(bordered(SURFACE_2, 11, BORDER, 1));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manualParams.topMargin = dp(7);
        connectionCard.addView(manualInput, manualParams);
        manualInput.setText(prefs.getString(KEY_TARGET, ""));
        boolean manual = prefs.getBoolean(KEY_MANUAL, false);
        manualCheck.setChecked(manual);
        manualInput.setEnabled(manual);
        manualInput.setAlpha(manual ? 1f : 0.45f);
        manualCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            manualInput.setEnabled(checked);
            manualInput.setAlpha(checked ? 1f : 0.45f);
            deviceGroup.setAlpha(checked ? 0.45f : 1f);
            deviceGroup.setEnabled(!checked);
        });
        deviceGroup.setAlpha(manual ? 0.45f : 1f);

        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams codeRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeRowParams.topMargin = dp(14);
        connectionCard.addView(codeRow, codeRowParams);
        codeRow.addView(text("配对码", 12, MUTED, false));
        codeInput = new EditText(this);
        codeInput.setHint("6 位数字");
        codeInput.setHintTextColor(FAINT);
        codeInput.setTextColor(TEXT);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        codeInput.setSingleLine(true);
        codeInput.setLetterSpacing(0.22f);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        codeInput.setBackground(bordered(SURFACE_2, 11, BORDER, 1));
        codeInput.setText(prefs.getString(KEY_CODE, ""));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(6);
        codeRow.addView(codeInput, codeParams);

        saveButton = new Button(this);
        saveButton.setText("保存并连接");
        saveButton.setTextColor(Color.WHITE);
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveButton.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        saveButton.setAllCaps(false);
        GradientDrawable buttonBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, 0xFF7D3CFF});
        buttonBg.setCornerRadius(dp(14));
        saveButton.setBackground(buttonBg);
        saveButton.setEnabled(false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveParams.topMargin = dp(18);
        connectionCard.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> saveAndFinish());

        TextView note = text("登录信息保存在本机 App 私有目录；重启 App 会自动恢复 Tailscale 节点，不会每次重新登录。", 11, FAINT, false);
        note.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(16);
        column.addView(note, noteParams);

        LinearLayout aboutCard = card();
        LinearLayout.LayoutParams aboutParams = cardParams();
        column.addView(aboutCard, aboutParams);
        aboutCard.addView(text("DSH PHONE BRIDGE", 11, FAINT, true));
        TextView versionText = text("当前版本 " + UpdateChecker.currentVersion(this), 15, TEXT, true);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(6);
        aboutCard.addView(versionText, versionParams);

        checkUpdateButton = secondaryButton("检查更新");
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        checkParams.topMargin = dp(12);
        aboutCard.addView(checkUpdateButton, checkParams);
        checkUpdateButton.setOnClickListener(v -> UpdateChecker.check(this, checkUpdateButton));

        TextView updateNote = text("更新从 GitHub Releases 下载，检查到新版本会显示更新日志。", 11, FAINT, false);
        updateNote.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams updateNoteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateNoteParams.topMargin = dp(10);
        aboutCard.addView(updateNote, updateNoteParams);

        return root;
    }

    private void handleStatus(String json) {
        if (json == null) return;
        try {
            JSONObject object = new JSONObject(json);
            String state = object.optString("state", "Stopped");
            String account = object.optString("account", "");
            authUrl = object.optString("authUrl", "");
            String error = object.optString("error", "");

            if (state.equals("Running")) {
                tailscaleReady = true;
                accountStatus.setText("已连接 Tailscale" + (TextUtils.isEmpty(account) ? "" : " · " + account));
                accountStatus.setTextColor(OK);
                loginButton.setVisibility(View.GONE);
                logoutButton.setVisibility(View.VISIBLE);
                saveButton.setEnabled(true);
                JSONArray devices = object.optJSONArray("devices");
                renderDevices(devices);
            } else {
                tailscaleReady = false;
                if (state.equals("NeedsLogin") || !TextUtils.isEmpty(authUrl)) {
                    accountStatus.setText("需要登录 Tailscale" + (TextUtils.isEmpty(authUrl) ? "" : " · 请打开登录页"));
                    loginButton.setVisibility(View.VISIBLE);
                } else {
                    accountStatus.setText("Tailscale 状态：" + state);
                }
                accountStatus.setTextColor(MUTED);
                logoutButton.setVisibility(View.GONE);
                saveButton.setEnabled(false);
                if (!TextUtils.isEmpty(error)) {
                    accountStatus.setText("Tailscale：" + error);
                    accountStatus.setTextColor(DANGER);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void renderDevices(JSONArray devices) {
        if (devices == null) devices = new JSONArray();
        String signature = devices.toString();
        if (signature.equals(lastDevicesJson)) return;
        lastDevicesJson = signature;
        deviceGroup.removeAllViews();
        if (devices.length() == 0) {
            deviceHint.setText("账号里还没有可用设备。请确认电脑 Tailscale 在线。");
            return;
        }
        String saved = prefs.getString(KEY_TARGET, "");
        RadioButton chosen = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null) continue;
            String name = device.optString("name", "device");
            String address = device.optString("address", "");
            String os = device.optString("os", "");
            boolean online = device.optBoolean("online", false);
            RadioButton radio = new RadioButton(this);
            radio.setText(name + "  ·  " + address + (TextUtils.isEmpty(os) ? "" : "  ·  " + os) + (online ? "  ·  在线" : "  ·  离线"));
            radio.setTextColor(online ? TEXT : MUTED);
            radio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            radio.setTag(address);
            radio.setPadding(dp(4), dp(8), dp(4), dp(8));
            deviceGroup.addView(radio, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (address.equals(saved)) chosen = radio;
            else if (chosen == null && online && name.toLowerCase().contains("laptop")) chosen = radio;
        }
        if (chosen == null && deviceGroup.getChildCount() > 0) {
            for (int i = 0; i < deviceGroup.getChildCount(); i++) {
                RadioButton radio = (RadioButton) deviceGroup.getChildAt(i);
                if (radio.getCurrentTextColor() == TEXT) {
                    chosen = radio;
                    break;
                }
            }
        }
        if (chosen != null) chosen.setChecked(true);
        deviceHint.setText("从 Tailscale 账号读取到 " + devices.length() + " 台设备；选择电脑后填写配对码。");
        deviceGroup.setAlpha(manualCheck.isChecked() ? 0.45f : 1f);
    }

    private void saveAndFinish() {
        if (!tailscaleReady) {
            toast("请先登录 Tailscale，等状态显示“已连接”后再保存");
            return;
        }
        String target;
        if (manualCheck.isChecked()) {
            target = manualInput.getText().toString().trim();
        } else {
            int id = deviceGroup.getCheckedRadioButtonId();
            if (id < 0) {
                toast("请先在列表里选择电脑，或勾选手动输入 IP");
                return;
            }
            RadioButton selected = findViewById(id);
            target = selected == null ? "" : String.valueOf(selected.getTag());
        }
        if (!manualCheck.isChecked()) manualInput.setText(target);
        String code = codeInput.getText().toString().trim();
        if (TextUtils.isEmpty(target)) {
            toast("请选择或输入电脑 IP");
            return;
        }
        if (!code.matches("\\d{6}")) {
            toast("请输入 6 位配对码");
            return;
        }
        prefs.edit()
                .putString(KEY_TARGET, target)
                .putString(KEY_CODE, code)
                .putBoolean(KEY_MANUAL, manualCheck.isChecked())
                .putBoolean("ts_use_tsnet", true)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    private void openAuthUrl() {
        if (TextUtils.isEmpty(authUrl)) {
            toast("登录地址还没有生成，请稍等");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
        } catch (Exception error) {
            toast("无法打开浏览器：" + error.getMessage());
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(bordered(SURFACE, 18, BORDER, 1));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        return params;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setAllCaps(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(bordered(SURFACE_2, 11, BORDER, 1));
        return button;
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private GradientDrawable bordered(int color, float radiusDp, int borderColor, float borderDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(borderDp), borderColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
