package com.obdlogger.core

/**
 * Offline VIN decoding: the maker from the WMI (first 3 or 2 characters) and the model year
 * from the 10th character. Enough to name a car roughly («Toyota, 2005»); the model and the
 * engine are left to the AI or the owner.
 */
object Vin {
    private val ALLOWED = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    fun clean(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /** 17 characters, no I, O, Q. The check digit is not required: European VINs often have none. */
    fun valid(s: String): Boolean = ALLOWED.matches(clean(s))

    private val WMI3 = mapOf(
        "SB1" to "Toyota", "NMT" to "Toyota", "VNK" to "Toyota", "TMB" to "Škoda", "TMA" to "Hyundai", "XW8" to "Volkswagen",
        "XTA" to "Lada", "X7L" to "Renault", "X9F" to "Ford", "Z94" to "Hyundai", "XWE" to "Kia", "Z8N" to "Nissan",
        "XUF" to "Chevrolet", "X4X" to "BMW", "XW7" to "Toyota", "Z6F" to "Ford", "X7M" to "Hyundai", "XTT" to "УАЗ",
        "X96" to "ГАЗ", "XWB" to "Daewoo", "Y6D" to "Daewoo", "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën",
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "WAU" to "Audi", "WBA" to "BMW", "WBS" to "BMW",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "W0L" to "Opel", "WF0" to "Ford",
        "WME" to "smart", "WP0" to "Porsche", "VSS" to "SEAT", "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "YV1" to "Volvo",
        "YS3" to "Saab", "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCC" to "Lotus", "SHH" to "Honda", "SHS" to "Honda",
        "TRU" to "Audi", "VWV" to "Volkswagen", "UU1" to "Dacia", "U5Y" to "Kia", "MAL" to "Hyundai", "MA3" to "Suzuki",
        "KMH" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "KL1" to "Chevrolet", "KLA" to "Daewoo", "JTD" to "Toyota",
        "JTE" to "Toyota", "JTN" to "Toyota", "JT1" to "Toyota", "JT2" to "Toyota", "JTH" to "Lexus", "JN1" to "Nissan",
        "JN8" to "Nissan", "JHM" to "Honda", "JMZ" to "Mazda", "JM1" to "Mazda", "JS2" to "Suzuki", "JF1" to "Subaru",
        "JMB" to "Mitsubishi", "JA3" to "Mitsubishi", "LVS" to "Ford", "LSV" to "Volkswagen", "LGW" to "Great Wall",
        "LB3" to "Geely", "LVV" to "Chery", "1HG" to "Honda", "1FA" to "Ford", "1G1" to "Chevrolet", "2T1" to "Toyota",
        "4T1" to "Toyota", "5YJ" to "Tesla",
    )
    private val WMI2 = mapOf(
        "JT" to "Toyota", "SB" to "Toyota", "JN" to "Nissan", "JH" to "Honda", "JM" to "Mazda", "JS" to "Suzuki",
        "JF" to "Subaru", "KM" to "Hyundai", "KN" to "Kia", "WV" to "Volkswagen", "WB" to "BMW", "WD" to "Mercedes-Benz",
        "VF" to "Renault / PSA", "ZF" to "Fiat", "YV" to "Volvo", "XT" to "Lada",
    )

    fun maker(vin: String): String? {
        val v = clean(vin)
        if (v.length < 3) return null
        return WMI3[v.take(3)] ?: WMI2[v.take(2)]
    }

    private const val YEARS = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /**
     * Model year from the 10th character. The letters repeat every 30 years (A = 1980 or 2010):
     * the later one is taken unless it is in the future. Null when the character is not a year code.
     */
    fun year(vin: String, now: Int = java.time.Year.now().value): Int? {
        val v = clean(vin)
        if (v.length != 17) return null
        val i = YEARS.indexOf(v[9])
        if (i < 0) return null
        val late = 2010 + i
        return if (late <= now + 1) late else 1980 + i
    }

    /** «Toyota, 2005 (по VIN)» — or null if nothing is known. */
    fun describe(vin: String, now: Int = java.time.Year.now().value): String? {
        if (!valid(vin)) return null
        val m = maker(vin)
        val y = year(vin, now)
        if (m == null && y == null) return null
        return listOfNotNull(m, y?.toString()).joinToString(", ") + " (по VIN)"
    }
}
