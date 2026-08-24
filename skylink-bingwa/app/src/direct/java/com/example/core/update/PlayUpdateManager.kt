package com.example.core.update

import android.app.Activity

/**
 * Direct/GitHub flavor stub for PlayUpdateManager.
 *
 * The direct flavor uses GitHub update checks for dev/debug builds.
 */
object PlayUpdateManager {
    const val REQUEST_CODE_PLAY_UPDATE = 8099

    @Suppress("UNUSED_PARAMETER")
    fun checkAndResume(activity: Activity) {
        // No-op for direct flavour
    }
}
