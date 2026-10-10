package com.obdlogger.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Бортач is for any car: no make or model in the app's own texts and rules. Manufacturer
 * protocols (mode 21 blocks) and the manufacturer DTC catalogue are the only exceptions.
 */
class UniversalTest {
    private fun root(): File? {
        System.getProperty("repo.root")?.let { return File(it) }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "obd-core/src/main").isDirectory) d = d.parentFile
        return d
    }

    @Test
    fun noCarModelsInSources() {
        val r = root() ?: return
        val models = Regex("(?i)avensis|corolla|camry|rav4|auris|\\bD-4D?\\b|1AZ|VVT-i|плавающ")
        val bad = listOf("obd-core/src/main", "app/src/main").flatMap { dir ->
            File(r, dir).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") && it.name != "DtcCatalog.kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, l -> if (models.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim().take(120)}" else null }
            }.toList()
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun engineTypeFromInfoOrSensors() {
        assertTrue(Engine.of("${Engine.FUEL_LINE} Дизель", emptyList()) == Engine.DIESEL)
        assertTrue(Engine.of(null, listOf("rpm", "o2_b1s1_v", "ltft_b1_pct")) == Engine.PETROL)
        assertTrue(Engine.of(null, listOf("rpm", "map_kpa", "coolant_c")) == Engine.DIESEL)
        assertTrue(Engine.of(null, emptyList()) == Engine.UNKNOWN)
    }

    @Test
    fun dipsAreRelativeToThisCarsIdle() {
        assertTrue(TripAnalyzer.dipBelow(700.0) == 595.0)
        assertTrue(TripAnalyzer.dipBelow(900.0) == 765.0)
        assertTrue(TripAnalyzer.dipBelow(null) == 560.0)
    }
}
