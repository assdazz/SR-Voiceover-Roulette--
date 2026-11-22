package com.srvr.mobile;

import org.json.JSONException;
import org.json.JSONObject;

final class SoundSettings {
    enum Pitch { NONE, UP, DOWN, RANDOM, FIXED }
    enum Speed { NONE, UP, DOWN }
    enum Trigger { TAP, HOLD, RELEASE }

    static final int MAX_VOLUME = 200;
    static final int MAX_PITCH_STEP = 12;
    static final int MAX_FIXED_PITCH = 24;
    static final int MAX_SPEED_STEP = 40;

    int volume = 100;
    Pitch pitch = Pitch.NONE;
    int pitchStep = 1;
    double fixedPitch;
    Speed speed = Speed.NONE;
    double speedStep = 10;
    Trigger trigger = Trigger.TAP;
    boolean interrupt;

    static SoundSettings read(String encoded) {
        SoundSettings settings = new SoundSettings();
        if (encoded == null) return settings;
        try {
            JSONObject data = new JSONObject(encoded);
            settings.volume = (int) bound(data.optInt("volume", 100), 0, MAX_VOLUME, 100);
            settings.pitch = readMode(data, "pitch", Pitch.NONE);
            settings.pitchStep = (int) bound(data.optInt("pitchStep", 1), 0, MAX_PITCH_STEP, 1);
            settings.fixedPitch = bound(data.optDouble("fixedPitch", 0), -MAX_FIXED_PITCH, MAX_FIXED_PITCH, 0);
            settings.speed = readMode(data, "speed", Speed.NONE);
            settings.speedStep = bound(data.optDouble("speedStep", 10), 0, MAX_SPEED_STEP, 10);
            settings.trigger = readMode(data, "trigger", Trigger.TAP);
            settings.interrupt = data.optBoolean("interrupt");
        } catch (JSONException e) {
            return new SoundSettings();
        }
        return settings;
    }

    String encode() {
        try {
            return new JSONObject()
                    .put("volume", volume)
                    .put("pitch", pitch.name())
                    .put("pitchStep", pitchStep)
                    .put("fixedPitch", fixedPitch)
                    .put("speed", speed.name())
                    .put("speedStep", speedStep)
                    .put("trigger", trigger.name())
                    .put("interrupt", interrupt)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static <T extends Enum<T>> T readMode(JSONObject data, String key, T fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), data.optString(key, fallback.name()));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double bound(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
