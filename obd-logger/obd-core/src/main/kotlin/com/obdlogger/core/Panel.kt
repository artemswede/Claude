package com.obdlogger.core

/** How the record panel orders its tiles. */
enum class PanelSort(val ru: String) {
    /** Out of norm first (as on «Внимание»), then the most jittery. */
    PROBLEM("Проблемные первыми"),
    /** The most jittery first. */
    JUMPS("Скачки первыми"),
    /** The owner's order; sensors not placed yet follow in the usual order. */
    CUSTOM("Свой порядок"),
}

/**
 * Which sensors the record panel shows and in what order: every sensor this car
 * reports, minus the ones the owner switched off, 8 per page. Raw undecoded bytes
 * (PID 01 bit fields, Toyota mode 21 bytes) are off unless switched on.
 */
object Panel {
    const val PER_PAGE = 8

    /** Not sensors: time, text fields and driver marks. */
    private val NOT_SENSORS = setOf("time", "t_s", "marker", "fuel_system")

    fun isRaw(code: String) = code.startsWith("pid01_") || code.startsWith("m21_")

    /** Every numeric sensor of this recording, in the usual order (interesting ones first, then as recorded). */
    fun sensors(store: SeriesStore): List<String> =
        (Attention.INTERESTING + store.columns).distinct()
            .filter { it in store.columns && it !in NOT_SENSORS && store.values(it).isNotEmpty() }

    /**
     * Visible sensors in order. [hidden] — switched off by the owner; null = never set,
     * then raw bytes are off. [custom] — the owner's order for [PanelSort.CUSTOM].
     */
    fun order(store: SeriesStore, sort: PanelSort, custom: List<String>, hidden: Set<String>?): List<String> {
        val all = sensors(store)
        val visible = all.filter { if (hidden == null) !isRaw(it) else it !in hidden }
        return when (sort) {
            PanelSort.CUSTOM -> (custom.filter { it in visible } + visible).distinct()
            PanelSort.PROBLEM, PanelSort.JUMPS -> {
                val ranked = Attention.rank(store, sort = if (sort == PanelSort.JUMPS) AttentionSort.JUMPS else AttentionSort.DEVIATION, codes = visible)
                    .map { it.code }
                (ranked + visible).distinct()
            }
        }
    }

    fun pages(count: Int) = if (count == 0) 1 else (count + PER_PAGE - 1) / PER_PAGE
}
