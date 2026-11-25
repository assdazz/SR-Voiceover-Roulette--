package com.srvr.mobile;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class TriggerPoints {
    static final String KEY = "trigger.points";
    static final String KEY_PASSTHROUGH = "trigger.passthrough";
    static final String KEY_SHOW_MARKERS = "trigger.showMarkers";

    static final float MIN_SIZE = 0.04f;
    static final float MAX_SIZE = 0.95f;
    static final float DEFAULT_WIDTH = 0.14f;
    static final float DEFAULT_HEIGHT = 0.22f;

    private final SharedPreferences preferences;

    record Point(int number, float x, float y, float width, float height,
            boolean locked, String soundId, String effects) {
        Point {
            x = clamp(x);
            y = clamp(y);
            width = clampSize(width, DEFAULT_WIDTH);
            height = clampSize(height, DEFAULT_HEIGHT);
        }

        SoundSettings settings() {
            return SoundSettings.read(effects);
        }

        Point at(float x, float y) {
            return new Point(number, x, y, width, height, locked, soundId, effects);
        }

        Point resize(float width, float height) {
            return new Point(number, x, y, width, height, locked, soundId, effects);
        }

        Point lock(boolean value) {
            return new Point(number, x, y, width, height, value, soundId, effects);
        }

        Point bind(String id, SoundSettings settings) {
            return new Point(number, x, y, width, height, locked, id, settings.encode());
        }
    }

    TriggerPoints(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    boolean passthroughEnabled() {
        return preferences.getBoolean(KEY_PASSTHROUGH, true);
    }

    void setPassthroughEnabled(boolean value) {
        preferences.edit().putBoolean(KEY_PASSTHROUGH, value).apply();
    }

    boolean showMarkers() {
        return preferences.getBoolean(KEY_SHOW_MARKERS, true);
    }

    void setShowMarkers(boolean value) {
        preferences.edit().putBoolean(KEY_SHOW_MARKERS, value).apply();
    }

    List<Point> all() {
        List<Point> points = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i);
                int number = entry == null ? -1 : entry.optInt("number", -1);
                if (number < 0) continue;
                float w = (float) entry.optDouble("w", DEFAULT_WIDTH);
                float h = (float) entry.optDouble("h", DEFAULT_HEIGHT);
                points.add(new Point(number, (float) entry.optDouble("x", 0.5),
                        (float) entry.optDouble("y", 0.5), w, h, entry.optBoolean("locked"),
                        entry.optString("sound"), entry.optString("effects")));
            }
        } catch (JSONException error) {
            android.util.Log.w("SRVR", "Could not read trigger points", error);
        }
        return points;
    }

    Point find(int number) {
        for (Point point : all()) if (point.number() == number) return point;
        return null;
    }

    Point create() {
        List<Point> points = all();
        int number = 0;
        for (Point point : points) number = Math.max(number, point.number() + 1);
        Point created = new Point(number, 0.5f, 0.5f, DEFAULT_WIDTH, DEFAULT_HEIGHT, false, "", "");
        points.add(created);
        preferences.edit().putString(KEY, encode(points)).apply();
        return created;
    }

    void update(int number, UnaryOperator<Point> change) {
        List<Point> points = all();
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.number() != number) continue;
            Point updated = change.apply(point);
            if (!updated.equals(point)) {
                points.set(i, updated);
                preferences.edit().putString(KEY, encode(points)).apply();
            }
            return;
        }
    }

    void remove(int number) {
        List<Point> points = all();
        if (points.removeIf(point -> point.number() == number)) {
            preferences.edit().putString(KEY, encode(points)).apply();
        }
    }

    boolean clearBindings(SharedPreferences.Editor edit, Predicate<String> shouldClear) {
        List<Point> points = all();
        boolean changed = false;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (!point.soundId().isEmpty() && shouldClear.test(point.soundId())) {
                points.set(i, new Point(point.number(), point.x(), point.y(),
                        point.width(), point.height(), point.locked(), "", ""));
                changed = true;
            }
        }
        if (changed) edit.putString(KEY, encode(points));
        return changed;
    }

    static int span(float fraction, int available, int minPx) {
        if (available <= 0) return Math.max(1, minPx);
        int px = Math.round(clampSize(fraction, DEFAULT_WIDTH) * available);
        return Math.max(Math.min(minPx, available), Math.min(px, available));
    }

    static int offset(float fraction, int span, int size) {
        return Math.round(clamp(fraction) * Math.max(0, span - size));
    }

    static float fraction(float offset, int span, int size, float fallback) {
        return span > size ? clamp(offset / (span - size)) : fallback;
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0.5f;
    }

    private static float clampSize(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(MIN_SIZE, Math.min(MAX_SIZE, value)) : fallback;
    }

    private static String encode(List<Point> points) {
        JSONArray data = new JSONArray();
        try {
            for (Point point : points) {
                data.put(new JSONObject().put("number", point.number()).put("x", point.x())
                        .put("y", point.y()).put("w", point.width()).put("h", point.height())
                        .put("locked", point.locked()).put("sound", point.soundId())
                        .put("effects", point.effects()));
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return data.toString();
    }
}
