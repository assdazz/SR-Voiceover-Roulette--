package com.srvr.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class TriggerPointActivity extends Activity {
    private static final int OVERLAY_PERMISSION = 41;
    private static final int NOTIFICATION_PERMISSION = 42;
    private static final int NO_SELECTION = -1;
    private final Map<Integer, TriggerPointView> pointViews = new HashMap<>();
    private SoundLibrary library;
    private FrameLayout stage;
    private LinearLayout panel;
    private LinearLayout pointOptions;
    private Button permission;
    private int selected = NO_SELECTION;
    private float panelX = 1f;
    private float panelY;
    private boolean askedForOverlay;
    private boolean requestedOverlay;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = SoundLibrary.get(this);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if (state != null) {
            selected = state.getInt("selected", NO_SELECTION);
            panelX = state.getFloat("panelX", 1f);
            panelY = state.getFloat("panelY");
            askedForOverlay = state.getBoolean("askedForOverlay");
            requestedOverlay = state.getBoolean("requestedOverlay");
        }
        buildStage();
        hideSystemBars();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected", selected);
        state.putFloat("panelX", panelX);
        state.putFloat("panelY", panelY);
        state.putBoolean("askedForOverlay", askedForOverlay);
        state.putBoolean("requestedOverlay", requestedOverlay);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        TriggerOverlayService.setEditing(true);
    }

    @Override protected void onResume() {
        super.onResume();
        renderSelection();
        startOverlayIfAllowed();
    }

    @Override protected void onStop() {
        TriggerOverlayService.setEditing(false);
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    private void buildStage() {
        WindowManager.LayoutParams window = getWindow().getAttributes();
        OverlayWindows.allowDisplayCutout(window);
        getWindow().setAttributes(window);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var cutout = insets.getDisplayCutout();
                if (cutout == null) view.setPadding(0, 0, 0, 0);
                else view.setPadding(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(),
                        cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
                return insets;
            });
        }
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        for (TriggerPoints.Point point : library.triggerPoints.all()) addPointView(point);

        Button back = Ui.button(this, "返回", false);
        back.setElevation(dp(6));
        back.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams backLayout = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.LEFT);
        backLayout.setMargins(dp(10), dp(10), 0, 0);
        stage.addView(back, backLayout);

        panel = Ui.card(this);
        panel.setElevation(dp(8));
        TextView handle = Ui.text(this, "按住拖动面板", 13, Ui.SECONDARY, false);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setMinHeight(dp(36));
        moveWith(handle, panel, () -> true, () -> {}, () -> {
            panelX = TriggerPoints.fraction(panel.getTranslationX(), stage.getWidth(), panel.getWidth(), panelX);
            panelY = TriggerPoints.fraction(panel.getTranslationY(), stage.getHeight(), panel.getHeight(), panelY);
        });
        Ui.below(panel, handle, 0);
        Button create = Ui.button(this, "创建一个新的触发点", true);
        create.setOnClickListener(view -> createPoint());
        Ui.below(panel, create, 4);
        permission = Ui.button(this, "允许跨应用显示触发点", false);
        permission.setOnClickListener(view -> requestPermission());
        Ui.below(panel, permission, 4);
        ScrollView optionsScroll = new ScrollView(this);
        pointOptions = Ui.column(this);
        optionsScroll.addView(pointOptions, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        Ui.below(panel, optionsScroll, 8);
        stage.addView(panel, anchored(dp(248), Ui.WRAP));
        panel.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                positionPanel());
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                positionStage());
        renderSelection();
        setContentView(root);
        root.requestApplyInsets();
    }

    private void createPoint() {
        TriggerPoints.Point point = library.triggerPoints.create();
        addPointView(point);
        positionPoint(point, pointViews.get(point.number()));
        select(point.number());
        if (Settings.canDrawOverlays(this)) startOverlayIfAllowed();
        else if (!askedForOverlay) requestPermission();
    }

    private void addPointView(TriggerPoints.Point point) {
        int number = point.number();
        TriggerPointView view = new TriggerPointView(this, number);
        view.setOnClickListener(button -> select(number));
        moveWith(view, view, () -> {
            TriggerPoints.Point current = library.triggerPoints.find(number);
            return current != null && !current.locked();
        }, () -> select(number), () -> savePosition(number, view));
        pointViews.put(number, view);
        stage.addView(view, anchored(dp(64), dp(64)));
    }

    private void select(int number) {
        if (selected == number) return;
        selected = number;
        renderSelection();
    }

    private void renderSelection() {
        for (Map.Entry<Integer, TriggerPointView> entry : pointViews.entrySet()) {
            entry.getValue().setHighlighted(entry.getKey() == selected);
        }
        updatePermissionButton();
        pointOptions.removeAllViews();
        TriggerPoints.Point point = library.triggerPoints.find(selected);
        if (point == null) return;
        Ui.below(pointOptions, Ui.text(this, "触发点 " + selected, 14, Ui.TEXT, true), 0);
        Button lock = Ui.button(this, point.locked() ? "解锁触发点" : "固定触发点", false);
        lock.setOnClickListener(view -> {
            library.triggerPoints.update(point.number(), value -> value.lock(!value.locked()));
            renderSelection();
        });
        Ui.below(pointOptions, lock, 5);
        Playable sound = library.playableById(point.soundId());
        Button bind = Ui.menuButton(this, sound == null ? "选择音效" : sound.title());
        bind.setMaxLines(2);
        bind.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bind.setContentDescription(sound == null ? "选择音效" : "切换音效，当前 " + sound.title());
        bind.setOnClickListener(view -> chooseSound(point));
        Ui.below(pointOptions, bind, 5);
        Button effects = Ui.button(this, "调整参数", false);
        effects.setEnabled(sound != null);
        effects.setOnClickListener(view -> editEffects(point, sound));
        Ui.below(pointOptions, effects, 5);
        Button delete = Ui.button(this, "删除触发点", false);
        delete.setOnClickListener(view -> confirmDelete(point.number()));
        Ui.below(pointOptions, delete, 5);
    }

    private void chooseSound(TriggerPoints.Point point) {
        List<Playable> sounds = library.playables();
        String[] choices = new String[sounds.size() + 1];
        choices[0] = "清除音效绑定";
        int checked = 0;
        for (int i = 0; i < sounds.size(); i++) {
            Playable item = sounds.get(i);
            choices[i + 1] = item instanceof SoundLibrary.Sound sound
                    ? sound.title() + " · " + sound.format() : item.title() + " · 播放集";
            if (item.id().equals(point.soundId())) checked = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("选择触发点音效")
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    Playable item = which == 0 ? null : sounds.get(which - 1);
                    String soundId = item == null ? "" : item.id();
                    if (!soundId.equals(point.soundId())) {
                        SoundSettings settings = item instanceof SoundLibrary.Sound sound
                                ? library.settings(sound) : new SoundSettings();
                        library.triggerPoints.update(point.number(), value -> value.bind(soundId, settings));
                        SoundPlayer.resetPointSequence(point.number());
                    }
                    dialog.dismiss();
                    renderSelection();
                }).setNegativeButton("取消", null).show();
    }

    private void editEffects(TriggerPoints.Point point, Playable item) {
        if (item instanceof SoundSet set) {
            startActivity(new Intent(this, SoundSetActivity.class).putExtra(SoundSetActivity.SET_ID, set.id()));
            return;
        }
        SoundSettings settings = point.settings();
        int number = point.number();
        SoundSettingsPanel.Actions actions = new SoundSettingsPanel.Actions(
                value -> library.triggerPoints.update(number, saved -> saved.bind(item.id(), value)),
                () -> SoundPlayer.resetPointSequence(number),
                () -> SoundPlayer.playPoint(this, point.bind(item.id(), settings), item),
                volume -> SoundPlayer.adjustPointVolume(this, number, volume));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(new SoundSettingsPanel(this, library, item.title() + " · 参数", settings, actions, true),
                new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(this).setTitle("触发点 " + number).setView(scroll)
                .setPositiveButton("完成", null).show();
    }

    private void confirmDelete(int number) {
        new AlertDialog.Builder(this).setTitle("删除触发点 " + number + "？")
                .setPositiveButton("删除", (dialog, which) -> {
                    library.triggerPoints.remove(number);
                    stage.removeView(pointViews.remove(number));
                    SoundPlayer.discardPoint(number);
                    if (selected == number) selected = NO_SELECTION;
                    renderSelection();
                }).setNegativeButton("取消", null).show();
    }

    private void positionStage() {
        for (TriggerPoints.Point point : library.triggerPoints.all()) positionPoint(point, pointViews.get(point.number()));
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int width = Math.min(dp(248), stage.getWidth());
        if (width > 0 && layout.width != width) {
            layout.width = width;
            panel.setLayoutParams(layout);
        }
        positionPanel();
    }

    private void positionPanel() {
        panel.setTranslationX(TriggerPoints.offset(panelX, stage.getWidth(), panel.getWidth()));
        panel.setTranslationY(TriggerPoints.offset(panelY, stage.getHeight(), panel.getHeight()));
    }

    private void positionPoint(TriggerPoints.Point point, TriggerPointView view) {
        if (view == null) return;
        int size = TriggerPointView.size(this, stage.getWidth(), stage.getHeight());
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (layout.width != size) {
            layout.width = size;
            layout.height = size;
            view.setLayoutParams(layout);
        }
        view.setTranslationX(TriggerPoints.offset(point.x(), stage.getWidth(), size));
        view.setTranslationY(TriggerPoints.offset(point.y(), stage.getHeight(), size));
    }

    private void savePosition(int number, View view) {
        library.triggerPoints.update(number, point -> point.at(
                TriggerPoints.fraction(view.getTranslationX(), stage.getWidth(), view.getWidth(), point.x()),
                TriggerPoints.fraction(view.getTranslationY(), stage.getHeight(), view.getHeight(), point.y())));
    }

    private void moveWith(View handle, View target, BooleanSupplier canMove, Runnable begin, Runnable finish) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private final int slop = ViewConfiguration.get(TriggerPointActivity.this).getScaledTouchSlop();
            private float startX;
            private float startY;
            private float originX;
            private float originY;
            private boolean draggable;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        originX = target.getTranslationX();
                        originY = target.getTranslationY();
                        draggable = canMove.getAsBoolean();
                        moved = false;
                        begin.run();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        if (draggable && (moved || Math.hypot(dx, dy) > slop)) {
                            moved = true;
                            target.setTranslationX(confine(originX + dx, stage.getWidth() - target.getWidth()));
                            target.setTranslationY(confine(originY + dy, stage.getHeight() - target.getHeight()));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) finish.run();
                        else view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) finish.run();
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (moved) finish.run();
                        moved = false;
                        draggable = false;
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void requestPermission() {
        askedForOverlay = true;
        new AlertDialog.Builder(this).setTitle("允许触发点显示在其他应用上方")
                .setMessage("在系统设置中为「SR 音效轮盘」开启悬浮窗权限。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        requestedOverlay = true;
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION);
                    } catch (ActivityNotFoundException error) {
                        requestedOverlay = false;
                        toast("此设备没有提供悬浮窗权限页面");
                    }
                }).setNegativeButton("稍后", null).show();
    }

    private void updatePermissionButton() {
        permission.setVisibility(!pointViews.isEmpty() && !Settings.canDrawOverlays(this) ? View.VISIBLE : View.GONE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY_PERMISSION && requestedOverlay) {
            requestedOverlay = false;
            if (!Settings.canDrawOverlays(this)) toast("授予悬浮窗权限后，触发点才能显示在其他应用上方");
        }
    }

    private void startOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this) || library.triggerPoints.all().isEmpty()
                || TriggerOverlayService.isRunning()) return;
        try {
            startForegroundService(new Intent(this, TriggerOverlayService.class));
        } catch (IllegalStateException | SecurityException error) {
            toast("无法启动固定触发点");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !library.preferences.getBoolean("notificationAsked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            library.preferences.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private static float confine(float value, int maximum) {
        return Math.max(0, Math.min(value, maximum));
    }

    private static FrameLayout.LayoutParams anchored(int width, int height) {
        return new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
