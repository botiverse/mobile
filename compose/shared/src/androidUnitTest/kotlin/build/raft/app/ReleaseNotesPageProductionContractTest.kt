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

    private fun source(): String =
        File(commonMainRoot(), "build/raft/app/ReleaseNotesPage.kt")
            .readText()
            .replace("\r\n", "\n")

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")
}
