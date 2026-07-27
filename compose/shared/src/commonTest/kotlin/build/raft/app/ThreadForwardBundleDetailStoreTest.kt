package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.model.ChannelId
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.SlockTimeFormat24Hour
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadForwardBundleDetailStoreTest {

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
    fun pageDataCarriesOnlyIdentityAndReadsHostInjectedTimeContext() {
        val route = threadForwardBundleRoute()
        val pageData = route.toThreadForwardBundlePageData()

        assertEquals(route, pageData.toThreadForwardBundleRouteOrNull())
        assertFalse(pageData.has("localOffsetMinutes"), "route must not carry host time context")
        listOf("payload", "messages", "bundle", "title", "timestampLabel").forEach { forbidden ->
            assertFalse(pageData.has(forbidden), "$forbidden must stay outside forward-bundle pageData")
        }

        val hostInjectedPageData = JSONObject().put("localOffsetMinutes", 480)
        assertEquals(480, hostInjectedPageData.threadForwardBundleTimeContextOrNull()?.localOffsetMinutes)
        assertEquals(SlockTimeFormat24Hour, hostInjectedPageData.threadForwardBundleTimeContextOrNull()?.timeFormat)
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
    fun storeLoadsBundleThroughRepositoryAndIgnoresDuplicateLoad() = runBlocking {
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
        while (store.state.value.status == ThreadForwardBundleDetailStatus.Loading) yield()

        assertEquals(1, loadCount)
        assertEquals(ThreadForwardBundleDetailStatus.Content, store.state.value.status)
        assertEquals(bundle, store.state.value.bundle)
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
}
