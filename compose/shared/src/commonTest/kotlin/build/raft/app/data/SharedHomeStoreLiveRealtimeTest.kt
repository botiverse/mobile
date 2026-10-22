package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hands feedback bc6e392e (Android 1.15.2): realtime messages reached the socket on time but were
 * applied up to 17 s later. The AppShell domain sink applies Home realtime under the realtime
 * authority registry lock and waited for Home loads there, so one Home bootstrap held every later
 * realtime event. Live Home applies must return without waiting for a Home load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreLiveRealtimeTest {
    @Test
    fun liveMessageAppliesWhileHomeLoadIsStillOnTheNetwork() = runTest {
        val harness = harness()
        harness.store.dispatch(SharedHomeIntent.Refresh)
        runCurrent()
        assertEquals(2, harness.api.channelsCalls, "the refresh load is on the network")

        val apply = async { harness.store.accept(realtime(messageNew("message-2", "live update"))) }
        runCurrent()

        assertTrue(apply.isCompleted, "the live apply must not wait for the in-flight Home load")
        assertEquals("live update", harness.channel().lastMessagePreview)
        harness.api.release()
        runCurrent()
        harness.scope.cancel()
    }

    @Test
    fun directMessageCreatedReturnsBeforeItsHomeReloadFinishes() = runTest {
        val harness = harness()

        val apply = async { harness.store.accept(realtime(DirectMessageCreated(ChannelId("dm-new")))) }
        runCurrent()

        assertTrue(apply.isCompleted, "the live apply must launch the Home reload, not run it inline")
        assertEquals(2, harness.api.channelsCalls, "dm:new still starts a Home reload")
        harness.api.release()
        harness.store.awaitRealtimeSettled()
        val content = harness.store.state.value.content
        assertTrue(content is SharedHomeContentState.Content, "reload settles on content, state=$content")
        assertEquals(false, content.contentRefreshing)
        harness.scope.cancel()
    }

    private class Harness(
        val scope: CoroutineScope,
        val store: SharedHomeStore,
        val api: GatedChannelsHomeApi,
    ) {
        fun channel(): HomeChannelRow {
            val content = store.state.value.content as? SharedHomeContentState.Content
                ?: error("home not loaded, state=${store.state.value.content}")
            return content.snapshot.channels.single()
        }
    }

    private suspend fun TestScope.harness(): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = authSessionFixture()
        val api = GatedChannelsHomeApi(gateFromCall = 2)
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource = SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                api = api,
                parseHome = { _, _ -> DataResult.Content(home()) }
            ),
            scope = scope
        )
        store.accept(SharedHomeIntent.Start)
        assertTrue(store.state.value.content is SharedHomeContentState.Content, "start load settles first")
        return Harness(scope, store, api)
    }

    private fun realtime(event: RealtimeEvent): SharedHomeIntent =
        SharedHomeIntent.ApplyRealtimeEvent(serverId = "server-1", event = event)

    private fun messageNew(id: String, body: String): MessageNew =
        MessageNew(
            Message(
                id = MessageId(id),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                seq = 2,
                author = member("member-2", "Cindy"),
                timestampLabel = "12:01",
                body = body
            )
        )

    private fun home(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember = member("user-1", "Me"),
            channels = listOf(
                HomeChannelRow(threadId = ThreadId("channel-1"), channelId = ChannelId("channel-1"), name = "general")
            ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = "$id@example.com",
            kind = MemberKind.Human,
            presence = Presence.Active
        )
}
