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
                "ds.hover.menu-item@$theme,compact" to "hover_menu_item_$theme",
                "ds.hover.list-rows@$theme,compact" to "hover_list_rows_$theme",
                "ds.hover.tabs@$theme,compact" to "hover_tabs_$theme",
                "ds.hover.app-rail-item@$theme,compact" to "hover_app_rail_item_$theme",
                "ds.hover.text-link@$theme,compact" to "hover_text_link_$theme",
                "ds.hover.controls@$theme,compact" to "hover_controls_$theme",
                "ds.avatar-list-row-states.gallery@$theme,compact" to "avatar_list_row_states_$theme",
                "ds.avatar-list-row-presence.gallery@$theme,compact" to "avatar_list_row_presence_$theme",
                "ds.member-list-row.gallery@$theme,compact" to "member_list_row_$theme",
                "ds.states.gallery@$theme,compact" to "states_$theme",
                "ds.conversation-kind-glyphs.gallery@$theme,compact" to "conversation_kind_glyphs_$theme",
                "ds.button.gallery@$theme,compact" to "button_$theme",
                "ds.icon-button.gallery@$theme,compact" to "icon_button_$theme",
                "ds.back-button.gallery@$theme,compact" to "back_button_$theme",
                "ds.icon-button-tooltip.gallery@$theme,compact" to "icon_button_tooltip_$theme",
                "ds.card.gallery@$theme,compact" to "card_$theme",
                "ds.badge-tags.gallery@$theme,compact" to "badge_tags_$theme",
                "ds.avatar.gallery@$theme,compact" to "avatar_$theme",
                "ds.avatar-empty.gallery@$theme,compact" to "avatar_empty_$theme",
                "ds.message-delegated-avatar.gallery@$theme,compact" to "message_delegated_avatar_$theme",
                "ds.inbox-item-leading.gallery@$theme,compact" to "inbox_item_leading_$theme",
                "ds.sticker-disc.gallery@$theme,compact" to "sticker_disc_$theme",
                "ds.status-dot.gallery@$theme,compact" to "status_dot_$theme",
                "ds.task-status-badge.gallery@$theme,compact" to "task_status_badge_$theme",
                "ds.message-reference-read-mark.gallery@$theme,compact" to "message_reference_read_mark_$theme",
                "ds.skeleton.gallery@$theme,compact" to "skeleton_$theme",
                "ds.steps.gallery@$theme,compact" to "steps_$theme",
                "ds.otter-indicator.gallery@$theme,compact" to "otter_indicator_$theme",
                "ds.progress-bar.gallery@$theme,compact" to "progress_bar_$theme",
                "ds.page-loading-bar.gallery@$theme,compact" to "page_loading_bar_$theme",
                "ds.slider.gallery@$theme,compact" to "slider_$theme",
                "ds.meter.gallery@$theme,compact" to "meter_$theme",
                "ds.read-only-composer.gallery@$theme,compact" to "read_only_composer_$theme",
                "ds.banner.gallery@$theme,compact" to "banner_$theme",
                "ds.list-row.gallery@$theme,compact" to "list_row_$theme",
                "ds.list-row-attached.gallery@$theme,compact" to "list_row_attached_$theme",
                "ds.list-row-risk.gallery@$theme,compact" to "list_row_risk_$theme",
                "ds.quiet-unread-dot.gallery@$theme,compact" to "quiet_unread_dot_$theme",
                "ds.sidebar.gallery@$theme,compact" to "sidebar_$theme",
                "ds.description-list.gallery@$theme,compact" to "description_list_$theme",
                "ds.section-code.gallery@$theme,compact" to "section_code_$theme",
                "ds.section-heading.gallery@$theme,compact" to "section_heading_$theme",
                "ds.settings-parts.gallery@$theme,compact" to "settings_parts_$theme",
                "ds.message-image.gallery@$theme,compact" to "message_image_gallery_$theme",
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
