package jp.autojimaku

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 他のアプリの上に重ねて表示する字幕。メインスレッドからだけ呼ぶこと。
 * - 翻訳した字幕は履歴として残り、箱の中を上下にスワイプすると前の字幕を見られる
 * - 上のつまみをドラッグすると移動、タップすると開閉する
 * - 箱の大きさや画面の向きが変わるたびに位置を計算し直し、常に画面内に収める
 */
class CaptionOverlay(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    /** 重ね表示用の画面に結びついた Context (回転時に正しい画面サイズが取れる) */
    private val ui: Context = overlayContext(context)
    private val settings = AppSettings(context)
    private val wm: WindowManager = ui.getSystemService(WindowManager::class.java)!!
    private val handler = Handler(Looper.getMainLooper())
    private val density = ui.resources.displayMetrics.density

    private val box = LinearLayout(ui)
    private val handleRow = FrameLayout(ui)
    private val latestButton = TextView(ui)
    private val scroll = CappedScrollView(ui)
    private val list = LinearLayout(ui)
    private val statusView = TextView(ui)

    private val params = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM or Gravity.LEFT }

    private class Entry(val view: LinearLayout, val main: TextView, val source: TextView) {
        var sourceText: String? = null
    }

    /** 確定した字幕 (古い順) */
    private val entries = ArrayDeque<Entry>()

    /** 話している途中の字幕 (一番下に1つだけ) */
    private var partial: Entry? = null
    private var partialSource: String? = null
    private var partialTranslation: String? = null
    private var status: String? = null

    /** 字幕の箱を開いているか (閉じているときはつまみだけ) */
    private var expanded = false

    /** 最後に履歴を指で触った時刻。しばらくは最新への自動スクロールと自動で閉じるのを止める */
    private var lastBrowseAt = 0L

    private var anchorX = settings.overlayCenterX
    private var anchorBottom = settings.overlayBottom
    private var textMaxWidth = 0
    private var fontSize = 20f

    private var attached = false
    private var destroyed = false

    private val collapseRunnable = Runnable { collapseIfIdle() }
    private val clearStatusRunnable = Runnable {
        status = null
        refresh()
    }

    init {
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(BOX_COLOR, 10f)
        box.setPadding(dp(12f), 0, dp(12f), dp(8f))
        box.minimumWidth = dp(80f)

        // つまみ: ドラッグで移動、タップで開閉
        val grip = View(ui).apply { background = rounded(GRIP_COLOR, 2f) }
        handleRow.addView(grip, FrameLayout.LayoutParams(dp(36f), dp(4f), Gravity.CENTER))
        latestButton.apply {
            text = "▼ 最新"
            setTextColor(LATEST_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), 0, 0, 0)
            visibility = View.GONE
            setOnClickListener {
                lastBrowseAt = 0L
                scrollToLatest()
                expand(OPEN_HOLD_MS)
            }
        }
        handleRow.addView(
            latestButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        handleRow.setOnTouchListener(DragListener())
        box.addView(handleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24f)))

        // 字幕の履歴 (高さは「表示する行数」まで。それより前はスクロール)
        list.orientation = LinearLayout.VERTICAL
        list.gravity = Gravity.CENTER_HORIZONTAL
        scroll.isVerticalScrollBarEnabled = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(
            list,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        scroll.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                lastBrowseAt = SystemClock.uptimeMillis()
            }
            false
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateLatestButton() }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isBrowsing()) scrollToLatest()
            updateLatestButton()
        }
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL },
        )

        statusView.setTextColor(STATUS_COLOR)
        statusView.gravity = Gravity.CENTER
        statusView.maxLines = 4
        box.addView(
            statusView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL },
        )

        // 大きさが変わったら (字幕が増えた・回転した など) 位置を計算し直して画面内に収める
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) reposition()
        }

        applyAppearance()
        refresh()
    }

    fun show() {
        if (attached || destroyed) return
        val s = screenSize()
        box.measure(
            View.MeasureSpec.makeMeasureSpec(s.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(s.y, View.MeasureSpec.AT_MOST),
        )
        reposition()
        try {
            wm.addView(box, params)
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
                wm.removeView(box)
            } catch (_: Exception) {
            }
            attached = false
        }
    }

    /** 案内やエラーを表示する。durationMs = 0 なら次の字幕まで出し続ける */
    fun showStatus(message: String, durationMs: Long) {
        status = message
        handler.removeCallbacks(clearStatusRunnable)
        if (durationMs > 0) handler.postDelayed(clearStatusRunnable, durationMs)
        refresh()
    }

    /** 話している途中の原文 */
    fun onPartialSource(text: String) {
        partialSource = text
        updatePartial()
    }

    /** 話している途中の文の暫定訳 */
    fun onPartialTranslation(text: String) {
        partialTranslation = text
        updatePartial()
    }

    /** 確定した文と訳 (履歴に追加する) */
    fun onFinal(source: String, translation: String) {
        removePartial()
        val e = newEntry()
        e.main.text = cap(translation)
        // 日本語の発話など、訳と原文が同じなら原文は出さない
        e.sourceText = source.takeIf { it.isNotBlank() && it != translation }
        e.source.text = e.sourceText?.let { cap(it) } ?: ""
        style(e)
        entries.lastOrNull()?.main?.setTextColor(OLD_LINE_COLOR)
        e.main.setTextColor(Color.WHITE)
        entries.addLast(e)
        list.addView(e.view, entryParams())
        while (entries.size > MAX_ENTRIES) list.removeView(entries.removeFirst().view)

        status = null
        handler.removeCallbacks(clearStatusRunnable)
        expand(min(MAX_HOLD_MS, BASE_HOLD_MS + translation.length * PER_CHAR_HOLD_MS))
    }

    /** 画面の回転など */
    fun onConfigurationChanged() {
        applyAppearance()
        reposition()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_FONT_SIZE, AppSettings.KEY_TOUCH_THROUGH, AppSettings.KEY_VISIBLE_LINES -> applyAppearance()
            AppSettings.KEY_SHOW_ORIGINAL -> {
                applyAppearance()
                updatePartial()
            }
        }
    }

    // ---- 表示 ----

    private fun applyAppearance() {
        val s = screenSize()
        val safe = safeArea(s)
        val landscape = s.x > s.y
        val safeWidth = safe.right - safe.left
        val safeHeight = safe.bottom - safe.top
        val boxMaxWidth = (safeWidth * (if (landscape) 0.75f else 0.96f)).toInt()
        textMaxWidth = max(dp(80f), boxMaxWidth - box.paddingLeft - box.paddingRight)
        fontSize = settings.fontSizeSp.toFloat()

        entries.forEach { style(it) }
        partial?.let { style(it) }
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(12f, fontSize * 0.75f))
        statusView.maxWidth = textMaxWidth

        // 一度に見える高さ = 表示する行数ぶん (画面の高さの 40〜45% まで)
        val probe = TextView(ui).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            setLineSpacing(0f, 1.1f)
        }
        val wanted = probe.lineHeight * settings.visibleLines + dp(6f)
        scroll.maxHeightPx = min(wanted, (safeHeight * (if (landscape) 0.45f else 0.4f)).toInt())
        scroll.requestLayout()

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
    }

    private fun style(e: Entry) {
        e.main.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        e.main.maxWidth = textMaxWidth
        e.source.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(11f, fontSize * 0.65f))
        e.source.maxWidth = textMaxWidth
        e.source.visibility = if (settings.showOriginal && !e.sourceText.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    private fun newEntry(): Entry {
        val main = TextView(ui).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            setLineSpacing(0f, 1.1f)
            setTextColor(Color.WHITE)
        }
        val source = TextView(ui).apply {
            gravity = Gravity.CENTER
            setTextColor(SUB_COLOR)
        }
        val view = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        view.addView(main, entryParams(topMargin = 0))
        view.addView(source, entryParams(topMargin = 0))
        return Entry(view, main, source)
    }

    private fun entryParams(topMargin: Int = dp(3f)) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            this.topMargin = topMargin
        }

    private fun updatePartial() {
        val translation = partialTranslation
        val source = partialSource
        val showSource = settings.showOriginal && !source.isNullOrBlank()
        if (translation == null && !showSource) {
            partial?.let { list.removeView(it.view) }
            partial = null
            return
        }
        val e = partial ?: newEntry().also {
            partial = it
            list.addView(it.view, entryParams())
        }
        e.main.text = translation?.let { cap(it) } ?: ""
        e.main.visibility = if (translation == null) View.GONE else View.VISIBLE
        e.main.setTextColor(PARTIAL_COLOR)
        e.sourceText = source
        e.source.text = source?.let { cap(it) } ?: ""
        style(e)
        expand(BASE_HOLD_MS)
    }

    private fun removePartial() {
        partial?.let { list.removeView(it.view) }
        partial = null
        partialSource = null
        partialTranslation = null
    }

    private fun expand(holdMs: Long) {
        val wasHidden = !expanded || scroll.visibility != View.VISIBLE
        expanded = true
        refresh()
        if (wasHidden && !isBrowsing()) scroll.post { scrollToLatest() }
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, holdMs)
    }

    /** しばらく字幕が来ず、履歴も見ていなければ、つまみだけにたたむ */
    private fun collapseIfIdle() {
        val sinceBrowse = SystemClock.uptimeMillis() - lastBrowseAt
        if (sinceBrowse < BROWSE_HOLD_MS) {
            handler.postDelayed(collapseRunnable, BROWSE_HOLD_MS - sinceBrowse)
            return
        }
        expanded = false
        refresh()
    }

    private fun toggle() {
        if (expanded && list.childCount > 0) {
            expanded = false
            lastBrowseAt = 0L
            handler.removeCallbacks(collapseRunnable)
            refresh()
        } else {
            lastBrowseAt = 0L
            expand(OPEN_HOLD_MS)
        }
    }

    private fun refresh() {
        if (destroyed) return
        val hasEntries = list.childCount > 0
        scroll.visibility = if (expanded && hasEntries) View.VISIBLE else View.GONE
        val st = status
        statusView.text = st ?: ""
        statusView.visibility = if (st != null) View.VISIBLE else View.GONE
        box.alpha = if (scroll.visibility == View.VISIBLE || st != null) 1f else COLLAPSED_ALPHA
        updateLatestButton()
    }

    /** 履歴をさかのぼって見ている途中か */
    private fun isBrowsing(): Boolean =
        SystemClock.uptimeMillis() - lastBrowseAt < BROWSE_HOLD_MS && scroll.canScrollVertically(1)

    private fun scrollToLatest() {
        scroll.scrollTo(0, list.height)
    }

    private fun updateLatestButton() {
        latestButton.visibility =
            if (scroll.visibility == View.VISIBLE && scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
    }

    private fun cap(text: String): String = if (text.length <= MAX_CHARS) text else "…" + text.takeLast(MAX_CHARS)

    // ---- 位置 (常に画面内) ----

    private fun reposition() {
        if (destroyed) return
        val s = screenSize()
        val w = if (box.width > 0) box.width else box.measuredWidth
        val h = if (box.height > 0) box.height else box.measuredHeight
        val (x, y) = OverlayGeometry.place(anchorX, anchorBottom, w, h, s.x, s.y, safeArea(s))
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            if (attached) updateLayout()
        }
    }

    private fun updateLayout() {
        try {
            wm.updateViewLayout(box, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = ui.resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }

    /** ステータスバー・ナビゲーションバー・カメラの切り欠きを避けた範囲 (全画面の動画でも同じ範囲に収める) */
    private fun safeArea(s: Point): SafeArea {
        val m = dp(4f)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val ins = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return SafeArea(ins.left + m, ins.top + m, s.x - ins.right - m, s.y - ins.bottom - m)
            } catch (e: Exception) {
                Log.w(TAG, "insets unavailable", e)
            }
        }
        return SafeArea(m, systemDimen("status_bar_height") + m, s.x - m, s.y - m)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimen(name: String): Int {
        val id = ui.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) ui.resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Float) = (v * density).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
    }

    /** つまみ: ドラッグで移動 (画面内に収まる範囲だけ)、タップで開閉 */
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
                    val s = screenSize()
                    val w = box.width
                    // 下端基準なので、指を上へ動かすと y が増える
                    val (rawAx, rawAy) = OverlayGeometry.anchorsFor(startX + dx.toInt(), startY - dy.toInt(), w, s.x, s.y)
                    val (x, y) = OverlayGeometry.place(rawAx, rawAy, w, box.height, s.x, s.y, safeArea(s))
                    val (ax, ay) = OverlayGeometry.anchorsFor(x, y, w, s.x, s.y)
                    anchorX = ax
                    anchorBottom = ay
                    reposition()
                    lastBrowseAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        settings.overlayCenterX = anchorX
                        settings.overlayBottom = anchorBottom
                    } else {
                        toggle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        settings.overlayCenterX = anchorX
                        settings.overlayBottom = anchorBottom
                    }
                }
            }
            return true
        }
    }

    /** 高さに上限のある ScrollView */
    private class CappedScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val TAG = "CaptionOverlay"
        val BOX_COLOR = Color.argb(0xC8, 0, 0, 0)
        val GRIP_COLOR = Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
        val LATEST_COLOR = Color.rgb(0x90, 0xCA, 0xF9)
        val SUB_COLOR = Color.rgb(0xC8, 0xC8, 0xC8)
        val OLD_LINE_COLOR = Color.argb(0xB0, 0xFF, 0xFF, 0xFF)
        val PARTIAL_COLOR = Color.rgb(0xFF, 0xF3, 0xC4)
        val STATUS_COLOR = Color.rgb(0xB3, 0xD4, 0xFF)
        const val COLLAPSED_ALPHA = 0.6f
        const val BASE_HOLD_MS = 6000L
        const val PER_CHAR_HOLD_MS = 60L
        const val MAX_HOLD_MS = 12_000L
        const val OPEN_HOLD_MS = 15_000L
        const val BROWSE_HOLD_MS = 15_000L
        const val MAX_ENTRIES = 300
        const val MAX_CHARS = 600

        /** Android 11 以降は重ね表示用の Context を使う (サービスの Context だと回転後の大きさがずれることがある) */
        fun overlayContext(context: Context): Context {
            if (Build.VERSION.SDK_INT < 30) return context
            return try {
                val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return context
                context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (e: Exception) {
                Log.w(TAG, "window context unavailable", e)
                context
            }
        }
    }
}
