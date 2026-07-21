package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeSavedActionContractTest {
    @Test
    fun unsaveActionUsesAStableItemKeyAndLatestCallback() {
        val pageSource = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val resultCard =
            pageSource.substringAfter("private fun SharedHomeSavedResultCard(")
                .substringBefore("private fun SharedHomeSavedUnsaveButton(")
        val unsaveButton =
            pageSource.substringAfter("private fun SharedHomeSavedUnsaveButton(")
                .substringBefore("private fun SharedHomeSavedSenderMeta(")

        assertTrue("interactionIdentity = \"saved-unsave:${'$'}{item.key}\"" in resultCard)
        assertTrue("val currentOnClick by rememberUpdatedState(onClick)" in unsaveButton)
        assertTrue(".slockButtonAccessibility(enabled = true) { currentOnClick() }" in unsaveButton)
        assertTrue(".pointerInput(interactionIdentity)" in unsaveButton)
        assertTrue("detectTapGestures(onTap = { currentOnClick() })" in unsaveButton)
        assertFalse(".clickable(onClick = onClick)" in unsaveButton)
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }
}
