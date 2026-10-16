package com.cmf.wakefixer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.OutputStream;

public class WakeService extends Service implements SensorEventListener {

    private SensorManager sensorManager;
    private PowerManager powerManager;
    private SharedPreferences prefs;
    private PowerManager.WakeLock cpuWakeLock;

    private Sensor accelerometer;
    private Sensor proximity;

    private boolean isNearPocket = false;
    private long lastWakeTime = 0L;
    private long lastTapTime = 0L;
    private int tapCount = 0;
    private float lastZ = 0f;
    private double lastPitch = 0.0;
    private boolean initialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("wake_prefs", Context.MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        acquirePersistentLock();
        startForegroundNotification();
        registerSensors();
        applyRootOptimizations();
    }

    private void acquirePersistentLock() {
        if (powerManager != null) {
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CMFWakeFixer::SensorKeepAliveLock"
            );
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();
        }
    }

    private void applyRootOptimizations() {
        if (!prefs.getBoolean("use_root", true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String tapEnabled = prefs.getBoolean("tap_to_wake", true) ? "1" : "0";
                    String[] commands = new String[] {
                        "cmd deviceidle whitelist +com.cmf.wakefixer",
                        "settings put secure double_tap_to_wake " + tapEnabled,
                        "settings put secure wake_gesture_enabled 1",
                        "echo " + tapEnabled + " > /proc/touchpanel/double_tap_enable 2>/dev/null",
                        "echo " + tapEnabled + " > /sys/class/sec/tsp/dt2w_enable 2>/dev/null",
                        "exit"
                    };
                    Process process = Runtime.getRuntime().exec("su");
                    OutputStream os = process.getOutputStream();
                    for (String cmd : commands) {
                        os.write((cmd + "\n").getBytes("UTF-8"));
                    }
                    os.flush();
                    os.close();
                    process.waitFor();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void registerSensors() {
        if (sensorManager == null) return;
        sensorManager.unregisterListener(this);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (proximity != null) {
            sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "RELOAD_CONFIG".equals(intent.getAction())) {
            registerSensors();
            applyRootOptimizations();
        }
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;

        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            float maxRange = event.sensor.getMaximumRange();
            float limit = maxRange > 5f ? 5f : maxRange;
            isNearPocket = event.values[0] < limit;
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (powerManager != null && powerManager.isInteractive()) {
                initialized = false;
                return;
            }

            if (prefs.getBoolean("pocket_check", true) && isNearPocket) {
                return;
            }

            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            long now = System.currentTimeMillis();
            if (now - lastWakeTime < 1500L) return;

            int raiseSensitivity = prefs.getInt("raise_sensitivity", 65);
            int shakeSensitivity = prefs.getInt("shake_sensitivity", 60);
            int tapSensitivity = prefs.getInt("tap_sensitivity", 70);

            double norm = Math.sqrt((double)(x * x + y * y + z * z));
            double pitch = Math.toDegrees(Math.atan2((double) y, (double) z));

            if (!initialized) {
                lastZ = z;
                lastPitch = pitch;
                initialized = true;
                return;
            }

            if (prefs.getBoolean("raise_to_wake", true)) {
                double pitchDelta = Math.abs(pitch - lastPitch);
                double requiredDelta = 45.0 - (raiseSensitivity * 0.35);
                boolean isUprightAngle = y > 2.5f && z > -2.0f;
                boolean isFlipOver = lastZ < -6.5f && z > 4.5f;

                if ((pitchDelta > requiredDelta && isUprightAngle) || isFlipOver) {
                    lastPitch = pitch;
                    lastZ = z;
                    wakeUpScreen();
                    return;
                }
            }

            if (prefs.getBoolean("shake_to_wake", true)) {
                double deltaForce = Math.abs(norm - SensorManager.GRAVITY_EARTH);
                double forceThreshold = 6.5 - (shakeSensitivity * 0.05);
                if (deltaForce > forceThreshold) {
                    lastPitch = pitch;
                    lastZ = z;
                    wakeUpScreen();
                    return;
                }
            }

            if (prefs.getBoolean("tap_to_wake", true)) {
                float zSpike = Math.abs(z - lastZ);
                float spikeThreshold = 3.2f - (tapSensitivity * 0.025f);
                if (zSpike > spikeThreshold && Math.abs(x) < 6.0f) {
                    long diff = now - lastTapTime;
                    if (diff >= 90L && diff <= 550L) {
                        tapCount++;
                        if (tapCount >= 2) {
                            tapCount = 0;
                            wakeUpScreen();
                            return;
                        }
                    } else if (diff > 550L) {
                        tapCount = 1;
                    }
                    lastTapTime = now;
                }
            }

            lastZ = z;
            lastPitch = pitch;
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeUpScreen() {
        lastWakeTime = System.currentTimeMillis();
        try {
            if (powerManager != null) {
                PowerManager.WakeLock screenLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "CMFWakeFixer::ScreenWakeTrigger"
                );
                screenLock.acquire(3000L);
            }
        } catch (Exception ignored) {
        }

        if (prefs.getBoolean("use_root", true)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 224"}).waitFor();
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startForegroundNotification() {
        String channelId = "cmf_wake_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "CMF Wake Daemon",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Raise and Tap to Wake sensors active while screen is off");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = builder
            .setContentTitle("CMF Wake Engine Active")
            .setContentText("Monitoring Raise, Shake, and Tap to Wake")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, notification, 1073741824);
        } else {
            startForeground(1001, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
