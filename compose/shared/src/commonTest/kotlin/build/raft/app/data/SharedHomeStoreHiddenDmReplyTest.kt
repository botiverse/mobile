package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #59: a DM someone else opened with you starts in your `hiddenDmIds` and shows in Home only
 * while it has unread messages. Your own reply in it, or in one of its threads, must show it again
 * without waiting for the other person to write; the rule mirrors botiverse/slock #10809
 * (system lines and an assistant's delegated sends do not count).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreHiddenDmReplyTest {
    private enum class OwnReplySource {
        RealtimeEcho,
        RealtimeEchoWhileHomeHidden,
        RealtimeThreadReply,
        SendSuccess,
        SendSuccessThreadReply,
    }

    @Test
    fun ownReplyInHiddenDmOrItsThreadUnhidesItAndTheRowAppears() = runTest {
        OwnReplySource.entries.forEach { source ->
            val harness = harness()
            assertFalse(harness.dmVisible(), "$source: the read hidden DM starts out of the list")

            when (source) {
                OwnReplySource.RealtimeEcho ->
                    harness.store.accept(realtime(dmMessage(author = "user-1")))
                OwnReplySource.RealtimeEchoWhileHomeHidden -> {
                    // The user is on the DM page (Home hidden) or replied from another device.
                    // Asserted while still hidden: coming back reloads the server's own list.
                    harness.store.accept(SharedHomeIntent.SetSurfaceVisible(false))
                    harness.store.accept(realtime(dmMessage(author = "user-1")))
                }
                OwnReplySource.RealtimeThreadReply ->
                    harness.store.accept(realtime(dmThreadReply(author = "user-1")))
                OwnReplySource.SendSuccess ->
                    harness.store.accept(ownSent(sentPage(channelId = "dm-1"), parentConversationId = null))
                OwnReplySource.SendSuccessThreadReply ->
                    harness.store.accept(ownSent(sentPage(channelId = "thread-1"), parentConversationId = "dm-1"))
            }
            runCurrent()

            assertEquals(listOf("dm-other"), harness.hiddenDmIds(), "$source: only the replied DM leaves the hidden list")
            assertTrue(harness.dmVisible(), "$source: the DM row is back in the list")
            assertEquals(0, harness.dmRow().unreadCount, "$source: own message adds no unread")
            assertEquals(0, harness.sidebarOrderUpdates, "$source: the server unhides on send; no PATCH")
            harness.scope.cancel()
        }
    }

    @Test
    fun othersMessageKeepsDmHiddenAndShowsItOnlyWhileUnread() = runTest {
        val harness = harness()

        harness.store.accept(realtime(dmMessage(author = "member-2")))
        runCurrent()

        assertEquals(listOf("dm-1", "dm-other"), harness.hiddenDmIds())
        assertEquals(1, harness.dmRow().unreadCount)
        assertTrue(harness.dmVisible(), "an unread hidden DM shows")

        harness.store.accept(SharedHomeIntent.MarkRead("dm-1"))
        runCurrent()

        assertEquals(0, harness.dmRow().unreadCount)
        assertFalse(harness.dmVisible(), "once read, the still-hidden DM leaves the list again")
        harness.scope.cancel()
    }

    private enum class NotOwnReply { SystemMessage, AssistantDelegatedSend }

    @Test
    fun systemMessageAndAssistantDelegatedSendDoNotUnhide() = runTest {
        NotOwnReply.entries.forEach { case ->
            val harness = harness()
            val (realtimeMessage, page) =
                when (case) {
                    NotOwnReply.SystemMessage ->
                        dmMessage(author = "user-1", messageType = MessageType.System) to
                            sentPage(channelId = "dm-1", messageType = "system")
                    NotOwnReply.AssistantDelegatedSend ->
                        dmMessage(author = "user-1", delegatedBy = MessageDelegatedBy(id = "assistant-1", name = "Pal")) to
                            sentPage(channelId = "dm-1", delegatedBy = WireDelegatedBy(id = "assistant-1", name = "Pal"))
                }

            harness.store.accept(realtime(realtimeMessage))
            runCurrent()

            assertEquals(listOf("dm-1", "dm-other"), harness.hiddenDmIds(), "$case: realtime keeps the DM hidden")
            assertEquals(
                emptySet(),
                page.ownSentConversationIds(fallbackChannelId = "dm-1", parentConversationId = null),
                "$case: the send response unhides nothing"
            )
            harness.scope.cancel()
        }
    }

    private class Harness(val scope: CoroutineScope, val store: SharedHomeStore) {
        var sidebarOrderUpdates = 0

        private fun content(): SharedHomeContentState.Content =
            store.state.value.content as? SharedHomeContentState.Content
                ?: error("home not loaded, state=${store.state.value.content}")

        fun hiddenDmIds(): List<String> = content().snapshot.sidebarOrder?.hiddenDmIds.orEmpty()

        fun dmRow(): HomeConversationRow = content().snapshot.directMessages.single { it.threadId.value == "dm-1" }

        fun dmVisible(): Boolean =
            content().snapshot.toHomeServerDrawerNormalizedContent().rows.any { row ->
                row is HomeServerDrawerNormalizedRow.Conversation && row.conversation.threadId.value == "dm-1"
            }
    }

    private suspend fun TestScope.harness(): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = authSessionFixture()
        val order = SettingsServerSidebarOrderSnapshot(hiddenDmIds = listOf("dm-1", "dm-other"))
        lateinit var harness: Harness
        val store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource = SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                api = RootEmptyHomeApi,
                parseHome = { _, _ -> DataResult.Content(home(order)) }
            ),
            scope = scope,
            loadSidebarOrder = { _ -> DataResult.Content(order) },
            updateSidebarOrder = { request ->
                harness.sidebarOrderUpdates += 1
                DataResult.Content(order.copy(hiddenDmIds = request.hiddenDmIds.orEmpty()))
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

    private fun dmMessage(
        author: String,
        messageType: MessageType = MessageType.Chat,
        delegatedBy: MessageDelegatedBy? = null,
    ): MessageNew =
        MessageNew(
            Message(
                id = MessageId("message-$author-${messageType.name}-${delegatedBy != null}"),
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                seq = 2,
                author = member(author, author),
                timestampLabel = "12:01",
                body = "hi",
                messageType = messageType,
                delegatedBy = delegatedBy
            )
        )

    private fun dmThreadReply(author: String): MessageNew =
        MessageNew(
            Message(
                id = MessageId("reply-$author"),
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("thread-1"),
                seq = 1,
                author = member(author, author),
                timestampLabel = "12:02",
                body = "in the thread",
                conversationContext =
                    MessageConversationContext(
                        channelKind = ConversationKind.Thread,
                        parentMessageId = MessageId("message-root"),
                        parentChannelId = ChannelId("dm-1"),
                        parentChannelKind = ConversationKind.DirectMessage
                    )
            )
        )

    private fun sentPage(
        channelId: String,
        messageType: String = "chat",
        delegatedBy: WireDelegatedBy? = null,
    ): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = "sent-$channelId",
                        channelId = channelId,
                        senderType = "user",
                        senderId = "user-1",
                        messageType = messageType,
                        delegatedBy = delegatedBy
                    )
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null
        )

    /** What the thread page dispatches after a send succeeds. */
    private fun ownSent(page: ThreadMessagesPage, parentConversationId: String?): SharedHomeIntent =
        SharedHomeIntent.OwnMessagesSent(
            serverId = "server-1",
            channelIds =
                page.ownSentConversationIds(
                    fallbackChannelId = page.messages.single().channelId.orEmpty(),
                    parentConversationId = parentConversationId
                )
        )

    private fun home(order: SettingsServerSidebarOrderSnapshot): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember = member("user-1", "Me"),
            channels = emptyList(),
            directMessages = listOf(
                HomeDirectMessageRow(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    name = "Cindy",
                    presenceMemberId = MemberId("member-2"),
                    unreadCountKnown = true
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
}
