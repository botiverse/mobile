/*
 * D-gate delegation teeth: proves the PRODUCTION SlockSharedStoreProvider delegates its owner
 * lifecycle to the single SharedRealtimeDomainEventOwner. Scope is provider->owner ONLY — this
 * file makes NO claim about Registry->provider coverage.
 *
 * Each tooth names the production deletion that must turn it red:
 *  T1 invalidation-delegation — delete the init subscription
 *     `threadSendScope.launch { authStore.sessionInvalidations.collect { realtimeDomainEventOwner.revokeOwner(); ... } }`
 *     (SlockSharedStoreProvider init, ~:163-168) or the revokeOwner() inside it -> RED.
 *  T2 release-delegation — delete the `realtimeDomainEventOwner.revokeOwner()` line inside
 *     `releaseShellStoreScope` (~:457-459) -> RED.
 *  T3 lookup-delegation — change `realtimeDomainEventOwner(scope)` (~:274) from returning the one
 *     constructor-injected instance back to per-shell getOrPut owners -> RED (assertSame fails and
 *     the cross-entry burn probe fails).
 *
 * Boundaries honored: the REAL internal constructor is used, no behavior forks; the invalidation is
 * driven through the SAME authStore instance passed into the constructor (its production
 * clearSession() emission); triggers are provider production methods only
 * (realtimeDomainEventOwner(scope) / releaseShellStoreScope(scope)); owner-direct calls appear
 * solely as state probes (minting an epoch the provider path must later burn).
 */
