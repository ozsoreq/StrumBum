package com.strumbum.app.music

import kotlin.math.abs

/**
 * Spec: the in-tune state turns on after the pitch has stayed within ±[toleranceCents]
 * for [holdMs]. It stays on while the note decays into silence, and turns off once a
 * reading goes past ±[releaseCents] or the target changes.
 *
 * The gap between the two thresholds is hysteresis. Without it a string sitting right at
 * ±3 cents, with a little noise on the reading, drops and re-fires the lock (and the
 * haptic tick) over and over.
 */
class InTuneDetector(
    private val toleranceCents: Double = 3.0,
    private val holdMs: Long = 400,
    private val releaseCents: Double = 5.0,
) {
    init {
        require(releaseCents >= toleranceCents)
    }

    var inTune: Boolean = false
        private set

    /**
     * The same band without the hold time: on within ±[toleranceCents], off past
     * ±[releaseCents]. The "In tune" label follows this, so it doesn't flicker either.
     */
    var centered: Boolean = false
        private set

    private var withinSince: Long? = null

    fun reset() {
        inTune = false
        centered = false
        withinSince = null
    }

    /**
     * Feed the latest offset, or null when there is no current pitch.
     * Returns true exactly once per lock, which is when the haptic tick fires.
     */
    fun update(cents: Double?, nowMs: Long): Boolean {
        if (cents == null || cents.isNaN()) {
            withinSince = null
            return false
        }
        val off = abs(cents)
        centered = off <= (if (centered) releaseCents else toleranceCents)
        if (inTune) {
            if (off > releaseCents) {
                inTune = false
                withinSince = null
            }
            return false
        }
        if (off > toleranceCents) {
            withinSince = null
            return false
        }
        val since = withinSince ?: nowMs.also { withinSince = it }
        if (nowMs - since >= holdMs) {
            inTune = true
            return true
        }
        return false
    }
}
