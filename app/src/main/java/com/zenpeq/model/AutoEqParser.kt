package com.zenpeq.model

import kotlin.math.pow
import kotlin.math.sqrt

data class ParseResult(
    val preampDb: Float?,
    val filters: List<EqFilter>,
    val warnings: List<String>,
)

/**
 * Parses EqualizerAPO / AutoEQ / squig.link parametric text:
 *   Preamp: -3.0 dB
 *   Filter 1: ON PK Fc 4500 Hz Gain -3.5 dB Q 2.5
 */
object AutoEqParser {
    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val preampRe = Regex("""^\s*Preamp\s*:\s*([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val filterRe = Regex("""^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+(.*)$""", ic)
    private val fcRe = Regex("""\bFc\s+([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val gainRe = Regex("""\bGain\s+([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val qRe = Regex("""\bQ\s+(\d+(?:[.,]\d+)?)""", ic)
    private val bwRe = Regex("""\bBW\s+Oct\s+(\d+(?:[.,]\d+)?)""", ic)

    private fun num(s: String?): Float? = s?.replace(',', '.')?.toFloatOrNull()

    fun parse(text: String): ParseResult {
        var preamp: Float? = null
        val filters = mutableListOf<EqFilter>()
        val warnings = mutableListOf<String>()

        text.lineSequence().forEach { line ->
            preampRe.find(line)?.let { preamp = num(it.groupValues[1]); return@forEach }
            val m = filterRe.find(line) ?: return@forEach
            val on = m.groupValues[1].equals("ON", ignoreCase = true)
            val typeToken = m.groupValues[2].uppercase()
            val rest = m.groupValues[3]
            if (!on) return@forEach

            val type = when (typeToken) {
                "PK", "PEQ" -> FilterType.PK
                "LSC", "LS", "LSQ" -> FilterType.LSC
                "HSC", "HS", "HSQ" -> FilterType.HSC
                else -> { warnings += "Skipped unsupported filter type $typeToken"; return@forEach }
            }
            val fc = num(fcRe.find(rest)?.groupValues?.get(1))
            if (fc == null) { warnings += "Skipped filter without Fc: ${line.trim()}"; return@forEach }
            val gain = num(gainRe.find(rest)?.groupValues?.get(1)) ?: 0f
            val q = num(qRe.find(rest)?.groupValues?.get(1))
                ?: num(bwRe.find(rest)?.groupValues?.get(1))?.let { bwToQ(it) }
                ?: if (type == FilterType.PK) 1.0f else 0.707f
            filters += EqFilter(type, fc.coerceIn(20f, 20_000f), gain, q.coerceIn(0.1f, 30f))
        }

        if (filters.size > MAX_FILTERS) {
            warnings += "Only the first $MAX_FILTERS of ${filters.size} filters were kept"
        }
        return ParseResult(preamp, filters.take(MAX_FILTERS), warnings)
    }

    private fun bwToQ(octaves: Float): Float {
        val x = 2.0.pow(octaves.toDouble())
        return (sqrt(x) / (x - 1.0)).toFloat()
    }
}
