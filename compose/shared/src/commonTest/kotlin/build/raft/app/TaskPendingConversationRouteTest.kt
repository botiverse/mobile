package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import build.raft.app.nav.RaftMobileStackMotion
import build.raft.app.nav.RaftPageStack
import build.raft.app.thread.THREAD_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Tests for the pushed pending task route ([TASK_PENDING_CONVERSATION_PAGE], #proj-mobile:#161):
 * identity-only pageData and route deduplication, seed store session lifecycle, live task candidate
 * resolution, channel membership / write checks, first-send + `task:updated` replace-in-place
 * transitions, and zero-motion stack replacement.
 */
class TaskPendingConversationRouteTest {
    private val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "server-1")

    @Test
    fun resolvePendingTaskSummaryPrefersConversationThenHighestRevisionThenCallerOrder() {
        val seed = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val optimisticSameRevision = seed.copy(status = TaskStatus.InProgress, revision = 1L)
        val higherRevision = seed.copy(status = TaskStatus.InReview, revision = 2L)
        val conversationArrived = seed.copy(conversationChannelId = ChannelId("conv-1"), conversationAnchored = true, revision = 1L)
        val otherTask = seed.copy(id = TaskId("t-other"), revision = 99L)

        // Optimistic same-revision edit from channelTasks beats the opener seed.
        assertEquals(
            optimisticSameRevision,
            resolvePendingTaskSummary("t-1", optimisticSameRevision, seed, otherTask),
        )
        // Higher revision from a store beats a stale copy.
        assertEquals(
            higherRevision,
            resolvePendingTaskSummary("t-1", seed, higherRevision),
        )
        // A copy with a conversation always wins so the page hands off.
        assertEquals(
            conversationArrived,
            resolvePendingTaskSummary("t-1", higherRevision, conversationArrived),
        )
        // At equal revision with no conversation yet, a hydrated row beats a summary-only row.
        val summaryOnly = seed.copy(conversationDetailsLoaded = false, revision = 2L)
        val hydrated = seed.copy(conversationDetailsLoaded = true, revision = 2L)
        assertEquals(
            hydrated,
            resolvePendingTaskSummary("t-1", summaryOnly, hydrated),
        )
        // A revisioned summary-only row still beats a revisionless hydrated copy.
        val revisionlessHydrated = seed.copy(conversationDetailsLoaded = true, revision = null)
        assertEquals(
            summaryOnly,
            resolvePendingTaskSummary("t-1", summaryOnly, revisionlessHydrated),
        )
        assertNull(resolvePendingTaskSummary("missing", seed, higherRevision))
    }

    @Test
    fun channelAccessUsesDirectoryProjectionLocalJoinAndSeedFallback() {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val emptyDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState()

        // Without directory row, opener seed hints govern until locally joined.
        val unjoinedFromSeed =
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = emptyDirectory,
                joinedLocally = false,
                seed = TaskPendingSeed(pending, channelJoined = false, canWriteChannel = false),
            )
        assertEquals(TaskPendingChannelAccess(joined = false, canWriteChannel = false), unjoinedFromSeed)

        val joinedAfterTap =
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = emptyDirectory,
                joinedLocally = true,
                seed = TaskPendingSeed(pending, channelJoined = false, canWriteChannel = false),
            )
        assertEquals(TaskPendingChannelAccess(joined = true, canWriteChannel = true), joinedAfterTap)

        // Live directory projection: unjoined public channel -> joined=false, canWriteChannel=false; archived -> canWriteChannel=false.
        val unjoinedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                joined = false,
                archived = false,
            )
        assertEquals(
            TaskPendingChannelAccess(joined = false, canWriteChannel = false),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(unjoinedProjection).toSharedConversationDirectoryState(),
                joinedLocally = false,
            ),
        )
        // System #all channel is always postable even if joined=false.
        val allProjection = unjoinedProjection.copy(title = "all")
        assertEquals(
            TaskPendingChannelAccess(joined = true, canWriteChannel = true),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(allProjection).toSharedConversationDirectoryState(),
                joinedLocally = false,
            ),
        )
        // Archived joined channel stays non-writable even if joinedLocally=true.
        val archivedProjection = unjoinedProjection.copy(joined = true, archived = true)
        assertEquals(
            TaskPendingChannelAccess(joined = true, canWriteChannel = false),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(archivedProjection).toSharedConversationDirectoryState(),
                joinedLocally = true,
            ),
        )
    }

    @Test
    fun replaceConversationPageDataReplacesPendingPageInPlaceWithoutMotion() {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val launchPayload =
            buildStandaloneTaskThreadLaunchPayload(
                task = pending,
                serverId = "server-1",
                conversationDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState(),
                timeContext = null,
                resolvedConversationChannelId = ChannelId("conv-local"),
            )!!
        val replaceData = taskPendingReplaceConversationPageData(launchPayload)
        assertTrue(replaceData.optBoolean(RAFT_REPLACE_CURRENT_PAGE, false))
        assertTrue(replaceData.optBoolean(RAFT_OPEN_WITHOUT_ANIMATION, false))

        // Verify RaftPageStack replaces the pending page in place (Back returns straight to the shell)
        // and RaftMobileStackMotion suppresses the replaced page's pop-exit animation.
        val stack = RaftPageStack()
        val shell = stack.pages.single()
        val pendingJson = Json.parseToJsonElement(taskPendingPageData(pending, scope).toString()).jsonObject
        stack.navigatorFor(shell).openPage(TASK_PENDING_CONVERSATION_PAGE, pendingJson)
        val pendingPage = stack.pages.last()
        val motion = RaftMobileStackMotion()
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = false)
        assertFalse(motion.exitsWithoutMotion(pendingPage.contentKey))

        val replaceJson = Json.parseToJsonElement(replaceData.toString()).jsonObject
        stack.navigatorFor(pendingPage).openPage(THREAD_PAGE, replaceJson)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, THREAD_PAGE), stack.pages.map { it.args.pageName })
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = true)
        assertTrue(motion.exitsWithoutMotion(pendingPage.contentKey))

        // A normal pop of THREAD_PAGE back to the shell keeps its pop-exit animation.
        val threadPage = stack.pages.last()
        assertTrue(stack.back())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), stack.pages.map { it.args.pageName })
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = false)
        assertFalse(motion.exitsWithoutMotion(threadPage.contentKey))
    }

    @Test
    fun aTaskWithoutAConversationOpensAsItsPageUnlessTheWideLayoutIsOn() {
        // #307: from a chat, a wide window with "Desktop layout on large screens" off still got the
        // old floating surface while the Tasks tab opened the page; both ask the same rule now.
        fun pane(widthDp: Int, heightDp: Int) =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(widthDp * 2, heightDp * 2, 2f, RaftAdaptivePanePosture.Flat),
            )
        val tablet = pane(1024, 768)
        assertTrue(taskPendingOpensAsPage(desktopModeEnabled = false, pane = tablet), "Desktop layout off: the page")
        assertFalse(taskPendingOpensAsPage(desktopModeEnabled = true, pane = tablet), "the wide layout keeps its surface")
        assertTrue(taskPendingOpensAsPage(desktopModeEnabled = true, pane = pane(402, 874)), "a phone: the page")
    }

    @Test
    fun pendingRouteParamsAndHandOffPreserveFocusedMessageId() {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}""")
        val pageData =
            taskPendingPageData(
                taskId = pending.id.value,
                channelId = pending.channelId,
                storeScope = scope,
                focusedMessageId = "msg-target-42",
            )
        val parsed = parseTaskPendingRouteParams(pageData, scope)
        assertEquals("t-1", parsed.taskId)
        assertEquals(ChannelId("channel-1"), parsed.channelId)
        assertEquals("msg-target-42", parsed.focusedMessageId)

        val launch =
            buildStandaloneTaskThreadLaunchPayload(
                task = pending,
                serverId = "server-1",
                conversationDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState(),
                timeContext = null,
                resolvedConversationChannelId = ChannelId("conv-local"),
                anchorMessageId = parsed.focusedMessageId,
            )!!
        assertEquals("conv-local", launch.streamChannelId)
        assertEquals("msg-target-42", launch.focusedMessageId)
    }

    private fun task(fields: String): TaskSummary {
        val base =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"msg-host","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,"""
        val json = base + fields.trim().removePrefix("{")
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }
}
