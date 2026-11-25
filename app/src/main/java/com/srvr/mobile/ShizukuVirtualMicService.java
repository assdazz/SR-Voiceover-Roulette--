package com.srvr.mobile;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ShizukuVirtualMicService extends IVirtualMicService.Stub {
    static final int SAMPLE_RATE = 48000;
    static final int FRAME_SAMPLES = 480; // 10 ms at 48 kHz mono for low-latency injection
    private static final String TAG = "SRVR-ShizukuMic";
    private static final float DUCK_FACTOR = 0.12f;

    private static final int[] CAPTURE_PRESETS = {
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_PERFORMANCE
    };

    private final Context rawContext;
    private final Object lock = new Object();
    private final Object policyLock = new Object();

    private volatile boolean running;
    private volatile int appUid;
    private volatile int mode = MODE_ALWAYS_SFX;
    private volatile boolean duckMicOnSfx;
    private volatile float realMicGain = 1f;
    private volatile float sfxGain = 1f;
    private volatile boolean uidExclusionActive;
    private volatile boolean realMicActive;
    private volatile boolean policyAttached;
    private volatile int injectChannels = 2;
    private volatile String realMicNote = "未启用";
    private volatile String policyNote = "未启动";
    private volatile String lastError = "";
    private volatile String attemptLog = "";
    private volatile long lastAttachMs;
    private volatile long writtenFrames;
    private volatile long sfxFrames;

    // Cached fast-path policy profile after initial probe succeeds
    private volatile int cachedChannelMask = -1;
    private volatile boolean cachedExcludeSelf = true;
    private volatile boolean cachedUseSystemUsage = false;
    private volatile boolean hasCachedProfile = false;

    private Context privilegedContext;
    private AudioManager audioManager;
    private Object audioPolicy;
    private AudioTrack injectTrack;
    private HandlerThread policyThread;
    private ParcelFileDescriptor pcmPipe;
    private Thread pipeReaderThread;
    private Thread injectorThread;
    private PcmRingBuffer sfxRing;

    public ShizukuVirtualMicService() {
        this(null);
    }

    public ShizukuVirtualMicService(Context context) {
        this.rawContext = context;
        unlockHiddenApis();
    }

    private static void unlockHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass failed", t);
        }
    }

    @Override public String start(int appUid, int mode, float realMicGain, float sfxGain,
            boolean duckMicOnSfx, ParcelFileDescriptor pcmReadPipe) {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
                this.appUid = appUid;
                this.mode = mode;
                this.realMicGain = clampGain(realMicGain);
                this.sfxGain = clampGain(sfxGain);
                this.duckMicOnSfx = duckMicOnSfx;
                this.pcmPipe = pcmReadPipe;
                this.lastError = "";
                this.attemptLog = "";
                this.writtenFrames = 0;
                this.sfxFrames = 0;
                this.realMicActive = false;
                this.realMicNote = mode == MODE_MIX_REAL_MIC ? "准备采集真麦" : "未启用（保留原麦通道）";

                try {
                    privilegedContext = resolvePrivilegedContext();
                    if (privilegedContext == null) {
                        return failLocked("无法获取系统 Context（UID=" + Process.myUid() + "）");
                    }
                    audioManager = (AudioManager) privilegedContext.getSystemService(Context.AUDIO_SERVICE);
                    if (audioManager == null) {
                        return failLocked("无法获取 AudioManager");
                    }

                    policyThread = new HandlerThread("SRVR-AudioPolicy");
                    policyThread.start();

                    // Validate policy registration immediately; if ON_DEMAND, detach right after verification.
                    attachPolicyLocked();
                    if (mode == MODE_ON_DEMAND) {
                        detachPolicyLocked();
                        policyNote = "待命中（平时使用原机真麦，放音效时自动接管）";
                    }

                    this.sfxRing = new PcmRingBuffer(SAMPLE_RATE / 2); // 500 ms ring buffer
                    this.running = true;

                    if (this.pcmPipe != null) {
                        pipeReaderThread = new Thread(this::readPipeLoop, "SRVR-PipeReader");
                        pipeReaderThread.start();
                    }
                    injectorThread = new Thread(this::injectLoop, "SRVR-MicInjector");
                    injectorThread.start();

                    Log.i(TAG, "Started virtual mic (" + modeName(mode) + "): " + this.policyNote);
                    return "OK:" + this.policyNote;
                } catch (Throwable t) {
                    String message = describeError(t);
                    Log.e(TAG, "Failed to start virtual mic: " + message, t);
                    return failLocked(message);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override public void updateConfig(int mode, float realMicGain, float sfxGain, boolean duckMicOnSfx) {
        long token = Binder.clearCallingIdentity();
        try {
            int previousMode = this.mode;
            this.mode = mode;
            this.realMicGain = clampGain(realMicGain);
            this.sfxGain = clampGain(sfxGain);
            this.duckMicOnSfx = duckMicOnSfx;
            if (mode != MODE_MIX_REAL_MIC) {
                this.realMicNote = mode == MODE_ON_DEMAND ? "按需模式（不放音效时用手机原麦）" : "已关闭（纯音效模式）";
            }
            if (running && previousMode != mode) {
                synchronized (policyLock) {
                    if (mode == MODE_ON_DEMAND) {
                        detachPolicyLocked();
                        policyNote = "待命中（平时使用原机真麦，放音效时自动接管）";
                    } else if (!policyAttached) {
                        try {
                            attachPolicyLocked();
                        } catch (Throwable t) {
                            lastError = describeError(t);
                        }
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override public void setInjectionActive(boolean active) {
        long token = Binder.clearCallingIdentity();
        try {
            if (running && mode == MODE_ON_DEMAND) {
                synchronized (policyLock) {
                    if (active && !policyAttached) {
                        PcmRingBuffer ring = this.sfxRing;
                        if (ring != null) ring.clear();
                        try {
                            attachPolicyLocked();
                            lastError = "";
                        } catch (Throwable t) {
                            lastError = "动态接管失败：" + describeError(t);
                            Log.w(TAG, lastError, t);
                        }
                    } else if (!active && policyAttached) {
                        detachPolicyLocked();
                        policyNote = "待命中（平时使用原机真麦，放音效时自动接管）";
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override public void stop() {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("running=").append(running).append(" mode=").append(modeName(mode)).append('\n');
        sb.append("uid=").append(Process.myUid());
        if (privilegedContext != null) {
            sb.append(" pkg=").append(privilegedContext.getOpPackageName());
        }
        sb.append('\n');
        sb.append("policyAttached=").append(policyAttached).append(" · ").append(policyNote).append('\n');
        sb.append("channels=").append(injectChannels).append(" uidExclude=").append(uidExclusionActive)
                .append(" attachMs=").append(lastAttachMs).append('\n');
        sb.append("realMicActive=").append(realMicActive).append(" · ").append(realMicNote).append('\n');
        sb.append("writtenFrames=").append(writtenFrames).append(" sfxFrames=").append(sfxFrames);
        if (!lastError.isEmpty()) {
            sb.append("\nerror=").append(lastError);
        }
        if (!attemptLog.isEmpty()) {
            sb.append("\nattempts:\n").append(attemptLog);
        }
        return sb.toString().trim();
    }

    @Override public String shutdownShizuku(boolean disableWirelessAdb) {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
            }
            if (disableWirelessAdb) {
                runShellCmd("settings", "put", "global", "adb_wifi_enabled", "0");
            }
            // Terminate shizuku_server cleanly right after Binder reply returns.
            new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}
                runShellCmd("sh", "-c", "pkill -f shizuku_server || killall shizuku_server");
                System.exit(0);
            }, "SRVR-ShizukuStopper").start();
            return "OK";
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void runShellCmd(String... cmd) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "Command failed: " + String.join(" ", cmd), t);
        } finally {
            if (p != null) p.destroy();
        }
    }

    @Override public void destroy() {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
            }
            Log.i(TAG, "Destroying ShizukuVirtualMicService process");
            System.exit(0);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void attachPolicyLocked() throws Exception {
        synchronized (policyLock) {
            if (policyAttached && injectTrack != null) return;
            long t0 = android.os.SystemClock.elapsedRealtime();
            Looper looper = policyThread != null ? policyThread.getLooper() : Looper.getMainLooper();
            PolicySetup setup = registerInjectorPolicy(privilegedContext, audioManager, looper, appUid);
            this.audioPolicy = setup.policy;
            this.injectTrack = setup.track;
            this.injectChannels = Math.max(1, setup.track.getChannelCount());
            this.uidExclusionActive = setup.uidExclusionActive;
            this.policyNote = setup.note;
            this.injectTrack.play();
            this.policyAttached = true;
            this.lastAttachMs = android.os.SystemClock.elapsedRealtime() - t0;
            policyLock.notifyAll();
        }
    }

    private void detachPolicyLocked() {
        synchronized (policyLock) {
            policyAttached = false;
            if (injectTrack != null) {
                try {
                    injectTrack.stop();
                } catch (Throwable ignored) {}
                try {
                    injectTrack.release();
                } catch (Throwable ignored) {}
                injectTrack = null;
            }
            if (audioPolicy != null && audioManager != null) {
                unregisterPolicyQuietly(audioManager, audioPolicy);
                audioPolicy = null;
            }
        }
    }

    private String failLocked(String message) {
        String savedAttempts = this.attemptLog;
        stopLocked();
        this.lastError = message;
        this.policyNote = "启动失败";
        this.attemptLog = savedAttempts;
        return "ERR:" + message;
    }

    private void stopLocked() {
        running = false;
        realMicActive = false;

        if (pcmPipe != null) {
            try {
                pcmPipe.close();
            } catch (IOException ignored) {}
            pcmPipe = null;
        }
        synchronized (policyLock) {
            policyLock.notifyAll();
        }
        if (pipeReaderThread != null) {
            pipeReaderThread.interrupt();
            try {
                pipeReaderThread.join(400);
            } catch (InterruptedException ignored) {}
            pipeReaderThread = null;
        }
        if (injectorThread != null) {
            injectorThread.interrupt();
            try {
                injectorThread.join(600);
            } catch (InterruptedException ignored) {}
            injectorThread = null;
        }
        detachPolicyLocked();
        if (policyThread != null) {
            policyThread.quitSafely();
            policyThread = null;
        }
        sfxRing = null;
        policyNote = "已停止";
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case MODE_ON_DEMAND -> "仅放音效时接管";
            case MODE_MIX_REAL_MIC -> "常驻+混入真麦";
            default -> "常驻纯音效";
        };
    }

    private Context resolvePrivilegedContext() {
        Context base = rawContext;
        if (base == null) {
            try {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
                Object at = current.invoke(null);
                if (at == null) {
                    Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
                    at = systemMain.invoke(null);
                }
                if (at != null) {
                    Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
                    base = (Context) getSystemContext.invoke(at);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to obtain ActivityThread system context", t);
            }
        }
        if (base == null) return null;
        if (Process.myUid() == Process.SHELL_UID) {
            Context shellBase = base;
            try {
                shellBase = base.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t) {
                Log.w(TAG, "Fallback from com.android.shell context: " + t.getMessage());
            }
            return new ShellIdentityContext(shellBase, Process.SHELL_UID, "com.android.shell");
        }
        return base;
    }

    private static final class ShellIdentityContext extends ContextWrapper {
        private final int uid;
        private final String shellPkg;

        ShellIdentityContext(Context base, int uid, String shellPkg) {
            super(base);
            this.uid = uid;
            this.shellPkg = shellPkg;
        }

        @Override public String getPackageName() {
            return shellPkg;
        }

        @Override public String getOpPackageName() {
            return shellPkg;
        }

        @Override public AttributionSource getAttributionSource() {
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource.Builder builder = new AttributionSource.Builder(uid)
                        .setPackageName(shellPkg);
                if (Build.VERSION.SDK_INT >= 34) {
                    builder.setPid(Process.myPid());
                }
                return builder.build();
            }
            return super.getAttributionSource();
        }

        @Override public Context getApplicationContext() {
            return this;
        }
    }

    private static final class PolicySetup {
        final Object policy;
        final AudioTrack track;
        final boolean uidExclusionActive;
        final String note;

        PolicySetup(Object policy, AudioTrack track, boolean uidExclusionActive, String note) {
            this.policy = policy;
            this.track = track;
            this.uidExclusionActive = uidExclusionActive;
            this.note = note;
        }
    }

    private PolicySetup registerInjectorPolicy(Context ctx, AudioManager am, Looper looper, int appUid)
            throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        int ruleCapturePreset = getStaticInt(ruleClass, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET", 0x2);
        int ruleExcludeUid = getStaticInt(ruleClass, "RULE_EXCLUDE_UID", 0x8004);
        int ruleMatchUid = getStaticInt(ruleClass, "RULE_MATCH_UID", 0x4);
        int mixRoleInjector = getStaticInt(ruleClass, "MIX_ROLE_INJECTOR", 1);
        int routeLoopBack = getStaticInt(mixClass, "ROUTE_FLAG_LOOP_BACK", 0x2);

        int[] channelMaskAttempts = hasCachedProfile
                ? new int[] { cachedChannelMask, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_IN_MONO }
                : new int[] { AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_IN_MONO };
        boolean[] exclusionAttempts = hasCachedProfile
                ? new boolean[] { cachedExcludeSelf, !cachedExcludeSelf }
                : new boolean[] { true, false };
        List<String> logLines = new ArrayList<>();
        Throwable firstError = null;

        for (boolean excludeSelf : exclusionAttempts) {
            for (int channelMask : channelMaskAttempts) {
                String maskName = channelMaskName(channelMask);
                Object candidatePolicy = null;
                try {
                    Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
                    Method setTargetMixRole = findMethod(ruleBuilderClass, "setTargetMixRole", int.class);
                    if (setTargetMixRole != null) {
                        setTargetMixRole.invoke(ruleBuilder, mixRoleInjector);
                    }
                    Method addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class);
                    for (int preset : CAPTURE_PRESETS) {
                        AudioAttributes attr = buildCaptureAttributes(preset);
                        if (attr != null) {
                            addRule.invoke(ruleBuilder, attr, ruleCapturePreset);
                        }
                    }

                    boolean excludedApplied = false;
                    if (excludeSelf) {
                        excludedApplied = applyUidExclusion(ruleBuilderClass, ruleBuilder,
                                ruleMatchUid, ruleExcludeUid, Process.myUid(), appUid);
                    }

                    Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

                    AudioFormat format = new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelMask)
                            .build();
                    Constructor<?> mixBuilderCtor = mixBuilderClass.getConstructor(ruleClass);
                    Object mixBuilder = mixBuilderCtor.newInstance(rule);
                    mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
                    mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, routeLoopBack);
                    Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

                    Constructor<?> policyBuilderCtor = policyBuilderClass.getConstructor(Context.class);
                    Object policyBuilder = policyBuilderCtor.newInstance(ctx);
                    policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
                    Method setLooper = findMethod(policyBuilderClass, "setLooper", Looper.class);
                    if (setLooper != null) {
                        setLooper.invoke(policyBuilder, looper);
                    }
                    candidatePolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

                    int res = registerPolicy(am, policyClass, candidatePolicy);
                    if (res != 0) {
                        throw new IllegalStateException("registerAudioPolicy=" + res);
                    }

                    String regId = getMixRegistration(mixClass, mix);
                    AudioFormat mixFormat = getMixFormat(mixClass, mix, format);

                    AudioTrack track = null;
                    String createDetail = "";
                    TrackResult manual = createManualInjectionTrack(ctx, mixFormat, regId,
                            hasCachedProfile ? cachedUseSystemUsage : false);
                    if (manual.track != null) {
                        track = manual.track;
                        createDetail = "manual(" + manual.note + ")";
                        cachedUseSystemUsage = manual.usedSystemUsage;
                    } else {
                        try {
                            Method createTrack = policyClass.getMethod("createAudioTrackSource", mixClass);
                            AudioTrack t = (AudioTrack) createTrack.invoke(candidatePolicy, mix);
                            if (t != null && t.getState() == AudioTrack.STATE_INITIALIZED) {
                                track = t;
                                createDetail = "api";
                            } else {
                                if (t != null) t.release();
                                throw new IllegalStateException("manual(" + manual.note + "); api=invalid; reg=" + regId);
                            }
                        } catch (Throwable apiErr) {
                            throw new IllegalStateException(
                                    "manual(" + manual.note + "); api:" + describeError(apiErr) + "; reg=" + regId);
                        }
                    }

                    cachedChannelMask = channelMask;
                    cachedExcludeSelf = excludeSelf;
                    hasCachedProfile = true;

                    String chLabel = track.getChannelCount() == 2 ? "STEREO" : "MONO";
                    String note = "AudioPolicy 已注册 · 48kHz/" + chLabel
                            + (excludedApplied ? " · 已排除自环" : "");
                    logLines.add("OK[" + (excludeSelf ? "excl" : "all") + "," + maskName + "]: " + createDetail);
                    this.attemptLog = String.join("\n", logLines);
                    return new PolicySetup(candidatePolicy, track, excludedApplied, note);
                } catch (Throwable t) {
                    if (candidatePolicy != null) {
                        unregisterPolicyQuietly(am, candidatePolicy);
                    }
                    String err = describeError(t);
                    logLines.add("FAIL[" + (excludeSelf ? "excl" : "all") + "," + maskName + "]: " + err);
                    if (firstError == null) firstError = t;
                    Log.w(TAG, "Policy attempt failed (" + excludeSelf + ", " + maskName + "): " + err);
                }
            }
        }
        this.attemptLog = String.join("\n", logLines);
        if (firstError instanceof Exception ex) throw ex;
        throw new IllegalStateException(firstError != null ? describeError(firstError) : "AudioPolicy 注册失败", firstError);
    }

    private static final class TrackResult {
        final AudioTrack track;
        final String note;
        final boolean usedSystemUsage;

        TrackResult(AudioTrack track, String note, boolean usedSystemUsage) {
            this.track = track;
            this.note = note;
            this.usedSystemUsage = usedSystemUsage;
        }
    }

    private static TrackResult createManualInjectionTrack(Context ctx, AudioFormat mixFormat,
            String regId, boolean preferSystemUsage) {
        if (regId == null || regId.isEmpty()) {
            return new TrackResult(null, "empty regId", false);
        }
        int usageVirtualSource = getStaticInt(AudioAttributes.class, "USAGE_VIRTUAL_SOURCE", 15);
        int outMask = normalizeOutChannelMask(mixFormat.getChannelMask());
        AudioFormat trackFormat = new AudioFormat.Builder()
                .setEncoding(mixFormat.getEncoding() != 0 ? mixFormat.getEncoding() : AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(mixFormat.getSampleRate() > 0 ? mixFormat.getSampleRate() : SAMPLE_RATE)
                .setChannelMask(outMask)
                .build();
        int minBuf = AudioTrack.getMinBufferSize(
                trackFormat.getSampleRate(), trackFormat.getChannelMask(), trackFormat.getEncoding());
        int frameBytes = FRAME_SAMPLES * (outMask == AudioFormat.CHANNEL_OUT_STEREO ? 4 : 2);
        // Keep buffer as tight as Android allows to minimize r_submix startup latency.
        int bufSize = Math.max(minBuf, frameBytes * 2);

        List<String> errs = new ArrayList<>();
        boolean[] useSystemUsageOptions = preferSystemUsage ? new boolean[] {true, false} : new boolean[] {false, true};
        int[] perfModes = { AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, AudioTrack.PERFORMANCE_MODE_NONE };
        for (boolean useSystemUsage : useSystemUsageOptions) {
            for (int perfMode : perfModes) {
                AudioTrack track = null;
                try {
                    AudioAttributes.Builder ab = new AudioAttributes.Builder();
                    if (useSystemUsage) {
                        Method setSys = findMethod(AudioAttributes.Builder.class, "setSystemUsage", int.class);
                        if (setSys == null) continue;
                        setSys.invoke(ab, usageVirtualSource);
                    } else {
                        ab.setUsage(usageVirtualSource);
                    }
                    Method addTag = findMethod(AudioAttributes.Builder.class, "addTag", String.class);
                    if (addTag == null) {
                        return new TrackResult(null, "no addTag", false);
                    }
                    String tag = regId.startsWith("addr=") ? regId : ("addr=" + regId);
                    addTag.invoke(ab, tag);
                    AudioAttributes attrs = ab.build();

                    AudioTrack.Builder tb = new AudioTrack.Builder()
                            .setAudioAttributes(attrs)
                            .setAudioFormat(trackFormat)
                            .setBufferSizeInBytes(bufSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(perfMode);
                    if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                        Method setContext = findMethod(AudioTrack.Builder.class, "setContext", Context.class);
                        if (setContext != null) {
                            setContext.invoke(tb, ctx);
                        }
                    }
                    track = tb.build();
                    if (track.getState() == AudioTrack.STATE_INITIALIZED) {
                        String perfTag = perfMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY ? "+LL" : "";
                        return new TrackResult(track,
                                (useSystemUsage ? "sysUsage+ctx" : "usage+ctx") + perfTag, useSystemUsage);
                    }
                    errs.add((useSystemUsage ? "sys" : "std") + "=state" + track.getState());
                    track.release();
                } catch (Throwable t) {
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Throwable ignored) {}
                    }
                    errs.add((useSystemUsage ? "sys" : "std") + ":" + describeError(t));
                }
            }
        }
        return new TrackResult(null, String.join(",", errs), false);
    }

    private static int normalizeOutChannelMask(int mask) {
        if (mask == AudioFormat.CHANNEL_IN_MONO) return AudioFormat.CHANNEL_OUT_MONO;
        if (mask == AudioFormat.CHANNEL_OUT_STEREO || mask == AudioFormat.CHANNEL_OUT_MONO) return mask;
        return AudioFormat.CHANNEL_OUT_STEREO;
    }

    private static String channelMaskName(int mask) {
        return switch (mask) {
            case AudioFormat.CHANNEL_OUT_STEREO -> "STEREO";
            case AudioFormat.CHANNEL_OUT_MONO -> "OUT_MONO";
            case AudioFormat.CHANNEL_IN_MONO -> "IN_MONO";
            default -> "MASK_" + mask;
        };
    }

    private static String getMixRegistration(Class<?> mixClass, Object mix) {
        try {
            Method m = findMethod(mixClass, "getRegistration");
            if (m != null) {
                Object val = m.invoke(mix);
                if (val instanceof String s) return s;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static AudioFormat getMixFormat(Class<?> mixClass, Object mix, AudioFormat fallback) {
        try {
            Method m = findMethod(mixClass, "getFormat");
            if (m != null) {
                Object val = m.invoke(mix);
                if (val instanceof AudioFormat af) return af;
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static AudioAttributes buildCaptureAttributes(int preset) {
        try {
            AudioAttributes.Builder builder = new AudioAttributes.Builder();
            Method setCapturePreset = AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
            setCapturePreset.invoke(builder, preset);
            return builder.build();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean applyUidExclusion(Class<?> ruleBuilderClass, Object ruleBuilder,
            int ruleMatchUid, int ruleExcludeUid, int myUid, int appUid) {
        try {
            Method excludeMixRule = findMethod(ruleBuilderClass, "excludeMixRule", int.class, Object.class);
            if (excludeMixRule != null) {
                excludeMixRule.invoke(ruleBuilder, ruleMatchUid, myUid);
                if (appUid > 0 && appUid != myUid) {
                    excludeMixRule.invoke(ruleBuilder, ruleMatchUid, appUid);
                }
                return true;
            }
            Method addMixRule = findMethod(ruleBuilderClass, "addMixRule", int.class, Object.class);
            if (addMixRule != null) {
                addMixRule.invoke(ruleBuilder, ruleExcludeUid, myUid);
                if (appUid > 0 && appUid != myUid) {
                    addMixRule.invoke(ruleBuilder, ruleExcludeUid, appUid);
                }
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "UID exclusion not supported: " + describeError(t));
        }
        return false;
    }

    private static int registerPolicy(AudioManager am, Class<?> policyClass, Object policy) throws Exception {
        Method instanceMethod = findMethod(AudioManager.class, "registerAudioPolicy", policyClass);
        if (instanceMethod != null) {
            return (Integer) instanceMethod.invoke(am, policy);
        }
        Method staticMethod = findMethod(AudioManager.class, "registerAudioPolicyStatic", policyClass);
        if (staticMethod != null) {
            return (Integer) staticMethod.invoke(null, policy);
        }
        throw new NoSuchMethodException("AudioManager.registerAudioPolicy not found");
    }

    private static void unregisterPolicyQuietly(AudioManager am, Object policy) {
        try {
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Method asyncMethod = findMethod(AudioManager.class, "unregisterAudioPolicyAsync", policyClass);
            if (asyncMethod != null) {
                asyncMethod.invoke(am, policy);
                return;
            }
            Method syncMethod = findMethod(AudioManager.class, "unregisterAudioPolicy", policyClass);
            if (syncMethod != null) {
                syncMethod.invoke(am, policy);
            }
        } catch (Throwable t) {
            Log.w(TAG, "unregisterAudioPolicy failed: " + describeError(t));
        }
    }

    private void readPipeLoop() {
        ParcelFileDescriptor pipe = this.pcmPipe;
        PcmRingBuffer ring = this.sfxRing;
        if (pipe == null || ring == null) return;
        byte[] bytes = new byte[FRAME_SAMPLES * 2];
        short[] samples = new short[FRAME_SAMPLES];
        try (FileInputStream in = new FileInputStream(pipe.getFileDescriptor())) {
            int carry = -1;
            while (running) {
                int read = in.read(bytes);
                if (read < 0) break;
                if (read == 0) continue;
                int count = 0;
                int i = 0;
                if (carry >= 0) {
                    samples[count++] = (short) ((carry & 0xff) | ((bytes[0] & 0xff) << 8));
                    carry = -1;
                    i = 1;
                }
                while (i + 1 < read) {
                    samples[count++] = (short) ((bytes[i] & 0xff) | ((bytes[i + 1] & 0xff) << 8));
                    i += 2;
                }
                if (i < read) {
                    carry = bytes[i] & 0xff;
                }
                if (count > 0) {
                    ring.write(samples, count);
                }
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "PCM pipe reader closed: " + e.getMessage());
        }
    }

    private void injectLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        PcmRingBuffer ring = this.sfxRing;
        if (ring == null) return;

        short[] sfxBuf = new short[FRAME_SAMPLES];
        short[] micBuf = new short[FRAME_SAMPLES];
        short[] outBuf = new short[FRAME_SAMPLES * 2];

        AudioRecord realMic = null;
        long nextMicRetryAt = 0;
        int silentMicStreak = 0;

        try {
            while (running) {
                AudioTrack track;
                int channels;
                synchronized (policyLock) {
                    while (running && (!policyAttached || injectTrack == null)) {
                        if (realMic != null) {
                            closeRealMic(realMic);
                            realMic = null;
                            realMicActive = false;
                        }
                        try {
                            policyLock.wait(200);
                        } catch (InterruptedException e) {
                            if (!running) return;
                        }
                    }
                    if (!running) break;
                    track = injectTrack;
                    channels = Math.max(1, injectChannels);
                }

                boolean wantMic = (mode == MODE_MIX_REAL_MIC) && uidExclusionActive;
                if (wantMic && realMic == null) {
                    long now = System.currentTimeMillis();
                    if (now >= nextMicRetryAt) {
                        realMic = tryOpenRealMic();
                        silentMicStreak = 0;
                        if (realMic == null) {
                            nextMicRetryAt = now + 3000;
                        }
                    }
                } else if (!wantMic && realMic != null) {
                    closeRealMic(realMic);
                    realMic = null;
                    realMicActive = false;
                    silentMicStreak = 0;
                    realMicNote = mode == MODE_MIX_REAL_MIC
                            ? "无法排除自环，已禁用真麦"
                            : (mode == MODE_ON_DEMAND ? "按需模式（不放音效时用手机原麦）" : "已关闭（纯音效模式）");
                }

                int sfxRead = ring.read(sfxBuf, FRAME_SAMPLES);
                boolean hasSfx = false;
                for (int i = 0; i < sfxRead; i++) {
                    if (sfxBuf[i] != 0) {
                        hasSfx = true;
                        break;
                    }
                }
                for (int i = sfxRead; i < FRAME_SAMPLES; i++) {
                    sfxBuf[i] = 0;
                }

                int micRead = 0;
                if (realMic != null) {
                    micRead = realMic.read(micBuf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING);
                    if (micRead < 0) {
                        closeRealMic(realMic);
                        realMic = null;
                        realMicActive = false;
                        realMicNote = "真麦读取中断（错误 " + micRead + "）";
                        nextMicRetryAt = System.currentTimeMillis() + 3000;
                        micRead = 0;
                    } else {
                        boolean micNonZero = false;
                        for (int i = 0; i < micRead; i++) {
                            if (micBuf[i] != 0) {
                                micNonZero = true;
                                break;
                            }
                        }
                        if (micNonZero) {
                            silentMicStreak = 0;
                            realMicNote = "真麦混音正常（有信号）";
                        } else if (++silentMicStreak >= 50) {
                            realMicNote = "后台真麦被系统静音（前台录音并发限制），建议用「常驻纯音效」或「仅放音效时接管」";
                        }
                    }
                }

                float mGain = realMicGain;
                if (hasSfx && duckMicOnSfx) {
                    mGain *= DUCK_FACTOR;
                }
                float sGain = sfxGain;

                int outCount = FRAME_SAMPLES * channels;
                if (outBuf.length < outCount) outBuf = new short[outCount];

                if (channels == 2) {
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        float mixed = (i < micRead ? micBuf[i] * mGain : 0f) + (sfxBuf[i] * sGain);
                        short s = softClip(mixed);
                        outBuf[i * 2] = s;
                        outBuf[i * 2 + 1] = s;
                    }
                } else {
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        float mixed = (i < micRead ? micBuf[i] * mGain : 0f) + (sfxBuf[i] * sGain);
                        outBuf[i] = softClip(mixed);
                    }
                }

                try {
                    int written = track.write(outBuf, 0, outCount, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) {
                        if (!policyAttached) continue;
                        lastError = "AudioTrack.write 失败 (" + written + ")";
                        Log.e(TAG, lastError);
                        break;
                    }
                    writtenFrames++;
                    if (hasSfx) sfxFrames++;
                } catch (IllegalStateException ise) {
                    if (!policyAttached) continue;
                    throw ise;
                }
            }
        } catch (Throwable t) {
            if (running) {
                lastError = describeError(t);
                Log.e(TAG, "Injector loop failed: " + lastError, t);
            }
        } finally {
            if (realMic != null) closeRealMic(realMic);
            realMicActive = false;
        }
    }

    private AudioRecord tryOpenRealMic() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, FRAME_SAMPLES * 2 * 4);
        int[] sources = {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
        };
        Throwable lastErr = null;
        for (int source : sources) {
            AudioRecord record = null;
            try {
                AudioRecord.Builder builder = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(bufBytes);
                if (Build.VERSION.SDK_INT >= 31 && privilegedContext != null) {
                    Method setContext = findMethod(AudioRecord.Builder.class, "setContext", Context.class);
                    if (setContext != null) {
                        setContext.invoke(builder, privilegedContext);
                    }
                }
                record = builder.build();
                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording();
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        realMicActive = true;
                        realMicNote = "真麦混音运行中（音源 " + source + "）";
                        return record;
                    }
                }
                record.release();
            } catch (Throwable t) {
                lastErr = t;
                if (record != null) {
                    try {
                        record.release();
                    } catch (Throwable ignored) {}
                }
            }
        }
        realMicActive = false;
        realMicNote = "真麦采集不可用：" + (lastErr == null ? "无法初始化" : describeError(lastErr));
        return null;
    }

    private static void closeRealMic(AudioRecord record) {
        try {
            record.stop();
        } catch (Throwable ignored) {}
        try {
            record.release();
        } catch (Throwable ignored) {}
    }

    private static short softClip(float sample) {
        if (sample > 32767f) return 32767;
        if (sample < -32768f) return -32768;
        return (short) Math.round(sample);
    }

    private static float clampGain(float gain) {
        if (Float.isNaN(gain) || Float.isInfinite(gain)) return 1f;
        return Math.max(0f, Math.min(2f, gain));
    }

    private static int getStaticInt(Class<?> cls, String name, int fallback) {
        try {
            Field field = cls.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            try {
                Method m = cls.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }

    private static String describeError(Throwable t) {
        Throwable root = t;
        while (root instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            root = ite.getTargetException();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    private static final class PcmRingBuffer {
        private final short[] buffer;
        private int readPos;
        private int writePos;
        private int available;

        PcmRingBuffer(int capacity) {
            this.buffer = new short[capacity];
        }

        synchronized void clear() {
            readPos = 0;
            writePos = 0;
            available = 0;
        }

        synchronized void write(short[] data, int count) {
            int cap = buffer.length;
            if (count >= cap) {
                int start = count - cap;
                System.arraycopy(data, start, buffer, 0, cap);
                readPos = 0;
                writePos = 0;
                available = cap;
                return;
            }
            int overflow = (available + count) - cap;
            if (overflow > 0) {
                readPos = (readPos + overflow) % cap;
                available -= overflow;
            }
            int first = Math.min(count, cap - writePos);
            System.arraycopy(data, 0, buffer, writePos, first);
            if (count > first) {
                System.arraycopy(data, first, buffer, 0, count - first);
            }
            writePos = (writePos + count) % cap;
            available += count;
        }

        synchronized int read(short[] dst, int maxCount) {
            int toRead = Math.min(available, maxCount);
            if (toRead == 0) return 0;
            int cap = buffer.length;
            int first = Math.min(toRead, cap - readPos);
            System.arraycopy(buffer, readPos, dst, 0, first);
            if (toRead > first) {
                System.arraycopy(buffer, 0, dst, first, toRead - first);
            }
            readPos = (readPos + toRead) % cap;
            available -= toRead;
            return toRead;
        }
    }
}
