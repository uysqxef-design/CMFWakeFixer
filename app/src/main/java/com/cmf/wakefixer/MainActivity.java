package com.cmf.wakefixer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("wake_prefs", Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0E0E10"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(56, 80, 56, 80);

        TextView title = new TextView(this);
        title.setText("CMF Wake Control");
        title.setTextSize(26f);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 16);
        container.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android 16 Root-Assisted Raise, Shake & Tap to Wake");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(Color.parseColor("#FF5722"));
        subtitle.setPadding(0, 0, 0, 48);
        container.addView(subtitle);

        addToggle(container, "Master Background Engine", "service_enabled", true);
        addToggle(container, "Raise & Turn Over to Wake", "raise_to_wake", true);
        addToggle(container, "Micro-Shake / Hold to Wake", "shake_to_wake", true);
        addToggle(container, "Double-Tap Screen to Wake", "tap_to_wake", true);
        addToggle(container, "Pocket Proximity Protection", "pocket_check", true);
        addToggle(container, "KernelSU Superuser Assist", "use_root", true);

        addSlider(container, "Raise Angle Sensitivity", "raise_sensitivity", 65);
        addSlider(container, "Shake & Hold Sensitivity", "shake_sensitivity", 60);
        addSlider(container, "Double-Tap Shock Sensitivity", "tap_sensitivity", 70);

        Button rootButton = new Button(this);
        rootButton.setText("Grant Root & Whitelist Battery");
        rootButton.setBackgroundColor(Color.parseColor("#FF5722"));
        rootButton.setTextColor(Color.WHITE);
        rootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissionsAndBattery();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Runtime.getRuntime().exec(new String[]{"su", "-c", "id"}).waitFor();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Root Granted & Doze Whitelisted", Toast.LENGTH_SHORT).show();
                                }
                            });
                            reloadService();
                        } catch (Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Grant Superuser in KernelSU Manager first", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.topMargin = 64;
        container.addView(rootButton, btnParams);

        scroll.addView(container);
        setContentView(scroll);

        requestPermissionsAndBattery();
        reloadService();
    }

    private void addToggle(LinearLayout container, String label, final String key, boolean defaultVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 24, 0, 24);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(16f);
        tv.setTextColor(Color.WHITE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, defaultVal));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
                reloadService();
            }
        });

        row.addView(tv);
        row.addView(sw);
        container.addView(row);
    }

    private void addSlider(LinearLayout container, final String label, final String key, int defaultVal) {
        final TextView tv = new TextView(this);
        int current = prefs.getInt(key, defaultVal);
        tv.setText(label + ": " + current + "%");
        tv.setTextSize(15f);
        tv.setTextColor(Color.LTGRAY);
        tv.setPadding(0, 32, 0, 12);

        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(current);
        
        // ---- FIXED CUT-OFF HERE ----
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tv.setText(label + ": " + progress + "%");
                prefs.edit().putInt(key, progress).apply(); // Save sensitivity live
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                reloadService(); // Force the sensor engine to update
            }
        });

        container.addView(tv);
        container.addView(seek);
    }

    private void requestPermissionsAndBattery() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reloadService() {
        if (prefs.getBoolean("service_enabled", true)) {
            Intent intent = new Intent(this, WakeService.class);
            intent.setAction("RELOAD_CONFIG");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } else {
            stopService(new Intent(this, WakeService.class));
        }
    }
}
