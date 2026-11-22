package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SyncTestPreferences
import build.raft.app.data.openWithinWideDetail
import build.raft.app.data.visibleSideThread
import build.raft.app.data.withOpenedDetail
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What was open beside a conversation on a tablet, replayed through the shell's own pieces: the shared workspace and
 * its side thread, the side columns ([RaftWideSideColumns], moved with the conversation as rememberRaftWideSideColumns
 * moves them), what the wide layout shows (raftWideNavEntryOf), and the beside memory ([AppShellBesideMemory] over the
 * production [AppShellBesideColumnsHost] and storage). Only the visibility answers are the test's ([Shell.answers]).
 * [Shell] stands in for the SettingsHomePage glue: a row picked, the history's restore path, the pages opening into
 * the columns, and what each composition's effects do.
 */
class AppShellBesideMemoryReplayTest {
    private val c1 = channel("c1")
    private val c2 = channel("c2")
    private val artifact = ArtifactLinkTarget("pt_a", version = 2)
    private val agent = MemberDetailIdentity(MemberId("a1"), MemberKind.Agent, ServerId("s1"))
    private val person = MemberDetailIdentity(MemberId("h1"), MemberKind.Human, ServerId("s1"))

    @Test
    fun backToAConversationOpensWhatWasOpenBesideItWithTheOneInFrontLast() =
        runTest {
            val shell = Shell()
            shell.open(c1)
            shell.openThread("m1")
            shell.openArtifact(artifact)
            shell.openProfile(agent)
            shell.agentShowsTab("activity")

            shell.open(c2)
            assertNull(shell.sideThread, "another conversation shows none of c1's panes")
            assertFalse(shell.columns.artifact.isOpen)
            assertFalse(shell.columns.profile.isOpen)
            assertTrue(shell.checks.isEmpty(), "c2 has nothing remembered: nothing is asked")

            // The wide history's Back reopens c1's entry, which keeps no panes: the memory opens them beside it again.
            shell.historyBack(to = c1)
            assertEquals("m1", shell.sideThread)
            assertEquals(artifact, shell.artifactOpen)
            assertEquals("a1", shell.profileOpen?.memberId?.value)
            assertEquals("activity", shell.profileTabOpen, "an agent's profile reopens on the tab it was on")
            assertFalse(
                shell.columns.artifact.isOnTopOf(shell.columns.profile),
                "the profile was in front: opened last, Back closes it first",
            )
            assertEquals(1, shell.checks.size, "every kind checked together, once")
        }

    @Test
    fun whatIsGoneIsForgottenAndWhatHadNoAnswerOpensNextTime() =
        runTest {
            val shell = Shell()
            shell.open(c1)
            shell.openThread("m1")
            shell.openArtifact(artifact)
            shell.openProfile(person)
            shell.open(c2)

            shell.answers[RaftBesideKind.Thread] = RaftBesideVisibility.Unknown
            shell.answers[RaftBesideKind.Artifact] = RaftBesideVisibility.Gone
            shell.historyBack(to = c1)
            assertNull(shell.sideThread, "no answer (offline): not opened this time")
            assertFalse(shell.columns.artifact.isOpen, "gone: never shown")
            assertEquals("h1", shell.profileOpen?.memberId?.value)
            assertEquals(
                RaftBesideState(
                    thread = "m1",
                    profile = RaftBesideProfile(RaftBesideProfileKind.Human, "h1"),
                    front = RaftBesideKind.Profile,
                ),
                shell.remembered("c1"),
                "the Artifact is forgotten, the thread kept",
            )

            shell.open(c2)
            shell.answers.clear()
            shell.historyBack(to = c1)
            assertEquals("m1", shell.sideThread, "the next visit asks again, and the thread opens")
        }

