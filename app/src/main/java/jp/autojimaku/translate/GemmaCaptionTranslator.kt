package jp.autojimaku.translate

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig

/**
 * スマホの中で動く AI (Gemma 4 E2B・LiteRT-LM) で翻訳する。無料・オフラインで、文脈を踏まえた自然な字幕になる。
 * 読み込みは GPU を優先し、使えなければ CPU にする。1文の翻訳に失敗したときは端末内翻訳 (ML Kit) で代わりに訳す。
 */
class GemmaCaptionTranslator(
    private val modelPath: String,
    /** GPU 用の準備結果を保存しておく場所 (2回目以降の読み込みが速くなる) */
    private val cacheDir: String,
    sourceLanguageLabel: String?,
    private val fallback: CaptionTranslator?,
    private val onStatus: (String) -> Unit,
) : CaptionTranslator {

    private val systemPrompt = SubtitlePrompt.system(sourceLanguageLabel)
    private var engine: Engine? = null
    private var useThinkingConfig = true
    private var lastNoticeAt = 0L

    /** どちらで動いているか (案内用) */
    var backendName: String = ""
        private set

    override fun prepare() {
        onStatus("端末内AI（Gemma）を読み込み中…（初回は30秒ほどかかることがあります）")
        val errors = mutableListOf<String>()
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        // 字幕1文の翻訳には十分な長さ。短くするとメモリを節約できる
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = cacheDir,
                    ),
                )
                try {
                    e.initialize()
                } catch (t: Throwable) {
                    e.close()
                    throw t
                }
                engine = e
                backendName = backend.name
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Gemma の ${backend.name} での読み込みに失敗", t)
                errors += "${backend.name}: ${t.message ?: t.javaClass.simpleName}"
                if (t is UnsatisfiedLinkError) break // この端末 (32bit など) では動かない
            }
        }
        val e = engine ?: throw IllegalStateException(
            "端末内AI（Gemma）を読み込めませんでした。メモリが足りないか、この端末では動かない可能性があります（${errors.joinToString(" / ")}）",
        )
        // 設定が受け付けられるか一度試す (受け付けられなければ思考の設定を外す)
        try {
            e.createConversation(conversationConfig()).close()
        } catch (t: Throwable) {
            Log.w(TAG, "会話の準備に失敗。設定を減らして再試行", t)
            useThinkingConfig = false
            e.createConversation(conversationConfig()).close()
        }
    }

    override fun translate(text: String, language: String?, context: List<String>): String? {
        if (language != null && normalizeLanguage(language) == "ja") return text
        val e = engine ?: return fallback?.translate(text, language, context)
        return try {
            // 1文ごとに新しい会話で訳す (前の文の影響や履歴の積み重なりを避ける)
            val conversation = e.createConversation(conversationConfig())
            try {
                clean(conversation.sendMessage(SubtitlePrompt.user(text, context)).toString()).ifBlank { text }
            } finally {
                conversation.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Gemma での翻訳に失敗", t)
            val fb = fallback ?: throw IllegalStateException("端末内AI（Gemma）での翻訳に失敗しました: ${t.message}")
            val now = System.nanoTime()
            if (lastNoticeAt == 0L || now - lastNoticeAt > NOTICE_INTERVAL_NANOS) {
                lastNoticeAt = now
                onStatus("端末内AI（Gemma）で翻訳できなかったため、この文は端末内翻訳で訳しました")
            }
            fb.translate(text, language, context)
        }
    }

    override fun close() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        fallback?.close()
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(systemPrompt),
        // 毎回同じ訳になるよう、いちばん確からしい語だけを選ぶ
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
        maxOutputToken = MAX_OUTPUT_TOKENS,
        // 字幕は速さが大事なので、考える過程は省く
        thinkingConfig = if (useThinkingConfig) ThinkingConfig(enableThinking = false) else null,
    )

    companion object {
        private const val TAG = "GemmaTranslator"
        private const val MAX_TOKENS = 1024
        private const val MAX_OUTPUT_TOKENS = 200
        private const val NOTICE_INTERVAL_NANOS = 30_000_000_000L

        private val prefixes = listOf("翻訳:", "翻訳：", "訳:", "訳：", "日本語:", "日本語：", "字幕:", "字幕：")

        /** AI が付けがちな前置き・かぎかっこ・余分な行を取り除く */
        fun clean(raw: String): String {
            var t = raw.trim()
            for (p in prefixes) if (t.startsWith(p)) t = t.removePrefix(p).trim()
            // 説明などが続いた場合は最初の段落だけ使う
            t = t.split("\n\n").first().trim()
            // 全体がかぎかっこ・引用符で囲まれていたら外す (中に同じ記号があるときは外さない)
            val pairs = listOf("「" to "」", "『" to "』", "\"" to "\"", "“" to "”")
            for ((open, close) in pairs) {
                if (t.length > 2 && t.startsWith(open) && t.endsWith(close)) {
                    val inner = t.substring(open.length, t.length - close.length)
                    if (!inner.contains(open) && !inner.contains(close)) t = inner.trim()
                }
            }
            return t
        }
    }
}
