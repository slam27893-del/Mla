package com.myai.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.view.ContextThemeWrapper
import com.myai.assistant.MyAiApplication
import com.myai.assistant.R
import com.myai.assistant.agent.ConfirmationHandler
import com.myai.assistant.databinding.OverlayBubbleBinding
import com.myai.assistant.ui.MainActivity
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service (type specialUse) that owns the floating bubble and the
 * floating chat panel. It only starts after:
 *   1. the user granted "display over other apps", and
 *   2. the user explicitly tapped "floating assistant" in MyAI.
 *
 * The permanent notification makes the service visible and stoppable, which is
 * both an Android requirement and a privacy feature.
 */
class FloatingAssistantService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var container: com.myai.assistant.AppContainer
    private lateinit var panel: OverlayPanel
    private lateinit var confirmation: OverlayConfirmation

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private val themedContext: Context by lazy {
        ContextThemeWrapper(this, R.style.Theme_MyAi)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        container = (application as MyAiApplication).container
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        confirmation = OverlayConfirmation(themedContext, windowManager)
        val confirmer = ConfirmationHandler { request -> confirmation.show(request) }
        panel = OverlayPanel(
            context = themedContext,
            windowManager = windowManager,
            controller = container.chatController,
            confirmer = confirmer,
            onClosed = { /* bubble stays available */ },
            localResults = { stepId -> container.screenVault.get(stepId) }
        )
        lifecycleScope.launch { container.chatController.load() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { panel.hide() }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        )

        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopService = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stopService)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_LONG).show()
        }.isSuccess
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val binding = OverlayBubbleBinding.inflate(LayoutInflater.from(themedContext))
        val view = binding.root
        val metrics = resources.displayMetrics
        val size = dp(56)

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(8)).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, metrics.heightPixels / 2)
                .coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        }

        view.setOnTouchListener(createDragListener(view, params))
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (added) {
            bubbleView = view
            bubbleParams = params
        }
    }

    private fun createDragListener(
        view: View,
        params: WindowManager.LayoutParams
    ): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    val metrics = resources.displayMetrics
                    val size = dp(56)
                    params.x = (initialX + dx).toInt()
                        .coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
                    params.y = (initialY + dy).toInt()
                        .coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) openPanel()
                    prefs.edit()
                        .putInt(KEY_X, params.x)
                        .putInt(KEY_Y, params.y)
                        .apply()
                    true
                }

                else -> false
            }
        }
    }

    private fun openPanel() {
        if (!panel.isShowing) panel.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.myai.assistant.action.STOP_FLOATING"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "myai_floating"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "myai.floating"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, FloatingAssistantService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, FloatingAssistantService::class.java)
            )
        }
    }
}
