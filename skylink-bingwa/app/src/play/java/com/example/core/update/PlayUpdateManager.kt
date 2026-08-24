package com.example.core.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play in-app update manager for the PLAY flavor.
 *
 * Implements the immediate update flow: when a new version is published to
 * the Play Store, Google Play renders a non-dismissible full-screen modal
 * blocking the user from proceeding until the update is installed.
 */
object PlayUpdateManager {

    const val REQUEST_CODE_PLAY_UPDATE = 8099

    fun checkAndResume(activity: Activity) {
        try {
            val appUpdateManager = AppUpdateManagerFactory.create(activity)
            val appUpdateInfoTask = appUpdateManager.appUpdateInfo

            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activity,
                        AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                        REQUEST_CODE_PLAY_UPDATE
                    )
                } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    // Resume an immediate update that was in progress
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activity,
                        AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                        REQUEST_CODE_PLAY_UPDATE
                    )
                }
            }
        } catch (_: Throwable) {
            // Defensive: update check failure on unconfigured / sideloaded play builds must never crash
        }
    }
}
