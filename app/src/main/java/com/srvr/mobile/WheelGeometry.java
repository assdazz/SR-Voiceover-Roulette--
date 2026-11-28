package com.srvr.mobile;

final class WheelGeometry {
    static final int INNER_SLOTS = 4;
    static final int MIDDLE_SLOTS = 4;
    static final int SLOT_COUNT = INNER_SLOTS + MIDDLE_SLOTS;
    static final int PREV_PAGE = SLOT_COUNT;
    static final int STOP_PLAYBACK = PREV_PAGE + 1;
    static final int NEXT_PAGE = STOP_PLAYBACK + 1;
    static final int CENTER = NEXT_PAGE + 1;
    static final int NONE = -2;
    static final int RADIUS_DP = 166;
    static final int PADDING_DP = 4;
    static final float HUB_RATIO = 0.26f;
    static final float CONTROL_RATIO = 0.20f;
    static final float MIDDLE_RATIO = 1f - CONTROL_RATIO;
    static final float INNER_RATIO = HUB_RATIO + (MIDDLE_RATIO - HUB_RATIO) * 0.5f;
    private static final float MIN_SOUND_BAND = 0.13f;
    private static final float START = -90f;
    private static final float SECTOR_STEP = 180f / INNER_SLOTS;

    record Panel(int left, int top, int width, int height) {}

    private WheelGeometry() {}

    static float hubRatio(int hubPercent) {
        return Math.max(0.15f, Math.min(0.45f, hubPercent / 100f));
    }

    static float middleRatio(int hubPercent, int controlPercent) {
        float hub = hubRatio(hubPercent);
        float maxControl = Math.max(0.12f, 1f - hub - 2f * MIN_SOUND_BAND);
        float control = Math.max(0.12f, Math.min(maxControl, controlPercent / 100f));
        return 1f - control;
    }

    static float innerRatio(int hubPercent, int controlPercent) {
        float hub = hubRatio(hubPercent);
        float middle = middleRatio(hubPercent, controlPercent);
        return hub + (middle - hub) * 0.5f;
    }

    static int ringOf(int slot) {
        if (slot < INNER_SLOTS) return 0;
        if (slot < SLOT_COUNT) return 1;
        return 2;
    }

    static float sweep(int slot) {
        return slot == STOP_PLAYBACK ? SECTOR_STEP * 2f : SECTOR_STEP;
    }

    static float start(int slot) {
        if (slot < INNER_SLOTS) return START + SECTOR_STEP * slot;
        if (slot < SLOT_COUNT) return START + SECTOR_STEP * (slot - INNER_SLOTS);
        if (slot == PREV_PAGE) return START;
        if (slot == STOP_PLAYBACK) return START + SECTOR_STEP;
        return START + SECTOR_STEP * 3f;
    }

    static float labelRadius(int slot, float hubRatio, float innerRatio, float middleRatio) {
        return switch (ringOf(slot)) {
            case 0 -> (hubRatio + innerRatio) / 2f;
            case 1 -> (innerRatio + middleRatio) / 2f;
            default -> (middleRatio + 1f) / 2f;
        };
    }

    static int hit(float inwardX, float y, float radius, float hubRatio, float innerRatio, float middleRatio) {
        if (radius <= 0) return NONE;
        double distance = Math.hypot(inwardX, y);
        if (distance < radius * hubRatio) return CENTER;
        if (inwardX < 0 || distance > radius) return NONE;
        double angle = Math.toDegrees(Math.atan2(y, inwardX)) - START;
        if (angle < 0) angle = 0;
        if (distance <= radius * innerRatio) {
            return Math.min(INNER_SLOTS - 1, (int) (angle / SECTOR_STEP));
        }
        if (distance <= radius * middleRatio) {
            return INNER_SLOTS + Math.min(MIDDLE_SLOTS - 1, (int) (angle / SECTOR_STEP));
        }
        if (angle < SECTOR_STEP) return PREV_PAGE;
        if (angle < SECTOR_STEP * 3f) return STOP_PLAYBACK;
        return NEXT_PAGE;
    }

    static float fitRadius(int width, int height, int padding, float preferredRadius) {
        return fitRadius(width, height, padding, preferredRadius, HUB_RATIO);
    }

    static float fitRadius(int width, int height, int padding, float preferredRadius, float hubRatio) {
        return Math.max(0, Math.min(preferredRadius,
                Math.min((width - 2f * padding) / (1f + hubRatio), height / 2f - padding)));
    }

    static int width(float radius, int padding) {
        return width(radius, padding, HUB_RATIO);
    }

    static int width(float radius, int padding, float hubRatio) {
        return (int) Math.ceil(radius * (1f + hubRatio)) + 2 * padding;
    }

    static int height(float radius, int padding) {
        return (int) Math.ceil(2 * radius) + 2 * padding;
    }

    static Panel place(int left, int top, int right, int bottom, int anchorX, int anchorY,
            int padding, float preferredRadius, boolean opensRight) {
        return place(left, top, right, bottom, anchorX, anchorY, padding, preferredRadius, HUB_RATIO, opensRight);
    }

    static Panel place(int left, int top, int right, int bottom, int anchorX, int anchorY,
            int padding, float preferredRadius, float hubRatio, boolean opensRight) {
        float radius = fitRadius(right - left, bottom - top, padding, preferredRadius, hubRatio);
        if (radius <= 0) return null;
        int width = Math.min(right - left, width(radius, padding, hubRatio));
        int height = Math.min(bottom - top, height(radius, padding));
        float shift = radius * (1f - hubRatio) / 2f;
        float hubOffset = width / 2f + (opensRight ? -shift : shift);
        int x = Math.max(left, Math.min(right - width, Math.round(anchorX - hubOffset)));
        int y = Math.max(top, Math.min(bottom - height, anchorY - height / 2));
        return new Panel(x, y, width, height);
    }
}
