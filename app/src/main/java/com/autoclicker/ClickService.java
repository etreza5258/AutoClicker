package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.Arrays;

public class ClickService extends AccessibilityService {

    public static ClickService instance;

    public interface CalibrationListener {
        void onProgress(int completed, int total);

        void onComplete(
                long averageInterval,
                long maxInterval,
                long suggestedInterval,
                double actualCps,
                long startOffset,
                int completedClicks
        );

        void onFailed(String message);
    }

    public interface RunStateListener {
        void onRunStateChanged(int state, String message);
    }

    // وضعیت رنگ کادر مدت زمان:
    // 0 = عادی
    // 1 = در حال کلیک
    // 2 = پایان خودکار
    // 3 = توقف دستی

    private int runVisualState = 0;
    private RunStateListener runStateListener;

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
    private int calibrationTotalClicks = 0;

    private int intervalMs = 1;
    private int touchDurationMs = 1;

    private long targetTime = 0;
    private long endTime = 0;

    private long startDeadlineUptime = 0;
    private long nextClickDeadlineUptime = 0;

    private boolean scheduled = false;
    private boolean durationMode = false;
    private boolean gestureInProgress = false;
    private boolean calibrationMode = false;
    private boolean runStarted = false;

    private String displayStatus = "آماده";

    private long firstClickOffsetMs = 0;
    private long lastClickStartUptime = 0;
    private long totalIntervalMs = 0;
    private long maxIntervalMs = 0;

    private int clicksStarted = 0;
    private int intervalSamples = 0;

    private double averageIntervalMs = 0;

    private long[] calibrationIntervals = new long[0];

    private CalibrationListener calibrationListener;

    private Runnable clickTask;
    private Runnable clockUpdater;

    public void setRunStateListener(RunStateListener listener) {

        runStateListener = listener;

        if (listener != null) {
            listener.onRunStateChanged(
                    runVisualState,
                    displayStatus
            );
        }
    }

    public int getRunVisualState() {
        return runVisualState;
    }

    private void notifyRunState(int state, String message) {

        runVisualState = state;

        if (runStateListener != null) {
            runStateListener.onRunStateChanged(
                    state,
                    message
            );
        }
    }

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

