package jp.autojimaku

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 他のアプリの上に重ねて表示する字幕。メインスレッドからだけ呼ぶこと。
 * 指でドラッグして移動でき、位置は保存される。
 */
class CaptionOverlay(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val settings = AppSettings(context)
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private val root = FrameLayout(context)
    private val box = LinearLayout(context)
    private val mainText = TextView(context)
    private val subText = TextView(context)
    private val params = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private var attached = false
    private var destroyed = false

    /** 確定した日本語字幕 (古い順・最大2行) */
    private val finals = ArrayDeque<String>()
    private var lastSource: String? = null
    private var partialSource: String? = null
    private var partialTranslation: String? = null
    private var status: String? = null

    private val hideRunnable = Runnable { clearCaptions() }
    private val clearStatusRunnable = Runnable {
        status = null
        render(resetHideTimer = false)
    }

    init {
        box.orientation = LinearLayout.VERTICAL
        box.background = GradientDrawable().apply {
            setColor(BOX_COLOR)
            cornerRadius = 10 * density
        }
        val ph = (14 * density).toInt()
        val pv = (8 * density).toInt()
        box.setPadding(ph, pv, ph, pv)

        mainText.setTextColor(Color.WHITE)
        mainText.gravity = Gravity.CENTER
        mainText.typeface = Typeface.DEFAULT_BOLD
        mainText.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        mainText.setLineSpacing(0f, 1.1f)
        mainText.maxLines = 6

        subText.setTextColor(SUB_COLOR)
        subText.gravity = Gravity.CENTER
        subText.maxLines = 3

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(mainText, LinearLayout.LayoutParams(lp))
        box.addView(subText, LinearLayout.LayoutParams(lp))
        root.addView(
            box,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        box.visibility = View.GONE
        root.setOnTouchListener(DragListener())

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = settings.overlayX
        params.y = settings.overlayY.takeIf { it >= 0 } ?: defaultY()
        applyAppearance()
    }

    fun show() {
        if (attached || destroyed) return
        try {
            wm.addView(root, params)
            attached = true
            settings.prefs.registerOnSharedPreferenceChangeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            throw e
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (attached) {
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
            attached = false
        }
    }

    /** 案内やエラーを表示する (字幕が出ている間は字幕を優先)。durationMs = 0 なら次の表示まで出し続ける */
    fun showStatus(message: String, durationMs: Long) {
        status = message
        handler.removeCallbacks(clearStatusRunnable)
        if (durationMs > 0) handler.postDelayed(clearStatusRunnable, durationMs)
        render(resetHideTimer = false)
    }

    /** 話している途中の原文 */
    fun onPartialSource(text: String) {
        partialSource = text
        render()
    }

    /** 話している途中の文の暫定訳 */
    fun onPartialTranslation(text: String) {
        partialTranslation = text
        render()
    }

    /** 確定した文と訳 */
    fun onFinal(source: String, translation: String) {
        finals.addLast(translation)
        while (finals.size > 2) finals.removeFirst()
        lastSource = source
        partialSource = null
        partialTranslation = null
        status = null
        handler.removeCallbacks(clearStatusRunnable)
        render()
    }

    /** 画面の回転など */
    fun onConfigurationChanged() {
        applyAppearance()
        clampPosition()
        if (attached) updateLayout()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_FONT_SIZE, AppSettings.KEY_SHOW_ORIGINAL, AppSettings.KEY_TOUCH_THROUGH -> applyAppearance()
        }
    }

    private fun applyAppearance() {
        val size = settings.fontSizeSp.toFloat()
        mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        subText.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(11f, size * 0.65f))
        val maxWidth = (screenSize().x * 0.92f).toInt()
        mainText.maxWidth = maxWidth
        subText.maxWidth = maxWidth

        if (settings.touchThrough) {
            // 字幕を素通りして下の動画アプリを操作できるようにする。
            // Android 12 以降は不透明度 0.8 以下でないとタッチが下に届かない
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = 0.8f
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
        }
        if (attached) updateLayout()
        render(resetHideTimer = false)
    }

    private fun render(resetHideTimer: Boolean = true) {
        if (destroyed) return
        val sb = SpannableStringBuilder()
        val partial = partialTranslation
        val older = finals.toList().takeLast(if (partial != null) 1 else 2)
        older.forEachIndexed { i, line ->
            val isNewest = partial == null && i == older.lastIndex
            appendLine(sb, line, if (isNewest) Color.WHITE else OLD_LINE_COLOR)
        }
        if (partial != null) appendLine(sb, partial, PARTIAL_COLOR)
        val st = status
        if (sb.isEmpty() && st != null) appendLine(sb, st, STATUS_COLOR)

        mainText.text = sb
        mainText.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE

        // 原文 (日本語の発話などで訳と同じなら出さない)
        val source = if (settings.showOriginal) {
            partialSource ?: lastSource?.takeIf { it != finals.lastOrNull() }
        } else {
            null
        }
        subText.text = source?.let { tail(it, MAX_SOURCE_CHARS) } ?: ""
        subText.visibility = if (source.isNullOrBlank()) View.GONE else View.VISIBLE

        val visible = mainText.visibility == View.VISIBLE || subText.visibility == View.VISIBLE
        box.visibility = if (visible) View.VISIBLE else View.GONE

        if (resetHideTimer) {
            handler.removeCallbacks(hideRunnable)
            val newest = partial ?: finals.lastOrNull() ?: ""
            val hold = min(MAX_HOLD_MS, BASE_HOLD_MS + newest.length * PER_CHAR_HOLD_MS)
            handler.postDelayed(hideRunnable, hold)
        }
    }

    private fun appendLine(sb: SpannableStringBuilder, text: String, color: Int) {
        if (sb.isNotEmpty()) sb.append('\n')
        val start = sb.length
        sb.append(tail(text, MAX_LINE_CHARS))
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 長すぎる文は新しい側 (末尾) を残す */
    private fun tail(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else "…" + text.takeLast(maxChars)

    private fun clearCaptions() {
        finals.clear()
        lastSource = null
        partialSource = null
        partialTranslation = null
        render(resetHideTimer = false)
    }

    private fun updateLayout() {
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = context.resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }

    /** 動画プレーヤーの操作バーに重なりにくい、画面下から少し上 */
    private fun defaultY(): Int = (screenSize().y * 0.12f).toInt()

    private fun clampPosition() {
        val s = screenSize()
        val halfW = s.x / 2
        params.x = params.x.coerceIn(-halfW, halfW)
        params.y = params.y.coerceIn(0, max(0, s.y - max(root.height, (40 * density).toInt())))
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && hypot(dx, dy) < 8 * density) return true
                    moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY - dy.toInt() // 下端基準なので上へ動かすと y が増える
                    clampPosition()
                    updateLayout()
                    // 動かしている間は字幕を消さない
                    handler.removeCallbacks(hideRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        settings.overlayX = params.x
                        settings.overlayY = params.y
                        handler.postDelayed(hideRunnable, BASE_HOLD_MS)
                    }
                }
            }
            return true
        }
    }

    private companion object {
        const val TAG = "CaptionOverlay"
        val BOX_COLOR = Color.argb(0xC8, 0, 0, 0)
        val SUB_COLOR = Color.rgb(0xC8, 0xC8, 0xC8)
        val OLD_LINE_COLOR = Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
        val PARTIAL_COLOR = Color.rgb(0xFF, 0xF3, 0xC4)
        val STATUS_COLOR = Color.rgb(0xB3, 0xD4, 0xFF)
        const val BASE_HOLD_MS = 5000L
        const val PER_CHAR_HOLD_MS = 60L
        const val MAX_HOLD_MS = 12_000L
        const val MAX_LINE_CHARS = 90
        const val MAX_SOURCE_CHARS = 160
    }
}
