package build.raft.app.data

import build.raft.app.SharedThreadComposerDraftAccount
import build.raft.app.SharedThreadComposerDraftAccountKeys
import build.raft.app.SharedThreadComposerDraftAccountStore
import build.raft.app.SharedThreadComposerDraftAccounts
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Architecture review B ("unknown vs none"): a session that is not known yet, or cannot be read, is
 * not a sign-out. Each risk below deleted or skipped user data on main; only an explicit sign-out
 * (no stored session, typed `Cleared`) may clear data, and that path keeps clearing it.
 */
class RaftSessionUnknownVsSignedOutTest {

    // --- Risk 1: cold start with an undecodable stored session deleted every account's drafts ---

    private val accountA = SharedThreadComposerDraftAccount("https://api.raft.build", "user-a")
    private val draftKey = "send=channel-1|parent=_|mention=channel-1"

    private class DraftDevice(val base: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore()) {
        var live: SharedThreadComposerDraftAccount? = null
        val store = SharedThreadComposerDraftAccountStore(base, liveAccount = { live })
        val persistence = SharedThreadComposerDraftPersistence(store)
        val slice = SharedThreadComposerDraftPersistSlice(persistence)
        val accounts =
            SharedThreadComposerDraftAccounts(
                base = base,
                store = store,
                persistenceFor = { view -> SharedThreadComposerDraftPersistence(view) },
            )

        fun signIn(account: SharedThreadComposerDraftAccount) {
            live = account
            accounts.onAccountBinding(account, slice)
        }

        fun storedText(account: SharedThreadComposerDraftAccount, key: String): String? =
            base.getString(
                SharedThreadComposerDraftAccountKeys.scopedKey(
                    account,
                    SharedThreadComposerDraftPersistence.DraftStoragePrefix + key + ":text",
                )
            )
    }

    private fun draftTypedBeforeProcessDeath(): InMemoryRaftKeyValueStore {
        val device = DraftDevice()
        device.signIn(accountA)
        device.persistence.save(key = draftKey, text = "unsent reply", asTask = false)
        assertEquals("unsent reply", device.storedText(accountA, draftKey))
        return device.base
    }

    @Test
    fun coldStartWithUndecodableStoredSessionKeepsEveryAccountsDrafts() = runBlocking {
        val drafts = draftTypedBeforeProcessDeath()
        // Next process: the session record is there but this build cannot decode it.
        val authStore =
            KeyValueAuthSessionStore(InMemoryRaftKeyValueStore(mapOf(RAFT_AUTH_SESSION_RECORD_KEY to "{\"user\":")))

        val next = DraftDevice(drafts)
        next.accounts.onStoredSessionReadAtStart(authStore)

        assertEquals(
            "unsent reply",
            next.storedText(accountA, draftKey),
            "an unreadable session is not a sign-out: drafts must stay until the user signs in again",
        )
        assertTrue(accountA.storageToken in next.store.owners())
        // Signing in again as the same account finds the draft.
        next.signIn(accountA)
        assertEquals("unsent reply", next.persistence.read(draftKey)?.text)
    }

    @Test
    fun coldStartWithNoStoredSessionStillDeletesLeftoverDrafts() = runBlocking {
        val drafts = draftTypedBeforeProcessDeath()
        val authStore = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())

        val next = DraftDevice(drafts)
        next.accounts.onStoredSessionReadAtStart(authStore)

