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
                // Wider than 1200dp (maxWidth).
                "ds.choice-list.gallery@$theme,compact" to "choice_list_$theme",
                "ds.choice-list.grouped@$theme,compact" to "choice_list_grouped_$theme",
                "ds.choice-list.multiple@$theme,compact" to "choice_list_multiple_$theme",
                "ds.choice-picker.gallery@$theme,compact" to "choice_picker_$theme",
                "ds.choice-picker.grouped@$theme,compact" to "choice_picker_grouped_$theme",
                "ds.combobox.trigger@$theme,compact" to "combobox_trigger_$theme",
                "ds.combobox.sheet@$theme,compact" to "combobox_sheet_$theme",
                "ds.combobox.list@$theme,compact" to "combobox_list_$theme",
                "ds.theme-decor.top@$theme,compact" to "theme_decor_top_$theme",
                "ds.theme-decor.top-actions@$theme,compact" to "theme_decor_top_actions_$theme",
                "ds.theme-decor.rail@$theme,compact" to "theme_decor_rail_$theme",
                // The wide viewport and the fine pointer.
                "ds.desktop-scale.gallery@$theme,wide,fine" to "desktop-scale_$theme",
                "ds.desktop-scale.gallery@$theme,wide,touch" to "wide-touch-scale_$theme",
                "ds.fit-toolbar.members@$theme,compact" to "fit_toolbar_members_$theme",
                "ds.fit-toolbar.members-fine@$theme,wide,fine" to "fit_toolbar_members_fine_$theme",
                "ds.fit-toolbar.filters@$theme,compact" to "fit_toolbar_filters_$theme",
                "ds.fit-toolbar.filters-end-view@$theme,compact" to "fit_toolbar_filters_end_view_$theme",
                "ds.pointer-density.controls@$theme,wide,fine" to "pointer-density-controls_$theme",
                "ds.pointer-density.heads@$theme,wide,fine" to "pointer-density-heads_$theme",
                "ds.pointer-density.rail@$theme,wide,fine" to "pointer-density-rail_$theme",
                "ds.sidebar-section.mouse@$theme,compact,fine" to "sidebar-section-mouse_$theme",
                // Settle frames.
                "ds.frame-insets.tooltip@$theme,compact" to "frame_inset_tooltip_$theme",
                "ds.toast-host.gallery@$theme,compact" to "toast_host_$theme",
                "ds.preview-card.open@$theme,compact" to "preview_card_open_$theme",
                "ds.preview-card.item@$theme,compact" to "preview_card_item_$theme",
                "ds.message-reaction.gallery@$theme,compact" to "message_reaction_$theme",
                "ds.message-reaction.tooltip@$theme,compact" to "message_reaction_tooltip_$theme",
                "ds.message-replies.gallery@$theme,compact" to "message_replies_$theme",
                "ds.app-rail.gallery@$theme,compact" to "app_rail_$theme",
                "ds.app-rail.tooltip@$theme,compact" to "app_rail_tooltip_$theme",
                "ds.app-rail.footer-toggle@$theme,compact" to "app_rail_footer_toggle_$theme",
                "ds.app-rail.compact@$theme,compact" to "app_rail_compact_$theme",
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
