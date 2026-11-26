package com.srvr.mobile;

import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Standalone UID 2000 entry point launched directly via IShizukuService.newProcess() + app_process.
 * Completely bypasses Shizuku's UserServiceStarter / LoadedApk / ShizukuProvider reverse callback,
 * fixing hangs on Android 16 / Shizuku 13.6.0 / strict OEM ROMs while reusing ShizukuVirtualMicService.
 */
public final class ShizukuMicProcessMain {
    static final int CMD_START = 1;
    static final int CMD_PCM = 2;
    static final int CMD_CONFIG = 3;
    static final int CMD_ACTIVE = 4;
    static final int CMD_DIAG = 5;
    static final int CMD_STOP = 6;
    static final int CMD_SHUTDOWN = 7;

    private static final Object OUT_LOCK = new Object();
    private static PrintStream protoOut;

    private ShizukuMicProcessMain() {}

    public static void main(String[] args) {
        // Reserve original stdout exclusively for line-based control replies; redirect System.out to stderr
        // so stray framework prints can never corrupt the control channel.
        protoOut = System.out;
        System.setOut(System.err);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("L");
            }
        } catch (Throwable t) {
            System.err.println("HiddenApiBypass warning: " + t);
        }

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable t) {
            System.err.println("prepareMainLooper warning: " + t);
        }

        // Pre-initialize ActivityThread on the main Looper thread so systemMain() binds cleanly.
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method current = atClass.getDeclaredMethod("currentActivityThread");
            if (current.invoke(null) == null) {
                Method systemMain = atClass.getDeclaredMethod("systemMain");
                systemMain.invoke(null);
            }
        } catch (Throwable t) {
            System.err.println("ActivityThread.systemMain warning: " + t);
        }

        Thread worker = new Thread(ShizukuMicProcessMain::runProtocolLoop, "SRVR-DirectMicProto");
        worker.setDaemon(true);
        worker.start();

        sendLine("READY");
        try {
            Looper.loop();
        } catch (Throwable t) {
            System.err.println("Main looper ended: " + t);
        }
        System.exit(0);
    }

    private static void runProtocolLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        ShizukuVirtualMicService service = new ShizukuVirtualMicService();
        service.setBufferLevelListener(avail -> sendLine("BUF:" + avail));
        ParcelFileDescriptor pcmWriteFd = null;
        FileOutputStream pcmOut = null;
        byte[] pcmBuf = new byte[8192];

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 16384))) {
            while (true) {
                int cmd;
                try {
                    cmd = in.readUnsignedByte();
                } catch (EOFException eof) {
                    break;
                }
                int len = in.readInt();
                if (len < 0 || len > 1024 * 1024) {
                    throw new IOException("Invalid packet length: " + len);
                }

                switch (cmd) {
                    case CMD_START -> {
                        int appUid = in.readInt();
                        int mode = in.readInt();
                        float realMicGain = in.readFloat();
                        float sfxGain = in.readFloat();
                        boolean duck = in.readByte() != 0;

                        closeQuietly(pcmOut);
                        closeQuietly(pcmWriteFd);
                        pcmOut = null;
                        pcmWriteFd = null;

                        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                        ParcelFileDescriptor readEnd = pipe[0];
                        pcmWriteFd = pipe[1];
                        pcmOut = new FileOutputStream(pcmWriteFd.getFileDescriptor());

                        String result = service.start(appUid, mode, realMicGain, sfxGain, duck, readEnd);
                        if (result == null || !result.startsWith("OK:")) {
                            closeQuietly(pcmOut);
                            closeQuietly(pcmWriteFd);
                            pcmOut = null;
                            pcmWriteFd = null;
                        }
                        sendEncoded("START", result == null ? "ERR:null" : result);
                    }
                    case CMD_PCM -> {
                        if (len > pcmBuf.length) {
                            pcmBuf = new byte[len];
                        }
                        in.readFully(pcmBuf, 0, len);
                        if (pcmOut != null && len > 0) {
                            try {
                                pcmOut.write(pcmBuf, 0, len);
                            } catch (IOException ignored) {}
                        }
                    }
                    case CMD_CONFIG -> {
                        int mode = in.readInt();
                        float realMicGain = in.readFloat();
                        float sfxGain = in.readFloat();
                        boolean duck = in.readByte() != 0;
                        service.updateConfig(mode, realMicGain, sfxGain, duck);
                    }
                    case CMD_ACTIVE -> {
                        boolean active = in.readByte() != 0;
                        service.setInjectionActive(active);
                    }
                    case CMD_DIAG -> {
                        String diag = service.getStatus();
                        sendEncoded("DIAG", "channel=Shizuku直连(newProcess)\n" + (diag == null ? "" : diag));
                    }
                    case CMD_STOP -> {
                        closeQuietly(pcmOut);
                        closeQuietly(pcmWriteFd);
                        pcmOut = null;
                        pcmWriteFd = null;
                        service.stop();
                        sendLine("STOPPED");
                        break;
                    }
                    case CMD_SHUTDOWN -> {
                        boolean disableWifi = in.readByte() != 0;
                        closeQuietly(pcmOut);
                        closeQuietly(pcmWriteFd);
                        pcmOut = null;
                        pcmWriteFd = null;
                        String res = service.shutdownShizuku(disableWifi);
                        sendEncoded("SHUTDOWN", res == null ? "OK" : res);
                        return;
                    }
                    default -> {
                        skipFully(in, len);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("DirectMic protocol ended: " + t);
        } finally {
            closeQuietly(pcmOut);
            closeQuietly(pcmWriteFd);
            try {
                service.stop();
            } catch (Throwable ignored) {}
            System.exit(0);
        }
    }

    private static void skipFully(DataInputStream in, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException();
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void sendLine(String line) {
        synchronized (OUT_LOCK) {
            protoOut.println(line);
            protoOut.flush();
        }
    }

    private static void sendEncoded(String tag, String text) {
        String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        sendLine(tag + ":" + b64);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {}
    }
}
