package jp.autojimaku

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import jp.autojimaku.speech.SpeechListener
import jp.autojimaku.translate.CaptionTranslator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 音声認識の結果 → 翻訳 → 字幕表示 をつなぐ。
 * 翻訳は専用スレッドで順番に行い、表示はメインスレッドで行う。
 */
class CaptionPipeline(
    private val translator: CaptionTranslator,
    /** 話している途中の文も翻訳して出す (端末内翻訳のときだけ。速くて無料なので) */
    private val translatePartials: Boolean,
    /** 話している途中の文の言語 (途中経過が届くのは言語を指定したときだけ) */
    private val partialLanguage: String?,
    private val overlay: () -> CaptionOverlay?,
) : SpeechListener {

    private val thread = HandlerThread("CaptionTranslate").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val partialScheduled = AtomicBoolean(false)
    private val finalSeq = AtomicInteger(0)
    private val pendingFinals = AtomicInteger(0)

    @Volatile
    private var pendingPartial: String? = null

    @Volatile
    private var lastPartialAt = 0L

    @Volatile
    private var closed = false

    @Volatile
    private var lastErrorAt = 0L

    /** 直前の確定文 (翻訳スレッドだけが触る) */
    private val recent = ArrayDeque<String>()

    override fun onPartial(text: String) {
        if (closed) return
        main.post { overlay()?.onPartialSource(text) }
        if (!translatePartials) return
        pendingPartial = text
        if (partialScheduled.compareAndSet(false, true)) {
            val wait = max(0L, PARTIAL_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPartialAt))
            worker.postDelayed({ translatePartial() }, wait)
        }
    }

    override fun onFinal(text: String, language: String?) {
        if (closed) return
        finalSeq.incrementAndGet()
        pendingPartial = null
        pendingFinals.incrementAndGet()
        worker.post {
            // 翻訳できない言語 (null) は原文をそのまま出す
            val ja = try {
                translator.translate(text, language, recent.toList())
            } catch (e: Exception) {
                reportTranslateError(e)
                null
            }
            recent.addLast(text)
            while (recent.size > CONTEXT_SIZE) recent.removeFirst()
            pendingFinals.decrementAndGet()
            if (!closed) main.post { overlay()?.onFinal(text, ja ?: text) }
        }
    }

    override fun onError(message: String) {
        if (closed) return
        main.post { overlay()?.showStatus(message, 6000) }
    }

    fun close() {
        closed = true
        thread.quitSafely()
    }

    private fun translatePartial() {
        partialScheduled.set(false)
        val text = pendingPartial ?: return
        // 確定文の翻訳待ちがあればそちらを優先
        if (closed || pendingFinals.get() > 0) return
        val seq = finalSeq.get()
        lastPartialAt = SystemClock.uptimeMillis()
        val ja = try {
            translator.translate(text, partialLanguage, emptyList())
        } catch (_: Exception) {
            return
        } ?: return
        if (!closed && seq == finalSeq.get() && pendingPartial != null) {
            main.post { overlay()?.onPartialTranslation(ja) }
        }
    }

    private fun reportTranslateError(e: Exception) {
        Log.w(TAG, "translate failed", e)
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorAt < 10_000) return
        lastErrorAt = now
        val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
        main.post { overlay()?.showStatus("翻訳エラー: $msg", 6000) }
    }

    private companion object {
        const val TAG = "CaptionPipeline"
        const val PARTIAL_INTERVAL_MS = 700L
        const val CONTEXT_SIZE = 2
    }
}
