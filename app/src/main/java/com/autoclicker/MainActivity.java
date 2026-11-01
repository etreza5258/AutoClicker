
package com.autoclicker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputType;
import android.widget.*;
import android.widget.AdapterView;

public class MainActivity extends Activity {

    EditText hour, minute, second, millisecond;
    EditText count, interval, touchDuration;
    EditText durationHour, durationMinute;
    EditText durationSecond, durationMillisecond;

    Spinner mode;

    TextView status;
    TextView countLabel;
    TextView durationLabel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        layout.setBackgroundColor(Color.WHITE);

        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("AutoClicker");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        addLabel(layout, "زمان شروع کلیک");

        hour = addField(layout, "ساعت", "12");
        minute = addField(layout, "دقیقه", "00");
        second = addField(layout, "ثانیه", "00");
        millisecond = addField(layout, "میلی‌ثانیه", "000");

        addLabel(layout, "روش پایان کلیک");

        mode = new Spinner(this);

        String[] modes = {
                "بر اساس تعداد کلیک",
                "بر اساس مدت زمان"
        };

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        modes
                );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        mode.setAdapter(adapter);
        layout.addView(mode);

        countLabel = addLabel(layout, "تعداد کلیک");
        count = addField(layout, "تعداد", "1");

        durationLabel = addLabel(layout, "مدت زمان کلیک");

        durationHour = addField(layout, "ساعت", "00");
        durationMinute = addField(layout, "دقیقه", "00");
        durationSecond = addField(layout, "ثانیه", "10");
        durationMillisecond =
                addField(layout, "میلی‌ثانیه", "000");

        mode.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {

                        boolean timeMode = position == 1;

                        countLabel.setVisibility(
                                timeMode ? View.GONE : View.VISIBLE
                        );

                        count.setVisibility(
                                timeMode ? View.GONE : View.VISIBLE
                        );

                        int visibility = timeMode
                                ? View.VISIBLE : View.GONE;

                        durationLabel.setVisibility(visibility);
                        durationHour.setVisibility(visibility);
                        durationMinute.setVisibility(visibility);
                        durationSecond.setVisibility(visibility);
                        durationMillisecond.setVisibility(visibility);
                    }

                    @Override
                    public void onNothingSelected(
                            AdapterView<?> parent) {
                    }
                }
        );

        interval = addField(
                layout,
                "فاصله بین کلیک‌ها (میلی‌ثانیه)",
                "1"
        );

        touchDuration = addField(
                layout,
                "مدت هر لمس (میلی‌ثانیه)",
                "1"
        );

        Button accessibility = new Button(this);
        accessibility.setText("فعال‌سازی دسترسی‌پذیری");
        layout.addView(accessibility);

        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
            ));
        });

        Button start = new Button(this);
        start.setText("ثبت زمان و شروع");
        layout.addView(start);

        start.setOnClickListener(v -> scheduleClick());

        Button stop = new Button(this);
        stop.setText("توقف کلیک");
        layout.addView(stop);

        stop.setOnClickListener(v -> {
            if (ClickService.instance != null) {
                ClickService.instance.stopClick();
                status.setText("زمان‌بندی متوقف شد");
            } else {
                status.setText("سرویس فعال نیست");
            }
        });

        status = new TextView(this);
        status.setText("آماده");
        status.setTextSize(18);
        status.setTextColor(Color.BLACK);
        status.setPadding(0, 20, 0, 0);
        layout.addView(status);

        setContentView(scroll);
    }

    private TextView addLabel(
            LinearLayout layout,
            String title) {

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.BLACK);
        label.setTextSize(15);
        layout.addView(label);

        return label;
    }

    private EditText addField(
            LinearLayout layout,
            String title,
            String value) {

        addLabel(layout, title);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setText(value);

        layout.addView(edit);

        return edit;
    }

    private int read(EditText field) {
        return Integer.parseInt(
                field.getText().toString().trim()
        );
    }

    private void scheduleClick() {

        try {

            int h = read(hour);
            int m = read(minute);
            int s = read(second);
            int ms = read(millisecond);

            int i = read(interval);
            int touch = read(touchDuration);

            boolean timeMode = mode.getSelectedItemPosition() == 1;

            int c = 1;
            long durationMs = 0;

            if (h < 0 || h > 23 ||
                    m < 0 || m > 59 ||
                    s < 0 || s > 59 ||
                    ms < 0 || ms > 999) {

                status.setText("زمان شروع معتبر نیست");
                return;
            }

            if (i < 1 || touch < 1 || touch > 1000) {
                status.setText("سرعت یا مدت لمس معتبر نیست");
                return;
            }

            if (timeMode) {

                int dh = read(durationHour);
                int dm = read(durationMinute);
                int ds = read(durationSecond);
                int dms = read(durationMillisecond);

                if (dh < 0 || dh > 99 ||
                        dm < 0 || dm > 59 ||
                        ds < 0 || ds > 59 ||
                        dms < 0 || dms > 999) {

                    status.setText("مدت زمان معتبر نیست");
                    return;
                }

                durationMs =
                        (((long) dh * 60 + dm) * 60 + ds) * 1000
                                + dms;

                if (durationMs <= 0) {
                    status.setText("مدت زمان باید بیشتر از صفر باشد");
                    return;
                }

            } else {

                c = read(count);

                if (c < 1) {
                    status.setText("تعداد کلیک باید حداقل ۱ باشد");
                    return;
                }
            }

            if (ClickService.instance == null) {
                status.setText("ابتدا دسترسی‌پذیری را فعال کن");
                return;
            }

            ClickService.instance.scheduleClick(
                    h,
                    m,
                    s,
                    ms,
                    timeMode,
                    c,
                    durationMs,
                    i,
                    touch
            );

            status.setText("زمان‌بندی ثبت شد");

        } catch (Exception e) {
            status.setText("لطفاً همه مقادیر را بررسی کن");
        }
    }
}
