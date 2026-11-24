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

    @Test
    fun copyTextReturnsBareLinkWhenMessageIsNullOrBlank() {
        val link = "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=android"
        assertEquals(link, settingsFeedbackTicketCopyText(link, null))
        assertEquals(link, settingsFeedbackTicketCopyText(link, ""))
        assertEquals(link, settingsFeedbackTicketCopyText(link, "   \n\t  \r\n "))
    }

    @Test
    fun copyTextIncludesShortMessageAndCollapsesMultilineWhitespace() {
        val link = "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=android"
        assertEquals(
            "Stamp link jumped to system browser\n$link",
            settingsFeedbackTicketCopyText(link, "Stamp link jumped to system browser"),
        )
        assertEquals(
            "Line one Line two Line three\n$link",
            settingsFeedbackTicketCopyText(link, "  Line one\n\n  Line\t\ttwo\r\nLine   three  "),
        )
    }

    @Test
    fun copyTextTruncatesLongAsciiCjkAndEmojiWithoutSplittingSurrogatePairs() {
        val link = "https://app.raft.build/s/acme/settings/feedback/ticket/$ticketId?platform=ios"

        // Exact 80-char ASCII fits without ellipsis.
        val exact80 = "a".repeat(80)
        assertEquals("$exact80\n$link", settingsFeedbackTicketCopyText(link, exact80))

        // 81-char ASCII truncates to 79 chars + ellipsis.
        val over80 = "a".repeat(81)
        assertEquals("${"a".repeat(79)}…\n$link", settingsFeedbackTicketCopyText(link, over80))

        // Long CJK message truncates cleanly to 79 CJK chars + ellipsis.
        val longCjk = "反馈".repeat(45) // 90 chars
        assertEquals("${longCjk.take(79)}…\n$link", settingsFeedbackTicketCopyText(link, longCjk))

        // Surrogate-pair emoji right at the truncation boundary never splits a high/low surrogate.
        val emoji80 = "🔥".repeat(80)
        assertEquals("$emoji80\n$link", settingsFeedbackTicketCopyText(link, emoji80))

        val emoji81 = "🔥".repeat(81)
        val copiedEmoji81 = settingsFeedbackTicketCopyText(link, emoji81)
        val expectedEmoji81 = "${"🔥".repeat(79)}…\n$link"
        assertEquals(expectedEmoji81, copiedEmoji81)

        // Mixed ASCII + surrogate-pair emoji right at char 79-80 boundary.
        val boundaryEmoji = "a".repeat(78) + "🔥" + "bc" // 81 code points
        val copiedBoundary = settingsFeedbackTicketCopyText(link, boundaryEmoji)
        assertEquals("${"a".repeat(78)}🔥…\n$link", copiedBoundary)
        val excerptLine = copiedBoundary.substringBefore('\n')
        for (idx in excerptLine.indices) {
            if (excerptLine[idx].isHighSurrogate()) {
                assertEquals(true, idx + 1 < excerptLine.length && excerptLine[idx + 1].isLowSurrogate())
            }
            if (excerptLine[idx].isLowSurrogate()) {
                assertEquals(true, idx > 0 && excerptLine[idx - 1].isHighSurrogate())
            }
        }
    }
}
