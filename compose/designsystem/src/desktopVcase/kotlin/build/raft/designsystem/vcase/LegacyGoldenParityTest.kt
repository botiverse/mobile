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
                "ds.text-field-container-addon.gallery@$theme,compact" to "text_field_container_addon_$theme",
                "ds.message-embed.gallery@$theme,compact" to "message_embed_$theme",
                "ds.task-compact-list.gallery@$theme,compact" to "task_compact_list_$theme",
                "ds.mode-bar.gallery@$theme,compact" to "mode_bar_$theme",
                "ds.selection-toolbar.gallery@$theme,compact" to "selection_toolbar_$theme",
                "ds.selection-toolbar.fit@$theme,compact" to "selection_toolbar_fit_$theme",
                "ds.selection-toolbar.dock@$theme,compact" to "selection_toolbar_dock_$theme",
                "ds.description-list.inline@$theme,compact" to "description_list_inline_$theme",
                "ds.timeline.gallery@$theme,compact" to "timeline_$theme",
                "ds.pull-down-panel.default@$theme,compact" to "pull_down_panel_$theme",
                "ds.message-attachment-card.gallery@$theme,compact" to "message_attachment_card_$theme",
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
