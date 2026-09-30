package build.raft.app.data

import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedWindowNavigationCoordinatorTest {
    @Test
    fun activatesTargetServerBeforeStartingHomeOrOpeningRoute() {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent = notificationIntent(serverId = "server-b")

        assertTrue(coordinator.enqueue(intent))
        assertIs<WindowNavigationAction.ActivateServer>(
            coordinator.advance(readiness(canonicalServerId = "server-a"))
        )
        // The activation command is one-shot while the scoped auth session catches up.
        assertNull(coordinator.advance(readiness(canonicalServerId = "server-a")))

        assertIs<WindowNavigationAction.EnsureHome>(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-b",
                    homeSnapshotServerId = "server-a",
                    homeSessionServerId = "server-a"
                )
            )
        )
        // #599's server-qualified gate must not open against a stale A projection.
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-b",
                    homeSnapshotServerId = "server-a",
                    homeSessionServerId = "server-b"
                )
            )
        )

        val open =
            assertIs<WindowNavigationAction.OpenConversation>(
                coordinator.advance(
                    readiness(
                        canonicalServerId = "server-b",
                        homeSnapshotServerId = "server-b",
                        homeSessionServerId = "server-b",
                        conversationDirectory = directoryFor("server-b")
                    )
                )
            )
        assertEquals("channel-1", open.request.conversationIdentity.channelId?.value)
        assertEquals("message-1", open.request.anchorMessageId)
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-b",
                    homeSnapshotServerId = "server-b",
                    homeSessionServerId = "server-b",
                    conversationDirectory = directoryFor("server-b")
                )
            )
        )
    }

    @Test
    fun activityRecreateAndDuplicatePendingIntentOpenOnlyOnceForTheWindowScope() {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent = notificationIntent(serverId = "server-b")
        assertTrue(coordinator.enqueue(intent))
        assertFalse(coordinator.enqueue(intent))
        assertEquals(1, coordinator.requestCount)

        val ready =
            readiness(
                canonicalServerId = "server-b",
                homeSnapshotServerId = "server-b",
                homeSessionServerId = "server-b",
                conversationDirectory = directoryFor("server-b")
            )
        assertIs<WindowNavigationAction.OpenConversation>(coordinator.advance(ready))
        assertNull(coordinator.advance(ready))
    }

    @Test
    fun readyHomeWaitsForDirectoryHydrationThenOpensExactlyOnce() {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent = hydratingThreadNotificationIntent(serverId = "server-b")
        assertTrue(coordinator.enqueue(intent))

        val homeReadyWithoutDirectory =
            readiness(
                canonicalServerId = "server-b",
                homeSnapshotServerId = "server-b",
                homeSessionServerId = "server-b"
            )
        // A canonical Home projection can arrive ahead of the conversation directory. This is
        // retryable rather than terminal, so a later directory emission still opens the route.
        assertNull(coordinator.advance(homeReadyWithoutDirectory))
        assertEquals(1, coordinator.requestCount)

        val directoryHydrated =
            homeReadyWithoutDirectory.copy(conversationDirectory = hydratedThreadDirectoryFor("server-b"))
        assertIs<WindowNavigationAction.OpenConversation>(coordinator.advance(directoryHydrated))
        assertNull(coordinator.advance(directoryHydrated))
    }

    @Test
    fun unavailableServerAndActivationFailureAreRejectedOnce() {
        val unavailableCoordinator = SharedWindowNavigationCoordinator()
        val unavailableIntent = notificationIntent(serverId = "server-missing")
        assertTrue(unavailableCoordinator.enqueue(unavailableIntent))
        val unavailable =
            assertIs<WindowNavigationAction.Reject>(
                unavailableCoordinator.advance(
                    readiness(canonicalServerId = "server-a", knownServerIds = setOf("server-a", "server-b"))
                )
            )
        assertEquals("server_unavailable", unavailable.reason)
        assertNull(unavailableCoordinator.advance(readiness(canonicalServerId = "server-a")))

        val failedCoordinator = SharedWindowNavigationCoordinator()
        val failedIntent = notificationIntent(serverId = "server-b")
        failedCoordinator.enqueue(failedIntent)
        val activation = assertIs<WindowNavigationAction.ActivateServer>(failedCoordinator.advance(readiness("server-a")))
        val rejected = assertIs<WindowNavigationAction.Reject>(failedCoordinator.rejectActivation(activation.intent.key, "activation_failed"))
        assertEquals("activation_failed", rejected.reason)
        assertNull(failedCoordinator.rejectActivation(activation.intent.key, "activation_failed"))
    }

    @Test
    fun windowScopesKeepReadinessAndOpenStateIsolatedThenReleaseClearsTheOldQueue() {
        val cache = SlockShellScopedStoreCache<SharedWindowNavigationCoordinator>()
        val shellA = SlockShellStoreScope.create("window-a", "server-a")
        val shellB = SlockShellStoreScope.create("window-b", "server-b")
        val coordinatorA = cache.getOrPut(shellA) { SharedWindowNavigationCoordinator() }
        val coordinatorB = cache.getOrPut(shellB) { SharedWindowNavigationCoordinator() }
        val intentA = notificationIntent("server-a")
        val intentB = notificationIntent("server-b")

        assertTrue(coordinatorA.enqueue(intentA))
        assertTrue(coordinatorB.enqueue(intentB))
        assertEquals(1, coordinatorA.requestCount)
        assertEquals(1, coordinatorB.requestCount)
        assertIs<WindowNavigationAction.OpenConversation>(
            coordinatorA.advance(readyReadiness("server-a"))
        )
        // A's canonical server and directory never touch B's pending intent.
        assertEquals(1, coordinatorB.requestCount)
        assertIs<WindowNavigationAction.OpenConversation>(
            coordinatorB.advance(readyReadiness("server-b"))
        )

        cache.remove(shellA)?.clear()
        assertEquals(0, coordinatorA.requestCount)
        // A late coroutine after the real window close cannot revive the old coordinator.
        assertFalse(coordinatorA.enqueue(intentA))
        assertNull(coordinatorA.advance(readyReadiness("server-a")))
        assertNull(coordinatorA.rejectActivation(intentA.key, "late_activation_failure"))
        val recreatedA = cache.getOrPut(shellA) { SharedWindowNavigationCoordinator() }
        assertTrue(recreatedA.enqueue(intentA))
        assertEquals(1, recreatedA.requestCount)
        assertIs<WindowNavigationAction.OpenConversation>(
            recreatedA.advance(readyReadiness("server-a"))
        )
    }

    @Test
    fun rejectionInOneWindowCannotConsumeAnotherWindowsIntent() {
        val cache = SlockShellScopedStoreCache<SharedWindowNavigationCoordinator>()
        val coordinatorA = cache.getOrPut(SlockShellStoreScope.create("window-a", "server-a")) {
            SharedWindowNavigationCoordinator()
        }
        val coordinatorB = cache.getOrPut(SlockShellStoreScope.create("window-b", "server-b")) {
            SharedWindowNavigationCoordinator()
        }
        coordinatorA.enqueue(notificationIntent("server-missing"))
        coordinatorB.enqueue(notificationIntent("server-b"))

        assertIs<WindowNavigationAction.Reject>(
            coordinatorA.advance(readiness(canonicalServerId = "server-a", knownServerIds = setOf("server-a")))
        )
        assertIs<WindowNavigationAction.OpenConversation>(
            coordinatorB.advance(readyReadiness("server-b"))
        )
    }

    // task #13 external deep-link CURRENT-BEHAVIOR pins (interface v3.1 §2):
    // today's coordinator treats "empty knownServerIds" as the not-ready signal.
    // These tests document that existing semantic; the target contract adds
    // serverListState so a legitimately loaded-empty list can still reject.

    @Test
    fun externalLinkStaysPendingUntilServerListHydratesThenOpensOnce() {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent = externalLinkIntent(serverId = "server-b")
        assertTrue(coordinator.enqueue(intent))

        // Session still resolving: an empty known-server list must not trip the
        // server_unavailable guard, and no canonical server means no action at all.
        val emptyReadiness = readiness(canonicalServerId = null, knownServerIds = emptySet())
        assertNull(coordinator.advance(emptyReadiness))
        assertEquals(1, coordinator.requestCount)

        // Server list hydrated but canonical server not yet selected: still pending.
        assertNull(
            coordinator.advance(
                readiness(canonicalServerId = null, knownServerIds = setOf("server-a", "server-b"))
            )
        )
        assertEquals(1, coordinator.requestCount)

        // Canonical server lands on another server: one-shot activation, not a reject.
        assertIs<WindowNavigationAction.ActivateServer>(
            coordinator.advance(readiness(canonicalServerId = "server-a"))
        )
        assertNull(coordinator.advance(readiness(canonicalServerId = "server-a")))

        // Readiness converged: opens exactly once.
        assertIs<WindowNavigationAction.OpenConversation>(
            coordinator.advance(readyReadiness("server-b"))
        )
        assertNull(coordinator.advance(readyReadiness("server-b")))
    }

    @Test
    fun externalLinkRejectsUnknownServerOnlyAfterListHydration() {
        // The server_unavailable guard engages the moment the known-server list is
        // non-empty, even before a canonical session exists. Pending therefore only
        // holds while the list is still empty (auth/session not resolved yet).
        val preAuth = SharedWindowNavigationCoordinator()
        preAuth.enqueue(externalLinkIntent(serverId = "server-missing"))
        assertNull(
            preAuth.advance(readiness(canonicalServerId = null, knownServerIds = emptySet()))
        )
        assertEquals(1, preAuth.requestCount)
        assertIs<WindowNavigationAction.Reject>(
            preAuth.advance(
                readiness(canonicalServerId = null, knownServerIds = setOf("server-a", "server-b"))
            )
        )
        assertNull(
            preAuth.advance(
                readiness(canonicalServerId = "server-a", knownServerIds = setOf("server-a", "server-b"))
            )
        )

        // List still empty but a canonical session exists on another server: the
        // coordinator requests activation (single-shot) rather than rejecting blind;
        // the host's selectServer resolves the slug authoritatively and failure is
        // reported through rejectActivation exactly once.
        val blind = SharedWindowNavigationCoordinator()
        blind.enqueue(externalLinkIntent(serverId = "server-missing"))
        val activation =
            assertIs<WindowNavigationAction.ActivateServer>(
                blind.advance(readiness(canonicalServerId = "server-a", knownServerIds = emptySet()))
            )
        assertNull(blind.advance(readiness(canonicalServerId = "server-a", knownServerIds = emptySet())))
        val rejected =
            assertIs<WindowNavigationAction.Reject>(
                blind.rejectActivation(activation.intent.key, "server_unavailable")
            )
        assertEquals("server_unavailable", rejected.reason)
        assertNull(blind.rejectActivation(activation.intent.key, "server_unavailable"))
    }



    @Test
    fun externalLinkReplyPermalinkResolvesThreadIdentity() {
        val coordinator = SharedWindowNavigationCoordinator()
        val intent = externalLinkReplyIntent(serverId = "server-b")
        assertTrue(coordinator.enqueue(intent))
        assertFalse(coordinator.enqueue(intent))

        val ready =
            readiness(
                canonicalServerId = "server-b",
                homeSnapshotServerId = "server-b",
                homeSessionServerId = "server-b",
                conversationDirectory = hydratedThreadDirectoryFor("server-b")
            )
        val action = assertIs<WindowNavigationAction.OpenConversation>(coordinator.advance(ready))
        assertNull(coordinator.advance(ready))
        // Thread-shaped permalink: lookup resolves the reply stream, not the parent channel.
        assertEquals(
            ConversationKind.Thread,
            action.request.conversationIdentity.kind
        )
    }

    // Target-contract tests for the external-link interface (v3.1): these pin
    // semantics the coordinator does not yet have. They are intentionally
    // ignored until WindowNavigationReadiness gains serverListState and
    // WindowNavigationIntent gains activationId/authGeneration.

    @Test
    @kotlin.test.Ignore
    fun externalLinkLoadedEmptyServerListRejects() {
        // Target contract: a successfully loaded, legitimately empty server list
        // must terminate the intent (there is provably no such server), unlike
        // a list that is merely still empty because it has not loaded.
        // Requires: WindowNavigationReadiness.serverListState = Loaded with
        // knownServerIds empty -> Reject("server_unavailable"), exactly once.
    }

    @Test
    @kotlin.test.Ignore
    fun externalLinkFailedListKeepsPendingAndDropsStaleSnapshot() {
        // Target contract: serverListState = Failed (even when a stale non-empty
        // knownServerIds snapshot is carried) must NOT reject as
        // server_unavailable; the intent stays pending until a Loaded emission.
        // Requires: readiness carries serverListState explicitly; Failed ->
        // advance returns null and no Reject, stale list never consulted.
    }

    @Test
    @kotlin.test.Ignore
    fun externalLinkDuplicateActivationReAcksAndNewActivationOpensAgain() {
        // Target contract: re-delivery of the same activationId is a no-op open
        // but still re-emits Accepted to the SAME shellScopeId; a genuinely new
        // user click carries a new activationId and opens again even after a
        // terminal state. Requires: activationId inside WindowNavigationIntent.key.
    }

    @Test
    fun homeReadyRequiresCanonicalAndBothHomeServerProjections() {
        assertFalse(windowNavigationHomeReady("server-b", "server-a", "server-b", "server-b"))
        assertFalse(windowNavigationHomeReady("server-b", "server-b", "server-a", "server-b"))
        assertFalse(windowNavigationHomeReady("server-b", "server-b", "server-b", "server-a"))
        assertTrue(windowNavigationHomeReady("server-b", "server-b", "server-b", "server-b"))
    }

    private fun externalLinkIntent(serverId: String): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = serverId,
            source = "external_link",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Markdown,
                    messageId = "message-1",
                    channelId = "channel-1",
                    threadChannelId = "channel-1",
                    conversationKind = ConversationKind.Channel
                )
        )

    private fun externalLinkReplyIntent(serverId: String): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = serverId,
            source = "external_link",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Markdown,
                    messageId = "reply-1",
                    channelId = "parent-channel",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-1",
                    conversationKind = ConversationKind.Thread,
                    parentConversationKind = ConversationKind.Channel
                )
        )

    private fun notificationIntent(serverId: String): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = serverId,
            source = "system_notification",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = "message-1",
                    channelId = "channel-1",
                    threadChannelId = "channel-1",
                    conversationKind = ConversationKind.Channel
                )
        )

    private fun readiness(
        canonicalServerId: String?,
        homeSnapshotServerId: String? = null,
        homeSessionServerId: String? = null,
        knownServerIds: Set<String> = setOf("server-a", "server-b"),
        conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
    ): WindowNavigationReadiness =
        WindowNavigationReadiness(
            knownServerIds = knownServerIds,
            canonicalServerId = canonicalServerId,
            homeSnapshotServerId = homeSnapshotServerId,
            homeSessionServerId = homeSessionServerId,
            conversationDirectory = conversationDirectory
        )

    private fun readyReadiness(serverId: String): WindowNavigationReadiness =
        readiness(
            canonicalServerId = serverId,
            homeSnapshotServerId = serverId,
            homeSessionServerId = serverId,
            conversationDirectory = directoryFor(serverId)
        )

    private fun directoryFor(serverId: String): SharedConversationDirectoryState =
        listOf(
            SharedConversationDirectoryProjection(
                serverId = serverId,
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = KuiklyThreadSurfaceKind.Channel,
                title = "General"
            )
        ).toSharedConversationDirectoryState()

    private fun hydratingThreadNotificationIntent(serverId: String): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = serverId,
            source = "system_notification",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = "reply-1",
                    channelId = "reply-stream",
                    threadChannelId = "reply-stream",
                    conversationKind = ConversationKind.Thread
                )
        )

    private fun hydratedThreadDirectoryFor(serverId: String): SharedConversationDirectoryState =
        listOf(
            SharedConversationDirectoryProjection(
                serverId = serverId,
                canonicalChannelId = "parent-channel",
                aliases = setOf("parent-channel"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = KuiklyThreadSurfaceKind.Channel,
                title = "General"
            ),
            SharedConversationDirectoryProjection(
                serverId = serverId,
                canonicalChannelId = "reply-stream",
                aliases = setOf("reply-stream"),
                conversationKind = ConversationKind.Thread,
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                channelKind = KuiklyThreadSurfaceKind.ReplyThread,
                title = "Reply",
                parentChannelId = "parent-channel",
                parentMessageId = "parent-message"
            )
        ).toSharedConversationDirectoryState()
}
