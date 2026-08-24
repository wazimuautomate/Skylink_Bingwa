package com.example.data.sync

/**
 * The shared vocabulary of the incremental sync engine.
 *
 * Skylink Bingwa is offline-first (CLAUDE.md §5): the server only FEEDS content and
 * configuration; once a resource has been synced the app keeps working with no
 * internet at all. Nothing in this file touches Android, the network or disk — these
 * are plain value types so the planning rules stay unit-testable on the JVM.
 *
 * Every field carries a default because these types are persisted with Moshi
 * REFLECTION (no codegen/KSP is configured for this module). A document written by an
 * older build, or by a server that has not learned about a newer field yet, must still
 * deserialise cleanly instead of throwing.
 */

/**
 * One independently versioned slice of shared application content.
 *
 * The enum NAME is the wire key used in [SyncManifest.resources] and in the persisted
 * [com.example.data.sync.SyncMetadata], so renaming a constant is a breaking change.
 * A key the server sends that does not match any constant here is IGNORED, never a
 * crash — the planner only ever iterates the constants this build knows.
 *
 * User behaviour (purchase history, favourites, recent recipients, personal
 * suggestions) is deliberately NOT represented here. The server is the source of truth
 * for configuration; the device is the source of truth for behaviour, and the sync
 * engine has no route by which it could overwrite the latter.
 */
enum class SyncResource {
    /** Seller config: Till/Paybill route, support details, offline instructions. */
    CONFIG,

    /** The offer catalogue. */
    OFFERS,

    /** Home billboards / promotions. */
    BILLBOARDS,

    /** Notification wording templates the on-device composer picks from. */
    NOTIFICATION_TEMPLATES,

    /** Admin-published in-app notifications (content, not sends). */
    REMOTE_NOTIFICATIONS
}

/**
 * The server's fingerprint of one resource.
 *
 * [version] and [checksum] are BOTH compared before a resource is skipped, so a
 * server that forgets to bump a version, or one that reuses a version after a
 * rollback, still triggers a re-download. [updatedAt] is epoch millis and is advisory
 * only (debug/diagnostics) — it never drives the skip decision, so a device with a
 * wrong clock cannot get stuck on stale content.
 */
data class ResourceVersion(
    val version: Long = 0L,
    val updatedAt: Long = 0L,
    val checksum: String = ""
)

/**
 * The tiny document every online client polls. It must stay small: the force-sync
 * watcher fetches it roughly every 90 seconds while the app is in the foreground.
 *
 * [publishVersion] is the admin console's release revision. When it changes, the owner
 * pressed Publish — for example because the displayed Paybill or Till number stopped
 * working — and every online device must pick the change up within minutes, with no
 * Play Store update, reinstall, manual refresh or cache clear.
 *
 * [resources] is keyed by [SyncResource.name]. Unknown keys from a future server are
 * ignored rather than rejected, so an older APK keeps syncing what it does understand.
 */
data class SyncManifest(
    val publishVersion: Long = 0L,
    val generatedAt: Long = 0L,
    val resources: Map<String, ResourceVersion> = emptyMap()
)

/**
 * Why a sync run was started. Each trigger has its own throttle window (see
 * [SyncPlanner.minIntervalMillis]) so battery and bandwidth are not wasted
 * (CLAUDE.md §11).
 */
enum class SyncTrigger {
    /** Process/app start. */
    APP_START,

    /** The device regained connectivity. */
    CONNECTIVITY_RESTORED,

    /** The WorkManager background job. */
    PERIODIC,

    /** The customer explicitly pulled to refresh — never throttled. */
    MANUAL_REFRESH,

    /** The app returned to the foreground after a spell in the background. */
    APP_RESUME,

    /** The manifest's publishVersion changed: the admin published — never throttled. */
    FORCE_PUBLISH
}

/**
 * The decision: exactly which resources this run will download, and a short
 * machine-readable [reason] used by tests and debug logging (never shown to the
 * customer). An empty [resources] list is the normal, desirable outcome — it means
 * nothing changed, so nothing was downloaded.
 */
data class SyncPlan(
    val resources: List<SyncResource> = emptyList(),
    val reason: String = ""
)

/**
 * What actually happened. A [failed] resource keeps its previously stored local
 * version, so it will simply be retried next time; its cached content is never
 * cleared. Old content beats no content.
 *
 * [manifestAvailable] is false when the manifest endpoint could not be reached or the
 * server predates it — the engine then falls back to syncing everything (subject to
 * the trigger throttle) rather than stopping.
 */
data class SyncOutcome(
    val synced: List<SyncResource> = emptyList(),
    val skipped: List<SyncResource> = emptyList(),
    val failed: List<SyncResource> = emptyList(),
    val manifestAvailable: Boolean = false
)

/**
 * Fetches the sync manifest. Returns null on ANY failure — offline, HTTP error,
 * malformed body, or a server old enough not to have the endpoint — in which case the
 * planner falls back to "sync everything, subject to throttle". Mirrors
 * [com.example.data.catalogue.RemoteBillboardSource]: a null result must never be
 * treated as "there is no content".
 */
interface RemoteSyncManifestSource {
    suspend fun fetch(): SyncManifest?
}
