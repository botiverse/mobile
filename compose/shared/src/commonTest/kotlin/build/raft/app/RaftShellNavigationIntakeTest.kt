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
    private val intake = RaftShellNavigationIntake(coordinator, inbox, artifactInbox)
    private val host = FakeHost()

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
        host.topPage = channelRoute()

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
    private fun intakeTest(body: suspend TestScope.() -> Unit) = runTest {
        val running = launch { intake.run(host) }
        advanceUntilIdle()
        body()
        running.cancel()
    }

    private fun channelRoute(): ThreadRouteIdentity {
        val probe = SharedWindowNavigationCoordinator()
        probe.enqueue(notificationIntent())
        val open = assertIs<WindowNavigationAction.OpenConversation>(probe.advance(host.readiness()))
        return open.request.conversationIdentity.resolveThreadRouteIdentity()
    }

    private class FakeHost : RaftShellNavigationIntakeHost {
        var sessionReady = true
        var topPage: ThreadRouteIdentity? = null
        val performed = mutableListOf<WindowNavigationAction>()
        val openedArtifacts = mutableListOf<RaftPendingArtifactLink>()
        val logs = mutableListOf<String>()

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

        override fun readiness(): WindowNavigationReadiness =
            WindowNavigationReadiness(
                knownServerIds = setOf(SERVER),
                canonicalServerId = SERVER,
                homeSnapshotServerId = SERVER,
                homeSessionServerId = SERVER,
                conversationDirectory = directory(),
            )

        override fun topPageRoute(): ThreadRouteIdentity? = topPage

        override suspend fun perform(action: WindowNavigationAction) {
            performed += action
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private companion object {
        const val SERVER = "server-a"

        fun notificationIntent(): WindowNavigationIntent =
            WindowNavigationIntent(
                serverId = SERVER,
                source = "system_notification",
                sourceIdentity =
                    ConversationOpenSourceMessageIdentity(
                        source = ConversationOpenSource.Notification,
                        messageId = "message-1",
                        channelId = "channel-1",
                        threadChannelId = "channel-1",
                        conversationKind = ConversationKind.Channel,
                    ),
            )

        fun directory(): SharedConversationDirectoryState =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = SERVER,
                    canonicalChannelId = "channel-1",
                    aliases = setOf("channel-1"),
                    conversationKind = ConversationKind.Channel,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftThreadSurfaceKind.Channel,
                    title = "General",
                )
            ).toSharedConversationDirectoryState()
    }
}