    @Test
    fun withinTheConversationItsPanesStayAndOnlyWhatIsClosedIsForgotten() =
        runTest {
            val shell = Shell()
            shell.open(c1)
            shell.openArtifact(artifact)
            shell.openProfile(person)

            shell.open(c1.copy(focusedMessageId = "m5"))
            shell.open(replyThread("m9"))
            assertTrue(shell.columns.artifact.isOpen && shell.columns.profile.isOpen, "a message jumped to, a thread in its place: kept")
            assertEquals("m9", shell.remembered("c1")?.thread, "a thread shown in the conversation's place is its thread")

            shell.historyBack(to = c1)
            assertNull(shell.remembered("c1")?.thread, "Back from the thread closed it: forgotten")
            assertEquals(artifact.id, shell.remembered("c1")?.artifact?.id)

            shell.open(c2)
            assertFalse(shell.columns.artifact.isOpen, "another conversation closes them")
            assertEquals(artifact.id, shell.remembered("c1")?.artifact?.id, "leaving forgets nothing")
        }

    @Test
    fun aFocusedMessageTakesTheThreadsPlaceAndTheRestOpensBehindIt() =
        runTest {
            val shell = Shell()
            shell.open(c1)
            shell.openThread("m1")
            shell.openProfile(person)
            shell.open(c2)

            // A notification for a message of c1: it is in the thread's place.
            shell.open(c1.copy(focusedMessageId = "m5"))
            assertNull(shell.sideThread)
            assertEquals("h1", shell.profileOpen?.memberId?.value, "the profile opens behind it")
            assertEquals("m1", shell.remembered("c1")?.thread, "the thread stays remembered")
        }

    private fun channel(id: String) =
        WireThreadLaunchPayload(surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = id, streamChannelId = id)

