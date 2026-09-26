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
        d.update(6.0, 600)
        assertFalse(d.inTune)
    }

    @Test fun lockHasHysteresis() {
        val d = InTuneDetector(3.0, 400, releaseCents = 5.0)
        d.update(2.0, 0)
        assertTrue(d.update(2.0, 400))
        // Wobbling across 3 cents neither drops the lock nor fires it again.
        val fired = (1..50).map { i -> d.update(if (i % 2 == 0) 2.8 else 4.5, 400L + i * 10) }
        assertTrue(d.inTune)
        assertEquals(0, fired.count { it })
        d.update(5.5, 1000)
        assertFalse(d.inTune)
        // Re-entering needs the tight tolerance and the hold time again.
        assertFalse(d.update(4.0, 1010))
        assertFalse(d.update(2.0, 1020))
        assertTrue(d.update(2.0, 1420))
    }

    @Test fun centeredFollowsTheBandWithoutTheHold() {
        val d = InTuneDetector(3.0, 400, releaseCents = 5.0)
        d.update(4.0, 0)
        assertFalse(d.centered)
        d.update(2.9, 10)
        assertTrue(d.centered)
        assertFalse(d.inTune)
        d.update(4.9, 20)
        assertTrue(d.centered)
        d.update(null, 30)
        assertTrue(d.centered)
        d.update(-5.1, 40)
        assertFalse(d.centered)
        d.update(-4.0, 50)
        assertFalse(d.centered)
        d.update(0.0, 60)
        d.reset()
        assertFalse(d.centered)
    }

    @Test fun nanCountsAsNoPitch() {
        val d = InTuneDetector(3.0, 400)
        d.update(0.0, 0)
        assertFalse(d.update(Double.NaN, 400))
        assertFalse(d.inTune)
        assertFalse(d.update(0.0, 500))
        assertTrue(d.update(0.0, 900))
    }
}
