package com.strumbum.app.music

import org.junit.Assert.assertEquals
import org.junit.Test

class StringPickerTest {
    private val strings = Tunings.STANDARD.strings
    private fun hz(midi: Double) = NoteMath.midiToHz(midi)

    @Test fun picksNearest() {
        val p = StringPicker()
        assertEquals(0, p.pick(hz(40.3), strings, 440.0))
        assertEquals(5, p.pick(hz(63.2), strings, 440.0))
    }

    @Test fun holdsCurrentStringNearTheMidpoint() {
        val p = StringPicker(hysteresisCents = 40.0)
        // G3 (55) and B3 (59): the midpoint is 57.
        assertEquals(3, p.pick(hz(56.0), strings, 440.0))
        assertEquals(3, p.pick(hz(57.1), strings, 440.0)) // B is closer, but not by 40 cents
        assertEquals(4, p.pick(hz(57.5), strings, 440.0)) // B closer by 100 cents
    }

    @Test fun resetForgetsCurrent() {
        val p = StringPicker()
        p.pick(hz(56.0), strings, 440.0)
        p.reset()
        assertEquals(4, p.pick(hz(57.1), strings, 440.0))
    }
}
