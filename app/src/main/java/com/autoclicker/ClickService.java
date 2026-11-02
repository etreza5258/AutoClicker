
package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ClickService extends AccessibilityService {

    public static ClickService instance;

    public interface CalibrationListener {
        void onProgress(int completed, int total);
        void onComplete(long averageInterval, long maxInterval,
                        long suggestedInterval, double actualCps,
                        long startOffset, int completedClicks);
        void onFailed(String message);
    }

    public interface RunStateListener {
        void onRunStateChanged(int state, String message);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View marker;
    private WindowManager.LayoutParams markerParams;
    private LinearLayout floatingPanel;
    private WindowManager.LayoutParams panelParams;
    private GradientDrawable panelBackground;

    private TextView clockText, countdownText, panelStatus;
    private LinearLayout expandedArea;
    private ScrollView panelScroll;
    private Button expandButton;

    private EditText pHour, pMinute, pSecond, pMs;
    private EditText pCount, pInterval, pTouch;
    private EditText pDurationHour, pDurationMinute;
    private EditText pDurationSecond, pDurationMs;
    private Spinner pMode;

    private boolean expanded = false;
    private boolean markerAttached = false;
    private boolean panelAttached = false;
    private boolean overlaysPassThrough = false;

    private int clickX = 500, clickY = 500;
    private int remainingCount = -1;
    private int intervalMs = 50, touchDurationMs = 1;

    private long targetTime, endTime;
    private long startDeadlineUptime, nextClickDeadlineUptime;

    // زمان هدف بر اساس ساعت یکنواخت سیستم
    private long targetElapsedRealtimeNanos;

    // اختلاف زمان ارسال دستور اولین Gesture با زمان هدف
    private long firstClickCommandOffsetNanos;

    private boolean scheduled;
    private boolean durationMode;
    private boolean gestureInProgress;
    private boolean calibrationMode;
    private boolean runStarted;

    private String displayStatus = "آماده";
    private int runVisualState = 0;

    private long firstClickOffsetMs;
    private long lastClickStartUptime;
    private long totalIntervalMs;
    private long maxIntervalMs;
    private int clicksStarted;
    private int intervalSamples;
    private double averageIntervalMs;

    private int calibrationTotalClicks;
    private long[] calibrationIntervals = new long[0];
    private CalibrationListener calibrationListener;
    private RunStateListener runStateListener;

    private Runnable clickTask;
    private Runnable clockUpdater;

    public void setRunStateListener(RunStateListener listener) {
        runStateListener = listener;
        if (listener != null) {
            listener.onRunStateChanged(runVisualState, displayStatus);
        }
    }

    public int getRunVisualState() {
        return runVisualState;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    // پس زمینه پنل همیشه مشکی است
    private void updatePanelColor(int state) {
        if (panelBackground == null) return;

        panelBackground.setColor(Color.BLACK);

        if (floatingPanel != null) {
            floatingPanel.invalidate();
        }
    }

    private void notifyRunState(int state, String message) {
        runVisualState = state;
        displayStatus = message;
        updatePanelColor(state);

        if (panelStatus != null) {
            panelStatus.setText(message);
        }

        if (runStateListener != null) {
            runStateListener.onRunStateChanged(state, message);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        showMarker();
        showFloatingPanel();
        startClockUpdater();
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
                dp(32), dp(32),
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

                        if (markerAttached) {
                            windowManager.updateViewLayout(marker, markerParams);
                        }
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

    private TextView panelLabel(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setPadding(dp(3), dp(5), dp(3), dp(3));
        return t;
    }

    private EditText panelNumber(String hint, int value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(Color.BLACK);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setEms(4);
        return e;
    }

    private LinearLayout panelRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        return row;
    }

    private void addPanelField(LinearLayout row, EditText field) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(field, lp);
    }

    private Button panelButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        return b;
    }

    // پنل کوچک: ساعت، شمارش معکوس و علامت باز و بسته شدن
    private void showFloatingPanel() {
        if (floatingPanel != null) return;

        floatingPanel = new LinearLayout(this);
        floatingPanel.setOrientation(LinearLayout.VERTICAL);
        floatingPanel.setPadding(dp(7), dp(4), dp(7), dp(4));

        panelBackground = rounded(Color.BLACK, 0xFF444444);
        floatingPanel.setBackground(panelBackground);

        LinearLayout header = panelRow();

        clockText = panelLabel("--:--:--.---", 16);
        clockText.setTextColor(Color.rgb(0, 200, 80));
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER);

        header.addView(clockText,
                new LinearLayout.LayoutParams(0, dp(38), 1));

        countdownText = panelLabel("آماده", 12);
        countdownText.setTextColor(Color.rgb(20, 90, 220));
        countdownText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        countdownText.setGravity(Gravity.CENTER);

        header.addView(countdownText,
                new LinearLayout.LayoutParams(0, dp(38), 1));

        expandButton = panelButton("+");
        header.addView(expandButton,
                new LinearLayout.LayoutParams(dp(38), dp(38)));

        floatingPanel.addView(header);

        expandedArea = new LinearLayout(this);
        expandedArea.setOrientation(LinearLayout.VERTICAL);

        panelStatus = panelLabel("آماده", 12);
        panelStatus.setGravity(Gravity.CENTER);
        expandedArea.addView(panelStatus);

        LinearLayout actionRow = panelRow();
        Button stopButton = panelButton("توقف");
        stopButton.setOnClickListener(v -> stopClick());

        actionRow.addView(stopButton,
                new LinearLayout.LayoutParams(-1, dp(42)));

        expandedArea.addView(actionRow);

        buildPanelSettings();

        panelScroll = new ScrollView(this);
        panelScroll.setFillViewport(false);
        panelScroll.addView(expandedArea);
        panelScroll.setVisibility(View.GONE);

        floatingPanel.addView(panelScroll,
                new LinearLayout.LayoutParams(-1, dp(390)));

        expandButton.setOnClickListener(v -> toggleExpanded());

        panelParams = new WindowManager.LayoutParams(
                dp(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity = Gravity.TOP | Gravity.RIGHT;
        panelParams.x = dp(10);
        panelParams.y = dp(60);

        clockText.setOnTouchListener(new View.OnTouchListener() {
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
                                    floatingPanel, panelParams);
                        }
                        return true;
                }
                return true;
            }
        });

        windowManager.addView(floatingPanel, panelParams);
        panelAttached = true;
    }

    private void buildPanelSettings() {
        expandedArea.addView(panelLabel("تنظیمات اجرای کلیکر", 17));

        expandedArea.addView(panelLabel(
                "زمان شروع: ساعت / دقیقه / ثانیه / میلی‌ثانیه", 12));

        LinearLayout timeRow = panelRow();

        pHour = panelNumber("ساعت", 12);
        pMinute = panelNumber("دقیقه", 0);
        pSecond = panelNumber("ثانیه", 0);
        pMs = panelNumber("میلی‌ثانیه", 0);

        addPanelField(timeRow, pHour);
        addPanelField(timeRow, pMinute);
        addPanelField(timeRow, pSecond);
        addPanelField(timeRow, pMs);

        expandedArea.addView(timeRow);

        expandedArea.addView(panelLabel("روش توقف", 12));

        pMode = new Spinner(this);

        String[] modes = {
                "توقف پس از تعداد کلیک",
                "توقف پس از مدت زمان"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, modes);

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);

        pMode.setAdapter(adapter);
        expandedArea.addView(pMode);

        expandedArea.addView(panelLabel("تعداد کلیک", 12));

        pCount = panelNumber("تعداد", 30);
        expandedArea.addView(pCount);

        expandedArea.addView(panelLabel(
                "مدت اجرا: ساعت / دقیقه / ثانیه / میلی‌ثانیه", 12));

        LinearLayout durationRow = panelRow();

        pDurationHour = panelNumber("ساعت", 0);
        pDurationMinute = panelNumber("دقیقه", 0);
        pDurationSecond = panelNumber("ثانیه", 10);
        pDurationMs = panelNumber("میلی‌ثانیه", 0);

        addPanelField(durationRow, pDurationHour);
        addPanelField(durationRow, pDurationMinute);
        addPanelField(durationRow, pDurationSecond);
        addPanelField(durationRow, pDurationMs);

        expandedArea.addView(durationRow);

        expandedArea.addView(panelLabel(
                "فاصله بین کلیک‌ها (میلی‌ثانیه)", 12));

        pInterval = panelNumber("فاصله", 50);
        expandedArea.addView(pInterval);

        expandedArea.addView(panelLabel(
                "مدت لمس (میلی‌ثانیه)", 12));

        pTouch = panelNumber("مدت لمس", 1);
        expandedArea.addView(pTouch);

        Button applyStart = panelButton("اعمال و شروع");
        applyStart.setOnClickListener(v -> applyPanelSettings());
        expandedArea.addView(applyStart);

        Button collapse = panelButton("بستن تنظیمات");
        collapse.setOnClickListener(v -> toggleExpanded());
        expandedArea.addView(collapse);
    }

    private void toggleExpanded() {
        if (overlaysPassThrough) return;

        expanded = !expanded;

        panelScroll.setVisibility(
                expanded ? View.VISIBLE : View.GONE);

        expandButton.setText(expanded ? "−" : "+");

        panelParams.flags = expanded
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        try {
            windowManager.updateViewLayout(floatingPanel, panelParams);
        } catch (Exception ignored) {}

        if (!expanded) {
            try {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(
                                Context.INPUT_METHOD_SERVICE);

                imm.hideSoftInputFromWindow(
                        floatingPanel.getWindowToken(), 0);
            } catch (Exception ignored) {}
        }
    }

    private int read(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void applyPanelSettings() {
        int h = read(pHour, -1);
        int m = read(pMinute, -1);
        int s = read(pSecond, -1);
        int ms = read(pMs, -1);

        int c = read(pCount, 0);
        int interval = read(pInterval, 0);
        int touch = read(pTouch, 0);

        long duration =
                read(pDurationHour, 0) * 3600000L
                + read(pDurationMinute, 0) * 60000L
                + read(pDurationSecond, 0) * 1000L
                + read(pDurationMs, 0);

        boolean byDuration = pMode.getSelectedItemPosition() == 1;

        if (h < 0 || h > 23 || m < 0 || m > 59 ||
                s < 0 || s > 59 || ms < 0 || ms > 999 ||
                interval < 1 || touch < 1 ||
                (!byDuration && c < 1) ||
                (byDuration && duration <= 0)) {

            Toast.makeText(this,
                    "مقادیر تنظیمات معتبر نیستند.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);

            imm.hideSoftInputFromWindow(
                    floatingPanel.getWindowToken(), 0);
        } catch (Exception ignored) {}

        scheduleClick(h, m, s, ms, byDuration,
                c, duration, interval, touch);

        expanded = false;
        panelScroll.setVisibility(View.GONE);
        expandButton.setText("+");

        panelParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        try {
            windowManager.updateViewLayout(floatingPanel, panelParams);
        } catch (Exception ignored) {}
    }

    private void makeOverlaysPassThrough() {
        if (overlaysPassThrough) return;

        try {
            if (markerAttached && marker != null) {
                markerParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(marker, markerParams);
            }

            if (panelAttached && floatingPanel != null) {
                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        floatingPanel, panelParams);
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

                windowManager.updateViewLayout(marker, markerParams);
            }

            if (panelAttached && floatingPanel != null) {
                panelParams.flags = expanded
                        ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                windowManager.updateViewLayout(
                        floatingPanel, panelParams);
            }
        } catch (Exception ignored) {}

        overlaysPassThrough = false;
    }

    public void testClickCurrentPosition() {
        if (scheduled || gestureInProgress) {
            Toast.makeText(this,
                    "برای تست، ابتدا اجرای فعلی را متوقف کن.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        updateClickPosition();
        makeOverlaysPassThrough();

        Path path = new Path();
        path.moveTo(clickX, clickY);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(path, 0, 60));

        boolean accepted = dispatchGesture(
                builder.build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gesture) {
                        super.onCompleted(gesture);
                        restoreOverlayTouch();
                    }

                    @Override
                    public void onCancelled(GestureDescription gesture) {
                        super.onCancelled(gesture);
                        restoreOverlayTouch();
                    }
                },
                null
        );

        if (!accepted) restoreOverlayTouch();
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
                                    Locale.getDefault());

                    clockText.setText(format.format(new Date()));
                }

                if (countdownText != null) {
                    if (scheduled) {
                        long now = System.currentTimeMillis();
                        long remaining;

                        if (now < targetTime) {
                            remaining = targetTime - now;
                        } else if (durationMode) {
                            remaining = Math.max(0, endTime - now);
                        } else {
                            countdownText.setText("در حال کلیک");
                            handler.postDelayed(this, 100);
                            return;
                        }

                        long hours = remaining / 3600000;
                        long minutes = (remaining % 3600000) / 60000;
                        long seconds = (remaining % 60000) / 1000;
                        long millis = remaining % 1000;

                        countdownText.setText(String.format(
                                Locale.US,
                                "%02d:%02d:%02d.%03d",
                                hours, minutes, seconds, millis));
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

        scheduleClick(hour, minute, second, 0,
                false, count, 0, interval, 1);
    }

    public void scheduleClick(
            int hour, int minute, int second,
            int millisecond, int count, int interval) {

        scheduleClick(hour, minute, second, millisecond,
                false, count, 0, interval, 1);
    }

    public void scheduleClick(
            int hour, int minute, int second,
            int millisecond, boolean byDuration,
            int count, long durationMs,
            int interval, int touchDuration) {

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

        durationMode = byDuration;
        remainingCount = byDuration ? -1 : count;
        intervalMs = Math.max(1, interval);
        touchDurationMs = Math.max(1, touchDuration);
        endTime = targetTime + durationMs;

        long wallNow = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtimeNanos();

        long delay = Math.max(0, targetTime - wallNow);

        startDeadlineUptime =
                SystemClock.uptimeMillis() + delay;

        targetElapsedRealtimeNanos =
                elapsedNow + delay * 1000000L;

        nextClickDeadlineUptime = startDeadlineUptime;

        resetMeasurements();
        firstClickCommandOffsetNanos = 0;

        if (countdownText != null) {
            countdownText.setTextColor(Color.rgb(20, 90, 220));
        }

        calibrationMode = false;
        calibrationListener = null;
        calibrationTotalClicks = 0;
        calibrationIntervals = new long[0];

        scheduled = true;
        gestureInProgress = false;
        runStarted = false;

        notifyRunState(0, "در انتظار زمان");

        createClickTask();
        handler.postDelayed(clickTask, delay);
    }

    public void startCalibration(
            int requestedInterval, int testClicks,
            CalibrationListener listener) {

        stopClick();

        if (requestedInterval < 1 || testClicks < 2) {
            if (listener != null) {
                listener.onFailed("تنظیمات کالیبراسیون معتبر نیست");
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

        targetElapsedRealtimeNanos = 0;
        firstClickCommandOffsetNanos = 0;

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
                if (!scheduled || gestureInProgress) return;

                long nowUptime = SystemClock.uptimeMillis();

                if (nowUptime < nextClickDeadlineUptime) {
                    handler.postDelayed(
                            clickTask,
                            nextClickDeadlineUptime - nowUptime);
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
                makeOverlaysPassThrough();

                displayStatus = calibrationMode
                        ? "در حال کالیبراسیون"
                        : "در حال کلیک";

                if (!calibrationMode && !runStarted) {
                    runStarted = true;
                    notifyRunState(1, "در حال کلیک");
                }

                gestureInProgress = true;

                long clickStart = SystemClock.uptimeMillis();

                boolean isFirstClick = clicksStarted == 0;

                if (isFirstClick) {
                    firstClickOffsetMs =
                            clickStart - startDeadlineUptime;
                } else {
                    long actualInterval =
                            clickStart - lastClickStartUptime;

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
                            (double) totalIntervalMs / intervalSamples;
                }

                lastClickStartUptime = clickStart;
                clicksStarted++;
                nextClickDeadlineUptime += intervalMs;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.Builder builder =
                        new GestureDescription.Builder();

                builder.addStroke(
                        new GestureDescription.StrokeDescription(
                                path, 0, touchDurationMs));

                GestureDescription gestureDescription = builder.build();

                // ثبت زمان درست پیش از ارسال اولین دستور Gesture
                long commandTimeNanos =
                        SystemClock.elapsedRealtimeNanos();

                boolean accepted = dispatchGesture(
                        gestureDescription,
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

                                if (!durationMode) remainingCount--;

                                if (calibrationMode &&
                                        calibrationListener != null) {
                                    calibrationListener.onProgress(
                                            clicksStarted,
                                            calibrationTotalClicks);
                                }

                                if (calibrationMode &&
                                        remainingCount <= 0) {
                                    finishCalibration();
                                    return;
                                }

                                if (durationMode &&
                                        System.currentTimeMillis() >= endTime) {
                                    finishClick("پایان زمان", 2);
                                    return;
                                }

                                if (!durationMode && remainingCount <= 0) {
                                    finishClick(
                                            "پایان تعداد کلیک", 2);
                                    return;
                                }

                                long current = SystemClock.uptimeMillis();

                                if (nextClickDeadlineUptime < current) {
                                    nextClickDeadlineUptime = current;
                                }

                                handler.postDelayed(
                                        clickTask,
                                        Math.max(0,
                                                nextClickDeadlineUptime - current));
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription gesture) {

                                super.onCancelled(gesture);
                                gestureInProgress = false;

                                if (calibrationMode) {
                                    failCalibration("لمس آزمایشی لغو شد");
                                } else {
                                    finishClick("لمس لغو شد", 3);
                                }
                            }
                        },
                        null
                );

                // بررسی دقت فقط برای اولین کلیک اجرای عادی
                if (isFirstClick && !calibrationMode) {

                    firstClickCommandOffsetNanos =
                            commandTimeNanos -
                            targetElapsedRealtimeNanos;

                    boolean accurate =
                            firstClickCommandOffsetNanos >= -50000000L &&
                            firstClickCommandOffsetNanos <= 50000000L;

                    if (countdownText != null) {
                        countdownText.setTextColor(
                                accurate
                                        ? Color.rgb(20, 90, 220)
                                        : Color.RED);
                    }
                }

                if (!accepted) {
                    gestureInProgress = false;

                    if (calibrationMode) {
                        failCalibration("فرمان لمس آزمایشی پذیرفته نشد");
                    } else {
                        finishClick("فرمان لمس پذیرفته نشد", 3);
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
                ? Math.round(averageIntervalMs) : intervalMs;

        long suggested = intervalMs;

        if (intervalSamples > 0) {
            long[] samples = Arrays.copyOf(
                    calibrationIntervals, intervalSamples);

            Arrays.sort(samples);

            int index = (int) Math.ceil(samples.length * 0.90) - 1;
            index = Math.max(0, Math.min(index, samples.length - 1));

            suggested = Math.max(intervalMs, samples[index] + 1);
        }

        double actualCps = average > 0 ? 1000.0 / average : 0;
        displayStatus = "کالیبراسیون تمام شد";

        CalibrationListener listener = calibrationListener;
        calibrationListener = null;
        calibrationMode = false;

        if (listener != null) {
            listener.onComplete(
                    average, maxIntervalMs, suggested,
                    actualCps, firstClickOffsetMs, clicksStarted);
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

        CalibrationListener listener = calibrationListener;
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
        targetElapsedRealtimeNanos = 0;
        firstClickCommandOffsetNanos = 0;
        remainingCount = -1;

        displayStatus = "متوقف شد";
        restoreOverlayTouch();
        calibrationMode = false;

        if (!wasCalibrating && wasScheduled) {
            notifyRunState(3, "متوقف شد");
        }

        if (wasCalibrating && calibrationListener != null) {
            CalibrationListener listener = calibrationListener;
            calibrationListener = null;
            listener.onFailed("کالیبراسیون متوقف شد");
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
        } catch (Exception ignored) {}

        runStateListener = null;
        instance = null;

        super.onDestroy();
    }
}
