package tw.finevolume

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** 測試音種類 */
enum class ToneKind(val id: String, val label: String) {
    CHORD("chord", "柔和和弦"),
    PIANO("piano", "鋼琴旋律"),
    BEAT("beat", "節拍鼓聲"),
    NOISE("noise", "粉紅雜訊"),
    FILE("file", "自選音樂…");

    companion object {
        fun from(id: String?) = values().firstOrNull { it.id == id } ?: CHORD
    }
}

/**
 * 播放測試音。合成的聲音用 AudioTrack 即時產生，自選音樂用 MediaPlayer 循環播放。
 * 全部以「媒體」用途播放，會經過音量效果，所以能直接聽出調整差異。
 * 合成音的原始音量約 −16 dBFS，留空間讓放大時也聽得出變化。
 */
class TestTone(private val context: Context) {
    @Volatile private var playing = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null

    val isPlaying get() = playing

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** @return 音效工作階段 ID（相容模式時用來掛效果）；播放失敗回傳 0 */
    fun start(kind: ToneKind, fileUri: Uri?): Int {
        stop()
        return if (kind == ToneKind.FILE) startFile(fileUri) else startSynth(kind)
    }

    private fun startFile(uri: Uri?): Int {
        if (uri == null) return 0
        return runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setOnPreparedListener { if (playing) it.start() }
            mp.prepareAsync()
            player = mp
            playing = true
            mp.audioSessionId
        }.getOrElse {
            player?.release(); player = null; playing = false
            0
        }
    }

    private fun startSynth(kind: ToneKind): Int {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
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

        val synth: Synth = when (kind) {
            ToneKind.PIANO -> PianoSynth()
            ToneKind.BEAT -> BeatSynth()
            ToneKind.NOISE -> PinkNoiseSynth()
            else -> ChordSynth()
        }
        thread = Thread {
            val frames = 1024
            val buf = ShortArray(frames * 2)
            while (playing) {
                for (i in 0 until frames) {
                    val v = (synth.next().coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    buf[i * 2] = v
                    buf[i * 2 + 1] = v
                }
                if (t.write(buf, 0, buf.size) < 0) break
            }
        }.apply { isDaemon = true; start() }
        return t.audioSessionId
    }

    fun stop() {
        if (!playing && track == null && player == null) return
        playing = false
        thread?.join(300)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    companion object {
        const val RATE = 44100
    }
}

private abstract class Synth {
    protected var n = 0L
    abstract fun next(): Double
}

private fun midiToHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

/** C–Am–F–G 和弦循環，每 1.2 秒換一次，帶淡入淡出 */
private class ChordSynth : Synth() {
    private val chords = arrayOf(
        doubleArrayOf(261.63, 329.63, 392.00),
        doubleArrayOf(220.00, 261.63, 329.63),
        doubleArrayOf(174.61, 220.00, 261.63),
        doubleArrayOf(196.00, 246.94, 293.66),
    )
    private val segment = (TestTone.RATE * 1.2).toInt()
    private val fade = (TestTone.RATE * 0.08).toInt()

    override fun next(): Double {
        val pos = (n % segment).toInt()
        val chord = chords[((n / segment) % chords.size).toInt()]
        val env = when {
            pos < fade -> pos.toDouble() / fade
            pos > segment - fade -> (segment - pos).toDouble() / fade
            else -> 1.0
        }
        val t = n.toDouble() / TestTone.RATE
        var s = 0.0
        for (f in chord) s += sin(2 * PI * f * t)
        n++
        return s * (0.16 / 3.0) * env
    }
}

/** 鋼琴風格旋律：貝多芬《給愛麗絲》開頭（1810 年，公有領域） */
private class PianoSynth : Synth() {
    // (MIDI 音高, 長度)，0 = 休止；1 單位 = 0.16 秒
    private val score = intArrayOf(
        76, 1, 75, 1, 76, 1, 75, 1, 76, 1, 71, 1, 74, 1, 72, 1, 69, 3,
        60, 1, 64, 1, 69, 1, 71, 3, 64, 1, 68, 1, 71, 1, 72, 3, 64, 1,
        76, 1, 75, 1, 76, 1, 75, 1, 76, 1, 71, 1, 74, 1, 72, 1, 69, 3,
        60, 1, 64, 1, 69, 1, 71, 3, 64, 1, 72, 1, 71, 1, 69, 4, 0, 4,
    )
    private val unit = (TestTone.RATE * 0.16).toInt()
    private val starts = IntArray(score.size / 2)
    private val total: Int

    init {
        var acc = 0
        for (i in starts.indices) { starts[i] = acc; acc += score[i * 2 + 1] * unit }
        total = acc
    }

    override fun next(): Double {
        val pos = (n % total).toInt()
        n++
        var s = 0.0
        // 讓前幾個音自然延續，避免換音時的爆音
        for (i in starts.indices) {
            val m = score[i * 2]
            if (m == 0) continue
            var dt = pos - starts[i]
            if (dt < 0) dt += total
            val t = dt.toDouble() / TestTone.RATE
            if (t > 1.6) continue
            val f = midiToHz(m)
            val attack = if (t < 0.004) t / 0.004 else 1.0
            val env = attack * exp(-3.2 * t)
            s += env * (sin(2 * PI * f * t) + 0.45 * sin(4 * PI * f * t) * exp(-2.0 * t) + 0.18 * sin(6 * PI * f * t) * exp(-4.0 * t))
        }
        return s * 0.11
    }
}

/** 約 100 BPM 的鼓點：大鼓、小鼓、腳踏鈸，適合測低音 */
private class BeatSynth : Synth() {
    private val step = (TestTone.RATE * 0.15).toInt() // 十六分音符
    private val kick = booleanArrayOf(true, false, false, false, false, false, false, false, true, false, true, false, false, false, false, false)
    private val snare = booleanArrayOf(false, false, false, false, true, false, false, false, false, false, false, false, true, false, false, false)
    private var kickT = 10.0; private var snareT = 10.0; private var hatT = 10.0
    private var kickPhase = 0.0
    private var rnd = 12345L
    private var prevNoise = 0.0

    private fun noise(): Double {
        rnd = rnd * 6364136223846793005L + 1442695040888963407L
        return ((rnd ushr 33).toDouble() / (1L shl 31).toDouble()) - 1.0
    }

    override fun next(): Double {
        val dt = 1.0 / TestTone.RATE
        if (n % step == 0L) {
            val i = ((n / step) % 16).toInt()
            if (kick[i]) { kickT = 0.0; kickPhase = 0.0 }
            if (snare[i]) snareT = 0.0
            if (i % 2 == 0) hatT = 0.0
        }
        n++
        var s = 0.0
        if (kickT < 0.6) {
            val f = 45.0 + 85.0 * exp(-kickT * 25.0)
            kickPhase += 2 * PI * f * dt
            s += sin(kickPhase) * exp(-kickT * 7.0) * 0.55
        }
        val nz = noise()
        if (snareT < 0.4) {
            s += nz * exp(-snareT * 18.0) * 0.22 + sin(2 * PI * 185.0 * snareT) * exp(-snareT * 20.0) * 0.18
        }
        if (hatT < 0.15) {
            s += (nz - prevNoise) * exp(-hatT * 60.0) * 0.06
        }
        prevNoise = nz
        kickT += dt; snareT += dt; hatT += dt
        return s * 0.5
    }
}

/** 粉紅雜訊（Paul Kellet 演算法），聲音穩定，最適合判斷大小聲 */
private class PinkNoiseSynth : Synth() {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
    private var rnd = 987654321L

    override fun next(): Double {
        rnd = rnd * 6364136223846793005L + 1442695040888963407L
        val white = ((rnd ushr 33).toDouble() / (1L shl 31).toDouble()) - 1.0
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        n++
        return pink * 0.035
    }
}
