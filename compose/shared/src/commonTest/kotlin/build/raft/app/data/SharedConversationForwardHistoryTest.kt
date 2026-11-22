package build.raft.app.data

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationForwardHistoryTest {
    @Test
    fun backThenForwardReopensTheMostRecentCanonicalConversationExactlyOnce() {
        val history = enabledHistory()
        history.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))

        assertTrue(history.recordHomeVisible())
        val action =
            assertIs<ConversationForwardAction.Open>(
                history.requestForward(ready("server-a", projection("server-a", "channel-a")))
            )
        assertEquals("channel-a", action.request.conversationIdentity.channelId?.value)
        assertFalse(history.canForward)
        assertNull(history.requestForward(ready("server-a", projection("server-a", "channel-a"))))
    }

    @Test
    fun backAfterForwardRestoresTheInverseOneStepHistory() {
        val history = enabledHistory()
        val target = entry("server-a", "dm-a", ConversationKind.DirectMessage)
        history.recordNewOpen(target)
        history.recordHomeVisible()
        assertIs<ConversationForwardAction.Open>(
            history.requestForward(
                ready(
                    "server-a",
                    projection(
                        "server-a",
                        "dm-a",
                        kind = ConversationKind.DirectMessage,
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage
                    )
                )
            )
        )

        assertTrue(history.recordHomeVisible())
        assertEquals(target.key, history.forwardEntryForTest()?.key)
    }

    @Test
    fun freshOpenAfterBackInvalidatesTheObsoleteForwardBranch() {
        val history = enabledHistory()
        history.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        history.recordHomeVisible()
        assertTrue(history.canForward)

        history.recordNewOpen(entry("server-a", "channel-b", ConversationKind.Channel))
        assertFalse(history.canForward)
        assertNull(history.requestForward(ready("server-a", projection("server-a", "channel-a"))))

        history.recordHomeVisible()
        val reopened =
            assertIs<ConversationForwardAction.Open>(
                history.requestForward(ready("server-a", projection("server-a", "channel-b")))
            )
        assertEquals("channel-b", reopened.request.conversationIdentity.channelId?.value)
    }

    @Test
    fun serverRemovalServerChangeAndReadRevocationDropClosed() {
        val removed = enabledHistory()
        removed.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        removed.recordHomeVisible()
        assertEquals(
            "server_unavailable",
            assertIs<ConversationForwardAction.Drop>(
                removed.requestForward(
                    ready(
                        serverId = "server-b",
                        directoryProjection = projection("server-b", "channel-b"),
                        knownServerIds = setOf("server-b")
                    )
                )
            ).reason
        )
        assertFalse(removed.canForward)

        val changed = enabledHistory()
        changed.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        changed.recordHomeVisible()
        assertEquals(
            "server_changed",
            assertIs<ConversationForwardAction.Drop>(
                changed.requestForward(
                    ready(
                        serverId = "server-b",
                        directoryProjection = projection("server-b", "channel-b"),
                        knownServerIds = setOf("server-a", "server-b")
                    )
                )
            ).reason
        )

        val revoked = enabledHistory()
        revoked.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        revoked.recordHomeVisible()
        assertEquals(
            "conversation_unavailable",
            assertIs<ConversationForwardAction.Drop>(
                revoked.requestForward(
                    ready(
                        "server-a",
                        projection("server-a", "channel-a", joined = false)
                    )
                )
            ).reason
        )
        assertFalse(revoked.canForward)

        val archived = enabledHistory()
        archived.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        archived.recordHomeVisible()
        assertEquals(
            "conversation_unavailable",
            assertIs<ConversationForwardAction.Drop>(
                archived.requestForward(
                    ready(
                        "server-a",
                        projection("server-a", "channel-a", archived = true)
                    )
                )
            ).reason
        )

        val cannotRead = enabledHistory()
        cannotRead.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        cannotRead.recordHomeVisible()
        assertEquals(
            "conversation_unavailable",
            assertIs<ConversationForwardAction.Drop>(
                cannotRead.requestForward(
                    ready(
                        "server-a",
                        projection("server-a", "channel-a", canRead = false)
                    )
                )
            ).reason
        )
    }

    @Test
    fun windowScopesAndReleaseKeepHistoryIsolated() {
        val cache = RaftShellScopedStoreCache<SharedConversationForwardHistory>()
        val scopeA = RaftShellStoreScope.create("window-a", "server-a")
        val scopeB = RaftShellStoreScope.create("window-b", "server-b")
        val historyA = cache.getOrPut(scopeA) { enabledHistory() }
        val historyB = cache.getOrPut(scopeB) { enabledHistory() }
        historyA.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        historyB.recordNewOpen(entry("server-b", "dm-b", ConversationKind.DirectMessage))
        historyA.recordHomeVisible()

        assertTrue(historyA.canForward)
        assertFalse(historyB.canForward)
        historyA.release()
        assertFalse(historyA.canForward)
        historyA.recordNewOpen(entry("server-a", "channel-c", ConversationKind.Channel))
        assertFalse(historyA.recordHomeVisible())
        assertFalse(historyA.canForward)
    }

    @Test
    fun clearDropsBothAnExposedBranchAndAnUnpoppedActiveEntry() {
        val history = enabledHistory()
        history.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        history.recordHomeVisible()
        assertTrue(history.canForward)

        history.clear()
        assertFalse(history.canForward)

        history.recordNewOpen(entry("server-a", "channel-b", ConversationKind.Channel))
        history.clear()
        assertFalse(history.recordHomeVisible())
        assertFalse(history.canForward)
    }

    @Test
    fun defaultOffAndPreferenceTransitionsNeverResurrectHistory() {
        val history = SharedConversationForwardHistory()
        val first = entry("server-a", "channel-a", ConversationKind.Channel)

        assertFalse(history.enabledForTest())
        history.recordNewOpen(first)
        assertFalse(history.recordHomeVisible())
        assertFalse(history.canForward)
        assertNull(history.requestForward(ready("server-a", projection("server-a", "channel-a"))))

        history.setEnabled(true)
        assertTrue(history.enabledForTest())
        assertFalse(history.canForward)
        history.recordNewOpen(first)
        assertTrue(history.recordHomeVisible())
        assertTrue(history.canForward)

        history.setEnabled(false)
        assertFalse(history.enabledForTest())
        assertFalse(history.canForward)
        assertNull(history.forwardEntryForTest())

        history.setEnabled(true)
        assertTrue(history.enabledForTest())
        assertFalse(history.canForward)
        assertNull(history.requestForward(ready("server-a", projection("server-a", "channel-a"))))
    }

    @Test
    fun gestureIsArmedOnlyOnLoadedVisibleHomeWithSomethingToReopen() {
        fun armed(
            preferenceEnabled: Boolean = true,
            authoritativeHomeStartAllowed: Boolean = true,
            homeSurfaceVisible: Boolean = true,
            homeContentReady: Boolean = true,
            canForward: Boolean = true,
        ) =
            appShellConversationForwardGestureArmed(
                preferenceEnabled = preferenceEnabled,
                authoritativeHomeStartAllowed = authoritativeHomeStartAllowed,
                homeSurfaceVisible = homeSurfaceVisible,
                homeContentReady = homeContentReady,
                canForward = canForward,
            )

        assertTrue(armed())
        assertFalse(armed(preferenceEnabled = false))
        assertFalse(armed(authoritativeHomeStartAllowed = false))
        // Off Home (another tab, a Home destination, the shell covered): never armed. The old
        // "home not ready" re-arm path left the recognizer live wherever the shell was.
        assertFalse(armed(homeSurfaceVisible = false))
        assertFalse(armed(homeContentReady = false))
        assertFalse(armed(canForward = false))
    }

    @Test
    fun backFromAConversationArmsTheGestureOnceHomeHasLoaded() {
        // #proj-mobile #111: the Beta setting on, a DM opened from Home, Back to Home.
        val history = SharedConversationForwardHistory()
        var nativeDisarms = 0
        ConversationForwardGestureRuntimeAuthority(supported = true) { enabled ->
            reconcileConversationForwardGesturePreference(enabled, history) { nativeDisarms += 1 }
        }.seedFromRepository(enabled = true)
        assertEquals(0, nativeDisarms)

        history.recordNewOpen(entry("server-a", "dm-a", ConversationKind.DirectMessage))
        // Covered by the conversation: nothing to reopen yet, and the shell is not Home.
        assertFalse(history.canForward)
        assertTrue(history.recordHomeVisible())

        fun armed(homeContentReady: Boolean) =
            appShellConversationForwardGestureArmed(
                preferenceEnabled = true,
                authoritativeHomeStartAllowed = true,
                homeSurfaceVisible = true,
                homeContentReady = homeContentReady,
                canForward = history.canForward,
            )
        // Home's list still loading when the shell reappears: not yet, but the candidate stays.
        assertFalse(armed(homeContentReady = false))
        assertTrue(history.canForward)
        // The list lands: the same rule now arms the recognizer.
        assertTrue(armed(homeContentReady = true))

        val action =
            assertIs<ConversationForwardAction.Open>(
                history.requestForward(
                    ready(
                        "server-a",
                        projection(
                            "server-a",
                            "dm-a",
                            kind = ConversationKind.DirectMessage,
                            surfaceKind = RaftThreadSurfaceKind.DirectMessage
                        )
                    )
                )
            )
        assertEquals("server-a", action.entry.serverId)
        // Reopened: nothing left to reopen until the next Back.
        assertFalse(armed(homeContentReady = true))
    }

    @Test
    fun iPhoneBackToHomeOffersTheConversationOnTheHostSignalAlone() {
        // QA, iPhone, main 1083462a: setting on, #all opened from Home, Back, right-edge swipe did
        // nothing; the log read `conversation_forward_arm ... available=false`. The shell's
        // Compose pageDidAppear (pageVisibleTick) never advanced across the native push/pop, and
        // it was the only signal that promoted the conversation left to the Forward entry.
        val history = enabledHistory()
        val returns = ConversationForwardHomeReturnSignals()
        fun armed() =
            appShellConversationForwardGestureArmed(
                preferenceEnabled = true,
                authoritativeHomeStartAllowed = true,
                homeSurfaceVisible = true,
                homeContentReady = true,
                canForward = history.canForward,
            )
        fun shellAppeared(pageVisibleTick: Int, hostDidAppearTick: Int) {
            if (returns.consume(pageVisibleTick, hostDidAppearTick)) history.recordHomeVisible()
        }

        // Cold start: the shell's first viewDidAppear; nothing to reopen (no crash, no arm).
        shellAppeared(pageVisibleTick = 0, hostDidAppearTick = 1)
        assertFalse(armed())

        val channel = entry("server-a", "channel-all", ConversationKind.Channel)
        history.recordNewOpen(channel)
        assertFalse(armed(), "The conversation on screen is not a Forward target")

        // Back: only the iOS host's viewDidAppear advances.
        shellAppeared(pageVisibleTick = 0, hostDidAppearTick = 2)
        assertTrue(armed())
        assertEquals(channel.key, history.forwardEntryForTest()?.key)

        // The Compose signal arriving late for the same return changes nothing.
        shellAppeared(pageVisibleTick = 1, hostDidAppearTick = 2)
        assertTrue(armed())
        assertEquals(channel.key, history.forwardEntryForTest()?.key)

        val reopened =
            assertIs<ConversationForwardAction.Open>(
                history.requestForward(ready("server-a", projection("server-a", "channel-all")))
            )
        assertEquals("channel-all", reopened.request.conversationIdentity.channelId?.value)
        assertFalse(armed(), "Reopened: nothing to go Forward to while it is on screen")

        // Back again from the reopened conversation: Forward is offered again.
        shellAppeared(pageVisibleTick = 1, hostDidAppearTick = 3)
        assertTrue(armed())
    }

    @Test
    fun homeReturnSignalsAreEachConsumedOnce() {
        val returns = ConversationForwardHomeReturnSignals()
        assertFalse(returns.consume(pageVisibleTick = 0, hostDidAppearTick = 0))
        assertTrue(returns.consume(pageVisibleTick = 0, hostDidAppearTick = 1))
        assertFalse(returns.consume(pageVisibleTick = 0, hostDidAppearTick = 1))
        assertTrue(returns.consume(pageVisibleTick = 1, hostDidAppearTick = 1))
        assertFalse(returns.consume(pageVisibleTick = 1, hostDidAppearTick = 1))
        assertTrue(returns.consume(pageVisibleTick = 2, hostDidAppearTick = 2))
        assertFalse(returns.consume(pageVisibleTick = 2, hostDidAppearTick = 2))
    }

    private fun enabledHistory(): SharedConversationForwardHistory =
        SharedConversationForwardHistory().also { it.setEnabled(true) }

    private fun entry(
        serverId: String,
        channelId: String,
        kind: ConversationKind
    ): ConversationForwardEntry =
        buildThreadLaunchConversationIdentity(kind = kind, channelId = ChannelId(channelId))
            .toConversationForwardEntry(serverId)

    private fun projection(
        serverId: String,
        channelId: String,
        kind: ConversationKind = ConversationKind.Channel,
        surfaceKind: String = RaftThreadSurfaceKind.Channel,
        joined: Boolean = true,
        archived: Boolean = false,
        canRead: Boolean = joined
    ): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = serverId,
            canonicalChannelId = channelId,
            aliases = setOf(channelId),
            conversationKind = kind,
            surfaceKind = surfaceKind,
            channelKind =
                when (kind) {
                    ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                    ConversationKind.Joint -> "joint"
                    else -> RaftThreadSurfaceKind.Channel
                },
            title = "ignored display title",
            joined = joined,
            archived = archived,
            permissions = ConversationPermissions(canRead = canRead)
        )

    private fun ready(
        serverId: String,
        directoryProjection: SharedConversationDirectoryProjection,
        knownServerIds: Set<String> = setOf(serverId)
    ): WindowNavigationReadiness =
        ready(
            serverId = serverId,
            directory =
                SharedConversationDirectoryState(
                    projectionsByCanonicalId =
                        mapOf(directoryProjection.canonicalChannelId to directoryProjection)
                ),
            knownServerIds = knownServerIds
        )

    private fun ready(
        serverId: String,
        directory: SharedConversationDirectoryState,
        knownServerIds: Set<String> = setOf(serverId)
    ): WindowNavigationReadiness =
        WindowNavigationReadiness(
            knownServerIds = knownServerIds,
            canonicalServerId = serverId,
            homeSnapshotServerId = serverId,
            homeSessionServerId = serverId,
            conversationDirectory = directory
        )
}
