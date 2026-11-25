package com.srvr.mobile;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;

final class OverlayWindows {
    private OverlayWindows() {}

    static Context context(Context service, Display display) {
        Context displayContext = service.createDisplayContext(display);
        Context base = Build.VERSION.SDK_INT >= 30
                ? displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                : displayContext;
        return new ContextThemeWrapper(base, R.style.AppTheme);
    }

    static WindowManager.LayoutParams params(int width, int height, String title) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (Build.VERSION.SDK_INT >= 30) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.setTitle(title);
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(0);
            allowDisplayCutout(layout);
        }
        return layout;
    }

    static void allowDisplayCutout(WindowManager.LayoutParams layout) {
        if (Build.VERSION.SDK_INT >= 30) {
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    static Rect displayBounds(WindowManager windows) {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = windows.getCurrentWindowMetrics().getBounds();
            return new Rect(0, 0, bounds.width(), bounds.height());
        }
        Display display = windows.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    static Rect fullDisplayArea(WindowManager windows) {
        if (Build.VERSION.SDK_INT >= 30) {
            var metrics = windows.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets cutout = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
            return new Rect(cutout.left, cutout.top, bounds.width() - cutout.right, bounds.height() - cutout.bottom);
        }
        Display display = windows.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        if (Build.VERSION.SDK_INT >= 29) {
            var cutout = display.getCutout();
            if (cutout != null) return new Rect(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(),
                    size.x - cutout.getSafeInsetRight(), size.y - cutout.getSafeInsetBottom());
        }
        return new Rect(0, 0, size.x, size.y);
    }

    static Rect safeArea(WindowManager windows) {
        if (Build.VERSION.SDK_INT >= 30) {
            var metrics = windows.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom);
        }
        Point size = new Point();
        windows.getDefaultDisplay().getSize(size);
        return new Rect(0, 0, size.x, size.y);
    }
}
