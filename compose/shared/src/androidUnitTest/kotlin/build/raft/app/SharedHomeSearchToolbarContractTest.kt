package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeSearchToolbarContractTest {
    @Test
    fun homeSearchToolbarUsesLocalizedPlaceholderWithoutSearchIcon() {
        val pageSource = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val toolbar =
            pageSource.substringAfter("private fun SharedHomeSearchToolbar(")
                .substringBefore("private fun SharedHomeSearchControlsRow(")

        assertTrue(
            "placeholder = stringResource(MR.strings.search_placeholder)" in pageSource,
            "Home Search must resolve the input placeholder from MR resources"
        )
        assertFalse(
            "SlockLucideIconName.Search" in toolbar,
            "Home Search toolbar must not render a separate leading search icon"
        )
    }

    @Test
    fun sharedHomeSearchInputRequiresCallersToProvidePlaceholder() {
        val inputSource = commonMainSource("build/raft/app/components/SharedHomeSearchInput.kt")

        assertTrue("placeholder: String," in inputSource)
        assertFalse(
            "Search channels, DMs, messages" in inputSource,
            "Shared search input must not carry hardcoded product copy as a default"
        )
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }
}
