package build.raft.app.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import build.raft.app.shared.SlockKuiklyActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import kotlin.math.roundToInt

class KmpVisualScreenshotCaptureTest {
    // Per-case timing to logcat + a hard per-case wall-clock backstop (task
    // #362). AndroidJUnitRunner buffers its one-dot-per-test stream, so a hung
    // shard prints nothing until the 45m job timeout kills it — these rules
    // make per-item duration observable (CASE_START/CASE_END) and stop any
    // single hung/animating case from burning the whole shard's budget. The
    // bounded idle waits below are the primary defense; the Timeout is the
    // belt-and-suspenders cap that turns a residual hang into a per-case
    // failure so the runner advances to the next case.
    private val caseTimingWatcher: TestWatcher =
        object : TestWatcher() {
            private var startUptimeMs = 0L

            override fun starting(description: Description) {
                startUptimeMs = SystemClock.uptimeMillis()
                Log.i(VISUAL_LOG_TAG, "CASE_START ${description.methodName}")
                recordTiming("START ${description.methodName}")
            }

            override fun succeeded(description: Description) {
                val durationMs = SystemClock.uptimeMillis() - startUptimeMs
                Log.i(VISUAL_LOG_TAG, "CASE_END ${description.methodName} status=OK durationMs=$durationMs")
                recordTiming("OK ${description.methodName} durationMs=$durationMs")
            }

            override fun failed(e: Throwable, description: Description) {
                val durationMs = SystemClock.uptimeMillis() - startUptimeMs
                Log.w(
                    VISUAL_LOG_TAG,
                    "CASE_END ${description.methodName} status=FAIL durationMs=$durationMs error=${e.javaClass.simpleName}: ${e.message}"
                )
                recordTiming("FAIL ${description.methodName} durationMs=$durationMs error=${e.javaClass.simpleName}")
            }
        }

    private val perCaseTimeout: Timeout =
        Timeout.builder()
            .withTimeout(PER_CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build()

    // Watcher outer, timeout inner: a timeout surfaces as a normal test
    // failure that caseTimingWatcher.failed() records with its duration.
    @get:Rule
    val visualCaptureRules: RuleChain =
        RuleChain.outerRule(caseTimingWatcher).around(perCaseTimeout)


    @Test
    fun captureTokenPalette() {
        captureKmpComponentCase(
            caseId = "components.tokens.palette",
            pageName = "compose_tokens_palette_visual",
            crop = TOKEN_PALETTE_CROP
        )
    }

    @Test
    fun captureHomeServerSwitcherOpen() {
        captureKmpHomeCase(
            caseId = "components.home.server-switcher.open",
            pageData = homeServerSwitcherPageData(),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureNavigationTabbarStates() {
        captureKmpComponentCase(
            caseId = "components.navigation.tabbar.states",
            pageName = "compose_bottom_tabbar_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureUiSegmentedControlStates() {
        captureKmpComponentCase(
            caseId = "components.ui.segmented-control.states",
            pageName = "compose_ui_segmented_control_visual",
            crop = UI_SEGMENTED_CONTROL_CROP
        )
    }

    @Test
    fun captureUiButtonStates() {
        captureKmpComponentCase(
            caseId = "components.ui.button.states",
            pageName = "compose_ui_button_visual",
            crop = UI_BUTTON_CROP
        )
    }

    @Test
    fun captureUiFormFieldStates() {
        captureKmpComponentCase(
            caseId = "components.ui.form-field.states",
            pageName = "compose_ui_form_field_visual",
            crop = UI_FORM_FIELD_CROP
        )
    }

    @Test
    fun captureUiTextareaStates() {
        captureKmpComponentCase(
            caseId = "components.ui.textarea.states",
            pageName = "compose_ui_textarea_visual",
            crop = UI_TEXTAREA_CROP
        )
    }

    @Test
    fun captureUiCheckboxStates() {
        captureKmpComponentCase(
            caseId = "components.ui.checkbox.states",
            pageName = "compose_ui_checkbox_visual",
            crop = UI_CHECKBOX_CROP
        )
    }

    @Test
    fun captureUiCheckMarkerStates() {
        captureKmpComponentCase(
            caseId = "components.ui.check-marker.states",
            pageName = "compose_ui_check_marker_visual",
            crop = UI_CHECK_MARKER_CROP
        )
    }

    @Test
    fun captureUiAttentionDotStates() {
        captureKmpComponentCase(
            caseId = "components.ui.attention-dot.states",
            pageName = "compose_ui_attention_dot_visual",
            crop = UI_ATTENTION_DOT_CROP
        )
    }

    @Test
    fun captureUiStatusDotStates() {
        captureKmpComponentCase(
            caseId = "components.ui.status-dot.states",
            pageName = "compose_ui_status_dot_visual",
            crop = UI_STATUS_DOT_CROP
        )
    }

    @Test
    fun captureUiProgressBarStates() {
        captureKmpComponentCase(
            caseId = "components.ui.progress-bar.states",
            pageName = "compose_ui_progress_bar_visual",
            crop = UI_PROGRESS_BAR_CROP
        )
    }

    @Test
    fun captureUiSkeletonStates() {
        captureKmpComponentCase(
            caseId = "components.ui.skeleton.states",
            pageName = "compose_ui_skeleton_visual",
            crop = UI_SKELETON_CROP
        )
    }

    @Test
    fun captureUiSpinnerStates() {
        captureKmpComponentCase(
            caseId = "components.ui.spinner.states",
            pageName = "compose_ui_spinner_visual",
            crop = UI_SPINNER_CROP
        )
    }

    @Test
    fun captureUiSlugInputStates() {
        captureKmpComponentCase(
            caseId = "components.ui.slug-input.states",
            pageName = "compose_ui_slug_input_visual",
            crop = UI_SLUG_INPUT_CROP
        )
    }

    @Test
    fun captureUiSectionEyebrowStates() {
        captureKmpComponentCase(
            caseId = "components.ui.section-eyebrow.states",
            pageName = "compose_ui_section_eyebrow_visual",
            crop = UI_SECTION_EYEBROW_CROP
        )
    }

    @Test
    fun captureUiSelectionPopoverStates() {
        captureKmpComponentCase(
            caseId = "components.ui.selection-popover.states",
            pageName = "compose_ui_selection_popover_visual",
            crop = UI_SELECTION_POPOVER_CROP
        )
    }

    @Test
    fun captureUiMenuItemStates() {
        captureKmpComponentCase(
            caseId = "components.ui.menu-item.states",
            pageName = "compose_ui_menu_item_visual",
            crop = UI_MENU_ITEM_CROP
        )
    }

    @Test
    fun captureUiSelectStates() {
        captureKmpComponentCase(
            caseId = "components.ui.select.states",
            pageName = "compose_ui_select_visual",
            crop = UI_SELECT_CROP
        )
    }

    @Test
    fun captureUiBadgeStates() {
        captureKmpComponentCase(
            caseId = "components.ui.badge.states",
            pageName = "compose_ui_badge_visual",
            crop = UI_BADGE_CROP
        )
    }

    @Test
    fun captureUiSectionHeaderStates() {
        captureKmpComponentCase(
            caseId = "components.ui.section-header.states",
            pageName = "compose_ui_section_header_visual",
            crop = UI_SECTION_HEADER_CROP
        )
    }

    @Test
    fun captureUiSurfaceListItemStates() {
        captureKmpComponentCase(
            caseId = "components.ui.surface-list-item.states",
            pageName = "compose_ui_surface_list_item_visual",
            crop = UI_SURFACE_LIST_ITEM_CROP
        )
    }

    @Test
    fun captureUiAvatarListRowStates() {
        captureKmpComponentCase(
            caseId = "components.ui.avatar-list-row.states",
            pageName = "compose_ui_avatar_list_row_visual",
            crop = UI_AVATAR_LIST_ROW_CROP
        )
    }

    @Test
    fun captureRaftUiBannerStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.banner.states",
            pageName = "compose_raft_ui_banner_visual",
            crop = RAFT_UI_BANNER_CROP
        )
    }

    @Test
    fun captureRaftUiEmptyStateStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.empty-state.states",
            pageName = "compose_raft_ui_empty_state_visual",
            crop = RAFT_UI_EMPTY_STATE_CROP
        )
    }

