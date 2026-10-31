
package com.autoclicker;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    EditText hour, minute, second, count, interval;
    Button start, accessibility;
    TextView status;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);
        layout.setBackgroundColor(Color.WHITE);

        hour = field("ساعت", "12");
        minute = field("دقیقه", "00");
        second = field("ثانیه", "00");
        count = field("تعداد کلیک", "1");
        interval = field("فاصله کلیک (میلی‌ثانیه)", "100");

        accessibility = new Button(this);
        accessibility.setText("فعال‌سازی دسترسی‌پذیری");
        layout.addView(accessibility);

        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            ));
        });

        start = new Button(this);
        start.setText("ثبت زمان کلیک");
        layout.addView(start);

        status = new TextView(this);
        status.setText("آماده");
        status.setTextSize(18);
        layout.addView(status);

        start.setOnClickListener(v -> scheduleClick());

        setContentView(layout);
    }

    EditText field(String title, String value) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.BLACK);

        LinearLayout parent = (LinearLayout)
            getWindow().getDecorView().findViewById(
                android.R.id.content
            );

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setInputType(2);

        if (parent != null) {
            parent.addView(label);
            parent.addView(edit);
        }

        return edit;
    }

    void scheduleClick() {
        try {
            int h = Integer.parseInt(hour.getText().toString());
            int m = Integer.parseInt(minute.getText().toString());
            int s = Integer.parseInt(second.getText().toString());
            int c = Integer.parseInt(count.getText().toString());
            int i = Integer.parseInt(interval.getText().toString());

            if (h > 23 || m > 59 || s > 59 ||
                c < 1 || i < 1) {
                status.setText("مقادیر نامعتبر است");
                return;
            }

            Intent intent = new Intent(this, ClickService.class);
            intent.putExtra("hour", h);
            intent.putExtra("minute", m);
            intent.putExtra("second", s);
            intent.putExtra("count", c);
            intent.putExtra("interval", i);

            startService(intent);
            status.setText("زمان‌بندی ثبت شد");

        } catch (Exception e) {
            status.setText("لطفاً اطلاعات را بررسی کن");
        }
    }
}
