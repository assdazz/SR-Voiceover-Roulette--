package com.srvr.mobile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class SoundPlayer {
    private static final Map<MediaPlayer, Voice> voices = new HashMap<>();
    private static final Map<String, SoundSequence> sequences = new HashMap<>();
    private static final Set<SetPlayback> sets = new HashSet<>();
    private static final Map<String, String> lastPickedRows = new HashMap<>();
    private static boolean boostWarningShown;

    interface Playback {
        void stop();
    }

    private static final class Voice implements Playback {
        private final MediaPlayer player;
        private final SoundLibrary.Sound sound;
        private final String key;
        private final Runnable completion;
        private Runnable onRelease;
        private LoudnessEnhancer boost;
        private VirtualMicPcmEngine.ClipHandle micClip;
        private int volume;
        private boolean ready;
        private boolean boostUnavailable;

        private Voice(MediaPlayer player, SoundLibrary.Sound sound, String key, int volume, Runnable completion) {
            this.player = player;
            this.sound = sound;
            this.key = key;
            this.volume = volume;
            this.completion = completion;
        }

        @Override public void stop() {
            stopVoice(this);
        }
    }

    private SoundPlayer() {}

    static Playback play(Context context, Playable item) {
        Context app = context.getApplicationContext();
        SoundLibrary library = SoundLibrary.get(app);
        if (item instanceof SoundSet set) {
            return playSet(app, library, set);
        }
        SoundLibrary.Sound sound = (SoundLibrary.Sound) item;
        SoundSettings settings = library.settings(sound);
        if (settings.interrupt) stopMatching(sound.id());
        return playClip(app, sound, settings, sound.id(), () -> library.rememberPlayback(sound), null);
    }

    private static Playback playSet(Context app, SoundLibrary library, SoundSet set) {
        List<SoundSet.Entry> entries = new ArrayList<>(set.entries());
        entries.removeIf(entry -> library.soundById(entry.soundId()) == null);
        if (entries.isEmpty()) {
            Toast.makeText(app, "播放集还没有可播放的音效", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (set.interrupt()) stopSet(set.id());
        if (set.amount() != SoundSet.Amount.ALL) {
            SoundSet.Entry chosen = set.amount() == SoundSet.Amount.RANDOM_ONCE_PER_ROUND
                    ? library.drawUnplayedEntry(set, entries)
                    : selectEntry(set, entries, lastPickedRows.getOrDefault(set.id(), library.lastSetEntry(set)));
            lastPickedRows.put(set.id(), chosen.id());
            entries = Collections.singletonList(chosen);
        } else if (set.playbackMode() == SoundSet.Mode.SHUFFLE) {
            Collections.shuffle(entries);
        }
        SetPlayback playback = new SetPlayback(app, library, set, entries);
        sets.add(playback);
        playback.advance();
        return playback;
    }

    private static SoundSet.Entry selectEntry(SoundSet set, List<SoundSet.Entry> entries, String last) {
        int previous = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(last)) {
                previous = i;
                break;
            }
        }
        if (set.mode() == SoundSet.Mode.SEQUENTIAL) return entries.get((previous + 1) % entries.size());
        if (entries.size() == 1) return entries.get(0);
        int draw = ThreadLocalRandom.current().nextInt(entries.size() - (previous >= 0 ? 1 : 0));
        if (previous >= 0 && draw >= previous) draw++;
        return entries.get(draw);
    }

    static void previewEntry(Context context, SoundLibrary.Sound sound, SoundSet.Entry entry) {
        playClip(context.getApplicationContext(), sound, entry.settings(), entryKey(entry.id()), null, null);
    }

    static Playback playTest(Context context, SoundLibrary.Sound sound, Runnable started, Runnable released) {
        Voice voice = playClip(context.getApplicationContext(), sound, new SoundSettings(), "test:virtumic",
                started, null);
        if (voice != null) voice.onRelease = released;
        return voice;
    }

    static Playback playPoint(Context context, TriggerPoints.Point point, Playable item) {
        if (item instanceof SoundSet) return play(context, item);
        Context app = context.getApplicationContext();
        SoundLibrary library = SoundLibrary.get(app);
        SoundLibrary.Sound sound = (SoundLibrary.Sound) item;
        SoundSettings settings = point.settings();
        String key = pointKey(point.number());
        if (settings.interrupt) stopMatching(key);
        return playClip(app, sound, settings, key, () -> library.rememberPlayback(sound), null);
    }

    static void resetPointSequence(int number) {
        sequences.remove(pointKey(number));
    }

    static void discardPoint(int number) {
        stopMatching(pointKey(number));
        resetPointSequence(number);
    }

    static void adjustPointVolume(Context context, int number, int percent) {
        adjustVolume(context, pointKey(number), percent);
    }

    private static String pointKey(int number) {
        return "point:" + number;
    }

    private static Voice playClip(Context app, SoundLibrary.Sound sound, SoundSettings settings, String key,
            Runnable started, Runnable completed) {
        SoundLibrary library = SoundLibrary.get(app);
        MediaPlayer player;
        try {
            player = new MediaPlayer();
        } catch (RuntimeException e) {
            reportFailure(app, sound, e);
            return null;
        }
        Voice voice = new Voice(player, sound, key, settings.volume, completed);
        voices.put(player, voice);
        VirtualMicController.get(app).prepareForPlayback();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (sound.bundled()) {
                try (AssetFileDescriptor asset = app.getAssets().openFd(sound.assetPath())) {
                    player.setDataSource(asset);
                }
            } else {
                player.setDataSource(library.file(sound).getAbsolutePath());
            }
            SoundSequence.Variation variation = sequences.computeIfAbsent(key, id -> new SoundSequence())
                    .next(settings, SystemClock.elapsedRealtime(), library.playback.sequenceMs());
            player.setOnPreparedListener(readyPlayer -> {
                if (!voices.containsKey(readyPlayer)) return;
                try {
                    voice.ready = true;
                    VirtualMicController vmic = VirtualMicController.get(app);
                    if (vmic.isActive()) {
                        voice.micClip = vmic.playClip(sound, voice.volume, library.playback.masterVolume(), variation);
                    }
                    applyVolume(app, voice, library.playback.masterVolume());
                    if (variation.speed() == 1f && variation.semitones() == 0) {
                        readyPlayer.start();
                    } else {
                        // A nonzero speed starts the prepared player.
                        readyPlayer.setPlaybackParams(new PlaybackParams()
                                .setSpeed(variation.speed())
                                .setPitch(variation.pitchRatio())
                                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL));
                    }
                    if (started != null) started.run();
                } catch (RuntimeException e) {
                    reportFailure(app, sound, e);
                    finishVoice(voice);
                }
            });
            player.setOnCompletionListener(done -> finishVoice(voice));
            player.setOnErrorListener((failed, what, extra) -> {
                if (voices.containsKey(failed)) {
                    reportFailure(app, sound, new IOException("MediaPlayer error " + what + "/" + extra));
                    finishVoice(voice);
                }
                return true;
            });
            player.prepareAsync();
            return voice;
        } catch (IOException | RuntimeException e) {
            stopVoice(voice);
            reportFailure(app, sound, e);
            return null;
        }
    }

    static void stopIfPlaying(SoundLibrary.Sound sound) {
        for (SetPlayback set : new ArrayList<>(sets)) {
            if (set.entries.stream().anyMatch(entry -> entry.soundId().equals(sound.id()))) set.stop();
        }
        for (Voice voice : new ArrayList<>(voices.values())) {
            if (sound.id().equals(voice.sound.id())) voice.stop();
        }
        resetSequence(sound);
    }

    static void stopSet(String id) {
        for (SetPlayback set : new ArrayList<>(sets)) {
            if (set.set.id().equals(id)) set.stop();
        }
    }

    static void resetSequence(SoundLibrary.Sound sound) {
        sequences.remove(sound.id());
    }

    static void resetEntrySequence(String id) {
        sequences.remove(entryKey(id));
    }

    private static void stopMatching(String key) {
        for (Voice voice : new ArrayList<>(voices.values())) {
            if (key.equals(voice.key)) voice.stop();
        }
    }

    private static void finishVoice(Voice voice) {
        if (!voices.containsKey(voice.player)) return;
        stopVoice(voice);
        if (voice.completion != null) voice.completion.run();
    }

    private static void stopVoice(Voice voice) {
        if (voice == null || voices.remove(voice.player) == null) return;
        if (voice.micClip != null) {
            voice.micClip.stop();
            voice.micClip = null;
        }
        if (voice.boost != null) voice.boost.release();
        voice.player.release();
        if (voice.onRelease != null) voice.onRelease.run();
    }

    static void stop() {
        for (SetPlayback set : new ArrayList<>(sets)) set.stop();
        for (Voice voice : new ArrayList<>(voices.values())) stopVoice(voice);
    }

    static void adjustVolume(Context context, SoundLibrary.Sound sound, int percent) {
        adjustVolume(context, sound.id(), percent);
    }

    static void adjustEntryVolume(Context context, String id, int percent) {
        adjustVolume(context, entryKey(id), percent);
    }

    private static void adjustVolume(Context context, String key, int percent) {
        Context app = context.getApplicationContext();
        int masterVolume = SoundLibrary.get(app).playback.masterVolume();
        for (Voice voice : new ArrayList<>(voices.values())) {
            if (!key.equals(voice.key)) continue;
            voice.volume = percent;
            if (voice.ready) applyVolume(app, voice, masterVolume);
        }
    }

    private static String entryKey(String id) {
        return "entry:" + id;
    }

    private static final class SetPlayback implements Playback {
        private final Context app;
        private final SoundLibrary library;
        private final SoundSet set;
        private final List<SoundSet.Entry> entries;
        private int next;
        private boolean started;
        private Voice voice;

        SetPlayback(Context app, SoundLibrary library, SoundSet set, List<SoundSet.Entry> entries) {
            this.app = app;
            this.library = library;
            this.set = set;
            this.entries = entries;
        }

        void advance() {
            voice = null;
            while (next < entries.size()) {
                SoundSet.Entry entry = entries.get(next++);
                SoundLibrary.Sound sound = library.soundById(entry.soundId());
                if (sound == null) continue;
                voice = playClip(app, sound, entry.settings(), entryKey(entry.id()),
                        () -> markStarted(entry), this::advance);
                if (voice != null) return;
            }
            sets.remove(this);
        }

        private void markStarted(SoundSet.Entry entry) {
            if (started) return;
            started = true;
            library.rememberPlayback(set, set.amount() == SoundSet.Amount.ALL ? "" : entry.id());
        }

        @Override public void stop() {
            sets.remove(this);
            stopVoice(voice);
            voice = null;
        }
    }

    static void adjustMasterVolume(Context context, int percent) {
        Context app = context.getApplicationContext();
        for (Voice voice : new ArrayList<>(voices.values())) {
            if (voice.ready) applyVolume(app, voice, percent);
        }
    }

    static void refreshLocalMonitor(Context context) {
        Context app = context.getApplicationContext();
        int masterVolume = SoundLibrary.get(app).playback.masterVolume();
        for (Voice voice : new ArrayList<>(voices.values())) {
            if (voice.ready) applyVolume(app, voice, masterVolume);
        }
    }

    private static void applyVolume(Context context, Voice voice, int masterVolume) {
        float gain = (voice.volume / 100f) * (masterVolume / 100f);
        if (voice.micClip != null) {
            voice.micClip.setGain(gain);
        }
        VirtualMicController vmic = VirtualMicController.get(context);
        boolean muteLocal = vmic.isActive() && !vmic.localMonitor();
        if (muteLocal) {
            voice.player.setVolume(0f, 0f);
            if (voice.boost != null) {
                voice.boost.release();
                voice.boost = null;
            }
            return;
        }
        float base = Math.min(gain, 1f);
        voice.player.setVolume(base, base);
        if (gain <= 1) {
            if (voice.boost != null) {
                voice.boost.release();
                voice.boost = null;
            }
            return;
        }
        if (voice.boostUnavailable) return;
        try {
            if (voice.boost == null) voice.boost = new LoudnessEnhancer(voice.player.getAudioSessionId());
            voice.boost.setTargetGain((int) Math.round(2000 * Math.log10(gain)));
            if (voice.boost.setEnabled(true) != AudioEffect.SUCCESS) {
                throw new IllegalStateException("Failed to enable the loudness effect");
            }
        } catch (RuntimeException e) {
            if (voice.boost != null) {
                voice.boost.release();
                voice.boost = null;
            }
            voice.boostUnavailable = true;
            Log.w("SRVR", "Audio boost unavailable", e);
            if (!boostWarningShown) {
                boostWarningShown = true;
                Toast.makeText(context, "此设备无法应用音量增益，按 100% 播放", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void reportFailure(Context context, SoundLibrary.Sound sound, Exception error) {
        Log.w("SRVR", "Could not play " + sound.id(), error);
        Toast.makeText(context, "无法播放「" + sound.title() + "」", Toast.LENGTH_SHORT).show();
    }
}
