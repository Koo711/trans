package jp.autojimaku.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google翻訳 (Google Cloud Translation API Basic v2) の最小限のクライアント。
 * ネットワーク処理なのでバックグラウンドスレッドで呼ぶこと。
 */
class GoogleTranslateClient(
    private val apiKey: String,
    private val baseUrl: String = "https://translation.googleapis.com/language/translate/v2",
) {
    /** @param source 元の言語 (分からなければ null で自動判別) */
    fun translate(text: String, source: String?, target: String = "ja"): String {
        val req = JSONObject()
            .put("q", JSONArray().put(text))
            .put("target", target)
            .put("format", "text")
        if (!source.isNullOrBlank()) req.put("source", source)

        val bytes = req.toString().toByteArray(Charsets.UTF_8)
        val conn = URL("$baseUrl?key=" + URLEncoder.encode(apiKey, "UTF-8")).openConnection() as HttpURLConnection
        val body: String
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw describeError(code, body)
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            throw CloudException("Google翻訳に接続できませんでした: ${e.message}")
        } finally {
            conn.disconnect()
        }

        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("translations")
        if (list == null || list.length() == 0) throw CloudException("Google翻訳が返答しませんでした", 200)
        return list.getJSONObject(0).optString("translatedText", "").trim()
    }

    private fun describeError(code: Int, body: String): CloudException {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (_: Exception) {
            null
        }
        val message = error?.optString("message", "").orEmpty()
        val reasons = buildString {
            val errors = error?.optJSONArray("errors")
            for (i in 0 until (errors?.length() ?: 0)) append(errors!!.getJSONObject(i).optString("reason")).append(' ')
            append(error?.optString("status", "") ?: "")
        }
        val all = "$message $reasons"
        val detail = message.take(160)
        return when {
            all.contains("API key not valid", true) || all.contains("API_KEY_INVALID", true) ->
                CloudException("Google翻訳の APIキーが正しくありません", code)
            // 1日・1分あたりの文字数の上限 (403 で返ることもある)
            code == 429 || all.contains("RateLimitExceeded", true) || all.contains("dailyLimitExceeded", true) ||
                all.contains("quota", true) || all.contains("RESOURCE_EXHAUSTED", true) ->
                CloudException("Google翻訳の利用上限に達しました", code, rateLimited = true)
            code == 403 ->
                CloudException(
                    "Google Cloud で「Cloud Translation API」を有効にし、請求先アカウントを設定してください（$detail）",
                    code,
                )
            code >= 500 -> CloudException("Google翻訳が混み合っています (HTTP $code)", code)
            else -> CloudException("Google翻訳エラー HTTP $code $detail", code)
        }
    }
}
