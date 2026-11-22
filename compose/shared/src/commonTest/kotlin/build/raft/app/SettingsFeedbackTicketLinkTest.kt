package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun aTicketOfAnotherAppLinksToItsOwnApp() {
        // slock #14722: the one list holds every app's tickets; a copied link names the ticket's app.
        // A web (or Raft Desktop) ticket needs no platform: the web reads its own app by default.
        assertEquals(
            "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId",
            settingsFeedbackTicketLink(null, "acme", ticketId, "web"),
        )
        assertEquals(
            "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=ohos",
            settingsFeedbackTicketLink(null, "acme", ticketId, "ohos"),
        )
    }
}
