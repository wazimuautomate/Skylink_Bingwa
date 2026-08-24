package com.example.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KenyanPhoneTest {

    @Test
    fun normalises_allCommonForms_toE164() {
        val expected = "+254712345678"
        assertEquals(expected, KenyanPhone.toE164("0712345678"))
        assertEquals(expected, KenyanPhone.toE164("0712 345 678"))
        assertEquals(expected, KenyanPhone.toE164("712345678"))
        assertEquals(expected, KenyanPhone.toE164("254712345678"))
        assertEquals(expected, KenyanPhone.toE164("+254 712 345 678"))
        assertEquals(expected, KenyanPhone.toE164("0712-345-678"))
    }

    @Test
    fun normalises_zero1Series() {
        assertEquals("+254112345678", KenyanPhone.toE164("0112345678"))
    }

    @Test
    fun rejects_invalidNumbers() {
        assertNull(KenyanPhone.toE164(""))
        assertNull(KenyanPhone.toE164("0812345678"))   // 8-series is not mobile
        assertNull(KenyanPhone.toE164("071234567"))    // too short
        assertNull(KenyanPhone.toE164("07123456789"))  // too long
        assertNull(KenyanPhone.toE164("abc"))
        assertFalse(KenyanPhone.isValid("12345"))
        assertTrue(KenyanPhone.isValid("0712345678"))
    }

    @Test
    fun msisdn_hasNoPlus() {
        assertEquals("254712345678", KenyanPhone.toMsisdn("0712345678"))
        assertNull(KenyanPhone.toMsisdn("nope"))
    }

    @Test
    fun display_isGrouped() {
        assertEquals("0712 345 678", KenyanPhone.toDisplay("+254712345678"))
        assertEquals("0712 345 678", KenyanPhone.toDisplay("712345678"))
        // Unrecognised input is returned trimmed, not crashed.
        assertEquals("not-a-number", KenyanPhone.toDisplay("  not-a-number  "))
    }

    @Test
    fun masked_hidesMiddle() {
        assertEquals("0712 ••• 678", KenyanPhone.toMasked("0712345678"))
    }
}
