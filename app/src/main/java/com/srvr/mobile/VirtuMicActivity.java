package com.srvr.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

public final class VirtuMicActivity extends Activity implements VirtualMicController.Listener {
    private static final Uri SHIZUKU_RELEASES = Uri.parse("https://shizuku.rikka.app/download/");
    private static final String TEST_SOUND_ID = "sounds/11.wav";
    private static final int ERROR_COLOR = 0xffff9b9b;

    private final Handler main = new Handler(Looper.getMainLooper());

    private VirtualMicController controller;
    private AudioManager audio;
    private SoundLibrary.Sound testSound;
    private SoundPlayer.Playback testPlayback;

    private TextView shizukuStatus;
    private Button openShizuku;
    private Button grantShizuku;
    private Button stopShizuku;
    private Button toggleMic;
    private TextView micState;
    private TextView micError;
    private Button modeAlwaysSfxBtn;
    private Button modeOnDemandBtn;
    private Button modeMixMicBtn;
    private TextView modeHint;
    private CheckBox duckMicBox;
    private CheckBox localMonitorBox;
    private TextView inputs;
    private TextView diagnostics;
    private TextView testStatus;
    private Button stopTest;
    private boolean updatingChecks;

    private final AudioDeviceCallback inputChanges = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            main.post(VirtuMicActivity.this::refreshInputs);
        }

        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            main.post(VirtuMicActivity.this::refreshInputs);
        }
    };

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            refreshDiagnostics();
            if (controller != null && controller.isActive()) {
                main.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        controller = VirtualMicController.get(this);
        audio = getSystemService(AudioManager.class);
        testSound = SoundLibrary.get(this).soundById(TEST_SOUND_ID);
        buildScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        controller.addListener(this);
        if (audio != null) audio.registerAudioDeviceCallback(inputChanges, main);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override protected void onPause() {
        main.removeCallbacks(pollTask);
        super.onPause();
    }

    @Override protected void onStop() {
        controller.removeListener(this);
        if (audio != null) audio.unregisterAudioDeviceCallback(inputChanges);
        if (testPlayback != null) endTest();
        super.onStop();
    }

    @Override public void onVirtualMicStateChanged() {
        refreshAll();
    }

    private void buildScreen() {
        LinearLayout page = Ui.screen(this);
        LinearLayout header = Ui.row(this);
        int buttonSize = Ui.dp(this, 48);
        Button back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(buttonSize, buttonSize));
        TextView title = Ui.text(this, getString(R.string.virtumic_title), 22, Ui.TEXT, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        titleLayout.setMarginStart(Ui.dp(this, 12));
        header.addView(title, titleLayout);
        Ui.below(page, header, 0);

        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout body = Ui.column(this);
        body.setPadding(0, 0, 0, Ui.dp(this, 24));
        scroll.addView(body, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        buildShizukuCard(body);
        buildSwitchCard(body);
        buildMixingCard(body);
        buildTestCard(body);
    }

    private void buildShizukuCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this);
        Ui.below(parent, card, 12);
        Ui.below(card, Ui.text(this, "1. Shizuku 权限状态", 18, Ui.TEXT, true), 0);
        String android = "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT;
        Ui.below(card, Ui.text(this, android, 13, Ui.SECONDARY, false), 6);
        shizukuStatus = Ui.text(this, "", 14, Ui.TEXT, false);
        Ui.below(card, shizukuStatus, 8);

        LinearLayout row = Ui.row(this);
        openShizuku = Ui.button(this, "打开 Shizuku", false);
        openShizuku.setOnClickListener(view -> launchShizuku());
        Ui.weighted(row, openShizuku, 0);
        grantShizuku = Ui.button(this, "申请授权", true);
        grantShizuku.setOnClickListener(view -> controller.requestShizukuPermission());
        Ui.weighted(row, grantShizuku, 8);
        Ui.below(card, row, 10);

        LinearLayout row2 = Ui.row(this);
        Button refresh = Ui.button(this, "刷新状态", false);
        refresh.setOnClickListener(view -> refreshAll());
        Ui.weighted(row2, refresh, 0);
        stopShizuku = Ui.button(this, "关闭调试与后台", false);
        stopShizuku.setOnClickListener(view -> confirmStopShizuku());
        Ui.weighted(row2, stopShizuku, 8);
        Ui.below(card, row2, 6);
    }

    private void confirmStopShizuku() {
        new AlertDialog.Builder(this)
                .setTitle("关闭无线调试与 Shizuku 后台？")
                .setMessage("将停止虚拟麦克风、穿透热区与 Shizuku 守护进程，并自动关闭系统「无线调试」开关（不影响有线 USB 调试）。\n\n下次使用时，打开无线调试并在 Shizuku 点启动即可。")
                .setPositiveButton("确认关闭", (dialog, which) -> {
                    controller.shutdownDebuggingAndShizuku();
                    toast("正在关闭无线调试与 Shizuku 后台…");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void buildSwitchCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this);
        Ui.below(parent, card, 12);
        Ui.below(card, Ui.text(this, "2. 内置虚拟麦克风", 18, Ui.TEXT, true), 0);
        String guide = "开启后，在轮盘、悬浮球或固定触发点播放任意音效时，都会自动将音频实时发送到虚拟麦克风通道。";
        Ui.below(card, Ui.text(this, guide, 13, Ui.SECONDARY, false), 6);

        toggleMic = Ui.button(this, "开启虚拟麦克风", true);
        toggleMic.setOnClickListener(view -> {
            if (controller.isActive()) controller.stop();
            else controller.start();
        });
        Ui.below(card, toggleMic, 12);

        micState = Ui.text(this, "", 14, Ui.ACCENT, true);
        Ui.below(card, micState, 8);

        micError = Ui.text(this, "", 13, ERROR_COLOR, false);
        micError.setVisibility(View.GONE);
        Ui.below(card, micError, 6);
    }

    private void buildMixingCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this);
        Ui.below(parent, card, 12);
        Ui.below(card, Ui.text(this, "3. 工作模式与音量", 18, Ui.TEXT, true), 0);

        modeAlwaysSfxBtn = Ui.button(this, "常驻纯音效（微信/QQ/语音包推荐）", false);
        modeAlwaysSfxBtn.setOnClickListener(view -> controller.setMode(VirtualMicController.MODE_ALWAYS_SFX));
        Ui.below(card, modeAlwaysSfxBtn, 10);

        modeOnDemandBtn = Ui.button(this, "仅放音效时接管（平时用手机原麦说话）", false);
        modeOnDemandBtn.setOnClickListener(view -> controller.setMode(VirtualMicController.MODE_ON_DEMAND));
        Ui.below(card, modeOnDemandBtn, 6);

        modeMixMicBtn = Ui.button(this, "常驻 + 混入真麦（实验性，受系统隐私限制）", false);
        modeMixMicBtn.setOnClickListener(view -> controller.setMode(VirtualMicController.MODE_MIX_REAL_MIC));
        Ui.below(card, modeMixMicBtn, 6);

        modeHint = Ui.text(this, "", 13, Ui.SECONDARY, false);
        Ui.below(card, modeHint, 8);

        localMonitorBox = makeCheckBox("本机同时监听（关闭则手机静音、仅发往虚拟麦）");
        localMonitorBox.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingChecks) return;
            controller.setLocalMonitor(checked);
        });
        Ui.below(card, localMonitorBox, 6);

        duckMicBox = makeCheckBox("混入真麦时，放音效自动压低真麦声音");
        duckMicBox.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingChecks) return;
            controller.setDuckMicOnSfx(checked);
        });
        Ui.below(card, duckMicBox, 4);

        Ui.slider(card, "虚拟麦音效音量", controller.sfxGainPercent(), 0, 200, 5,
                value -> value + "%", value -> controller.setSfxGainPercent(value));
        Ui.slider(card, "真实麦克风音量（仅混入真麦模式生效）", controller.realMicGainPercent(), 0, 200, 5,
                value -> value + "%", value -> controller.setRealMicGainPercent(value));
    }

    private void buildTestCard(LinearLayout parent) {
        LinearLayout card = Ui.card(this);
        Ui.below(parent, card, 12);
        Ui.below(card, Ui.text(this, "4. 发送测试与诊断", 18, Ui.TEXT, true), 0);
        String help = "先开启虚拟麦克风，再打开微信/QQ按住录音或游戏开麦，用悬浮球或下方按钮播放音效即可验证。";
        Ui.below(card, Ui.text(this, help, 13, Ui.SECONDARY, false), 6);
        String sample = testSound == null ? "测试音效缺失" : "测试音效 · " + testSound.title();
        Ui.below(card, Ui.text(this, sample, 14, Ui.TEXT, true), 10);

        LinearLayout actions = Ui.row(this);
        Button play = Ui.button(this, "发送测试音", true);
        play.setEnabled(testSound != null);
        play.setOnClickListener(view -> startTest());
        Ui.weighted(actions, play, 0);
        stopTest = Ui.button(this, "停止", false);
        stopTest.setOnClickListener(view -> endTest());
        Ui.weighted(actions, stopTest, 8);
        Ui.below(card, actions, 8);

        testStatus = Ui.text(this, "", 13, Ui.SECONDARY, false);
        testStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.below(card, testStatus, 8);
        showTestStatus("是否收到音效，以录音回听或队友听感为准。");

        inputs = Ui.text(this, "", 13, Ui.SECONDARY, false);
        Ui.below(card, inputs, 10);

        diagnostics = Ui.text(this, "", 12, Ui.SECONDARY, false);
        diagnostics.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        diagnostics.setBackground(Ui.shape(this, Ui.BG, 10, Ui.LINE));
        Ui.below(card, diagnostics, 8);
    }

    private CheckBox makeCheckBox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(14);
        box.setTextColor(Ui.TEXT);
        box.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        box.setMinHeight(Ui.dp(this, 44));
        return box;
    }

    private void refreshAll() {
        refreshShizuku();
        refreshSwitch();
        refreshModesAndChecks();
        refreshInputs();
        refreshDiagnostics();

        main.removeCallbacks(pollTask);
        if (controller.isActive()) {
            main.postDelayed(pollTask, 1000);
        }
    }

    private void refreshShizuku() {
        PackageInfo info = controller.shizukuPackageInfo();
        boolean running = controller.isShizukuRunning();
        boolean granted = controller.hasShizukuPermission();

        StringBuilder sb = new StringBuilder();
        sb.append("安装状态：").append(info == null ? "未安装 Shizuku"
                : "已安装" + (info.versionName != null ? "（" + info.versionName + "）" : ""));
        sb.append("\n服务状态：");
        if (!running) {
            sb.append("未运行（请先在 Shizuku 中启动服务）");
        } else {
            int uid = controller.shizukuUid();
            String mode = uid == 0 ? "Root (UID 0)" : (uid == Process.SHELL_UID ? "ADB (UID 2000)" : "UID " + uid);
            sb.append("已运行 · ").append(mode);
        }
        sb.append("\n应用授权：").append(granted ? "已授权" : (running ? "未授权" : "等待服务启动"));
        shizukuStatus.setText(sb.toString());

        openShizuku.setText(info == null ? "下载 Shizuku" : "打开 Shizuku");
        grantShizuku.setEnabled(running && !granted);
        grantShizuku.setAlpha(running && !granted ? 1f : 0.46f);
        grantShizuku.setText(granted ? "已授权" : "申请授权");

        boolean canStop = running && granted;
        stopShizuku.setEnabled(canStop);
        stopShizuku.setAlpha(canStop ? 1f : 0.46f);
        stopShizuku.setBackground(Ui.shape(this, canStop ? 0xff4a2525 : Ui.CONTROL, 12, canStop ? ERROR_COLOR : 0));
    }

    private void refreshSwitch() {
        boolean active = controller.isActive();
        boolean busy = controller.isBusy();
        toggleMic.setEnabled(!busy);
        toggleMic.setAlpha(busy ? 0.6f : 1f);
        toggleMic.setText(busy ? "正在连接 Shizuku…" : (active ? "关闭虚拟麦克风" : "开启虚拟麦克风"));
        toggleMic.setTextColor(active ? Ui.TEXT : Ui.DARK_GREEN);
        toggleMic.setBackground(Ui.shape(this, active ? 0xff4a2525 : Ui.ACCENT, 12, active ? ERROR_COLOR : 0));

        micState.setText("当前状态：" + controller.statusSummary());
        micState.setTextColor(active ? Ui.ACCENT : Ui.SECONDARY);

        String err = controller.lastError();
        if (err == null || err.isEmpty()) {
            micError.setVisibility(View.GONE);
        } else {
            micError.setVisibility(View.VISIBLE);
            micError.setText("提示：" + err);
        }
    }

    private void refreshModesAndChecks() {
        int mode = controller.mode();
        styleModeButton(modeAlwaysSfxBtn, mode == VirtualMicController.MODE_ALWAYS_SFX);
        styleModeButton(modeOnDemandBtn, mode == VirtualMicController.MODE_ON_DEMAND);
        styleModeButton(modeMixMicBtn, mode == VirtualMicController.MODE_MIX_REAL_MIC);

        modeHint.setText(switch (mode) {
            case VirtualMicController.MODE_ON_DEMAND ->
                    "说明：平时不接管，正常使用手机原麦说话；播放轮盘音效时自动接管并发送音效，播完自动恢复原麦。";
            case VirtualMicController.MODE_MIX_REAL_MIC ->
                    "说明：尝试同时采集真麦与轮盘音效混音。Android 13+ 在前台应用录音时会自动静音后台真麦。";
            default ->
                    "说明：持续接管麦克风通道，微信/QQ/游戏一录音就能清晰收到轮盘音效；不用时在通知栏点「关闭虚拟麦」恢复原麦。";
        });

        updatingChecks = true;
        boolean mixMode = mode == VirtualMicController.MODE_MIX_REAL_MIC;
        duckMicBox.setChecked(controller.duckMicOnSfx());
        duckMicBox.setEnabled(mixMode);
        duckMicBox.setAlpha(mixMode ? 1f : 0.46f);
        localMonitorBox.setChecked(controller.localMonitor());
        updatingChecks = false;
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setTextColor(selected ? Ui.DARK_GREEN : Ui.TEXT);
        button.setBackground(Ui.shape(this, selected ? Ui.ACCENT : Ui.CONTROL, 12, 0));
    }

    private void refreshInputs() {
        Set<String> names = new LinkedHashSet<>();
        if (audio != null) {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                names.add(switch (device.getType()) {
                    case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风";
                    case AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "系统回环输入 (REMOTE_SUBMIX)";
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦";
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳麦";
                    case AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频输入";
                    default -> device.getProductName().toString();
                });
            }
        }
        inputs.setText("系统录音设备：" + (names.isEmpty() ? "未报告输入设备" : String.join("、", names)));
    }

    private void refreshDiagnostics() {
        String raw = controller.pollDiagnostics();
        if (raw == null || raw.isEmpty()) {
            diagnostics.setText("诊断信息：虚拟麦克风尚未启动");
        } else {
            diagnostics.setText("远端服务诊断：\n" + raw);
        }
    }

    private void launchShizuku() {
        if (controller.shizukuPackageInfo() == null) {
            open(new Intent(Intent.ACTION_VIEW, SHIZUKU_RELEASES), "无法打开 Shizuku 下载页");
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(VirtualMicController.SHIZUKU_PACKAGE);
        if (launch == null) toast("系统找不到 Shizuku 启动入口");
        else open(launch, "无法打开 Shizuku");
    }

    private void startTest() {
        if (testSound == null) return;
        if (testPlayback != null) testPlayback.stop();
        testPlayback = SoundPlayer.playTest(this, testSound, () -> {
            String suffix = controller.isActive() ? "（已同步推入虚拟麦）" : "（虚拟麦未开启，仅本地试听）";
            testStatus.setText("正在播放测试音…" + suffix);
        }, () -> {
            testPlayback = null;
            showTestStatus("测试播放已结束，请检查录音或队友听感。");
            refreshDiagnostics();
        });
        showTestStatus(testPlayback == null ? "无法开始播放测试音。" : "正在准备测试音…");
    }

    private void endTest() {
        if (testPlayback != null) testPlayback.stop();
        showTestStatus("测试播放已停止。");
    }

    private void showTestStatus(String message) {
        boolean active = testPlayback != null;
        stopTest.setEnabled(active);
        stopTest.setAlpha(active ? 1f : 0.46f);
        testStatus.setText(message);
    }

    private void open(Intent intent, String failure) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(failure);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
