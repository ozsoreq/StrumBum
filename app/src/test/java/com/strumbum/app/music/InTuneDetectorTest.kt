package com.strumbum.app.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InTuneDetectorTest {
    @Test fun locksAfterHoldTimeAndFiresOnce() {
        val d = InTuneDetector(3.0, 400)
        val fired = (0..60).map { i -> d.update(if (i % 2 == 0) 2.5 else -1.0, i * 10L) }
        assertTrue(d.inTune)
        assertEquals(1, fired.count { it })
        assertEquals(40, fired.indexOf(true))
    }

    @Test fun leavingToleranceRestartsTheClock() {
        val d = InTuneDetector(3.0, 400)
        d.update(0.0, 0)
        d.update(0.0, 300)
        d.update(5.0, 350)
        assertFalse(d.update(0.0, 400))
        assertFalse(d.update(0.0, 700))
        assertTrue(d.update(0.0, 800))
    }

    @Test fun silenceKeepsLockButRestartsTheClock() {
        val d = InTuneDetector(3.0, 400)
        d.update(0.0, 0)
        d.update(0.0, 400)
        assertTrue(d.inTune)
        d.update(null, 500)
        assertTrue(d.inTune)
        d.update(4.0, 600)
        assertFalse(d.inTune)
    }
}
