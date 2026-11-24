package io.github.guanhui999.fakelocgum.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.guanhui999.fakelocgum.App
import io.github.guanhui999.fakelocgum.MainActivity
import io.github.guanhui999.fakelocgum.R
import io.github.guanhui999.fakelocgum.engine.JoystickController
import io.github.guanhui999.fakelocgum.engine.JoystickPadView
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/** A small foreground overlay; never uses a location service or another IPC channel. */
class JoystickOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = (application as App).store
    private lateinit var controller: JoystickController
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var pad: JoystickPadView? = null
    private var telemetryLabel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var hidden = false
    private var ending = false

    override fun onCreate() {
        super.onCreate()
        controller = JoystickController(store)
        wm = getSystemService(WindowManager::class.java)
        scope.launch {
            controller.status.collect { motion ->
                telemetryLabel?.text = String.format(Locale.ROOT,
                    "%s\n%.1f m · %.2f m/s · %s", motion.message, motion.distanceMetres,
                    motion.speed, store.delivery.value.name)
            }
        }
        scope.launch {
            store.config.collect { cfg ->
                if (!cfg.spoofs() || !cfg.overlayEnabled) finishOverlay()
                else pad?.isEnabled = cfg.playing
            }
        }
        scope.launch {
            while (isActive) {
                delay(700)
                if (!Settings.canDrawOverlays(this@JoystickOverlayService)) {
                    failed("悬浮窗权限已撤销，已停止摇杆；返回首页使用页面内控件")
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE) {
            store.update { it.copy(overlayEnabled = false, joystickSpeedMps = 0f) }
            finishOverlay(); return START_NOT_STICKY
        }
        if (!store.load().spoofs() || !store.load().overlayEnabled || !Settings.canDrawOverlays(this)) {
            finishOverlay(); return START_NOT_STICKY
        }
        if (ending) return START_NOT_STICKY
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮摇杆", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val show = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(SHOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val close = PendingIntent.getService(this, 3, Intent(this, javaClass).setAction(CLOSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("FakeLoc 悬浮摇杆")
                .setContentText("拖动标题移动；隐藏后可从此通知恢复")
                .setContentIntent(open).setOngoing(true)
                .addAction(0, "显示", show).addAction(0, "关闭摇杆", close).build()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
            hidden = false
            showWindow()
        }.onFailure { failed("悬浮摇杆启动失败：${it.javaClass.simpleName}"); Log.e(TAG, "overlay start failed", it) }
        return START_NOT_STICKY
    }

    private fun showWindow() {
        if (root != null || ending || hidden) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply { setColor(0xDD202735.toInt()); cornerRadius = dp(18).toFloat() }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "移动摇杆"; setTextColor(Color.WHITE); textSize = 12f
            gravity = Gravity.CENTER; contentDescription = "拖动此处移动悬浮窗"
        }
        val hide = TextView(this).apply { text = "－"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val close = TextView(this).apply { text = "×"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(hide, LinearLayout.LayoutParams(dp(40), dp(38)))
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(38)))
        view.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        val disk = JoystickPadView(this).apply {
            onVector = controller::input
            onCancel = controller::stop
            isEnabled = store.load().spoofs()
        }
        pad = disk
        // Explicit sizes prevent the header/default LayoutParams from consuming the pad's hit area.
        view.addView(disk, LinearLayout.LayoutParams(dp(170), dp(170)))
        telemetryLabel = TextView(this).apply {
            text = "推住方向持续移动\n上北下南，左西右东"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 3
        }
        view.addView(telemetryLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        val lp = params ?: WindowManager.LayoutParams(dp(180), dp(266),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // Keep below Android's 0.8 untrusted-overlay touch opacity threshold.
            alpha = 0.78f
            x = 0; y = dp(140)
        }.also { params = it }
        clamp(lp, snap = true)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    controller.stop(); disk.cancelInput()
                    downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).roundToInt()
                    lp.y = startY + (event.rawY - downY).roundToInt()
                    updateWindow(false); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { updateWindow(true); true }
                else -> false
            }
        }
        hide.contentDescription = "隐藏摇杆，从通知恢复"
        hide.setOnClickListener { hidden = true; removeWindow(); status.value = "已隐藏，可从通知或首页重新显示" }
        close.contentDescription = "关闭悬浮摇杆"
        close.setOnClickListener {
            store.update { it.copy(overlayEnabled = false, joystickSpeedMps = 0f) }
            finishOverlay()
        }
        wm.addView(view, lp)
        root = view
        status.value = "悬浮摇杆已显示"
    }

    private fun clamp(lp: WindowManager.LayoutParams, snap: Boolean) {
        val bounds: Rect
        var left = 0; var top = 0; var right = 0; var bottom = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            left = insets.left; top = insets.top; right = insets.right; bottom = insets.bottom
        } else {
            val size = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
            bounds = Rect(0, 0, size.x, size.y)
            fun bar(name: String): Int {
                val id = resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else resources.getDimensionPixelSize(id)
            }
            top = bar("status_bar_height"); bottom = bar("navigation_bar_height")
        }
        // Conservative insets also cover ROMs whose overlay origin is below the status bar.
        val maxX = (bounds.width() - right - lp.width).coerceAtLeast(left)
        val maxY = (bounds.height() - bottom - top - lp.height).coerceAtLeast(top)
        lp.x = lp.x.coerceIn(left, maxX)
        lp.y = lp.y.coerceIn(top, maxY)
        if (snap) lp.x = if (lp.x < (left + maxX) / 2) left else maxX
    }

    private fun updateWindow(snap: Boolean) {
        val view = root ?: return
        val lp = params ?: return
        clamp(lp, snap)
        runCatching { wm.updateViewLayout(view, lp) }.onFailure { failed("悬浮窗不可用，已回退页面摇杆") }
    }

    private fun removeWindow() {
        controller.stop()
        pad?.cancelInput()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null; pad = null; telemetryLabel = null
    }

    private fun finishOverlay() {
        if (ending) return
        ending = true
        removeWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        status.value = "悬浮摇杆已关闭"
    }

    private fun failed(message: String) {
        status.value = message
        store.update { it.copy(overlayEnabled = false, joystickSpeedMps = 0f) }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finishOverlay()
        status.value = message
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.stop(); pad?.cancelInput(); updateWindow(true)
    }
    override fun onDestroy() { scope.cancel(); removeWindow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "FakeLocGum/Overlay"
        private const val CHANNEL = "fakeloc_joystick"
        private const val ID = 1701
        private const val SHOW = "io.github.guanhui999.fakelocgum.overlay.SHOW"
        private const val CLOSE = "io.github.guanhui999.fakelocgum.overlay.CLOSE"
        private val status = MutableStateFlow("悬浮摇杆未开启")
        val state = status.asStateFlow()
        fun show(context: Context) {
            // Called only by a visible Activity/explicit user action, not a background launch loophole.
            runCatching { ContextCompat.startForegroundService(context,
                Intent(context, JoystickOverlayService::class.java).setAction(SHOW)) }
                .onFailure {
                    status.value = "悬浮摇杆启动失败，使用页面内摇杆"
                    (context.applicationContext as App).store.update { cfg -> cfg.copy(overlayEnabled = false, joystickSpeedMps = 0f) }
                    Log.e(TAG, "start service failed", it)
                }
        }
    }
}
