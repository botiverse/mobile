package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.SyncTestPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device's storage of what was open beside each conversation (RaftBesideMemoryRepository.kt), after the web's
 * tests/besideMemory.test.ts (botiverse/slock staging 51c1f706a): per person, server and conversation, bounded, and
 * nothing unreadable or thrown reaches the caller.
 */
class RaftBesideMemoryRepositoryTest {
    private val preferences = SyncTestPreferences()
    private val memory = RaftBesideMemoryRepository(preferences, nowMillis = { 0L })
    private val key = raftBesideMemoryKey("u1", "s1")

    private fun thread(id: String) = RaftBesideState(thread = id)

    private fun threadInFront(id: String) = RaftBesideState(thread = id, front = RaftBesideKind.Thread)

    @Test
    fun rememberedPerPersonServerAndConversationAndReadBack() {
        memory.write("u1", "s1", "c1", RaftBesideState(thread = "m1", front = RaftBesideKind.Thread), atMillis = 10)
        val human = RaftBesideProfile(RaftBesideProfileKind.Human, "h1")
        memory.write("u1", "s2", "c1", RaftBesideState(profile = human), atMillis = 11)
        memory.write("u2", "s1", "c1", RaftBesideState(artifact = RaftBesideArtifact("pt_a")), atMillis = 12)

        assertEquals(threadInFront("m1"), memory.read("u1", "s1", "c1"))
        assertEquals(RaftBesideState(profile = human, front = RaftBesideKind.Profile), memory.read("u1", "s2", "c1"))
        assertEquals(RaftBesideState(artifact = RaftBesideArtifact("pt_a"), front = RaftBesideKind.Artifact), memory.read("u2", "s1", "c1"))
        assertNull(memory.read("u1", "s1", "c2"))
        assertNull(memory.read("u3", "s1", "c1"), "someone else signing in reads none of it")
        assertEquals(mapOf("c1" to RaftBesideStoredEntry(threadInFront("m1"), at = 10)), decodeRaftBesideMemory(preferences.getString(key)))
    }

    @Test
    fun nothingOpenIsRememberedAsNothingAndTheLastConversationTakesTheEntryWithIt() {
        memory.write("u1", "s1", "c1", thread("m1"), atMillis = 1)
        memory.write("u1", "s1", "c2", thread("m2"), atMillis = 2)
        memory.write("u1", "s1", "c1", RaftBesideState(), atMillis = 3)
        assertNull(memory.read("u1", "s1", "c1"))
        assertEquals(threadInFront("m2"), memory.read("u1", "s1", "c2"))

        memory.write("u1", "s1", "c2", RaftBesideState(), atMillis = 4)
        assertNull(preferences.getString(key))
        assertNull(preferences.getString(RAFT_BESIDE_MEMORY_INDEX_KEY))

        val untouched = SyncTestPreferences()
        RaftBesideMemoryRepository(untouched, nowMillis = { 0L }).write("u1", "s1", "c3", RaftBesideState(), atMillis = 5)
        assertTrue(untouched.values.isEmpty(), "nothing to forget writes nothing")
    }

    @Test
    fun noOneSignedInNoServerOrAnIdThatIsNoIdReadsAndWritesNothing() {
        memory.write(null, "s1", "c1", thread("m1"))
        memory.write("u1", null, "c1", thread("m1"))
        memory.write("u1", "s1", "c:1", thread("m1"))
        memory.write("u:1", "s1", "c1", thread("m1"))
        assertTrue(preferences.values.isEmpty())
        assertNull(memory.read(null, "s1", "c1"))
    }

    @Test
    fun atMost200ConversationsAreKeptTheLeastRecentlyWrittenDropped() {
        for (index in 0 until RAFT_BESIDE_MEMORY_MAX_CONVERSATIONS + 5) {
            memory.write("u1", "s1", "c$index", thread("m$index"), atMillis = index.toLong())
        }
        // Writing an old one again makes it recent.
        memory.write("u1", "s1", "c5", thread("again"), atMillis = 1_000)
        memory.write("u1", "s1", "new", thread("m"), atMillis = 1_001)

        assertEquals(RAFT_BESIDE_MEMORY_MAX_CONVERSATIONS, decodeRaftBesideMemory(preferences.getString(key)).size)
        assertNull(memory.read("u1", "s1", "c0"))
        assertNull(memory.read("u1", "s1", "c4"))
        assertNull(memory.read("u1", "s1", "c6"), "the new one dropped the then oldest")
        assertEquals(threadInFront("again"), memory.read("u1", "s1", "c5"))
        assertEquals(threadInFront("m204"), memory.read("u1", "s1", "c204"))
        val entries = mapOf("a" to 1L, "b" to 3L, "c" to 2L).mapValues { (_, at) -> RaftBesideStoredEntry(thread("m"), at) }
        assertEquals(setOf("b", "c"), raftPruneBesideEntries(entries, max = 2).keys)
    }

    @Test
    fun malformedDataAndAnotherVersionReadAsNothingAndTheNextWriteReplacesThem() {
        val unreadable =
            listOf("not json", "[]", "null", """{"v":2,"conversations":{"c1":{"thread":"m1","at":1}}}""", """{"v":1,"conversations":[]}""")
        for (raw in unreadable) {
            preferences.putString(key, raw)
            assertNull(memory.read("u1", "s1", "c1"), raw)
        }
        preferences.putString(
            key,
            """{"v":1,"conversations":{"c1":{"thread":7,"at":"x"},"bad id":{"thread":"m1","at":1},"c2":{"thread":"m2"}}}""",
        )
        assertNull(memory.read("u1", "s1", "c1"))
        assertEquals(threadInFront("m2"), memory.read("u1", "s1", "c2"))

        memory.write("u1", "s1", "c3", thread("m3"), atMillis = 9)
        assertEquals(
            mapOf("c2" to RaftBesideStoredEntry(threadInFront("m2"), at = 0), "c3" to RaftBesideStoredEntry(threadInFront("m3"), at = 9)),
            decodeRaftBesideMemory(preferences.getString(key)),
        )
    }

    @Test
    fun aStorageThatThrowsNeverThrowsHere() {
        val broken =
            RaftBesideMemoryRepository(
                object : SharedPreferenceStore {
                    override fun getString(key: String): String? = error("blocked")

                    override fun putString(key: String, value: String?) {
                        error("full")
                    }
                },
                nowMillis = { 0L },
            )
        broken.write("u1", "s1", "c1", thread("m1"))
        assertNull(broken.read("u1", "s1", "c1"))
        broken.dropServer("s1", "u1")
    }

    @Test
    fun losingAServerDropsItsEntriesOnly() {
        memory.write("u1", "s1", "c1", thread("m1"))
        memory.write("u1", "s2", "c1", thread("m1"))
        memory.write("u10", "s1", "c1", thread("m1"))
        preferences.putString("slock:conversationWidth", "500")

        memory.dropServer("s1", "u1")
        assertNull(memory.read("u1", "s1", "c1"))
        assertEquals(threadInFront("m1"), memory.read("u1", "s2", "c1"), "the person's other servers keep theirs")
        assertEquals(threadInFront("m1"), memory.read("u10", "s1", "c1"), "no one else's entry goes")
        assertEquals("500", preferences.getString("slock:conversationWidth"))

        // Removed from the server with no account known: every person's entry there goes.
        memory.dropServer("s1", accountId = null)
        assertNull(memory.read("u10", "s1", "c1"))
        assertEquals(threadInFront("m1"), memory.read("u1", "s2", "c1"))
    }
}
