package com.strumbum.app.music

import kotlin.math.abs

/**
 * Spec: the in-tune state turns on after the pitch has stayed within ±[toleranceCents]
 * for [holdMs]. It stays on while the note decays into silence, and turns off as soon as
 * a reading leaves the tolerance or the target changes.
 */
class InTuneDetector(
    private val toleranceCents: Double = 3.0,
    private val holdMs: Long = 400,
) {
    var inTune: Boolean = false
        private set
    private var withinSince: Long? = null

    fun reset() {
        inTune = false
        withinSince = null
    }

    /**
     * Feed the latest offset, or null when there is no current pitch.
     * Returns true exactly once per lock, which is when the haptic tick fires.
     */
    fun update(cents: Double?, nowMs: Long): Boolean {
        if (cents == null) {
            withinSince = null
            return false
        }
        if (abs(cents) > toleranceCents) {
            inTune = false
            withinSince = null
            return false
        }
        val since = withinSince ?: nowMs.also { withinSince = it }
        if (!inTune && nowMs - since >= holdMs) {
            inTune = true
            return true
        }
        return false
    }
}
