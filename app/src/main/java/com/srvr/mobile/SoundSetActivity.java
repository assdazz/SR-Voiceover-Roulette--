package com.srvr.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ObjIntConsumer;
import java.util.function.UnaryOperator;

public final class SoundSetActivity extends Activity {
    static final String SET_ID = "soundSetId";
    private SoundLibrary library;
    private String id;
    private TextView title;
    private Button mode;
    private Button amount;
    private Button trigger;
    private Button interrupt;
    private Button preview;
    private Button removeSelected;
    private LinearLayout table;
    private String selectedEntryId;
    private boolean observing;

    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if (SoundLibrary.SETS.equals(key) || SoundLibrary.IMPORTS.equals(key)) refresh();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = SoundLibrary.get(this);
        id = getIntent().getStringExtra(SET_ID);
        if (state != null) selectedEntryId = state.getString("selectedEntryId");
        if (current() == null) {
            finish();
            return;
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildScreen();
        refresh();
        library.preferences.registerOnSharedPreferenceChangeListener(changed);
        observing = true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("selectedEntryId", selectedEntryId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (observing) library.preferences.unregisterOnSharedPreferenceChangeListener(changed);
        super.onDestroy();
    }

    private void buildScreen() {
        LinearLayout page = Ui.screen(this);
        LinearLayout toolbar = Ui.row(this);
        title = Ui.text(this, "", 23, Ui.TEXT, true);
        title.setMinHeight(dp(48));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setOnClickListener(view -> rename());
        toolbar.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button importButton = Ui.button(this, "导入音频", true);
        importButton.setOnClickListener(view -> SoundImport.open(this));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        action.setMarginStart(dp(8));
        toolbar.addView(importButton, action);
        Ui.below(page, toolbar, 0);

        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout body = Ui.column(this);
        body.setPadding(0, dp(12), 0, dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout playback = Ui.card(this);
        Ui.below(playback, Ui.text(this, "播放方式", 13, Ui.SECONDARY, true), 0);
        LinearLayout choices = Ui.row(this);
        choices.setBaselineAligned(false);
        mode = Ui.menuButton(this, "");
        mode.setOnClickListener(view -> chooseMode());
        choices.addView(mode, new LinearLayout.LayoutParams(0, Ui.MATCH, 1));
        amount = Ui.menuButton(this, "");
        amount.setSingleLine(false);
        amount.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
        amount.setOnClickListener(view -> chooseAmount());
        LinearLayout.LayoutParams amountLayout = new LinearLayout.LayoutParams(0, Ui.MATCH, 1.25f);
        amountLayout.setMarginStart(dp(6));
        choices.addView(amount, amountLayout);
        Ui.below(playback, choices, 5);
        Ui.below(body, playback, 0);
        LinearLayout triggering = Ui.card(this);
        Ui.below(triggering, Ui.text(this, "触发方式", 13, Ui.SECONDARY, true), 0);
        LinearLayout triggerChoices = Ui.row(this);
        trigger = Ui.menuButton(this, "");
        trigger.setOnClickListener(view -> chooseTrigger());
        Ui.weighted(triggerChoices, trigger, 0);
        interrupt = Ui.menuButton(this, "");
        interrupt.setOnClickListener(view -> chooseInterrupt());
        Ui.weighted(triggerChoices, interrupt, 6);
        Ui.below(triggering, triggerChoices, 5);
        Ui.below(body, triggering, 8);

        LinearLayout actions = Ui.row(this);
        Button add = Ui.menuButton(this, "从音效库添加");
        add.setOnClickListener(view -> addFromLibrary());
        Ui.weighted(actions, add, 0);
        preview = Ui.button(this, "试听播放集", false);
        preview.setOnClickListener(view -> {
            SoundSet set = current();
            if (set != null) SoundPlayer.play(this, set);
        });
        Ui.weighted(actions, preview, 8);
        Button stop = Ui.button(this, "停止", false);
        stop.setOnClickListener(view -> SoundPlayer.stopSet(id));
        LinearLayout.LayoutParams stopLayout = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        stopLayout.setMarginStart(dp(6));
        actions.addView(stop, stopLayout);
        Ui.below(body, actions, 8);

        LinearLayout tableCard = Ui.card(this);
        removeSelected = Ui.button(this, "删除选中音效配置", false);
        removeSelected.setOnClickListener(view -> removeSelection());
        Ui.below(tableCard, removeSelected, 0);
        table = Ui.column(this);
        Ui.below(tableCard, table, 8);
        Ui.below(body, tableCard, 10);
        Button remove = Ui.button(this, "删除播放集", false);
        remove.setOnClickListener(view -> confirmRemove());
        Ui.below(body, remove, 12);
    }

    private void refresh() {
        SoundSet set = current();
        if (set == null) {
            finish();
            return;
        }
        title.setText(set.title());
        title.setContentDescription(set.title() + "，点击重命名");
        boolean randomRound = set.amount() == SoundSet.Amount.RANDOM_ONCE_PER_ROUND;
        String modeLabel = set.playbackMode().label;
        mode.setText(modeLabel);
        mode.setContentDescription("播放方式，" + modeLabel + (randomRound ? "，由播放数量决定" : ""));
        mode.setEnabled(!randomRound);
        mode.setAlpha(randomRound ? 0.55f : 1f);
        amount.setText(set.amount().label);
        amount.setContentDescription("每次播放数量，" + set.amount().label);
        trigger.setText(SoundSettingsPanel.TRIGGER_LABELS[set.trigger().ordinal()]);
        interrupt.setText(set.interrupt() ? "打断音效" : "不打断音效");
        preview.setEnabled(!set.entries().isEmpty());
        if (entryIndex(set, selectedEntryId) < 0) selectedEntryId = null;
        removeSelected.setEnabled(selectedEntryId != null);
        removeSelected.setAlpha(selectedEntryId == null ? 0.46f : 1f);
        table.removeAllViews();
        Ui.below(table, row(true, false, "音效名", "音量", "变调", "速度"), 0);
        if (set.entries().isEmpty()) {
            Ui.below(table, Ui.text(this, "导入音频或从音效库添加", 13, Ui.SECONDARY, false), 6);
            return;
        }
        for (int i = 0; i < set.entries().size(); i++) {
            SoundSet.Entry entry = set.entries().get(i);
            SoundLibrary.Sound sound = library.soundById(entry.soundId());
            String name = sound == null ? "音效缺失" : sound.title();
            SoundSettings effects = entry.settings();
            String pitchSummary = pitch(effects);
            String speedSummary = speed(effects);
            LinearLayout item = row(false, entry.id().equals(selectedEntryId), (i + 1) + ". " + name,
                    effects.volume + "%", pitchSummary, speedSummary);
            item.setContentDescription(name + "，音量 " + effects.volume + "%，变调 "
                    + pitchSummary.replace('\n', ' ') + "，速度 " + speedSummary);
            item.setOnClickListener(view -> {
                selectedEntryId = entry.id();
                refresh();
                editEntry(entry.id());
            });
            Ui.below(table, item, 4);
        }
    }

    private LinearLayout row(boolean header, boolean selected, String name, String volume, String pitch, String speed) {
        LinearLayout row = Ui.row(this);
        row.setBaselineAligned(false);
        row.setMinimumHeight(dp(48));
        row.setSelected(selected);
        row.setBackground(Ui.shape(this, header || selected ? Ui.CONTROL : Ui.BG, 8,
                selected ? Ui.ACCENT : 0));
        addCell(row, name, header, 1.26f, 2);
        addCell(row, volume, header, 0.70f, 2);
        addCell(row, pitch, header, 1.08f, Integer.MAX_VALUE);
        addCell(row, speed, header, 1.12f, 2);
        return row;
    }

    private void addCell(LinearLayout row, String value, boolean header, float weight, int maxLines) {
        TextView cell = Ui.text(this, value, 13, header ? Ui.ACCENT : Ui.TEXT, header);
        cell.setPadding(dp(4), dp(6), dp(4), dp(6));
        cell.setGravity(Gravity.CENTER_VERTICAL);
        if (maxLines != Integer.MAX_VALUE) {
            cell.setMaxLines(maxLines);
            cell.setEllipsize(TextUtils.TruncateAt.END);
        }
        row.addView(cell, new LinearLayout.LayoutParams(0, Ui.WRAP, weight));
    }

    private static String pitch(SoundSettings effects) {
        return switch (effects.pitch) {
            case NONE -> "不变";
            case UP -> "连播升调\n+" + effects.pitchStep + " 半音";
            case DOWN -> "连续降调\n−" + effects.pitchStep + " 半音";
            case RANDOM -> "随机变调\n±" + effects.pitchStep + " 半音";
            case FIXED -> "固定变调\n" + SoundSettingsPanel.signedNumber(effects.fixedPitch) + " 半音";
        };
    }

    private static String speed(SoundSettings effects) {
        String step = SoundSettingsPanel.number(effects.speedStep) + "%";
        return switch (effects.speed) {
            case NONE -> "不变";
            case UP -> "连播变快" + step;
            case DOWN -> "连播变慢" + step;
        };
    }

    private void chooseMode() {
        SoundSet set = current();
        if (set == null) return;
        SoundSet.Mode[] modes = SoundSet.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        new AlertDialog.Builder(this).setTitle("播放方式")
                .setSingleChoiceItems(labels, set.mode().ordinal(), (dialog, which) -> {
                    update(value -> new SoundSet(id, value.title(), modes[which], value.amount(),
                            value.trigger(), value.interrupt(), value.entries()));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseAmount() {
        SoundSet set = current();
        if (set == null) return;
        SoundSet.Amount[] options = SoundSet.Amount.values();
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) labels[i] = options[i].label;
        new AlertDialog.Builder(this).setTitle("每次播放数量")
                .setSingleChoiceItems(labels, set.amount().ordinal(), (dialog, which) -> {
                    update(value -> new SoundSet(id, value.title(), value.mode(), options[which],
                            value.trigger(), value.interrupt(), value.entries()));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseTrigger() {
        SoundSet set = current();
        if (set == null) return;
        new AlertDialog.Builder(this).setTitle("播放集触发方式")
                .setSingleChoiceItems(SoundSettingsPanel.TRIGGER_LABELS, set.trigger().ordinal(), (dialog, which) -> {
                    update(value -> new SoundSet(id, value.title(), value.mode(), value.amount(),
                            SoundSettings.Trigger.values()[which], value.interrupt(), value.entries()));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseInterrupt() {
        SoundSet set = current();
        if (set == null) return;
        String[] options = {"打断音效", "不打断音效"};
        new AlertDialog.Builder(this).setTitle("同一播放集再次触发")
                .setSingleChoiceItems(options, set.interrupt() ? 0 : 1, (dialog, which) -> {
                    update(value -> new SoundSet(id, value.title(), value.mode(), value.amount(), value.trigger(),
                            which == 0, value.entries()));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void addFromLibrary() {
        List<SoundLibrary.Sound> sounds = library.sounds();
        String[] labels = new String[sounds.size()];
        boolean[] chosen = new boolean[sounds.size()];
        for (int i = 0; i < sounds.size(); i++) {
            SoundLibrary.Sound sound = sounds.get(i);
            labels[i] = sound.title() + " · " + sound.format();
        }
        new AlertDialog.Builder(this).setTitle("从音效库添加")
                .setMultiChoiceItems(labels, chosen, (dialog, index, checked) -> chosen[index] = checked)
                .setPositiveButton("添加", (dialog, which) -> {
                    List<SoundLibrary.Sound> added = new ArrayList<>();
                    for (int i = 0; i < chosen.length; i++) if (chosen[i]) added.add(sounds.get(i));
                    library.addToSet(id, added);
                }).setNegativeButton("取消", null).show();
    }

    private void editEntry(String entryId) {
        SoundSet set = current();
        if (set == null) return;
        int index = entryIndex(set, entryId);
        if (index < 0) return;
        SoundSet.Entry entry = set.entries().get(index);
        SoundLibrary.Sound sound = library.soundById(entry.soundId());
        LinearLayout contents = Ui.column(this);
        contents.setPadding(dp(12), 0, dp(12), dp(12));
        if (sound != null) {
            SoundSettings effects = entry.settings();
            SoundSettingsPanel.Actions actions = new SoundSettingsPanel.Actions(
                    value -> editEntries(entryId, (entries, position) -> entries.set(position,
                            new SoundSet.Entry(entryId, entry.soundId(), value.encode()))),
                    () -> SoundPlayer.resetEntrySequence(entryId),
                    () -> SoundPlayer.previewEntry(this, sound,
                            new SoundSet.Entry(entryId, entry.soundId(), effects.encode())),
                    volume -> SoundPlayer.adjustEntryVolume(this, entryId, volume));
            Ui.below(contents, new SoundSettingsPanel(this, library, sound.title(), effects, actions, false), 0);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contents);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("播放集音效参数")
                .setView(scroll).setPositiveButton("完成", null).create();
        LinearLayout controls = Ui.row(this);
        Button up = Ui.button(this, "上移", false);
        up.setEnabled(index > 0);
        up.setOnClickListener(view -> move(entryId, -1, dialog));
        Ui.weighted(controls, up, 0);
        Button down = Ui.button(this, "下移", false);
        down.setEnabled(index < set.entries().size() - 1);
        down.setOnClickListener(view -> move(entryId, 1, dialog));
        Ui.weighted(controls, down, 6);
        Button remove = Ui.button(this, "删除选中音效配置", false);
        remove.setOnClickListener(view -> {
            removeSelection();
            dialog.dismiss();
        });
        Ui.below(contents, controls, 8);
        Ui.below(contents, remove, 6);
        dialog.show();
    }

    private void removeSelection() {
        String entryId = selectedEntryId;
        if (entryId == null) return;
        selectedEntryId = null;
        editEntries(entryId, (entries, position) -> entries.remove(position));
        SoundPlayer.resetEntrySequence(entryId);
    }

    private void move(String entryId, int offset, AlertDialog dialog) {
        editEntries(entryId, (entries, position) -> {
            int next = position + offset;
            if (next >= 0 && next < entries.size()) Collections.swap(entries, position, next);
        });
        dialog.dismiss();
    }

    private void editEntries(String entryId, ObjIntConsumer<List<SoundSet.Entry>> edit) {
        update(set -> {
            int position = entryIndex(set, entryId);
            if (position < 0) return set;
            List<SoundSet.Entry> entries = new ArrayList<>(set.entries());
            edit.accept(entries, position);
            return set.withEntries(entries);
        });
    }

    private static int entryIndex(SoundSet set, String id) {
        for (int i = 0; i < set.entries().size(); i++) if (set.entries().get(i).id().equals(id)) return i;
        return -1;
    }

    private void rename() {
        SoundSet set = current();
        if (set == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
        input.setText(set.title());
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("播放集名称")
                .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("请输入名称");
                return;
            }
            update(value -> new SoundSet(id, name, value.mode(), value.amount(), value.trigger(),
                    value.interrupt(), value.entries()));
            dialog.dismiss();
        });
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this).setTitle("删除播放集？")
                .setMessage("这会清除该播放集的轮盘绑定。音效文件仍在音效库。")
                .setPositiveButton("删除", (dialog, which) -> {
                    SoundSet set = current();
                    if (set == null) return;
                    SoundPlayer.stopSet(id);
                    library.remove(set);
                    finish();
                }).setNegativeButton("取消", null).show();
    }

    private SoundSet current() {
        return library.setById(id);
    }

    private void update(UnaryOperator<SoundSet> change) {
        SoundSet set = current();
        if (set != null) library.saveSet(change.apply(set));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SoundImport.REQUEST && result == RESULT_OK && data != null) {
            SoundImport.copy(this, library, data, id);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
