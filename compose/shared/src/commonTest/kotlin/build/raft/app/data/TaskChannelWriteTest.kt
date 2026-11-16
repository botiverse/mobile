package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The task write gate (web `canWriteTasksInChannel`, botiverse/slock #15155
 * `packages/web/src/components/task/taskChannelWrite.ts`, merged at 14d508af4). The server refuses
 * every task write without post authority (`routes/tasks.ts` `rejectIfNoTaskWriteAccess`, 403) and
 * in an archived channel (`rejectIfArchived`, 409); `channelService.canUserPostToChannel` lets every
 * human member post in the enabled `#all` without a membership row. Wire rows follow
 * `GET /api/channels/:id` at botiverse/slock staging d8173995a (`routes/channels.ts`: `joined`,
 * `archivedAt`; no `canPost` on a channel row, only on `POST /agents/:id/lounge`).
 */
class TaskChannelWriteTest {
    @AfterTest
    fun forgetLounges() = RaftAgentLounges.clear()

    @Test
    fun onlyAKnownNoHidesTaskWrites() {
        // Unknown: a channel that is not loaded, or whose membership is not hydrated.
        assertTrue(canWriteTasksInChannel(null))
        assertTrue(canWriteTasksInChannel(TaskChannelWriteFacts(joined = null)))
        assertTrue(canWriteTasksInChannel(TaskChannelWriteFacts(joined = true)))

        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(joined = true, archived = true)), "archived")
        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(joined = true, canPost = false)), "canPost false")
        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(joined = false)), "joined false")
    }

    @Test
    fun aDmALoungeAndAllHaveNothingToJoinFirst() {
        assertTrue(canWriteTasksInChannel(TaskChannelWriteFacts(joined = false, directMessage = true)), "DM")
        assertTrue(canWriteTasksInChannel(TaskChannelWriteFacts(joined = false, lounge = true)), "Lounge")
        assertTrue(canWriteTasksInChannel(TaskChannelWriteFacts(joined = false, enabledAllChannel = true)), "#all")

        // Still never in an archived one, nor where the Lounge said no.
        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(directMessage = true, archived = true)))
        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(lounge = true, canPost = false)))
        assertFalse(canWriteTasksInChannel(TaskChannelWriteFacts(enabledAllChannel = true, archived = true)))
    }

    @Test
    fun allIsTheSystemPublicChannelOnly() {
        assertTrue(isEnabledAllChannel("channel", "all"))
        assertTrue(isEnabledAllChannel("channel", "#all"))
        assertFalse(isEnabledAllChannel("private", "all"), "a private #all is disabled (server isEnabledAllChannel)")
        assertFalse(isEnabledAllChannel("channel", "all-hands"))
    }

    @Test
    fun aChannelRowsTaskPermissionFollowsMembershipAndArchive() {
        fun canCreateTask(json: String): Boolean =
            assertNotNull(parseRaftChannelDetail(json), json).permissions.canCreateTask

        assertTrue(canCreateTask(channelDetail(joined = true)))
        assertFalse(canCreateTask(channelDetail(joined = false)), "a public channel read without joining it")
        assertFalse(canCreateTask(channelDetail(joined = true, archivedAt = "\"2026-11-13T15:20:11.482Z\"")), "archived")
        assertTrue(canCreateTask(channelDetail(name = "all", joined = false)), "#all needs no membership row")
    }

    @Test
    fun theDirectoryAnswersForTheTasksChannel() {
        val server = ServerId("server-1")
        val directory =
            listOf(
                row("ch-member", joined = true),
                row("ch-reader", joined = false),
                row("ch-archived", joined = true, archived = true),
                row("ch-all", name = "all", joined = false),
                row("ch-lounge", joined = false, loungeAgentId = "agent-bugen"),
            ).mapNotNull { it.toSharedConversationDirectoryProjection(server) }
                .toSharedConversationDirectoryState()

        assertTrue(directory.canWriteTasksInChannel(server, ChannelId("ch-member")))
        assertFalse(directory.canWriteTasksInChannel(server, ChannelId("ch-reader")))
        assertFalse(directory.canWriteTasksInChannel(server, ChannelId("ch-archived")))
        assertTrue(directory.canWriteTasksInChannel(server, ChannelId("ch-all")))
        assertTrue(directory.canWriteTasksInChannel(server, ChannelId("ch-lounge")))
        assertTrue(directory.canWriteTasksInChannel(server, ChannelId("ch-unlisted")), "not known here: the server decides")
        assertTrue(directory.canWriteTasksInChannel(server, null))

        // The Lounge's open answer said this person may only read it.
        RaftAgentLounges.rememberRow(server.value, row("ch-lounge", joined = false, loungeAgentId = "agent-bugen"), canPost = false)
        assertFalse(directory.canWriteTasksInChannel(server, ChannelId("ch-lounge")))
    }

    @Test
    fun aConversationKeepsReadingItsTasksWhereItMayNotWriteThem() {
        val member = conversation(joined = true, canCreateTask = true)
        assertTrue(member.channelTaskSurfacesShown())
        assertTrue(member.channelTaskWritesAllowed())

        // Archived: the Tasks tab stays; the page drops the writes from its live channelArchived
        // (ThreadArchivedChannelTest), so an unarchive on the open page brings them back.
        val archivedRow = conversation(joined = true, canCreateTask = false, archived = true)
        assertTrue(archivedRow.channelTaskSurfacesShown())

        val reader = conversation(joined = false, canCreateTask = false)
        assertFalse(reader.channelTaskSurfacesShown())
        assertFalse(reader.channelTaskWritesAllowed())

        val dm = conversation(joined = true, canCreateTask = true).copy(kind = ConversationKind.DirectMessage, channelType = "dm")
        assertTrue(dm.channelTaskWritesAllowed())
    }

    private fun conversation(joined: Boolean, canCreateTask: Boolean, archived: Boolean = false) =
        ConversationSummary(
            threadId = ThreadId("ch-1"),
            channelId = ChannelId("ch-1"),
            channelType = "channel",
            kind = ConversationKind.Channel,
            name = "ship-mobile",
            joined = joined,
            archived = archived,
            permissions = ConversationPermissions(canSend = joined, canAttach = joined, canCreateTask = canCreateTask),
        )

    private fun row(
        id: String,
        name: String = id,
        joined: Boolean,
        archived: Boolean = false,
        loungeAgentId: String? = null,
    ) = HomeChannelRow(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        channelType = "channel",
        name = name,
        joined = joined,
        archived = archived,
        loungeAgentId = loungeAgentId,
    )

    private fun channelDetail(name: String = "proj-mobile", joined: Boolean, archivedAt: String = "null"): String =
        """
        {
          "id": "channel-1",
          "serverId": "server-1",
          "name": "$name",
          "description": null,
          "type": "channel",
          "createdAt": "2026-03-02T08:00:00.000Z",
          "archivedAt": $archivedAt,
          "archivedByUserId": null,
          "archivedByAgentId": null,
          "guestVisible": false,
          "guestJoinable": false,
          "loungeAgentId": null,
          "joined": $joined,
          "activityMuted": false,
          "activityMuteSupported": true
        }
        """.trimIndent()
}
