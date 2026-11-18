package com.zenpeq.audio

import com.zenpeq.model.EqFilter
import com.zenpeq.model.FilterType
import com.zenpeq.model.GraphicPoint
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_Q
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Log-spaced render grid. DynamicsProcessing band i spans (edges[i], edges[i+1]]; cutoff = upper edge. */
class BandGrid(val count: Int) {
    val edges = DoubleArray(count + 1) {
        FilterMath.MIN_HZ * (FilterMath.MAX_HZ / FilterMath.MIN_HZ).pow(it.toDouble() / count)
    }
    val cutoffs = FloatArray(count) { edges[it + 1].toFloat() }
    val centers = DoubleArray(count) { sqrt(edges[it] * edges[it + 1]) }
}

/**
 * RBJ biquad magnitude responses. DynamicsProcessing bands have no Q, so the engine sums the
 * response of every parametric filter (plus an optional GraphicEQ layer) and writes that curve
 * onto a dense band grid.
 */
object FilterMath {
    private const val FS = 48_000.0
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    private class Biquad(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double,
    )

    private fun design(f: EqFilter): Biquad {
        val a = 10.0.pow(f.gainDb.coerceIn(-24f, 24f) / 40.0)
        val w0 = 2.0 * PI * f.freq.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ) / FS
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * f.q.coerceIn(MIN_Q, MAX_Q))
        return when (f.type) {
            FilterType.PK -> Biquad(
                1 + alpha * a, -2 * c, 1 - alpha * a,
                1 + alpha / a, -2 * c, 1 - alpha / a,
            )
            FilterType.LSC -> {
                val t = 2 * sqrt(a) * alpha
                Biquad(
                    a * ((a + 1) - (a - 1) * c + t),
                    2 * a * ((a - 1) - (a + 1) * c),
                    a * ((a + 1) - (a - 1) * c - t),
                    (a + 1) + (a - 1) * c + t,
                    -2 * ((a - 1) + (a + 1) * c),
                    (a + 1) + (a - 1) * c - t,
                )
            }
            FilterType.HSC -> {
                val t = 2 * sqrt(a) * alpha
                Biquad(
                    a * ((a + 1) + (a - 1) * c + t),
                    -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - t),
                    (a + 1) - (a - 1) * c + t,
                    2 * ((a - 1) - (a + 1) * c),
                    (a + 1) - (a - 1) * c - t,
                )
            }
        }
    }

    private fun magDb(q: Biquad, hz: Double): Double {
        val w = 2.0 * PI * hz / FS
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = q.b0 + q.b1 * c1 + q.b2 * c2
        val ni = -(q.b1 * s1 + q.b2 * s2)
        val dr = q.a0 + q.a1 * c1 + q.a2 * c2
        val di = -(q.a1 * s1 + q.a2 * s2)
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Response of a single filter (for drawing individual band curves). */
    fun filterCurve(f: EqFilter, freqs: DoubleArray): DoubleArray {
        val bq = design(f)
        return DoubleArray(freqs.size) { magDb(bq, freqs[it]) }
    }

    /** GraphicEQ layer, linearly interpolated over log-frequency, held flat outside its range. */
    fun graphicCurve(points: List<GraphicPoint>, freqs: DoubleArray): DoubleArray {
        if (points.isEmpty()) return DoubleArray(freqs.size)
        val p = points.sortedBy { it.freq }
        return DoubleArray(freqs.size) { i ->
            val hz = freqs[i]
            when {
                hz <= p.first().freq -> p.first().gainDb.toDouble()
                hz >= p.last().freq -> p.last().gainDb.toDouble()
                else -> {
                    var lo = 0
                    var hi = p.size - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (p[mid].freq <= hz) lo = mid else hi = mid
                    }
                    val f0 = p[lo].freq.toDouble()
                    val f1 = p[hi].freq.toDouble()
                    if (f1 <= f0) p[lo].gainDb.toDouble()
                    else {
                        val t = ln(hz / f0) / ln(f1 / f0)
                        p[lo].gainDb + (p[hi].gainDb - p[lo].gainDb).toDouble() * t
                    }
                }
            }
        }
    }

    /** Combined response in dB of all enabled filters (+ graphic layer) at each frequency. */
    fun curve(filters: List<EqFilter>, freqs: DoubleArray, graphic: List<GraphicPoint> = emptyList()): DoubleArray {
        val bq = filters.filter { it.enabled }.map { design(it) }
        val g = graphicCurve(graphic, freqs)
        return DoubleArray(freqs.size) { i -> bq.sumOf { magDb(it, freqs[i]) } + g[i] }
    }
}
