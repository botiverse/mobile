package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import build.raft.app.ShellNavigationIntakeFixtures.SERVER
import build.raft.app.ShellNavigationIntakeFixtures.channelRoute
import build.raft.app.ShellNavigationIntakeFixtures.notificationIntent
import build.raft.app.ShellNavigationIntakeFixtures.pendingTask
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * #4260: on a phone a conversation is a page over the app shell, and a covered shell does not
 * recompose. A message link or a notification open must still be taken and opened then, with
 * nothing from the shell's composition (no effect re-run, no [RaftShellNavigationIntake.wake]
 * from it), and never pushed twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftShellNavigationIntakeTest {
    private val inbox = RaftMessageLinkInbox(nowEpochMillis = { 1_000L })
    private val coordinator = SharedWindowNavigationCoordinator()
    private val artifactInbox = RaftArtifactLinkInbox(nowEpochMillis = { 1_000L })
    private val externalInviteInbox = RaftExternalInviteLinkInbox(nowEpochMillis = { 1_000L })
    private val intake = RaftShellNavigationIntake(coordinator, inbox, artifactInbox, externalInviteInbox)
    private val host = FakeShellNavigationIntakeHost()

    @Test
    fun aPendingLinkIsTakenAndOpenedWhileTheShellIsNotComposing() = intakeTest {
        // The link arrives while a conversation page covers the shell: only the platform ingress runs.
        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        advanceUntilIdle()

        assertNull(inbox.pending.value, "the link was taken")
        val open = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertEquals(MessageLinkNavigationSource, open.intent.source)
        assertEquals("channel-1", open.request.conversationIdentity.channelId?.value)
        assertEquals("message-9", open.request.anchorMessageId)
    }

    @Test
    fun aNotificationOpenIsOpenedWhileTheShellIsNotComposing() = intakeTest {
        // The notification open event's handler (outside composition) queues it; nothing else runs.
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()

        val open = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertEquals("system_notification", open.intent.source)
        assertEquals("message-1", open.request.anchorMessageId)
    }

    @Test
    fun repeatedWakesAndTheSameNotificationTwiceOpenOnce() = intakeTest {
        assertTrue(intake.enqueue(notificationIntent()))
        assertFalse(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        // The shell composes again (Back) and wakes the intake on its state changes.
        repeat(3) {
            intake.wake()
            advanceUntilIdle()
        }

        assertEquals(1, host.performed.size)
    }

    @Test
    fun anOpenWhoseConversationIsAlreadyTheTopPageIsNotPushedAgain() = intakeTest {
        host.topPage = channelRoute(host)

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        // And again when the shell composes (Back to it, a state change).
        repeat(2) {
            intake.wake()
            advanceUntilIdle()
        }

        assertTrue(host.performed.isEmpty(), "both target the page on top: ${host.performed}")
        assertEquals(2, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
    }

    @Test
    fun aLinkWaitsForASessionAndOpensOnceTheShellSaysItIsReady() = intakeTest {
        host.sessionReady = false

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "android_view")
        advanceUntilIdle()
        assertTrue(host.performed.isEmpty())
        assertTrue(inbox.pending.value != null, "kept while signed out")

        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
    }

    @Test
    fun anArtifactLinkWaitsForAReadySessionThenOpensInTheViewerOnce() = intakeTest {
        host.sessionReady = false
        val target = ArtifactLinkTarget("pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11", 3)
        artifactInbox.submit(target, "android_view")
        advanceUntilIdle()
        assertTrue(host.openedArtifacts.isEmpty(), "no viewer before the session is ready")

        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertEquals(listOf(target), host.openedArtifacts.map { it.target })
        assertNull(artifactInbox.pending.value)
        assertTrue(host.performed.isEmpty(), "an Artifact link opens no conversation")

        intake.wake()
        advanceUntilIdle()
        assertEquals(1, host.openedArtifacts.size, "taken once")
    }

    /** [body] with the intake running (a foreground child, so advanceUntilIdle runs it), stopped after. */
    @Test
    fun anExternalInviteLinkWaitsForAReadySessionThenOpensItsPageOnce() = intakeTest {
        host.sessionReady = false
        externalInviteInbox.submit("tok-123", "android_view")
        advanceUntilIdle()
        assertTrue(host.openedInvites.isEmpty(), "no invite page before the session is ready")
        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertEquals(listOf("tok-123"), host.openedInvites.map { it.token })
        assertNull(externalInviteInbox.pending.value)
        assertTrue(host.performed.isEmpty(), "an invite link opens no conversation")
        intake.wake()
        advanceUntilIdle()
        assertEquals(1, host.openedInvites.size, "taken once")
    }

    @Test
    fun aSwitchAccountLinkWaitsUntilAnotherAccountIsSignedIn() = intakeTest {
        // Switch account: the shell still has the account being signed out of.
        externalInviteInbox.submit("tok-9", "external_invite_switch_account", notForAccountId = "account-a")
        intake.wake()
        advanceUntilIdle()
        assertTrue(host.openedInvites.isEmpty(), "not reopened for the account it isn't for")
        assertEquals("tok-9", externalInviteInbox.pending.value?.token)

        host.accountId = null
        intake.wake()
        advanceUntilIdle()
        assertTrue(host.openedInvites.isEmpty(), "nobody known yet: keep waiting")

        host.accountId = "account-b"
        intake.wake()
        advanceUntilIdle()
        assertEquals(listOf("tok-9"), host.openedInvites.map { it.token })
    }

    @Test
    fun aTaskSurfaceOverTheTopPageClosesAndALinkIntoTheConversationUnderItStillOpensAtItsMessage() = intakeTest {
        // A pending task's page is open over #general (a window dialog, not a page): the visible
        // route is still #general's.
        host.topPage = channelRoute(host)
        val cover = RaftIncomingNavigationSignal()
        host.cover = cover
        val closes = mutableListOf<Int>()
        var open = true
        cover.register {
            closes += host.performed.size
            open.also { open = false }
        }

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        advanceUntilIdle()

        val opened = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single(), "not skipped as the top page")
        assertEquals("message-9", opened.request.anchorMessageId)
        assertEquals(listOf(0), closes, "the surface closed before the open")
        assertTrue(host.logs.none { it.startsWith("window_navigation_open_skipped") })

        // Nothing over the page any more: the same open is the top page again.
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        assertEquals(1, host.performed.size)
        assertEquals(1, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })

        // An Artifact link closes it too.
        open = true
        artifactInbox.submit(ArtifactLinkTarget("pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11", 3), "android_view")
        advanceUntilIdle()
        assertEquals(1, host.openedArtifacts.size)
        assertFalse(open, "closed before the viewer opened")
    }

    @Test
    fun anOpenWaitsForARunningFirstSendThenClosesThePendingTaskKeepingItsDraft() = intakeTest {
        val cover = RaftIncomingNavigationSignal()
        host.cover = cover
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
        cover.register { flow.closeForIncomingNavigation() }
        val task = pendingTask()
        flow.open(TaskOpenSurface.Pending(task))
        val answer = CompletableDeferred<ThreadApiResult<Unit>>()
        val sending =
            launch {
                flow.send(
                    text = "On it",
                    serverId = SERVER,
                    tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                    sendMessage = { _, _, _ -> answer.await() },
                    currentServerId = { SERVER },
                    fallbackError = "Couldn't send the message",
                )
            }
        advanceUntilIdle()
        assertTrue(flow.sending)

        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        assertTrue(host.performed.isEmpty(), "the send is not cut off: the open waits")
        assertEquals(task, flow.surface?.task)

        answer.complete(ThreadApiResult.Failure(code = 504))
        advanceUntilIdle()
        sending.join()
        advanceUntilIdle()
        assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertNull(flow.surface, "closed once the send ended")
        assertEquals("On it", flow.draft(task.id.value), "the unsent text stays as the draft")
    }

    private fun intakeTest(body: suspend TestScope.() -> Unit) = runTest {
        val running = launch { intake.run(host) }
        advanceUntilIdle()
        body()
        running.cancel()
    }

    /** POST /tasks/:id/conversation creates the conversation. */
    private class ConversationApi : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: build.raft.app.data.DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
            HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}""")
    }
}

/** The app shell as [RaftShellNavigationIntake] sees it, recording what it opens. */
internal class FakeShellNavigationIntakeHost : RaftShellNavigationIntakeHost {
    var sessionReady = true
    var topPage: ThreadRouteIdentity? = null
    /** The window's task surfaces; null: none registered. */
    var cover: RaftIncomingNavigationSignal? = null
    val performed = mutableListOf<WindowNavigationAction>()
    val openedArtifacts = mutableListOf<RaftPendingArtifactLink>()
    val openedInvites = mutableListOf<RaftPendingExternalInviteLink>()
    var accountId: String? = "account-a"
    val logs = mutableListOf<String>()
    /** Runs after each [perform] is recorded (a UI test shows the opened page). */
    var onPerform: (WindowNavigationAction) -> Unit = {}

    override fun messageLinkSessionReady(): Boolean = sessionReady

    override suspend fun resolveMessageLink(taken: RaftPendingMessageLink): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = SERVER,
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = taken.link.messageId,
                    channelId = taken.link.channelId,
                    threadChannelId = taken.link.channelId,
                    conversationKind = ConversationKind.Channel,
                ),
            source = MessageLinkNavigationSource,
            launchId = "message-link-${taken.id}",
        )

    override fun openArtifactLink(taken: RaftPendingArtifactLink) {
        openedArtifacts += taken
    }

    override fun openExternalInviteLink(taken: RaftPendingExternalInviteLink) {
        openedInvites += taken
    }

    override fun currentAccountId(): String? = accountId

    override fun readiness(): WindowNavigationReadiness =
        WindowNavigationReadiness(
            knownServerIds = setOf(SERVER),
            canonicalServerId = SERVER,
            homeSnapshotServerId = SERVER,
            homeSessionServerId = SERVER,
            conversationDirectory = ShellNavigationIntakeFixtures.directory(),
        )

    override fun topPageRoute(): ThreadRouteIdentity? = topPage

    override suspend fun closeIncomingNavigationCover(): Boolean = cover?.closeAll() ?: false

    override suspend fun perform(action: WindowNavigationAction) {
        performed += action
        onPerform(action)
    }

    override fun log(message: String) {
        logs += message
    }
}

internal object ShellNavigationIntakeFixtures {
    const val SERVER = "server-a"

    /** A notification open of [messageId] in [channelId] (#general or #random). */
    fun notificationIntent(channelId: String = "channel-1", messageId: String = "message-1"): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = SERVER,
            source = "system_notification",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = messageId,
                    channelId = channelId,
                    threadChannelId = channelId,
                    conversationKind = ConversationKind.Channel,
                ),
        )

    /** #general's page route, as its ThreadPage reports it visible. */
    fun channelRoute(host: RaftShellNavigationIntakeHost): ThreadRouteIdentity {
        val probe = SharedWindowNavigationCoordinator()
        probe.enqueue(notificationIntent())
        val open = assertIs<WindowNavigationAction.OpenConversation>(probe.advance(host.readiness()!!))
        return open.request.conversationIdentity.resolveThreadRouteIdentity()
    }

    /** A v2 task in #general whose conversation is not created yet (no message in it). */
    fun pendingTask(): TaskSummary {
        val json =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"t-1","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,
               "messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}"""
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    fun directory(): SharedConversationDirectoryState =
        listOf("channel-1" to "General", "channel-2" to "Random").map { (id, title) ->
            SharedConversationDirectoryProjection(
                serverId = SERVER,
                canonicalChannelId = id,
                aliases = setOf(id),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                title = title,
            )
        }.toSharedConversationDirectoryState()
}
