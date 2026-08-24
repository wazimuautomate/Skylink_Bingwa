package com.example.core.notifications.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider decides which copy the app speaks. A failed or stale sync must
 * never leave the customer with worse copy than the APK already contains, and it
 * must never wipe a cache that is keeping the app working offline.
 */
class NotificationTemplateProviderTest {

    private fun set(version: Int, id: String = "server_1") = NotificationTemplateSet(
        version = version,
        templates = listOf(
            NotificationTemplate(
                id = id,
                category = NotificationCategory.PROMOTION.name,
                title = "Server title",
                body = "Server body"
            )
        )
    )

    private class FakeSource(private val result: NotificationTemplateSet?) : RemoteNotificationTemplateSource {
        var calls = 0
        override suspend fun fetch(): NotificationTemplateSet? {
            calls++
            return result
        }
    }

    private class ThrowingSource : RemoteNotificationTemplateSource {
        override suspend fun fetch(): NotificationTemplateSet? = throw IllegalStateException("no network")
    }

    @Test
    fun `with nothing cached the in-APK seed is used`() = runTest {
        val provider = NotificationTemplateProvider(InMemoryNotificationTemplateStore())
        assertEquals(DefaultNotificationTemplates.SEED, provider.current())
    }

    @Test
    fun `a higher-version stored set wins`() = runTest {
        val newer = set(DefaultNotificationTemplates.SEED_VERSION + 1)
        val provider = NotificationTemplateProvider(InMemoryNotificationTemplateStore(newer))
        assertEquals(newer, provider.current())
    }

    @Test
    fun `an equal or older stored set never downgrades the seed`() = runTest {
        for (version in listOf(0, DefaultNotificationTemplates.SEED_VERSION)) {
            val provider = NotificationTemplateProvider(InMemoryNotificationTemplateStore(set(version)))
            assertEquals(DefaultNotificationTemplates.SEED, provider.current())
        }
    }

    @Test
    fun `an empty stored set never silences the app`() = runTest {
        val empty = NotificationTemplateSet(version = 999, templates = emptyList())
        val provider = NotificationTemplateProvider(InMemoryNotificationTemplateStore(empty))
        assertEquals(DefaultNotificationTemplates.SEED, provider.current())
    }

    @Test
    fun `a newer set is cached and then used`() = runTest {
        val store = InMemoryNotificationTemplateStore()
        val provider = NotificationTemplateProvider(store)
        val newer = set(DefaultNotificationTemplates.SEED_VERSION + 5)

        assertTrue(provider.syncFrom(FakeSource(newer)))
        assertEquals(newer, store.load())
        assertEquals(newer, provider.current())
    }

    @Test
    fun `a null fetch keeps the cache`() = runTest {
        val cached = set(DefaultNotificationTemplates.SEED_VERSION + 2, id = "cached")
        val store = InMemoryNotificationTemplateStore(cached)
        val provider = NotificationTemplateProvider(store)

        assertFalse(provider.syncFrom(FakeSource(null)))
        assertEquals(cached, store.load())
        assertEquals(cached, provider.current())
    }

    @Test
    fun `an empty fetch never wipes the cache`() = runTest {
        val cached = set(DefaultNotificationTemplates.SEED_VERSION + 2, id = "cached")
        val store = InMemoryNotificationTemplateStore(cached)
        val provider = NotificationTemplateProvider(store)

        assertFalse(provider.syncFrom(FakeSource(NotificationTemplateSet(version = 999, templates = emptyList()))))
        assertEquals(cached, store.load())
    }

    @Test
    fun `a stale fetch does not replace a newer cache`() = runTest {
        val cached = set(10, id = "cached")
        val store = InMemoryNotificationTemplateStore(cached)
        val provider = NotificationTemplateProvider(store)

        assertFalse(provider.syncFrom(FakeSource(set(10, id = "same_version"))))
        assertFalse(provider.syncFrom(FakeSource(set(9, id = "older"))))
        assertEquals(cached, store.load())
    }

    @Test
    fun `a throwing source is survivable`() = runTest {
        val cached = set(10, id = "cached")
        val store = InMemoryNotificationTemplateStore(cached)
        val provider = NotificationTemplateProvider(store)

        assertFalse(provider.syncFrom(ThrowingSource()))
        assertEquals(cached, store.load())
    }

    @Test
    fun `the source is consulted exactly once per sync`() = runTest {
        val source = FakeSource(set(DefaultNotificationTemplates.SEED_VERSION + 1))
        NotificationTemplateProvider(InMemoryNotificationTemplateStore()).syncFrom(source)
        assertEquals(1, source.calls)
    }
}
