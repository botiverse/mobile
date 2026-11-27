package build.raft.app

import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.data.ActivityObjectRow
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * Unit coverage for wide Activity's right-pane Artifact state, external row selection resolution,
 * arrival reopen guard for Artifact rows, and wide navigation history entries.
 */
class ActivityWideArtifactStateTest {
    private val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "s1")

    private class RecordingNavigator : RaftCmpNavigator {
        val opened = mutableListOf<Pair<String, JsonObject>>()

        override fun openPage(pageName: String, pageData: JsonObject) {
            opened += pageName to pageData
        }

        override fun closePage() = Unit
    }

    private fun artifactRow(id: String, title: String = "Spec") =
        InboxItem(
            key = "object:artifact:$id",
            kind = InboxItemKind.Object,
            channelId = ChannelId("object:$id"),
            channelName = title,
            unreadCount = 1,
            activityObject =
                ActivityObjectRow(
                    objectKind = "artifact",
                    objectId = id,
                    title = title,
                    artifactKind = "html",
                    eventKind = "comment",
                    reason = "mentioned",
                    actorType = "user",
                    actorId = "u1",
                    actorName = "artin",
                ),
        )

    private fun threadRow(threadId: String) =
        InboxItem(
            key = "thread:$threadId",
            kind = InboxItemKind.Thread,
            channelId = ChannelId(threadId),
            threadChannelId = ChannelId(threadId),
            parentChannelId = ChannelId("c-general"),
            channelName = "general",
            unreadCount = 2,
        )

    @Test
    fun openSwitchCloseAndPromoteToWindowLifecycle() {
        val state = ActivityWideArtifactState()
        assertFalse(state.isOpen)
        assertNull(state.currentArtifactId)
        assertNull(state.currentTarget)
        assertFalse(state.close())

        state.open(ArtifactLinkTarget("pt_alpha"), scope)
        val firstEntryId = state.pane.entry!!.id
        assertTrue(state.isOpen)
        assertEquals("pt_alpha", state.currentArtifactId)
        assertEquals(ArtifactLinkTarget("pt_alpha"), state.currentTarget)

        // Opening another Artifact replaces the current entry in place.
        state.open(ArtifactLinkTarget("pt_beta"), scope)
        assertTrue(state.isOpen)
        assertEquals("pt_beta", state.currentArtifactId)
        assertTrue(state.pane.entry!!.id != firstEntryId)

        // Closing a stale entry id does nothing.
        state.close(firstEntryId)
        assertTrue(state.isOpen)
        assertEquals("pt_beta", state.currentArtifactId)

        // Narrowing promotes the open viewer onto the platform window stack and clears split state.
        val navigator = RecordingNavigator()
        assertTrue(state.promoteToWindow(navigator))
        assertFalse(state.isOpen)
        assertNull(state.currentArtifactId)
        assertEquals(1, navigator.opened.size)
        assertEquals(RAFT_ARTIFACT_VIEWER_PAGE, navigator.opened[0].first)
        assertEquals(ArtifactLinkTarget("pt_beta"), navigator.opened[0].second.toKuiklyJson().raftArtifactViewerTargetOrNull())
        assertFalse(state.promoteToWindow(navigator))
    }

    @Test
    fun externalSelectedItemKeyMatchesArtifactsByCanonicalIdAndConversationsByThreadId() {
        val uuid = "c82e597f-5434-4741-909b-d1c8a7a531c8"
        val shortId = build.raft.app.data.encodeArtifactShortId(uuid)!!
        val items = listOf(artifactRow("pt_$uuid", "Launch doc"), threadRow("t-1"))

        assertEquals(
            "object:artifact:pt_$uuid",
            activityExternalSelectedItemKey(items, externalSelectedThreadId = null, externalSelectedArtifactId = "pt_$uuid"),
        )
        // Base58 22-char short id still resolves to the same Activity Artifact row.
        assertEquals(
            "object:artifact:pt_$uuid",
            activityExternalSelectedItemKey(items, externalSelectedThreadId = null, externalSelectedArtifactId = shortId),
        )
        // When both are non-null, Artifact takes precedence for Activity's detail pane.
        assertEquals(
            "object:artifact:pt_$uuid",
            activityExternalSelectedItemKey(items, externalSelectedThreadId = "t-1", externalSelectedArtifactId = "pt_$uuid"),
        )
        // Conversation selection highlights the thread row and never collides with object rows.
        assertEquals(
            "thread:t-1",
            activityExternalSelectedItemKey(items, externalSelectedThreadId = "t-1", externalSelectedArtifactId = null),
        )
        assertNull(activityExternalSelectedItemKey(items, externalSelectedThreadId = "object:pt_$uuid", externalSelectedArtifactId = null))
        assertNull(activityExternalSelectedItemKey(items, externalSelectedThreadId = null, externalSelectedArtifactId = null))
    }

    @Test
    fun arrivalReopenNeverAutoReopensArtifactRow() {
        val items = listOf(artifactRow("pt_a"), threadRow("t-1"))
        assertNull(
            activityArrivalReopenTarget(
                wide = true,
                somethingOpen = false,
                items = items,
                lastOpenedKey = "object:artifact:pt_a",
            ),
            "Artifact rows are never auto-reopened on arrival (web useThreadsInboxOpen.reopen)",
        )
        assertEquals(
            "thread:t-1",
            activityArrivalReopenTarget(
                wide = true,
                somethingOpen = false,
                items = items,
                lastOpenedKey = "thread:t-1",
            )?.key,
        )
        assertNull(
            activityArrivalReopenTarget(
                wide = true,
                somethingOpen = true,
                items = items,
                lastOpenedKey = "thread:t-1",
            ),
            "Nothing auto-reopens when an Artifact is already open beside the Activity list",
        )
    }

    @Test
    fun wideNavigationHistoryTracksActivityArtifactAndSwitchingWithConversation() {
        val convRoute = WireThreadLaunchPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, threadId = "t-1", sourceThreadId = "t-1")
        val convId = convRoute.toRaftAdaptiveDetailIdentity("s1")!!

        val artifactEntry =
            raftWideNavEntryOf(
                serverId = "s1",
                selectedTab = "activity",
                railInlineSecondaryDestination = null,
                selectedHomeDestination = null,
                tasksDetailColumnOpen = false,
                conversation = null,
                conversationRoute = null,
                member = null,
                activityArtifact = ArtifactLinkTarget("pt_a"),
            )!!
        val convEntry =
            raftWideNavEntryOf(
                serverId = "s1",
                selectedTab = "activity",
                railInlineSecondaryDestination = null,
                selectedHomeDestination = null,
                tasksDetailColumnOpen = false,
                conversation = convId,
                conversationRoute = convRoute,
                member = null,
                activityArtifact = null,
            )!!
        // Chat tab ignores Activity's artifact state.
        val chatEntry =
            raftWideNavEntryOf(
                serverId = "s1",
                selectedTab = "home",
                railInlineSecondaryDestination = null,
                selectedHomeDestination = null,
                tasksDetailColumnOpen = false,
                conversation = convId,
                conversationRoute = convRoute,
                member = null,
                activityArtifact = ArtifactLinkTarget("pt_a"),
            )!!

        assertEquals(RaftWideNavDetail.Artifact(ArtifactLinkTarget("pt_a")), artifactEntry.detail)
        assertEquals(RaftWideNavDetail.Conversation(convId, convRoute.toRaftWideNavRoute()), convEntry.detail)
        assertEquals(RaftWideNavDetail.Conversation(convId, convRoute.toRaftWideNavRoute()), chatEntry.detail)

        val history =
            RaftWideNavHistory()
                .record(RaftWideNavEntry("s1", "activity"))
                .record(artifactEntry)
                .record(convEntry)
        assertEquals(listOf(artifactEntry, convEntry), history.entries)
        assertEquals(artifactEntry, history.go(RaftWideNavDirection.Back).current)
    }
}
