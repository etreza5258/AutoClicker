package com.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ClickService extends AccessibilityService {

    public interface RunStateListener {
        void onRunStateChanged(String status);
        void onReportsChanged(List<String> reports);
        void onCalibrationFinished(String result);
    }

    public static ClickService instance;

    private static final int CLOCK_PHONE = 0;
    private static final int CLOCK_TSETMC = 1;

    private static final String TSETMC_TIME_URL =
            "https://cdn.tsetmc.com/api/StaticData/GetTime";

    private static final TimeZone TSETMC_TIME_ZONE =
            TimeZone.getTimeZone("Asia/Tehran");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View markerView;
    private View panelView;
    private WindowManager.LayoutParams markerParams;
    private WindowManager.LayoutParams panelParams;

    private TextView clockText;
    private TextView tsetmcClockText;
    private TextView tsetmcErrorText;
    private ScrollView tsetmcErrorScroll;
    private TextView clockDifferenceText;
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

    private static final int LAST_LOOP_NORMAL = 0;
    private static final int LAST_LOOP_SINGLE = 1;
    private static final int LAST_LOOP_PREVIOUS_TOTAL = 2;
    private static final int LAST_LOOP_CUSTOM = 3;

    private int configuredLastLoopMode = LAST_LOOP_NORMAL;
    private long configuredCustomLastLoopClicks;
    private long previousLoopsClickTotal;

    private long completedLoops;
    private long currentLoopClicks;
    private long currentLoopClickLimit;
    private boolean currentLoopHasClickLimit;
    private boolean currentLoopIsConfiguredLast;
    private boolean currentLoopDeadlineReached;
    private boolean currentLoopFinished;

    private long currentLoopStartElapsed;
    private long currentLoopDeadlineElapsed;
    private long currentLoopStartWall;
    private long nextClickNumber;

    private long targetStartElapsed;
    private long targetStartWall;

    private boolean scheduleUsesTsetmcClock;

    private Runnable pendingClickRunnable;
    private Runnable pendingLoopDeadlineRunnable;
    private Runnable clockRunnable;
    private Runnable pendingPauseRunnable;
    private Runnable pendingStartRunnable;
    private Runnable pendingTouchRunnable;
    private Runnable calibrationRunnable;
    private Runnable tsetmcSyncRunnable;

    private final ArrayList<String> reports = new ArrayList<>();

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private final SimpleDateFormat tsetmcTimeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private long tsetmcServerAnchorMillis;
    private long tsetmcAnchorElapsed;
    private long tsetmcLastSyncElapsed;

    private boolean tsetmcClockAvailable;
    private volatile boolean tsetmcSyncInProgress;

    private String tsetmcClockError = "در انتظار دریافت ساعت سرور";

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
    private Spinner pClockBasisSpinner;
    private EditText pLastLoopCustomCount;

    private int markerOriginalFlags;
    private int panelOriginalFlags;
    private boolean overlaysTemporarilyDisabled;
    private boolean touchInFlight;

    private int calibrationClicks;
    private long calibrationIntervalMillis;
    private long calibrationTouchMillis;
    private long calibrationTotalInterval;
    private long calibrationMinInterval;
    private long calibrationMaxInterval;
    private long calibrationLastClickElapsed;
    private long calibrationStartElapsed;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        tsetmcTimeFormat.setTimeZone(TSETMC_TIME_ZONE);

        AccessibilityServiceInfo activeInfo = getServiceInfo();

        if (activeInfo == null ||
                (activeInfo.getCapabilities() &
                        AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            showToast("قابلیت اجرای کلیک در سرویس فعال نیست؛ فایل accessibility_service.xml را بررسی کن.");
        }

        createMarker();
        createFloatingPanel();
        startClockUpdater();
        startTsetmcSync();

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

        if (tsetmcSyncRunnable != null) {
            handler.removeCallbacks(tsetmcSyncRunnable);
            tsetmcSyncRunnable = null;
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
    // ساعت TSETMC
    // =========================================================

    private void startTsetmcSync() {
        syncTsetmcClock();

        tsetmcSyncRunnable = new Runnable() {
            @Override
            public void run() {
                syncTsetmcClock();
                handler.postDelayed(this, 10000);
            }
        };

        handler.postDelayed(tsetmcSyncRunnable, 10000);
    }

    private void syncTsetmcClock() {
        if (tsetmcSyncInProgress) {
            return;
        }

        tsetmcSyncInProgress = true;
        tsetmcClockError = "در حال دریافت ساعت از سرور اصلی TSETMC...";
        updateClockViews();

        new Thread(() -> {
            HttpURLConnection connection = null;
            long requestStart = SystemClock.elapsedRealtime();

            try {
                URL url = new URL(TSETMC_TIME_URL);
                connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                );

                connection.setRequestProperty(
                        "Accept",
                        "text/plain,application/json,*/*"
                );

                connection.setRequestProperty(
                        "Accept-Language",
                        "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7"
                );

                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "close");
                connection.setRequestProperty("Referer", "https://www.tsetmc.com/");
                connection.setRequestProperty("Origin", "https://www.tsetmc.com");
                connection.setRequestProperty("Cache-Control", "no-cache");

                connection.connect();

                int responseCode = connection.getResponseCode();

                InputStream input =
                        responseCode >= 200 && responseCode < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                StringBuilder response = new StringBuilder();

                if (input != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, "UTF-8"))) {

                        String line;
                        int totalChars = 0;

                        while ((line = reader.readLine()) != null &&
                                totalChars < 8192) {
                            if (response.length() > 0) {
                                response.append('\n');
                            }
                            response.append(line);
                            totalChars += line.length();
                        }
                    }
                }

                String rawTime = response.toString().trim();

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception(
                            "HTTP " + responseCode +
                                    (rawTime.isEmpty() ? "" : "؛ " + rawTime)
                    );
                }

                long parsedServerTime = parseTsetmcTime(rawTime);

                if (parsedServerTime <= 0) {
                    throw new Exception(
                            "قالب پاسخ قابل تشخیص نیست. پاسخ سرور: " + rawTime
                    );
                }

                long responseEnd = SystemClock.elapsedRealtime();
                long midpointElapsed =
                        requestStart + (responseEnd - requestStart) / 2;

                final long acceptedServerTime = parsedServerTime;
                final long acceptedMidpoint = midpointElapsed;
                final String acceptedRawTime = rawTime;

                handler.post(() -> {
                    tsetmcServerAnchorMillis = acceptedServerTime;
                    tsetmcAnchorElapsed = acceptedMidpoint;
                    tsetmcLastSyncElapsed = SystemClock.elapsedRealtime();

                    tsetmcClockAvailable = true;
                    tsetmcClockError = "اتصال موفق؛ پاسخ سرور: " + acceptedRawTime;
                    tsetmcSyncInProgress = false;

                    updateClockViews();
                });

            } catch (Exception e) {
                String message = e.getMessage();

                final String finalError =
                        TSETMC_TIME_URL + "\n" +
                                (message == null
                                        ? e.getClass().getSimpleName()
                                        : message);

                handler.post(() -> {
                    tsetmcSyncInProgress = false;
                    tsetmcClockError = finalError;

                    if (tsetmcServerAnchorMillis <= 0) {
                        tsetmcClockAvailable = false;
                    }

                    updateClockViews();
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private long parseTsetmcTime(String raw) {
        if (raw == null) {
            return -1;
        }

        String value = raw.trim()
                .replace("\uFEFF", "")
                .replace("\"", "")
                .replace("\\/", "/")
                .trim();

        StringBuilder normalized = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            if (ch >= '\u06F0' && ch <= '\u06F9') {
                normalized.append((char) ('0' + (ch - '\u06F0')));
            } else if (ch >= '\u0660' && ch <= '\u0669') {
                normalized.append((char) ('0' + (ch - '\u0660')));
            } else {
                normalized.append(ch);
            }
        }

        value = normalized.toString().trim();

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                        "(\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{1,2}:\\d{1,2}(?:\\.\\d{1,3})?)"
                ).matcher(value);

        if (matcher.find()) {
            value = matcher.group(1);
        }

        String[] patterns = {
                "MM/dd/yyyy HH:mm:ss.SSS",
                "M/d/yyyy H:mm:ss.SSS",
                "MM/dd/yyyy HH:mm:ss",
                "M/d/yyyy H:mm:ss"
        };

        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser =
                        new SimpleDateFormat(pattern, Locale.US);

                parser.setLenient(false);
                parser.setTimeZone(TSETMC_TIME_ZONE);

                ParsePosition position = new ParsePosition(0);
                Date parsed = parser.parse(value, position);

                if (parsed != null &&
                        position.getIndex() == value.length()) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {
            }
        }

        return -1;
    }

    private long getEstimatedTsetmcTimeMillis() {
        if (!tsetmcClockAvailable || tsetmcServerAnchorMillis <= 0) {
            return -1;
        }

        long elapsedNow = SystemClock.elapsedRealtime();

        return tsetmcServerAnchorMillis +
                (elapsedNow - tsetmcAnchorElapsed);
    }

    private String formatTsetmcTime(long millis) {
        if (millis <= 0) {
            return "--:--:--.---";
        }
        return tsetmcTimeFormat.format(new Date(millis));
    }

    private String formatDifference(long difference) {
        String sign = difference > 0 ? "+" : difference < 0 ? "-" : "";
        return sign + Math.abs(difference) + " ms";
    }

    private void updateClockViews() {
        long phoneNow = System.currentTimeMillis();

        if (clockText != null) {
            clockText.setText("گوشی: " + formatWall(phoneNow));
        }

        if (tsetmcClockText != null) {
            if (tsetmcClockAvailable) {
                tsetmcClockText.setText(
                        "TSETMC: " +
                                formatTsetmcTime(getEstimatedTsetmcTimeMillis())
                );
            } else {
                tsetmcClockText.setText("TSETMC: دریافت ساعت ناموفق");
            }
        }

        if (tsetmcErrorText != null) {
            String error;

            if (tsetmcClockAvailable) {
                error = tsetmcClockError == null ||
                        tsetmcClockError.trim().isEmpty()
                        ? "اتصال به ساعت TSETMC برقرار است."
                        : tsetmcClockError;

                tsetmcErrorText.setTextColor(
                        Color.rgb(150, 220, 170)
                );
            } else {
                error = tsetmcClockError == null ||
                        tsetmcClockError.trim().isEmpty()
                        ? "خطای نامشخص؛ هنوز پاسخی دریافت نشده است."
                        : tsetmcClockError;

                tsetmcErrorText.setTextColor(Color.WHITE);
            }

            tsetmcErrorText.setText(error);
        }

        if (clockDifferenceText != null) {
            if (tsetmcClockAvailable) {
                long difference =
                        phoneNow - getEstimatedTsetmcTimeMillis();

                clockDifferenceText.setText(
                        "اختلاف گوشی − TSETMC: " +
                                formatDifference(difference)
                );

                if (difference > 0) {
                    clockDifferenceText.setTextColor(
                            Color.rgb(255, 190, 100)
                    );
                } else if (difference < 0) {
                    clockDifferenceText.setTextColor(
                            Color.rgb(120, 210, 255)
                    );
                } else {
                    clockDifferenceText.setTextColor(Color.WHITE);
                }
            } else {
                clockDifferenceText.setText(
                        "اختلاف: در انتظار همگام‌سازی"
                );
                clockDifferenceText.setTextColor(Color.LTGRAY);
            }
        }
    }

    private String formatTargetClock(long millis) {
        return scheduleUsesTsetmcClock
                ? formatTsetmcTime(millis)
                : formatWall(millis);
    }

    // =========================================================
    // گزارش‌ها
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
    // زمان‌بندی چرخه‌ها
    // =========================================================

    public void scheduleLoops(
            int hour, int minute, int second, int millisecond,
            long loopSeconds, long loopMillis,
            long pauseSeconds, long pauseMillis,
            long loopCount, long intervalMillis, long touchMillis,
            boolean singleClickLastLoop
    ) {
        scheduleLoops(
                hour, minute, second, millisecond,
                loopSeconds, loopMillis,
                pauseSeconds, pauseMillis,
                loopCount, intervalMillis, touchMillis,
                singleClickLastLoop, false
        );
    }

    public void scheduleLoops(
            int hour, int minute, int second, int millisecond,
            long loopSeconds, long loopMillis,
            long pauseSeconds, long pauseMillis,
            long loopCount, long intervalMillis, long touchMillis,
            boolean singleClickLastLoop, boolean useTsetmcClock
    ) {
        scheduleLoops(
                hour, minute, second, millisecond,
                loopSeconds, loopMillis,
                pauseSeconds, pauseMillis,
                loopCount, intervalMillis, touchMillis,
                singleClickLastLoop ? LAST_LOOP_SINGLE : LAST_LOOP_NORMAL,
                0,
                useTsetmcClock
        );
    }

    public void scheduleLoops(
            int hour, int minute, int second, int millisecond,
            long loopSeconds, long loopMillis,
            long pauseSeconds, long pauseMillis,
            long loopCount, long intervalMillis, long touchMillis,
            int lastLoopMode, long customLastLoopClicks,
            boolean useTsetmcClock
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
                touchMillis < 1 ||
                lastLoopMode < LAST_LOOP_NORMAL ||
                lastLoopMode > LAST_LOOP_CUSTOM ||
                (lastLoopMode == LAST_LOOP_CUSTOM &&
                        customLastLoopClicks < 1)) {
            showToast("تنظیمات واردشده معتبر نیست.");
            return;
        }

        if (useTsetmcClock && !tsetmcClockAvailable) {
            showToast(
                    "ساعت TSETMC هنوز دریافت نشده است. اتصال اینترنت را بررسی کن."
            );
            syncTsetmcClock();
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
        configuredLastLoopMode = lastLoopMode;
        configuredCustomLastLoopClicks = customLastLoopClicks;
        previousLoopsClickTotal = 0;

        scheduleUsesTsetmcClock = useTsetmcClock;

        completedLoops = 0;
        currentLoopClicks = 0;
        currentLoopStartWall = 0;
        currentLoopHasClickLimit = false;
        currentLoopClickLimit = Long.MAX_VALUE;
        currentLoopIsConfiguredLast = false;
        currentLoopDeadlineReached = false;
        currentLoopFinished = false;

        reports.clear();
        notifyReports();

        Calendar target;
        long nowClock;

        if (useTsetmcClock) {
            target = Calendar.getInstance(TSETMC_TIME_ZONE);
            nowClock = getEstimatedTsetmcTimeMillis();
            target.setTimeInMillis(nowClock);
        } else {
            target = Calendar.getInstance();
            nowClock = System.currentTimeMillis();
        }

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, second);
        target.set(Calendar.MILLISECOND, millisecond);

        if (target.getTimeInMillis() <= nowClock) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        targetStartWall = target.getTimeInMillis();

        long delayMillis = Math.max(
                0,
                targetStartWall - nowClock
        );

        targetStartElapsed =
                SystemClock.elapsedRealtime() + delayMillis;

        generation++;
        final int thisGeneration = generation;

        running = true;
        waitingForStart = true;

        String clockName =
                useTsetmcClock ? "TSETMC" : "ساعت گوشی";

        notifyState(
                "در انتظار شروع بر اساس " +
                        clockName + " در " +
                        formatTargetClock(targetStartWall)
        );

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
        currentLoopFinished = false;
        currentLoopDeadlineReached = false;

        currentLoopStartElapsed = SystemClock.elapsedRealtime();
        currentLoopStartWall = System.currentTimeMillis();

        currentLoopDeadlineElapsed =
                currentLoopStartElapsed + loopDurationMillis;

        currentLoopIsConfiguredLast =
                configuredLoopCount > 0 &&
                        completedLoops + 1 == configuredLoopCount;

        currentLoopHasClickLimit = false;
        currentLoopClickLimit = Long.MAX_VALUE;

        if (currentLoopIsConfiguredLast) {
            if (configuredLastLoopMode == LAST_LOOP_SINGLE) {
                currentLoopHasClickLimit = true;
                currentLoopClickLimit = 1;

                notifyState("چرخه آخر؛ یک کلیک تا پایان زمان چرخه");

            } else if (configuredLastLoopMode == LAST_LOOP_PREVIOUS_TOTAL) {
                currentLoopHasClickLimit = true;
                currentLoopClickLimit = previousLoopsClickTotal;

                notifyState(
                        "چرخه آخر؛ هدف کلیک برابر مجموع چرخه‌های قبلی: " +
                                currentLoopClickLimit
                );

            } else if (configuredLastLoopMode == LAST_LOOP_CUSTOM) {
                currentLoopHasClickLimit = true;
                currentLoopClickLimit = configuredCustomLastLoopClicks;

                notifyState(
                        "چرخه آخر؛ تعداد کلیک دلخواه: " +
                                currentLoopClickLimit
                );

            } else {
                notifyState("چرخه آخر؛ اجرای عادی تا پایان زمان");
            }
        } else {
            notifyState("شروع چرخه " + (completedLoops + 1));
        }

        pendingLoopDeadlineRunnable = () -> {
            pendingLoopDeadlineRunnable = null;

            if (!running ||
                    runGeneration != generation ||
                    currentLoopFinished) {
                return;
            }

            currentLoopDeadlineReached = true;
            removePendingClick();

            if (!gestureInProgress) {
                finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            }
        };

        handler.postDelayed(
                pendingLoopDeadlineRunnable,
                loopDurationMillis
        );

        if (!currentLoopHasClickLimit || currentLoopClickLimit > 0) {
            scheduleNextClick(runGeneration, currentLoopStartElapsed);
        }

        updatePanelStatus();
    }

    private void scheduleNextClick(
            int runGeneration,
            long plannedElapsed
    ) {
        if (!running ||
                runGeneration != generation ||
                currentLoopFinished) {
            return;
        }

        if (currentLoopHasClickLimit &&
                currentLoopClicks >= currentLoopClickLimit) {
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

            if (!running ||
                    runGeneration != generation ||
                    currentLoopFinished) {
                return;
            }

            if (SystemClock.elapsedRealtime() >= currentLoopDeadlineElapsed) {
                finishCurrentLoop(runGeneration, "پایان زمان چرخه");
                return;
            }

            performRegularClick(runGeneration);
        };

        handler.postDelayed(pendingClickRunnable, delay);
    }

    private void performRegularClick(int runGeneration) {
        if (!running ||
                runGeneration != generation ||
                gestureInProgress ||
                currentLoopFinished) {
            return;
        }

        if (currentLoopHasClickLimit &&
                currentLoopClicks >= currentLoopClickLimit) {
            return;
        }

        long nowBeforeTouch = SystemClock.elapsedRealtime();

        long remainingBeforeTouch =
                currentLoopDeadlineElapsed - nowBeforeTouch;

        if (remainingBeforeTouch <= 0) {
            currentLoopDeadlineReached = true;
            finishCurrentLoop(runGeneration, "پایان زمان چرخه");
            return;
        }

        gestureInProgress = true;

        long effectiveTouchDuration = Math.max(
                1,
                Math.min(touchDurationMillis, remainingBeforeTouch)
        );

        boolean accepted = dispatchTouch(
                effectiveTouchDuration,
                completed -> {
                    if (runGeneration != generation || !running) {
                        return;
                    }

                    gestureInProgress = false;

                    if (currentLoopFinished) {
                        return;
                    }

                    if (completed) {
                        currentLoopClicks++;
                    }

                    long now = SystemClock.elapsedRealtime();

                    if (currentLoopDeadlineReached ||
                            now >= currentLoopDeadlineElapsed) {
                        finishCurrentLoop(runGeneration, "پایان زمان چرخه");
                        return;
                    }

                    if (currentLoopHasClickLimit &&
                            currentLoopClicks >= currentLoopClickLimit) {
                        notifyState(
                                "تعداد کلیک هدف تکمیل شد؛ انتظار تا پایان زمان چرخه"
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

    private void finishCurrentLoop(
            int runGeneration,
            String reason
    ) {
        if (!running ||
                runGeneration != generation ||
                currentLoopFinished) {
            return;
        }

        currentLoopFinished = true;
        removePendingClick();

        if (pendingLoopDeadlineRunnable != null) {
            handler.removeCallbacks(pendingLoopDeadlineRunnable);
            pendingLoopDeadlineRunnable = null;
        }

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
                (currentLoopIsConfiguredLast && currentLoopHasClickLimit
                        ? "هدف کلیک چرخه آخر: " + currentLoopClickLimit + "\n"
                        : "") +
                "وضعیت: " + reason;

        addReport(report);

        if (Long.MAX_VALUE - previousLoopsClickTotal < currentLoopClicks) {
            previousLoopsClickTotal = Long.MAX_VALUE;
        } else {
            previousLoopsClickTotal += currentLoopClicks;
        }

        boolean wasConfiguredLast = currentLoopIsConfiguredLast;

        completedLoops++;
        currentLoopClicks = 0;

        if (wasConfiguredLast) {
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
        if (pendingLoopDeadlineRunnable != null) {
            handler.removeCallbacks(pendingLoopDeadlineRunnable);
            pendingLoopDeadlineRunnable = null;
        }

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
    // ارسال لمس واقعی - نسخه سریع‌تر
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

            showToast("ارسال لمس ممکن نیست؛ نشانگر یا سرویس آماده نیست.");
            return false;
        }

        AccessibilityServiceInfo activeInfo = getServiceInfo();

        if (activeInfo == null ||
                (activeInfo.getCapabilities() &
                        AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {

            showToast(
                    "سرویس اجازه ارسال لمس ندارد؛ canPerformGestures باید true باشد."
            );
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

        boolean accepted = false;

        try {
            Path path = new Path();
            path.moveTo(targetX, targetY);
            path.lineTo(targetX + 0.1f, targetY + 0.1f);

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

            // حذف تأخیر ثابت ۳۵ میلی‌ثانیه:
            // لمس بلافاصله ارسال می‌شود.
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
            showToast(
                    "خطای ارسال لمس: " +
                            (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage())
            );
        }

        if (!accepted &&
                thisTouchGeneration == touchGeneration) {

            touchInFlight = false;
            restoreOverlayTouchability();

            if (result != null) {
                result.onFinished(false);
            }

            return false;
        }

        return accepted;
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

                windowManager.updateViewLayout(markerView, markerParams);
            }

            if (panelAdded && panelView != null) {
                panelOriginalFlags = panelParams.flags;
                panelParams.flags |=
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                windowManager.updateViewLayout(panelView, panelParams);
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
                windowManager.updateViewLayout(markerView, markerParams);
            }

            if (panelAdded && panelView != null) {
                panelParams.flags = panelOriginalFlags;
                windowManager.updateViewLayout(panelView, panelParams);
            }
        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // کلیک آزمایشی
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

        boolean accepted = dispatchTouch(50, completed -> {
            testClickRunning = false;

            if (completed) {
                showToast("کلیک آزمایشی با موفقیت انجام شد.");
                notifyState("کلیک آزمایشی موفق بود");
            } else {
                showToast("کلیک آزمایشی ناموفق بود.");
                notifyState("کلیک آزمایشی ناموفق بود");
            }
        });

        if (!accepted) {
            testClickRunning = false;
            showToast("ارسال کلیک آزمایشی انجام نشد.");
            notifyState("کلیک آزمایشی ارسال نشد");
        }
    }

    // =========================================================
    // کالیبراسیون ۳۰ کلیکی
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

        calibrationStartElapsed = SystemClock.elapsedRealtime();
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
                        long delta = now - calibrationLastClickElapsed;

                        calibrationTotalInterval += delta;
                        calibrationMinInterval =
                                Math.min(calibrationMinInterval, delta);
                        calibrationMaxInterval =
                                Math.max(calibrationMaxInterval, delta);
                    }

                    calibrationLastClickElapsed = now;
                    calibrationClicks++;

                    String progress =
                            "کالیبراسیون: " + calibrationClicks + "/30";

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

        long elapsed = Math.max(
                0,
                SystemClock.elapsedRealtime() - calibrationStartElapsed
        );

        long average =
                calibrationClicks > 1
                        ? calibrationTotalInterval / (calibrationClicks - 1)
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
    // نشانگر
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

        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        markerParams.x = markerX;
        markerParams.y = markerY;

        markerView.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            int startX;
            int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = markerParams.x;
                        startY = markerParams.y;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;

                        markerParams.x = startX + (int) dx;
                        markerParams.y = startY + (int) dy;

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
        });

        try {
            windowManager.addView(markerView, markerParams);
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
                roundBackground(Color.rgb(25, 34, 48), dp(13))
        );

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        clockText = new TextView(this);
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(13);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(
                clockText,
                new LinearLayout.LayoutParams(0, dp(40), 1)
        );

        expandButton = smallButton("باز");

        header.addView(
                expandButton,
                new LinearLayout.LayoutParams(dp(62), dp(40))
        );

        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        tsetmcClockText = new TextView(this);
        tsetmcClockText.setTextColor(Color.rgb(150, 220, 170));
        tsetmcClockText.setTextSize(12);
        tsetmcClockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tsetmcClockText.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(
                tsetmcClockText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(25)
                )
        );

        tsetmcErrorScroll = new ScrollView(this);
        tsetmcErrorScroll.setFillViewport(true);
        tsetmcErrorScroll.setVerticalScrollBarEnabled(true);
        tsetmcErrorScroll.setHorizontalScrollBarEnabled(false);
        tsetmcErrorScroll.setBackground(
                roundBackground(Color.rgb(38, 43, 55), dp(6))
        );

        tsetmcErrorText = new TextView(this);
        tsetmcErrorText.setTextColor(Color.WHITE);
        tsetmcErrorText.setTextSize(10);
        tsetmcErrorText.setTypeface(Typeface.MONOSPACE);
        tsetmcErrorText.setTextIsSelectable(true);
        tsetmcErrorText.setGravity(Gravity.TOP | Gravity.LEFT);
        tsetmcErrorText.setPadding(dp(6), dp(5), dp(6), dp(5));
        tsetmcErrorText.setText(
                "در انتظار دریافت اطلاعات ساعت TSETMC..."
        );

        tsetmcErrorScroll.addView(
                tsetmcErrorText,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                tsetmcErrorScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(75)
                )
        );

        clockDifferenceText = new TextView(this);
        clockDifferenceText.setTextColor(Color.WHITE);
        clockDifferenceText.setTextSize(11);
        clockDifferenceText.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(
                clockDifferenceText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(24)
                )
        );

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

        controlButtonsRow = new LinearLayout(this);
        controlButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlButtonsRow.setGravity(Gravity.CENTER_VERTICAL);

        stopButton = smallButton("■ توقف");

        controlButtonsRow.addView(
                stopButton,
                new LinearLayout.LayoutParams(0, dp(40), 1)
        );

        clearReportsButton = smallButton("پاک‌کردن گزارش‌ها");

        LinearLayout.LayoutParams clearParams =
                new LinearLayout.LayoutParams(0, dp(40), 1);

        clearParams.setMargins(dp(5), 0, 0, 0);
        controlButtonsRow.addView(clearReportsButton, clearParams);

        root.addView(
                controlButtonsRow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        expandedContent = new LinearLayout(this);
        expandedContent.setOrientation(LinearLayout.VERTICAL);
        expandedContent.setVisibility(View.GONE);
        root.addView(expandedContent);

        calibrationResultText = new TextView(this);
        calibrationResultText.setTextColor(Color.rgb(150, 220, 170));
        calibrationResultText.setTextSize(11);
        calibrationResultText.setPadding(0, dp(7), 0, dp(7));
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

        panelParams.x = Math.max(dp(5), screenWidth - dp(300));
        panelParams.y = dp(80);

        installPanelDrag(clockText);
        installPanelDrag(tsetmcClockText);
        installPanelDrag(clockDifferenceText);
        installPanelDrag(countdownText);
        installPanelDrag(panelStatusText);

        expandButton.setOnClickListener(v -> {
            panelExpanded = !panelExpanded;

            expandedContent.setVisibility(
                    panelExpanded ? View.VISIBLE : View.GONE
            );

            expandButton.setText(panelExpanded ? "بستن" : "باز");

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

        updateClockViews();
        updatePanelStatus();
    }

    private void installPanelDrag(View dragView) {
        dragView.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = panelParams.x;
                        startY = panelParams.y;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;

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
        });
    }

    private void buildFloatingSettings(LinearLayout parent) {
        TextView heading = new TextView(this);
        heading.setText("تنظیمات اجرای لوپی");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(8), 0, dp(5));
        parent.addView(heading);

        TextView clockBasisLabel = new TextView(this);
        clockBasisLabel.setText("مبنای زمان‌بندی شروع");
        clockBasisLabel.setTextColor(Color.WHITE);
        clockBasisLabel.setTextSize(12);
        clockBasisLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        clockBasisLabel.setPadding(0, dp(8), 0, dp(4));
        parent.addView(clockBasisLabel);

        pClockBasisSpinner = new Spinner(this);

        ArrayAdapter<String> clockAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "ساعت گوشی اندروید",
                        "ساعت سرور TSETMC"
                }
        );

        clockAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        pClockBasisSpinner.setAdapter(clockAdapter);
        pClockBasisSpinner.setSelection(CLOCK_PHONE);

        parent.addView(
                pClockBasisSpinner,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                )
        );

        Button syncClockButton = smallButton(
                "به‌روزرسانی ساعت TSETMC"
        );

        parent.addView(syncClockButton);

        syncClockButton.setOnClickListener(v -> {
            tsetmcClockError = "در حال دریافت ساعت از سرور اصلی...";
            updateClockViews();
            syncTsetmcClock();
            showToast("درخواست دریافت ساعت TSETMC ارسال شد.");
        });

        pStartHour = panelField(parent, "ساعت شروع", "00");
        pStartMinute = panelField(parent, "دقیقه شروع", "00");
        pStartSecond = panelField(parent, "ثانیه شروع", "00");
        pStartMillis = panelField(parent, "میلی‌ثانیه شروع", "000");

        pLoopSeconds = panelField(parent, "مدت چرخه؛ ثانیه", "5");
        pLoopMillis = panelField(parent, "مدت چرخه؛ میلی‌ثانیه", "0");

        pPauseSeconds = panelField(parent, "مکث؛ ثانیه", "1");
        pPauseMillis = panelField(parent, "مکث؛ میلی‌ثانیه", "0");

        pLoopCount = panelField(parent, "تعداد چرخه؛ صفر = نامحدود", "10");

        // مقدار پیش‌فرض فاصله کلیک از ۲۰۰ به ۵۰ میلی‌ثانیه تغییر کرد.
        pInterval = panelField(parent, "فاصله کلیک؛ ms", "50");

        // مدت لمس مستقل باقی مانده است.
        pTouch = panelField(parent, "مدت لمس؛ ms", "20");

        TextView lastLoopLabel = new TextView(this);
        lastLoopLabel.setText("رفتار چرخه آخر");
        lastLoopLabel.setTextColor(Color.WHITE);
        lastLoopLabel.setTextSize(12);
        lastLoopLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lastLoopLabel.setPadding(0, dp(10), 0, dp(4));
        parent.addView(lastLoopLabel);

        pLastLoopSpinner = new Spinner(this);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "چرخه آخر: اجرای عادی تا پایان زمان",
                        "چرخه آخر: فقط یک کلیک تا پایان زمان",
                        "چرخه آخر: مجموع کلیک‌های چرخه‌های قبلی",
                        "چرخه آخر: تعداد کلیک دلخواه"
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

        pLastLoopCustomCount = panelField(
                parent,
                "تعداد کلیک دلخواه چرخه آخر",
                "100"
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
            long interval = readNumber(pInterval, 50);
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
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
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

        // مقدار جایگزین فاصله کلیک نیز ۵۰ میلی‌ثانیه است.
        long interval = readNumber(pInterval, 50);

        long touch = readNumber(pTouch, 20);

        int lastLoopMode =
                pLastLoopSpinner == null
                        ? LAST_LOOP_NORMAL
                        : pLastLoopSpinner.getSelectedItemPosition();

        long customLastLoopClicks =
                readNumber(pLastLoopCustomCount, 100);

        boolean useTsetmc =
                pClockBasisSpinner != null &&
                        pClockBasisSpinner.getSelectedItemPosition()
                                == CLOCK_TSETMC;

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
                lastLoopMode < LAST_LOOP_NORMAL ||
                lastLoopMode > LAST_LOOP_CUSTOM ||
                (lastLoopMode == LAST_LOOP_CUSTOM &&
                        customLastLoopClicks < 1) ||
                (count == 0 && lastLoopMode != LAST_LOOP_NORMAL) ||
                loopSec * 1000L + loopMs <= 0) {

            showToast(
                    count == 0 && lastLoopMode != LAST_LOOP_NORMAL
                            ? "برای تنظیم چرخه آخر، تعداد کل چرخه‌ها باید مشخص باشد."
                            : "یکی از مقادیر تنظیمات معتبر نیست."
            );
            return;
        }

        scheduleLoops(
                h, m, s, ms,
                loopSec, loopMs,
                pauseSec, pauseMs,
                count, interval, touch,
                lastLoopMode, customLastLoopClicks,
                useTsetmc
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
                updateClockViews();

                if (countdownText != null) {
                    if (waitingForStart) {
                        long remaining = Math.max(
                                0,
                                targetStartElapsed -
                                        SystemClock.elapsedRealtime()
                        );

                        countdownText.setText(
                                "تا شروع: " +
                                        formatDuration(remaining * 1_000_000L)
                        );

                    } else if (running) {
                        long remaining = Math.max(
                                0,
                                currentLoopDeadlineElapsed -
                                        SystemClock.elapsedRealtime()
                        );

                        if (currentLoopIsConfiguredLast &&
                                currentLoopHasClickLimit) {
                            countdownText.setText(
                                    "چرخه آخر: " +
                                            currentLoopClicks + "/" +
                                            currentLoopClickLimit +
                                            " | زمان: " +
                                            formatDuration(remaining * 1_000_000L)
                            );
                        } else {
                            countdownText.setText(
                                    "باقی‌مانده چرخه: " +
                                            formatDuration(remaining * 1_000_000L)
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

    private GradientDrawable roundBackground(int color, int radius) {
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
