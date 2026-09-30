package build.raft.app.data

import build.raft.app.ConversationOpenSource
import build.raft.app.KuiklyThreadSurfaceKind
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
                        surfaceKind = KuiklyThreadSurfaceKind.DirectMessage
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
    fun freshDrawerOpenOnAnotherServerReplacesTheOldServerBranch() {
        val history = enabledHistory()
        history.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        history.recordHomeVisible()
        assertTrue(history.canForward)

        history.recordNewOpen(entry("server-b", "dm-b", ConversationKind.DirectMessage))
        assertFalse(history.canForward)
        assertNull(
            history.requestForward(
                ready(
                    serverId = "server-a",
                    directoryProjection = projection("server-a", "channel-a"),
                    knownServerIds = setOf("server-a", "server-b")
                )
            )
        )

        history.recordHomeVisible()
        val reopened =
            assertIs<ConversationForwardAction.Open>(
                history.requestForward(
                    ready(
                        serverId = "server-b",
                        directoryProjection =
                            projection(
                                serverId = "server-b",
                                channelId = "dm-b",
                                kind = ConversationKind.DirectMessage,
                                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage
                            ),
                        knownServerIds = setOf("server-a", "server-b")
                    )
                )
            )
        assertEquals("server-b", reopened.entry.serverId)
        assertEquals("dm-b", reopened.request.conversationIdentity.channelId?.value)
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
    fun canonicalAliasOrKindDriftCannotRewriteTheRememberedIdentity() {
        val history = enabledHistory()
        history.recordNewOpen(entry("server-a", "channel-a", ConversationKind.Channel))
        history.recordHomeVisible()
        val aliasDirectory =
            SharedConversationDirectoryState(
                projectionsByCanonicalId =
                    mapOf(
                        "channel-b" to
                            projection("server-a", "channel-b").copy(aliases = setOf("channel-a"))
                    ),
                aliasesById = mapOf("channel-a" to setOf("channel-b"))
            )
        assertEquals(
            "conversation_unavailable",
            assertIs<ConversationForwardAction.Drop>(
                history.requestForward(
                    ready("server-a", directory = aliasDirectory)
                )
            ).reason
        )
    }

    @Test
    fun windowScopesAndReleaseKeepHistoryIsolated() {
        val cache = SlockShellScopedStoreCache<SharedConversationForwardHistory>()
        val scopeA = SlockShellStoreScope.create("window-a", "server-a")
        val scopeB = SlockShellStoreScope.create("window-b", "server-b")
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
    fun historyEnvelopeContainsRouteIdentityOnlyForChannelAndDm() {
        val channel = entry("server-a", "channel-a", ConversationKind.Channel)
        val dm = entry("server-a", "dm-a", ConversationKind.DirectMessage)

        assertEquals(ConversationOpenSource.HistoryForward, channel.sourceIdentity.source)
        assertEquals("channel-a", channel.sourceIdentity.channelId)
        assertEquals(ConversationKind.Channel, channel.sourceIdentity.conversationKind)
        assertNull(channel.sourceIdentity.messageId)
        assertNull(channel.sourceIdentity.originFallback)
        assertEquals("dm-a", dm.sourceIdentity.channelId)
        assertEquals(ConversationKind.DirectMessage, dm.sourceIdentity.conversationKind)
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
        surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
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
                    ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
                    ConversationKind.Joint -> "joint"
                    else -> KuiklyThreadSurfaceKind.Channel
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
            conversationDirectory = directory,
            serverListState = WindowNavigationServerListState.Loaded
        )
}
