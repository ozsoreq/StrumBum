package com.strumbum.app.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTrackerTest {
    private fun hz(midi: Double) = NoteMath.midiToHz(midi)

    @Test fun startsAtTheNearestNote() {
        assertEquals(57, NoteTracker().nearest(hz(57.4), 440.0))
        assertEquals(58, NoteTracker().nearest(hz(57.6), 440.0))
    }

    @Test fun holdsTheNoteAcrossTheMidpoint() {
        val t = NoteTracker(hysteresisCents = 20.0)
        // A3 + 49 c, then noise pushes it just past the midpoint and back.
        listOf(57.49, 57.52, 57.48, 57.55, 57.69).forEach { assertEquals(57, t.nearest(hz(it), 440.0)) }
        assertEquals(58, t.nearest(hz(57.71), 440.0))
        // ...and now A# is held the same way.
        assertEquals(58, t.nearest(hz(57.45), 440.0))
        assertEquals(57, t.nearest(hz(57.29), 440.0))
    }

    @Test fun bigJumpsFollowImmediately() {
        val t = NoteTracker()
        assertEquals(40, t.nearest(hz(40.0), 440.0))
        assertEquals(52, t.nearest(hz(52.1), 440.0))
    }

    @Test fun resetForgetsTheNote() {
        val t = NoteTracker()
        t.nearest(hz(57.4), 440.0)
        assertEquals(57, t.nearest(hz(57.6), 440.0))
        t.reset()
        assertEquals(58, t.nearest(hz(57.6), 440.0))
    }

    @Test fun usesCalibration() {
        assertEquals(69, NoteTracker().nearest(432.0, 432.0))
    }
}
