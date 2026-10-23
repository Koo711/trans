package jp.autojimaku

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import jp.autojimaku.cloud.GeminiClient
import jp.autojimaku.cloud.GoogleTranslateClient
import jp.autojimaku.cloud.OpenAiClient
import jp.autojimaku.models.GemmaModel
import jp.autojimaku.models.ModelKind
import jp.autojimaku.models.SherpaModels
import jp.autojimaku.models.VoskModels
import jp.autojimaku.speech.OpenAiSpeechEngine
import jp.autojimaku.speech.SherpaEngine
import jp.autojimaku.speech.SpeechEngine
import jp.autojimaku.speech.VoskEngine
import jp.autojimaku.translate.CaptionTranslator
import jp.autojimaku.translate.GeminiCaptionTranslator
import jp.autojimaku.translate.GemmaCaptionTranslator
import jp.autojimaku.translate.GoogleCloudCaptionTranslator
import jp.autojimaku.translate.MlKitCaptionTranslator
import jp.autojimaku.translate.OpenAiCaptionTranslator

/**
 * 字幕を出している間ずっと動くフォアグラウンドサービス。
 * 音声の取得 → 音声認識 → 翻訳 → 重ね表示 を管理する。
 */
class CaptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var started = false
    private var stopped = false // lock で保護

    private var overlay: CaptionOverlay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: AudioCapture? = null
    private var pipeline: CaptionPipeline? = null
    private var translator: CaptionTranslator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> if (!started) {
                started = true
                begin(intent)
            }
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        synchronized(lock) { stopped = true }
        isRunning = false
        notifyState()

        capture?.stop()
        capture = null
        pipeline?.close()
        pipeline = null
        translator?.close()
        translator = null
        overlay?.destroy()
        overlay = null

        projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        val settings = AppSettings(this)
        val usePlayback = settings.audioInput == AudioInput.PLAYBACK

        // startForegroundService() で起動されたので、まず必ずフォアグラウンド化する
        createChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (usePlayback) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, notification, type)
            } else if (usePlayback) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail("字幕サービスを開始できませんでした: ${e.message}")
            return
        }

        isRunning = true
        notifyState()

        if (!Settings.canDrawOverlays(this)) {
            fail("「他のアプリの上に重ねて表示」を許可してください")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("録音（音声の取得）の許可が必要です")
            return
        }

        if (usePlayback) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

            @Suppress("DEPRECATION")
            val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            val mp = try {
                if (data == null) null
                else getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            }
            if (mp == null) {
                fail("再生音声を取得する許可を得られませんでした")
                return
            }
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 通知やクイック設定から共有が止められた
                    main.post { stopSelf() }
                }
            }
            mp.registerCallback(cb, main)
            projection = mp
            projectionCallback = cb
        }

        val ov = CaptionOverlay(this)
        try {
            ov.show()
        } catch (e: Exception) {
            fail("字幕を画面に表示できませんでした: ${e.message}")
            return
        }
        overlay = ov
        ov.showStatus("字幕の準備中…", 0)

        val mp = projection
        Thread({ setup(settings, mp) }, "CaptionSetup").start()
    }

    /** 時間のかかる準備 (モデル読み込みなど) を別スレッドで行う */
    private fun setup(settings: AppSettings, mp: MediaProjection?) {
        val lang = Languages.byId(settings.languageId)
        var tr: CaptionTranslator? = null
        var pipe: CaptionPipeline? = null
        var engine: SpeechEngine? = null
        var record: AudioRecord? = null
        try {
            val key = settings.openAiKey
            val client = if (key.isNotBlank()) OpenAiClient(key) else null
            fun requireClient() = client ?: throw IllegalStateException("OpenAI の APIキーが設定されていません")

            status("翻訳の準備中…")
            val showStatus: (String) -> Unit = { msg -> main.post { overlay?.showStatus(msg, 0) } }
            fun mlKit() = MlKitCaptionTranslator(
                fixedLanguage = if (lang.isAuto || lang.isJapanese) null else lang.mlKit,
                onStatus = showStatus,
            )
            tr = when (settings.translateEngine) {
                TranslateEngineType.MLKIT -> {
                    if (!lang.mlKitOk) throw IllegalStateException("${lang.label}は端末内翻訳に対応していません。翻訳を「Google翻訳」「Gemini」「OpenAI」のどれかにしてください")
                    mlKit()
                }
                TranslateEngineType.OPENAI ->
                    OpenAiCaptionTranslator(requireClient(), settings.openAiChatModel, lang.label.takeUnless { lang.isAuto })
                TranslateEngineType.GEMMA -> {
                    if (!GemmaModel.isReady(this)) {
                        throw IllegalStateException("端末内AI（Gemma）のモデルがありません。アプリを開いてもう一度「字幕を開始」を押してください")
                    }
                    GemmaCaptionTranslator(
                        modelPath = GemmaModel.file(this).absolutePath,
                        cacheDir = java.io.File(cacheDir, "gemma").apply { mkdirs() }.absolutePath,
                        sourceLanguageLabel = lang.label.takeUnless { lang.isAuto },
                        // 1文の翻訳に失敗したときは端末内翻訳で続ける
                        fallback = if (lang.mlKitOk) mlKit() else null,
                        onStatus = showStatus,
                    )
                }
                TranslateEngineType.GOOGLE -> {
                    val googleKey = settings.googleKey
                    if (googleKey.isBlank()) throw IllegalStateException("Google翻訳の APIキーが設定されていません")
                    GoogleCloudCaptionTranslator(
                        client = GoogleTranslateClient(googleKey),
                        // 利用上限や通信エラーのときは端末内翻訳で続ける
                        fallback = if (lang.mlKitOk) mlKit() else null,
                        onStatus = { msg -> main.post { overlay?.showStatus(msg, 8000) } },
                    )
                }
                TranslateEngineType.GEMINI -> {
                    val geminiKey = settings.geminiKey
                    if (geminiKey.isBlank()) throw IllegalStateException("Gemini の APIキーが設定されていません")
                    GeminiCaptionTranslator(
                        client = GeminiClient(geminiKey),
                        model = settings.geminiModel,
                        sourceLanguageLabel = lang.label.takeUnless { lang.isAuto },
                        // 利用上限や通信エラーのときは端末内翻訳で続ける
                        fallback = if (lang.mlKitOk) mlKit() else null,
                        onStatus = { msg -> main.post { overlay?.showStatus(msg, 8000) } },
                    )
                }
            }
            tr.prepare()

            pipe = CaptionPipeline(
                translator = tr,
                translatePartials = settings.translateEngine == TranslateEngineType.MLKIT &&
                    settings.asrEngine == AsrEngineType.VOSK && !lang.isJapanese,
                partialLanguage = lang.code,
                overlay = { overlay },
            )

            status("音声認識の準備中…")
            engine = createEngine(settings, lang, pipe, ::requireClient)

            val created = (if (mp != null) AudioCapture.createPlaybackRecord(mp) else AudioCapture.createMicRecord())
                ?: throw IllegalStateException("音声の取得を開始できませんでした")
            record = created.first

            val cap = AudioCapture(
                record = created.first,
                sampleRate = created.second,
                engine = engine,
                onNoAudio = {
                    if (mp != null) {
                        main.post {
                            overlay?.showStatus(
                                "音声が届いていません。動画を再生しても字幕が出ない場合は、" +
                                    "そのアプリが再生音の取得を禁止している可能性があります。" +
                                    "設定で「マイク」に切り替えるか、ブラウザで再生してください。",
                                12_000,
                            )
                        }
                    }
                },
                onError = { msg -> main.post { fail(msg) } },
            )
            engine.start(cap.engineRate)

            synchronized(lock) {
                if (stopped) throw CancelledException()
                capture = cap
                pipeline = pipe
                translator = tr
                cap.start() // ここから先は AudioCapture が record と engine を解放する
            }
            main.post {
                overlay?.showStatus(
                    if (mp != null) "準備完了。動画を再生すると字幕が出ます" else "準備完了。マイクで音声を聞き取ります",
                    5000,
                )
            }
        } catch (e: Throwable) {
            record?.release()
            try {
                engine?.stop()
            } catch (_: Throwable) {
            }
            pipe?.close()
            tr?.close()
            val alreadyStopped = synchronized(lock) { stopped }
            if (e !is CancelledException && !alreadyStopped) {
                Log.e(TAG, "setup failed", e)
                val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                main.post { fail(msg) }
            }
        }
    }

    private fun createEngine(
        settings: AppSettings,
        lang: SourceLanguage,
        pipe: CaptionPipeline,
        requireClient: () -> OpenAiClient,
    ): SpeechEngine = when (settings.asrEngine) {
        AsrEngineType.VOSK -> {
            val model = lang.vosk
                ?: throw IllegalStateException("${lang.label}は端末内・軽量の音声認識に対応していません。設定で別の音声認識を選んでください")
            if (!VoskModels.isReady(this, model)) throw IllegalStateException(MODEL_MISSING)
            VoskEngine(VoskModels.dir(this, model), lang.code, pipe)
        }
        AsrEngineType.SHERPA -> {
            if (!lang.hasOnDevice) {
                throw IllegalStateException("${lang.label}は端末内・多言語の音声認識に対応していません。設定で別の音声認識を選んでください")
            }
            val threads = SherpaEngine.defaultThreads()
            val primary = SherpaModels.primaryFor(lang, settings.whisperSize)
            for (m in SherpaModels.requiredFor(lang, settings.whisperSize)) {
                if (!SherpaModels.isReady(this, m)) throw IllegalStateException(MODEL_MISSING)
            }
            val isWhisper = primary.kind == ModelKind.WHISPER
            // 自動判別のときは、ダウンロード済みの専用モデルも使う
            val specialists = if (lang.isAuto) {
                SpecialModel.entries.map { SherpaModels.special(it) }
                    .filter { SherpaModels.isReady(this, it) && it.language != null }
                    .associate { it.language!! to SherpaModels.buildConfig(SherpaModels.dir(this, it), it, "", threads) }
            } else {
                emptyMap()
            }
            SherpaEngine(
                vadModelPath = SherpaModels.file(this, SherpaModels.VAD, "silero_vad.onnx").absolutePath,
                primary = SherpaModels.buildConfig(SherpaModels.dir(this, primary), primary, lang.asrHint ?: "", threads),
                primaryIsWhisper = isWhisper,
                fixedLanguage = if (lang.isAuto) null else lang.code,
                specialists = specialists,
                listener = pipe,
            )
        }
        AsrEngineType.OPENAI -> OpenAiSpeechEngine(requireClient(), settings.openAiSttModel, lang.asrHint, pipe)
    }

    private fun status(message: String) {
        main.post { overlay?.showStatus(message, 0) }
    }

    private fun fail(message: String) {
        AppSettings(this).setLastError(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop),
            getString(R.string.action_stop),
            stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(stopAction)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private class CancelledException : RuntimeException()

    companion object {
        private const val TAG = "CaptionService"
        const val ACTION_START = "jp.autojimaku.action.START"
        const val ACTION_STOP = "jp.autojimaku.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_MISSING = "音声認識モデルがありません。アプリを開いてもう一度「字幕を開始」を押してください"

        /** 字幕を表示中かどうか (同じプロセス内の画面から参照する) */
        @Volatile
        var isRunning = false
            private set

        /** 状態が変わったときに呼ばれる (メインスレッド) */
        var stateListener: (() -> Unit)? = null

        private fun notifyState() {
            stateListener?.invoke()
        }

        fun start(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, CaptionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
            if (data != null) intent.putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptionService::class.java))
        }
    }
}
