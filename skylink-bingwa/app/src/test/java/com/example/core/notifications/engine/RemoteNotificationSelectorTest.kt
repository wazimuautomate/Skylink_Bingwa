package com.example.core.notifications.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admin-published messages: highest priority first, only inside their window,
 * never repeated, and always still available from the cache when the device is
 * offline.
 */
class RemoteNotificationSelectorTest {

    private val now = 1_000_000L

    private fun item(
        id: String,
        priority: Int = 0,
        startsAt: Long = 0L,
        endsAt: Long = Long.MAX_VALUE,
        title: String = "Title",
        body: String = "Body"
    ) = RemoteNotification(
        id = id,
        category = "GENERAL",
        title = title,
        body = body,
        deepLinkRoute = "notifications",
        startsAt = startsAt,
        endsAt = endsAt,
        priority = priority
    )

    @Test
    fun `highest priority wins`() {
        val due = RemoteNotificationSelector.due(
            listOf(item("a", priority = 1), item("b", priority = 5), item("c", priority = 3)),
            now,
            emptySet()
        )
        assertNotNull(due)
        assertEquals("b", due!!.id)
    }

    @Test
    fun `a message that has not started yet is not due`() {
        val due = RemoteNotificationSelector.due(
            listOf(item("future", startsAt = now + 1L)),
            now,
            emptySet()
        )
        assertNull(due)
    }

    @Test
    fun `an expired message is not due`() {
        val due = RemoteNotificationSelector.due(
            listOf(item("past", endsAt = now - 1L)),
            now,
            emptySet()
        )
        assertNull(due)
    }

    @Test
    fun `the window boundaries are inclusive`() {
        assertNotNull(
            RemoteNotificationSelector.due(listOf(item("start", startsAt = now)), now, emptySet())
        )
        assertNotNull(
            RemoteNotificationSelector.due(listOf(item("end", endsAt = now)), now, emptySet())
        )
    }

    @Test
    fun `an already shown message is never repeated`() {
        val items = listOf(item("a", priority = 9), item("b", priority = 1))
        val due = RemoteNotificationSelector.due(items, now, setOf("a"))
        assertNotNull(due)
        assertEquals("b", due!!.id)
        assertNull(RemoteNotificationSelector.due(items, now, setOf("a", "b")))
    }

    @Test
    fun `an id-less or empty message is skipped`() {
        assertNull(RemoteNotificationSelector.due(listOf(item("")), now, emptySet()))
        assertNull(
            RemoteNotificationSelector.due(listOf(item("blank", title = "", body = "  ")), now, emptySet())
        )
    }

    @Test
    fun `nothing due returns null rather than a weak message`() {
        assertNull(RemoteNotificationSelector.due(emptyList(), now, emptySet()))
    }

    @Test
    fun `equal priority breaks towards the most recently started`() {
        val due = RemoteNotificationSelector.due(
            listOf(item("old", startsAt = 1L), item("new", startsAt = 500L)),
            now,
            emptySet()
        )
        assertEquals("new", due!!.id)
    }

    @Test
    fun `selection is stable for the same input`() {
        val items = listOf(item("a", priority = 2, startsAt = 10L), item("b", priority = 2, startsAt = 10L))
        val first = RemoteNotificationSelector.due(items, now, emptySet())
        val second = RemoteNotificationSelector.due(items, now, emptySet())
        assertEquals(first, second)
    }

    @Test
    fun `a null fetch keeps the cache so messages still show offline`() {
        val cached = listOf(item("cached"))
        assertEquals(cached, RemoteNotificationSelector.merge(cached, null))
        // A cached message is still selectable with no network involved.
        val due = RemoteNotificationSelector.due(
            RemoteNotificationSelector.merge(cached, null), now, emptySet()
        )
        assertNotNull(due)
        assertEquals("cached", due!!.id)
    }

    @Test
    fun `an explicit empty list from the server clears the queue`() {
        val cached = listOf(item("cached"))
        assertTrue(RemoteNotificationSelector.merge(cached, emptyList()).isEmpty())
    }

    @Test
    fun `defaults make an unspecified message immediately and indefinitely due`() {
        val bare = RemoteNotification(id = "bare", title = "Hi", body = "There")
        assertEquals(0L, bare.startsAt)
        assertEquals(Long.MAX_VALUE, bare.endsAt)
        assertEquals("GENERAL", bare.category)
        assertEquals("home", bare.deepLinkRoute)
        assertNotNull(RemoteNotificationSelector.due(listOf(bare), now, emptySet()))
    }
}
