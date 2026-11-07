
package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
    private TextView calibrationResultText;

    private LinearLayout expandedContent;
    private LinearLayout settingsContainer;
    private LinearLayout controlButtonsRow;
    private ScrollView settingsScroll;

    private Button expandButton;
    private Button stopButton;
    private Button clearReportsButton;

    private boolean markerAdded;
    private boolean panelAdded;
    private boolean panelExpanded;

    private int markerX = 350;
    private int markerY = 700;

    private RunStateListener listener;

    private boolean running;
    private boolean waitingForStart;
    private boolean calibrationRunning;
    private boolean gestureInProgress;
    private boolean testClickRunning;

    private int generation = 0;
    private int touchGeneration = 0;

    private long loopDurationMillis;
    private long pauseDurationMillis;
    private long intervalMillis;
    private long touchDurationMillis;

    private long configuredLoopCount;
    private boolean lastLoopSingleClick;

    private long completedLoops;
    private long currentLoopClicks;

    private long currentLoopStartElapsed;
    private long currentLoopDeadlineElapsed;
    private long currentLoopStartWall;

    private long nextClickNumber;

    private boolean currentLoopIsLastSingle;

    private long targetStartElapsed;
    private long targetStartWall;

    private Runnable pendingClickRunnable;
    private Runnable clockRunnable;
    private Runnable pendingPauseRunnable;
    private Runnable pendingStartRunnable;
    private Runnable pendingTouchRunnable;
    private Runnable calibrationRunnable;

    private final ArrayList<String> reports = new ArrayList<>();

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private long calibrationIntervalMillis;
    private long calibrationTouchMillis;
    private int calibrationClicks;

    private long calibrationStartElapsed;
    private long calibrationLastClickElapsed;
    private long calibrationTotalInterval;
    private long calibrationMinInterval;
    private long calibrationMaxInterval;

    private EditText pStartHour;
    private EditText pStartMinute;
    private EditText pStartSecond;
    private EditText pStartMillis;

    private EditText pLoopSeconds;
    private EditText pLoopMillis;

    private EditText pPauseSeconds;
    private EditText pPauseMillis;

    private EditText pLoopCount;
    private EditText pInterval;
    private EditText pTouch;

    private Spinner pLastLoopSpinner;

    private int markerOriginalFlags;
    private int panelOriginalFlags;
    private boolean overlaysTemporarilyDisabled;

    private boolean touchInFlight;

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
    }

    @Override
    public void onInterrupt() {
        stopRun("توقف سرویس");
        stopCalibration();
        cancelPendingTouch();
    }

    @Override
    public void onDestroy() {
        stopRun("توقف سرویس");
        stopCalibration();
        cancelPendingTouch();

        if (clockRunnable != null) {
            handler.removeCallbacks(clockRunnable);
            clockRunnable = null;
        }

        cancelPendingActions();
        restoreOverlayTouchability();

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
        if (calibrationRunning) {
            return "در حال کالیبراسیون: " + calibrationClicks + "/30";
        }

        if (testClickRunning) {
            return "در حال کلیک آزمایشی";
        }

        if (waitingForStart) {
            return "در انتظار زمان شروع";
        }

        if (running) {
            return "در حال اجرای چرخه " + (completedLoops + 1);
        }

        return "آماده";
    }

    public List<String> getReports() {
        return new ArrayList<>(reports);
    }

    // =========================================================
    // پاک کردن گزارش ها
    // =========================================================

    public void clearReports() {

        reports.clear();

        notifyReports();
        updatePanelStatus();

        if (panelReportText != null) {
            panelReportText.setText("گزارشی ثبت نشده است.");
        }

        showToast("تمام گزارش‌ها پاک شدند.");
    }

    // =========================================================
    // اجرای لوپی
    // =========================================================

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
            showToast("نسخه اندروید پشتیبانی نمی‌شود.");
            return;
        }

        if (hour < 0 || hour > 23 ||
                minute < 0 || minute > 59 ||
                second < 0 || second > 59 ||
                millisecond < 0 || millisecond > 999 ||
                loopSeconds < 0 ||
                loopMillis < 0 || loopMillis > 999 ||
                pauseSeconds < 0 ||
                pauseMillis < 0 || pauseMillis > 999 ||
                loopCount < 0 ||
                intervalMillis < 1 ||
                touchMillis < 1) {

            showToast("تنظیمات واردشده معتبر نیست.");
            return;
        }

        long durationMillis;
        long pauseMillisTotal;

        try {
            durationMillis = Math.addExact(
                    Math.multiplyExact(loopSeconds, 1000L),
                    loopMillis
            );

            pauseMillisTotal = Math.addExact(
                    Math.multiplyExact(pauseSeconds, 1000L),
                    pauseMillis
            );

        } catch (Exception e) {
            showToast("مدت زمان واردشده بیش از حد بزرگ است.");
            return;
        }

        if (durationMillis <= 0) {
            showToast("مدت چرخه باید بیشتر از صفر باشد.");
            return;
        }

        if (calibrationRunning) {
            stopCalibration();
        }

        if (testClickRunning) {
            cancelPendingTouch();
            testClickRunning = false;
        }

        stopRun("شروع اجرای جدید");

        loopDurationMillis = durationMillis;
        pauseDurationMillis = pauseMillisTotal;
        this.intervalMillis = intervalMillis;
        touchDurationMillis = touchMillis;

        configuredLoopCount = loopCount;
        lastLoopSingleClick = singleClickLastLoop;

        completedLoops = 0;
        currentLoopClicks = 0;
        currentLoopStartWall = 0;

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

        targetStartElapsed =
                SystemClock.elapsedRealtime() + delayMillis;

        generation++;

        final int thisGeneration = generation;

        running = true;
        waitingForStart = true;

        notifyState("در انتظار شروع در " + formatWall(targetStartWall));

        pendingStartRunnable = () -> {

            if (thisGeneration != generation || !running) {
                return;
            }

            pendingStartRunnable = null;
            waitingForStart = false;

            beginLoop(thisGeneration);
        };

        handler.postDelayed(pendingStartRunnable, delayMillis);
    }

    private void beginLoop(int runGeneration) {

        if (!running || runGeneration != generation) {
            return;
        }

        if (configuredLoopCount > 0 &&
                completedLoops >= configuredLoopCount) {

            finishAll("پایان عادی");
            return;
        }

        currentLoopClicks = 0;
        nextClickNumber = 0;
        gestureInProgress = false;

        currentLoopStartElapsed = SystemClock.elapsedRealtime();
        currentLoopStartWall = System.currentTimeMillis();

        currentLoopDeadlineElapsed =
                currentLoopStartElapsed + loopDurationMillis;

        currentLoopIsLastSingle =
                lastLoopSingleClick &&
                        configuredLoopCount > 0 &&
                        completedLoops + 1 == configuredLoopCount;

        if (currentLoopIsLastSingle) {
            notifyState("چرخه آخر؛ فقط یک کلیک");
            performSingleLastClick(runGeneration);
        } else {
            notifyState("شروع چرخه " + (completedLoops + 1));

            scheduleNextClick(
                    runGeneration,
                    currentLoopStartElapsed
            );
        }

        updatePanelStatus();
    }

    private void scheduleNextClick(
            int runGeneration,
            long plannedElapsed
    ) {

        if (!running || runGeneration != generation) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= currentLoopDeadlineElapsed) {
            finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            return;
        }

        long delay = Math.max(0, plannedElapsed - now);

        pendingClickRunnable = () -> {

            pendingClickRunnable = null;

            if (!running || runGeneration != generation) {
                return;
            }

            if (SystemClock.elapsedRealtime() >=
                    currentLoopDeadlineElapsed) {

                finishCurrentLoop(
                        runGeneration,
                        "پایان زمان چرخه"
                );
                return;
            }

            performRegularClick(runGeneration);
        };

        handler.postDelayed(pendingClickRunnable, delay);
    }

    private void performRegularClick(int runGeneration) {

        if (!running ||
                runGeneration != generation ||
                gestureInProgress) {
            return;
        }

        if (SystemClock.elapsedRealtime() >=
                currentLoopDeadlineElapsed) {

            finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            return;
        }

        gestureInProgress = true;

        boolean accepted = dispatchTouch(
                touchDurationMillis,
                completed -> {

                    if (runGeneration != generation || !running) {
                        return;
                    }

                    gestureInProgress = false;

                    if (completed) {
                        currentLoopClicks++;
                    }

                    long now = SystemClock.elapsedRealtime();

                    if (now >= currentLoopDeadlineElapsed) {
                        finishCurrentLoop(
                                runGeneration,
                                "پایان زمان چرخه"
                        );
                        return;
                    }

                    nextClickNumber++;

                    long nextPlanned =
                            currentLoopStartElapsed +
                                    nextClickNumber * intervalMillis;

                    if (nextPlanned < now) {
                        nextPlanned = now;
                    }

                    scheduleNextClick(runGeneration, nextPlanned);
                }
        );

        if (!accepted) {
            gestureInProgress = false;
            finishCurrentLoop(runGeneration, "خطا در ارسال لمس");
        }
    }

    private void performSingleLastClick(int runGeneration) {

        if (!running || runGeneration != generation) {
            return;
        }

        gestureInProgress = true;

        boolean accepted = dispatchTouch(
                touchDurationMillis,
                completed -> {

                    if (runGeneration != generation || !running) {
                        return;
                    }

                    gestureInProgress = false;

                    if (completed) {
                        currentLoopClicks++;

                        finishCurrentLoop(
                                runGeneration,
                                "چرخه آخر؛ یک کلیک انجام شد"
                        );

                    } else {
                        finishCurrentLoop(
                                runGeneration,
                                "خطا در کلیک چرخه آخر"
                        );
                    }
                }
        );

        if (!accepted) {
            gestureInProgress = false;

            finishCurrentLoop(
                    runGeneration,
                    "خطا در ارسال کلیک چرخه آخر"
            );
        }
    }

    private void finishCurrentLoop(
            int runGeneration,
            String reason
    ) {

        if (!running || runGeneration != generation) {
            return;
        }

        removePendingClick();

        long endElapsed = SystemClock.elapsedRealtime();
        long endWall = System.currentTimeMillis();

        long actualDuration = Math.max(
                0,
                endElapsed - currentLoopStartElapsed
        );

        String report =
                "چرخه " + (completedLoops + 1) + "\n" +
                "شروع: " + formatWall(currentLoopStartWall) + "\n" +
                "پایان: " + formatWall(endWall) + "\n" +
                "مدت واقعی: " +
                formatDuration(actualDuration * 1_000_000L) + "\n" +
                "تعداد کلیک تکمیل‌شده: " + currentLoopClicks + "\n" +
                "وضعیت: " + reason;

        addReport(report);

        completedLoops++;
        currentLoopClicks = 0;

        if (currentLoopIsLastSingle) {
            finishAll("پایان اجرای چرخه آخر");
            return;
        }

        if (configuredLoopCount > 0 &&
                completedLoops >= configuredLoopCount) {

            finishAll("تمام چرخه‌ها تکمیل شدند");
            return;
        }

        notifyState(
                "چرخه " + completedLoops +
                        " تمام شد؛ مکث بین چرخه‌ها"
        );

        if (pauseDurationMillis <= 0) {
            beginLoop(runGeneration);
        } else {

            pendingPauseRunnable = () -> {

                pendingPauseRunnable = null;

                if (runGeneration == generation && running) {
                    beginLoop(runGeneration);
                }
            };

            handler.postDelayed(
                    pendingPauseRunnable,
                    pauseDurationMillis
            );
        }
    }

    private void finishAll(String reason) {

        generation++;

        running = false;
        waitingForStart = false;
        gestureInProgress = false;

        cancelPendingActions();

        notifyState(reason);
        updatePanelStatus();
    }

    public void stopRun(String reason) {

        boolean wasRunning = running;
        boolean wasWaiting = waitingForStart;

        generation++;

        cancelPendingActions();

        if (wasRunning &&
                !wasWaiting &&
                currentLoopStartWall > 0) {

            long endElapsed = SystemClock.elapsedRealtime();
            long endWall = System.currentTimeMillis();

            long duration = Math.max(
                    0,
                    endElapsed - currentLoopStartElapsed
            );

            String report =
                    "چرخه " + (completedLoops + 1) + "\n" +
                    "شروع: " + formatWall(currentLoopStartWall) + "\n" +
                    "پایان: " + formatWall(endWall) + "\n" +
                    "مدت واقعی: " +
                    formatDuration(duration * 1_000_000L) + "\n" +
                    "تعداد کلیک تکمیل‌شده: " + currentLoopClicks + "\n" +
                    "وضعیت: " + reason;

            addReport(report);
        }

        running = false;
        waitingForStart = false;
        gestureInProgress = false;
        currentLoopClicks = 0;

        cancelPendingTouch();
        restoreOverlayTouchability();

        notifyState(reason);
        updatePanelStatus();
    }

    private void cancelPendingActions() {

        if (pendingStartRunnable != null) {
            handler.removeCallbacks(pendingStartRunnable);
            pendingStartRunnable = null;
        }

        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }

        if (pendingPauseRunnable != null) {
            handler.removeCallbacks(pendingPauseRunnable);
            pendingPauseRunnable = null;
        }
    }

    private void removePendingClick() {

        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }
    }

    // =========================================================
    // ارسال لمس واقعی به صفحه مقصد
    // =========================================================

    private interface GestureResult {
        void onFinished(boolean completed);
    }

    private boolean dispatchTouch(
            long durationMillis,
            GestureResult result
    ) {

        if (Build.VERSION.SDK_INT < 24 ||
                markerView == null ||
                !markerAdded ||
                windowManager == null ||
                touchInFlight) {
            return false;
        }

        int[] location = new int[2];
        markerView.getLocationOnScreen(location);

        final float targetX =
                location[0] + markerView.getWidth() / 2f;

        final float targetY =
                location[1] + markerView.getHeight() / 2f;

        final long duration = Math.max(1, durationMillis);
        final int thisTouchGeneration = ++touchGeneration;

        touchInFlight = true;

        disableOverlayTouchability();

        pendingTouchRunnable = () -> {

            pendingTouchRunnable = null;

            if (thisTouchGeneration != touchGeneration) {
                return;
            }

            Path path = new Path();
            path.moveTo(targetX, targetY);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            path,
                            0,
                            duration
                    );

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();

            boolean accepted;

            try {

                accepted = dispatchGesture(
                        gesture,
                        new GestureResultCallback() {

                            @Override
                            public void onCompleted(
                                    GestureDescription description
                            ) {

                                if (thisTouchGeneration != touchGeneration) {
                                    return;
                                }

                                touchInFlight = false;
                                restoreOverlayTouchability();

                                if (result != null) {
                                    result.onFinished(true);
                                }
                            }

                            @Override
                            public void onCancelled(
                                    GestureDescription description
                            ) {

                                if (thisTouchGeneration != touchGeneration) {
                                    return;
                                }

                                touchInFlight = false;
                                restoreOverlayTouchability();

                                if (result != null) {
                                    result.onFinished(false);
                                }
                            }
                        },
                        handler
                );

            } catch (Exception e) {
                accepted = false;
            }

            if (!accepted &&
                    thisTouchGeneration == touchGeneration) {

                touchInFlight = false;
                restoreOverlayTouchability();

                if (result != null) {
                    result.onFinished(false);
                }
            }
        };

        handler.postDelayed(pendingTouchRunnable, 35);

        return true;
    }

    private void cancelPendingTouch() {

        touchGeneration++;

        if (pendingTouchRunnable != null) {
            handler.removeCallbacks(pendingTouchRunnable);
            pendingTouchRunnable = null;
        }

        touchInFlight = false;
        testClickRunning = false;

        restoreOverlayTouchability();
    }

    private void disableOverlayTouchability() {

        if (overlaysTemporarilyDisabled || windowManager == null) {
            return;
        }

        overlaysTemporarilyDisabled = true;

        try {

            if (markerAdded && markerView != null) {

                markerOriginalFlags = markerParams.flags;

                markerParams.flags |=
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        markerView,
                        markerParams
                );
            }

            if (panelAdded && panelView != null) {

                panelOriginalFlags = panelParams.flags;

                panelParams.flags |=
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(
                        panelView,
                        panelParams
                );
            }

        } catch (Exception ignored) {
        }
    }

    private void restoreOverlayTouchability() {

        if (!overlaysTemporarilyDisabled || windowManager == null) {
            return;
        }

        overlaysTemporarilyDisabled = false;

        try {

            if (markerAdded && markerView != null) {

                markerParams.flags = markerOriginalFlags;

                windowManager.updateViewLayout(
                        markerView,
                        markerParams
                );
            }

            if (panelAdded && panelView != null) {

                panelParams.flags = panelOriginalFlags;

                windowManager.updateViewLayout(
                        panelView,
                        panelParams
                );
            }

        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // کلیک آزمایشی واقعی
    // =========================================================

    public void testClickCurrentPosition() {

        if (running || waitingForStart ||
                calibrationRunning || testClickRunning) {

            showToast("ابتدا اجرای فعلی را متوقف کن.");
            return;
        }

        if (!markerAdded || markerView == null) {
            showToast("نشانگر روی صفحه موجود نیست.");
            return;
        }

        testClickRunning = true;
        notifyState("در حال اجرای کلیک آزمایشی");

        boolean accepted = dispatchTouch(
                50,
                completed -> {

                    testClickRunning = false;

                    if (completed) {
                        showToast("کلیک آزمایشی با موفقیت انجام شد.");
                        notifyState("کلیک آزمایشی موفق بود");
                    } else {
                        showToast("کلیک آزمایشی ناموفق بود.");
                        notifyState("کلیک آزمایشی ناموفق بود");
                    }
                }
        );

        if (!accepted) {
            testClickRunning = false;
            showToast("ارسال کلیک آزمایشی انجام نشد.");
            notifyState("کلیک آزمایشی ارسال نشد");
        }
    }

    // =========================================================
    // کالیبراسیون واقعی ۳۰ کلیکی
    // =========================================================

    public void startCalibration(
            long intervalMillis,
            long touchMillis
    ) {

        if (running || waitingForStart || testClickRunning) {
            showToast("ابتدا اجرای فعلی را متوقف کن.");
            return;
        }

        if (!markerAdded || markerView == null) {
            showToast("نشانگر روی صفحه موجود نیست.");
            return;
        }

        if (intervalMillis < 1 || touchMillis < 1) {
            showToast("فاصله و مدت لمس باید بیشتر از صفر باشند.");
            return;
        }

        if (calibrationRunning) {
            stopCalibration();
        }

        cancelPendingTouch();

        calibrationIntervalMillis = intervalMillis;
        calibrationTouchMillis = touchMillis;

        calibrationClicks = 0;
        calibrationTotalInterval = 0;
        calibrationMinInterval = Long.MAX_VALUE;
        calibrationMaxInterval = 0;
        calibrationLastClickElapsed = 0;

        calibrationStartElapsed =
                SystemClock.elapsedRealtime();

        calibrationRunning = true;

        if (calibrationResultText != null) {
            calibrationResultText.setText(
                    "کالیبراسیون شروع شد: ۰ از ۳۰ کلیک"
            );
        }

        notifyState("شروع کالیبراسیون ۳۰ کلیک");
        performCalibrationClick();
    }

    private void performCalibrationClick() {

        if (!calibrationRunning) {
            return;
        }

        if (calibrationClicks >= 30) {
            finishCalibration();
            return;
        }

        boolean accepted = dispatchTouch(
                calibrationTouchMillis,
                completed -> {

                    if (!calibrationRunning) {
                        return;
                    }

                    if (!completed) {
                        calibrationRunning = false;

                        String error =
                                "کالیبراسیون ناموفق بود؛ لمس تکمیل نشد.";

                        notifyCalibration(error);

                        if (calibrationResultText != null) {
                            calibrationResultText.setText(error);
                        }

                        notifyState("کالیبراسیون ناموفق");
                        return;
                    }

                    long now = SystemClock.elapsedRealtime();

                    if (calibrationLastClickElapsed > 0) {

                        long delta =
                                now - calibrationLastClickElapsed;

                        calibrationTotalInterval += delta;

                        calibrationMinInterval =
                                Math.min(
                                        calibrationMinInterval,
                                        delta
                                );

                        calibrationMaxInterval =
                                Math.max(
                                        calibrationMaxInterval,
                                        delta
                                );
                    }

                    calibrationLastClickElapsed = now;
                    calibrationClicks++;

                    String progress =
                            "کالیبراسیون: " +
                                    calibrationClicks + "/30";

                    if (calibrationResultText != null) {
                        calibrationResultText.setText(progress);
                    }

                    notifyState(progress);

                    if (calibrationClicks >= 30) {
                        finishCalibration();
                        return;
                    }

                    calibrationRunnable = this::performCalibrationClick;

                    handler.postDelayed(
                            calibrationRunnable,
                            calibrationIntervalMillis
                    );
                }
        );

        if (!accepted) {

            calibrationRunning = false;

            String error =
                    "ارسال لمس برای کالیبراسیون ناموفق بود.";

            notifyCalibration(error);

            if (calibrationResultText != null) {
                calibrationResultText.setText(error);
            }

            notifyState("کالیبراسیون ناموفق");
        }
    }

    private void finishCalibration() {

        if (!calibrationRunning) {
            return;
        }

        calibrationRunning = false;

        if (calibrationRunnable != null) {
            handler.removeCallbacks(calibrationRunnable);
            calibrationRunnable = null;
        }

        long now = SystemClock.elapsedRealtime();

        long elapsed = Math.max(
                0,
                now - calibrationStartElapsed
        );

        long average =
                calibrationClicks > 1
                        ? calibrationTotalInterval /
                        (calibrationClicks - 1)
                        : 0;

        long minimum =
                calibrationMinInterval == Long.MAX_VALUE
                        ? 0
                        : calibrationMinInterval;

        String result =
                "کالیبراسیون کامل شد؛ ۳۰ کلیک موفق\n" +
                "میانگین فاصله: " + average + " ms\n" +
                "کمترین فاصله: " + minimum + " ms\n" +
                "بیشترین فاصله: " + calibrationMaxInterval + " ms\n" +
                "مدت کل: " + elapsed + " ms";

        addReport(result);
        notifyCalibration(result);

        if (calibrationResultText != null) {
            calibrationResultText.setText(result);
        }

        notifyState("کالیبراسیون تمام شد");
        showToast("کالیبراسیون ۳۰ کلیکی تکمیل شد.");
    }

    public void stopCalibration() {

        if (!calibrationRunning) {
            return;
        }

        calibrationRunning = false;

        if (calibrationRunnable != null) {
            handler.removeCallbacks(calibrationRunnable);
            calibrationRunnable = null;
        }

        cancelPendingTouch();

        String result =
                "کالیبراسیون متوقف شد. تعداد کلیک موفق: " +
                        calibrationClicks + "/30";

        if (calibrationResultText != null) {
            calibrationResultText.setText(result);
        }

        notifyCalibration(result);
        notifyState("کالیبراسیون متوقف شد");
    }

    // =========================================================
    // نشانگر آبی
    // =========================================================

    private void createMarker() {

        if (markerAdded || windowManager == null) {
            return;
        }

        TextView marker = new TextView(this);

        marker.setText("●");
        marker.setTextSize(34);
        marker.setTextColor(Color.rgb(0, 125, 255));
        marker.setGravity(Gravity.CENTER);
        marker.setShadowLayer(5, 0, 0, Color.WHITE);

        markerView = marker;

        markerParams = new WindowManager.LayoutParams(
                dp(55),
                dp(55),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity =
                Gravity.TOP | Gravity.LEFT;

        markerParams.x = markerX;
        markerParams.y = markerY;

        markerView.setOnTouchListener(
                new View.OnTouchListener() {

                    float downRawX;
                    float downRawY;

                    int startX;
                    int startY;

                    @Override
                    public boolean onTouch(
                            View view,
                            MotionEvent event
                    ) {

                        switch (event.getAction()) {

                            case MotionEvent.ACTION_DOWN:

                                downRawX = event.getRawX();
                                downRawY = event.getRawY();

                                startX = markerParams.x;
                                startY = markerParams.y;

                                return true;

                            case MotionEvent.ACTION_MOVE:

                                float dx =
                                        event.getRawX() - downRawX;

                                float dy =
                                        event.getRawY() - downRawY;

                                markerParams.x =
                                        startX + (int) dx;

                                markerParams.y =
                                        startY + (int) dy;

                                markerX = markerParams.x + dp(27);
                                markerY = markerParams.y + dp(27);

                                try {
                                    windowManager.updateViewLayout(
                                            markerView,
                                            markerParams
                                    );
                                } catch (Exception ignored) {
                                }

                                return true;

                            case MotionEvent.ACTION_UP:
                                return true;
                        }

                        return true;
                    }
                }
        );

        try {

            windowManager.addView(
                    markerView,
                    markerParams
            );

            markerAdded = true;

        } catch (Exception e) {
            markerAdded = false;
        }
    }

    private void removeMarker() {

        if (markerAdded &&
                markerView != null &&
                windowManager != null) {

            try {
                windowManager.removeView(markerView);
            } catch (Exception ignored) {
            }
        }

        markerAdded = false;
        markerView = null;
    }

    // =========================================================
    // پنل شناور
    // =========================================================

    private void createFloatingPanel() {

        if (panelAdded || windowManager == null) {
            return;
        }

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(11), dp(9), dp(11), dp(9));

        root.setBackground(
                roundBackground(
                        Color.rgb(25, 34, 48),
                        dp(13)
                )
        );

        // ردیف اول: ساعت و دکمه باز و بسته کردن

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        clockText = new TextView(this);

        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(18);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(
                clockText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1
                )
        );

        expandButton = smallButton("باز");

        header.addView(
                expandButton,
                new LinearLayout.LayoutParams(
                        dp(62),
                        dp(40)
                )
        );

        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        // ردیف دوم: شمارش معکوس، کاملاً جدا از دکمه‌ها

        countdownText = new TextView(this);

        countdownText.setTextColor(Color.rgb(100, 205, 255));
        countdownText.setTextSize(16);
        countdownText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        countdownText.setGravity(Gravity.CENTER_VERTICAL);
        countdownText.setPadding(0, dp(5), 0, dp(5));

        root.addView(
                countdownText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(38)
                )
        );

        // ردیف سوم: وضعیت اجرای برنامه

        panelStatusText = new TextView(this);

        panelStatusText.setTextColor(Color.WHITE);
        panelStatusText.setTextSize(12);
        panelStatusText.setGravity(Gravity.CENTER_VERTICAL);
        panelStatusText.setPadding(0, dp(3), 0, dp(7));

        root.addView(
                panelStatusText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(30)
                )
        );

        // ردیف چهارم: دکمه توقف و پاک کردن گزارش ها

        controlButtonsRow = new LinearLayout(this);
        controlButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlButtonsRow.setGravity(Gravity.CENTER_VERTICAL);

        stopButton = smallButton("■ توقف");

        controlButtonsRow.addView(
                stopButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1
                )
        );

        clearReportsButton = smallButton("پاک‌کردن گزارش‌ها");

        LinearLayout.LayoutParams clearParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1
                );

        clearParams.setMargins(dp(5), 0, 0, 0);

        controlButtonsRow.addView(
                clearReportsButton,
                clearParams
        );

        root.addView(
                controlButtonsRow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        // بخش تنظیمات بازشونده

        expandedContent = new LinearLayout(this);
        expandedContent.setOrientation(LinearLayout.VERTICAL);
        expandedContent.setVisibility(View.GONE);

        root.addView(expandedContent);

        calibrationResultText = new TextView(this);

        calibrationResultText.setTextColor(
                Color.rgb(150, 220, 170)
        );

        calibrationResultText.setTextSize(11);
        calibrationResultText.setPadding(
                0,
                dp(7),
                0,
                dp(7)
        );

        calibrationResultText.setText("کالیبراسیون هنوز اجرا نشده است.");

        expandedContent.addView(calibrationResultText);

        panelReportText = new TextView(this);

        panelReportText.setTextColor(Color.WHITE);
        panelReportText.setTextSize(10);
        panelReportText.setPadding(0, dp(7), 0, dp(5));

        expandedContent.addView(panelReportText);

        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(false);
        settingsScroll.setVerticalScrollBarEnabled(true);

        settingsContainer = new LinearLayout(this);
        settingsContainer.setOrientation(LinearLayout.VERTICAL);

        settingsScroll.addView(
                settingsContainer,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        expandedContent.addView(
                settingsScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(300)
                )
        );

        buildFloatingSettings(settingsContainer);

        panelView = root;

        panelParams = new WindowManager.LayoutParams(
                dp(290),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity = Gravity.TOP | Gravity.LEFT;

        int screenWidth =
                getResources().getDisplayMetrics().widthPixels;

        panelParams.x =
                Math.max(dp(5), screenWidth - dp(300));

        panelParams.y = dp(80);

        installPanelDrag(clockText);
        installPanelDrag(countdownText);
        installPanelDrag(panelStatusText);

        expandButton.setOnClickListener(v -> {

            panelExpanded = !panelExpanded;

            expandedContent.setVisibility(
                    panelExpanded ? View.VISIBLE : View.GONE
            );

            expandButton.setText(
                    panelExpanded ? "بستن" : "باز"
            );

            if (panelExpanded) {
                panelParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
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

        stopButton.setOnClickListener(v -> {

            stopRun("توقف دستی");
            stopCalibration();
            cancelPendingTouch();

            showToast("تمام عملیات متوقف شدند.");
        });

        clearReportsButton.setOnClickListener(v -> clearReports());

        try {

            windowManager.addView(panelView, panelParams);
            panelAdded = true;

        } catch (Exception e) {
            panelAdded = false;
        }

        updatePanelStatus();
    }

    private void installPanelDrag(View dragView) {

        dragView.setOnTouchListener(
                new View.OnTouchListener() {

                    float downX;
                    float downY;

                    int startX;
                    int startY;

                    @Override
                    public boolean onTouch(
                            View view,
                            MotionEvent event
                    ) {

                        switch (event.getAction()) {

                            case MotionEvent.ACTION_DOWN:

                                downX = event.getRawX();
                                downY = event.getRawY();

                                startX = panelParams.x;
                                startY = panelParams.y;

                                return true;

                            case MotionEvent.ACTION_MOVE:

                                float dx =
                                        event.getRawX() - downX;

                                float dy =
                                        event.getRawY() - downY;

                                panelParams.x = startX + (int) dx;
                                panelParams.y = startY + (int) dy;

                                try {
                                    windowManager.updateViewLayout(
                                            panelView,
                                            panelParams
                                    );
                                } catch (Exception ignored) {
                                }

                                return true;

                            case MotionEvent.ACTION_UP:
                                return true;
                        }

                        return true;
                    }
                }
        );
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

        // تنظیم مستقل رفتار چرخه آخر

        TextView lastLoopLabel = new TextView(this);

        lastLoopLabel.setText("رفتار چرخه آخر");
        lastLoopLabel.setTextColor(Color.WHITE);
        lastLoopLabel.setTextSize(12);
        lastLoopLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lastLoopLabel.setPadding(0, dp(10), 0, dp(4));

        parent.addView(lastLoopLabel);

        pLastLoopSpinner = new Spinner(this);

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        new String[]{
                                "چرخه آخر: اجرای عادی",
                                "چرخه آخر: فقط یک کلیک"
                        }
                );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        pLastLoopSpinner.setAdapter(adapter);

        parent.addView(
                pLastLoopSpinner,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                )
        );

        Button start = smallButton("شروع با تنظیمات پنل");
        parent.addView(start);

        start.setOnClickListener(v -> startFromFloatingPanel());

        Button test = smallButton("تست کلیک نشانگر");
        parent.addView(test);

        test.setOnClickListener(v -> testClickCurrentPosition());

        Button calibrate = smallButton("شروع کالیبراسیون ۳۰ کلیکی");
        parent.addView(calibrate);

        calibrate.setOnClickListener(v -> {

            long interval = readNumber(pInterval, 200);
            long touch = readNumber(pTouch, 20);

            startCalibration(interval, touch);
        });

        Button stopCalibrationButton = smallButton("توقف کالیبراسیون");
        parent.addView(stopCalibrationButton);

        stopCalibrationButton.setOnClickListener(v -> stopCalibration());
    }

    private EditText panelField(
            LinearLayout parent,
            String label,
            String value
    ) {

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

        edit.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
        );

        edit.setPadding(dp(5), 0, dp(5), 0);

        edit.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.GRAY)
        );

        parent.addView(
                edit,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(38)
                )
        );

        return edit;
    }

    private void startFromFloatingPanel() {

        if (pStartHour == null) {
            return;
        }

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

        boolean singleLast =
                pLastLoopSpinner.getSelectedItemPosition() == 1;

        if (h < 0 || h > 23 ||
                m < 0 || m > 59 ||
                s < 0 || s > 59 ||
                ms < 0 || ms > 999 ||
                loopSec < 0 ||
                loopMs < 0 || loopMs > 999 ||
                pauseSec < 0 ||
                pauseMs < 0 || pauseMs > 999 ||
                count < 0 ||
                interval < 1 ||
                touch < 1 ||
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
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(55, 85, 120)
                )
        );

        return button;
    }

    private void removePanel() {

        if (panelAdded &&
                panelView != null &&
                windowManager != null) {

            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {
            }
        }

        panelAdded = false;
        panelView = null;
    }

    // =========================================================
    // ساعت و شمارش معکوس
    // =========================================================

    private void startClockUpdater() {

        if (clockRunnable != null) {
            handler.removeCallbacks(clockRunnable);
        }

        clockRunnable = new Runnable() {

            @Override
            public void run() {

                if (clockText != null) {
                    clockText.setText(
                            formatWall(System.currentTimeMillis())
                    );
                }

                if (countdownText != null) {

                    if (waitingForStart) {

                        long remaining = Math.max(
                                0,
                                targetStartElapsed -
                                        SystemClock.elapsedRealtime()
                        );

                        countdownText.setText(
                                "تا شروع: " +
                                        formatDuration(
                                                remaining * 1_000_000L
                                        )
                        );

                    } else if (running) {

                        long remaining = Math.max(
                                0,
                                currentLoopDeadlineElapsed -
                                        SystemClock.elapsedRealtime()
                        );

                        if (currentLoopIsLastSingle) {
                            countdownText.setText("چرخه آخر: یک کلیک");
                        } else {
                            countdownText.setText(
                                    "باقی‌مانده چرخه: " +
                                            formatDuration(
                                                    remaining * 1_000_000L
                                            )
                            );
                        }

                    } else if (calibrationRunning) {

                        countdownText.setText(
                                "کالیبراسیون: " + calibrationClicks + "/30"
                        );

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

    // =========================================================
    // ابزارهای کمکی
    // =========================================================

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
                hours,
                minutes,
                seconds,
                ms
        );
    }

    private long readNumber(EditText edit, long fallback) {

        try {

            String value = edit.getText().toString().trim();

            if (value.isEmpty()) {
                return fallback;
            }

            return Long.parseLong(value);

        } catch (Exception e) {
            return fallback;
        }
    }

    private GradientDrawable roundBackground(
            int color,
            int radius
    ) {

        GradientDrawable drawable = new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), Color.rgb(75, 90, 110));

        return drawable;
    }

    private int dp(float value) {

        return (int) (
                value *
                        getResources().getDisplayMetrics().density +
                        0.5f
        );
    }

    private void showToast(String message) {

        handler.post(() ->
                Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT
                ).show()
        );
    }
}
