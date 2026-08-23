package com.example.core.notifications.engine

import kotlin.random.Random

/**
 * Everything the assistant knows about this customer when it writes a line.
 *
 * All of it comes from the installation's OWN local state (profile name, local
 * purchase history) or from a real Safaricom SMS the caller parsed. Nothing here
 * is a guess about the customer's consumption (CLAUDE.md §8).
 *
 * Every field has a default so a caller can supply only what it actually knows —
 * a missing value never produces a broken sentence.
 */
data class NotificationPersonalization(
    /** First name from the local profile. Blank is fine; the copy drops the name. */
    val userName: String = "",
    val nowMillis: Long = 0L,
    /** The bundle they usually buy, e.g. "Sh20 = 250MB". From local history only. */
    val usualBundleLabel: String = "",
    /** The amount they usually spend, in KSh. From local history only. */
    val usualAmountKsh: Int = 0,
    /**
     * The remaining balance EXACTLY as Safaricom stated it. Only ever set for a
     * [NotificationCategory.isBalanceDriven] category — the composer refuses to
     * use it anywhere else.
     */
    val balanceText: String = "",
    /** Recipient label, ALREADY MASKED by the caller. Never a full phone number. */
    val recipientLabel: String = "",
    val daysSinceLastPurchase: Int = 0,
    /** "data" | "SMS" | "minutes". */
    val categoryLabel: String = ""
)

/** A finished, ready-to-post notification. */
data class ComposedNotification(
    val templateId: String,
    val title: String,
    val body: String
)

/**
 * Turns a [NotificationCategory] plus what we know about the customer into warm,
 * human copy.
 *
 * Pure Kotlin — no Android imports — so every rule below is unit-testable on the
 * JVM. Selection is deterministic for a given `seed`, and never repeats the
 * template that was used last time for the same category.
 */
object NotificationComposer {

    private val LEFTOVER_TOKEN = Regex("\\{[A-Za-z0-9_]+\\}")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Composes a notification, or returns null when there is nothing honest and
     * well-formed to say. Silence beats a weak message (CLAUDE.md §9).
     *
     * @param templates the current set (server-synced or seed).
     * @param seed deterministic randomness source — pass the current time.
     * @param lastTemplateId the template used last time for this category; it is
     *        excluded whenever another candidate exists, so the same wording
     *        never appears back-to-back.
     */
    fun compose(
        category: NotificationCategory,
        personalization: NotificationPersonalization,
        templates: NotificationTemplateSet,
        seed: Long,
        lastTemplateId: String?
    ): ComposedNotification? {
        val candidates = candidatesFor(category, templates.templates)
            .ifEmpty { candidatesFor(category, DefaultNotificationTemplates.SEED.templates) }
        if (candidates.isEmpty()) return null

        val pool = if (candidates.size > 1 && !lastTemplateId.isNullOrEmpty()) {
            val withoutLast = candidates.filter { it.id != lastTemplateId }
            if (withoutLast.isEmpty()) candidates else withoutLast
        } else {
            candidates
        }

        val chosen = pick(pool, seed) ?: return null
        val values = placeholderValues(category, personalization)
        val name = personalization.userName.trim()

        val body = render(chosen.body, name, values)
        if (body.isEmpty()) return null
        val title = render(chosen.title, name, values).ifEmpty { "My Bingwa" }

        return ComposedNotification(templateId = chosen.id, title = title, body = body)
    }

    /**
     * Enabled templates for [category] that have a body. Templates mentioning
     * `{balance}` are dropped for a category that is not balance-driven — the app
     * may only state a balance when Safaricom is the source (CLAUDE.md §8).
     */
    private fun candidatesFor(
        category: NotificationCategory,
        all: List<NotificationTemplate>
    ): List<NotificationTemplate> = all.filter { template ->
        template.enabled &&
            template.body.isNotBlank() &&
            template.category.equals(category.name, ignoreCase = true) &&
            (category.isBalanceDriven || !template.body.contains(BALANCE_TOKEN)) &&
            (category.isBalanceDriven || !template.title.contains(BALANCE_TOKEN))
    }

    /** Deterministic weighted choice. Weights below 1 count as 1. */
    private fun pick(pool: List<NotificationTemplate>, seed: Long): NotificationTemplate? {
        if (pool.isEmpty()) return null
        if (pool.size == 1) return pool[0]
        var total = 0
        for (template in pool) total += weightOf(template)
        if (total <= 0) return pool[0]
        var cursor = Random(seed).nextInt(total)
        for (template in pool) {
            cursor -= weightOf(template)
            if (cursor < 0) return template
        }
        return pool[pool.size - 1]
    }

    private fun weightOf(template: NotificationTemplate): Int =
        if (template.weight < 1) 1 else template.weight

    private fun placeholderValues(
        category: NotificationCategory,
        p: NotificationPersonalization
    ): Map<String, String> {
        val name = p.userName.trim()
        val timeOfDay = TimeOfDayResolver.resolve(p.nowMillis)
        val balance = p.balanceText.trim()
        return mapOf(
            "{greeting}" to TimeOfDayResolver.greeting(timeOfDay, name),
            "{bundle}" to p.usualBundleLabel.trim().ifEmpty { "your usual bundle" },
            "{amount}" to if (p.usualAmountKsh > 0) "Sh${p.usualAmountKsh}" else "a small top-up",
            BALANCE_TOKEN to if (category.isBalanceDriven && balance.isNotEmpty()) balance else "very little",
            "{recipient}" to p.recipientLabel.trim().ifEmpty { "that number" },
            "{days}" to daysText(p.daysSinceLastPurchase),
            "{category}" to p.categoryLabel.trim().ifEmpty { "data" }
        )
    }

    private fun daysText(days: Int): String = when {
        days <= 0 -> "a while"
        days == 1 -> "1 day"
        else -> "$days days"
    }

    /**
     * Substitutes every placeholder, removes any token we do not recognise, and
     * tidies the punctuation that a dropped value leaves behind.
     */
    private fun render(raw: String, name: String, values: Map<String, String>): String {
        if (raw.isEmpty()) return ""
        var text = raw

        // The greeting is substituted first: it already contains the name.
        text = text.replace("{greeting}", values["{greeting}"] ?: "")

        text = if (name.isEmpty()) {
            // Drop the name AND its punctuation so the line still reads naturally.
            text.replace("{name}, ", "")
                .replace(", {name}", "")
                .replace(" {name}", "")
                .replace("{name} ", "")
                .replace("{name}", "")
        } else {
            text.replace("{name}", name)
        }

        for ((token, value) in values) {
            if (token == "{greeting}") continue
            text = text.replace(token, value)
        }

        // Anything still wrapped in braces was never supported: remove it rather
        // than showing a literal "{x}" to the customer.
        text = LEFTOVER_TOKEN.replace(text, "")

        return tidy(text)
    }

    private fun tidy(raw: String): String {
        var text = WHITESPACE.replace(raw, " ")
        text = text.replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(",,", ",")
            .replace(", .", ".")
        text = text.trim()
        while (text.isNotEmpty() && (text[0] == ',' || text[0] == '.' || text[0] == '-')) {
            text = text.substring(1).trim()
        }
        text = text.trim()
        if (text.isEmpty()) return text
        val first = text[0]
        return if (first.isLetter() && first.isLowerCase()) {
            first.uppercaseChar().toString() + text.substring(1)
        } else {
            text
        }
    }

    private const val BALANCE_TOKEN = "{balance}"
}
