package jp.autojimaku

import kotlin.math.max
import kotlin.math.roundToInt

/** 画面の中で字幕を置いてよい範囲 (ステータスバー・ナビゲーションバー・切り欠きを除く) */
data class SafeArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** 字幕ウィンドウの位置計算 (Gravity.BOTTOM | Gravity.LEFT 基準) */
object OverlayGeometry {

    /**
     * 字幕の箱がはみ出さない位置を返す。
     * 位置は「箱の中心の横位置」と「画面下端から箱の下端まで」を画面に対する割合で覚えておき、
     * 箱の大きさや画面の向きが変わるたびにこの関数で計算し直す。
     *
     * @return (左端の x, 画面下端から箱の下端までの距離 y)
     */
    fun place(
        anchorX: Float,
        anchorBottom: Float,
        boxWidth: Int,
        boxHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        safe: SafeArea,
    ): Pair<Int, Int> {
        val minX = safe.left
        val maxX = max(minX, safe.right - boxWidth)
        val x = (anchorX * screenWidth - boxWidth / 2f).roundToInt().coerceIn(minX, maxX)

        val minY = screenHeight - safe.bottom // 下側のバーの上
        val maxY = max(minY, screenHeight - safe.top - boxHeight) // 箱の上端が上側のバーより下
        val y = (anchorBottom * screenHeight).roundToInt().coerceIn(minY, maxY)
        return x to y
    }

    /** 指で動かした後の位置を、画面に対する割合に直して保存用にする */
    fun anchorsFor(x: Int, y: Int, boxWidth: Int, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val ax = ((x + boxWidth / 2f) / screenWidth).coerceIn(0f, 1f)
        val ay = (y.toFloat() / screenHeight).coerceIn(0f, 1f)
        return ax to ay
    }
}
