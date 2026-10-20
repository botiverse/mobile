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
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ThreadId
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun visualFixtureRemainsAdmittedWithoutProductionAuthority() {
        assertTrue(
            threadForwardBundleContentAdmitted(
                visualFixture = true,
                productionAuthorityValid = false,
                status = ThreadForwardBundleDetailStatus.Empty,
            )
        )
        assertFalse(
            threadForwardBundleContentAdmitted(
                visualFixture = false,
                productionAuthorityValid = false,
                status = ThreadForwardBundleDetailStatus.Content,
            )
        )
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
    fun pageDataKeepsProductStateOutAndReadsOnlyHostTimeFallback() {
        val route = threadForwardBundleRoute()
        val pageData = route.toThreadForwardBundlePageData()

        assertEquals(route, pageData.toThreadForwardBundleRouteOrNull())
        assertFalse(pageData.has("localOffsetMinutes"), "route must not carry host time context")
        listOf("payload", "messages", "bundle", "title", "timestampLabel").forEach { forbidden ->
            assertFalse(pageData.has(forbidden), "$forbidden must stay outside forward-bundle pageData")
        }

        val hostInjectedPageData =
            JSONObject()
                .put("localOffsetMinutes", 480)
                .put("timeFormat", RaftTimeFormat24Hour)
        assertEquals(480, hostInjectedPageData.threadForwardBundleTimeCapabilitiesOrNull()?.localOffsetMinutes)
        assertEquals(
            RaftTimeFormat24Hour,
            hostInjectedPageData.threadForwardBundleTimeCapabilitiesOrNull()?.timeFormat
        )
        assertNull(JSONObject().threadForwardBundleTimeCapabilitiesOrNull())
        assertNull(JSONObject().put("localOffsetMinutes", "invalid").threadForwardBundleTimeCapabilitiesOrNull())
    }

    @Test
    fun inlinePreviewUsesWebLongContentThresholdForSingleMessage() {
        val short = forwardItem(id = "short", body = "x".repeat(ThreadForwardBundlePreviewCharLimit))
        val long = forwardItem(id = "long", body = "x".repeat(ThreadForwardBundlePreviewCharLimit + 1))

        val shortPreview = ForwardMessageBundle(messages = listOf(short)).inlinePreviewMessages().single()
        val longPreview = ForwardMessageBundle(messages = listOf(long)).inlinePreviewMessages().single()

        assertFalse(shortPreview.truncated)
        assertEquals(short.body, shortPreview.body)
        assertTrue(longPreview.truncated)
        assertTrue(longPreview.body.endsWith("…"))
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
    fun storeLoadsBundleThroughRepositoryIgnoresDuplicateLoadAndAllowsRevalidation() = runBlocking {
        val bundle = ForwardMessageBundle(messages = listOf(forwardItem("message-1", "full body")))
        var loadCount = 0
        val repository =
            object : ThreadForwardBundleDetailRepository {
                override suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle> {
                    loadCount += 1
                    return DataResult.Content(bundle)
                }
            }
        val store =
            ThreadForwardBundleDetailStore(
                route = threadForwardBundleRoute(),
                repository = repository,
                scope = this
            )

        store.dispatch(ThreadForwardBundleDetailIntent.Load)
        store.dispatch(ThreadForwardBundleDetailIntent.Load)
        yield()

        assertEquals(1, loadCount)
        assertEquals(ThreadForwardBundleDetailStatus.Content, store.state.value.status)
        assertEquals(bundle, store.state.value.bundle)

        store.dispatch(ThreadForwardBundleDetailIntent.Retry)
        yield()
        assertEquals(2, loadCount)
        store.clear()
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
