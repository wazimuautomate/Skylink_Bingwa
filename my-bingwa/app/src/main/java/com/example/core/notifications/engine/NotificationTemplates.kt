package com.example.core.notifications.engine

/**
 * The assistant's voice.
 *
 * Every notification My Bingwa posts is rendered from one of these templates, so
 * the copy can be refreshed from the server without shipping a new APK while a
 * complete, honest seed set always exists offline inside the APK.
 *
 * ## Supported placeholders
 *
 * The composer replaces these tokens; an unknown or unsupplied token is removed
 * safely and NEVER left as a literal `{x}`:
 *
 *  - `{name}`      the customer's first name. When it is blank the surrounding
 *                  punctuation is removed too, so the line still reads naturally
 *                  ("Hi ," never appears).
 *  - `{greeting}`  a time-of-day greeting that already includes the name, e.g.
 *                  "Good morning James" / "Still awake James?" / "Good evening".
 *  - `{bundle}`    the bundle the customer usually buys, e.g. "Sh20 = 250MB".
 *                  Falls back to "your usual bundle".
 *  - `{amount}`    the amount they usually spend, already prefixed, e.g. "Sh20".
 *                  Falls back to "a small top-up".
 *  - `{balance}`   the remaining balance EXACTLY as Safaricom stated it. Falls
 *                  back to "very little". See the honesty rule below.
 *  - `{recipient}` the recipient label, already masked by the caller.
 *                  Falls back to "that number".
 *  - `{days}`      days since the last purchase, e.g. "3 days" / "1 day".
 *                  Falls back to "a while".
 *  - `{category}`  "data" | "SMS" | "minutes". Falls back to "data".
 *
 * ## HONESTY RULES (CLAUDE.md §7 and §8) — these bind every template, seed or
 * ## server-published, and are enforced by unit tests.
 *
 * 1. My Bingwa NEVER claims it delivered a bundle. The customer app does not
 *    deliver bundles and does not verify delivery in version 1. These strings
 *    are banned anywhere in a notification: "Bundle delivered", "Data
 *    activated", "Delivery successful", "Bundle confirmed". A payment ends at
 *    "Payment received" and asks the customer to wait.
 * 2. Anything that sounds like a bundle arriving is attributed to Safaricom and
 *    only ever fires from a real carrier SMS event that the caller passes in
 *    (the [NotificationCategory.BUNDLE_RECEIVED] category).
 * 3. Balance statements ("you're almost out of data", `{balance}`) are permitted
 *    ONLY for the [NotificationCategory.isBalanceDriven] categories — LOW_DATA,
 *    VERY_LOW_DATA, NO_DATA, LOW_SMS, LOW_MINUTES — because there the carrier,
 *    not My Bingwa, is the factual source. For every other category the app must
 *    never claim to know the customer's usage. These strings are banned
 *    anywhere: "Recommended for your usage", "Based on your browsing", "You need
 *    more data", "You are running out of data". The composer additionally drops
 *    any template containing `{balance}` when the category is not balance-driven.
 * 4. Bundle suggestions come from the customer's OWN local purchase history
 *    (`{bundle}` / `{amount}`), never from a guess about their consumption.
 *
 * ## Emoji
 *
 * CLAUDE.md §6 bans emoji in the Compose UI. The owner's explicit instruction for
 * notification copy is different and newer: sparing emoji make the assistant feel
 * human. At most one emoji per message, and only in roughly half the templates.
 * Never copy this style into a Compose screen.
 */
data class NotificationTemplate(
    val id: String = "",
    /**
     * The [NotificationCategory] name as a STRING, so a server-published set that
     * names a category this build does not know can never crash the app — it is
     * simply never selected.
     */
    val category: String = "",
    val title: String = "",
    val body: String = "",
    /** Relative selection weight. Values below 1 are treated as 1. */
    val weight: Int = 1,
    val enabled: Boolean = true
)

/**
 * A whole set of templates plus its revision. The provider prefers a stored set
 * only when its [version] is higher than the in-APK seed's.
 *
 * Every field has a default so a snapshot written by an older build still
 * deserialises through Moshi reflection.
 */
