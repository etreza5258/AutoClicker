package com.autoclicker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.text.InputType;
import android.widget.*;

import java.util.Locale;

public class MainActivity extends Activity
        implements ClickService.RunStateListener {

    private EditText hour, minute, second, millisecond;
    private EditText interval, touchDuration;
    private EditText loopSeconds, loopMs;
    private EditText pauseSeconds, pauseMs;
    private EditText loopCount, desiredCps;

    private TextView statusText, calibrationResult;
    private long suggestedInterval = -1;

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private EditText numberField(String hint, int value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        e.setTextColor(Color.BLACK);
        e.setEms(5);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(3), 0, dp(3));
        return l;
    }

    private void addField(LinearLayout parent, EditText field) {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(field, p);
    }

    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(7), 0, dp(4));
        return t;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private int value(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private GradientDrawable background(int color, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private LinearLayout createCard(
            LinearLayout parent, String title) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(10));
        card.setBackground(background(Color.WHITE, 0xFFDDDDDD));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(0, dp(6), 0, dp(6));
        parent.addView(card, params);

        TextView heading = label(title, 17);
        heading.setTypeface(null,
                android.graphics.Typeface.BOLD);
        card.addView(heading);

        return card;
    }

    private void setStatusColor(int state) {
        if (statusText == null) return;

        int color;

        if (state == 1) {
            color = Color.rgb(20, 130, 50);
        } else if (state == 2) {
            color = Color.rgb(20, 90, 180);
        } else if (state == 3) {
            color = Color.rgb(190, 40, 40);
        } else {
            color = Color.DKGRAY;
        }

        statusText.setTextColor(color);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(20));
        root.setBackgroundColor(0xFFF3F5F7);

        scroll.addView(root);

        TextView title = label("AutoClicker - Loop Edition", 24);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null,
                android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = label(
                "اجرای زمان‌بندی‌شده با گزارش هر لوپ", 13);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        // زمان شروع
        LinearLayout timeCard =
                createCard(root, "۱. زمان شروع");

        timeCard.addView(label(
                "ساعت / دقیقه / ثانیه / میلی‌ثانیه", 13));

        LinearLayout startTime = horizontal();

        hour = numberField("ساعت", 12);
        minute = numberField("دقیقه", 0);
        second = numberField("ثانیه", 0);
        millisecond = numberField("میلی‌ثانیه", 0);

        addField(startTime, hour);
        addField(startTime, minute);
        addField(startTime, second);
        addField(startTime, millisecond);

        timeCard.addView(startTime);

        // مدت لوپ
        LinearLayout loopCard =
                createCard(root, "۲. مدت اجرای هر لوپ");

        loopCard.addView(label(
                "ثانیه / میلی‌ثانیه", 13));

        LinearLayout loopRow = horizontal();

        loopSeconds = numberField("ثانیه", 10);
        loopMs = numberField("میلی‌ثانیه", 0);

        addField(loopRow, loopSeconds);
        addField(loopRow, loopMs);

        loopCard.addView(loopRow);

        // مکث
        LinearLayout pauseCard =
                createCard(root, "۳. مکث بین لوپ‌ها");

        pauseCard.addView(label(
                "ثانیه / میلی‌ثانیه", 13));

        LinearLayout pauseRow = horizontal();

        pauseSeconds = numberField("ثانیه", 1);
        pauseMs = numberField("میلی‌ثانیه", 0);

        addField(pauseRow, pauseSeconds);
        addField(pauseRow, pauseMs);

        pauseCard.addView(pauseRow);

        // تعداد لوپ
        LinearLayout countCard =
                createCard(root, "۴. تعداد تکرار");

        countCard.addView(label(
                "تعداد لوپ؛ صفر یعنی اجرای نامحدود", 13));

        loopCount = numberField("تعداد لوپ", 5);
        countCard.addView(loopCount);

        // سرعت
        LinearLayout speedCard =
                createCard(root, "۵. سرعت کلیک");

        speedCard.addView(label(
                "فاصله بین کلیک‌ها (میلی‌ثانیه)", 14));

        interval = numberField("فاصله کلیک", 50);
        speedCard.addView(interval);

        speedCard.addView(label(
                "مدت لمس هر کلیک (میلی‌ثانیه)", 14));

        touchDuration = numberField("مدت لمس", 1);
        speedCard.addView(touchDuration);

        // اجرا
        LinearLayout actionCard =
                createCard(root, "۶. اجرای کلیکر");

        Button startButton =
                makeButton("تنظیم و شروع کلیک");

        actionCard.addView(startButton);

        Button stopButton =
                makeButton("توقف کلیک");

        actionCard.addView(stopButton);

        Button accessibilityButton =
                makeButton("فعال‌سازی دسترسی کلیکر");

        actionCard.addView(accessibilityButton);

        statusText = label("وضعیت: آماده", 15);
        statusText.setGravity(Gravity.CENTER);
        actionCard.addView(statusText);

        // تست محل کلیک
        LinearLayout testCard =
                createCard(root, "۷. تست محل کلیک");

        testCard.addView(label(
                "نشانگر آبی را روی محل موردنظر قرار بده.", 13));

        Button testButton =
                makeButton("تست کلیک در محل نشانگر آبی");

        testCard.addView(testButton);

        // کالیبراسیون
        LinearLayout calibrationCard =
                createCard(root, "۸. کالیبراسیون سرعت");

        calibrationCard.addView(label(
                "سرعت درخواستی (کلیک در ثانیه)", 14));

        desiredCps = numberField("کلیک در ثانیه", 10);
        calibrationCard.addView(desiredCps);

        Button calibrationButton =
                makeButton("اجرای تست ۳۰ کلیک");

        calibrationCard.addView(calibrationButton);

        calibrationResult = label(
                "نتیجه کالیبراسیون هنوز ثبت نشده است.", 13);

        calibrationCard.addView(calibrationResult);

        Button applyButton =
                makeButton("اعمال فاصله پیشنهادی");

        calibrationCard.addView(applyButton);

        // رویدادها
        startButton.setOnClickListener(v -> scheduleClick());

        stopButton.setOnClickListener(v -> {
            if (ClickService.instance != null) {
                ClickService.instance.stopClick();
            }
        });

        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        testButton.setOnClickListener(v -> {
            if (ClickService.instance == null) {
                Toast.makeText(this,
                        "ابتدا دسترسی کلیکر را فعال کن.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            ClickService.instance.testClickCurrentPosition();

            Toast.makeText(this,
                    "فرمان تست به محل نشانگر ارسال شد.",
                    Toast.LENGTH_SHORT).show();
        });

        calibrationButton.setOnClickListener(
                v -> runCalibration());

        applyButton.setOnClickListener(v -> {
            if (suggestedInterval > 0) {
                interval.setText(String.valueOf(suggestedInterval));
                calibrationResult.append(
                        "\nفاصله پیشنهادی اعمال شد.");
            } else {
                Toast.makeText(this,
                        "ابتدا تست کالیبراسیون را اجرا کن.",
                        Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(scroll);
    }

    private void scheduleClick() {
        if (ClickService.instance == null) {
            Toast.makeText(this,
                    "ابتدا سرویس دسترسی کلیکر را فعال کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        int h = value(hour, -1);
        int m = value(minute, -1);
        int s = value(second, -1);
        int ms = value(millisecond, -1);

        int loopSec = value(loopSeconds, -1);
        int loopMilli = value(loopMs, -1);

        int pauseSec = value(pauseSeconds, -1);
        int pauseMilli = value(pauseMs, -1);

        int loops = value(loopCount, -1);
        int i = value(interval, -1);
        int touch = value(touchDuration, -1);

        long loopDuration =
                loopSec * 1000L + loopMilli;

        long pauseDuration =
                pauseSec * 1000L + pauseMilli;

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
                i < 1 || touch < 1) {

            Toast.makeText(this,
                    "مقادیر واردشده معتبر نیستند.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        ClickService.instance.scheduleClick(
                h, m, s, ms,
                loopDuration,
                pauseDuration,
                loops,
                i,
                touch);

        statusText.setText("وضعیت: زمان‌بندی شد");
        setStatusColor(0);
    }

    private void runCalibration() {
        if (ClickService.instance == null) {
            Toast.makeText(this,
                    "ابتدا سرویس دسترسی کلیکر را فعال کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        int cps = value(desiredCps, 10);

        if (cps < 1 || cps > 100) {
            Toast.makeText(this,
                    "سرعت را بین ۱ تا ۱۰۰ وارد کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        int requestedInterval = Math.max(1, 1000 / cps);

        calibrationResult.setText("تست در حال اجراست...");
        suggestedInterval = -1;

        ClickService.instance.startCalibration(
                requestedInterval,
                30,
                new ClickService.CalibrationListener() {

                    @Override
                    public void onProgress(int completed, int total) {
                        runOnUiThread(() ->
                                calibrationResult.setText(
                                        "پیشرفت تست: " +
                                        completed + " از " + total));
                    }

                    @Override
                    public void onComplete(
                            long averageInterval,
                            long maxInterval,
                            long suggested,
                            double actualCps,
                            long startOffset,
                            int completedClicks) {

                        runOnUiThread(() -> {
                            suggestedInterval = suggested;

                            String result = String.format(
                                    Locale.US,
                                    "تست کامل شد\n" +
                                    "تعداد کلیک: %d\n" +
                                    "میانگین فاصله: %d ms\n" +
                                    "بیشترین فاصله: %d ms\n" +
                                    "سرعت واقعی: %.2f کلیک در ثانیه\n" +
                                    "اختلاف شروع: %d ms\n" +
                                    "فاصله پیشنهادی: %d ms",
                                    completedClicks,
                                    averageInterval,
                                    maxInterval,
                                    actualCps,
                                    startOffset,
                                    suggested);

                            calibrationResult.setText(result);
                        });
                    }

                    @Override
                    public void onFailed(String message) {
                        runOnUiThread(() ->
                                calibrationResult.setText(
                                        "تست ناموفق بود: " + message));
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (ClickService.instance != null) {
            ClickService.instance.setRunStateListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (ClickService.instance != null) {
            ClickService.instance.setRunStateListener(null);
        }
        super.onDestroy();
    }

    @Override
    public void onRunStateChanged(int state, String message) {
        runOnUiThread(() -> {
            if (statusText != null) {
                statusText.setText("وضعیت: " + message);
                setStatusColor(state);
            }
        });
    }
}