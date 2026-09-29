package build.raft.app.data

import build.raft.app.SlockAdaptiveDetailIdentity
import build.raft.app.SlockAdaptiveDetailSelection
import build.raft.app.SlockAdaptivePaneLayout
import build.raft.app.SlockAdaptivePanePosture
import build.raft.app.SlockAdaptivePaneState
import build.raft.app.SlockAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.SlockAdaptiveWorkspaceIntent
import build.raft.app.reduceSlockAdaptiveWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedAdaptiveWorkspaceStoreTest {
    @Test
    fun shellScopedSessionSurvivesAppShellConsumerRecreation() {
        val cache = SlockShellScopedStoreCache<SharedAdaptiveWorkspaceStore>()
        val originalScope = SlockShellStoreScope.create("shell-a", initialServerId = "server-a")
        val store = cache.getOrPut(originalScope) { SharedAdaptiveWorkspaceStore() }
        val identity =
            SlockAdaptiveDetailIdentity(
                serverId = "server-a",
                threadId = "channel-a",
                surfaceKind = "channel",
                streamChannelId = "channel-a",
            )
        val selection = SlockAdaptiveDetailSelection(identity = identity, generation = 7L)
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
                    SlockAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane =
                            SlockAdaptivePaneState(
                                layout = SlockAdaptivePaneLayout.DualPane,
                                posture = SlockAdaptivePanePosture.Flat,
                            ),
                        selection = selection,
                        generationCursor = 7L,
                    ),
                launchPayload = payload,
                handoffRoomLeaseOwner = leaseOwner,
            )
        }

        val recreatedConsumer =
            cache.getOrPut(SlockShellStoreScope.create("shell-a")) {
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
            SlockAdaptivePaneState(
                layout = SlockAdaptivePaneLayout.DualPane,
                posture = SlockAdaptivePanePosture.Flat,
            )
        shellStore.update {
            it.copy(
                workspace =
                    SlockAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane = wide,
                    )
            )
        }
        activityStore.update {
            it.copy(
                workspace =
                    SlockAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a:activity",
                        serverId = "server-a",
                        pane = wide,
                        selection =
                            SlockAdaptiveDetailSelection(
                                identity =
                                    SlockAdaptiveDetailIdentity(
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
                SlockAdaptiveDetailIdentity(
                    serverId = "server-a",
                    threadId = "search-opened-b",
                    surfaceKind = "channel",
                )
            session.copy(
                workspace =
                    reduceSlockAdaptiveWorkspace(
                        workspace,
                        SlockAdaptiveWorkspaceIntent.ParkSelection(identity),
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
