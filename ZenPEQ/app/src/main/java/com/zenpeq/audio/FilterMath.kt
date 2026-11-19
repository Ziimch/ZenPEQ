package com.zenpeq.audio

import com.zenpeq.model.EqFilter
import com.zenpeq.model.FilterType
import com.zenpeq.model.GraphicPoint
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_Q
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Log-spaced render grid. DynamicsProcessing band i spans (edges[i], edges[i+1]]; cutoff = upper edge.
 * [maxHz] is capped below Nyquist so no band is ever requested above half the output sample rate.
 * [probes] holds [FilterMath.SUB_POINTS] log-spaced frequencies inside every band (band-major).
 */
class BandGrid(val count: Int, val maxHz: Double = FilterMath.MAX_HZ) {
    val edges = DoubleArray(count + 1) {
        FilterMath.MIN_HZ * (maxHz / FilterMath.MIN_HZ).pow(it.toDouble() / count)
    }
    val cutoffs = FloatArray(count) { edges[it + 1].toFloat() }
    val probes = DoubleArray(count * FilterMath.SUB_POINTS) { i ->
        val band = i / FilterMath.SUB_POINTS
        val t = ((i % FilterMath.SUB_POINTS) + 0.5) / FilterMath.SUB_POINTS
        edges[band] * (edges[band + 1] / edges[band]).pow(t)
    }
}

/**
 * Filter responses for a magnitude-domain equaliser.
 *
 * DynamicsProcessing applies a gain per FFT band, so there is no biquad whose bilinear transform could
 * "cramp" near Nyquist. Each filter is therefore evaluated from its analog (s-domain) RBJ prototype at
 * the true frequency, which is exactly the response that Vicanek's matched biquads approximate. Peaks
 * and shelves at 10–16 kHz stay symmetrical at any output sample rate.
 */
object FilterMath {
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0
    const val BANDS = 128
    const val SUB_POINTS = 5
    private const val PEAK_WEIGHT = 0.5          // blend of RMS (0) and peak (1) per band
    const val AUTO_PREAMP_MARGIN_DB = 0.3f

    private class Proto(val type: FilterType, val f0: Double, val a: Double, val q: Double)

    private fun proto(f: EqFilter) = Proto(
        f.type,
        f.freq.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ).toDouble(),
        10.0.pow(f.gainDb.coerceIn(-24f, 24f) / 40.0),
        f.q.coerceIn(MIN_Q, MAX_Q).toDouble(),
    )

    private fun sq(v: Double) = v * v

    /** |H(jΩ)| in dB at [hz] for the analog prototype (x = f / f0, A = 10^(dB/40)). */
    private fun magDb(p: Proto, hz: Double): Double {
        val x = hz / p.f0
        val x2 = x * x
        val a = p.a
        return when (p.type) {
            FilterType.PK -> {
                val d = 1.0 - x2
                10.0 * log10((sq(d) + sq(x * a / p.q)) / (sq(d) + sq(x / (a * p.q))))
            }
            FilterType.LSC -> {
                val k = x2 * a / sq(p.q)
                20.0 * log10(a) + 10.0 * log10((sq(a - x2) + k) / (sq(1.0 - a * x2) + k))
            }
            FilterType.HSC -> {
                val k = x2 * a / sq(p.q)
                20.0 * log10(a) + 10.0 * log10((sq(1.0 - a * x2) + k) / (sq(a - x2) + k))
            }
        }
    }

    /** Response of a single filter (for drawing individual band curves). */
    fun filterCurve(f: EqFilter, freqs: DoubleArray): DoubleArray {
        val p = proto(f)
        return DoubleArray(freqs.size) { magDb(p, freqs[it]) }
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
        val protos = filters.filter { it.enabled }.map { proto(it) }
        val g = graphicCurve(graphic, freqs)
        return DoubleArray(freqs.size) { i -> protos.sumOf { magDb(it, freqs[i]) } + g[i] }
    }

    /**
     * Per-band gains for the PreEq stage. Each band probes [SUB_POINTS] frequencies across its own
     * bandwidth and takes half RMS magnitude, half the largest deviation, so a narrow high-Q notch can't
     * fall between band centres.
     */
    fun renderBands(grid: BandGrid, filters: List<EqFilter>, graphic: List<GraphicPoint>): FloatArray {
        val db = curve(filters, grid.probes, graphic)
        return FloatArray(grid.count) { b ->
            var sumSq = 0.0
            var peak = 0.0
            for (k in 0 until SUB_POINTS) {
                val v = db[b * SUB_POINTS + k]
                sumSq += sq(10.0.pow(v / 20.0))
                if (abs(v) > abs(peak)) peak = v
            }
            val rmsDb = 10.0 * log10(sumSq / SUB_POINTS)
            (rmsDb * (1.0 - PEAK_WEIGHT) + peak * PEAK_WEIGHT).coerceIn(-20.0, 20.0).toFloat()
        }
    }

    /** Preamp from the real summed curve: minus its highest positive peak, plus a safety margin. */
    fun autoPreampDb(bandGains: FloatArray): Float {
        val peak = bandGains.max()
        return if (peak > 0f) -(peak + AUTO_PREAMP_MARGIN_DB) else 0f
    }
}