    @Test
    fun captureRaftUiTypographyStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.typography.states",
            pageName = "compose_raft_ui_typography_visual",
            crop = RAFT_UI_TYPOGRAPHY_CROP
        )
    }

    @Test
    fun captureRaftUiFieldInputStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.field-input.states",
            pageName = "compose_raft_ui_field_input_visual",
            crop = RAFT_UI_FIELD_INPUT_CROP
        )
    }

    @Test
    fun captureRaftUiAvatarStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.avatar.states",
            pageName = "compose_raft_ui_avatar_visual",
            crop = RAFT_UI_AVATAR_CROP
        )
    }

    @Test
    fun captureRaftUiButtonBadgeStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.button-badge.states",
            pageName = "compose_raft_ui_button_badge_visual",
            crop = RAFT_UI_BUTTON_BADGE_CROP
        )
    }

    @Test
    fun captureRaftUiCheckboxStatusStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.checkbox-status.states",
            pageName = "compose_raft_ui_checkbox_status_visual",
            crop = RAFT_UI_CHECKBOX_STATUS_CROP
        )
    }

    @Test
    fun captureRaftUiTextareaStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.textarea.states",
            pageName = "compose_raft_ui_textarea_visual",
            crop = RAFT_UI_TEXTAREA_CROP
        )
    }

    @Test
    fun captureRaftUiTabsStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.tabs.states",
            pageName = "compose_raft_ui_tabs_visual",
            crop = RAFT_UI_TABS_CROP
        )
    }

    @Test
    fun captureRaftUiSegmentedControlStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.segmented-control.states",
            pageName = "compose_raft_ui_segmented_control_visual",
            crop = RAFT_UI_SEGMENTED_CONTROL_CROP
        )
    }

    @Test
    fun captureRaftUiLabelRadioStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.label-radio.states",
            pageName = "compose_raft_ui_label_radio_visual",
            crop = RAFT_UI_LABEL_RADIO_CROP
        )
    }

    @Test
    fun captureRaftUiSelectStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.select.states",
            pageName = "compose_raft_ui_select_visual",
            crop = RAFT_UI_SELECT_CROP
        )
    }

    @Test
    fun captureRaftUiMenuSurfacesStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.menu-surfaces.states",
            pageName = "compose_raft_ui_menu_surfaces_visual",
            crop = RAFT_UI_MENU_SURFACES_CROP
        )
    }

    @Test
    fun captureRaftUiPopoverSurfaceStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.popover-surface.states",
            pageName = "compose_raft_ui_popover_surface_visual",
            crop = RAFT_UI_POPOVER_SURFACE_CROP
        )
    }

    @Test
    fun captureRaftUiComboboxSurfaceStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.combobox-surface.states",
            pageName = "compose_raft_ui_combobox_surface_visual",
            crop = RAFT_UI_COMBOBOX_SURFACE_CROP
        )
    }

    @Test
    fun captureRaftUiNotificationCenterStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.notification-center.states",
            pageName = "compose_raft_ui_notification_center_visual",
            crop = RAFT_UI_NOTIFICATION_CENTER_CROP
        )
    }

    @Test
    fun captureRaftUiInteractionStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.interaction-states",
            pageName = "compose_raft_ui_interaction_states_visual",
            crop = RAFT_UI_INTERACTION_STATES_CROP
        )
    }

    @Test
    fun captureRaftUiControlledOpenOverlays() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.controlled-open-overlays",
            pageName = "compose_raft_ui_controlled_open_overlays_visual",
            crop = RAFT_UI_CONTROLLED_OPEN_OVERLAYS_CROP
        )
    }

    @Test
    fun captureRaftUiWipToggleGroupStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.wip-toggle-group.states",
            pageName = "compose_raft_ui_wip_toggle_group_visual",
            crop = RAFT_UI_WIP_TOGGLE_GROUP_CROP
        )
    }

    @Test
    fun captureRaftUiWipListPanelsStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.wip-list-panels.states",
            pageName = "compose_raft_ui_wip_list_panels_visual",
            crop = RAFT_UI_WIP_LIST_PANELS_CROP
        )
    }

    @Test
    fun captureRaftUiWipMessageCardsStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.wip-message-cards.states",
            pageName = "compose_raft_ui_wip_message_cards_visual",
            crop = RAFT_UI_WIP_MESSAGE_CARDS_CROP
        )
    }

    @Test
    fun captureRaftUiWipOverlayShellsStates() {
        captureKmpComponentCase(
            caseId = "library.raft-ui.wip-overlay-shells.states",
            pageName = "compose_raft_ui_wip_overlay_shells_visual",
            crop = RAFT_UI_WIP_OVERLAY_SHELLS_CROP
        )
    }

    @Test
    fun captureThreadComposerEmpty() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.empty",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-empty")
                    .put("visualFixture", "empty"),
            crop = THREAD_COMPOSER_COMPONENT_CROP
        )
    }

    @Test
    fun captureThreadComposerStates() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.states",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer")
                    .put("visualFixture", "states"),
            crop = THREAD_COMPOSER_COMPONENT_CROP
        )
    }

    @Test
    fun captureThreadComposerAsTaskSelected() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.as-task-selected",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-as-task")
                    .put("visualFixture", "as_task_selected"),
            crop = THREAD_COMPOSER_COMPONENT_CROP
        )
    }

    @Test
    fun captureThreadComposerMemberSuggestions() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.member-suggestions",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-member-suggestions")
                    .put("visualFixture", "member_suggestions"),
            crop = THREAD_COMPOSER_SUGGESTIONS_CROP
        )
    }

    @Test
    fun captureThreadComposerChannelSuggestions() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.channel-suggestions",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-channel-suggestions")
                    .put("visualFixture", "channel_suggestions"),
            crop = THREAD_COMPOSER_SUGGESTIONS_CROP
        )
    }

    @Test
    fun captureThreadComposerImagePreview() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.image-preview",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-image-preview")
                    .put("visualFixture", "image_preview"),
            crop = THREAD_COMPOSER_ATTACHMENTS_CROP,
            settleMs = 1_500
        )
    }

    @Test
    fun captureThreadComposerFilePreviews() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.file-previews",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-file-previews")
                    .put("visualFixture", "file_previews"),
            crop = THREAD_COMPOSER_ATTACHMENTS_CROP,
            settleMs = 1_500
        )
    }

    @Test
    fun captureThreadFilesList() {
        captureKmpComponentCase(
            caseId = "components.thread.files.list",
            pageName = "compose_thread_files_visual",
            crop = THREAD_FILES_LIST_CROP,
            settleMs = 1_500
        )
    }

    @Test
    fun captureThreadMessageRow() {
        captureKmpComponentCase(
            caseId = "components.thread.message.row",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row")
                    .put("visualFixture", "default"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageRowDeletedHuman() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.deleted-human",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-deleted-human")
                    .put("visualFixture", "deleted_human"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageMenuDefault() {
        captureKmpComponentCase(
            caseId = "components.thread.message-menu.default",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-menu-default")
                    .put("visualFixture", "default")
                    .put("menuVariant", "default"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageMenuTask() {
        captureKmpComponentCase(
            caseId = "components.thread.message-menu.task",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-menu-task")
                    .put("visualFixture", "default")
                    .put("menuVariant", "task"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageShareSelection() {
        captureKmpComponentCase(
            caseId = "components.thread.message-share.selection",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-share-selection")
                    .put("visualFixture", "share_selection"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageSharePreview() {
        captureKmpComponentCase(
            caseId = "components.thread.message-share.preview",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-share-preview")
                    .put("visualFixture", "share_preview"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadMessageRowRichContent() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.rich-content",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-rich")
                    .put("visualFixture", "rich_content"),
            crop = THREAD_MESSAGE_ROW_RICH_CONTENT_CROP
        )
    }

    @Test
    fun captureThreadMessageRowLongInlineCode() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.long-inline-code",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-long-inline-code")
                    .put("visualFixture", "long_inline_code"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadHeaderStates() {
        captureKmpComponentCase(
            caseId = "components.thread.header.states",
            pageName = "compose_thread_header_visual",
            crop = THREAD_HEADER_COMPONENT_CROP
        )
    }

    @Test
    fun captureThreadReactionAtlas() {
        captureKmpComponentCase(
            caseId = "components.thread.reaction-atlas",
            pageName = "compose_thread_reaction_atlas_visual",
            crop = THREAD_REACTION_ATLAS_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureHomeTitlebarStates() {
        captureKmpComponentCase(
            caseId = "components.home.titlebar.states",
            pageName = "compose_home_titlebar_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureHomeNotificationCenterStates() {
        captureKmpComponentCase(
            caseId = "components.home.notification-center.states",
            pageName = "compose_home_notification_center_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureHomeSearchResults() {
        captureKmpComponentCase(
            caseId = "components.home.search.results",
            pageName = "compose_home_search_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeSearchChannelDropdown() {
        captureKmpComponentCase(
            caseId = "components.home.search.channel-dropdown",
            pageName = "compose_home_search_channel_dropdown_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeSavedResults() {
        captureKmpComponentCase(
            caseId = "components.home.saved.results",
            pageName = "compose_home_saved_visual",
            crop = HOME_SAVED_RESULTS_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeActivityResults() {
        captureKmpComponentCase(
            caseId = "components.home.activity.results",
            pageName = "compose_home_activity_visual",
            crop = HOME_ACTIVITY_RESULTS_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeCreateChannelDialog() {
        captureKmpComponentCase(
            caseId = "components.home.create-channel.dialog",
            pageName = "compose_home_create_channel_dialog_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureTasksPanelStates() {
        captureKmpComponentCase(
            caseId = "components.tasks.panel.states",
            pageName = "compose_tasks_panel_visual",
            crop = TASKS_PANEL_STATES_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureTasksStatusMenu() {
        captureKmpComponentCase(
            caseId = "components.tasks.status-menu",
            pageName = "compose_tasks_status_menu_visual",
            crop = TASKS_STATUS_MENU_CROP
        )
    }

    @Test
    fun captureMembersCreateAgentCodexDialog() {
        captureKmpComponentCase(
            caseId = "components.members.create-agent.dialog",
            pageName = "compose_create_agent_dialog_visual",
            pageData = createAgentDialogPageData(runtime = "codex", errorMessage = "Failed to create agent"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersCreateAgentClaudeDialog() {
        captureKmpComponentCase(
            caseId = "components.members.create-agent.claude-dialog",
            pageName = "compose_create_agent_dialog_visual",
            pageData = createAgentDialogPageData(runtime = "claude"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersCreateAgentClaudeCustomProviderDialog() {
        captureKmpComponentCase(
            caseId = "components.members.create-agent.claude-custom-provider-dialog",
            pageName = "compose_create_agent_dialog_visual",
            pageData =
                createAgentDialogPageData(
                    runtime = "claude",
                    providerMode = "custom",
                    providerApiUrl = "https://gateway.example.com",
                    providerApiKey = "visual-test-key",
                    advancedOpen = true,
                    command = "claude"
                ),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersAgentDetailProfile() {
        captureKmpComponentCase(
            caseId = "components.members.agent-detail.profile",
            pageName = "compose_members_agent_detail_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersAgentDetailProfileScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile",
            tab = "profile"
        )
    }

    @Test
    fun captureMembersAgentDetailChatScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.chat",
            tab = "chat"
        )
    }

    @Test
    fun captureMembersAgentDetailRemindersScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.reminders",
            tab = "reminders"
        )
    }

    @Test
    fun captureMembersAgentDetailWorkspaceScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.workspace",
            tab = "workspace"
        )
    }

    @Test
    fun captureMembersAgentDetailAppsScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.apps",
            tab = "integrations"
        )
    }

    @Test
    fun captureMembersAgentDetailActivityScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.activity",
            tab = "activity"
        )
    }

    @Test
    fun captureMembersHumanDetailProfileScreen() {
        captureKmpComponentCase(
            caseId = "screens.members.human.profile",
            pageName = "compose_member_detail",
            pageData = memberDetailVisualPageData(
                instanceId = "visual-members-human-profile",
                memberId = "visual-human-1",
                memberKind = "human"
            ),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    private fun captureMembersAgentDetailScreen(
        caseId: String,
        tab: String
    ) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_member_detail",
            pageData = memberDetailVisualPageData(
                instanceId = "visual-members-agent-$tab",
                memberId = "agent-product-ux",
                memberKind = "agent",
                initialAgentTab = tab
            ),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersAgentLifecycleActions() {
        captureKmpComponentCase(
            caseId = "components.members.agent-lifecycle-actions",
            pageName = "compose_members_agent_detail_visual",
            pageData = JSONObject().put("visualMode", "lifecycle_actions"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureMembersAvatarManagement() {
        captureKmpComponentCase(
            caseId = "components.members.avatar-management",
            pageName = "compose_members_agent_detail_visual",
            pageData = JSONObject().put("visualMode", "avatar_management"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureChannelMembersPanel() {
        captureKmpComponentCase(
            caseId = "components.channel.members.panel",
            pageName = "compose_channel_members_panel_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureChannelSettingsPanel() {
        captureKmpComponentCase(
            caseId = "components.channel.settings.panel",
            pageName = "compose_channel_settings_panel_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureChannelAddMemberPanel() {
        captureKmpComponentCase(
            caseId = "components.channel.members.add-panel",
            pageName = "compose_channel_add_member_panel_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureSettingsRowStates() {
        captureKmpComponentCase(
            caseId = "settings.row.states",
            pageName = "compose_settings_row_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureSettingsPageStates() {
        captureKmpComponentCase(
            caseId = "components.settings.page.states",
            pageName = "compose_settings_page_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureSettingsAccountPage() {
        captureKmpSettingsPageCase("components.settings.account.page", "account")
    }

    @Test
    fun captureSettingsAccountProviderLinked() {
        captureKmpSettingsPageCase("components.settings.account.provider-linked", "account-provider-linked")
    }

    @Test
    fun captureSettingsAccountPasswordExpanded() {
        captureKmpSettingsPageCase("components.settings.account.password-expanded", "account-password-expanded")
    }

    @Test
    fun captureSettingsAccountErrorState() {
        captureKmpSettingsPageCase("components.settings.account.error-state", "account-error")
    }

    @Test
    fun captureSettingsServerProfile() {
        captureKmpSettingsPageCase("components.settings.server.profile", "server-profile")
    }

    @Test
    fun captureSettingsServerDangerModal() {
        captureKmpSettingsPageCase("screens.settings.server-danger-modal", "server-danger-modal")
    }

    @Test
    fun captureSettingsComputersScreen() {
        captureKmpComponentCase(
            caseId = "screens.settings.computers",
            pageName = "compose_settings_computers",
            pageData = JSONObject().put("visualFixture", "computers"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureSettingsComputerDetailScreen() {
        captureKmpComponentCase(
            caseId = "screens.settings.computer-detail",
            pageName = "compose_settings_computer_detail",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-settings-computer-detail")
                    .put("visualFixture", "computer_detail"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureSettingsAdministrationAdmins() {
        captureKmpSettingsPageCase("components.settings.administration.admins", "administration-admins")
    }

    @Test
    fun captureSettingsAdministrationInvites() {
        captureKmpSettingsPageCase("components.settings.administration.invites", "administration-invites")
    }

    @Test
    fun captureSettingsAdministrationJoinLinks() {
        captureKmpSettingsPageCase("components.settings.administration.join-links", "administration-join-links")
    }

    @Test
    fun captureSettingsAdministrationPreJoinAgreement() {
        captureKmpSettingsPageCase("components.settings.administration.pre-join-agreement", "administration-pre-join-agreement")
    }

    @Test
    fun captureSettingsAdministrationOnboarding() {
        captureKmpSettingsPageCase("components.settings.administration.onboarding", "administration-onboarding")
    }

    @Test
    fun captureSettingsAdministrationMemberPermissions() {
        captureKmpSettingsPageCase("components.settings.administration.member-permissions", "administration-member-permissions")
    }

    @Test
    fun captureSettingsAdministrationTranslation() {
        captureKmpSettingsPageCase("components.settings.administration.translation", "administration-translation")
    }

    @Test
    fun captureSettingsBillingPage() {
        captureKmpSettingsPageCase("components.settings.billing.page", "billing")
    }

    @Test
    fun captureSettingsLanguageRegionPage() {
        captureKmpSettingsPageCase("components.settings.language-region.page", "language-region")
    }

    @Test
    fun captureSettingsAppearancePage() {
        captureKmpSettingsPageCase("components.settings.appearance.page", "appearance")
    }

    @Test
    fun captureSettingsNotificationsPage() {
        captureKmpSettingsPageCase("components.settings.notifications.page", "notifications")
    }

    @Test
    fun captureSettingsConnectedAppsPage() {
        captureKmpSettingsPageCase("components.settings.connected-apps.page", "connected-apps")
    }

    @Test
    fun captureAuthLoginInputsEmpty() {
        captureKmpComponentCase(
            caseId = "auth.login.inputs.empty",
            pageName = "compose_auth_login_inputs",
            pageData = JSONObject().put("visualFixture", "empty"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureAuthLoginInputsFilled() {
        captureKmpComponentCase(
            caseId = "auth.login.inputs.filled",
            pageName = "compose_auth_login_inputs",
            pageData = JSONObject().put("visualFixture", "filled"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureAuthLoginInputsInvalid() {
        captureKmpComponentCase(
            caseId = "auth.login.inputs.invalid",
            pageName = "compose_auth_login_inputs",
            pageData = JSONObject().put("visualFixture", "invalid"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureAuthLoginScreen() {
        captureKmpComponentCase(
            caseId = "screens.auth.login",
            pageName = SlockKuiklyActivity.PAGE_LOGIN,
            crop = FULL_VIEWPORT_CROP,
            settleMs = 12_000
        )
    }

    @Test
    fun captureAuthForgotPasswordScreen() {
        captureKmpComponentCase(
            caseId = "screens.auth.forgot-password",
            pageName = SlockKuiklyActivity.PAGE_FORGOT_PASSWORD,
            crop = FULL_VIEWPORT_CROP,
            settleMs = 12_000
        )
    }

    @Test
    fun captureAuthChooseServerScreen() {
        captureKmpComponentCase(
            caseId = "screens.auth.choose-server",
            pageName = "compose_auth_choose_server_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureAuthRegisterInputs() {
        captureKmpComponentCase(
            caseId = "components.auth.register.inputs",
            pageName = "compose_auth_register_inputs",
            pageData =
                JSONObject()
                    .put("registerName", "Product UX Designer")
                    .put("registerEmail", "new@slock.ai")
                    .put("registerPassword", "correct horse battery staple"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    private fun captureKmpSettingsPageCase(
        caseId: String,
        variant: String
    ) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_settings_page_visual",
            pageData = JSONObject().put("variant", variant),
            crop = FULL_VIEWPORT_CROP
        )
    }

    // React baselines have no device status bar, so captures must not inherit
    // the host/native top inset. Read by slockTopSafeAreaPadding().
    private fun withVisualCaptureParams(pageData: JSONObject?): JSONObject =
        (pageData ?: JSONObject()).put("slockVisualZeroSafeArea", true)

    /**
     * Idle-sync with a hard wall-clock ceiling (task #362). Plain
     * [android.app.Instrumentation.waitForIdleSync] blocks forever on screens
     * that keep the main thread perpetually busy — a continuous
     * spinner/skeleton/indeterminate-progress animation, a never-resolving
     * loading state, or async thumbnail decoding — and it swallows interrupts,
     * so it cannot be unblocked by the JUnit Timeout rule. Running it on a
     * daemon thread and bounding the join lets capture proceed and grab the
     * current frame instead of stalling the whole 6-way shard until the 45m
     * job timeout (the failure mode that killed shards 2 & 5 on
     * components.thread.files.list / screens.members.agent-detail.activity).
     */
    private fun waitForIdleBounded(maxMs: Long = IDLE_WAIT_TIMEOUT_MS) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settled = CountDownLatch(1)
        Thread {
            instrumentation.waitForIdleSync()
            settled.countDown()
        }.apply {
            isDaemon = true
            name = "visual-wait-for-idle"
        }.start()
        if (!settled.await(maxMs, TimeUnit.MILLISECONDS)) {
            Log.w(
                VISUAL_LOG_TAG,
                "waitForIdle exceeded ${maxMs}ms; proceeding with current frame (screen likely still animating/loading)"
            )
        }
    }

    private fun captureKmpHomeCase(
        caseId: String,
        pageData: JSONObject,
        crop: VisualCrop = FULL_VIEWPORT_CROP
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_HOME)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            waitForIdleBounded()
            Thread.sleep(2_500)
            scenario.onActivity { suppressVisualKeyboard(it) }
            waitForIdleBounded()
            val normalized = captureStableVisualBitmap(context, scenario, caseId, crop)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            clearMirroredCaseArtifacts(context, caseId)
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                visualMetadata(
                    context = context,
                    caseId = caseId,
                    imageFile = imageFile,
                    bitmap = normalized,
                    crop = crop,
                    captureMethod = crop.captureMethod
                )
            )
            mirrorFileForAdbPull(context, imageFile)
            mirrorFileForAdbPull(context, metadataFile)
            normalized.recycle()
        }
    }

    private fun captureKmpComponentCase(
        caseId: String,
        pageName: String,
        crop: VisualCrop,
        pageData: JSONObject? = null,
        settleMs: Long = 1_500,
        beforeScreenshot: (() -> Unit)? = null
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", pageName)
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            waitForIdleBounded()
            Thread.sleep(settleMs)
            beforeScreenshot?.invoke()
            waitForIdleBounded()
            scenario.onActivity { suppressVisualKeyboard(it) }
            waitForIdleBounded()
            val normalized = captureStableVisualBitmap(context, scenario, caseId, crop)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            clearMirroredCaseArtifacts(context, caseId)
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                visualMetadata(
                    context = context,
                    caseId = caseId,
                    imageFile = imageFile,
                    bitmap = normalized,
                    crop = crop,
                    captureMethod = crop.captureMethod
                )
            )
            mirrorFileForAdbPull(context, imageFile)
            mirrorFileForAdbPull(context, metadataFile)
            normalized.recycle()
        }
    }

    private fun applyVisualFullscreen(activity: Activity) {
        activity.window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.ime()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun suppressVisualKeyboard(activity: Activity) {
        activity.currentFocus?.clearFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.hide(WindowInsets.Type.ime())
        }
    }

    private fun captureActivityScreenshot(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        caseId: String
    ): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            if (root.width > 0 && root.height > 0) {
                bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                    root.draw(Canvas(output))
                }
            }
        }
        return bitmap
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                "Unable to capture KMP visual screenshot for $caseId"
            }
    }

    private fun captureStableVisualBitmap(
        context: Context,
        scenario: ActivityScenario<SlockKuiklyActivity>,
        caseId: String,
        crop: VisualCrop
    ): Bitmap {
        val deadline = SystemClock.uptimeMillis() + STABLE_CAPTURE_TIMEOUT_MS
        var lastBitmap: Bitmap? = null
        var lastStats: VisualBitmapStats? = null
        var lastProblems: List<String> = emptyList()

        do {
            waitForIdleBounded()
            val screenshot = captureActivityScreenshot(scenario, caseId)
            validateCropBounds(caseId, screenshot, crop)
            val cropped = screenshot.cropDp(crop)
            // Task #342: keep native device pixels (emulator pinned to 480dpi
            // = exactly 3x, matching React's deviceScaleFactor 3) instead of
            // downsampling to 1x dp pixels — the downsample blurred every
            // android capture and hid sub-dp diffs.
            val normalized = cropped
            val stats = normalized.visualStats()
            val problems = visualValidationProblems(normalized, stats)
            if (problems.isEmpty()) {
                if (cropped !== screenshot && cropped !== normalized) {
                    cropped.recycle()
                }
                if (screenshot !== normalized) {
                    screenshot.recycle()
                }
                lastBitmap?.recycle()
                return normalized
            }

            lastBitmap?.recycle()
            lastBitmap = normalized
            lastStats = stats
            lastProblems = problems
            if (cropped !== screenshot && cropped !== normalized) {
                cropped.recycle()
            }
            if (screenshot !== normalized) {
                screenshot.recycle()
            }
            Thread.sleep(STABLE_CAPTURE_RETRY_MS)
        } while (SystemClock.uptimeMillis() < deadline)

        val failureBitmap = requireNotNull(lastBitmap) {
            "Unable to capture KMP visual screenshot for $caseId"
        }
        writeFailureArtifacts(
            context = context,
            caseId = caseId,
            bitmap = failureBitmap,
            crop = crop,
            stats = requireNotNull(lastStats),
            problems = lastProblems
        )
        failureBitmap.recycle()
        throw AssertionError("$caseId visual capture failed validation after waiting for first frame: ${lastProblems.joinToString("; ")}")
    }

    private fun homeServerSwitcherPageData(): JSONObject {
        val payload =
            JSONObject()
                .put("instanceId", "visual-home-server-switcher")
                .put("currentServerName", "Slock Design")
                .put("currentServerId", "srv_design")
                .put("serverUnreadCount", 24)
                .put("inboxUnreadCount", 7)
                .put("savedCount", 3)
                .put("serverSwitcherExpanded", true)
                .put("applyTopSafeArea", false)
                .put("contentRefreshEnabled", false)
                .put("bottomContentPaddingDp", 24)
                .put(
                    "servers",
                    JSONArray()
                        .put(server("srv_design", "Slock Design", "slock-design", 24))
                        .put(server("srv_android", "Android Artifacts", "android-artifacts", 12))
                        .put(server("srv_feedback", "Feedback", "feedback", 3))
                )
                .put(
                    "channels",
                    JSONArray()
                        .put(conversation("ch_design", "design", 3, joined = true))
                        .put(conversation("ch_android", "android-artifacts", 0, joined = true))
                )
                .put(
                    "directMessages",
                    JSONArray()
                        .put(conversation("dm_android", "Android Developer", 8, avatarLabel = "AD", presence = "Online"))
                        .put(conversation("dm_product", "Product UX Designer", 0, avatarLabel = "PU", presence = "Offline", hasDraft = true))
                )
        return JSONObject()
            .put("instanceId", "visual-home-server-switcher")
            .put("payload", payload.toString())
    }

    private fun server(id: String, name: String, slug: String, unreadCount: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("slug", slug)
            .put("unreadCount", unreadCount)

    private fun createAgentDialogPageData(
        runtime: String,
        providerMode: String = "default",
        providerApiUrl: String = "",
        providerApiKey: String = "",
        advancedOpen: Boolean = false,
        command: String = "",
        errorMessage: String = ""
    ): JSONObject =
        JSONObject()
            .put("runtime", runtime)
            .put("providerMode", providerMode)
            .put("providerApiUrl", providerApiUrl)
            .put("providerApiKey", providerApiKey)
            .put("advancedOpen", advancedOpen)
            .put("command", command)
            .put("errorMessage", errorMessage)

    private fun memberDetailVisualPageData(
        instanceId: String,
        memberId: String,
        memberKind: String,
        initialAgentTab: String? = null
    ): JSONObject {
        val payload =
            JSONObject()
                .put("instanceId", instanceId)
                .put("memberId", memberId)
                .put("memberKind", memberKind)
                .put("localOffsetMinutes", 480)
                .put("todayEpochDay", 20623)
                .put("nowEpochMillis", 1781872200000)
                .put("timeFormat", "24h")
                .put(
                    "agentPanelTabOrder",
                    JSONArray()
                        .put("profile")
                        .put("activity")
                        .put("chat")
                        .put("reminders")
                        .put("workspace")
                        .put("integrations")
                )
        initialAgentTab?.let { payload.put("initialAgentTab", it) }
        return JSONObject()
            .put("instanceId", instanceId)
            .put("payload", payload.toString())
    }

    private fun conversation(
        id: String,
        name: String,
        unreadCount: Int,
        avatarLabel: String = name.take(2).uppercase(),
        presence: String? = null,
        hasDraft: Boolean = false,
        joined: Boolean = true
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("unreadCount", unreadCount)
            .put("avatarLabel", avatarLabel)
            .put("hasDraft", hasDraft)
            .put("joined", joined)
            .apply {
                if (presence != null) put("presence", presence)
            }

    private fun Bitmap.cropDp(crop: VisualCrop): Bitmap {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val left = (crop.leftDp * density).roundToInt().coerceIn(0, width - 1)
        val top = (crop.topDp * density).roundToInt().coerceIn(0, height - 1)
        val desiredWidth = (crop.widthDp * density).roundToInt()
        val desiredHeight = (crop.heightDp * density).roundToInt()
        val cropWidth = desiredWidth.coerceAtMost(width - left)
        val cropHeight = desiredHeight.coerceAtMost(height - top)
        return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    }

    private fun validateCropBounds(caseId: String, screenshot: Bitmap, crop: VisualCrop) {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val left = (crop.leftDp * density).roundToInt()
        val top = (crop.topDp * density).roundToInt()
        val desiredWidth = (crop.widthDp * density).roundToInt()
        val desiredHeight = (crop.heightDp * density).roundToInt()
        require(left >= 0 && top >= 0) {
            "$caseId visual crop starts outside screenshot: crop=$crop screenshot=${screenshot.width}x${screenshot.height}"
        }
        require(left + desiredWidth <= screenshot.width && top + desiredHeight <= screenshot.height) {
            "$caseId visual crop is clipped: crop=$crop px=($left,$top ${desiredWidth}x$desiredHeight) screenshot=${screenshot.width}x${screenshot.height}"
        }
    }

    private fun visualValidationProblems(bitmap: Bitmap, stats: VisualBitmapStats): List<String> {
        val problems = mutableListOf<String>()
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            problems += "bitmap is too small: ${bitmap.width}x${bitmap.height}"
        }
        if (stats.uniqueColors <= 2) {
            problems += "bitmap has only ${stats.uniqueColors} unique color(s)"
        }
        if (stats.dominantRatio >= 0.985f) {
            problems += "bitmap is ${"%.2f".format(stats.dominantRatio * 100)}% one color"
        }
        return problems
    }

    private fun Bitmap.visualStats(): VisualBitmapStats {
        val counts = linkedMapOf<Int, Int>()
        val pixels = IntArray(width)
        for (y in 0 until height) {
            getPixels(pixels, 0, width, 0, y, width, 1)
            for (pixel in pixels) {
                val opaque = Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                counts[opaque] = (counts[opaque] ?: 0) + 1
            }
        }
        val total = width * height
        val dominant = counts.values.maxOrNull() ?: 0
        return VisualBitmapStats(
            uniqueColors = counts.size,
            dominantRatio = if (total == 0) 1f else dominant.toFloat() / total.toFloat()
        )
    }

    private fun tapDp(xDp: Float, yDp: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val y = yDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0))
        waitForIdleBounded()
    }

    private fun writeFailureArtifacts(
        context: Context,
        caseId: String,
        bitmap: Bitmap,
        crop: VisualCrop,
        stats: VisualBitmapStats,
        problems: List<String>
    ) {
        val outputDir = visualOutputDir(context)
        outputDir.mkdirs()
        clearMirroredCaseArtifacts(context, caseId)
        val imageFile = File(outputDir, "$caseId.failure.png")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val metadataFile = File(outputDir, "$caseId.failure.json")
        metadataFile.writeText(
            """
            {
              "provider": "android",
              "providerType": "android-kuikly-shared-page",
              "source": "kmp-shared",
              "caseId": "$caseId",
              "status": "failed",
              "image": "${imageFile.absolutePath}",
              "systemBars": "hidden",
              "crop": {
                "mode": "manual-rect",
                "contract": "${crop.contract}",
                "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
              },
              "validation": {
                "uniqueColors": ${stats.uniqueColors},
                "dominantRatio": ${stats.dominantRatio},
                "errors": ${JSONArray(problems)}
              },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
        )
        mirrorFileForAdbPull(context, imageFile)
        mirrorFileForAdbPull(context, metadataFile)
    }

    private fun visualMetadata(
        context: Context,
        caseId: String,
        imageFile: File,
        bitmap: Bitmap,
        crop: VisualCrop,
        captureMethod: String
    ): String {
        val stats = bitmap.visualStats()
        return """
            {
              "provider": "android",
              "providerType": "android-kuikly-shared-page",
              "source": "kmp-shared",
              "caseId": "$caseId",
              "image": "${imageFile.absolutePath}",
              "viewport": { "width": ${bitmap.width}, "height": ${bitmap.height}, "density": 1 },
              "systemBars": "hidden",
              "crop": {
                "mode": "manual-rect",
                "contract": "${crop.contract}",
                "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
              },
              "capture": {
                "method": "$captureMethod",
                "fullscreen": true,
                "systemBars": "hidden",
                "validation": {
                  "uniqueColors": ${stats.uniqueColors},
                  "dominantRatio": ${stats.dominantRatio}
                }
              },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
    }

    private fun visualOutputDir(context: Context): File =
        File(context.filesDir, "visual-testing/android")

    // Append a per-case timing line to a shard-scoped log that rides along in
    // the existing visual-results-android-shard-N artifact (task #362). START
    // is written before the case runs, so a case that later times out is still
    // recorded — pinpointing exactly which item stalled and for how long, even
    // without the (workflow-scoped) logcat upload.
    private fun recordTiming(line: String) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = visualOutputDir(context)
            dir.mkdirs()
            val timingFile = File(dir, CAPTURE_TIMING_FILE)
            timingFile.appendText("${SystemClock.uptimeMillis()} $line\n")
            mirrorFileForAdbPull(context, timingFile)
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "recordTiming failed: ${t.message}")
        }
    }

    private fun mirrorFileForAdbPull(context: Context, file: File) {
        VisualAndroidArtifacts.mirrorFile(file)
    }

    private fun clearMirroredCaseArtifacts(context: Context, caseId: String) {
        File(visualOutputDir(context), "$caseId.png").delete()
        File(visualOutputDir(context), "$caseId.metadata.json").delete()
        File(visualOutputDir(context), "$caseId.failure.png").delete()
        File(visualOutputDir(context), "$caseId.failure.json").delete()
        deletePublicDownloadArtifactsWithShell(caseId)
    }

    private fun deletePublicDownloadArtifactsWithShell(caseId: String) {
        VisualAndroidArtifacts.deleteCaseArtifacts(caseId)
    }


    private data class VisualCrop(
        val leftDp: Int,
        val topDp: Int,
        val widthDp: Int,
        val heightDp: Int,
        val contract: String = "component-bounds",
        val captureMethod: String = "app-window-root-draw-manual-crop-density-resize"
    )

    private data class VisualBitmapStats(
        val uniqueColors: Int,
        val dominantRatio: Float
    )

    companion object {
        private val FULL_VIEWPORT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 844,
                contract = "viewport-bounds",
                captureMethod = "app-window-root-draw-viewport-density-resize"
            )
        private val HOME_SAVED_RESULTS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 620
            )
        private val THREAD_HEADER_COMPONENT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 62
            )
        private val THREAD_REACTION_ATLAS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 270
            )
        private val HOME_ACTIVITY_RESULTS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 620
            )
        private val TOKEN_PALETTE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 620
            )
        private val UI_SEGMENTED_CONTROL_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 96
            )
        private val UI_BUTTON_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 150
            )
        private val UI_FORM_FIELD_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 226
            )
        private val UI_TEXTAREA_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 240
            )
        private val UI_CHECKBOX_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 132
            )
        private val UI_CHECK_MARKER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 112
            )
        private val UI_ATTENTION_DOT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 80
            )
        private val UI_STATUS_DOT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 92
            )
        private val UI_PROGRESS_BAR_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 124
            )
        private val UI_SKELETON_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 150
            )
        private val UI_SPINNER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 92
            )
        private val UI_SLUG_INPUT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 122
            )
        private val UI_SECTION_EYEBROW_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 92
            )
        private val UI_SELECTION_POPOVER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 252
            )
        private val UI_MENU_ITEM_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 176
            )
        private val UI_SELECT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 194
            )
        private val UI_BADGE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 92
            )
        private val UI_SECTION_HEADER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 88
            )
        private val UI_SURFACE_LIST_ITEM_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 168
            )
        private val UI_AVATAR_LIST_ROW_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 168
            )
        private val RAFT_UI_BANNER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 176
            )
        private val RAFT_UI_EMPTY_STATE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 248
            )
        private val RAFT_UI_TYPOGRAPHY_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 178
            )
        private val RAFT_UI_FIELD_INPUT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 236
            )
        private val RAFT_UI_AVATAR_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 116
            )
        private val RAFT_UI_BUTTON_BADGE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 142
            )
        private val RAFT_UI_CHECKBOX_STATUS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 122
            )
        private val RAFT_UI_TEXTAREA_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 178
            )
        private val RAFT_UI_TABS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 144
            )
        private val RAFT_UI_SEGMENTED_CONTROL_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 96
            )
        private val RAFT_UI_LABEL_RADIO_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 154
            )
        private val RAFT_UI_SELECT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 148
            )
        private val RAFT_UI_MENU_SURFACES_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 210
            )
        private val RAFT_UI_POPOVER_SURFACE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 174
            )
        private val RAFT_UI_COMBOBOX_SURFACE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 258
            )
        private val RAFT_UI_NOTIFICATION_CENTER_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 294
            )
        private val RAFT_UI_INTERACTION_STATES_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 424
            )
        private val RAFT_UI_CONTROLLED_OPEN_OVERLAYS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 760
            )
        private val RAFT_UI_WIP_TOGGLE_GROUP_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 112
            )
        private val RAFT_UI_WIP_LIST_PANELS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 388
            )
        private val RAFT_UI_WIP_MESSAGE_CARDS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 250
            )
        private val RAFT_UI_WIP_OVERLAY_SHELLS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 284
            )
        private val THREAD_COMPOSER_COMPONENT_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 100
            )
        private val THREAD_COMPOSER_SUGGESTIONS_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 320
            )
        private val THREAD_COMPOSER_ATTACHMENTS_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 270
            )
        private val THREAD_FILES_LIST_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 360
            )
        // Header + toolbar + four status sections with the done card expanded measure
        // close to the full viewport, so the crop keeps the whole 844dp height instead
        // of a content-sized guess that could clip the Done section (task #330/#353).
        private val TASKS_PANEL_STATES_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 844
            )
        private val TASKS_STATUS_MENU_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 260
            )
        private val THREAD_MESSAGE_ROW_RICH_CONTENT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 394
            )
        private val THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 760
            )

        @BeforeClass
        @JvmStatic
        fun clearVisualOutputDirectory() {
            VisualAndroidArtifacts.resetPublicOutput()
            // Start each shard's per-case timing log clean.
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "visual-testing/android/$CAPTURE_TIMING_FILE"
            ).delete()
        }

        private const val STABLE_CAPTURE_TIMEOUT_MS = 20_000L
        private const val STABLE_CAPTURE_RETRY_MS = 750L

        // Logcat tag for per-case timing (CASE_START/CASE_END) and idle-wait
        // warnings — grep this out of the uploaded logcat artifact (task #362).
        private const val VISUAL_LOG_TAG = "VisualCapture"

        // Shard-scoped per-case timing log carried in the results artifact.
        private const val CAPTURE_TIMING_FILE = "_capture-timing.log"

        // Ceiling for a single idle-sync. Healthy cases idle in well under a
        // second; an animating/never-idle screen would otherwise block forever.
        private const val IDLE_WAIT_TIMEOUT_MS = 8_000L

        // Hard per-case backstop. Healthy 3x captures run ~20-40s; this only
        // trips on a genuinely stuck case, failing that case alone instead of
        // the whole shard.
        private const val PER_CASE_TIMEOUT_SECONDS = 120L
    }
}
