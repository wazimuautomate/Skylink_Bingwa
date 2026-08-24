package com.example.core.review

import android.app.Activity
import com.example.core.update.openPlayStoreListing

/**
 * DIRECT FLAVOUR — the honest fallback.
 *
 * Google's in-app review card only works when the Play Store installed the app, so
 * on the GitHub/direct APK it can never be shown: the request simply fails. Rather
 * than pretend, this opens the Play listing, where the customer can leave the same
 * review.
 *
 * It does take them out of the app, which is why it is bound by the same
 * [ReviewPolicy] as the real card — at most once every 60 days, and only after a
 * customer has actually received two bundles. A rating request is worth one
 * interruption every couple of months; it is not worth more than that.
 */
object AppReviewLauncher {

    /**
     * Open the Play listing so the customer can rate and comment there. Returns true
     * when the listing was opened; false when the device has neither the Play Store
     * nor a browser, in which case nothing at all happens — a rating request must
     * never surface an error.
     */
    suspend fun launch(activity: Activity): Boolean = try {
        openPlayStoreListing(activity)
        true
    } catch (t: Throwable) {
        false
    }
}
