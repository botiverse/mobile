package build.raft.designsystem.vcase

import dev.vcase.core.ImageCompare
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Migration gate (vcase design, phase 1): a gallery test is deleted only once its vcase
 * goldens equal the legacy RaftScreenshot goldens pixel for pixel. Runs after a
 * vcase record (recordRoborazziDesktop); skipped while the vcase goldens do not exist yet.
 */
class LegacyGoldenParityTest {
    private val legacyDir = File(System.getProperty("raft.screenshot.goldenDir") ?: "screenshots")
    private val vcaseDir = File(System.getProperty("vcase.goldens") ?: "screenshots/vcase", "desktop-skiko")

    /** vcase capture stem to the legacy golden name it replaces. */
    private val pairs =
        listOf("light", "dark").flatMap { theme ->
            listOf(
                "ds.empty-state.gallery@$theme,compact" to "empty_state_$theme",
                "ds.save-status.gallery@$theme,compact" to "save_status_$theme",
                "ds.composer-drop-overlay.default@$theme,compact" to "composer_drop_overlay_$theme",
            )
        }

    @Test
    fun vcaseGoldensEqualLegacyGoldens() {
        assumeTrue("record the vcase goldens first", pairs.all { File(vcaseDir, "${it.first}.png").isFile })
        val report =
            pairs.map { (stem, legacy) ->
                val r = ImageCompare.compare(File(legacyDir, "$legacy.png"), File(vcaseDir, "$stem.png"))
                Triple(stem, legacy, r)
            }
        report.forEach { (stem, legacy, r) -> println("$legacy.png vs $stem.png: ${r.describe()}") }
        assertTrue(report.all { it.third.matches }, report.filterNot { it.third.matches }.joinToString("\n") { "${it.second} -> ${it.first}: ${it.third.describe()}" })
    }
}
