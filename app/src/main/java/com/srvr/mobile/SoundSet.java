package com.srvr.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

record SoundSet(String id, String title, Mode mode, Amount amount, SoundSettings.Trigger trigger, boolean interrupt,
                List<Entry> entries) implements Playable {
    enum Mode {
        SEQUENTIAL("顺序播放"), SHUFFLE("随机播放");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    enum Amount {
        ALL("一次播放全部"), ONE("一次播放一条"), RANDOM_ONCE_PER_ROUND("一次随机不重复播放一条");

        final String label;

        Amount(String label) {
            this.label = label;
        }
    }

    record Entry(String id, String soundId, String effects) {
        static Entry create(SoundLibrary.Sound sound, SoundSettings settings) {
            return new Entry(UUID.randomUUID().toString(), sound.id(), settings.encode());
        }

        SoundSettings settings() {
            return SoundSettings.read(effects);
        }
    }

    SoundSet {
        entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    SoundSet withEntries(List<Entry> updated) {
        return new SoundSet(id, title, mode, amount, trigger, interrupt, updated);
    }

    Mode playbackMode() {
        return amount == Amount.RANDOM_ONCE_PER_ROUND ? Mode.SHUFFLE : mode;
    }

    JSONObject encode() throws JSONException {
        JSONArray items = new JSONArray();
        for (Entry entry : entries) {
            items.put(new JSONObject().put("id", entry.id()).put("sound", entry.soundId())
                    .put("effects", entry.effects()));
        }
        return new JSONObject().put("id", id).put("title", title).put("mode", mode.name())
                .put("amount", amount.name()).put("trigger", trigger.name()).put("interrupt", interrupt)
                .put("entries", items);
    }

    static SoundSet read(JSONObject data) throws JSONException {
        List<Entry> entries = new ArrayList<>();
        JSONArray items = data.optJSONArray("entries");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject entry = items.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                String sound = entry.optString("sound");
                if (!id.isEmpty() && !sound.isEmpty()) {
                    entries.add(new Entry(id, sound, entry.optString("effects")));
                }
            }
        }
        return new SoundSet(data.getString("id"), data.getString("title"),
                SoundSettings.readMode(data, "mode", Mode.SEQUENTIAL),
                SoundSettings.readMode(data, "amount", Amount.ONE),
                SoundSettings.readMode(data, "trigger", SoundSettings.Trigger.TAP),
                data.optBoolean("interrupt"), entries);
    }
}
