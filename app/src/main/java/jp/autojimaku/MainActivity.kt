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
    private lateinit var apiKeyEdit: EditText
    private lateinit var sttModelEdit: EditText
    private lateinit var chatModelEdit: EditText
    private lateinit var fontSizeLabel: TextView
    private lateinit var fontSizeSeek: SeekBar
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
            if (Settings.canDrawOverlays(this)) proceed()
            else setStatus("「他のアプリの上に重ねて表示」が許可されていません")
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
        apiKeyEdit = requireViewById(R.id.apiKeyEdit)
        sttModelEdit = requireViewById(R.id.sttModelEdit)
        chatModelEdit = requireViewById(R.id.chatModelEdit)
        fontSizeLabel = requireViewById(R.id.fontSizeLabel)
        fontSizeSeek = requireViewById(R.id.fontSizeSeek)
        showOriginalSwitch = requireViewById(R.id.showOriginalSwitch)
        touchThroughSwitch = requireViewById(R.id.touchThroughSwitch)
        progressBar = requireViewById(R.id.progressBar)
        statusText = requireViewById(R.id.statusText)
        startButton = requireViewById(R.id.startButton)
    }

    private fun loadValues() {
        inputGroup.check(if (settings.audioInput == AudioInput.PLAYBACK) R.id.radioPlayback else R.id.radioMic)
        asrGroup.check(radioIdFor(settings.asrEngine))
        translateGroup.check(if (settings.translateEngine == TranslateEngineType.MLKIT) R.id.radioMlKit else R.id.radioTrOpenAi)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.all.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter
        languageSpinner.setSelection(Languages.all.indexOf(Languages.byId(settings.languageId)))

        val sizes = ArrayAdapter(this, android.R.layout.simple_spinner_item, WhisperSize.entries.map { it.label })
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        whisperSpinner.adapter = sizes
        whisperSpinner.setSelection(settings.whisperSize.ordinal)

        apiKeyEdit.setText(settings.openAiKey)
        sttModelEdit.setText(settings.openAiSttModel)
        chatModelEdit.setText(settings.openAiChatModel)

        fontSizeSeek.max = AppSettings.MAX_FONT_SIZE - AppSettings.MIN_FONT_SIZE
        fontSizeSeek.progress = settings.fontSizeSp - AppSettings.MIN_FONT_SIZE
        fontSizeLabel.text = "文字サイズ: ${settings.fontSizeSp}"

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
            settings.translateEngine = if (id == R.id.radioTrOpenAi) TranslateEngineType.OPENAI else TranslateEngineType.MLKIT
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
        showOriginalSwitch.setOnCheckedChangeListener { _, checked -> settings.showOriginal = checked }
        touchThroughSwitch.setOnCheckedChangeListener { _, checked -> settings.touchThrough = checked }

        startButton.setOnClickListener {
            if (CaptionService.isRunning) {
                CaptionService.stop(this)
                setStatus("字幕を停止しました")
            } else {
                proceed()
            }
        }
    }

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
            else -> "端末内翻訳: × → この言語は翻訳を「OpenAI」にしてください"
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
        if (tr == TranslateEngineType.MLKIT && !lang.mlKitOk) {
            setStatus("${lang.label}は端末内翻訳に対応していません。翻訳を「OpenAI」にしてください。")
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
        if (tr == TranslateEngineType.MLKIT && mlKit != null && !lang.isAuto && !lang.isJapanese && mlKitCheckedFor != mlKit) {
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
                    "次の画面で「${getString(R.string.app_name)}」をオンにしてから、この画面に戻ってください。",
            )
            .setPositiveButton("設定を開く") { _, _ ->
                resumeAfterOverlayPermission = true
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("キャンセル", null)
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
    }
}
