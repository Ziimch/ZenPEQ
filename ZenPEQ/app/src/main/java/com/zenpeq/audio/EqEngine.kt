package com.zenpeq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.SessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drives DynamicsProcessing instances (API 28+, which is also this app's minSdk).
 *  - EQ curve -> PreEq stage: 128 log bands, each probing 5 sub-frequencies (see [FilterMath.renderBands])
 *  - preamp   -> per-channel input gain (applied before PreEq); the L/R balance offset rides on it
 *  - limiter  -> -0.5 dBFS peak limiter after the EQ
 * Every change is ramped in a few small steps, so dragging a point never zippers or clicks.
 * Routing: global session 0, or one instance per player session announced by apps.
 */
class EqEngine {
    data class Result(
        val route: EngineRoute,
        val bands: Int = 0,
        val appSessions: Int = 0,
        val preampDb: Float = 0f,
        val peakDb: Float = 0f,
        val limiterActive: Boolean = false,
        val error: String? = null,
    )

    private data class Pushed(val preampDb: Float, val peakDb: Float, val limiterActive: Boolean)

    /** One DynamicsProcessing attached to one audio session. */
    private class Slot(private val session: Int) {
        private var dp: DynamicsProcessing? = null
        private var grid: BandGrid? = null
        private var limiterBuilt = false
        private var limiterSupported = true // flips off once a build only works without the limiter
        private var limiterOn = false
        private var curGains = FloatArray(0)
        private var curL = 0f
        private var curR = 0f
        val bands: Int get() = grid?.count ?: 0

        /** Throws if the device rejects every configuration. */
        suspend fun apply(
            profile: EqProfile,
            filters: List<EqFilter>,
            limiterWanted: Boolean,
            sampleRate: Int,
            balanceDb: Float,
        ): Pushed {
            var last: Exception? = null
            val maxHz = min(FilterMath.MAX_HZ, sampleRate / 2.0 - 1.0) // never ask for bands above Nyquist
            val sizes = listOfNotNull(grid?.count).plus(listOf(FilterMath.BANDS, 64, 32)).distinct()
            // Try with the limiter first; some vendor builds reject it, so retry without.
            val variants = if (limiterSupported) listOf(true, false) else listOf(false)
            for (withLimiter in variants) {
                for (n in sizes) {
                    try {
                        ensure(n, withLimiter, maxHz, limiterWanted)
                        val pushed = rampToProfile(profile, filters, limiterWanted, balanceDb)
                        if (!withLimiter) limiterSupported = false
                        return pushed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        last = e
                        release()
                    }
                }
            }
            throw last ?: IllegalStateException("unknown error")
        }

        private fun ensure(bands: Int, withLimiter: Boolean, maxHz: Double, limiterWanted: Boolean) {
            val g = grid
            if (dp != null && g != null && g.count == bands && limiterBuilt == withLimiter && abs(g.maxHz - maxHz) < 1.0) return
            release()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true, /* preEqBandCount = */ bands,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ withLimiter,
            ).build()
            val d = DynamicsProcessing(/* priority = */ 0, session, config)
            val newGrid = BandGrid(bands, maxHz)
            dp = d
            grid = newGrid
            limiterBuilt = withLimiter

            // Start flat so the first ramp fades the EQ in instead of switching it on with a click.
            val eq = DynamicsProcessing.Eq(true, true, bands)
            for (i in 0 until bands) eq.setBand(i, DynamicsProcessing.EqBand(true, newGrid.cutoffs[i], 0f))
            d.setPreEqAllChannelsTo(eq)
            d.setInputGainByChannelIndex(0, 0f)
            d.setInputGainByChannelIndex(1, 0f)
            curGains = FloatArray(bands)
            curL = 0f
            curR = 0f
            limiterOn = false
            if (withLimiter) setLimiter(d, limiterWanted)
            val status = d.setEnabled(true)
            if (status != AudioEffect.SUCCESS) error("setEnabled failed ($status)")
        }

        private fun setLimiter(d: DynamicsProcessing, on: Boolean) {
            d.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true, /* enabled = */ on, /* linkGroup = */ 0,
                    LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB, LIMITER_POST_GAIN_DB,
                ),
            )
            limiterOn = on
        }

        private suspend fun rampToProfile(
            profile: EqProfile,
            filters: List<EqFilter>,
            limiterWanted: Boolean,
            balanceDb: Float,
        ): Pushed {
            val d = checkNotNull(dp)
            val g = checkNotNull(grid)
            val target = FilterMath.renderBands(g, filters, profile.graphic)
            val peak = max(0f, target.max())
            val preamp = (if (profile.autoPreamp) FilterMath.autoPreampDb(target) else profile.preampDb)
                .coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
            // Balance only ever attenuates the louder side, so it can't add clipping headroom needs.
            val left = preamp - max(0f, balanceDb)
            val right = preamp - max(0f, -balanceDb)
            if (limiterBuilt && limiterOn != limiterWanted) setLimiter(d, limiterWanted)
            ramp(target, left, right)
            return Pushed(preamp, peak, limiterBuilt && limiterWanted)
        }

        /** ~20 ms smoothstep from the currently applied values; only changed bands are written. */
        suspend fun ramp(target: FloatArray, left: Float, right: Float) {
            val d = dp ?: return
            val g = grid ?: return
            if (target.size != curGains.size) return
            val from = curGains.copyOf()
            val fromL = curL
            val fromR = curR
            if (from.contentEquals(target) && fromL == left && fromR == right) return
            for (s in 1..RAMP_STEPS) {
                val last = s == RAMP_STEPS
                val t = s.toFloat() / RAMP_STEPS
                val e = t * t * (3f - 2f * t)
                for (i in target.indices) {
                    val v = if (last) target[i] else from[i] + (target[i] - from[i]) * e
                    if (v != curGains[i] && (last || abs(v - curGains[i]) > 0.005f)) {
                        d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, g.cutoffs[i], v))
                        curGains[i] = v
                    }
                }
                val l = if (last) left else fromL + (left - fromL) * e
                val r = if (last) right else fromR + (right - fromR) * e
                if (l != curL) { d.setInputGainByChannelIndex(0, l); curL = l }
                if (r != curR) { d.setInputGainByChannelIndex(1, r); curR = r }
                if (!last) delay(RAMP_STEP_MS)
            }
        }

        suspend fun fadeToFlat() = ramp(FloatArray(curGains.size), 0f, 0f)

        fun release() {
            runCatching { dp?.release() }
            dp = null
            grid = null
            limiterBuilt = false
            limiterOn = false
            curGains = FloatArray(0)
        }
    }

    private var globalSlot: Slot? = null
    private val appSlots = mutableMapOf<Int, Slot>()

    suspend fun apply(
        profile: EqProfile?,
        enabled: Boolean,
        mode: SessionMode,
        sessions: Set<Int>,
        limiter: Boolean,
        sampleRate: Int,
        balanceDb: Float,
    ): Result {
        if (!enabled || profile == null) {
            // Fade out before tearing the effect down so bypass doesn't click.
            for (slot in listOfNotNull(globalSlot) + appSlots.values) {
                try {
                    slot.fadeToFlat()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // effect already gone; release() below cleans up
                }
            }
            release()
            return Result(EngineRoute.NONE)
        }
        val filters = profile.filters.filter { it.enabled }.take(MAX_FILTERS)
        var globalError: String? = null

        if (mode != SessionMode.PER_APP) {
            val slot = globalSlot ?: Slot(0).also { globalSlot = it }
            try {
                val r = slot.apply(profile, filters, limiter, sampleRate, balanceDb)
                releaseApps() // global works: no need for per-player instances
                return Result(EngineRoute.GLOBAL, slot.bands, 0, r.preampDb, r.peakDb, r.limiterActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                globalError = e.message ?: e.javaClass.simpleName
                slot.release()
                globalSlot = null
            }
            if (mode == SessionMode.GLOBAL) return Result(EngineRoute.NONE, error = globalError)
        } else {
            globalSlot?.release()
            globalSlot = null
        }

        // Per-app route (AUTO fallback or forced).
        appSlots.keys.filter { it !in sessions }.forEach { appSlots.remove(it)?.release() }
        var applied = 0
        var bands = 0
        var last: Pushed? = null
        var lastError: String? = globalError
        for (s in sessions) {
            try {
                val slot = appSlots.getOrPut(s) { Slot(s) }
                last = slot.apply(profile, filters, limiter, sampleRate, balanceDb)
                bands = slot.bands
                applied++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appSlots.remove(s)?.release() // session probably gone
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return if (applied > 0 || sessions.isEmpty()) {
            Result(
                EngineRoute.PER_APP, bands, applied,
                last?.preampDb ?: 0f, last?.peakDb ?: 0f, last?.limiterActive ?: false, globalError,
            )
        } else {
            Result(EngineRoute.NONE, error = lastError)
        }
    }

    private fun releaseApps() {
        appSlots.values.forEach { it.release() }
        appSlots.clear()
    }

    fun release() {
        globalSlot?.release()
        globalSlot = null
        releaseApps()
    }

    private companion object {
        const val RAMP_STEPS = 4
        const val RAMP_STEP_MS = 5L           // 4 steps x 5 ms ~ 20 ms per change
        const val LIMITER_THRESHOLD_DB = -0.5f // headroom for Bluetooth AAC/SBC inter-sample peaks
        const val LIMITER_ATTACK_MS = 3f
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 12f
        const val LIMITER_POST_GAIN_DB = 0f
    }
}