        assertNull(next.storedText(accountA, draftKey))
        assertEquals(emptySet(), next.store.owners())
    }

    @Test
    fun keyValueStoreTellsAnUndecodableSessionApartFromNoSession() = runBlocking {
        val corrupt =
            KeyValueAuthSessionStore(InMemoryRaftKeyValueStore(mapOf(RAFT_AUTH_SESSION_RECORD_KEY to "{\"user\":")))
        assertTrue(corrupt.readSessionState() is RaftAuthSessionState.Corrupt)
        assertNull(corrupt.readSession())
        assertEquals(RaftAuthSessionState.SignedOut, KeyValueAuthSessionStore(InMemoryRaftKeyValueStore()).readSessionState())
    }

    @Test
    fun unknownAndCorruptBindingsNeverDeleteDrafts() {
        val drafts = draftTypedBeforeProcessDeath()
        val next = DraftDevice(drafts)
        next.accounts.onAccountBinding(RaftAccountBinding.Unknown, next.slice)
        next.accounts.onAccountBinding(RaftAccountBinding.Corrupt("SerializationException"), next.slice)
        assertEquals("unsent reply", next.storedText(accountA, draftKey))

        next.live = accountA
        next.accounts.onAccountBinding(RaftAccountBinding.SignedIn(accountA), next.slice)
        assertEquals("unsent reply", next.persistence.read(draftKey)?.text)
        // A corrupt read while A is bound changes nothing either.
        next.accounts.onAccountBinding(RaftAccountBinding.Corrupt("SerializationException"), next.slice)
        assertEquals("unsent reply", next.storedText(accountA, draftKey))

        // Sign-out keeps deleting them.
        next.live = null
        next.accounts.onAccountBinding(RaftAccountBinding.SignedOut, next.slice)
        assertNull(next.storedText(accountA, draftKey))
    }

    @Test
    fun accountBindingTransitionPublishesCorruptAndNeverRetiresOnIt() {
        val context = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        val binding = MutableStateFlow<RaftAccountBinding<ProcessPresenceAuthContext>>(RaftAccountBinding.Unknown)
        val retired = mutableListOf<ProcessPresenceAuthContext>()
        val transition =
            processAccountBindingTransition(
                presenceAuthContext = context,
                accountBinding = binding,
                registry = ProcessMemberPresenceRegistry(),
                onBindingRetired = { retired += it },
                currentEnvironmentId = { "env-1" },
            )
        val signedIn =
            RealtimeAuthRevision(
                generation = 2L,
                session = RealtimeAuthoritySession(principalId = "user-1", serverIds = listOf("s"), environmentId = "env-test"),
            )

        transition(RealtimeAuthRevision(generation = 1L, session = null), RaftAuthSessionState.Corrupt("SerializationException"))
        assertEquals(RaftAccountBinding.Corrupt("SerializationException"), binding.value)

        transition(signedIn, RaftAuthSessionState.SignedIn(signedInSession("token-1")))
        assertEquals("user-1", binding.value.accountOrNull?.accountId)

        transition(RealtimeAuthRevision(generation = 3L, session = null), RaftAuthSessionState.Corrupt("SerializationException"))
        assertEquals(emptyList(), retired)
        assertEquals("user-1", context.value?.accountId)

        transition(RealtimeAuthRevision(generation = 4L, session = null), RaftAuthSessionState.SignedOut)
        assertEquals(listOf("user-1"), retired.map { it.accountId })
        assertEquals(RaftAccountBinding.SignedOut, binding.value)
        assertNull(context.value)
    }

    // --- Risks 2 and 3: one session read that came back empty was treated as a sign-out ---

    private class FlakyTypedAuthSessionStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
        private var session: StoredAuthSession? = null
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)
        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        /** Reads that come back empty although the session is still stored (a transient read failure). */
        var absentReads = 0

        override suspend fun readSession(): StoredAuthSession? {
            if (absentReads > 0) {
                absentReads -= 1
                return null
            }
            return session
        }

        override suspend fun saveSession(session: StoredAuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession(reason: String, source: String) {
            session = null
        }

        suspend fun emit(next: StoredAuthSession?, event: AuthSessionEvent) {
            session = next
            events.emit(event)
        }
    }

    private class BindingHarness {
        val auth = FlakyTypedAuthSessionStore()
        val presenceAuthContext = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        /** Pending-send journal cleanup calls (production wires onBindingRetired to clearForAuthRevision). */
        val retired = mutableListOf<ProcessPresenceAuthContext>()
        val rotated = mutableListOf<Pair<ProcessPresenceAuthContext, ProcessPresenceAuthContext>>()
        private val transition =
            processPresenceBindingTransition(
                presenceAuthContext = presenceAuthContext,
                registry = ProcessMemberPresenceRegistry(),
                onBindingRetired = { retired += it },
                onBindingAuthRevisionRotated = { old, new -> rotated += old to new },
                currentEnvironmentId = { "env-1" },
            )
        val provider =
            RealtimeAuthorityProvider(
                registry =
                    RealtimeAuthorityRegistry(
                        authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                        processInstanceId = "unknown-vs-signed-out",
                    ),
                authStore = auth,
                authEvents = auth,
                currentEnvironmentId = { "env-test" },
                onAuthRevisionAdvanced = { revision -> transition(revision) },
            )
    }

    private fun signedInSession(token: String) =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = token,
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "server-a", slug = "server-a")),
        )

    @Test
    fun transientAbsentSessionReadDoesNotRetirePendingSendsOrTheBinding() = runBlocking {
        val harness = BindingHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(scope)
        harness.provider.registerScope("shell")
        harness.auth.emit(signedInSession("token-1"), AuthSessionEvent.Authenticated(generation = 1))
        assertEquals("user-1", assertNotNull(harness.presenceAuthContext.value).accountId)

        // The session is still stored; one read after a metadata change comes back empty.
        harness.auth.absentReads = 1
        harness.auth.emit(signedInSession("token-1"), AuthSessionEvent.BindingMetadataChanged(generation = 2))

        assertEquals(emptyList(), harness.retired, "a failed read must not clear the account's pending sends")
        assertEquals("user-1", harness.presenceAuthContext.value?.accountId)

        // The next read works again: same account, so pending sends move to the new revision.
        harness.auth.emit(signedInSession("token-2"), AuthSessionEvent.TokensRotated(generation = 3))
        assertEquals(emptyList(), harness.retired)
        assertEquals(1, harness.rotated.size)
        assertEquals("user-1", harness.presenceAuthContext.value?.accountId)
        scope.cancel()
    }

    @Test
    fun transientAbsentSessionReadNeverPublishesSignedOutToBindingFollowers() = runBlocking {
        val harness = BindingHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // What the members follower, the drafts follower and the sync scope observe.
        val observed = mutableListOf<ProcessPresenceAuthContext?>()
        scope.launch { harness.presenceAuthContext.collect { observed += it } }
        harness.provider.start(scope)
        harness.provider.registerScope("shell")
        harness.auth.emit(signedInSession("token-1"), AuthSessionEvent.Authenticated(generation = 1))

        harness.auth.absentReads = 1
        harness.auth.emit(signedInSession("token-1"), AuthSessionEvent.BindingMetadataChanged(generation = 2))
        harness.auth.emit(signedInSession("token-2"), AuthSessionEvent.TokensRotated(generation = 3))

        val afterFirstBinding = observed.dropWhile { it == null }
        assertTrue(afterFirstBinding.isNotEmpty())
        assertTrue(
            afterFirstBinding.none { it == null },
            "followers saw a signed-out binding (members retired, drafts deleted): $observed",
        )
        scope.cancel()
    }

    @Test
    fun explicitSignOutStillRetiresPendingSendsAndTheBinding() = runBlocking {
        val harness = BindingHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harness.provider.start(scope)
        harness.provider.registerScope("shell")
        harness.auth.emit(signedInSession("token-1"), AuthSessionEvent.Authenticated(generation = 1))

        harness.auth.emit(null, AuthSessionEvent.Cleared(generation = 2, reason = AuthSessionClearReason.Logout))

        assertEquals(listOf("user-1"), harness.retired.map { it.accountId })
        assertNull(harness.presenceAuthContext.value)
        scope.cancel()
    }

    // --- Risk 4: the server_selected pull was dropped when the binding was not ready yet ---

    private val boundContext =
        ProcessPresenceAuthContext(
            environmentId = "https://api.raft.build",
            accountId = "user-a",
            authorityGeneration = 1L,
            writeGeneration = 1L,
        )

    @Test
    fun serverSelectedPullBeforeTheBindingIsKnownRunsOnceTheAccountIsBound() {
        val gate = RaftSyncPullBindingGate()

        assertTrue(gate.deferUntilBound("server-1", "server_selected", RaftAccountBinding.Unknown))
        assertEquals(
            listOf(RaftDeferredSyncPull("server-1", "server_selected")),
            gate.onBinding(RaftAccountBinding.SignedIn(boundContext)),
        )
        // Once only.
        assertEquals(emptyList(), gate.onBinding(RaftAccountBinding.SignedIn(boundContext)))

        // A known binding without an account is not waited for, and a sign-out drops what waited.
        assertFalse(gate.deferUntilBound("server-1", "server_selected", RaftAccountBinding.SignedOut))
        assertTrue(gate.deferUntilBound("server-2", "server_selected", RaftAccountBinding.Unknown))
        assertEquals(emptyList(), gate.onBinding(RaftAccountBinding.SignedOut))
        assertEquals(emptyList(), gate.onBinding(RaftAccountBinding.SignedIn(boundContext)))
    }

    // --- Risk 5: an offline cold start read last-known flags before the binding and never again ---

    private val flagServer = ServerId("s1")
    private val flagKeys = setOf(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY)

    private class ScriptedFlagsApi(val response: HomeNetworkResponse) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse = response
    }

    @Test
    fun offlineColdStartRereadsLastKnownFlagsWhenTheBindingArrives() = runBlocking {
        val disk = InMemoryRaftKeyValueStore()
        var binding: ServerFeatureFlagLastKnownBinding? = ServerFeatureFlagLastKnownBinding("https://api.example", "user-a")
        fun process(response: HomeNetworkResponse) =
            SharedServerFeatureFlags(
                api = ScriptedFlagsApi(response),
                lastKnown = ServerFeatureFlagLastKnownStore(store = disk, currentBinding = { binding }, keys = flagKeys),
                clientDefaultOnKeys = flagKeys,
            )
        // Last online session: the server said OFF (client default is ON), and it was stored.
        process(
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"evaluations":[{"key":"thread_markdown_single_paragraph_light_v0","enabled":false}]}""",
            )
        ).prefetch(flagServer)

        // Offline cold start, first frames before the account binding is known.
        val savedBinding = binding
        binding = null
        val coldStart = process(HomeNetworkResponse(code = 0, bodyText = null))
        coldStart.prefetch(flagServer)
        assertTrue(coldStart.isEnabled(flagServer, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
        val revisionBeforeBinding = coldStart.snapshotRevision.value

        binding = savedBinding
        coldStart.onLastKnownBindingChanged()

        assertTrue(
            coldStart.snapshotRevision.value > revisionBeforeBinding,
            "readers keyed on snapshotRevision must re-read once the binding is known",
        )
        assertFalse(coldStart.isEnabled(flagServer, THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY))
    }
}
