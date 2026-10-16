package com.cmf.wakefixer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("wake_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E0E10"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 80, 56, 80)
        }

        val title = TextView(this).apply {
            text = "CMF Wake Control"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "Android 16 Root-Assisted Raise, Shake & Tap to Wake"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5722"))
            setPadding(0, 0, 0, 48)
        }
        container.addView(subtitle)

        fun addToggle(label: String, key: String, defaultVal: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 24, 0, 24)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(this).apply {
                isChecked = prefs.getBoolean(key, defaultVal)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(key, isChecked).apply()
                    reloadService()
                }
            }
            row.addView(tv)
            row.addView(sw)
            container.addView(row)
        }

        fun addSlider(label: String, key: String, defaultVal: Int) {
            val tv = TextView(this).apply {
                val current = prefs.getInt(key, defaultVal)
                text = "$label: $current%"
                textSize = 15f
                setTextColor(Color.LTGRAY)
                setPadding(0, 32, 0, 12)
            }
            val seek = SeekBar(this).apply {
                max = 100
                progress = prefs.getInt(key, defaultVal)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val clamped = if (progress < 10) 10 else progress
                        tv.text = "$label: $clamped%"
                        prefs.edit().putInt(key, clamped).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        reloadService()
                    }
                })
            }
            container.addView(tv)
            container.addView(seek)
        }

        addToggle("Master Background Engine", "service_enabled", true)
        addToggle("Raise & Turn Over to Wake", "raise_to_wake", true)
        addToggle("Micro-Shake / Hold to Wake", "shake_to_wake", true)
        addToggle("Double-Tap Screen to Wake", "tap_to_wake", true)
        addToggle("Pocket Proximity Protection", "pocket_check", true)
        addToggle("KernelSU Superuser Assist", "use_root", true)

        addSlider("Raise Angle Sensitivity", "raise_sensitivity", 65)
        addSlider("Shake & Hold Sensitivity", "shake_sensitivity", 60)
        addSlider("Double-Tap Shock Sensitivity", "tap_sensitivity", 70)

        val rootButton = Button(this).apply {
            text = "Grant Root & Whitelist Battery"
            setBackgroundColor(Color.parseColor("#FF5722"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                requestPermissionsAndBattery()
                Thread {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Root Granted & Doze Whitelisted", Toast.LENGTH_SHORT).show()
                        }
                        reloadService()
                    } catch (_: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Grant Superuser in KernelSU Manager first", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 64 }
        container.addView(rootButton, btnParams)

        scroll.addView(container)
        setContentView(scroll)

        requestPermissionsAndBattery()
        reloadService()
    }

    private fun requestPermissionsAndBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun reloadService() {
        val prefs = getSharedPreferences("wake_prefs", Context.MODE_PRIVATE)
        val intent = Intent(this, WakeService::class.java).apply {
            action = "RELOAD_CONFIG"
        }
        if (prefs.getBoolean("service_enabled", true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(Intent(this, WakeService::class.java))
        }
    }
}
