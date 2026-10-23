package jp.autojimaku.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import jp.autojimaku.Languages
import jp.autojimaku.cloud.GeminiClient
import jp.autojimaku.cloud.CloudException
import jp.autojimaku.cloud.GoogleTranslateClient
import jp.autojimaku.cloud.OpenAiClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 日本語への翻訳。どのメソッドもブロッキングなので同じバックグラウンドスレッドから呼ぶ */
interface CaptionTranslator {
    /** モデルのダウンロードなど */
    fun prepare()

    /**
     * @param language 話されている言語 (分からなければ null)
     * @param context 直前に話された文 (古い順)。文脈として使えるエンジンだけが使う
     * @return 日本語の字幕。日本語の発話ならそのまま。翻訳できない言語なら null
     */
    fun translate(text: String, language: String?, context: List<String>): String?

    fun close()
}

/** 言語コードの表記ゆれをそろえる (ML Kit の言語判別は古いコードを返すことがある) */
internal fun normalizeLanguage(code: String): String {
    val base = code.lowercase().substringBefore('-').substringBefore('_')
    return when (base) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "fil" -> "tl"
        "nb", "nn" -> "no"
        "yue" -> "zh"
        else -> base
    }
}

/**
 * 端末内の翻訳 (Google ML Kit)。無料・オフライン。
 * 自動判別のときは、文ごとに言語を判別し、その言語の翻訳モデルを (初回はダウンロードして) 使う。
 */
