package com.example.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the onboarding Kenyan-phone normaliser (Phase 2). */
class OnboardingPhoneTest {

    @Test
    fun `accepts local 07 format`() {
        assertEquals("0727921038", normalizeKenyanPhone("0727921038"))
    }

    @Test
    fun `accepts local 01 format`() {
        assertEquals("0110000111", normalizeKenyanPhone("0110000111"))
    }

    @Test
    fun `strips spaces`() {
        assertEquals("0727921038", normalizeKenyanPhone("0727 921 038"))
    }

    @Test
    fun `accepts international 254 prefix`() {
        assertEquals("0727921038", normalizeKenyanPhone("254727921038"))
    }

    @Test
    fun `accepts plus 254 prefix`() {
        assertEquals("0727921038", normalizeKenyanPhone("+254 727 921 038"))
    }

    @Test
    fun `accepts nine digit form without leading zero`() {
        assertEquals("0727921038", normalizeKenyanPhone("727921038"))
    }

    @Test
    fun `rejects too short`() {
        assertNull(normalizeKenyanPhone("0727"))
    }

    @Test
    fun `rejects non-mobile prefix`() {
        assertNull(normalizeKenyanPhone("0427921038"))
    }

    @Test
    fun `rejects empty`() {
        assertNull(normalizeKenyanPhone(""))
    }
}
