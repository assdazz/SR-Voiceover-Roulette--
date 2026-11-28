package com.srvr.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

import static com.srvr.mobile.WheelGeometry.CENTER;
import static com.srvr.mobile.WheelGeometry.NEXT_PAGE;
import static com.srvr.mobile.WheelGeometry.NONE;
import static com.srvr.mobile.WheelGeometry.PREV_PAGE;
import static com.srvr.mobile.WheelGeometry.STOP_PLAYBACK;

final class WheelView extends View {
    private static final String PREV_PAGE_LABEL = "上一组";
    private static final String NEXT_PAGE_LABEL = "下一组";
    private static final String STOP_PLAYBACK_DESC = "取消当前播放";
    private static final int STOP_FILL = 0xff2b1919;
    private static final int STOP_ACTIVE_FILL = 0xffff7d7d;
    private static final int STOP_TEXT = 0xffffa0a0;
    private static final int STOP_ACTIVE_TEXT = 0xff240c0c;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path slice = new Path();
    private final RectF outer = new RectF();
    private final RectF middle = new RectF();
    private final RectF inner = new RectF();
    private final RectF hub = new RectF();
    private final int[] screenLocation = new int[2];
    private final AccessibilityManager accessibility;
    private final WheelNodes nodes = new WheelNodes();
    private Playable[] sounds = new Playable[SoundLibrary.SLOTS_PER_PAGE];
    private final SoundLibrary library;
    private final IntConsumer onSoundSelected;
    private final IntConsumer onEmpty;
    private final Runnable onPlaybackFinished;
    private final Runnable pressAction = this::playPressedSound;
    private final int touchSlop;
    private float cx;
    private float cy;
    private float radius;
    private boolean opensRight = true;
    private int sizePercent = 100;
    private int hubPercent = FloatingSettings.DEFAULT_WHEEL_HUB;
    private int controlPercent = FloatingSettings.DEFAULT_WHEEL_CONTROL;
    private float hubRatio = WheelGeometry.HUB_RATIO;
    private float innerRatio = WheelGeometry.INNER_RATIO;
    private float middleRatio = WheelGeometry.MIDDLE_RATIO;
    private int page;
    private String pageTitle = "";
    private int pressed = NONE;
    private int flashed = NONE;
    private int selected = NONE;
    private SoundPlayer.Playback heldPlayback;
    private SoundSettings.Trigger pressedTrigger;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean playedInGesture;