class MlKitCaptionTranslator(
    /** 言語が決まっているときの ML Kit 言語コード (自動判別は null) */
    private val fixedLanguage: String?,
    private val onStatus: (String) -> Unit,
) : CaptionTranslator {

    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private var identifier: LanguageIdentifier? = null

    /** 言語が判別できなかったときは直前の言語とみなす */
    private var lastLanguage: String? = null

    override fun prepare() {
        fixedLanguage?.let { ensureModel(it) }
    }

    override fun translate(text: String, language: String?, context: List<String>): String? {
        val code = language?.let { normalizeLanguage(it) } ?: identify(text) ?: lastLanguage ?: return null
        if (code == JAPANESE) return text
        val src = TranslateLanguage.fromLanguageTag(code) ?: return null
        lastLanguage = code
        ensureModel(src)
        return Tasks.await(translator(src).translate(text), 15, TimeUnit.SECONDS)
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        identifier?.close()
        identifier = null
    }

    private fun translator(src: String): Translator = translators.getOrPut(src) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.JAPANESE)
                .build(),
        )
    }

    /** 翻訳モデルが無ければダウンロードする (時間がかかるときは案内を出す) */
    private fun ensureModel(src: String) {
        if (src in ready) return
        val task = translator(src).downloadModelIfNeeded(DownloadConditions.Builder().build())
        try {
            Tasks.await(task, 400, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            val name = Languages.all.firstOrNull { it.mlKit == src }?.label ?: src
            onStatus("${name}の翻訳モデルをダウンロード中…（約30MB）")
            Tasks.await(task, 10, TimeUnit.MINUTES)
        }
        ready += src
    }

    private fun identify(text: String): String? {
        val id = identifier ?: LanguageIdentification.getClient().also { identifier = it }
        val tag = try {
            Tasks.await(id.identifyLanguage(text), 5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        }
        if (tag == null || tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        return normalizeLanguage(tag)
    }

    private companion object {
        const val JAPANESE = "ja"
    }
}

/** クラウドの AI (OpenAI・Gemini) に渡す翻訳の指示 */
internal object SubtitlePrompt {
    fun system(sourceLanguageLabel: String?): String =
        "あなたは動画のリアルタイム字幕の翻訳者です。" +
            (if (sourceLanguageLabel != null) "${sourceLanguageLabel}の発話を" else "発話（言語はさまざま）を") +
            "日本語の字幕として自然で読みやすい短い文に翻訳してください。" +
            "入力は音声認識の結果なので、聞き間違いと思われる箇所は文脈から推測して直してください。" +
            "専門用語は一般的な日本語訳かカタカナを使います。" +
            "発話がすでに日本語なら、そのまま出力してください。" +
            "出力は日本語の字幕だけにしてください（説明・引用符・前置きは不要）。"

    fun user(text: String, context: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("直前の発話（参考。翻訳しない）:\n")
            for (c in context) append("- ").append(c).append('\n')
            append('\n')
        }
        append("翻訳する発話:\n")
        append(text)
    }
}

/** OpenAI のチャットモデルで翻訳。文脈を踏まえた自然な字幕になり、どの言語にも対応する */
class OpenAiCaptionTranslator(
    private val client: OpenAiClient,
    private val model: String,
    /** 動画の言語名 (自動判別は null) */
    sourceLanguageLabel: String?,
) : CaptionTranslator {

    private val systemPrompt = SubtitlePrompt.system(sourceLanguageLabel)

    override fun prepare() {}

    override fun translate(text: String, language: String?, context: List<String>): String? {
        if (language != null && normalizeLanguage(language) == "ja") return text
        return client.chat(model, systemPrompt, SubtitlePrompt.user(text, context)).ifBlank { text }
    }

    override fun close() {}
}

/**
 * クラウド翻訳 (Gemini・Google翻訳) の共通部分。
 * 利用上限 (無料枠の回数・文字数制限など) や通信エラーのときは、字幕が止まらないよう端末内翻訳に切り替える。
 * 利用上限のときは1分ほど端末内翻訳で続けてから、クラウドに戻る。
 */
abstract class CloudCaptionTranslator(
    /** 代わりに使う端末内翻訳。その言語が端末内翻訳に未対応なら null */
    private val fallback: CaptionTranslator?,
    private val onStatus: (String) -> Unit,
) : CaptionTranslator {

    /** 利用上限に達したとき、この時刻 (ナノ秒) まではクラウドを呼ばない */
    private var pausedUntil = 0L
    private var lastNoticeAt = 0L

    /** クラウドで翻訳する (失敗したら CloudException を投げる) */
    protected abstract fun translateOnline(text: String, language: String?, context: List<String>): String

    override fun prepare() {}

    override fun translate(text: String, language: String?, context: List<String>): String? {
        if (language != null && normalizeLanguage(language) == "ja") return text
        val now = System.nanoTime()
        if (fallback != null && now < pausedUntil) return fallback.translate(text, language, context)
        return try {
            translateOnline(text, language, context).ifBlank { text }
        } catch (e: CloudException) {
            if (fallback == null || !e.canFallBack) throw e
            if (e.rateLimited) pausedUntil = now + PAUSE_NANOS
            if (lastNoticeAt == 0L || now - lastNoticeAt > NOTICE_INTERVAL_NANOS) {
                lastNoticeAt = now
                onStatus(
                    if (e.rateLimited) "${e.message}。1分ほど端末内翻訳で続けます" else "${e.message}。この文は端末内翻訳で訳しました",
                )
            }
            fallback.translate(text, language, context)
        }
    }

    override fun close() {
        fallback?.close()
    }

    private companion object {
        const val PAUSE_NANOS = 60_000_000_000L
        const val NOTICE_INTERVAL_NANOS = 30_000_000_000L
    }
}

/** Google Gemini で翻訳。文脈を踏まえた自然な字幕になり、どの言語にも対応する */
class GeminiCaptionTranslator(
    private val client: GeminiClient,
    private val model: String,
    /** 動画の言語名 (自動判別は null) */
    sourceLanguageLabel: String?,
    fallback: CaptionTranslator?,
    onStatus: (String) -> Unit,
) : CloudCaptionTranslator(fallback, onStatus) {

    private val systemPrompt = SubtitlePrompt.system(sourceLanguageLabel)

    override fun translateOnline(text: String, language: String?, context: List<String>): String =
        client.generate(model, systemPrompt, SubtitlePrompt.user(text, context))
}

/** オンラインの Google翻訳 (Cloud Translation API)。端末内翻訳より高精度で、どの言語にも対応する */
class GoogleCloudCaptionTranslator(
    private val client: GoogleTranslateClient,
    fallback: CaptionTranslator?,
    onStatus: (String) -> Unit,
) : CloudCaptionTranslator(fallback, onStatus) {

    override fun translateOnline(text: String, language: String?, context: List<String>): String =
        client.translate(text, sourceCode(language))

    /** Google翻訳に渡す元の言語。中国語は簡体字・繁体字を自動で判別させるため渡さない */
    private fun sourceCode(language: String?): String? {
        val code = language?.lowercase()?.substringBefore('-') ?: return null
        return when (code) {
            "zh", "yue" -> null
            else -> normalizeLanguage(code)
        }
    }
}
