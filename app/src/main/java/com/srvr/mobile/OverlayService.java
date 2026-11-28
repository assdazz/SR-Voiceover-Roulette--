package com.srvr.mobile;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

public final class OverlayService extends Service {
    static final String STATE_CHANGED = "com.srvr.mobile.OVERLAY_STATE_CHANGED";
    static final String EDIT_POSITION = "com.srvr.mobile.EDIT_OVERLAY_POSITION";
    private static final String STOP = "com.srvr.mobile.STOP_OVERLAY";
    private static final String CHANNEL = "sound-wheel-overlay";
    private static final int NOTIFICATION_ID = 1;
    private static boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private DisplayManager displays;
    private AppOpsManager appOps;
    private Context windowContext;
    private SoundLibrary library;
    private ImageButton bubble;
    private WindowManager.LayoutParams bubbleParams;
    private BubbleNavigation bubbleNavigation;
    private FloatingPositionEditor positionEditor;
    private WindowManager.LayoutParams editorParams;
    private FrameLayout panel;
    private WindowManager.LayoutParams panelParams;
    private WheelView panelWheel;
    private FloatingSettings.Position position;
    private boolean temporaryPositionEditor;

    private final AppOpsManager.OnOpChangedListener overlayPermission = (op, packageName) ->
            main.post(() -> {
                if (!Settings.canDrawOverlays(this)) stopSelf();
            });

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (key == null) return;
        if (key.equals(SoundLibrary.BANK) || key.equals(SoundLibrary.GROUPS)
                || key.equals(SoundLibrary.IMPORTS) || key.equals(SoundLibrary.SETS)
                || key.startsWith(SoundLibrary.SLOT_PREFIX)) refreshPanel();
        if ((key.equals(FloatingSettings.WHEEL_SIZE)
                || key.equals(FloatingSettings.WHEEL_HUB_RATIO)
                || key.equals(FloatingSettings.WHEEL_CONTROL_THICKNESS)) && panel != null) {
            if (sizeAndLocatePanel()) update(panel, panelParams);
            else dismissPanel();
        }
        if (key.equals(FloatingSettings.BUTTON_SIZE)) layoutWindows();
        if (key.equals(FloatingSettings.TRANSPARENCY) && bubble != null) {
            Ui.styleFloatingButton(bubble, library.floating);
        }
        if (key.equals(FloatingSettings.TAP_ACTION) && bubble != null) {
            configureBubbleTap();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    };

