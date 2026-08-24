package com.example.core.review

import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord

/**
 * When it is fair to ask a customer to rate Skylink Bingwa.
 *
 * Pure and clock-injected so it is unit tested without Android or Play services.
 *
 * Three rules, and each is there for a reason:
 *
 *  - **After a real success, never before.** The prompt only follows a payment the
 *    customer actually received. Asking someone who has only browsed — or worse,
 *    someone whose payment just failed — is how apps earn one-star reviews.
 *  - **Not on the first purchase.** [MIN_SUCCESSFUL_PURCHASES] means the customer
 *    has come back at least once, so they have an opinion worth writing down.
 *  - **At most once every [MIN_DAYS_BETWEEN_PROMPTS] days.** Google quotas the real
 *    card anyway, but the quota is invisible to us: the API never says whether it
 *    displayed. Keeping our own, stricter limit is what guarantees the one time it
 *    does appear lands on a happy customer rather than being burned early.
 *
 * Deliberately NOT here: any "Do you like the app?" question before the card.
 * Google's policy forbids pre-asking and only launching the flow for people who say
 * yes — the card asks, and everyone who is eligible sees the same one.
 */
object ReviewPolicy {

    /** The customer must have completed at least this many purchases. */
    const val MIN_SUCCESSFUL_PURCHASES = 1

    /** Never prompt twice inside this many days. */
    const val MIN_DAYS_BETWEEN_PROMPTS = 30

    private const val DAY_MILLIS = 86_400_000L

    /**
     * How long to wait after the purchase lands before showing the card.
     *
     * Right after paying, the phone is busy: M-Pesa's own SMS arrives, a caller-ID
     * app summarises it, Safaricom confirms the bundle. Putting a rating card into
     * that pile-up means it is dismissed without being read. A few seconds is enough
     * for the noise to pass, and short enough that the customer is still on the
     * screen where the good thing just happened.
     */
    const val SETTLE_DELAY_MILLIS = 3_000L

    /**
     * True when [nowMillis] is a fair moment to show the rating card.
     *
     * @param purchases this installation's Activity.
     * @param lastPromptMillis when we last launched the flow (0 = never).
     */
    fun shouldPrompt(
        purchases: List<PurchaseRecord>,
        lastPromptMillis: Long,
        nowMillis: Long
    ): Boolean {
        val successful = purchases.count { it.status == PaymentStatus.RECEIVED }
        if (successful < MIN_SUCCESSFUL_PURCHASES) return false

        if (lastPromptMillis <= 0L) return true
        // A clock that has moved backwards (manual change, timezone edit) must not
        // unlock the prompt — treat any non-forward gap as "too soon".
        val elapsed = nowMillis - lastPromptMillis
        if (elapsed < 0L) return false
        return elapsed >= MIN_DAYS_BETWEEN_PROMPTS * DAY_MILLIS
    }
}
