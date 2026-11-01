
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

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    private View marker;
    private WindowManager.LayoutParams markerParams;

    private LinearLayout floatingPanel;
    private WindowManager.LayoutParams panelParams;

    private TextView clockText;
    private TextView countdownText;

    private boolean markerAttached = false;
    private boolean panelAttached = false;
    private boolean overlaysPassThrough = false;

    private int clickX = 500;
    private int clickY = 500;

    private int remainingCount = -1;

    private int intervalMs = 1;
    private int touchDurationMs = 1;

    private long targetTime = 0;
    private long endTime = 0;

    private boolean scheduled = false;
    private boolean durationMode = false;
    private boolean gestureInProgress = false;

    private String displayStatus = "آماده";

    private Runnable clickTask;
    private Runnable clockUpdater;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        showMarker();
        showFloatingPanel();
        startClockUpdater();
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
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

        markerParams.gravity =
                Gravity.TOP | Gravity.LEFT;

        markerParams.x = dp(100);
        markerParams.y = dp(250);

        marker.setOnTouchListener(
                new View.OnTouchListener() {

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

                        windowManager.updateViewLayout(
                                marker, markerParams
                        );

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
        markerAttached = true;

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
        floatingPanel.setPadding(
                dp(12), dp(8), dp(12), dp(8)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(0xEE222222);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xFF777777);

        floatingPanel.setBackground(background);

        clockText = new TextView(this);
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(15);
        clockText.setGravity(Gravity.CENTER);
        clockText.setTypeface(
                android.graphics.Typeface.MONOSPACE
        );

        countdownText = new TextView(this);
        countdownText.setTextColor(0xFF66CCFF);
        countdownText.setTextSize(17);
        countdownText.setGravity(Gravity.CENTER);
        countdownText.setTypeface(
                android.graphics.Typeface.MONOSPACE
        );

        Button stopButton = new Button(this);
        stopButton.setText("توقف");
        stopButton.setTextSize(12);
        stopButton.setOnClickListener(v -> stopClick());

        floatingPanel.addView(clockText);
        floatingPanel.addView(countdownText);
        floatingPanel.addView(stopButton);

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity =
                Gravity.TOP | Gravity.RIGHT;

        panelParams.x = dp(10);
        panelParams.y = dp(60);

        clockText.setOnTouchListener(
                new View.OnTouchListener() {

            int startX, startY;
            float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        startX = panelParams.x;
                        startY = panelParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:

                        panelParams.x = startX -
                                (int) (event.getRawX() - touchX);

                        panelParams.y = startY +
                                (int) (event.getRawY() - touchY);

                        if (panelAttached) {
                            windowManager.updateViewLayout(
                                    floatingPanel, panelParams
                            );
                        }

                        return true;

                    case MotionEvent.ACTION_UP:
                        return true;
                }

                return true;
            }
        });

        windowManager.addView(floatingPanel, panelParams);
        panelAttached = true;
    }

    private void makeOverlaysPassThrough() {

        if (overlaysPassThrough) return;

        try {

            if (markerAttached && marker != null) {

                markerParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        marker, markerParams
                );
            }

            if (panelAttached && floatingPanel != null) {

                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        floatingPanel, panelParams
                );
            }

            overlaysPassThrough = true;

        } catch (Exception e) {
            displayStatus = "خطای تنظیم پنجره";
        }
    }

    private void restoreOverlayTouch() {

        if (!overlaysPassThrough) return;

        try {

            if (markerAttached && marker != null) {

                markerParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                windowManager.updateViewLayout(
                        marker, markerParams
                );
            }

            if (panelAttached && floatingPanel != null) {

                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                windowManager.updateViewLayout(
                        floatingPanel, panelParams
                );
            }

        } catch (Exception e) {
            // سرویس ممکن است در حال بسته شدن باشد.
        }

        overlaysPassThrough = false;
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

                    clockText.setText(
                            "ساعت: " +
                                    format.format(new Date())
                    );
                }

                if (countdownText != null) {

                    if (scheduled) {

                        long remaining;

                        if (System.currentTimeMillis()
                                < targetTime) {

                            remaining = targetTime -
                                    System.currentTimeMillis();

                        } else if (durationMode) {

                            remaining = Math.max(
                                    0,
                                    endTime -
                                            System.currentTimeMillis()
                            );

                        } else {

                            countdownText.setText("در حال کلیک");
                            handler.postDelayed(this, 100);
                            return;
                        }

                        long hours = remaining / 3600000;
                        long minutes =
                                (remaining % 3600000) / 60000;
                        long seconds =
                                (remaining % 60000) / 1000;
                        long millis = remaining % 1000;

                        countdownText.setText(
                                String.format(
                                        Locale.US,
                                        "%02d : %02d : %02d . %03d",
                                        hours, minutes, seconds, millis
                                )
                        );

                    } else {

                        countdownText.setText(displayStatus);
                    }
                }

                handler.postDelayed(this, 100);
            }
        };

        handler.post(clockUpdater);
    }

    public void scheduleClick(
            int hour, int minute, int second,
            int count, int interval, int posX, int posY) {

        clickX = posX;
        clickY = posY;

        scheduleClick(
                hour, minute, second, 0,
                false, count, 0, interval, 1
        );
    }

    public void scheduleClick(
            int hour, int minute, int second,
            int millisecond, int count, int interval) {

        scheduleClick(
                hour, minute, second, millisecond,
                false, count, 0, interval, 1
        );
    }

    public void scheduleClick(
            int hour,
            int minute,
            int second,
            int millisecond,
            boolean byDuration,
            int count,
            long durationMs,
            int interval,
            int touchDuration) {

        stopClick();

        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        if (target.getTimeInMillis()
                <= System.currentTimeMillis()) {

            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        targetTime = target.getTimeInMillis();

        durationMode = byDuration;
        remainingCount = byDuration ? -1 : count;

        intervalMs = Math.max(1, interval);
        touchDurationMs = Math.max(1, touchDuration);

        endTime = targetTime + durationMs;

        scheduled = true;
        gestureInProgress = false;

        displayStatus = "در انتظار زمان";

        clickTask = new Runnable() {

            @Override
            public void run() {

                if (!scheduled || gestureInProgress) {
                    return;
                }

                if (durationMode &&
                        System.currentTimeMillis() >= endTime) {

                    finishClick("پایان زمان");
                    return;
                }

                if (!durationMode && remainingCount <= 0) {
                    finishClick("پایان تعداد کلیک");
                    return;
                }

                updateClickPosition();

                if (!overlaysPassThrough) {
                    makeOverlaysPassThrough();
                }

                displayStatus = "در حال کلیک";
                gestureInProgress = true;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.Builder builder =
                        new GestureDescription.Builder();

                builder.addStroke(
                        new GestureDescription.StrokeDescription(
                                path,
                                0,
                                touchDurationMs
                        )
                );

                boolean accepted = dispatchGesture(
                        builder.build(),

                        new GestureResultCallback() {

                            @Override
                            public void onCompleted(
                                    GestureDescription gesture) {

                                super.onCompleted(gesture);

                                gestureInProgress = false;

                                if (!scheduled) {
                                    restoreOverlayTouch();
                                    return;
                                }

                                if (!durationMode) {
                                    remainingCount--;
                                }

                                if (durationMode &&
                                        System.currentTimeMillis()
                                                >= endTime) {

                                    finishClick("پایان زمان");
                                    return;
                                }

                                if (!durationMode &&
                                        remainingCount <= 0) {

                                    finishClick("پایان تعداد کلیک");
                                    return;
                                }

                                handler.postDelayed(
                                        clickTask,
                                        intervalMs
                                );
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription gesture) {

                                super.onCancelled(gesture);

                                gestureInProgress = false;
                                finishClick("لمس لغو شد");
                            }
                        },
                        null
                );

                if (!accepted) {

                    gestureInProgress = false;
                    finishClick("فرمان لمس پذیرفته نشد");
                }
            }
        };

        long delay = Math.max(
                0,
                targetTime - System.currentTimeMillis()
        );

        handler.postDelayed(clickTask, delay);
    }

    private void finishClick(String message) {

        scheduled = false;
        gestureInProgress = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        displayStatus = message;
        restoreOverlayTouch();
    }

    public void stopClick() {

        scheduled = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
            clickTask = null;
        }

        targetTime = 0;
        endTime = 0;
        remainingCount = -1;

        displayStatus = "متوقف شد";

        restoreOverlayTouch();

        if (countdownText != null) {
            countdownText.setText(displayStatus);
        }
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

        if (markerAttached && marker != null) {
            windowManager.removeView(marker);
            markerAttached = false;
        }

        if (panelAttached && floatingPanel != null) {
            windowManager.removeView(floatingPanel);
            panelAttached = false;
        }

        instance = null;

        super.onDestroy();
    }
}
