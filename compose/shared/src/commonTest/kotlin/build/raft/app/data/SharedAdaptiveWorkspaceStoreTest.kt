package build.raft.app.data

import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftAdaptiveDetailSelection
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePanePosture
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.RaftAdaptiveWorkspaceIntent
import build.raft.app.reduceRaftAdaptiveWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedAdaptiveWorkspaceStoreTest {
    @Test
    fun shellScopedSessionSurvivesAppShellConsumerRecreation() {
        val cache = RaftShellScopedStoreCache<SharedAdaptiveWorkspaceStore>()
        val originalScope = RaftShellStoreScope.create("shell-a", initialServerId = "server-a")
        val store = cache.getOrPut(originalScope) { SharedAdaptiveWorkspaceStore() }
        val identity =
            RaftAdaptiveDetailIdentity(
                serverId = "server-a",
                threadId = "channel-a",
                surfaceKind = "channel",
                streamChannelId = "channel-a",
            )
        val selection = RaftAdaptiveDetailSelection(identity = identity, generation = 7L)
        val payload =
            WireThreadLaunchPayload(
                instanceId = "shared-shell-thread-channel-a",
                surfaceKind = "channel",
                sourceThreadId = "channel-a",
                streamChannelId = "channel-a",
            )
        val leaseOwner =
            RealtimeRoomLeaseOwner(
                shellInstanceId = "shell-a",
                serverId = "server-a",
                consumerId = "adaptive-handoff",
                generation = 7L,
            )
        store.update {
            SharedAdaptiveWorkspaceSession(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane =
                            RaftAdaptivePaneState(
                                layout = RaftAdaptivePaneLayout.DualPane,
                                posture = RaftAdaptivePanePosture.Flat,
                            ),
                        selection = selection,
                        generationCursor = 7L,
                    ),
                launchPayload = payload,
                handoffRoomLeaseOwner = leaseOwner,
            )
        }

        val recreatedConsumer =
            cache.getOrPut(RaftShellStoreScope.create("shell-a")) {
                error("same shell scope must reuse its adaptive workspace store")
            }

        assertSame(store, recreatedConsumer)
        assertEquals(selection, recreatedConsumer.snapshot().workspace?.selection)
        assertEquals(7L, recreatedConsumer.snapshot().workspace?.generationCursor)
        assertEquals(payload, recreatedConsumer.snapshot().launchPayload)
        assertEquals(leaseOwner, recreatedConsumer.snapshot().handoffRoomLeaseOwner)
    }

    @Test
    fun parkSelectionOnShellStoreLeavesSiblingActivityStoreUntouched() {
        // task #45: pushed Search/Saved share the shell scope and park into the shell
        // store only; the Activity surface keeps its own page-local store, so an
        // OFF-period park can never overwrite the Activity-side parked selection.
        val shellStore = SharedAdaptiveWorkspaceStore()
        val activityStore = SharedAdaptiveWorkspaceStore()
        val wide =
            RaftAdaptivePaneState(
                layout = RaftAdaptivePaneLayout.DualPane,
                posture = RaftAdaptivePanePosture.Flat,
            )
        shellStore.update {
            it.copy(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane = wide,
                    )
            )
        }
        activityStore.update {
            it.copy(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a:activity",
                        serverId = "server-a",
                        pane = wide,
                        selection =
                            RaftAdaptiveDetailSelection(
                                identity =
                                    RaftAdaptiveDetailIdentity(
                                        serverId = "server-a",
                                        threadId = "activity-thread",
                                        surfaceKind = "channel",
                                    ),
                                generation = 1L,
                            ),
                    )
            )
        }

        shellStore.update { session ->
            val workspace = session.workspace ?: return@update session
            val identity =
                RaftAdaptiveDetailIdentity(
                    serverId = "server-a",
                    threadId = "search-opened-b",
                    surfaceKind = "channel",
                )
            session.copy(
                workspace =
                    reduceRaftAdaptiveWorkspace(
                        workspace,
                        RaftAdaptiveWorkspaceIntent.ParkSelection(identity),
                    ),
            )
        }

        assertEquals(
            "search-opened-b",
            shellStore.snapshot().workspace?.selection?.identity?.threadId,
        )
        assertEquals(
            "activity-thread",
            activityStore.snapshot().workspace?.selection?.identity?.threadId,
        )
    }

    @Test
    fun shellReleaseClearsWorkspacePayloadAndLeaseOwner() {
        val store = SharedAdaptiveWorkspaceStore()
        store.update {
            it.copy(
                launchPayload = WireThreadLaunchPayload(sourceThreadId = "channel-a"),
                handoffRoomLeaseOwner =
                    RealtimeRoomLeaseOwner(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        consumerId = "adaptive-handoff",
                        generation = 1L,
                    ),
            )
        }

        store.clear()

        assertNull(store.snapshot().workspace)
        assertNull(store.snapshot().launchPayload)
        assertNull(store.snapshot().handoffRoomLeaseOwner)
    }
}
