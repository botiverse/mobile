package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class AnnouncementsRemindersApiTest {
    @Test
    fun typedAnnouncementsRemindersApiAcceptsCanonicalUpstreamShapes() = runBlocking {
        val transport = RecordingAnnouncementsRemindersApi()
        val client = RaftAnnouncementsRemindersApiClient(transport)
        val agentId = MemberId("agent-1")

        val announcements = assertIs<DataResult.Content<AnnouncementsPage>>(client.activeAnnouncements()).data
        assertEquals(1, announcements.announcementCount)
        assertEquals("Release notes", announcements.firstTitle)
        assertEquals(2, announcements.firstPageCount)
        assertEquals("announcement-1", announcements.items.single().id)
        assertEquals("Release notes", announcements.items.single().title)
        assertEquals("One", announcements.items.single().body)
        // The server `Announcement` contract (packages/shared/src/index.ts) is
        // strictly { id, title, pages, publishedAt }. There is no severity or
        // action; the parser must use the data-class defaults instead of
        // sniffing speculative fields.
        assertEquals("View", announcements.items.single().actionLabel)
        assertEquals(null, announcements.items.single().targetId)
        assertEquals(AnnouncementKind.Info, announcements.items.single().kind)

        assertEquals(null, announcements.items.single().label)
        assertEquals(null, announcements.items.single().image)
        assertEquals(null, announcements.items.single().cta)

        assertEquals(
            true,
            assertIs<DataResult.Content<AnnouncementCommandResult>>(client.dismissAnnouncement("announcement-1")).data.ok
        )

        val reminders = assertIs<DataResult.Content<RemindersPage>>(client.reminders(agentId, "scheduled,fired")).data
        assertEquals(1, reminders.reminderCount)
        assertEquals("standup", reminders.firstTitle)
        assertEquals("scheduled", reminders.firstStatus)

        assertEquals(
            listOf(
                "GET announcements/active",
                "POST announcements/announcement-1/dismiss",
                "GET reminders ownerAgentId=agent-1 status=scheduled,fired"
            ),
            transport.calls
        )
    }

    @Test
    fun announcementPresentationFieldsAreRetainedAndUnsafeCtaIsDropped() = runBlocking {
        val client = RaftAnnouncementsRemindersApiClient(
            RecordingAnnouncementsRemindersApi(
                activeBody = """{"announcements":[
                    {"id":"announcement-1","title":"Release notes","label":"Latest","image":{"assetId":"550e8400-e29b-41d4-a716-446655440000","alt":"Release image"},"cta":{"label":"Open","href":"/releases/1"},"pages":[{"body":"One"}],"publishedAt":"2026-05-22T00:00:00.000Z"},
                    {"id":"announcement-2","title":"Unsafe","cta":{"label":"Open","href":"javascript:alert(1)"},"pages":[{"body":"Two"}],"publishedAt":"2026-05-22T00:00:00.000Z"}
                ]}"""
            )
        )
        val items = assertIs<DataResult.Content<AnnouncementsPage>>(client.activeAnnouncements()).data.items
        assertEquals(2, items.size)
        val item = items.first()
        assertEquals("Latest", item.label)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", item.image?.assetId)
        assertEquals("Open", item.cta?.label)
        assertEquals("/releases/1", item.cta?.href)
    }

    @Test
    fun typedAnnouncementsRemindersApiRejectsGuessedWrappersAndUnsupportedCommands() = runBlocking {
        val client =
            RaftAnnouncementsRemindersApiClient(
                RecordingAnnouncementsRemindersApi(
                    activeBody = """{"items":[{"id":"announcement-1","title":"Release notes"}]}""",
                    dismissBody = """{"success":true}""",
                    remindersBody = """{"items":[{"reminderId":"reminder-1","title":"standup"}]}"""
                )
            )
        val agentId = MemberId("agent-1")

        assertEquals(
            "announcements_active_contract_mismatch",
            assertIs<DataResult.Error>(client.activeAnnouncements()).error.code
        )
        assertEquals(
            "announcement_dismiss_contract_mismatch",
            assertIs<DataResult.Error>(client.dismissAnnouncement("announcement-1")).error.code
        )
        assertEquals(
            "reminders_list_contract_mismatch",
            assertIs<DataResult.Error>(client.reminders(agentId)).error.code
        )
    }

    @Test
    fun networkAnnouncementsRemindersApiUsesRaftNetworkClientRoutes() = runBlocking {
        val networkClient = RecordingAnnouncementsRemindersNetworkClient()
        val client = RaftAnnouncementsRemindersApiClient(NetworkRaftAnnouncementsRemindersApi(networkClient))
        val agentId = MemberId("agent-1")

        assertIs<DataResult.Content<AnnouncementsPage>>(client.activeAnnouncements())
        assertIs<DataResult.Content<AnnouncementCommandResult>>(client.dismissAnnouncement("announcement-1"))
        assertIs<DataResult.Content<RemindersPage>>(client.reminders(agentId, "scheduled,fired"))

        assertEquals(
            listOf(
                "GET announcements/active priority=Visible query= server= body=",
                "POST announcements/announcement-1/dismiss priority=Foreground query= server= body=",
                "GET reminders priority=Visible query=ownerAgentId=agent-1&status=scheduled,fired server= body="
            ),
            networkClient.calls
        )
    }

    private class RecordingAnnouncementsRemindersApi(
        private val activeBody: String =
            """{"announcements":[{"id":"announcement-1","title":"Release notes","pages":[{"body":"One"},{"body":"Two"}],"publishedAt":"2026-05-22T00:00:00.000Z"}]}""",
        private val dismissBody: String = """{"ok":true}""",
        private val remindersBody: String =
            """{"reminders":[{"reminderId":"reminder-1","title":"standup","fireAt":"2026-05-22T00:00:00.000Z","status":"scheduled"}]}"""
    ) : RaftAnnouncementsRemindersApi {
        val calls = mutableListOf<String>()

        override suspend fun activeAnnouncements(): HomeNetworkResponse {
            calls += "GET announcements/active"
            return ok(activeBody)
        }

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse {
            calls += "POST announcements/$announcementId/dismiss"
            return ok(dismissBody)
        }

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse {
            calls += "GET reminders ownerAgentId=${ownerAgentId.value} status=$status"
            return ok(remindersBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class RecordingAnnouncementsRemindersNetworkClient : RaftNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalizedPath = path.trim('/')
            calls +=
                "$method $normalizedPath priority=$priority query=${query.joinToString("&") { "${it.first}=${it.second}" }} " +
                    "server=${serverIdOverride?.value.orEmpty()} body=${bodyText.orEmpty()}"
            return when (normalizedPath) {
                "announcements/active" -> ok(activeBody)
                "announcements/announcement-1/dismiss" -> ok(dismissBody)
                "reminders" -> ok(remindersBody)
                else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
            }
        }

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)

        private companion object {
            const val activeBody =
                """{"announcements":[{"id":"announcement-1","title":"Release notes","pages":[{"body":"One"},{"body":"Two"}],"publishedAt":"2026-05-22T00:00:00.000Z"}]}"""
            const val dismissBody = """{"ok":true}"""
            const val remindersBody =
                """{"reminders":[{"reminderId":"reminder-1","title":"standup","fireAt":"2026-05-22T00:00:00.000Z","status":"scheduled"}]}"""
        }
    }
}
