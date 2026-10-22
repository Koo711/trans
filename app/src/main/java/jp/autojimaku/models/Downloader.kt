package jp.autojimaku.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** モデルのダウンロード。途中で切れても次回は続きから再開する。ブロッキングなのでバックグラウンドスレッドで呼ぶ */
object Downloader {

    /**
     * @param expectedBytes 分かっていれば正しい大きさ (0 = 不明)。違っていたらエラーにする
     * @param onBytes (これまでに受け取ったバイト数 (再開分を含む), 全体のバイト数 (不明なら 0))
     */
    fun download(url: String, dest: File, expectedBytes: Long, onBytes: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                fetch(url, part, expectedBytes, onBytes)
                break
            } catch (e: IOException) {
                // 通信の一時的な切断はその場で数回やり直す (続きから)
                if (++attempt >= 3 || e is PermanentException) throw e
                Thread.sleep(2000L * attempt)
            }
        }
        if (expectedBytes > 0 && part.length() != expectedBytes) {
            part.delete()
            throw PermanentException("ダウンロードしたファイルの大きさが違います (${dest.name})")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("ファイルを保存できませんでした (${dest.name})")
    }

    private fun fetch(url: String, part: File, expectedBytes: Long, onBytes: (Long, Long) -> Unit) {
        var have = part.length()
        if (expectedBytes > 0 && have == expectedBytes) {
            onBytes(have, expectedBytes)
            return
        }
        if (expectedBytes in 1 until have) {
            part.delete()
            have = 0
        }

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                416 -> {
                    // 既に全部ある (大きさが分からないとき)
                    onBytes(have, have)
                    return
                }
                else -> if (code >= 500) {
                    throw IOException("サーバーエラー HTTP $code")
                } else {
                    throw PermanentException("ダウンロードできませんでした HTTP $code")
                }
            }
            if (!append) have = 0
            val length = conn.contentLengthLong
            val total = if (expectedBytes > 0) expectedBytes else if (length > 0) have + length else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    var reported = 0L
                    onBytes(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - reported >= 256 * 1024) {
                            reported = done
                            onBytes(done, total)
                        }
                    }
                    onBytes(done, total)
                    if (length > 0 && done - have < length) throw IOException("ダウンロードが途中で切れました")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** やり直しても直らないエラー */
    class PermanentException(message: String) : IOException(message)
}
