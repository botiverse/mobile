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
        val emptyReadiness =
            readiness(
                canonicalServerId = null,
                knownServerIds = emptySet(),
                serverListState = WindowNavigationServerListState.Loading
            )
        assertNull(coordinator.advance(emptyReadiness))
        assertEquals(1, coordinator.requestCount)

        // Server list hydrated but canonical server not yet selected: still pending.
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = null,
                    knownServerIds = setOf("server-a", "server-b"),
                    serverListState = WindowNavigationServerListState.Loading
                )
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
            preAuth.advance(
                readiness(
                    canonicalServerId = null,
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loading
                )
            )
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
        val activating =
            readiness(
                canonicalServerId = "server-a",
                knownServerIds = emptySet(),
                serverListState = WindowNavigationServerListState.Loading
            )
        val activation =
            assertIs<WindowNavigationAction.ActivateServer>(blind.advance(activating))
        assertNull(blind.advance(activating))
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

    // Target-contract tests for the external-link interface (v3.4): serverListState
    // distinguishes "still resolving"/"failed" from a legitimately loaded list;
    // activationId separates distinct user clicks so terminal dedup only applies
    // within one activation; authGeneration drops intents bound to a dead session.

    @Test
    fun externalLinkLoadedEmptyServerListRejects() {
        // A successfully loaded, legitimately empty server list terminates the
        // intent — there is provably no such server — unlike a list that is
        // merely empty because it has not loaded yet.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(externalLinkIntent(serverId = "server-missing"))

        // Pre-auth: no canonical server yet, so the intent stays pending without
        // activating or rejecting.
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = null,
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loading
                )
            )
        )
        val rejected =
            assertIs<WindowNavigationAction.Reject>(
                coordinator.advance(
                    readiness(
                        canonicalServerId = "server-a",
                        knownServerIds = emptySet(),
                        serverListState = WindowNavigationServerListState.Loaded
                    )
                )
            )
        assertEquals("server_unavailable", rejected.reason)
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-a",
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loaded
                )
            )
        )
    }

    @Test
    fun externalLinkFailedListKeepsPendingAndDropsStaleSnapshot() {
        // A Failed list emission must NOT reject as server_unavailable even when it
        // still carries a stale non-empty snapshot; the intent stays pending until
        // a Loaded emission decides.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(externalLinkIntent(serverId = "server-missing"))

        val failedWithStaleList =
            readiness(
                canonicalServerId = null,
                knownServerIds = setOf("server-a", "server-b"),
                serverListState = WindowNavigationServerListState.Failed
            )
        assertNull(coordinator.advance(failedWithStaleList))
        assertNull(coordinator.advance(failedWithStaleList))
        assertEquals(1, coordinator.requestCount)

        assertIs<WindowNavigationAction.Reject>(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-a",
                    knownServerIds = setOf("server-a", "server-b"),
                    serverListState = WindowNavigationServerListState.Loaded
                )
            )
        )
    }

    @Test
    fun externalLinkDuplicateActivationDedupsAndNewActivationOpensAgain() {
        // Re-delivery of the same activationId dedups inside the window scope; a
        // genuinely new user click mints a new activationId and opens again even
        // after the previous activation reached a terminal stage.
        val coordinator = SharedWindowNavigationCoordinator()
        val first = externalLinkIntent(serverId = "server-b").copy(activationId = "act-1")
        assertTrue(coordinator.enqueue(first))
        assertFalse(coordinator.enqueue(first.copy()))

        val ready = readyReadiness("server-b")
        assertIs<WindowNavigationAction.OpenConversation>(coordinator.advance(ready))
        assertNull(coordinator.advance(ready))
        assertFalse(coordinator.enqueue(first.copy()))

        val second = externalLinkIntent(serverId = "server-b").copy(activationId = "act-2")
        assertTrue(coordinator.enqueue(second))
        assertIs<WindowNavigationAction.OpenConversation>(coordinator.advance(ready))
        assertNull(coordinator.advance(ready))
    }

    @Test
    fun notificationColdStartKeepsPendingWhileServerListLoads() {
        // Regression pin (SangLan mandate): the notification migration point must
        // not hard-fill `Loaded`. Before the auth session resolves, the server
        // list reports Loading/empty and the intent stays Pending — exactly the
        // wait semantics the legacy empty-list guard provided.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(notificationIntent(serverId = "server-b"))

        val coldStart =
            readiness(
                canonicalServerId = null,
                knownServerIds = emptySet(),
                serverListState = WindowNavigationServerListState.Loading
            )
        assertNull(coordinator.advance(coldStart))
        assertEquals(1, coordinator.requestCount)

        // List still Loading but a canonical session landed: activate, never reject.
        assertIs<WindowNavigationAction.ActivateServer>(
            coordinator.advance(
                readiness(
                    canonicalServerId = "server-a",
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loading
                )
            )
        )
    }

    @Test
    fun externalLinkUnboundIntentBindsOnceThenDiesOnSwitch() {
        // An intent delivered before login carries no generation: the first
        // authenticated readiness binds it exactly once, and a later account
        // switch terminates it rather than replaying the other user's route.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(externalLinkIntent(serverId = "server-b").copy(activationId = "act-1"))

        // Pre-auth (no canonical server yet): pending, unbound.
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = null,
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loading
                )
            )
        )

        // First authenticated readiness binds silently; the route then proceeds
        // through the normal activation/home gates.
        assertIs<WindowNavigationAction.ActivateServer>(
            coordinator.advance(
                readiness(canonicalServerId = "server-a").copy(authGeneration = "p1:1")
            )
        )

        // Fresh coordinator, same scenario but the session flips before open.
        val flipped = SharedWindowNavigationCoordinator()
        flipped.enqueue(externalLinkIntent(serverId = "server-b").copy(activationId = "act-2"))
        // Bind at p1:1 while not yet open (canonical elsewhere → ActivateServer first).
        assertIs<WindowNavigationAction.ActivateServer>(
            flipped.advance(readiness(canonicalServerId = "server-a").copy(authGeneration = "p1:1"))
        )
        val stale =
            assertIs<WindowNavigationAction.Reject>(
                flipped.advance(readyReadiness("server-b").copy(authGeneration = "p2:2"))
            )
        assertEquals("auth_generation_stale", stale.reason)
    }

    @Test
    fun loginCancelledFlushesUnboundButKeepsBound() {
        // Contract: a cancelled login drops the still-Unbound pending intents in
        // this scope so a later login cannot replay them; bound intents survive
        // (their lifecycle is governed by authGeneration).
        val coordinator = SharedWindowNavigationCoordinator()
        val unbound = externalLinkIntent("server-b").copy(activationId = "act-u")
        val bound =
            externalLinkIntent("server-b").copy(
                activationId = "act-b",
                authGeneration = "p1:1"
            )
        coordinator.enqueue(unbound)
        coordinator.enqueue(bound)

        val dropped = coordinator.flushUnboundExternalLinkIntents()
        assertEquals(listOf(unbound.activationId), dropped.map { it.activationId })
        // Dropped intents are terminal — no later advance or replay.
        assertNull(
            coordinator.advance(
                readiness(
                    canonicalServerId = null,
                    knownServerIds = emptySet(),
                    serverListState = WindowNavigationServerListState.Loading
                ).copy(authGeneration = "p1:1")
            )
        )
        // The bound intent is still tracked (count includes terminals).
        assertEquals(2, coordinator.requestCount)
    }

    @Test
    fun failedServerListKeepsIntentPendingWithoutDispatch() {
        // A degraded list must not terminate (server_unavailable) AND must not
        // dispatch: a stale snapshot cannot drive activation or open.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(externalLinkIntent("server-b").copy(activationId = "act-f"))

        assertNull(
            coordinator.advance(
                readyReadiness("server-a").copy(
                    serverListState = WindowNavigationServerListState.Failed
                )
            )
        )
        // Still pending once a real list lands — activation proceeds normally.
        assertIs<WindowNavigationAction.ActivateServer>(
            coordinator.advance(readyReadiness("server-a"))
        )
    }

    @Test
    fun failedServerListBindsUnboundAndDiesOnGenerationSwitch() {
        // While suspended on a degraded list, generation handling still runs:
        // an Unbound intent binds to the first authenticated generation, and a
        // bound intent dies when the generation moves on — a later account can
        // never adopt the pending route across the degraded gap.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(externalLinkIntent("server-b").copy(activationId = "act-bind"))

        // Failed readiness binds the unbound intent to p1:1 without dispatching.
        assertNull(
            coordinator.advance(
                readyReadiness("server-a").copy(
                    serverListState = WindowNavigationServerListState.Failed,
                    authGeneration = "p1:1"
                )
            )
        )

        // A second account's generation arriving while still Failed terminates it.
        val stale =
            assertIs<WindowNavigationAction.Reject>(
                coordinator.advance(
                    readyReadiness("server-b").copy(
                        serverListState = WindowNavigationServerListState.Failed,
                        authGeneration = "p2:2"
                    )
                )
            )
        assertEquals("auth_generation_stale", stale.reason)
    }

    @Test
    fun externalLinkBoundIntentDiesWhenAuthGenerationMovesOn() {
        // An intent bound to principal+generation "p1:1" terminates once the live
        // session reports a different generation; the stale route is never replayed.
        val coordinator = SharedWindowNavigationCoordinator()
        coordinator.enqueue(
            externalLinkIntent(serverId = "server-b").copy(
                activationId = "act-1",
                authGeneration = "p1:1"
            )
        )

        // Logged out entirely: the bound intent dies rather than later replaying
        // a dead session's route once a different account lands.
        val loggedOut =
            assertIs<WindowNavigationAction.Reject>(
                coordinator.advance(readyReadiness("server-b").copy(authGeneration = null))
            )
        assertEquals("auth_generation_stale", loggedOut.reason)

        // A second intent bound while session 1 was live dies on session 2.
        val movedOn = SharedWindowNavigationCoordinator()
        movedOn.enqueue(
            externalLinkIntent(serverId = "server-b").copy(
                activationId = "act-1",
                authGeneration = "p1:1"
            )
        )
        val stale =
            assertIs<WindowNavigationAction.Reject>(
                movedOn.advance(readyReadiness("server-b").copy(authGeneration = "p2:2"))
            )
        assertEquals("auth_generation_stale", stale.reason)
        assertNull(movedOn.advance(readyReadiness("server-b").copy(authGeneration = "p2:2")))

        // A bound intent that still matches opens normally.
        val fresh = SharedWindowNavigationCoordinator()
        fresh.enqueue(
            externalLinkIntent(serverId = "server-b").copy(
                activationId = "act-1",
                authGeneration = "p2:2"
            )
        )
        assertIs<WindowNavigationAction.OpenConversation>(
            fresh.advance(readyReadiness("server-b").copy(authGeneration = "p2:2"))
        )
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
        conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
        serverListState: WindowNavigationServerListState = WindowNavigationServerListState.Loaded,
        authGeneration: String? = null
    ): WindowNavigationReadiness =
        WindowNavigationReadiness(
            knownServerIds = knownServerIds,
            canonicalServerId = canonicalServerId,
            homeSnapshotServerId = homeSnapshotServerId,
            homeSessionServerId = homeSessionServerId,
            conversationDirectory = conversationDirectory,
            serverListState = serverListState,
            authGeneration = authGeneration
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
