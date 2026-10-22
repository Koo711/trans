package jp.autojimaku.speech

import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** 端末内・オフラインの音声認識 (Vosk) */
class VoskEngine(
    private val modelDir: File,
    private val lang: String,
    private val listener: SpeechListener,
) : SpeechEngine {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var lastPartial = ""

    override fun start(sampleRate: Int) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (_: Throwable) {
        }
        val m = Model(modelDir.absolutePath)
        val r = try {
            Recognizer(m, sampleRate.toFloat())
        } catch (e: Exception) {
            m.close()
            throw e
        }
        try {
            // 無音 0.6 秒で文を確定。話し続けても 10 秒で区切って字幕を出す
            r.setEndpointerDelays(5.0f, 0.6f, 10.0f)
        } catch (_: Throwable) {
            // 古いネイティブライブラリでは未対応。既定値のまま動かす
        }
        model = m
        recognizer = r
    }

    override fun feed(samples: ShortArray, count: Int) {
        val r = recognizer ?: return
        if (r.acceptWaveForm(samples, count)) {
            lastPartial = ""
            val text = TextTidy.tidy(field(r.result, "text"), lang, final = true)
            if (text.isNotEmpty() && !isNoise(text)) listener.onFinal(text, lang)
        } else {
            val partial = field(r.partialResult, "partial")
            if (partial != lastPartial) {
                lastPartial = partial
                if (partial.isNotBlank()) {
                    listener.onPartial(TextTidy.tidy(partial, lang, final = false))
                }
            }
        }
    }

    override fun stop() {
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
        model = null
    }

    private fun field(json: String?, name: String): String =
        try {
            if (json.isNullOrEmpty()) "" else JSONObject(json).optString(name, "")
        } catch (_: Exception) {
            ""
        }

    /** 雑音を単語と誤認識したもの (英語モデルでよく出る) を捨てる */
    private fun isNoise(text: String): Boolean {
        if (lang != "en") return false
        val t = text.lowercase().trimEnd('.', '?', '!')
        return t in NOISE_WORDS
    }

    private companion object {
        val NOISE_WORDS = setOf("the", "a", "uh", "um", "huh", "hmm", "oh", "ah", "but", "and")
    }
}
