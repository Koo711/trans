package jp.autojimaku

import android.content.Context
import android.content.SharedPreferences

enum class AudioInput { PLAYBACK, MIC }

/** 音声認識: 端末内・軽量 (Vosk) / 端末内・多言語 (Whisper・専用モデル) / OpenAI */
enum class AsrEngineType { VOSK, SHERPA, OPENAI }
enum class TranslateEngineType { MLKIT, OPENAI }

/** 端末内・多言語で使う Whisper の大きさ */
enum class WhisperSize(val id: String, val label: String, val approxMb: Int) {
    TINY("tiny", "tiny（約100MB・速い／精度は低め）", 104),
    BASE("base", "base（約160MB・標準）", 161),
    SMALL("small", "small（約380MB・高精度／高性能なスマホ向け）", 376),
}

/** アプリの設定 (SharedPreferences) */
class AppSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var audioInput: AudioInput
        get() = enumValue(KEY_INPUT, AudioInput.PLAYBACK)
        set(v) = putString(KEY_INPUT, v.name)

    var languageId: String
        get() = Languages.byId(prefs.getString(KEY_LANGUAGE, null)).code
        set(v) = putString(KEY_LANGUAGE, v)

    var asrEngine: AsrEngineType
        get() = enumValue(KEY_ASR, AsrEngineType.VOSK)
        set(v) = putString(KEY_ASR, v.name)

    var whisperSize: WhisperSize
        get() = enumValue(KEY_WHISPER_SIZE, WhisperSize.BASE)
        set(v) = putString(KEY_WHISPER_SIZE, v.name)

    var translateEngine: TranslateEngineType
        get() = enumValue(KEY_TRANSLATE, TranslateEngineType.MLKIT)
        set(v) = putString(KEY_TRANSLATE, v.name)

    var openAiKey: String
        get() = prefs.getString(KEY_OPENAI_KEY, null) ?: ""
        set(v) = putString(KEY_OPENAI_KEY, v.trim())

    var openAiSttModel: String
        get() = prefs.getString(KEY_STT_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STT_MODEL
        set(v) = putString(KEY_STT_MODEL, v.trim())

    var openAiChatModel: String
        get() = prefs.getString(KEY_CHAT_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_MODEL
        set(v) = putString(KEY_CHAT_MODEL, v.trim())

    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(v) = prefs.edit().putInt(KEY_FONT_SIZE, v).apply()

    var showOriginal: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ORIGINAL, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_ORIGINAL, v).apply()

    var touchThrough: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_THROUGH, false)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_THROUGH, v).apply()

    /** 字幕の位置: 箱の中心の横位置 (画面幅に対する割合) */
    var overlayCenterX: Float
        get() = prefs.getFloat(KEY_OVERLAY_CX, 0.5f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(KEY_OVERLAY_CX, v).apply()

    /** 字幕の位置: 画面下端から箱の下端まで (画面の高さに対する割合) */
    var overlayBottom: Float
        get() = prefs.getFloat(KEY_OVERLAY_BOTTOM, 0.12f).coerceIn(0f, 1f)
        set(v) = prefs.edit().putFloat(KEY_OVERLAY_BOTTOM, v).apply()

    /** 字幕の箱に一度に表示する行数 (それより前はスクロールで見る) */
    var visibleLines: Int
        get() = prefs.getInt(KEY_VISIBLE_LINES, DEFAULT_VISIBLE_LINES).coerceIn(MIN_VISIBLE_LINES, MAX_VISIBLE_LINES)
        set(v) = prefs.edit().putInt(KEY_VISIBLE_LINES, v).apply()

    /** サービスで起きたエラーを画面に伝えるためのメッセージ */
    fun setLastError(message: String) = putString(KEY_LAST_ERROR, message)

    fun consumeLastError(): String? {
        val msg = prefs.getString(KEY_LAST_ERROR, null) ?: return null
        prefs.edit().remove(KEY_LAST_ERROR).apply()
        return msg
    }

    private fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    companion object {
        const val PREFS_NAME = "settings"

        const val KEY_INPUT = "audio_input"
        const val KEY_LANGUAGE = "language"
        const val KEY_ASR = "asr_engine"
        const val KEY_TRANSLATE = "translate_engine"
        const val KEY_WHISPER_SIZE = "whisper_size"
        const val KEY_OPENAI_KEY = "openai_key"
        const val KEY_STT_MODEL = "openai_stt_model"
        const val KEY_CHAT_MODEL = "openai_chat_model"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_SHOW_ORIGINAL = "show_original"
        const val KEY_TOUCH_THROUGH = "touch_through"
        const val KEY_OVERLAY_CX = "overlay_center_x"
        const val KEY_OVERLAY_BOTTOM = "overlay_bottom"
        const val KEY_VISIBLE_LINES = "visible_lines"
        const val KEY_LAST_ERROR = "last_error"

        const val DEFAULT_STT_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_CHAT_MODEL = "gpt-5-mini"

        const val MIN_FONT_SIZE = 14
        const val MAX_FONT_SIZE = 36
        const val DEFAULT_FONT_SIZE = 20

        const val MIN_VISIBLE_LINES = 2
        const val MAX_VISIBLE_LINES = 8
        const val DEFAULT_VISIBLE_LINES = 3
    }
}
