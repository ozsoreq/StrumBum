package com.strumbum.app.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMathTest {
    @Test fun a4IsMidi69() = assertEquals(440.0, NoteMath.midiToHz(69), 1e-9)

    @Test fun lowE() = assertEquals(82.4069, NoteMath.midiToHz(40), 1e-4)

    @Test fun calibrationShiftsEverything() = assertEquals(432.0 / 440.0 * 82.4069, NoteMath.midiToHz(40, 432.0), 1e-4)

    @Test fun centsSign() {
        assertEquals(-100.0, NoteMath.cents(NoteMath.midiToHz(68), 440.0), 1e-9)
        assertEquals(1200.0, NoteMath.cents(880.0, 440.0), 1e-9)
    }

    @Test fun nearestMidiRoundsAtHalfSemitone() {
        assertEquals(40, NoteMath.nearestMidi(NoteMath.midiToHz(40.49)))
        assertEquals(41, NoteMath.nearestMidi(NoteMath.midiToHz(40.51)))
    }

    @Test fun names() {
        assertEquals("E2", NoteMath.noteName(40))
        assertEquals("C#3", NoteMath.noteName(49))
        assertEquals("Db3", NoteMath.noteName(49, preferFlats = true))
        assertEquals("C-1", NoteMath.noteName(0))
    }

    @Test fun presetsFitToneRange() {
        Tunings.all.flatMap { it.strings }.forEach { assert(it in Tunings.toneRange) { "missing tone $it" } }
    }

    @Test fun summaries() {
        assertEquals("E A D G B E", Tunings.STANDARD.summary)
        assertEquals("Eb Ab Db Gb Bb Eb", Tunings.byId("half_down").summary)
        assertEquals(Tunings.STANDARD, Tunings.byId("nope"))
    }
}
