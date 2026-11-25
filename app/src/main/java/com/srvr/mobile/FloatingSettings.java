package com.srvr.mobile;

import android.content.SharedPreferences;

final class FloatingSettings {
    enum TapAction {
        REPLAY("重播上次音效"), OPEN_WHEEL("打开轮盘"), NONE("无操作");

        final String label;

        TapAction(String label) {
            this.label = label;
        }
    }

    record Position(float x, float y) {
        Position {
            x = fraction(x, 1f);
            y = fraction(y, 0.48f);
        }

        private static float fraction(float value, float fallback) {
            return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
        }
    }

    static final int POSITION_MARGIN_DP = 6;
    static final int MIN_BUTTON_SIZE_DP = 48;
    static final int MAX_BUTTON_SIZE_DP = 80;
    static final int MAX_TRANSPARENCY = 70;
    static final int MIN_HOLD_MS = 150;
    static final int MAX_HOLD_MS = 500;
    static final int MIN_WHEEL_SIZE = 70;
    static final int MAX_WHEEL_SIZE = 140;
    static final int WHEEL_SIZE_STEP = 5;
    static final int MIN_WHEEL_HUB = 15;
    static final int MAX_WHEEL_HUB = 55;
    static final int WHEEL_HUB_STEP = 1;
    static final int DEFAULT_WHEEL_HUB = 30;
    static final int MIN_WHEEL_OUTER = 12;
    static final int MAX_WHEEL_OUTER = 45;
    static final int WHEEL_OUTER_STEP = 1;
    static final int DEFAULT_WHEEL_OUTER = 30;
    static final String WHEEL_SIZE = "wheel.size";
    static final String WHEEL_HUB_RATIO = "wheel.hubRatio";
    static final String WHEEL_OUTER_THICKNESS = "wheel.outerThickness";
    static final String POSITION_LOCKED = "bubble.locked";
    static final String POSITION_X = "bubble.x";
    private static final String POSITION_Y = "bubble.y";
    static final String BUTTON_SIZE = "bubble.size";
    static final String TRANSPARENCY = "bubble.transparency";
    static final String TAP_ACTION = "bubble.tapAction";
    private static final String HOLD_MS = "bubble.holdMs";
    private final SharedPreferences preferences;

    FloatingSettings(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    Position position() {
        return new Position(preferences.getFloat(POSITION_X, 1f), preferences.getFloat(POSITION_Y, 0.48f));
    }

    boolean locked() {
        return preferences.getBoolean(POSITION_LOCKED, false);
    }

    void savePosition(Position position) {
        preferences.edit().putFloat(POSITION_X, position.x()).putFloat(POSITION_Y, position.y()).apply();
    }

    void setLocked(boolean locked) {
        preferences.edit().putBoolean(POSITION_LOCKED, locked).apply();
    }

    int wheelSize() {
        return value(WHEEL_SIZE, 100, MIN_WHEEL_SIZE, MAX_WHEEL_SIZE);
    }

    void saveWheelSize(int percent) {
        preferences.edit().putInt(WHEEL_SIZE, percent).apply();
    }

    int wheelHubPercent() {
        return value(WHEEL_HUB_RATIO, DEFAULT_WHEEL_HUB, MIN_WHEEL_HUB, MAX_WHEEL_HUB);
    }

    void saveWheelHubPercent(int percent) {
        preferences.edit().putInt(WHEEL_HUB_RATIO, percent).apply();
    }

    int wheelOuterPercent() {
        return value(WHEEL_OUTER_THICKNESS, DEFAULT_WHEEL_OUTER, MIN_WHEEL_OUTER, MAX_WHEEL_OUTER);
    }

    void saveWheelOuterPercent(int percent) {
        preferences.edit().putInt(WHEEL_OUTER_THICKNESS, percent).apply();
    }

    int buttonSize() {
        return value(BUTTON_SIZE, 56, MIN_BUTTON_SIZE_DP, MAX_BUTTON_SIZE_DP);
    }

    void saveButtonSize(int dp) {
        preferences.edit().putInt(BUTTON_SIZE, dp).apply();
    }

    int transparency() {
        return value(TRANSPARENCY, 0, 0, MAX_TRANSPARENCY);
    }

    void saveTransparency(int percent) {
        preferences.edit().putInt(TRANSPARENCY, percent).apply();
    }

    int holdMs() {
        return value(HOLD_MS, 220, MIN_HOLD_MS, MAX_HOLD_MS);
    }

    void saveHoldMs(int millis) {
        preferences.edit().putInt(HOLD_MS, millis).apply();
    }

    TapAction tapAction() {
        return TapAction.values()[value(TAP_ACTION, TapAction.REPLAY.ordinal(), 0, TapAction.values().length - 1)];
    }

    void saveTapAction(TapAction action) {
        preferences.edit().putInt(TAP_ACTION, action.ordinal()).apply();
    }

    String gestureDescription() {
        TapAction action = tapAction();
        return action == TapAction.NONE ? "长按打开轮盘" : "点按" + action.label + "，长按打开轮盘";
    }

    private int value(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, preferences.getInt(key, fallback)));
    }
}
