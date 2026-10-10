package com.obdlogger.app

import android.content.Context
import com.obdlogger.core.WatchRule

/**
 * Conditions the owner asked Бортач to watch (from the assistant's «[наблюдать: …]»),
 * and the reports waiting to be posted into the chat. Kept in preferences: the service
 * counts, the chat posts.
 */
object WatchStore {
    private const val RULES = "watch_rules"
    private const val REPORTS = "watch_reports"
    private const val SEP = "\u001E"

    fun rules(ctx: Context): List<WatchRule> =
        Prefs.of(ctx).getString(RULES, null)?.lines()?.mapNotNull { WatchRule.decode(it) }.orEmpty()

    @Synchronized
    fun setRules(ctx: Context, list: List<WatchRule>) =
        Prefs.of(ctx).edit().putString(RULES, list.joinToString("\n") { it.encode() }).apply()

    @Synchronized
    fun add(ctx: Context, r: WatchRule) {
        val list = rules(ctx).filter { !(it.code == r.code && it.op == r.op && it.mode == r.mode) } + r
        setRules(ctx, list.takeLast(5))
    }

    @Synchronized
    fun remove(ctx: Context, r: WatchRule) = setRules(ctx, rules(ctx).filter { it.encode() != r.encode() })

    /** After a trip: one fewer trip left for each rule; spent rules are dropped. */
    @Synchronized
    fun tripDone(ctx: Context) = setRules(ctx, rules(ctx).map { it.copy(tripsLeft = it.tripsLeft - 1) }.filter { it.tripsLeft > 0 })

    @Synchronized
    fun postReport(ctx: Context, text: String) {
        val now = Prefs.of(ctx).getString(REPORTS, null)?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
        Prefs.of(ctx).edit().putString(REPORTS, (now + text).takeLast(10).joinToString(SEP)).apply()
    }

    /** Reports not yet in the chat; taking them clears the queue. */
    @Synchronized
    fun takeReports(ctx: Context): List<String> {
        val list = Prefs.of(ctx).getString(REPORTS, null)?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
        if (list.isNotEmpty()) Prefs.of(ctx).edit().remove(REPORTS).apply()
        return list
    }
}
