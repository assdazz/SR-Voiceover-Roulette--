package com.srvr.mobile;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

final class FloatingPositionEditor extends FrameLayout {
    private final ImageButton preview;
    private final LinearLayout controls;
    private final Runnable cancel;
    private final int buttonSize;
    private final int margin;
    private FloatingSettings.Position position;
    private float downX;
    private float downY;
    private float originX;
    private float originY;

    FloatingPositionEditor(Context context, FloatingSettings settings, FloatingSettings.Position initial,
            Consumer<FloatingSettings.Position> confirm, Runnable cancel) {
        super(context);
        this.position = initial;
        this.cancel = cancel;
        buttonSize = Ui.dp(context, settings.buttonSize());
        margin = Ui.dp(context, FloatingSettings.POSITION_MARGIN_DP);
        setBackgroundColor(0x97101814);
        setClickable(true);
        setFocusableInTouchMode(true);

        controls = Ui.column(context);
        controls.setBackground(Ui.shape(context, 0xf023322a, 16, Ui.LINE));
        int padding = Ui.dp(context, 12);
        controls.setPadding(padding, padding, padding, padding);
        Ui.below(controls, Ui.text(context, "调整悬浮窗位置", 18, Ui.TEXT, true), 0);
        TextView instructions = Ui.text(context, "拖动悬浮按钮，再确认当前位置。", 14, Ui.SECONDARY, false);
        Ui.below(controls, instructions, 4);
        LinearLayout actions = Ui.row(context);
        Button close = Ui.button(context, "取消", false);
        close.setOnClickListener(view -> cancel.run());
        Button save = Ui.button(context, "保存位置", true);
        save.setOnClickListener(view -> confirm.accept(position));
        Ui.weighted(actions, close, 0);
        Ui.weighted(actions, save, 8);
        Ui.below(controls, actions, 8);
        LayoutParams footer = new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        footer.setMargins(padding, padding, padding, padding);
        addView(controls, footer);

        preview = Ui.floatingButton(context, settings);
        preview.setContentDescription("拖动悬浮按钮");
        preview.setOnTouchListener((view, event) -> drag(event));
        addView(preview, new LayoutParams(buttonSize, buttonSize, Gravity.TOP | Gravity.LEFT));
        requestFocus();
    }

    @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
        super.onSizeChanged(width, height, previousWidth, previousHeight);
        placePreview();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) cancel.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean drag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                originX = preview.getX();
                originY = preview.getY();
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                int travelX = travel(getWidth());
                int travelY = travel(getHeight());
                float x = travelX == 0 ? position.x() : (originX + event.getRawX() - downX - margin) / travelX;
                float y = travelY == 0 ? position.y() : (originY + event.getRawY() - downY - margin) / travelY;
                position = new FloatingSettings.Position(x, y);
                placePreview();
                break;
            default:
                break;
        }
        return true;
    }

    private void placePreview() {
        preview.setTranslationX(margin + Math.round(position.x() * travel(getWidth())));
        preview.setTranslationY(margin + Math.round(position.y() * travel(getHeight())));
        int gravity = position.y() >= 0.5f ? Gravity.TOP : Gravity.BOTTOM;
        LayoutParams layout = (LayoutParams) controls.getLayoutParams();
        if (layout.gravity != gravity) {
            layout.gravity = gravity;
            controls.setLayoutParams(layout);
        }
    }

    private int travel(int size) {
        return Math.max(0, size - 2 * margin - buttonSize);
    }
}
