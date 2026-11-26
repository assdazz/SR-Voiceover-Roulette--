package com.srvr.mobile;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Client-side IVirtualMicService implementation talking to ShizukuMicProcessMain via
 * IShizukuService.newProcess() anonymous pipes (stdin/stdout/stderr).
 * Completely avoids Shizuku.bindUserService() hangs while keeping full 10ms PCM streaming & stderr capture.
 */
final class DirectProcessMicClient implements IVirtualMicService {
    private static final String TAG = "SRVR-DirectMic";
    private static final long READY_TIMEOUT_MS = 4500L;
    private static final long START_TIMEOUT_MS = 6000L;

    interface DisconnectListener {
        void onDisconnected(String reason);
    }

    private final IRemoteProcess remoteProcess;
    private final ParcelFileDescriptor stdinFd;
    private final ParcelFileDescriptor stdoutFd;
    private final ParcelFileDescriptor stderrFd;
    private final DataOutputStream out;
    private final Object writeLock = new Object();
    private final DisconnectListener disconnectListener;

    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final StringBuilder stderrLog = new StringBuilder();
    private final Object stderrLock = new Object();

    private volatile boolean closed;
    private volatile CountDownLatch startLatch;
    private volatile AtomicReference<String> startReply;
    private volatile CountDownLatch diagLatch;
    private volatile AtomicReference<String> diagReply;
    private volatile String cachedDiag = "channel=Shizuku直连(newProcess)";

    private Thread pcmForwardThread;
    private ParcelFileDescriptor activePcmReadFd;

    static DirectProcessMicClient launch(Context app, DisconnectListener listener) throws IOException {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) {
            throw new IOException("Shizuku Binder 不可用");
        }
        IShizukuService shizuku = IShizukuService.Stub.asInterface(binder);
        if (shizuku == null) {
            throw new IOException("无法获取 IShizukuService");
        }
        String apkPath = app.getApplicationInfo().sourceDir;
        String shellCmd = "export CLASSPATH=" + shellQuote(apkPath)
                + "; exec /system/bin/app_process /system/bin " + ShizukuMicProcessMain.class.getName();
        IRemoteProcess proc;
        try {
            proc = shizuku.newProcess(new String[]{"/system/bin/sh", "-c", shellCmd}, null, null);
        } catch (Throwable t) {
            throw new IOException("Shizuku.newProcess 调用失败：" + t.getMessage(), t);
        }
        if (proc == null) {
            throw new IOException("Shizuku.newProcess 返回空进程");
        }

