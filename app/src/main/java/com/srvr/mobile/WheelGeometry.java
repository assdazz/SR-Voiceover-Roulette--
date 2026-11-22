package com.srvr.mobile;

final class WheelGeometry {
    static final int INNER_SLOTS = 4;
    static final int OUTER_SLOTS = 3;
    static final int SLOT_COUNT = INNER_SLOTS + OUTER_SLOTS;
    static final int NEXT_PAGE = SLOT_COUNT;
    static final int CENTER = NEXT_PAGE + 1;
    static final int NONE = -2;
    static final int RADIUS_DP = 146;
    static final int PADDING_DP = 4;
    static final float HUB_RATIO = 0.32f;
    static final float INNER_RATIO = 0.76f;
    private static final float START = -90f;
    private static final float INNER_STEP = 180f / INNER_SLOTS;
    private static final float OUTER_STEP = 180f / (OUTER_SLOTS + 1);

    record Panel(int left, int top, int width, int height) {}

    private WheelGeometry() {}

    static float sweep(int slot) {
        return slot < INNER_SLOTS ? INNER_STEP : OUTER_STEP;
    }

    static float start(int slot) {
        return START + sweep(slot) * (slot < INNER_SLOTS ? slot : slot - INNER_SLOTS);
    }

    static float labelRadius(int slot) {
        return slot < INNER_SLOTS ? (HUB_RATIO + INNER_RATIO) / 2 : (INNER_RATIO + 1) / 2;
    }

    static int hit(float inwardX, float y, float radius) {
        if (radius <= 0) return NONE;
        double distance = Math.hypot(inwardX, y);
        if (distance < radius * HUB_RATIO) return CENTER;
        if (inwardX < 0 || distance > radius) return NONE;
        double angle = Math.toDegrees(Math.atan2(y, inwardX)) - START;
        if (distance <= radius * INNER_RATIO) {
            return Math.min(INNER_SLOTS - 1, (int) (angle / INNER_STEP));
        }
        return INNER_SLOTS + Math.min(OUTER_SLOTS, (int) (angle / OUTER_STEP));
    }

    static float fitRadius(int width, int height, int padding, float preferredRadius) {
        return Math.max(0, Math.min(preferredRadius,
                Math.min((width - 2f * padding) / (1 + HUB_RATIO), height / 2f - padding)));
    }

    static int width(float radius, int padding) {
        return (int) Math.ceil(radius * (1 + HUB_RATIO)) + 2 * padding;
    }

    static int height(float radius, int padding) {
        return (int) Math.ceil(2 * radius) + 2 * padding;
    }

    static Panel place(int left, int top, int right, int bottom, int anchorX, int anchorY,
            int padding, float preferredRadius, boolean opensRight) {
        float radius = fitRadius(right - left, bottom - top, padding, preferredRadius);
        if (radius <= 0) return null;
        int width = Math.min(right - left, width(radius, padding));
        int height = Math.min(bottom - top, height(radius, padding));
        float shift = radius * (1f - HUB_RATIO) / 2f;
        float hubOffset = width / 2f + (opensRight ? -shift : shift);
        int x = Math.max(left, Math.min(right - width, Math.round(anchorX - hubOffset)));
        int y = Math.max(top, Math.min(bottom - height, anchorY - height / 2));
        return new Panel(x, y, width, height);
    }
}
