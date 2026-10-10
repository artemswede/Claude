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
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"

    /** OpenRouter models the owner allows — only these two DeepSeek V4 Flash builds, never another one. */
    val OR_MODELS = listOf("deepseek/deepseek-v4-flash-0731" to "V4 Flash 0731", "deepseek/deepseek-v4-flash" to "V4 Flash 0423")

    /** An OpenRouter key («sk-or-…») goes to OpenRouter; any other — straight to DeepSeek. */
    fun openRouter(key: String) = key.startsWith("sk-or-")

    /**
     * «Свой сервер»: the owner's bortach-proxy (Vercel). The key passed around is then
     * «bp:» + its token, and the address is remembered here; the AI key itself lives on
     * the server, so the head unit needs no VPN and keeps no AI key.
     */
    private const val PROXY = "bp:"
    @Volatile private var proxyUrl = ""

    fun proxied(key: String) = key.startsWith(PROXY)

    fun proxy(ctx: Context): Pair<String, String> =
        Prefs.of(ctx).getString(Prefs.AI_PROXY_URL, null).orEmpty().trim() to Prefs.of(ctx).getString(Prefs.AI_PROXY_TOKEN, null).orEmpty().trim()

    fun setProxy(ctx: Context, url: String, token: String) =
        Prefs.of(ctx).edit().putString(Prefs.AI_PROXY_URL, url.trim()).putString(Prefs.AI_PROXY_TOKEN, token.trim()).apply()

    /** «my.vercel.app» → «https://my.vercel.app/api/chat». */
    fun endpoint(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.endsWith("/api/chat")) u = u.removeSuffix("/api") + "/api/chat"
        return u
    }

    fun orModel(ctx: Context): String = Prefs.of(ctx).getString(Prefs.AI_OR_MODEL, null)?.takeIf { m -> OR_MODELS.any { it.first == m } } ?: OR_MODELS[0].first
    fun setOrModel(ctx: Context, m: String) = Prefs.of(ctx).edit().putString(Prefs.AI_OR_MODEL, m).apply()

    /** Trips whose raw rows go with every question: 0, 1, 2 or all six. */
    val RAW_CHOICES = listOf(0, 1, 2, 6)
    fun rawTrips(ctx: Context): Int = Prefs.of(ctx).getInt(Prefs.AI_RAW, 2)
    fun setRawTrips(ctx: Context, n: Int) = Prefs.of(ctx).edit().putInt(Prefs.AI_RAW, n).apply()

    /** Context window for the memory threshold: V4 Flash and Gemini have 1M (capped in ChatMemory), DeepSeek's own API — 128K. */
    fun window(key: String): Int = if (openRouter(key) || proxied(key)) 1_000_000 else 128_000

    /** Answer modes: deep thinking (default, never switched off by itself) or fast. */
    val MODES = listOf("Думающий" to true, "Быстрый" to false)

    /** What the chat sends with: the own server when it is set up, otherwise the AI key. */
    fun key(ctx: Context): String {
        val (url, token) = proxy(ctx)
        if (url.isNotEmpty() && token.isNotEmpty()) {
            proxyUrl = endpoint(url)
            return PROXY + token
        }
        return Prefs.of(ctx).getString(Prefs.AI_KEY, null).orEmpty().trim()
    }
    fun setKey(ctx: Context, key: String) = Prefs.of(ctx).edit().putString(Prefs.AI_KEY, key.trim()).remove(Prefs.AI_MODEL_ID).apply()
    /** Thinking mode on — the default; Бортач never falls back to the fast mode by itself. */
    fun thinking(ctx: Context): Boolean = Prefs.of(ctx).getString(Prefs.AI_MODEL, "think") != "fast"
    fun setThinking(ctx: Context, on: Boolean) = Prefs.of(ctx).edit().putString(Prefs.AI_MODEL, if (on) "think" else "fast").apply()

    /** The model id this key can use, picked from the server's list and remembered. */
    fun modelId(ctx: Context, key: String): String {
        if (openRouter(key)) return orModel(ctx)
        if (proxied(key)) return "auto"
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

    /**
     * «Проверить ключ»: what the server says about the key (OpenRouter: limit and usage) and a
     * one-word test question to the chosen model. Blocking; returns a report for the owner.
     */
    fun check(ctx: Context): String {
        val key = key(ctx)
        if (key.isEmpty()) return "Ключ не задан."
        val out = StringBuilder()
        if (proxied(key)) {
            try {
                val c = URL(proxyUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer ${key.removePrefix(PROXY)}")
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val j = try { JSONObject(text) } catch (_: Exception) { null }
                if (code in 200..299 && j != null) {
                    out.append("Свой сервер на связи: ${j.optString("provider")}, модель ${j.optString("model")}" +
                        (j.optString("region").takeIf { it.isNotBlank() && it != "null" }?.let { ", регион $it" } ?: "") + ".\n")
                } else {
                    out.append("Свой сервер ответил $code: ${j?.optJSONObject("error")?.optString("message") ?: text.take(200)}")
                    return out.toString()
                }
            } catch (e: java.net.UnknownHostException) {
                return "Адрес сервера не найден: ${e.message}. Проверьте адрес в Настройках → ИИ-чат."
            } catch (e: Exception) {
                return "Свой сервер недоступен: ${e.message}. Если адрес *.vercel.app не открывается без VPN — привяжите к проекту свой домен."
            }
        }
        if (openRouter(key)) {
            try {
                val c = URL("https://openrouter.ai/api/v1/key").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer $key")
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val d = JSONObject(text).optJSONObject("data")
                    out.append("Ключ OpenRouter принят.")
                    d?.let {
                        val limit = if (it.isNull("limit")) "без лимита" else "лимит ${it.optDouble("limit")} $"
                        out.append(" Потрачено ${"%.4f".format(java.util.Locale.ROOT, it.optDouble("usage"))} $, $limit")
                        if (!it.isNull("limit_remaining")) out.append(", осталось ${it.optDouble("limit_remaining")} $")
                        if (it.optBoolean("is_free_tier")) out.append(", бесплатный уровень — нужно пополнить баланс")
                        out.append(".")
                    }
                } else out.append("Ключ OpenRouter не принят ($code): ${text.take(200)}")
            } catch (e: Exception) {
                out.append("Сервер OpenRouter недоступен: ${e.message}")
            }
            out.append("\n")
        }
        val model = modelId(ctx, key)
        try {
            val a = ask(key, model, "Отвечай одним словом.", listOf(ChatMessage("user", "Скажи «работает».")), thinking = false)
            out.append("Модель $model отвечает: «${a.take(40)}». Всё в порядке.")
        } catch (e: Exception) {
            out.append("Модель $model: ${e.message}")
        }
        return out.toString()
    }

    /** The model no longer exists (renamed on the server): pick again. */
    class ModelGone(msg: String) : IOException(msg)

    /** «sk-…ab12» — enough to recognise the key, not enough to use it. */
    fun keyText(ctx: Context): String = key(ctx).let {
        if (proxied(it)) "свой сервер · ${proxy(ctx).first.removePrefix("https://").substringBefore('/')}"
        else if (it.isEmpty()) "не задан" else "${if (openRouter(it)) "OpenRouter" else "DeepSeek"} · ${it.take(6)}…${it.takeLast(4)}"
    }

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
        if (model == "auto") {
            // Own server: it knows its provider and sets the reasoning fields itself.
            b.put("thinking", thinking)
        } else if (model.contains("/")) {
            // OpenRouter: its unified reasoning switch; only the chosen model, no fallback to others.
            b.put("reasoning", if (thinking) JSONObject().put("effort", "high") else JSONObject().put("enabled", false))
        } else if (extras) {
            b.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            if (thinking) b.put("reasoning_effort", "high")
        }
        return b
    }

    private fun post(key: String, body: JSONObject): String {
        try {
            val own = proxied(key)
            val c = URL(if (own) proxyUrl else if (openRouter(key)) OPENROUTER else "$BASE/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            // The own server waits for the model up to 5 minutes (Vercel maxDuration).
            c.readTimeout = if (own) 310_000 else 180_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (own) "application/octet-stream" else "application/json")
            c.setRequestProperty("Authorization", "Bearer ${key.removePrefix(PROXY)}")
            c.setRequestProperty("User-Agent", "Bortach/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})")
            if (openRouter(key)) {
                c.setRequestProperty("X-Title", "Bortach")
                c.setRequestProperty("HTTP-Referer", "https://github.com/artemswede/Claude")
            }
            val raw = body.toString().toByteArray(Charsets.UTF_8)
            if (own) {
                // Gzip: Russian text with numbers shrinks 5–8 times, so long contexts fit Vercel's 4.5 MB.
                c.setRequestProperty("X-Bortach-Gzip", "1")
                c.outputStream.use { o -> java.util.zip.GZIPOutputStream(o).use { it.write(raw) } }
            } else c.outputStream.use { it.write(raw) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = try { JSONObject(text).optJSONObject("error") } catch (_: Exception) { null }
                // OpenRouter puts the real reason into metadata (provider, raw provider answer, moderation reasons).
                val meta = err?.optJSONObject("metadata")
                val detail = listOfNotNull(
                    meta?.optString("provider_name")?.takeIf { it.isNotBlank() }?.let { "провайдер $it" },
                    meta?.optJSONArray("reasons")?.let { r -> List(r.length()) { r.optString(it) }.joinToString(", ").takeIf { it.isNotBlank() }?.let { "причины: $it" } },
                    meta?.opt("raw")?.toString()?.take(300)?.takeIf { it.isNotBlank() },
                ).joinToString("; ")
                val msg = err?.optString("message")?.let { m -> if (detail.isNotEmpty()) "$m ($detail)" else m } ?: text.take(300).ifBlank { null }
                if (msg != null && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(msg) && Regex("exist|not found|invalid|unknown", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                    if (openRouter(key)) throw IOException("Модель «${body.optString("model")}» сейчас недоступна на OpenRouter: $msg. Выберите другую в Настройках → ИИ-чат.")
                    throw ModelGone("Модель «${body.optString("model")}» недоступна: $msg")
                }
                if (code == 400 && body.has("thinking") && !own) throw BadRequest(msg ?: "400")
                if (own) throw IOException(when (code) {
                    401 -> "Токен своего сервера не подходит. Проверьте его в Настройках → ИИ-чат."
                    413 -> "Запрос слишком большой для сервера. Уменьшите «Сырые данные для ИИ» в Настройках."
                    429 -> "Лимит запросов у ИИ исчерпан — повторите через минуту. ${msg.orEmpty()}"
                    504 -> "Сервер не дождался ответа модели. Повторите или включите «Быстрый» режим."
                    else -> "Свой сервер ($code): ${msg ?: "без пояснения"}"
                })
                throw IOException(when (code) {
                    401 -> "Ключ не подходит. Проверьте его в Настройках."
                    403 -> "Доступ запрещён (403): ${msg ?: "без пояснения"}. На OpenRouter это обычно лимит, заданный на самом ключе, " +
                        "модель или провайдер, недоступные в вашем регионе, или модерация. Нажмите «Проверить ключ» в Настройках → ИИ-чат."
                    402 -> if (openRouter(key)) "На счёте OpenRouter закончились кредиты." else "На счёте DeepSeek закончились деньги."
                    429 -> "Слишком много запросов — повторите через минуту."
                    in 500..599 -> "Сервер сейчас не отвечает ($code). Повторите позже."
                    else -> "Сервер ответил $code${msg?.let { ": $it" } ?: ""}"
                })
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: java.net.UnknownHostException) {
            throw IOException(if (proxied(key)) "Магнитола не видит свой сервер: проверьте интернет и адрес в Настройках." else "Нет интернета: магнитола не видит сервер ИИ.")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException(if (proxied(key)) "Свой сервер долго не отвечает. Повторите вопрос." else "DeepSeek долго не отвечает. Повторите вопрос.")
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
