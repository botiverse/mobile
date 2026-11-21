package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedForegroundBannerStoreTest {
    @Test
    fun oneModelOwnsReplacementDismissAndExpiry() = runBlocking {
        val expiry = CompletableDeferred<Unit>()
        val store = SharedForegroundBannerStore(this) { expiry.await() }
        val first = banner("first")
        val duplicate = banner("first")
        val replacement = banner("second")

        store.show(first)
        store.show(duplicate)
        assertSame(first, store.banner.value)

        store.show(replacement)
        store.dismiss(expectedId = first.id)
        assertSame(replacement, store.banner.value)

        expiry.complete(Unit)
        yield()
        assertNull(store.banner.value)
    }

    @Test
    fun staleRouteOwnerCannotClearNewVisibleRoute() = runBlocking {
        val store = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val first = route("first")
        val second = route("second")

        store.setVisibleRoute("owner-a", first)
        store.setVisibleRoute("owner-b", second)
        store.clearVisibleRoute("owner-a")
        assertEquals(second, store.visibleRouteIdentity())

        store.clearVisibleRoute("owner-b")
        assertNull(store.visibleRouteIdentity())
        assertEquals(emptyList(), store.visibleRouteIdentities())
        store.clear()
    }

    @Test
    fun overlayVisibleRouteDoesNotEraseOrShadowPrimaryVisibleRoute() = runBlocking {
        val store = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val hostRoute = route("channel-host")
        val ottieDmRoute = route("dm-ottie")

        store.setVisibleRoute("thread:host", hostRoute)
        store.setVisibleRoute("thread:ottie", ottieDmRoute, isOverlay = true)

        assertEquals(hostRoute, store.visibleRouteIdentity())
        assertEquals(listOf(hostRoute, ottieDmRoute), store.visibleRouteIdentities())

        store.clearVisibleRoute("thread:ottie")
        assertEquals(hostRoute, store.visibleRouteIdentity())
        assertEquals(listOf(hostRoute), store.visibleRouteIdentities())
        store.clear()
    }

    @Test
    fun closingSecondaryPaneRestoresPrimaryVisibleRoute() = runBlocking {
        val store = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val mainChannel = route("channel-main")
        val sideThread = route("thread-side")

        store.setVisibleRoute("thread:main", mainChannel)
        store.setVisibleRoute("thread:side", sideThread)
        assertEquals(sideThread, store.visibleRouteIdentity())
        assertEquals(listOf(mainChannel, sideThread), store.visibleRouteIdentities())

        store.clearVisibleRoute("thread:side")
        assertEquals(mainChannel, store.visibleRouteIdentity())
        assertEquals(listOf(mainChannel), store.visibleRouteIdentities())
        store.clear()
    }

    @Test
    fun overlayRouteActsAsFallbackWhenNoPrimaryRouteExistsAndReRegistrationMovesOwnerToEnd() = runBlocking {
        val store = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val overlay = route("dm-ottie")
        val first = route("channel-a")
        val second = route("channel-b")

        store.setVisibleRoute("thread:ottie", overlay, isOverlay = true)
        assertEquals(overlay, store.visibleRouteIdentity())
        assertEquals(listOf(overlay), store.visibleRouteIdentities())

        store.setVisibleRoute("thread:a", first)
        store.setVisibleRoute("thread:b", second)
        assertEquals(second, store.visibleRouteIdentity())

        store.setVisibleRoute("thread:a", first)
        assertEquals(first, store.visibleRouteIdentity())
        assertEquals(listOf(overlay, second, first), store.visibleRouteIdentities())
        store.clear()
    }

    private fun banner(id: String): RaftForegroundMessageBanner {
        val conversation =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("thread-$id"),
                channelId = ChannelId("channel-$id"),
                kind = ConversationKind.Channel
            )
        return RaftForegroundMessageBanner(
            id = id,
            serverId = "server",
            focusedMessageId = "message-$id",
            conversation = conversation,
            openRequest = HomeMessageOpenRequest(conversation, "message-$id"),
            title = "title",
            context = "context",
            threadContext = null,
            body = "body",
            iconKind = RaftForegroundMessageBannerIconKind.Channel,
            iconName = null,
            senderMemberId = null,
            avatarWireValue = null,
            avatarLabel = "A"
        )
    }

    private fun route(instanceKey: String): ThreadRouteIdentity =
        ThreadRouteIdentity(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = null,
            instanceKey = instanceKey,
            timelineKey = instanceKey,
            canRenderTimelineCandidates = true
        )
}
