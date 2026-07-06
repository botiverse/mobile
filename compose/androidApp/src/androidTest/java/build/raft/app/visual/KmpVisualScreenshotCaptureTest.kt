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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import build.raft.app.shared.SlockKuiklyActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
                currentCaseId = null
                // Log the display/viewport condition up front (task #367): a
                // case that later renders blank should carry the exact
                // width/height/density it ran under, so a CI-only failure can be
                // told apart from a geometry difference.
                val dm = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
                Log.i(
                    VISUAL_LOG_TAG,
                    "CASE_START ${description.methodName} display=${dm.widthPixels}x${dm.heightPixels} densityDpi=${dm.densityDpi}"
                )
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
                currentCaseId?.let { writeExceptionFailureArtifact(it, e) }
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

    // Set at the start of each capture so caseTimingWatcher.failed() can write a
    // failure.json even when the case dies on the exception/timeout path (which
    // never reaches writeFailureArtifacts) — task #370: form-field and other
    // exception/timeout failures otherwise leave no on-site diagnostics.
    private var currentCaseId: String? = null

    // Set true when a case falls back to a clamped short-window capture (task
    // #370): those images pad-mismatch against React's full height and score a
    // bit lower, so metadata flags them — a low score on a flagged case is a
    // flaky short-window run, NOT a regression.
    private var lastCaptureClampedToShortWindow = false


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
            crop = NAVIGATION_TABBAR_CROP
        )
    }

    @Test
    fun captureForegroundBannerContextVariants() {
        captureKmpComponentCase(
            caseId = "components.shell.foreground-banner.context-variants",
            pageName = "compose_foreground_banner_visual",
            crop = FOREGROUND_BANNER_CONTEXT_CROP
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
            crop = THREAD_MESSAGE_ROW_CROP,
            requireReactionAtlas = true
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
            crop = THREAD_MESSAGE_ROW_DELETED_HUMAN_CROP
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
            crop = FULL_VIEWPORT_CROP,
            requireReactionAtlas = true
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
            crop = FULL_VIEWPORT_CROP,
            requireReactionAtlas = true
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
    fun captureThreadMessageRowWideMarkdownWrap() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.wide-markdown-wrap",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-wide-markdown-wrap")
                    .put("visualFixture", "wide_markdown_wrap"),
            crop = FULL_VIEWPORT_CROP
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
            settleMs = 2_500,
            requireReactionAtlas = true
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
    fun captureHomeLoadingScreen() {
        captureKmpComponentCase(
            caseId = "screens.home.loading",
            pageName = "compose_home_loading_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
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

    // Joint-channel roster variant split from components.channel.members.panel
    // (task #370): react counterpart + manifest entry land separately, so this
    // case is react-missing until then.
    @Test
    fun captureChannelMembersPanelJointChannel() {
        captureKmpComponentCase(
            caseId = "components.channel.members.panel.joint-channel",
            pageName = "compose_channel_members_panel_joint_channel_visual",
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
    fun captureAuthLoginSigning() {
        captureKmpComponentCase(
            caseId = "screens.auth.login.signing",
            pageName = "compose_auth_login_inputs",
            pageData = JSONObject().put("visualFixture", "signing"),
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

    /**
     * Close the scenario with a wall-clock ceiling (task #364). Even after the
     * screenshot is captured, ActivityScenario.close() -> moveToState(DESTROYED)
     * does its own unbounded Instrumentation.waitForIdleSync(), which never
     * returns on a screen still animating/loading at teardown (e.g.
     * components.members.avatar-management) — so the case would burn the per-case
     * backstop even though its image is already written. Run close() on a daemon
     * thread and bound the join; if teardown never idles, abandon it (the leaked
     * activity is replaced when the next case launches fresh) so the case is
     * reported OK with the image it already produced.
     */
    private fun closeScenarioBounded(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        maxMs: Long = SCENARIO_CLOSE_TIMEOUT_MS
    ) {
        val closed = CountDownLatch(1)
        Thread {
            try {
                scenario.close()
            } catch (t: Throwable) {
                Log.w(VISUAL_LOG_TAG, "scenario.close() threw: ${t.message}")
            } finally {
                closed.countDown()
            }
        }.apply {
            isDaemon = true
            name = "visual-scenario-close"
        }.start()
        if (!closed.await(maxMs, TimeUnit.MILLISECONDS)) {
            Log.w(
                VISUAL_LOG_TAG,
                "scenario.close() exceeded ${maxMs}ms; abandoning teardown (screen never idle at close)"
            )
        }
    }

    /**
     * Launch the scenario with a wall-clock ceiling (task #370). This is the
     * LAST unbounded idle-wait in the capture path: ActivityScenario.launch()
     * blocks until the activity reaches RESUMED, and on a page that ANRs at
     * first composition (member-detail LazyColumn never composes in headless →
     * main wedges → "System UI isn't responding") that wait hangs ~100s, fully
     * silent — the observed 76-146s member-case durations were spent HERE, not
     * in runOnMainSync (#177's bound never tripped) nor the bounded capture
     * loop. #364 bounded onActivity + close but left launch unbounded, so a
     * wedged launch pushed lifecycle to 146s (4s from the 150s per-case backstop
     * — a tail-drop tip-over on a slow runner). Run launch on a daemon thread
     * and bound the join; on timeout throw so the case fast-fails cleanly
     * (CASE_END status=FAIL) instead of hanging toward the backstop and dropping
     * the shard tail. On success log the launch duration to confirm where the
     * time goes. 45s cleanly separates a genuine ANR launch (100s+) from a
     * merely slow-but-progressing launch on a degraded runner (seen ~15-25s).
     */
    private fun launchScenarioBounded(
        intent: Intent,
        maxMs: Long = SCENARIO_LAUNCH_TIMEOUT_MS
    ): ActivityScenario<SlockKuiklyActivity> {
        val start = SystemClock.uptimeMillis()
        val scenarioRef = AtomicReference<ActivityScenario<SlockKuiklyActivity>?>(null)
        val errorRef = AtomicReference<Throwable?>(null)
        val launched = CountDownLatch(1)
        Thread {
            try {
                scenarioRef.set(ActivityScenario.launch<SlockKuiklyActivity>(intent))
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                launched.countDown()
            }
        }.apply {
            isDaemon = true
            name = "visual-scenario-launch"
        }.start()
        if (!launched.await(maxMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError(
                "ActivityScenario.launch exceeded ${maxMs}ms — activity never reached " +
                    "RESUMED (likely ANR at first composition in headless capture)"
            )
        }
        errorRef.get()?.let { throw it }
        val scenario = scenarioRef.get()
            ?: throw AssertionError("ActivityScenario.launch returned null")
        Log.i(VISUAL_LOG_TAG, "ACTIVITY_LAUNCHED durationMs=${SystemClock.uptimeMillis() - start}")
        return scenario
    }

    private fun captureKmpHomeCase(
        caseId: String,
        pageData: JSONObject,
        crop: VisualCrop = FULL_VIEWPORT_CROP
    ) {
        currentCaseId = caseId
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_HOME)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        val scenario = launchScenarioBounded(intent)
        try {
            onActivityNoIdle { applyVisualFullscreen(it) }
            waitForIdleBounded()
            Thread.sleep(2_500)
            onActivityNoIdle { suppressVisualKeyboard(it) }
            waitForIdleBounded()
            val normalized = captureStableVisualBitmap(context, caseId, crop)
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
        } finally {
            closeScenarioBounded(scenario)
        }
    }

    private fun captureKmpComponentCase(
        caseId: String,
        pageName: String,
        crop: VisualCrop,
        pageData: JSONObject? = null,
        settleMs: Long = 1_500,
        requireReactionAtlas: Boolean = false,
        beforeScreenshot: (() -> Unit)? = null
    ) {
        currentCaseId = caseId
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", pageName)
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
        val scenario = launchScenarioBounded(intent)
        try {
            onActivityNoIdle { applyVisualFullscreen(it) }
            waitForIdleBounded()
            Thread.sleep(settleMs)
            beforeScreenshot?.invoke()
            waitForIdleBounded()
            onActivityNoIdle { suppressVisualKeyboard(it) }
            waitForIdleBounded()
            val normalized = captureStableVisualBitmap(context, caseId, crop, requireReactionAtlas)
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
        } finally {
            closeScenarioBounded(scenario)
        }
    }

    private fun applyVisualFullscreen(activity: Activity) {
        activity.window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Draw edge-to-edge so the decor view fills the full display and the
            // status-bar area is not reserved (task #370): hiding the bars alone
            // leaves the content laid out inside the inset-reduced area, so the
            // captured screenshot came back ~118px short (1170x2414 vs the
            // 1170x2532 the FULL_VIEWPORT crop needs) and every full-viewport
            // case failed validateCropBounds. Zeroing decor-fits makes the
            // window a true 1170x2532 with no top-inset shift.
            activity.window.setDecorFitsSystemWindows(false)
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

    /**
     * Run [block] on the main thread via runOnMainSync, but bound the wait so a
     * wedged/ANR'd main thread can't hang the case (task #370). runOnMainSync
     * POSTS the runnable to the main looper and blocks the caller on an internal
     * monitor until it runs — but if the main thread itself is stuck (e.g. the
     * member-detail LazyColumn never composes in headless because it has no
     * measurable viewport → main spins → "System UI isn't responding" ANR), the
     * runnable never executes and runOnMainSync blocks FOREVER. That monitor
     * wait is not interruptible by the JUnit per-case Timeout, so a wedged main
     * would otherwise burn the case toward the 150s backstop — and if it tips
     * over, drop every case MethodSorter-ordered after it (the tail-drop root
     * cause). Running runOnMainSync on a daemon thread and bounding the join
     * turns "main unresponsive" into a fast, clean AssertionError → the
     * TestWatcher emits CASE_END status=FAIL, so the case is visibly failed
     * (CC-Wow2's #167 tail-drop guard stays quiet on it) instead of hanging.
     */
    private fun runOnMainSyncBounded(maxMs: Long = MAIN_SYNC_TIMEOUT_MS, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        Thread {
            instrumentation.runOnMainSync { block() }
            done.countDown()
        }.apply {
            isDaemon = true
            name = "visual-run-on-main"
        }.start()
        if (!done.await(maxMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError(
                "main thread unresponsive: runOnMainSync exceeded ${maxMs}ms " +
                    "(likely ANR — page never idle/composed in headless capture)"
            )
        }
    }

    /**
     * Run [block] against the current RESUMED activity on the main thread
     * WITHOUT ActivityScenario.onActivity's internal waitForIdleSync (task
     * #364). onActivity() blocks on waitForIdleSync, which never returns on a
     * never-idle screen (animation/loading) — the same unbounded wait #362
     * fixed for the standalone calls but which stayed hidden inside onActivity.
     * The main-thread post is bounded ([runOnMainSyncBounded], task #370) so a
     * wedged/ANR'd main fails the case fast instead of hanging. If the activity
     * already died (e.g. a page that crashes on render, like wip-list-panels),
     * there is no RESUMED activity — skip instead of NPE-cascading on a
     * destroyed scenario.
     */
    private fun onActivityNoIdle(block: (Activity) -> Unit) {
        runOnMainSyncBounded {
            val activity =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull()
            if (activity != null) {
                block(activity)
            } else {
                Log.w(VISUAL_LOG_TAG, "onActivityNoIdle: no RESUMED activity (page may have crashed/finished)")
            }
        }
    }

    private fun captureActivityScreenshot(caseId: String): Bitmap {
        var bitmap: Bitmap? = null
        onActivityNoIdle { activity ->
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
        caseId: String,
        crop: VisualCrop,
        requireReactionAtlas: Boolean = false
    ): Bitmap {
        lastCaptureClampedToShortWindow = false
        if (requireReactionAtlas) {
            awaitReactionAtlasReady(caseId)
        }
        val deadline = SystemClock.uptimeMillis() + STABLE_CAPTURE_TIMEOUT_MS
        var lastBitmap: Bitmap? = null
        var lastStats: VisualBitmapStats? = null
        var lastProblems: List<String> = emptyList()

        do {
            waitForIdleBounded()
            val screenshot = captureActivityScreenshot(caseId)
            val boundsProblem = cropBoundsProblem(screenshot, crop)
            if (boundsProblem != null) {
                // Window not at full size yet (e.g. edge-to-edge not settled) —
                // retry within the deadline rather than dropping to "missing".
                Log.w(VISUAL_LOG_TAG, "$caseId $boundsProblem — retrying")
                lastProblems = listOf(boundsProblem)
                screenshot.recycle()
                Thread.sleep(STABLE_CAPTURE_RETRY_MS)
                continue
            }
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

        if (lastBitmap == null) {
            // The window never reached the crop's full size within the deadline
            // (task #370: a flaky wm-size run can leave the display undersize, so
            // a FULL_VIEWPORT 2532px crop never fits). Rather than drop the case
            // to "missing", capture the actual window with a clamped crop (cropDp
            // coerces to the available bounds) — the diff normalizes the size
            // difference, so a slightly-short image beats nothing.
            Log.w(VISUAL_LOG_TAG, "$caseId window never reached full crop size within ${STABLE_CAPTURE_TIMEOUT_MS}ms; capturing clamped best-effort")
            lastCaptureClampedToShortWindow = true
            val screenshot = captureActivityScreenshot(caseId)
            val clamped = screenshot.cropDp(crop)
            if (clamped !== screenshot) {
                screenshot.recycle()
            }
            return clamped
        }

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

    /**
     * Block (bounded) until the reaction sprite atlas has finished decoding, so
     * a reaction case captures the sprite glyphs rather than the emoji fallback
     * (task #379). The React side (#137) logs [REACTION_ATLAS_READY_TOKEN] via
     * the Kuikly bridge (host tag "SlockKuikly") the moment the hoisted atlas
     * ImageBitmap goes null->non-null; #138 mounts that provider into the fixture
     * so the token fires during our capture. The token is deduped + process-
     * global, so the first reaction case in a shard waits for the decode and
     * every later reaction case sees the already-buffered token immediately.
     *
     * The token content is the correctness gate (see [logcatContainsToken]).
     * This reads our OWN process's logcat, which needs no READ_LOGS permission
     * since the token is emitted by the app-under-test process the
     * instrumentation shares.
     *
     * Non-fatal by design: if the token never appears within the deadline we log
     * and proceed to capture anyway (the old emoji-fallback frame), so a signal-
     * side regression degrades to "no improvement", never a shard failure.
     */
    private fun awaitReactionAtlasReady(caseId: String) {
        val deadline = SystemClock.uptimeMillis() + REACTION_ATLAS_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (logcatContainsToken(REACTION_ATLAS_READY_TOKEN)) {
                Log.i(VISUAL_LOG_TAG, "$caseId reaction atlas ready (token seen); settling ${REACTION_ATLAS_SETTLE_AFTER_MS}ms before capture")
                Thread.sleep(REACTION_ATLAS_SETTLE_AFTER_MS)
                waitForIdleBounded()
                return
            }
            Thread.sleep(REACTION_ATLAS_POLL_MS)
        }
        Log.w(VISUAL_LOG_TAG, "$caseId reaction atlas NOT ready within ${REACTION_ATLAS_TIMEOUT_MS}ms; capturing anyway (may show emoji fallback)")
    }

    /**
     * True if the current process logcat buffer contains [token]. Reads a
     * snapshot (`logcat -d`, non-blocking, exits immediately) filtered to the
     * bridge tag [REACTION_ATLAS_LOG_TAG] to bound the scanned volume — the CI
     * ring buffer is grown to 16M, so an unfiltered dump per poll would be heavy
     * I/O. The tag filter is only a volume optimization; the [token] content
     * match stays the correctness gate (the tag is shared with other bridge
     * output, per CC-Wow2), and the token line is confirmed to ride this tag
     * (#137: recordLog(level, "SlockKuikly", content)).
     *
     * ASSUMPTION — test and app share one process: the instrumented test runs
     * in the SAME process/UID as the app under test, so an unprivileged
     * `logcat -d` returns this process's own logs, which include the Kuikly
     * bridge's token — no READ_LOGS needed. If the capture is ever split into a
     * separate instrumentation process from the app, this read would no longer
     * see the app's token (the settle would just time out non-fatally); route
     * the signal cross-process (or grant READ_LOGS) before doing that.
     *
     * Any failure returns false so the caller falls through to its deadline
     * rather than throwing.
     */
    private fun logcatContainsToken(token: String): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-s", REACTION_ATLAS_LOG_TAG)
            )
            process.inputStream.bufferedReader().useLines { lines ->
                lines.any { it.contains(token) }
            }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "logcatContainsToken failed: ${t.message}")
            false
        } finally {
            process?.destroy()
        }
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

    // Returns null when the screenshot is large enough for the crop, else a
    // description. Task #370: a too-small screenshot (e.g. the edge-to-edge
    // window not yet settled so the decor view is still inset-reduced) is
    // RETRYABLE, not fatal — the stable-capture loop keeps polling until the
    // window reaches full size or the deadline passes, instead of hard-throwing
    // and dropping the case to "missing" on a transient timing race.
    private fun cropBoundsProblem(screenshot: Bitmap, crop: VisualCrop): String? {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val left = (crop.leftDp * density).roundToInt()
        val top = (crop.topDp * density).roundToInt()
        val desiredWidth = (crop.widthDp * density).roundToInt()
        val desiredHeight = (crop.heightDp * density).roundToInt()
        if (left < 0 || top < 0) {
            return "crop starts outside screenshot: crop=$crop screenshot=${screenshot.width}x${screenshot.height}"
        }
        if (left + desiredWidth > screenshot.width || top + desiredHeight > screenshot.height) {
            return "crop needs ${left + desiredWidth}x${top + desiredHeight}px but screenshot is only ${screenshot.width}x${screenshot.height} (window not at full size / inset not settled)"
        }
        return null
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
        val diagnostics = collectDiagnosticsJson(context)
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
              "diagnostics": $diagnostics,
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
        val diagnostics = collectDiagnosticsJson(context)
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
              "diagnostics": $diagnostics,
              "clampedToShortWindow": $lastCaptureClampedToShortWindow,
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
    }

    // Capture-environment diagnostics (task #367). A case can render blank in
    // one environment (e.g. CI's software-GPU headless emulator) while passing
    // in another with identical geometry — so record the display/GPU/layout
    // condition each capture ran under. The view-tree counts distinguish
    // "content never laid out" (data/fixture problem: laidOut≈0) from "content
    // laid out but drawn blank" (renderer/GPU problem: laidOut>0 yet the bitmap
    // is one color). Emitted into both metadata.json and failure.json.
    private fun collectDiagnosticsJson(context: Context): String {
        val dm = context.resources.displayMetrics
        var rootWidth = 0
        var rootHeight = 0
        var hardwareAccelerated = false
        val counts = IntArray(3) // total, laidOut (w>0 && h>0), visibleContentLeaf
        try {
            onActivityNoIdle { activity ->
                val root = activity.window.decorView.rootView
                rootWidth = root.width
                rootHeight = root.height
                hardwareAccelerated = root.isHardwareAccelerated
                summarizeViewTree(root, counts)
            }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "collectDiagnostics failed: ${t.message}")
        }
        return """
            {
                "displayWidthPx": ${dm.widthPixels},
                "displayHeightPx": ${dm.heightPixels},
                "densityDpi": ${dm.densityDpi},
                "rootWidthPx": $rootWidth,
                "rootHeightPx": $rootHeight,
                "hardwareAccelerated": $hardwareAccelerated,
                "totalViews": ${counts[0]},
                "laidOutViews": ${counts[1]},
                "visibleContentLeafViews": ${counts[2]}
            }
        """.trimIndent()
    }

    private fun summarizeViewTree(view: View, counts: IntArray) {
        if (counts[0] >= DIAGNOSTICS_MAX_VIEWS) return
        counts[0]++
        val laidOut = view.width > 0 && view.height > 0
        if (laidOut) counts[1]++
        if (view !is ViewGroup) {
            if (laidOut && view.visibility == View.VISIBLE) counts[2]++
        } else {
            for (i in 0 until view.childCount) summarizeViewTree(view.getChildAt(i), counts)
        }
    }

    // Failure-path safety net (task #370): validation failures go through
    // writeFailureArtifacts (full image + #90 diagnostics), but a case that dies
    // on the exception/timeout path (e.g. form-field's intermittent flake) never
    // reaches it and used to leave NO on-site diagnostics — only logcat. Write a
    // minimal failure.json here so the failure is at least triageable from the
    // published artifact. Timeout-safe on purpose: display metrics only, NO
    // view-tree walk / runOnMainSync, since a timeout means the main thread may
    // itself be blocked. Skips if a full failure.json already exists.
    private fun writeExceptionFailureArtifact(caseId: String, error: Throwable) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = visualOutputDir(context)
            dir.mkdirs()
            val file = File(dir, "$caseId.failure.json")
            if (file.exists()) return
            val dm = context.resources.displayMetrics
            file.writeText(
                """
                {
                  "provider": "android",
                  "providerType": "android-kuikly-shared-page",
                  "source": "kmp-shared",
                  "caseId": "$caseId",
                  "status": "failed",
                  "failurePath": "exception-or-timeout",
                  "error": {
                    "type": "${error.javaClass.name}",
                    "message": ${JSONObject.quote(error.message ?: "")}
                  },
                  "diagnostics": {
                    "displayWidthPx": ${dm.widthPixels},
                    "displayHeightPx": ${dm.heightPixels},
                    "densityDpi": ${dm.densityDpi},
                    "note": "view-tree diagnostics skipped on the exception/timeout path (main thread may be blocked)"
                  },
                  "capturedAt": "${System.currentTimeMillis()}"
                }
                """.trimIndent() + "\n"
            )
            mirrorFileForAdbPull(context, file)
            Log.i(VISUAL_LOG_TAG, "wrote exception-path failure.json for $caseId (${error.javaClass.simpleName})")
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "writeExceptionFailureArtifact failed: ${t.message}")
        }
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
        // React's element is 120dp tall (1170x360px @3x) because it includes the
        // Chat/Tasks/Files mode-tab strip below the 62dp header; the fixture
        // renders both on a 120dp canvas (task #370).
        private val THREAD_HEADER_COMPONENT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 120
            )
        // React captures the tab bar element at full page width: 1170x159px @3x
        // = 390x53dp. The fixture renders the bar flush at the window origin, so
        // the crop starts at (0,0) (task #370).
        private val NAVIGATION_TABBAR_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 53
            )
        private val FOREGROUND_BANNER_CONTEXT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 322
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
        // React's content-sized element measures 543px @3x = 181dp; the
        // remaining in-image diff is the genuine ~1.25dp/row menu-row height
        // delta vs react, to be fixed in the component later (task #370).
        private val UI_MENU_ITEM_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 181
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
        // React pins this element at 182dp; fixture canvas matches so the
        // top-aligned content's bottom slack exposes real spacing diffs
        // (task #370).
        private val UI_SURFACE_LIST_ITEM_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 182
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
        // React's measured element rect is x=16,y=16,374x108dp (deleted-human
        // 374x109dp) — the row card inside the fixture's SlockSpacing.Md (16dp)
        // padding, from the paired validation run's metadata. The android
        // fixture is the same 390dp-wide Column with 16dp padding, so cropping
        // the identical rect makes both providers emit 374dp-wide images
        // (task #370).
        private val THREAD_MESSAGE_ROW_CROP =
            VisualCrop(
                leftDp = 16,
                topDp = 16,
                widthDp = 374,
                heightDp = 108
            )
        private val THREAD_MESSAGE_ROW_DELETED_HUMAN_CROP =
            VisualCrop(
                leftDp = 16,
                topDp = 16,
                widthDp = 374,
                heightDp = 109
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

        // Reaction-glyph sprite atlas settle (task #379). The reaction atlas is
        // decoded async off an imageResource; in the headless capture env the
        // decode lags the first snapshot, so reaction cases captured before it
        // completes render the emoji fallback instead of the sprite glyphs. The
        // React side (#137) emits a deduped bridge log the moment the hoisted
        // atlas ImageBitmap goes null->non-null, and #138 mounts that same
        // provider into the message-row/menu fixtures so the token fires during
        // capture. We grep the token OUT of our own process logcat and only then
        // let the stable capture proceed. The token content is the correctness
        // gate (tag "SlockKuikly" is shared with other bridge output); we
        // additionally tag-filter the dump purely to bound scanned volume.
        // Non-fatal: on timeout we capture anyway (degrades to the old emoji-
        // fallback frame, never a failure) so a signal regression can't break
        // the shard.
        private const val REACTION_ATLAS_READY_TOKEN = "thread_reaction_atlas_ready"
        // Kuikly bridge host tag the token rides (SlockKuiklyActivity ->
        // SlockAndroidBuglyDiagnostics.recordLog(level, "SlockKuikly", content)).
        private const val REACTION_ATLAS_LOG_TAG = "SlockKuikly"
        private const val REACTION_ATLAS_TIMEOUT_MS = 20_000L
        private const val REACTION_ATLAS_POLL_MS = 250L
        // Brief settle after the token so the next recomposition swaps emoji->
        // sprite before we snapshot (the token means "atlas ready", the draw
        // switches on the following frame).
        private const val REACTION_ATLAS_SETTLE_AFTER_MS = 600L

        // Logcat tag for per-case timing (CASE_START/CASE_END) and idle-wait
        // warnings — grep this out of the uploaded logcat artifact (task #362).
        private const val VISUAL_LOG_TAG = "VisualCapture"

        // Shard-scoped per-case timing log carried in the results artifact.
        private const val CAPTURE_TIMING_FILE = "_capture-timing.log"

        // Cap the diagnostics view-tree walk so it never dominates a capture.
        private const val DIAGNOSTICS_MAX_VIEWS = 2_000

        // Ceiling for a single idle-sync. Healthy cases idle in well under a
        // second; an animating/never-idle screen would otherwise block forever.
        private const val IDLE_WAIT_TIMEOUT_MS = 8_000L

        // Ceiling for a single main-thread post (runOnMainSyncBounded, task
        // #370). A healthy runOnMainSync completes in milliseconds; only a
        // wedged/ANR'd main (Android declares ANR at 5s) blocks longer, so 8s
        // fast-fails a genuinely unresponsive main without false-tripping a
        // legitimately slow (but progressing) draw.
        private const val MAIN_SYNC_TIMEOUT_MS = 8_000L

        // Ceiling for ActivityScenario.close() teardown (its moveToState does an
        // unbounded waitForIdleSync). Normal close is sub-second.
        private const val SCENARIO_CLOSE_TIMEOUT_MS = 5_000L

        // Ceiling for ActivityScenario.launch() (task #370). launch() blocks
        // until RESUMED via an unbounded idle-wait; a page that ANRs at first
        // composition hangs it ~100s (silent). 45s separates a genuine ANR
        // launch (100s+) from a slow-but-progressing launch on a degraded runner
        // (~15-25s), and keeps a wedged case well under the 150s per-case
        // backstop so it fast-fails instead of tipping into a tail-drop.
        private const val SCENARIO_LAUNCH_TIMEOUT_MS = 45_000L

        // Hard per-case backstop. With every unbounded waitForIdleSync path now
        // bounded (idle waits, onActivity, close — task #362/#364), no case
        // hangs forever, so this is a generous ceiling for legitimately-slow 3x
        // captures, not a hang guard: the heaviest never-idle screens (e.g.
        // components.thread.files.list, whose async thumbnails never idle) still
        // run ~90-100s at 3x density, so keep well above that. It only trips on
        // a genuinely stuck case, failing that case alone instead of the shard.
        private const val PER_CASE_TIMEOUT_SECONDS = 150L
    }
}
