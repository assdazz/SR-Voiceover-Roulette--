package com.srvr.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private SoundLibrary library;
    private ScrollView scroll;
    private LinearLayout body;
    private LinearLayout tabs;
    private WheelView wheel;
    private Button prevGroupButton;
    private Button nextGroupButton;
    private TextView groupTitleText;
    private TextView groupIndexText;
    private ImageButton microphone;
    private Button deleteSlot;
    private LinearLayout soundParameters;
    private int selectedPage = -1;
    private int selectedSlot = -1;
    private String selectedSoundId;
    private boolean libraryTab;

    private final VirtualMicController.Listener micStateChanged = this::updateMicrophoneButton;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (key == null) return;
        if ((key.equals(SoundLibrary.IMPORTS) || key.equals(SoundLibrary.SETS)) && libraryTab) renderTab(false);
        if (key.equals(SoundLibrary.BANK) || key.equals(SoundLibrary.GROUPS)
                || key.equals(SoundLibrary.IMPORTS)
                || key.equals(SoundLibrary.SETS)
                || key.startsWith(SoundLibrary.SLOT_PREFIX)) refreshWheel();
        if (key.equals(FloatingSettings.WHEEL_SIZE)
                || key.equals(FloatingSettings.WHEEL_HUB_RATIO)
                || key.equals(FloatingSettings.WHEEL_CONTROL_THICKNESS)
                || key.equals(FloatingSettings.POSITION_X)) configureWheel();
        if (key.equals(PlaybackSettings.SEQUENCE_MS) && soundParameters != null) {
            int last = soundParameters.getChildCount() - 1;
            if (soundParameters.getChildAt(last) instanceof SoundSettingsPanel panel) panel.refreshSequenceHint();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            libraryTab = state.getBoolean("libraryTab");
            selectedPage = state.getInt("selectedPage", -1);
            selectedSlot = state.getInt("selectedSlot", -1);
            selectedSoundId = state.getString("selectedSoundId");
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        library = SoundLibrary.get(this);
        buildScreen();
        renderTab(false);
        library.preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        VirtualMicController.get(this).addListener(micStateChanged);
        updateMicrophoneButton();
    }

    @Override protected void onResume() {
        super.onResume();
        updateMicrophoneButton();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("libraryTab", libraryTab);
        state.putInt("selectedPage", selectedPage);
        state.putInt("selectedSlot", selectedSlot);
        state.putString("selectedSoundId", selectedSoundId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        VirtualMicController.get(this).removeListener(micStateChanged);
        library.preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        super.onDestroy();
    }

    private void updateMicrophoneButton() {
        if (microphone == null) return;
        boolean active = VirtualMicController.get(this).isActive();
        microphone.setColorFilter(active ? Ui.DARK_GREEN : Ui.TEXT);
        microphone.setBackground(Ui.shape(this, active ? Ui.ACCENT : Ui.CONTROL, 12, 0));
    }

    private void buildScreen() {
        LinearLayout page = Ui.screen(this);
        LinearLayout titleRow = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_app_logo);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = Ui.text(this, "SR 音效轮盘", 26, Ui.TEXT, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        titleLayout.setMarginStart(dp(12));
        titleRow.addView(title, titleLayout);
        microphone = Ui.iconButton(this, R.drawable.ic_microphone, getString(R.string.virtumic_title));
        microphone.setOnClickListener(view -> startActivity(new Intent(this, VirtuMicActivity.class)));
        LinearLayout.LayoutParams microphoneLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        microphoneLayout.setMarginStart(dp(8));
        titleRow.addView(microphone, microphoneLayout);
        ImageButton settings = Ui.iconButton(this, R.drawable.ic_settings, "设置");
        settings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams settingsLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsLayout.setMarginStart(dp(8));
        titleRow.addView(settings, settingsLayout);
        Ui.below(page, titleRow, 0);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout content = Ui.column(this);
        content.setPadding(0, dp(16), 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        tabs = Ui.row(this);
        tabs.setBaselineAligned(false);
        Ui.below(content, tabs, 0);
        body = Ui.column(this);
        Ui.below(content, body, 14);
    }

    private void renderTab(boolean resetScroll) {
        int position = resetScroll ? 0 : scroll.getScrollY();
        tabs.removeAllViews();
        body.removeAllViews();
        Button wheelTab = Ui.button(this, "轮盘", !libraryTab);
        Button soundsTab = Ui.button(this, "音效库", libraryTab);
        wheelTab.setSelected(!libraryTab);
        soundsTab.setSelected(libraryTab);
        wheelTab.setOnClickListener(view -> {
            if (libraryTab) {
                libraryTab = false;
                renderTab(true);
            }
        });
        soundsTab.setOnClickListener(view -> {
            if (!libraryTab) {
                libraryTab = true;
                renderTab(true);
            }
        });
        addTab(wheelTab, 1, 0);
        addTab(soundsTab, 1, 8);
        Button triggers = Ui.button(this, "音效固定触发点\n（实验性）", false);
        triggers.setContentDescription("音效固定触发点（实验性）");
        triggers.setTextSize(13);
        triggers.setPadding(dp(8), dp(6), dp(8), dp(6));
        triggers.setOnClickListener(view -> startActivity(new Intent(this, TriggerPointActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        addTab(triggers, 1.5f, 8);
        wheel = null;
        prevGroupButton = null;
        nextGroupButton = null;
        groupTitleText = null;
        groupIndexText = null;
        deleteSlot = null;
        soundParameters = null;
        if (libraryTab) showLibrary();
        else showWheel();
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void addTab(Button tab, float weight, int gap) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, Ui.MATCH, weight);
        layout.setMarginStart(dp(gap));
        tabs.addView(tab, layout);
    }

    private void showWheel() {
        LinearLayout board = Ui.card(this);

        LinearLayout groupBar = Ui.row(this);
        groupBar.setBaselineAligned(false);
        int navSize = dp(42);
        prevGroupButton = compactNavButton("‹", "上一组", navSize);
        prevGroupButton.setOnClickListener(view -> library.previousPage());
        groupBar.addView(prevGroupButton, new LinearLayout.LayoutParams(navSize, navSize));

        LinearLayout groupPill = Ui.row(this);
        groupPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        groupPill.setBackground(new RippleDrawable(ColorStateList.valueOf(0x308de4a8),
                Ui.shape(this, Ui.BG, 12, Ui.LINE), null));
        groupPill.setClickable(true);
        groupPill.setFocusable(true);
        groupPill.setContentDescription("点击自定义当前轮盘组名称");
        groupPill.setOnClickListener(view -> showRenameGroupDialog());

        groupTitleText = Ui.text(this, "", 15, Ui.TEXT, true);
        groupTitleText.setMaxLines(1);
        groupTitleText.setEllipsize(TextUtils.TruncateAt.END);
        groupTitleText.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        groupPill.addView(groupTitleText, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));

        groupIndexText = Ui.text(this, "", 12, Ui.ACCENT, true);
        groupIndexText.setMaxLines(1);
        LinearLayout.LayoutParams indexLp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        indexLp.setMarginStart(dp(6));
        groupPill.addView(groupIndexText, indexLp);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, navSize, 1);
        pillLp.setMarginStart(dp(6));
        pillLp.setMarginEnd(dp(6));
        groupBar.addView(groupPill, pillLp);

        nextGroupButton = compactNavButton("›", "下一组", navSize);
        nextGroupButton.setOnClickListener(view -> library.nextPage());
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(navSize, navSize);
        nextLp.setMarginEnd(dp(6));
        groupBar.addView(nextGroupButton, nextLp);

        Button addGroup = Ui.button(this, "＋新组", true);
        addGroup.setTextSize(13);
        addGroup.setMinWidth(dp(56));
        addGroup.setMinimumWidth(dp(56));
        addGroup.setMinHeight(navSize);
        addGroup.setMinimumHeight(navSize);
        addGroup.setPadding(dp(10), 0, dp(10), 0);
        addGroup.setContentDescription("新增新的轮盘组");
        addGroup.setOnClickListener(view -> showAddGroupDialog());
        groupBar.addView(addGroup, new LinearLayout.LayoutParams(Ui.WRAP, navSize));
        Ui.below(board, groupBar, 0);

        wheel = new WheelView(this, this::selectSlot,
                slot -> assignSlot(library.page(), slot), () -> {});
        configureWheel();
        Ui.below(board, wheel, 8);
        FloatingSettings floating = library.floating;
        Ui.slider(board, "轮盘大小", floating.wheelSize(), FloatingSettings.MIN_WHEEL_SIZE,
                FloatingSettings.MAX_WHEEL_SIZE, FloatingSettings.WHEEL_SIZE_STEP,
                value -> value + "%", true, floating::saveWheelSize);
        Ui.slider(board, "中空比例", floating.wheelHubPercent(), FloatingSettings.MIN_WHEEL_HUB,
                FloatingSettings.MAX_WHEEL_HUB, FloatingSettings.WHEEL_HUB_STEP,
                value -> value + "%", false, floating::saveWheelHubPercent);
        Ui.slider(board, "第三圈厚度", floating.wheelControlPercent(), FloatingSettings.MIN_WHEEL_CONTROL,
                FloatingSettings.MAX_WHEEL_CONTROL, FloatingSettings.WHEEL_CONTROL_STEP,
                value -> value + "%", false, floating::saveWheelControlPercent);
        TextView help = Ui.text(this, "点击上方组名可改名或删组 · 选中扇区后可在下方调参", 13, Ui.SECONDARY, false);
        help.setGravity(Gravity.CENTER);
        Ui.below(board, help, 4);

        LinearLayout slotActions = Ui.row(this);
        Button edit = Ui.menuButton(this, "管理槽位");
        edit.setOnClickListener(view -> showSlotList());
        Ui.weighted(slotActions, edit, 0);
        deleteSlot = Ui.button(this, "删除选中音效配置", false);
        deleteSlot.setTextSize(13);
        deleteSlot.setOnClickListener(view -> deleteSelectedSlot());
        Ui.weighted(slotActions, deleteSlot, 8);
        Ui.below(board, slotActions, 12);

        Ui.below(body, board, 0);
        soundParameters = Ui.column(this);
        Ui.below(body, soundParameters, 12);
        refreshWheel();
    }

    private Button compactNavButton(String label, String description, int size) {
        Button button = Ui.button(this, label, false);
        button.setTextSize(22);
        button.setMinWidth(size);
        button.setMinimumWidth(size);
        button.setMinHeight(size);
        button.setMinimumHeight(size);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        return button;
    }

    private void refreshWheel() {
        if (wheel == null) return;
        int page = library.page();
        int total = library.pageCount();
        String name = library.pageName(page);
        if (groupTitleText != null) groupTitleText.setText(name);
        if (groupIndexText != null) groupIndexText.setText((page + 1) + "/" + total + " ✎");
        boolean canSwitch = total > 1;
        if (prevGroupButton != null) {
            prevGroupButton.setEnabled(canSwitch);
            prevGroupButton.setAlpha(canSwitch ? 1f : 0.42f);
        }
        if (nextGroupButton != null) {
            nextGroupButton.setEnabled(canSwitch);
            nextGroupButton.setAlpha(canSwitch ? 1f : 0.42f);
        }

        Playable[] sounds = library.pageSounds(page);
        wheel.setPage(page, name, sounds);
        int slot = selectedPage == page ? selectedSlot : -1;
        Playable sound = slot >= 0 && slot < sounds.length ? sounds[slot] : null;
        if (sound != null && !sound.id().equals(selectedSoundId)) sound = null;
        updateSelection(slot, sound);
    }

    private void configureWheel() {
        if (wheel == null) return;
        wheel.configure(library.floating.wheelSize(), library.floating.wheelHubPercent(),
                library.floating.wheelControlPercent(), library.floating.position().x() <= 0.5f);
    }

    private void selectSlot(int index) {
        updateSelection(index, library.pageSounds()[index]);
    }

    private void updateSelection(int index, Playable sound) {
        selectedPage = sound == null ? -1 : library.page();
        selectedSlot = sound == null ? -1 : index;
        wheel.setSelectedSlot(sound == null ? -1 : index);
        deleteSlot.setEnabled(sound != null);
        deleteSlot.setAlpha(sound == null ? 0.46f : 1f);
        showSoundParameters(sound);
    }

    private void deleteSelectedSlot() {
        int page = library.page();
        if (selectedPage != page) return;
        Playable[] sounds = library.pageSounds(page);
        if (selectedSlot >= 0 && selectedSlot < sounds.length && sounds[selectedSlot] != null
                && sounds[selectedSlot].id().equals(selectedSoundId)) {
            library.assign(page, selectedSlot, null);
        }
    }

    private EditText dialogInput(String initial, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(initial);
        input.setSelection(input.getText().length());
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(20)});
        return input;
    }

    private FrameLayout wrapDialogInput(EditText input) {
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));
        box.addView(input, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return box;
    }

    private void showAddGroupDialog() {
        int nextNum = library.pageCount() + 1;
        EditText input = dialogInput("", "自定义组名（留空默认「第" + nextNum + "组」）");
        new AlertDialog.Builder(this)
                .setTitle("新增轮盘组")
                .setView(wrapDialogInput(input))
                .setPositiveButton("创建", (dialog, which) -> {
                    int created = library.addGroup(input.getText().toString());
                    Toast.makeText(this, "已新增并切换到「" + library.pageName(created) + "」",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameGroupDialog() {
        int page = library.page();
        int total = library.pageCount();
        EditText input = dialogInput(library.customPageName(page),
                "默认：第" + (page + 1) + "组（留空恢复默认）");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("轮盘组名称（第 " + (page + 1) + "/" + total + " 组）")
                .setView(wrapDialogInput(input))
                .setPositiveButton("保存", (dialog, which) -> library.renameGroup(page, input.getText().toString()))
                .setNegativeButton("取消", null);
        if (total > 1) {
            builder.setNeutralButton("删除此组", (dialog, which) -> confirmRemoveGroup(page));
        }
        builder.show();
    }

    private void confirmRemoveGroup(int page) {
        String name = library.pageName(page);
        new AlertDialog.Builder(this)
                .setTitle("删除「" + name + "」？")
                .setMessage("该组内的槽位配置会一并移除，不会删除音效库中的音频文件。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (library.removeGroup(page)) {
                        Toast.makeText(this, "已删除「" + name + "」", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSoundParameters(Playable sound) {
        String id = sound == null ? null : sound.id();
        if (TextUtils.equals(id, selectedSoundId) && soundParameters.getChildCount() > 0) return;
        selectedSoundId = id;
        soundParameters.removeAllViews();
        if (sound instanceof SoundSet set) {
            Button edit = Ui.button(this, "编辑播放集", false);
            edit.setOnClickListener(view -> openSet(set));
            Ui.below(soundParameters, edit, 0);
        }
        Ui.below(soundParameters, new SoundSettingsPanel(this, library,
                sound instanceof SoundLibrary.Sound clip ? clip : null), 4);
    }

    private void showSlotList() {
        int page = library.page();
        Playable[] sounds = library.pageSounds(page);
        String[] choices = new String[sounds.length];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = SoundLibrary.slotLabel(i) + "　" + (sounds[i] == null ? "空槽位" : sounds[i].title());
        }
        new AlertDialog.Builder(this).setTitle("管理「" + library.pageName(page) + "」槽位")
                .setItems(choices, (dialog, which) -> assignSlot(page, which))
                .setNegativeButton("关闭", null).show();
    }

    private void assignSlot(int page, int slot) {
        List<Playable> sounds = library.playables();
        String[] choices = new String[sounds.size() + 1];
        choices[0] = "清空槽位";
        for (int i = 0; i < sounds.size(); i++) {
            Playable item = sounds.get(i);
            choices[i + 1] = item instanceof SoundLibrary.Sound clip ? clip.filename() : item.title() + " · 播放集";
        }
        new AlertDialog.Builder(this).setTitle("为" + SoundLibrary.slotLabel(slot) + "选择音效")
                .setItems(choices, (dialog, index) -> library.assign(page, slot, index == 0 ? null : sounds.get(index - 1)))
                .setNegativeButton("取消", null).show();
    }

    private void showLibrary() {
        List<SoundLibrary.Sound> sounds = library.sounds();
        LinearLayout header = Ui.row(this);
        TextView count = Ui.text(this, sounds.size() + " 个音效", 18, Ui.TEXT, true);
        if (Build.VERSION.SDK_INT >= 28) count.setAccessibilityHeading(true);
        header.addView(count, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button createSet = Ui.button(this, "创建播放集", false);
        createSet.setOnClickListener(view -> openSet(library.createSet()));
        header.addView(createSet);
        Button importButton = Ui.button(this, "导入音频", true);
        importButton.setOnClickListener(view -> SoundImport.open(this));
        LinearLayout.LayoutParams importLayout = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        importLayout.setMarginStart(dp(6));
        header.addView(importButton, importLayout);
        Ui.below(body, header, 0);

        for (SoundSet set : library.sets()) {
            LinearLayout row = Ui.row(this);
            Button edit = Ui.button(this, set.title() + " · " + set.playbackMode().label, false);
            edit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            edit.setOnClickListener(view -> openSet(set));
            Ui.weighted(row, edit, 0);
            Button preview = Ui.button(this, "试听", false);
            preview.setContentDescription("试听 " + set.title());
            preview.setOnClickListener(view -> SoundPlayer.play(this, set));
            LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
            previewLayout.setMarginStart(dp(4));
            row.addView(preview, previewLayout);
            Ui.below(body, row, 7);
        }

        for (SoundLibrary.Sound sound : sounds) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(12), dp(5), dp(5), dp(5));
            row.setBackground(Ui.shape(this, Ui.CARD, 12, Ui.LINE));
            LinearLayout labels = Ui.column(this);
            TextView name = Ui.text(this, sound.title(), 15, Ui.TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            Ui.below(labels, name, 0);
            Ui.below(labels, Ui.text(this, sound.format() + (sound.bundled() ? " · 内置" : " · 已导入"),
                    12, Ui.SECONDARY, false), 0);
            row.addView(labels, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            Button preview = Ui.button(this, "试听", false);
            preview.setContentDescription("试听 " + sound.title());
            preview.setOnClickListener(view -> SoundPlayer.play(this, sound));
            row.addView(preview);
            if (!sound.bundled()) {
                Button remove = Ui.button(this, "移除", false);
                remove.setContentDescription("移除 " + sound.title());
                remove.setOnClickListener(view -> confirmRemoval(sound));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
                params.leftMargin = dp(4);
                row.addView(remove, params);
            }
            Ui.below(body, row, 7);
        }
        Button stop = Ui.button(this, "停止播放", false);
        stop.setOnClickListener(view -> SoundPlayer.stop());
        Ui.below(body, stop, 12);
    }

    private void confirmRemoval(SoundLibrary.Sound sound) {
        new AlertDialog.Builder(this).setTitle("移除「" + sound.title() + "」？")
                .setMessage("轮盘和播放集里使用这个音效的位置也会清除。")
                .setPositiveButton("移除", (dialog, which) -> {
                    SoundPlayer.stopIfPlaying(sound);
                    library.remove(sound);
                })
                .setNegativeButton("取消", null).show();
    }

    private void openSet(SoundSet set) {
        startActivity(new Intent(this, SoundSetActivity.class).putExtra(SoundSetActivity.SET_ID, set.id()));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SoundImport.REQUEST && result == RESULT_OK && data != null) {
            SoundImport.copy(this, library, data, null);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }

}
