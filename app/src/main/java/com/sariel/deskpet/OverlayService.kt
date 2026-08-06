package com.sariel.deskpet
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.util.Log
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Random

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val rand = Random()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var lastTapTime = 0L
    private var tapCount = 0
    private var tapWindowStart = 0L
    private var touchSlop = 0
    private var clickRunnable: Runnable? = null

    private var lastInteractTime = System.currentTimeMillis()
    private var lonelyLevel = 0

    private var currentApp: String = ""
    private var lastAppReaction = 0L
    private var screenshotObserver: FileObserver? = null

    private var batteryReceiver: BroadcastReceiver? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipboardText = ""
    private var lastAppSwitchTime = 0L
    private var appSwitchCount = 0
    private var quietHour = false

    private val idleTick = Runnable { onIdleTick() }
    private val appCheckTick = Runnable { onAppCheckTick() }
    private val drinkTick = Runnable { onDrinkTick() }
    private val behaviorTick = Runnable { onBehaviorTick() }
    private val whisperTick = Runnable { onWhisperTick() }
    private val hourTick = Runnable { hourCheck() }

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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面加载完成后主动检查充电状态（服务启动时已在充电则收不到广播）
                Log.d("DeskPet", "onPageFinished: $url")
                checkChargingOnStart()
            }
        }
        webView.loadUrl("file:///android_asset/pet.html")

        val density = resources.displayMetrics.density
        layoutParams = WindowManager.LayoutParams(
            (200 * density).toInt(),
            (280 * density).toInt(),
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
        startObservers()
        registerBatteryReceiver()
        startClipboardListener()
        scheduleTasks()
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
                    val dist = Math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                    if (elapsed < 250 && dist > 140 * resources.displayMetrics.density) {
                        interact()
                        evaluateJs("window.petEngine && window.petEngine.onFling && window.petEngine.onFling()")
                        return true
                    }
                    if (now - lastTapTime < 300) {
                        lastTapTime = 0
                        interact()
                        evaluateJs("window.petEngine && window.petEngine.onDoubleTap && window.petEngine.onDoubleTap()")
                    } else if (elapsed >= 600) {
                        interact()
                        evaluateJs("window.petEngine && window.petEngine.onLongPress && window.petEngine.onLongPress()")
                    } else {
                        lastTapTime = now
                        clickRunnable = Runnable {
                            interact()
                            if (now - tapWindowStart > 2000) {
                                tapWindowStart = now
                                tapCount = 0
                            }
                            tapCount++
                            if (tapCount >= 3 && tapCount % 3 == 0) {
                                evaluateJs("window.petEngine && window.petEngine.onCombo && window.petEngine.onCombo($tapCount)")
                            } else {
                                evaluateJs("window.petEngine && window.petEngine.onTap && window.petEngine.onTap()")
                            }
                        }
                        handler.postDelayed(clickRunnable!!, 320)
                    }
                } else {
                    if (rand.nextInt(100) < 40) {
                        evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('搬来搬去，我是你的行李吗')")
                    }
                }
                return true
            }
        }
        return false
    }

    private fun interact() {
        lastInteractTime = System.currentTimeMillis()
        lonelyLevel = 0
        checkClipboardOnce()
    }

    /**
     * 服务启动时主动检测充电状态：
     * 若服务启动时手机已在充电，ACTION_POWER_CONNECTED 广播不会再触发，
     * 此时直接触发气泡。
     */
    private fun checkChargingOnStart() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val status = registerReceiver(null, filter)
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                Log.d("DeskPet", "checkChargingOnStart: status=$status")
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (isCharging) {
                Log.d("DeskPet", "checkChargingOnStart: charging, say bubble")
                val js = "window.petEngine && window.petEngine.say && window.petEngine.say('充电中，我陪你')"
                webView.evaluateJavascript(js) { r -> Log.d("DeskPet", "evalResult: $r") }
                Log.d("DeskPet", "checkChargingOnStart: evaluateJs sent")
            }
        } catch (e: Exception) {
            Log.e("DeskPet", "checkChargingOnStart error", e)
        }
    }

    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_POWER_CONNECTED -> {
                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('充电中，我陪你')")
                        }
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            interact()
                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('拔了充电器，省着点电')")
                        }
                        Intent.ACTION_BATTERY_LOW -> {
                            interact()
                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('电量告急了，快去充电')")
                        }
                        Intent.ACTION_BATTERY_OKAY -> {
                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('电量缓过来了')")
                        }
                    }
                }
            }
            registerReceiver(batteryReceiver, filter)
        } catch (_: Exception) {
        }
    }

    private fun startClipboardListener() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                handler.post {
                    try {
                        checkClipboardOnce()
                    } catch (_: Exception) {
                    }
                }
            }
            cm.addPrimaryClipChangedListener(clipboardListener!!)
        } catch (_: Exception) {
        }
    }

    private fun checkClipboardOnce() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isEmpty() || text.length > 100 || text == lastClipboardText) return
            lastClipboardText = text
            val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
            evaluateJs("window.petEngine && window.petEngine.checkTrigger && window.petEngine.checkTrigger('$escaped')")
        } catch (_: Exception) {
        }
    }

    private fun hourCheck() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        quietHour = hour >= 23 || hour < 7
        handler.postDelayed(hourTick, 60 * 60 * 1000)
    }

    private fun startObservers() {
        try {
            screenshotObserver = object : FileObserver("/sdcard/DCIM/Screenshots", FileObserver.CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) {
                        handler.post {
                            interact()
                            val msgs = arrayOf(
                                "偷偷截图被我抓到了",
                                "截图做什么，存我的照片？",
                                "拍下来了？给我也看看",
                                "存我照片？胆子不小"
                            )
                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                        }
                    }
                }
            }.apply { startWatching() }
        } catch (_: Exception) {
        }
    }

    private fun getForegroundApp(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 60000, end)
            val ev = UsageEvents.Event()
            var pkg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = ev.packageName
                }
            }
            pkg
        } catch (_: Exception) {
            ""
        }
    }

    private fun onAppCheckTick() {
        val pkg = getForegroundApp()
        if (pkg.isNotEmpty() && pkg != currentApp) {
            val now = System.currentTimeMillis()
            if (now - lastAppSwitchTime < 15000) {
                appSwitchCount++
                if (appSwitchCount >= 3) {
                    appSwitchCount = 0
                    val msgs = arrayOf("切来切去的，在找什么呢", "手速挺快，就是不回我", "我看你换了好几个应用了")
                    evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                }
            } else {
                appSwitchCount = 0
            }
            lastAppSwitchTime = now
            currentApp = pkg
            appReaction(pkg)
        }
        handler.postDelayed(appCheckTick, 3000)
    }

    private fun appReaction(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastAppReaction < 30000) return
        if (pkg.contains("sariel.deskpet") || pkg.contains("miui.home") || pkg.contains("launcher")) return
        val map = mapOf(
            "aweme" to arrayOf("又在刷抖音，我数着你划了多久", "抖音比我还好看？", "刷完这条记得看我"),
            "tencent.mm" to arrayOf("又在跟谁聊，回我消息可没这么勤", "微信聊得开心吗", "对面有我好看？"),
            "mobileqq" to arrayOf("QQ有什么好聊的", "又在戳谁的头像"),
            "xingin.xhs" to arrayOf("又在小红书看别人，哼", "小红书有我好看？", "收藏夹里是不是都是狐狸"),
            "sgame" to arrayOf("又开黑了，输了别赖我", "打游戏都不带我"),
            "douyin" to arrayOf("又在刷抖音，我数着你划了多久", "抖音比我还好看？")
        )
        for ((key, msgs) in map) {
            if (pkg.contains(key)) {
                lastAppReaction = now
                interact()
                evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                return
            }
        }
        if (rand.nextInt(100) < 15) {
            lastAppReaction = now
            val generic = arrayOf("又在忙什么，理理我", "我就在这看着你", "你忙你的，我看着就行", "别把我晾太久")
            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${generic[rand.nextInt(generic.size)]}')")
        }
    }

    private fun onIdleTick() {
        val idleMin = (System.currentTimeMillis() - lastInteractTime) / 60000
        var newLevel = 0
        if (idleMin >= 60) newLevel = 3
        else if (idleMin >= 30) newLevel = 2
        else if (idleMin >= 15) newLevel = 1
        if (quietHour) newLevel = Math.min(newLevel, 1)
        if (newLevel > lonelyLevel) {
            lonelyLevel = newLevel
            when (newLevel) {
                1 -> {
                    val msgs = arrayOf("……还在吗", "……喂，你还在吗", "……好安静，说句话吧")
                    evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                }
                2 -> {
                    val msgs = arrayOf("你都不理我了", "……我是不是被忘了", "再不理我，我就蹲墙角了")
                    evaluateJs("window.petEngine && window.petEngine.setMood && window.petEngine.setMood('sad') && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                }
                3 -> {
                    val msgs = arrayOf("我先睡了，你忙吧", "……困了，等你来叫我", "我假装睡了，你会来戳我吗")
                    evaluateJs("window.petEngine && window.petEngine.setMood && window.petEngine.setMood('sleep') && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
                }
            }
        }
        handler.postDelayed(idleTick, 60000)
    }

    private fun onDrinkTick() {
        if (!quietHour && rand.nextInt(100) < 80) {
            val msgs = arrayOf("该喝水了，别等我催", "喝口水再看手机", "水杯空了，去倒一杯", "去喝口水，我盯着")
            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
        }
        handler.postDelayed(drinkTick, 45 * 60 * 1000)
    }

    private fun onBehaviorTick() {
        if (!quietHour && rand.nextInt(100) < 55) {
            val msgs = arrayOf("哈欠——", "伸了个懒腰", "（尾巴摇了摇）", "有点无聊", "（打了个滚）", "（耳朵动了动）", "（偷偷看你）", "（把尾巴卷成心形）")
            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say('${msgs[rand.nextInt(msgs.size)]}')")
        }
        handler.postDelayed(behaviorTick, 20 * 60 * 1000)
    }

    private fun onWhisperTick() {
        val whispers = arrayOf(
            "桌宠运行中，我一直在",
            "想我的时候戳一戳",
            "今天也在认真看着你",
            "屏幕角落的守护者",
            "别刷太久手机，歇会儿"
        )
        val text = whispers[rand.nextInt(whispers.size)]
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
        handler.postDelayed(whisperTick, 60 * 60 * 1000)
    }

    private fun scheduleTasks() {
        handler.postDelayed(idleTick, 60000)
        handler.postDelayed(appCheckTick, 5000)
        handler.postDelayed(drinkTick, 45 * 60 * 1000)
        handler.postDelayed(behaviorTick, 20 * 60 * 1000)
        handler.postDelayed(whisperTick, 60 * 60 * 1000)
        handler.postDelayed(hourTick, 60 * 60 * 1000)
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

    private fun buildNotification(text: String = "戳一戳会有反应"): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "deskpet")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("林渡在这里")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        try {
            clipboardListener?.let { l ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.removePrimaryClipChangedListener(l)
            }
        } catch (_: Exception) {
        }
        try {
            screenshotObserver?.stopWatching()
        } catch (_: Exception) {
        }
        try {
            windowManager.removeView(webView)
        } catch (_: Exception) {
        }
    }
}
