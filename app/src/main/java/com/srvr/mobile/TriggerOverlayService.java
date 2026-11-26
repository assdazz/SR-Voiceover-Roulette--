package com.srvr.mobile;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

public final class TriggerOverlayService extends Service {
    private static final String TAG = "SRVR-TriggerOverlay";
    private static final long TOUCH_BIND_TIMEOUT_MS = 8000L;
    private static final String STOP = "com.srvr.mobile.STOP_TRIGGER_POINTS";
    private static final String CHANNEL = "sound-trigger-points";
    private static final int NOTIFICATION = 2;
    private static final int MAX_SLOTS = 16;

    private static TriggerOverlayService active;
    private static boolean editing;

    private record PointWindow(TriggerPointView view, WindowManager.LayoutParams layout,
            PointTouch touch, boolean passthrough) {}

    private record ActiveZone(TriggerPoints.Point point, Rect rect, Playable item,
            SoundSettings.Trigger trigger) {}

    private static final class SlotState {
        int zoneNumber = -1;
        TriggerPoints.Point point;
        Playable item;
        SoundSettings.Trigger trigger;
        SoundPlayer.Playback held;
        boolean inside;

        void reset() {
            if (held != null) {
                held.stop();
                held = null;
            }
            zoneNumber = -1;
            point = null;
            item = null;
            trigger = null;
            inside = false;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, PointWindow> points = new HashMap<>();
    private final List<ActiveZone> activeZones = new ArrayList<>();
    private final SlotState[] slotStates = new SlotState[MAX_SLOTS];

    private SoundLibrary library;
    private Context overlayContext;
    private WindowManager windows;
    private DisplayManager displays;
    private AppOpsManager appOps;

    private Rect currentDisplayBounds = new Rect();
    private boolean passthroughActive;
    private boolean touchBinding;
    private ITouchMonitorService touchService;
    private Shizuku.UserServiceArgs touchServiceArgs;
    private final Runnable touchBindTimeoutTask = this::handleTouchBindTimeout;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (TriggerPoints.KEY.equals(key)
                || TriggerPoints.KEY_PASSTHROUGH.equals(key)
                || TriggerPoints.KEY_SHOW_MARKERS.equals(key)) {
            refresh();
        }
    };

    private final AppOpsManager.OnOpChangedListener permissionChanged = (op, name) ->
            main.post(() -> {
                if (!Settings.canDrawOverlays(this) && !passthroughActive) {
                    stopSelf();
                } else {
                    refresh();
                }
            });

