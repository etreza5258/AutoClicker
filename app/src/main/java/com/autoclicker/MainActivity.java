package com.autoclicker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.widget.*;

public class MainActivity extends Activity {

    EditText hour, minute, second, count, interval, x, y;
    TextView status;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(25, 25, 25, 25);
        layout.setBackgroundColor(Color.WHITE);

        hour = addField(layout, "ساعت", "12");
        minute = addField(layout, "دقیقه", "00");
        second = addField(layout, "ثانیه", "00");
        count = addField(layout, "تعداد کلیک", "1");
        interval = addField(layout, "فاصله کلیک (میلی‌ثانیه)", "100");
        x = addField(layout, "مختصات افقی کلیک", "500");
        y = addField(layout, "مختصات عمودی کلیک", "500");

        Button accessibility = new Button(this);
        accessibility.setText("فعال‌سازی دسترسی‌پذیری");
        layout.addView(accessibility);

        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            ));
        });

        Button start = new Button(this);
        start.setText("ثبت زمان کلیک");
        layout.addView(start);

        status = new TextView(this);
        status.setText("آماده");
        status.setTextSize(18);
        layout.addView(status);

        start.setOnClickListener(v -> scheduleClick());

        setContentView(layout);
    }

    private EditText addField(
        LinearLayout layout, String title, String value
    ) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.BLACK);
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
            int c = Integer.parseInt(count.getText().toString());
            int i = Integer.parseInt(interval.getText().toString());
            int posX = Integer.parseInt(x.getText().toString());
            int posY = Integer.parseInt(y.getText().toString());

            if (h > 23 || m > 59 || s > 59 ||
                c < 1 || i < 1 || posX < 0 || posY < 0) {
                status.setText("مقادیر واردشده معتبر نیستند");
                return;
            }

            if (ClickService.instance == null) {
                status.setText("ابتدا دسترسی‌پذیری را فعال کن");
                return;
            }

            ClickService.instance.scheduleClick(
                h, m, s, c, i, posX, posY
            );

            status.setText("زمان‌بندی ثبت شد");

        } catch (Exception e) {
            status.setText("لطفاً اطلاعات را بررسی کن");
        }
    }
}