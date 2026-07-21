package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentDownloadLifecycleProductionContractTest {
    @Test
    fun pageCancellationDetachesObserverWithoutCancellingTransfer() {
        val source = commonMainSource("build/raft/app/base/BridgeModule.kt")
        val open = source.functionBody("    suspend fun openAttachment(", "    suspend fun saveAttachment(")
        val save = source.functionBody("    suspend fun saveAttachment(", "    suspend fun attachmentDownloadControl(")

        listOf(open, save).forEach { function ->
            val cancellation =
                function.substring(
                    function.indexOf("continuation.invokeOnCancellation"),
                    function.lastIndexOf("        }")
                )
            assertTrue("removeCallback" in cancellation)
            assertFalse("AttachmentDownloadCoordinator.control" in cancellation)
            assertFalse("requestAttachmentDownloadControl" in cancellation)
        }
    }

    private fun String.functionBody(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        assertTrue(start >= 0 && end > start, "Missing attachment bridge function boundary.")
        return substring(start, end)
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }
}
