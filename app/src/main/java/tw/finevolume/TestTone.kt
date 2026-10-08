package tw.finevolume

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * 測試音：C–E–G 和弦，每 1.2 秒換一次和弦根音並帶淡入淡出，聽起來比單一嗶聲舒服。
 * 以媒體用途播放，會經過音量效果，所以可以直接聽出調整差異。
 * 原始音量約 −16 dBFS，留空間讓放大時也聽得出變化。
 */
class TestTone {
    @Volatile private var playing = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    val isPlaying get() = playing

    /** @return 這段聲音的音效工作階段 ID（相容模式時用來掛效果） */
    fun start(): Int {
        if (playing) return track?.audioSessionId ?: 0
        val rate = 44100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        playing = true
        t.play()

        thread = Thread {
            // 和弦進行：C、A 小、F、G
            val chords = arrayOf(
                doubleArrayOf(261.63, 329.63, 392.00),
                doubleArrayOf(220.00, 261.63, 329.63),
                doubleArrayOf(174.61, 220.00, 261.63),
                doubleArrayOf(196.00, 246.94, 293.66),
            )
            val segment = (rate * 1.2).toInt()
            val fade = (rate * 0.08).toInt()
            val amp = 0.16 / 3.0
            val frames = 1024
            val buf = ShortArray(frames * 2)
            var n = 0L
            while (playing) {
                for (i in 0 until frames) {
                    val pos = (n % segment).toInt()
                    val chord = chords[((n / segment) % chords.size).toInt()]
                    val env = when {
                        pos < fade -> pos.toDouble() / fade
                        pos > segment - fade -> (segment - pos).toDouble() / fade
                        else -> 1.0
                    }
                    val tSec = n.toDouble() / rate
                    var s = 0.0
                    for (f in chord) s += sin(2 * PI * f * tSec)
                    val v = (s * amp * env * Short.MAX_VALUE).toInt().toShort()
                    buf[i * 2] = v
                    buf[i * 2 + 1] = v
                    n++
                }
                if (t.write(buf, 0, buf.size) < 0) break
            }
        }.apply { isDaemon = true; start() }
        return t.audioSessionId
    }

    fun stop() {
        if (!playing) return
        playing = false
        thread?.join(300)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
