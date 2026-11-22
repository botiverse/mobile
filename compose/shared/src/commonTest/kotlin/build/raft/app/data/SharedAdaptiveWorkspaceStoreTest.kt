package build.raft.app.data

import build.raft.app.RaftAdaptiveDetailCloseRequest
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
import kotlin.test.assertFalse
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
        assertNull(store.snapshot().defaultLandingServerId)
    }

    @Test
    fun narrowDropClearsDefaultLandingServerIdWhileExplicitClosePreservesIt() {
        val store = SharedAdaptiveWorkspaceStore()
        val wide =
            RaftAdaptivePaneState(
                widthPx = 2400,
                heightPx = 1600,
                density = 2f,
                posture = RaftAdaptivePanePosture.Flat,
                layout = RaftAdaptivePaneLayout.DualPane,
            )
        val compact =
            wide.copy(
                widthPx = 900,
                heightPx = 1600,
                layout = RaftAdaptivePaneLayout.Compact,
            )
        val payload = WireThreadLaunchPayload(threadId = "all", sourceThreadId = "all")
        val identity =
            RaftAdaptiveDetailIdentity(
                serverId = "server-a",
                threadId = "all",
                surfaceKind = "channel",
                channelId = "all",
            )
        store.update {
            it.copy(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane = wide,
                    )
            )
        }

        // Default landing installs selection and defaultLandingServerId atomically.
        val landed = store.update { it.withOpenedDetail(identity, payload, defaultLanding = true) }
        assertEquals("server-a", landed.defaultLandingServerId)
        assertEquals(identity, landed.workspace?.selection?.identity)

        // Narrowing drops the untouched default landing and resets defaultLandingServerId + payload.
        val narrowed =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(compact),
                        )
                )
            }
        assertNull(narrowed.workspace?.selection)
        assertNull(narrowed.launchPayload)
        assertNull(narrowed.defaultLandingServerId)

        // Widening back allows default landing to run again for server-a.
        val widened =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(wide),
                        )
                )
            }
        assertNull(widened.defaultLandingServerId)
        val reLanded = store.update { it.withOpenedDetail(identity, payload, defaultLanding = true) }
        assertEquals("server-a", reLanded.defaultLandingServerId)
        assertEquals(identity, reLanded.workspace?.selection?.identity)

        // Explicit close in DualPane clears selection but preserves defaultLandingServerId.
        val closed = store.update { it.closeDetailCompletely() }
        assertNull(closed.workspace?.selection)
        assertNull(closed.launchPayload)
        assertEquals("server-a", closed.defaultLandingServerId)

        // A later narrow -> wide round-trip keeps the explicit close settled.
        val narrowedAfterClose =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(compact),
                        )
                )
            }
        assertEquals("server-a", narrowedAfterClose.defaultLandingServerId)

        // Switching server clears defaultLandingServerId automatically.
        val switched =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.AuthorityChanged("server-b"),
                        )
                )
            }
        assertEquals("server-b", switched.workspace?.serverId)
        assertNull(switched.defaultLandingServerId)
    }

    @Test
    fun explicitOpenBeforeDefaultLandingSettlesLandingAcrossExplicitClosesAndKeepsNarrowContinuity() {
        val store = SharedAdaptiveWorkspaceStore()
        val wide =
            RaftAdaptivePaneState(
                widthPx = 2400,
                heightPx = 1600,
                density = 2f,
                posture = RaftAdaptivePanePosture.Flat,
                layout = RaftAdaptivePaneLayout.DualPane,
            )
        val compact = wide.copy(widthPx = 900, heightPx = 1600, layout = RaftAdaptivePaneLayout.Compact)
        val payload = WireThreadLaunchPayload(threadId = "dm-1", sourceThreadId = "dm-1")
        val identity =
            RaftAdaptiveDetailIdentity(
                serverId = "server-a",
                threadId = "dm-1",
                surfaceKind = "channel",
                channelId = "dm-1",
            )
        store.update {
            it.copy(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane = wide,
                    )
            )
        }

        // Deep link / notification / user tap opens via OpenDetail BEFORE default landing ran.
        val opened = store.update { it.withOpenedDetail(identity, payload, defaultLanding = false) }
        assertEquals("server-a", opened.defaultLandingServerId)
        assertFalse(opened.workspace!!.selectionIsDefaultLanding)

        // Because selectionIsDefaultLanding is false, narrowing retains the user selection instead of dropping it.
        val narrowed =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(compact),
                        )
                )
            }
        assertEquals(identity, narrowed.workspace?.selection?.identity)
        assertEquals("server-a", narrowed.defaultLandingServerId)

        // Returning to wide and explicitly closing via backFromDetail keeps defaultLandingServerId settled.
        val widened =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(wide),
                        )
                )
            }
        val activeSelection = widened.workspace!!.selection!!
        val closedByBack =
            store.update { session ->
                session.backFromDetail(
                    RaftAdaptiveDetailCloseRequest(
                        shellInstanceId = "shell-a",
                        identityKey = activeSelection.identity.key,
                        generation = activeSelection.generation,
                    )
                )
            }
        assertNull(closedByBack.workspace?.selection)
        assertNull(closedByBack.launchPayload)
        assertEquals("server-a", closedByBack.defaultLandingServerId)

        // Even if a selection arrived without setting defaultLandingServerId (e.g. parked during
        // desktop-mode OFF), closing it in DualPane via backFromDetail or closeDetailCompletely settles it.
        val parkedWithoutLanding =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.ParkSelection(identity),
                        ),
                    launchPayload = payload,
                    defaultLandingServerId = null,
                )
            }
        assertNull(parkedWithoutLanding.defaultLandingServerId)
        val closedCompletely = store.update { it.closeDetailCompletely() }
        assertNull(closedCompletely.workspace?.selection)
        assertEquals("server-a", closedCompletely.defaultLandingServerId)

        val parkedAgain =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.ParkSelection(identity),
                        ),
                    launchPayload = payload,
                    defaultLandingServerId = null,
                )
            }
        val parkedSel = parkedAgain.workspace!!.selection!!
        val closedByBackAgain =
            store.update { session ->
                session.backFromDetail(
                    RaftAdaptiveDetailCloseRequest(
                        shellInstanceId = "shell-a",
                        identityKey = parkedSel.identity.key,
                        generation = parkedSel.generation,
                    )
                )
            }
        assertNull(closedByBackAgain.workspace?.selection)
        assertEquals("server-a", closedByBackAgain.defaultLandingServerId)
    }

    @Test
    fun compactBackFromDetailClearsDefaultLandingSoReWideningCanLandAgain() {
        val store = SharedAdaptiveWorkspaceStore()
        val wide =
            RaftAdaptivePaneState(
                layout = RaftAdaptivePaneLayout.DualPane,
                widthDp = 1280f,
                heightDp = 800f,
                posture = RaftAdaptivePosture.Flat,
                hingeBoundsDp = null,
            )
        val compact =
            RaftAdaptivePaneState(
                layout = RaftAdaptivePaneLayout.Compact,
                widthDp = 420f,
                heightDp = 800f,
                posture = RaftAdaptivePosture.Flat,
                hingeBoundsDp = null,
            )
        val identity = detailIdentity("server-a", "thread-user")
        val payload = launchPayload("server-a", "thread-user")

        store.update {
            SharedAdaptiveWorkspaceSession(
                workspace =
                    RaftAdaptiveWorkspaceState(
                        shellInstanceId = "shell-a",
                        serverId = "server-a",
                        pane = wide,
                    )
            )
        }
        val opened = store.update { it.withOpenedDetail(identity, payload, defaultLanding = false) }
        assertEquals("server-a", opened.defaultLandingServerId)

        // Narrowing retains the user-selected detail full-screen (DetailOnly).
        val narrowed =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(compact),
                        )
                )
            }
        val compactSel = narrowed.workspace!!.selection!!
        assertEquals("server-a", narrowed.defaultLandingServerId)

        // Pressing Back in Compact returns to ListOnly and clears defaultLandingServerId so widening
        // back to DualPane restores the remembered default conversation instead of staying blank.
        val backedToList =
            store.update { session ->
                session.backFromDetail(
                    RaftAdaptiveDetailCloseRequest(
                        shellInstanceId = "shell-a",
                        identityKey = compactSel.identity.key,
                        generation = compactSel.generation,
                    )
                )
            }
        assertNull(backedToList.workspace?.selection)
        assertNull(backedToList.defaultLandingServerId)

        val reWidened =
            store.update { session ->
                session.copy(
                    workspace =
                        reduceRaftAdaptiveWorkspace(
                            session.workspace!!,
                            RaftAdaptiveWorkspaceIntent.WindowChanged(wide),
                        )
                )
            }
        assertNull(reWidened.workspace?.selection)
        assertNull(reWidened.defaultLandingServerId)

        // Same for closeDetailCompletely in Compact.
        val openedAgain = store.update { it.withOpenedDetail(identity, payload, defaultLanding = false) }
        assertEquals("server-a", openedAgain.defaultLandingServerId)
        store.update { session ->
            session.copy(
                workspace =
                    reduceRaftAdaptiveWorkspace(
                        session.workspace!!,
                        RaftAdaptiveWorkspaceIntent.WindowChanged(compact),
                    )
            )
        }
        val closedCompletelyInCompact = store.update { it.closeDetailCompletely() }
        assertNull(closedCompletelyInCompact.workspace?.selection)
        assertNull(closedCompletelyInCompact.defaultLandingServerId)
    }
}
