package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SlockFilePreviewI18nProductionContractTest {
    @Test
    fun productionFilePreviewKeepsVisibleCopyInResources() {
        val source = source("build/raft/app/SlockFilePreview.kt")
        val visualFixtureStart = source.indexOf("private fun SlockFilePreviewEditorVisualContent()")
        val productionStart = source.indexOf("private fun SlockFilePreviewStandalonePageContent()")
        assertTrue(visualFixtureStart >= 0, "Missing file-preview visual fixture marker")
        assertTrue(productionStart > visualFixtureStart, "Missing production file-preview marker")

        val production = source.removeRange(visualFixtureStart, productionStart)
        val hardcodedVisibleCopy =
            Regex("\"[A-Z][A-Za-z0-9][^\"\\\\]*\"")
                .findAll(production)
                .map { it.value }
                .toList()

        assertTrue(
            hardcodedVisibleCopy.isEmpty(),
            "Production file preview must not add hardcoded English UI copy: $hardcodedVisibleCopy"
        )
        listOf(
            "file_preview_action_download",
            "file_preview_action_open_with",
            "file_preview_download_downloading",
            "file_preview_download_pause",
            "file_preview_media_playing",
            "file_preview_fallback_file",
            "file_preview_markdown_open_with_hint",
            "file_preview_truncated_open_with_hint"
        ).forEach { resourceName ->
            assertContains(production, "MR.strings.$resourceName")
        }
    }

    private fun source(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
