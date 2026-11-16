package com.tirthankar.ascent.designsystem

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReducedMotionTest {

    @Test
    fun `only a zero animator scale means remove animations`() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(0.5f))
        assertFalse(isReducedMotion(1f))
        assertFalse(isReducedMotion(10f))
    }
}
