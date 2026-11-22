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
                "ds.composer-attachment.gallery@$theme,compact" to "composer_attachment_$theme",
                "ds.message-action-card.states@$theme,compact" to "message_action_card_states_$theme",
                "ds.message-action-card.header-meta@$theme,compact" to "message_action_card_header_meta_$theme",
                "ds.message-action-card.content@$theme,compact" to "message_action_card_content_$theme",
                "ds.message-item-toolbar.gallery@$theme,compact" to "message_item_toolbar_$theme",
                "ds.text-selection-toolbar.gallery@$theme,compact" to "text_selection_toolbar_$theme",
                "ds.message-list-jump.gallery@$theme,compact" to "message_list_jump_$theme",
                "ds.message-quoted-preview.gallery@$theme,compact" to "message_quoted_preview_$theme",
                "ds.message-forwarded-bundle.gallery@$theme,compact" to "message_forwarded_bundle_$theme",
                "ds.search-panel.empty@$theme,compact" to "search_panel_empty_$theme",
                "ds.search-panel.recent@$theme,compact" to "search_panel_recent_$theme",
                "ds.search-panel.results@$theme,compact" to "search_panel_results_$theme",
                "ds.key-hints.gallery@$theme,compact" to "key_hints_$theme",
                "ds.side-action-column.all@$theme,compact" to "side_action_column_all_$theme",
                "ds.side-action-column.overflow@$theme,compact" to "side_action_column_overflow_$theme",
                "ds.side-action-column.tab-bar@$theme,compact" to "side_action_column_tab_bar_$theme",
                "ds.side-action-column.attention@$theme,compact" to "side_action_column_attention_$theme",
                "ds.side-action-column.tooltip@$theme,compact" to "side_action_column_tooltip_$theme",
                "ds.side-action-column.rtl@$theme,compact" to "side_action_column_rtl_$theme",
                "ds.sidebar-head-highlight.gallery@$theme,compact" to "sidebar-head-highlight_$theme",
                "ds.theme-frames.gallery@$theme,compact" to "theme_frames_$theme",
                "ds.scroll-area.scrollbar@$theme,compact" to "scroll_area_scrollbar_$theme",
                "ds.scroll-area.mask-fade-x@$theme,compact" to "scroll_area_mask_fade_x_$theme",
                "ds.mobile-nav-server.tab@$theme,compact" to "mobile_nav_server_tab_$theme",
                "ds.mobile-nav-server.popover@$theme,compact" to "mobile_nav_server_popover_$theme",
                "ds.mobile-nav-server.popover-reorder@$theme,compact" to "mobile_nav_server_popover_reorder_$theme",
                "ds.mobile-nav-server.popover-pages@$theme,compact" to "mobile_nav_server_popover_pages_$theme",
                "ds.mobile-nav-server.popover-short@$theme,compact" to "mobile_nav_server_popover_short_$theme",
                "ds.top-bar.gallery@$theme,compact" to "top_bar_$theme",
                "ds.top-bar.center-leading@$theme,compact" to "top_bar_center_leading_$theme",
                "ds.top-bar.window-title-bar@$theme,compact" to "top_bar_window_title_bar_$theme",
                "ds.top-bar.tooltip-below@$theme,compact" to "top_bar_tooltip_below_$theme",
                "ds.workspace-strip.tooltip@$theme,compact" to "workspace_strip_tooltip_$theme",
                "ds.search-trigger.gallery@$theme,compact" to "search_trigger_$theme",
                "ds.workspace-strip.item@$theme,compact" to "workspace_strip_item_$theme",
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