        DirectProcessMicClient client = new DirectProcessMicClient(proc, listener);
        if (!client.awaitReady(READY_TIMEOUT_MS)) {
            String err = client.stderrSummary();
            client.destroy();
            throw new IOException("直连子进程启动超时" + (err.isEmpty() ? "" : "：" + err));
        }
        return client;
    }

    static boolean runQuickShellCommand(String shellCmd) {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.pingBinder()) return false;
            IShizukuService shizuku = IShizukuService.Stub.asInterface(binder);
            if (shizuku == null) return false;
            IRemoteProcess proc = shizuku.newProcess(new String[]{"/system/bin/sh", "-c", shellCmd}, null, null);
            if (proc == null) return false;
            try {
                proc.waitForTimeout(3000, "MILLISECONDS");
            } catch (Throwable ignored) {}
            try {
                proc.destroy();
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "runQuickShellCommand failed", t);
            return false;
        }
    }

    private DirectProcessMicClient(IRemoteProcess proc, DisconnectListener listener) throws IOException {
        this.remoteProcess = proc;
        this.disconnectListener = listener;
        try {
            this.stdinFd = proc.getOutputStream();
            this.stdoutFd = proc.getInputStream();
            this.stderrFd = proc.getErrorStream();
        } catch (RemoteException e) {
            throw new IOException("无法获取远端进程管道：" + e.getMessage(), e);
        }
        if (stdinFd == null || stdoutFd == null) {
            throw new IOException("远端进程管道为空");
        }
        this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(stdinFd.getFileDescriptor()), 8192));

        Thread outReader = new Thread(this::readStdoutLoop, "SRVR-DirectMicOut");
        outReader.setDaemon(true);
        outReader.start();

        if (stderrFd != null) {
            Thread errReader = new Thread(this::readStderrLoop, "SRVR-DirectMicErr");
            errReader.setDaemon(true);
            errReader.start();
        }
    }

    private boolean awaitReady(long timeoutMs) {
        try {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && !closed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void readStdoutLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(stdoutFd.getFileDescriptor()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("READY".equals(line)) {
                    readyLatch.countDown();
                } else if (line.startsWith("START:")) {
                    String decoded = decodeB64(line.substring(6));
                    AtomicReference<String> ref = startReply;
                    CountDownLatch latch = startLatch;
                    if (ref != null) ref.set(decoded);
                    if (latch != null) latch.countDown();
                } else if (line.startsWith("DIAG:")) {
                    String decoded = decodeB64(line.substring(5));
                    cachedDiag = decoded;
                    AtomicReference<String> ref = diagReply;
                    CountDownLatch latch = diagLatch;
                    if (ref != null) ref.set(decoded);
                    if (latch != null) latch.countDown();
                }
            }
        } catch (Throwable t) {
            if (!closed) {
                Log.w(TAG, "DirectMic stdout closed", t);
            }
        } finally {
            readyLatch.countDown();
            CountDownLatch sl = startLatch;
            if (sl != null) sl.countDown();
            CountDownLatch dl = diagLatch;
            if (dl != null) dl.countDown();
            if (!closed) {
                closed = true;
                String err = stderrSummary();
                String reason = err.isEmpty() ? "Shizuku 直连虚拟麦进程已退出" : "直连虚拟麦进程退出：" + err;
                if (disconnectListener != null) {
                    disconnectListener.onDisconnected(reason);
                }
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(stderrFd.getFileDescriptor()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.w(TAG, "[stderr] " + line);
                synchronized (stderrLock) {
                    if (stderrLog.length() > 1600) {
                        stderrLog.delete(0, stderrLog.length() - 1200);
                    }
                    if (stderrLog.length() > 0) stderrLog.append('\n');
                    stderrLog.append(line);
                }
            }
        } catch (Throwable ignored) {}
    }

    String stderrSummary() {
        synchronized (stderrLock) {
            return stderrLog.toString().trim();
        }
    }

    @Override public String start(int appUid, int mode, float realMicGain, float sfxGain,
            boolean duckMicOnSfx, ParcelFileDescriptor pcmReadPipe) throws RemoteException {
        if (closed) throw new RemoteException("Direct process already closed");
        stopPcmForwarder();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        this.startReply = ref;
        this.startLatch = latch;

        try {
            synchronized (writeLock) {
                out.writeByte(ShizukuMicProcessMain.CMD_START);
                out.writeInt(17);
                out.writeInt(appUid);
                out.writeInt(mode);
                out.writeFloat(realMicGain);
                out.writeFloat(sfxGain);
                out.writeByte(duckMicOnSfx ? 1 : 0);
                out.flush();
            }
            if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                String err = stderrSummary();
                return "ERR:初始化虚拟麦超时" + (err.isEmpty() ? "" : "（" + err + "）");
            }
            String result = ref.get();
            if (result == null) {
                String err = stderrSummary();
                return "ERR:直连进程未返回结果" + (err.isEmpty() ? "" : "（" + err + "）");
            }
            if (result.startsWith("OK:") && pcmReadPipe != null) {
                startPcmForwarder(pcmReadPipe.dup());
            }
            return result;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RemoteException("直连启动失败：" + e.getMessage());
        }
    }

    private void startPcmForwarder(ParcelFileDescriptor readFd) {
        activePcmReadFd = readFd;
        pcmForwardThread = new Thread(() -> {
            byte[] buf = new byte[ShizukuVirtualMicService.FRAME_SAMPLES * 2]; // 960 bytes = 10ms mono PCM
            try (FileInputStream in = new FileInputStream(readFd.getFileDescriptor())) {
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    synchronized (writeLock) {
                        out.writeByte(ShizukuMicProcessMain.CMD_PCM);
                        out.writeInt(n);
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(readFd);
            }
        }, "SRVR-DirectPcmFwd");
        pcmForwardThread.setDaemon(true);
        pcmForwardThread.start();
    }

    private void stopPcmForwarder() {
        ParcelFileDescriptor fd = activePcmReadFd;
        activePcmReadFd = null;
        closeQuietly(fd);
        Thread t = pcmForwardThread;
        pcmForwardThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override public void updateConfig(int mode, float realMicGain, float sfxGain, boolean duckMicOnSfx)
            throws RemoteException {
        if (closed) return;
        try {
            synchronized (writeLock) {
                out.writeByte(ShizukuMicProcessMain.CMD_CONFIG);
                out.writeInt(13);
                out.writeInt(mode);
                out.writeFloat(realMicGain);
                out.writeFloat(sfxGain);
                out.writeByte(duckMicOnSfx ? 1 : 0);
                out.flush();
            }
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override public void setInjectionActive(boolean active) throws RemoteException {
        if (closed) return;
        try {
            synchronized (writeLock) {
                out.writeByte(ShizukuMicProcessMain.CMD_ACTIVE);
                out.writeInt(1);
                out.writeByte(active ? 1 : 0);
                out.flush();
            }
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override public void stop() throws RemoteException {
        stopPcmForwarder();
        if (closed) return;
        try {
            synchronized (writeLock) {
                out.writeByte(ShizukuMicProcessMain.CMD_STOP);
                out.writeInt(0);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    @Override public String getStatus() throws RemoteException {
        if (closed) return cachedDiag;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        this.diagReply = ref;
        this.diagLatch = latch;
        try {
            synchronized (writeLock) {
                out.writeByte(ShizukuMicProcessMain.CMD_DIAG);
                out.writeInt(0);
                out.flush();
            }
            if (latch.await(800, TimeUnit.MILLISECONDS)) {
                String d = ref.get();
                if (d != null) return d;
            }
        } catch (Throwable ignored) {}
        String err = stderrSummary();
        return err.isEmpty() ? cachedDiag : cachedDiag + "\nstderr=" + err;
    }

    @Override public String shutdownShizuku(boolean disableWirelessAdb) throws RemoteException {
        stopPcmForwarder();
        if (!closed) {
            try {
                synchronized (writeLock) {
                    out.writeByte(ShizukuMicProcessMain.CMD_SHUTDOWN);
                    out.writeInt(1);
                    out.writeByte(disableWirelessAdb ? 1 : 0);
                    out.flush();
                }
            } catch (IOException ignored) {}
        }
        return "OK";
    }

    @Override public void destroy() {
        closed = true;
        stopPcmForwarder();
        closeQuietly(out);
        closeQuietly(stdinFd);
        closeQuietly(stdoutFd);
        closeQuietly(stderrFd);
        try {
            remoteProcess.destroy();
        } catch (Throwable ignored) {}
    }

    @Override public IBinder asBinder() {
        return null;
    }

    private static String decodeB64(String b64) {
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return b64;
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {}
    }
}
