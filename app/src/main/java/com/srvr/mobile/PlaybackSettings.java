package com.srvr.mobile;

import android.content.SharedPreferences;

final class PlaybackSettings {
    static final int MAX_MASTER_VOLUME = 100;
    static final int MIN_SEQUENCE_MS = 100;
    static final int MAX_SEQUENCE_MS = 5000;
    static final String SEQUENCE_MS = "playback.sequenceMs";
    private static final String MASTER_VOLUME = "playback.masterVolume";
    private final SharedPreferences preferences;

    PlaybackSettings(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    int masterVolume() {
        return Math.max(0, Math.min(MAX_MASTER_VOLUME,
                preferences.getInt(MASTER_VOLUME, MAX_MASTER_VOLUME)));
    }

    void saveMasterVolume(int percent) {
        preferences.edit().putInt(MASTER_VOLUME, percent).apply();
    }

    int sequenceMs() {
        return Math.max(MIN_SEQUENCE_MS, Math.min(MAX_SEQUENCE_MS, preferences.getInt(SEQUENCE_MS, 2500)));
    }

    void saveSequenceMs(int millis) {
        preferences.edit().putInt(SEQUENCE_MS, millis).apply();
    }
}
