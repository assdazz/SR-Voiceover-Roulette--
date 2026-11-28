package com.srvr.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class SoundLibrary {
    static final int SLOTS_PER_PAGE = WheelGeometry.SLOT_COUNT;
    private static final int LEGACY_SLOTS_PER_PAGE = 7;
    private static final int DEFAULT_PAGES = 2;
    static final String BANK = "bank";
    static final String GROUPS = "wheel.groups";
    static final String IMPORTS = "imports";
    static final String SETS = "soundSets";
    static final String SLOT_PREFIX = "wheel.slot.";
    private static final String MIGRATED_SLOTS = "wheel.slotsMigrated";
    private static final String EXTRA_PAGE = "wheel.extraPage";
    private static final String ASSET_DIRECTORY = "sounds";
    private static final String ASSET_PREFIX = ASSET_DIRECTORY + "/";
    // Preserve the original default wheel order and IDs already saved by older installs.
    private static final String[] ORIGINAL_BUNDLED_ORDER = {
        "78物业.mp3", "冰冰冰.wav", "呦吼吼.wav", "哇哦.wav", "哎呦我去.wav", "嗯？.wav",
        "嘶吼（魔了）.wav", "巴哈哈哈哈哈.wav", "怒.wav", "爱音唐哭.wav", "男人叹气声.wav", "真的很帅.wav", "饿啊.wav"
    };
    private static final String SETTINGS_PREFIX = "settings.";
    private static final String LAST_PLAYED = "lastPlayedSound";
    private static final String LAST_SET_ENTRY = "lastPlayedSetEntry";
    private static final String PLAYED_IN_ROUND = "set.round.played.";
    private static final String LAST_IN_ROUND = "set.round.last.";

    record Sound(String id, String filename, boolean bundled) implements Playable {
        @Override public String title() {
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }

        String format() {
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(dot + 1).toUpperCase(Locale.ROOT) : "音频";
        }

        String assetPath() {
            return ASSET_PREFIX + filename;
        }
    }

    record WheelGroup(String id, String name, String[] slots) {}

    private static SoundLibrary instance;

    final SharedPreferences preferences;
    final FloatingSettings floating;
    final PlaybackSettings playback;
    final TriggerPoints triggerPoints;
    private final Context context;
    private final File importsDir;
    private final List<Sound> bundled = new ArrayList<>();
    private final List<Sound> imported = new ArrayList<>();
    private final List<SoundSet> sets = new ArrayList<>();
    private final List<WheelGroup> groups = new ArrayList<>();

    static SoundLibrary get(Context context) {
        if (instance == null) {
            instance = new SoundLibrary(context.getApplicationContext());
        }
        return instance;
    }

    private SoundLibrary(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("soundboard", Context.MODE_PRIVATE);
        floating = new FloatingSettings(preferences);
        playback = new PlaybackSettings(preferences);
        triggerPoints = new TriggerPoints(preferences);
        importsDir = new File(context.getFilesDir(), "sounds");
        try {
            bundled.addAll(bundledSounds(context.getAssets().list(ASSET_DIRECTORY)));
        } catch (IOException e) {
            android.util.Log.w("SRVR", "Could not read bundled sounds", e);
        }
        try {
            JSONArray savedSounds = new JSONArray(preferences.getString(IMPORTS, "[]"));
            for (int i = 0; i < savedSounds.length(); i++) {
                JSONObject entry = savedSounds.optJSONObject(i);
                if (entry == null) continue;
                Sound sound = new Sound(entry.optString("id"), entry.optString("name"), false);
                if (sound.id().matches("[0-9a-f-]{36}") && file(sound).isFile()) {
                    imported.add(sound);
                }
            }
        } catch (JSONException e) {
            android.util.Log.w("SRVR", "Could not read imported sounds", e);
        }
        try {
            JSONArray stored = new JSONArray(preferences.getString(SETS, "[]"));
            for (int i = 0; i < stored.length(); i++) sets.add(SoundSet.read(stored.getJSONObject(i)));
        } catch (JSONException e) {
            android.util.Log.w("SRVR", "Could not read sound sets", e);
        }
        SharedPreferences.Editor cleanup = preferences.edit();
        if (triggerPoints.clearBindings(cleanup, id -> playableById(id) == null)) cleanup.apply();
        migrateSlots();
        loadOrMigrateGroups();
    }

    static List<Sound> bundledSounds(String[] filenames) {
        List<Sound> sounds = new ArrayList<>();
        if (filenames == null) return sounds;
        Set<String> available = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (String filename : filenames) {
            int dot = filename.lastIndexOf('.');
            if (dot <= 0) continue;
            switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
                case "wav", "mp3", "m4a", "aac", "flac", "ogg", "opus" -> available.add(filename);
            }
        }
        for (int index = 0; index < ORIGINAL_BUNDLED_ORDER.length; index++) {
            String filename = ORIGINAL_BUNDLED_ORDER[index];
            if (available.remove(filename)) sounds.add(new Sound(originalBundledId(index), filename, true));
        }
        for (String filename : available) sounds.add(new Sound("asset:" + filename, filename, true));
        return sounds;
    }

    private static String originalBundledId(int index) {
        String filename = ORIGINAL_BUNDLED_ORDER[index];
        return String.format(Locale.ROOT, "%s%02d%s", ASSET_PREFIX, index + 1,
                filename.substring(filename.lastIndexOf('.')));
    }

    List<Sound> sounds() {
        List<Sound> result = new ArrayList<>(bundled);
        result.addAll(imported);
        return result;
    }

    Sound soundById(String id) {
        for (Sound sound : bundled) if (sound.id().equals(id)) return sound;
        for (Sound sound : imported) if (sound.id().equals(id)) return sound;
        return null;
    }

    List<SoundSet> sets() {
        return new ArrayList<>(sets);
    }

    SoundSet setById(String id) {
        for (SoundSet set : sets) if (set.id().equals(id)) return set;
        return null;
    }

    SoundSet createSet() {
        int number = sets.size() + 1;
        String title = "播放集 " + number;
        List<String> names = new ArrayList<>();
        for (SoundSet set : sets) names.add(set.title());
        while (names.contains(title)) title = "播放集 " + ++number;
        SoundSet created = new SoundSet("set:" + UUID.randomUUID(), title, SoundSet.Mode.SEQUENTIAL,
                SoundSet.Amount.ONE, SoundSettings.Trigger.TAP, false, Collections.emptyList());
        sets.add(created);
        preferences.edit().putString(SETS, encodeSets()).apply();
        return created;
    }

    void saveSet(SoundSet set) {
        SoundSet previous = setById(set.id());
        int index = sets.indexOf(previous);
        if (index < 0) return;
        sets.set(index, set);
        SharedPreferences.Editor edit = preferences.edit().putString(SETS, encodeSets());
        if (previous.amount() != set.amount()) clearRandomRound(edit, set.id());
        edit.apply();
    }

    void addToSet(String id, List<Sound> sounds) {
        SoundSet set = setById(id);
        if (set == null || sounds.isEmpty()) return;
        saveSet(withAddedSounds(set, sounds));
    }

    private SoundSet withAddedSounds(SoundSet set, List<Sound> sounds) {
        List<SoundSet.Entry> entries = new ArrayList<>(set.entries());
        for (Sound sound : sounds) {
            if (soundById(sound.id()) != null) entries.add(SoundSet.Entry.create(sound, new SoundSettings()));
        }
        return set.withEntries(entries);
    }

    List<Playable> playables() {
        List<Playable> result = new ArrayList<>(sounds());
        result.addAll(sets);
        return result;
    }

    Playable playableById(String id) {
        return id.startsWith("set:") ? setById(id) : soundById(id);
    }

    static String slotLabel(int index) {
        return index < WheelGeometry.INNER_SLOTS ? "内圈 " + (index + 1)
                : "中圈 " + (index - WheelGeometry.INNER_SLOTS + 1);
    }

    Playable lastPlayed() {
        return playableById(preferences.getString(LAST_PLAYED, ""));
    }

    String lastSetEntry(SoundSet set) {
        return set.id().equals(preferences.getString(LAST_PLAYED, ""))
                ? preferences.getString(LAST_SET_ENTRY, "") : "";
    }

    SoundSet.Entry drawUnplayedEntry(SoundSet set, List<SoundSet.Entry> playable) {
        String playedKey = PLAYED_IN_ROUND + set.id();
        String lastKey = LAST_IN_ROUND + set.id();
        Set<String> previous = preferences.getStringSet(playedKey, Collections.emptySet());
        Set<String> played = new HashSet<>();
        List<SoundSet.Entry> eligible = new ArrayList<>();
        for (SoundSet.Entry entry : playable) {
            if (previous.contains(entry.id())) played.add(entry.id());
            else eligible.add(entry);
        }
        if (eligible.isEmpty()) {
            played.clear();
            eligible.addAll(playable);
        }
        if (played.isEmpty() && eligible.size() > 1) {
            String last = preferences.getString(lastKey, lastSetEntry(set));
            eligible.removeIf(entry -> entry.id().equals(last));
        }
        SoundSet.Entry chosen = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        played.add(chosen.id());
        preferences.edit().putStringSet(playedKey, played).putString(lastKey, chosen.id()).apply();
        return chosen;
    }

    private void clearRandomRound(SharedPreferences.Editor edit, String setId) {
        edit.remove(PLAYED_IN_ROUND + setId).remove(LAST_IN_ROUND + setId);
    }

    void rememberPlayback(Playable sound) {
        rememberPlayback(sound, "");
    }

    void rememberPlayback(Playable sound, String entryId) {
        if (sound.id().equals(preferences.getString(LAST_PLAYED, ""))
                && entryId.equals(preferences.getString(LAST_SET_ENTRY, ""))) return;
        preferences.edit().putString(LAST_PLAYED, sound.id()).putString(LAST_SET_ENTRY, entryId).apply();
    }

    int page() {
        return Math.floorMod(preferences.getInt(BANK, 0), pageCount());
    }

    int pageCount() {
        return Math.max(1, groups.size());
    }

    String pageName(int index) {
        int safe = Math.floorMod(index, pageCount());
        String custom = customPageName(safe);
        return custom.isEmpty() ? "第" + (safe + 1) + "组" : custom;
    }

    String customPageName(int index) {
        if (groups.isEmpty()) return "";
        int safe = Math.floorMod(index, groups.size());
        String raw = groups.get(safe).name();
        return raw == null ? "" : raw.trim();
    }

    void setPage(int index) {
        int target = Math.floorMod(index, pageCount());
        preferences.edit().putInt(BANK, target).apply();
    }

    void nextPage() {
        setPage(page() + 1);
    }

    void previousPage() {
        setPage(page() - 1);
    }

    int addGroup(String customName) {
        String clean = customName == null ? "" : customName.trim();
        String[] emptySlots = new String[SLOTS_PER_PAGE];
        java.util.Arrays.fill(emptySlots, "");
        groups.add(new WheelGroup("group:" + UUID.randomUUID(), clean, emptySlots));
        int newIndex = groups.size() - 1;
        preferences.edit()
                .putString(GROUPS, encodeGroups())
                .putInt(BANK, newIndex)
                .apply();
        return newIndex;
    }

    void renameGroup(int index, String customName) {
        if (groups.isEmpty()) return;
        int safe = Math.floorMod(index, groups.size());
        WheelGroup current = groups.get(safe);
        String clean = customName == null ? "" : customName.trim();
        if (clean.equals(current.name())) return;
        groups.set(safe, new WheelGroup(current.id(), clean, current.slots()));
        preferences.edit().putString(GROUPS, encodeGroups()).apply();
    }

    boolean removeGroup(int index) {
        if (groups.size() <= 1) return false;
        int safe = Math.floorMod(index, groups.size());
        groups.remove(safe);
        int currentPage = preferences.getInt(BANK, 0);
        int nextPage = currentPage >= groups.size() ? groups.size() - 1
                : (currentPage > safe ? currentPage - 1 : Math.max(0, currentPage));
        preferences.edit()
                .putString(GROUPS, encodeGroups())
                .putInt(BANK, nextPage)
                .apply();
        return true;
    }

    Playable[] pageSounds() {
        return pageSounds(page());
    }

    Playable[] pageSounds(int pageIndex) {
        Playable[] slots = new Playable[SLOTS_PER_PAGE];
        if (groups.isEmpty()) return slots;
        WheelGroup group = groups.get(Math.floorMod(pageIndex, groups.size()));
        String[] ids = group.slots();
        for (int i = 0; i < slots.length; i++) {
            String id = i < ids.length && ids[i] != null ? ids[i] : "";
            slots[i] = playableById(id);
        }
        return slots;
    }

    private String legacySlotId(int slot) {
        return preferences.getString(SLOT_PREFIX + slot, defaultSlotId(slot));
    }

    private String defaultSlotId(int slot) {
        return slot < ORIGINAL_BUNDLED_ORDER.length ? originalBundledId(slot) : "";
    }

    private void migrateSlots() {
        if (preferences.getBoolean(MIGRATED_SLOTS, false)) return;
        final int oldSlotsPerPage = 8;
        final String oldSlotPrefix = "slot.";
        SharedPreferences.Editor edit = preferences.edit().putBoolean(MIGRATED_SLOTS, true);
        List<String> overflow = new ArrayList<>();
        boolean customized = false;
        for (int oldSlot = 0; oldSlot < DEFAULT_PAGES * oldSlotsPerPage; oldSlot++) {
            customized |= preferences.contains(oldSlotPrefix + oldSlot);
        }
        if (customized) {
            for (int page = 0; page < DEFAULT_PAGES; page++) {
                for (int slot = 0; slot < oldSlotsPerPage; slot++) {
                    int oldIndex = page * oldSlotsPerPage + slot;
                    String id = preferences.getString(oldSlotPrefix + oldIndex, defaultSlotId(oldIndex));
                    if (slot < LEGACY_SLOTS_PER_PAGE) {
                        edit.putString(SLOT_PREFIX + (page * LEGACY_SLOTS_PER_PAGE + slot), id);
                    } else if (soundById(id) != null) {
                        overflow.add(id);
                    }
                }
            }
        }
        for (String direction : new String[] {"UP", "DOWN", "LEFT", "RIGHT"}) {
            String id = preferences.getString("bubble.shortcut." + direction, "");
            if (soundById(id) != null) overflow.add(id);
        }
        if (!overflow.isEmpty()) {
            edit.putBoolean(EXTRA_PAGE, true);
            for (int slot = 0; slot < overflow.size(); slot++) {
                edit.putString(SLOT_PREFIX + (DEFAULT_PAGES * LEGACY_SLOTS_PER_PAGE + slot), overflow.get(slot));
            }
        }
        edit.apply();
    }

    private void loadOrMigrateGroups() {
        groups.clear();
        String saved = preferences.getString(GROUPS, null);
        if (saved != null) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    String id = obj.optString("id", "group-" + (i + 1));
                    String name = obj.optString("name", "");
                    String[] slots = new String[SLOTS_PER_PAGE];
                    java.util.Arrays.fill(slots, "");
                    JSONArray slotArr = obj.optJSONArray("slots");
                    if (slotArr != null) {
                        for (int s = 0; s < Math.min(SLOTS_PER_PAGE, slotArr.length()); s++) {
                            slots[s] = slotArr.optString(s, "");
                        }
                    }
                    groups.add(new WheelGroup(id, name, slots));
                }
            } catch (JSONException e) {
                android.util.Log.w("SRVR", "Could not read wheel groups", e);
            }
        }
        if (!groups.isEmpty()) return;

        // Migrate from legacy 7-slot pages (wheel.slot.0..N) to 8-slot WheelGroups without shifting slots.
        int legacyPages = preferences.getBoolean(EXTRA_PAGE, false) ? DEFAULT_PAGES + 1 : DEFAULT_PAGES;
        for (int p = 0; p < legacyPages; p++) {
            String[] slots = new String[SLOTS_PER_PAGE];
            java.util.Arrays.fill(slots, "");
            for (int s = 0; s < LEGACY_SLOTS_PER_PAGE; s++) {
                slots[s] = legacySlotId(p * LEGACY_SLOTS_PER_PAGE + s);
            }
            groups.add(new WheelGroup("group-" + (p + 1), "", slots));
        }
        preferences.edit().putString(GROUPS, encodeGroups()).apply();
    }

    void assign(int pageIndex, int slotInPage, Playable sound) {
        if (groups.isEmpty() || slotInPage < 0 || slotInPage >= SLOTS_PER_PAGE) return;
        int safe = Math.floorMod(pageIndex, groups.size());
        WheelGroup current = groups.get(safe);
        String[] nextSlots = current.slots().clone();
        nextSlots[slotInPage] = sound == null ? "" : sound.id();
        groups.set(safe, new WheelGroup(current.id(), current.name(), nextSlots));
        preferences.edit().putString(GROUPS, encodeGroups()).apply();
    }

    void assign(int slot, Playable sound) {
        if (slot < 0) return;
        assign(slot / SLOTS_PER_PAGE, slot % SLOTS_PER_PAGE, sound);
    }

    SoundSettings settings(Sound sound) {
        return SoundSettings.read(preferences.getString(SETTINGS_PREFIX + sound.id(), null));
    }

    SoundSettings.Trigger trigger(Playable item) {
        return item instanceof SoundSet set ? set.trigger() : settings((Sound) item).trigger;
    }

    void saveSettings(Sound sound, SoundSettings settings) {
        preferences.edit().putString(SETTINGS_PREFIX + sound.id(), settings.encode()).apply();
    }

    File file(Sound sound) {
        return new File(importsDir, sound.id());
    }

    // File I/O runs on the import executor. The UI thread registers completed copies.
    Sound copy(Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = "导入音效";
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        }
        if (!importsDir.isDirectory() && !importsDir.mkdirs()) {
            throw new IOException("Cannot create sound storage");
        }
        Sound sound = new Sound(UUID.randomUUID().toString(), name, false);
        File staging = File.createTempFile("import-", ".tmp", importsDir);
        try {
            try (InputStream input = resolver.openInputStream(uri);
                    FileOutputStream output = new FileOutputStream(staging)) {
                if (input == null) throw new IOException("Cannot open audio document");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (staging.length() == 0 || !staging.renameTo(file(sound))) {
                throw new IOException("Cannot save audio document");
            }
            return sound;
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    void register(List<Sound> copies, String setId) {
        imported.addAll(copies);
        SharedPreferences.Editor edit = preferences.edit().putString(IMPORTS, encodeImports());
        SoundSet set = setById(setId);
        if (set != null) {
            sets.set(sets.indexOf(set), withAddedSounds(set, copies));
            edit.putString(SETS, encodeSets());
        }
        edit.apply();
    }

    void remove(Sound sound) {
        if (sound.bundled() || !imported.remove(sound)) return;
        SharedPreferences.Editor edit = preferences.edit().putString(IMPORTS, encodeImports())
                .remove(SETTINGS_PREFIX + sound.id());
        for (int i = 0; i < sets.size(); i++) {
            SoundSet set = sets.get(i);
            List<SoundSet.Entry> entries = new ArrayList<>(set.entries());
            if (entries.removeIf(entry -> entry.soundId().equals(sound.id()))) sets.set(i, set.withEntries(entries));
        }
        edit.putString(SETS, encodeSets());
        clearReferences(edit, sound.id());
        if (edit.commit()) file(sound).delete();
    }

    void remove(SoundSet set) {
        if (!sets.remove(set)) return;
        SharedPreferences.Editor edit = preferences.edit().putString(SETS, encodeSets());
        clearRandomRound(edit, set.id());
        clearReferences(edit, set.id());
        edit.apply();
    }

    private void clearReferences(SharedPreferences.Editor edit, String id) {
        triggerPoints.clearBindings(edit, id::equals);
        if (id.equals(preferences.getString(LAST_PLAYED, ""))) edit.remove(LAST_PLAYED).remove(LAST_SET_ENTRY);
        boolean changed = false;
        for (int g = 0; g < groups.size(); g++) {
            WheelGroup group = groups.get(g);
            String[] slots = group.slots();
            String[] updated = null;
            for (int s = 0; s < slots.length; s++) {
                if (id.equals(slots[s])) {
                    if (updated == null) updated = slots.clone();
                    updated[s] = "";
                }
            }
            if (updated != null) {
                groups.set(g, new WheelGroup(group.id(), group.name(), updated));
                changed = true;
            }
        }
        if (changed) edit.putString(GROUPS, encodeGroups());
    }

    private String encodeGroups() {
        JSONArray data = new JSONArray();
        try {
            for (WheelGroup group : groups) {
                JSONObject obj = new JSONObject();
                obj.put("id", group.id());
                obj.put("name", group.name());
                JSONArray slotArr = new JSONArray();
                for (String s : group.slots()) slotArr.put(s == null ? "" : s);
                obj.put("slots", slotArr);
                data.put(obj);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return data.toString();
    }

    private String encodeSets() {
        JSONArray data = new JSONArray();
        try {
            for (SoundSet set : sets) data.put(set.encode());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return data.toString();
    }

    private String encodeImports() {
        JSONArray entries = new JSONArray();
        for (Sound sound : imported) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", sound.id());
                entry.put("name", sound.filename());
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            entries.put(entry);
        }
        return entries.toString();
    }
}
