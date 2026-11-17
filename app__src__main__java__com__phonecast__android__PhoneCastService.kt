package com.phonecast.android

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCastService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var hlsServer: HlsHttpServer? = null
    private var announcer: UdpDiscovery? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var lastStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        startForegroundCompat()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtraCompat(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode != RESULT_OK) {
            updateStatus("Screen-capture permission missing")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(
                resultCode,
                resultData,
                intent.getIntExtra(EXTRA_HEIGHT, 720),
                intent.getIntExtra(EXTRA_FPS, 30),
                intent.getIntExtra(EXTRA_BITRATE, 5_000_000)
            )
        } catch (error: Exception) {
            updateStatus("Failed: ${error.message ?: error.javaClass.simpleName}")
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, requestedHeight: Int, fps: Int, bitrate: Int) {
        stopCapture(removeForeground = false)
        announcer = UdpDiscovery(DISCOVERY_PORT)
        hlsServer = HlsHttpServer(HTTP_PORT) {
            announcer?.start()
            updateStatus("Ready: add the phone in Cyber TV (Xtream Codes) and open the channel")
        }.also { it.start() }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection could not be created")
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                updateStatus("Screen capture stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val capture = fitResolution(metrics.widthPixels, metrics.heightPixels, requestedHeight)
        val width = capture.first
        val height = capture.second

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // A still screen produces no new frames; repeat the last one so the TV stream never stalls.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        val densityDpi = metrics.densityDpi
        virtualDisplay = projection!!.createVirtualDisplay(
            "PhoneCast",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        ) ?: throw IllegalStateException("Virtual display could not be created")

        running.set(true)
        outputThread = Thread({ drainEncoder() }, "PhoneCast-Encoder-Output").apply { start() }
        updateStatus("Capturing ${width}x${height} @ ${fps} FPS; preparing stream")
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val codec = encoder ?: break
            try {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val muxer = hlsServer?.muxer ?: continue
                        format.getByteBuffer("csd-0")?.let { muxer.setCodecConfig(copyBuffer(it)) }
                        format.getByteBuffer("csd-1")?.let { muxer.setCodecConfig(copyBuffer(it)) }
                        updateStatus("Encoding H.264; waiting for an IDR frame")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            hlsServer?.muxer?.consume(bytes, info.presentationTimeUs, info.flags)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                updateStatus("Streaming H.264 ${encoderSizeLabel()} (TS / HLS)")
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            } catch (error: Exception) {
                updateStatus("Encoder stopped: ${error.message ?: error.javaClass.simpleName}")
                running.set(false)
                stopSelf()
            }
        }
    }

    private fun encoderSizeLabel(): String {
        val format = try { encoder?.outputFormat } catch (_: Exception) { null }
        val width = format?.getIntegerOrNull(MediaFormat.KEY_WIDTH) ?: 0
        val height = format?.getIntegerOrNull(MediaFormat.KEY_HEIGHT) ?: 0
        return if (width > 0 && height > 0) "${width}x${height}" else "video"
    }

    private fun stopCapture(removeForeground: Boolean = true) {
        running.set(false)
        outputThread?.let { if (it !== Thread.currentThread()) it.join(1500) }
        outputThread = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        val activeProjection = projection
        projection = null
        try { activeProjection?.stop() } catch (_: Exception) { }
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
        encoder = null
        try { inputSurface?.release() } catch (_: Exception) { }
        inputSurface = null
        try { announcer?.close() } catch (_: Exception) { }
        announcer = null
        try { hlsServer?.close() } catch (_: Exception) { }
        hlsServer = null
        updateStatus("Stopped")
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PhoneCast screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, PhoneCastService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PhoneCast is sharing your screen")
            .setContentText("Tap Stop to end capture and streaming.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateStatus(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_STATUS, status).apply()
    }

    private fun fitResolution(displayWidth: Int, displayHeight: Int, requestedHeight: Int): Pair<Int, Int> {
        val ratio = displayWidth.toDouble() / displayHeight.coerceAtLeast(1)
        val width = ((requestedHeight * ratio).toInt() / 2) * 2
        return width.coerceAtLeast(2) to requestedHeight.coerceAtLeast(2)
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java)
        else getParcelableExtra(key) as? Intent
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        try { getInteger(key) } catch (_: Exception) { null }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.phonecast.android.START"
        const val ACTION_STOP = "com.phonecast.android.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HEIGHT = "capture_height"
        const val EXTRA_FPS = "capture_fps"
        const val EXTRA_BITRATE = "capture_bitrate"
        const val PREFS = "phonecast"
        const val PREF_STATUS = "status"
        const val PREF_HEIGHT = "height"
        const val PREF_FPS = "fps"
        const val PREF_BITRATE = "bitrate"
        const val HTTP_PORT = 8088
        const val DISCOVERY_PORT = 39394
        private const val MIME_TYPE = "video/avc"
        private const val CHANNEL_ID = "phonecast_capture"
        private const val NOTIFICATION_ID = 1452
        private const val RESULT_OK = Activity.RESULT_OK
    }
}

