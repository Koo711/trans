package jp.autojimaku

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import jp.autojimaku.models.GemmaModel
import jp.autojimaku.models.ModelKind
import jp.autojimaku.models.SherpaModel
import jp.autojimaku.models.SherpaModels
import jp.autojimaku.models.VoskModels

class MainActivity : Activity() {

    private lateinit var settings: AppSettings

    private lateinit var inputGroup: RadioGroup
    private lateinit var languageSpinner: Spinner
    private lateinit var languageInfo: TextView
    private lateinit var asrGroup: RadioGroup
    private lateinit var radioVosk: RadioButton
    private lateinit var radioSherpa: RadioButton
    private lateinit var whisperSection: LinearLayout
    private lateinit var whisperSpinner: Spinner
    private lateinit var asrInfo: TextView
    private lateinit var translateGroup: RadioGroup
    private lateinit var openAiSection: LinearLayout
    private lateinit var geminiSection: LinearLayout
    private lateinit var googleSection: LinearLayout
    private lateinit var gemmaSection: LinearLayout
    private lateinit var gemmaStatus: TextView
    private lateinit var gemmaDownloadButton: Button
    private lateinit var gemmaImportButton: Button
    private lateinit var gemmaDeleteButton: Button
    private lateinit var googleKeyEdit: EditText
    private lateinit var geminiKeyEdit: EditText
    private lateinit var geminiModelEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var sttModelEdit: EditText
    private lateinit var chatModelEdit: EditText
    private lateinit var fontSizeLabel: TextView
    private lateinit var fontSizeSeek: SeekBar
    private lateinit var visibleLinesLabel: TextView
    private lateinit var visibleLinesSeek: SeekBar
    private lateinit var showOriginalSwitch: Switch
    private lateinit var touchThroughSwitch: Switch
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    /** モデルのダウンロードなどの準備中 */
    private var preparing = false

    /** 「重ねて表示」の設定画面から戻ったら続きを行う */
    private var resumeAfterOverlayPermission = false

