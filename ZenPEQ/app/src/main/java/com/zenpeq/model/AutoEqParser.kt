package com.zenpeq.model

import kotlin.math.pow
import kotlin.math.sqrt

data class ParseResult(
    val preampDb: Float?,
    val filters: List<EqFilter>,
    val warnings: List<String>,
    val graphic: List<GraphicPoint> = emptyList(),
)

/**
 * Parses Equalizer APO / AutoEq / Squiglink text:
 *   Preamp: -3.0 dB
 *   Filter 1: ON PK Fc 4500 Hz Gain -3.5 dB Q 2.5      (PK/PEQ, LSC/LS/LSQ, HSC/HS/HSQ)
 *   GraphicEQ: 20 -2.4; 21 -2.5; ...                   (single line)
 */
object AutoEqParser {
    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val preampRe = Regex("""^\s*Preamp\s*:\s*([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val filterRe = Regex("""^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Za-z]+)\s+(.*)$""", ic)
    private val graphicRe = Regex("""^\s*GraphicEQ\s*:\s*(.*)$""", ic)
    private val fcRe = Regex("""\bFc\s+([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val gainRe = Regex("""\bGain\s+([+-]?\d+(?:[.,]\d+)?)""", ic)
    private val qRe = Regex("""\bQ\s+(\d+(?:[.,]\d+)?)""", ic)
    private val bwRe = Regex("""\bBW\s+Oct\s+(\d+(?:[.,]\d+)?)""", ic)

    private fun num(s: String?): Float? = s?.replace(',', '.')?.toFloatOrNull()

    fun parse(text: String): ParseResult {
        var preamp: Float? = null
        val filters = mutableListOf<EqFilter>()
        val graphic = mutableListOf<GraphicPoint>()
        val warnings = mutableListOf<String>()
        var clamped = false

        text.lineSequence().forEach { line ->
            preampRe.find(line)?.let { preamp = num(it.groupValues[1]); return@forEach }
            graphicRe.find(line)?.let { m ->
                m.groupValues[1].split(';').forEach { chunk ->
                    val parts = chunk.trim().split(Regex("\\s+"))
                    val f = num(parts.getOrNull(0))
                    val g = num(parts.getOrNull(1))
                    if (f != null && g != null) {
                        graphic += GraphicPoint(f.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ), g.coerceIn(-24f, 24f))
                    }
                }
                return@forEach
            }
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
            if (gain !in -MAX_GAIN_DB..MAX_GAIN_DB || q !in MIN_Q..MAX_Q) clamped = true
            filters += EqFilter(
                type,
                fc.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ),
                gain.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
                q.coerceIn(MIN_Q, MAX_Q),
            )
        }

        if (clamped) warnings += "Some gains/Q values were clamped to ±${MAX_GAIN_DB.toInt()} dB and Q $MIN_Q–$MAX_Q"
        if (filters.size > MAX_FILTERS) {
            warnings += "Only the first $MAX_FILTERS of ${filters.size} filters were kept"
        }
        val points = graphic.sortedBy { it.freq }.distinctBy { it.freq }.take(MAX_GRAPHIC_POINTS)
        return ParseResult(preamp, filters.take(MAX_FILTERS), warnings, points)
    }

    private fun bwToQ(octaves: Float): Float {
        val x = 2.0.pow(octaves.toDouble())
        return (sqrt(x) / (x - 1.0)).toFloat()
    }
}
