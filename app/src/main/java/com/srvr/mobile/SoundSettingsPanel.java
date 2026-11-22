package com.srvr.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

final class SoundSettingsPanel extends LinearLayout {
    private static final String[] PITCH_LABELS = {
        "不改变音调", "连续播放时升调", "连续播放时降调", "每次随机变调", "固定变调"
    };
    private static final String[] SPEED_LABELS = {"不改变速度", "连续播放时变快", "连续播放时变慢"};
    static final String[] TRIGGER_LABELS = {"点击触发", "长按触发", "松手触发"};
    private static final String[] TRIGGER_HINTS = {
        "轻点播放一次，松手后继续播放。", "按住扇区播放，松手即停。", "按下时保持安静，松手时播放一次。"
    };

    private final SoundLibrary library;
    private final SoundSettings settings;
    private final Actions actions;
    private final Button pitchPicker;
    private final Button speedPicker;
    private final Button triggerPicker;
    private final Button pitchValue;
    private final TextView pitchLabel;
    private final TextView triggerHint;
    private final TextView sequenceHint;
    private final LinearLayout pitchAmount;
    private final LinearLayout speedAmount;

    record Actions(Consumer<SoundSettings> save, Runnable resetSequence, Runnable preview, IntConsumer volume) {}

    SoundSettingsPanel(Context context, SoundLibrary library, SoundLibrary.Sound sound) {
        this(context, library, sound == null ? "未选择音效" : sound.title(),
                sound == null ? new SoundSettings() : library.settings(sound),
                sound == null ? null : new Actions(settings -> library.saveSettings(sound, settings),
                        () -> SoundPlayer.resetSequence(sound), () -> SoundPlayer.play(context, sound),
                        volume -> SoundPlayer.adjustVolume(context, sound, volume)), true, "音效参数");
    }

    SoundSettingsPanel(Context context, SoundLibrary library, String title, SoundSettings settings,
            Actions actions, boolean showTrigger) {
        this(context, library, title, settings, actions, showTrigger, null);
    }

    private SoundSettingsPanel(Context context, SoundLibrary library, String title, SoundSettings settings,
            Actions actions, boolean showTrigger, String sectionTitle) {
        super(context);
        this.library = library;
        this.settings = settings;
        this.actions = actions;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(12), dp(12), dp(12));
        setBackground(Ui.shape(context, Ui.CARD, 18, Ui.LINE));

