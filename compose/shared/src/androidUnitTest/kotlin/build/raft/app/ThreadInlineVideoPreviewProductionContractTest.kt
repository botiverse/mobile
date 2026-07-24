package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThreadInlineVideoPreviewProductionContractTest {
    @Test
    fun inlineVideoAutoRequestsItsStreamingSourceWithoutASeparateOpenPreviewAction() {
        val source = source()
        val launch =
            source
                .substringAfter("LaunchedEffect(attachment.id, host != null)")
                .substringBefore("Column(")

        assertContains(launch, "if (previewState == null)")
        assertContains(launch, "host?.requestPreview?.invoke(attachment.id, false)")
        assertEquals(0, source.occurrences("SlockLucideIconName.AppWindow"))
        assertFalse("onOpenPreview" in source)
    }

    @Test
    fun fallbackVideoControlsKeepOnlyThePlaybackButton() {
        val source = source()
        val controls =
            source
                .substringAfter("!nativeController ->")
                .substringBefore("!firstFrameVisible ->")

        assertEquals(1, controls.occurrences("SlockBrutalIconButton("))
        assertContains(controls, "SlockLucideIconName.Pause else SlockLucideIconName.Play")
        assertFalse("SlockLucideIconName.AppWindow" in controls)
        assertFalse("onOpenPreview" in controls)
    }

    private fun source(): String =
        File(commonMainRoot(), "build/raft/app/ThreadInlineVideoPreview.kt")
            .readText()
            .replace("\r\n", "\n")

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun String.occurrences(needle: String): Int = split(needle).size - 1
}
