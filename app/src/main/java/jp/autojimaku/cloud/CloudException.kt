package jp.autojimaku.cloud

import java.io.IOException

/**
 * クラウド翻訳 (Gemini・Google翻訳) のエラー。
 * @param httpCode HTTP ステータス。接続できなかったときは -1、返答が空だったときは 200
 * @param rateLimited 利用上限 (回数・文字数の制限) に達した
 */
open class CloudException(
    message: String,
    val httpCode: Int = -1,
    val rateLimited: Boolean = httpCode == 429,
) : IOException(message) {
    /** しばらくすると直る・この文だけの問題で、端末内翻訳で代わりに訳してよいエラー */
    val canFallBack: Boolean get() = rateLimited || httpCode == -1 || httpCode == 200 || httpCode >= 500
}