    WheelView(Context context, IntConsumer onSoundSelected,
            IntConsumer onEmpty, Runnable onPlaybackFinished) {
        super(context);
        library = SoundLibrary.get(context);
        this.onSoundSelected = onSoundSelected;
        this.onEmpty = onEmpty;
        this.onPlaybackFinished = onPlaybackFinished;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        accessibility = context.getSystemService(AccessibilityManager.class);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setPage(int page, Playable[] sounds) {
        setPage(page, library.pageName(page), sounds);
    }

    void setPage(int page, String pageTitle, Playable[] sounds) {
        String safeTitle = pageTitle == null ? "" : pageTitle;
        if (this.page == page && Objects.equals(this.pageTitle, safeTitle) && Arrays.equals(this.sounds, sounds)) {
            return;
        }
        cancelTouch();
        flashed = NONE;
        if (this.page != page) selected = NONE;
        this.page = page;
        this.pageTitle = safeTitle;
        this.sounds = sounds;
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    void setSelectedSlot(int slot) {
        if (selected == slot) return;
        selected = slot;
        invalidate();
    }

    static float preferredRadius(Context context, int sizePercent) {
        return Ui.dp(context, WheelGeometry.RADIUS_DP) * (sizePercent / 100f);
    }

    void configure(int sizePercent, boolean opensRight) {
        configure(sizePercent, FloatingSettings.DEFAULT_WHEEL_HUB, FloatingSettings.DEFAULT_WHEEL_CONTROL, opensRight);
    }

    void configure(int sizePercent, int hubPercent, int controlPercent, boolean opensRight) {
        if (this.sizePercent == sizePercent && this.hubPercent == hubPercent
                && this.controlPercent == controlPercent && this.opensRight == opensRight) return;
        cancelTouch();
        this.sizePercent = sizePercent;
        this.hubPercent = hubPercent;
        this.controlPercent = controlPercent;
        this.hubRatio = WheelGeometry.hubRatio(hubPercent);
        this.middleRatio = WheelGeometry.middleRatio(hubPercent, controlPercent);
        this.innerRatio = WheelGeometry.innerRatio(hubPercent, controlPercent);
        this.opensRight = opensRight;
        updateGeometry(getWidth(), getHeight());
        requestLayout();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int padding = Ui.dp(getContext(), WheelGeometry.PADDING_DP);
        float preferred = preferredRadius(getContext(), sizePercent);
        int width = resolveSize(WheelGeometry.width(preferred, padding, hubRatio), widthSpec);
        float fitted = WheelGeometry.fitRadius(width, Integer.MAX_VALUE, padding, preferred, hubRatio);
        setMeasuredDimension(width, resolveSize(WheelGeometry.height(fitted, padding), heightSpec));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        updateGeometry(width, height);
    }

    private void updateGeometry(int width, int height) {
        int padding = Ui.dp(getContext(), WheelGeometry.PADDING_DP);
        radius = WheelGeometry.fitRadius(width, height, padding, preferredRadius(getContext(), sizePercent), hubRatio);
        float shift = radius * (1f - hubRatio) / 2f;
        cx = width / 2f + (opensRight ? -shift : shift);
        cy = height / 2f;
        setCircle(outer, radius);
        setCircle(middle, radius * middleRatio);
        setCircle(inner, radius * innerRatio);
        setCircle(hub, radius * hubRatio);
        invalidate();
    }

    private void setCircle(RectF bounds, float r) {
        bounds.set(cx - r, cy - r, cx + r, cy + r);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i <= NEXT_PAGE; i++) {
            boolean active = pressed == i || flashed == i || nodes.focused == i;
            int ring = WheelGeometry.ringOf(i);
            float start = WheelGeometry.start(i);
            float sweep = WheelGeometry.sweep(i);
            if (!opensRight) {
                start = 180 - start;
                sweep = -sweep;
            }
            RectF outerArc = ring == 0 ? inner : ring == 1 ? middle : outer;
            RectF innerArc = ring == 0 ? hub : ring == 1 ? inner : middle;
            slice.reset();
            slice.arcTo(outerArc, start, sweep, true);
            slice.arcTo(innerArc, start + sweep, -sweep, false);
            slice.close();

            float x = labelX(i);
            float y = labelY(i);
            if (ring == 2) {
                float controlBand = 1f - middleRatio;
                if (i == STOP_PLAYBACK) {
                    outlinePath(canvas, active ? STOP_ACTIVE_FILL : STOP_FILL, dp);
                    drawStopLabel(canvas, x, y, controlBand * radius, active ? STOP_ACTIVE_TEXT : STOP_TEXT, dp);
                } else {
                    String label = i == PREV_PAGE ? PREV_PAGE_LABEL : NEXT_PAGE_LABEL;
                    outlinePath(canvas, active ? Ui.ACCENT : Ui.CONTROL, dp);
                    float size = Math.min(radius * Math.min(0.088f, controlBand * 0.44f), textSize(11));
                    drawLabel(canvas, label, x, y, radius * 0.34f, size, active ? Ui.DARK_GREEN : Ui.ACCENT);
                }
                continue;
            }

            Playable sound = soundAt(i);
            boolean chosen = sound != null && selected == i;
            int fill = ring == 0 ? 0xff20312a : 0xff182621;
            if (sound == null) fill = ring == 0 ? 0xff15201c : 0xff121c18;
            else if (chosen) fill = 0xff2aaa65;
            outlinePath(canvas, active ? Ui.ACCENT : fill, dp);

            String label = sound == null ? "＋" : sound.title();
            int labelColor = active || chosen ? Ui.DARK_GREEN : sound == null ? Ui.SECONDARY : Ui.TEXT;
            float ringThickness = ring == 0 ? (innerRatio - hubRatio) : (middleRatio - innerRatio);
            float baseWidth = ring == 0 ? 0.28f : 0.30f;
            float labelWidth = radius * Math.max(0.20f, Math.min(0.38f, baseWidth * (ringThickness / 0.25f * 0.35f + 0.65f)));
            float size = Math.min(radius * Math.min(ring == 0 ? 0.088f : 0.082f, ringThickness * 0.42f),
                    textSize(ring == 0 ? 12 : 11));
            drawSlotLabel(canvas, label, x, y, labelWidth, size, labelColor);
        }

        float centerRadius = radius * hubRatio;
        boolean centerActive = pressed == CENTER || nodes.focused == CENTER;
        slice.reset();
        slice.addOval(hub, Path.Direction.CW);
        outlinePath(canvas, centerActive ? Ui.ACCENT : 0xff17271f, dp);
        int centerText = centerActive ? Ui.DARK_GREEN : Ui.ACCENT;
        paint.setColor(centerText);
        float mark = Math.min(centerRadius * 0.16f, 5.5f * dp);
        float iconY = cy - centerRadius * 0.25f;
        canvas.drawRoundRect(cx - mark, iconY - mark, cx + mark, iconY + mark, 2 * dp, 2 * dp, paint);
        drawLabel(canvas, pageLabel(), cx, cy + centerRadius * 0.27f, centerRadius * 1.72f,
                Math.min(centerRadius * 0.38f, textSize(12)), centerText);
    }

    private void drawStopLabel(Canvas canvas, float x, float y, float bandPx, int color, float dp) {
        float maxByBand = Math.max(7f * dp, (bandPx * 0.80f) / 2f);
        float size = Math.min(textSize(10.5f), maxByBand);
        float lineGap = size * 1.18f;
        float mark = Math.min(size * 0.34f, 4.2f * dp);
        paint.setColor(color);
        float iconY = y - lineGap * 1.62f;
        canvas.drawRoundRect(x - mark, iconY - mark, x + mark, iconY + mark, 1.4f * dp, 1.4f * dp, paint);
        float width = Math.max(bandPx * 0.90f, size * 2.2f);
        drawLabel(canvas, "取消", x, y - lineGap * 0.56f, width, size, color);
        drawLabel(canvas, "当前", x, y + lineGap * 0.56f, width, size, color);
        drawLabel(canvas, "播放", x, y + lineGap * 1.68f, width, size, color);
    }

    private String pageLabel() {
        return pageTitle.isEmpty() ? "第" + (page + 1) + "组" : pageTitle;
    }

    private void outlinePath(Canvas canvas, int fill, float stroke) {
        paint.setColor(fill);
        canvas.drawPath(slice, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(0xff60716b);
        canvas.drawPath(slice, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private float labelX(int slot) {
        double angle = Math.toRadians(WheelGeometry.start(slot) + WheelGeometry.sweep(slot) / 2);
        return cx + (opensRight ? 1 : -1) * radius
                * WheelGeometry.labelRadius(slot, hubRatio, innerRatio, middleRatio)
                * (float) Math.cos(angle);
    }

    private float labelY(int slot) {
        double angle = Math.toRadians(WheelGeometry.start(slot) + WheelGeometry.sweep(slot) / 2);
        return cy + radius
                * WheelGeometry.labelRadius(slot, hubRatio, innerRatio, middleRatio)
                * (float) Math.sin(angle);
    }

    private void drawSlotLabel(Canvas canvas, String label, float x, float y, float width,
            float size, int color) {
        text.setTextSize(size);
        int firstLine = text.breakText(label, true, width, null);
        if (firstLine > 0 && firstLine < label.length()
                && Character.isHighSurrogate(label.charAt(firstLine - 1))) firstLine--;
        if (firstLine <= 0 || firstLine >= label.length()) {
            drawLabel(canvas, label, x, y, width, size, color);
            return;
        }
        drawLabel(canvas, label.substring(0, firstLine), x, y - size * 0.54f, width, size, color);
        drawLabel(canvas, label.substring(firstLine), x, y + size * 0.54f, width, size, color);
    }

    private float textSize(float sp) {
        return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                sp, getResources().getDisplayMetrics());
    }

    private void drawLabel(Canvas canvas, String label, float x, float y, float width,
            float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        CharSequence visible = TextUtils.ellipsize(label, text, width, TextUtils.TruncateAt.END);
        float left = x - text.measureText(visible, 0, visible.length()) / 2;
        canvas.drawText(visible, 0, visible.length(), left,
                y - (text.ascent() + text.descent()) / 2, text);
    }

    private int hit(float x, float y) {
        return WheelGeometry.hit(opensRight ? x - cx : cx - x, y - cy, radius, hubRatio, innerRatio, middleRatio);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int target = hit(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (target == NONE) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                tracking = true;
                pressed = target;
                downX = event.getX();
                downY = event.getY();
                schedulePress(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                if (target != pressed) {
                    moveTo(target);
                } else if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop
                        && pressedTrigger == SoundSettings.Trigger.TAP) {
                    removeCallbacks(pressAction);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (tracking) finishTouch(target);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelTouch();
                break;
            default:
                return true;
        }
        invalidate();
        return true;
    }

    void moveFromBubble(float screenX, float screenY) {
        int target = screenSlot(screenX, screenY);
        if (!tracking && target == NONE) return;
        tracking = true;
        if (target != pressed) moveTo(target);
        invalidate();
    }

    void finishFromBubble(float screenX, float screenY) {
        finishTouch(screenSlot(screenX, screenY));
        invalidate();
    }

    private int screenSlot(float x, float y) {
        getLocationOnScreen(screenLocation);
        int target = hit(x - screenLocation[0], y - screenLocation[1]);
        return target == CENTER ? NONE : target;
    }

    private void moveTo(int target) {
        stopHeldPlayback();
        pressed = target;
        schedulePress(false);
    }

    private Playable soundAt(int target) {
        return target >= 0 && target < sounds.length ? sounds[target] : null;
    }

    private SoundSettings.Trigger triggerAt(int target) {
        Playable sound = soundAt(target);
        return sound == null ? null : library.trigger(sound);
    }

    private void schedulePress(boolean allowTap) {
        removeCallbacks(pressAction);
        if (playedInGesture) return;
        pressedTrigger = triggerAt(pressed);
        if (pressedTrigger == SoundSettings.Trigger.HOLD) {
            postDelayed(pressAction, ViewConfiguration.getLongPressTimeout());
        } else if (allowTap && pressedTrigger == SoundSettings.Trigger.TAP) {
            // Give a swipe a chance to leave the initial sector.
            postDelayed(pressAction, ViewConfiguration.getTapTimeout());
        }
    }

    private void playPressedSound() {
        Playable sound = soundAt(pressed);
        if (!tracking || playedInGesture || sound == null) return;
        SoundSettings.Trigger trigger = library.trigger(sound);
        if (trigger == SoundSettings.Trigger.RELEASE) return;
        select(pressed);
        SoundPlayer.Playback playback = SoundPlayer.play(getContext(), sound);
        if (trigger == SoundSettings.Trigger.HOLD) heldPlayback = playback;
        playedInGesture = true;
    }

    private void finishTouch(int target) {
        boolean played = playedInGesture;
        cancelTouch();
        if (!played) played = activate(target, false);
        if (played) onPlaybackFinished.run();
    }

    void cancelTouch() {
        removeCallbacks(pressAction);
        stopHeldPlayback();
        pressedTrigger = null;
        pressed = NONE;
        tracking = false;
        playedInGesture = false;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        cancelTouch();
        super.onDetachedFromWindow();
    }

    private boolean activate(int target, boolean accessibilityClick) {
        if (target == PREV_PAGE) {
            library.previousPage();
            return false;
        }
        if (target == NEXT_PAGE) {
            library.nextPage();
            return false;
        }
        if (target == STOP_PLAYBACK) {
            SoundPlayer.stop();
            flash(STOP_PLAYBACK);
            return true;
        }
        if (target == CENTER) {
            SoundPlayer.stop();
            return false;
        }
        if (target < 0) return false;
        Playable sound = soundAt(target);
        if (sound == null) {
            onEmpty.accept(target);
            return false;
        }
        select(target);
        if (!accessibilityClick && library.trigger(sound) == SoundSettings.Trigger.HOLD) return false;
        return SoundPlayer.play(getContext(), sound) != null;
    }

    private void stopHeldPlayback() {
        if (heldPlayback != null) heldPlayback.stop();
        heldPlayback = null;
    }

    private void flash(int target) {
        flashed = target;
        invalidate();
        postDelayed(() -> {
            if (flashed == target) flashed = NONE;
            invalidate();
        }, 160);
    }

    private void select(int target) {
        setSelectedSlot(target);
        flash(target);
        onSoundSelected.accept(target);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return nodes;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if (!accessibility.isTouchExplorationEnabled()) return super.dispatchHoverEvent(event);
        int target = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT
                ? NONE : hit(event.getX(), event.getY());
        if (target != nodes.hovered) {
            nodes.send(nodes.hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            nodes.hovered = target;
            nodes.send(target, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        }
        return target != NONE || super.dispatchHoverEvent(event);
    }

    private final class WheelNodes extends AccessibilityNodeProvider {
        private int focused = NONE;
        private int hovered = NONE;

        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id == View.NO_ID) {
                AccessibilityNodeInfo host = AccessibilityNodeInfo.obtain(WheelView.this);
                onInitializeAccessibilityNodeInfo(host);
                for (int i = 0; i <= CENTER; i++) host.addChild(WheelView.this, i);
                return host;
            }
            if (id < 0 || id > CENTER) return null;
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.setSource(WheelView.this, id);
            info.setParent(WheelView.this);
            info.setPackageName(getContext().getPackageName());
            info.setClassName("android.widget.Button");
            info.setContentDescription(label(id));
            info.setEnabled(true);
            info.setVisibleToUser(isShown());
            info.setFocusable(true);
            info.setClickable(true);
            info.setAccessibilityFocused(id == focused);
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            info.addAction(id == focused ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            setBounds(info, id);
            return info;
        }

        private String label(int id) {
            if (id == CENTER) return pageLabel() + "，停止播放";
            if (id == PREV_PAGE) return PREV_PAGE_LABEL;
            if (id == STOP_PLAYBACK) return STOP_PLAYBACK_DESC;
            if (id == NEXT_PAGE) return NEXT_PAGE_LABEL;
            return SoundLibrary.slotLabel(id) + "，" + (sounds[id] == null ? "空槽位" : sounds[id].title());
        }

        private void setBounds(AccessibilityNodeInfo info, int id) {
            int x = Math.round(id == CENTER ? cx : labelX(id));
            int y = Math.round(id == CENTER ? cy : labelY(id));
            float width;
            if (id == CENTER) width = hubRatio;
            else {
                int ring = WheelGeometry.ringOf(id);
                width = ring == 0 ? (innerRatio - hubRatio) * 0.45f
                        : ring == 1 ? (middleRatio - innerRatio) * 0.45f
                        : (1f - middleRatio) * 0.5f;
            }
            int r = Math.round(radius * width);
            Rect bounds = new Rect(x - r, y - r, x + r, y + r);
            info.setBoundsInParent(bounds);
            int[] screen = new int[2];
            getLocationOnScreen(screen);
            bounds.offset(screen[0], screen[1]);
            info.setBoundsInScreen(bounds);
        }

        @Override public boolean performAction(int id, int action, Bundle arguments) {
            if (id == View.NO_ID) return WheelView.this.performAccessibilityAction(action, arguments);
            if (id < 0 || id > CENTER) return false;
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    send(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    if (activate(id, true)) onPlaybackFinished.run();
                    return true;
                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    send(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    focused = id;
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    invalidate();
                    return true;
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focused != id) return false;
                    focused = NONE;
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focused != NONE
                    ? createAccessibilityNodeInfo(focused) : null;
        }

        private void send(int id, int type) {
            if (id < 0 || getParent() == null || !accessibility.isEnabled()) return;
            AccessibilityEvent event = AccessibilityEvent.obtain(type);
            event.setSource(WheelView.this, id);
            event.setPackageName(getContext().getPackageName());
            event.setClassName("android.widget.Button");
            event.setContentDescription(label(id));
            event.setEnabled(true);
            getParent().requestSendAccessibilityEvent(WheelView.this, event);
        }
    }
}
