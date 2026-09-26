package com.strumbum.app.music

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rounds a noisy reading for display, e.g. the "12 cents flat" text. The shown number
 * changes only once the value is more than [marginCents] past the rounding boundary, so a
 * reading that sits near x.5 doesn't alternate between two numbers every frame.
 */
class StickyRounder(private val marginCents: Double = 0.3) {
    private var shown: Int? = null

    fun reset() {
        shown = null
    }

    fun round(value: Double): Int {
        val cur = shown
        val n = if (cur != null && abs(value - cur) <= 0.5 + marginCents) cur else value.roundToInt()
        shown = n
        return n
    }
}
