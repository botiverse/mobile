package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerLastConversationTest {
    private val owner = messageBodyFontSizeOwnerKey("https://api.raft.build", "user-1")!!

    private val serverACandidates =
        listOf(
            RaftAdaptiveHomeLandingCandidate("a-all", "all", ConversationKind.Channel),
            RaftAdaptiveHomeLandingCandidate("a-dev", "dev", ConversationKind.Channel),
            RaftAdaptiveHomeLandingCandidate("a-dm-alice", "Alice", ConversationKind.DirectMessage),
        )
    private val serverBCandidates =
        listOf(
            RaftAdaptiveHomeLandingCandidate("b-all", "all", ConversationKind.Channel),
            RaftAdaptiveHomeLandingCandidate("b-ops", "ops", ConversationKind.Channel),
        )

    @Test
    fun eachServerKeepsItsOwnLastConversation() {
        val repository = ServerLastConversationRepository { MapPreferences() }
        repository.write(owner, "server-a", channel("server-a", "a-dev").toServerLastConversation()!!)
        repository.write(owner, "server-b", channel("server-b", "b-ops").toServerLastConversation()!!)

        assertEquals("a-dev", repository.read(owner, "server-a")?.threadId)
        assertEquals("b-ops", repository.read(owner, "server-b")?.threadId)
        assertNull(repository.read(owner, "server-c"))
    }

    @Test
    fun anotherUserOrEnvironmentDoesNotSeeTheConversation() {
        val preferences = MapPreferences()
        val repository = ServerLastConversationRepository { preferences }
        repository.write(owner, "server-a", channel("server-a", "a-dev").toServerLastConversation()!!)

        assertNull(repository.read(messageBodyFontSizeOwnerKey("https://api.raft.build", "user-2"), "server-a"))
        assertNull(repository.read(messageBodyFontSizeOwnerKey("https://staging.raft.build", "user-1"), "server-a"))
        assertNull(repository.read(null, "server-a"))
        assertEquals(
            setOf("serverLastConversation:https://api.raft.build|user-1|server-a"),
            preferences.values.keys,
        )
    }

    @Test
    fun keptAcrossRestartsThroughTheSharedPreferences() {
        val preferences = MapPreferences()
        ServerLastConversationRepository { preferences }
            .write(owner, "server-a", thread("server-a", parentChannelId = "a-dev").toServerLastConversation()!!)

        val restored = ServerLastConversationRepository { preferences }.read(owner, "server-a")

        assertEquals(RaftThreadSurfaceKind.ReplyThread, restored?.surfaceKind)
        assertEquals("a-dev", restored?.parentChannelId)
        assertEquals("msg-1", restored?.parentMessageId)
        assertEquals("stream-1", restored?.streamChannelId)
    }

    @Test
    fun anUnreadableEntryIsDroppedAndReadsAsNone() {
        val preferences = MapPreferences()
        preferences.values[serverLastConversationKey(owner, "server-a")] = "{not json"
        val repository = ServerLastConversationRepository { preferences }

        assertNull(repository.read(owner, "server-a"))
        assertNull(preferences.values[serverLastConversationKey(owner, "server-a")])
    }

    @Test
    fun aRememberedConversationStillListedComesBack() {
        val remembered = channel("server-a", "a-dm-alice").toServerLastConversation()

        val resolution = resolveRaftAdaptiveHomeLanding(null, remembered, serverACandidates)

        assertEquals(RaftAdaptiveHomeLanding.Row("a-dm-alice"), resolution.landing)
        assertFalse(resolution.rememberedUnavailable)
    }

    @Test
    fun aDeletedOrLeftConversationFallsBackToAllAndIsForgotten() {
        val remembered = channel("server-a", "a-gone").toServerLastConversation()

        val resolution = resolveRaftAdaptiveHomeLanding(null, remembered, serverACandidates)

        assertEquals(RaftAdaptiveHomeLanding.Row("a-all"), resolution.landing)
        assertTrue(resolution.rememberedUnavailable)
    }

    @Test
    fun aReplyThreadComesBackWhileItsParentIsListed() {
        val remembered = thread("server-a", parentChannelId = "a-dev").toServerLastConversation()!!

        val restored = resolveRaftAdaptiveHomeLanding(null, remembered, serverACandidates)
        val gone = resolveRaftAdaptiveHomeLanding(null, thread("server-a", parentChannelId = "a-gone").toServerLastConversation(), serverACandidates)

        val landing = restored.landing as RaftAdaptiveHomeLanding.ReplyThread
        assertEquals("a-dev", landing.parentThreadId)
        val identity = landing.toThreadLaunchConversationIdentity()
        assertEquals(ConversationKind.Thread, identity.kind)
        assertEquals("a-dev:msg-1", identity.threadId.value)
        assertEquals("a-dev", identity.thread?.parentChannelId?.value)
        assertEquals("msg-1", identity.thread?.parentMessageId?.value)
        assertEquals("stream-1", identity.thread?.streamChannelId?.value)
        assertEquals(RaftAdaptiveHomeLanding.Row("a-all"), gone.landing)
        assertTrue(gone.rememberedUnavailable)
    }

    @Test
    fun aVisibleSelectionWinsOverTheMemory() {
        val remembered = channel("server-a", "a-dev").toServerLastConversation()

        val resolution = resolveRaftAdaptiveHomeLanding("a-dm-alice", remembered, serverACandidates)

        assertEquals(RaftAdaptiveHomeLanding.Row("a-dm-alice"), resolution.landing)
        assertFalse(resolution.rememberedUnavailable)
    }

    /** artin: switching servers back and forth must not send the first server back to #all. */
    @Test
    fun switchingAToBToARestoresTheConversationOpenOnA() {
        val repository = ServerLastConversationRepository { MapPreferences() }
        // The shell writes every shown selection under its server (the selection LaunchedEffect).
        fun show(state: RaftAdaptiveWorkspaceState): RaftAdaptiveWorkspaceState {
            val identity = state.selection?.identity ?: return state
            repository.write(owner, identity.serverId, identity.toServerLastConversation()!!)
            return state
        }
        // The shell's default landing on an empty wide detail; the selection was cleared on switch.
        fun land(state: RaftAdaptiveWorkspaceState, candidates: List<RaftAdaptiveHomeLandingCandidate>): RaftAdaptiveWorkspaceState {
            val landing =
                resolveRaftAdaptiveHomeLanding(
                    selectedThreadId = null,
                    remembered = repository.read(owner, state.serverId),
                    candidates = candidates,
                ).landing as RaftAdaptiveHomeLanding.Row
            return show(state.reduce(RaftAdaptiveWorkspaceIntent.LandDefaultDetail(channel(state.serverId, landing.threadId))))
        }

        val onA =
            land(
                RaftAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = "server-a")
                    .reduce(RaftAdaptiveWorkspaceIntent.WindowChanged(widePane())),
                serverACandidates,
            )
        assertEquals("a-all", onA.selection?.identity?.threadId)
        val pickedOnA = show(onA.reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(channel("server-a", "a-dm-alice"))))

        val onB = land(pickedOnA.reduce(RaftAdaptiveWorkspaceIntent.AuthorityChanged("server-b")), serverBCandidates)
        assertEquals("b-all", onB.selection?.identity?.threadId)
        val pickedOnB = show(onB.reduce(RaftAdaptiveWorkspaceIntent.OpenDetail(channel("server-b", "b-ops"))))

        val backOnA = pickedOnB.reduce(RaftAdaptiveWorkspaceIntent.AuthorityChanged("server-a"))
        assertNull(backOnA.selection)
        assertEquals("a-dm-alice", land(backOnA, serverACandidates).selection?.identity?.threadId)

        val backOnB = land(backOnA.reduce(RaftAdaptiveWorkspaceIntent.AuthorityChanged("server-b")), serverBCandidates)
        assertEquals("b-ops", backOnB.selection?.identity?.threadId)
    }

    private fun channel(serverId: String, threadId: String): RaftAdaptiveDetailIdentity =
        RaftAdaptiveDetailIdentity(
            serverId = serverId,
            threadId = threadId,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelId = threadId,
        )

    private fun thread(serverId: String, parentChannelId: String): RaftAdaptiveDetailIdentity =
        RaftAdaptiveDetailIdentity(
            serverId = serverId,
            threadId = "$parentChannelId:msg-1",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            streamChannelId = "stream-1",
            parentChannelId = parentChannelId,
            parentMessageId = "msg-1",
        )

    private fun widePane(): RaftAdaptivePaneState =
        RaftAdaptivePaneState(
            widthPx = 2200,
            heightPx = 1200,
            density = 2f,
            posture = RaftAdaptivePanePosture.Flat,
            layout = RaftAdaptivePaneLayout.DualPane,
        )

    private fun RaftAdaptiveWorkspaceState.reduce(intent: RaftAdaptiveWorkspaceIntent): RaftAdaptiveWorkspaceState =
        reduceRaftAdaptiveWorkspace(this, intent)

    private class MapPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
