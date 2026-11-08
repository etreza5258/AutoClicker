package com.autoclicker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity
        implements ClickService.RunStateListener {

    private EditText startHour, startMinute, startSecond, startMillis;
    private EditText loopSeconds, loopMillis;
    private EditText pauseSeconds, pauseMillis;
    private EditText loopCount, intervalMillis, touchMillis;

    private EditText lastLoopCustomClicks;

    private Spinner lastLoopSpinner;

    private TextView statusText, reportText;

    private Button startButton, stopButton;

    private int background = Color.rgb(245, 247, 250);
    private int primary = Color.rgb(35, 105, 210);

    // ترتیب این گزینه‌ها باید با حالت‌های ClickService هماهنگ باشد.
    private static final int LAST_LOOP_NORMAL = 0;
    private static final int LAST_LOOP_SINGLE = 1;
    private static final int LAST_LOOP_PREVIOUS_TOTAL = 2;
    private static final int LAST_LOOP_CUSTOM = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(background);

        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("اتوکلیکر لوپی");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(30, 40, 55));
        title.setGravity(Gravity.CENTER);

        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "تنظیم زمان شروع، چرخه‌ها و رفتار چرخه آخر"
        );
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(5), 0, dp(16));

        root.addView(subtitle, matchWrap());

        // =====================================================
        // زمان شروع
        // =====================================================

        addSection(root, "زمان شروع بر اساس ساعت گوشی");

        LinearLayout startRow = horizontal(root);

        startHour = addField(startRow, "ساعت", "00");
        startMinute = addField(startRow, "دقیقه", "00");
        startSecond = addField(startRow, "ثانیه", "00");
        startMillis = addField(startRow, "میلی‌ثانیه", "000");

        // =====================================================
        // مدت هر چرخه
        // =====================================================

        addSection(root, "مدت اجرای هر چرخه");

        LinearLayout loopRow = horizontal(root);

        loopSeconds = addField(loopRow, "ثانیه", "5");
        loopMillis = addField(loopRow, "میلی‌ثانیه", "0");

        // =====================================================
        // مکث بین چرخه‌ها
        // =====================================================

        addSection(root, "مکث بین چرخه‌ها");

        LinearLayout pauseRow = horizontal(root);

        pauseSeconds = addField(pauseRow, "ثانیه", "1");
        pauseMillis = addField(pauseRow, "میلی‌ثانیه", "0");

        // =====================================================
        // تعداد چرخه‌ها
        // =====================================================

        addSection(root, "تعداد چرخه‌ها");

        loopCount = addSingleField(
                root,
                "تعداد چرخه (صفر = نامحدود)",
                "10"
        );

        // =====================================================
        // تنظیمات کلیک
        // =====================================================

        addSection(root, "تنظیم کلیک");

        LinearLayout clickRow = horizontal(root);

        intervalMillis = addField(
                clickRow,
                "فاصله کلیک (ms)",
                "200"
        );

        touchMillis = addField(
                clickRow,
                "مدت لمس (ms)",
                "20"
        );

        // =====================================================
        // رفتار چرخه آخر
        // =====================================================

        addSection(root, "رفتار چرخه آخر");

        lastLoopSpinner = new Spinner(this);

        String[] lastOptions = {
                "اجرای عادی تا پایان زمان چرخه",
                "فقط یک کلیک تا پایان زمان چرخه",
                "مجموع کلیک‌های چرخه‌های قبلی",
                "تعداد کلیک دلخواه"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                lastOptions
        );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        lastLoopSpinner.setAdapter(adapter);
        lastLoopSpinner.setSelection(LAST_LOOP_NORMAL);

        root.addView(lastLoopSpinner, matchWrap());

        // تعداد کلیک دلخواه چرخه آخر

        lastLoopCustomClicks = addSingleField(
                root,
                "تعداد کلیک دلخواه چرخه آخر (فقط حالت چهارم)",
                "100"
        );

        TextView hint = new TextView(this);

        hint.setText(
                "در حالت مجموع کلیک‌ها، تعداد کلیک‌های موفق تمام چرخه‌های قبلی جمع می‌شود.\n" +
                "در حالت تعداد دلخواه، عدد واردشده هدف کلیک چرخه آخر است.\n" +
                "در هر دو حالت، با تمام‌شدن زمان چرخه، کلیک متوقف می‌شود؛ " +
                "حتی اگر تعداد هدف کامل نشده باشد.\n" +
                "برای اجرای نامحدود، حالت چرخه آخر باید روی اجرای عادی باشد."
        );

        hint.setTextColor(Color.rgb(90, 95, 105));
        hint.setTextSize(12);
        hint.setPadding(
                dp(4),
                dp(7),
                dp(4),
                dp(8)
        );

        root.addView(hint, matchWrap());

        // =====================================================
        // کنترل برنامه
        // =====================================================

        addSection(root, "کنترل برنامه");

        startButton = makeButton(
                "شروع اجرای لوپی",
                primary
        );

        root.addView(startButton, matchWrap());

        stopButton = makeButton(
                "توقف دستی",
                Color.rgb(190, 55, 55)
        );

        root.addView(stopButton, matchWrap());

        Button accessibilityButton = makeButton(
                "فعال‌سازی سرویس دسترس‌پذیری",
                Color.rgb(75, 85, 100)
        );

        root.addView(accessibilityButton, matchWrap());

        Button overlayButton = makeButton(
                "مجوز نمایش روی برنامه‌های دیگر",
                Color.rgb(75, 85, 100)
        );

        root.addView(overlayButton, matchWrap());

        Button testButton = makeButton(
                "تست کلیک در محل نشانگر آبی",
                Color.rgb(40, 135, 100)
        );

        root.addView(testButton, matchWrap());

        Button calibrationButton = makeButton(
                "کالیبراسیون ۳۰ کلیک",
                Color.rgb(105, 85, 170)
        );

        root.addView(calibrationButton, matchWrap());

        // =====================================================
        // وضعیت برنامه
        // =====================================================

        statusText = new TextView(this);
        statusText.setText("وضعیت: آماده");
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusText.setTextColor(Color.rgb(30, 55, 85));
        statusText.setPadding(
                dp(5),
                dp(16),
                dp(5),
                dp(8)
        );

        root.addView(statusText, matchWrap());

        // =====================================================
        // گزارش چرخه‌ها
        // =====================================================

        addSection(root, "گزارش چرخه‌ها");

        reportText = new TextView(this);
        reportText.setText("هنوز گزارشی ثبت نشده است.");
        reportText.setTextSize(12);
        reportText.setTextColor(Color.rgb(45, 50, 60));
        reportText.setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
        );
        reportText.setBackgroundColor(Color.WHITE);

        root.addView(reportText, matchWrap());

        setContentView(scroll);

        // =====================================================
        // دکمه شروع
        // =====================================================

        startButton.setOnClickListener(v -> startRun());

        // =====================================================
        // دکمه توقف
        // =====================================================

        stopButton.setOnClickListener(v -> {

            if (ClickService.instance != null) {
                ClickService.instance.stopRun("توقف دستی");
            }
        });

        // =====================================================
        // فعال‌سازی Accessibility
        // =====================================================

        accessibilityButton.setOnClickListener(v ->
                startActivity(
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
        );

        // =====================================================
        // مجوز Overlay
        // =====================================================

        overlayButton.setOnClickListener(v -> {

            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                    !Settings.canDrawOverlays(this)) {

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );

                startActivity(intent);

            } else {

                Toast.makeText(
                        this,
                        "مجوز نمایش روی برنامه‌ها فعال است.",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        // =====================================================
        // تست کلیک
        // =====================================================

        testButton.setOnClickListener(v -> {

            if (ClickService.instance != null) {

                ClickService.instance.testClickCurrentPosition();

            } else {

                toast("ابتدا سرویس دسترس‌پذیری را فعال کن.");
            }
        });

        // =====================================================
        // کالیبراسیون
        // =====================================================

        calibrationButton.setOnClickListener(v -> {

            if (ClickService.instance != null) {

                long interval = number(intervalMillis, 200);
                long touch = number(touchMillis, 20);

                ClickService.instance.startCalibration(
                        interval,
                        touch
                );

            } else {

                toast("ابتدا سرویس دسترس‌پذیری را فعال کن.");
            }
        });
    }

    // =========================================================
    // بازگشت به برنامه
    // =========================================================

    @Override
    protected void onResume() {
        super.onResume();

        if (ClickService.instance != null) {

            ClickService.instance.setRunStateListener(this);

            updateReports(
                    ClickService.instance.getReports()
            );

            statusText.setText(
                    "وضعیت: " +
                            ClickService.instance.getCurrentStatus()
            );
        }
    }

    // =========================================================
    // شروع اجرای لوپی
    // =========================================================

    private void startRun() {

        if (ClickService.instance == null) {
            toast("ابتدا سرویس دسترس‌پذیری را فعال کن.");
            return;
        }

        int h = (int) number(startHour, 0);
        int m = (int) number(startMinute, 0);
        int s = (int) number(startSecond, 0);
        int ms = (int) number(startMillis, 0);

        long loopSec = number(loopSeconds, 5);
        long loopMs = number(loopMillis, 0);

        long pauseSec = number(pauseSeconds, 1);
        long pauseMs = number(pauseMillis, 0);

        long count = number(loopCount, 10);

        long interval = number(intervalMillis, 200);
        long touch = number(touchMillis, 20);

        long customLastClicks =
                number(lastLoopCustomClicks, 100);

        // همان ترتیب گزینه‌های ClickService
        int lastLoopMode =
                lastLoopSpinner.getSelectedItemPosition();

        // =====================================================
        // اعتبارسنجی زمان شروع
        // =====================================================

        if (h < 0 || h > 23 ||
                m < 0 || m > 59 ||
                s < 0 || s > 59 ||
                ms < 0 || ms > 999) {

            toast("زمان شروع معتبر نیست.");
            return;
        }

        // =====================================================
        // اعتبارسنجی مدت چرخه و مکث
        // =====================================================

        if (loopSec < 0 ||
                loopMs < 0 ||
                loopMs > 999 ||
                pauseSec < 0 ||
                pauseMs < 0 ||
                pauseMs > 999) {

            toast("مقادیر مدت چرخه یا مکث معتبر نیست.");
            return;
        }

        // =====================================================
        // اعتبارسنجی تعداد چرخه
        // =====================================================

        if (count < 0) {
            toast("تعداد چرخه معتبر نیست.");
            return;
        }

        // =====================================================
        // اعتبارسنجی فاصله و مدت لمس
        // =====================================================

        if (interval < 1 || touch < 1) {
            toast(
                    "فاصله کلیک و مدت لمس باید حداقل یک میلی‌ثانیه باشد."
            );
            return;
        }

        // =====================================================
        // اعتبارسنجی مقدار مدت چرخه
        // =====================================================

        long totalLoopMillis;

        try {

            totalLoopMillis = Math.addExact(
                    Math.multiplyExact(loopSec, 1000L),
                    loopMs
            );

            Math.addExact(
                    Math.multiplyExact(pauseSec, 1000L),
                    pauseMs
            );

        } catch (ArithmeticException e) {

            toast("مقدار زمان واردشده بیش از حد بزرگ است.");
            return;
        }

        if (totalLoopMillis <= 0) {
            toast("مدت چرخه باید بیشتر از صفر باشد.");
            return;
        }

        // =====================================================
        // اعتبارسنجی رفتار چرخه آخر
        // =====================================================

        if (lastLoopMode < LAST_LOOP_NORMAL ||
                lastLoopMode > LAST_LOOP_CUSTOM) {

            toast("حالت چرخه آخر معتبر نیست.");
            return;
        }

        // در اجرای نامحدود چرخه آخری وجود ندارد.
        if (count == 0 &&
                lastLoopMode != LAST_LOOP_NORMAL) {

            toast(
                    "برای استفاده از تنظیم چرخه آخر، تعداد چرخه‌ها باید محدود باشد."
            );
            return;
        }

        // فقط حالت تعداد دلخواه نیازمند عدد مثبت است.
        if (lastLoopMode == LAST_LOOP_CUSTOM &&
                customLastClicks < 1) {

            toast(
                    "تعداد کلیک دلخواه باید حداقل یک باشد."
            );
            return;
        }

        // =====================================================
        // اجرای برنامه
        // =====================================================

        ClickService.instance.setRunStateListener(this);

        ClickService.instance.scheduleLoops(
                h,
                m,
                s,
                ms,

                loopSec,
                loopMs,

                pauseSec,
                pauseMs,

                count,

                interval,
                touch,

                lastLoopMode,
                customLastClicks,

                false
        );
    }

    // =========================================================
    // ساخت ردیف افقی
    // =========================================================

    private LinearLayout horizontal(LinearLayout parent) {

        LinearLayout row = new LinearLayout(this);

        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        parent.addView(row, matchWrap());

        return row;
    }

    // =========================================================
    // ساخت فیلدهای ورودی
    // =========================================================

    private EditText addField(
            LinearLayout row,
            String label,
            String value
    ) {

        LinearLayout column = new LinearLayout(this);

        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(
                dp(3),
                dp(2),
                dp(3),
                dp(2)
        );

        TextView text = new TextView(this);

        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(Color.DKGRAY);
        text.setGravity(Gravity.CENTER);

        column.addView(text, matchWrap());

        EditText edit = new EditText(this);

        edit.setSingleLine(true);
        edit.setText(value);
        edit.setTextSize(15);
        edit.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
        );
        edit.setGravity(Gravity.CENTER);
        edit.setSelectAllOnFocus(true);
        edit.setPadding(
                dp(3),
                dp(5),
                dp(3),
                dp(5)
        );

        column.addView(edit, matchWrap());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                );

        row.addView(column, params);

        return edit;
    }

    // =========================================================
    // ساخت فیلد تکی
    // =========================================================

    private EditText addSingleField(
            LinearLayout parent,
            String label,
            String value
    ) {

        LinearLayout row = horizontal(parent);

        return addField(
                row,
                label,
                value
        );
    }

    // =========================================================
    // عنوان بخش‌ها
    // =========================================================

    private void addSection(
            LinearLayout root,
            String text
    ) {

        TextView title = new TextView(this);

        title.setText(text);
        title.setTextSize(16);
        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );
        title.setTextColor(Color.rgb(35, 65, 100));
        title.setPadding(
                dp(2),
                dp(15),
                dp(2),
                dp(5)
        );

        root.addView(title, matchWrap());
    }

    // =========================================================
    // ساخت دکمه
    // =========================================================

    private Button makeButton(
            String text,
            int color
    ) {

        Button button = new Button(this);

        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);

        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        params.setMargins(
                0,
                dp(4),
                0,
                dp(4)
        );

        button.setLayoutParams(params);

        return button;
    }

    // =========================================================
    // پارامترهای اندازه
    // =========================================================

    private LinearLayout.LayoutParams matchWrap() {

        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    // =========================================================
    // خواندن عدد
    // =========================================================

    private long number(
            EditText edit,
            long fallback
    ) {

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

    // =========================================================
    // تبدیل DP
    // =========================================================

    private int dp(float value) {

        return (int) (
                value *
                        getResources().getDisplayMetrics().density +
                        0.5f
        );
    }

    // =========================================================
    // پیام
    // =========================================================

    private void toast(String message) {

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    // =========================================================
    // دریافت وضعیت سرویس
    // =========================================================

    @Override
    public void onRunStateChanged(String status) {

        runOnUiThread(() ->
                statusText.setText("وضعیت: " + status)
        );
    }

    // =========================================================
    // دریافت گزارش‌ها
    // =========================================================

    @Override
    public void onReportsChanged(List<String> reports) {

        runOnUiThread(() ->
                updateReports(reports)
        );
    }

    // =========================================================
    // دریافت نتیجه کالیبراسیون
    // =========================================================

    @Override
    public void onCalibrationFinished(String result) {

        runOnUiThread(() -> {

            statusText.setText(
                    "کالیبراسیون: " + result
            );

            toast(result);
        });
    }

    // =========================================================
    // نمایش گزارش‌ها
    // =========================================================

    private void updateReports(List<String> reports) {

        if (reportText == null) {
            return;
        }

        if (reports == null || reports.isEmpty()) {

            reportText.setText(
                    "هنوز گزارشی ثبت نشده است."
            );

            return;
        }

        StringBuilder builder = new StringBuilder();

        for (int i = reports.size() - 1; i >= 0; i--) {

            builder.append(reports.get(i))
                    .append("\n\n");
        }

        reportText.setText(builder.toString());
    }
}
