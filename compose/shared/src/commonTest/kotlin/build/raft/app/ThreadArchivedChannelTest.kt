package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.parseRaftChannelDetail
import build.raft.app.data.toConversationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * artin 11-14: an archived channel (#proj-mobile, archived on the web) opened on the phone with no
 * Archived badge and a live composer. `GET /channels` leaves archived channels out, so the Home
 * directory never knew; the page's own load did (`channelArchived`, `archivedAt`) but the page
 * payload dropped it.
 */
class ThreadArchivedChannelTest {
    @Test
    fun anArchivedChannelsDetailKeepsArchivedMembershipAndItsUnarchiveCapability() {
        val summary =
            assertNotNull(parseRaftChannelDetail(ARCHIVED_CHANNEL_DETAIL_JSON)).toConversationSummary()

        assertTrue(summary.archived)
        // Archived is not "not joined": a member gets the banner, never a Join (web ChatPanel).
        assertTrue(summary.joined)
        assertEquals(true, summary.channelCapabilities?.archiveChannels)
    }

    @Test
    fun theChannelPageKnowsItsChannelIsArchivedFromItsOwnLoad() = runTest {
        val payload = loadedChannelPage(archivedMessagesResponse(channelArchived = true))

        assertEquals("content", payload.status)
        assertTrue(payload.channelArchived)
        assertTrue(threadFooterShowsArchived(payload))
        assertTrue(threadHeaderShowsArchivedBadge(payload, directoryArchived = false))
    }

    @Test
    fun aLiveChannelKeepsItsComposer() = runTest {
        val payload = loadedChannelPage(archivedMessagesResponse(channelArchived = false))

        assertEquals("content", payload.status)
        assertFalse(payload.channelArchived)
        assertFalse(threadFooterShowsArchived(payload))
        assertFalse(threadHeaderShowsArchivedBadge(payload, directoryArchived = false))
    }

    @Test
    fun aReloadedPageFollowsAnUnarchive() {
        val archived = basePayload().copy(status = "content", channelArchived = true, channelCanUnarchive = true)
        val reloaded = basePayload().copy(status = "content", channelArchived = false, channelCanUnarchive = true)
        val loading = basePayload().copy(status = "loading", channelArchived = false)

        assertFalse(archived.withContentSliceFrom(reloaded).channelArchived)
        // A loading frame says nothing about the archive: the banner stays until the page loads.
        assertTrue(archived.withContentSliceFrom(loading).channelArchived)
    }

    @Test
    fun aReplyThreadInAnArchivedChannelShowsTheBannerButNoHeaderBadge() {
        val reply =
            basePayload().copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread, channelArchived = true)

        assertTrue(threadFooterShowsArchived(reply))
        assertFalse(threadHeaderShowsArchivedBadge(reply, directoryArchived = true))
    }

    @Test
    fun unarchiveFollowsTheChannelCapabilityThenTheServerRole() {
        val page = basePayload().copy(channelArchived = true)

        assertTrue(threadArchivedChannelCanUnarchive(page.copy(channelCanUnarchive = true), roleMayArchive = false))
        assertFalse(threadArchivedChannelCanUnarchive(page.copy(channelCanUnarchive = false), roleMayArchive = true))
        assertTrue(threadArchivedChannelCanUnarchive(page, roleMayArchive = true))
        assertFalse(threadArchivedChannelCanUnarchive(page, roleMayArchive = false))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadedChannelPage(body: String): RaftThreadScreenPayload {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = body),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val payload = store.state.value.payload
        scope.cancel()
        return payload
    }

    private companion object {
        /** `GET /api/channels/:id` for an archived public channel the viewer is in (botiverse/slock staging). */
        val ARCHIVED_CHANNEL_DETAIL_JSON =
            """
            {
              "id": "channel-1",
              "serverId": "server-1",
              "name": "proj-mobile",
              "description": "请去 /raft #proj-mobile",
              "type": "channel",
              "createdAt": "2026-03-02T08:00:00.000Z",
              "archivedAt": "2026-11-13T15:20:11.482Z",
              "archivedByUserId": "u1",
              "archivedByAgentId": null,
              "guestVisible": false,
              "guestJoinable": false,
              "joined": true,
              "activityMuted": false,
              "activityMuteSupported": true,
              "channelCapabilities": {
                "addChannelMembers": false,
                "removeChannelMembers": false,
                "changeChannelMemberRoles": false,
                "editChannelMetadata": true,
                "archiveChannels": true,
                "deleteChannels": true,
                "changeChannelVisibility": true,
                "manageGuestAccess": true,
                "federateChannels": true
              }
            }
            """.trimIndent()

        /** `GET /api/messages/channel/:id?limit=50` for that channel (`channelArchived` from isChannelArchived). */
        fun archivedMessagesResponse(channelArchived: Boolean): String =
            """
            {
              "historyLimited": false,
              "channelArchived": $channelArchived,
              "messages": [
                {
                  "id": "m1",
                  "seq": 10,
                  "channelId": "channel-1",
                  "content": "总结一下本 channel 未完成的任务，我准备关停此 channel",
                  "createdAt": "2026-10-20T05:01:00Z",
                  "senderId": "u1",
                  "senderName": "artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()
    }
}
