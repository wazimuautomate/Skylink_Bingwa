package com.example.data.config

import com.example.BuildConfig

/**
 * Seller details that can change over time and are therefore synced from the
 * server (Till, Paybill, support contacts). They are FETCHED when online but always
 * available OFFLINE from a cache/defaults — the server is only for syncing, the app
 * always has a usable copy (Phase 6).
 */
data class AppConfig(
    val tillNumber: String,
    val paybillNumber: String,
    val supportNumber: String,
    val supportWhatsapp: String
) {
    companion object {
        /**
         * The seller numbers bundled in the APK at build time
         * (`SEED_*` in build.gradle.kts) — the values that were current when this
         * version was built.
         *
         * These used to be blank, on the reasoning that the owner sets them in the
         * admin and the app syncs them. In the field that meant a customer whose
         * first sync had not landed — a weak connection, a slow first launch — saw an
         * empty Support page and offline instructions that refused to show a number
         * to pay, and the numbers appeared or vanished depending purely on whether a
         * sync had succeeded. A store that sells things has to be able to tell you
         * where to pay before it has talked to anything.
         *
         * This is a FLOOR, not the truth: the first successful sync replaces it, the
         * synced copy is cached and preferred forever after, and the admin remains
         * the only place a number is changed. A stale bundled number is corrected by
         * the next sync; a blank one could never be corrected offline at all.
         */
        val DEFAULT = AppConfig(
            tillNumber = BuildConfig.SEED_TILL_NUMBER,
            paybillNumber = BuildConfig.SEED_PAYBILL_NUMBER,
            supportNumber = BuildConfig.SEED_SUPPORT_NUMBER,
            supportWhatsapp = BuildConfig.SEED_SUPPORT_WHATSAPP
        )
    }
}

/**
 * Source of [AppConfig]. [cached] always returns something usable (last sync or
 * defaults) so the app never blocks offline; [fetch] pulls a fresh copy from the
 * server and persists it. Pure interface so the repository stays Android-free and
 * testable; the Android implementation ([com.example.data.config.AndroidRemoteConfigSource])
 * uses SharedPreferences + Retrofit.
 */
interface RemoteConfigSource {
    /** Last synced config, or the baked-in defaults. Never blocks, never fails. */
    fun cached(): AppConfig

    /** Fetch + persist a fresh config when online; returns null on any failure. */
    suspend fun fetch(): AppConfig?
}
