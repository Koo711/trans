package jp.autojimaku.models

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 端末内AI翻訳に使う Gemma 4 E2B (LiteRT-LM 形式) のモデルファイルの管理 */
object GemmaModel {
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$FILE_NAME"
    const val SIZE_BYTES = 2_588_147_712L
    const val SIZE_LABEL = "約2.6GB"

    /** 自分で用意したファイルとして受け付ける最小の大きさ (壊れたファイルや別物をはじく) */
    private const val MIN_IMPORT_BYTES = 500_000_000L

    fun file(context: Context): File = File(File(context.filesDir, "gemma"), FILE_NAME)

    fun isReady(context: Context): Boolean = file(context).length() >= MIN_IMPORT_BYTES

    fun sizeOnDiskMb(context: Context): Long = file(context).length() / 1_000_000

    /** Hugging Face からダウンロードする (途中で切れたら続きから)。onProgress は 0〜100 */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        // 途中までダウンロード済みなら、残りの分だけ空きがあればよい
        val have = File(file(context).path + ".part").length()
        ensureFreeSpace(context, SIZE_BYTES - have)
        var lastPct = -1
        Downloader.download(URL, file(context), SIZE_BYTES) { done, _ ->
            val pct = (done * 100 / SIZE_BYTES).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
        }
    }

    /** 端末内の .litertlm ファイル (ダウンロードフォルダなど) をアプリにコピーする。onProgress は 0〜100 */
    fun import(context: Context, uri: Uri, onProgress: (Int) -> Unit) {
        val resolver = context.contentResolver
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (total in 1 until MIN_IMPORT_BYTES) throw IOException("Gemma のモデルファイルではないようです（ファイルが小さすぎます）")
        if (total > 0) ensureFreeSpace(context, total)

        val dest = file(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".import")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("ファイルを開けませんでした")
            input.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    if (done < MIN_IMPORT_BYTES) throw IOException("Gemma のモデルファイルではないようです（ファイルが小さすぎます）")
                }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("モデルを保存できませんでした")
        } finally {
            tmp.delete()
        }
    }

    fun delete(context: Context) {
        val f = file(context)
        f.delete()
        File(f.path + ".part").delete()
        File(f.path + ".import").delete()
        File(context.cacheDir, "gemma").deleteRecursively()
    }

    private fun ensureFreeSpace(context: Context, needed: Long) {
        val free = context.filesDir.usableSpace
        val want = needed + 300_000_000L
        if (free < want) {
            throw IOException(
                String.format(java.util.Locale.ROOT, "スマホの空き容量が足りません（約%.1fGB 必要・空き%.1fGB）", want / 1e9, free / 1e9),
            )
        }
    }
}
