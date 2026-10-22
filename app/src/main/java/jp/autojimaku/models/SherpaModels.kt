package jp.autojimaku.models

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import jp.autojimaku.SourceLanguage
import jp.autojimaku.SpecialModel
import jp.autojimaku.WhisperSize
import java.io.File

/** ダウンロードするファイル。name は保存するときの名前 */
data class ModelFile(val url: String, val name: String, val bytes: Long)

enum class ModelKind { WHISPER, TRANSDUCER, VAD }

/** 端末内・多言語の音声認識 (sherpa-onnx) で使うモデル */
data class SherpaModel(
    val id: String,
    val label: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    /** 専用モデルが対応する言語 (Whisper は null) */
    val language: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val totalMb: Int get() = (totalBytes / 1_000_000).toInt()
}

object SherpaModels {
    private const val HF = "https://huggingface.co"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /** 話している区間の検出 (Silero VAD)。どのモデルを使うときも必要 */
    val VAD = SherpaModel(
        "silero-vad", "音声区間の検出", ModelKind.VAD,
        listOf(ModelFile("$GH/silero_vad.onnx", "silero_vad.onnx", 643_854)),
    )

    /** タイ語専用 (GigaSpeech2 で学習した Zipformer) */
    val THAI = SherpaModel(
        "zipformer-th-2024-06-20", "タイ語専用モデル", ModelKind.TRANSDUCER,
        "$HF/yfyeung/icefall-asr-gigaspeech2-th-zipformer-2024-06-20/resolve/main".let { b ->
            listOf(
                ModelFile("$b/exp/encoder-epoch-12-avg-5.int8.onnx", "encoder.int8.onnx", 154_671_320),
                ModelFile("$b/exp/decoder-epoch-12-avg-5.onnx", "decoder.onnx", 5_165_084),
                ModelFile("$b/exp/joiner-epoch-12-avg-5.int8.onnx", "joiner.int8.onnx", 1_033_417),
                ModelFile("$b/data/lang_bpe_2000/tokens.txt", "tokens.txt", 39_142),
            )
        },
        language = "th",
    )

    /** ベトナム語専用 (Zipformer 30M) */
    val VIETNAMESE = SherpaModel(
        "zipformer-vi-30m-2026-02-09", "ベトナム語専用モデル", ModelKind.TRANSDUCER,
        "$HF/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/resolve/main".let { b ->
            listOf(
                ModelFile("$b/encoder.int8.onnx", "encoder.int8.onnx", 27_699_063),
                ModelFile("$b/decoder.onnx", "decoder.onnx", 5_165_084),
                ModelFile("$b/joiner.int8.onnx", "joiner.int8.onnx", 1_033_417),
                ModelFile("$b/tokens.txt", "tokens.txt", 23_238),
            )
        },
        language = "vi",
    )

    fun whisper(size: WhisperSize): SherpaModel {
        val s = size.id
        val b = "$HF/csukuangfj/sherpa-onnx-whisper-$s/resolve/main"
        val (enc, dec) = when (size) {
            WhisperSize.TINY -> 12_937_772L to 89_855_401L
            WhisperSize.BASE -> 29_120_534L to 130_672_026L
            WhisperSize.SMALL -> 112_442_483L to 262_226_114L
        }
        return SherpaModel(
            "whisper-$s", "Whisper $s", ModelKind.WHISPER,
            listOf(
                ModelFile("$b/$s-encoder.int8.onnx", "encoder.int8.onnx", enc),
                ModelFile("$b/$s-decoder.int8.onnx", "decoder.int8.onnx", dec),
                ModelFile("$b/$s-tokens.txt", "tokens.txt", 816_730),
            ),
        )
    }

    fun special(model: SpecialModel): SherpaModel = when (model) {
        SpecialModel.THAI -> THAI
        SpecialModel.VIETNAMESE -> VIETNAMESE
    }

    /** その言語で使う主なモデル (タイ語・ベトナム語は専用モデル、それ以外は Whisper) */
    fun primaryFor(lang: SourceLanguage, size: WhisperSize): SherpaModel =
        lang.special?.let { special(it) } ?: whisper(size)

    /** 開始前にそろっている必要があるモデル */
    fun requiredFor(lang: SourceLanguage, size: WhisperSize): List<SherpaModel> =
        listOf(VAD, primaryFor(lang, size))

    fun dir(context: Context, model: SherpaModel): File = File(File(context.filesDir, "sherpa"), model.id)

    fun file(context: Context, model: SherpaModel, name: String): File = File(dir(context, model), name)

    fun isReady(context: Context, model: SherpaModel): Boolean =
        model.files.all { File(dir(context, model), it.name).length() == it.bytes }

    /** まだ無いファイルをまとめてダウンロードする。onProgress は 0〜100 */
    fun download(context: Context, models: List<SherpaModel>, onProgress: (Int) -> Unit) {
        val todo = models.flatMap { m -> m.files.map { m to it } }
            .filter { (m, f) -> File(dir(context, m), f.name).length() != f.bytes }
        val total = todo.sumOf { it.second.bytes }.coerceAtLeast(1)
        var finished = 0L
        var lastPct = -1
        for ((m, f) in todo) {
            Downloader.download(f.url, File(dir(context, m), f.name), f.bytes) { got, _ ->
                val pct = ((finished + got) * 100 / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
            finished += f.bytes
        }
    }

    /** sherpa-onnx の設定を作る。whisperLanguage は "" で自動判別 */
    fun buildConfig(dir: File, model: SherpaModel, whisperLanguage: String, numThreads: Int): OfflineModelConfig {
        fun p(name: String) = File(dir, name).absolutePath
        return when (model.kind) {
            ModelKind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    language = whisperLanguage,
                    task = "transcribe",
                ),
                tokens = p("tokens.txt"),
                numThreads = numThreads,
                modelType = "whisper",
            )
            ModelKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.onnx"),
                    joiner = p("joiner.int8.onnx"),
                ),
                tokens = p("tokens.txt"),
                numThreads = numThreads,
                modelType = "transducer",
            )
            ModelKind.VAD -> throw IllegalArgumentException("VAD は音声認識モデルではありません")
        }
    }
}
