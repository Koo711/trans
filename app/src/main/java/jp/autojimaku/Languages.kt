package jp.autojimaku

import java.text.Collator
import java.util.Locale

/** 特定の言語専用の端末内モデル (sherpa-onnx) */
enum class SpecialModel { THAI, VIETNAMESE }

/** 動画で話されている言語 */
data class SourceLanguage(
    /** 識別子 兼 言語コード (ISO 639-1)。自動判別は "auto" */
    val code: String,
    val label: String,
    /** ML Kit 端末内翻訳の言語コード。null = 端末内翻訳に未対応 (OpenAI 翻訳のみ) */
    val mlKit: String?,
    /** 端末内・軽量 (Vosk) のモデル名と大きさ (MB) */
    val vosk: String? = null,
    val voskMb: Int = 0,
    /** Whisper (端末内・多言語) が対応しているか */
    val whisper: Boolean = true,
    /** Whisper / OpenAI に渡す言語コード。null = 自動判別させる */
    val asrHint: String? = code,
    /** タイ語・ベトナム語の専用モデル */
    val special: SpecialModel? = null,
) {
    val isAuto: Boolean get() = code == AUTO
    val isJapanese: Boolean get() = code == "ja"
    val hasVosk: Boolean get() = vosk != null
    val hasOnDevice: Boolean get() = whisper || special != null

    /** 端末内翻訳 (ML Kit) で字幕にできるか */
    val mlKitOk: Boolean get() = isAuto || isJapanese || mlKit != null

    companion object {
        const val AUTO = "auto"
    }
}

object Languages {
    private fun l(
        code: String,
        label: String,
        mlKit: String? = code,
        vosk: String? = null,
        voskMb: Int = 0,
        whisper: Boolean = true,
        asrHint: String? = code,
        special: SpecialModel? = null,
    ) = SourceLanguage(code, label, mlKit, vosk, voskMb, whisper, asrHint, special)

    /** よく使う言語 (この順で先頭に並べる) */
    private val popular = listOf(
        l(SourceLanguage.AUTO, "自動判別（複数の言語が混ざる動画も可）", mlKit = null, asrHint = null),
        l("en", "英語", vosk = "vosk-model-small-en-us-0.15", voskMb = 40),
        l("zh", "中国語", vosk = "vosk-model-small-cn-0.22", voskMb = 42),
        l("ko", "韓国語", vosk = "vosk-model-small-ko-0.22", voskMb = 82),
        l("th", "タイ語", special = SpecialModel.THAI),
        l("vi", "ベトナム語", vosk = "vosk-model-small-vn-0.4", voskMb = 32, special = SpecialModel.VIETNAMESE),
        l("id", "インドネシア語"),
        l("es", "スペイン語", vosk = "vosk-model-small-es-0.42", voskMb = 39),
        l("fr", "フランス語", vosk = "vosk-model-small-fr-0.22", voskMb = 41),
        l("de", "ドイツ語", vosk = "vosk-model-small-de-0.15", voskMb = 45),
        l("pt", "ポルトガル語", vosk = "vosk-model-small-pt-0.3", voskMb = 31),
        l("ru", "ロシア語", vosk = "vosk-model-small-ru-0.22", voskMb = 45),
        l("hi", "ヒンディー語", vosk = "vosk-model-small-hi-0.22", voskMb = 42),
        l("ja", "日本語（翻訳せず文字起こしのみ）", vosk = "vosk-model-small-ja-0.22", voskMb = 48),
    )

