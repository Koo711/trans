package jp.autojimaku.speech

import java.util.Locale

/** 音声認識の結果を受け取る側 */
interface SpeechListener {
    /** 話している途中の暫定テキスト (届かないエンジンもある) */
    fun onPartial(text: String)

    /**
     * 1文 (1区切り) が確定したテキスト。
     * @param language 話されている言語 (ISO 639-1)。分からなければ null
     */
    fun onFinal(text: String, language: String?)

    fun onError(message: String)
}

/**
 * 音声認識エンジン。
 * start → feed (音声スレッドから繰り返し) → stop の順に呼ばれる。音声は 16kHz・16bit モノラル。
 * start はモデル読み込みのため準備用スレッドから呼ばれる。
 */
interface SpeechEngine {
    /** モデルの読み込みなど。時間がかかることがある (ブロッキング) */
    fun start(sampleRate: Int)

    /** 16bit モノラル PCM を渡す。samples は呼び出し後に再利用されるので保持する場合はコピーすること */
    fun feed(samples: ShortArray, count: Int)

    fun stop()
}

object TextTidy {
    private val spaces = Regex("\\s+")
    private val lowerI = Regex("\\bi\\b")

    /** 同じ語句が何度も繰り返される Whisper 特有の暴走 (5回以上) を2回に縮める */
    private val repeats = Regex("(.{1,40}?)(?:[\\s、,。.]*\\1){4,}")

    /** 単語の間に空白を入れない言語 */
    private val noSpaceLangs = setOf("zh", "ja", "yue")

    /** 音声認識の結果を翻訳・表示しやすい形に整える */
    fun tidy(raw: String, lang: String?, final: Boolean): String {
        var t = raw.trim().replace(spaces, " ")
        if (t.isEmpty()) return t
        t = t.replace(repeats, "$1 $1")
        // 大文字だけで出力するモデル (ベトナム語など) は小文字にそろえる
        if (t.any { it.isUpperCase() } && t.none { it.isLowerCase() } && t.count { it.isLetter() } > 3) {
            t = t.lowercase(Locale.ROOT)
        }
        when (lang) {
            in noSpaceLangs -> t = t.replace(" ", "")
            "en" -> t = t.replace(lowerI, "I")
            // タイ語の「ำ」が「ํ + า」に分かれて出てくるモデルがあるので1文字に戻す
            "th" -> t = t.replace("ํา", "ำ")
        }
        if (t.first().isLowerCase()) t = t.replaceFirstChar { it.uppercaseChar() }
        if (final && lang != "th" && lang != "lo" && lang != "km" && lang != "my" && t.last().isLetterOrDigit()) {
            t += if (lang in noSpaceLangs) "。" else "."
        }
        return t
    }

    /** 無音や BGM から生まれがちな「それっぽい文」(Whisper 系の幻聴) */
    private val hallucinations = listOf(
        "thank you for watching",
        "thanks for watching",
        "please subscribe",
        "subtitles by",
        "amara.org",
        "ご視聴ありがとうございました",
        "チャンネル登録",
        "字幕by",
        "請不吝點贊",
        "请不吝点赞",
        "訂閱",
        "sous-titrage",
        "untertitel",
        "subtítulos realizados",
    )
    private val shortNoise = setOf("you", "thank you", "thanks", "bye", "okay", "uh", "um", "ok")

    fun isHallucination(text: String, voicedMs: Int): Boolean {
        val t = text.trim().lowercase()
        if (t.length < 2) return true
        if (hallucinations.any { t.contains(it) }) return true
        if (voicedMs < 1500 && t.trimEnd('.', '!', '?', '。') in shortNoise) return true
        return false
    }
}
