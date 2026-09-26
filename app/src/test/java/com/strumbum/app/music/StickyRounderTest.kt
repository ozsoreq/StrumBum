package com.strumbum.app.music

import org.junit.Assert.assertEquals
import org.junit.Test

class StickyRounderTest {
    @Test fun roundsNormallyAtFirst() {
        assertEquals(13, StickyRounder().round(12.6))
        assertEquals(12, StickyRounder().round(12.4))
    }

    @Test fun ignoresWobbleAroundTheHalf() {
        val r = StickyRounder(marginCents = 0.3)
        assertEquals(12, r.round(12.4))
        listOf(12.6, 12.45, 12.7, 12.55, 11.3).forEach { assertEquals(12, r.round(it)) }
        assertEquals(13, r.round(12.85))
        assertEquals(13, r.round(12.3))
        assertEquals(12, r.round(12.1))
    }

    @Test fun resetStartsOver() {
        val r = StickyRounder()
        r.round(12.4)
        r.reset()
        assertEquals(13, r.round(12.6))
    }
}
