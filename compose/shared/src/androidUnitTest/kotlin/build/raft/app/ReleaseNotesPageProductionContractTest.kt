package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReleaseNotesPageProductionContractTest {
    @Test
    fun viewModelDefersPagerBoundScopeUntilAfterFrameworkAttachment() {
        val viewModel =
            source()
                .substringAfter("internal class ReleaseNotesViewModel(")
                .substringBefore("internal data class ReleaseNotesRequestIdentity")

        assertContains(viewModel, "private val storeLifecycle =")
        assertContains(viewModel, "ReleaseNotesStoreLifecycle {")
        assertContains(viewModel, "scope = viewModelScope")
        assertContains(viewModel, "get() = storeLifecycle.value")
        assertContains(viewModel, "get() = store.state")
        assertContains(viewModel, "storeLifecycle.clearIfInitialized()")
        assertContains(viewModel, "private val delegate = lazy(factory)")
        assertContains(viewModel, "if (delegate.isInitialized()) delegate.value.clear()")
        assertFalse(
            "private val store =\n        ReleaseNotesStore(" in viewModel,
            "ViewModel construction must not read pager-bound viewModelScope before attachment"
        )
    }

    @Test
    fun nativeBodyReservesSectionGapForLargeFontInlineCode() {
        assertContains(source(), "sectionHeadingTopPadding = SlockSpacing.Sm")

        val renderer = markdownSource()
        assertContains(renderer, "sectionHeadingTopPadding: Dp = 0.dp")
        assertContains(renderer, "remember(sectionHeadingTopPadding)")
        assertContains(renderer, "heading2 = { model ->")
        assertContains(renderer, "heading3 = { model ->")
        assertContains(renderer, "Modifier.padding(top = sectionHeadingTopPadding)")
    }

    @Test
    fun ordinaryMarkdownSurfacesKeepDefaultZeroSectionGap() {
        assertFalse(
            "sectionHeadingTopPadding" in commonMainSource("build/raft/app/ThreadPage.kt"),
            "Thread message Markdown must keep the renderer's default-zero section gap"
        )
        assertFalse(
            "sectionHeadingTopPadding" in commonMainSource("build/raft/app/SlockFilePreview.kt"),
            "File-preview Markdown must keep the renderer's default-zero section gap"
        )
    }

    private fun source(): String =
        File(commonMainRoot(), "build/raft/app/ReleaseNotesPage.kt")
            .readText()
            .replace("\r\n", "\n")

    private fun markdownSource(): String =
        commonMainSource("build/raft/app/markdown/SlockMarkdown.kt")

    private fun commonMainSource(relativePath: String): String =
        File(commonMainRoot(), relativePath).readText().replace("\r\n", "\n")

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")
}