    /** このアプリを開いている間に翻訳モデルを確認済みの言語 */
    private var mlKitCheckedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        bindViews()
        loadValues()
        attachListeners()
        applyInsets()
    }

    override fun onResume() {
        super.onResume()
        CaptionService.stateListener = {
            updateUi()
            settings.consumeLastError()?.let { setStatus(it) }
        }
        updateUi()
        settings.consumeLastError()?.let { setStatus(it) }
        if (resumeAfterOverlayPermission) {
            resumeAfterOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                proceed()
            } else {
                setStatus("「他のアプリの上に重ねて表示」が許可されていません")
                showRestrictedSettingsHelp()
            }
        }
    }

    override fun onPause() {
        CaptionService.stateListener = null
        super.onPause()
    }

    // ---- 画面の設定 ----

    private fun bindViews() {
        inputGroup = requireViewById(R.id.inputGroup)
        languageSpinner = requireViewById(R.id.languageSpinner)
        languageInfo = requireViewById(R.id.languageInfo)
        asrGroup = requireViewById(R.id.asrGroup)
        radioVosk = requireViewById(R.id.radioVosk)
        radioSherpa = requireViewById(R.id.radioSherpa)
        whisperSection = requireViewById(R.id.whisperSection)
        whisperSpinner = requireViewById(R.id.whisperSpinner)
        asrInfo = requireViewById(R.id.asrInfo)
        translateGroup = requireViewById(R.id.translateGroup)
        openAiSection = requireViewById(R.id.openAiSection)
        geminiSection = requireViewById(R.id.geminiSection)
        googleSection = requireViewById(R.id.googleSection)
        gemmaSection = requireViewById(R.id.gemmaSection)
        gemmaStatus = requireViewById(R.id.gemmaStatus)
        gemmaDownloadButton = requireViewById(R.id.gemmaDownloadButton)
        gemmaImportButton = requireViewById(R.id.gemmaImportButton)
        gemmaDeleteButton = requireViewById(R.id.gemmaDeleteButton)
        googleKeyEdit = requireViewById(R.id.googleKeyEdit)
        geminiKeyEdit = requireViewById(R.id.geminiKeyEdit)
        geminiModelEdit = requireViewById(R.id.geminiModelEdit)
        apiKeyEdit = requireViewById(R.id.apiKeyEdit)
        sttModelEdit = requireViewById(R.id.sttModelEdit)
        chatModelEdit = requireViewById(R.id.chatModelEdit)
        fontSizeLabel = requireViewById(R.id.fontSizeLabel)
        fontSizeSeek = requireViewById(R.id.fontSizeSeek)
        visibleLinesLabel = requireViewById(R.id.visibleLinesLabel)
        visibleLinesSeek = requireViewById(R.id.visibleLinesSeek)
        showOriginalSwitch = requireViewById(R.id.showOriginalSwitch)
        touchThroughSwitch = requireViewById(R.id.touchThroughSwitch)
        progressBar = requireViewById(R.id.progressBar)
        statusText = requireViewById(R.id.statusText)
        startButton = requireViewById(R.id.startButton)
    }

    private fun loadValues() {
        inputGroup.check(if (settings.audioInput == AudioInput.PLAYBACK) R.id.radioPlayback else R.id.radioMic)
        asrGroup.check(radioIdFor(settings.asrEngine))
        translateGroup.check(
            when (settings.translateEngine) {
                TranslateEngineType.MLKIT -> R.id.radioMlKit
                TranslateEngineType.GEMMA -> R.id.radioTrGemma
                TranslateEngineType.GOOGLE -> R.id.radioTrGoogle
                TranslateEngineType.GEMINI -> R.id.radioTrGemini
                TranslateEngineType.OPENAI -> R.id.radioTrOpenAi
            },
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.all.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter
        languageSpinner.setSelection(Languages.all.indexOf(Languages.byId(settings.languageId)))

        val sizes = ArrayAdapter(this, android.R.layout.simple_spinner_item, WhisperSize.entries.map { it.label })
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        whisperSpinner.adapter = sizes
        whisperSpinner.setSelection(settings.whisperSize.ordinal)

        googleKeyEdit.setText(settings.googleKey)
        geminiKeyEdit.setText(settings.geminiKey)
        geminiModelEdit.setText(settings.geminiModel)
        apiKeyEdit.setText(settings.openAiKey)
        sttModelEdit.setText(settings.openAiSttModel)
        chatModelEdit.setText(settings.openAiChatModel)

        fontSizeSeek.max = AppSettings.MAX_FONT_SIZE - AppSettings.MIN_FONT_SIZE
        fontSizeSeek.progress = settings.fontSizeSp - AppSettings.MIN_FONT_SIZE
        fontSizeLabel.text = "文字サイズ: ${settings.fontSizeSp}"

        visibleLinesSeek.max = AppSettings.MAX_VISIBLE_LINES - AppSettings.MIN_VISIBLE_LINES
        visibleLinesSeek.progress = settings.visibleLines - AppSettings.MIN_VISIBLE_LINES
        visibleLinesLabel.text = visibleLinesText(settings.visibleLines)

        showOriginalSwitch.isChecked = settings.showOriginal
        touchThroughSwitch.isChecked = settings.touchThrough
        updateOpenAiSection()
        updateLanguageUi()
    }

    private fun attachListeners() {
        inputGroup.setOnCheckedChangeListener { _, id ->
            settings.audioInput = if (id == R.id.radioMic) AudioInput.MIC else AudioInput.PLAYBACK
        }
        asrGroup.setOnCheckedChangeListener { _, id ->
            settings.asrEngine = when (id) {
                R.id.radioVosk -> AsrEngineType.VOSK
                R.id.radioSherpa -> AsrEngineType.SHERPA
                else -> AsrEngineType.OPENAI
            }
            updateOpenAiSection()
            updateLanguageUi()
        }
        translateGroup.setOnCheckedChangeListener { _, id ->
            settings.translateEngine = when (id) {
                R.id.radioTrOpenAi -> TranslateEngineType.OPENAI
                R.id.radioTrGemma -> TranslateEngineType.GEMMA
                R.id.radioTrGoogle -> TranslateEngineType.GOOGLE
                R.id.radioTrGemini -> TranslateEngineType.GEMINI
                else -> TranslateEngineType.MLKIT
            }
            updateOpenAiSection()
            updateLanguageUi()
        }
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = Languages.all[position]
                settings.languageId = lang.code
                // その言語で使えない音声認識が選ばれていたら、使えるものに切り替える
                val usable = usableEngine(lang, settings.asrEngine)
                if (usable != settings.asrEngine) asrGroup.check(radioIdFor(usable))
                updateLanguageUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        whisperSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.whisperSize = WhisperSize.entries[position]
                updateLanguageUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        googleKeyEdit.addTextChangedListener(afterChanged { settings.googleKey = it })
        geminiKeyEdit.addTextChangedListener(afterChanged { settings.geminiKey = it })
        geminiModelEdit.addTextChangedListener(afterChanged { settings.geminiModel = it })
        apiKeyEdit.addTextChangedListener(afterChanged { settings.openAiKey = it })
        sttModelEdit.addTextChangedListener(afterChanged { settings.openAiSttModel = it })
        chatModelEdit.addTextChangedListener(afterChanged { settings.openAiChatModel = it })

        fontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = AppSettings.MIN_FONT_SIZE + progress
                fontSizeLabel.text = "文字サイズ: $size"
                if (fromUser) settings.fontSizeSp = size
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        visibleLinesSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val lines = AppSettings.MIN_VISIBLE_LINES + progress
                visibleLinesLabel.text = visibleLinesText(lines)
                if (fromUser) settings.visibleLines = lines
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        showOriginalSwitch.setOnCheckedChangeListener { _, checked -> settings.showOriginal = checked }
        touchThroughSwitch.setOnCheckedChangeListener { _, checked -> settings.touchThrough = checked }

        gemmaDownloadButton.setOnClickListener { confirmGemmaDownload(thenStart = false) }
        gemmaImportButton.setOnClickListener { pickGemmaFile() }
        gemmaDeleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("端末内AIのモデルを削除")
                .setMessage("Gemma のモデル（${GemmaModel.SIZE_LABEL}）を削除して、スマホの空き容量を増やします。使うときはもう一度ダウンロードが必要です。")
                .setPositiveButton("削除") { _, _ ->
                    if (CaptionService.isRunning && settings.translateEngine == TranslateEngineType.GEMMA) {
                        setStatus("字幕を停止してから削除してください")
                    } else {
                        GemmaModel.delete(this)
                        updateGemmaUi()
                        setStatus("端末内AIのモデルを削除しました")
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        startButton.setOnClickListener {
            if (CaptionService.isRunning) {
                CaptionService.stop(this)
                setStatus("字幕を停止しました")
            } else {
                proceed()
            }
        }
    }

    private fun visibleLinesText(lines: Int) = "字幕の箱の高さ: ${lines}行ぶん（それより前の字幕は箱を上下にスワイプして見られます）"

    private fun afterChanged(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            onChange(s?.toString() ?: "")
        }
    }

    private fun updateOpenAiSection() {
        val needed = settings.asrEngine == AsrEngineType.OPENAI || settings.translateEngine == TranslateEngineType.OPENAI
        openAiSection.visibility = if (needed) View.VISIBLE else View.GONE
        geminiSection.visibility = if (settings.translateEngine == TranslateEngineType.GEMINI) View.VISIBLE else View.GONE
        googleSection.visibility = if (settings.translateEngine == TranslateEngineType.GOOGLE) View.VISIBLE else View.GONE
        gemmaSection.visibility = if (settings.translateEngine == TranslateEngineType.GEMMA) View.VISIBLE else View.GONE
        updateGemmaUi()
    }

    private fun radioIdFor(engine: AsrEngineType) = when (engine) {
        AsrEngineType.VOSK -> R.id.radioVosk
        AsrEngineType.SHERPA -> R.id.radioSherpa
        AsrEngineType.OPENAI -> R.id.radioAsrOpenAi
    }

    private fun usableEngine(lang: SourceLanguage, engine: AsrEngineType): AsrEngineType = when {
        engine == AsrEngineType.VOSK && !lang.hasVosk ->
            if (lang.hasOnDevice) AsrEngineType.SHERPA else AsrEngineType.OPENAI
        engine == AsrEngineType.SHERPA && !lang.hasOnDevice ->
            if (lang.hasVosk) AsrEngineType.VOSK else AsrEngineType.OPENAI
        else -> engine
    }

    /** 選んだ言語で使える機能の案内と、選択肢の有効・無効 */
    private fun updateLanguageUi() {
        val lang = Languages.byId(settings.languageId)
        val engine = settings.asrEngine
        radioVosk.isEnabled = lang.hasVosk
        radioSherpa.isEnabled = lang.hasOnDevice

        val primary = SherpaModels.primaryFor(lang, settings.whisperSize)
        whisperSection.visibility =
            if (engine == AsrEngineType.SHERPA && primary.kind == ModelKind.WHISPER) View.VISIBLE else View.GONE

        fun mark(ok: Boolean) = if (ok) "○" else "×"
        val sherpaNote = when {
            !lang.hasOnDevice -> ""
            lang.special != null -> "（${primary.label}）"
            else -> "（Whisper）"
        }
        val translateLine = when {
            lang.isJapanese -> "翻訳: 不要（聞き取った日本語をそのまま字幕にします）"
            lang.isAuto -> "翻訳: 文ごとに言語を判別して翻訳します（初めての言語は翻訳モデルを自動でダウンロード）"
            lang.mlKit != null -> "端末内翻訳: ○"
            settings.translateEngine == TranslateEngineType.OPENAI -> "端末内翻訳: ×（OpenAI で翻訳します）"
            settings.translateEngine == TranslateEngineType.GEMINI -> "端末内翻訳: ×（Gemini で翻訳します。利用上限のときは原文のまま表示）"
            settings.translateEngine == TranslateEngineType.GOOGLE -> "端末内翻訳: ×（Google翻訳で翻訳します。利用上限のときは原文のまま表示）"
            settings.translateEngine == TranslateEngineType.GEMMA -> "端末内翻訳: ×（端末内AI（Gemma）で翻訳します）"
            else -> "端末内翻訳: × → この言語は翻訳を「Google翻訳」「Gemini」「OpenAI」のどれかにしてください"
        }
        languageInfo.text = "音声認識: 端末内・軽量 ${mark(lang.hasVosk)} ／ 端末内・多言語 ${mark(lang.hasOnDevice)}$sherpaNote ／ OpenAI ○\n$translateLine"

        asrInfo.text = when (engine) {
            AsrEngineType.VOSK ->
                if (lang.hasVosk) "モデル約${lang.voskMb}MB（初回のみダウンロード）。話している途中から字幕が出ます。" else ""
            AsrEngineType.SHERPA -> when {
                !lang.hasOnDevice -> ""
                lang.isAuto ->
                    "Whisper で文ごとに言語を判別します（モデル約${primary.totalMb}MB）。" +
                        "タイ語・ベトナム語の専用モデルをダウンロード済みなら、その言語は専用モデルで認識します。"
                lang.special != null -> "${primary.label}（約${primary.totalMb}MB・初回のみダウンロード）で認識します。文の区切りごとに字幕が出ます。"
                else -> "モデル約${primary.totalMb}MB（初回のみダウンロード）。文の区切りごとに字幕が出ます。"
            }
            AsrEngineType.OPENAI -> ""
        }
        asrInfo.visibility = if (asrInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateGemmaUi() {
        val ready = GemmaModel.isReady(this)
        gemmaStatus.text = if (ready) {
            "モデル: 用意済み（${GemmaModel.sizeOnDiskMb(this)}MB）"
        } else {
            "モデル: 未ダウンロード（${GemmaModel.SIZE_LABEL}）"
        }
        gemmaDownloadButton.isEnabled = !ready && !preparing
        gemmaImportButton.isEnabled = !ready && !preparing
        gemmaDeleteButton.isEnabled = ready && !preparing
    }

    private fun confirmGemmaDownload(thenStart: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("端末内AI（Gemma 4 E2B）のダウンロード")
            .setMessage(
                "スマホの中で翻訳するための AI モデル（${GemmaModel.SIZE_LABEL}）を一度だけダウンロードします。" +
                    "大きいので Wi-Fi でのダウンロードをおすすめします。途中で止まっても、次回は続きから再開します。\n\n" +
                    "パソコンなどで入手した gemma-4-E2B-it.litertlm ファイルがスマホにある場合は「ファイルから」を選んでください。",
            )
            .setPositiveButton("ダウンロード") { _, _ -> downloadGemma(thenStart) }
            .setNeutralButton("ファイルから") { _, _ -> pickGemmaFile() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun downloadGemma(thenStart: Boolean) {
        runGemmaTask("端末内AIのモデルをダウンロード中…", thenStart) { onPct ->
            GemmaModel.download(applicationContext, onPct)
        }
    }

    private fun pickGemmaFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_GEMMA_FILE)
        } catch (_: ActivityNotFoundException) {
            setStatus("ファイルを選ぶ画面を開けませんでした")
        }
    }

    private fun importGemma(uri: Uri) {
        runGemmaTask("端末内AIのモデルを読み込み中…", thenStart = false) { onPct ->
            GemmaModel.import(applicationContext, uri, onPct)
        }
    }

    /** ダウンロード・読み込みを別スレッドで行い、進み具合を表示する */
    private fun runGemmaTask(message: String, thenStart: Boolean, task: ((Int) -> Unit) -> Unit) {
        setPreparing(true, message)
        updateGemmaUi()
        Thread {
            try {
                task { pct ->
                    runOnUiThread {
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        setStatus("$message $pct%")
                    }
                }
                runOnUiThread {
                    setPreparing(false, "端末内AIのモデルの準備ができました")
                    updateGemmaUi()
                    if (thenStart && !isDestroyed) proceed()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setPreparing(false, "端末内AIのモデルを用意できませんでした: ${e.message}")
                    updateGemmaUi()
                }
            }
        }.start()
    }

    private fun updateUi() {
        startButton.text = when {
            preparing -> "準備中…"
            CaptionService.isRunning -> "字幕を停止"
            else -> "字幕を開始"
        }
        startButton.isEnabled = !preparing
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    /** Android 15 以降は画面端まで描画されるので、システムバーの分だけ余白を空ける */
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        val root = requireViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ---- 開始までの流れ: 権限 → モデル準備 → 再生音声の取得許可 → サービス開始 ----

    private fun proceed() {
        if (CaptionService.isRunning || preparing) return
        val lang = Languages.byId(settings.languageId)
        val asr = settings.asrEngine
        val tr = settings.translateEngine

        if ((asr == AsrEngineType.OPENAI || tr == TranslateEngineType.OPENAI) && settings.openAiKey.isBlank()) {
            setStatus("OpenAI を使う設定になっています。APIキーを入力してください。")
            apiKeyEdit.requestFocus()
            return
        }
        if (tr == TranslateEngineType.GEMMA && !GemmaModel.isReady(this)) {
            confirmGemmaDownload(thenStart = true)
            return
        }
        if (tr == TranslateEngineType.GOOGLE && settings.googleKey.isBlank()) {
            setStatus("Google翻訳（オンライン）で翻訳する設定になっています。Cloud Translation API の APIキーを入力してください。")
            googleKeyEdit.requestFocus()
            return
        }
        if (tr == TranslateEngineType.GEMINI && settings.geminiKey.isBlank()) {
            setStatus("Gemini で翻訳する設定になっています。Gemini の APIキーを入力してください。")
            geminiKeyEdit.requestFocus()
            return
        }
        if (tr == TranslateEngineType.MLKIT && !lang.mlKitOk) {
            setStatus("${lang.label}は端末内翻訳に対応していません。翻訳を「Google翻訳」「Gemini」「OpenAI」のどれかにしてください。")
            return
        }
        if (usableEngine(lang, asr) != asr) {
            setStatus("${lang.label}は選んだ音声認識に対応していません。別の音声認識を選んでください。")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            askOverlayPermission()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        val voskModel = lang.vosk
        if (asr == AsrEngineType.VOSK && voskModel != null && !VoskModels.isReady(this, voskModel)) {
            confirmVoskDownload(lang, voskModel)
            return
        }
        if (asr == AsrEngineType.SHERPA) {
            val missing = SherpaModels.requiredFor(lang, settings.whisperSize).filterNot { SherpaModels.isReady(this, it) }
            if (missing.isNotEmpty()) {
                confirmSherpaDownload(missing)
                return
            }
        }
        val mlKit = lang.mlKit
        // Google翻訳・Gemini のときも、利用上限時の代わり (端末内翻訳) に使うモデルを先に用意しておく
        val needsMlKit = tr != TranslateEngineType.OPENAI
        if (needsMlKit && mlKit != null && !lang.isAuto && !lang.isJapanese && mlKitCheckedFor != mlKit) {
            prepareMlKit(lang, mlKit)
            return
        }
        if (settings.audioInput == AudioInput.PLAYBACK) {
            requestPlaybackCapture()
        } else {
            startCaptions(Activity.RESULT_OK, null)
        }
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("重ねて表示の許可")
            .setMessage(
                "字幕を YouTube や Udemy の上に表示するために必要です。\n\n" +
                    "次の画面で「${getString(R.string.app_name)}」をオンにしてから、この画面に戻ってください。\n\n" +
                    "スイッチがグレーで押せないときは、一度タップしてからこの画面に戻ってください。解除の手順をご案内します。",
            )
            .setPositiveButton("設定を開く") { _, _ -> openOverlaySettings() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openOverlaySettings() {
        resumeAfterOverlayPermission = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    /**
     * Android 15 以降、Google Play 以外から入れたアプリは「重ねて表示」が「制限付き設定」でロックされる。
     * アプリ情報の︙メニューから「制限付き設定を許可」すると解除できるので、その手順を案内する。
     */
    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle("スイッチがグレーで押せない場合")
            .setMessage(
                "Google Play 以外から入れたアプリは、Android の「制限付き設定」で許可がロックされています。次の手順で解除できます。\n\n" +
                    "1. 下の「アプリ情報を開く」を押す\n" +
                    "2. 右上の︙（その他）→「制限付き設定を許可」を押し、画面ロックで確認\n" +
                    "3. このアプリに戻り、もう一度「字幕を開始」を押してスイッチをオンにする\n\n" +
                    "︙に項目が無いときは、先に許可の画面でグレーのスイッチを一度タップしてから、もう一度見てください。",
            )
            .setPositiveButton("アプリ情報を開く") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNeutralButton("許可の画面を開く") { _, _ -> openOverlaySettings() }
            .setNegativeButton("閉じる", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            proceed()
        } else {
            setStatus(
                "音声を取得するには「録音」の許可が必要です（再生中の音声を取得する場合も Android の仕組み上必要です）。" +
                    "許可できない場合は、端末の設定 → アプリ → ${getString(R.string.app_name)} → 権限 から許可してください。",
            )
        }
    }

    private fun confirmVoskDownload(lang: SourceLanguage, model: String) {
        AlertDialog.Builder(this)
            .setTitle("音声認識モデルのダウンロード")
            .setMessage("端末内で音声を聞き取るためのモデル（${lang.label}・約${lang.voskMb}MB）を一度だけダウンロードします。Wi-Fi でのダウンロードをおすすめします。")
            .setPositiveButton("ダウンロード") { _, _ -> downloadVosk(model) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmSherpaDownload(models: List<SherpaModel>) {
        val mb = models.sumOf { it.totalBytes } / 1_000_000
        val names = models.filter { it.kind != ModelKind.VAD }.joinToString("・") { it.label }.ifEmpty { "音声区間の検出" }
        AlertDialog.Builder(this)
            .setTitle("音声認識モデルのダウンロード")
            .setMessage(
                "端末内で音声を聞き取るためのモデル（$names・約${mb}MB）を一度だけダウンロードします。" +
                    "Wi-Fi でのダウンロードをおすすめします。途中で止まっても、次回は続きから再開します。",
            )
            .setPositiveButton("ダウンロード") { _, _ -> downloadSherpa(models) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun downloadSherpa(models: List<SherpaModel>) {
        setPreparing(true, "音声認識モデルをダウンロード中…")
        val appContext = applicationContext
        Thread {
            try {
                SherpaModels.download(appContext, models) { pct ->
                    runOnUiThread {
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        setStatus("音声認識モデルをダウンロード中… $pct%")
                    }
                }
                runOnUiThread {
                    setPreparing(false, "音声認識モデルの準備ができました")
                    updateLanguageUi()
                    if (!isDestroyed) proceed()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setPreparing(false, "ダウンロードに失敗しました: ${e.message}\n通信環境を確認して、もう一度「字幕を開始」を押してください（続きから再開します）。")
                }
            }
        }.start()
    }

    private fun downloadVosk(model: String) {
        setPreparing(true, "音声認識モデルをダウンロード中…")
        val appContext = applicationContext
        Thread {
            try {
                VoskModels.download(appContext, model) { pct ->
                    runOnUiThread {
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        setStatus(if (pct < 100) "音声認識モデルをダウンロード中… $pct%" else "モデルを展開中…")
                    }
                }
                runOnUiThread {
                    setPreparing(false, "音声認識モデルの準備ができました")
                    if (!isDestroyed) proceed()
                }
            } catch (e: Exception) {
                runOnUiThread { setPreparing(false, "ダウンロードに失敗しました: ${e.message}\n通信環境を確認して、もう一度お試しください。") }
            }
        }.start()
    }

    private fun prepareMlKit(lang: SourceLanguage, mlKit: String) {
        setPreparing(true, "${lang.label}の翻訳モデルを準備中…（初回は約30MBのダウンロード）")
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mlKit)
                .setTargetLanguage(TranslateLanguage.JAPANESE)
                .build(),
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                mlKitCheckedFor = mlKit
                setPreparing(false, "翻訳モデルの準備ができました")
                if (!isDestroyed) proceed()
            }
            .addOnFailureListener { e ->
                translator.close()
                setPreparing(false, "翻訳モデルの準備に失敗しました: ${e.message}\n通信環境を確認して、もう一度お試しください。")
            }
    }

    private fun setPreparing(value: Boolean, message: String) {
        preparing = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progressBar.isIndeterminate = true
        setStatus(message)
        updateUi()
    }

    private fun requestPlaybackCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // 「画面全体」を選ばせる (アプリ単位の共有だと音声を取得できない場合がある)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        setStatus("確認画面で「開始」を選んでください。映像は記録せず、音声だけを字幕に使います。")
        try {
            startActivityForResult(intent, REQ_PROJECTION)
        } catch (e: ActivityNotFoundException) {
            setStatus("この端末では再生音声を取得できません。音声の取得元を「マイク」にしてください。")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GEMMA_FILE) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) importGemma(uri) else setStatus("キャンセルされました")
            return
        }
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            startCaptions(resultCode, data)
        } else {
            setStatus("キャンセルされました")
        }
    }

    private fun startCaptions(resultCode: Int, data: Intent?) {
        try {
            CaptionService.start(this, resultCode, data)
            setStatus("字幕を開始しました。このまま YouTube や Udemy を開いて動画を再生してください。")
        } catch (e: Exception) {
            setStatus("字幕を開始できませんでした: ${e.message}")
        }
        updateUi()
    }

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_PROJECTION = 2
        const val REQ_GEMMA_FILE = 3
    }
}
