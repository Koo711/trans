package jp.autojimaku.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端末内・多言語の音声認識 (sherpa-onnx)。
 * Silero VAD で話している区間を切り出し、区間ごとに Whisper や言語専用モデルで文字にする。
 */
class SherpaEngine(
    private val vadModelPath: String,
    /** 主に使うモデル */
    private val primary: OfflineModelConfig,
    private val primaryIsWhisper: Boolean,
    /** 言語が決まっているとき (専用モデル・言語指定)。null = Whisper の判別結果を使う */
    private val fixedLanguage: String?,
    /** 自動判別のとき、判別された言語をより正確な専用モデルで認識し直す (言語コード → モデル) */
    private val specialists: Map<String, OfflineModelConfig>,
    private val listener: SpeechListener,
) : SpeechEngine {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SherpaAsr") }
    private val queued = AtomicInteger(0)

    @Volatile
    private var closed = false

    // ---- 音声スレッドだけが触る ----
    private var vad: Vad? = null
    private val window = FloatArray(WINDOW)
    private var windowFill = 0
    private val ring = FloatArray(RING)
    private var total = 0L

    @Volatile
    private var lastErrorAt = 0L

    // ---- 認識スレッドだけが触る (start で作る) ----
    private var recognizer: OfflineRecognizer? = null
    private val primaryConfig = recognizerConfig(primary)
    private val specialistRecognizers = HashMap<String, OfflineRecognizer>()

    /** 自動判別で直前に確定した言語 (短い区間の判別は外れやすいので、これを優先する) */
    private var stickyLanguage: String? = null

    override fun start(sampleRate: Int) {
        require(sampleRate == SAMPLE_RATE) { "16kHz の音声が必要です" }
        val r = OfflineRecognizer(null, primaryConfig)
        val v = try {
            Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModelPath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = WINDOW,
                        maxSpeechDuration = MAX_SPEECH_SEC,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        } catch (e: Throwable) {
            r.release()
            throw e
        }
        recognizer = r
        vad = v
    }

    override fun feed(samples: ShortArray, count: Int) {
        val v = vad ?: return
        for (i in 0 until count) {
            val f = samples[i] / 32768f
            ring[(total % RING).toInt()] = f
            total++
            window[windowFill++] = f
            if (windowFill == WINDOW) {
                windowFill = 0
                v.acceptWaveform(window)
                while (!v.empty()) {
                    val seg = v.front()
                    v.pop()
                    submit(withPreRoll(seg.start.toLong(), seg.samples))
                }
            }
        }
    }

    override fun stop() {
        closed = true
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        vad = null
        try {
            // 認識中のものが終わってから解放する
            executor.execute {
                recognizer?.release()
                recognizer = null
                specialistRecognizers.values.forEach { it.release() }
                specialistRecognizers.clear()
            }
        } catch (_: RejectedExecutionException) {
        }
        executor.shutdown()
    }

    /** VAD が切り出した区間の直前 0.25 秒を足す (語頭が欠けないように) */
    private fun withPreRoll(start: Long, samples: FloatArray): FloatArray {
        val from = maxOf(0L, start - PRE_ROLL, total - RING)
        val n = (start - from).toInt()
        if (n <= 0) return samples
        val out = FloatArray(n + samples.size)
        for (i in 0 until n) out[i] = ring[((from + i) % RING).toInt()]
        System.arraycopy(samples, 0, out, n, samples.size)
        return out
    }

    private fun submit(samples: FloatArray) {
        if (closed) return
        if (queued.get() >= MAX_QUEUE) {
            reportError("処理が追いつかないため、一部の字幕を飛ばしました（設定でより軽いモデルを選ぶと改善します）")
            return
        }
        queued.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (!closed) decode(samples)
                } catch (e: Throwable) {
                    Log.w(TAG, "decode failed", e)
                    if (!closed) reportError("音声認識エラー: ${e.message}")
                } finally {
                    queued.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            queued.decrementAndGet()
        }
    }

    private fun decode(samples: FloatArray) {
        val r = recognizer ?: return
        val voicedMs = samples.size * 1000 / SAMPLE_RATE
        var (text, detected) = recognize(r, samples)
        var fromWhisper = primaryIsWhisper
        var language = fixedLanguage ?: detected

        if (primaryIsWhisper && fixedLanguage == null && detected != null) {
            // 短い区間で言語が変わったと判別されたときは、外れの可能性が高いので直前の言語とみなす
            val sticky = stickyLanguage
            val lang = if (sticky != null && sticky != detected && voicedMs < SHORT_SEGMENT_MS) sticky else detected
            stickyLanguage = lang
            language = lang

            val config = specialists[lang]
            if (config != null) {
                // タイ語などと分かったら、より正確な専用モデルで認識し直す
                val sr = specialistRecognizers.getOrPut(lang) { OfflineRecognizer(null, recognizerConfig(config)) }
                text = recognize(sr, samples).first
                fromWhisper = false
            } else if (lang != detected) {
                text = recognizeAs(r, samples, lang)
            }
        }
        if (closed) return

        val tidy = TextTidy.tidy(text, language, final = !fromWhisper)
        if (tidy.isEmpty()) return
        if (fromWhisper && TextTidy.isHallucination(tidy, voicedMs)) return
        listener.onFinal(tidy, language)
    }

    /** @return (テキスト, 判別された言語 or null) */
    private fun recognize(r: OfflineRecognizer, samples: FloatArray): Pair<String, String?> {
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val result = r.getResult(stream)
            val lang = result.lang.trim().removePrefix("<|").removeSuffix("|>").takeIf { it.isNotEmpty() }
            return result.text.trim() to lang
        } finally {
            stream.release()
        }
    }

    /** Whisper の言語を指定して認識し直す */
    private fun recognizeAs(r: OfflineRecognizer, samples: FloatArray, lang: String): String {
        val model = primaryConfig.modelConfig
        r.setConfig(primaryConfig.copy(modelConfig = model.copy(whisper = model.whisper.copy(language = lang))))
        try {
            return recognize(r, samples).first
        } finally {
            r.setConfig(primaryConfig)
        }
    }

    private fun reportError(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < 10_000) return
        lastErrorAt = now
        listener.onError(message)
    }

    private fun recognizerConfig(model: OfflineModelConfig) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = model,
        decodingMethod = "greedy_search",
    )

    companion object {
        private const val TAG = "SherpaEngine"
        const val SAMPLE_RATE = 16000
        private const val WINDOW = 512
        private const val RING = SAMPLE_RATE * 30
        private const val PRE_ROLL = SAMPLE_RATE / 4
        private const val MAX_SPEECH_SEC = 7f
        private const val MAX_QUEUE = 3
        private const val SHORT_SEGMENT_MS = 3000

        /** 認識に使うスレッド数 (大きいコアの数くらい) */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