    /** その他 (五十音順に並べる) */
    private val others = listOf(
        l("is", "アイスランド語"),
        l("az", "アゼルバイジャン語", mlKit = null),
        l("af", "アフリカーンス語"),
        l("am", "アムハラ語", mlKit = null),
        l("ar", "アラビア語"),
        l("sq", "アルバニア語"),
        l("hy", "アルメニア語", mlKit = null),
        l("it", "イタリア語", vosk = "vosk-model-small-it-0.22", voskMb = 48),
        l("cy", "ウェールズ語"),
        l("uk", "ウクライナ語", vosk = "vosk-model-small-uk-v3-nano", voskMb = 73),
        l("uz", "ウズベク語", mlKit = null, vosk = "vosk-model-small-uz-0.22", voskMb = 49),
        l("ur", "ウルドゥー語"),
        l("et", "エストニア語"),
        l("eo", "エスペラント語", vosk = "vosk-model-small-eo-0.42", voskMb = 42, whisper = false),
        l("nl", "オランダ語", vosk = "vosk-model-small-nl-0.22", voskMb = 39),
        l("kk", "カザフ語", mlKit = null, vosk = "vosk-model-small-kz-0.42", voskMb = 58),
        l("ca", "カタルーニャ語", vosk = "vosk-model-small-ca-0.4", voskMb = 42),
        l("gl", "ガリシア語"),
        l("kn", "カンナダ語"),
        l("yue", "広東語", mlKit = "zh", asrHint = null),
        l("el", "ギリシャ語"),
        l("ky", "キルギス語", mlKit = null, vosk = "vosk-model-small-ky-0.42", voskMb = 49, whisper = false),
        l("gu", "グジャラート語", vosk = "vosk-model-small-gu-0.42", voskMb = 100),
        l("km", "クメール語（カンボジア語）", mlKit = null),
        l("hr", "クロアチア語"),
        l("ka", "ジョージア語", vosk = "vosk-model-small-ka-0.42", voskMb = 45),
        l("si", "シンハラ語", mlKit = null),
        l("sv", "スウェーデン語"),
        l("sw", "スワヒリ語"),
        l("sk", "スロバキア語"),
        l("sl", "スロベニア語"),
        l("sr", "セルビア語", mlKit = null),
        l("so", "ソマリ語", mlKit = null),
        l("tg", "タジク語", mlKit = null, vosk = "vosk-model-small-tg-0.22", voskMb = 50),
        l("ta", "タミル語"),
        l("cs", "チェコ語", vosk = "vosk-model-small-cs-0.4-rhasspy", voskMb = 44),
        l("da", "デンマーク語"),
        l("te", "テルグ語", vosk = "vosk-model-small-te-0.42", voskMb = 58),
        l("tr", "トルコ語", vosk = "vosk-model-small-tr-0.3", voskMb = 35),
        l("ne", "ネパール語", mlKit = null),
        l("no", "ノルウェー語"),
        l("ht", "ハイチ語"),
        l("ha", "ハウサ語", mlKit = null),
        l("eu", "バスク語", mlKit = null),
        l("ps", "パシュトー語", mlKit = null),
        l("pa", "パンジャブ語", mlKit = null),
        l("hu", "ハンガリー語"),
        l("tl", "フィリピン語（タガログ語）"),
        l("fi", "フィンランド語"),
        l("bg", "ブルガリア語"),
        l("be", "ベラルーシ語"),
        l("he", "ヘブライ語"),
        l("fa", "ペルシャ語", vosk = "vosk-model-small-fa-0.42", voskMb = 53),
        l("bn", "ベンガル語"),
        l("pl", "ポーランド語", vosk = "vosk-model-small-pl-0.22", voskMb = 50),
        l("bs", "ボスニア語", mlKit = null),
        l("mk", "マケドニア語"),
        l("mr", "マラーティー語"),
        l("ml", "マラヤーラム語", mlKit = null),
        l("mt", "マルタ語"),
        l("ms", "マレー語"),
        l("my", "ミャンマー語", mlKit = null),
        l("mn", "モンゴル語", mlKit = null),
        l("yo", "ヨルバ語", mlKit = null),
        l("lo", "ラオ語", mlKit = null),
        l("la", "ラテン語", mlKit = null),
        l("lv", "ラトビア語"),
        l("lt", "リトアニア語"),
        l("ro", "ルーマニア語"),
    )

    val all: List<SourceLanguage> = popular + others.sortedWith(compareBy(Collator.getInstance(Locale.JAPANESE)) { it.label })

    /** 以前の版の設定値 ("en_hq" など) も受け付ける */
    fun byId(id: String?): SourceLanguage {
        val key = when (id) {
            null -> "en"
            "en_hq" -> "en"
            else -> id
        }
        return all.firstOrNull { it.code == key } ?: all.first { it.code == "en" }
    }
}
