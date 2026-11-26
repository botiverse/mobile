package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftReactionFavoritesTest {
    private class MemoryPreferenceStore : SharedPreferenceStore {
        val values = linkedMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class ThrowingPreferenceStore : SharedPreferenceStore {
        override fun getString(key: String): String? = error("SecurityError")

        override fun putString(key: String, value: String?) = error("QuotaExceededError")
    }

    @Test
    fun pickFavoriteDefaultsToThumbsUpAndBreaksTiesByMostRecent() {
        assertEquals(RaftDefaultFavoriteReaction, pickFavoriteReaction(emptyMap()))
        assertEquals(
            "😂",
            pickFavoriteReaction(
                mapOf(
                    "👍" to RaftReactionUsageEntry(count = 2, lastUsedAt = 5L),
                    "😂" to RaftReactionUsageEntry(count = 3, lastUsedAt = 1L),
                )
            ),
        )
        assertEquals(
            "🔥",
            pickFavoriteReaction(
                mapOf(
                    "😂" to RaftReactionUsageEntry(count = 3, lastUsedAt = 1L),
                    "🔥" to RaftReactionUsageEntry(count = 3, lastUsedAt = 2L),
                )
            ),
        )
    }

    @Test
    fun addReactionUsageIncrementsAndEvictsLeastUsedOldestPastLimit() {
        var usage: RaftReactionUsage = emptyMap()
        usage = addReactionUsage(usage, "😂", 1L)
        usage = addReactionUsage(usage, "😂", 2L)
        assertEquals(
            mapOf("😂" to RaftReactionUsageEntry(count = 2, lastUsedAt = 2L)),
            usage,
        )

        val full = linkedMapOf<String, RaftReactionUsageEntry>()
        for (i in 0 until RaftReactionUsageLimit) {
            full["e$i"] = RaftReactionUsageEntry(count = 2, lastUsedAt = i.toLong())
        }
        val capped = addReactionUsage(full, "new", 100L)
        assertEquals(RaftReactionUsageLimit, capped.size)
        assertNull(capped["e0"], "least-used oldest other emoji should be evicted")
        assertEquals(RaftReactionUsageEntry(count = 1, lastUsedAt = 100L), capped["new"])

        val incrementedExisting = addReactionUsage(full, "e0", 100L)
        assertEquals(RaftReactionUsageLimit, incrementedExisting.size)
        assertEquals(RaftReactionUsageEntry(count = 3, lastUsedAt = 100L), incrementedExisting["e0"])
    }

    @Test
    fun encodeAndDecodeRoundTripAndTolerateCorruption() {
        val original =
            linkedMapOf(
                "👍" to RaftReactionUsageEntry(count = 2, lastUsedAt = 10L),
                "🔥" to RaftReactionUsageEntry(count = 5, lastUsedAt = 99L),
            )
        val encoded = encodeReactionUsage(original)
        assertEquals<RaftReactionUsage>(original, decodeReactionUsage(encoded))

        assertTrue(decodeReactionUsage(null).isEmpty())
        assertTrue(decodeReactionUsage("").isEmpty())
        assertTrue(decodeReactionUsage("{not valid json").isEmpty())
        assertTrue(decodeReactionUsage("[1, 2, 3]").isEmpty())
        assertTrue(decodeReactionUsage("42").isEmpty())

        val partiallyCorrupt =
            """{"👍":{"count":2,"lastUsedAt":10},"badZero":{"count":0,"lastUsedAt":5},""" +
                """"badNeg":{"count":-1,"lastUsedAt":5},"badStr":{"count":"3","lastUsedAt":5},""" +
                """"badMissing":{"count":2},"badObj":"oops","   ":{"count":9,"lastUsedAt":99}}"""
        assertEquals(
            mapOf("👍" to RaftReactionUsageEntry(count = 2, lastUsedAt = 10L)),
            decodeReactionUsage(partiallyCorrupt),
        )
    }

    @Test
    fun storeIsolatesCountsPerUserAndPersistsAcrossFreshCache() {
        val prefs = MemoryPreferenceStore()
        var now = 100L
        val store = RaftReactionFavoriteStore(storeProvider = { prefs }, nowEpochMillis = { ++now })

        repeat(3) { store.recordReactionUsage("https://api.example|user-1", "😂") }
        store.recordReactionUsage("https://api.example|user-2", "🔥")

        assertEquals("😂", store.currentFavoriteReaction("https://api.example|user-1"))
        assertEquals("🔥", store.currentFavoriteReaction("https://api.example|user-2"))
        assertEquals(RaftDefaultFavoriteReaction, store.currentFavoriteReaction("https://api.example|user-3"))
        assertTrue(prefs.values.containsKey(raftReactionUsageStorageKey("https://api.example|user-1")))
        assertTrue(prefs.values.containsKey(raftReactionUsageStorageKey("https://api.example|user-2")))
        assertFalse(prefs.values.containsKey(raftReactionUsageStorageKey("https://api.example|user-3")))

        // Simulating a cold launch with a fresh cache reloads persisted counts from SharedPreferenceStore.
        store.clearCache()
        assertEquals("😂", store.currentFavoriteReaction("https://api.example|user-1"))
        assertEquals("🔥", store.currentFavoriteReaction("https://api.example|user-2"))
    }

    @Test
    fun throwingStoreFallsBackToThumbsUpAndStillLearnsInMemory() {
        var now = 10L
        val store =
            RaftReactionFavoriteStore(
                storeProvider = { ThrowingPreferenceStore() },
                nowEpochMillis = { ++now },
            )
        assertEquals(RaftDefaultFavoriteReaction, store.currentFavoriteReaction("user-1"))
        store.recordReactionUsage("user-1", "😂")
        store.recordReactionUsage("user-1", "😂")
        store.recordReactionUsage("user-1", "🔥")
        assertEquals("😂", store.currentFavoriteReaction("user-1"))
    }

    @Test
    fun blankOwnerOrEmojiIsIgnoredSafely() {
        val prefs = MemoryPreferenceStore()
        val store = RaftReactionFavoriteStore(storeProvider = { prefs }, nowEpochMillis = { 1L })

        assertEquals(RaftDefaultFavoriteReaction, store.currentFavoriteReaction(null))
        assertEquals(RaftDefaultFavoriteReaction, store.currentFavoriteReaction("   "))
        assertTrue(store.recordReactionUsage(null, "😂").isEmpty())
        assertTrue(store.recordReactionUsage("   ", "😂").isEmpty())
        assertTrue(store.recordReactionUsage("user-1", "   ").isEmpty())
        assertTrue(prefs.values.isEmpty())
    }
}