    private final DisplayManager.DisplayListener displayChanged = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) layoutWindows();
        }
    };

    static boolean isRunning() {
        return running;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean editPosition = EDIT_POSITION.equals(intent.getAction());
        if (running) {
            if (editPosition) showPositionEditor();
            return START_NOT_STICKY;
        }
        temporaryPositionEditor = editPosition;
        try {
            library = SoundLibrary.get(this);
            startForeground(NOTIFICATION_ID, notification());
            displays = getSystemService(DisplayManager.class);
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("No default display");
            windowContext = OverlayWindows.context(this, display);
            windows = windowContext.getSystemService(WindowManager.class);
            position = library.floating.position();
            attachBubble();
            displays.registerDisplayListener(displayChanged, main);
            appOps = getSystemService(AppOpsManager.class);
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), overlayPermission);
            library.preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
            running = true;
            announceState();
            if (editPosition) showPositionEditor();
        } catch (SecurityException | IllegalStateException | WindowManager.BadTokenException e) {
            android.util.Log.w("SRVR", "Could not show floating wheel", e);
            Toast.makeText(this, "系统无法显示悬浮窗，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "音效轮盘悬浮窗",
                NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open, flags);
        PendingIntent close = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_overlay)
                .setContentTitle("SR 音效轮盘悬浮窗已开启")
                .setContentText(library.floating.gestureDescription())
                .setContentIntent(openApp)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_overlay, "关闭悬浮窗", close).build())
                .build();
    }

    private void attachBubble() {
        bubble = Ui.floatingButton(windowContext, library.floating);
        configureBubbleTap();
        bubble.setOnLongClickListener(view -> showPanel());
        bubbleNavigation = new BubbleNavigation();
        bubble.setOnTouchListener(bubbleNavigation);
        int size = dp(library.floating.buttonSize());
        bubbleParams = OverlayWindows.params(size, size, "SR 音效轮盘悬浮按钮");
        bubbleParams.flags |= WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        locateBubble();
        windows.addView(bubble, bubbleParams);
    }

    private void configureBubbleTap() {
        FloatingSettings.TapAction action = library.floating.tapAction();
        bubble.setContentDescription(library.floating.gestureDescription());
        switch (action) {
            case REPLAY -> bubble.setOnClickListener(view -> replayLast());
            case OPEN_WHEEL -> bubble.setOnClickListener(view -> showPanel());
            case NONE -> bubble.setOnClickListener(null);
        }
        bubble.setClickable(action != FloatingSettings.TapAction.NONE);
    }

    private Rect safeArea() {
        return OverlayWindows.safeArea(windows);
    }

    private Rect bubbleTravel() {
        Rect area = safeArea();
        int margin = dp(FloatingSettings.POSITION_MARGIN_DP);
        int left = area.left + margin;
        int top = area.top + margin;
        return new Rect(left, top, Math.max(left, area.right - margin - bubbleParams.width),
                Math.max(top, area.bottom - margin - bubbleParams.height));
    }

    private void locateBubble() {
        Rect travel = bubbleTravel();
        bubbleParams.x = travel.left + Math.round(position.x() * travel.width());
        bubbleParams.y = travel.top + Math.round(position.y() * travel.height());
    }

    private void replayLast() {
        Playable sound = library.lastPlayed();
        if (sound == null) {
            Toast.makeText(this, "还没有播放记录，可长按打开轮盘", Toast.LENGTH_SHORT).show();
        } else {
            SoundPlayer.play(this, sound);
        }
    }

    private void showPositionEditor() {
        if (positionEditor != null) return;
        bubbleNavigation.cancel();
        dismissPanel();
        positionEditor = new FloatingPositionEditor(windowContext, library.floating, position, chosen -> {
            position = chosen;
            library.floating.savePosition(chosen);
            finishPositionEditor();
            if (!temporaryPositionEditor) layoutWindows();
            announceState();
        }, this::finishPositionEditor);
        editorParams = OverlayWindows.params(Ui.WRAP, Ui.WRAP, "SR 调整悬浮窗位置");
        editorParams.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        locatePositionEditor();
        try {
            windows.addView(positionEditor, editorParams);
            bubble.setVisibility(View.INVISIBLE);
        } catch (SecurityException | WindowManager.BadTokenException e) {
            finishPositionEditor();
            Toast.makeText(this, "无法打开位置调整界面", Toast.LENGTH_SHORT).show();
        }
    }

    private void locatePositionEditor() {
        Rect area = safeArea();
        editorParams.x = area.left;
        editorParams.y = area.top;
        editorParams.width = Math.max(1, area.width());
        editorParams.height = Math.max(1, area.height());
    }

    private void closePositionEditor() {
        remove(positionEditor);
        positionEditor = null;
        if (!temporaryPositionEditor && bubble != null && panel == null) bubble.setVisibility(View.VISIBLE);
    }

    private void finishPositionEditor() {
        closePositionEditor();
        if (temporaryPositionEditor) stopSelf();
    }

    private boolean showPanel() {
        if (panel != null || positionEditor != null) return false;
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return false;
        }
        panel = new FrameLayout(windowContext) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    dismissPanel();
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) dismissPanel();
                return true;
            }
        };
        panelWheel = new WheelView(windowContext, sound -> {},
                slot -> Toast.makeText(this, "空槽位，可在应用内分配音效", Toast.LENGTH_SHORT).show(),
                this::dismissPanel);
        panel.addView(panelWheel, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        panelParams = OverlayWindows.params(Ui.WRAP, Ui.WRAP, "SR 音效轮盘");
        panelParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        refreshPanel();
        if (!sizeAndLocatePanel()) {
            dismissPanel();
            Toast.makeText(this, "可用空间不足，无法打开悬浮轮盘", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            windows.addView(panel, panelParams);
            // The bubble keeps the initial touch sequence for a long-press and drag.
            if (!bubbleNavigation.wheelGesture) bubble.setVisibility(View.INVISIBLE);
            return true;
        } catch (SecurityException | WindowManager.BadTokenException e) {
            dismissPanel();
            if (!Settings.canDrawOverlays(this)) stopSelf();
            else Toast.makeText(this, "无法打开悬浮轮盘", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void refreshPanel() {
        if (panelWheel == null) return;
        int page = library.page();
        panelWheel.setPage(page, library.pageName(page), library.pageSounds());
    }

    private boolean sizeAndLocatePanel() {
        Rect area = safeArea();
        int anchorX = bubbleParams.x + bubbleParams.width / 2;
        int anchorY = bubbleParams.y + bubbleParams.height / 2;
        boolean opensRight = anchorX <= area.centerX();
        int sizePercent = library.floating.wheelSize();
        int hubPercent = library.floating.wheelHubPercent();
        int controlPercent = library.floating.wheelControlPercent();
        WheelGeometry.Panel bounds = WheelGeometry.place(area.left, area.top, area.right, area.bottom,
                anchorX, anchorY, dp(WheelGeometry.PADDING_DP),
                WheelView.preferredRadius(windowContext, sizePercent),
                WheelGeometry.hubRatio(hubPercent), opensRight);
        if (bounds == null) return false;
        panelWheel.configure(sizePercent, hubPercent, controlPercent, opensRight);
        panelParams.width = bounds.width();
        panelParams.height = bounds.height();
        panelParams.x = bounds.left();
        panelParams.y = bounds.top();
        return true;
    }

    private void dismissPanel() {
        remove(panel);
        panel = null;
        panelWheel = null;
        if (bubble != null && positionEditor == null) bubble.setVisibility(View.VISIBLE);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        main.post(this::layoutWindows);
    }

    private void layoutWindows() {
        if (bubble == null) return;
        int size = dp(library.floating.buttonSize());
        bubbleParams.width = size;
        bubbleParams.height = size;
        Ui.styleFloatingButton(bubble, library.floating);
        locateBubble();
        update(bubble, bubbleParams);
        if (panel != null) {
            if (sizeAndLocatePanel()) update(panel, panelParams);
            else dismissPanel();
        }
        if (positionEditor != null) {
            locatePositionEditor();
            update(positionEditor, editorParams);
        }
    }

    private void update(View view, WindowManager.LayoutParams layout) {
        try {
            windows.updateViewLayout(view, layout);
        } catch (IllegalArgumentException | SecurityException e) {
            stopSelf();
        }
    }

    private void remove(View view) {
        if (view != null && view.getParent() != null) {
            windows.removeViewImmediate(view);
        }
    }

    private void announceState() {
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    }

    @Override public void onDestroy() {
        if (appOps != null) appOps.stopWatchingMode(overlayPermission);
        if (displays != null) displays.unregisterDisplayListener(displayChanged);
        if (library != null) library.preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if (bubbleNavigation != null) bubbleNavigation.cancel();
        closePositionEditor();
        dismissPanel();
        remove(bubble);
        bubble = null;
        if (!temporaryPositionEditor) SoundPlayer.stop();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        announceState();
        super.onDestroy();
    }

    private int dp(int value) {
        return Ui.dp(windowContext, value);
    }

    private final class BubbleNavigation implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
        private float startX;
        private float startY;
        private int originX;
        private int originY;
        private boolean dragging;
        private boolean canceled;
        private boolean wheelGesture;
        private boolean movedOnWheel;
        private final Runnable hold = () -> {
            wheelGesture = true;
            wheelGesture = bubble.performLongClick();
            canceled = !wheelGesture;
            bubble.setPressed(false);
        };

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    originX = bubbleParams.x;
                    originY = bubbleParams.y;
                    dragging = false;
                    canceled = false;
                    wheelGesture = false;
                    movedOnWheel = false;
                    view.setPressed(true);
                    main.postAtTime(hold, event.getDownTime() + library.floating.holdMs());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (canceled) return true;
                    if (wheelGesture) {
                        if (panelWheel != null && (movedOnWheel || movedPastSlop(event))) {
                            movedOnWheel = true;
                            panelWheel.moveFromBubble(event.getRawX(), event.getRawY());
                        }
                        return true;
                    }
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    if (!dragging && Math.hypot(dx, dy) <= slop) return true;
                    main.removeCallbacks(hold);
                    view.setPressed(false);
                    if (library.floating.locked()) {
                        canceled = true;
                        return true;
                    }
                    dragging = true;
                    Rect travel = bubbleTravel();
                    bubbleParams.x = clamp(originX + Math.round(dx), travel.left, travel.right);
                    bubbleParams.y = clamp(originY + Math.round(dy), travel.top, travel.bottom);
                    update(bubble, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(hold);
                    view.setPressed(false);
                    if (wheelGesture) {
                        wheelGesture = false;
                        if (panelWheel != null && (movedOnWheel || movedPastSlop(event))) {
                            panelWheel.finishFromBubble(event.getRawX(), event.getRawY());
                        }
                        if (panel != null) bubble.setVisibility(View.INVISIBLE);
                    } else if (dragging) savePosition();
                    else if (!canceled) view.performClick();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    return true;
                default:
                    return true;
            }
        }

        private boolean movedPastSlop(MotionEvent event) {
            return Math.hypot(event.getRawX() - startX, event.getRawY() - startY) > slop;
        }

        private void cancel() {
            main.removeCallbacks(hold);
            canceled = true;
            if (bubble != null) bubble.setPressed(false);
            if (dragging) savePosition();
            if (wheelGesture && panelWheel != null) {
                panelWheel.cancelTouch();
                bubble.setVisibility(View.INVISIBLE);
            }
            dragging = false;
            wheelGesture = false;
        }

        private void savePosition() {
            Rect travel = bubbleTravel();
            float x = travel.width() > 0 ? (bubbleParams.x - travel.left) / (float) travel.width() : position.x();
            float y = travel.height() > 0 ? (bubbleParams.y - travel.top) / (float) travel.height() : position.y();
            position = new FloatingSettings.Position(x, y);
            library.floating.savePosition(position);
        }
    }

    private int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
