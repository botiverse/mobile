package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadSingleTapProductionContractTest {
    @Test
    fun productionRowTapUsesAuthoritativeReplyMetadata() {
        val rowTapAction =
            threadPageSource().substringBetween(
                "// THREAD_SINGLE_TAP_ROW_ACTION_START",
                "// THREAD_SINGLE_TAP_ROW_ACTION_END"
            )

        assertTrue(rowTapAction.contains("val rowTapAction ="))
        assertTrue(rowTapAction.contains("threadMessageTapOpensThread("))
        assertTrue(
            rowTapAction.contains("replyCount = replyMetadata.replyCount"),
            "production row tap must gate on the message's authoritative reply count"
        )
        assertTrue(rowTapAction.contains("onOpenThread(message.id)"))
        assertTrue(rowTapAction.contains("else {\n            null\n        }"))
    }

    private fun threadPageSource(): String =
        listOf(
            File("src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
            File("shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
            File("compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
        ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("ThreadPage.kt source not found")

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) {
            "single-tap production markers missing"
        }
        return substring(startIndex, endIndex + end.length)
    }
}
