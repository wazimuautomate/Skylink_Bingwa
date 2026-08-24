package com.example.core.notifications.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer turns a category plus what we know locally into warm, human copy.
 * It must be deterministic (so the engine is testable), never repeat itself
 * back-to-back, never print a raw placeholder, and never break when a value is
 * missing.
 */
class NotificationComposerTest {

    private val morning = TimeOfDayResolverTest.nairobi(hour = 9)

    private fun personalization(
        name: String = "James",
        bundle: String = "Sh20 = 250MB",
        amount: Int = 20,
        balance: String = "",
        recipient: String = "",
        days: Int = 0,
        categoryLabel: String = ""
    ) = NotificationPersonalization(
        userName = name,
        nowMillis = morning,
        usualBundleLabel = bundle,
        usualAmountKsh = amount,
        balanceText = balance,
        recipientLabel = recipient,
        daysSinceLastPurchase = days,
        categoryLabel = categoryLabel
    )

    private fun templateSet(vararg templates: NotificationTemplate) =
        NotificationTemplateSet(version = 99, templates = templates.toList())

    private fun template(
        id: String,
        category: NotificationCategory,
        title: String,
        body: String,
        weight: Int = 1,
        enabled: Boolean = true
    ) = NotificationTemplate(
        id = id,
        category = category.name,
        title = title,
        body = body,
        weight = weight,
        enabled = enabled
    )

    // ----- determinism ----------------------------------------------------

