package jp.autojimaku.models

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** 端末内・軽量の音声認識 (Vosk) のモデルのダウンロードと管理 */
object VoskModels {
    private const val BASE_URL = "https://alphacephei.com/vosk/models/"
    private const val READY_MARK = ".ready"

    fun dir(context: Context, name: String): File = File(File(context.filesDir, "vosk"), name)

    fun isReady(context: Context, name: String): Boolean = File(dir(context, name), READY_MARK).exists()

    /** モデルをダウンロードして展開する。ブロッキングなのでバックグラウンドスレッドで呼ぶ。onProgress は 0〜100 */
    fun download(context: Context, name: String, onProgress: (Int) -> Unit) {
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        // 途中で切れたら続きから再開できるよう、ZIP は完了まで残しておく
        val zip = File(context.cacheDir, "$name.zip")
        val staging = File(root, ".staging-$name")
        var lastPct = -1
        Downloader.download("$BASE_URL$name.zip", zip, 0) { done, total ->
            if (total > 0) {
                val pct = (done * 100 / total).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        }
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(zip, staging)

            // ZIP の中は通常「モデル名/…」というフォルダ1つ
            val children = staging.listFiles()?.filter { !it.name.startsWith(".") && it.name != "__MACOSX" }.orEmpty()
            val content = if (children.size == 1 && children[0].isDirectory) children[0] else staging
            val target = dir(context, name)
            target.deleteRecursively()
            if (!content.renameTo(target)) throw IOException("モデルの展開に失敗しました")
            File(target, READY_MARK).writeText("ok")
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun unzip(zipFile: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw IOException("不正なZIPファイルです")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}
