package com.strumbum.app.music

import kotlin.math.abs

/**
 * Nearest-note picking with hysteresis, for chromatic mode and the "You're playing" hint.
 *
 * Plain rounding flips between two notes when the pitch sits near the midpoint (±50 cents),
 * and in chromatic mode the needle then swings across the whole meter. The tracker keeps
 * the current note until the pitch is more than [hysteresisCents] past that midpoint.
 */
class NoteTracker(private val hysteresisCents: Double = 20.0) {
    private var current: Int? = null

    fun reset() {
        current = null
    }

    /** Returns the MIDI note that [hz] should be named and measured against. */
    fun nearest(hz: Double, a4: Double): Int {
        val cur = current
        val note = if (cur != null && abs(NoteMath.cents(hz, NoteMath.midiToHz(cur, a4))) <= 50.0 + hysteresisCents) {
            cur
        } else {
            NoteMath.nearestMidi(hz, a4)
        }
        current = note
        return note
    }
}
