package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchPersonalizationRepositoryTest {
    @Test
    fun historyNormalizesCaseInsensitiveDuplicatesAndKeepsLatestFifteen() {
        var history = emptyList<String>()
        repeat(16) { index -> history = addSearchHistoryEntry(history, " query-$index ") }
        history = addSearchHistoryEntry(history, "QUERY-7")

        assertEquals(15, history.size)
        assertEquals("QUERY-7", history.first())
        assertEquals(1, history.count { it.equals("query-7", ignoreCase = true) })
        assertTrue("query-0" !in history)
    }

    @Test
    fun usageUsesNinetyDayWindowSevenDayHalfLifeAndStorageBounds() {
        val day = 24L * 60L * 60L * 1_000L
        val now = 100L * day
        val key = SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Channel, "channel-a")!!
        val timestamps = listOf(now, now - 7L * day, now - 90L * day, now - 91L * day, now + 6L * 60L * 1_000L)
        val normalized = normalizeSearchEntityUsage(mapOf(key.storageValue to timestamps), now)
        val rank = searchFrequentEntityRank(normalized.getValue(key.storageValue), now)

        assertEquals(listOf(now, now - 7L * day, now - 90L * day), normalized.getValue(key.storageValue))
        assertTrue(abs(rank.score - (1.0 + 0.5 + 2.0.pow(-(90.0 / 7.0)))) < 0.000001)
        assertEquals(now, rank.lastOpenedAtMillis)

        val manyKeys =
            (0..SearchUsageEntityLimit).associate { index ->
                "channel:$index" to listOf(now - index)
            }
        val bounded = normalizeSearchEntityUsage(manyKeys, now)
        assertEquals(SearchUsageEntityLimit, bounded.size)
        assertTrue("channel:${SearchUsageEntityLimit}" !in bounded)
    }

    @Test
    fun repositoryScopesHistoryAndUsageAndClearPreservesUsage() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentSearchPersonalizationRepository(store)
        val ownerA = SearchPersonalizationOwner("server-a", "user-a")
        val ownerB = SearchPersonalizationOwner("server-a", "user-b")
        val now = 1_800_000_000_000L
        val entity = SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Human, "member-a")!!

        repository.recordSuccessfulOpen(ownerA, " i18n ", entity, now)
        assertEquals(listOf("i18n"), repository.read(ownerA, now).history)
        assertEquals(emptyList(), repository.read(ownerB, now).history)

        val cleared = repository.clearHistory(ownerA, now + 1)
        assertEquals(emptyList(), cleared.history)
        assertEquals(listOf(now), cleared.usage.getValue(entity.storageValue))
        assertEquals(emptyMap(), repository.read(ownerB, now + 1).usage)
    }

    @Test
    fun dropServerRemovesOnlyThatServerOfThatAccountAndClearAllRemovesEverything() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentSearchPersonalizationRepository(store)
        val removedA = SearchPersonalizationOwner("server-gone", "user-a")
        val removedB = SearchPersonalizationOwner("server-gone", "user-b")
        val kept = SearchPersonalizationOwner("server-kept", "user-a")
        val now = 1_800_000_000_000L
        val entity = SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Channel, "channel-a")!!
        repository.recordSuccessfulOpen(removedA, "secret plan", entity, now)
        repository.recordSuccessfulOpen(removedB, "roadmap", null, now)
        repository.recordSuccessfulOpen(kept, "release", entity, now)

        // Only user-a lost the server: user-b on the same device keeps its searches there.
        repository.dropServer(" server-gone ", " user-a ", now + 1)
        assertEquals(emptyList(), repository.read(removedA, now + 1).history)
        assertEquals(emptyMap(), repository.read(removedA, now + 1).usage)
        assertEquals(listOf("roadmap"), repository.read(removedB, now + 1).history)

        repository.dropServer("server-gone", principalId = null, nowMillis = now + 1)
        assertEquals(emptyList(), repository.read(removedB, now + 1).history)
        assertEquals(listOf("release"), repository.read(kept, now + 1).history)
        assertTrue(store.getString("search:personalization:v1").orEmpty().contains("server-kept"))
        assertTrue(!store.getString("search:personalization:v1").orEmpty().contains("server-gone"))

        repository.clearAll()
        assertEquals(null, store.getString("search:personalization:v1"))
        assertEquals(emptyList(), repository.read(kept, now + 2).history)
    }

    @Test
    fun corruptOrFailingStorageIsEmptyAndNeverBlocksUpdates() = runBlocking {
        val corrupt = InMemoryRaftKeyValueStore(mapOf("search:personalization:v1" to "{not-json"))
        val owner = SearchPersonalizationOwner("server-a", "user-a")
        val now = 1_800_000_000_000L
        assertEquals(emptyList(), PersistentSearchPersonalizationRepository(corrupt).read(owner, now).history)

        val failing = ThrowingPreferenceStore()
        val repository = PersistentSearchPersonalizationRepository(failing)
        val updated = repository.recordSuccessfulOpen(owner, "query", null, now)
        assertEquals(emptyList(), updated.history)
        assertEquals(emptyList(), repository.read(owner, now).history)
    }

    @Test
    fun oldestScopeIsEvictedWithoutBorrowingAnotherOwnersData() = runBlocking {
        val repository = PersistentSearchPersonalizationRepository(InMemoryRaftKeyValueStore())
        val now = 1_800_000_000_000L
        repeat(SearchPersonalizationScopeLimit + 1) { index ->
            repository.recordSuccessfulOpen(
                owner = SearchPersonalizationOwner("server-$index", "user-$index"),
                rawQuery = "query-$index",
                entityKey = null,
                openedAtMillis = now + index,
            )
        }

        assertEquals(
            emptyList(),
            repository.read(SearchPersonalizationOwner("server-0", "user-0"), now + 100).history,
        )
        assertEquals(
            listOf("query-${SearchPersonalizationScopeLimit}"),
            repository.read(
                SearchPersonalizationOwner(
                    "server-${SearchPersonalizationScopeLimit}",
                    "user-${SearchPersonalizationScopeLimit}",
                ),
                now + 100,
            ).history,
        )
    }
}

private class ThrowingPreferenceStore : SharedPreferenceStore {
    override fun getString(key: String): String? = error("read denied")

    override fun putString(key: String, value: String?) {
        error("write full")
    }
}
