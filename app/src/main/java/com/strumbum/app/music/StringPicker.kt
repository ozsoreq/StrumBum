package com.strumbum.app.music

import kotlin.math.abs

/**
 * Picks the string the player is tuning in auto mode.
 *
 * Plain nearest-string picking flickers between neighbours when a string is tuned
 * far off (G3 and B3 are only 400 cents apart). The picker therefore keeps the
 * current string until another string is closer by [hysteresisCents].
 */
class StringPicker(private val hysteresisCents: Double = 40.0) {
    private var current: Int? = null

    fun reset() {
        current = null
    }

    /** Returns the index into [strings] (MIDI notes) that [hz] should be compared against. */
    fun pick(hz: Double, strings: List<Int>, a4: Double): Int {
        require(strings.isNotEmpty())
        val distances = strings.map { abs(NoteMath.cents(hz, NoteMath.midiToHz(it, a4))) }
        val best = distances.indices.minBy { distances[it] }
        val cur = current
        val chosen = if (cur != null && cur in strings.indices && distances[cur] - distances[best] < hysteresisCents) cur else best
        current = chosen
        return chosen
    }
}