        if (sectionTitle != null) {
            TextView sectionHeading = Ui.text(context, sectionTitle, 13, Ui.SECONDARY, true);
            if (Build.VERSION.SDK_INT >= 28) sectionHeading.setAccessibilityHeading(true);
            Ui.below(this, sectionHeading, 0);
        }
        LinearLayout header = Ui.row(context);
        TextView heading = Ui.text(context, title, 18, Ui.TEXT, true);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LayoutParams(0, Ui.WRAP, 1));
        Button preview = Ui.button(context, "试听", false);
        preview.setContentDescription("试听 " + title);
        if (actions != null) preview.setOnClickListener(view -> actions.preview().run());
        header.addView(preview);
        Ui.below(this, header, sectionTitle == null ? 0 : 4);
        addVolume();

        pitchPicker = choiceButton("变调", PITCH_LABELS, () -> settings.pitch.ordinal(), index -> {
            settings.pitch = SoundSettings.Pitch.values()[index];
            saveVariation();
            refreshModes();
        });
        Ui.below(this, pitchPicker, 4);
        pitchAmount = Ui.row(context);
        pitchLabel = Ui.text(context, "", 14, Ui.SECONDARY, false);
        pitchAmount.addView(pitchLabel, new LayoutParams(0, Ui.WRAP, 1));
        pitchValue = Ui.button(context, "", false);
        pitchValue.setOnClickListener(view -> editPitch());
        pitchAmount.addView(pitchValue);
        Ui.below(this, pitchAmount, 4);

        speedPicker = choiceButton("播放速度", SPEED_LABELS, () -> settings.speed.ordinal(), index -> {
            settings.speed = SoundSettings.Speed.values()[index];
            saveVariation();
            refreshModes();
        });
        Ui.below(this, speedPicker, 8);
        speedAmount = speedInput();
        Ui.below(this, speedAmount, 4);
        sequenceHint = Ui.text(context, "", 12, Ui.SECONDARY, false);
        Ui.below(this, sequenceHint, 4);
        refreshSequenceHint();

        triggerPicker = choiceButton("触发", TRIGGER_LABELS, () -> settings.trigger.ordinal(), index -> {
            settings.trigger = SoundSettings.Trigger.values()[index];
            save();
            refreshModes();
        });
        triggerHint = Ui.text(context, "", 12, Ui.SECONDARY, false);
        if (showTrigger) {
            Ui.below(this, triggerPicker, 8);
            Ui.below(this, triggerHint, 4);
            Switch interrupt = new Switch(context);
            interrupt.setText("打断（断续播放时打断上一次的播放）");
            interrupt.setTextColor(Ui.TEXT);
            interrupt.setTextSize(14);
            interrupt.setMinHeight(dp(48));
            interrupt.setChecked(settings.interrupt);
            interrupt.setOnCheckedChangeListener((view, checked) -> {
                settings.interrupt = checked;
                save();
            });
            Ui.below(this, interrupt, 4);
        }
        refreshModes();
        if (actions == null) {
            setAlpha(0.46f);
            disable(this);
        }
    }

    private static void disable(View view) {
        view.setEnabled(false);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) disable(group.getChildAt(i));
        }
    }

    void refreshSequenceHint() {
        sequenceHint.setText(Ui.seconds(library.playback.sequenceMs()) + "内再次播放算连续播放");
    }

    private void addVolume() {
        Context context = getContext();
        LinearLayout row = Ui.row(context);
        TextView label = Ui.text(context, "音量", 14, Ui.SECONDARY, false);
        row.addView(label, new LayoutParams(Ui.WRAP, Ui.WRAP));

        SeekBar slider = new SeekBar(context);
        slider.setMax(SoundSettings.MAX_VOLUME);
        slider.setProgress(settings.volume);
        slider.setContentDescription("音效音量，百分比");
        LayoutParams sliderLayout = new LayoutParams(0, dp(48), 1);
        sliderLayout.leftMargin = dp(8);
        sliderLayout.rightMargin = dp(8);
        row.addView(slider, sliderLayout);

        Button value = Ui.button(context, "", false);
        showVolume(value);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setVolume(value, progress);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        value.setOnClickListener(view -> editNumber("音量（%）", settings.volume, 0,
                SoundSettings.MAX_VOLUME, false, percent -> {
                    int progress = (int) percent;
                    slider.setProgress(progress);
                    setVolume(value, progress);
                }));
        row.addView(value);
        Ui.below(this, row, 4);
    }

    private void setVolume(Button value, int percent) {
        settings.volume = percent;
        showVolume(value);
        save();
        if (actions != null) actions.volume().accept(percent);
    }

    private void showVolume(Button value) {
        value.setText(settings.volume + "%");
        value.setContentDescription("修改音量，当前 " + settings.volume + "%");
    }

    private Button choiceButton(String label, String[] choices, IntSupplier selected,
            IntConsumer choose) {
        Button button = Ui.menuButton(getContext(), "");
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(view -> new AlertDialog.Builder(getContext()).setTitle(label)
                .setSingleChoiceItems(choices, selected.getAsInt(), (dialog, index) -> {
                    dialog.dismiss();
                    choose.accept(index);
                }).setNegativeButton("取消", null).show());
        return button;
    }

    private LinearLayout speedInput() {
        Context context = getContext();
        LinearLayout row = Ui.row(context);
        TextView label = Ui.text(context, "每次变化", 14, Ui.SECONDARY, false);
        row.addView(label, new LayoutParams(0, Ui.WRAP, 1));
        EditText input = numberInput(settings.speedStep, true, false);
        input.setContentDescription("每次速度变化百分比");
        input.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(input, new LayoutParams(dp(92), Ui.WRAP));
        row.addView(Ui.text(context, "%", 14, Ui.SECONDARY, false));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable value) {
                double percent = parse(value);
                if (inRange(percent, 0, SoundSettings.MAX_SPEED_STEP)) {
                    input.setError(null);
                    if (settings.speedStep != percent) {
                        settings.speedStep = percent;
                        saveVariation();
                    }
                } else if (value.length() > 0) {
                    input.setError("请输入 0–" + SoundSettings.MAX_SPEED_STEP + " 之间的百分比");
                }
            }
        });
        input.setOnFocusChangeListener((view, focused) -> {
            if (!focused && !inRange(parse(input.getText()), 0, SoundSettings.MAX_SPEED_STEP)) {
                input.setText(number(settings.speedStep));
            }
        });
        return row;
    }

    private void refreshModes() {
        pitchPicker.setText("变调 · " + PITCH_LABELS[settings.pitch.ordinal()]);
        pitchAmount.setVisibility(settings.pitch == SoundSettings.Pitch.NONE ? GONE : VISIBLE);
        pitchLabel.setText(switch (settings.pitch) {
            case FIXED -> "固定半音";
            case RANDOM -> "随机范围";
            default -> "每次变化";
        });
        pitchValue.setText(settings.pitch == SoundSettings.Pitch.FIXED
                ? signedNumber(settings.fixedPitch) + " 半音"
                : "±" + settings.pitchStep + " 半音");
        pitchValue.setContentDescription(pitchLabel.getText() + "，" + pitchValue.getText());

        speedPicker.setText("速度 · " + SPEED_LABELS[settings.speed.ordinal()]);
        speedAmount.setVisibility(settings.speed == SoundSettings.Speed.NONE ? GONE : VISIBLE);
        boolean hasSequence = settings.pitch == SoundSettings.Pitch.UP || settings.pitch == SoundSettings.Pitch.DOWN
                || settings.speed != SoundSettings.Speed.NONE;
        sequenceHint.setVisibility(hasSequence ? VISIBLE : GONE);
        triggerPicker.setText("触发 · " + TRIGGER_LABELS[settings.trigger.ordinal()]);
        triggerHint.setText(TRIGGER_HINTS[settings.trigger.ordinal()]);
    }

    private void editPitch() {
        if (settings.pitch == SoundSettings.Pitch.FIXED) {
            editNumber("固定变调（半音）", settings.fixedPitch, -SoundSettings.MAX_FIXED_PITCH,
                    SoundSettings.MAX_FIXED_PITCH, true, amount -> {
                        settings.fixedPitch = amount;
                        saveVariation();
                        refreshModes();
                    });
        } else {
            editNumber(settings.pitch == SoundSettings.Pitch.RANDOM ? "随机范围（± 半音）" : "每次变化（半音）",
                    settings.pitchStep, 0, SoundSettings.MAX_PITCH_STEP, false, amount -> {
                        settings.pitchStep = (int) amount;
                        saveVariation();
                        refreshModes();
                    });
        }
    }

    private void editNumber(String title, double value, int min, int max, boolean decimals, DoubleConsumer accept) {
        LinearLayout body = Ui.column(getContext());
        body.setPadding(dp(24), dp(8), dp(24), 0);
        EditText input = numberInput(value, decimals, min < 0);
        input.setContentDescription(title);
        Ui.below(body, input, 0);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(title)
                .setView(body).setPositiveButton("确定", null).setNegativeButton("取消", null).create();
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            double entered = parse(input.getText());
            if (!inRange(entered, min, max) || (!decimals && entered != Math.rint(entered))) {
                input.setError("请输入 " + min + " 到 " + max + " 之间的数值");
                return;
            }
            accept.accept(entered);
            dialog.dismiss();
        });
    }

    private EditText numberInput(double value, boolean decimals, boolean signed) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        int flags = InputType.TYPE_CLASS_NUMBER;
        if (decimals) flags |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) flags |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        input.setInputType(flags);
        input.setSelectAllOnFocus(true);
        input.setText(number(value));
        return input;
    }

    private void saveVariation() {
        save();
        if (actions != null) actions.resetSequence().run();
    }

    private void save() {
        if (actions != null) actions.save().accept(settings);
    }

    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String signedNumber(double value) {
        return (value > 0 ? "+" : "") + number(value);
    }

    private static double parse(CharSequence value) {
        try {
            return Double.parseDouble(value.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean inRange(double value, int min, int max) {
        return value >= min && value <= max;
    }

    private int dp(int value) {
        return Ui.dp(getContext(), value);
    }
}
