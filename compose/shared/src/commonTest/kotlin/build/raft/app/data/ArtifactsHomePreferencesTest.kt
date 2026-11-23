package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * web `artifactDashboardPrefs.ts` (`readArtifactDashboardPrefs`) & `artifactDashboardFilters.ts`
 * (botiverse/slock #16137, #16153, #16182): device view/sort prefs, per-(account, server) persisted
 * filters with 16-server write-only LRU & strict validation, and per-(account, server) settled-seen
 * memory.
 */
class ArtifactsHomePreferencesTest {
    private class MemoryPreferenceStore(
        var throwOnRead: Boolean = false,
        var throwOnWrite: Boolean = false,
    ) : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? {
            if (throwOnRead) throw IllegalStateException("storage read failed")
            return values[key]
        }

        override fun putString(key: String, value: String?) {
            if (throwOnWrite) throw IllegalStateException("quota exceeded")
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun readsWhatTheWebWritesMapsLegacyTableToListAndDefaultsTheRest() {
        assertEquals(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Updated), parseArtifactsHomePrefs("""{"view":"list","widths":{"author":200},"listSort":"updated"}"""))
        assertEquals(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Opened), parseArtifactsHomePrefs("""{"view":"table"}"""))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs(null))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs("not json"))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs("""{"view":"cards","listSort":"title","order":"updated"}"""))
    }

    @Test
    fun writesAndReadsBackUnderTheWebKey() {
        val store = MemoryPreferenceStore()
        val repository = ArtifactsHomePreferenceRepository(store)
        repository.write(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Opened))
        assertEquals(setOf("raft:artifacts:dashboard:v1"), store.values.keys)
        assertEquals(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Opened), repository.read())
    }

    @Test
    fun filtersPersistAllFiveDimensionsAndAuthorActorPerAccountAndServer() {
        val store = MemoryPreferenceStore()
        val repo = ArtifactsHomeFilterRepository(store)
        val agentUuid = "11111111-1111-1111-1111-111111111111"
        val userUuid = "22222222-2222-2222-2222-222222222222"
        val actor =
            ArtifactsHomeActor(
                type = "agent",
                id = agentUuid,
                name = "remy",
                displayName = "Remy",
                avatarUrl = "https://cdn.raft.build/remy.png",
                onBehalfOf = ArtifactsHomeBehalf("user-9", "Alice"),
            )
        val conversation =
            ArtifactsHomeConversation(
                id = "conv-1",
                name = "dm-peer",
                type = "dm",
                peerDisplayName = "Remy",
                peer = ArtifactsHomeActor("user", userUuid, "remy", "Remy", avatarUrl = "pixel-human:abc"),
                count = 4,
                updatedAt = "2026-11-20T08:00:00.000Z",
                archived = false,
            )
        val saved =
            ArtifactsHomeFilters(
                search = "launch",
                place = ArtifactsHomePlace.Conversation(conversation),
                author = "agent:$agentUuid",
                authorActor = actor,
                status = ArtifactsHomeStatusFilter.All,
                relevant = true,
            )

        repo.write("reader-1", "server-1", saved)
        assertTrue(store.values.containsKey("raft:artifacts:filters:v1:reader-1:server-1"))

        // Reload from storage (clear in-memory cache).
        repo.clearMemoryOnlyForTests()
        assertEquals(saved, repo.read("reader-1", "server-1"))
        // Isolated by account and server.
        assertEquals(ArtifactsHomeFilters.Default, repo.read("reader-2", "server-1"))
        assertEquals(ArtifactsHomeFilters.Default, repo.read("reader-1", "server-2"))

        // Returning to defaults removes the storage key.
        repo.write("reader-1", "server-1", ArtifactsHomeFilters.Default)
        assertFalse(store.values.containsKey("raft:artifacts:filters:v1:reader-1:server-1"))
        repo.clearMemoryOnlyForTests()
        assertEquals(ArtifactsHomeFilters.Default, repo.read("reader-1", "server-1"))
    }

    @Test
    fun filterParserValidatesStrictlyAndClampsSearch() {
        val upperUuid = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        val lowerUuid = upperUuid.lowercase()
        val otherUuid = "99999999-9999-9999-9999-999999999999"
        val overLong = "a".repeat(ARTIFACTS_HOME_SEARCH_MAX_CHARS + 50)
        val parsed =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "search": "$overLong",
                  "place": { "kind": "home", "home": "public" },
                  "author": "user:$upperUuid",
                  "authorActor": {
                    "type": "user",
                    "id": "$lowerUuid",
                    "name": "kirby",
                    "displayName": "Kirby",
                    "avatarUrl": "https://cdn.raft.build/k.png"
                  },
                  "status": "settled",
                  "relevant": true
                }
                """.trimIndent(),
            )
        assertEquals(ARTIFACTS_HOME_SEARCH_MAX_CHARS, parsed.search.length)
        assertEquals(ArtifactsHomePlace.Home(ArtifactsHomeScope.Public), parsed.place)
        assertEquals("user:$lowerUuid", parsed.author, "UUID id is lowercased like web")
        assertEquals("Kirby", parsed.authorActor?.displayName)
        assertEquals(ArtifactsHomeStatusFilter.Settled, parsed.status)
        assertTrue(parsed.relevant)

        // Uppercase prefix ("USER:") and non-UUID author ids rejected; mismatched authorActor stripped; bad avatar schemes dropped.
        val badAuthor =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "author": "USER:$lowerUuid",
                  "authorActor": { "type": "user", "id": "$lowerUuid", "name": "k", "displayName": "K" }
                }
                """.trimIndent(),
            )
        assertNull(badAuthor.author)
        assertNull(badAuthor.authorActor)

        val nonUuidAuthor = parseStoredArtifactsHomeFilters("""{ "author": "user:not-a-uuid" }""")
        assertNull(nonUuidAuthor.author)

        val mismatched =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "author": "user:$lowerUuid",
                  "authorActor": { "type": "user", "id": "$otherUuid", "name": "other", "displayName": "Other" }
                }
                """.trimIndent(),
            )
        assertEquals("user:$lowerUuid", mismatched.author)
        assertNull(mismatched.authorActor)

        // Bad avatar URLs (protocol-relative, javascript:, data:) rejected; valid pixel-human: accepted.
        for (badUrl in listOf("//evil.example/a.png", "javascript:alert(1)", "data:image/png;base64,xx", "   ")) {
            val bad =
                parseStoredArtifactsHomeFilters(
                    """
                    {
                      "author": "user:$lowerUuid",
                      "authorActor": { "type": "user", "id": "$lowerUuid", "name": "k", "displayName": "K", "avatarUrl": "$badUrl" }
                    }
                    """.trimIndent(),
                )
            assertNull(bad.authorActor, "avatarUrl '$badUrl' should be rejected")
        }

        // Non-DM peer, float count, whitespace conversation id, invalid gone, non-agent onBehalfOf rejected.
        val nonDmPeer =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "place": {
                    "kind": "conversation",
                    "conversation": {
                      "id": "c-1",
                      "name": "general",
                      "type": "channel",
                      "count": 1,
                      "updatedAt": "2026-11-20T08:00:00.000Z",
                      "peer": { "type": "user", "id": "$lowerUuid", "name": "k", "displayName": "K" }
                    }
                  }
                }
                """.trimIndent(),
            )
        assertNull(nonDmPeer.place)

        val floatCount =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "place": {
                    "kind": "conversation",
                    "conversation": { "id": "c-1", "name": "general", "type": "channel", "count": 1.5, "updatedAt": "2026-11-20T08:00:00.000Z" }
                  }
                }
                """.trimIndent(),
            )
        assertNull(floatCount.place)

        val whitespaceId =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "place": {
                    "kind": "conversation",
                    "conversation": { "id": "   ", "name": "general", "type": "channel", "count": 1, "updatedAt": "2026-11-20T08:00:00.000Z" }
                  }
                }
                """.trimIndent(),
            )
        assertNull(whitespaceId.place)

        val userWithBehalf =
            parseStoredArtifactsHomeFilters(
                """
                {
                  "author": "user:$lowerUuid",
                  "authorActor": {
                    "type": "user",
                    "id": "$lowerUuid",
                    "name": "k",
                    "displayName": "K",
                    "onBehalfOf": { "id": "$otherUuid", "name": "alice", "displayName": "Alice" }
                  }
                }
                """.trimIndent(),
            )
        assertNull(userWithBehalf.authorActor)
    }

    @Test
    fun filterLruCapsAtSixteenServersPerReaderAndEvictsOnlyOnWrite() {
        val store = MemoryPreferenceStore()
        val repo = ArtifactsHomeFilterRepository(store)
        for (i in 1..16) {
            repo.write("reader-1", "s-$i", ArtifactsHomeFilters(search = "q$i"))
        }
        // Reading s-1 must NOT promote it in LRU (write-only LRU parity with web).
        assertEquals("q1", repo.read("reader-1", "s-1").search)

        // Writing a 17th server evicts s-1 (the oldest written).
        repo.write("reader-1", "s-17", ArtifactsHomeFilters(search = "q17"))
        assertFalse(store.values.containsKey("raft:artifacts:filters:v1:reader-1:s-1"))
        assertTrue(store.values.containsKey("raft:artifacts:filters:v1:reader-1:s-2"))
        assertTrue(store.values.containsKey("raft:artifacts:filters:v1:reader-1:s-17"))

        repo.clearMemoryOnlyForTests()
        assertEquals(ArtifactsHomeFilters.Default, repo.read("reader-1", "s-1"))
        assertEquals("q17", repo.read("reader-1", "s-17").search)
    }

    @Test
    fun filterStorageFailureFallsBackToMemoryAndShadowDefaultOverridesStaleDisk() {
        val store = MemoryPreferenceStore()
        val repo = ArtifactsHomeFilterRepository(store)
        val saved = ArtifactsHomeFilters(search = "plan", relevant = true)
        repo.write("reader-1", "s-1", saved)

        // Storage starts throwing on write: reset to default must shadow the stale disk entry in memory.
        store.throwOnWrite = true
        repo.write("reader-1", "s-1", ArtifactsHomeFilters.Default)
        assertEquals(ArtifactsHomeFilters.Default, repo.read("reader-1", "s-1"))

        // And writing a new filter while storage throws keeps it in memory.
        val inMem = ArtifactsHomeFilters(status = ArtifactsHomeStatusFilter.Settled)
        repo.write("reader-1", "s-1", inMem)
        assertEquals(inMem, repo.read("reader-1", "s-1"))
    }

    @Test
    fun dropServerAndForgetCleanUpBothFilterAndSettledSeenRepositories() {
        val store = MemoryPreferenceStore()
        val filters = ArtifactsHomeFilterRepository(store)
        val settledSeen = ArtifactsHomeSettledSeenRepository(store)

        filters.write("r-1", "s-1", ArtifactsHomeFilters(search = "a"))
        filters.write("r-1", "s-2", ArtifactsHomeFilters(search = "b"))
        filters.write("r-2", "s-1", ArtifactsHomeFilters(search = "c"))
        settledSeen.observe("r-1", "s-1", detected = true, rowsLoaded = true)
        settledSeen.observe("r-1", "s-2", detected = true, rowsLoaded = true)
        settledSeen.observe("r-2", "s-1", detected = true, rowsLoaded = true)

        filters.dropServer("s-1", readerId = "r-1")
        settledSeen.dropServer("s-1", readerId = "r-1")
        assertEquals(ArtifactsHomeFilters.Default, filters.read("r-1", "s-1"))
        assertEquals("b", filters.read("r-1", "s-2").search)
        assertEquals("c", filters.read("r-2", "s-1").search)
        assertFalse(settledSeen.read("r-1", "s-1"))
        assertTrue(settledSeen.read("r-1", "s-2"))
        assertTrue(settledSeen.read("r-2", "s-1"))

        filters.forget("r-1")
        settledSeen.forget("r-1")
        assertEquals(ArtifactsHomeFilters.Default, filters.read("r-1", "s-2"))
        assertFalse(settledSeen.read("r-1", "s-2"))
        assertEquals("c", filters.read("r-2", "s-1").search)
        assertTrue(settledSeen.read("r-2", "s-1"))
    }

    @Test
    fun settledSeenPersistsCapsAtSixteenAndClearsWhenFlagTurnsOff() {
        val store = MemoryPreferenceStore()
        val repo = ArtifactsHomeSettledSeenRepository(store)

        for (i in 1..16) {
            repo.observe("r-1", "s-$i", detected = true, rowsLoaded = true)
        }
        // Bump s-1 recency, then add s-17: s-2 should be evicted, s-1 kept.
        repo.observe("r-1", "s-1", detected = true, rowsLoaded = true)
        repo.observe("r-1", "s-17", detected = true, rowsLoaded = true)
        repo.clearMemoryOnlyForTests()
        assertTrue(repo.read("r-1", "s-1"))
        assertFalse(repo.read("r-1", "s-2"))
        assertTrue(repo.read("r-1", "s-17"))

        // Observing settled=false when rows loaded clears the server entry.
        repo.observe("r-1", "s-1", detected = false, rowsLoaded = true)
        repo.clearMemoryOnlyForTests()
        assertFalse(repo.read("r-1", "s-1"))

        // Corrupt storage falls back cleanly without throwing.
        store.values["raft:artifacts:settled-seen:v1:r-bad"] = "not-json"
        assertFalse(repo.read("r-bad", "s-1"))
    }
}
