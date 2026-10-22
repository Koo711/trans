package jp.autojimaku.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenAiException(message: String, val httpCode: Int = -1) : IOException(message)

/** OpenAI API の最小限のクライアント (外部ライブラリなし)。ネットワーク処理なのでバックグラウンドスレッドで呼ぶこと */
class OpenAiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
) {

    @Volatile
    private var reasoningParamSupported = true

    /** 音声 (WAV) を文字起こしする */
    fun transcribe(wav: ByteArray, model: String, language: String?, prompt: String?): String {
        val boundary = "----AutoJimaku" + System.nanoTime()
        val body = ByteArrayOutputStream(wav.size + 1024)

        fun write(s: String) = body.write(s.toByteArray(Charsets.UTF_8))
        fun field(name: String, value: String) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write(value)
            write("\r\n")
        }

        field("model", model)
        field("response_format", "json")
        if (!language.isNullOrBlank()) field("language", language)
        if (!prompt.isNullOrBlank()) field("prompt", prompt)
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        write("Content-Type: audio/wav\r\n\r\n")
        body.write(wav)
        write("\r\n--$boundary--\r\n")

        val json = post("audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray())
        return JSONObject(json).optString("text", "").trim()
    }

    /** チャットで1回だけ問い合わせて返答テキストを返す */
    fun chat(model: String, system: String, user: String): String {
        val useReasoning = reasoningParamSupported && acceptsMinimalReasoning(model)
        return try {
            chatOnce(model, system, user, useReasoning)
        } catch (e: OpenAiException) {
            if (useReasoning && e.httpCode == 400 && e.message?.contains("reasoning", ignoreCase = true) == true) {
                reasoningParamSupported = false
                chatOnce(model, system, user, false)
            } else {
                throw e
            }
        }
    }

    private fun chatOnce(model: String, system: String, user: String, minimalReasoning: Boolean): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val req = JSONObject()
            .put("model", model)
            .put("messages", messages)
        // GPT-5 系は推論を最小にすると字幕に使える速さになる
        if (minimalReasoning) req.put("reasoning_effort", "minimal")

        val json = post("chat/completions", "application/json; charset=utf-8", req.toString().toByteArray(Charsets.UTF_8))
        val choices = JSONObject(json).getJSONArray("choices")
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim()
    }

    private fun acceptsMinimalReasoning(model: String) = model == "gpt-5" || model.startsWith("gpt-5-")

    private fun post(path: String, contentType: String, body: ByteArray): String {
        val conn = URL("$baseUrl/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw OpenAiException(describeError(code, text), code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).getJSONObject("error").optString("message", "")
        } catch (_: Exception) {
            ""
        }.take(200)
        return when (code) {
            401 -> "OpenAI APIキーが正しくありません (401)"
            429 -> "OpenAI の利用上限または残高不足です (429) $detail"
            else -> "OpenAI エラー HTTP $code $detail"
        }
    }
}

object Wav {
    /** 16bit モノラル PCM を WAV ファイルの中身にする */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1.toShort()) // PCM
        buf.putShort(1.toShort()) // モノラル
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2.toShort())
        buf.putShort(16.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    /** 48kHz などを 16kHz に間引いて送信量を減らす (16000 の整数倍のときだけ) */
    fun downsampleTo16k(samples: ShortArray, sampleRate: Int): Pair<ShortArray, Int> {
        if (sampleRate <= 16000 || sampleRate % 16000 != 0) return samples to sampleRate
        val factor = sampleRate / 16000
        val out = ShortArray(samples.size / factor)
        for (i in out.indices) {
            var sum = 0
            val base = i * factor
            for (j in 0 until factor) sum += samples[base + j]
            out[i] = (sum / factor).toShort()
        }
        return out to 16000
    }
}
