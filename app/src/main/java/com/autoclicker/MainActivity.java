
package com.autoclicker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;

public class MainActivity extends Activity {

    EditText hour, minute, second, millisecond, count, interval;
    TextView status;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        layout.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("AutoClicker");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        hour = addField(layout, "ساعت", "12");
        minute = addField(layout, "دقیقه", "00");
        second = addField(layout, "ثانیه", "00");
        millisecond = addField(layout, "میلی‌ثانیه", "000");

        count = addField(layout, "تعداد کلیک", "1");
        interval = addField(layout, "فاصله بین کلیک‌ها (میلی‌ثانیه)", "100");

        Button accessibility = new Button(this);
        accessibility.setText("فعال‌سازی دسترسی‌پذیری");
        layout.addView(accessibility);

        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
            ));
        });

        Button start = new Button(this);
        start.setText("ثبت زمان و شروع شمارش معکوس");
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

        setContentView(layout);
    }

    private EditText addField(
            LinearLayout layout,
            String title,
            String value) {

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.BLACK);
        label.setTextSize(15);
        layout.addView(label);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(2);
        edit.setText(value);
        layout.addView(edit);

        return edit;
    }

    private void scheduleClick() {

        try {

            int h = Integer.parseInt(hour.getText().toString());
            int m = Integer.parseInt(minute.getText().toString());
            int s = Integer.parseInt(second.getText().toString());
            int ms = Integer.parseInt(millisecond.getText().toString());

            int c = Integer.parseInt(count.getText().toString());
            int i = Integer.parseInt(interval.getText().toString());

            if (h < 0 || h > 23 ||
                    m < 0 || m > 59 ||
                    s < 0 || s > 59 ||
                    ms < 0 || ms > 999 ||
                    c < 1 || i < 1) {

                status.setText("مقادیر واردشده معتبر نیستند");
                return;
            }

            if (ClickService.instance == null) {
                status.setText("ابتدا دسترسی‌پذیری را فعال کن");
                return;
            }

            ClickService.instance.scheduleClick(
                    h, m, s, ms, c, i
            );

            status.setText("زمان‌بندی ثبت شد");

        } catch (Exception e) {
            status.setText("لطفاً همه مقادیر را بررسی کن");
        }
    }
}
