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
import android.widget.Toast;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class TriggerOverlayService extends Service {
    private static final String STOP = "com.srvr.mobile.STOP_TRIGGER_POINTS";
    private static final String CHANNEL = "sound-trigger-points";
    private static final int NOTIFICATION = 2;
    private static TriggerOverlayService active;
    private static boolean editing;

    private record PointWindow(TriggerPointView view, WindowManager.LayoutParams layout, PointTouch touch) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, PointWindow> points = new HashMap<>();
    private SoundLibrary library;
    private Context overlayContext;
    private WindowManager windows;
    private DisplayManager displays;
    private AppOpsManager appOps;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (TriggerPoints.KEY.equals(key)) refresh();
    };

    private final AppOpsManager.OnOpChangedListener permissionChanged = (op, name) ->
            main.post(() -> {
                if (!Settings.canDrawOverlays(this)) stopSelf();
            });

    private final DisplayManager.DisplayListener displayChanged = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) refresh();
        }
    };

    static void setEditing(boolean value) {
        editing = value;
        if (active != null) active.refresh();
    }

    static boolean isRunning() {
        return active != null;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active == this) {
            refresh();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION, notification());
            library = SoundLibrary.get(this);
            displays = getSystemService(DisplayManager.class);
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("No default display");
            overlayContext = OverlayWindows.context(this, display);
            windows = overlayContext.getSystemService(WindowManager.class);
            displays.registerDisplayListener(displayChanged, main);
            appOps = getSystemService(AppOpsManager.class);
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), permissionChanged);
            library.preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
            active = this;
            refresh();
        } catch (SecurityException | IllegalStateException | WindowManager.BadTokenException error) {
            overlayFailed(error);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "音效固定触发点",
                NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent edit = PendingIntent.getActivity(this, 2, new Intent(this, TriggerPointActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent close = PendingIntent.getService(this, 3,
                new Intent(this, TriggerOverlayService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_overlay)
                .setContentTitle("SR 音效固定触发点")
                .setContentText("点蓝色触发区播放音效")
                .setContentIntent(edit)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_overlay, "关闭触发点", close).build())
                .build();
    }

    private void refresh() {
        if (windows == null) return;
        if (editing) {
            clearWindows();
            return;
        }
        var saved = library.triggerPoints.all();
        if (saved.isEmpty()) {
            clearWindows();
            stopSelf();
            return;
        }
        Map<Integer, TriggerPoints.Point> wanted = new HashMap<>();
        for (TriggerPoints.Point point : saved) wanted.put(point.number(), point);
        for (Iterator<Map.Entry<Integer, PointWindow>> iterator = points.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, PointWindow> existing = iterator.next();
            if (!wanted.containsKey(existing.getKey())) {
                remove(existing.getValue());
                iterator.remove();
            }
        }
        Rect area = OverlayWindows.fullDisplayArea(windows);
        if (area.isEmpty()) {
            clearWindows();
            return;
        }
        int size = TriggerPointView.size(overlayContext, area.width(), area.height());
        try {
            for (TriggerPoints.Point point : saved) {
                PointWindow window = points.get(point.number());
                int x = area.left + TriggerPoints.offset(point.x(), area.width(), size);
                int y = area.top + TriggerPoints.offset(point.y(), area.height(), size);
                if (window == null) {
                    TriggerPointView view = new TriggerPointView(overlayContext, point.number());
                    PointTouch touch = new PointTouch(view, point.number());
                    WindowManager.LayoutParams layout = OverlayWindows.params(size, size,
                            "SR 音效触发点 " + point.number());
                    layout.flags |= WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    OverlayWindows.allowDisplayCutout(layout);
                    layout.x = x;
                    layout.y = y;
                    windows.addView(view, layout);
                    points.put(point.number(), new PointWindow(view, layout, touch));
                } else if (window.layout().x != x || window.layout().y != y || window.layout().width != size) {
                    WindowManager.LayoutParams layout = window.layout();
                    layout.x = x;
                    layout.y = y;
                    layout.width = size;
                    layout.height = size;
                    windows.updateViewLayout(window.view(), layout);
                }
            }
        } catch (SecurityException | IllegalArgumentException | WindowManager.BadTokenException error) {
            overlayFailed(error);
        }
    }

    private void overlayFailed(RuntimeException error) {
        android.util.Log.w("SRVR", "Could not show fixed trigger points", error);
        Toast.makeText(this, "无法显示触发点，请检查悬浮窗权限", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        main.post(this::refresh);
    }

    private void clearWindows() {
        for (PointWindow window : points.values()) remove(window);
        points.clear();
    }

    private void remove(PointWindow window) {
        window.touch().cancel();
        if (window.view().getParent() != null) windows.removeViewImmediate(window.view());
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        if (library != null) library.preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if (appOps != null) appOps.stopWatchingMode(permissionChanged);
        if (displays != null) displays.unregisterDisplayListener(displayChanged);
        clearWindows();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private final class PointTouch implements View.OnTouchListener {
        private final View view;
        private final int number;
        private TriggerPoints.Point pressed;
        private Playable item;
        private SoundSettings.Trigger trigger;
        private SoundPlayer.Playback held;
        private boolean fired;
        private final Runnable play = this::playPressed;

        PointTouch(View view, int number) {
            this.view = view;
            this.number = number;
            view.setOnClickListener(button -> {
                TriggerPoints.Point point = library.triggerPoints.find(number);
                if (point == null) return;
                Playable sound = library.playableById(point.soundId());
                if (sound != null) SoundPlayer.playPoint(TriggerOverlayService.this, point, sound);
                else Toast.makeText(TriggerOverlayService.this, "请在固定触发点设置中选择音效",
                        Toast.LENGTH_SHORT).show();
            });
            view.setOnTouchListener(this);
        }

        @Override public boolean onTouch(View child, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = library.triggerPoints.find(number);
                    item = pressed == null ? null : library.playableById(pressed.soundId());
                    trigger = item instanceof SoundSet set ? set.trigger() : pressed == null ? null : pressed.settings().trigger;
                    fired = false;
                    view.setPressed(true);
                    if (item != null && trigger == SoundSettings.Trigger.HOLD) {
                        main.postDelayed(play, ViewConfiguration.getLongPressTimeout());
                    } else if (item != null && trigger == SoundSettings.Trigger.TAP) {
                        main.postDelayed(play, ViewConfiguration.getTapTimeout());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!inside(event)) cancel();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressed != null && inside(event) && !fired && (item == null || trigger != SoundSettings.Trigger.HOLD)) {
                        view.performClick();
                    }
                    cancel();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    return true;
                default:
                    return true;
            }
        }

        private boolean inside(MotionEvent event) {
            return event.getX() >= 0 && event.getY() >= 0
                    && event.getX() < view.getWidth() && event.getY() < view.getHeight();
        }

        private void playPressed() {
            if (pressed == null || item == null) return;
            fired = true;
            SoundPlayer.Playback playback = SoundPlayer.playPoint(TriggerOverlayService.this, pressed, item);
            if (trigger == SoundSettings.Trigger.HOLD) held = playback;
        }

        void cancel() {
            main.removeCallbacks(play);
            if (held != null) held.stop();
            held = null;
            pressed = null;
            item = null;
            view.setPressed(false);
        }
    }
}
