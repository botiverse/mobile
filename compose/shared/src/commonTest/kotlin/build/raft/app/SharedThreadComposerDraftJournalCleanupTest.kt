package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-time removal of the crash-journal rows that internal 1.27 test builds (#4955 / #4987) left
 * behind. The key names below are that layout as those builds wrote it, spelled out on purpose.
 */
class SharedThreadComposerDraftJournalCleanupTest {
    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val accountB = SharedThreadComposerDraftAccount("https://staging.raft.build", "user-b")
    private val draftOne = "send=channel-1|parent=_|mention=channel-1"
    private val draftTwo = "send=channel-2|parent=thread-9|mention=channel-2"

    private fun scope(account: SharedThreadComposerDraftAccount) = "threadComposerDraft:v2:${account.storageToken}:"

    /** What a journaling build left: rows listed in each scope's journal index, plus one listed only as an active draft. */
    private fun leftovers(base: SharedPreferenceStore) {
        base.putString(SharedThreadComposerDraftAccountKeys.OwnersKey, listOf(accountA.storageToken, accountB.storageToken).joinToString("\n"))
        // Unscoped (pre-V-102 facade layout).
        base.putString("threadComposerDraft:v1:journalKeys", draftOne)
        base.putString("threadComposerDraft:v1:$draftOne:journal", "j2;1;1;1;0;1;1;xy")
        // Account A: two journaled drafts in the index; a third row whose index write never landed but
        // whose draft is in the active index.
        base.putString("${scope(accountA)}journalKeys", "$draftOne\n$draftTwo\n")
        base.putString("${scope(accountA)}$draftOne:journal", "j2;5;2;7;0;45;3;${draftOne}abc")
        base.putString("${scope(accountA)}$draftTwo:journal", """{"key":"$draftTwo","seq":1,"generation":1,"process":1,"text":"legacy json","asTask":false}""")
        base.putString("${scope(accountA)}activeKeys", "$draftOne\nsend=channel-3|parent=_|mention=channel-3")
        base.putString("${scope(accountA)}send=channel-3|parent=_|mention=channel-3:journal", "j2;9;1;7;0;1;1;zq")
        // Account B: an index whose rows are already gone.
        base.putString("${scope(accountB)}journalKeys", draftTwo)
    }

    @Test
    fun removesEveryJournalRowAndIndexOnceAndKeepsTheDrafts() {
        val base = InMemoryRaftKeyValueStore()
        val view = SharedThreadComposerDraftAccountStore(base, liveAccount = { accountA })
        val persistence = SharedThreadComposerDraftPersistence(view)
        persistence.save(key = draftOne, text = "the real draft", asTask = true)
        leftovers(base)
        val log = mutableListOf<String>()

        val deleted = SharedThreadComposerDraftJournalCleanup.runOnce(base) { log += it }

        // 4 rows (v1 one, A's two listed, A's active-only one) + 3 indexes (v1, A, B).
        assertEquals(7, deleted)
        listOf(
            "threadComposerDraft:v1:journalKeys",
            "threadComposerDraft:v1:$draftOne:journal",
            "${scope(accountA)}journalKeys",
            "${scope(accountA)}$draftOne:journal",
            "${scope(accountA)}$draftTwo:journal",
            "${scope(accountA)}send=channel-3|parent=_|mention=channel-3:journal",
            "${scope(accountB)}journalKeys",
        ).forEach { key -> assertNull(base.getString(key), key) }
        assertEquals(listOf("thread_composer_draft_journal_cleanup deleted=7"), log)
        assertEquals("1", base.getString(SharedThreadComposerDraftJournalCleanup.DoneKey))

        // The normal draft rows and indexes are untouched: the draft restores as it was saved.
        assertEquals("the real draft", persistence.read(draftOne)?.text)
        assertEquals(true, persistence.read(draftOne)?.asTask)
        assertEquals(
            listOf(accountA.storageToken, accountB.storageToken).joinToString("\n"),
            base.getString(SharedThreadComposerDraftAccountKeys.OwnersKey),
        )
        val composer = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence)))
        composer.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "after-cleanup"), draftOne))
        assertEquals("the real draft", composer.state.value.seedText)
    }

    @Test
    fun aSecondLaunchOnlyReadsTheDoneMarker() {
        val base = InMemoryRaftKeyValueStore()
        leftovers(base)
        assertEquals(7, SharedThreadComposerDraftJournalCleanup.runOnce(base))
        val recorded = RecordingStore(base)

        assertEquals(0, SharedThreadComposerDraftJournalCleanup.runOnce(recorded))

        assertEquals(listOf(SharedThreadComposerDraftJournalCleanup.DoneKey), recorded.reads)
        assertEquals(emptyList<String>(), recorded.writes)
    }

    @Test
    fun aDeviceThatNeverJournaledOnlyGetsTheDoneMarker() {
        val base = InMemoryRaftKeyValueStore()
        val recorded = RecordingStore(base)

        assertEquals(0, SharedThreadComposerDraftJournalCleanup.runOnce(recorded))

        assertEquals(listOf(SharedThreadComposerDraftJournalCleanup.DoneKey), recorded.writes)
        assertEquals("1", base.getString(SharedThreadComposerDraftJournalCleanup.DoneKey))
    }

    @Test
    fun aFailedCleanupIsLoggedAndRunsAgainOnTheNextLaunch() {
        val base = InMemoryRaftKeyValueStore()
        leftovers(base)
        var failing = true
        val flaky =
            object : SharedPreferenceStore {
                override fun getString(key: String): String? = base.getString(key)

                override fun putString(key: String, value: String?) {
                    if (failing && key.endsWith(":journal")) error("disk full")
                    base.putString(key, value)
                }
            }
        val log = mutableListOf<String>()

        assertEquals(0, SharedThreadComposerDraftJournalCleanup.runOnce(flaky) { log += it })

        assertEquals(listOf("thread_composer_draft_journal_cleanup_failed deleted=0 error=IllegalStateException"), log)
        assertNull(base.getString(SharedThreadComposerDraftJournalCleanup.DoneKey))
        assertTrue(base.getString("${scope(accountA)}$draftOne:journal") != null)

        failing = false
        assertEquals(7, SharedThreadComposerDraftJournalCleanup.runOnce(flaky))
        assertNull(base.getString("${scope(accountA)}$draftOne:journal"))
        assertEquals("1", base.getString(SharedThreadComposerDraftJournalCleanup.DoneKey))
    }

    private class RecordingStore(private val inner: SharedPreferenceStore) : SharedPreferenceStore {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()

        override fun getString(key: String): String? {
            reads += key
            return inner.getString(key)
        }

        override fun putString(key: String, value: String?) {
            writes += key
            inner.putString(key, value)
        }
    }
}
