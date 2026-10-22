package jp.autojimaku

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import jp.autojimaku.audio.Resampler16k
import jp.autojimaku.speech.SpeechEngine
import kotlin.math.abs
import kotlin.math.max

/**
 * 音声を読み続けて音声認識エンジンに渡す。
 * 専用スレッドで動き、終了時に AudioRecord の解放と engine.stop() まで行う。
 */
class AudioCapture(
    private val record: AudioRecord,
    private val sampleRate: Int,
    private val engine: SpeechEngine,
    /** しばらく無音しか届かないとき (再生音の取得が禁止されている可能性) */
    private val onNoAudio: () -> Unit,
    private val onError: (String) -> Unit,
) {
    /** 音声認識エンジンには常に 16kHz で渡す */
    val engineRate: Int = Resampler16k.OUT_RATE
    private val resampler = Resampler16k(sampleRate)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "AudioCapture").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            thread?.join(1500)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("音声の取得を開始できませんでした。他のアプリが録音中の可能性があります。")
                return
            }
            val buf = ShortArray(sampleRate / 10) // 100ms ずつ
            val out = ShortArray(resampler.maxOutput(buf.size))
            val startedAt = SystemClock.elapsedRealtime()
            var heard = false
            var warned = false
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    if (running) onError("音声の取得が中断されました (code $n)")
                    break
                }
                if (n == 0) continue
                if (!heard) {
                    for (i in 0 until n) {
                        if (abs(buf[i].toInt()) > 32) {
                            heard = true
                            break
                        }
                    }
                    if (!heard && !warned && SystemClock.elapsedRealtime() - startedAt > NO_AUDIO_WARN_MS) {
                        warned = true
                        onNoAudio()
                    }
                }
                val count = resampler.process(buf, n, out)
                try {
                    engine.feed(out, count)
                } catch (e: Exception) {
                    Log.w(TAG, "engine.feed failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            if (running) onError("音声の取得でエラー: ${e.message}")
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            try {
                engine.stop()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val NO_AUDIO_WARN_MS = 30_000L
        private val SAMPLE_RATES = intArrayOf(16000, 48000, 44100)

        /** 他のアプリが再生している音声 (YouTube など) を取得する AudioRecord を作る */
        @SuppressLint("MissingPermission") // 呼び出し前に RECORD_AUDIO を確認済み
        fun createPlaybackRecord(projection: MediaProjection): Pair<AudioRecord, Int>? {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            for (rate in SAMPLE_RATES) {
                try {
                    val record = AudioRecord.Builder()
                        .setAudioFormat(format(rate))
                        .setBufferSizeInBytes(bufferSize(rate))
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "playback capture at $rate Hz failed", e)
                }
            }
            return null
        }

        /** マイクから取得する AudioRecord を作る */
        @SuppressLint("MissingPermission")
        fun createMicRecord(): Pair<AudioRecord, Int>? {
            for (rate in SAMPLE_RATES) {
                try {
                    val record = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format(rate))
                        .setBufferSizeInBytes(bufferSize(rate))
                        .build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "mic capture at $rate Hz failed", e)
                }
            }
            return null
        }

        private fun format(rate: Int): AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        private fun bufferSize(rate: Int): Int {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return max(min, rate * 2) // 1 秒分以上
        }
    }
}
