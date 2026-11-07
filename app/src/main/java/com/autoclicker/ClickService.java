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
import java.util.ArrayList;
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
    private TextView historyText;
    private LinearLayout expandedArea;
    private ScrollView panelScroll;
    private Button expandButton;

    private EditText pHour, pMinute, pSecond, pMs;
    private EditText pLoopSeconds, pLoopMs;
    private EditText pPauseSeconds, pPauseMs;
    private EditText pLoopCount, pInterval, pTouch;

    private boolean expanded = false;
    private boolean markerAttached = false;
    private boolean panelAttached = false;
    private boolean overlaysPassThrough = false;
    private boolean closingApplication = false;

    private int clickX = 500;
    private int clickY = 500;

    private int intervalMs = 50;
    private int touchDurationMs = 1;

    // زمان‌بندی اصلی
    private boolean scheduled = false;
    private boolean gestureInProgress = false;
    private boolean calibrationMode = false;

    private long targetWallTime;
    private long targetElapsed;

    private long loopDurationMs;
    private long pauseDurationMs;
    private int totalLoops;
    private int currentLoop;

    private long loopStartElapsed;
    private long loopDeadlineElapsed;
    private long loopStartWall;
    private long loopEndWall;

    private int loopClicks;
    private int totalClicks;

    private long nextClickDeadline;
    private long lastClickStart;
    private long firstClickOffsetMs;

    private boolean loopActive = false;
    private boolean waitingForPause = false;

    private int calibrationTotalClicks;
    private int calibrationRemaining;
    private int calibrationClicks;
    private long[] calibrationIntervals = new long[0];

    private long totalIntervalMs;
    private long maxIntervalMs;
    private int intervalSamples;
    private double averageIntervalMs;

    private CalibrationListener calibrationListener;
    private RunStateListener runStateListener;

    private String displayStatus = "آماده";
    private int runVisualState = 0;

    private Runnable clickTask;
    private Runnable clockUpdater;
    private Runnable loopStartTask;

    private final ArrayList<String> loopHistory =
            new ArrayList<>();

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public void setRunStateListener(RunStateListener listener) {
        runStateListener = listener;

        if (listener != null) {
            listener.onRunStateChanged(
                    runVisualState, displayStatus);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private String formatTime(long time) {
        return timeFormat.format(new Date(time));
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

    private void updatePanelColor(int state) {
        if (panelBackground == null) return;

        panelBackground.setColor(Color.BLACK);

        if (floatingPanel != null) {
            floatingPanel.invalidate();
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

    // =====================================================
    // نشانگر آبی
    // =====================================================

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

                        if (markerAttached) {
                            windowManager.updateViewLayout(
                                    marker, markerParams);
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

    // =====================================================
    // ابزارهای پنل
    // =====================================================

    private TextView panelLabel(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setPadding(dp(5), dp(7), dp(5), dp(7));
        return t;
    }

    private EditText panelNumber(String hint, int value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(Color.BLACK);
        e.setHintTextColor(0xFF777777);
        e.setHint(hint);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(5), dp(4), dp(5), dp(4));
        e.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        e.setBackground(rounded(0xFFF2F2F2, 0xFF888888));
        return e;
    }

    private LinearLayout panelRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        return row;
    }

    private void addPanelField(LinearLayout row, EditText field) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(50), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(field, lp);
    }

    private Button panelButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private TextView sectionTitle(String text, int color) {
        TextView title = panelLabel(text, 15);
        title.setTextColor(color);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }

    private void addSeparator() {
        View line = new View(this);
        line.setBackgroundColor(0xFF444444);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(1));

        lp.setMargins(0, dp(8), 0, dp(8));
        expandedArea.addView(line, lp);
    }

    // =====================================================
    // پنل شناور
    // =====================================================

    private void showFloatingPanel() {
        if (floatingPanel != null) return;

        floatingPanel = new LinearLayout(this);
        floatingPanel.setOrientation(LinearLayout.VERTICAL);
        floatingPanel.setPadding(
                dp(7), dp(5), dp(7), dp(5));

        panelBackground = rounded(
                Color.BLACK, 0xFF555555);

        floatingPanel.setBackground(panelBackground);

        LinearLayout header = panelRow();

        clockText = panelLabel("--:--:--.---", 20);
        clockText.setTextColor(0xFF00D65A);
        clockText.setTypeface(
                Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER);

        header.addView(clockText,
                new LinearLayout.LayoutParams(0, dp(44), 1));

        countdownText = panelLabel("آماده", 12);
        countdownText.setTextColor(0xFF287BFF);
        countdownText.setTypeface(
                Typeface.MONOSPACE, Typeface.BOLD);
        countdownText.setGravity(Gravity.CENTER);

        header.addView(countdownText,
                new LinearLayout.LayoutParams(0, dp(44), 1));

        expandButton = panelButton("+");
        expandButton.setTextSize(19);
        expandButton.setTextColor(Color.WHITE);
        expandButton.setBackground(
                rounded(0xFF333333, 0xFF777777));

        header.addView(expandButton,
                new LinearLayout.LayoutParams(dp(36), dp(40)));

        Button powerButton = panelButton("⏻");
        powerButton.setTextSize(23);
        powerButton.setTextColor(0xFFFF5555);
        powerButton.setBackground(
                rounded(0xFF292929, 0xFFFF5555));

        LinearLayout.LayoutParams powerParams =
                new LinearLayout.LayoutParams(dp(40), dp(40));

        powerParams.setMargins(dp(4), 0, 0, 0);
        header.addView(powerButton, powerParams);

        powerButton.setOnClickListener(
                v -> closeApplication());

        floatingPanel.addView(header);

        expandedArea = new LinearLayout(this);
        expandedArea.setOrientation(LinearLayout.VERTICAL);

        panelStatus = panelLabel("آماده", 13);
        panelStatus.setGravity(Gravity.CENTER);
        panelStatus.setTextColor(0xFF00D5FF);
        expandedArea.addView(panelStatus);

        Button stopButton = panelButton("توقف اجرای کلیک");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackground(
                rounded(0xFF9C2929, 0xFFFF5555));

        stopButton.setOnClickListener(v -> stopClick());

        expandedArea.addView(stopButton,
                new LinearLayout.LayoutParams(-1, dp(45)));

        buildPanelSettings();

        panelScroll = new ScrollView(this);
        panelScroll.setFillViewport(false);
        panelScroll.addView(expandedArea);
        panelScroll.setVisibility(View.GONE);

        floatingPanel.addView(panelScroll,
                new LinearLayout.LayoutParams(-1, dp(420)));

        expandButton.setOnClickListener(v -> toggleExpanded());

        panelParams = new WindowManager.LayoutParams(
                dp(330),
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

    // =====================================================
    // تنظیمات پنل شناور
    // =====================================================

    private void buildPanelSettings() {
        TextView title =
                sectionTitle("تنظیمات اجرای کلیکر", 0xFF00D5FF);

        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        expandedArea.addView(title);

        addSeparator();

        expandedArea.addView(
                sectionTitle("زمان شروع", 0xFF00D5FF));

        expandedArea.addView(panelLabel(
                "ساعت | دقیقه | ثانیه | میلی‌ثانیه", 11));

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

        addSeparator();

        expandedArea.addView(
                sectionTitle("مدت هر لوپ", 0xFFFFC04D));

        expandedArea.addView(
                panelLabel("ثانیه | میلی‌ثانیه", 11));

        LinearLayout durationRow = panelRow();

        pLoopSeconds = panelNumber("ثانیه", 10);
        pLoopMs = panelNumber("میلی‌ثانیه", 0);

        addPanelField(durationRow, pLoopSeconds);
        addPanelField(durationRow, pLoopMs);

        expandedArea.addView(durationRow);

        addSeparator();

        expandedArea.addView(
                sectionTitle("مکث بین لوپ‌ها", 0xFFFFC04D));

        expandedArea.addView(
                panelLabel("ثانیه | میلی‌ثانیه", 11));

        LinearLayout pauseRow = panelRow();

        pPauseSeconds = panelNumber("ثانیه", 1);
        pPauseMs = panelNumber("میلی‌ثانیه", 0);

        addPanelField(pauseRow, pPauseSeconds);
        addPanelField(pauseRow, pPauseMs);

        expandedArea.addView(pauseRow);

        addSeparator();

        expandedArea.addView(
                sectionTitle("تعداد لوپ", 0xFF00D5FF));

        expandedArea.addView(
                panelLabel("صفر = اجرای نامحدود", 11));

        pLoopCount = panelNumber("تعداد لوپ", 5);
        expandedArea.addView(pLoopCount);

        addSeparator();

        expandedArea.addView(
                sectionTitle("سرعت کلیک", 0xFF00D5FF));

        expandedArea.addView(
                panelLabel("فاصله کلیک‌ها (میلی‌ثانیه)", 12));

        pInterval = panelNumber("فاصله", 50);
        expandedArea.addView(pInterval);

        expandedArea.addView(
                panelLabel("مدت لمس (میلی‌ثانیه)", 12));

        pTouch = panelNumber("مدت لمس", 1);
        expandedArea.addView(pTouch);

        addSeparator();

        Button applyStart = panelButton("اعمال تنظیمات و شروع");
        applyStart.setTextSize(15);
        applyStart.setTextColor(Color.WHITE);
        applyStart.setBackground(
                rounded(0xFF087D45, 0xFF19C875));

        applyStart.setOnClickListener(v -> applyPanelSettings());

        expandedArea.addView(applyStart,
                new LinearLayout.LayoutParams(-1, dp(50)));

        addSeparator();

        expandedArea.addView(
                sectionTitle("گزارش لوپ‌ها", 0xFF00D5FF));

        historyText = panelLabel("هنوز گزارشی ثبت نشده است.", 11);
        historyText.setTypeface(Typeface.MONOSPACE);
        expandedArea.addView(historyText);

        Button collapse = panelButton("بستن تنظیمات");
        collapse.setTextColor(Color.WHITE);
        collapse.setBackground(
                rounded(0xFF444444, 0xFF777777));

        collapse.setOnClickListener(v -> toggleExpanded());

        LinearLayout.LayoutParams collapseParams =
                new LinearLayout.LayoutParams(-1, dp(45));

        collapseParams.setMargins(0, dp(7), 0, dp(5));
        expandedArea.addView(collapse, collapseParams);
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
            windowManager.updateViewLayout(
                    floatingPanel, panelParams);
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
            return Integer.parseInt(
                    e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void applyPanelSettings() {
        int h = read(pHour, -1);
        int m = read(pMinute, -1);
        int s = read(pSecond, -1);
        int ms = read(pMs, -1);

        int loopSec = read(pLoopSeconds, -1);
        int loopMilli = read(pLoopMs, -1);

        int pauseSec = read(pPauseSeconds, -1);
        int pauseMilli = read(pPauseMs, -1);

        int loops = read(pLoopCount, -1);
        int interval = read(pInterval, -1);
        int touch = read(pTouch, -1);

        long loopDuration = loopSec * 1000L + loopMilli;
        long pauseDuration = pauseSec * 1000L + pauseMilli;

        if (h < 0 || h > 23 ||
                m < 0 || m > 59 ||
                s < 0 || s > 59 ||
                ms < 0 || ms > 999 ||
                loopSec < 0 ||
                loopMilli < 0 || loopMilli > 999 ||
                loopDuration <= 0 ||
                pauseSec < 0 ||
                pauseMilli < 0 || pauseMilli > 999 ||
                loops < 0 ||
                interval < 1 || touch < 1) {

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

        scheduleClick(
                h, m, s, ms,
                loopDuration,
                pauseDuration,
                loops,
                interval,
                touch);

        expanded = false;
        panelScroll.setVisibility(View.GONE);
        expandButton.setText("+");

        panelParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        try {
            windowManager.updateViewLayout(
                    floatingPanel, panelParams);
        } catch (Exception ignored) {}
    }

    // =====================================================
    // عبور لمس از روی پنجره‌ها
    // =====================================================

    private void makeOverlaysPassThrough() {
        if (overlaysPassThrough) return;

        try {
            if (markerAttached && marker != null) {
                markerParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        marker, markerParams);
            }

            if (panelAttached && floatingPanel != null) {
                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        floatingPanel, panelParams);
            }

            overlaysPassThrough = true;

        } catch (Exception ignored) {}
    }

    private void restoreOverlayTouch() {
        if (!overlaysPassThrough) return;

        try {
            if (markerAttached && marker != null) {
                markerParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                windowManager.updateViewLayout(
                        marker, markerParams);
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

    // =====================================================
    // تست لمس
    // =====================================================

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
                new GestureDescription.StrokeDescription(
                        path, 0, 60));

        boolean accepted = dispatchGesture(
                builder.build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(
                            GestureDescription gesture) {
                        super.onCompleted(gesture);
                        restoreOverlayTouch();
                    }

                    @Override
                    public void onCancelled(
                            GestureDescription gesture) {
                        super.onCancelled(gesture);
                        restoreOverlayTouch();
                    }
                },
                null);

        if (!accepted) {
            restoreOverlayTouch();
        }
    }

    // =====================================================
    // ساعت و شمارش معکوس
    // =====================================================

    private void startClockUpdater() {
        clockUpdater = new Runnable() {
            @Override
            public void run() {
                if (clockText != null) {
                    clockText.setText(
                            formatTime(System.currentTimeMillis()));
                }

                if (countdownText != null) {
                    updateCountdownText();
                }

                handler.postDelayed(this, 50);
            }
        };

        handler.post(clockUpdater);
    }

    private String formatRemaining(long millis) {
        millis = Math.max(0, millis);

        long hours = millis / 3600000;
        long minutes = (millis % 3600000) / 60000;
        long seconds = (millis % 60000) / 1000;
        long ms = millis % 1000;

        return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                hours, minutes, seconds, ms);
    }

    private void updateCountdownText() {
        if (calibrationMode) {
            countdownText.setText("کالیبراسیون");
            return;
        }

        if (!scheduled) {
            countdownText.setText(displayStatus);
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now < targetElapsed) {
            countdownText.setText(
                    "شروع: " + formatRemaining(targetElapsed - now));
            return;
        }

        if (waitingForPause) {
            long remaining = Math.max(
                    0, nextLoopStartElapsed - now);

            countdownText.setText(
                    "مکث: " + formatRemaining(remaining));
            return;
        }

        if (loopActive) {
            long remaining = Math.max(
                    0, loopDeadlineElapsed - now);

            countdownText.setText(
                    "لوپ " + currentLoop + " | " +
                    formatRemaining(remaining));
            return;
        }

        countdownText.setText("در حال آماده‌سازی");
    }

    // =====================================================
    // شروع زمان‌بندی
    // =====================================================

    public void scheduleClick(
            int hour, int minute, int second,
            int millisecond, long loopDuration,
            long pauseDuration, int loops,
            int interval, int touchDuration) {

        stopClick();

        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        if (target.getTimeInMillis() <=
                System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        targetWallTime = target.getTimeInMillis();

        long wallNow = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();

        long delay = Math.max(0, targetWallTime - wallNow);

        targetElapsed = elapsedNow + delay;

        loopDurationMs = loopDuration;
        pauseDurationMs = pauseDuration;
        totalLoops = loops;

        intervalMs = interval;
        touchDurationMs = touchDuration;

        currentLoop = 0;
        totalClicks = 0;

        loopActive = false;
        waitingForPause = false;
        gestureInProgress = false;

        loopHistory.clear();
        updateHistoryText();

        calibrationMode = false;
        calibrationListener = null;

        scheduled = true;

        createClickTask();

        notifyRunState(0, "در انتظار زمان شروع");

        if (loopStartTask != null) {
            handler.removeCallbacks(loopStartTask);
        }

        loopStartTask = this::beginLoop;

        handler.postDelayed(loopStartTask, delay);
    }

    // =====================================================
    // شروع هر لوپ
    // =====================================================

    private void beginLoop() {
        if (!scheduled || calibrationMode) return;

        waitingForPause = false;

        currentLoop++;

        loopActive = true;
        loopClicks = 0;

        loopStartElapsed = SystemClock.elapsedRealtime();
        loopDeadlineElapsed =
                loopStartElapsed + loopDurationMs;

        loopStartWall = System.currentTimeMillis();

        nextClickDeadline = loopStartElapsed;

        lastClickStart = 0;
        firstClickOffsetMs = 0;

        notifyRunState(
                1,
                "لوپ " + currentLoop + " شروع شد");

        handler.removeCallbacks(clickTask);
        handler.post(clickTask);
    }

    // =====================================================
    // پایان پنجره هر لوپ
    // =====================================================

    private void finishCurrentLoop(String reason) {
        if (!loopActive) return;

        loopActive = false;

        loopEndWall = System.currentTimeMillis();

        long actualDuration =
                SystemClock.elapsedRealtime() - loopStartElapsed;

        String report = String.format(
                Locale.US,
                "لوپ %d | شروع %s | پایان %s | مدت %s | کلیک %d | %s",
                currentLoop,
                formatTime(loopStartWall),
                formatTime(loopEndWall),
                formatRemaining(actualDuration),
                loopClicks,
                reason);

        loopHistory.add(0, report);

        while (loopHistory.size() > 10) {
            loopHistory.remove(loopHistory.size() - 1);
        }

        updateHistoryText();

        handler.removeCallbacks(clickTask);

        if (!scheduled) return;

        if (totalLoops > 0 && currentLoop >= totalLoops) {
            scheduled = false;
            waitingForPause = false;
            restoreOverlayTouch();

            notifyRunState(
                    2,
                    "تمام لوپ‌ها اجرا شدند");

            return;
        }

        waitingForPause = true;

        notifyRunState(
                0,
                "پایان لوپ " + currentLoop +
                        "؛ مکث قبل از لوپ بعدی");

        nextLoopStartElapsed =
                SystemClock.elapsedRealtime() + pauseDurationMs;

        if (loopStartTask != null) {
            handler.removeCallbacks(loopStartTask);
        }

        loopStartTask = this::beginLoop;

        handler.postDelayed(
                loopStartTask,
                pauseDurationMs);
    }

    private long nextLoopStartElapsed;

    private void updateHistoryText() {
        if (historyText == null) return;

        if (loopHistory.isEmpty()) {
            historyText.setText("هنوز گزارشی ثبت نشده است.");
            return;
        }

        StringBuilder builder = new StringBuilder();

        for (String item : loopHistory) {
            builder.append(item).append("\n\n");
        }

        historyText.setText(builder.toString());
    }

    // =====================================================
    // زمان‌بندی کلیک‌ها
    // =====================================================

    private void createClickTask() {
        clickTask = new Runnable() {
            @Override
            public void run() {
                if (!scheduled || calibrationMode ||
                        !loopActive || gestureInProgress) {
                    return;
                }

                long now = SystemClock.elapsedRealtime();

                if (now >= loopDeadlineElapsed) {
                    finishCurrentLoop("پایان زمان لوپ");
                    return;
                }

                if (now < nextClickDeadline) {
                    handler.postDelayed(
                            this,
                            Math.max(1, nextClickDeadline - now));
                    return;
                }

                updateClickPosition();
                makeOverlaysPassThrough();

                gestureInProgress = true;

                long clickStart = SystemClock.elapsedRealtime();

                if (loopClicks == 0) {
                    firstClickOffsetMs =
                            clickStart - loopStartElapsed;
                }

                lastClickStart = clickStart;

                loopClicks++;
                totalClicks++;

                nextClickDeadline += intervalMs;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.Builder builder =
                        new GestureDescription.Builder();

                builder.addStroke(
                        new GestureDescription.StrokeDescription(
                                path, 0, touchDurationMs));

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

                                if (!loopActive) {
                                    restoreOverlayTouch();
                                    return;
                                }

                                long current =
                                        SystemClock.elapsedRealtime();

                                if (current >= loopDeadlineElapsed) {
                                    finishCurrentLoop(
                                            "پایان زمان لوپ");
                                    return;
                                }

                                if (nextClickDeadline < current) {
                                    nextClickDeadline = current;
                                }

                                handler.postDelayed(
                                        clickTask,
                                        Math.max(
                                                0,
                                                nextClickDeadline - current));
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription gesture) {
                                super.onCancelled(gesture);

                                gestureInProgress = false;

                                if (scheduled && loopActive) {
                                    finishCurrentLoop(
                                            "لمس لغو شد");
                                    stopRemainingLoops(
                                            "اجرای لمس لغو شد");
                                }

                                restoreOverlayTouch();
                            }
                        },
                        null);

                if (!accepted) {
                    gestureInProgress = false;
                    finishCurrentLoop("فرمان لمس پذیرفته نشد");
                    stopRemainingLoops(
                            "فرمان لمس پذیرفته نشد");
                }
            }
        };
    }

    private void stopRemainingLoops(String message) {
        scheduled = false;
        waitingForPause = false;
        loopActive = false;

        if (loopStartTask != null) {
            handler.removeCallbacks(loopStartTask);
        }

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        restoreOverlayTouch();
        notifyRunState(3, message);
    }

    // =====================================================
    // کالیبراسیون
    // =====================================================

    public void startCalibration(
            int requestedInterval,
            int testClicks,
            CalibrationListener listener) {

        stopClick();

        if (requestedInterval < 1 || testClicks < 2) {
            if (listener != null) {
                listener.onFailed(
                        "تنظیمات کالیبراسیون معتبر نیست");
            }
            return;
        }

        calibrationListener = listener;
        calibrationMode = true;
        scheduled = true;

        intervalMs = requestedInterval;
        touchDurationMs = 1;

        calibrationTotalClicks = testClicks;
        calibrationRemaining = testClicks;
        calibrationClicks = 0;

        calibrationIntervals = new long[testClicks];

        totalIntervalMs = 0;
        maxIntervalMs = 0;
        intervalSamples = 0;
        averageIntervalMs = 0;

        lastClickStart = 0;
        firstClickOffsetMs = 0;

        loopActive = false;
        waitingForPause = false;
        gestureInProgress = false;

        notifyRunState(1, "در حال کالیبراسیون");

        createCalibrationTask();
        handler.post(clickTask);
    }

    private void createCalibrationTask() {
        clickTask = new Runnable() {
            @Override
            public void run() {
                if (!scheduled || !calibrationMode ||
                        gestureInProgress) {
                    return;
                }

                if (calibrationRemaining <= 0) {
                    finishCalibration();
                    return;
                }

                updateClickPosition();
                makeOverlaysPassThrough();

                gestureInProgress = true;

                long clickStart = SystemClock.elapsedRealtime();

                if (calibrationClicks == 0) {
                    firstClickOffsetMs = 0;
                } else {
                    long actualInterval =
                            clickStart - lastClickStart;

                    totalIntervalMs += actualInterval;
                    intervalSamples++;

                    if (actualInterval > maxIntervalMs) {
                        maxIntervalMs = actualInterval;
                    }

                    if (intervalSamples <= calibrationIntervals.length) {
                        calibrationIntervals[
                                intervalSamples - 1] = actualInterval;
                    }

                    averageIntervalMs =
                            (double) totalIntervalMs /
                                    intervalSamples;
                }

                lastClickStart = clickStart;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.Builder builder =
                        new GestureDescription.Builder();

                builder.addStroke(
                        new GestureDescription.StrokeDescription(
                                path, 0, touchDurationMs));

                boolean accepted = dispatchGesture(
                        builder.build(),
                        new GestureResultCallback() {
                            @Override
                            public void onCompleted(
                                    GestureDescription gesture) {
                                super.onCompleted(gesture);

                                gestureInProgress = false;

                                if (!scheduled || !calibrationMode) {
                                    restoreOverlayTouch();
                                    return;
                                }

                                calibrationRemaining--;
                                calibrationClicks++;

                                if (calibrationListener != null) {
                                    calibrationListener.onProgress(
                                            calibrationClicks,
                                            calibrationTotalClicks);
                                }

                                if (calibrationRemaining <= 0) {
                                    finishCalibration();
                                    return;
                                }

                                handler.postDelayed(
                                        clickTask,
                                        intervalMs);
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription gesture) {
                                super.onCancelled(gesture);

                                gestureInProgress = false;

                                failCalibration(
                                        "لمس آزمایشی لغو شد");
                            }
                        },
                        null);

                if (!accepted) {
                    gestureInProgress = false;
                    failCalibration(
                            "فرمان لمس آزمایشی پذیرفته نشد");
                }
            }
        };
    }

    private void finishCalibration() {
        scheduled = false;
        calibrationMode = false;
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
            long[] samples = new long[intervalSamples];

            System.arraycopy(
                    calibrationIntervals,
                    0,
                    samples,
                    0,
                    intervalSamples);

            java.util.Arrays.sort(samples);

            int index = (int) Math.ceil(
                    samples.length * 0.90) - 1;

            index = Math.max(
                    0, Math.min(index, samples.length - 1));

            suggested = Math.max(
                    intervalMs, samples[index] + 1);
        }

        double actualCps =
                average > 0 ? 1000.0 / average : 0;

        CalibrationListener listener = calibrationListener;
        calibrationListener = null;

        notifyRunState(2, "کالیبراسیون تمام شد");

        if (listener != null) {
            listener.onComplete(
                    average,
                    maxIntervalMs,
                    suggested,
                    actualCps,
                    firstClickOffsetMs,
                    calibrationClicks);
        }
    }

    private void failCalibration(String message) {
        scheduled = false;
        calibrationMode = false;
        gestureInProgress = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
        }

        restoreOverlayTouch();

        CalibrationListener listener = calibrationListener;
        calibrationListener = null;

        notifyRunState(3, message);

        if (listener != null) {
            listener.onFailed(message);
        }
    }

    // =====================================================
    // توقف دستی
    // =====================================================

    public void stopClick() {
        boolean wasScheduled = scheduled;
        boolean wasCalibration = calibrationMode;

        scheduled = false;
        calibrationMode = false;
        waitingForPause = false;

        if (clickTask != null) {
            handler.removeCallbacks(clickTask);
            clickTask = null;
        }

        if (loopStartTask != null) {
            handler.removeCallbacks(loopStartTask);
            loopStartTask = null;
        }

        if (loopActive) {
            finishCurrentLoop("توقف دستی");
        }

        loopActive = false;
        gestureInProgress = false;

        restoreOverlayTouch();

        if (wasCalibration && calibrationListener != null) {
            CalibrationListener listener = calibrationListener;
            calibrationListener = null;
            listener.onFailed("کالیبراسیون متوقف شد");
        }

        if (wasScheduled && !wasCalibration) {
            notifyRunState(3, "توقف دستی");
        } else if (!wasScheduled) {
            notifyRunState(0, "آماده");
        }
    }

    // =====================================================
    // بستن برنامه
    // =====================================================

    private void closeApplication() {
        if (closingApplication) return;

        closingApplication = true;

        stopClick();

        if (clockUpdater != null) {
            handler.removeCallbacks(clockUpdater);
            clockUpdater = null;
        }

        try {
            if (markerAttached && marker != null) {
                windowManager.removeView(marker);
                markerAttached = false;
                marker = null;
            }
        } catch (Exception ignored) {}

        try {
            if (panelAttached && floatingPanel != null) {
                windowManager.removeView(floatingPanel);
                panelAttached = false;
                floatingPanel = null;
            }
        } catch (Exception ignored) {}

        runStateListener = null;
        calibrationListener = null;

        instance = null;

        disableSelf();
        stopSelf();

        handler.postDelayed(() ->
                android.os.Process.killProcess(
                        android.os.Process.myPid()), 700);
    }

    // =====================================================
    // Accessibility
    // =====================================================

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
            clockUpdater = null;
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

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }
}