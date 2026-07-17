package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadAvatarGestureProductionCallsiteTest {
    @Test
    fun virtualizedMessageAvatarRestartsGestureDetectorForCurrentAuthorIdentity() {
        val source = threadPageSource()
        val card = source.functionWindow("private fun ThreadMessageCard(", "internal fun threadMessageFocusHighlightColor(")
        val avatar = source.functionWindow("private fun ThreadMessageAvatar(", "private fun ThreadMessageStaticAvatar(")

        assertTrue(
            "message.threadAuthorGestureIdentity()" in card,
            "ThreadMessageCard must derive the gesture key from the current message and author.",
        )
        assertEquals(
            2,
            card.occurrences("interactionIdentity = authorGestureIdentity"),
            "Both left- and right-aligned avatar slots must receive the current row identity.",
        )
        assertTrue(
            ".pointerInput(interactionIdentity)" in avatar,
            "The production avatar detector must restart when a virtualized row changes author identity.",
        )
        assertFalse(
            ".pointerInput(Unit)" in avatar,
            "A Unit-keyed detector can retain a previous virtualized row's profile target.",
        )
    }

    private fun threadPageSource(): String {
        val commonMain = requireNotNull(
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory),
        ) { "Could not locate shared commonMain Kotlin sources" }
        return File(commonMain, "build/raft/app/ThreadPage.kt").readText().replace("\r\n", "\n")
    }

    private fun String.functionWindow(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production function window: $startMarker" }
        return substring(start, end)
    }

    private fun String.occurrences(token: String): Int = windowed(token.length).count { it == token }
}
