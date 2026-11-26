package com.srvr.mobile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VirtualMicPcmEngine {
    static final int SAMPLE_RATE = ShizukuVirtualMicService.SAMPLE_RATE;
    static final int FRAME_SAMPLES = ShizukuVirtualMicService.FRAME_SAMPLES;
    private static final int FRAME_MS = 10;
    private static final long FRAME_NS = 10_000_000L;
    private static final int BURST_PREFILL_FRAMES = 3; // Fast 30ms onset cushion (<0.5ms burst, low latency)
    private static final int TARGET_LOW_SAMPLES = SAMPLE_RATE * 30 / 1000;  // 30ms low watermark
    private static final int TARGET_HIGH_SAMPLES = SAMPLE_RATE * 60 / 1000; // 60ms high watermark
    private static final int TAIL_SILENCE_FRAMES = 4;  // 40ms clean silence tail
    private static final int HOLD_ACTIVE_MS = 1000;    // Keep mic routed 1s for zero-latency consecutive clips
    private static final String TAG = "SRVR-MicEngine";

    interface ClipHandle {
        void setGain(float gain);
        void stop();
    }

    interface ActivityCallback {
        void onAudioActivityChanged(boolean active);
    }

    private final ExecutorService decoders = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SRVR-MicDecoder");
        thread.setDaemon(true);
        return thread;
    });

    private final CopyOnWriteArrayList<ClipStream> activeClips = new CopyOnWriteArrayList<>();
    private final Object pumpLock = new Object();

    private volatile boolean running;
    private volatile Runnable onPipeBroken;
    private volatile ActivityCallback activityCallback;
    private volatile long sentFrames;
    private volatile int remoteBufferSamples = -1;
    private volatile long remoteBufferUpdatedMs;
    private volatile String lastClipInfo = "暂无";
    private volatile String lastDecodeError = "";

    private ParcelFileDescriptor writePipe;
    private Thread pumpThread;

    void updateRemoteBufferSamples(int availSamples) {
        this.remoteBufferSamples = availSamples;
        this.remoteBufferUpdatedMs = SystemClock.elapsedRealtime();
    }

    synchronized void start(ParcelFileDescriptor writePipe, Runnable onPipeBroken, ActivityCallback activityCallback) {
        stop();
        this.writePipe = writePipe;
        this.onPipeBroken = onPipeBroken;
        this.activityCallback = activityCallback;
        this.sentFrames = 0;
        this.lastDecodeError = "";
        this.running = true;
        this.pumpThread = new Thread(this::pumpLoop, "SRVR-MicPump");
        this.pumpThread.start();
    }

    synchronized void stop() {
        running = false;
        for (ClipStream clip : activeClips) {
            clip.cancel();
        }
        activeClips.clear();
        synchronized (pumpLock) {
            pumpLock.notifyAll();
        }
        if (writePipe != null) {
            try {
                writePipe.close();
            } catch (IOException ignored) {}
            writePipe = null;
        }
        if (pumpThread != null) {
            pumpThread.interrupt();
            try {
                pumpThread.join(500);
            } catch (InterruptedException ignored) {}
            pumpThread = null;
        }
    }

    boolean isRunning() {
        return running;
    }

    String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("sentFrames=").append(sentFrames).append(" lastClip=").append(lastClipInfo);
        if (!lastDecodeError.isEmpty()) {
            sb.append("\ndecodeError=").append(lastDecodeError);
        }
        return sb.toString();
    }

    ClipHandle enqueue(Context app, SoundLibrary.Sound sound, float gain, SoundSequence.Variation variation) {
        return enqueue(app, sound, gain, variation, null);
    }

    ClipHandle enqueue(Context app, SoundLibrary.Sound sound, float gain, SoundSequence.Variation variation,
            Runnable onFinished) {
        if (!running || sound == null) return null;
        float speedFactor = 1f;
        if (variation != null) {
            float speed = variation.speed() > 0f ? variation.speed() : 1f;
            float pitch = variation.pitchRatio() > 0f ? variation.pitchRatio() : 1f;
            speedFactor = Math.max(0.25f, Math.min(4.0f, speed * pitch));
        }
        lastClipInfo = sound.filename();
        lastDecodeError = "";
        ClipStream clip = new ClipStream(app.getApplicationContext(), sound, gain, speedFactor,
                err -> lastDecodeError = err, onFinished);
        activeClips.add(clip);
        decoders.execute(clip::decodeLoop);
        synchronized (pumpLock) {
            pumpLock.notifyAll();
        }
        return clip;
    }

    void stopAllClips() {
        for (ClipStream clip : activeClips) {
            clip.cancel();
        }
        activeClips.clear();
    }

    private void pumpLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        ParcelFileDescriptor pipe = this.writePipe;
        if (pipe == null) return;

        short[] clipBuf = new short[FRAME_SAMPLES];
        float[] accum = new float[FRAME_SAMPLES];
        byte[] wireBuf = new byte[FRAME_SAMPLES * 2];
        int tailSilenceFrames = 0;
        int burstRemaining = 0;
        boolean wasAudible = false;
        boolean notifiedActive = false;
        long holdUntilMs = 0;

        try (FileOutputStream out = new FileOutputStream(pipe.getFileDescriptor())) {
            long nextTickNs = System.nanoTime();
            while (running) {
                if (activeClips.isEmpty() && tailSilenceFrames <= 0) {
                    wasAudible = false;
                    burstRemaining = 0;
                    if (notifiedActive) {
                        long now = SystemClock.elapsedRealtime();
                        long remainingHold = holdUntilMs - now;
                        if (remainingHold <= 0) {
                            notifiedActive = false;
                            notifyActivity(false);
                        } else {
                            synchronized (pumpLock) {
                                if (running && activeClips.isEmpty()) {
                                    try {
                                        pumpLock.wait(remainingHold);
                                    } catch (InterruptedException e) {
                                        if (!running) return;
                                    }
                                }
                            }
                            nextTickNs = System.nanoTime();
                            continue;
                        }
                    }
                    synchronized (pumpLock) {
                        while (running && activeClips.isEmpty()) {
                            try {
                                pumpLock.wait();
                            } catch (InterruptedException e) {
                                if (!running) return;
                            }
                        }
                    }
                    nextTickNs = System.nanoTime();
                    continue;
                }

                if (!notifiedActive) {
                    notifiedActive = true;
                    notifyActivity(true);
                }

                java.util.Arrays.fill(accum, 0f);
                boolean anySamples = false;

                Iterator<ClipStream> it = activeClips.iterator();
                while (it.hasNext()) {
                    ClipStream clip = it.next();
                    if (clip.isCancelled()) {
                        activeClips.remove(clip);
                        continue;
                    }
                    int read = clip.read(clipBuf, FRAME_SAMPLES);
                    if (read > 0) {
                        anySamples = true;
                        float g = clip.gain();
                        for (int i = 0; i < read; i++) {
                            accum[i] += clipBuf[i] * g;
                        }
                    }
                    if (clip.isFinished()) {
                        activeClips.remove(clip);
                        clip.notifyFinished();
                    }
                }

                if (anySamples) {
                    if (!wasAudible) {
                        wasAudible = true;
                        burstRemaining = BURST_PREFILL_FRAMES;
                    }
                    tailSilenceFrames = TAIL_SILENCE_FRAMES;
                    holdUntilMs = SystemClock.elapsedRealtime() + HOLD_ACTIVE_MS;
                } else if (tailSilenceFrames > 0) {
                    tailSilenceFrames--;
                    if (tailSilenceFrames == 0) wasAudible = false;
                }

                if (anySamples || tailSilenceFrames > 0) {
                    for (int i = 0; i < FRAME_SAMPLES; i++) {
                        short s = softLimitSample(accum[i]);
                        wireBuf[i * 2] = (byte) (s & 0xff);
                        wireBuf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                    }
                    out.write(wireBuf, 0, wireBuf.length);
                    if (anySamples) sentFrames++;
                }

                // Burst first 30ms immediately at onset so UID 2000 AudioTrack starts glitch-free with minimal latency.
                if (anySamples && burstRemaining > 0) {
                    burstRemaining--;
                    nextTickNs = System.nanoTime();
                    continue;
                }

                // Closed-loop clock sync: keep UID 2000 buffer tightly pinned in [30ms, 60ms] for low latency & zero drift.
                long periodNs = FRAME_NS;
                if (anySamples) {
                    long ageMs = SystemClock.elapsedRealtime() - remoteBufferUpdatedMs;
                    int remoteAvail = remoteBufferSamples;
                    if (remoteAvail >= 0 && ageMs <= 400L) {
                        if (remoteAvail < SAMPLE_RATE * 15 / 1000) {
                            periodNs = 7_000_000L;
                        } else if (remoteAvail < TARGET_LOW_SAMPLES) {
                            periodNs = 9_400_000L;
                        } else if (remoteAvail > TARGET_HIGH_SAMPLES) {
                            periodNs = 10_600_000L;
                        }
                    }
                }

                nextTickNs += periodNs;
                long sleepNs = nextTickNs - System.nanoTime();
                if (sleepNs > 500_000L) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        if (!running) return;
                    }
                } else if (sleepNs < -100_000_000L) {
                    nextTickNs = System.nanoTime();
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "Virtual mic pipe broken: " + e.getMessage());
                running = false;
                Runnable broken = this.onPipeBroken;
                if (broken != null) broken.run();
            }
        } finally {
            if (notifiedActive) notifyActivity(false);
        }
    }

    private void notifyActivity(boolean active) {
        ActivityCallback cb = this.activityCallback;
        if (cb != null) {
            try {
                cb.onAudioActivityChanged(active);
            } catch (Throwable ignored) {}
        }
    }

    private static short softLimitSample(float sample) {
        final float knee = 30140f; // Transparent up to ~92% full scale, smooth tanh above
        float abs = Math.abs(sample);
        if (abs <= knee) return (short) Math.round(sample);
        float headroom = 32767f - knee;
        float excess = (abs - knee) / headroom;
        float limited = knee + headroom * (float) Math.tanh(excess);
        return (short) Math.round(sample < 0f ? -limited : limited);
    }

    private interface ErrorSink {
        void onError(String message);
    }

    private static final class ClipStream implements ClipHandle {
        private static final int QUEUE_CAPACITY = SAMPLE_RATE; // 1s pre-decoded buffer (~96KB)

        private final Context app;
        private final SoundLibrary.Sound sound;
        private final float rateMultiplier;
        private final ErrorSink errorSink;
        private final Runnable onFinished;
        private final short[] ring = new short[QUEUE_CAPACITY];

        private volatile float gain;
        private volatile boolean cancelled;
        private volatile boolean decoderDone;
        private volatile boolean finishedNotified;

        private int readPos;
        private int writePos;
        private int available;

        ClipStream(Context app, SoundLibrary.Sound sound, float gain, float rateMultiplier,
                ErrorSink errorSink, Runnable onFinished) {
            this.app = app;
            this.sound = sound;
            this.gain = Math.max(0f, Math.min(2f, gain));
            this.rateMultiplier = rateMultiplier;
            this.errorSink = errorSink;
            this.onFinished = onFinished;
        }

        void notifyFinished() {
            if (finishedNotified || cancelled) return;
            finishedNotified = true;
            if (onFinished != null) {
                try {
                    onFinished.run();
                } catch (Throwable ignored) {}
            }
        }

        float gain() {
            return gain;
        }

        @Override public void setGain(float gain) {
            this.gain = Math.max(0f, Math.min(2f, gain));
        }

        @Override public void stop() {
            cancel();
        }

        void cancel() {
            cancelled = true;
            synchronized (ring) {
                available = 0;
                readPos = 0;
                writePos = 0;
                ring.notifyAll();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean isFinished() {
            synchronized (ring) {
                return cancelled || (decoderDone && available == 0);
            }
        }

        int read(short[] dst, int maxCount) {
            synchronized (ring) {
                if (cancelled || available == 0) return 0;
                int count = Math.min(available, maxCount);
                int first = Math.min(count, ring.length - readPos);
                System.arraycopy(ring, readPos, dst, 0, first);
                if (count > first) {
                    System.arraycopy(ring, 0, dst, first, count - first);
                }
                readPos = (readPos + count) % ring.length;
                available -= count;
                ring.notifyAll();
                return count;
            }
        }

        private void pushSamples(short[] data, int count) throws InterruptedException {
            int offset = 0;
            while (offset < count && !cancelled) {
                synchronized (ring) {
                    while (!cancelled && available >= ring.length) {
                        ring.wait(50);
                    }
                    if (cancelled) return;
                    int free = ring.length - available;
                    int toWrite = Math.min(free, count - offset);
                    int first = Math.min(toWrite, ring.length - writePos);
                    System.arraycopy(data, offset, ring, writePos, first);
                    if (toWrite > first) {
                        System.arraycopy(data, offset + first, ring, 0, toWrite - first);
                    }
                    writePos = (writePos + toWrite) % ring.length;
                    available += toWrite;
                    offset += toWrite;
                }
            }
        }

        void decodeLoop() {
            try {
                // Fast, bulletproof direct RIFF/WAVE reader for WAV files (avoids MediaExtractor buffer issues).
                if (sound.filename().toLowerCase(Locale.ROOT).endsWith(".wav") && tryDecodeWavDirect()) {
                    return;
                }
                decodeWithMediaExtractor();
            } catch (Throwable t) {
                if (!cancelled) {
                    String err = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage());
                    Log.w(TAG, "Decode failed for " + sound.id() + " (" + err + ")", t);
                    if (errorSink != null) errorSink.onError(sound.filename() + " -> " + err);
                }
            } finally {
                decoderDone = true;
            }
        }

        private InputStream openSoundStream() throws IOException {
            if (sound.bundled()) {
                return app.getAssets().open(sound.assetPath());
            }
            return new FileInputStream(SoundLibrary.get(app).file(sound));
        }

        private boolean tryDecodeWavDirect() throws IOException, InterruptedException {
            try (InputStream raw = new BufferedInputStream(openSoundStream(), 32768)) {
                byte[] header = readExact(raw, 12);
                if (header == null) return false;
                if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F'
                        || header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
                    return false;
                }

                int audioFormat = -1;
                int channels = 0;
                int sampleRate = 0;
                int bitsPerSample = 0;
                long dataSize = -1;

                while (!cancelled) {
                    byte[] chunkHeader = readExact(raw, 8);
                    if (chunkHeader == null) break;
                    String chunkId = new String(chunkHeader, 0, 4);
                    long chunkSize = u32le(chunkHeader, 4);
                    if ("fmt ".equals(chunkId)) {
                        int readLen = (int) Math.min(chunkSize, 64);
                        byte[] fmt = readExact(raw, readLen);
                        if (fmt == null || fmt.length < 16) return false;
                        audioFormat = u16le(fmt, 0);
                        channels = u16le(fmt, 2);
                        sampleRate = (int) u32le(fmt, 4);
                        bitsPerSample = u16le(fmt, 14);
                        // Handle WAVE_FORMAT_EXTENSIBLE (0xFFFE)
                        if (audioFormat == 0xFFFE && fmt.length >= 26) {
                            audioFormat = u16le(fmt, 24);
                        }
                        long remaining = chunkSize - readLen;
                        if (remaining > 0) skipFully(raw, remaining);
                    } else if ("data".equals(chunkId)) {
                        dataSize = (chunkSize == 0xFFFFFFFFL || chunkSize == 0xFFFFFFFEL)
                                ? Long.MAX_VALUE : chunkSize;
                        break;
                    } else {
                        skipFully(raw, chunkSize);
                    }
                    if ((chunkSize & 1) == 1) {
                        skipFully(raw, 1);
                    }
                }

                if (dataSize <= 0 || channels <= 0 || sampleRate <= 0) return false;
                int pcmEncoding;
                if (audioFormat == 1) {
                    pcmEncoding = switch (bitsPerSample) {
                        case 8 -> AudioFormat.ENCODING_PCM_8BIT;
                        case 16 -> AudioFormat.ENCODING_PCM_16BIT;
                        case 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED;
                        case 32 -> AudioFormat.ENCODING_PCM_32BIT;
                        default -> -1;
                    };
                } else if (audioFormat == 3 && bitsPerSample == 32) {
                    pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT;
                } else {
                    return false; // Non-PCM WAV (e.g. ADPCM / MP3 inside WAV) -> fallback to MediaExtractor
                }
                if (pcmEncoding < 0) return false;

                int bytesPerFrame = (bitsPerSample / 8) * channels;
                int chunkBytes = Math.max(bytesPerFrame, (16384 / bytesPerFrame) * bytesPerFrame);
                byte[] buf = new byte[chunkBytes];
                Resampler resampler = new Resampler(sampleRate, SAMPLE_RATE, rateMultiplier);
                long remaining = dataSize;

                while (!cancelled && remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    want = (want / bytesPerFrame) * bytesPerFrame;
                    if (want <= 0) break;
                    int read = readAligned(raw, buf, want, bytesPerFrame);
                    if (read <= 0) break;
                    remaining -= read;
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN);
                    short[] mono = convertToMono16(bb, read, channels, pcmEncoding);
                    emitResampled(resampler, mono);
                }
                return true;
            }
        }

        private void decodeWithMediaExtractor() throws IOException, InterruptedException {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            AssetFileDescriptor afd = null;
            try {
                if (sound.bundled()) {
                    afd = app.getAssets().openFd(sound.assetPath());
                    extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } else {
                    extractor.setDataSource(SoundLibrary.get(app).file(sound).getAbsolutePath());
                }

                int audioTrack = -1;
                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrack = i;
                        format = f;
                        break;
                    }
                }
                if (audioTrack < 0 || format == null) {
                    throw new IOException("No audio track found");
                }
                extractor.selectTrack(audioTrack);

                String mime = format.getString(MediaFormat.KEY_MIME);
                int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
                int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                int pcmEncoding = getPcmEncoding(format);
                int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                        ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;

                if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) {
                    decodeRawPcm(extractor, sampleRate, channels, pcmEncoding, maxInput);
                } else {
                    // Ensure large lossless/FLAC/VBR frames never overflow MediaCodec's default input buffer.
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(maxInput, 512 * 1024));
                    codec = MediaCodec.createDecoderByType(mime);
                    codec.configure(format, null, null, 0);
                    codec.start();
                    decodeWithCodec(extractor, codec, sampleRate, channels, pcmEncoding);
                }
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Throwable ignored) {}
                    try {
                        codec.release();
                    } catch (Throwable ignored) {}
                }
                try {
                    extractor.release();
                } catch (Throwable ignored) {}
                if (afd != null) {
                    try {
                        afd.close();
                    } catch (IOException ignored) {}
                }
            }
        }

        private void decodeRawPcm(MediaExtractor extractor, int srcRate, int channels, int pcmEncoding, int maxInput)
                throws InterruptedException {
            int bufSize = Math.max(262144, maxInput);
            ByteBuffer buffer = ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN);
            Resampler resampler = new Resampler(srcRate, SAMPLE_RATE, rateMultiplier);
            while (!cancelled) {
                long sampleBytes = extractor.getSampleSize();
                if (sampleBytes > buffer.capacity() && sampleBytes <= 4 * 1024 * 1024) {
                    buffer = ByteBuffer.allocate((int) sampleBytes + 4096).order(ByteOrder.LITTLE_ENDIAN);
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (size > 0) {
                    buffer.position(0);
                    buffer.limit(size);
                    short[] mono = convertToMono16(buffer, size, channels, pcmEncoding);
                    emitResampled(resampler, mono);
                }
                extractor.advance();
            }
        }

        private void decodeWithCodec(MediaExtractor extractor, MediaCodec codec,
                int initialRate, int initialChannels, int initialEncoding) throws InterruptedException {
            int srcRate = initialRate;
            int channels = initialChannels;
            int pcmEncoding = initialEncoding;
            Resampler resampler = new Resampler(srcRate, SAMPLE_RATE, rateMultiplier);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;

            while (!cancelled && !outputEos) {
                if (!inputEos) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf != null) {
                            inBuf.clear();
                            long expectedSize = extractor.getSampleSize();
                            if (expectedSize > inBuf.capacity()) {
                                Log.w(TAG, "Skipping oversized compressed frame (" + expectedSize
                                        + " > " + inBuf.capacity() + ") for " + sound.filename());
                                extractor.advance();
                                codec.queueInputBuffer(inIndex, 0, 0, 0, 0);
                            } else {
                                int sampleSize = extractor.readSampleData(inBuf, 0);
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputEos = true;
                                } else {
                                    long pts = extractor.getSampleTime();
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                                    extractor.advance();
                                }
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    pcmEncoding = getPcmEncoding(outFmt);
                    resampler = new Resampler(srcRate, SAMPLE_RATE, rateMultiplier);
                } else if (outIndex >= 0) {
                    short[] mono = null;
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            ByteBuffer slice = outBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            slice.position(info.offset);
                            slice.limit(info.offset + info.size);
                            mono = convertToMono16(slice, info.size, channels, pcmEncoding);
                        }
                    }
                    // Release MediaCodec buffer BEFORE blocking on ring queue so system C2/OMX slots never stall.
                    codec.releaseOutputBuffer(outIndex, false);
                    if (mono != null && mono.length > 0) {
                        emitResampled(resampler, mono);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                    }
                }
            }
        }

        private void emitResampled(Resampler resampler, short[] mono) throws InterruptedException {
            if (mono.length == 0) return;
            short[] out = resampler.process(mono);
            if (out.length > 0) {
                pushSamples(out, out.length);
            }
        }

        private static byte[] readExact(InputStream in, int length) throws IOException {
            byte[] buf = new byte[length];
            int offset = 0;
            while (offset < length) {
                int r = in.read(buf, offset, length - offset);
                if (r < 0) return null;
                offset += r;
            }
            return buf;
        }

        private static int readAligned(InputStream in, byte[] dst, int maxBytes, int alignment) throws IOException {
            int r = in.read(dst, 0, maxBytes);
            if (r <= 0) return r;
            int rem = r % alignment;
            if (rem != 0) {
                int need = alignment - rem;
                int offset = r;
                while (need > 0) {
                    int more = in.read(dst, offset, need);
                    if (more < 0) return (offset / alignment) * alignment;
                    offset += more;
                    need -= more;
                }
                return offset;
            }
            return r;
        }

        private static void skipFully(InputStream in, long bytes) throws IOException {
            long rem = bytes;
            while (rem > 0) {
                long skipped = in.skip(rem);
                if (skipped > 0) {
                    rem -= skipped;
                } else {
                    if (in.read() < 0) break;
                    rem--;
                }
            }
        }

        private static int u16le(byte[] b, int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }

        private static long u32le(byte[] b, int off) {
            return (b[off] & 0xffL)
                    | ((b[off + 1] & 0xffL) << 8)
                    | ((b[off + 2] & 0xffL) << 16)
                    | ((b[off + 3] & 0xffL) << 24);
        }

        private static int getPcmEncoding(MediaFormat format) {
            if (Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                return format.getInteger(MediaFormat.KEY_PCM_ENCODING);
            }
            return AudioFormat.ENCODING_PCM_16BIT;
        }

        private static short[] convertToMono16(ByteBuffer buf, int byteSize, int channels, int encoding) {
            int ch = Math.max(1, channels);
            int bytesPerSample = switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> 1;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
                case AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4;
                default -> 2;
            };
            int frameBytes = bytesPerSample * ch;
            int frames = byteSize / frameBytes;
            if (frames <= 0) return new short[0];

            short[] mono = new short[frames];
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                for (int c = 0; c < ch; c++) {
                    sum += readSample(buf, encoding);
                }
                float avg = sum / ch;
                if (avg > 32767f) avg = 32767f;
                else if (avg < -32768f) avg = -32768f;
                mono[f] = (short) Math.round(avg);
            }
            return mono;
        }

        private static float readSample(ByteBuffer buf, int encoding) {
            return switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> ((buf.get() & 0xff) - 128) * 256f;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    int b0 = buf.get() & 0xff;
                    int b1 = buf.get() & 0xff;
                    int b2 = buf.get();
                    int val = (b2 << 16) | (b1 << 8) | b0;
                    yield val / 256f;
                }
                case AudioFormat.ENCODING_PCM_32BIT -> buf.getInt() / 65536f;
                case AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat() * 32767f;
                default -> buf.getShort();
            };
        }
    }

    private static final class Resampler {
        private final double step;
        private double phase;
        private short prevSample;
        private boolean hasPrev;

        Resampler(int srcRate, int dstRate, float rateMultiplier) {
            int safeSrc = srcRate > 0 ? srcRate : dstRate;
            this.step = ((double) safeSrc / (double) dstRate) * rateMultiplier;
        }

        short[] process(short[] input) {
            if (input.length == 0) return new short[0];
            if (Math.abs(step - 1.0) < 1e-4 && Math.abs(phase) < 1e-4) {
                prevSample = input[input.length - 1];
                hasPrev = true;
                return input;
            }
            // Stop at input.length - 1 so the fractional tail carries into [-1, 0) on the next chunk,
            // enabling seamless linear interpolation between prevSample and input[0] with zero boundary clicks.
            double limit = input.length - 1.0;
            int estimated = (int) Math.ceil((limit - phase) / step) + 2;
            if (estimated <= 0) estimated = 1;
            short[] out = new short[estimated];
            int count = 0;
            while (phase < limit) {
                int idx = (int) Math.floor(phase);
                double frac = phase - idx;
                float s0;
                float s1;
                if (idx < 0) {
                    s0 = hasPrev ? prevSample : input[0];
                    s1 = input[0];
                } else {
                    s0 = input[idx];
                    s1 = input[idx + 1];
                }
                float val = (float) (s0 + (s1 - s0) * frac);
                if (val > 32767f) val = 32767f;
                else if (val < -32768f) val = -32768f;
                if (count == out.length) {
                    short[] grown = new short[out.length * 2];
                    System.arraycopy(out, 0, grown, 0, out.length);
                    out = grown;
                }
                out[count++] = (short) Math.round(val);
                phase += step;
            }
            phase -= input.length;
            prevSample = input[input.length - 1];
            hasPrev = true;
            if (count == out.length) return out;
            short[] exact = new short[count];
            System.arraycopy(out, 0, exact, 0, count);
            return exact;
        }
    }
}
