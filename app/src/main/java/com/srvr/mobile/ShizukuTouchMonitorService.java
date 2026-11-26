package com.srvr.mobile;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShizukuTouchMonitorService extends ITouchMonitorService.Stub {
    private static final String TAG = "SRVR-TouchMon";
    private static final int MAX_SLOTS = 16;

    private static final int EV_SYN = 0;
    private static final int SYN_REPORT = 0;
    private static final int EV_KEY = 1;
    private static final int BTN_TOUCH = 0x14a;
    private static final int EV_ABS = 3;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;

    private static final Pattern DEVICE_LINE = Pattern.compile("add device \\d+:\\s*(/dev/input/event\\d+)");
    private static final Pattern NAME_LINE = Pattern.compile("name:\\s*\"([^\"]*)\"");
    private static final Pattern AXIS_LINE = Pattern.compile(
            "\\b(0035|0036)\\s*:.*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");

    private final Object lock = new Object();
    private volatile boolean running;
    private volatile Callback callback;
    private volatile String statusInfo = "未启动";
    private volatile long eventCount;
    private FileInputStream deviceStream;
    private Thread readerThread;

    public ShizukuTouchMonitorService() {}

    private record TouchDevice(String path, String name, int minX, int maxX, int minY, int maxY) {
        boolean valid() {
            return path != null && maxX > minX && maxY > minY;
        }

        int priority() {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (lower.contains("touch") || lower.contains("_ts") || lower.endsWith("ts")
                    || lower.contains("fts") || lower.contains("goodix") || lower.contains("focal")
                    || lower.contains("synaptics") || lower.contains("sec_touch") || lower.contains("screen")) {
                return 2;
            }
            return 1;
        }
    }

    @Override public String start(Callback callback) {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
                this.callback = callback;
                this.eventCount = 0;

                TouchDevice device = findTouchDevice();
                if (device == null) {
                    statusInfo = "未找到触摸屏输入设备";
                    return "ERR:" + statusInfo;
                }
                try {
                    deviceStream = new FileInputStream(device.path());
                } catch (IOException e) {
                    statusInfo = "无法打开 " + device.path() + " (" + e.getMessage() + ")";
                    Log.w(TAG, statusInfo, e);
                    return "ERR:" + statusInfo;
                }

                running = true;
                statusInfo = device.path() + " (" + device.name() + ") ["
                        + device.minX() + ".." + device.maxX() + " x " + device.minY() + ".." + device.maxY() + "]";
                readerThread = new Thread(() -> readLoop(device), "SRVR-TouchEvdev");
                readerThread.start();
                Log.i(TAG, "Touch monitor started: " + statusInfo);
                return "OK:" + statusInfo;
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
        return "running=" + running + " uid=" + Process.myUid()
                + " events=" + eventCount + " device=" + statusInfo;
    }

    @Override public void destroy() {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                stopLocked();
            }
            System.exit(0);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void stopLocked() {
        running = false;
        callback = null;
        if (deviceStream != null) {
            try {
                deviceStream.close();
            } catch (IOException ignored) {}
            deviceStream = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(400);
            } catch (InterruptedException ignored) {}
            readerThread = null;
        }
        statusInfo = "已停止";
    }

    private void readLoop(TouchDevice device) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        int structSize = Process.is64Bit() ? 24 : 16;
        int typeOffset = Process.is64Bit() ? 16 : 8;
        byte[] buf = new byte[structSize * 32];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        boolean[] active = new boolean[MAX_SLOTS];
        boolean[] wasActive = new boolean[MAX_SLOTS];
        boolean[] dirty = new boolean[MAX_SLOTS];
        int[] rawX = new int[MAX_SLOTS];
        int[] rawY = new int[MAX_SLOTS];
        int currentSlot = 0;

        float spanX = Math.max(1f, device.maxX() - device.minX());
        float spanY = Math.max(1f, device.maxY() - device.minY());
        FileInputStream in = this.deviceStream;
        if (in == null) return;

        int carry = 0;
        try {
            while (running) {
                int read = in.read(buf, carry, buf.length - carry);
                if (read < 0) break;
                int total = carry + read;
                int offset = 0;
                while (offset + structSize <= total) {
                    int type = bb.getShort(offset + typeOffset) & 0xffff;
                    int code = bb.getShort(offset + typeOffset + 2) & 0xffff;
                    int value = bb.getInt(offset + typeOffset + 4);
                    offset += structSize;

                    if (type == EV_ABS) {
                        if (code == ABS_MT_SLOT) {
                            currentSlot = (value >= 0 && value < MAX_SLOTS) ? value : 0;
                        } else if (code == ABS_MT_TRACKING_ID) {
                            if (value >= 0) {
                                active[currentSlot] = true;
                                dirty[currentSlot] = true;
                            } else {
                                active[currentSlot] = false;
                                dirty[currentSlot] = true;
                            }
                        } else if (code == ABS_MT_POSITION_X) {
                            rawX[currentSlot] = value;
                            dirty[currentSlot] = true;
                        } else if (code == ABS_MT_POSITION_Y) {
                            rawY[currentSlot] = value;
                            dirty[currentSlot] = true;
                        }
                    } else if (type == EV_KEY && code == BTN_TOUCH && value == 0) {
                        for (int s = 0; s < MAX_SLOTS; s++) {
                            if (active[s]) {
                                active[s] = false;
                                dirty[s] = true;
                            }
                        }
                    } else if (type == EV_SYN && code == SYN_REPORT) {
                        Callback cb = this.callback;
                        for (int s = 0; s < MAX_SLOTS; s++) {
                            if (!dirty[s] && active[s] == wasActive[s]) continue;
                            float nx = clamp01((rawX[s] - device.minX()) / spanX);
                            float ny = clamp01((rawY[s] - device.minY()) / spanY);
                            int action = -1;
                            if (!wasActive[s] && active[s]) {
                                action = ACTION_DOWN;
                            } else if (wasActive[s] && !active[s]) {
                                action = ACTION_UP;
                            } else if (wasActive[s] && active[s]) {
                                action = ACTION_MOVE;
                            }
                            wasActive[s] = active[s];
                            dirty[s] = false;
                            if (action >= 0 && cb != null) {
                                eventCount++;
                                try {
                                    cb.onTouchEvent(s, action, nx, ny);
                                } catch (RemoteException e) {
                                    Log.w(TAG, "Touch callback disconnected");
                                    running = false;
                                    return;
                                }
                            }
                        }
                    }
                }
                carry = total - offset;
                if (carry > 0) {
                    System.arraycopy(buf, offset, buf, 0, carry);
                }
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "Touch evdev reader stopped: " + e.getMessage());
        }
    }

    private static float clamp01(float v) {
        if (!Float.isFinite(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private static TouchDevice findTouchDevice() {
        List<TouchDevice> candidates = new ArrayList<>();
        java.lang.Process proc = null;
        try {
            proc = new ProcessBuilder("getevent", "-p").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String path = null;
                String name = "";
                int minX = 0, maxX = -1, minY = 0, maxY = -1;
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher dm = DEVICE_LINE.matcher(line);
                    if (dm.find()) {
                        addIfValid(candidates, path, name, minX, maxX, minY, maxY);
                        path = dm.group(1);
                        name = "";
                        minX = 0; maxX = -1; minY = 0; maxY = -1;
                        continue;
                    }
                    if (path == null) continue;
                    Matcher nm = NAME_LINE.matcher(line);
                    if (nm.find()) {
                        name = nm.group(1);
                        continue;
                    }
                    Matcher am = AXIS_LINE.matcher(line);
                    if (am.find()) {
                        String code = am.group(1);
                        int min = Integer.parseInt(am.group(2));
                        int max = Integer.parseInt(am.group(3));
                        if ("0035".equals(code)) {
                            minX = min;
                            maxX = max;
                        } else if ("0036".equals(code)) {
                            minY = min;
                            maxY = max;
                        }
                    }
                }
                addIfValid(candidates, path, name, minX, maxX, minY, maxY);
            }
            proc.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "getevent -p failed", t);
        } finally {
            if (proc != null) proc.destroy();
        }

        TouchDevice best = null;
        for (TouchDevice dev : candidates) {
            if (best == null || dev.priority() > best.priority()) {
                best = dev;
            }
        }
        return best;
    }

    private static void addIfValid(List<TouchDevice> list, String path, String name,
            int minX, int maxX, int minY, int maxY) {
        TouchDevice d = new TouchDevice(path, name, minX, maxX, minY, maxY);
        if (d.valid()) list.add(d);
    }
}
