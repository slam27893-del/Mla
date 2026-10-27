package com.myai.assistant.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myai.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Captures **exactly one** frame, hands the bitmap to [CaptureBus] and stops.
 *
 * Why a short-lived foreground service: since Android 14 the projection must be
 * obtained from a foreground service whose type is `mediaProjection`, started
 * *before* `getMediaProjection()`. The notification exists only for the second or
 * two the capture takes, and the projection is always stopped in `finally`, so no
 * casting session survives the tap.
 *
 * Nothing is written to disk: the only copy of the frame is the in-memory bitmap
 * delivered to the requester, which is recycled straight after analysis.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || resultCode != Activity.RESULT_OK) {
            CaptureBus.fail("لا توجد صلاحية التقاط صالحة.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) {
            CaptureBus.fail("تعذّر تشغيل جلسة الالتقاط المؤقتة.")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            val bitmap = runCatching { runBlocking { captureOneFrame(resultCode, data) } }.getOrNull()
            if (bitmap != null) {
                CaptureBus.deliver(CaptureBus.Outcome.Success(bitmap))
            } else {
                CaptureBus.fail("تعذّر التقاط الصورة. أعد المحاولة أو استخدم زر المساعد الأساسي.")
            }
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.draft_capture_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.draft_capture_title))
            .setContentText(getString(R.string.draft_capture_body))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess
    }

    private suspend fun captureOneFrame(resultCode: Int, data: Intent): Bitmap? = withContext(Dispatchers.Default) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return@withContext null
        val projection = manager.getMediaProjection(resultCode, data) ?: return@withContext null

        val thread = HandlerThread("myai-capture").apply { start() }
        val handler = Handler(thread.looper)
        var reader: ImageReader? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        try {
            // Android 14+ requires a callback registered before createVirtualDisplay.
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() = Unit
                },
                handler
            )

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "myai-draft-capture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            val image = awaitImage(reader, CAPTURE_TIMEOUT_MS)
            image?.let { frame -> runCatching { frameToBitmap(frame, width, height) }.getOrNull() }
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { reader?.close() }
            runCatching { projection.stop() }
            thread.quitSafely()
        }
    }

    private suspend fun awaitImage(reader: ImageReader, timeoutMs: Long): Image? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) return image
            delay(POLL_MS)
        }
        return null
    }

    private fun frameToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            return if (paddedWidth == width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            }
        } finally {
            runCatching { image.close() }
        }
    }

    companion object {
        private const val ACTION_CAPTURE = "com.myai.assistant.action.CAPTURE_FRAME"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "myai_capture"
        private const val NOTIFICATION_ID = 1002
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val POLL_MS = 40L

        /** Starts a one-shot capture from a consent result produced by CaptureConsentActivity. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context.applicationContext, ScreenCaptureService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }
}
