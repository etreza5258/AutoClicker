package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClickService extends AccessibilityService {

    public interface RunStateListener {
        void onRunStateChanged(String status);
        void onReportsChanged(List<String> reports);
        void onCalibrationFinished(String result);
    }

    public static ClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;

    private View markerView;
    private View panelView;
    private WindowManager.LayoutParams markerParams;
    private WindowManager.LayoutParams panelParams;

    private TextView clockText;
    private TextView countdownText;
    private TextView panelStatusText;
    private TextView panelReportText;
    private LinearLayout expandedContent;

    private boolean panelExpanded = false;
    private boolean markerAdded = false;
    private boolean panelAdded = false;

    private int markerX = 350;
    private int markerY = 700;

    private RunStateListener listener;

    private boolean running = false;
    private boolean waitingForStart = false;
    private boolean calibrationRunning = false;

    private int generation = 0;

    private long loopDurationNanos;
    private long pauseDurationNanos;
    private long intervalNanos;
    private long touchDurationMillis;
    private long configuredLoopCount;
    private boolean lastLoopSingleClick;

    private long completedLoops = 0;
    private long currentLoopClicks = 0;

    private long currentLoopStartElapsed;
    private long currentLoopDeadlineElapsed;
    private long currentLoopStartWall;

    private long nextClickNumber = 0;

    private boolean currentLoopIsLastSingle = false;
    private boolean gestureInProgress = false;

    private final ArrayList<String> reports = new ArrayList<>();

    private long targetStartElapsed;
    private long targetStartWall;

    private Runnable pendingClickRunnable;
    private Runnable clockRunnable;

    private long calibrationIntervalMillis;
    private long calibrationTouchMillis;
    private int calibrationClicks;
    private long calibrationStartElapsed;
    private long calibrationLastClickElapsed;
    private long calibrationTotalInterval;
    private long calibrationMinInterval;
    private long calibrationMaxInterval;

    private EditText pStartHour, pStartMinute, pStartSecond, pStartMillis;
    private EditText pLoopSeconds, pLoopMillis;
    private EditText pPauseSeconds, pPauseMillis;
    private EditText pLoopCount, pInterval, pTouch;
    private Spinner pLastLoopSpinner;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createMarker();
        createFloatingPanel();
        startClockUpdater();

        notifyState("آماده");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // این سرویس برای اجرای Gesture استفاده می‌شود.
    }

    @Override
    public void onInterrupt() {
        stopRun("توقف سرویس");
    }

    @Override
    public void onDestroy() {
        stopRun("توقف سرویس");
        handler.removeCallbacksAndMessages(null);

        removeMarker();
        removePanel();

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    public void setRunStateListener(RunStateListener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onRunStateChanged(getCurrentStatus());
            listener.onReportsChanged(new ArrayList<>(reports));
        }
    }

    public String getCurrentStatus() {
        if (calibrationRunning) return "در حال کالیبراسیون";
        if (waitingForStart) return "در انتظار زمان شروع";
        if (running) return "در حال اجرای چرخه " + (completedLoops + 1);
        return "آماده";
    }

    public List<String> getReports() {
        return new ArrayList<>(reports);
    }

    // ---------------------------------------------------------
    // زمان‌بندی اصلی
    // ---------------------------------------------------------

    public void scheduleLoops(
            int hour,
            int minute,
            int second,
            int millisecond,
            long loopSeconds,
            long loopMillis,
            long pauseSeconds,
            long pauseMillis,
            long loopCount,
            long intervalMillis,
            long touchMillis,
            boolean singleClickLastLoop
    ) {
        if (Build.VERSION.SDK_INT < 24) {
            showToast("نسخه اندروید برای اجرای Gesture مناسب نیست.");
            return;
        }

        if (loopSeconds < 0 || loopMillis < 0 || loopMillis > 999 ||
                pauseSeconds < 0 || pauseMillis < 0 || pauseMillis > 999 ||
                loopCount < 0 || intervalMillis < 1 || touchMillis < 1) {
            showToast("تنظیمات واردشده معتبر نیست.");
            return;
        }

        long durationMillis = loopSeconds * 1000L + loopMillis;
        long pauseMillisTotal = pauseSeconds * 1000L + pauseMillis;

        if (durationMillis <= 0) {
            showToast("مدت چرخه باید بیشتر از صفر باشد.");
            return;
        }

        if (calibrationRunning) {
            stopCalibration();
        }

        stopRun("شروع اجرای جدید");

        loopDurationNanos = durationMillis * 1_000_000L;
        pauseDurationNanos = pauseMillisTotal * 1_000_000L;
        intervalNanos = intervalMillis * 1_000_000L;
        touchDurationMillis = touchMillis;

        configuredLoopCount = loopCount;
        lastLoopSingleClick = singleClickLastLoop;

        completedLoops = 0;
        currentLoopClicks = 0;
        reports.clear();
        notifyReports();

        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        long nowWall = System.currentTimeMillis();

        if (target.getTimeInMillis() <= nowWall) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        targetStartWall = target.getTimeInMillis();

        long delayMillis = targetStartWall - nowWall;
        targetStartElapsed = SystemClock.elapsedRealtime() + delayMillis;

        generation++;
        final int thisGeneration = generation;

        running = true;
        waitingForStart = true;

        notifyState("در انتظار شروع در " + formatWall(targetStartWall));
        updatePanelStatus();

        handler.postDelayed(() -> {
            if (thisGeneration != generation || !running) return;

            waitingForStart = false;
            beginLoop(thisGeneration);
        }, delayMillis);
    }

    private void beginLoop(int runGeneration) {
        if (!running || runGeneration != generation) return;

        if (configuredLoopCount > 0 && completedLoops >= configuredLoopCount) {
            finishAll("پایان عادی");
            return;
        }

        currentLoopClicks = 0;
        nextClickNumber = 0;
        gestureInProgress = false;

        currentLoopStartElapsed = SystemClock.elapsedRealtime();
        currentLoopStartWall = System.currentTimeMillis();
        currentLoopDeadlineElapsed = currentLoopStartElapsed +
                (loopDurationNanos / 1_000_000L);

        currentLoopIsLastSingle =
                lastLoopSingleClick &&
                configuredLoopCount > 0 &&
                completedLoops + 1 == configuredLoopCount;

        String type = currentLoopIsLastSingle ? "؛ چرخه آخر: فقط یک کلیک" : "";

        notifyState("چرخه " + (completedLoops + 1) + " شروع شد" + type);
        updatePanelStatus();

        if (currentLoopIsLastSingle) {
            performSingleLastClick(runGeneration);
        } else {
            scheduleNextClick(runGeneration, currentLoopStartElapsed);
        }
    }

    private void scheduleNextClick(int runGeneration, long plannedElapsed) {
        if (!running || runGeneration != generation) return;

        long now = SystemClock.elapsedRealtime();

        if (now >= currentLoopDeadlineElapsed) {
            finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            return;
        }

        long delay = Math.max(0, plannedElapsed - now);

        pendingClickRunnable = () -> {
            if (!running || runGeneration != generation) return;

            long current = SystemClock.elapsedRealtime();

            if (current >= currentLoopDeadlineElapsed) {
                finishCurrentLoop(runGeneration, "پایان زمان چرخه");
                return;
            }

            performRegularClick(runGeneration);
        };

        handler.postDelayed(pendingClickRunnable, delay);
    }

    private void performRegularClick(int runGeneration) {
        if (!running || runGeneration != generation || gestureInProgress) return;

        if (SystemClock.elapsedRealtime() >= currentLoopDeadlineElapsed) {
            finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            return;
        }

        gestureInProgress = true;

        boolean accepted = dispatchTouch(touchDurationMillis, new GestureResult() {
            @Override
            public void onFinished(boolean completed) {
                if (runGeneration != generation || !running) return;

                gestureInProgress = false;

                if (completed) {
                    currentLoopClicks++;
                }

                long now = SystemClock.elapsedRealtime();

                if (now >= currentLoopDeadlineElapsed) {
                    finishCurrentLoop(runGeneration, "پایان زمان چرخه");
                    return;
                }

                nextClickNumber++;

                long nextPlanned = currentLoopStartElapsed +
                        (nextClickNumber * intervalNanos / 1_000_000L);

                if (nextPlanned < now) {
                    nextPlanned = now;
                }

                scheduleNextClick(runGeneration, nextPlanned);
            }
        });

        if (!accepted) {
            gestureInProgress = false;
            finishCurrentLoop(runGeneration, "خطا در ارسال لمس");
        }
    }

    private void performSingleLastClick(int runGeneration) {
        if (!running || runGeneration != generation) return;

        gestureInProgress = true;

        boolean accepted = dispatchTouch(touchDurationMillis, new GestureResult() {
            @Override
            public void onFinished(boolean completed) {
                if (runGeneration != generation || !running) return;

                gestureInProgress = false;

                if (completed) {
                    currentLoopClicks++;
                    finishCurrentLoop(runGeneration, "چرخه آخر؛ یک کلیک انجام شد");
                } else {
                    finishCurrentLoop(runGeneration, "خطا در کلیک چرخه آخر");
                }
            }
        });

        if (!accepted) {
            gestureInProgress = false;
            finishCurrentLoop(runGeneration, "خطا در ارسال کلیک چرخه آخر");
        }
    }

    private void finishCurrentLoop(int runGeneration, String reason) {
        if (!running || runGeneration != generation) return;

        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }

        long endElapsed = SystemClock.elapsedRealtime();
        long endWall = System.currentTimeMillis();

        long actualDuration = Math.max(0, endElapsed - currentLoopStartElapsed);

        String report =
                "چرخه " + (completedLoops + 1) + "\n" +
                "شروع: " + formatWall(currentLoopStartWall) + "\n" +
                "پایان: " + formatWall(endWall) + "\n" +
                "مدت واقعی: " + formatDuration(actualDuration) + "\n" +
                "تعداد کلیک تکمیل‌شده: " + currentLoopClicks + "\n" +
                "وضعیت: " + reason;

        addReport(report);

        completedLoops++;
        currentLoopClicks = 0;

        if (currentLoopIsLastSingle) {
            finishAll("پایان اجرای چرخه آخر");
            return;
        }

        if (configuredLoopCount > 0 && completedLoops >= configuredLoopCount) {
            finishAll("تمام چرخه‌ها تکمیل شدند");
            return;
        }

        notifyState("چرخه " + completedLoops + " تمام شد؛ مکث بین چرخه‌ها");
        updatePanelStatus();

        long pauseMillis = pauseDurationNanos / 1_000_000L;

        if (pauseMillis <= 0) {
            beginLoop(runGeneration);
        } else {
            handler.postDelayed(() -> {
                if (runGeneration == generation && running) {
                    beginLoop(runGeneration);
                }
            }, pauseMillis);
        }
    }

    private void finishAll(String reason) {
        running = false;
        waitingForStart = false;
        gestureInProgress = false;

        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }

        notifyState(reason);
        updatePanelStatus();
    }

    public void stopRun(String reason) {
        if (!running && !waitingForStart) {
            notifyState(reason);
            return;
        }

        generation++;

        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }

        if (running && !waitingForStart && currentLoopStartWall > 0) {
            long endElapsed = SystemClock.elapsedRealtime();
            long endWall = System.currentTimeMillis();
            long duration = Math.max(0, endElapsed - currentLoopStartElapsed);

            String report =
                    "چرخه " + (completedLoops + 1) + "\n" +
                    "شروع: " + formatWall(currentLoopStartWall) + "\n" +
                    "پایان: " + formatWall(endWall) + "\n" +
                    "مدت واقعی: " + formatDuration(duration) + "\n" +
                    "تعداد کلیک تکمیل‌شده: " + currentLoopClicks + "\n" +
                    "وضعیت: " + reason;

            addReport(report);
        }

        running = false;
        waitingForStart = false;
        gestureInProgress = false;
        currentLoopClicks = 0;

        notifyState(reason);
        updatePanelStatus();
    }

    // ---------------------------------------------------------
    // اجرای Gesture و تست نشانگر
    // ---------------------------------------------------------

    private interface GestureResult {
        void onFinished(boolean completed);
    }

    private boolean dispatchTouch(long durationMillis, GestureResult result) {
        if (Build.VERSION.SDK_INT < 24) return false;

        Path path = new Path();
        path.moveTo(markerX, markerY);

        long duration = Math.max(1, durationMillis);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);

        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();

        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (result != null) result.onFinished(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (result != null) result.onFinished(false);
            }
        }, handler);
    }

    public void testClickCurrentPosition() {
        if (running || calibrationRunning) {
            showToast("هنگام اجرای برنامه امکان تست جداگانه وجود ندارد.");
            return;
        }

        boolean accepted = dispatchTouch(50, new GestureResult() {
            @Override
            public void onFinished(boolean completed) {
                showToast(completed ? "کلیک آزمایشی انجام شد." : "کلیک آزمایشی ناموفق بود.");
            }
        });

        if (!accepted) {
            showToast("ارسال کلیک آزمایشی انجام نشد.");
        }
    }

    // ---------------------------------------------------------
    // کالیبراسیون مستقل
    // ---------------------------------------------------------

    public void startCalibration(long intervalMillis, long touchMillis) {
        if (running || waitingForStart) {
            showToast("ابتدا اجرای لوپی را متوقف کن.");
            return;
        }

        if (calibrationRunning) {
            stopCalibration();
        }

        calibrationIntervalMillis = Math.max(1, intervalMillis);
        calibrationTouchMillis = Math.max(1, touchMillis);

        calibrationClicks = 0;
        calibrationTotalInterval = 0;
        calibrationMinInterval = Long.MAX_VALUE;
        calibrationMaxInterval = 0;
        calibrationLastClickElapsed = 0;
        calibrationStartElapsed = SystemClock.elapsedRealtime();

        calibrationRunning = true;
        notifyState("شروع کالیبراسیون ۳۰ کلیک");

        performCalibrationClick(generation);
    }

    private void performCalibrationClick(int runGeneration) {
        if (!calibrationRunning) return;

        boolean accepted = dispatchTouch(calibrationTouchMillis, new GestureResult() {
            @Override
            public void onFinished(boolean completed) {
                if (!calibrationRunning) return;

                if (!completed) {
                    stopCalibration();
                    notifyCalibration("کالیبراسیون ناموفق بود.");
                    return;
                }

                long now = SystemClock.elapsedRealtime();

                if (calibrationLastClickElapsed > 0) {
                    long delta = now - calibrationLastClickElapsed;
                    calibrationTotalInterval += delta;
                    calibrationMinInterval = Math.min(calibrationMinInterval, delta);
                    calibrationMaxInterval = Math.max(calibrationMaxInterval, delta);
                }

                calibrationLastClickElapsed = now;
                calibrationClicks++;

                if (calibrationClicks >= 30) {
                    long elapsed = now - calibrationStartElapsed;
                    long average = calibrationClicks > 1
                            ? calibrationTotalInterval / (calibrationClicks - 1)
                            : 0;

                    calibrationRunning = false;

                    String result =
                            "۳۰ کلیک تکمیل شد؛ میانگین فاصله: " + average +
                            " ms؛ کمترین: " + calibrationMinInterval +
                            " ms؛ بیشترین: " + calibrationMaxInterval +
                            " ms؛ مدت کل: " + elapsed + " ms";

                    notifyCalibration(result);
                    notifyState("کالیبراسیون تمام شد");
                    return;
                }

                handler.postDelayed(
                        () -> performCalibrationClick(generation),
                        calibrationIntervalMillis
                );
            }
        });

        if (!accepted) {
            stopCalibration();
            notifyCalibration("ارسال Gesture برای کالیبراسیون ناموفق بود.");
        }
    }

    private void stopCalibration() {
        calibrationRunning = false;
        handler.removeCallbacksAndMessages(null);
        startClockUpdater();
    }

    // ---------------------------------------------------------
    // نشانگر آبی قابل جابه‌جایی
    // ---------------------------------------------------------

    private void createMarker() {
        if (markerAdded || windowManager == null) return;

        TextView marker = new TextView(this);
        marker.setText("●");
        marker.setTextSize(34);
        marker.setTextColor(Color.rgb(0, 125, 255));
        marker.setGravity(Gravity.CENTER);
        marker.setShadowLayer(5, 0, 0, Color.WHITE);

        markerView = marker;

        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

        markerParams = new WindowManager.LayoutParams(
                dp(55),
                dp(55),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        markerParams.x = markerX;
        markerParams.y = markerY;

        markerView.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = markerParams.x;
                        startY = markerParams.y;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;

                        if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                            moved = true;
                        }

                        markerParams.x = startX + (int) dx;
                        markerParams.y = startY + (int) dy;

                        markerX = markerParams.x + dp(27);
                        markerY = markerParams.y + dp(27);

                        try {
                            windowManager.updateViewLayout(markerView, markerParams);
                        } catch (Exception ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        return true;
                }
                return true;
            }
        });

        try {
            windowManager.addView(markerView, markerParams);
            markerAdded = true;
        } catch (Exception e) {
            markerAdded = false;
        }
    }

    private void removeMarker() {
        if (markerAdded && markerView != null && windowManager != null) {
            try {
                windowManager.removeView(markerView);
            } catch (Exception ignored) {
            }
        }
        markerAdded = false;
    }

    // ---------------------------------------------------------
    // پنل شناور
    // ---------------------------------------------------------

    private void createFloatingPanel() {
        if (panelAdded || windowManager == null) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(9), dp(7), dp(9), dp(7));
        root.setBackground(roundBackground(Color.rgb(25, 34, 48), dp(12)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        clockText = new TextView(this);
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(14);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(clockText, new LinearLayout.LayoutParams(0, -2, 1));

        Button expandButton = smallButton("باز");
        header.addView(expandButton);

        Button stopButton = smallButton("■");
        header.addView(stopButton);

        root.addView(header);

        countdownText = new TextView(this);
        countdownText.setTextColor(Color.rgb(170, 220, 255));
        countdownText.setTextSize(12);
        countdownText.setPadding(0, dp(3), 0, 0);
        root.addView(countdownText);

        panelStatusText = new TextView(this);
        panelStatusText.setTextColor(Color.WHITE);
        panelStatusText.setTextSize(11);
        panelStatusText.setPadding(0, dp(3), 0, 0);
        root.addView(panelStatusText);

        expandedContent = new LinearLayout(this);
        expandedContent.setOrientation(LinearLayout.VERTICAL);
        expandedContent.setVisibility(View.GONE);
        root.addView(expandedContent);

        panelReportText = new TextView(this);
        panelReportText.setTextColor(Color.WHITE);
        panelReportText.setTextSize(10);
        panelReportText.setPadding(0, dp(6), 0, dp(3));
        expandedContent.addView(panelReportText);

        buildFloatingSettings(expandedContent);

        panelView = root;

        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

        panelParams = new WindowManager.LayoutParams(
                dp(230),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity = Gravity.TOP | Gravity.RIGHT;
        panelParams.x = dp(8);
        panelParams.y = dp(80);

        expandButton.setOnClickListener(v -> {
            panelExpanded = !panelExpanded;
            expandedContent.setVisibility(panelExpanded ? View.VISIBLE : View.GONE);
            expandButton.setText(panelExpanded ? "بستن" : "باز");

            if (panelExpanded) {
                panelParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            } else {
                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            }

            try {
                windowManager.updateViewLayout(panelView, panelParams);
            } catch (Exception ignored) {
            }
        });

        stopButton.setOnClickListener(v -> stopRun("توقف دستی"));

        try {
            windowManager.addView(panelView, panelParams);
            panelAdded = true;
        } catch (Exception e) {
            panelAdded = false;
        }

        updatePanelStatus();
    }

    private void buildFloatingSettings(LinearLayout parent) {
        TextView heading = new TextView(this);
        heading.setText("تنظیمات اجرای لوپی");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(8), 0, dp(5));
        parent.addView(heading);

        pStartHour = panelField(parent, "ساعت شروع", "00");
        pStartMinute = panelField(parent, "دقیقه شروع", "00");
        pStartSecond = panelField(parent, "ثانیه شروع", "00");
        pStartMillis = panelField(parent, "میلی‌ثانیه شروع", "000");

        pLoopSeconds = panelField(parent, "مدت چرخه؛ ثانیه", "5");
        pLoopMillis = panelField(parent, "مدت چرخه؛ میلی‌ثانیه", "0");

        pPauseSeconds = panelField(parent, "مکث؛ ثانیه", "1");
        pPauseMillis = panelField(parent, "مکث؛ میلی‌ثانیه", "0");

        pLoopCount = panelField(parent, "تعداد چرخه؛ صفر = نامحدود", "10");
        pInterval = panelField(parent, "فاصله کلیک؛ ms", "200");
        pTouch = panelField(parent, "مدت لمس؛ ms", "20");

        pLastLoopSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "چرخه آخر: اجرای عادی",
                        "چرخه آخر: فقط یک کلیک"
                }
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pLastLoopSpinner.setAdapter(adapter);
        parent.addView(pLastLoopSpinner);

        Button start = smallButton("شروع با تنظیمات پنل");
        parent.addView(start);
        start.setOnClickListener(v -> startFromFloatingPanel());

        Button test = smallButton("تست کلیک نشانگر");
        parent.addView(test);
        test.setOnClickListener(v -> testClickCurrentPosition());
    }

    private EditText panelField(LinearLayout parent, String label, String value) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(10);
        title.setPadding(0, dp(4), 0, 0);
        parent.addView(title);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setTextSize(13);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(Color.GRAY);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setPadding(dp(5), 0, dp(5), 0);
        edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.GRAY));
        parent.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        ));
        return edit;
    }

    private void startFromFloatingPanel() {
        if (pStartHour == null) return;

        int h = (int) readNumber(pStartHour, 0);
        int m = (int) readNumber(pStartMinute, 0);
        int s = (int) readNumber(pStartSecond, 0);
        int ms = (int) readNumber(pStartMillis, 0);

        long loopSec = readNumber(pLoopSeconds, 5);
        long loopMs = readNumber(pLoopMillis, 0);
        long pauseSec = readNumber(pPauseSeconds, 1);
        long pauseMs = readNumber(pPauseMillis, 0);
        long count = readNumber(pLoopCount, 10);
        long interval = readNumber(pInterval, 200);
        long touch = readNumber(pTouch, 20);

        boolean singleLast = pLastLoopSpinner.getSelectedItemPosition() == 1;

        if (h < 0 || h > 23 || m < 0 || m > 59 ||
                s < 0 || s > 59 || ms < 0 || ms > 999 ||
                loopSec < 0 || loopMs < 0 || loopMs > 999 ||
                pauseSec < 0 || pauseMs < 0 || pauseMs > 999 ||
                count < 0 || interval < 1 || touch < 1 ||
                loopSec * 1000L + loopMs <= 0) {
            showToast("یکی از مقادیر تنظیمات معتبر نیست.");
            return;
        }

        scheduleLoops(
                h, m, s, ms,
                loopSec, loopMs,
                pauseSec, pauseMs,
                count,
                interval,
                touch,
                singleLast
        );
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(10);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(55, 85, 120))
        );
        return button;
    }

    private void removePanel() {
        if (panelAdded && panelView != null && windowManager != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {
            }
        }
        panelAdded = false;
    }

    // ---------------------------------------------------------
    // ساعت، شمارش معکوس و گزارش
    // ---------------------------------------------------------

    private void startClockUpdater() {
        if (clockRunnable != null) {
            handler.removeCallbacks(clockRunnable);
        }

        clockRunnable = new Runnable() {
            @Override
            public void run() {
                if (clockText != null) {
                    clockText.setText(formatWall(System.currentTimeMillis()));
                }

                if (countdownText != null) {
                    if (waitingForStart) {
                        long remaining = Math.max(
                                0,
                                targetStartElapsed - SystemClock.elapsedRealtime()
                        );
                        countdownText.setText("تا شروع: " + formatDuration(remaining * 1_000_000L));
                    } else if (running) {
                        long remaining = Math.max(
                                0,
                                currentLoopDeadlineElapsed - SystemClock.elapsedRealtime()
                        );
                        countdownText.setText("باقی‌مانده چرخه: " +
                                formatDuration(remaining * 1_000_000L));
                    } else {
                        countdownText.setText("آماده");
                    }
                }

                handler.postDelayed(this, 50);
            }
        };

        handler.post(clockRunnable);
    }

    private void updatePanelStatus() {
        if (panelStatusText != null) {
            panelStatusText.setText(getCurrentStatus());
        }

        if (panelReportText != null) {
            if (reports.isEmpty()) {
                panelReportText.setText("گزارشی ثبت نشده است.");
            } else {
                StringBuilder builder = new StringBuilder();
                int start = Math.max(0, reports.size() - 3);
                for (int i = reports.size() - 1; i >= start; i--) {
                    builder.append(reports.get(i)).append("\n\n");
                }
                panelReportText.setText(builder.toString());
            }
        }
    }

    private void addReport(String report) {
        reports.add(report);

        while (reports.size() > 20) {
            reports.remove(0);
        }

        notifyReports();
        updatePanelStatus();
    }

    private void notifyReports() {
        if (listener != null) {
            listener.onReportsChanged(new ArrayList<>(reports));
        }
    }

    private void notifyState(String status) {
        if (listener != null) {
            listener.onRunStateChanged(status);
        }
        updatePanelStatus();
    }

    private void notifyCalibration(String result) {
        if (listener != null) {
            listener.onCalibrationFinished(result);
        }
    }

    // ---------------------------------------------------------
    // ابزارهای کمکی
    // ---------------------------------------------------------

    private String formatWall(long millis) {
        return timeFormat.format(new Date(millis));
    }

    private String formatDuration(long nanos) {
        long millis = Math.max(0, nanos / 1_000_000L);

        long hours = millis / 3_600_000L;
        millis %= 3_600_000L;

        long minutes = millis / 60_000L;
        millis %= 60_000L;

        long seconds = millis / 1_000L;
        long ms = millis % 1_000L;

        return String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d.%03d",
                hours, minutes, seconds, ms
        );
    }

    private long readNumber(EditText edit, long fallback) {
        try {
            String value = edit.getText().toString().trim();
            if (value.isEmpty()) return fallback;
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), Color.rgb(75, 90, 110));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showToast(String message) {
        handler.post(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        );
    }
}