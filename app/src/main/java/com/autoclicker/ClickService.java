package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.drawable.GradientDrawable;

import java.util.Calendar;

public class ClickService extends AccessibilityService {

    public static ClickService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View marker;
    private WindowManager.LayoutParams markerParams;

    private int clickX = 500;
    private int clickY = 500;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showMarker();
    }

    private void showMarker() {
        if (marker != null) return;

        marker = new View(this);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x663399FF);
        circle.setStroke(3, 0xFF1685E8);
        marker.setBackground(circle);

        markerParams = new WindowManager.LayoutParams(
                32,
                32,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        markerParams.x = clickX;
        markerParams.y = clickY;

        marker.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = markerParams.x;
                        startY = markerParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        markerParams.x = startX + (int)(event.getRawX() - touchX);
                        markerParams.y = startY + (int)(event.getRawY() - touchY);
                        windowManager.updateViewLayout(marker, markerParams);
                        clickX = markerParams.x + 16;
                        clickY = markerParams.y + 16;
                        return true;
                }
                return true;
            }
        });

        windowManager.addView(marker, markerParams);
    }

    public void scheduleClick(
            int hour, int minute, int second, int millisecond,
            int count, int interval) {

        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        if (target.getTimeInMillis() <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        long delay = target.getTimeInMillis() - System.currentTimeMillis();

        handler.postDelayed(new Runnable() {
            int remaining = count;

            @Override
            public void run() {
                if (remaining <= 0) return;

                performClick(clickX, clickY);
                remaining--;

                if (remaining > 0) {
                    handler.postDelayed(this, interval);
                }
            }
        }, delay);
    }

    private void performClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        clickPath.lineTo(x + 1, y + 1);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                clickPath, 0, 50));

        dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (marker != null) {
            windowManager.removeView(marker);
            marker = null;
        }
        instance = null;
        super.onDestroy();
    }
}