    /** A reply thread of c1, as the channel page opens one (and a notification or a link to it does). */
    private fun replyThread(parentMessageId: String) =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            threadId = "stream-$parentMessageId",
            sourceThreadId = "stream-$parentMessageId",
            streamChannelId = "stream-$parentMessageId",
            parentChannelId = "c1",
            parentMessageId = parentMessageId,
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
            parentChannelKind = RaftThreadSurfaceKind.Channel,
        )

    /** The app shell on a tablet (desktop mode, the rail beside a dual pane), modelled on SettingsHomePage. */
    private inner class Shell {
        private val preferences = SyncTestPreferences()
        private val repository = RaftBesideMemoryRepository(preferences, nowMillis = { 0L })
        private val store = SharedAdaptiveWorkspaceStore()
        val columns = RaftWideSideColumns()

        /** What the visibility checks answer, per kind; visible where nothing is set. */
        val answers = mutableMapOf<RaftBesideKind, RaftBesideVisibility>()

        /** What was checked, one entry per restore. */
        val checks = mutableListOf<RaftBesideState>()

        private val memory =
            AppShellBesideMemory(
                repository = repository,
                host =
                    AppShellBesideColumnsHost(
                        columns = columns,
                        workspace = store,
                        desktopMode = { true },
                        storeScope = { RaftShellStoreScope.create("shell", "s1") },
                    ),
                visibility =
                    AppShellBesideVisibilityCheck { _, state ->
                        checks += state
                        state.kinds.associateWith { answers[it] ?: RaftBesideVisibility.Visible }
                    },
            )
        private var columnsConversation: RaftDetailConversation? = null
        private var visit: AppShellBesideVisitKey? = null

        val sideThread: String?
            get() = store.snapshot().visibleSideThread()?.payload?.parentMessageId

        val artifactOpen: ArtifactLinkTarget?
            get() = columns.artifact.entry?.pageData?.toKuiklyJson()?.raftArtifactViewerTargetOrNull()

        private val profilePage: RaftMemberDetailPayload?
            get() = columns.profile.profile.entries.lastOrNull()?.pageData?.toKuiklyJson()?.toRaftMemberDetailPayload()

        val profileOpen: MemberDetailIdentity?
            get() = profilePage?.identity

        val profileTabOpen: String?
            get() = profilePage?.initialAgentTab

        init {
            val pane =
                RaftAdaptivePaneState(
                    widthPx = 2400,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat,
                    layout = RaftAdaptivePaneLayout.DualPane,
                )
            val workspace =
                reduceRaftAdaptiveWorkspace(
                    RaftAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = "s1"),
                    RaftAdaptiveWorkspaceIntent.WindowChanged(pane),
                )
            // The detail pane measured with room for the thread's column beside the conversation.
            store.update { it.copy(workspace = workspace, detailPaneWidthDp = 1200f) }
        }

        fun remembered(conversation: String): RaftBesideState? = repository.read("u1", "s1", conversation)

        /** A row picked, a notification or a link (openLaunchPayloadInAdaptiveDetail). */
        suspend fun open(route: WireThreadLaunchPayload) {
            store.update { it.withOpenedDetail(route.toRaftAdaptiveDetailIdentity("s1")!!, route) }
            compose()
        }

        /** The wide history's Back (restoreWideNavEntry): the entry's route reopens through the same open path. */
        suspend fun historyBack(to: WireThreadLaunchPayload) = open(to)

        /** A thread's replies tapped in the conversation (ThreadPage openWithinWideDetail): its side column. */
        suspend fun openThread(parentMessageId: String) {
            val main = store.snapshot().workspace!!.selection!!
            val source = RaftAdaptiveDetailCloseRequest("shell", main.identity.key, main.generation)
            store.update { it.openWithinWideDetail(source, replyThread(parentMessageId), "s1", desktopModeOnLargeScreen = true) }
            compose()
        }

        /** An Artifact card opened in the conversation (RaftWorkspaceDetailGrid `openArtifact`). */
        suspend fun openArtifact(target: ArtifactLinkTarget) {
            val pageData = raftArtifactViewerPageData(target, "c1").toJsonObject()
            columns.artifact = columns.artifact.open(RAFT_ARTIFACT_VIEWER_PAGE, pageData, columns.profile)
            compose()
        }

        /** A member's avatar tapped in the conversation: their profile in the profile column. */
        suspend fun openProfile(identity: MemberDetailIdentity) {
            val pageData = RaftMembersPayload(instanceId = "thread").toMemberDetailPageData(identity)!!.toJsonObject()
            columns.profile = columns.profile.open(MEMBER_DETAIL_PAGE, pageData)
            compose()
        }

        /** The agent profile's tab picked, as MembersDetailContent reports it. */
        suspend fun agentShowsTab(tab: String) {
            columns.reportAgentTab(columns.profile.profile.entries.last().id, tab)
            compose()
        }

        /** What the wide layout shows (SettingsHomePage `wideNavShowing`): Chat with the shared detail. */
        private fun shown(): RaftWideNavEntry? {
            val session = store.snapshot()
            return raftWideNavEntryOf(
                serverId = "s1",
                selectedTab = RaftRailChatTab,
                railInlineSecondaryDestination = null,
                selectedHomeDestination = null,
                tasksDetailColumnOpen = false,
                conversation = session.workspace?.selection?.identity,
                conversationRoute = session.launchPayload,
                member = null,
            )
        }

        /** A composition after a step: rememberRaftWideSideColumns, then AppShellBesideMemoryEffect. */
        private suspend fun compose() {
            val conversation = store.snapshot().workspace?.selection?.identity?.detailConversation()
            if (conversation != columnsConversation) {
                columnsConversation = conversation
                columns.moveTo(conversation)
            }
            val entry = raftBesideConversationOf(shown())
            val key = entry?.let { AppShellBesideVisitKey("u1", it.detail) }
            if (key != visit) {
                memory.leave()
                visit = key
                if (key != null && entry != null) {
                    memory.enter(key, entry.threadTaken)?.let { plan -> memory.restore(key, plan) { entry.threadTaken } }
                }
            }
            val current = visit ?: return
            memory.shown(current)?.let { memory.commit(current, it) }
        }
    }
}
