package jp.autojimaku.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(message: String, httpCode: Int = -1) : CloudException(message, httpCode)

/** Google Gemini API の最小限のクライアント (外部ライブラリなし)。ネットワーク処理なのでバックグラウンドスレッドで呼ぶこと */
class GeminiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    @Volatile
    private var thinkingConfigSupported = true

    /** システム指示と1つの発話を渡して、返答のテキストを返す */
    fun generate(model: String, system: String, user: String): String {
        // Flash-Lite は最初から思考が最小。それ以外は字幕に使える速さにするため思考を「low」にする
        val lowThinking = thinkingConfigSupported && !model.contains("flash-lite")
        return try {
            generateOnce(model, system, user, lowThinking)
        } catch (e: GeminiException) {
            if (lowThinking && e.httpCode == 400 && e.message?.contains("think", ignoreCase = true) == true) {
                thinkingConfigSupported = false
                generateOnce(model, system, user, false)
            } else {
                throw e
            }
        }
    }

    private fun generateOnce(model: String, system: String, user: String, lowThinking: Boolean): String {
        val req = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
        if (lowThinking) {
            req.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        }
        val json = JSONObject(post("models/$model:generateContent", req.toString()))

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GeminiException("Gemini が返答しませんでした ${reason.ifEmpty { "" }}".trim(), 200)
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            // 思考の要約 (thought = true) は字幕に含めない
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun post(path: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL("$baseUrl/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiException(describeError(code, text), code)
            return text
        } catch (e: GeminiException) {
            throw e
        } catch (e: IOException) {
            throw GeminiException("Gemini に接続できませんでした: ${e.message}")
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
        return when {
            detail.contains("API key", ignoreCase = true) && (code == 400 || code == 401 || code == 403) ->
                "Gemini の APIキーが正しくありません"
            code == 429 -> "Gemini の利用上限に達しました（無料枠の回数制限など）"
            code == 404 -> "Gemini のモデル名が見つかりません。設定のモデル名を確認してください ($detail)"
            code >= 500 -> "Gemini が混み合っています (HTTP $code)"
            else -> "Gemini エラー HTTP $code $detail"
        }
    }
}
