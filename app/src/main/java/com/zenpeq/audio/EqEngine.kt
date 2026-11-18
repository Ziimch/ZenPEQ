package com.zenpeq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.zenpeq.model.EqFilter
import com.zenpeq.model.EqProfile
import com.zenpeq.model.MAX_FILTERS
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.SessionMode

/**
 * Drives DynamicsProcessing instances (API 28+, which is also this app's minSdk).
 *  - EQ curve -> PreEq stage, rendered from the parametric filters (+ GraphicEQ layer)
 *  - preamp   -> inputGain (applied before PreEq)
 *  - limiter  -> brickwall peak limiter after the EQ
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
        val bands: Int get() = grid?.count ?: 0

        /** Throws if the device rejects every configuration. */
        fun apply(profile: EqProfile, filters: List<EqFilter>, limiterWanted: Boolean): Pushed {
            var last: Exception? = null
            val sizes = listOfNotNull(grid?.count).plus(listOf(64, 32, 16)).distinct()
            // Try with the limiter first; some vendor builds reject it, so retry without.
            val variants = if (limiterSupported) listOf(true, false) else listOf(false)
            for (withLimiter in variants) {
                for (n in sizes) {
                    try {
                        ensure(n, withLimiter)
                        val pushed = push(profile, filters, limiterWanted)
                        if (!withLimiter) limiterSupported = false
                        return pushed
                    } catch (e: Exception) {
                        last = e
                        release()
                    }
                }
            }
            throw last ?: IllegalStateException("unknown error")
        }

        private fun ensure(bands: Int, withLimiter: Boolean) {
            if (dp != null && grid?.count == bands && limiterBuilt == withLimiter) return
            release()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true, /* preEqBandCount = */ bands,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ withLimiter,
            ).build()
            dp = DynamicsProcessing(/* priority = */ 0, session, config)
            grid = BandGrid(bands)
            limiterBuilt = withLimiter
        }

        private fun push(profile: EqProfile, filters: List<EqFilter>, limiterWanted: Boolean): Pushed {
            val d = checkNotNull(dp)
            val g = checkNotNull(grid)
            val gains = FilterMath.curve(filters, g.centers, profile.graphic).map { it.coerceIn(-20.0, 20.0) }
            val peak = maxOf(0.0, gains.max())
            // Auto-Preamp: pull everything down by the highest positive gain so the boost can't clip.
            val preamp = (if (profile.autoPreamp) -peak else profile.preampDb.toDouble())
                .coerceIn(MIN_PREAMP_DB.toDouble(), MAX_PREAMP_DB.toDouble())

            val eq = DynamicsProcessing.Eq(true, true, g.count)
            for (i in 0 until g.count) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, g.cutoffs[i], gains[i].toFloat()))
            }
            d.setPreEqAllChannelsTo(eq)
            d.setInputGainAllChannelsTo(preamp.toFloat())
            if (limiterBuilt) {
                d.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(
                        /* inUse = */ true, /* enabled = */ limiterWanted, /* linkGroup = */ 0,
                        LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB, 0f,
                    ),
                )
            }
            val status = d.setEnabled(true)
            if (status != AudioEffect.SUCCESS) error("setEnabled failed ($status)")
            return Pushed(preamp.toFloat(), peak.toFloat(), limiterBuilt && limiterWanted)
        }

        fun release() {
            runCatching { dp?.release() }
            dp = null
            grid = null
            limiterBuilt = false
        }
    }

    private var globalSlot: Slot? = null
    private val appSlots = mutableMapOf<Int, Slot>()

    fun apply(
        profile: EqProfile?,
        enabled: Boolean,
        mode: SessionMode,
        sessions: Set<Int>,
        limiter: Boolean,
    ): Result {
        if (!enabled || profile == null) {
            release()
            return Result(EngineRoute.NONE)
        }
        val filters = profile.filters.filter { it.enabled }.take(MAX_FILTERS)
        var globalError: String? = null

        if (mode != SessionMode.PER_APP) {
            val slot = globalSlot ?: Slot(0).also { globalSlot = it }
            try {
                val r = slot.apply(profile, filters, limiter)
                releaseApps() // global works: no need for per-player instances
                return Result(EngineRoute.GLOBAL, slot.bands, 0, r.preampDb, r.peakDb, r.limiterActive)
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
                last = slot.apply(profile, filters, limiter)
                bands = slot.bands
                applied++
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
        const val LIMITER_THRESHOLD_DB = 0f   // 0 dBFS ceiling
        const val LIMITER_ATTACK_MS = 1f      // fast attack
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 20f         // effectively brickwall
    }
}
