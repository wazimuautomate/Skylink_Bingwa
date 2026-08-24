package com.example.core.review

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * PLAY FLAVOUR — the real Google Play in-app review card.
 *
 * The card is rendered by the Play Store **over** this activity: the customer rates,
 * writes a comment and submits without ever leaving Skylink Bingwa, and the review lands
 * on the Play listing.
 *
 * Two honest limitations that shape every caller:
 *
 *  1. **We cannot make it appear.** Google quotas the card (roughly a few times per
 *     year per user). [launch] returning normally means "the flow finished", NOT
 *     "the card was shown" and NOT "the customer reviewed" — the API deliberately
 *     tells us nothing, so no analytics, no follow-up and no retry can be built on
 *     it. That is why [ReviewPolicy] is stricter than the quota: our one attempt has
 *     to be worth spending.
 *  2. **It only works for a Play install.** On a build the Play Store did not
 *     install, the request fails and this silently does nothing — which is exactly
 *     right, and why the direct flavour has its own implementation.
 *
 * Every failure is swallowed: a rating prompt must never be the thing that shows a
 * customer an error, and there is nothing useful to tell them anyway.
 */
object AppReviewLauncher {

    /**
     * Ask Play to show the rating card. Returns once the flow is finished (or has
     * failed), which is the caller's cue to record the attempt — a burned quota slot
     * counts either way, so we never re-ask on the assumption it "did not show".
     */
    suspend fun launch(activity: Activity): Boolean = try {
        val manager = ReviewManagerFactory.create(activity)
        val reviewInfo = manager.requestReviewFlow().await()
        if (reviewInfo == null) false else manager.launchReviewFlow(activity, reviewInfo).await() != null
    } catch (t: Throwable) {
        // No Play Store, a sideloaded install, no network, a Play Services error —
        // all of them mean "no card today", and none of them are the customer's
        // problem to hear about.
        false
    }

    /**
     * Bridge Play's Task to a coroutine. Resumes with null on failure rather than
     * throwing, so the caller has one path for "we did not get a card".
     */
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
        suspendCoroutine { continuation ->
            addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
}
