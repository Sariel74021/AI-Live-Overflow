package com.sariel.deskpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var lastTapTime = 0L
    private var touchSlop = 0
    private var clickRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = (resources.displayMetrics.density * 8).toInt()
        createNotificationChannel()

        webView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            setInitialScale(100)
            settings.allowFileAccess = true
            settings.javaScriptEnabled = true
        }
        webView.loadUrl("file:///android_asset/pet.html")

        val density = resources.displayMetrics.density
        layoutParams = WindowManager.LayoutParams(
            (180 * density).toInt(),
            (240 * density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        webView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        windowManager.addView(webView, layoutParams)
        startForeground(1, buildNotification())
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                isDragging = false
                clickRunnable?.let { handler.removeCallbacks(it) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    isDragging = true
                    clickRunnable?.let { handler.removeCallbacks(it) }
                }
                if (isDragging) {
                    layoutParams.x += dx.toInt()
                    layoutParams.y += dy.toInt()
                    downX = event.rawX
                    downY = event.rawY
                    try {
                        windowManager.updateViewLayout(webView, layoutParams)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val elapsed = System.currentTimeMillis() - downTime
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        lastTapTime = 0
                        evaluateJs("window.petEngine && window.petEngine.onDoubleTap && window.petEngine.onDoubleTap()")
                    } else if (elapsed >= 600) {
                        evaluateJs("window.petEngine && window.petEngine.onLongPress && window.petEngine.onLongPress()")
                    } else {
                        lastTapTime = now
                        clickRunnable = Runnable {
                            evaluateJs("window.petEngine && window.petEngine.onTap && window.petEngine.onTap()")
                        }
                        handler.postDelayed(clickRunnable!!, 320)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun evaluateJs(js: String) {
        handler.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "deskpet",
                "桌宠",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "deskpet")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("林渡在这里")
            .setContentText("桌宠运行中，戳一戳会有反应")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            windowManager.removeView(webView)
        } catch (_: Exception) {
        }
    }
}
