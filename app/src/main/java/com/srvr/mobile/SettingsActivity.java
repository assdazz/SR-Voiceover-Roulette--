package com.srvr.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final int OVERLAY_PERMISSION = 21;
    private static final int NOTIFICATION_PERMISSION = 23;
    private SoundLibrary library;
    private ScrollView scroll;
    private Button overlayToggle;
    private TextView overlayStatus;
    private boolean pendingOverlayPermission;
    private boolean pendingPositionEdit;
    private boolean overlayPermissionReturned;

    private final BroadcastReceiver overlayChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshOverlayStatus();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (FloatingSettings.TAP_ACTION.equals(key)) refreshOverlayStatus();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = SoundLibrary.get(this);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if (state != null) {
            pendingOverlayPermission = state.getBoolean("pendingOverlayPermission");
            pendingPositionEdit = state.getBoolean("pendingPositionEdit");
            overlayPermissionReturned = state.getBoolean("overlayPermissionReturned");
        }
        buildSettings();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("settingsScroll")));
        library.preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        IntentFilter filter = new IntentFilter(OverlayService.STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(overlayChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(overlayChanged, filter);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pendingOverlayPermission", pendingOverlayPermission);
        state.putBoolean("pendingPositionEdit", pendingPositionEdit);
        state.putBoolean("overlayPermissionReturned", overlayPermissionReturned);
        state.putInt("settingsScroll", scroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (overlayPermissionReturned && pendingOverlayPermission) {
            pendingOverlayPermission = false;
            overlayPermissionReturned = false;
            boolean editPosition = pendingPositionEdit;
            pendingPositionEdit = false;
            if (Settings.canDrawOverlays(this)) startOverlay(editPosition);
            else toast("悬浮窗需要允许在其他应用上层显示");
        }
        refreshOverlayStatus();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(overlayChanged);
        library.preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        super.onDestroy();
    }

    private void buildSettings() {
        LinearLayout page = Ui.screen(this);
        LinearLayout toolbar = Ui.row(this);
        Button back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "设置", 25, Ui.TEXT, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        titleLayout.setMarginStart(dp(12));
        toolbar.addView(title, titleLayout);
        Ui.below(page, toolbar, 0);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout sections = Ui.column(this);
        sections.setPadding(0, 0, 0, dp(24));
        scroll.addView(sections, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        buildOverlay(section(sections, "悬浮窗"));
        buildGestures(section(sections, "手势"));
        buildPlayback(section(sections, "播放"));
        Button about = Ui.button(this, getString(R.string.about_title), false);
        about.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        about.setOnClickListener(view -> startActivity(new Intent(this, AboutActivity.class)));
        Ui.below(sections, about, 12);
    }

    private LinearLayout section(LinearLayout parent, String name) {
        LinearLayout card = Ui.card(this);
        TextView heading = Ui.text(this, name, 18, Ui.TEXT, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        Ui.below(card, heading, 0);
        Ui.below(parent, card, 12);
        return card;
    }

    private void buildOverlay(LinearLayout card) {
        FloatingSettings floating = library.floating;
        LinearLayout row = Ui.row(this);
        LinearLayout status = Ui.column(this);
        Ui.below(status, Ui.text(this, "跨应用悬浮窗", 15, Ui.TEXT, true), 0);
        overlayStatus = Ui.text(this, "", 13, Ui.SECONDARY, false);
        overlayStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.below(status, overlayStatus, 2);
        row.addView(status, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        overlayToggle = Ui.button(this, "", true);
        overlayToggle.setOnClickListener(view -> {
            if (OverlayService.isRunning()) stopService(new Intent(this, OverlayService.class));
            else requestOverlay(false);
        });
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        action.setMarginStart(dp(8));
        row.addView(overlayToggle, action);
        Ui.below(card, row, 8);
        refreshOverlayStatus();

        Button position = Ui.button(this, "调整悬浮窗位置", false);
        position.setOnClickListener(view -> requestOverlay(true));
        Ui.below(card, position, 8);
        Switch locked = new Switch(this);
        locked.setText("锁定悬浮窗位置");
        locked.setTextColor(Ui.TEXT);
        locked.setTextSize(14);
        locked.setMinHeight(dp(48));
        locked.setChecked(floating.locked());
        locked.setOnCheckedChangeListener((view, checked) -> floating.setLocked(checked));
        Ui.below(card, locked, 0);
        Ui.slider(card, "悬浮按钮大小", floating.buttonSize(), FloatingSettings.MIN_BUTTON_SIZE_DP,
                FloatingSettings.MAX_BUTTON_SIZE_DP, 2, value -> value + " dp", floating::saveButtonSize);
        Ui.slider(card, "悬浮按钮透明度", floating.transparency(), 0, FloatingSettings.MAX_TRANSPARENCY,
                5, value -> value + "%", floating::saveTransparency);
    }

    private void buildGestures(LinearLayout card) {
        FloatingSettings floating = library.floating;
        Ui.slider(card, "长按打开轮盘", floating.holdMs(), FloatingSettings.MIN_HOLD_MS,
                FloatingSettings.MAX_HOLD_MS, 10, Ui::seconds, floating::saveHoldMs);
        Button tap = Ui.menuButton(this, "轻点悬浮按钮 · " + floating.tapAction().label);
        tap.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tap.setOnClickListener(view -> {
            FloatingSettings.TapAction[] actions = FloatingSettings.TapAction.values();
            String[] labels = new String[actions.length];
            for (int i = 0; i < actions.length; i++) labels[i] = actions[i].label;
            new AlertDialog.Builder(this).setTitle("轻点悬浮按钮")
                    .setSingleChoiceItems(labels, floating.tapAction().ordinal(), (dialog, index) -> {
                        floating.saveTapAction(actions[index]);
                        tap.setText("轻点悬浮按钮 · " + actions[index].label);
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).show();
        });
        Ui.below(card, tap, 4);
    }

    private void buildPlayback(LinearLayout card) {
        PlaybackSettings playback = library.playback;
        Ui.slider(card, "总音量", playback.masterVolume(), 0, PlaybackSettings.MAX_MASTER_VOLUME,
                1, value -> value + "%", value -> {
                    playback.saveMasterVolume(value);
                    SoundPlayer.adjustMasterVolume(this, value);
                });
        Ui.slider(card, "连续触发间隔", playback.sequenceMs(), PlaybackSettings.MIN_SEQUENCE_MS,
                PlaybackSettings.MAX_SEQUENCE_MS, 100, Ui::seconds, playback::saveSequenceMs);
    }

    private void refreshOverlayStatus() {
        boolean running = OverlayService.isRunning();
        overlayToggle.setText(running ? "关闭" : "开启");
        overlayToggle.setContentDescription(running ? "关闭跨应用悬浮窗" : "开启跨应用悬浮窗");
        overlayStatus.setText(running ? library.floating.gestureDescription() : "在其他应用中呼出轮盘");
    }

    private void requestOverlay(boolean editPosition) {
        if (Settings.canDrawOverlays(this)) {
            startOverlay(editPosition);
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许显示悬浮窗")
                .setMessage("在系统设置中找到「SR 音效轮盘」，开启「允许在其他应用上层显示」，然后返回。")
                .setPositiveButton("去设置", (dialog, which) -> openOverlaySettings(editPosition))
                .setNegativeButton("取消", null).show();
    }

    private void openOverlaySettings(boolean editPosition) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            pendingOverlayPermission = true;
            pendingPositionEdit = editPosition;
            overlayPermissionReturned = false;
            startActivityForResult(intent, OVERLAY_PERMISSION);
        } catch (ActivityNotFoundException e) {
            pendingOverlayPermission = false;
            pendingPositionEdit = false;
            toast("此设备没有提供悬浮窗权限页面");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY_PERMISSION) overlayPermissionReturned = true;
    }

    private void startOverlay(boolean editPosition) {
        try {
            Intent start = new Intent(this, OverlayService.class);
            if (editPosition) start.setAction(OverlayService.EDIT_POSITION);
            startForegroundService(start);
        } catch (IllegalStateException | SecurityException e) {
            toast("无法启动悬浮窗，请在应用内重试");
            return;
        }
        if (!editPosition && Build.VERSION.SDK_INT >= 33 && !library.preferences.getBoolean("notificationAsked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            library.preferences.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
