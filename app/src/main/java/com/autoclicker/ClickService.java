
package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

public class ClickService extends AccessibilityService {

    public static ClickService instance;

    private Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;

    private View marker;
    private WindowManager.LayoutParams markerParams;

    private LinearLayout floatingPanel;
    private TextView clockText;
    private TextView countdownText;

    private int clickX = 500;
    private int clickY = 500;

    private int remainingCount = -1;
    private int intervalMs = 100;

    private long targetTime = 0;
    private boolean scheduled = false;

    private Runnable clickTask;
    private Runnable clockUpdater;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        showMarker();
        showFloatingPanel();
        startClockUpdater();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showMarker() {

        if (marker != null) return;

        marker = new View(this);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x663399FF);
        circle.setStroke(dp(2), 0xFF1685E8);

        marker.setBackground(circle);

        markerParams = new WindowManager.LayoutParams(
                dp(32),
                dp(32),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        markerParams.x = dp(100);
        markerParams.y = dp(250);

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

                        markerParams.x = startX +
                                (int) (event.getRawX() - touchX);

                        markerParams.y = startY +
                                (int) (event.getRawY() - touchY);

                        windowManager.updateViewLayout(marker, markerParams);
                        updateClickPosition();
                        return true;

                    case MotionEvent.ACTION_UP:
                        updateClickPosition();
                        return true;
                }

                return true;
            }
        });

        windowManager.addView(marker, markerParams);
        updateClickPosition();
    }

    private void updateClickPosition() {

        if (marker == null) return;

        int[] location = new int[2];
        marker.getLocationOnScreen(location);

        clickX = location[0] + marker.getWidth() / 2;
        clickY = location[1] + marker.getHeight() / 2;
    }

    private void showFloatingPanel() {

        if (floatingPanel != null) return;

        floatingPanel = new LinearLayout(this);
        floatingPanel.setOrientation(LinearLayout.VERTICAL);
        floatingPanel.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE222222);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xFF777777);

        floatingPanel.setBackground(background);

        clockText = new TextView(this);
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(15);
        clockText.setGravity(Gravity.CENTER);
        clockText.setTypeface(android.graphics.Typeface.MONOSPACE);

        countdownText = new TextView(this);
        countdownText.setTextColor(0xFF66CCFF);
        countdownText.setTextSize(17);
        countdownText.setGravity(Gravity.CENTER);
        countdownText.setTypeface(android.graphics.Typeface.MONOSPACE);

        Button stopButton = new Button(this);
        stopButton.setText("توقف");
        stopButton.setTextSize(12);
        stopButton.setOnClickListener(v -> stopClick());

        floatingPanel.addView(clockText);
        floatingPanel.addView(countdownText);
        floatingPanel.addView(stopButton);

        WindowManager.LayoutParams panelParams =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );

        panelParams.gravity = Gravity.TOP | Gravity.RIGHT;
        panelParams.x = dp(10);
        panelParams.y = dp(60);

        windowManager.addView(floatingPanel, panelParams);
    }

    private void startClockUpdater() {

        clockUpdater = new Runnable() {

            @Override
            public void run() {

                if (clockText != null) {

                    SimpleDateFormat format =
                            new SimpleDateFormat(
                                    "HH:mm:ss.SSS",
                                    Locale.getDefault()
                            );

                    clockText.setText("ساعت: " +
                            format.format(new Date()));
                }

                if (countdownText != null) {

                    if (scheduled) {

                        long remaining =
                                Math.max(0, targetTime -
                                        System.currentTimeMillis());

                        long hours = remaining / 3600000;
                        long minutes = (remaining % 3600000) / 60000;
                        long seconds = (remaining % 60000) / 1000;
                        long millis = remaining % 1000;

                        countdownText.setText(
                                String.format(
                                        Locale.US,
                                        "%02d : %02d : %02d . %03d",
                                        hours, minutes, seconds, millis
                                )
                        );

                    } else if (remainingCount == 0) {
                        countdownText.setText("پایان");
                    }
                }

                handler.postDelayed(this, 50);
            }
        };

        handler.post(clockUpdater);
    }

    // نسخه سازگار با کد قبلی MainActivity
    public void scheduleClick(
            int hour,
            int minute,
            int second,
            int count,
            int interval,
            int posX,
            int posY) {

        clickX = posX;
        clickY = posY;

        scheduleClick(
                hour, minute, second, 0,
                count, interval
        );
    }

    // نسخه جدید با امکان تعیین میلی‌ثانیه
    public void scheduleClick(
            int hour,
            int minute,
            int second,
            int millisecond,
            int count,
            int interval) {

        stopClick();

        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        if (target.getTimeInMillis() <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        targetTime = target.getTimeInMillis();
        remainingCount = count;
        intervalMs = interval;
        scheduled = true;

        clickTask = new Runnable() {

            @Override
            public void run() {

                if (!scheduled) return;

                if (remainingCount <= 0) {
                    scheduled = false;
                    return;
                }

                updateClickPosition();
                performClick(clickX, clickY);

                remainingCount--;

                if (remainingCount > 0) {
                    handler.postDelayed(this, intervalMs);
                } else {
                    scheduled = false;
                }
            }
        };

        long delay = Math.max(
                0,
                targetTime - System.currentTimeMillis()
        );

        handler.postDelayed(clickTask, delay);
    }

    public void stopClick() {

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
            clickTask = null;
        }

        scheduled = false;
        targetTime = 0;
        remainingCount = -1;

        if (countdownText != null) {
            countdownText.setText("متوقف شد");
        }
    }

    private void performClick(float x, float y) {

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        60
                )
        );

        dispatchGesture(builder.build(), null, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopClick();
    }

    @Override
    public void onDestroy() {

        stopClick();

        if (clockUpdater != null) {
            handler.removeCallbacks(clockUpdater);
        }

        if (marker != null) {
            windowManager.removeView(marker);
            marker = null;
        }

        if (floatingPanel != null) {
            windowManager.removeView(floatingPanel);
            floatingPanel = null;
        }

        instance = null;

        super.onDestroy();
    }
}
