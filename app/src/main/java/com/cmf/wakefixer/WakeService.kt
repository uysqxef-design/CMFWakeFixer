package com.cmf.wakefixer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class WakeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var prefs: SharedPreferences
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private var accelerometer: Sensor? = null
    private var proximity: Sensor? = null

    private var isNearPocket = false
    private var lastWakeTime = 0L
    private var lastTapTime = 0L
    private var tapCount = 0
    private var lastZ = 0f
    private var lastPitch = 0.0
    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wake_prefs", Context.MODE_PRIVATE)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        acquirePersistentLock()
        startForegroundNotification()
        registerSensors()
        applyRootOptimizations()
    }

    private fun acquirePersistentLock() {
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CMFWakeFixer::SensorKeepAliveLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun applyRootOptimizations() {
        if (!prefs.getBoolean("use_root", true)) return
        Thread {
            try {
                val tapEnabled = if (prefs.getBoolean("tap_to_wake", true)) "1" else "0"
                val commands = arrayOf(
                    "cmd deviceidle whitelist +com.cmf.wakefixer",
                    "settings put secure double_tap_to_wake $tapEnabled",
                    "settings put secure wake_gesture_enabled 1",
                    "echo $tapEnabled > /proc/touchpanel/double_tap_enable 2>/dev/null",
                    "echo $tapEnabled > /sys/class/sec/tsp/dt2w_enable 2>/dev/null"
                )
                val process = Runtime.getRuntime().exec("su")
                val os = process.outputStream
                for (cmd in commands) {
                    os.write("$cmd\n".toByteArray())
                }
                os.write("exit\n".toByteArray())
                os.flush()
                os.close()
                process.waitFor()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RELOAD_CONFIG") {
            registerSensors()
            applyRootOptimizations()
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val maxRange = event.sensor.maximumRange
            isNearPocket = event.values[0] < (if (maxRange > 5f) 5f else maxRange)
            return
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (powerManager.isInteractive) {
                initialized = false
                return
            }

            if (prefs.getBoolean("pocket_check", true) && isNearPocket) {
                return
            }

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val now = System.currentTimeMillis()
            if (now - lastWakeTime < 1500) return

            val raiseSensitivity = prefs.getInt("raise_sensitivity", 65)
            val shakeSensitivity = prefs.getInt("shake_sensitivity", 60)
            val tapSensitivity = prefs.getInt("tap_sensitivity", 70)

            val norm = sqrt((x * x + y * y + z * z).toDouble())
            val pitch = Math.toDegrees(atan2(y.toDouble(), z.toDouble()))

            if (!initialized) {
                lastZ = z
                lastPitch = pitch
                initialized = true
                return
            }

            // 1. Raise / Turn Over Detection
            if (prefs.getBoolean("raise_to_wake", true)) {
                val pitchDelta = abs(pitch - lastPitch)
                val requiredDelta = 45.0 - (raiseSensitivity * 0.35)
                val isUprightAngle = y > 2.5f && z > -2.0f
                val isFlipOver = lastZ < -6.5f && z > 4.5f

                if ((pitchDelta > requiredDelta && isUprightAngle) || isFlipOver) {
                    lastPitch = pitch
                    lastZ = z
                    wakeUpScreen()
                    return
                }
            }

            // 2. Micro-Shake / Hold Movement Detection
            if (prefs.getBoolean("shake_to_wake", true)) {
                val deltaForce = abs(norm - SensorManager.GRAVITY_EARTH)
                val forceThreshold = 6.5 - (shakeSensitivity * 0.05)
                if (deltaForce > forceThreshold) {
                    lastPitch = pitch
                    lastZ = z
                    wakeUpScreen()
                    return
                }
            }

            // 3. Double-Tap Screen Shock Detection (works even when touch digitizer is off)
            if (prefs.getBoolean("tap_to_wake", true)) {
                val zSpike = abs(z - lastZ)
                val spikeThreshold = 3.2f - (tapSensitivity * 0.025f)
                if (zSpike > spikeThreshold && abs(x) < 6.0f) {
                    if (now - lastTapTime in 90..550) {
                        tapCount++
                        if (tapCount >= 2) {
                            tapCount = 0
                            wakeUpScreen()
                            return
                        }
                    } else if (now - lastTapTime >= 550) {
                        tapCount = 1
                    }
                    lastTapTime = now
                }
            }

            lastZ = z
            lastPitch = pitch
        }
    }

    private fun wakeUpScreen() {
        lastWakeTime = System.currentTimeMillis()
        try {
            @Suppress("DEPRECATION")
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "CMFWakeFixer::ScreenWakeTrigger"
            )
            screenLock.acquire(3000)
        } catch (_: Exception) {
        }

        if (prefs.getBoolean("use_root", true)) {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 224")).waitFor()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startForegroundNotification() {
        val channelId = "cmf_wake_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CMF Wake Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Raise and Tap to Wake sensors active while screen is off"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CMF Wake Engine Active")
            .setContentText("Monitoring Raise, Shake, and Tap to Wake")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
