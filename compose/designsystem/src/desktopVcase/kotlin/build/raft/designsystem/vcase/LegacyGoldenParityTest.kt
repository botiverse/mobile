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
                "ds.text-field.gallery@$theme,compact" to "text_field_$theme",
                "ds.text-field-leading-action.gallery@$theme,compact" to "text_field_leading_action_$theme",
                "ds.textarea.gallery@$theme,compact" to "textarea_$theme",
                "ds.field.gallery@$theme,compact" to "field_$theme",
                "ds.dialog-form.gallery@$theme,compact" to "dialog_form_$theme",
                "ds.dialog-footer-stacked.gallery@$theme,compact" to "dialog_footer_stacked_$theme",
                "ds.dialog-footer-note.gallery@$theme,compact" to "dialog_footer_note_$theme",
                "ds.checkbox.gallery@$theme,compact" to "checkbox_$theme",
                "ds.radio.gallery@$theme,compact" to "radio_$theme",
                "ds.switch.gallery@$theme,compact" to "switch_$theme",
                "ds.icon-toggle-button.gallery@$theme,compact" to "icon-toggle-button_$theme",
                "ds.tabs.gallery@$theme,compact" to "tabs_$theme",
                "ds.segmented-control.gallery@$theme,compact" to "segmented_control_$theme",
                "ds.text-link.gallery@$theme,compact" to "text_link_$theme",
                "ds.workspace-button.gallery@$theme,compact" to "workspace_button_$theme",
                "ds.select.gallery@$theme,compact" to "select_$theme",
                "ds.filter-chip.gallery@$theme,compact" to "filter_chip_$theme",
                "ds.menu.gallery@$theme,compact" to "menu_$theme",
                "ds.overlays.gallery@$theme,compact" to "overlays_$theme",
                "ds.dialog-sheet-centered.gallery@$theme,compact" to "dialog_sheet_centered_$theme",
                "ds.dialog-sheet.gallery@$theme,compact" to "dialog_sheet_$theme",
                "ds.sheet-close-row-actions.gallery@$theme,compact" to "sheet_close_row_actions_$theme",
                "ds.drawer.gallery@$theme,compact" to "drawer_$theme",
                "ds.drawer-canvas.gallery@$theme,compact" to "drawer_canvas_$theme",
                "ds.popover-surface.gallery@$theme,compact" to "popover_surface_$theme",
                "ds.dialog-header.gallery@$theme,compact" to "dialog_header_$theme",
                "ds.menu-popup.gallery@$theme,compact" to "menu_popup_$theme",
                "ds.select-popup-actions.gallery@$theme,compact" to "select_popup_actions_$theme",
                "ds.large-font.gallery@$theme,compact" to "large_font_$theme",
                "ds.separator.gallery@$theme,compact" to "separator_$theme",
                "ds.badge.gallery@$theme,compact" to "badge_$theme",
                "ds.spinner.gallery@$theme,compact" to "spinner_$theme",
                "ds.pane-grid.gallery@$theme,compact" to "pane_grid_$theme",
                "ds.pane-grid-folded.gallery@$theme,compact" to "pane_grid_folded_$theme",
                "ds.pane-grid-clipped.gallery@$theme,compact" to "pane_grid_clipped_$theme",
                "ds.pane-grid-tabs.gallery@$theme,compact" to "pane_grid_tabs_$theme",
                "ds.pane-grid-clipped-media.gallery@$theme,compact" to "pane_grid_clipped_media_$theme",
                "ds.pane-grid-heads.gallery@$theme,compact" to "pane_grid_heads_$theme",
                "ds.pane-grid-head-tabs.gallery@$theme,compact" to "pane_grid_head_tabs_$theme",
                "ds.pane-grid-expanded.gallery@$theme,compact" to "pane_grid_expanded_$theme",
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
