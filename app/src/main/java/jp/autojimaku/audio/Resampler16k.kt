package jp.autojimaku.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 16bit PCM を 16kHz に変換する (ローパスフィルタ + 線形補間)。
 * 音声を少しずつ渡しても途切れないよう、前回の続きを内部に持つ。
 */
class Resampler16k(private val inRate: Int) {

    val passthrough = inRate == OUT_RATE
    private val step = inRate.toDouble() / OUT_RATE
    private val taps: FloatArray = if (passthrough) FloatArray(0) else lowpass(inRate)
    private val history = FloatArray(maxOf(0, taps.size - 1))
    private var work = FloatArray(0)

    /** 次に出力する位置 (直前の入力の最後のサンプルを 0 とした位置) */
    private var pos = 1.0
    private var prev = 0f

    fun maxOutput(n: Int): Int = if (passthrough) n else ceil(n / step).toInt() + 2

    /** input[0 until n] を変換して out に書き、書いた数を返す */
    fun process(input: ShortArray, n: Int, out: ShortArray): Int {
        if (passthrough) {
            System.arraycopy(input, 0, out, 0, n)
            return n
        }
        if (n <= 0) return 0
        val h = history.size
        if (work.size < h + n) work = FloatArray(h + n)
        System.arraycopy(history, 0, work, 0, h)
        for (i in 0 until n) work[h + i] = input[i].toFloat()

        // c[0] = prev, c[k] = filtered[k - 1] (k = 1..n) として補間する
        var count = 0
        while (pos < n) {
            val i = floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i == 0) prev else filtered(i - 1)
            val b = filtered(i)
            out[count++] = clamp(a + (b - a) * frac)
            pos += step
        }
        pos -= n
        prev = filtered(n - 1)

        // 次回のために入力の末尾を残す
        if (h > 0) System.arraycopy(work, n, history, 0, h)
        return count
    }

    /** k 番目 (今回の入力の位置) のフィルタ後の値 */
    private fun filtered(k: Int): Float {
        var acc = 0f
        val base = k // work[k .. k + taps.size - 1] が対象 (history 分ずれている)
        for (t in taps.indices) acc += taps[t] * work[base + t]
        return acc
    }

    private fun clamp(v: Float): Short {
        val r = v.roundToInt()
        return when {
            r > Short.MAX_VALUE -> Short.MAX_VALUE
            r < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> r.toShort()
        }
    }

    companion object {
        const val OUT_RATE = 16000
        private const val TAPS = 63

        /** 7.2kHz より上を落とすローパス (ハミング窓の sinc) */
        private fun lowpass(rate: Int): FloatArray {
            val fc = 7200.0 / rate
            val m = TAPS - 1
            val h = FloatArray(TAPS)
            var sum = 0.0
            for (i in 0 until TAPS) {
                val x = i - m / 2.0
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val w = 0.54 - 0.46 * cos(2 * PI * i / m)
                val v = sinc * w
                h[i] = v.toFloat()
                sum += v
            }
            for (i in h.indices) h[i] = (h[i] / sum).toFloat()
            return h
        }
    }
}
