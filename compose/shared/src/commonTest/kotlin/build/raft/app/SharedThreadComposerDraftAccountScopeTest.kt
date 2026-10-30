package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.SharedPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V-102 (web parity botiverse/slock#11995): composer drafts belong to the signed-in account. */
class SharedThreadComposerDraftAccountScopeTest {
    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val accountB = SharedThreadComposerDraftAccount("https://api.raft.build", "user-b")
    private val accountAOnStaging = SharedThreadComposerDraftAccount("https://staging.raft.build", "user-a")
    private val draftKey = "send=channel-1|parent=_|mention=channel-1"

    private class Harness(
        val base: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore(),
        val scheduler: ManualScheduler = ManualScheduler()
    ) {
        var live: SharedThreadComposerDraftAccount? = null
        val retired = mutableListOf<Set<String>>()
        val store = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        private fun facade(view: SharedPreferenceStore) =
            SharedThreadComposerDraftPersistence(view, retireTemporaryFileRefs = { _, refs -> retired += refs })
        val persistence = facade(store)
        val slice = SharedThreadComposerDraftPersistSlice(persistence, scheduler = scheduler)
        val accounts =
            SharedThreadComposerDraftAccounts(base = base, store = store, persistenceFor = ::facade)

        /** What the auth binding follower does when the binding becomes [account]. */
        fun bind(account: SharedThreadComposerDraftAccount?) {
            live = account
            accounts.onAccountBinding(account, slice)
        }

        fun composer(): SharedThreadComposerDraftStore =
            SharedThreadComposerDraftStore(persistSlices = listOf(slice))
    }

    @Test
    fun twoAccountsNeverSeeEachOthersDrafts() {
        val h = Harness()
        h.bind(accountA)
        h.persistence.save(key = draftKey, text = "A's unsent text", asTask = false)
        assertEquals("A's unsent text", h.persistence.read(draftKey)?.text)

        // Another account bound without its own draft: nothing to read, and A's text is not
        // offered by a fresh composer either.
        h.live = accountB
        h.store.rebind(accountB)
        assertNull(h.persistence.read(draftKey))
        val composerB = h.composer()
        composerB.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "b"), draftKey))
        assertEquals("", composerB.state.value.seedText)
        h.persistence.save(key = draftKey, text = "B's text", asTask = false)

        h.live = accountA
        h.store.rebind(accountA)
        assertEquals("A's unsent text", h.persistence.read(draftKey)?.text)

        // Same user id on another environment is another account.
        h.live = accountAOnStaging
        h.store.rebind(accountAOnStaging)
        assertNull(h.persistence.read(draftKey))
    }

    @Test
    fun nothingIsReadOrWrittenWhileSignedOutOrWhileTheLiveAccountIsNotTheBoundOne() {
        val h = Harness()
        assertNull(h.persistence.read(draftKey))
        h.persistence.save(key = draftKey, text = "typed while signed out", asTask = false)
        h.live = accountA
        assertNull(h.persistence.read(draftKey))

        h.persistence.save(key = draftKey, text = "A", asTask = false)
        // Someone else signed in; the follower has not rebound yet: fail closed.
        h.live = accountB
        assertNull(h.persistence.read(draftKey))
        h.persistence.save(key = draftKey, text = "leaks?", asTask = false)
        h.live = accountA
        assertEquals("A", h.persistence.read(draftKey)?.text)
    }

    @Test
    fun signOutDeletesTheAccountsDraftsAndTemporaryFiles() {
        val h = Harness()
        h.bind(accountA)
        val pasted =
            SharedThreadComposerAttachment(
                id = "pasted",
                fileName = "pasted.png",
                mimeType = "image/png",
                status = "ready",
                fileRef = "cache/pasted.png",
                source = "composer_image_compressor"
            )
        h.persistence.save(key = draftKey, text = "secret", asTask = false, uploadedAttachments = listOf(pasted))
        h.persistence.save(key = "send=dm-1|parent=_|mention=dm-1", text = "dm secret", asTask = false)
        assertEquals(2, h.persistence.activeDraftRecords().size)

        h.bind(null)

        assertTrue(h.retired.flatten().contains("cache/pasted.png"), "temporary file retired: ${h.retired}")
        assertEquals(emptySet(), h.slice.activeDraftRecordsState.value)
        assertNull(h.base.getString(SharedThreadComposerDraftAccountKeys.OwnersKey))
        // Signing back in as the same account finds nothing: the drafts left the device.
        h.bind(accountA)
        assertNull(h.persistence.read(draftKey))
        assertNull(h.persistence.read("send=dm-1|parent=_|mention=dm-1"))
        assertNull(
            h.base.getString(
                SharedThreadComposerDraftAccountKeys.scopedKey(
                    accountA,
                    SharedThreadComposerDraftPersistence.ActiveIndexKey
                )
            )
        )
    }

    @Test
    fun aPendingDebouncedWriteIsDroppedOnSignOutAndNeverLandsInTheNextAccount() {
        val h = Harness()
        h.bind(accountA)
        val composer = h.composer()
        composer.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "a"), draftKey))
        composer.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "half-typed"))

        h.bind(null)
        h.bind(accountB)
        h.scheduler.runAll()
        // The composer that was open for A keeps living (e.g. a page not yet torn down).
        composer.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "still A"))
        composer.dispatch(SharedThreadComposerDraftIntent.Flush)
        h.scheduler.runAll()

        assertNull(h.persistence.read(draftKey))
        h.live = accountA
        h.store.rebind(accountA)
        assertNull(h.persistence.read(draftKey))
    }

    @Test
    fun accountSwitchDeletesThePreviousAccountsDrafts() {
        val h = Harness()
        h.bind(accountA)
        h.persistence.save(key = draftKey, text = "A", asTask = false)

        h.bind(accountB)

        h.live = accountA
        h.store.rebind(accountA)
        assertNull(h.persistence.read(draftKey))
    }

    @Test
    fun leftoverRowsOfAnotherAccountAreDeletedWhenAnAccountIsBound() {
        // A's sign-out cleanup never ran (process died): binding B removes A's rows.
        val base = InMemoryRaftKeyValueStore()
        Harness(base).apply {
            bind(accountA)
            persistence.save(key = draftKey, text = "A", asTask = false)
        }
        val next = Harness(base)
        next.bind(accountB)
        assertNull(
            base.getString(
                SharedThreadComposerDraftAccountKeys.scopedKey(
                    accountA,
                    SharedThreadComposerDraftPersistence.DraftStoragePrefix + draftKey + ":text"
                )
            )
        )
        assertEquals(null, base.getString(SharedThreadComposerDraftAccountKeys.OwnersKey))
    }

    @Test
    fun legacyUnscopedDraftsMoveToTheAccountSignedInAtFirstRun() {
        val base = InMemoryRaftKeyValueStore()
        // A draft as an older version wrote it: unscoped rows over the raw store.
        SharedThreadComposerDraftPersistence(base).save(key = draftKey, text = "from 1.17", asTask = true)

        val h = Harness(base)
        h.live = accountA
        // The first composer read (before the follower ran) already sees it.
        val composer = h.composer()
        composer.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "a"), draftKey))
        assertEquals("from 1.17", composer.state.value.seedText)
        assertTrue(composer.state.value.seedAsTask)
        h.accounts.onAccountBinding(accountA, h.slice)

        SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).forEach { row -> assertNull(base.getString(row)) }
        assertNull(base.getString(SharedThreadComposerDraftPersistence.ActiveIndexKey))
        assertEquals("from 1.17", h.persistence.read(draftKey)?.text)

        // It is A's now: B does not get it.
        h.bind(accountB)
        assertNull(h.persistence.read(draftKey))
    }

    @Test
    fun legacyUnscopedDraftsAreDeletedWhenNobodyIsSignedInAtFirstRun() {
        val base = InMemoryRaftKeyValueStore()
        val pasted =
            SharedThreadComposerAttachment(
                id = "pasted",
                fileName = "pasted.png",
                mimeType = "image/png",
                status = "ready",
                fileRef = "cache/legacy.png",
                source = "composer_image_compressor"
            )
        SharedThreadComposerDraftPersistence(base)
            .save(key = draftKey, text = "from 1.17", asTask = false, uploadedAttachments = listOf(pasted))

        val h = Harness(base)
        h.accounts.onNoStoredSessionAtStart()

        assertTrue(h.retired.flatten().contains("cache/legacy.png"), "temporary file retired: ${h.retired}")
        SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).forEach { row -> assertNull(base.getString(row)) }
        assertNull(base.getString(SharedThreadComposerDraftPersistence.ActiveIndexKey))
        h.bind(accountB)
        assertNull(h.persistence.read(draftKey))
    }

    @Test
    fun draftSurvivesComposerViewModelRecreationForTheSameAccount() {
        val h = Harness()
        h.bind(accountA)
        val first = h.composer()
        first.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c"), draftKey))
        first.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "keep me"))
        h.scheduler.runAll()

        // The VM is gone (rotation, back-and-forth); the same binding is re-delivered.
        h.accounts.onAccountBinding(accountA, h.slice)
        val recreated = h.composer()
        recreated.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c"), draftKey))

        assertEquals("keep me", recreated.state.value.seedText)
    }

    private class ManualScheduler : SharedThreadComposerDraftPersistenceScheduler {
        private val tasks = mutableListOf<Pair<() -> Unit, BooleanArray>>()

        override fun schedule(
            delayMillis: Long,
            block: () -> Unit
        ): SharedThreadComposerDraftPersistenceCancellation {
            val cancelled = BooleanArray(1)
            tasks += block to cancelled
            return SharedThreadComposerDraftPersistenceCancellation { cancelled[0] = true }
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { (block, cancelled) -> if (!cancelled[0]) block() }
        }
    }
}