    private void resetMeasurements() {

        firstClickOffsetMs = 0;
        lastClickStartUptime = 0;
        totalIntervalMs = 0;
        maxIntervalMs = 0;

        clicksStarted = 0;
        intervalSamples = 0;

        averageIntervalMs = 0;
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

                            countdownText.setText(
                                    calibrationMode
                                            ? "کالیبراسیون"
                                            : "در حال کلیک"
                            );

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
                hour,
                minute,
                second,
                0,
                false,
                count,
                0,
                interval,
                1
        );
    }

    public void scheduleClick(
            int hour,
            int minute,
            int second,
            int millisecond,
            int count,
            int interval) {

        scheduleClick(
                hour,
                minute,
                second,
                millisecond,
                false,
                count,
                0,
                interval,
                1
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

        long delay = Math.max(
                0,
                targetTime - System.currentTimeMillis()
        );

        startDeadlineUptime =
                SystemClock.uptimeMillis() + delay;

        nextClickDeadlineUptime = startDeadlineUptime;

        resetMeasurements();

        calibrationMode = false;
        calibrationListener = null;
        calibrationTotalClicks = 0;
        calibrationIntervals = new long[0];

        scheduled = true;
        gestureInProgress = false;
        runStarted = false;

        displayStatus = "در انتظار زمان";
        notifyRunState(0, displayStatus);

        createClickTask();

        handler.postDelayed(clickTask, delay);
    }

    public void startCalibration(
            int requestedInterval,
            int testClicks,
            CalibrationListener listener) {

        stopClick();

        if (requestedInterval < 1 || testClicks < 2) {

            if (listener != null) {
                listener.onFailed(
                        "تنظیمات کالیبراسیون معتبر نیست"
                );
            }

            return;
        }

        calibrationListener = listener;
        calibrationMode = true;

        durationMode = false;
        remainingCount = testClicks;
        calibrationTotalClicks = testClicks;

        intervalMs = requestedInterval;
        touchDurationMs = 1;

        targetTime = System.currentTimeMillis();
        endTime = 0;

        startDeadlineUptime = SystemClock.uptimeMillis();
        nextClickDeadlineUptime = startDeadlineUptime;

        resetMeasurements();

        calibrationIntervals = new long[testClicks];

        scheduled = true;
        gestureInProgress = false;
        runStarted = false;

        displayStatus = "در حال کالیبراسیون";

        createClickTask();

        handler.post(clickTask);
    }

    private void createClickTask() {

        clickTask = new Runnable() {

            @Override
            public void run() {

                if (!scheduled || gestureInProgress) {
                    return;
                }

                long nowUptime = SystemClock.uptimeMillis();

                if (nowUptime < nextClickDeadlineUptime) {

                    handler.postDelayed(
                            clickTask,
                            nextClickDeadlineUptime - nowUptime
                    );

                    return;
                }

                if (durationMode &&
                        System.currentTimeMillis() >= endTime) {

                    finishClick("پایان زمان", 2);
                    return;
                }

                if (!durationMode && remainingCount <= 0) {

                    if (calibrationMode) {
                        finishCalibration();
                    } else {
                        finishClick("پایان تعداد کلیک", 2);
                    }

                    return;
                }

                updateClickPosition();

                if (!overlaysPassThrough) {
                    makeOverlaysPassThrough();
                }

                displayStatus = calibrationMode
                        ? "در حال کالیبراسیون"
                        : "در حال کلیک";

                if (!calibrationMode && !runStarted) {

                    runStarted = true;

                    notifyRunState(
                            1,
                            "در حال کلیک"
                    );
                }

                gestureInProgress = true;

                long clickStartUptime =
                        SystemClock.uptimeMillis();

                if (clicksStarted == 0) {

                    firstClickOffsetMs =
                            clickStartUptime -
                            startDeadlineUptime;

                } else {

                    long actualInterval =
                            clickStartUptime -
                            lastClickStartUptime;

                    totalIntervalMs += actualInterval;
                    intervalSamples++;

                    if (actualInterval > maxIntervalMs) {
                        maxIntervalMs = actualInterval;
                    }

                    if (calibrationMode &&
                            intervalSamples <= calibrationIntervals.length) {

                        calibrationIntervals[intervalSamples - 1] =
                                actualInterval;
                    }

                    averageIntervalMs =
                            (double) totalIntervalMs /
                            intervalSamples;
                }

                lastClickStartUptime = clickStartUptime;
                clicksStarted++;

                // مهلت کلیک بعدی بر اساس زمان شروع فعلی
                nextClickDeadlineUptime += intervalMs;

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

                                if (calibrationMode &&
                                        calibrationListener != null) {

                                    calibrationListener.onProgress(
                                            clicksStarted,
                                            calibrationTotalClicks
                                    );
                                }

                                if (calibrationMode &&
                                        remainingCount <= 0) {

                                    finishCalibration();
                                    return;
                                }

                                if (durationMode &&
                                        System.currentTimeMillis()
                                                >= endTime) {

                                    finishClick("پایان زمان", 2);
                                    return;
                                }

                                if (!durationMode &&
                                        remainingCount <= 0) {

                                    finishClick(
                                            "پایان تعداد کلیک",
                                            2
                                    );

                                    return;
                                }

                                long currentUptime =
                                        SystemClock.uptimeMillis();

                                // اگر زمان‌بندی عقب افتاده باشد،
                                // کلیک بعدی در اولین فرصت اجرا می‌شود.
                                if (nextClickDeadlineUptime <
                                        currentUptime) {

                                    nextClickDeadlineUptime =
                                            currentUptime;
                                }

                                long wait =
                                        nextClickDeadlineUptime -
                                        currentUptime;

                                handler.postDelayed(
                                        clickTask,
                                        Math.max(0, wait)
                                );
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription gesture) {

                                super.onCancelled(gesture);

                                gestureInProgress = false;

                                if (calibrationMode) {

                                    failCalibration(
                                            "لمس آزمایشی لغو شد"
                                    );

                                } else {

                                    finishClick(
                                            "لمس لغو شد",
                                            3
                                    );
                                }
                            }
                        },
                        null
                );

                if (!accepted) {

                    gestureInProgress = false;

                    if (calibrationMode) {

                        failCalibration(
                                "فرمان لمس آزمایشی پذیرفته نشد"
                        );

                    } else {

                        finishClick(
                                "فرمان لمس پذیرفته نشد",
                                3
                        );
                    }
                }
            }
        };
    }

    private void finishCalibration() {

        scheduled = false;
        gestureInProgress = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        restoreOverlayTouch();

        long average = intervalSamples > 0
                ? Math.round(averageIntervalMs)
                : intervalMs;

        long suggested = intervalMs;

        if (intervalSamples > 0) {

            long[] samples = Arrays.copyOf(
                    calibrationIntervals,
                    intervalSamples
            );

            Arrays.sort(samples);

            int index = (int) Math.ceil(
                    samples.length * 0.90
            ) - 1;

            index = Math.max(
                    0,
                    Math.min(index, samples.length - 1)
            );

            suggested = Math.max(
                    intervalMs,
                    samples[index] + 1
            );
        }

        double actualCps = average > 0
                ? 1000.0 / average
                : 0;

        displayStatus = "کالیبراسیون تمام شد";

        CalibrationListener listener =
                calibrationListener;

        calibrationListener = null;
        calibrationMode = false;

        if (listener != null) {

            listener.onComplete(
                    average,
                    maxIntervalMs,
                    suggested,
                    actualCps,
                    firstClickOffsetMs,
                    clicksStarted
            );
        }
    }

    private void failCalibration(String message) {

        scheduled = false;
        gestureInProgress = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        restoreOverlayTouch();

        displayStatus = message;

        CalibrationListener listener =
                calibrationListener;

        calibrationListener = null;
        calibrationMode = false;

        if (listener != null) {
            listener.onFailed(message);
        }
    }

    private void finishClick(String message, int state) {

        scheduled = false;
        gestureInProgress = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        displayStatus = message;

        restoreOverlayTouch();

        if (!calibrationMode) {
            notifyRunState(state, message);
        }
    }

    public void stopClick() {

        boolean wasCalibrating = calibrationMode;
        boolean wasScheduled = scheduled;

        scheduled = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
            clickTask = null;
        }

        targetTime = 0;
        endTime = 0;

        startDeadlineUptime = 0;
        nextClickDeadlineUptime = 0;

        remainingCount = -1;

        displayStatus = "متوقف شد";

        restoreOverlayTouch();

        calibrationMode = false;

        if (!wasCalibrating && wasScheduled) {
            notifyRunState(3, "متوقف شد");
        }

        if (wasCalibrating && calibrationListener != null) {

            CalibrationListener listener =
                    calibrationListener;

            calibrationListener = null;

            listener.onFailed(
                    "کالیبراسیون متوقف شد"
            );
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

        try {

            if (markerAttached && marker != null) {
                windowManager.removeView(marker);
                markerAttached = false;
            }

            if (panelAttached && floatingPanel != null) {
                windowManager.removeView(floatingPanel);
                panelAttached = false;
            }

        } catch (Exception e) {
            // پنجره ممکن است قبلاً بسته شده باشد.
        }

        runStateListener = null;
        instance = null;

        super.onDestroy();
    }
}