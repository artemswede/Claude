package com.obdlogger.app

import android.content.Context
import com.obdlogger.core.ChatMessage
import com.obdlogger.core.ChatState
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
    private const val BASE = "https://api.deepseek.com"
    private const val ENDPOINT = "$BASE/chat/completions"

    /** Answer modes: deep thinking (default, never switched off by itself) or fast. */
    val MODES = listOf("Думающий" to true, "Быстрый" to false)

    fun key(ctx: Context): String = Prefs.of(ctx).getString(Prefs.AI_KEY, null).orEmpty().trim()
    fun setKey(ctx: Context, key: String) = Prefs.of(ctx).edit().putString(Prefs.AI_KEY, key.trim()).remove(Prefs.AI_MODEL_ID).apply()
    /** Thinking mode on — the default; Бортач never falls back to the fast mode by itself. */
    fun thinking(ctx: Context): Boolean = Prefs.of(ctx).getString(Prefs.AI_MODEL, "think") != "fast"
    fun setThinking(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putString(Prefs.AI_MODEL, if (on) "think" else "fast").apply()

    /** The model id this key can use, picked from the server's list and remembered. */
    fun modelId(ctx: Context, key: String): String {
        Prefs.of(ctx).getString(Prefs.AI_MODEL_ID, null)?.let { return it }
        val ids = try { models(key) } catch (_: Exception) { emptyList() }
        return pick(ids).also { Prefs.of(ctx).edit().putString(Prefs.AI_MODEL_ID, it).apply() }
    }

    fun forgetModel(ctx: Context) = Prefs.of(ctx).edit().remove(Prefs.AI_MODEL_ID).apply()

    /** The strongest model in the list: V4 Pro, then other Pro, reasoner, Flash, chat. */
    fun pick(ids: List<String>): String {
        val order = listOf("v4-pro", "pro", "reasoner", "v4", "flash", "chat")
        for (k in order) ids.firstOrNull { it.contains(k) }?.let { return it }
        return ids.firstOrNull() ?: "deepseek-chat"
    }

    /** GET /models: the ids this key may use. */
    fun models(key: String): List<String> {
        val c = URL("$BASE/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $key")
        if (c.responseCode !in 200..299) return emptyList()
        val a = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("data") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
    }

    /** The model no longer exists (renamed on the server): pick again. */
    class ModelGone(msg: String) : IOException(msg)

    /** «sk-…ab12» — enough to recognise the key, not enough to use it. */
    fun keyText(ctx: Context): String = key(ctx).let { if (it.isEmpty()) "не задан" else "${it.take(3)}…${it.takeLast(4)}" }

    /**
     * Asks the model; throws [IOException] with a message for the owner. Blocking: call off
     * the main thread. With [thinking] it asks for deep reasoning (thinking on, effort high);
     * a server that does not know those fields gets the plain request instead.
     */
    fun ask(key: String, model: String, system: String, history: List<ChatMessage>, thinking: Boolean): String = try {
        post(key, body(model, system, history, thinking, extras = true))
    } catch (e: BadRequest) {
        // An older model or API that rejects «thinking» / «reasoning_effort»: the same question without them.
        post(key, body(model, system, history, thinking, extras = false))
    }

    private class BadRequest(msg: String) : IOException(msg)

    private fun body(model: String, system: String, history: List<ChatMessage>, thinking: Boolean, extras: Boolean): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val b = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        if (extras) {
            b.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            if (thinking) b.put("reasoning_effort", "high")
        }
        return b
    }

    private fun post(key: String, body: JSONObject): String {
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
                if (msg != null && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(msg) && Regex("exist|not found|invalid|unknown", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                    throw ModelGone("Модель «${body.optString("model")}» недоступна: $msg")
                }
                if (code == 400 && body.has("thinking")) throw BadRequest(msg ?: "400")
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

    /** The one chat: messages and the summary of what was folded out of them. */
    fun load(ctx: Context): ChatState = try {
        val text = file(ctx).readText()
        fun msgs(a: JSONArray) = MutableList(a.length()) { i -> a.getJSONObject(i).let { ChatMessage(it.getString("role"), it.getString("text")) } }
        if (text.trimStart().startsWith("[")) ChatState(msgs(JSONArray(text)))
        else JSONObject(text).let { ChatState(msgs(it.optJSONArray("messages") ?: JSONArray()), it.optString("summary")) }
    } catch (_: Exception) {
        ChatState()
    }

    fun save(ctx: Context, state: ChatState) {
        val a = JSONArray()
        state.messages.forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
        try { file(ctx).writeText(JSONObject().put("summary", state.summary).put("messages", a).toString()) } catch (_: Exception) {}
    }
}
