package com.example.core.notifications.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed copy is the app's voice AND its honesty guarantee. These tests are the
 * enforcement of CLAUDE.md §7 (never claim delivery) and §8 (never claim to know
 * the customer's usage).
 */
class DefaultNotificationTemplatesTest {

    @Test
    fun `every category has at least three variants so wording never repeats`() {
        for (category in NotificationCategory.values()) {
            val templates = DefaultNotificationTemplates.forCategory(category)
            assertTrue(
                "${category.name} has only ${templates.size} seed templates",
                templates.size >= 3
            )
        }
    }

    @Test
    fun `template ids are unique`() {
        val ids = DefaultNotificationTemplates.SEED.templates.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `no seed template contains a banned claim`() {
        for (template in DefaultNotificationTemplates.SEED.templates) {
            val text = (template.title + " " + template.body).lowercase()
            for (banned in DefaultNotificationTemplates.BANNED_PHRASES) {
                assertTrue(
                    "template '${template.id}' contains banned phrase '$banned'",
                    !text.contains(banned)
                )
            }
        }
    }

    @Test
    fun `only balance-driven categories mention a balance`() {
        for (template in DefaultNotificationTemplates.SEED.templates) {
            if (!template.title.contains("{balance}") && !template.body.contains("{balance}")) continue
            val category = NotificationCategory.fromName(template.category)
            assertTrue(
                "template '${template.id}' uses {balance} on a non-balance-driven category",
                category != null && category.isBalanceDriven
            )
        }
    }

    @Test
    fun `payment copy never promises a bundle arrival`() {
        val forbidden = listOf("delivered", "activated", "confirmed", "successful")
        for (template in DefaultNotificationTemplates.forCategory(NotificationCategory.PURCHASE_SUCCESS)) {
            val text = (template.title + " " + template.body).lowercase()
            for (word in forbidden) {
                assertTrue(
                    "purchase template '${template.id}' says '$word'",
                    !text.contains(word)
                )
            }
        }
    }

    @Test
    fun `carrier copy is attributed to Safaricom`() {
        for (template in DefaultNotificationTemplates.forCategory(NotificationCategory.BUNDLE_RECEIVED)) {
            val text = template.title + " " + template.body
            assertTrue(
                "bundle template '${template.id}' must credit Safaricom",
                text.contains("Safaricom")
            )
        }
    }

    @Test
    fun `every seed template names a category this build knows`() {
        for (template in DefaultNotificationTemplates.SEED.templates) {
            assertTrue(
                "template '${template.id}' has unknown category '${template.category}'",
                NotificationCategory.fromName(template.category) != null
            )
            assertTrue("template '${template.id}' has no body", template.body.isNotBlank())
            assertTrue("template '${template.id}' has no title", template.title.isNotBlank())
            assertTrue("template '${template.id}' has no id", template.id.isNotBlank())
        }
    }

    @Test
    fun `seed version is the documented one`() {
        assertEquals(DefaultNotificationTemplates.SEED_VERSION, DefaultNotificationTemplates.SEED.version)
    }
}
