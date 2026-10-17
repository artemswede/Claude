package com.obdlogger.app

import android.content.Context
import com.obdlogger.core.Evidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Мнение ИИ» on the overview: after a saved trip the AI (Flash, never the Pro quota) fills the
 * same slots as Бортач's own version — headline, urgency, confidence, why, next step, the
 * sensor to chart. The layout stays the app's; the AI writes the meaning. One try per trip,
 * cached in a file; without a key or on an error the scripted version stays.
 */
object AiOpinion {
    class Opinion(
        /** The trip it is about (CSV base name). */
        val trip: String,
        val headline: String,
        val urgency: String,
        val confidence: String,
        val why: List<Evidence>,
        val next: String,
        val chart: String,
        val model: String,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "ai_opinion.json")

    @Volatile private var running: String? = null

    fun load(ctx: Context): Opinion? = try {
        val j = JSONObject(file(ctx).readText())
        val w = j.optJSONArray("why") ?: JSONArray()
        Opinion(j.getString("trip"), j.optString("headline"), j.optString("urgency"), j.optString("confidence"),
            List(w.length()) { i -> w.getJSONObject(i).let { Evidence(it.optString("label"), it.optString("value"), it.optBoolean("deviating")) } },
            j.optString("next"), j.optString("chart"), j.optString("model")).takeIf { it.headline.isNotBlank() }
    } catch (_: Exception) { null }

    /** The JSON answer (own server: by schema; others: the first {...} in the text). */
    fun parse(trip: String, raw: String, model: String): Opinion? = try {
        val t = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
        val j = JSONObject(t)
        val w = j.optJSONArray("why") ?: JSONArray()
        val conf = j.optString("confidence").lowercase().let { c -> listOf("высокая", "средняя", "низкая").firstOrNull { it in c } ?: "средняя" }
        Opinion(trip, j.optString("headline").trim(), j.optString("urgency").trim(), conf,
            List(w.length()) { i -> w.optJSONObject(i)?.let { Evidence(it.optString("label"), it.optString("value"), it.optBoolean("deviating")) } ?: Evidence(w.optString(i), "") }
                .filter { it.label.isNotBlank() }.take(4),
            j.optString("next").trim(), j.optString("chart").trim().substringBefore(' '), model).takeIf { it.headline.isNotBlank() }
    } catch (_: Exception) { null }

    private fun save(ctx: Context, o: Opinion) {
        val w = JSONArray()
        o.why.forEach { w.put(JSONObject().put("label", it.label).put("value", it.value).put("deviating", it.deviating)) }
        SessionFiles.writeSynced(file(ctx), JSONObject().put("trip", o.trip).put("headline", o.headline).put("urgency", o.urgency)
            .put("confidence", o.confidence).put("why", w).put("next", o.next).put("chart", o.chart).put("model", o.model).toString())
    }

    private const val ASK = """Дай мнение о машине для главного экрана Бортача — по последней поездке на фоне прежних, профиля «как обычно» и медкарты.
Верни только JSON: {"headline": "главный вывод до 70 знаков, без воды", "urgency": "насколько срочно и что будет, если ничего не делать — одна фраза", "confidence": "высокая|средняя|низкая", "why": [{"label": "датчик и режим", "value": "число с единицей и сравнение с обычным", "deviating": true}], "next": "одно следующее действие", "chart": "код датчика для графика из СТАТИСТИКИ ДАТЧИКОВ"}
2–3 пункта why. Если всё в порядке — так и скажи в headline («Всё как обычно: …»), why — подтверждающие числа."""

    /** In the background after a trip was saved; [force] — «Пересчитать». [done] runs on any thread. */
    fun refresh(ctx: Context, csv: File, force: Boolean = false, excluded: Set<String> = emptySet(), done: (Opinion?) -> Unit = {}) {
        val key = AiChat.key(ctx)
        val trip = csv.nameWithoutExtension
        if (key.isEmpty() || running == trip) return done(null)
        if (!force && load(ctx)?.trip == trip) return done(load(ctx))
        running = trip
        val appCtx = ctx.applicationContext
        Thread {
            val o = try {
                val m = com.obdlogger.app.ui.TripsModel.build(appCtx, Prefs.currentCar(appCtx))
                val details = m.trips.take(3).mapNotNull { com.obdlogger.app.ui.TripCache.detail(it) }
                val base = com.obdlogger.core.Baseline.render(CarProfile.of(appCtx, m).profiles)
                val system = com.obdlogger.core.ChatPrompt.system(Prefs.vehicle(appCtx).ifBlank { "не названа" }, m.trips.map { it.summary },
                    m.checks.mapNotNull { it.check }, "", null, details, "", com.obdlogger.core.ChatPrompt.research(details, 0), base) +
                    "\n\n" + MaintenanceStore.load(appCtx).render()
                val prompt = buildString {
                    append(ASK)
                    if (excluded.isNotEmpty()) append("\nНе повторяй уже рассмотренные темы: ${excluded.joinToString(", ")}. Ищи другие проблемы.")
                    append("\nПоследняя поездка: " + TripIndex.title(trip))
                }
                val raw = AiChat.ask(key, AiChat.modelId(appCtx, key), system,
                    listOf(com.obdlogger.core.ChatMessage("user", prompt)),
                    thinking = false, tier = "flash", format = "opinion")
                parse(trip, raw, AiChat.lastModel)?.also { save(appCtx, it) }
            } catch (_: Exception) { null } finally { running = null }
            done(o)
        }.start()
    }
}
