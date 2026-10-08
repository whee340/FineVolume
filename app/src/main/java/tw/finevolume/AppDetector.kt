package tw.finevolume

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Process
import android.service.notification.NotificationListenerService

/** 只用來取得「誰在播放媒體」的權限，不讀取任何通知內容 */
class MediaListener : NotificationListenerService()

/** 權限檢查與設定頁 */
object Access {
    fun listenerComponent(c: Context) = ComponentName(c, MediaListener::class.java)

    fun hasMediaAccess(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationListenerAccessGranted(listenerComponent(c))
    }.getOrDefault(false)

    fun hasUsageAccess(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun mediaSettingsIntent(): Intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    fun usageSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun appLabel(c: Context, pkg: String): String = runCatching {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/**
 * 判斷「現在是哪個 App 在出聲」：
 * 1. 正在播放的媒體 App（需要通知存取權，背景播放也抓得到）
 * 2. 沒有媒體在播時，用最後一個在前景的 App（需要使用情況存取權）
 * 本 App、桌面、系統介面不算，會保留上一個 App。
 */
class AppDetector(
    private val context: Context,
    private val handler: Handler,
    private val onChange: (String?) -> Unit,
) {
    var current: String? = null
        private set

    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var sessionsRegistered = false
    private var controllers: List<MediaController> = emptyList()
    private var lastForeground: String? = null
    private var lastQuery = 0L
    private val ignored: Set<String> by lazy { buildIgnored() }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
        override fun onSessionDestroyed() = recompute()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
        recompute()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!sessionsRegistered) registerSessions()
            recompute()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        registerSessions()
        handler.post(poll)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        if (sessionsRegistered) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        sessionsRegistered = false
        setControllers(emptyList())
    }

    private fun registerSessions() {
        if (!Access.hasMediaAccess(context)) return
        runCatching {
            val comp = Access.listenerComponent(context)
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
            setControllers(msm.getActiveSessions(comp))
            sessionsRegistered = true
        }
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = list
        controllers.forEach { runCatching { it.registerCallback(controllerCallback, handler) } }
    }

    private fun playingMediaApp(): String? = controllers.firstOrNull {
        runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
    }?.packageName?.takeIf { it !in ignored }

    private fun foregroundApp(): String? {
        if (!Access.hasUsageAccess(context)) return lastForeground
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 10 * 60_000L else lastQuery - 1_000L
        runCatching {
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName !in ignored) {
                    lastForeground = e.packageName
                }
            }
        }
        lastQuery = now
        return lastForeground
    }

    private fun recompute() {
        val next = playingMediaApp() ?: foregroundApp()
        if (next != current) {
            current = next
            onChange(next)
        }
    }

    private fun buildIgnored(): Set<String> {
        val set = mutableSetOf(context.packageName, "com.android.systemui", "android")
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { set += it.activityInfo.packageName }
        }
        return set
    }

    companion object {
        private const val POLL_MS = 1500L
    }
}
