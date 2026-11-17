package com.phonecast.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateSeek: SeekBar
    private lateinit var bitrateView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refreshStatus = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(PhoneCastService.PREFS, Context.MODE_PRIVATE)
            statusView.text = buildString {
                append("Status: ")
                append(prefs.getString(PhoneCastService.PREF_STATUS, "Ready"))
                val ip = localIpv4()
                val server = "http://$ip:${PhoneCastService.HTTP_PORT}"
                append("\n\nCyber TV (Xtream Codes) settings:")
                append("\nServer: $server")
                append("\nUsername: phone")
                append("\nPassword: phone")
                append("\nStream format: TS")
                append("\n(Any username and password work.)")
                append("\n\nDirect links:")
                append("\nTS: $server/live/phone/phone/1.ts")
                append("\nHLS: $server/phonecast.m3u8")
                append("\nM3U: $server/get.php?username=phone&password=phone&type=m3u_plus")
                append("\n\nRequests from the TV:\n")
                append(RequestLog.text())
                append("\n\nKeep the phone and PS4 on the same Wi-Fi network.")
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        buildInterface()
        handler.post(refreshStatus)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshStatus)
        super.onDestroy()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xFF08121D.toInt())
        }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        root.addView(text("PHONECAST", 28f, 0xFF00D9F5.toInt(), true))
        root.addView(text("Phone screen as a live channel for Cyber TV", 16f, 0xFFE5F1FA.toInt()))
        root.addView(text(
            "Local Wi-Fi • H.264 • Xtream Codes • TS / HLS • No root",
            13f,
            0xFF9DB4C8.toInt()
        ).apply { setPadding(0, dp(8), 0, dp(18)) })

        root.addView(label("Capture size"))
        resolutionSpinner = spinner(listOf("480p (854 × 480)", "720p (1280 × 720)", "1080p (1920 × 1080)"))
        resolutionSpinner.setSelection(1)
        root.addView(resolutionSpinner)

        root.addView(label("Frame rate"))
        fpsSpinner = spinner(listOf("15 FPS", "24 FPS", "30 FPS"))
        fpsSpinner.setSelection(2)
        root.addView(fpsSpinner)

        bitrateView = label("Video bitrate: 5 Mbps")
        root.addView(bitrateView)
        bitrateSeek = SeekBar(this).apply {
            max = 11
            progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    bitrateView.text = "Video bitrate: ${progress + 1} Mbps"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(bitrateSeek)

        val start = Button(this).apply {
            text = "Start screen sharing"
            setTextColor(0xFF061018.toInt())
            setBackgroundColor(0xFF00D9F5.toInt())
            setOnClickListener { requestCapturePermission() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(18)
        })

        val stop = Button(this).apply {
            text = "Stop screen sharing"
            setOnClickListener {
                startService(Intent(this@MainActivity, PhoneCastService::class.java)
                    .setAction(PhoneCastService.ACTION_STOP))
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
        })

        statusView = text("Status: Ready", 14f, 0xFFE5F1FA.toInt()).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF102131.toInt())
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
    }

    private fun requestCapturePermission() {
        saveSettings()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("The result is required to start the user-approved MediaProjection session.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            getSharedPreferences(PhoneCastService.PREFS, MODE_PRIVATE).edit()
                .putString(PhoneCastService.PREF_STATUS, "Capture permission denied")
                .apply()
            return
        }
        val serviceIntent = Intent(this, PhoneCastService::class.java)
            .setAction(PhoneCastService.ACTION_START)
            .putExtra(PhoneCastService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(PhoneCastService.EXTRA_RESULT_DATA, data)
            .putExtra(PhoneCastService.EXTRA_HEIGHT, selectedHeight())
            .putExtra(PhoneCastService.EXTRA_FPS, selectedFps())
            .putExtra(PhoneCastService.EXTRA_BITRATE, (bitrateSeek.progress + 1) * 1_000_000)
        startForegroundService(serviceIntent)
    }

    private fun saveSettings() {
        getSharedPreferences(PhoneCastService.PREFS, MODE_PRIVATE).edit()
            .putInt(PhoneCastService.PREF_HEIGHT, selectedHeight())
            .putInt(PhoneCastService.PREF_FPS, selectedFps())
            .putInt(PhoneCastService.PREF_BITRATE, (bitrateSeek.progress + 1) * 1_000_000)
            .apply()
    }

    private fun selectedHeight(): Int = when (resolutionSpinner.selectedItemPosition) {
        0 -> 480
        2 -> 1080
        else -> 720
    }

    private fun selectedFps(): Int = when (fpsSpinner.selectedItemPosition) {
        0 -> 15
        1 -> 24
        else -> 30
    }

    private fun localIpv4(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotBlank() && !it.startsWith("127.") }
                ?: "not connected"
        } catch (_: Exception) {
            "not connected"
        }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items
        )
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun label(value: String) = text(value, 14f, 0xFFB7CBDC.toInt(), true).apply {
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE = 4001
    }
}

