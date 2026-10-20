package build.raft.app

import build.raft.app.data.ProcessPresenceAuthContext
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadReplyCacheIdentityAuthority
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Test-side reply-cache identity authority (task #1122).
 *
 * The production data source has no private identity resolver any more: identity comes only from
 * a `replyCacheIdentityAuthority`, which in production is `RaftSharedStoreProvider` handing a
 * process-wide `ProcessPresenceAuthContext` to [ThreadReplyCacheIdentityAuthority]. Tests cannot
 * build the provider, so this helper constructs THE SAME production class and supplies only the
 * one thing the provider would: the auth context. Environment / principal / server re-validation
 * and selection currency are therefore production code paths, not a test model.
 *
 * What IS a model here is the authority generation. The production registry
 * (`RealtimeAuthorityRegistry.advanceAuthFromCurrentSession`) allocates `generation + 1` whenever the
 * bound session identity changes and never re-uses a number. This holder mirrors that shape with the
 * SAME identity the registry compares — `RealtimeAuthoritySession` normalised: (environmentId,
 * principalId, distinct non-blank server ids in order); token refreshes and the *selected* server are
 * not part of it. First identity seen is generation 1, every identity change is +1, A→B→A yields
 * 1, 2, 3. It is NOT the registry, and it must not be read as a mirror of anything but that counting
 * rule over that identity (pinned by `SharedThreadTestIdentityAuthorityTest`).
 *
 * Holder scope: one holder per [RaftAuthSessionStore] INSTANCE (see
 * [testThreadReplyCacheIdentityAuthority]). The store instance is the test's stand-in for "one
 * process"; the identity fields are runtime inputs read from it, never part of the memo key. Two
 * data sources built on the same store therefore share one generation sequence — the shape the
 * deleted per-instance resolver got wrong (task #1122 defect: "A→B→A = 1, 3; restart from 1").
 */
internal class TestThreadReplyCacheIdentityAuthority(
    private val sessionStore: RaftAuthSessionStore,
    internal val environmentId: String,
    selectedServerStore: SelectedServerStore? = null,
) {
    /** The registry's normalised session identity, reproduced field for field. */
    private data class Identity(val environmentId: String, val principalId: String, val serverIds: List<String>)

    private val lock = SynchronizedObject()
    private var generation = 0L
    private var lastIdentity: Identity? = null
    internal val explicitSelectedServerStore: SelectedServerStore? = selectedServerStore
    // `SelectedServerStore` is owner-bound (it fails closed for any principal but its first), exactly
    // like production, where a principal change rebinds the shell scope. Mirror that: one store per
    // principal; a store the test supplied serves the first principal only.
    private val selectedServerStoreByPrincipal = mutableMapOf<String, SelectedServerStore>()

    /** Generation the next capture will publish for the store's current session identity. */
    suspend fun currentGeneration(): Long? {
        val session = sessionStore.readSession() ?: return null
        return synchronized(lock) { generationFor(identityOf(session)) }
    }

    private fun identityOf(session: StoredAuthSession): Identity =
        Identity(
            environmentId = environmentId.trim(),
            principalId = session.user.id.trim(),
            serverIds = session.servers.map { it.id.trim() }.filter { it.isNotEmpty() }.distinct(),
        )

    suspend fun capture(): ThreadReplyCacheIdentitySnapshot? {
        val session = sessionStore.readSession() ?: return null
        val principalId = session.user.id.trim()
        val context =
            synchronized(lock) {
                ProcessPresenceAuthContext(
                    environmentId = environmentId,
                    accountId = principalId,
                    authorityGeneration = generationFor(identityOf(session)),
                    writeGeneration = generation,
                )
            }
        val serverStore =
            synchronized(lock) {
                selectedServerStoreByPrincipal.getOrPut(principalId) {
                    // A store the test supplied belongs to the first principal that captures on it;
                    // any later principal gets a fresh store, as a shell rebinding would give it.
                    explicitSelectedServerStore?.takeIf { selectedServerStoreByPrincipal.isEmpty() }
                        ?: SelectedServerStore(
                            RaftShellStoreScope.create(
                                "test-identity-authority-$principalId",
                                session.selectedServerId ?: session.currentServerId ?: "",
                            ),
                        )
                }
            }
        return ThreadReplyCacheIdentityAuthority(
            sessionStore = sessionStore,
            selectedServerStore = serverStore,
            authContextProvider = { context },
            environmentIdProvider = { environmentId },
        ).capture()
    }

    private fun generationFor(identity: Identity): Long {
        if (identity != lastIdentity) {
            lastIdentity = identity
            generation += 1L
        }
        return generation
    }
}

private val testIdentityAuthorityLock = SynchronizedObject()
private val testIdentityAuthorityByStore = mutableMapOf<RaftAuthSessionStore, TestThreadReplyCacheIdentityAuthority>()

/**
 * Memoised per session-store INSTANCE: every call with the same store returns the same holder, so
 * however many data sources a test builds on one store, they count generations together. Stores
 * are compared by reference (`InMemoryRaftAuthSessionStore` does not override `equals`).
 *
 * The memo is a test-process-wide map (one strong reference per store ever used; memory only,
 * never cleared). It stays test-local only because every test function creates its own store —
 * a class-field store would carry a generation sequence across test functions and stay green.
 * That invariant is enforced, not assumed: `scripts/ci/check_source_constraints.py`
 * (`check_test_session_stores_are_test_local`) fails on any class-field
 * `InMemoryRaftAuthSessionStore` under commonTest.
 *
 * Configuration on a repeated call for the same store is checked, not merged, and both optional
 * parameters mean the same thing when omitted (`null`): "I am not specifying this — reuse what the
 * store's holder already has". A caller that DOES specify an [environmentId] or a
 * [selectedServerStore] different from the existing holder's gets an `IllegalStateException`
 * instead of silently running under someone else's configuration. A first call that omits the
 * environment builds the holder on [TestThreadEnvironmentId].
 */
internal fun testThreadReplyCacheIdentityAuthority(
    sessionStore: RaftAuthSessionStore,
    environmentId: String? = null,
    selectedServerStore: SelectedServerStore? = null,
): TestThreadReplyCacheIdentityAuthority =
    synchronized(testIdentityAuthorityLock) {
        val cached = testIdentityAuthorityByStore[sessionStore]
        if (cached != null) {
            // The memo key is the store instance only; a SPECIFIED configuration must therefore agree,
            // or the caller would silently run under someone else's environment / server store.
            check(environmentId == null || cached.environmentId == environmentId) {
                "test identity authority for this session store already exists with environment " +
                    "'${cached.environmentId}', requested '$environmentId'"
            }
            check(selectedServerStore == null || cached.explicitSelectedServerStore === selectedServerStore) {
                "test identity authority for this session store already exists with a different SelectedServerStore"
            }
            return@synchronized cached
        }
        TestThreadReplyCacheIdentityAuthority(
            sessionStore,
            environmentId ?: TestThreadEnvironmentId,
            selectedServerStore,
        ).also { testIdentityAuthorityByStore[sessionStore] = it }
    }
