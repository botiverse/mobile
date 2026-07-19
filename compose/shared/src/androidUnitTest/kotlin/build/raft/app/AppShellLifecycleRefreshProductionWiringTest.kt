package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellLifecycleRefreshProductionWiringTest {
    @Test
    fun longLivedLifecycleListenerReadsLatestAppearanceGeneration() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        assertTrue(
            "val latestVisibleAppearanceGeneration by rememberUpdatedState(visibleAppearanceGeneration)" in source,
            "The app lifecycle listener must not freeze the appearance generation from its initial composition."
        )

        val listenerStart = source.indexOf("DisposableEffect(\n        realtimeEvents,")
        val listenerEnd = source.indexOf("\n    val showAppShellBottomTabs", listenerStart)
        assertTrue(listenerStart >= 0 && listenerEnd > listenerStart, "Missing app-shell realtime lifecycle listener.")
        val listener = source.substring(listenerStart, listenerEnd)

        assertTrue("val appearanceGenerationAtDelivery = latestVisibleAppearanceGeneration" in listener)
        assertFalse(
            "val appearanceGenerationAtDelivery = visibleAppearanceGeneration" in listener,
            "A raw composition capture suppresses later foreground refreshes as duplicate appearances."
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