    @Test
    fun `same seed produces the same template every time`() {
        val first = NotificationComposer.compose(
            NotificationCategory.MORNING, personalization(),
            DefaultNotificationTemplates.SEED, seed = 12345L, lastTemplateId = null
        )
        val second = NotificationComposer.compose(
            NotificationCategory.MORNING, personalization(),
            DefaultNotificationTemplates.SEED, seed = 12345L, lastTemplateId = null
        )
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `different seeds eventually pick different templates`() {
        val picked = HashSet<String>()
        for (seed in 0L until 60L) {
            val composed = NotificationComposer.compose(
                NotificationCategory.MORNING, personalization(),
                DefaultNotificationTemplates.SEED, seed, null
            )
            if (composed != null) picked.add(composed.templateId)
        }
        assertTrue("selection should vary across seeds, got $picked", picked.size > 1)
    }

    // ----- rotation -------------------------------------------------------

    @Test
    fun `the previous template is never chosen again immediately`() {
        for (category in NotificationCategory.values()) {
            for (seed in 0L until 25L) {
                val first = NotificationComposer.compose(
                    category, personalization(), DefaultNotificationTemplates.SEED, seed, null
                )
                assertNotNull("no copy for ${category.name}", first)
                val second = NotificationComposer.compose(
                    category, personalization(), DefaultNotificationTemplates.SEED, seed, first!!.templateId
                )
                assertNotNull(second)
                assertTrue(
                    "${category.name} repeated '${first.templateId}' back-to-back",
                    second!!.templateId != first.templateId
                )
            }
        }
    }

    @Test
    fun `a single available template is reused rather than going silent`() {
        val only = templateSet(
            template("only", NotificationCategory.PROMOTION, "Deal", "{greeting}. Have a look.")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.PROMOTION, personalization(), only, seed = 1L, lastTemplateId = "only"
        )
        assertNotNull(composed)
        assertEquals("only", composed!!.templateId)
    }

    // ----- placeholders ---------------------------------------------------

    @Test
    fun `every supported placeholder is substituted`() {
        val templates = templateSet(
            template(
                "all", NotificationCategory.LOW_DATA,
                "{name}",
                "{greeting} | {bundle} | {amount} | {balance} | {recipient} | {days} | {category}"
            )
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.LOW_DATA,
            personalization(
                balance = "12MB",
                recipient = "07** *** 456",
                days = 3,
                categoryLabel = "data"
            ),
            templates,
            seed = 7L,
            lastTemplateId = null
        )
        assertNotNull(composed)
        val body = composed!!.body
        assertTrue(body, body.contains("Good morning James"))
        assertTrue(body, body.contains("Sh20 = 250MB"))
        assertTrue(body, body.contains("12MB"))
        assertTrue(body, body.contains("07** *** 456"))
        assertTrue(body, body.contains("3 days"))
        assertTrue(body, body.contains("data"))
        assertEquals("James", composed.title)
    }

    @Test
    fun `an unknown placeholder is removed instead of shown to the customer`() {
        val templates = templateSet(
            template("odd", NotificationCategory.PROMOTION, "Deal", "{greeting}. {mystery} Have a look.")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.PROMOTION, personalization(), templates, seed = 3L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertTrue(composed!!.body, !composed.body.contains("{"))
        assertTrue(composed.body, !composed.body.contains("}"))
        assertTrue(composed.body, composed.body.contains("Good morning James"))
    }

    @Test
    fun `missing values fall back instead of leaving a gap`() {
        val templates = templateSet(
            template(
                "gaps", NotificationCategory.INACTIVITY,
                "It's been a while",
                "It's been {days} since your last one. {bundle} for {amount}, for {recipient}."
            )
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.INACTIVITY,
            NotificationPersonalization(userName = "James", nowMillis = morning),
            templates,
            seed = 5L,
            lastTemplateId = null
        )
        assertNotNull(composed)
        val body = composed!!.body
        assertTrue(body, body.contains("a while"))
        assertTrue(body, body.contains("your usual bundle"))
        assertTrue(body, body.contains("a small top-up"))
        assertTrue(body, body.contains("that number"))
        assertTrue(body, !body.contains("{"))
    }

    @Test
    fun `one day reads as one day not one days`() {
        val templates = templateSet(
            template("d", NotificationCategory.INACTIVITY, "Hi", "It's been {days}.")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.INACTIVITY, personalization(days = 1), templates, seed = 2L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertEquals("It's been 1 day.", composed!!.body)
    }

    // ----- blank name -----------------------------------------------------

    @Test
    fun `blank name still reads naturally`() {
        val bodies = listOf(
            template("a", NotificationCategory.LOW_DATA, "{name}, you're almost out of data", "Hi {name}, tap here."),
            template("b", NotificationCategory.LOW_DATA, "Low", "Thanks {name}! All set."),
            template("c", NotificationCategory.LOW_DATA, "Low", "All good {name} — you're set.")
        )
        for (single in bodies) {
            val composed = NotificationComposer.compose(
                NotificationCategory.LOW_DATA,
                personalization(name = "  "),
                templateSet(single),
                seed = 1L,
                lastTemplateId = null
            )
            assertNotNull(composed)
            val text = composed!!.title + " " + composed.body
            assertTrue(text, !text.contains("{name}"))
            assertTrue("dangling comma in '$text'", !text.contains(" ,"))
            assertTrue("double space in '$text'", !text.contains("  "))
            assertTrue("leading comma in '${composed.body}'", !composed.body.startsWith(","))
            assertTrue("leading comma in '${composed.title}'", !composed.title.startsWith(","))
        }
    }

    @Test
    fun `blank name capitalises the sentence that lost its name`() {
        val templates = templateSet(
            template("cap", NotificationCategory.LOW_DATA, "Low on data", "{name}, you're almost out of data.")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.LOW_DATA, personalization(name = ""), templates, seed = 1L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertEquals("You're almost out of data.", composed!!.body)
    }

    @Test
    fun `no seed template leaves a placeholder or bad spacing for any category`() {
        for (category in NotificationCategory.values()) {
            for (name in listOf("James", "")) {
                val composed = NotificationComposer.compose(
                    category,
                    personalization(
                        name = name,
                        balance = "12MB",
                        recipient = "07** *** 456",
                        days = 2,
                        categoryLabel = "data"
                    ),
                    DefaultNotificationTemplates.SEED,
                    seed = category.ordinal.toLong(),
                    lastTemplateId = null
                )
                assertNotNull("${category.name} produced no copy", composed)
                val text = composed!!.title + " " + composed.body
                assertTrue("${category.name}: placeholder left in '$text'", !text.contains("{"))
                assertTrue("${category.name}: double space in '$text'", !text.contains("  "))
                assertTrue("${category.name}: dangling comma in '$text'", !text.contains(" ,"))
                assertTrue("${category.name}: empty body", composed.body.isNotBlank())
                assertTrue("${category.name}: empty title", composed.title.isNotBlank())
            }
        }
    }

    // ----- honesty --------------------------------------------------------

    @Test
    fun `a balance template is refused for a non balance-driven category`() {
        val templates = templateSet(
            template("sneaky", NotificationCategory.PROMOTION, "Deal", "You have {balance} left.")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.PROMOTION,
            personalization(balance = "12MB"),
            templates,
            seed = 1L,
            lastTemplateId = null
        )
        // No usable PROMOTION template remains, so it falls back to the honest seed.
        assertNotNull(composed)
        assertTrue(composed!!.templateId, composed.templateId != "sneaky")
        assertTrue(composed.body, !composed.body.contains("12MB"))
    }

    @Test
    fun `no composed seed copy contains a banned claim`() {
        for (category in NotificationCategory.values()) {
            for (seed in 0L until 12L) {
                val composed = NotificationComposer.compose(
                    category,
                    personalization(balance = "12MB", recipient = "07** *** 456", days = 4, categoryLabel = "data"),
                    DefaultNotificationTemplates.SEED,
                    seed,
                    null
                )
                if (composed == null) continue
                val text = (composed.title + " " + composed.body).lowercase()
                for (banned in DefaultNotificationTemplates.BANNED_PHRASES) {
                    assertTrue("${category.name} said '$banned'", !text.contains(banned))
                }
            }
        }
    }

    // ----- fallback and silence -------------------------------------------

    @Test
    fun `an empty set falls back to the in-APK seed`() {
        val composed = NotificationComposer.compose(
            NotificationCategory.MORNING, personalization(),
            NotificationTemplateSet(), seed = 1L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertTrue(
            composed!!.templateId,
            DefaultNotificationTemplates.SEED.templates.any { it.id == composed.templateId }
        )
    }

    @Test
    fun `disabled templates are ignored`() {
        val templates = templateSet(
            template("off", NotificationCategory.MORNING, "Hi", "Body", enabled = false)
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.MORNING, personalization(), templates, seed = 1L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertTrue(composed!!.templateId != "off")
    }

    @Test
    fun `a blank-bodied template is never posted verbatim`() {
        // Silence beats a weak message (CLAUDE.md §9): a template with no body is
        // not a candidate, so the composer falls back to real seed copy.
        val templates = templateSet(
            template("blank", NotificationCategory.GENERAL, "Title", "   ")
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.GENERAL, personalization(), templates, seed = 1L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertTrue(composed!!.templateId != "blank")
        assertTrue(composed.body.isNotBlank())
    }

    @Test
    fun `an unknown category name in a template set is never selected`() {
        val templates = NotificationTemplateSet(
            version = 99,
            templates = listOf(
                NotificationTemplate(
                    id = "from_a_newer_server",
                    category = "SOME_CATEGORY_THIS_BUILD_DOES_NOT_KNOW",
                    title = "Hi",
                    body = "Body"
                )
            )
        )
        val composed = NotificationComposer.compose(
            NotificationCategory.GENERAL, personalization(), templates, seed = 1L, lastTemplateId = null
        )
        assertNotNull(composed)
        assertTrue(composed!!.templateId != "from_a_newer_server")
        assertNull(NotificationCategory.fromName("SOME_CATEGORY_THIS_BUILD_DOES_NOT_KNOW"))
    }
}
