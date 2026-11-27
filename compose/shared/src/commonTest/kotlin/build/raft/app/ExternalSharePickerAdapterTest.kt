package build.raft.app

import build.raft.app.data.ExternalShareEnvelope
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareServerOption
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.thread.RaftForwardPickerLoadState
import build.raft.app.thread.RaftForwardTargetPickerIntent
import build.raft.app.thread.resolvedAuxiliaryThreadId
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.thread.toInitialPayload
import build.raft.app.thread.toRaftForwardTargetPickerItem
import build.raft.app.threadComposerDraftKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalSharePickerAdapterTest {
    @Test
    fun localDebouncedQueryKeepsTextButDisablesStaleRowsUntilStoreCatchesUp() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 7)

        val pending = selecting.toExternalSharePickerSurface(queryOverride = "search")

        assertEquals("search", pending.picker.query)
        assertEquals(RaftForwardPickerLoadState.Loading, pending.picker.loadState)
        assertFalse(pending.picker.interactive)
    }

    @Test
    fun staleOrTamperedRowTapFailsClosed() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 9)
        val rendered = first.toRaftForwardTargetPickerItem()

        assertNull(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 8)
            )
        )
        assertNull(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(
                    rendered.copy(payload = target("channel-other")),
                    renderGeneration = 9
                )
            )
        )
        assertIs<ExternalShareIntent.ToggleTarget>(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 9)
            )
        )
    }

    @Test
    fun concreteTargetMapsToTypedConversation() {
        val destination = target("channel-a")
        val identity = destination.toExternalShareConversationIdentityOrNull()
            ?: error("expected identity")
        assertEquals(ChannelId("channel-a"), identity.channelId)
        assertEquals(destination.channelKind, identity.kind)
    }

    @Test
    fun conversationTargetsExposePickerGroupAndAcceptSelectionOnlyWhenQueryBlank() {
        val channel = target("channel-a")
        val thread = threadTarget("thread-5", parentMessageId = MessageId("parent-5"))
        val task = taskTarget("task-7")
        val selecting = state(targets = listOf(channel), conversationTargets = listOf(thread, task), generation = 4)

        val surface = selecting.toExternalSharePickerSurface()
        assertEquals(listOf("thread-5", "task-7"), surface.picker.conversationItems.map { it.payload.id })
        assertEquals(listOf("thread-5", "task-7"), surface.picker.visibleConversationItems.map { it.payload.id })

        val threadItem = thread.toRaftForwardTargetPickerItem()
        val taskItem = task.toRaftForwardTargetPickerItem()
        val selectedThread =
            assertIs<ExternalShareIntent.ToggleTarget>(
                selecting.toExternalShareIntent(RaftForwardTargetPickerIntent.TargetSelected(threadItem, renderGeneration = 4))
            )
        assertEquals(thread, selectedThread.target)
        val selectedTask =
            assertIs<ExternalShareIntent.ToggleTarget>(
                selecting.toExternalShareIntent(RaftForwardTargetPickerIntent.TargetSelected(taskItem, renderGeneration = 4))
            )
        assertEquals(task, selectedTask.target)

        // During search mode, conversationItems are hidden and stale taps against conversationTargets fail closed.
        val searching = selecting.copy(query = "abc", targets = listOf(channel))
        assertEquals(emptyList(), searching.toExternalSharePickerSurface().picker.visibleConversationItems)
        assertNull(searching.toExternalShareIntent(RaftForwardTargetPickerIntent.TargetSelected(threadItem, renderGeneration = 4)))
    }

    @Test
    fun replyThreadAndTaskConversationBuildValidComposerLaunchPayloadsAndDraftKeys() {
        val dm =
            ForwardMessageTarget(
                serverId = ServerId("server"),
                kind = ForwardMessageTargetKind.RecentDirectMessage,
                id = "dm-1",
                title = "Alice",
                channelId = ChannelId("dm-1"),
                channelKind = ConversationKind.DirectMessage,
                channelType = "dm"
            )
        val dmIdentity = assertNotNull(dm.toExternalShareConversationIdentityOrNull())
        val dmPayload = assertNotNull(buildStandaloneConversationThreadLaunchPayload(dmIdentity, timeContext = null))
        assertEquals(RaftThreadSurfaceKind.DirectMessage, dmPayload.surfaceKind)
        assertEquals("dm-1", dmPayload.streamChannelId)
        assertTrue(!dmPayload.threadComposerDraftKey().isNullOrBlank())

        val task = taskTarget("task-conv-7")
        val taskIdentity = assertNotNull(task.toExternalShareConversationIdentityOrNull())
        val taskPayload = assertNotNull(buildStandaloneConversationThreadLaunchPayload(taskIdentity, timeContext = null))
        assertEquals(ConversationKind.Task, taskIdentity.kind)
        assertEquals(RaftThreadSurfaceKind.Channel, taskPayload.surfaceKind)
        assertEquals(RaftTaskConversationChannelKind, taskPayload.channelKind)
        assertEquals("task-conv-7", taskPayload.streamChannelId)
        assertEquals("send=task-conv-7|parent=_|mention=task-conv-7", taskPayload.threadComposerDraftKey())

        // Reply thread in a private channel.
        val privateThread =
            threadTarget(
                id = "thread-5",
                parentChannelType = "private",
                parentMessageId = MessageId("parent-5")
            )
        val threadIdentity = assertNotNull(privateThread.toExternalShareConversationIdentityOrNull())
        val threadPayload =
            assertNotNull(
                buildStandaloneConversationThreadLaunchPayload(
                    conversation = threadIdentity,
                    timeContext = null,
                    originFallback = privateThread.toExternalShareOriginFallbackOrNull()
                )
            )
        assertEquals(ConversationKind.Thread, threadIdentity.kind)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, threadPayload.surfaceKind)
        assertEquals("thread-5", threadPayload.streamChannelId)
        assertEquals("room-1", threadPayload.parentChannelId)
        assertEquals("parent-5", threadPayload.parentMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, threadPayload.parentSurfaceKind)
        assertEquals("private", threadPayload.parentChannelKind)
        assertEquals("topic preview", threadPayload.originFallbackPreview)
        assertEquals("send=thread-5|parent=parent-5|mention=room-1", threadPayload.threadComposerDraftKey())
        // Verify the staged draft key matches the exact key ThreadPage computes once loaded.
        val loadedThreadPayload = threadPayload.toInitialPayload(threadPayload.instanceId).copy(status = "ready")
        val loadedThreadAuxId = loadedThreadPayload.resolvedAuxiliaryThreadId()
        assertEquals(
            threadPayload.threadComposerDraftKey(),
            loadedThreadPayload
                .resolveThreadComposer()
                .copy(streamChannelId = loadedThreadAuxId)
                .toThreadComposerSurface(
                    channelId = loadedThreadAuxId.orEmpty(),
                    parentChannelId = loadedThreadPayload.parentChannelId
                )
                .threadComposerDraftKey()
        )

        // Reply thread in a DM preserves DM parent surface & channel kind.
        val dmThread =
            threadTarget(
                id = "dm-thread-9",
                parentChannelId = ChannelId("dm-1"),
                parentChannelType = "dm",
                parentMessageId = MessageId("parent-9")
            )
        val dmThreadIdentity = assertNotNull(dmThread.toExternalShareConversationIdentityOrNull())
        val dmThreadPayload = assertNotNull(buildStandaloneConversationThreadLaunchPayload(dmThreadIdentity, timeContext = null))
        assertEquals(RaftThreadSurfaceKind.ReplyThread, dmThreadPayload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, dmThreadPayload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, dmThreadPayload.parentChannelKind)
        assertEquals("send=dm-thread-9|parent=parent-9|mention=dm-1", dmThreadPayload.threadComposerDraftKey())

        // Reply thread missing parentMessageId fails closed.
        assertNull(threadTarget("thread-incomplete", parentMessageId = null).toExternalShareConversationIdentityOrNull())

        // Joint channel & joint reply-thread targets carry jointChannelId into launch payload and shared draft key.
        val jointChannel =
            ForwardMessageTarget(
                serverId = ServerId("server-a"),
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "chan-joint-a",
                title = "wg-shared",
                channelId = ChannelId("chan-joint-a"),
                channelKind = ConversationKind.Joint,
                channelType = "joint",
                jointChannelId = "joint-42",
            )
        val jointIdentity = assertNotNull(jointChannel.toExternalShareConversationIdentityOrNull())
        val jointPayload = assertNotNull(buildStandaloneConversationThreadLaunchPayload(jointIdentity, timeContext = null))
        assertEquals("joint-42", jointIdentity.jointChannelId)
        assertEquals("joint-42", jointPayload.jointChannelId)
        assertEquals("send=chan-joint-a|parent=_|mention=chan-joint-a|joint=joint-42", jointPayload.threadComposerDraftKey())

        val jointThread =
            threadTarget(
                id = "thread-joint-a",
                parentChannelId = ChannelId("chan-joint-a"),
                parentChannelType = "joint",
                parentMessageId = MessageId("parent-42"),
            )
        val jointThreadIdentity = assertNotNull(jointThread.toExternalShareConversationIdentityOrNull(resolvedJointChannelId = "joint-42"))
        val jointThreadPayload = assertNotNull(buildStandaloneConversationThreadLaunchPayload(jointThreadIdentity, timeContext = null))
        assertEquals("joint-42", jointThreadIdentity.jointChannelId)
        assertEquals("joint-42", jointThreadPayload.jointChannelId)
        assertEquals("send=thread-joint-a|parent=parent-42|mention=chan-joint-a|joint=joint-42", jointThreadPayload.threadComposerDraftKey())
    }

    @Test
    fun serverOptionsMapToReusableServerSwitcherItemsWithAvatarsAndNoUnreadBadges() {
        val selecting =
            state(targets = listOf(target("channel-a")), generation = 1).copy(
                serverId = "srv-2",
                serverOptions =
                    listOf(
                        ExternalShareServerOption(
                            id = "srv-1",
                            title = "Botiverse",
                            slug = "botiverse",
                            avatarUrl = "https://cdn.example/botiverse.png",
                            publicPageLive = true
                        ),
                        ExternalShareServerOption(
                            id = "srv-2",
                            title = "Acme Labs",
                            slug = null,
                            avatarUrl = null,
                            publicPageLive = false
                        )
                    )
            )

        val switcherItems = selecting.toExternalShareServerSwitcherItems()

        assertEquals(2, switcherItems.size)
        val first = switcherItems[0]
        assertEquals("srv-1", first.id)
        assertEquals("Botiverse", first.name)
        assertEquals("botiverse", first.slug)
        assertEquals("https://cdn.example/botiverse.png", first.avatarUrl)
        assertTrue(first.publicPageLive)
        assertEquals(0, first.broaderUnreadCount)
        assertNull(first.activityUnreadCount)
        assertFalse(first.serverPushMuted)
        assertEquals("https://cdn.example/botiverse.png", first.toServerSwitcherAvatarPresentation().avatarWireValue)

        val second = switcherItems[1]
        assertEquals("srv-2", second.id)
        assertEquals("Acme Labs", second.name)
        assertEquals("", second.slug)
        assertNull(second.avatarUrl)
        assertFalse(second.publicPageLive)
        assertNull(second.toServerSwitcherAvatarPresentation().avatarWireValue)
        assertEquals("A", second.toServerSwitcherAvatarPresentation().fallbackLabel)

        // Control state resolves selected server and enables menu only when interactive with 2+ servers.
        val multiEnabled = assertNotNull(resolveRaftServerSwitcherControlState(switcherItems, "srv-2", enabled = true))
        assertEquals("srv-2", multiEnabled.selectedServer.id)
        assertTrue(multiEnabled.canOpenMenu)

        val multiLoading = assertNotNull(resolveRaftServerSwitcherControlState(switcherItems, "srv-2", enabled = false))
        assertFalse(multiLoading.canOpenMenu)

        val singleServer = assertNotNull(resolveRaftServerSwitcherControlState(listOf(first), "srv-1", enabled = true))
        assertEquals("srv-1", singleServer.selectedServer.id)
        assertFalse(singleServer.canOpenMenu)

        assertNull(resolveRaftServerSwitcherControlState(emptyList(), "srv-1", enabled = true))
    }

    private fun state(
        targets: List<ForwardMessageTarget>,
        conversationTargets: List<ForwardMessageTarget> = emptyList(),
        selected: List<ForwardMessageTarget> = emptyList(),
        generation: Int
    ): ExternalShareState.SelectingTargets =
        ExternalShareState.SelectingTargets(
            envelope = ExternalShareEnvelope("token", 1L, "android", text = "hello"),
            accountId = "account",
            serverId = "server",
            targets = targets,
            conversationTargets = conversationTargets,
            selectedTargets = selected,
            requestGeneration = generation
        )

    private fun target(id: String) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId(id)
        )

    private fun threadTarget(
        id: String,
        parentChannelId: ChannelId = ChannelId("room-1"),
        parentChannelType: String = "channel",
        parentMessageId: MessageId? = null
    ) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.ConversationThread,
            id = id,
            title = "#general › $id",
            channelId = ChannelId(id),
            channelKind = ConversationKind.Thread,
            channelType = "thread",
            parentChannelId = parentChannelId,
            parentChannelName = "general",
            parentChannelType = parentChannelType,
            parentMessageId = parentMessageId,
            parentMessagePreview = "topic preview"
        )

    private fun taskTarget(id: String) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.ConversationTask,
            id = id,
            title = "#general:#7 $id",
            channelId = ChannelId(id),
            channelKind = ConversationKind.Task,
            channelType = "task",
            parentChannelId = ChannelId("room-1"),
            parentChannelName = "general",
            parentChannelType = "channel",
            taskNumber = 7,
            taskTitle = id
        )
}