    private final DisplayManager.DisplayListener displayChanged = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) refresh();
        }
    };

    private final ITouchMonitorService.Callback.Stub touchCallback = new ITouchMonitorService.Callback.Stub() {
        @Override public void onTouchEvent(int slot, int action, float normX, float normY) {
            main.post(() -> handleShizukuTouch(slot, action, normX, normY));
        }
    };

    private final ServiceConnection touchConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.removeCallbacks(touchBindTimeoutTask);
            touchBinding = false;
            if (active != TriggerOverlayService.this || binder == null || !binder.pingBinder()) {
                return;
            }
            touchService = ITouchMonitorService.Stub.asInterface(binder);
            try {
                String res = touchService.start(touchCallback);
                if (res != null && res.startsWith("OK:")) {
                    passthroughActive = true;
                    Log.i(TAG, "Shizuku passthrough touch monitor started: " + res);
                    main.post(() -> {
                        updateNotification();
                        refresh();
                    });
                } else {
                    Log.w(TAG, "Shizuku touch monitor rejected: " + res);
                    passthroughActive = false;
                    main.post(() -> {
                        Toast.makeText(TriggerOverlayService.this,
                                "Shizuku 穿透监听启动失败，已回退普通模式：" + res, Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Shizuku touch monitor start failed", e);
                passthroughActive = false;
                main.post(TriggerOverlayService.this::refresh);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            main.removeCallbacks(touchBindTimeoutTask);
            touchBinding = false;
            touchService = null;
            passthroughActive = false;
            main.post(() -> {
                resetAllSlots();
                updateNotification();
                refresh();
            });
        }
    };

    static void setEditing(boolean value) {
        editing = value;
        if (active != null) active.refresh();
    }

    static boolean isRunning() {
        return active != null;
    }

    @Override public void onCreate() {
        super.onCreate();
        for (int i = 0; i < MAX_SLOTS; i++) slotStates[i] = new SlotState();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        library = SoundLibrary.get(this);
        boolean wantPassthrough = library.triggerPoints.passthroughEnabled()
                && VirtualMicController.get(this).hasShizukuPermission();
        if (!wantPassthrough && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active == this) {
            refresh();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION, notification());
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
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "音效固定触发热区",
                NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent edit = PendingIntent.getActivity(this, 2, new Intent(this, TriggerPointActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent close = PendingIntent.getService(this, 3,
                new Intent(this, TriggerOverlayService.class).setAction(STOP), flags);
        String subtitle = passthroughActive
                ? "Shizuku 零拦截穿透运行中（不阻挡游戏触摸）"
                : "普通悬浮触发区运行中";
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_overlay)
                .setContentTitle("SR 音效触发热区")
                .setContentText(subtitle)
                .setContentIntent(edit)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_overlay, "关闭触发热区", close).build())
                .build();
    }

    private void updateNotification() {
        if (active != this) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION, notification());
    }

    private void syncTouchMonitor(boolean shouldRun) {
        if (shouldRun) {
            if (touchService != null || touchBinding) return;
            if (!VirtualMicController.get(this).hasShizukuPermission()) return;
            touchBinding = true;
            try {
                touchServiceArgs = buildTouchServiceArgs();
                main.removeCallbacks(touchBindTimeoutTask);
                main.postDelayed(touchBindTimeoutTask, TOUCH_BIND_TIMEOUT_MS);
                Shizuku.bindUserService(touchServiceArgs, touchConnection);
            } catch (Throwable t) {
                main.removeCallbacks(touchBindTimeoutTask);
                touchBinding = false;
                passthroughActive = false;
                Log.w(TAG, "Failed to bind ShizukuTouchMonitorService", t);
            }
        } else {
            unbindTouchMonitor();
        }
    }

    private void handleTouchBindTimeout() {
        if (!touchBinding) return;
        Log.w(TAG, "Timed out binding ShizukuTouchMonitorService after " + TOUCH_BIND_TIMEOUT_MS + " ms");
        unbindTouchMonitor();
        if (active == this) {
            Toast.makeText(this,
                    "Shizuku 穿透监听连接超时，已回退普通模式（建议检查 Shizuku 版本/权限监控设置）",
                    Toast.LENGTH_LONG).show();
            updateNotification();
            refresh();
        }
    }

    private void unbindTouchMonitor() {
        main.removeCallbacks(touchBindTimeoutTask);
        passthroughActive = false;
        touchBinding = false;
        resetAllSlots();
        ITouchMonitorService svc = touchService;
        touchService = null;
        if (svc != null) {
            try {
                svc.stop();
            } catch (RemoteException ignored) {}
        }
        if (touchServiceArgs != null && VirtualMicController.get(this).isShizukuRunning()) {
            try {
                Shizuku.unbindUserService(touchServiceArgs, touchConnection, true);
            } catch (Throwable ignored) {}
        }
        touchServiceArgs = null;
    }

    private Shizuku.UserServiceArgs buildTouchServiceArgs() {
        int versionCode = 1;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionCode = (int) info.getLongVersionCode();
        } catch (Throwable ignored) {}
        return new Shizuku.UserServiceArgs(new ComponentName(getPackageName(),
                ShizukuTouchMonitorService.class.getName()))
                .daemon(false)
                .processNameSuffix("shizuku_touch")
                .debuggable(false)
                .version(versionCode);
    }

    private void refresh() {
        if (windows == null) return;
        if (editing) {
            clearWindows();
            activeZones.clear();
            resetAllSlots();
            return;
        }
        List<TriggerPoints.Point> saved = library.triggerPoints.all();
        saved.removeIf(point -> library.playableById(point.soundId()) == null);
        if (saved.isEmpty()) {
            clearWindows();
            activeZones.clear();
            unbindTouchMonitor();
            stopSelf();
            return;
        }

        boolean wantPassthrough = library.triggerPoints.passthroughEnabled()
                && VirtualMicController.get(this).hasShizukuPermission();
        syncTouchMonitor(wantPassthrough);

        boolean usePassthrough = wantPassthrough && passthroughActive;
        boolean canDrawOverlay = Settings.canDrawOverlays(this);
        boolean shouldShowWindows = canDrawOverlay && (!usePassthrough || library.triggerPoints.showMarkers());

        Rect area = OverlayWindows.fullDisplayArea(windows);
        currentDisplayBounds = OverlayWindows.displayBounds(windows);
        if (area.isEmpty() || currentDisplayBounds.isEmpty()) {
            clearWindows();
            activeZones.clear();
            return;
        }

        int minPx = TriggerPointView.minSizePx(overlayContext);
        activeZones.clear();
        for (TriggerPoints.Point point : saved) {
            Playable item = library.playableById(point.soundId());
            if (item == null) continue;
            int w = TriggerPoints.span(point.width(), area.width(), minPx);
            int h = TriggerPoints.span(point.height(), area.height(), minPx);
            int x = area.left + TriggerPoints.offset(point.x(), area.width(), w);
            int y = area.top + TriggerPoints.offset(point.y(), area.height(), h);
            SoundSettings.Trigger trig = item instanceof SoundSet set
                    ? set.trigger() : point.settings().trigger;
            activeZones.add(new ActiveZone(point, new Rect(x, y, x + w, y + h), item, trig));
        }

        if (!shouldShowWindows) {
            clearWindows();
            return;
        }

        Map<Integer, ActiveZone> wanted = new HashMap<>();
        for (ActiveZone z : activeZones) wanted.put(z.point().number(), z);

        for (Iterator<Map.Entry<Integer, PointWindow>> iterator = points.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Integer, PointWindow> existing = iterator.next();
            ActiveZone target = wanted.get(existing.getKey());
            if (target == null || existing.getValue().passthrough() != usePassthrough) {
                remove(existing.getValue());
                iterator.remove();
            }
        }

        try {
            for (ActiveZone zone : activeZones) {
                int num = zone.point().number();
                Rect r = zone.rect();
                PointWindow window = points.get(num);
                if (window == null) {
                    TriggerPointView view = new TriggerPointView(overlayContext, num);
                    view.setLabel(zone.item().title());
                    view.setPassthroughMarker(usePassthrough);
                    PointTouch touch = usePassthrough ? null : new PointTouch(view, num);
                    WindowManager.LayoutParams layout = OverlayWindows.params(r.width(), r.height(),
                            "SR 音效触发热区 " + num);
                    layout.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    if (usePassthrough) {
                        layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    } else {
                        layout.flags |= WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
                    }
                    OverlayWindows.allowDisplayCutout(layout);
                    layout.x = r.left;
                    layout.y = r.top;
                    windows.addView(view, layout);
                    points.put(num, new PointWindow(view, layout, touch, usePassthrough));
                } else {
                    window.view().setLabel(zone.item().title());
                    window.view().setPassthroughMarker(usePassthrough);
                    WindowManager.LayoutParams layout = window.layout();
                    if (layout.x != r.left || layout.y != r.top
                            || layout.width != r.width() || layout.height != r.height()) {
                        layout.x = r.left;
                        layout.y = r.top;
                        layout.width = r.width();
                        layout.height = r.height();
                        windows.updateViewLayout(window.view(), layout);
                    }
                }
            }
        } catch (SecurityException | IllegalArgumentException | WindowManager.BadTokenException error) {
            if (!usePassthrough) overlayFailed(error);
        }
    }

    private void handleShizukuTouch(int slot, int action, float rawNormX, float rawNormY) {
        if (!passthroughActive || editing || slot < 0 || slot >= MAX_SLOTS) return;
        if (activeZones.isEmpty() || currentDisplayBounds.isEmpty()) return;

        Display display = displays != null ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;

        float sx;
        float sy;
        switch (rotation) {
            case Surface.ROTATION_90 -> {
                sx = rawNormY;
                sy = 1f - rawNormX;
            }
            case Surface.ROTATION_180 -> {
                sx = 1f - rawNormX;
                sy = 1f - rawNormY;
            }
            case Surface.ROTATION_270 -> {
                sx = 1f - rawNormY;
                sy = rawNormX;
            }
            default -> {
                sx = rawNormX;
                sy = rawNormY;
            }
        }

        int px = Math.round(sx * currentDisplayBounds.width());
        int py = Math.round(sy * currentDisplayBounds.height());
        SlotState state = slotStates[slot];

        if (action == ITouchMonitorService.ACTION_DOWN) {
            state.reset();
            ActiveZone hit = findHitZone(px, py);
            if (hit == null) return;
            state.zoneNumber = hit.point().number();
            state.point = hit.point();
            state.item = hit.item();
            state.trigger = hit.trigger();
            state.inside = true;
            setZoneHighlight(state.zoneNumber, true);

            if (state.trigger == SoundSettings.Trigger.TAP) {
                SoundPlayer.playPoint(this, state.point, state.item);
            } else if (state.trigger == SoundSettings.Trigger.HOLD) {
                state.held = SoundPlayer.playPoint(this, state.point, state.item);
            }
        } else if (action == ITouchMonitorService.ACTION_MOVE) {
            if (state.zoneNumber < 0) return;
            ActiveZone zone = findZoneByNumber(state.zoneNumber);
            boolean nowInside = zone != null && zone.rect().contains(px, py);
            if (state.inside && !nowInside) {
                state.inside = false;
                setZoneHighlight(state.zoneNumber, false);
                if (state.trigger == SoundSettings.Trigger.HOLD && state.held != null) {
                    state.held.stop();
                    state.held = null;
                }
            }
        } else if (action == ITouchMonitorService.ACTION_UP) {
            if (state.zoneNumber < 0) return;
            int num = state.zoneNumber;
            if (state.trigger == SoundSettings.Trigger.RELEASE && state.inside
                    && state.point != null && state.item != null) {
                SoundPlayer.playPoint(this, state.point, state.item);
            }
            state.reset();
            main.postDelayed(() -> refreshZoneHighlight(num), 140);
        }
    }

    private ActiveZone findHitZone(int px, int py) {
        for (int i = activeZones.size() - 1; i >= 0; i--) {
            ActiveZone z = activeZones.get(i);
            if (z.rect().contains(px, py)) return z;
        }
        return null;
    }

    private ActiveZone findZoneByNumber(int number) {
        for (ActiveZone z : activeZones) {
            if (z.point().number() == number) return z;
        }
        return null;
    }

    private void setZoneHighlight(int number, boolean activeFlag) {
        PointWindow pw = points.get(number);
        if (pw != null) pw.view().setTriggered(activeFlag);
    }

    private void refreshZoneHighlight(int number) {
        boolean stillHeld = false;
        for (SlotState s : slotStates) {
            if (s.zoneNumber == number && s.inside) {
                stillHeld = true;
                break;
            }
        }
        setZoneHighlight(number, stillHeld);
    }

    private void resetAllSlots() {
        for (SlotState s : slotStates) {
            int num = s.zoneNumber;
            s.reset();
            if (num >= 0) setZoneHighlight(num, false);
        }
    }

    private void overlayFailed(RuntimeException error) {
        Log.w(TAG, "Could not show fixed trigger points", error);
        Toast.makeText(this, "无法显示触发热区，请检查悬浮窗或 Shizuku 权限", Toast.LENGTH_SHORT).show();
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
        if (window.touch() != null) window.touch().cancel();
        if (window.view().getParent() != null) windows.removeViewImmediate(window.view());
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        unbindTouchMonitor();
        if (library != null) library.preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if (appOps != null) appOps.stopWatchingMode(permissionChanged);
        if (displays != null) displays.unregisterDisplayListener(displayChanged);
        clearWindows();
        activeZones.clear();
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
                else Toast.makeText(TriggerOverlayService.this, "请在触发热区设置中选择音效",
                        Toast.LENGTH_SHORT).show();
            });
            view.setOnTouchListener(this);
        }

        @Override public boolean onTouch(View child, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    pressed = library.triggerPoints.find(number);
                    item = pressed == null ? null : library.playableById(pressed.soundId());
                    trigger = item instanceof SoundSet set ? set.trigger()
                            : pressed == null ? null : pressed.settings().trigger;
                    fired = false;
                    view.setPressed(true);
                    if (item != null && trigger == SoundSettings.Trigger.HOLD) {
                        main.postDelayed(play, ViewConfiguration.getLongPressTimeout());
                    } else if (item != null && trigger == SoundSettings.Trigger.TAP) {
                        main.postDelayed(play, ViewConfiguration.getTapTimeout());
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!inside(event)) cancel();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (pressed != null && inside(event) && !fired
                            && (item == null || trigger != SoundSettings.Trigger.HOLD)) {
                        view.performClick();
                    }
                    cancel();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    cancel();
                    return true;
                }
                default -> {
                    return true;
                }
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
