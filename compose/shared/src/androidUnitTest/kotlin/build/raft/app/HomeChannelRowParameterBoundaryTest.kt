package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeChannelRowParameterBoundaryTest {
    @Test
    fun groupedContentOwnsUnreadTitleStyleInputs() {
        val source = homeSource()
        val channelRow = source.functionWindow("private fun ChannelRow(", "private fun HomeSidebarSkeletonRow(")

        assertTrue(
            "hasUnread = content.badge != null" in channelRow,
            "ChannelRow title weight must read unread state from the grouped content parameter.",
        )
        assertTrue(
            "badgeTone = content.badgeTone" in channelRow,
            "ChannelRow title weight must read badge tone from the grouped content parameter.",
        )
        assertFalse(
            "hasUnread = badge != null" in channelRow || "badgeTone = badgeTone" in channelRow,
            "The removed flat ChannelRow parameters must not survive inside the grouped implementation.",
        )
    }

    private fun homeSource(): String {
        val commonMain =
            requireNotNull(
                listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                    .firstOrNull(File::isDirectory),
            ) { "Could not locate shared commonMain Kotlin sources" }
        return File(commonMain, "build/raft/app/SlockHomeComponents.kt").readText().replace("\r\n", "\n")
    }

    private fun String.functionWindow(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production function window: $startMarker" }
        return substring(start, end)
    }
}
