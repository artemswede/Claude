package com.obdlogger.app

import android.content.Context
import com.obdlogger.core.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI chat: questions about this car's trips go to DeepSeek with a compact summary
 * of the data ([com.obdlogger.core.ChatPrompt]); the key is the owner's and stays in
 * the app's settings. History is kept on the device (chat.json).
 */
object AiChat {
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    val MODELS = listOf("deepseek-chat" to "Быстрый", "deepseek-reasoner" to "Думающий")

    fun key(ctx: Context): String = Prefs.of(ctx).getString(Prefs.AI_KEY, null).orEmpty().trim()
    fun setKey(ctx: Context, key: String) = Prefs.of(ctx).edit().putString(Prefs.AI_KEY, key.trim()).apply()
    fun model(ctx: Context): String = Prefs.of(ctx).getString(Prefs.AI_MODEL, null) ?: MODELS[0].first
    fun setModel(ctx: Context, m: String) = Prefs.of(ctx).edit().putString(Prefs.AI_MODEL, m).apply()

    /** «sk-…ab12» — enough to recognise the key, not enough to use it. */
    fun keyText(ctx: Context): String = key(ctx).let { if (it.isEmpty()) "не задан" else "${it.take(3)}…${it.takeLast(4)}" }

    /** Asks the model; throws [IOException] with a message for the owner. Blocking: call off the main thread. */
    fun ask(key: String, model: String, system: String, history: List<ChatMessage>): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        try {
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 180_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IOException(when (code) {
                    401 -> "Ключ DeepSeek не подходит. Проверьте его в Настройках."
                    402 -> "На счёте DeepSeek закончились деньги."
                    429 -> "Слишком много запросов — повторите через минуту."
                    in 500..599 -> "Сервер DeepSeek сейчас не отвечает ($code). Повторите позже."
                    else -> "DeepSeek ответил $code${msg?.let { ": $it" } ?: ""}"
                })
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: java.net.UnknownHostException) {
            throw IOException("Нет интернета: магнитола не видит api.deepseek.com.")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("DeepSeek долго не отвечает. Повторите вопрос.")
        } catch (e: javax.net.ssl.SSLException) {
            throw IOException("Не удалось установить защищённое соединение (часто — неверная дата на магнитоле). ${e.message.orEmpty()}")
        } catch (e: org.json.JSONException) {
            throw IOException("Непонятный ответ сервера.")
        }
    }

    /** Markdown the model likes (**bold**, # headers) as plain readable text. */
    fun plain(s: String): String = s.lines().joinToString("\n") { line ->
        line.replace("**", "").replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ")
    }.trim()

    private fun file(ctx: Context) = File(ctx.filesDir, "chat.json")

    fun load(ctx: Context): MutableList<ChatMessage> = try {
        val a = JSONArray(file(ctx).readText())
        MutableList(a.length()) { i -> a.getJSONObject(i).let { ChatMessage(it.getString("role"), it.getString("text")) } }
    } catch (_: Exception) {
        mutableListOf()
    }

    fun save(ctx: Context, all: List<ChatMessage>) {
        val a = JSONArray()
        all.takeLast(200).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
        try { file(ctx).writeText(a.toString()) } catch (_: Exception) {}
    }
}
