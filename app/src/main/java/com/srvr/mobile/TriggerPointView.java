package com.srvr.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.TextView;

final class TriggerPointView extends TextView {
    private static final int SIZE_DP = 64;

    TriggerPointView(Context context, int number) {
        super(context);
        setGravity(Gravity.CENTER);
        setText(Integer.toString(number));
        setTextSize(20);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setTextColor(0xffe9f5ff);
        setContentDescription("触发点 " + number);
        setHighlighted(false);
    }

    static int size(Context context, int width, int height) {
        return Math.max(1, Math.min(Ui.dp(context, SIZE_DP), Math.min(width, height)));
    }

    void setHighlighted(boolean highlighted) {
        setBackground(Ui.shape(getContext(), highlighted ? 0xcf236cba : 0x9128518c, 12,
                highlighted ? 0xffaadeff : 0xff67b5ff));
        setSelected(highlighted);
    }
}