data class NotificationTemplateSet(
    val version: Int = 0,
    val templates: List<NotificationTemplate> = emptyList()
)

private fun t(
    id: String,
    category: NotificationCategory,
    title: String,
    body: String,
    weight: Int = 1
): NotificationTemplate = NotificationTemplate(
    id = id,
    category = category.name,
    title = title,
    body = body,
    weight = weight,
    enabled = true
)

/**
 * The in-APK seed. Always available, always offline, at least three variants per
 * category so the assistant never repeats itself back-to-back.
 */
object DefaultNotificationTemplates {

    /** Bump when the seed copy changes; a server set must exceed this to win. */
    const val SEED_VERSION: Int = 1

    val SEED: NotificationTemplateSet = NotificationTemplateSet(
        version = SEED_VERSION,
        templates = listOf(

            // ----- Connectivity: observed locally, never a usage claim ---------
            t(
                "offline_1", NotificationCategory.OFFLINE,
                "Looks like you're offline",
                "{greeting}. Your phone can't reach the internet right now — tap here and let's fix that."
            ),
            t(
                "offline_2", NotificationCategory.OFFLINE,
                "No connection right now",
                "{name}, the network dropped. Grab {bundle} in two taps and you're back 😊"
            ),
            t(
                "offline_3", NotificationCategory.OFFLINE,
                "Still offline",
                "{greeting}. Whenever you're ready, {amount} gets you moving again."
            ),

            t(
                "online_1", NotificationCategory.ONLINE,
                "You're back online",
                "{greeting}. The connection is back 🎉 Have a look at today's offers while you're here."
            ),
            t(
                "online_2", NotificationCategory.ONLINE,
                "Welcome back",
                "{name}, the network is back. {bundle} is still waiting whenever you want it."
            ),
            t(
                "online_3", NotificationCategory.ONLINE,
                "Back on the network",
                "{greeting}. Everything is loading again — tap through for today's deals."
            ),

            // ----- Time-of-day greetings --------------------------------------
            t(
                "morning_1", NotificationCategory.MORNING,
                "{greeting}",
                "A fresh day. Today's {category} offers are ready when you are ☀️"
            ),
            t(
                "morning_2", NotificationCategory.MORNING,
                "Morning check-in",
                "{greeting}. Starting the day online? {bundle} takes two taps."
            ),
            t(
                "morning_3", NotificationCategory.MORNING,
                "{greeting}",
                "Hope today treats you well. Have a look at what's on offer this morning."
            ),

            t(
                "afternoon_1", NotificationCategory.AFTERNOON,
                "{greeting}",
                "Halfway through the day. Today's {category} offers are still here if you want them."
            ),
            t(
                "afternoon_2", NotificationCategory.AFTERNOON,
                "Afternoon check-in",
                "{greeting}. Quick one — {bundle} is two taps away 😊"
            ),
            t(
                "afternoon_3", NotificationCategory.AFTERNOON,
                "{greeting}",
                "Busy afternoon? The offers page is one tap from here."
            ),

            t(
                "evening_1", NotificationCategory.EVENING,
                "{greeting}",
                "Winding down? Tonight's offers are ready whenever you are 🌙"
            ),
            t(
                "evening_2", NotificationCategory.EVENING,
                "Evening check-in",
                "{greeting}. Plans for tonight? Grab {bundle} before things get busy."
            ),
            t(
                "evening_3", NotificationCategory.EVENING,
                "{greeting}",
                "The evening is yours. Have a look at what's on offer."
            ),

            t(
                "late_night_1", NotificationCategory.LATE_NIGHT,
                "{greeting}",
                "It's late. If you need something to keep you going, the offers are right here."
            ),
            t(
                "late_night_2", NotificationCategory.LATE_NIGHT,
                "Still up?",
                "{name}, we're open all night. {bundle} takes two taps 🌙"
            ),
            t(
                "late_night_3", NotificationCategory.LATE_NIGHT,
                "{greeting}",
                "Late one tonight? Everything you need is a tap away."
            ),

            // ----- Payment: ends at "Payment received" (CLAUDE.md §7) ----------
            t(
                "purchase_success_1", NotificationCategory.PURCHASE_SUCCESS,
                "Payment received",
                "Thanks {name}! Your payment came through. Sit tight — Safaricom will message you shortly."
            ),
            t(
                "purchase_success_2", NotificationCategory.PURCHASE_SUCCESS,
                "Payment received",
                "{greeting}. We've got your payment for {recipient} ✅ Please hold on while it's processed."
            ),
            t(
                "purchase_success_3", NotificationCategory.PURCHASE_SUCCESS,
                "Payment received",
                "All good {name} — payment received. We'll take it from here, please give it a moment."
            ),

            // ----- Balance-driven: ONLY these may talk about what's left -------
            t(
                "low_data_1", NotificationCategory.LOW_DATA,
                "{name}, you're almost out of data",
                "Safaricom says you have {balance} left. Grab {bundle} before it runs dry 😅"
            ),
            t(
                "low_data_2", NotificationCategory.LOW_DATA,
                "Almost out of data",
                "{greeting}. Your data is nearly finished. {bundle} takes two taps."
            ),
            t(
                "low_data_3", NotificationCategory.LOW_DATA,
                "Low on data",
                "{name}, looks like today's bundle is nearly done. Top up with {amount} whenever you're ready."
            ),

            t(
                "very_low_data_1", NotificationCategory.VERY_LOW_DATA,
                "{name}, your data is nearly finished",
                "Safaricom says only {balance} is left. {bundle} will keep you going."
            ),
            t(
                "very_low_data_2", NotificationCategory.VERY_LOW_DATA,
                "Very low on data",
                "{greeting}. You're down to your last bit of data — {amount} sorts it out 😅"
            ),
            t(
                "very_low_data_3", NotificationCategory.VERY_LOW_DATA,
                "Almost done",
                "{name}, that bundle is nearly finished. Grab {bundle} before you drop off."
            ),

            t(
                "no_data_1", NotificationCategory.NO_DATA,
                "{name}, your data is finished",
                "Safaricom says your bundle is done. {bundle} gets you straight back on."
            ),
            t(
                "no_data_2", NotificationCategory.NO_DATA,
                "Out of data",
                "{greeting}. Your data has finished — {amount} and you're back online."
            ),
            t(
                "no_data_3", NotificationCategory.NO_DATA,
                "No data left",
                "{name}, that was the last of it. Tap here and pick a bundle 😊"
            ),

            t(
                "low_sms_1", NotificationCategory.LOW_SMS,
                "{name}, your SMS are nearly done",
                "Safaricom says you have {balance} left. An SMS bundle takes two taps."
            ),
            t(
                "low_sms_2", NotificationCategory.LOW_SMS,
                "Low on SMS",
                "{greeting}. Your texts are almost finished — {amount} tops them back up."
            ),
            t(
                "low_sms_3", NotificationCategory.LOW_SMS,
                "Nearly out of SMS",
                "{name}, only a few texts left. Grab {bundle} whenever you're ready 😊"
            ),

            t(
                "low_minutes_1", NotificationCategory.LOW_MINUTES,
                "{name}, your minutes are nearly done",
                "Safaricom says you have {balance} left. Top up before your next call."
            ),
            t(
                "low_minutes_2", NotificationCategory.LOW_MINUTES,
                "Low on minutes",
                "{greeting}. Your talk time is almost finished — {amount} keeps you talking."
            ),
            t(
                "low_minutes_3", NotificationCategory.LOW_MINUTES,
                "Nearly out of minutes",
                "{name}, that call used nearly the last of it. Grab {bundle} whenever you're ready."
            ),

            // ----- Carrier events: always attributed to Safaricom --------------
            t(
                "bundle_received_1", NotificationCategory.BUNDLE_RECEIVED,
                "Safaricom messaged you",
                "{name}, Safaricom just sent you an update about your bundle. Tap to see it."
            ),
            t(
                "bundle_received_2", NotificationCategory.BUNDLE_RECEIVED,
                "Update from Safaricom",
                "{greeting}. Safaricom has messaged you about your {category}. Have a look 😊"
            ),
            t(
                "bundle_received_3", NotificationCategory.BUNDLE_RECEIVED,
                "Message from Safaricom",
                "{name}, there's a new message from Safaricom about your bundle. Tap to check."
            ),

            t(
                "gift_received_1", NotificationCategory.GIFT_RECEIVED,
                "Someone thought of you",
                "{name}, a bundle was bought for {recipient}. Tap to see the details 🎁"
            ),
            t(
                "gift_received_2", NotificationCategory.GIFT_RECEIVED,
                "A bundle for {recipient}",
                "{greeting}. A purchase was made for {recipient}. Safaricom will message when it's ready."
            ),
            t(
                "gift_received_3", NotificationCategory.GIFT_RECEIVED,
                "Something for you",
                "{name}, someone bought a bundle for {recipient}. Tap to view it in your activity."
            ),

            // ----- Promotions --------------------------------------------------
            t(
                "promotion_1", NotificationCategory.PROMOTION,
                "Today's deal",
                "{greeting}. There's a fresh offer up today — worth a look 🔥"
            ),
            t(
                "promotion_2", NotificationCategory.PROMOTION,
                "New offer",
                "{name}, we've added something new to the offers page. Tap to see it."
            ),
            t(
                "promotion_3", NotificationCategory.PROMOTION,
                "Deal of the day",
                "{greeting}. Today's offers are live. Have a look before they change."
            ),

            // ----- Admin-published general news --------------------------------
            t(
                "general_1", NotificationCategory.GENERAL,
                "From My Bingwa",
                "{greeting}. We've got a short update for you — tap to read it."
            ),
            t(
                "general_2", NotificationCategory.GENERAL,
                "A quick note",
                "{name}, there's news from My Bingwa. Tap to have a look."
            ),
            t(
                "general_3", NotificationCategory.GENERAL,
                "News from My Bingwa",
                "{greeting}. Something new to share with you 📣"
            ),

            // ----- Purchase-history driven, never usage driven -----------------
            t(
                "inactivity_1", NotificationCategory.INACTIVITY,
                "It's been a while",
                "{greeting}. It's been {days} since your last bundle. Everything still works the same 😊"
            ),
            t(
                "inactivity_2", NotificationCategory.INACTIVITY,
                "We've missed you",
                "{name}, it's been {days}. Your usual, {bundle}, is still here."
            ),
            t(
                "inactivity_3", NotificationCategory.INACTIVITY,
                "Still here for you",
                "{greeting}. It's been {days} since we last saw you. Tap in whenever you need us."
            ),

            t(
                "habit_1", NotificationCategory.HABIT_REMINDER,
                "Your usual?",
                "{greeting}. {bundle} is the one you normally pick — two taps and it's done."
            ),
            t(
                "habit_2", NotificationCategory.HABIT_REMINDER,
                "Same as last time?",
                "{name}, {bundle} is ready to go whenever you are 😊"
            ),
            t(
                "habit_3", NotificationCategory.HABIT_REMINDER,
                "Quick rebuy",
                "{greeting}. {amount} gets you the same bundle you usually pick."
            )
        )
    )

    /** The seed templates for one category. Empty only if a category is unseeded. */
    fun forCategory(category: NotificationCategory): List<NotificationTemplate> =
        SEED.templates.filter { it.category.equals(category.name, ignoreCase = true) }

    /**
     * Strings no notification may ever contain (CLAUDE.md §7/§8). Compared
     * case-insensitively by the composer's tests.
     */
    val BANNED_PHRASES: List<String> = listOf(
        "bundle delivered",
        "data activated",
        "delivery successful",
        "bundle confirmed",
        "recommended for your usage",
        "based on your browsing",
        "you need more data",
        "you are running out of data"
    )
}
