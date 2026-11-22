package com.srvr.mobile;

import java.util.concurrent.ThreadLocalRandom;

final class SoundSequence {
    private static final double PITCH_LIMIT = 12;
    private static final double SPEED_LIMIT = 60;

    record Variation(double semitones, float speed) {
        float pitchRatio() {
            return (float) Math.pow(2, semitones / 12.0);
        }
    }

    private long lastAt = -1;
    private int repeats;
    private Integer lastRandomPitch;

    Variation next(SoundSettings settings, long now, int windowMs) {
        if (lastAt >= 0 && now - lastAt <= windowMs) repeats++;
        else repeats = 0;
        lastAt = now;

        double pitch = Math.min(PITCH_LIMIT, repeats * (double) settings.pitchStep);
        double semitones = switch (settings.pitch) {
            case NONE -> 0;
            case UP -> pitch;
            case DOWN -> -pitch;
            case RANDOM -> randomPitch(settings.pitchStep);
            case FIXED -> settings.fixedPitch;
        };
        double speedChange = Math.min(SPEED_LIMIT, repeats * settings.speedStep) / 100;
        float speed = switch (settings.speed) {
            case NONE -> 1f;
            case UP -> (float) (1 + speedChange);
            case DOWN -> (float) (1 - speedChange);
        };
        return new Variation(semitones, speed);
    }

    private int randomPitch(int range) {
        if (range == 0) return 0;
        boolean omitLast = lastRandomPitch != null && Math.abs(lastRandomPitch) <= range;
        int value = ThreadLocalRandom.current().nextInt(-range, range + (omitLast ? 0 : 1));
        if (omitLast && value >= lastRandomPitch) value++;
        lastRandomPitch = value;
        return value;
    }
}
