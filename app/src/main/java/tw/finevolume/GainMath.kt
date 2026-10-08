package tw.finevolume

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 音量倍率（1.0 = 原音）與滑桿位置、dB 之間的換算。
 * 滑桿 0..1000：左半段 0..500 對應 0%..100%（平方曲線，越小聲越細），右半段 500..1000 對應 100%..400%。
 */
object GainMath {
    const val MAX_GAIN = 4f          // 400%，約 +12 dB
    const val SLIDER_MAX = 1000
    private const val UNITY_POS = 500f
    private const val STEP_DB = 1.5f // − / + 每按一次的幅度

    fun posToGain(pos: Int): Float {
        val p = pos.toFloat()
        return if (p <= UNITY_POS) (p / UNITY_POS).pow(2)
        else 1f + (p - UNITY_POS) / (SLIDER_MAX - UNITY_POS) * (MAX_GAIN - 1f)
    }

    fun gainToPos(g: Float): Int {
        val pos = if (g <= 1f) sqrt(g) * UNITY_POS
        else UNITY_POS + (g - 1f) / (MAX_GAIN - 1f) * (SLIDER_MAX - UNITY_POS)
        return pos.roundToInt().coerceIn(0, SLIDER_MAX)
    }

    /** DynamicsProcessing 用的 dB 值；0 倍時給一個接近靜音的值 */
    fun toDb(g: Float): Float = if (g <= 0.0001f) -90f else 20f * log10(g)

    fun clamp(g: Float): Float = min(MAX_GAIN, max(0f, (g * 1000f).roundToInt() / 1000f))

    fun nudge(g: Float, dir: Int): Float {
        if (g <= 0f && dir > 0) return 0.005f
        val next = g * 10f.pow(dir * STEP_DB / 20f)
        if (dir < 0 && next < 0.004f) return 0f
        return clamp(next)
    }

    fun pctText(g: Float): String {
        val pct = g * 100f
        // 整數就不顯示小數點（2%），只有像 0.5% 這種才留一位小數
        if (pct >= 10f || abs(pct - pct.roundToInt()) < 0.05f) return "${pct.roundToInt()}%"
        return String.format("%.1f%%", pct)
    }

    fun dbText(g: Float): String =
        if (g <= 0.0001f) "−∞ dB" else String.format("%+.1f dB", toDb(g)).replace("-", "−")
}
