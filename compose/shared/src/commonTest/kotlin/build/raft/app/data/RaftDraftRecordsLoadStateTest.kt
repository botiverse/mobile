package build.raft.app.data

import build.raft.app.SharedThreadComposerDraftAccount
import build.raft.app.SharedThreadComposerDraftAccountStore
import build.raft.app.SharedThreadComposerDraftAccounts
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Architecture review B, site 7: the Drafts projection ([SharedThreadComposerDraftPersistSlice.activeDraftRecordsState])
 * is account-scoped, so before the account binding is known it reads nothing. An empty set then
 * is "not read yet", not "no drafts": the Drafts page must not show its empty state, and
 * drafts_page_viewed must not report 0. The slice says so with an explicit load flag.
 */
class RaftDraftRecordsLoadStateTest {
    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val draftKey = "send=channel-1|parent=_|mention=channel-1"

    private class Device(base: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore()) {
        var live: SharedThreadComposerDraftAccount? = null
        val store = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val persistence = SharedThreadComposerDraftPersistence(store)
        val slice = SharedThreadComposerDraftPersistSlice(persistence, recordsKnownAtStart = false)
        val accounts =
            SharedThreadComposerDraftAccounts(
                base = base,
                store = store,
                persistenceFor = { view -> SharedThreadComposerDraftPersistence(view) },
            )
    }

    @Test
    fun draftRecordsAreUnknownUntilTheAccountBindingIsKnown() {
        val base = InMemoryRaftKeyValueStore()
        Device(base).apply {
            live = accountA
            accounts.onAccountBinding(RaftAccountBinding.SignedIn(accountA), slice)
            persistence.save(key = draftKey, text = "unsent reply", asTask = false)
        }

        val next = Device(base)
        assertFalse(next.slice.activeDraftRecordsKnown.value, "cold start: the drafts have not been read for anyone yet")
        next.accounts.onAccountBinding(RaftAccountBinding.Unknown, next.slice)
        assertFalse(next.slice.activeDraftRecordsKnown.value)
        next.accounts.onAccountBinding(RaftAccountBinding.Corrupt("SerializationException"), next.slice)
        assertFalse(next.slice.activeDraftRecordsKnown.value, "an unreadable session has no drafts to list either way")

        next.live = accountA
        next.accounts.onAccountBinding(RaftAccountBinding.SignedIn(accountA), next.slice)
        assertTrue(next.slice.activeDraftRecordsKnown.value)
        assertEquals(setOf(draftKey), next.slice.activeDraftRecordsState.value.map { it.key }.toSet())
    }

    @Test
    fun signedOutIsAKnownEmptyAnswer() {
        val device = Device()
        device.accounts.onAccountBinding(RaftAccountBinding.SignedOut, device.slice)
        assertTrue(device.slice.activeDraftRecordsKnown.value)
    }
}
