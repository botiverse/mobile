package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelId
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.thread.AuthenticatedThreadForwardBundleDetailRepository
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.ThreadForwardBundleDetailIntent
import build.raft.app.thread.ThreadForwardBundleDetailRepository
import build.raft.app.thread.ThreadForwardBundleDetailStatus
import build.raft.app.thread.ThreadForwardBundleDetailStore
import build.raft.app.thread.ThreadForwardBundleInlinePreviewLimit
import build.raft.app.thread.ThreadForwardBundlePreviewLineLimit
import build.raft.app.thread.ThreadForwardBundleRoute
import build.raft.app.thread.dispatch
import build.raft.app.thread.inlinePreviewMessages
import build.raft.app.thread.toThreadForwardBundleRouteOrNull
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ThreadForwardBundleDetailStoreTest {

    @Test
    fun mismatchedOwnerRejectsBeforeStaleMessageCacheOrNetwork() = runBlocking {
        val route = threadForwardBundleRoute()
        val staleBundle = ForwardMessageBundle(messages = listOf(forwardItem("message-1", "stale")))
        val messageStore = SharedThreadMessageStore()
        messageStore.upsert(
            route.serverId,
            route.channelId,
            Message(
                id = route.messageId,
                threadId = ThreadId(route.channelId.value),
                channelId = route.channelId,
                author =
                    MemberProfile(
                        id = MemberId("user-a"),
                        displayName = "A",
                        handle = "a",
                        kind = MemberKind.Human,
                    ),
                timestampLabel = "10:00",
                body = "stale",
                forwardBundle = staleBundle,
            )
        )
        var networkClientCalls = 0
        val gateway =
            object : AuthenticatedRouteAuthorityGateway {
                override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? = null

                override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean = false

                override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient {
                    networkClientCalls += 1
                    return object : RaftNetworkClient {
                        override suspend fun request(
                            priority: DataRequestPriority,
                            path: String,
                            method: String,
                            query: List<Pair<String, String>>,
                            serverIdOverride: ServerId?,
                            bodyText: String?,
                        ): HomeNetworkResponse = error("network must not run")
                    }
                }
            }
        val repository =
            AuthenticatedThreadForwardBundleDetailRepository(
                messageStore = messageStore,
                authStore = InMemoryRaftAuthSessionStore(),
                owner =
                    AuthenticatedRouteOwner(
                        environmentId = "https://api.raft.build/api",
                        accountId = "account-a",
                        serverId = "server-1",
                    ),
                gateway = gateway,
            )

        val result = repository.load(route)

        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, networkClientCalls)
    }

    @Test
    fun matchingOwnerDoesNotReadLegacyServerOnlyMessageCache() = runBlocking {
        val route = threadForwardBundleRoute()
        val messageStore = SharedThreadMessageStore()
        messageStore.upsert(
            route.serverId,
            route.channelId,
            cachedMessage(route, body = "stale"),
        )
        var requestCalls = 0
        val networkClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    requestCalls += 1
                    return HomeNetworkResponse(code = 404, bodyText = null)
                }
            }
        val repository =
            AuthenticatedThreadForwardBundleDetailRepository(
                messageStore = messageStore,
                authStore = authenticatedStore(),
                owner = owner(),
                gateway = FixedForwardBundleGateway(networkClient),
            )

        val result = repository.load(route)

        assertIs<DataResult.Error>(result)
        assertEquals(1, requestCalls)
    }

    @Test
    fun routeAcceptsIdentityOnlyAndRejectsMissingIdentity() {
        val route =
            JSONObject()
                .put("serverId", "server-1")
                .put("channelId", "channel-1")
                .put("messageId", "message-1")
                .toThreadForwardBundleRouteOrNull()

        assertEquals(ServerId("server-1"), route?.serverId)
        assertEquals(ChannelId("channel-1"), route?.channelId)
        assertEquals(MessageId("message-1"), route?.messageId)
        assertNull(JSONObject().put("payload", "full-message-content").toThreadForwardBundleRouteOrNull())
    }

    @Test
    fun inlinePreviewKeepsThreeItemsAndTruncatesAfterEightLines() {
        val bundle =
            ForwardMessageBundle(
                messages =
                    (1..5).map { index ->
                        forwardItem(
                            id = "message-$index",
                            body = (1..9).joinToString("\n") { line -> "message $index line $line" }
                        )
                    }
            )

        val previews = bundle.inlinePreviewMessages()

        assertEquals(ThreadForwardBundleInlinePreviewLimit, previews.size)
        assertTrue(previews.all { it.truncated })
        assertTrue(previews.all { it.body.lines().size == ThreadForwardBundlePreviewLineLimit })
    }

    @Test
    fun aForwardedQuoteReplyKeepsItsQuoteWithoutTheRefLine() {
        // web ForwardedBundleCard.tsx:185 (botiverse/slock #16158): each forwarded body through
        // `stripQuoteReplyRefsForPreview`.
        val reply = "> Status page banner goes live at 9:00 PT\n> msg=c54e1234\n\nCan we move it to 8:30?"
        val preview = ForwardMessageBundle(messages = listOf(forwardItem("message-1", reply))).inlinePreviewMessages().single()
        assertEquals("> Status page banner goes live at 9:00 PT\n\nCan we move it to 8:30?", preview.body)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun warmScopedCacheStartsInContentAndRevalidatesWithoutFlashingLoading() = runTest {
        val route = threadForwardBundleRoute()
        val messageStore = SharedThreadMessageStore()
        val gateway = FixedForwardBundleGateway(
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse = error("warm cache must not hit network")
            }
        )
        val receipt = requireNotNull(gateway.capture(owner()))
        val scopeId = build.raft.app.data.authenticatedRouteCandidateCacheScopeId(
            owner = receipt.owner,
            authGeneration = receipt.authGeneration,
            selectionGeneration = receipt.selectionGeneration,
        )
        val cached = cachedMessage(route, body = "Instant bundle body")
        messageStore.upsertScoped(scopeId, route.channelId, cached)

        val revalidationGate = CompletableDeferred<Unit>()
        var revalidationResult: DataResult<ForwardMessageBundle> = DataResult.Content(requireNotNull(cached.forwardBundle))
        val repository =
            AuthenticatedThreadForwardBundleDetailRepository(
                messageStore = messageStore,
                authStore = authenticatedStore(),
                owner = owner(),
                gateway = gateway,
                candidateCacheScopeIdProvider = { scopeId },
            )
        val gatedRepo =
            object : ThreadForwardBundleDetailRepository {
                override fun peek(route: ThreadForwardBundleRoute): ForwardMessageBundle? = repository.peek(route)
                override suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle> {
                    revalidationGate.await()
                    return revalidationResult
                }
            }

        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(coroutineContext + storeJob)
        val store = ThreadForwardBundleDetailStore(route = route, repository = gatedRepo, scope = storeScope)
        // Frame 1: immediately Content with zero Loading flash.
        assertEquals(ThreadForwardBundleDetailStatus.Content, store.state.value.status)
        assertEquals(cached.forwardBundle, store.state.value.bundle)

        // Revalidation via Retry keeps Content on screen instead of flashing Loading.
        store.dispatch(ThreadForwardBundleDetailIntent.Retry)
        runCurrent()
        assertEquals(ThreadForwardBundleDetailStatus.Content, store.state.value.status)
        assertEquals(cached.forwardBundle, store.state.value.bundle)

        // If revalidation discovers authority loss, state still transitions to Error.
        revalidationResult = build.raft.app.data.authenticatedRouteAuthorityChanged()
        revalidationGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThreadForwardBundleDetailStatus.Error, store.state.value.status)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, store.state.value.errorCode)

        // Explicit user Retry from Error transitions back to Loading.
        val retryStore =
            ThreadForwardBundleDetailStore(
                route = route,
                repository =
                    object : ThreadForwardBundleDetailRepository {
                        override fun peek(route: ThreadForwardBundleRoute): ForwardMessageBundle? = null
                        override suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle> {
                            CompletableDeferred<Unit>().await()
                            return DataResult.Content(requireNotNull(cached.forwardBundle))
                        }
                    },
                scope = storeScope,
            )
        assertEquals(ThreadForwardBundleDetailStatus.Loading, retryStore.state.value.status)
        storeJob.cancelAndJoin()
    }

    private fun threadForwardBundleRoute(): ThreadForwardBundleRoute =
        ThreadForwardBundleRoute(
            serverId = ServerId("server-1"),
            channelId = ChannelId("channel-1"),
            messageId = MessageId("message-1")
        )

    private fun forwardItem(id: String, body: String): ForwardMessageItem =
        ForwardMessageItem(
            id = id,
            authorId = "author-1",
            authorName = "Artea",
            body = body
        )

    private fun owner(): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner("https://api.raft.build/api", "account-a", "server-1")

    private fun authenticatedStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "account-a", email = "a@raft.build"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

    private fun cachedMessage(
        route: ThreadForwardBundleRoute,
        body: String,
    ): Message =
        Message(
            id = route.messageId,
            threadId = ThreadId(route.channelId.value),
            channelId = route.channelId,
            author =
                MemberProfile(
                    id = MemberId("user-a"),
                    displayName = "A",
                    handle = "a",
                    kind = MemberKind.Human,
                ),
            timestampLabel = "10:00",
            body = body,
            forwardBundle = ForwardMessageBundle(messages = listOf(forwardItem("message-1", body))),
        )
}

private class FixedForwardBundleGateway(
    private val client: RaftNetworkClient,
) : AuthenticatedRouteAuthorityGateway {
    private val receipt =
        AuthenticatedRouteAuthorityReceipt(
            owner = AuthenticatedRouteOwner("https://api.raft.build/api", "account-a", "server-1"),
            authGeneration = 1L,
            selectionGeneration = 1L,
            canManageSettings = false,
            expectedAuthBinding =
                RaftExpectedAuthBinding(
                    accountId = "account-a",
                    accessToken = "access-a",
                    refreshToken = "refresh-a",
                    serverId = "server-1",
                    environmentId = "https://api.raft.build/api",
                ),
        )

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? =
        receipt.takeIf { it.owner == owner }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean =
        receipt == this.receipt

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient = client
}
