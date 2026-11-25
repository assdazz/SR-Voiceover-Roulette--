package com.srvr.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.TextView;

final class TriggerPointView extends TextView {
    private static final int MIN_DP = 40;

    private final int number;
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path handlePath = new Path();
    private boolean highlighted;
    private boolean passthroughMarker;
    private boolean triggered;
    private boolean showResizeHandle;

    TriggerPointView(Context context, int number) {
        super(context);
        this.number = number;
        setGravity(Gravity.CENTER);
        setText(Integer.toString(number));
        setTextSize(15);
        setMaxLines(2);
        setEllipsize(TextUtils.TruncateAt.END);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setTextColor(0xffe9f5ff);
        setPadding(Ui.dp(context, 4), Ui.dp(context, 2), Ui.dp(context, 4), Ui.dp(context, 2));
        setContentDescription("触发热区 " + number);
        handlePaint.setColor(0xffaadeff);
        handlePaint.setStyle(Paint.Style.FILL);
        updateAppearance();
    }

    static int minSizePx(Context context) {
        return Ui.dp(context, MIN_DP);
    }

    void setLabel(String soundTitle) {
        if (soundTitle == null || soundTitle.isEmpty()) {
            setText(Integer.toString(number));
        } else {
            setText(number + "\n" + soundTitle);
        }
    }

    void setShowResizeHandle(boolean show) {
        if (showResizeHandle == show) return;
        showResizeHandle = show;
        invalidate();
    }

    boolean isInResizeHandle(float localX, float localY) {
        int zone = Ui.dp(getContext(), 28);
        return localX >= getWidth() - zone && localY >= getHeight() - zone;
    }

    void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
        setSelected(highlighted);
        updateAppearance();
    }

    void setPassthroughMarker(boolean marker) {
        this.passthroughMarker = marker;
        updateAppearance();
    }

    void setTriggered(boolean active) {
        if (this.triggered == active) return;
        this.triggered = active;
        updateAppearance();
    }

    private void updateAppearance() {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(Ui.dp(getContext(), 10));
        if (passthroughMarker) {
            // Subtle translucent visual zone marker that never obstructs game visibility.
            shape.setColor(triggered ? 0x558de4a8 : 0x2267b5ff);
            shape.setStroke(Ui.dp(getContext(), triggered ? 2.5f : 1.5f),
                    triggered ? 0xff8de4a8 : 0xaa67b5ff);
            setTextColor(triggered ? 0xffd7ffe3 : 0xb8e9f5ff);
        } else {
            shape.setColor(highlighted ? 0x88236cba : 0x5528518c);
            shape.setStroke(Ui.dp(getContext(), highlighted ? 2.5f : 1.5f),
                    highlighted ? 0xffaadeff : 0xff67b5ff);
            setTextColor(0xffe9f5ff);
        }
        setBackground(shape);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (showResizeHandle && !passthroughMarker) {
            int sz = Ui.dp(getContext(), 14);
            int m = Ui.dp(getContext(), 3);
            int r = getWidth() - m;
            int b = getHeight() - m;
            handlePath.reset();
            handlePath.moveTo(r, b - sz);
            handlePath.lineTo(r, b);
            handlePath.lineTo(r - sz, b);
            handlePath.close();
            handlePaint.setColor(highlighted ? 0xff8de4a8 : 0xb0aadeff);
            canvas.drawPath(handlePath, handlePaint);
        }
    }
}
