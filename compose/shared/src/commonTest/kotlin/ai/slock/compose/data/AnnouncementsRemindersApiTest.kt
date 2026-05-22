package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class AnnouncementsRemindersApiTest {
    @Test
    fun typedAnnouncementsRemindersApiAcceptsCanonicalUpstreamShapes() = runBlocking {
        val transport = RecordingAnnouncementsRemindersApi()
        val client = SlockAnnouncementsRemindersApiClient(transport)
        val agentId = MemberId("agent-1")

        val announcements = assertIs<DataResult.Content<AnnouncementsPage>>(client.activeAnnouncements()).data
        assertEquals(1, announcements.announcementCount)
        assertEquals("Release notes", announcements.firstTitle)
        assertEquals(2, announcements.firstPageCount)

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
    fun typedAnnouncementsRemindersApiRejectsGuessedWrappersAndUnsupportedCommands() = runBlocking {
        val client =
            SlockAnnouncementsRemindersApiClient(
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

    private class RecordingAnnouncementsRemindersApi(
        private val activeBody: String =
            """{"announcements":[{"id":"announcement-1","title":"Release notes","pages":[{"body":"One"},{"body":"Two"}],"publishedAt":"2026-05-22T00:00:00.000Z"}]}""",
        private val dismissBody: String = """{"ok":true}""",
        private val remindersBody: String =
            """{"reminders":[{"reminderId":"reminder-1","title":"standup","fireAt":"2026-05-22T00:00:00.000Z","status":"scheduled"}]}"""
    ) : SlockAnnouncementsRemindersApi {
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
}
