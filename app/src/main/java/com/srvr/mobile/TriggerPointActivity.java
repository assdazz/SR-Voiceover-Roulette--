package com.srvr.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TriggerPointActivity extends Activity {
    private static final int OVERLAY_PERMISSION = 41;
    private static final int NOTIFICATION_PERMISSION = 42;
    private static final int PICK_BACKGROUND = 43;
    private static final int NO_SELECTION = -1;
    private static final String BACKGROUND_FILENAME = "trigger_align_bg.img";

    private final Map<Integer, TriggerPointView> pointViews = new HashMap<>();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SRVR-TriggerBg");
        t.setDaemon(true);
        return t;
    });
    private SoundLibrary library;
    private ImageView backgroundImage;
    private Bitmap backgroundBitmap;
    private FrameLayout stage;
    private LinearLayout panel;
    private LinearLayout pointOptions;
    private Button permission;
    private Button pickBackgroundBtn;
    private Button clearBackgroundBtn;
    private CheckBox passthroughBox;
    private CheckBox showMarkersBox;
    private TextView shizukuHint;
    private int selected = NO_SELECTION;
    private float panelX = 1f;
    private float panelY;
    private boolean askedForOverlay;
    private boolean requestedOverlay;
    private boolean updatingChecks;

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
        startOverlayIfAllowed();
        super.onStop();
    }

    @Override protected void onDestroy() {
        imageExecutor.shutdownNow();
        if (backgroundImage != null) backgroundImage.setImageDrawable(null);
        if (backgroundBitmap != null) {
            backgroundBitmap.recycle();
            backgroundBitmap = null;
        }
        super.onDestroy();
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
        // Full-screen screenshot layer spans physical display bounds (0..W, 0..H) so game screenshots align 1:1.
        backgroundImage = new ImageView(this);
        backgroundImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        backgroundImage.setAlpha(0.85f);
        backgroundImage.setVisibility(View.GONE);
        root.addView(backgroundImage, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        stage = new FrameLayout(this);
        FrameLayout.LayoutParams stageParams = new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH);
        root.addView(stage, stageParams);
        if (Build.VERSION.SDK_INT >= 28) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var cutout = insets.getDisplayCutout();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) stage.getLayoutParams();
                int l = cutout == null ? 0 : cutout.getSafeInsetLeft();
                int t = cutout == null ? 0 : cutout.getSafeInsetTop();
                int r = cutout == null ? 0 : cutout.getSafeInsetRight();
                int b = cutout == null ? 0 : cutout.getSafeInsetBottom();
                if (lp.leftMargin != l || lp.topMargin != t || lp.rightMargin != r || lp.bottomMargin != b) {
                    lp.setMargins(l, t, r, b);
                    stage.setLayoutParams(lp);
                }
                return insets;
            });
        }
        for (TriggerPoints.Point point : library.triggerPoints.all()) addPointView(point);

        Button back = Ui.button(this, "返回", false);
        back.setElevation(dp(6));
        back.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams backLayout = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.LEFT);
        backLayout.setMargins(dp(10), dp(10), 0, 0);
        stage.addView(back, backLayout);

        panel = Ui.card(this);
        panel.setElevation(dp(8));
        TextView handle = Ui.text(this, "按住拖动面板 · 拖热区右下角可缩放范围", 12, Ui.SECONDARY, false);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setMinHeight(dp(34));
        movePanelWith(handle, panel);
        Ui.below(panel, handle, 0);

        Button create = Ui.button(this, "框选一个新的触发热区", true);
        create.setOnClickListener(view -> createPoint());
        Ui.below(panel, create, 4);

        LinearLayout bgRow = Ui.row(this);
        pickBackgroundBtn = Ui.button(this, "放置对齐截图", false);
        pickBackgroundBtn.setTextSize(12);
        pickBackgroundBtn.setOnClickListener(view -> openBackgroundPicker());
        Ui.weighted(bgRow, pickBackgroundBtn, 0);
        clearBackgroundBtn = Ui.button(this, "清除", false);
        clearBackgroundBtn.setTextSize(12);
        clearBackgroundBtn.setOnClickListener(view -> clearBackground());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        clearLp.setMarginStart(dp(6));
        bgRow.addView(clearBackgroundBtn, clearLp);
        Ui.below(panel, bgRow, 4);

        permission = Ui.button(this, "允许跨应用显示边框", false);
        permission.setOnClickListener(view -> requestPermission());
        Ui.below(panel, permission, 4);

        ScrollView optionsScroll = new ScrollView(this);
        LinearLayout scrollInner = Ui.column(this);

        passthroughBox = makeCheckBox("Shizuku 零拦截穿透（不挡游戏触摸）");
        passthroughBox.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingChecks) return;
            library.triggerPoints.setPassthroughEnabled(checked);
            if (checked && VirtualMicController.get(this).isShizukuRunning()
                    && !VirtualMicController.get(this).hasShizukuPermission()) {
                VirtualMicController.get(this).requestShizukuPermission();
            }
            renderSelection();
        });
        Ui.below(scrollInner, passthroughBox, 2);

        showMarkersBox = makeCheckBox("游戏中显示热区边框（取消则隐形）");
        showMarkersBox.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingChecks) return;
            library.triggerPoints.setShowMarkers(checked);
            renderSelection();
        });
        Ui.below(scrollInner, showMarkersBox, 0);

        shizukuHint = Ui.text(this, "", 11, Ui.SECONDARY, false);
        Ui.below(scrollInner, shizukuHint, 2);

        pointOptions = Ui.column(this);
        Ui.below(scrollInner, pointOptions, 6);

        optionsScroll.addView(scrollInner, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        Ui.below(panel, optionsScroll, 4);

        stage.addView(panel, anchored(dp(268), Ui.WRAP));
        panel.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                positionPanel());
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                positionStage());
        renderSelection();
        loadSavedBackground();
        setContentView(root);
        root.requestApplyInsets();
    }

    private File backgroundFile() {
        return new File(getFilesDir(), BACKGROUND_FILENAME);
    }

    private void openBackgroundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, PICK_BACKGROUND);
        } catch (ActivityNotFoundException e) {
            toast("无法打开系统相册/文件选择器");
        }
    }

    private void clearBackground() {
        File file = backgroundFile();
        if (file.exists()) file.delete();
        applyBackgroundBitmap(null);
        toast("已清除对齐背景图");
    }

    private void loadSavedBackground() {
        File file = backgroundFile();
        updateBackgroundButtons(file.isFile());
        if (!file.isFile()) return;
        imageExecutor.execute(() -> {
            Bitmap bmp = decodeScaledBitmap(file);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                applyBackgroundBitmap(bmp);
            });
        });
    }

    private void importBackgroundUri(Uri uri) {
        imageExecutor.execute(() -> {
            File target = backgroundFile();
            File tmp = new File(getFilesDir(), BACKGROUND_FILENAME + ".tmp");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("无法读取所选图片");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
            } catch (IOException | SecurityException e) {
                if (tmp.exists()) tmp.delete();
                runOnUiThread(() -> toast("导入背景图失败：" + e.getMessage()));
                return;
            }
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                runOnUiThread(() -> toast("保存背景图失败"));
                return;
            }
            Bitmap bmp = decodeScaledBitmap(target);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    target.delete();
                    applyBackgroundBitmap(null);
                    toast("无法解析该图片格式");
                } else {
                    applyBackgroundBitmap(bmp);
                    toast("已设置对齐背景图");
                }
            });
        });
    }

    private Bitmap decodeScaledBitmap(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int maxDim = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            int limit = Math.max(1920, maxDim);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= limit || bounds.outHeight / (sample * 2) >= limit) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyBackgroundBitmap(Bitmap bmp) {
        Bitmap old = backgroundBitmap;
        backgroundBitmap = bmp;
        if (backgroundImage != null) {
            backgroundImage.setImageBitmap(bmp);
            backgroundImage.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
        }
        if (old != null && old != bmp) old.recycle();
        updateBackgroundButtons(bmp != null);
    }

    private void updateBackgroundButtons(boolean hasBackground) {
        if (pickBackgroundBtn != null) {
            pickBackgroundBtn.setText(hasBackground ? "更换对齐截图" : "放置对齐截图");
        }
        if (clearBackgroundBtn != null) {
            clearBackgroundBtn.setVisibility(hasBackground ? View.VISIBLE : View.GONE);
        }
    }

    private CheckBox makeCheckBox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(12);
        box.setTextColor(Ui.TEXT);
        box.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        box.setMinHeight(dp(34));
        return box;
    }

    private void createPoint() {
        TriggerPoints.Point point = library.triggerPoints.create();
        addPointView(point);
        positionPoint(point, pointViews.get(point.number()));
        select(point.number());
        if (Settings.canDrawOverlays(this)) startOverlayIfAllowed();
        else if (!askedForOverlay && library.triggerPoints.showMarkers()) requestPermission();
    }

    private void addPointView(TriggerPoints.Point point) {
        int number = point.number();
        TriggerPointView view = new TriggerPointView(this, number);
        view.setOnClickListener(button -> select(number));
        attachZoneGesture(view, number);
        pointViews.put(number, view);
        stage.addView(view, anchored(dp(90), dp(90)));
    }

    private void select(int number) {
        if (selected == number) return;
        selected = number;
        renderSelection();
    }

    private void renderSelection() {
        updatingChecks = true;
        boolean passthrough = library.triggerPoints.passthroughEnabled();
        passthroughBox.setChecked(passthrough);
        showMarkersBox.setChecked(library.triggerPoints.showMarkers());
        showMarkersBox.setEnabled(passthrough);
        showMarkersBox.setAlpha(passthrough ? 1f : 0.46f);
        updatingChecks = false;

        VirtualMicController vmic = VirtualMicController.get(this);
        if (passthrough) {
            if (vmic.hasShizukuPermission()) {
                shizukuHint.setText("Shizuku 已就绪：退出后热区不阻挡任何游戏操作。");
                shizukuHint.setTextColor(Ui.ACCENT);
            } else if (vmic.isShizukuRunning()) {
                shizukuHint.setText("Shizuku 运行中但未授权，请先授权（或去麦克风页授权）。");
                shizukuHint.setTextColor(0xffffd27d);
            } else {
                shizukuHint.setText("未检测到 Shizuku，退出后将自动回退为普通悬浮窗。");
                shizukuHint.setTextColor(0xffff9b9b);
            }
        } else {
            shizukuHint.setText("当前为普通实体悬浮窗模式（框内触摸由本应用拦截）。");
            shizukuHint.setTextColor(Ui.SECONDARY);
        }

        for (Map.Entry<Integer, TriggerPointView> entry : pointViews.entrySet()) {
            int num = entry.getKey();
            TriggerPointView view = entry.getValue();
            TriggerPoints.Point pt = library.triggerPoints.find(num);
            Playable bound = pt == null ? null : library.playableById(pt.soundId());
            view.setLabel(bound == null ? null : bound.title());
            view.setHighlighted(num == selected);
            view.setShowResizeHandle(pt != null && !pt.locked());
        }
        updatePermissionButton();
        pointOptions.removeAllViews();
        TriggerPoints.Point point = library.triggerPoints.find(selected);
        if (point == null) return;
        Ui.below(pointOptions, Ui.text(this, "热区 " + selected, 14, Ui.TEXT, true), 0);
        Button lock = Ui.button(this, point.locked() ? "解锁热区（允许拖动/缩放）" : "固定热区位置与大小", false);
        lock.setTextSize(12);
        lock.setOnClickListener(view -> {
            library.triggerPoints.update(point.number(), value -> value.lock(!value.locked()));
            renderSelection();
        });
        Ui.below(pointOptions, lock, 4);
        Playable sound = library.playableById(point.soundId());
        Button bind = Ui.menuButton(this, sound == null ? "选择音效" : sound.title());
        bind.setMaxLines(2);
        bind.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bind.setContentDescription(sound == null ? "选择音效" : "切换音效，当前 " + sound.title());
        bind.setOnClickListener(view -> chooseSound(point));
        Ui.below(pointOptions, bind, 4);

        if (!point.locked()) {
            int wPct = Math.round(point.width() * 100);
            int hPct = Math.round(point.height() * 100);
            Ui.slider(pointOptions, "热区宽度", wPct, 4, 60, 1, value -> value + "%", value -> {
                library.triggerPoints.update(point.number(), saved -> saved.resize(value / 100f, saved.height()));
                TriggerPoints.Point updated = library.triggerPoints.find(point.number());
                if (updated != null) positionPoint(updated, pointViews.get(point.number()));
            });
            Ui.slider(pointOptions, "热区高度", hPct, 4, 60, 1, value -> value + "%", value -> {
                library.triggerPoints.update(point.number(), saved -> saved.resize(saved.width(), value / 100f));
                TriggerPoints.Point updated = library.triggerPoints.find(point.number());
                if (updated != null) positionPoint(updated, pointViews.get(point.number()));
            });
        }

        Button effects = Ui.button(this, "调整音效参数", false);
        effects.setEnabled(sound != null);
        effects.setOnClickListener(view -> editEffects(point, sound));
        Ui.below(pointOptions, effects, 4);
        Button delete = Ui.button(this, "删除热区", false);
        delete.setOnClickListener(view -> confirmDelete(point.number()));
        Ui.below(pointOptions, delete, 4);
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
        new AlertDialog.Builder(this).setTitle("选择热区音效")
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
        new AlertDialog.Builder(this).setTitle("热区 " + number).setView(scroll)
                .setPositiveButton("完成", null).show();
    }

    private void confirmDelete(int number) {
        new AlertDialog.Builder(this).setTitle("删除热区 " + number + "？")
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
        int width = Math.min(dp(268), stage.getWidth());
        if (width > 0 && layout.width != width) {
            layout.width = width;
            panel.setLayoutParams(layout);
        }
        int maxPanelHeight = Math.max(dp(180), stage.getHeight() - dp(16));
        if (stage.getHeight() > 0 && panel.getHeight() > maxPanelHeight) {
            layout.height = maxPanelHeight;
            panel.setLayoutParams(layout);
        }
        positionPanel();
    }

    private void positionPanel() {
        panel.setTranslationX(TriggerPoints.offset(panelX, stage.getWidth(), panel.getWidth()));
        panel.setTranslationY(TriggerPoints.offset(panelY, stage.getHeight(), panel.getHeight()));
    }

    private void positionPoint(TriggerPoints.Point point, TriggerPointView view) {
        if (view == null || stage.getWidth() <= 0 || stage.getHeight() <= 0) return;
        int minPx = TriggerPointView.minSizePx(this);
        int w = TriggerPoints.span(point.width(), stage.getWidth(), minPx);
        int h = TriggerPoints.span(point.height(), stage.getHeight(), minPx);
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (layout.width != w || layout.height != h) {
            layout.width = w;
            layout.height = h;
            view.setLayoutParams(layout);
        }
        view.setTranslationX(TriggerPoints.offset(point.x(), stage.getWidth(), w));
        view.setTranslationY(TriggerPoints.offset(point.y(), stage.getHeight(), h));
    }

    private void saveGeometry(int number, View view) {
        int sw = stage.getWidth();
        int sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float newW = Math.max(TriggerPoints.MIN_SIZE, Math.min(TriggerPoints.MAX_SIZE, view.getWidth() / (float) sw));
        float newH = Math.max(TriggerPoints.MIN_SIZE, Math.min(TriggerPoints.MAX_SIZE, view.getHeight() / (float) sh));
        library.triggerPoints.update(number, point -> point.resize(newW, newH).at(
                TriggerPoints.fraction(view.getTranslationX(), sw, view.getWidth(), point.x()),
                TriggerPoints.fraction(view.getTranslationY(), sh, view.getHeight(), point.y())));
    }

    private void attachZoneGesture(TriggerPointView view, int number) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private final int slop = ViewConfiguration.get(TriggerPointActivity.this).getScaledTouchSlop();
            private float startX;
            private float startY;
            private float originX;
            private float originY;
            private int originW;
            private int originH;
            private boolean draggable;
            private boolean resizing;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startX = event.getRawX();
                        startY = event.getRawY();
                        originX = view.getTranslationX();
                        originY = view.getTranslationY();
                        originW = view.getWidth();
                        originH = view.getHeight();
                        TriggerPoints.Point current = library.triggerPoints.find(number);
                        draggable = current != null && !current.locked();
                        resizing = draggable && view.isInResizeHandle(event.getX(), event.getY());
                        moved = false;
                        select(number);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        if (draggable && (moved || Math.hypot(dx, dy) > slop)) {
                            moved = true;
                            if (resizing) {
                                int minPx = TriggerPointView.minSizePx(TriggerPointActivity.this);
                                int maxW = Math.max(minPx, Math.round(stage.getWidth() - originX));
                                int maxH = Math.max(minPx, Math.round(stage.getHeight() - originY));
                                int newW = Math.max(minPx, Math.min(maxW, Math.round(originW + dx)));
                                int newH = Math.max(minPx, Math.min(maxH, Math.round(originH + dy)));
                                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                                lp.width = newW;
                                lp.height = newH;
                                view.setLayoutParams(lp);
                            } else {
                                view.setTranslationX(confine(originX + dx, stage.getWidth() - view.getWidth()));
                                view.setTranslationY(confine(originY + dy, stage.getHeight() - view.getHeight()));
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (moved) {
                            saveGeometry(number, view);
                            if (resizing) renderSelection();
                        } else {
                            v.performClick();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (moved) saveGeometry(number, view);
                        moved = false;
                        draggable = false;
                        resizing = false;
                        return true;
                    }
                    default -> {
                        return true;
                    }
                }
            }
        });
    }

    private void movePanelWith(View handle, View target) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private final int slop = ViewConfiguration.get(TriggerPointActivity.this).getScaledTouchSlop();
            private float startX;
            private float startY;
            private float originX;
            private float originY;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startX = event.getRawX();
                        startY = event.getRawY();
                        originX = target.getTranslationX();
                        originY = target.getTranslationY();
                        moved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        if (moved || Math.hypot(dx, dy) > slop) {
                            moved = true;
                            target.setTranslationX(confine(originX + dx, stage.getWidth() - target.getWidth()));
                            target.setTranslationY(confine(originY + dy, stage.getHeight() - target.getHeight()));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) {
                            panelX = TriggerPoints.fraction(panel.getTranslationX(), stage.getWidth(), panel.getWidth(), panelX);
                            panelY = TriggerPoints.fraction(panel.getTranslationY(), stage.getHeight(), panel.getHeight(), panelY);
                        }
                        return true;
                    }
                    default -> {
                        return true;
                    }
                }
            }
        });
    }

    private void requestPermission() {
        askedForOverlay = true;
        new AlertDialog.Builder(this).setTitle("允许触发热区显示在其他应用上方")
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
        boolean needOverlay = !library.triggerPoints.passthroughEnabled() || library.triggerPoints.showMarkers();
        permission.setVisibility(!pointViews.isEmpty() && needOverlay && !Settings.canDrawOverlays(this)
                ? View.VISIBLE : View.GONE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY_PERMISSION && requestedOverlay) {
            requestedOverlay = false;
            if (!Settings.canDrawOverlays(this)) toast("授予悬浮窗权限后，热区边框才能显示在其他应用上方");
        } else if (request == PICK_BACKGROUND && result == RESULT_OK && data != null && data.getData() != null) {
            importBackgroundUri(data.getData());
        }
    }

    private void startOverlayIfAllowed() {
        if (library.triggerPoints.all().isEmpty()) return;
        boolean canPassthrough = library.triggerPoints.passthroughEnabled()
                && VirtualMicController.get(this).hasShizukuPermission();
        if (!canPassthrough && !Settings.canDrawOverlays(this)) return;
        if (TriggerOverlayService.isRunning()) return;
        try {
            startForegroundService(new Intent(this, TriggerOverlayService.class));
        } catch (IllegalStateException | SecurityException error) {
            toast("无法启动音效触发热区");
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
