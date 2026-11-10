package build.raft.app.data

import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent Lounge v0 in the Home store (web slock #13875 / #13880, server #13878): a Lounge row's
 * menu acts on a conversation no Home list holds. Pin is a `channel` pin of the Lounge id; Close
 * puts the Lounge id in `hiddenDmIds`; a message from someone else counts unread on the summary
 * its agent's DM row carries and only shows a closed Lounge (no write); your own post there
 * takes it off `hiddenDmIds` locally (the server does it on send); opening it PATCHes it off;
 * Mark as Read and Mute reach the Lounge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreAgentLoungeTest {
    @BeforeTest
    fun knownLounge() {
        RaftAgentLounges.clear()
        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(
                threadId = ThreadId(LOUNGE),
                channelId = ChannelId(LOUNGE),
                name = "bugen-lounge",
                joined = true,
                activityNotificationPreference = ChannelActivityNotificationPreference(activityMuteSupported = true),
                loungeAgentId = "agent-bugen",
            ),
        )
    }

    @AfterTest
    fun forget() = RaftAgentLounges.clear()

    @Test
    fun pinIsAChannelPinOfTheLoungeIdInThePatchBody() = runTest {
        val harness = harness()
        harness.store.accept(SharedHomeIntent.TogglePinned(LOUNGE))
        runCurrent()

        val request = harness.updates.single()
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = LOUNGE)), request.pinned)
        val body = Json.parseToJsonElement(buildSharedSettingsServerSidebarOrderUpdateBody(request)).jsonObject
        val pinned = body.getValue("pinned").jsonArray.single().jsonObject
        assertEquals("channel", pinned.getValue("kind").jsonPrimitive.content)
        assertEquals(LOUNGE, pinned.getValue("id").jsonPrimitive.content)
        assertEquals(listOf(LOUNGE), harness.order().pinnedChannelIds)
        harness.scope.cancel()
    }

    @Test
    fun closePutsTheLoungeIdInHiddenDmIds() = runTest {
        val harness = harness()
        harness.store.accept(SharedHomeIntent.CloseDirectMessage(LOUNGE))
        runCurrent()

        assertEquals(listOf(LOUNGE), harness.updates.single().hiddenDmIds)
        val body = Json.parseToJsonElement(buildSharedSettingsServerSidebarOrderUpdateBody(harness.updates.single())).jsonObject
        assertEquals(listOf(LOUNGE), body.getValue("hiddenDmIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(LOUNGE), harness.order().hiddenDmIds)
        harness.scope.cancel()
    }

    @Test
    fun aClosedLoungesNewUnreadOnlyShowsItAndMarkReadClearsIt() = runTest {
        val harness = harness(hiddenDmIds = listOf(LOUNGE))
        harness.store.accept(realtime(loungeMessage(author = "member-2", seq = 5, mentionsMe = true)))
        // A repeat delivery (sync:resume) counts nothing again.
        harness.store.accept(realtime(loungeMessage(author = "member-2", seq = 5, mentionsMe = true)))
        runCurrent()

        assertEquals(1, harness.lounge().unreadCount)
        assertEquals(1, harness.lounge().mentionCount)
        assertEquals(listOf(LOUNGE), harness.order().hiddenDmIds, "display only: still closed")
        assertTrue(harness.updates.isEmpty(), "no PATCH on unread or @")

        harness.store.accept(SharedHomeIntent.MarkRead(LOUNGE))
        runCurrent()

        assertEquals(listOf(ChannelId(LOUNGE)), harness.readAll)
        assertEquals(0, harness.lounge().unreadCount)
        assertEquals(0, harness.lounge().mentionCount)
        harness.scope.cancel()
    }

    @Test
    fun yourOwnPostReopensAClosedLoungeLocallyWithoutAPatch() = runTest {
        val harness = harness(hiddenDmIds = listOf(LOUNGE, "dm-other"))
        harness.store.accept(realtime(loungeMessage(author = "user-1", seq = 6)))
        runCurrent()

        assertEquals(listOf("dm-other"), harness.order().hiddenDmIds)
        assertEquals(0, harness.lounge().unreadCount, "your own message is not unread")
        assertTrue(harness.updates.isEmpty(), "the server reopens it on send")
        harness.scope.cancel()
    }

    @Test
    fun openingAClosedLoungePatchesItOffHiddenDmIds() = runTest {
        val harness = harness(hiddenDmIds = listOf(LOUNGE, "dm-other"))
        harness.store.accept(SharedHomeIntent.ReopenClosedConversation(LOUNGE))
        runCurrent()

        assertEquals(listOf("dm-other"), harness.updates.single().hiddenDmIds)
        assertEquals(listOf("dm-other"), harness.order().hiddenDmIds)

        // Not closed (any more): nothing to write.
        harness.store.accept(SharedHomeIntent.ReopenClosedConversation(LOUNGE))
        runCurrent()
        assertEquals(1, harness.updates.size)
        harness.scope.cancel()
    }

    @Test
    fun muteReachesTheLounge() = runTest {
        val harness = harness()
        harness.store.accept(SharedHomeIntent.SetChannelActivityMuted(LOUNGE, muted = true))
        runCurrent()

        assertEquals(listOf(ChannelId(LOUNGE) to true), harness.mutes)
        assertEquals(true, RaftAgentLounges.lounge("server-1", LOUNGE)?.activityMuted)
        harness.scope.cancel()
    }

    private class Harness(val scope: CoroutineScope, val store: SharedHomeStore) {
        val updates = mutableListOf<SettingsServerSidebarOrderUpdateRequest>()
        val readAll = mutableListOf<ChannelId>()
        val mutes = mutableListOf<Pair<ChannelId, Boolean>>()

        private fun content(): SharedHomeContentState.Content =
            store.state.value.content as? SharedHomeContentState.Content
                ?: error("home not loaded, state=${store.state.value.content}")

        fun order(): SettingsServerSidebarOrderSnapshot = content().snapshot.sidebarOrder ?: error("no sidebar order")

        fun lounge(): AgentLoungeSummary =
            content().snapshot.directMessages.single { it.threadId.value == "dm-bugen" }.lounge
                ?: error("the DM row lost its Lounge, rows=${content().snapshot.directMessages}")
    }

    private suspend fun TestScope.harness(hiddenDmIds: List<String> = emptyList()): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = authSessionFixture()
        var order = SettingsServerSidebarOrderSnapshot(hiddenDmIds = hiddenDmIds)
        lateinit var harness: Harness
        val store =
            SharedHomeStore(
                authStore = InMemoryRaftAuthSessionStore(session),
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(session),
                        api = RootEmptyHomeApi,
                        parseHome = { _, _ -> DataResult.Content(home(order)) }
                    ),
                scope = scope,
                loadSidebarOrder = { _ -> DataResult.Content(order) },
                updateSidebarOrder = { request ->
                    harness.updates += request
                    order =
                        order.copy(
                            pinned = request.pinned ?: order.pinned,
                            pinnedChannelIds = request.pinned?.filter { it.kind == "channel" }?.map { it.id } ?: order.pinnedChannelIds,
                            pinnedOrder = request.pinned?.map { it.id } ?: order.pinnedOrder,
                            hiddenDmIds = request.hiddenDmIds ?: order.hiddenDmIds,
                        )
                    DataResult.Content(order)
                },
                markChannelReadAll = { channelId ->
                    harness.readAll += channelId
                    DataResult.Content(ReadReceiptResult(channelId = channelId))
                },
                setChannelActivityMuted = { channelId, muted ->
                    harness.mutes += channelId to muted
                    DataResult.Content(
                        ChannelActivityMuteSettings(
                            channelId = channelId,
                            activityNotificationPreference =
                                ChannelActivityNotificationPreference(activityMuted = muted, activityMuteSupported = true),
                            prefsVersion = 7,
                        )
                    )
                }
            )
        harness = Harness(scope, store)
        store.accept(SharedHomeIntent.Start)
        check(store.state.value.content is SharedHomeContentState.Content) {
            "start load settles first, state=${store.state.value.content}"
        }
        return harness
    }

    private fun realtime(event: MessageNew): SharedHomeIntent =
        SharedHomeIntent.ApplyRealtimeEvent(serverId = "server-1", event = event)

    private fun loungeMessage(author: String, seq: Long, mentionsMe: Boolean = false): MessageNew =
        MessageNew(
            Message(
                id = MessageId("lounge-message-$author-$seq"),
                threadId = ThreadId(LOUNGE),
                channelId = ChannelId(LOUNGE),
                seq = seq,
                author = member(author, author),
                timestampLabel = "2026-11-10T10:00:00.000Z",
                body = if (mentionsMe) "hi @Me" else "hi",
                mentions = if (mentionsMe) listOf(build.raft.app.model.MessageMention(type = "user", id = "user-1")) else emptyList(),
            )
        )

    private fun home(order: SettingsServerSidebarOrderSnapshot): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember = member("user-1", "Me"),
            channels = emptyList(),
            directMessages =
                listOf(
                    HomeDirectMessageRow(
                        threadId = ThreadId("dm-bugen"),
                        channelId = ChannelId("dm-bugen"),
                        name = "Bugen",
                        directMessagePeerType = "agent",
                        presenceMemberId = MemberId("agent-bugen"),
                        unreadCountKnown = true,
                        lounge = AgentLoungeSummary(ChannelId(LOUNGE), joined = true),
                    )
                ),
            unreadTotal = 0,
            sidebarOrder = order
        )

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = "$id@example.com",
            kind = MemberKind.Human,
            presence = Presence.Active
        )

    private companion object {
        const val LOUNGE = "lounge-bugen"
    }
}
