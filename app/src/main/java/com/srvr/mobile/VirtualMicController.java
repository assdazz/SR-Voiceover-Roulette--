package com.srvr.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

final class VirtualMicController {
    static final String SHIZUKU_PACKAGE = ShizukuProvider.MANAGER_APPLICATION_ID;
    static final int SHIZUKU_PERMISSION_REQUEST = 9031;

    static final int MODE_ALWAYS_SFX = IVirtualMicService.MODE_ALWAYS_SFX;
    static final int MODE_ON_DEMAND = IVirtualMicService.MODE_ON_DEMAND;
    static final int MODE_MIX_REAL_MIC = IVirtualMicService.MODE_MIX_REAL_MIC;

    private static final String TAG = "SRVR-VirtualMic";
    private static final long BIND_TIMEOUT_MS = 8000L;
    private static final String KEY_MODE = "vmic.mode";
    private static final String KEY_DUCK_MIC = "vmic.duckMicOnSfx";
    private static final String KEY_LOCAL_MONITOR = "vmic.localMonitor";
    private static final String KEY_REAL_MIC_GAIN = "vmic.realMicGain";
    private static final String KEY_SFX_GAIN = "vmic.sfxGain";

    interface Listener {
        void onVirtualMicStateChanged();
    }

    private static VirtualMicController instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final VirtualMicPcmEngine pcmEngine = new VirtualMicPcmEngine();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SRVR-MicCtrl");
        t.setDaemon(true);
        return t;
    });

    private volatile IVirtualMicService remoteService;
    private volatile boolean usingDirectProcess;
    private volatile String directLaunchError = "";
    private volatile boolean binding;
    private volatile boolean starting;
    private volatile boolean active;
    private volatile boolean wantActive;
    private volatile boolean pendingShutdownShizuku;
    private volatile String statusSummary = "未开启";
    private volatile String lastError = "";
    private volatile String remoteDiagnostics = "";

    private Shizuku.UserServiceArgs userServiceArgs;
    private final Runnable bindTimeoutTask = this::handleBindTimeout;

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        Log.i(TAG, "Shizuku binder received");
        notifyListeners();
    };

    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        Log.w(TAG, "Shizuku binder dead");
        handleServiceLost("Shizuku 服务已断开");
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            lastError = "";
            if (wantActive) start();
        } else {
            wantActive = false;
            lastError = "未获得 Shizuku 授权";
        }
        notifyListeners();
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.removeCallbacks(bindTimeoutTask);
            binding = false;
            if (binder == null || !binder.pingBinder()) {
                handleServiceLost("Shizuku 虚拟麦服务未返回有效 Binder");
                return;
            }
            remoteService = IVirtualMicService.Stub.asInterface(binder);
            usingDirectProcess = false;
            Log.i(TAG, "Connected to ShizukuVirtualMicService");
            if (pendingShutdownShizuku) {
                pendingShutdownShizuku = false;
                executeRemoteShutdown();
            } else if (wantActive) {
                beginInjection();
            } else {
                notifyListeners();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            main.removeCallbacks(bindTimeoutTask);
            binding = false;
            starting = false;
            handleServiceLost("Shizuku 虚拟麦服务连接已断开");
        }
    };

    private VirtualMicController(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = SoundLibrary.get(app).preferences;
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    static synchronized VirtualMicController get(Context context) {
        if (instance == null) {
            instance = new VirtualMicController(context);
        }
        return instance;
    }

    void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    PackageInfo shizukuPackageInfo() {
        try {
            return app.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    boolean isShizukuSupportedVersion() {
        try {
            return isShizukuRunning() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 11;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean hasShizukuPermission() {
        if (!isShizukuSupportedVersion()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    int shizukuUid() {
        try {
            return isShizukuRunning() ? Shizuku.getUid() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    void requestShizukuPermission() {
        if (!isShizukuSupportedVersion()) {
            lastError = "请先安装并启动 Shizuku（v11 及以上）";
            notifyListeners();
            return;
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
        } catch (Throwable t) {
            lastError = "请求 Shizuku 授权失败：" + t.getMessage();
            notifyListeners();
        }
    }

    boolean isActive() {
        return active && pcmEngine.isRunning();
    }

    boolean isBusy() {
        return binding || starting;
    }

    String statusSummary() {
        return statusSummary;
    }

    String lastError() {
        return lastError;
    }

    String pollDiagnostics() {
        IVirtualMicService service = remoteService;
        if (service != null) {
            try {
                remoteDiagnostics = service.getStatus();
            } catch (RemoteException e) {
                remoteDiagnostics = "读取远端状态失败：" + e.getMessage();
            }
        }
        String local = pcmEngine.diagnostics();
        if (remoteDiagnostics == null || remoteDiagnostics.isEmpty()) {
            return "本地推流：" + local;
        }
        return remoteDiagnostics + "\n--- 本地推流 ---\n" + local;
    }

    int mode() {
        int val = prefs.getInt(KEY_MODE, MODE_ALWAYS_SFX);
        if (val < MODE_ALWAYS_SFX || val > MODE_MIX_REAL_MIC) return MODE_ALWAYS_SFX;
        return val;
    }

    void setMode(int value) {
        int clamped = (value >= MODE_ALWAYS_SFX && value <= MODE_MIX_REAL_MIC) ? value : MODE_ALWAYS_SFX;
        prefs.edit().putInt(KEY_MODE, clamped).apply();
        pushConfigToRemote();
        notifyListeners();
    }

    boolean duckMicOnSfx() {
        return prefs.getBoolean(KEY_DUCK_MIC, false);
    }

    void setDuckMicOnSfx(boolean value) {
        prefs.edit().putBoolean(KEY_DUCK_MIC, value).apply();
        pushConfigToRemote();
        notifyListeners();
    }

    boolean localMonitor() {
        return prefs.getBoolean(KEY_LOCAL_MONITOR, true);
    }

    void setLocalMonitor(boolean value) {
        prefs.edit().putBoolean(KEY_LOCAL_MONITOR, value).apply();
        SoundPlayer.refreshLocalMonitor(app);
        notifyListeners();
    }

    int realMicGainPercent() {
        return prefs.getInt(KEY_REAL_MIC_GAIN, 100);
    }

    void setRealMicGainPercent(int percent) {
        int clamped = Math.max(0, Math.min(200, percent));
        prefs.edit().putInt(KEY_REAL_MIC_GAIN, clamped).apply();
        pushConfigToRemote();
    }

    int sfxGainPercent() {
        return prefs.getInt(KEY_SFX_GAIN, 100);
    }

    void setSfxGainPercent(int percent) {
        int clamped = Math.max(0, Math.min(200, percent));
        prefs.edit().putInt(KEY_SFX_GAIN, clamped).apply();
        pushConfigToRemote();
    }

    void start() {
        lastError = "";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastError = "Shizuku 虚拟麦克风建议在 Android 10+（推荐 Android 13+）运行";
            notifyListeners();
            return;
        }
        if (!isShizukuRunning()) {
            lastError = "Shizuku 未运行，请先打开 Shizuku 并激活服务";
            notifyListeners();
            return;
        }
        if (!isShizukuSupportedVersion()) {
            lastError = "Shizuku 版本过低，请升级到最新版本";
            notifyListeners();
            return;
        }
        if (!hasShizukuPermission()) {
            wantActive = true;
            statusSummary = "等待 Shizuku 授权…";
            requestShizukuPermission();
            notifyListeners();
            return;
        }

        wantActive = true;
        if (remoteService != null) {
            beginInjection();
            return;
        }
        if (binding || starting) return;

        binding = true;
        directLaunchError = "";
        statusSummary = "正在通过 Shizuku 直连通道启动虚拟麦…";
        notifyListeners();

        controlExecutor.execute(() -> {
            if (!wantActive) {
                binding = false;
                notifyListeners();
                return;
            }
            try {
                DirectProcessMicClient client = DirectProcessMicClient.launch(app,
                        reason -> main.post(() -> handleServiceLost(reason)),
                        pcmEngine::updateRemoteBufferSamples);
                if (!wantActive) {
                    client.destroy();
                    binding = false;
                    notifyListeners();
                    return;
                }
                remoteService = client;
                usingDirectProcess = true;
                binding = false;
                Log.i(TAG, "Connected via DirectProcessMicClient (Shizuku.newProcess)");
                beginInjection();
            } catch (Throwable directErr) {
                directLaunchError = directErr.getMessage() != null ? directErr.getMessage() : directErr.toString();
                Log.w(TAG, "Direct newProcess launch failed, falling back to bindUserService: " + directLaunchError, directErr);
                main.post(this::startViaUserServiceFallback);
            }
        });
    }

    private void startViaUserServiceFallback() {
        if (!wantActive || !binding) return;
        statusSummary = "直连失败，正在回退 Shizuku 服务绑定…";
        notifyListeners();
        try {
            userServiceArgs = buildServiceArgs();
            main.removeCallbacks(bindTimeoutTask);
            main.postDelayed(bindTimeoutTask, BIND_TIMEOUT_MS);
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (Throwable t) {
            main.removeCallbacks(bindTimeoutTask);
            binding = false;
            wantActive = false;
            statusSummary = "未开启";
            lastError = "绑定 Shizuku 服务失败：" + t.getMessage()
                    + (directLaunchError.isEmpty() ? "" : "\n直连诊断：" + directLaunchError);
            notifyListeners();
        }
    }

    void stop() {
        main.removeCallbacks(bindTimeoutTask);
        pendingShutdownShizuku = false;
        wantActive = false;
        starting = false;
        active = false;
        pcmEngine.stop();
        stopForegroundService();

        IVirtualMicService service = remoteService;
        boolean wasDirect = usingDirectProcess;
        remoteService = null;
        usingDirectProcess = false;
        if (service != null) {
            try {
                service.stop();
            } catch (RemoteException ignored) {}
            if (wasDirect) {
                try {
                    service.destroy();
                } catch (Throwable ignored) {}
            }
        }
        if (!wasDirect && userServiceArgs != null && isShizukuRunning()) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            } catch (Throwable ignored) {}
        }
        binding = false;
        statusSummary = "未开启";
        remoteDiagnostics = "";
        SoundPlayer.refreshLocalMonitor(app);
        notifyListeners();
    }

    void shutdownDebuggingAndShizuku() {
        main.removeCallbacks(bindTimeoutTask);
        wantActive = false;
        starting = false;
        active = false;
        pcmEngine.stop();
        stopForegroundService();
        try {
            app.stopService(new Intent(app, TriggerOverlayService.class));
        } catch (Throwable ignored) {}
        SoundPlayer.refreshLocalMonitor(app);

        if (!isShizukuRunning()) {
            statusSummary = "未开启";
            lastError = "";
            notifyListeners();
            return;
        }
        if (!hasShizukuPermission()) {
            lastError = "当前未获 Shizuku 授权，请点「打开 Shizuku」手动停止";
            notifyListeners();
            return;
        }

        statusSummary = "正在关闭无线调试与 Shizuku 后台…";
        lastError = "";
        notifyListeners();

        if (remoteService != null) {
            executeRemoteShutdown();
        } else {
            binding = true;
            controlExecutor.execute(() -> {
                boolean ok = DirectProcessMicClient.runQuickShellCommand(
                        "settings put global adb_wifi_enabled 0; (sleep 0.2; pkill -f shizuku_server || killall shizuku_server) >/dev/null 2>&1 &");
                if (ok) {
                    binding = false;
                    main.postDelayed(() -> {
                        statusSummary = "未开启";
                        remoteDiagnostics = "";
                        notifyListeners();
                    }, 450);
                } else {
                    main.post(() -> {
                        pendingShutdownShizuku = true;
                        try {
                            userServiceArgs = buildServiceArgs();
                            main.removeCallbacks(bindTimeoutTask);
                            main.postDelayed(bindTimeoutTask, BIND_TIMEOUT_MS);
                            Shizuku.bindUserService(userServiceArgs, serviceConnection);
                        } catch (Throwable t) {
                            main.removeCallbacks(bindTimeoutTask);
                            binding = false;
                            pendingShutdownShizuku = false;
                            statusSummary = "未开启";
                            lastError = "无法连接 Shizuku 执行关闭：" + t.getMessage();
                            notifyListeners();
                        }
                    });
                }
            });
        }
    }

    private void handleBindTimeout() {
        if (!binding) return;
        Log.w(TAG, "Timed out waiting for Shizuku UserService after " + BIND_TIMEOUT_MS + " ms");
        boolean wasShutdown = pendingShutdownShizuku;
        binding = false;
        starting = false;
        wantActive = false;
        pendingShutdownShizuku = false;
        if (userServiceArgs != null && isShizukuRunning()) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            } catch (Throwable ignored) {}
        }
        remoteService = null;
        if (wasShutdown) {
            statusSummary = "连接超时";
            lastError = "连接 Shizuku 执行关闭超时（8 秒未响应）。请点上方「打开 Shizuku」手动停止服务，并在系统开发者选项关闭无线调试。";
        } else {
            statusSummary = "连接超时（点击按钮可重试）";
            lastError = buildBindTimeoutHint();
        }
        notifyListeners();
    }

    private String buildBindTimeoutHint() {
        PackageInfo info = shizukuPackageInfo();
        String ver = info != null && info.versionName != null ? info.versionName : "";
        boolean is136 = ver.startsWith("13.6.");
        StringBuilder sb = new StringBuilder();
        sb.append("连接 Shizuku 虚拟麦子服务超时（8 秒未收到响应），请按顺序排查：\n");
        if (is136) {
            sb.append("1.【最可能原因】检测到 Shizuku 为 ").append(ver)
                    .append("：该版本在部分 Android 15/16 机型存在 UserService 绑定卡死 Bug。请先彻底卸载 Shizuku 13.6，改装 13.5.4 后重新激活并授权。\n");
        } else {
            sb.append("1. 检查 Shizuku 版本：若使用 13.6.x 出现卡死，请卸载后改装稳定的 13.5.4 并重新激活。\n");
        }
        sb.append("2. 厂商开发者权限：OPPO/一加/真我(ColorOS) 需开启「禁止权限监控」（若中文下被隐藏，可临时切英文在开发者选项开启 Disable system optimization 后切回中文）；小米/红米需开启「USB 调试（安全设置）」。\n");
        sb.append("3. 后台与空间限制：将 Shizuku 与本软件均设为「电池无限制」并允许后台运行/自启动；切勿安装在应用分身/双开空间。\n");
        sb.append("4. 重启服务：打开 Shizuku 点停止后再重新启动无线调试服务，然后返回重试。");
        if (directLaunchError != null && !directLaunchError.isEmpty()) {
            sb.append("\n[直连通道诊断] ").append(directLaunchError);
        }
        return sb.toString();
    }

    private void executeRemoteShutdown() {
        IVirtualMicService service = remoteService;
        remoteService = null;
        binding = false;
        controlExecutor.execute(() -> {
            try {
                if (service != null) {
                    service.shutdownShizuku(true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "shutdownShizuku IPC failed", t);
            }
            main.postDelayed(() -> {
                statusSummary = "未开启";
                remoteDiagnostics = "";
                notifyListeners();
            }, 400);
        });
    }

    void prepareForPlayback() {
        if (!isActive() || mode() != MODE_ON_DEMAND) return;
        IVirtualMicService service = remoteService;
        if (service == null) return;
        controlExecutor.execute(() -> {
            try {
                service.setInjectionActive(true);
            } catch (RemoteException e) {
                Log.w(TAG, "prepareForPlayback failed", e);
            }
        });
    }

    VirtualMicPcmEngine.ClipHandle playClip(SoundLibrary.Sound sound, int soundVolumePercent,
            int masterVolumePercent, SoundSequence.Variation variation) {
        return playClip(sound, soundVolumePercent, masterVolumePercent, variation, null);
    }

    VirtualMicPcmEngine.ClipHandle playClip(SoundLibrary.Sound sound, int soundVolumePercent,
            int masterVolumePercent, SoundSequence.Variation variation, Runnable onFinished) {
        if (!isActive() || sound == null) return null;
        float gain = (soundVolumePercent / 100f) * (masterVolumePercent / 100f);
        Runnable uiFinished = onFinished != null ? () -> main.post(onFinished) : null;
        return pcmEngine.enqueue(app, sound, gain, variation, uiFinished);
    }

    void stopAllClips() {
        pcmEngine.stopAllClips();
    }

    private Shizuku.UserServiceArgs buildServiceArgs() {
        int versionCode = 1;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            versionCode = (int) info.getLongVersionCode();
        } catch (Throwable ignored) {}
        return new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(),
                ShizukuVirtualMicService.class.getName()))
                .daemon(false)
                .processNameSuffix("shizuku_vmic")
                .debuggable(false)
                .version(versionCode);
    }

    private void beginInjection() {
        IVirtualMicService service = remoteService;
        if (service == null || starting) return;
        starting = true;
        statusSummary = "正在初始化虚拟麦克风通道…";
        notifyListeners();

        int chosenMode = mode();
        float micGain = realMicGainPercent() / 100f;
        float soundGain = sfxGainPercent() / 100f;
        boolean duck = duckMicOnSfx();

        controlExecutor.execute(() -> {
            ParcelFileDescriptor[] pipe = null;
            try {
                if (!wantActive || remoteService != service) {
                    starting = false;
                    notifyListeners();
                    return;
                }
                pipe = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor readEnd = pipe[0];
                ParcelFileDescriptor writeEnd = pipe[1];

                String result = service.start(
                        Process.myUid(),
                        chosenMode,
                        micGain,
                        soundGain,
                        duck,
                        readEnd);

                try {
                    readEnd.close();
                } catch (IOException ignored) {}

                if (!wantActive || remoteService != service) {
                    try {
                        writeEnd.close();
                    } catch (IOException ignored) {}
                    try {
                        service.stop();
                    } catch (RemoteException ignored) {}
                    starting = false;
                    notifyListeners();
                    return;
                }

                if (result != null && result.startsWith("OK:")) {
                    pcmEngine.start(writeEnd,
                            () -> main.post(() -> handleServiceLost("音频管道已断开")),
                            this::onAudioActivityChanged);
                    starting = false;
                    active = true;
                    lastError = "";
                    statusSummary = (usingDirectProcess ? "运行中（直连）· " : "运行中 · ") + result.substring(3);
                    main.post(() -> {
                        startForegroundService();
                        SoundPlayer.refreshLocalMonitor(app);
                    });
                    pollDiagnostics();
                    notifyListeners();
                } else {
                    try {
                        writeEnd.close();
                    } catch (IOException ignored) {}
                    starting = false;
                    active = false;
                    wantActive = false;
                    statusSummary = "启动失败";
                    lastError = result != null && result.startsWith("ERR:")
                            ? result.substring(4) : "虚拟麦克风服务返回异常：" + result;
                    pollDiagnostics();
                    notifyListeners();
                }
            } catch (Throwable t) {
                if (pipe != null) {
                    for (ParcelFileDescriptor fd : pipe) {
                        if (fd != null) {
                            try {
                                fd.close();
                            } catch (IOException ignored) {}
                        }
                    }
                }
                starting = false;
                active = false;
                wantActive = false;
                statusSummary = "启动失败";
                lastError = "启动虚拟麦克风失败：" + t.getMessage();
                notifyListeners();
            }
        });
    }

    private void onAudioActivityChanged(boolean playing) {
        if (!active || mode() != MODE_ON_DEMAND) return;
        IVirtualMicService service = remoteService;
        if (service == null) return;
        controlExecutor.execute(() -> {
            if (!active || mode() != MODE_ON_DEMAND) return;
            try {
                service.setInjectionActive(playing);
            } catch (RemoteException e) {
                Log.w(TAG, "setInjectionActive failed", e);
            }
        });
    }

    private void pushConfigToRemote() {
        IVirtualMicService service = remoteService;
        if (service == null || !active) return;
        try {
            service.updateConfig(mode(), realMicGainPercent() / 100f,
                    sfxGainPercent() / 100f, duckMicOnSfx());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to update remote config", e);
        }
    }

    private void handleServiceLost(String reason) {
        main.removeCallbacks(bindTimeoutTask);
        boolean wasActive = active || binding || starting;
        IVirtualMicService oldService = remoteService;
        boolean wasDirect = usingDirectProcess;
        active = false;
        wantActive = false;
        binding = false;
        starting = false;
        remoteService = null;
        usingDirectProcess = false;
        if (wasDirect && oldService != null) {
            try {
                oldService.destroy();
            } catch (Throwable ignored) {}
        }
        pcmEngine.stop();
        stopForegroundService();
        statusSummary = "未开启";
        if (wasActive && reason != null) {
            lastError = reason;
        }
        SoundPlayer.refreshLocalMonitor(app);
        notifyListeners();
    }

    private void startForegroundService() {
        try {
            Intent intent = new Intent(app, VirtualMicForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start VirtualMicForegroundService", t);
        }
    }

    private void stopForegroundService() {
        try {
            app.stopService(new Intent(app, VirtualMicForegroundService.class));
        } catch (Throwable ignored) {}
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onVirtualMicStateChanged();
            }
        });
    }
}
