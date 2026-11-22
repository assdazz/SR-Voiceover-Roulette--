package com.srvr.mobile;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AboutActivity extends Activity {
    private ScrollView scroll;
    private LinearLayout safety;
    private LinearLayout developer;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = Ui.screen(this);
        LinearLayout toolbar = Ui.row(this);
        Button back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        int buttonSize = Ui.dp(this, 48);
        toolbar.addView(back, new LinearLayout.LayoutParams(buttonSize, buttonSize));
        TextView title = Ui.text(this, getString(R.string.about_title), 25, Ui.TEXT, true);
        markHeading(title);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        titleLayout.setMarginStart(Ui.dp(this, 12));
        toolbar.addView(title, titleLayout);
        Ui.below(page, toolbar, 0);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout body = Ui.column(this);
        body.setPadding(0, 0, 0, Ui.dp(this, 24));
        scroll.addView(body, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout version = Ui.card(this);
        String appName = getApplicationInfo().loadLabel(getPackageManager()).toString();
        Ui.below(version, Ui.text(this, appName, 18, Ui.TEXT, true), 0);
        Ui.below(version, Ui.text(this, versionLabel(), 14, Ui.ACCENT, true), 4);
        Ui.below(body, version, 12);

        safety = fold(body, R.string.about_safety, state != null && state.getBoolean("safetyExpanded"));
        label(safety, R.string.about_behaviour_title, 0);
        paragraph(safety, R.string.about_behaviour, 4);
        paragraph(safety, R.string.about_permissions, 8);
        label(safety, R.string.about_files_title, 14);
        paragraph(safety, R.string.about_preferences, 4);
        paragraph(safety, R.string.about_imports, 6);
        paragraph(safety, R.string.about_logs, 6);
        label(safety, R.string.about_notice_title, 14);
        paragraph(safety, R.string.about_notice_third_party, 4);
        paragraph(safety, R.string.about_notice_account, 6);
        paragraph(safety, R.string.about_notice_fair_play, 6);

        developer = fold(body, R.string.about_developer, state != null && state.getBoolean("developerExpanded"));
        paragraph(developer, R.string.about_original_author, 0);
        paragraph(developer, R.string.about_mobile_author, 16);
        label(developer, R.string.about_ai_title, 12);
        paragraph(developer, R.string.about_ai_process, 4);
        label(developer, R.string.about_updates_title, 12);
        paragraph(developer, R.string.about_updates, 4);
        label(developer, R.string.about_feedback_title, 12);
        paragraph(developer, R.string.about_feedback, 4);

        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scrollY")));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("safetyExpanded", safety.getVisibility() == View.VISIBLE);
        state.putBoolean("developerExpanded", developer.getVisibility() == View.VISIBLE);
        state.putInt("scrollY", scroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    private String versionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) return getString(R.string.about_version, info.versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return getString(R.string.about_version_unavailable);
    }

    private LinearLayout fold(LinearLayout parent, int titleId, boolean expanded) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.CARD, 14, Ui.LINE));
        Button header = Ui.button(this, "", false);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x308de4a8), null,
                Ui.shape(this, Ui.CARD, 14, 0)));
        markHeading(header);
        Ui.below(card, header, 0);

        LinearLayout details = Ui.column(this);
        int padding = Ui.dp(this, 14);
        details.setPadding(padding, 0, padding, padding);
        String title = getString(titleId);
        setExpanded(header, details, title, expanded);
        header.setOnClickListener(view -> setExpanded(header, details, title, details.getVisibility() != View.VISIBLE));
        Ui.below(card, details, 0);
        Ui.below(parent, card, 10);
        return details;
    }

    private void setExpanded(Button header, LinearLayout details, String title, boolean expanded) {
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setText((expanded ? "▾  " : "▸  ") + title);
        String state = getString(expanded ? R.string.about_expanded : R.string.about_collapsed);
        if (Build.VERSION.SDK_INT >= 30) {
            header.setContentDescription(title);
            header.setStateDescription(state);
        } else {
            header.setContentDescription(title + "，" + state);
        }
    }

    private void label(LinearLayout parent, int id, int gap) {
        TextView label = Ui.text(this, getString(id), 15, Ui.TEXT, true);
        markHeading(label);
        Ui.below(parent, label, gap);
    }

    private void paragraph(LinearLayout parent, int id, int gap) {
        TextView text = Ui.text(this, getString(id), 14, Ui.SECONDARY, false);
        text.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.below(parent, text, gap);
    }

    private static void markHeading(View view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
}
