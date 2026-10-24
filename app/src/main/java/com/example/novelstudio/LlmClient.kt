package com.example.novelstudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 与桌面版一致的 API 配置（默认地址由构建变体决定：remote=局域网 / local=本机 127.0.0.1）。 */
data class ApiConfig(
    var baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    var apiKey: String = "",
    var model: String = "qwen3.8-27b",
    var temperature: String = "0.8",
    var maxTokens: String = "8192"
)

/**
 * OpenAI 兼容 /chat/completions 客户端。
 * stream=true 时按 SSE 逐块回调 onChunk(delta)；abort 置位后立即停止读取，
 * 已收到的部分作为返回值保留。服务端若忽略 stream 参数返回普通 JSON，则整体回调一次。
 */
object LlmClient {

    /** 一次 LLM 调用的可中止句柄。 */
    class AbortFlag {
        @Volatile
        var aborted: Boolean = false
        fun set() { aborted = true }
    }

    data class ChatMessage(val role: String, val content: String)

    suspend fun call(
        cfg: ApiConfig,
        messages: List<ChatMessage>,
        stream: Boolean = false,
        onChunk: ((String) -> Unit)? = null,
        abort: AbortFlag? = null
    ): String = withContext(Dispatchers.IO) {
        val url = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            put("temperature", cfg.temperature.toDoubleOrNull() ?: 0.8)
            put("max_tokens", cfg.maxTokens.toIntOrNull() ?: 8192)
            put("stream", stream)
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 3_600_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (cfg.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) { "" }
                throw IOException("HTTP $code $err".trim())
            }
            val contentType = conn.contentType ?: ""
            if (!stream || !contentType.contains("text/event-stream")) {
                // 非流式（或服务端忽略 stream 参数）：整体读取
                val text = conn.inputStream.bufferedReader().readText()
                val content = JSONObject(text)
                    .getJSONArray("choices").getJSONObject(0)
                    .optJSONObject("message")?.opt("content") as? String ?: ""
                if (onChunk != null && content.isNotEmpty()) onChunk(content)
                return@withContext content
            }
            // SSE 流式。注意：Android org.json 的 optString 在值为 JSON null 时会返回字面量
            // "null" 字符串（首包角色 delta 常为 content:null），必须用 opt+类型判断避免开头多出 null。
            val parts = StringBuilder()
            conn.inputStream.use { ins ->
                val reader = BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8))
                while (true) {
                    if (abort?.aborted == true) break
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    try {
                        val delta = JSONObject(payload)
                            .getJSONArray("choices").getJSONObject(0)
                            .optJSONObject("delta")
                        val c = delta?.opt("content") as? String
                        if (!c.isNullOrEmpty()) {
                            parts.append(c)
                            onChunk?.invoke(c)
                        }
                    } catch (e: Exception) {
                        // 跳过无法解析的行
                    }
                }
            }
            return@withContext parts.toString()
        } finally {
            conn.disconnect()
        }
    }
}
