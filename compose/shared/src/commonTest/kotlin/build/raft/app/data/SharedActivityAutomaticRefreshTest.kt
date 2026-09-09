package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedActivityAutomaticRefreshTest {
    @Test
    fun visibleSignalsCoalesceAndPullBypassesFreshness() = runBlocking {
        val fixture = ActivityAutomaticRefreshFixture(InMemorySlockAuthSessionStore(canonicalV2TestSession()))
        try {
            val gate = CompletableDeferred<Unit>()
            fixture.inbox.gate = gate
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(1, fixture.inbox.requests)
            assertTrue(fixture.store.state.value.loading)
            gate.complete(Unit)
            assertTrue(fixture.store.state.value.loaded)
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(1, fixture.inbox.requests)
            fixture.now = 4_999L
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(1, fixture.inbox.requests)
            fixture.now = 5_000L
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(2, fixture.inbox.requests)
            fixture.store.dispatch(SharedActivityIntent.PullRefresh)
            assertEquals(3, fixture.inbox.requests)
            assertFalse(fixture.store.state.value.pullRefreshing)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun failedRefreshCanRetryImmediately() = runBlocking {
        val fixture = ActivityAutomaticRefreshFixture(InMemorySlockAuthSessionStore(canonicalV2TestSession()))
        try {
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            fixture.inbox.result = DataResult.Error(ScreenError("offline"))
            fixture.store.dispatch(SharedActivityIntent.PullRefresh)
            assertEquals("offline", fixture.store.state.value.errorCode)
            fixture.inbox.result = DataResult.Empty(EmptyReason.NoInboxItems)
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(3, fixture.inbox.requests)
            assertEquals(null, fixture.store.state.value.errorCode)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun freshnessCannotCrossOwnersOrBypassServerMembership() = runBlocking {
        val fixture = ActivityAutomaticRefreshFixture(InMemorySlockAuthSessionStore(canonicalV2TestSession()))
        try {
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            val original = canonicalV2TestSession()
            fixture.auth.saveSession(original.copy(user = original.user.copy(id = "user-2")))
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(2, fixture.inbox.requests)
            assertEquals("user-2", fixture.store.state.value.ownerUserId)
            val otherServer = original.servers.single().copy(id = "server-2")
            fixture.auth.saveSession(original.copy(selectedServerId = "server-2", servers = listOf(otherServer)))
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(3, fixture.inbox.requests)
            assertEquals("server-2", fixture.store.state.value.ownerServerId)
            fixture.auth.clearSession()
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(3, fixture.inbox.requests)
            assertEquals("unauthenticated", fixture.store.state.value.errorCode)
            fixture.auth.saveSession(original.copy(selectedServerId = "server-2", servers = listOf(otherServer)))
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(4, fixture.inbox.requests)
            fixture.auth.saveSession(original.copy(selectedServerId = "server-2", servers = emptyList()))
            fixture.store.dispatch(SharedActivityIntent.RefreshOnVisible)
            assertEquals(4, fixture.inbox.requests)
            assertEquals("server_missing", fixture.store.state.value.errorCode)
        } finally {
            fixture.scope.cancel()
        }
    }
}

private class ActivityAutomaticRefreshFixture(val auth: InMemorySlockAuthSessionStore) {
    var now = 0L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val inbox = ActivityAutomaticRefreshInbox()
    private val network = RecordingNetworkClient(pageBody = "")
    val store = SharedActivityStore(
        authStore = auth,
        inboxRepository = inbox,
        readReceiptRepository = RealReadReceiptRepository(auth, network),
        conversationRepository = RealConversationRepository(auth, network),
        perReceiverStateVersionStore = PerReceiverStateVersionStore(InMemorySlockKeyValueStore()),
        scope = scope,
        automaticRefreshNowMillis = { now }
    )
}

private class ActivityAutomaticRefreshInbox : InboxRepository {
    var requests = 0
    var gate: CompletableDeferred<Unit>? = null
    var result: DataResult<InboxSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)

    override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
        requests += 1
        gate?.await()
        return result
    }

    override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
        error("Unexpected pagination")

    override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> =
        error("Unexpected mutation")
}
