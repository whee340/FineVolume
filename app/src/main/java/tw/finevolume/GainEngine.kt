package tw.finevolume

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * 用 DynamicsProcessing 的輸入增益調整音量（可負可正），並用內建限幅器避免放大時爆音。
 * 優先掛在全域輸出（session 0）；手機不支援時改掛在播放器宣告的個別音效工作階段。
 */
class GainEngine {
    private var global: DynamicsProcessing? = null
    private val sessions = mutableMapOf<Int, DynamicsProcessing>()

    var globalSupported = true
        private set

    private var gain = 1f
    private var enabled = true
    private var limiter = true

    fun start() {
        if (global != null) return
        global = create(0)
        globalSupported = global != null
        apply(gain, enabled, limiter)
    }

    fun openSession(sessionId: Int) {
        if (sessionId <= 0 || globalSupported || sessions.containsKey(sessionId)) return
        create(sessionId)?.let {
            sessions[sessionId] = it
            configure(it)
        }
    }

    fun closeSession(sessionId: Int) {
        sessions.remove(sessionId)?.let { runCatching { it.release() } }
    }

    fun apply(gain: Float, enabled: Boolean, limiter: Boolean) {
        this.gain = gain; this.enabled = enabled; this.limiter = limiter
        // 失去控制權（例如其他等化器 App 搶走）就重建一次
        global?.let { if (!runCatching { it.hasControl() }.getOrDefault(false)) { runCatching { it.release() }; global = create(0) } }
        global?.let { configure(it) }
        sessions.values.forEach { configure(it) }
    }

    private fun configure(dp: DynamicsProcessing) {
        runCatching {
            val active = enabled && (gain != 1f || limiter)
            dp.setInputGainAllChannelsTo(if (enabled) GainMath.toDb(gain) else 0f)
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true,
                    /* enabled = */ enabled && limiter,
                    /* linkGroup = */ 0,
                    /* attackTime ms = */ 1f,
                    /* releaseTime ms = */ 60f,
                    /* ratio = */ 10f,
                    /* threshold dB = */ -1f,
                    /* postGain dB = */ 0f,
                )
            )
            dp.setEnabled(active)
        }.onFailure { Log.w(TAG, "configure failed", it) }
    }

    private fun create(sessionId: Int): DynamicsProcessing? = runCatching {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, 0,
            /* mbcInUse = */ false, 0,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        ).build()
        DynamicsProcessing(PRIORITY, sessionId, config)
    }.onFailure { Log.w(TAG, "DynamicsProcessing($sessionId) unavailable", it) }.getOrNull()

    fun release() {
        runCatching { global?.release() }
        global = null
        sessions.values.forEach { runCatching { it.release() } }
        sessions.clear()
    }

    companion object {
        private const val TAG = "GainEngine"
        private const val PRIORITY = 1000
    }
}
