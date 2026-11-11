package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #119: a feedback ticket's copied link is the web's Settings › Feedback ticket route (slock
 * `feedbackTicketPath`) on the Developer environment's web URL, plus the platform whose Hands app
 * (`raft-android`, `raft-ios`) holds the ticket's diagnostics.
 */
class SettingsFeedbackTicketLinkTest {
    private val ticketId = "11111111-2222-4333-8444-555555555555"

    @Test
    fun theLinkIsTheWebTicketRouteWithThePlatform() {
        assertEquals(
            "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=android",
            settingsFeedbackTicketLink(null, "acme", ticketId, "android"),
        )
        assertEquals(
            "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=ios",
            settingsFeedbackTicketLink("", "acme", ticketId, "ios"),
        )
    }

    @Test
    fun theLinkIsOnTheDeveloperEnvironmentsWebUrl() {
        assertEquals(
            "https://staging.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=ios",
            settingsFeedbackTicketLink(" https://staging.raft.build/ ", "acme", ticketId, "ios"),
        )
        assertEquals(
            "http://localhost:5173/s/acme/settings/feedback/ticket/$ticketId?platform=android",
            settingsFeedbackTicketLink("http://localhost:5173", "acme", ticketId, "android"),
        )
    }

    @Test
    fun theTicketIdIsEncodedAsTheWebEncodesIt() {
        assertEquals(
            "https://app.raft.build/s/acme/settings/feedback/ticket/a%2Fb%3Fc%23d%20%C3%A9-_.!~*'()?platform=android",
            settingsFeedbackTicketLink(null, "acme", "a/b?c#d é-_.!~*'()", "android"),
        )
    }

    @Test
    fun noLinkWithoutAServerATicketOrAHandsPlatform() {
        assertNull(settingsFeedbackTicketLink(null, null, ticketId, "android"))
        assertNull(settingsFeedbackTicketLink(null, " ", ticketId, "android"))
        assertNull(settingsFeedbackTicketLink(null, "ac/me", ticketId, "android"))
        assertNull(settingsFeedbackTicketLink(null, "acme", " ", "ios"))
        assertNull(settingsFeedbackTicketLink(null, "acme", ticketId, "web"))
        assertNull(settingsFeedbackTicketLink(null, "acme", ticketId, ""))
    }

    @Test
    fun onlyAndroidAndIosNameAHandsApp() {
        assertEquals("android", settingsFeedbackTicketLinkPlatform("android"))
        assertEquals("ios", settingsFeedbackTicketLinkPlatform("ios"))
        assertNull(settingsFeedbackTicketLinkPlatform("web"))
    }
}
