package com.example.core.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * A stable identifier for this handset.
 *
 * WHY THIS EXISTS AT ALL, given the app has no accounts and keeps everything local:
 * the referral programme pays real money, and the cheapest way to farm it is to
 * onboard with one SIM, redeem your own code, uninstall, reinstall, and repeat
 * with the next SIM. Nothing on the phone survives that loop — except ANDROID_ID,
 * which is reset only by a factory reset or a new user profile. That makes it the
 * one signal capable of recognising "this is the same handset coming back", which
 * is exactly what the server's one-redemption-per-device rule needs.
 *
 * PRIVACY: the raw value never leaves the device in a readable form. It is hashed
 * server-side (SHA-256, with a salt prefix) the moment it arrives and only the
 * hash is stored, so the register holds an opaque token that cannot be reversed
 * into an advertising or tracking identifier. It is used for exactly one purpose —
 * refusing a duplicate referral redemption — and for nothing else.
 */
object DeviceIdentity {

    /**
     * ANDROID_ID for this app+user+device, or null when the platform withholds it.
     *
     * A null return is handled honestly upstream: the server refuses to attribute a
     * referral it cannot device-check, rather than opening the farming hole. It does
     * NOT block onboarding or purchasing — only the referral credit.
     */
    @SuppressLint("HardwareIds")
    fun stableId(context: Context): String? {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (t: Throwable) {
            null
        }
        // Some emulators and a few OEM builds return null, a blank, or the well-known
        // buggy constant. None of those identify a handset, so treat them as absent.
        if (raw.isNullOrBlank() || raw == "9774d56d682e549c" || raw.length < 8) return null
        return raw
    }
}
