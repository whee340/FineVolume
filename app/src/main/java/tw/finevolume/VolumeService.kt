package tw.finevolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** 常駐服務：保持音量效果，並在切換輸出裝置或 App 時自動套用對應的設定 */
class VolumeService : Service() {

    private lateinit var am: AudioManager
    private lateinit var store: ProfileStore
    private val engine = GainEngine()
    private val handler = Handler(Looper.getMainLooper())
    private var ignoreVolumeUntil = 0L
    private lateinit var detector: AppDetector

    // 切換 App／裝置時平滑過渡音量，避免突然跳一下
    private var appliedGain = -1f
    private var rampStep = 0
    private var rampFrom = 0f
    private var rampTo = 0f
    private val ramp = object : Runnable {
        override fun run() {
            rampStep++
            val t = rampStep / RAMP_STEPS.toFloat()
            val fromDb = GainMath.toDb(rampFrom).coerceAtLeast(-60f)
            val toDb = GainMath.toDb(rampTo).coerceAtLeast(-60f)
            val g = if (rampStep >= RAMP_STEPS) rampTo else Math.pow(10.0, ((fromDb + (toDb - fromDb) * t) / 20f).toDouble()).toFloat()
            pushGain(g)
            if (rampStep < RAMP_STEPS) handler.postDelayed(this, RAMP_INTERVAL_MS)
        }
    }
    private var rampEnabled = true
    private var rampLimiter = true

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = scheduleDeviceCheck()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = scheduleDeviceCheck()
    }
    private val deviceCheck = Runnable { checkDevice(force = false) }

    /** 使用者用實體按鍵調整系統音量時，記到目前裝置 */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            if (!store.rememberSysVolume || SystemClock.elapsedRealtime() < ignoreVolumeUntil) return
            val key = currentKey ?: return
            val p = store.get(key) ?: return
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (p.sysVolume != v) store.save(p.copy(sysVolume = v))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        store = ProfileStore(this)
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        running = true
        engine.start()
        am.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(volumeReceiver, filter)
        detector = AppDetector(this, handler) { pkg ->
            currentApp = pkg
            currentAppLabel = pkg?.let { Access.appLabel(this, it) }
            applyCurrent(smooth = true)
        }
        detector.start()
        checkDevice(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                store.serviceOn = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UP, ACTION_DOWN -> {
                val key = currentKey
                val p = key?.let { store.get(it) }
                if (p != null) {
                    val dir = if (intent.action == ACTION_UP) 1 else -1
                    val app = currentApp
                    if (app != null) {
                        // 在某個 App 裡調整，就幫這個 App 另外記一筆
                        val cur = store.getApp(p.key, app)
                            ?: Profile(key = store.appKey(p.key, app), name = p.name, gain = p.gain,
                                app = app, appLabel = currentAppLabel ?: app)
                        store.save(cur.copy(gain = GainMath.nudge(cur.gain, dir), lastUsed = System.currentTimeMillis()))
                    } else {
                        store.save(p.copy(gain = GainMath.nudge(p.gain, dir)))
                    }
                    applyCurrent()
                }
            }
            ACTION_APPLY -> applyCurrent()
            ACTION_SESSION_OPEN -> {
                engine.openSession(intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0))
                applyCurrent()
            }
            ACTION_SESSION_CLOSE -> engine.closeSession(intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0))
        }
        return START_STICKY
    }

    private fun scheduleDeviceCheck() {
        handler.removeCallbacks(deviceCheck)
        handler.postDelayed(deviceCheck, 600) // 等系統把路由切完
    }

    private fun checkDevice(force: Boolean) {
        val dev = DeviceResolver.current(am)
        if (!force && dev.key == currentKey) return
        currentKey = dev.key
        currentName = dev.name
        var p = store.getOrCreate(dev.key, dev.name).copy(name = dev.name, lastUsed = System.currentTimeMillis())
        store.save(p)
        // 切換後系統會自己改音量，這段時間的變化不要記
        ignoreVolumeUntil = SystemClock.elapsedRealtime() + 2500
        if (store.rememberSysVolume) {
            if (p.sysVolume >= 0) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, p.sysVolume.coerceIn(0, max), 0) }
            } else {
                p = p.copy(sysVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC))
                store.save(p)
            }
        }
        applyCurrent(smooth = true)
    }

    private fun applyCurrent(smooth: Boolean = false) {
        val key = currentKey ?: return
        val p = store.get(key) ?: return
        val target = store.effectiveGain(p, currentApp)
        // 記錄這個 App 最近一次使用時間（排序用）
        store.getApp(p.key, currentApp)?.let { store.save(it.copy(lastUsed = System.currentTimeMillis())) }
        handler.removeCallbacks(ramp)
        rampEnabled = p.enabled; rampLimiter = p.limiter
        if (smooth && appliedGain >= 0f && appliedGain != target && p.enabled) {
            rampFrom = appliedGain; rampTo = target; rampStep = 0
            handler.post(ramp)
        } else {
            pushGain(target)
        }
        globalSupported = engine.globalSupported
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
        listener?.invoke()
    }

    private fun pushGain(g: Float) {
        engine.apply(g, rampEnabled, rampLimiter)
        appliedGain = g
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // 舊版用的是低重要性頻道（會被放到「靜音」區最下面），換成新頻道
        runCatching { nm.deleteNotificationChannel("volume") }
        val ch = NotificationChannel(CHANNEL, "音量調整（置頂）", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "顯示目前裝置與音量，並提供快速調整"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val p = currentKey?.let { store.get(it) }
        val where = p?.let { d -> currentAppLabel?.let { "${d.name} · $it" } ?: d.name }
        val text = when {
            p == null -> "偵測輸出裝置中…"
            !p.enabled -> "$where：原音（未調整）"
            else -> {
                val g = store.effectiveGain(p, currentApp)
                "$where：${GainMath.pctText(g)}（${GainMath.dbText(g)}）"
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun act(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, VolumeService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(0)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .addAction(Notification.Action.Builder(null, "－ 小聲", act(ACTION_DOWN, 1)).build())
            .addAction(Notification.Action.Builder(null, "＋ 大聲", act(ACTION_UP, 2)).build())
            .addAction(Notification.Action.Builder(null, "停止", act(ACTION_STOP, 3)).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        currentApp = null
        currentAppLabel = null
        detector.stop()
        handler.removeCallbacksAndMessages(null)
        am.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(volumeReceiver) }
        engine.release()
        listener?.invoke()
        super.onDestroy()
    }

    companion object {
        const val ACTION_APPLY = "tw.finevolume.APPLY"
        const val ACTION_UP = "tw.finevolume.UP"
        const val ACTION_DOWN = "tw.finevolume.DOWN"
        const val ACTION_STOP = "tw.finevolume.STOP"
        const val ACTION_SESSION_OPEN = "tw.finevolume.SESSION_OPEN"
        const val ACTION_SESSION_CLOSE = "tw.finevolume.SESSION_CLOSE"
        private const val CHANNEL = "volume_top"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
        @Volatile var currentKey: String? = null
            private set
        @Volatile var currentName: String? = null
            private set
        @Volatile var globalSupported = true
            private set
        /** 目前判斷在出聲的 App（套件名稱與名稱），null = 無法判斷 */
        @Volatile var currentApp: String? = null
            private set
        @Volatile var currentAppLabel: String? = null
            private set
        private const val RAMP_STEPS = 10
        private const val RAMP_INTERVAL_MS = 30L
        /** 主畫面註冊，狀態變化時更新畫面 */
        var listener: (() -> Unit)? = null

        fun start(c: Context) {
            c.startForegroundService(Intent(c, VolumeService::class.java))
        }

        fun sendSession(c: Context, sessionId: Int, open: Boolean) {
            if (!running || sessionId <= 0) return
            runCatching {
                c.startService(
                    Intent(c, VolumeService::class.java)
                        .setAction(if (open) ACTION_SESSION_OPEN else ACTION_SESSION_CLOSE)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                )
            }
        }

        fun send(c: Context, action: String) {
            if (running) runCatching { c.startService(Intent(c, VolumeService::class.java).setAction(action)) }
        }
    }
}
