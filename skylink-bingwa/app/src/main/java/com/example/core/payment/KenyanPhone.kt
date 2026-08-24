package com.example.core.payment

/**
 * Kenyan Safaricom phone-number handling (Plan.md §5.5, design.md §19.2).
 *
 * - Store the canonical E.164 form (`+2547XXXXXXXX` / `+2541XXXXXXXX`).
 * - Display a familiar grouped local form (`07XX XXX XXX`).
 *
 * Pure and dependency-free so it is unit-testable and safe to call from the
 * gateway before an STK request.
 */
object KenyanPhone {

    /**
     * Normalises `07…`, `01…`, `7…`, `1…`, `254…` and `+254…` inputs (with spaces
     * or dashes) into E.164 `+254…`. Returns `null` when the input cannot be a
     * valid 9-significant-digit Safaricom number, so callers can block STK on a
     * bad payer/recipient rather than sending Daraja a malformed MSISDN.
     */
    fun toE164(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val national: String = when {
            // 2547XXXXXXXX / 2541XXXXXXXX (12 digits)
            digits.length == 12 && digits.startsWith("254") -> digits.substring(3)
            // 07XXXXXXXX / 01XXXXXXXX (10 digits, leading 0)
            digits.length == 10 && digits.startsWith("0") -> digits.substring(1)
            // 7XXXXXXXX / 1XXXXXXXX (9 significant digits)
            digits.length == 9 -> digits
            else -> return null
        }
        // Safaricom mobile national numbers are 9 digits starting 7 or 1.
        if (national.length != 9) return null
        if (national[0] != '7' && national[0] != '1') return null
        return "+254$national"
    }

    /** True when [raw] is a usable Safaricom mobile number. */
    fun isValid(raw: String): Boolean = toE164(raw) != null

    /**
     * The Daraja `PhoneNumber` / `PartyA` MSISDN form: `2547XXXXXXXX` (no `+`).
     * Returns `null` for invalid input.
     */
    fun toMsisdn(raw: String): String? = toE164(raw)?.removePrefix("+")

    /**
     * Familiar on-screen grouping, e.g. `0712 345 678`. Falls back to the trimmed
     * input when it is not a recognisable Kenyan number, so partially-typed values
     * still render.
     */
    fun toDisplay(raw: String): String {
        val e164 = toE164(raw) ?: return raw.trim()
        val national = e164.removePrefix("+254") // 9 digits: 7XXXXXXXX
        return "0${national.substring(0, 3)} ${national.substring(3, 6)} ${national.substring(6)}"
    }

    /** Masked form for lock-screen-safe / low-emphasis contexts: `0712 ••• 678`. */
    fun toMasked(raw: String): String {
        val e164 = toE164(raw) ?: return raw.trim()
        val national = e164.removePrefix("+254")
        return "0${national.substring(0, 3)} ••• ${national.substring(6)}"
    }
}
