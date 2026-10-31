package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.Calendar;

public class ClickService extends AccessibilityService {

    public static ClickService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable task;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopClick();
    }

    public void scheduleClick(
            int hour, int minute, int second,
            int count, int interval, int x, int y) {

        stopClick();

        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, 0);

        if (target.getTimeInMillis() <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        long delay = target.getTimeInMillis()
                - System.currentTimeMillis();

        task = new Runnable() {
            int remaining = count;

            @Override
            public void run() {
                if (remaining <= 0) {
                    stopClick();
                    return;
                }

                performClick(x, y);
                remaining--;

                if (remaining > 0) {
                    handler.postDelayed(this, interval);
                } else {
                    task = null;
                }
            }
        };

        handler.postDelayed(task, delay);
    }

    private void performClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(path, 0, 50)
        );

        dispatchGesture(builder.build(), null, null);
    }

    private void stopClick() {
        if (task != null) {
            handler.removeCallbacks(task);
            task = null;
        }
    }

    @Override
    public void onDestroy() {
        stopClick();
        instance = null;
        super.onDestroy();
    }
}