package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftPersistence
import build.raft.app.SharedThreadMessageStore
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.ServerId
import build.raft.app.network.sync.OwnerCommitGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProviderOwnerDelegationGateTest {

    // ---------------------------------------------------------------------------------------------
    // T1: session invalidation on the constructor-injected authStore must burn the current owner
    //     epoch through the provider's OWN init subscription.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun t1_sessionInvalidationRevokesOwnerThroughProviderInitSubscription() {
        val authStore = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        // Session exists BEFORE construction so the invalidation is a real logout transition.
        runBlocking { authStore.saveSession(storedSession()) }
        val threadSendScope = unconfinedScope()
        try {
            // Init subscribes authStore.sessionInvalidations on threadSendScope; Unconfined makes
            // that subscription (and later collection) run eagerly inline — deterministic.
            val provider = realProvider(authStore, threadSendScope)
            val owner = provider.realtimeDomainEventOwner(SlockShellStoreScope.Legacy)

            // Probe: mint an epoch owner-direct; only the provider's subscription may burn it.
            val epoch = owner.rotateOwner(serverId = "server-1", memberId = "member-1")
            assertTrue(
                owner.runIfCurrentOwner(epoch) {},
                "positive control: a freshly minted epoch must be current before the invalidation",
            )

            // Trigger via the production emission path of the SAME instance the provider subscribed.
            runBlocking { authStore.clearSession(reason = "delegation-gate", source = "test") }

            assertTrue(
                awaitEpochBurned(owner, epoch),
                "invalidation-delegation: authStore.sessionInvalidations must burn the current owner " +
                    "epoch via the provider init subscription (init -> realtimeDomainEventOwner.revokeOwner()). " +
                    "The epoch is still current after clearSession(), so the subscription is missing or " +
                    "no longer revokes the owner.",
            )
        } finally {
            threadSendScope.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // T2: releaseShellStoreScope (production method) must burn the current epoch, and a re-mint
    //     must be strictly higher (release rotated the single owner commit gate).
    // ---------------------------------------------------------------------------------------------
    @Test
    fun t2_releaseShellStoreScopeBurnsEpochAndRemintIsStrictlyHigher() {
        val authStore = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        val threadSendScope = unconfinedScope()
        try {
            val provider = realProvider(authStore, threadSendScope)
            // Non-Legacy scope: releaseShellStoreScope early-returns for Legacy.
            val shellScope = SlockShellStoreScope.create("delegation-gate-shell")
            val owner = provider.realtimeDomainEventOwner(shellScope)

            val epoch = owner.rotateOwner(serverId = "server-1", memberId = "member-1")
            assertTrue(
                owner.runIfCurrentOwner(epoch) {},
                "positive control: a freshly minted epoch must be current before release",
            )

            provider.releaseShellStoreScope(shellScope)

            assertFalse(
                owner.runIfCurrentOwner(epoch) {},
                "release-delegation: releaseShellStoreScope must burn the current owner epoch " +
                    "(releaseShellStoreScope -> realtimeDomainEventOwner.revokeOwner()). The guard still " +
                    "accepts the pre-release epoch, so the revokeOwner() delegation was removed.",
            )

            val remint = owner.rotateOwner(serverId = "server-1", memberId = "member-1")
            assertTrue(
                remint.connectionEpoch > epoch.connectionEpoch,
                "release-delegation: a re-mint after release must be STRICTLY higher than the burned " +
                    "epoch (burned=${epoch.connectionEpoch} reminted=${remint.connectionEpoch}); " +
                    "equal-or-lower means the release did not rotate the owner commit gate.",
            )
        } finally {
            threadSendScope.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // T3: every scope lookup must return the ONE constructor-injected owner (single-authority),
    //     including behavioral identity: an epoch minted via one entry is burned by a release
    //     keyed to a different scope and observed refused via a third entry.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun t3_everyScopeLookupReturnsTheSingleConstructorInjectedOwner() {
        val authStore = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        val threadSendScope = unconfinedScope()
        try {
            val provider = realProvider(authStore, threadSendScope)
            val scopeA = SlockShellStoreScope.create("delegation-gate-shell-a")
            val scopeB = SlockShellStoreScope.create("delegation-gate-shell-b")
            val ownerA = provider.realtimeDomainEventOwner(scopeA)
            val ownerB = provider.realtimeDomainEventOwner(scopeB)
            val ownerLegacy = provider.realtimeDomainEventOwner(SlockShellStoreScope.Legacy)

            assertSame(
                provider.realtimeDomainEventOwner, ownerA,
                "single-authority: the scopeA lookup must return the constructor-injected owner, " +
                    "not a per-shell getOrPut instance",
            )
            assertSame(
                ownerA, ownerB,
                "single-authority: two shell scopes must resolve to the SAME owner instance",
            )
            assertSame(
                ownerA, ownerLegacy,
                "single-authority: the Legacy scope must resolve to the SAME owner instance as shell scopes",
            )

            // Behavioral identity across entries: mint via scopeA's entry, burn via the provider
            // release path keyed to scopeB, observe the refusal via the Legacy entry.
            val epoch = ownerA.rotateOwner(serverId = "server-1", memberId = "member-1")
            assertTrue(
                ownerLegacy.runIfCurrentOwner(epoch) {},
                "positive control: the epoch minted via scopeA's entry must be current via the Legacy entry",
            )
            provider.releaseShellStoreScope(scopeB)
            assertFalse(
                ownerLegacy.runIfCurrentOwner(epoch) {},
                "single-authority: releasing scopeB must burn the epoch minted via scopeA's entry as " +
                    "observed via the Legacy entry — per-shell owner instances would keep it current.",
            )
        } finally {
            threadSendScope.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** Eagerly-inline scope: init's sessionInvalidations collector subscribes during construction. */
    private fun unconfinedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Same real-driver idiom as SqlDelightFoldPersistStoreTest.freshDb (JVM androidUnitTest). */
    private fun freshDb(): SlockSqlDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return SlockSqlDatabase(driver)
    }

    private fun storedSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace")),
        )

    /**
     * Bounded probe for T1's asynchronous leg: burned = the owner guard refuses the epoch.
     * Never green while the epoch stays current — if the init subscription is deleted the state
     * never changes and this times out red.
     */
    private fun awaitEpochBurned(owner: SharedRealtimeDomainEventOwner, epoch: RealtimeOwnerEpoch): Boolean =
        runBlocking {
            val started = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < 2.seconds) {
                if (!owner.runIfCurrentOwner(epoch) {}) return@runBlocking true
                delay(10)
            }
            !owner.runIfCurrentOwner(epoch) {}
        }

    /** Only `request` is abstract on SlockNetworkClient; 501 everywhere — no tooth needs the network. */
    private class RefusingNetworkClient : SlockNetworkClient {
        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
    }

    /** SlockAuthTransport has no default members; refuse every call — no tooth performs auth I/O. */
    private class RefusingAuthTransport : SlockAuthTransport {
        private fun refuse() = AuthNetworkResponse(code = 501, bodyText = null)
        override suspend fun login(bodyText: String) = refuse()
        override suspend fun completeSocialLogin(provider: String, bodyText: String) = refuse()
        override suspend fun startMobileOAuth(bodyText: String, authorization: String?) = refuse()
        override suspend fun completeMobileOAuth(bodyText: String, authorization: String?) = refuse()
        override suspend fun startMobileOAuthLink(provider: String, bodyText: String, authorization: String) = refuse()
        override suspend fun completeMobileOAuthLink(provider: String, bodyText: String, authorization: String) = refuse()
        override suspend fun register(bodyText: String) = refuse()
        override suspend fun forgotPassword(bodyText: String) = refuse()
        override suspend fun resendVerification(authorization: String) = refuse()
        override suspend fun verifyEmail(bodyText: String) = refuse()
        override suspend fun currentUser(authorization: String) = refuse()
        override suspend fun refresh(bodyText: String) = refuse()
        override suspend fun logout(bodyText: String) = refuse()
        override suspend fun servers(authorization: String) = refuse()
        override suspend fun serverOrder(authorization: String) = refuse()
        override suspend fun createServer(authorization: String, bodyText: String) = refuse()
    }

    /**
     * The REAL production SlockSharedStoreProvider via its internal constructor — the same shape
     * companion create() builds, with in-memory/JVM substitutes for platform-bound dependencies.
     * `authStore` and `threadSendScope` are caller-owned because T1's contract is about exactly
     * those two: the SAME store instance is both constructor-injected and invalidation-driven, and
     * the init collector runs on the caller's eagerly-inline scope.
     */
    private fun realProvider(
        authStore: KeyValueAuthSessionStore,
        threadSendScope: CoroutineScope,
    ): SlockSharedStoreProvider {
        val db = freshDb()
        val sharedPreferenceStore = InMemorySlockKeyValueStore()
        val apiEnvironmentKeyValueStore = InMemorySlockKeyValueStore()
        val networkClient = RefusingNetworkClient()
        val eventBusRelay = SlockEventBusRelay(SlockEventBuses.inProcess())
        // ONE gate shared by the owner and the provider — mirrors create() (:595, :674-676).
        val ownerCommitGate = OwnerCommitGate()
        val tasksDataStore = SharedTasksDataStore()
        // Background scopes for stores that only launch work when driven (never driven here).
        val backgroundScope = unconfinedScope()
        return SlockSharedStoreProvider(
            sqlDatabase = db,
            // Retained-only reference in the provider; authStore below owns its own KV store.
            authKeyValueStore = InMemorySlockKeyValueStore(),
            sharedPreferenceStore = sharedPreferenceStore,
            apiEnvironmentKeyValueStore = apiEnvironmentKeyValueStore,
            authStore = authStore,
            authSessionEvents = KuiklyAuthSessionEvents(eventBus = eventBusRelay, source = "delegation-gate-test"),
            authDataSource = SharedAuthDataSource(
                transport = RefusingAuthTransport(),
                sessionStore = authStore,
            ),
            authLossFunnel = SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSession = { _, _ -> },
                scope = backgroundScope,
            ),
            networkClient = networkClient,
            scopedNetworkClientFactory = { _ -> RefusingNetworkClient() },
            localDataStore = RelationalComposeLocalDataStore(SqlDelightRelationalStore(db)),
            agentDetailStore = SharedAgentDetailStore(),
            agentPanelTabOrderStore = SharedAgentPanelTabOrderStore(),
            tasksDataStore = tasksDataStore,
            serverMembersStore = SharedServerMembersStore(
                // Production repository over the refusing client — same wiring as create() (:615-624).
                memberRepository = RealMemberRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                ),
            ),
            composerDraftPersistSlice = SharedThreadComposerDraftPersistSlice(
                SharedThreadComposerDraftPersistence(sharedPreferenceStore),
            ),
            activityPersistSlice = createSharedActivityPersistSlice(
                storage = SharedPreferencePersistStorage(sharedPreferenceStore),
            ),
            homeSavedStateCache = SharedHomeSavedStateCache(),
            homeIdentityPersistSlice = createSharedHomeIdentityPersistSlice(
                storage = SharedPreferencePersistStorage(sharedPreferenceStore),
            ),
            apiEnvironmentStore = SharedApiEnvironmentStore(apiEnvironmentKeyValueStore),
            eventBus = eventBusRelay,
            // The single production owner under test — same wiring as create() (:670-675).
            realtimeDomainEventOwner = SharedRealtimeDomainEventOwner(
                tasksDataStore = tasksDataStore,
                cursorStore = RealtimeAppliedSeqCursorStore(sharedPreferenceStore),
                ownerCommitGate = ownerCommitGate,
            ),
            ownerCommitGate = ownerCommitGate,
            messageTranslationStore = SharedMessageTranslationStore(
                authStore = authStore,
                api = NetworkSlockMessageTranslationApi(networkClient),
                scope = backgroundScope,
            ),
            threadMessageStore = SharedThreadMessageStore(),
            threadPayloadStore = SharedThreadPayloadStore(),
            foregroundReconciler = SharedForegroundReconciler(
                authStore = authStore,
                refreshMembers = {},
                scope = backgroundScope,
            ),
            homeStoreScope = unconfinedScope(),
            homeActivityStoreScope = unconfinedScope(),
            // T1's subscription scope: init launches the sessionInvalidations collector here.
            threadSendScope = threadSendScope,
        )
    }
}
