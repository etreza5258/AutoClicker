
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

public class MainActivity extends Activity implements ClickService.RunStateListener {

    private EditText hour, minute, second, millisecond;
    private EditText count, interval, touchDuration;
    private EditText durationHour, durationMinute, durationSecond, durationMs;
    private EditText desiredCps;

    private LinearLayout durationContainer;
    private TextView statusText, calibrationResult;
    private Spinner modeSpinner;

    private int mode = 0;

    private EditText numberField(String hint, int value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        e.setEms(5);
        return e;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, 4, 0, 4);
        return l;
    }

    private void addField(LinearLayout parent, EditText field) {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(3, 0, 3, 0);
        parent.addView(field, p);
    }

    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, 8, 0, 4);
        return t;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private int value(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void setDurationColor(int state) {
        if (durationContainer == null) return;

        int color;
        if (state == 1) {
            color = Color.rgb(190, 240, 195);
        } else if (state == 2) {
            color = Color.rgb(255, 220, 160);
        } else if (state == 3) {
            color = Color.rgb(255, 190, 190);
        } else {
            color = Color.rgb(235, 235, 235);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(14);
        bg.setStroke(1, Color.LTGRAY);
        durationContainer.setBackground(bg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 20);
        scroll.addView(root);

        TextView title = label("AutoClicker", 24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(label("زمان شروع کلیک بر اساس ساعت گوشی", 16));

        LinearLayout startTime = horizontal();
        hour = numberField("ساعت", 12);
        minute = numberField("دقیقه", 0);
        second = numberField("ثانیه", 0);
        millisecond = numberField("میلی‌ثانیه", 0);

        addField(startTime, hour);
        addField(startTime, minute);
        addField(startTime, second);
        addField(startTime, millisecond);
        root.addView(startTime);

        root.addView(label("روش توقف", 16));

        modeSpinner = new Spinner(this);
        String[] modes = {
                "توقف پس از تعداد مشخص کلیک",
                "توقف پس از مدت زمان مشخص"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        root.addView(modeSpinner);

        root.addView(label("تعداد کلیک", 15));
        count = numberField("تعداد", 30);
        root.addView(count);

        root.addView(label("مدت زمان کلیک", 16));

        durationContainer = new LinearLayout(this);
        durationContainer.setOrientation(LinearLayout.VERTICAL);
        durationContainer.setPadding(8, 6, 8, 8);

        TextView durationLabel = label(
                "ساعت / دقیقه / ثانیه / میلی‌ثانیه", 14);
        durationContainer.addView(durationLabel);

        LinearLayout durationFields = horizontal();
        durationHour = numberField("ساعت", 0);
        durationMinute = numberField("دقیقه", 0);
        durationSecond = numberField("ثانیه", 10);
        durationMs = numberField("میلی‌ثانیه", 0);

        addField(durationFields, durationHour);
        addField(durationFields, durationMinute);
        addField(durationFields, durationSecond);
        addField(durationFields, durationMs);

        durationContainer.addView(durationFields);
        root.addView(durationContainer);
        setDurationColor(0);

        modeSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view,
                            int position, long id) {
                        mode = position;
                        durationContainer.setVisibility(
                                mode == 1 ? View.VISIBLE : View.GONE);
                        count.setVisibility(
                                mode == 0 ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

        root.addView(label("فاصله بین کلیک‌ها (میلی‌ثانیه)", 15));
        interval = numberField("مثلاً 50", 50);
        root.addView(interval);

        root.addView(label("مدت لمس (میلی‌ثانیه)", 15));
        touchDuration = numberField("مدت لمس", 1);
        root.addView(touchDuration);

        Button startButton = makeButton("تنظیم و شروع کلیک");
        root.addView(startButton);

        Button stopButton = makeButton("توقف کلیک");
        root.addView(stopButton);

        Button accessibilityButton =
                makeButton("فعال‌سازی دسترسی کلیکر");
        root.addView(accessibilityButton);

        statusText = label("وضعیت: آماده", 15);
        root.addView(statusText);

        root.addView(label("کالیبراسیون سرعت کلیک", 19));

        desiredCps = numberField("کلیک در ثانیه", 10);
        root.addView(desiredCps);

        Button calibrationButton =
                makeButton("اجرای تست ۳۰ کلیک");
        root.addView(calibrationButton);

        calibrationResult = label(
                "نتیجه کالیبراسیون هنوز ثبت نشده است.", 14);
        root.addView(calibrationResult);

        Button applyButton =
                makeButton("اعمال فاصله پیشنهادی");
        root.addView(applyButton);

        startButton.setOnClickListener(v -> scheduleClick());

        stopButton.setOnClickListener(v -> {
            if (ClickService.instance != null) {
                ClickService.instance.stopClick();
            }
            statusText.setText("وضعیت: توقف دستی");
            setDurationColor(3);
        });

        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        calibrationButton.setOnClickListener(v -> runCalibration());

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

    private long suggestedInterval = -1;

    private void scheduleClick() {
        if (ClickService.instance == null) {
            Toast.makeText(this,
                    "ابتدا سرویس دسترسی کلیکر را فعال کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        int h = value(hour, 0);
        int m = value(minute, 0);
        int s = value(second, 0);
        int ms = value(millisecond, 0);

        int c = value(count, 1);
        int i = value(interval, 50);
        int touch = value(touchDuration, 1);

        long duration =
                value(durationHour, 0) * 3600000L
                + value(durationMinute, 0) * 60000L
                + value(durationSecond, 0) * 1000L
                + value(durationMs, 0);

        if (h < 0 || h > 23 ||
                m < 0 || m > 59 ||
                s < 0 || s > 59 ||
                ms < 0 || ms > 999 ||
                i < 1 || touch < 1 ||
                (mode == 0 && c < 1) ||
                (mode == 1 && duration <= 0)) {
            Toast.makeText(this,
                    "مقادیر واردشده معتبر نیستند.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        ClickService.instance.scheduleClick(
                h, m, s, ms,
                mode == 1,
                c,
                duration,
                i,
                touch
        );

        statusText.setText("وضعیت: زمان‌بندی شد");
        setDurationColor(0);
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
                                    suggested
                            );

                            calibrationResult.setText(result);
                        });
                    }

                    @Override
                    public void onFailed(String message) {
                        runOnUiThread(() ->
                                calibrationResult.setText(
                                        "تست ناموفق بود: " + message));
                    }
                }
        );
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
            statusText.setText("وضعیت: " + message);
            setDurationColor(state);
        });
    }
}

