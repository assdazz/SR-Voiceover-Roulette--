package com.srvr.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

final class Ui {
    static final int BG = 0xff0d1312;
    static final int CARD = 0xff141f1c;
    static final int CONTROL = 0xff20312a;
    static final int LINE = 0xff30443b;
    static final int TEXT = 0xffeef7f0;
    static final int SECONDARY = 0xffadc0b5;
    static final int ACCENT = 0xff8de4a8;
    static final int DARK_GREEN = 0xff11271b;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (stroke != 0) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    static LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    static LinearLayout row(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    static LinearLayout screen(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            activity.getWindow().setStatusBarColor(BG);
        }
        LinearLayout page = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(activity, 560));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), 0);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        activity.setContentView(root);
        root.requestApplyInsets();
        return page;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        int padding = dp(context, 12);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(shape(context, CARD, 18, LINE));
        return card;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    static Button button(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? DARK_GREEN : TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x308de4a8),
                shape(context, primary ? ACCENT : CONTROL, 12, 0), null));
        return button;
    }

    static Button menuButton(Context context, String label) {
        Button button = button(context, label, false);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_dropdown, 0);
        button.setCompoundDrawablePadding(dp(context, 6));
        return button;
    }

    static ImageButton iconButton(Context context, int drawable, String label) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setColorFilter(TEXT);
        button.setContentDescription(label);
        int padding = dp(context, 12);
        button.setPadding(padding, padding, padding, padding);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x308de4a8),
                shape(context, CONTROL, 12, 0), null));
        return button;
    }

    static ImageButton floatingButton(Context context, FloatingSettings settings) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(R.drawable.ic_overlay);
        button.setColorFilter(ACCENT);
        styleFloatingButton(button, settings);
        return button;
    }

    static void styleFloatingButton(ImageButton button, FloatingSettings settings) {
        Context context = button.getContext();
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x458de4a8),
                shape(context, 0xff19392b, settings.buttonSize() / 2, ACCENT), null));
        int padding = dp(context, settings.buttonSize() / 4f);
        button.setPadding(padding, padding, padding, padding);
        button.setAlpha(1f - settings.transparency() / 100f);
    }

    static String seconds(int millis) {
        return (millis / 1000f) + " 秒";
    }

    static void slider(LinearLayout parent, String label, int current, int min, int max, int step,
            IntFunction<String> format, IntConsumer change) {
        slider(parent, label, current, min, max, step, format, false, change);
    }

    static void slider(LinearLayout parent, String label, int current, int min, int max, int step,
            IntFunction<String> format, boolean applyOnRelease, IntConsumer change) {
        Context context = parent.getContext();
        LinearLayout header = row(context);
        TextView name = text(context, label, 14, TEXT, false);
        header.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView amount = text(context, format.apply(current), 14, ACCENT, true);
        header.addView(amount);
        below(parent, header, 8);

        SeekBar bar = new SeekBar(context);
        bar.setContentDescription(label + "，" + amount.getText());
        bar.setMax((max - min) / step);
        bar.setProgress((current - min) / step);
        bar.setMinimumHeight(dp(context, 48));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = min + step * progress;
                amount.setText(format.apply(value));
                bar.setContentDescription(label + "，" + amount.getText());
                if (!applyOnRelease || !dragging) change.accept(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                if (applyOnRelease) change.accept(min + step * seekBar.getProgress());
            }
        });
        below(parent, bar, 0);
    }

    static void below(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(parent.getContext(), gap);
        parent.addView(view, params);
    }

    static void weighted(LinearLayout row, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, 1);
        params.setMarginStart(dp(row.getContext(), gap));
        row.addView(view, params);
    }
}
