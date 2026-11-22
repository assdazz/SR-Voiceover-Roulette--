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
    private final SharedPreferences preferences;

    record Point(int number, float x, float y, boolean locked, String soundId, String effects) {
        Point {
            x = clamp(x);
            y = clamp(y);
        }

        SoundSettings settings() {
            return SoundSettings.read(effects);
        }

        Point at(float x, float y) {
            return new Point(number, x, y, locked, soundId, effects);
        }

        Point lock(boolean value) {
            return new Point(number, x, y, value, soundId, effects);
        }

        Point bind(String id, SoundSettings settings) {
            return new Point(number, x, y, locked, id, settings.encode());
        }
    }

    TriggerPoints(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    List<Point> all() {
        List<Point> points = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i);
                int number = entry == null ? -1 : entry.optInt("number", -1);
                if (number < 0) continue;
                points.add(new Point(number, (float) entry.optDouble("x", 0.5),
                        (float) entry.optDouble("y", 0.5), entry.optBoolean("locked"),
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
        Point created = new Point(number, 0.5f, 0.5f, false, "", "");
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
                points.set(i, new Point(point.number(), point.x(), point.y(), point.locked(), "", ""));
                changed = true;
            }
        }
        if (changed) edit.putString(KEY, encode(points));
        return changed;
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

    private static String encode(List<Point> points) {
        JSONArray data = new JSONArray();
        try {
            for (Point point : points) {
                data.put(new JSONObject().put("number", point.number()).put("x", point.x())
                        .put("y", point.y()).put("locked", point.locked()).put("sound", point.soundId())
                        .put("effects", point.effects()));
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return data.toString();
    }
}
