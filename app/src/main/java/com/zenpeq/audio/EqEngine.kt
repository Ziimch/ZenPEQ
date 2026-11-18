package com.zenpeq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.SessionMode

/**
 * Drives DynamicsProcessing instances.
 *  - EQ bands -> PreEq stage, rendered from the parametric filters
 *  - preamp   -> inputGain (applied before PreEq)
 * Routing: global session 0, or one instance per player session announced by apps.
 */
class EqEngine {
    data class Result(
        val route: EngineRoute,
        val bands: Int = 0,
        val appSessions: Int = 0,
        val preampDb: Float = 0f,
        val peakDb: Float = 0f,
        val error: String? = null,
    )

    /** One DynamicsProcessing attached to one audio session. */
    private class Slot(private val session: Int) {
        private var dp: DynamicsProcessing? = null
        private var grid: BandGrid? = null
        val bands: Int get() = grid?.count ?: 0

        /** Returns (preampDb, peakDb); throws if the device rejects the effect. */
        fun apply(profile: EqProfile, filters: List<EqFilter>): Pair<Float, Float> {
            var last: Exception? = null
            // Prefer a dense grid; fall back to fewer bands if a vendor implementation rejects it.
            val sizes = listOfNotNull(grid?.count).plus(listOf(48, 24, 10)).distinct()
            for (n in sizes) {
                try {
                    ensure(n)
                    return push(profile, filters)
                } catch (e: Exception) {
                    last = e
                    release()
                }
            }
            throw last ?: IllegalStateException("unknown error")
        }

        private fun ensure(bands: Int) {
            if (dp != null && grid?.count == bands) return
            release()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true, /* preEqBandCount = */ bands,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ false,
            ).build()
            dp = DynamicsProcessing(/* priority = */ 0, session, config)
            grid = BandGrid(bands)
        }

        private fun push(profile: EqProfile, filters: List<EqFilter>): Pair<Float, Float> {
            val d = checkNotNull(dp)
            val g = checkNotNull(grid)
            val gains = FilterMath.curve(filters, g.centers).map { it.coerceIn(-20.0, 20.0) }
            val peak = maxOf(0.0, gains.max())
            // Auto-Preamp: pull everything down by the highest positive gain so the boost can't clip.
            val preamp = (if (profile.autoPreamp) -peak else profile.preampDb.toDouble()).coerceIn(-24.0, 12.0)

            val eq = DynamicsProcessing.Eq(true, true, g.count)
            for (i in 0 until g.count) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, g.cutoffs[i], gains[i].toFloat()))
            }
            d.setPreEqAllChannelsTo(eq)
            d.setInputGainAllChannelsTo(preamp.toFloat())
            val status = d.setEnabled(true)
            if (status != AudioEffect.SUCCESS) error("setEnabled failed ($status)")
            return preamp.toFloat() to peak.toFloat()
        }

        fun release() {
            runCatching { dp?.release() }
            dp = null
            grid = null
        }
    }

    private var globalSlot: Slot? = null
    private val appSlots = mutableMapOf<Int, Slot>()

    fun apply(profile: EqProfile?, enabled: Boolean, mode: SessionMode, sessions: Set<Int>): Result {
        if (!enabled || profile == null) {
            release()
            return Result(EngineRoute.NONE)
        }
        val filters = profile.filters.filter { it.enabled }.take(MAX_FILTERS)
        var globalError: String? = null

        if (mode != SessionMode.PER_APP) {
            val slot = globalSlot ?: Slot(0).also { globalSlot = it }
            try {
                val (pre, peak) = slot.apply(profile, filters)
                releaseApps() // global works: no need for per-player instances
                return Result(EngineRoute.GLOBAL, slot.bands, 0, pre, peak)
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
        var pre = 0f
        var peak = 0f
        var lastError: String? = globalError
        for (s in sessions) {
            try {
                val slot = appSlots.getOrPut(s) { Slot(s) }
                val r = slot.apply(profile, filters)
                pre = r.first; peak = r.second; bands = slot.bands
                applied++
            } catch (e: Exception) {
                appSlots.remove(s)?.release() // session probably gone
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return if (applied > 0 || sessions.isEmpty()) {
            Result(EngineRoute.PER_APP, bands, applied, pre, peak, globalError)
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
}
