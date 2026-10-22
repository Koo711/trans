package jp.autojimaku.speech

import android.os.SystemClock
import jp.autojimaku.cloud.OpenAiClient
import jp.autojimaku.cloud.Wav
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

/**
 * OpenAI の文字起こし API を使う高精度な音声認識。
 * 音量で「話している区間」を切り出し (簡易 VAD)、区間ごとに送信する。
 */
class OpenAiSpeechEngine(
    private val client: OpenAiClient,
    private val model: String,
    /** null = 自動判別 */
    private val language: String?,
    private val listener: SpeechListener,
) : SpeechEngine {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "OpenAiStt") }
    private val pending = AtomicInteger(0)

    private var sampleRate = 16000
    private var inSpeech = false
    private val chunks = ArrayList<ShortArray>()
    private val chunkRms = ArrayList<Double>()
    private val preRoll = ArrayDeque<ShortArray>()
    private var silenceMs = 0
    private var voicedMs = 0
    private var noiseFloor = 150.0

    @Volatile
    private var lastText = ""

    @Volatile
    private var lastErrorAt = 0L

    @Volatile
    private var closed = false

    override fun start(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    override fun feed(samples: ShortArray, count: Int) {
        if (count <= 0 || closed) return
        val chunk = samples.copyOf(count)
        val chunkMs = count * 1000 / sampleRate
        val rms = rms(chunk)
        val voiced = rms > max(MIN_THRESHOLD, noiseFloor * 3.0)

        if (!inSpeech) {
            if (voiced) {
                inSpeech = true
                chunks.clear()
                chunkRms.clear()
                // 話し始めの直前も少し含める (語頭が切れないように)
                for (c in preRoll) {
                    chunks.add(c)
                    chunkRms.add(0.0)
                }
                preRoll.clear()
                chunks.add(chunk)
                chunkRms.add(rms)
                voicedMs = chunkMs
                silenceMs = 0
            } else {
                noiseFloor = noiseFloor * 0.95 + rms * 0.05
                preRoll.addLast(chunk)
                while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
            }
            return
        }

        chunks.add(chunk)
        chunkRms.add(rms)
        if (voiced) {
            voicedMs += chunkMs
            silenceMs = 0
        } else {
            silenceMs += chunkMs
        }

        if (silenceMs >= END_SILENCE_MS) {
            if (voicedMs >= MIN_VOICED_MS) submit(concat(chunks), voicedMs)
            chunks.clear()
            chunkRms.clear()
            inSpeech = false
        } else if (totalMs() >= MAX_SEGMENT_MS) {
            splitAtQuietPoint()
        }
    }

    override fun stop() {
        closed = true
        executor.shutdownNow()
    }

    /** 話し続けている場合: 直近 1.5 秒で一番静かな所で区切って送る (単語の途中で切れにくくする) */
    private fun splitAtQuietPoint() {
        val from = max(1, chunks.size - SPLIT_SEARCH_CHUNKS)
        var cut = chunks.size - 1
        var minRms = Double.MAX_VALUE
        for (i in from until chunks.size) {
            if (chunkRms[i] < minRms) {
                minRms = chunkRms[i]
                cut = i
            }
        }
        val head = ArrayList(chunks.subList(0, cut + 1))
        val restChunks = ArrayList(chunks.subList(cut + 1, chunks.size))
        val restRms = ArrayList(chunkRms.subList(cut + 1, chunkRms.size))
        submit(concat(head), voicedMs)
        chunks.clear()
        chunks.addAll(restChunks)
        chunkRms.clear()
        chunkRms.addAll(restRms)
        voicedMs = totalMs().toInt()
        silenceMs = 0
    }

    private fun submit(audio: ShortArray, voicedMs: Int) {
        if (closed) return
        if (pending.get() >= MAX_PENDING) {
            reportError("通信が追いつかないため、一部の字幕を飛ばしました")
            return
        }
        pending.incrementAndGet()
        val rate = sampleRate
        try {
            executor.execute {
                try {
                    val (pcm, outRate) = Wav.downsampleTo16k(audio, rate)
                    val raw = client.transcribe(Wav.encode(pcm, outRate), model, language, lastText.takeLast(200))
                    val text = TextTidy.tidy(raw, language, final = false)
                    if (!closed && !TextTidy.isHallucination(text, voicedMs)) {
                        lastText = text
                        listener.onFinal(text, language)
                    }
                } catch (e: Exception) {
                    if (!closed) reportError("音声認識エラー: ${e.message}")
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.decrementAndGet()
        }
    }

    private fun reportError(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorAt < ERROR_INTERVAL_MS) return
        lastErrorAt = now
        listener.onError(message)
    }

    private fun totalMs(): Long {
        var n = 0L
        for (c in chunks) n += c.size
        return n * 1000 / sampleRate
    }

    private fun concat(list: List<ShortArray>): ShortArray {
        var size = 0
        for (c in list) size += c.size
        val out = ShortArray(size)
        var pos = 0
        for (c in list) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        return out
    }

    private fun rms(chunk: ShortArray): Double {
        var sum = 0.0
        for (s in chunk) sum += s.toDouble() * s
        return sqrt(sum / chunk.size)
    }

    private companion object {
        const val MIN_THRESHOLD = 300.0
        const val END_SILENCE_MS = 700
        const val MIN_VOICED_MS = 300
        const val MAX_SEGMENT_MS = 8000L
        const val PRE_ROLL_CHUNKS = 3
        const val SPLIT_SEARCH_CHUNKS = 15
        const val MAX_PENDING = 4
        const val ERROR_INTERVAL_MS = 10_000L
    }
}
