package com.mira.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

data class ModelTurn(
    val text: String,
    val callName: String?,
    val callArgs: JSONObject?,
    val finishReason: String
)

/** Google AI Studio (Gemini) REST client. No SDK, only HttpURLConnection + org.json. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun textPart(t: String): JSONObject = JSONObject().put("text", t)

    fun imagePart(b64: String): JSONObject =
        JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", "image/jpeg").put("data", b64)
        )

    fun buildRequest(
        system: String,
        parts: List<JSONObject>,
        tools: JSONArray?,
        thinkingBudget: Int?,
        model: String
    ): JSONObject {
        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(system))))
        val partsArr = JSONArray()
        for (p in parts) partsArr.put(p)
        body.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partsArr)))
        if (tools != null) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            body.put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "ANY"))
            )
        }
        val gen = JSONObject().put("temperature", 0.4)
        if (thinkingBudget != null && model.contains("2.5")) {
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", thinkingBudget))
        }
        body.put("generationConfig", gen)
        return body
    }

    /** Calls the API with automatic back-off on 429/5xx/network errors. */
    suspend fun generate(
        apiKey: String,
        model: String,
        body: JSONObject,
        onWait: (String) -> Unit
    ): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return call(apiKey, model, body)
            } catch (e: ApiException) {
                val retryable = e.code == 429 || e.code == 500 || e.code == 503 ||
                    e.code == 504 || e.code == -1
                val maxAttempts = if (e.code == -1) 3 else 5
                if (!retryable || attempt >= maxAttempts) throw e
                val waitS = minOf(70L, 6L * (1L shl attempt))
                onWait("API مصروف یا حد پوری (${e.code}) — ${waitS} سیکنڈ بعد دوبارہ")
                delay(waitS * 1000L)
                attempt++
            }
        }
    }

    private suspend fun call(apiKey: String, model: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").optString("message", text)
                    } catch (ignored: Exception) {
                        text
                    }
                    throw ApiException(code, msg.take(300))
                }
                JSONObject(text)
            } catch (e: IOException) {
                throw ApiException(-1, "نیٹ ورک مسئلہ: " + (e.message ?: ""))
            } finally {
                conn.disconnect()
            }
        }

    fun parse(resp: JSONObject): ModelTurn {
        val cands = resp.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val block = resp.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            return ModelTurn("", null, null, if (block.isNotEmpty()) "BLOCKED:$block" else "NO_CANDIDATE")
        }
        val c = cands.getJSONObject(0)
        val finish = c.optString("finishReason", "")
        val parts = c.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        var name: String? = null
        var args: JSONObject? = null
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.getString("text"))
                val fc = p.optJSONObject("functionCall")
                if (fc != null && name == null) {
                    name = fc.getString("name")
                    args = fc.optJSONObject("args") ?: JSONObject()
                }
            }
        }
        return ModelTurn(sb.toString().trim(), name, args, finish)
    }
}
