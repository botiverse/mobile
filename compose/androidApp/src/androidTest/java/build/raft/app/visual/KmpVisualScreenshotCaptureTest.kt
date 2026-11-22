package build.raft.app.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.app.Activity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.WindowInsetsController
import android.view.WindowManager
import build.raft.app.runtimeFormVisualPicker
import build.raft.app.shared.RaftCmpActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.VisualCaptureLocaleRestoreToken
import build.raft.app.data.applyVisualCaptureLocale
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.parseVisualCaptureLocale
import build.raft.app.data.restoreVisualCaptureLocale
import build.raft.app.data.raftSharedPreferenceStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import kotlin.math.roundToInt

class KmpVisualScreenshotCaptureTest {
    private fun visualAttempt(): Int =
        InstrumentationRegistry.getArguments().getString("visualAttempt")?.toIntOrNull() ?: 0

    private fun visualGeneration(): String =
        InstrumentationRegistry.getArguments().getString("visualGeneration").orEmpty()

    private fun visualBootId(): String =
        InstrumentationRegistry.getArguments().getString("visualBootId").orEmpty()

    private var activeCaptureLocale: AppLocale? = null
    private var captureLocaleRestoreToken: VisualCaptureLocaleRestoreToken? = null

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
                lastFullLength = null
                activeCaptureLocale =
                    parseVisualCaptureLocale(
                        InstrumentationRegistry.getArguments().getString("capture_locale")
                    )
                captureLocaleRestoreToken = activeCaptureLocale?.applyVisualCaptureLocale()
                activeCaptureLocale?.let { locale ->
                    Log.i(
                        VISUAL_LOG_TAG,
                        "capture_locale applied: method=${description.methodName} " +
                            "appLocale=${locale.languageTag} " +
                            "stringDescLocale=${locale.kmmResourceLocaleTag}"
                    )
                }
                // Log the display/viewport condition up front (task #367): a
                // case that later renders blank should carry the exact
                // width/height/density it ran under, so a CI-only failure can be
                // told apart from a geometry difference.
                val dm = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
                Log.i(
                    VISUAL_LOG_TAG,
                    "CASE_START ${description.methodName} display=${dm.widthPixels}x${dm.heightPixels} " +
                        "densityDpi=${dm.densityDpi} attempt=${visualAttempt()} generation=${visualGeneration()}"
                )
                recordTiming("START ${description.methodName}")
            }

            override fun succeeded(description: Description) {
                val durationMs = SystemClock.uptimeMillis() - startUptimeMs
                Log.i(
                    VISUAL_LOG_TAG,
                    "CASE_END ${description.methodName} status=OK durationMs=$durationMs " +
                        "attempt=${visualAttempt()} generation=${visualGeneration()}"
                )
                recordTiming("OK ${description.methodName} durationMs=$durationMs")
            }

            override fun failed(e: Throwable, description: Description) {
                val durationMs = SystemClock.uptimeMillis() - startUptimeMs
                Log.w(
                    VISUAL_LOG_TAG,
                    "CASE_END ${description.methodName} status=FAIL durationMs=$durationMs " +
                        "attempt=${visualAttempt()} generation=${visualGeneration()} " +
                        "error=${e.javaClass.simpleName}: ${e.message}"
                )
                recordTiming("FAIL ${description.methodName} durationMs=$durationMs error=${e.javaClass.simpleName}")
                currentCaseId?.let { writeExceptionFailureArtifact(it, e) }
            }

            override fun finished(description: Description) {
                captureLocaleRestoreToken?.let(::restoreVisualCaptureLocale)
                captureLocaleRestoreToken = null
                activeCaptureLocale = null
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

    private var lastCaptureMethod: String? = null
    private var lastCaptureCompositedWindowCount = 0
    private var lastStableFrameCount = 0
    private var lastReadyTokenObserved = false
    private var lastReadyTokenRequired = false


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
    fun captureActivityTabAll() {
        // Activity is the second bottom tab (artin 2026-10-20): main-tab head, the segment
        // strip (All / Unread / Mentions) and the bottom bar with the Activity badge.
        captureKmpComponentCase(
            caseId = "screens.activity-tab.all",
            pageName = "compose_activity_tab_visual",
            pageData = JSONObject().put("segment", "all"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureAndroidFloatingTabbarStates() {
        captureKmpComponentCase(
            caseId = "components.navigation.tabbar.android-floating",
            pageName = "compose_android_floating_tabbar_visual",
            pageData = JSONObject().put("slockNativeTabBarRenderer", "androidFloating"),
            crop = ANDROID_FLOATING_TABBAR_CROP
        )
        captureKmpComponentCase(
            caseId = "components.navigation.tabbar.android-floating.tasks-selected",
            pageName = "compose_android_floating_tabbar_visual",
            pageData =
                JSONObject()
                    .put("slockNativeTabBarRenderer", "androidFloating")
                    .put("selectedKey", "tasks"),
            crop = ANDROID_FLOATING_TABBAR_CROP
        )
        captureKmpComponentCase(
            caseId = "components.navigation.tabbar.android-floating.disabled",
            pageName = "compose_android_floating_tabbar_visual",
            pageData =
                JSONObject()
                    .put("slockNativeTabBarRenderer", "androidFloating")
                    .put("disabledKey", "more"),
            crop = ANDROID_FLOATING_TABBAR_CROP
        )
        captureKmpComponentCase(
            caseId = "components.navigation.tabbar.android-floating.safe-area",
            pageName = "compose_android_floating_tabbar_visual",
            pageData =
                JSONObject()
                    .put("slockNativeTabBarRenderer", "androidFloating")
                    .put("bottomSafeAreaDp", 24),
            crop = ANDROID_FLOATING_TABBAR_SAFE_AREA_CROP
        )
    }

    @Test
    fun captureLucideCatalogAtlas() {
        captureKmpComponentCase(
            caseId = "components.icons.lucide.catalog-atlas",
            pageName = "compose_lucide_catalog_atlas_visual",
            crop = LUCIDE_CATALOG_ATLAS_CROP,
        )
    }

    @Test
    fun captureLucideStateAtlas() {
        captureKmpComponentCase(
            caseId = "components.icons.lucide.state-atlas",
            pageName = "compose_lucide_state_atlas_visual",
            crop = LUCIDE_STATE_ATLAS_CROP,
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
    fun captureUiCardStates() {
        captureKmpComponentCase(
            caseId = "components.ui.card.states",
            pageName = "compose_ui_card_visual",
            crop = UI_CARD_CROP
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
    fun captureUiTypographySpecimen() {
        captureKmpComponentCase(
            caseId = "components.ui.typography.specimen",
            pageName = "compose_ui_typography_specimen_visual",
            crop = UI_TYPOGRAPHY_SPECIMEN_CROP
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
    fun captureUiSlugInputStates() {
        captureKmpComponentCase(
            caseId = "components.ui.slug-input.states",
            pageName = "compose_ui_slug_input_visual",
            crop = UI_SLUG_INPUT_CROP
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
        // TESTBOX_ELIGIBLE: library.raft-ui.segmented-control.states
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
    fun captureThreadComposerEmpty() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.empty",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-empty")
                    .put("visualFixture", "empty"),
            crop = THREAD_COMPOSER_EMPTY_CROP
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
            crop = THREAD_COMPOSER_TWO_LINE_CROP
        )
    }

    @Test
    fun captureThreadComposerPendingMentionActions() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.pending-mention-actions",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-pending-mention-actions")
                    .put("visualFixture", "pending_mention_actions"),
            crop = THREAD_COMPOSER_PENDING_MENTION_ACTIONS_CROP
        )
    }

    @Test
    fun captureThreadComposerPendingMentionBatchPartial() {
        captureKmpComponentCase(
            caseId = "components.thread.composer.pending-mention-batch-partial",
            pageName = "compose_thread_composer_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer-pending-mention-batch-partial")
                    .put("visualFixture", "pending_mention_actions_batch_partial")
                    .put("androidVisualMentionDrawerStartsClosed", true),
            crop = THREAD_COMPOSER_MODAL_VIEWPORT_CROP,
            settleMs = 2_000,
            beforeScreenshot = ::openPendingMentionDrawer,
            captureAppWindowSurface = true
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
            crop = THREAD_COMPOSER_TWO_LINE_CROP
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
            crop = THREAD_COMPOSER_MEMBER_SUGGESTIONS_CROP
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
            crop = THREAD_COMPOSER_IMAGE_PREVIEW_CROP,
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
    fun captureFilePreviewImageEditor() {
        captureKmpComponentCase(
            caseId = "components.file-preview.image-editor",
            pageName = "compose_file_preview_editor_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-file-preview-image-editor")
                    .put("visualFixture", "pencil_options")
                    .put("safeAreaBottom", "24")
                    .put("slockHostExcludesNavigationBar", false),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 1_500,
        )
    }

    @Test
    fun captureFilePreviewImageActions() {
        captureKmpComponentCase(
            caseId = "components.file-preview.image-actions",
            pageName = "compose_file_preview_editor_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-file-preview-image-actions")
                    .put("visualFixture", "action_bar")
                    .put("safeAreaBottom", "24")
                    .put("slockHostExcludesNavigationBar", false),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 1_500,
        )
    }

    @Test
    fun captureThreadFilesList() {
        captureKmpComponentCase(
            caseId = "components.thread.files.list",
            pageName = "compose_thread_files_visual",
            crop = THREAD_FILES_LIST_CROP,
            settleMs = 1_500,
            beforeScreenshot = { dismissCompatibilityDialog() }
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
                    // The default row plus web THREAD_MESSAGE_FIXTURE's linked task #10.
                    .put("visualFixture", "message_row"),
            crop = THREAD_MESSAGE_ROW_CROP,
            requireReactionAtlas = true,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message.row", "visual-thread-message-row", widthDp = 374, insetDp = 16) }
        )
    }

    @Test
    fun captureThreadMessageBodyFontLarge() {
        captureKmpComponentCase(
            caseId = "components.thread.message.font-lg",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-font-lg")
                    .put("visualFixture", "default")
                    .put("messageBodyFontSize", "lg"),
            crop = THREAD_MESSAGE_ROW_CROP,
            requireReactionAtlas = true,
        )
    }

    @Test
    fun captureThreadMessageSavedBadge() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.saved-badge",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-saved")
                    .put("visualFixture", "saved"),
            crop = THREAD_MESSAGE_ROW_SAVED_CROP,
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
            crop = THREAD_MESSAGE_ROW_DELETED_HUMAN_CROP,
            // The row carries a reaction: wait for the emoji atlas like message.row, so the
            // capture never races its load (both cases crashed mid-capture without it).
            requireReactionAtlas = true,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.deleted-human", "visual-thread-message-row-deleted-human", widthDp = 374, insetDp = 16) }
        )
    }

    /** Task #12: inline image attachment frame (raft-ui MessageImageGallery, lone tall image). */
    @Test
    fun captureThreadMessageRowInlineImage() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.inline-image",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-inline-image")
                    .put("visualFixture", "inline_image"),
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
    fun captureThreadForwardModal() {
        captureKmpComponentCase(
            caseId = "components.thread.forward-modal",
            pageName = "compose_thread_forward_modal_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-forward-modal"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
        )
    }

    @Test
    fun captureThreadForwardModalScrollProbe() {
        val caseId = "components.thread.forward-modal-scroll-probe"
        currentCaseId = caseId
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", "compose_thread_forward_modal_visual")
                .putExtra(
                    "pageData",
                    withVisualCaptureParams(
                        JSONObject()
                            .put("instanceId", "visual-thread-forward-modal-scroll-probe")
                            .put("fixture", "scroll_probe")
                    ).toString()
                )
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        val scenario = ActivityScenario.launch<RaftCmpActivity>(intent)
        finishLeakedPredecessorActivities()
        var before: Bitmap? = null
        var afterSwipe: Bitmap? = null
        var afterTap: Bitmap? = null
        var artifact: Bitmap? = null
        try {
            onActivityNoIdle { applyVisualFullscreen(it) }
            waitForIdleBounded()
            Thread.sleep(1_500)

            val beforeBitmap = captureActivityScreenshot(caseId)
            before = beforeBitmap

            swipeDisplayFraction(startYFraction = 0.82f, endYFraction = 0.43f)
            waitForIdleBounded()
            Thread.sleep(800)
            val afterSwipeBitmap = captureActivityScreenshot(caseId)
            afterSwipe = afterSwipeBitmap
            val scrollChangedRatio = beforeBitmap.changedPixelRatio(afterSwipeBitmap)
            Log.i(
                VISUAL_LOG_TAG,
                "FORWARD_SCROLL_PROBE phase=swipe changedRatio=$scrollChangedRatio"
            )
            assertTrue(
                "Forward recipient LazyColumn did not move after a real vertical swipe; changedRatio=$scrollChangedRatio",
                scrollChangedRatio >= FORWARD_PROBE_MIN_SCROLL_CHANGED_RATIO
            )
            artifact = captureStableVisualBitmap(context, caseId, FULL_VIEWPORT_CROP)

            tapDisplayFraction(xFraction = 0.50f, yFraction = 0.68f)
            waitForIdleBounded()
            Thread.sleep(500)
            val afterTapBitmap = captureActivityScreenshot(caseId)
            afterTap = afterTapBitmap
            val tapChangedRatio = afterSwipeBitmap.changedPixelRatio(afterTapBitmap)
            Log.i(
                VISUAL_LOG_TAG,
                "FORWARD_SCROLL_PROBE phase=tap changedRatio=$tapChangedRatio"
            )
            assertTrue(
                "A visible recipient row must remain tappable after scrolling; changedRatio=$tapChangedRatio",
                tapChangedRatio >= FORWARD_PROBE_MIN_TAP_CHANGED_RATIO
            )

            // This probe owns its interaction sequence instead of going
            // through captureKmpComponentCase. Persist the stable post-swipe
            // frame so the artifact visibly proves the scrolled row geometry;
            // the post-scroll tap assertion above remains a separate gate.
            writeSuccessfulVisualArtifacts(
                context = context,
                caseId = caseId,
                bitmap = requireNotNull(artifact),
                crop = FULL_VIEWPORT_CROP,
                routePageName = "compose_thread_forward_modal_visual"
            )
        } finally {
            before?.recycle()
            afterSwipe?.recycle()
            afterTap?.recycle()
            artifact?.recycle()
            closeScenarioBounded(scenario)
        }
    }

    private fun swipeDisplayFraction(startYFraction: Float, endYFraction: Float) {
        awaitFixtureIsActiveInputWindow("Swipe target fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * startYFraction
        val endY = metrics.heightPixels * endYFraction
        val downTime = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0),
            true
        )
        repeat(FORWARD_PROBE_SWIPE_STEPS) { step ->
            val fraction = (step + 1f) / FORWARD_PROBE_SWIPE_STEPS
            instrumentation.uiAutomation.injectInputEvent(
                MotionEvent.obtain(
                    downTime,
                    downTime + (FORWARD_PROBE_SWIPE_DURATION_MS * fraction).toLong(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    startY + (endY - startY) * fraction,
                    0
                ),
                true
            )
        }
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(
                downTime,
                downTime + FORWARD_PROBE_SWIPE_DURATION_MS,
                MotionEvent.ACTION_UP,
                x,
                endY,
                0
            ),
            true
        )
    }

    private fun tapDisplayFraction(xFraction: Float, yFraction: Float) {
        awaitFixtureIsActiveInputWindow("Tap target fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * xFraction
        val y = metrics.heightPixels * yFraction
        val downTime = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0),
            true
        )
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0),
            true
        )
    }

    private fun Bitmap.changedPixelRatio(other: Bitmap): Float {
        require(width == other.width && height == other.height) {
            "Forward scroll probe bitmap size changed: ${width}x$height vs ${other.width}x${other.height}"
        }
        val startY = (height * FORWARD_PROBE_COMPARE_TOP_FRACTION).toInt()
        val endY = (height * FORWARD_PROBE_COMPARE_BOTTOM_FRACTION).toInt()
        var sampled = 0
        var changed = 0
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < width) {
                val before = getPixel(x, y)
                val after = other.getPixel(x, y)
                val distance =
                    kotlin.math.abs(Color.red(before) - Color.red(after)) +
                        kotlin.math.abs(Color.green(before) - Color.green(after)) +
                        kotlin.math.abs(Color.blue(before) - Color.blue(after))
                if (distance >= FORWARD_PROBE_MIN_COLOR_DISTANCE) changed++
                sampled++
                x += FORWARD_PROBE_PIXEL_STRIDE
            }
            y += FORWARD_PROBE_PIXEL_STRIDE
        }
        return if (sampled == 0) 0f else changed.toFloat() / sampled.toFloat()
    }

    private fun Bitmap.countThreadChildActionProbeSamples(
        red: Int,
        green: Int,
        blue: Int
    ): Int {
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                if (
                    kotlin.math.abs(Color.red(pixel) - red) <= THREAD_CHILD_ACTION_PROBE_TOLERANCE &&
                    kotlin.math.abs(Color.green(pixel) - green) <= THREAD_CHILD_ACTION_PROBE_TOLERANCE &&
                    kotlin.math.abs(Color.blue(pixel) - blue) <= THREAD_CHILD_ACTION_PROBE_TOLERANCE
                ) {
                    samples++
                }
                x += FORWARD_PROBE_PIXEL_STRIDE
            }
            y += FORWARD_PROBE_PIXEL_STRIDE
        }
        return samples
    }

    private fun Bitmap.countThreadOpenProbeYellowSamplesInTopBand(): Int {
        val endY = (height * THREAD_OPEN_PROBE_TOP_BAND_FRACTION).toInt()
        var samples = 0
        var y = 0
        while (y < endY) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                if (
                    kotlin.math.abs(Color.red(pixel) - 0xFF) <= FORWARD_PROBE_YELLOW_TOLERANCE &&
                    kotlin.math.abs(Color.green(pixel) - 0xD4) <= FORWARD_PROBE_YELLOW_TOLERANCE &&
                    kotlin.math.abs(Color.blue(pixel) - 0x40) <= FORWARD_PROBE_YELLOW_TOLERANCE
                ) {
                    samples++
                }
                x += FORWARD_PROBE_PIXEL_STRIDE
            }
            y += FORWARD_PROBE_PIXEL_STRIDE
        }
        return samples
    }

    private fun Bitmap.fullBitmapChangedPixelRatio(other: Bitmap): Float {
        require(width == other.width && height == other.height) {
            "Bitmap size changed: ${width}x$height vs ${other.width}x${other.height}"
        }
        var sampled = 0
        var changed = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val before = getPixel(x, y)
                val after = other.getPixel(x, y)
                val distance =
                    kotlin.math.abs(Color.red(before) - Color.red(after)) +
                        kotlin.math.abs(Color.green(before) - Color.green(after)) +
                        kotlin.math.abs(Color.blue(before) - Color.blue(after))
                if (distance >= FORWARD_PROBE_MIN_COLOR_DISTANCE) changed++
                sampled++
                x += FORWARD_PROBE_PIXEL_STRIDE
            }
            y += FORWARD_PROBE_PIXEL_STRIDE
        }
        return if (sampled == 0) 0f else changed.toFloat() / sampled.toFloat()
    }

    @Test
    fun captureThreadCommentAnchor() {
        captureKmpComponentCase(
            caseId = "components.thread.comment-anchor",
            pageName = "compose_thread_comment_anchor_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-comment-anchor"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
        )
    }

    @Test
    fun captureThreadForwardBundle() {
        captureKmpComponentCase(
            caseId = "components.thread.forward-bundle",
            pageName = "compose_thread_forward_bundle_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-forward-bundle"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
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
        // The generated share image (Copy / Download), not the dialog: a phone-width card.
        captureThreadShareImageCase(caseId = "components.thread.message-share.preview", cardWidthDp = 390)
    }

    /**
     * The share image for the `share_image` message, [cardWidthDp] wide, cropped to the image
     * the host draws pixel for pixel at the top left. A card wider than the pinned 390dp screen
     * widens the display for the capture (as full-length cases grow it) and restores it after.
     */
    private fun captureThreadShareImageCase(caseId: String, cardWidthDp: Int) {
        val instanceId = "visual-thread-message-share-image-$cardWidthDp"
        var baseDisplay: Pair<Int, Int>? = null
        val widen = cardWidthDp > 390
        val widenDisplay: () -> Unit = {
            val base = currentDisplaySizePx().also { baseDisplay = it }
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            resizeDisplayWidth(caseId, (cardWidthDp * density).roundToInt(), base.second)
        }
        val restoreDisplay: () -> Unit = {
            baseDisplay?.let { restoreDisplaySize(caseId, it) }
        }
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", instanceId)
                    .put("visualFixture", "share_image")
                    .put("shareImageWidth", cardWidthDp),
            crop = FULL_VIEWPORT_CROP,
            beforeScreenshot = widenDisplay.takeIf { widen },
            afterScreenshot = restoreDisplay.takeIf { widen },
            cropOverride = { awaitThreadRowContentCrop(caseId, instanceId, widthDp = cardWidthDp) }
        )
    }

    @Test
    fun captureThreadForwardBundlePage() {
        captureKmpComponentCase(
            caseId = "components.thread.forward-bundle.page",
            pageName = "compose_thread_forward_bundle_page",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-forward-bundle-page")
                    .put("visualFixture", "forward_bundle"),
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
            crop = THREAD_MESSAGE_ROW_RICH_CONTENT_CROP,
            // The row carries a reaction: wait for the emoji atlas like message.row, so the
            // capture never races its load (both cases crashed mid-capture without it).
            requireReactionAtlas = true,
            // web crops the 342px row element to its content height (component-bounds).
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.rich-content", "visual-thread-message-row-rich", widthDp = 342) }
        )
    }

    @Test
    fun captureThreadMessageRowInlineReplies() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.inline-replies",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-inline-replies")
                    .put("visualFixture", "inline_replies"),
            crop = FULL_VIEWPORT_CROP
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
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.long-inline-code", "visual-thread-message-row-long-inline-code", widthDp = 390) }
        )
    }

    @Test
    fun captureThreadMessageRowGfmTaskListShort() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.gfm-task-list-short",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-gfm-task-list-short")
                    .put("visualFixture", "gfm_task_list_short"),
            crop = FULL_VIEWPORT_CROP,
            // This route is the regression consumer for task #211.  A native
            // CALL_KOTLIN fallback can still produce a rich-looking PNG and
            // therefore must not be admitted by the generic colour/stability
            // checks alone.
            readyToken = "thread_visual_ready fixtureKey=gfm_task_list_short"
        )
    }

    @Test
    fun captureThreadMessageRowMdLinkRef() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-link-ref",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-link-ref")
                    .put("visualFixture", "md_link_ref"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-link-ref", "visual-thread-message-row-md-link-ref", widthDp = 390) }
        )
    }

    @Test
    fun captureThreadMessageRowMdWrapSlice1() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-wrap-slice1",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-wrap-slice1")
                    .put("visualFixture", "md_wrap_slice1"),
            crop = THREAD_MESSAGE_ROW_MD_WRAP_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-wrap-slice1", "visual-thread-message-row-md-wrap-slice1", widthDp = 260) }
        )
    }

    @Test
    fun captureThreadMessageRowMdWrapStatus606() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-wrap-status606",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-wrap-status606")
                    .put("visualFixture", "md_wrap_status606"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-wrap-status606", "visual-thread-message-row-md-wrap-status606", widthDp = 390) }
        )
    }

    @Test
    fun captureThreadMessageRowMdLatestRelease() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-latest-release",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-latest-release")
                    .put("visualFixture", "md_latest_release"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-latest-release", "visual-thread-message-row-md-latest-release", widthDp = 390) }
        )
    }

    @Test
    fun captureThreadMessageRowMdCodeBlock() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-code-block",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-code-block")
                    .put("visualFixture", "md_code_block"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadMessageRowMdMermaidFlowchart() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-mermaid-flowchart",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-mermaid-flowchart")
                    .put("visualFixture", "md_mermaid_flowchart"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadMessageRowMdMermaidInvalidFallback() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-mermaid-invalid-fallback",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-mermaid-invalid-fallback")
                    .put("visualFixture", "md_mermaid_invalid"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadMessageRowMdTable() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-table",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-table")
                    .put("visualFixture", "md_table"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadMessageRowMdBlockquote() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-blockquote",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-blockquote")
                    .put("visualFixture", "md_blockquote"),
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP
        )
    }

    @Test
    fun captureThreadMessageRowMdWrapAdjacent() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-wrap-adjacent",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-wrap-adjacent")
                    .put("visualFixture", "md_wrap_adjacent"),
            // Standard 390dp width: the '#31 / #521' adjacent pair must stay on
            // one line (task #60 acceptance), so no narrow wrap column here.
            crop = THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-wrap-adjacent", "visual-thread-message-row-md-wrap-adjacent", widthDp = 390) }
        )
    }

    @Test
    fun captureThreadMessageRowMdWrapClarify() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-wrap-clarify",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-wrap-clarify")
                    .put("visualFixture", "md_wrap_clarify"),
            crop = THREAD_MESSAGE_ROW_MD_WRAP_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-wrap-clarify", "visual-thread-message-row-md-wrap-clarify", widthDp = 260) }
        )
    }

    @Test
    fun captureThreadMessageRowMdWrapTask607() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.md-wrap-task607",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-md-wrap-task607")
                    .put("visualFixture", "md_wrap_task607"),
            crop = THREAD_MESSAGE_ROW_MD_WRAP_CROP,
            cropOverride = { awaitThreadRowContentCrop("components.thread.message-row.md-wrap-task607", "visual-thread-message-row-md-wrap-task607", widthDp = 260) }
        )
    }

    @Test
    fun captureThreadMessageRowPermalinkChips() {
        captureKmpComponentCase(
            caseId = "components.thread.message-row.permalink-chips",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row-permalink-chips")
                    .put("visualFixture", "permalink_chips"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadActionCardCatalog() {
        captureKmpComponentCase(
            caseId = "components.thread.action-card.catalog",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-action-card-catalog")
                    .put("visualFixture", "action_card_catalog"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureThreadApproveLoginActionCardPending() {
        captureKmpComponentCase(
            caseId = "components.thread.action-card.approve-login.pending",
            pageName = "compose_thread_message_row_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-action-card-approve-login")
                    .put("visualFixture", "approve_login_action_card"),
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
    fun captureThreadDmAgentActivityHeader() {
        captureKmpComponentCase(
            caseId = "components.thread.dm-agent-header.activity",
            pageName = "compose_thread_dm_agent_activity_header_visual",
            crop = THREAD_HEADER_COMPONENT_CROP
        )
    }

    @Test
    fun captureThreadActivityMuteStates() {
        captureKmpComponentCase(
            caseId = "components.thread.activity-mute.states",
            pageName = "compose_thread_activity_mute_visual",
            crop = FULL_VIEWPORT_CROP
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
    fun captureHomeTitlebarLoadingAndErrorIdentity() {
        captureKmpComponentCase(
            caseId = "components.home.titlebar.loading-fallback",
            pageName = "compose_home_titlebar_loading_visual",
            crop = FULL_VIEWPORT_CROP
        )
        captureKmpComponentCase(
            caseId = "components.home.titlebar.error-removed-avatar-fallback",
            pageName = "compose_home_titlebar_error_visual",
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
    fun captureHomeAppUpdateBanner() {
        captureKmpComponentCase(
            caseId = "components.home.app-update-banner",
            pageName = "compose_home_app_update_banner_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureAppUpdateDetails() {
        captureKmpComponentCase(
            caseId = "screens.app-update.details",
            pageName = "compose_app_update_details_visual",
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
    fun captureHomeSearchHomeDefault() {
        captureKmpComponentCase(
            caseId = "components.home.search.home-default",
            pageName = "compose_home_search_home_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500,
        )
    }

    @Test
    fun captureHomeSearchHomeEdit() {
        captureKmpComponentCase(
            caseId = "components.home.search.home-edit",
            pageName = "compose_home_search_home_edit_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500,
        )
    }

    @Test
    fun captureHomeSearchChannelPinMenu() {
        captureHomeSearchEntityPinMenu(
            caseId = "components.home.search.channel-pin-menu",
            pageName = "compose_home_search_channel_pin_visual",
            entityLabel = "Channel"
        )
    }

    // The first entity row under the search header: filters on one line since #4807 (row at
    // 214dp), then the Free plan's 30-day history notice above the results since #4879 pushes
    // it to 277dp (its avatar spans ~264-291dp). The Pin item opens 30dp below the press.
    private val SEARCH_PIN_ROW_Y_DP = 277f

    private fun captureHomeSearchEntityPinMenu(
        caseId: String,
        pageName: String,
        entityLabel: String
    ) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = pageName,
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500,
            beforeScreenshot = {
                var before: Bitmap? = null
                var menuOpen: Bitmap? = null
                var selected: Bitmap? = null
                try {
                    dismissCompatibilityDialog()
                    waitForIdleBounded()
                    awaitFixtureIsActiveInputWindow("Search fixture")
                    before = captureAppWindowsScreenshot("$caseId.before")
                    val yellowBefore = before.countThreadOpenProbeYellowSamplesInTopBand()
                    val cyanBefore = before.countThreadChildActionProbeSamples(0x00, 0xE5, 0xFF)
                    val duplicateBefore = before.countThreadChildActionProbeSamples(0xFF, 0x00, 0x00)
                    menuOpen = captureAfterLongPressRendered(
                        label = "$caseId.open",
                        before = before,
                        longPress = { longPressDisplayDp(x = 180f, y = SEARCH_PIN_ROW_Y_DP) },
                        rendered = { _, _, changedRatio ->
                            changedRatio >= SEARCH_PIN_MENU_MIN_CHANGED_RATIO
                        },
                    )
                    val yellowMenuOpen = menuOpen.countThreadOpenProbeYellowSamplesInTopBand()
                    val changedRatio = before.fullBitmapChangedPixelRatio(menuOpen)
                    assertTrue(
                        "Long-pressing an active $entityLabel search result must render the Pin menu; changedRatio=$changedRatio",
                        changedRatio >= SEARCH_PIN_MENU_MIN_CHANGED_RATIO
                    )
                    assertTrue(
                        "Long-pressing a $entityLabel row must not open it; yellowBefore=$yellowBefore yellowMenuOpen=$yellowMenuOpen",
                        yellowMenuOpen < yellowBefore + THREAD_TAP_PROBE_MIN_ADDED_YELLOW_SAMPLES
                    )

                    tapDisplayDp(x = 240f, y = SEARCH_PIN_ROW_Y_DP + 30f)
                    waitForIdleBounded()
                    Thread.sleep(300)
                    selected = captureActivityScreenshot("$caseId.selected")
                    val yellowSelected = selected.countThreadOpenProbeYellowSamplesInTopBand()
                    val cyanSelected = selected.countThreadChildActionProbeSamples(0x00, 0xE5, 0xFF)
                    val duplicateSelected = selected.countThreadChildActionProbeSamples(0xFF, 0x00, 0x00)
                    assertTrue(
                        "Selecting the real Pin menu item must not open the $entityLabel; yellowBefore=$yellowBefore yellowSelected=$yellowSelected",
                        yellowSelected < yellowBefore + THREAD_TAP_PROBE_MIN_ADDED_YELLOW_SAMPLES
                    )
                    assertTrue(
                        "Selecting the real Pin menu item must toggle exactly once; cyanBefore=$cyanBefore cyanSelected=$cyanSelected",
                        cyanSelected >= cyanBefore + THREAD_CHILD_ACTION_PROBE_MIN_ADDED_SAMPLES
                    )
                    assertTrue(
                        "Selecting the real Pin menu item must not toggle twice; duplicateBefore=$duplicateBefore duplicateSelected=$duplicateSelected",
                        duplicateSelected < duplicateBefore + THREAD_CHILD_ACTION_PROBE_MIN_ADDED_SAMPLES
                    )

                    Thread.sleep(1_000)
                    waitForIdleBounded()
                    longPressDisplayDp(x = 180f, y = SEARCH_PIN_ROW_Y_DP)
                    waitForIdleBounded()
                    Thread.sleep(500)
                } finally {
                    before?.recycle()
                    menuOpen?.recycle()
                    selected?.recycle()
                }
            }
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
    fun captureHomeSearchScopeDropdown() {
        captureKmpComponentCase(
            caseId = "components.home.search.scope-dropdown",
            pageName = "compose_home_search_scope_dropdown_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeSearchError() {
        captureKmpComponentCase(
            caseId = "components.home.search.error",
            pageName = "compose_home_search_error_visual",
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
    fun captureHomeActivityConversationList() {
        // Activity as the web ThreadsInbox list layout (one compact row per
        // conversation, avatar/tile, one-line preview, time, unread count, @ marker, muted bell)
        // with sidebar pins, so the Pinned / All conversations sections render. The web has no
        // matching react case yet; this is Android evidence until the React case lands.
        captureKmpComponentCase(
            caseId = "components.home.activity.conversation-list",
            pageName = "compose_home_activity_conversation_list_visual",
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
    fun captureHomeDmAgentActivityStates() {
        captureKmpComponentCase(
            caseId = "screens.home.dm-agent-activity-states",
            pageName = "compose_home_dm_agent_activity_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeServerSwitcherBadgeStates() {
        captureKmpComponentCase(
            caseId = "screens.home.server-switcher-badge-states",
            pageName = "compose_home_server_switcher_badge_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureWideActivityTask() {
        // Wide lane only (scripts/visual_android_case_filter.py: `screens.wide.*`), on a 1280x800dp
        // display (wm size 2560x1600, density 320), by touch.
        captureKmpComponentCase(
            caseId = "screens.wide.activity-task",
            pageName = "compose_wide_shell_visual",
            pageData = JSONObject().put("scene", "activity-task"),
            crop = WIDE_VIEWPORT_CROP,
            settleMs = 3_000
        )
    }

    @Test
    fun captureWideChannel() {
        // Wide lane only (scripts/visual_android_case_filter.py: `screens.wide.*`), on a 1280x800dp
        // display (wm size 2560x1600, density 320), by touch.
        captureKmpComponentCase(
            caseId = "screens.wide.channel",
            pageName = "compose_wide_shell_visual",
            pageData = JSONObject().put("scene", "channel"),
            crop = WIDE_VIEWPORT_CROP,
            settleMs = 3_000
        )
    }

    @Test
    fun captureWideChannelThread() {
        // Wide lane only (scripts/visual_android_case_filter.py: `screens.wide.*`), on a 1280x800dp
        // display (wm size 2560x1600, density 320), by touch.
        captureKmpComponentCase(
            caseId = "screens.wide.channel-thread",
            pageName = "compose_wide_shell_visual",
            pageData = JSONObject().put("scene", "channel-thread"),
            crop = WIDE_VIEWPORT_CROP,
            settleMs = 3_000
        )
    }

    @Test
    fun captureWideSettings() {
        // Wide lane only (scripts/visual_android_case_filter.py: `screens.wide.*`), on a 1280x800dp
        // display (wm size 2560x1600, density 320), by touch.
        captureKmpComponentCase(
            caseId = "screens.wide.settings",
            pageName = "compose_wide_shell_visual",
            pageData = JSONObject().put("scene", "settings"),
            crop = WIDE_VIEWPORT_CROP,
            settleMs = 3_000
        )
    }

    @Test
    fun captureWideTasksTask() {
        // Wide lane only (scripts/visual_android_case_filter.py: `screens.wide.*`), on a 1280x800dp
        // display (wm size 2560x1600, density 320), by touch.
        captureKmpComponentCase(
            caseId = "screens.wide.tasks-task",
            pageName = "compose_wide_shell_visual",
            pageData = JSONObject().put("scene", "tasks-task"),
            crop = WIDE_VIEWPORT_CROP,
            settleMs = 3_000
        )
    }

    @Test
    fun captureHomeChannelList() {
        captureKmpComponentCase(
            caseId = "screens.home.channel-list",
            pageName = "compose_home_channel_list_visual",
            pageData = JSONObject().put("appearance", "light"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 4_500
        )
    }

    @Test
    fun captureHomeChannelListDark() {
        captureKmpComponentCase(
            caseId = "screens.home.channel-list.dark",
            pageName = "compose_home_channel_list_visual",
            pageData = JSONObject().put("appearance", "dark"),
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
    fun captureMembersCreateAgentDialog() {
        captureKmpComponentCase(
            caseId = "components.members.create-agent.dialog",
            pageName = "compose_create_agent_dialog_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    // React CREATE_AGENT_CASES counterparts (slock packages/web/visual-testing/
    // VisualTestingCases.tsx). State comes from the gated
    // compose_create_agent_state_visual fixture page; `variant` only selects it.
    @Test
    fun captureMembersCreateAgentDialogError() {
        captureMembersCreateAgentStateCase(
            caseId = "components.members.create-agent.dialog-error",
            variant = "error"
        )
    }

    @Test
    fun captureMembersCreateAgentDialogEmpty() {
        captureMembersCreateAgentStateCase(
            caseId = "components.members.create-agent.dialog-empty",
            variant = "empty",
            // A blank initial name is replaced by a random suggestion, so clear
            // the seeded name the way a user would to reach React's empty field.
            beforeScreenshot = { clearEditableTextField("Product-QA-Bot") }
        )
    }

    @Test
    fun captureMembersCreateAgentDialogNoComputer() {
        captureMembersCreateAgentStateCase(
            caseId = "components.members.create-agent.dialog-no-computer",
            variant = "no-computer"
        )
    }



    // Runtime form cases (slock packages/runtime-form/fixtures/visual-cases.v3.json): the generated
    // kitchen sink pages and each runtime's real v3 create form, drawn by RuntimeFormV2Fields from the
    // synced fixtures (compose_runtime_form_visual). Full length: slock sharedCases capture.fullLength.
    @Test
    fun captureRuntimeFormKitchenSinkInputs() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.kitchen-sink.inputs",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.kitchen-sink.inputs"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormKitchenSinkChoices() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.kitchen-sink.choices",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.kitchen-sink.choices"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormBuiltin() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.builtin",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.builtin"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormClaude() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.claude",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.claude"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormCodex() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.codex",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.codex"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormGrok() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.grok",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.grok"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormKimiSdk() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.kimi-sdk",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.kimi-sdk"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureRuntimeFormPi() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.create.pi",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.create.pi"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    // Runtime form picker cases (visual-cases.v3.json `picker`): the form at rest, then the capture
    // opens the named choice field and types the query into its list's search (one screen, not full
    // length: slock sharedCases has no capture.fullLength for them).
    @Test
    fun captureRuntimeFormPickerBuiltinProviderOpen() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.picker.builtin.provider-open",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.picker.builtin.provider-open"),
            crop = FULL_VIEWPORT_CROP,
            beforeScreenshot = { openRuntimeFormPicker("components.runtime-form.picker.builtin.provider-open") }
        )
    }

    @Test
    fun captureRuntimeFormPickerBuiltinProviderSearch() {
        captureKmpComponentCase(
            caseId = "components.runtime-form.picker.builtin.provider-search",
            pageName = "compose_runtime_form_visual",
            pageData = JSONObject().put("caseId", "components.runtime-form.picker.builtin.provider-search"),
            crop = FULL_VIEWPORT_CROP,
            beforeScreenshot = { openRuntimeFormPicker("components.runtime-form.picker.builtin.provider-search") }
        )
    }

    /**
     * Opens the field of [caseId]'s picker (visual-cases.v3.json `picker`, read through
     * [runtimeFormVisualPicker]) as a person does: a tap (accessibility click) on the trigger
     * announced as "<label>[, Required], <value>" (RaftSelectTrigger), then waits for the window the
     * list opens in (RaftCombobox: over ten options, a sheet with a search row) to take input focus.
     * A query is typed into that window's search row (a tap, then `input text`). Web's capture
     * does the same (slock react-provider.spec.ts
     * openRuntimeFormPicker: a click, then the query typed into the combobox).
     */
    /** Logs whether this app holds Play services' FontsProvider (see RaftCmpActivity.onCmpFirstFrame). */
    private fun logRuntimeFormPickerFontsProvider(caseId: String, step: String) {
        // The app process's own record lists the providers it is connected to (a provider dump
        // filtered by name also prints unrelated records, so it cannot say who holds this one).
        val process = readShellCommand("dumpsys activity processes build.raft.app.connectedtest")
        val held = process.lineSequence().any { "fonts.provider.FontsProvider" in it }
        Log.i(VISUAL_LOG_TAG, "$caseId picker: FontsProvider bound by this app ($step)=$held")
    }

    private fun openRuntimeFormPicker(caseId: String) {
        val picker = requireNotNull(runtimeFormVisualPicker(caseId)) { "$caseId has no picker in visual-cases.v3.json" }
        awaitFixtureIsActiveInputWindow("the ${picker.fieldLabel} field")
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun descendants(root: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> {
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > ACCESSIBILITY_TREE_MAX_DEPTH) return
                nodes += node
                for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
            }
            visit(root, 0)
            return nodes
        }
        val deadline = SystemClock.uptimeMillis() + EDITABLE_FIELD_LOOKUP_TIMEOUT_MS
        var formWindowId = -1
        var trigger: android.view.accessibility.AccessibilityNodeInfo? = null
        var seen = emptyList<String>()
        while (trigger == null) {
            val root = uiAutomation.rootInActiveWindow
            formWindowId = root?.windowId ?: -1
            val nodes = descendants(root)
            // The trigger, or the node carrying its name, then its clickable self or ancestor. The
            // drop-down role (a Spinner) wins over the field's label row, which also starts with the label.
            trigger =
                nodes
                    .filter { node ->
                        val name = (node.contentDescription ?: node.text)?.toString()?.trim().orEmpty()
                        name.startsWith("${picker.fieldLabel}, ")
                    }
                    .mapNotNull { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
                    .sortedByDescending { it.className?.toString()?.endsWith("Spinner") == true }
                    .firstOrNull()
                    ?: nodes.firstOrNull { it.isClickable && it.className?.toString()?.endsWith("Spinner") == true }
            seen =
                nodes
                    .mapNotNull { node ->
                        val name = (node.contentDescription ?: node.text)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        if (name == null && !node.isClickable) null else "${node.className?.toString()?.substringAfterLast('.')}${if (node.isClickable) "*" else ""}:${name?.take(40)}"
                    }
                    .take(40)
            if (trigger != null || SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        requireNotNull(trigger) {
            "$caseId: no field trigger announced as \"${picker.fieldLabel}, …\"; window=$formWindowId " +
                "nodes (class, * = clickable: name)=${seen.joinToString(" | ")}"
        }
        if (!trigger.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            // As tapComposeNodeWithText: a tap at the trigger's centre.
            val bounds = android.graphics.Rect()
            trigger.getBoundsInScreen(bounds)
            require(!bounds.isEmpty) { "$caseId: the ${picker.fieldLabel} trigger has empty bounds" }
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            tapDp(bounds.exactCenterX() / density, bounds.exactCenterY() / density)
        }
        Log.i(VISUAL_LOG_TAG, "$caseId picker: opened ${picker.fieldLabel}")
        logRuntimeFormPickerFontsProvider(caseId, "opened")
        var listRoot: android.view.accessibility.AccessibilityNodeInfo? = null
        while (listRoot == null) {
            listRoot =
                uiAutomation.rootInActiveWindow?.takeIf { it.windowId != formWindowId }
                    ?: uiAutomation.windows
                        .mapNotNull { it.root }
                        .lastOrNull { it.windowId != formWindowId && it.packageName == "build.raft.app.connectedtest" }
            if (listRoot != null || SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        requireNotNull(listRoot) { "$caseId: tapping ${picker.fieldLabel} opened no list window" }
        val query = picker.query
        if (query != null) {
            val search =
                requireNotNull(descendants(listRoot).firstOrNull { it.isEditable }) {
                    "$caseId: the ${picker.fieldLabel} list has no search row to type \"$query\" into"
                }
            // A tap on the search row (it takes focus), then the text as key events, as a person types it.
            val bounds = android.graphics.Rect()
            search.getBoundsInScreen(bounds)
            require(!bounds.isEmpty) { "$caseId: the ${picker.fieldLabel} search row has empty bounds" }
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            Log.i(VISUAL_LOG_TAG, "$caseId picker: tapping the search row at $bounds")
            tapDp(bounds.exactCenterX() / density, bounds.exactCenterY() / density)
            Thread.sleep(300)
            Log.i(VISUAL_LOG_TAG, "$caseId picker: typing \"$query\"")
            require(query.all { it.isLetterOrDigit() || it == '-' }) { "$caseId: query \"$query\" needs escaping for input text" }
            runShellCommand("input text $query")
            var shown: String? = null
            while (SystemClock.uptimeMillis() < deadline + EDITABLE_FIELD_LOOKUP_TIMEOUT_MS) {
                val root = uiAutomation.rootInActiveWindow ?: listRoot
                shown = descendants(root).firstOrNull { it.isEditable }?.text?.toString()
                if (shown == query) break
                Thread.sleep(250)
            }
            Log.i(VISUAL_LOG_TAG, "$caseId picker: search row shows \"$shown\"")
            // The capture app must not hold Play services' FontsProvider: when Play services restarts
            // itself, ActivityManager kills every process holding it (run 44924992344). Visual pages
            // skip the WebView warmup for that (RaftCmpActivity.onCmpFirstFrame).
            logRuntimeFormPickerFontsProvider(caseId, "after typing")
            require(shown == query) { "$caseId: the ${picker.fieldLabel} search row shows \"$shown\" after typing \"$query\"" }
        }
        // The sheet slides in (RaftSheetWindow); the stable-frame capture waits for the rest.
        Thread.sleep(800)
    }

    private fun captureMembersCreateAgentStateCase(
        caseId: String,
        variant: String,
        beforeScreenshot: (() -> Unit)? = null
    ) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_create_agent_state_visual",
            pageData = JSONObject().put("variant", variant),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500,
            beforeScreenshot = beforeScreenshot
        )
    }

    /** Clear the editable field currently showing [text] through its accessibility SetText action. */
    private fun clearEditableTextField(text: String) {
        // As the tap helpers do: a sleeping display, the keyguard or a system dialog (a launcher
        // ANR) takes the active window, and the fixture's field is then not in the tree read here.
        awaitFixtureIsActiveInputWindow("the field showing \"$text\"")
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + EDITABLE_FIELD_LOOKUP_TIMEOUT_MS
        var field: android.view.accessibility.AccessibilityNodeInfo? = null
        while (field == null) {
            val roots = listOfNotNull(uiAutomation.rootInActiveWindow) + uiAutomation.windows.mapNotNull { it.root }
            field =
                roots
                    .asSequence()
                    .flatMap { findComposeNodesWithText(it, text).asSequence() }
                    .mapNotNull { node -> generateSequence(node) { it.parent }.firstOrNull { it.isEditable } }
                    .firstOrNull()
            if (field != null || SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        requireNotNull(field) {
            val active = uiAutomation.rootInActiveWindow
            "No editable field showing \"$text\" to clear; activeWindowPackage=${active?.packageName} " +
                "windows=${uiAutomation.windows.size}"
        }
        val cleared =
            field.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        ""
                    )
                }
            )
        require(cleared) { "SetText(\"\") was rejected by the field showing \"$text\"" }
        Thread.sleep(500)
    }

    @Test
    fun captureAnnouncementModalFirstPage() {
        captureKmpComponentCase(
            caseId = "components.announcement-modal.first-page",
            pageName = "compose_announcement_modal_visual",
            pageData = JSONObject().put("pageIndex", 0),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureHomeSidebarRowStates() {
        captureKmpComponentCase(
            caseId = "components.home.sidebar.row-states",
            pageName = "compose_home_sidebar_row_states_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
        )
    }

    @Test
    fun captureMembersRestartDialogRestart() {
        captureKmpComponentCase(
            caseId = "components.members.restart-dialog.restart",
            pageName = "compose_members_restart_dialog_visual",
            pageData = JSONObject().put("action", "restart"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureThreadInlineAudioControls() {
        captureKmpComponentCase(
            caseId = "components.thread.inline-audio.controls",
            pageName = "compose_thread_inline_audio_controls_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
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

    // task #535: Computer-row oracle states (web AgentDetailPanel.tsx blob
    // c549c316acf5 :771-772 / :2053-2084). Expected: computer-offline = gray dot +
    // Offline; computer-missing / no-computer = "No computer assigned", no dot.
    // Android's rendering of these states is task #259; until it lands these
    // captures record the current (wrong) behaviour as the before-frame.
    @Test
    fun captureMembersAgentDetailProfileComputerOfflineScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile.computer-offline",
            tab = "profile",
            memberId = "agent-computer-offline"
        )
    }

    @Test
    fun captureMembersAgentDetailProfileComputerMissingScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile.computer-missing",
            tab = "profile",
            memberId = "agent-computer-missing"
        )
    }

    @Test
    fun captureMembersAgentDetailProfileNoComputerScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile.no-computer",
            tab = "profile",
            memberId = "agent-no-computer"
        )
    }

    // task #537: a machine name that cannot fit one 390dp line, so the wrap /
    // silent-truncate / overflow outcome is observable at all.
    @Test
    fun captureMembersAgentDetailProfileLongMachineNameScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile.long-machine-name",
            tab = "profile",
            memberId = "agent-long-machine"
        )
    }

    // task #259: a bare daemon (isComputer=false) with no computer version:
    // web shows Connected · daemon vX, never a computer version.
    @Test
    fun captureMembersAgentDetailProfileDaemonOnlyScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.profile.daemon-only",
            tab = "profile",
            memberId = "agent-daemon-only"
        )
    }

    // task #261: no server membership row -> serverRole omitted -> role chip hidden.
    @Test
    fun captureMembersAgentDetailProfileNoMembershipScreen() {
        captureMembersAgentDetailStateCase(
            caseId = "screens.members.agent-detail.profile.no-membership",
            state = "no-membership"
        )
    }

    // task #259: computers still loading -> the Computer row must not render yet.
    @Test
    fun captureMembersAgentDetailProfileLoadingStateScreen() {
        captureMembersAgentDetailStateCase(
            caseId = "screens.members.agent-detail.profile.loading-state",
            state = "loading-state"
        )
    }

    // Seeds come from the gated compose_members_agent_detail_state_visual page,
    // not from the production compose_member_detail route's pageData.
    private fun captureMembersAgentDetailStateCase(caseId: String, state: String) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_members_agent_detail_state_visual",
            pageData = JSONObject().put("state", state),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
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

    // slock task #17: the Activity tab with `activity_overview_web_v0` on opens on the per-turn
    // Overview (web AgentActivityOverview). The seed mirrors the web behaviour-test turns.
    @Test
    fun captureMembersAgentDetailActivityOverviewScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.activity.overview",
            tab = "activity",
            memberId = "agent-activity-overview"
        )
    }

    @Test
    fun captureMembersHumanDetailProfileScreen() {
        captureKmpComponentCase(
            caseId = "screens.members.human.profile",
            pageName = "compose_member_detail",
            pageData = memberDetailVisualPageData(
                instanceId = "visual-members-human-profile",
                memberId = "visual-user",
                memberKind = "human"
            ),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    private fun captureMembersAgentDetailScreen(
        caseId: String,
        tab: String,
        memberId: String = "agent-product-ux"
    ) {
        val pageData =
            memberDetailVisualPageData(
                instanceId = "visual-members-agent-$tab-$memberId",
                memberId = memberId,
                memberKind = "agent",
                initialAgentTab = tab
            )
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_member_detail",
            pageData = pageData,
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
    fun captureMembersAgentIssueReport() {
        captureKmpComponentCase(
            caseId = "components.members.agent-issue-report",
            pageName = "compose_members_agent_detail_visual",
            pageData = JSONObject().put("visualMode", "report_issue"),
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
    fun captureChannelSettingsPinnedPanel() {
        captureKmpComponentCase(
            caseId = "components.channel.settings.pinned-panel",
            pageName = "compose_channel_settings_pinned_panel_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureDirectMessageSettingsPanel() {
        captureKmpComponentCase(
            caseId = "components.thread.dm-settings.panel",
            pageName = "compose_dm_settings_panel_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureChannelMembersParityRoster() {
        captureKmpComponentCase(
            caseId = "components.channel.members.parity.roster",
            pageName = "compose_channel_members_parity_visual",
            pageData = JSONObject().put("variant", "roster"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500,
        )
    }

    @Test
    fun captureThreadFollowersParity() {
        captureKmpComponentCase(
            caseId = "components.thread.followers.parity",
            pageName = "compose_thread_followers_visual",
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500,
            // Tap the avatar-chip trigger by its accessibility label, not a fixed point: #4032 moved a
            // reply thread's Search / View in Channel into the ⋯ menu, the chip slid right and the
            // old (265dp, 28dp) tap missed it, leaving a near-blank page (nightlies from 10-31).
            beforeScreenshot = {
                tapComposeNodeWithText(
                    // `thread_followers_count` for the fixture's three agents (it renders zh-CN).
                    texts = listOf("3 个 Agent 正在关注", "3 watching", "3 エージェントがフォロー中"),
                    what = "Thread followers trigger",
                )
                Thread.sleep(500)
            },
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

    // components.settings.page.states, account.provider-linked, and
    // account.password-expanded retired by owner ruling (artin,
    // #wg-nightly-visual-fix 2026-07-23): the structural section-composition
    // differences on these native settings surfaces are intentional and must
    // not be re-verified. Manifest/harness removal in slock #5317.

    @Test
    fun captureSettingsAccountPage() {
        captureKmpSettingsPageCase("components.settings.account.page", "account")
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
    fun captureAgentMigrationLogTable() {
        captureKmpComponentCase(
            caseId = "screens.agent-migration-log.table.phone",
            pageName = "compose_agent_migration_log_visual",
            pageData = JSONObject().put("view", "table"),
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureAgentMigrationLogChart() {
        captureKmpComponentCase(
            caseId = "screens.agent-migration-log.chart.phone",
            pageName = "compose_agent_migration_log_visual",
            crop = FULL_VIEWPORT_CROP
        )
    }

    @Test
    fun captureAgentMigrationLogChartAgent() {
        captureKmpComponentCase(
            caseId = "screens.agent-migration-log.chart.agent.phone",
            pageName = "compose_agent_migration_log_visual",
            pageData = JSONObject().put("agent", "visual-mig-kirby"),
            crop = FULL_VIEWPORT_CROP,
            // The haze is blurred, which a software draw leaves out.
            captureAppWindowSurface = true
        )
    }

    // Settings > Computers is native again (RaftSettingsComputersPage). These
    // cases render its production content from seeded store snapshots through
    // the compose_settings_computers_visual fixture (no network, pinned clock).
    // Ids reuse the retired React/Android pair ids (slock #5009:
    // screens.settings.computers, screens.settings.computer-detail).
    @Test
    fun captureSettingsComputersList() {
        captureSettingsComputersCase("screens.settings.computers", "list")
    }

    @Test
    fun captureSettingsComputersEmpty() {
        captureSettingsComputersCase("screens.settings.computers.empty", "empty")
    }

    @Test
    fun captureSettingsComputersError() {
        captureSettingsComputersCase("screens.settings.computers.error", "error")
    }

    @Test
    fun captureSettingsComputerDetail() {
        captureSettingsComputersCase("screens.settings.computer-detail", "detail")
    }

    private fun captureSettingsComputersCase(caseId: String, fixtureKey: String) {
        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_settings_computers_visual",
            pageData = JSONObject().put("fixtureKey", fixtureKey),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
        )
    }

    // Full-route Settings captures (task #398 / #388). These drive the real
    // Settings destination switch (SettingsAppShellContent) through the
    // seeded-store route entry ASI added (pageName compose_settings_route_visual,
    // #232): an in-memory visual session + seeded Settings repository behind
    // SharedSettingsViewModel, no product snapshots/tokens through pageData.
    // Captured at the normal viewport for now; full-height (long-scroll) is
    // layered on later behind the fullHeight marker once the eager-render hook
    // lands (#388 A).
    @Test
    fun captureSettingsRootPage() {
        captureKmpComponentCase(
            caseId = "components.settings.root.page",
            pageName = "compose_settings_route_visual",
            pageData =
                JSONObject()
                    .put("destination", "home")
                    .put("fixtureKey", "canonical"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    // Administration cases retired: the app renders the WEB settings page in
    // an in-app WebView (SettingsAdministrationPage, Billing pattern), so
    // there is no native admin surface left to compare against React.

    // Connected Apps cases retired: production routes the destination to
    // SettingsMarketplacePage → StandaloneSettingsWebViewPage (Administration/
    // Billing WebView pattern), so these captures exercised the bypassed
    // legacy native SettingsConnectedAppsContent. WebView surfaces cannot be
    // reliably diffed; a WebView-capable provider would return as a NEW case,
    // not by reviving the native fixture.

    // Full-height (long-scroll) variants of the route captures (task #388):
    // whole route in one frame at content height, paired with React's
    // fullPage captures under the shared contract "full-height".

    // components.settings.language-region.page retired by the same owner
    // ruling (artin, 2026-07-23) — composition difference is intentional,
    // never re-verify.

    @Test
    fun captureSettingsAppearancePage() {
        // TESTBOX_ELIGIBLE: components.settings.appearance.page
        captureKmpComponentCase(
            caseId = "components.settings.appearance.page",
            pageName = "compose_settings_route_visual",
            pageData =
                JSONObject()
                    .put("destination", "appearance")
                    .put("fixtureKey", "canonical")
                    .put("fixtureRevision", "settings-appearance-v2")
                    // SHA-256 of the frozen rendered goal:
                    // route=compose_settings_route_visual;destination=appearance;fixture=canonical;
                    // fixtureRevision=settings-appearance-v2;viewport=1170x2532@480
                    .put(
                        "renderedGoalSha256",
                        "bc0d82ab7b79ef4008cefdaf0db0ffdcb8d5daeefbce8911e7614f65ecc28624"
                    ),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500,
            readyToken = "settings_route_visual_ready destination=appearance fixtureKey=canonical"
        )
    }

    @Test
    fun captureSettingsNotificationsPage() {
        captureKmpComponentCase(
            caseId = "components.settings.notifications.page",
            pageName = "compose_settings_route_visual",
            pageData =
                JSONObject()
                    .put("destination", "notifications")
                    .put("fixtureKey", "canonical"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_500
        )
    }

    @Test
    fun captureSettingsFeedbackTwoAttachments() {
        captureKmpComponentCase(
            caseId = "screens.settings.feedback.two-attachments",
            pageName = "compose_settings_route_visual",
            pageData =
                JSONObject()
                    .put("destination", "hands-feedback")
                    .put("fixtureKey", "feedback-two-attachments"),
            crop = FULL_VIEWPORT_CROP,
            settleMs = 2_000
        )
    }

    @Test
    fun captureAuthLoginScreen() {
        captureKmpComponentCase(
            caseId = "screens.auth.login",
            pageName = RaftCmpActivity.PAGE_LOGIN,
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
    // the host/native top inset. Read by raftTopSafeAreaPadding().
    private fun withVisualCaptureParams(pageData: JSONObject?): JSONObject =
        (pageData ?: JSONObject()).apply {
            if (!has("slockVisualZeroSafeArea")) {
                put("slockVisualZeroSafeArea", true)
            }
        }

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
        scenario: ActivityScenario<RaftCmpActivity>,
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
     * Finish predecessor fixture activities leaked by an abandoned teardown
     * (see [closeScenarioBounded]). Since #3043 gave RaftCmpActivity a
     * translucent window, a leaked predecessor is only PAUSED — never STOPPED —
     * when the next case launches on top of it: it stays visible underneath,
     * keeps receiving frames, and its churn starves the new case's main thread.
     * On the loaded 10-16 nightly that turned the compact-last render gate RED:
     * the first poll took 29.7s and the reported-open modal had not painted a
     * single pixel yet. Call right after ActivityScenario.launch — at that
     * point launch has blocked until the new activity is RESUMED, so every
     * non-RESUMED activity is a predecessor. finish() is idempotent for an
     * activity already being destroyed by its own scenario.
     */
    private fun finishLeakedPredecessorActivities() {
        runOnMainSyncBounded {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val leaked =
                listOf(Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED, Stage.PRE_ON_CREATE)
                    .flatMap { monitor.getActivitiesInStage(it) }
                    .distinct()
            leaked.forEach { activity ->
                Log.w(
                    VISUAL_LOG_TAG,
                    "finishing leaked predecessor ${activity.javaClass.simpleName} " +
                        "left behind by an abandoned teardown"
                )
                activity.finish()
            }
        }
    }

    private fun captureKmpHomeCase(
        caseId: String,
        pageData: JSONObject,
        crop: VisualCrop = FULL_VIEWPORT_CROP
    ) {
        currentCaseId = caseId
        lastReadyTokenObserved = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", RaftCmpActivity.PAGE_HOME)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        val scenario = ActivityScenario.launch<RaftCmpActivity>(intent)
        finishLeakedPredecessorActivities()
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

    private fun writeSuccessfulVisualArtifacts(
        context: Context,
        caseId: String,
        bitmap: Bitmap,
        crop: VisualCrop,
        routePageName: String? = null
    ) {
        val outputDir = visualOutputDir(context)
        outputDir.mkdirs()
        clearMirroredCaseArtifacts(context, caseId)
        val imageFile = File(outputDir, "$caseId.png")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        mirrorFileForAdbPull(context, imageFile)
        val metadataFile = File(outputDir, "$caseId.metadata.json")
        metadataFile.writeText(
            visualMetadata(
                context = context,
                caseId = caseId,
                imageFile = imageFile,
                bitmap = bitmap,
                crop = crop,
                captureMethod = crop.captureMethod,
                routePageName = routePageName
            )
        )
        mirrorFileForAdbPull(context, metadataFile)
    }

    private fun captureKmpComponentCase(
        caseId: String,
        pageName: String,
        crop: VisualCrop,
        pageData: JSONObject? = null,
        settleMs: Long = 1_500,
        requireReactionAtlas: Boolean = false,
        beforeScreenshot: (() -> Unit)? = null,
        afterScreenshot: (() -> Unit)? = null,
        suppressSoftKeyboard: Boolean = true,
        captureSystemSurface: Boolean = false,
        captureAppWindowSurface: Boolean = false,
        // Native transient surfaces may cancel on a second window read; preserve the validated interaction frame.
        capturedBitmapAfterBeforeScreenshot: (() -> Bitmap)? = null,
        assertBitmap: ((Bitmap) -> Unit)? = null,
        readyToken: String? = null,
        // Full-height cases (#388) only know the crop height after the page
        // reports its content bottom; invoked post-settle to replace [crop].
        cropOverride: (() -> VisualCrop)? = null
    ) {
        currentCaseId = caseId
        lastFullLength = null
        var fullLengthBaseDisplay: Pair<Int, Int>? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, RaftCmpActivity::class.java)
                .putExtra("pageName", pageName)
                .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, suppressSoftKeyboard)
                .putExtra("pageData", withVisualCaptureParams(pageData).toString())
        val scenario = ActivityScenario.launch<RaftCmpActivity>(intent)
        finishLeakedPredecessorActivities()
        try {
            onActivityNoIdle { applyVisualFullscreen(it, hideKeyboard = suppressSoftKeyboard) }
            waitForIdleBounded()
            Thread.sleep(settleMs)
            beforeScreenshot?.invoke()
            if (capturedBitmapAfterBeforeScreenshot == null) {
                waitForIdleBounded()
            }
            if (suppressSoftKeyboard) {
                var fixtureOwnedWindows = 0
                onActivityNoIdle {
                    fixtureOwnedWindows = fixtureOwnedWindowViews(it).size
                    suppressVisualKeyboard(it)
                }
                waitForIdleBounded()
                if (fixtureOwnedWindows > 0) {
                    // Let a dialog laid out above a dismissed IME settle back.
                    Thread.sleep(DIALOG_IME_DISMISS_SETTLE_MS)
                    waitForIdleBounded()
                }
            }
            lastReadyTokenObserved = readyToken?.let { awaitVisualReadyToken(caseId, it) } ?: false
            lastReadyTokenRequired = readyToken != null
            onActivityNoIdle { activity ->
                val hostErrorShown = activity.javaClass
                    .getDeclaredField("hostErrorSurfaceShown")
                    .apply { isAccessible = true }
                    .getBoolean(activity)
                require(!hostErrorShown) {
                    "$caseId host error surface was rendered; refusing to capture CALL_KOTLIN fallback"
                }
            }
            val baseCrop = cropOverride?.invoke() ?: crop
            val fullLength =
                if (caseId in fullLengthCases) {
                    fullLengthBaseDisplay = currentDisplaySizePx()
                    expandToFullLength(caseId, requireNotNull(fullLengthBaseDisplay))
                } else {
                    null
                }
            lastFullLength = fullLength
            val effectiveCrop = fullLength?.extend(baseCrop) ?: baseCrop
            val normalized =
                if (capturedBitmapAfterBeforeScreenshot != null) {
                    val screenshot = capturedBitmapAfterBeforeScreenshot.invoke()
                    require(cropBoundsProblem(screenshot, effectiveCrop) == null) {
                        cropBoundsProblem(screenshot, effectiveCrop).orEmpty()
                    }
                    screenshot.cropDp(effectiveCrop).also { cropped ->
                        if (cropped !== screenshot) screenshot.recycle()
                    }
                } else if (captureAppWindowSurface) {
                    requireNotNull(captureWindowPixelCopyBitmap(caseId, effectiveCrop)) {
                        "Unable to capture system surface for $caseId"
                    }
                } else if (captureSystemSurface) {
                    requireNotNull(captureUiAutomationFallbackBitmap(caseId, effectiveCrop)) {
                        "Unable to capture system surface for $caseId"
                    }
                } else {
                    captureStableVisualBitmap(
                        context = context,
                        caseId = caseId,
                        crop = effectiveCrop,
                        requireReactionAtlas = requireReactionAtlas,
                        requiredStableFrames = if (readyToken == null) 1 else 2
                    )
                }
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            clearMirroredCaseArtifacts(context, caseId)
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // Preserve the exact bitmap that the oracle evaluated even when
            // a case-specific assertion fails. The exception path writes its
            // failure.json separately, while this mirrored PNG remains
            // available for direct visual review of the RED artifact.
            mirrorFileForAdbPull(context, imageFile)
            assertBitmap?.invoke(normalized)
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                visualMetadata(
                    context = context,
                    caseId = caseId,
                    imageFile = imageFile,
                    bitmap = normalized,
                    crop = effectiveCrop,
                    captureMethod = effectiveCrop.captureMethod,
                    routePageName = pageName,
                    fixtureKey = pageData?.optString("fixtureKey")?.takeIf { it.isNotBlank() },
                    fixtureRevision = pageData?.optString("fixtureRevision")?.takeIf { it.isNotBlank() },
                    renderedGoalSha256 = pageData?.optString("renderedGoalSha256")?.takeIf { it.isNotBlank() }
                )
            )
            mirrorFileForAdbPull(context, metadataFile)
            normalized.recycle()
        } finally {
            try {
                afterScreenshot?.invoke()
            } catch (t: Throwable) {
                Log.w(VISUAL_LOG_TAG, "after-screenshot teardown failed: ${t.message}")
            }
            closeScenarioBounded(scenario)
            fullLengthBaseDisplay?.let { restoreDisplaySize(caseId, it) }
        }
    }

    private fun tapDisplayDp(x: Float, y: Float) {
        awaitFixtureIsActiveInputWindow("Tap target fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val xPx = x * density
        val yPx = y * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, xPx, yPx, 0),
            true,
        )
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xPx, yPx, 0),
            true,
        )
    }

    private fun longPressDisplayDp(
        x: Float,
        y: Float,
        durationMs: Long = 700L,
        inputReadyCaseId: String? = null,
        inputReadyToken: String? = null,
    ) {
        require((inputReadyCaseId == null) == (inputReadyToken == null)) {
            "Long-press input readiness case/token must be provided together"
        }
        awaitFixtureIsActiveInputWindow("Long-press target fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val xPx = x * density
        val yPx = y * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, xPx, yPx, 0),
            true,
        )
        var released = false
        try {
            // Kuikly/Compose starts this pointer-input coroutine lazily on the
            // first ACTION_DOWN. Keep that exact gesture held while the
            // launch-unique signal proves the target row's detector started;
            // only then let it cross the long-press threshold and release.
            if (inputReadyCaseId != null && inputReadyToken != null) {
                awaitThreadLongPressInputReady(
                    caseId = inputReadyCaseId,
                    token = inputReadyToken,
                )
            }
            val remainingHoldMs = durationMs - (SystemClock.uptimeMillis() - downTime)
            if (remainingHoldMs > 0L) {
                Thread.sleep(remainingHoldMs)
            }
            instrumentation.uiAutomation.injectInputEvent(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    xPx,
                    yPx,
                    0,
                ),
                true,
            )
            released = true
        } finally {
            if (!released) {
                instrumentation.uiAutomation.injectInputEvent(
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        xPx,
                        yPx,
                        0,
                    ),
                    true,
                )
            }
        }
    }

    /**
     * Long-press, then poll-capture until the triggered actions UI has actually
     * rendered, instead of guessing a fixed
     * post-gesture sleep. On a loaded nightly shard the menu can render slower
     * than any fixed wait, so a fixed sleep captures before the render and the
     * changedRatio assert flakes (green in isolation, red under load — the
     * compact-menu case seen 2026-07-31). We wait for the real pixels:
     * poll every [pollMs] until [rendered] is true for the fresh capture or
     * [timeoutMs] elapses, re-issuing the long-press once at [retryAtMs] to guard
     * the occasional un-registered press. [rendered] must encode the case's FULL
     * render-ready signal, not just a coarse changedRatio: e.g. the compact case
     * needs the black menu chrome, which renders after the first pixel change,
     * so a changedRatio-only gate would return before the menu and the caller's
     * later assert would still fail. Returns the final
     * capture (rendered on success, last poll on timeout so the caller's own
     * asserts still fail with real detail). The caller owns recycling the
     * returned bitmap; [captureAppWindowsScreenshot] holds no disk state, so the
     * intermediate polls are recycled here and never persisted. On timeout the
     * before/last-poll frames plus a bounded view-tree dump ARE persisted as
     * render-fail evidence (wg-nightly task #16): the caller's assert fires
     * next and the exception path can no longer reach these pixels.
     */
    private fun captureAfterLongPressRendered(
        label: String,
        before: Bitmap,
        longPress: () -> Unit,
        rendered: (before: Bitmap, shot: Bitmap, changedRatio: Float) -> Boolean,
        timeoutMs: Long = LONG_PRESS_RENDER_TIMEOUT_MS,
        pollMs: Long = LONG_PRESS_RENDER_POLL_MS,
        retryAtMs: Long = LONG_PRESS_RENDER_RETRY_AT_MS,
    ): Bitmap {
        longPress()
        val start = SystemClock.uptimeMillis()
        var retried = false
        var poll = 0
        var latest: Bitmap? = null
        while (true) {
            Thread.sleep(pollMs)
            waitForIdleBounded()
            latest?.recycle()
            // Long-press menus open in a `Modal`, which on CMP is its own Dialog
            // window; the activity decor alone never shows them, so poll the
            // fixture-owned window composite the final capture uses.
            latest = captureAppWindowsScreenshot("$label.render-poll")
            poll += 1
            val changedRatio = before.fullBitmapChangedPixelRatio(latest!!)
            val ok = rendered(before, latest!!, changedRatio)
            val elapsed = SystemClock.uptimeMillis() - start
            Log.i(
                VISUAL_LOG_TAG,
                "$label long-press render poll=$poll elapsedMs=$elapsed " +
                    "changedRatio=$changedRatio rendered=$ok retried=$retried"
            )
            if (ok || elapsed >= timeoutMs) {
                if (ok) {
                    deleteRenderFailDiagnostics(label)
                } else {
                    writeRenderFailDiagnostics(
                        label = label,
                        before = before,
                        lastPoll = latest!!,
                        polls = poll,
                        elapsedMs = elapsed,
                        lastChangedRatio = changedRatio,
                        retried = retried,
                    )
                }
                return latest!!
            }
            // task #228: only re-press when the screen has not responded at all.
            // Re-pressing while a ripple (or menu) is already showing muddies the
            // state and overwrites the poll=1 scene, so the render-fail evidence
            // written afterwards shows an empty screen. That made "the gate did
            // not recognise it" and "nothing happened" indistinguishable for five
            // investigation rounds (run 32849366613 logcat).
            val screenResponded = changedRatio >= LONG_PRESS_RETRY_SUPPRESS_CHANGED_RATIO
            if (!retried && elapsed >= retryAtMs) {
                if (screenResponded) {
                    Log.i(
                        VISUAL_LOG_TAG,
                        "$label long-press retry suppressed poll=$poll " +
                            "elapsedMs=$elapsed changedRatio=$changedRatio"
                    )
                } else {
                    Log.i(
                        VISUAL_LOG_TAG,
                        "$label long-press retry poll=$poll elapsedMs=$elapsed"
                    )
                    longPress()
                }
                retried = true
            }
        }
    }

    private fun awaitThreadLongPressInputReady(
        caseId: String,
        token: String,
    ) {
        awaitVisualReadyToken(caseId, token)
        // The pointer coroutine starts lazily on ACTION_DOWN; that same gesture
        // remains held here. Give detectTapGestures one short bounded hand-off
        // after logcat observes the launch-unique token, then finish the hold.
        Thread.sleep(LONG_PRESS_INPUT_READY_SETTLE_MS)
        Log.i(
            VISUAL_LOG_TAG,
            "$caseId long-press input detector ready; settled ${LONG_PRESS_INPUT_READY_SETTLE_MS}ms"
        )
    }

    /**
     * Startup/compile timing shifts (e.g. baseline-profile changes such as PR
     * #1710) can leave a system/transition window ("android") as the active
     * input window for a while after launch. A gesture issued in that state
     * misses the fixture entirely, so poll until the fixture owns the active
     * input window instead of requiring it at a single instant; fail only after
     * the bounded timeout (nightly run 30402296301, 7/8 shards). Called by every
     * gesture-injection helper, so all interaction cases share the gate; when
     * the fixture already owns the window the first poll returns immediately.
     */
    private fun awaitFixtureIsActiveInputWindow(
        what: String,
        timeoutMs: Long = ACTIVE_INPUT_WINDOW_TIMEOUT_MS
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val powerManager =
            instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var activePackage: CharSequence? = null
        var wokeDisplay = false
        var dismissedSystemDialogs = 0
        var forceStopped = false
        var forceStoppedPackage: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            // CI emulators ship stay_on_while_plugged_in=0 with a finite
            // screen_off_timeout (PR #1747's first-character finding); when the
            // display sleeps mid-shard the keyguard/system window (package
            // "android") takes input focus and eats injected gestures. Re-wake
            // and dismiss the keyguard before re-reading the focused window.
            if (powerManager?.isInteractive == false) {
                runShellCommand("input keyevent KEYCODE_WAKEUP")
                runShellCommand("wm dismiss-keyguard")
                wokeDisplay = true
            }
            activePackage =
                instrumentation.uiAutomation.rootInActiveWindow?.packageName
            if (activePackage == "build.raft.app.connectedtest") return
            // A system window (package "android") holding input focus is a
            // crash / ANR / keyguard dialog over the fixture — e.g. a launcher
            // ANR ("Application Not Responding: ...nexuslauncher", proof run
            // 30503233522 shard1) that hide_error_dialogs does not suppress.
            // BACK dismisses the focused dialog; it targets the focused system
            // window, never the backgrounded fixture, so the fixture cannot be
            // navigated. Best-effort each poll until focus returns to us.
            if (activePackage == "android") {
                runShellCommand("input keyevent KEYCODE_BACK")
                dismissedSystemDialogs++
                // A transient dialog clears with a single BACK. A system app
                // that is genuinely hung (proof run 30512804561 shard0: the
                // launcher ANR returned across dismissedSystemDialogs=39, BACK
                // losing the whole 10s) needs to be restarted. Once BACK has
                // clearly not won, force-stop the ANR/crash owner so its window
                // rebuilds — but ONLY a non-fixture, non-instrumentation app,
                // never our own capture process.
                if (dismissedSystemDialogs == PERSISTENT_DIALOG_FORCE_STOP_AFTER && !forceStopped) {
                    val offender = focusedSystemAppPackage()
                    if (offender != null &&
                        offender != "build.raft.app.connectedtest" &&
                        offender != "build.raft.app.connectedtest.test"
                    ) {
                        runShellCommand("am force-stop $offender")
                        forceStoppedPackage = offender
                        forceStopped = true
                        Log.w(VISUAL_LOG_TAG, "$what: force-stopped hung system app $offender after $dismissedSystemDialogs BACK dismisses")
                    }
                }
            }
            Thread.sleep(ACTIVE_INPUT_WINDOW_POLL_MS)
        }
        // Name the offending window definitively: package alone cannot separate
        // keyguard / ANR dialog / a stuck starting-window (splash), and our
        // PixelCopy screenshots read the app window directly, so a rendered
        // before-frame proves nothing about what the screen composite shows.
        // The chain-repro (run 30500947125) showed the older mCurrentFocus grep
        // matches nothing on this emulator image, so log the FULL window and
        // input-focus tables to logcat and embed a broad-label extract.
        val windowDump = readShellCommand("dumpsys window windows")
        windowDump.chunked(3500).forEachIndexed { index, chunk ->
            Log.i(VISUAL_LOG_TAG, "GATE_TIMEOUT_WINDOWS[$index] $chunk")
        }
        val inputFocus = readShellCommand("dumpsys input")
            .lineSequence()
            .filter { it.contains("FocusedWindow", ignoreCase = true) || it.contains("FocusedApplication", ignoreCase = true) }
            .joinToString(" | ") { it.trim() }
            .take(400)
        Log.i(VISUAL_LOG_TAG, "GATE_TIMEOUT_INPUT_FOCUS $inputFocus")
        val focusDump = windowDump
            .lineSequence()
            .filter { line ->
                listOf("mCurrentFocus", "mFocusedWindow", "mFocusedApp", "imeInputTarget", "imeLayeringTarget", "Window #")
                    .any { line.contains(it) }
            }
            .joinToString(" | ") { it.trim() }
            .ifEmpty { "no-focus-labels; head=" + windowDump.take(300).replace('\n', ' ') }
            .take(700)
        throw IllegalArgumentException(
            "$what is not the active input window after ${timeoutMs}ms: " +
                "package=$activePackage interactive=${powerManager?.isInteractive} " +
                "wokeDisplay=$wokeDisplay dismissedSystemDialogs=$dismissedSystemDialogs " +
                "forceStopped=$forceStoppedPackage inputFocus=[$inputFocus] focus=[$focusDump]"
        )
    }

    /**
     * Parse the package that owns the focused ANR / crash dialog from the
     * window table (e.g. "Application Not Responding: com.google...launcher" or
     * "Application Error: <pkg>"). Returns null when no such dialog is named, so
     * the caller only ever force-stops an explicitly-identified hung app.
     */
    private fun focusedSystemAppPackage(): String? {
        val marker = Regex("""Application (?:Not Responding|Error): ([A-Za-z0-9_.]+)""")
        return readShellCommand("dumpsys window windows")
            .lineSequence()
            .mapNotNull { marker.find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }

    /** Shell command via UiAutomation returning stdout (best-effort, empty on failure). */
    private fun readShellCommand(command: String): String =
        try {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { String(it.readBytes()) }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "shell read '$command' failed: ${t.message}")
            ""
        }

    private fun dismissCompatibilityDialog() {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        val button =
            root.findAccessibilityNodeInfosByText("Don't Show Again").firstOrNull()
                ?: root.findAccessibilityNodeInfosByText("OK").firstOrNull()
                ?: return
        button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(500)
    }

    /**
     * Nodes under [root] whose text or content description is exactly [text].
     *
     * `AccessibilityNodeInfo.findAccessibilityNodeInfosByText` delegates to the
     * view's `AccessibilityNodeProvider`, and Compose's provider implements only
     * `createAccessibilityNodeInfo` / `performAction`, not by-text search. Since
     * the CMP switch every fixture label is a Compose virtual node, so that call
     * returns nothing even while the label is on screen (the pending-mention
     * failure listed "View" among the tree's texts). Walk the tree instead.
     */
    private fun findComposeNodesWithText(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        text: String
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > ACCESSIBILITY_TREE_MAX_DEPTH) return
            if (node.text?.toString()?.trim() == text || node.contentDescription?.toString()?.trim() == text) {
                matches += node
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(root, 0)
        return matches
    }

    /** Taps the clickable node (or its clickable ancestor) whose text or description is one of [texts]. */
    private fun tapComposeNodeWithText(texts: List<String>, what: String) {
        awaitFixtureIsActiveInputWindow(what)
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + PENDING_MENTION_VIEW_ACTION_TIMEOUT_MS
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        while (target == null) {
            target =
                texts
                    .asSequence()
                    .flatMap { findComposeNodesWithText(uiAutomation.rootInActiveWindow, it).asSequence() }
                    .mapNotNull { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
                    .firstOrNull()
            if (target != null || SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        requireNotNull(target) { "$what is not visible (looked for ${texts.joinToString(" | ")})" }
        val bounds = android.graphics.Rect()
        target.getBoundsInScreen(bounds)
        require(!bounds.isEmpty) { "$what has empty bounds" }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        tapDp(bounds.exactCenterX() / density, bounds.exactCenterY() / density)
    }

    private fun openPendingMentionDrawer() {
        // The accessibility snapshot can trail the first painted frame on a
        // loaded CI emulator (the partial-batch fixture also composes the
        // notice row above the banner), so poll the live tree instead of
        // reading it exactly once.
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + PENDING_MENTION_VIEW_ACTION_TIMEOUT_MS
        var viewAction: android.view.accessibility.AccessibilityNodeInfo? = null
        while (viewAction == null) {
            viewAction = findComposeNodesWithText(uiAutomation.rootInActiveWindow, "View").firstOrNull()
            if (viewAction != null || SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        requireNotNull(viewAction) {
            // Name what the tree did hold, so a CI-only miss is diagnosable.
            val texts = mutableListOf<String>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 40 || texts.size > 60) return
                (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it.take(40) }
                for (index in 0 until node.childCount) collect(node.getChild(index), depth + 1)
            }
            val root = uiAutomation.rootInActiveWindow
            collect(root, 0)
            "Pending mention View action is not visible; activeWindowPackage=${root?.packageName} " +
                "windows=${uiAutomation.windows.size} texts=${texts.joinToString(" | ")}"
        }
        val clickableAction = generateSequence(viewAction) { it.parent }.firstOrNull { it.isClickable }
        requireNotNull(clickableAction) { "Pending mention View action has no clickable ancestor" }
        val bounds = android.graphics.Rect()
        clickableAction.getBoundsInScreen(bounds)
        require(!bounds.isEmpty) { "Pending mention View action has empty bounds" }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        tapDisplayDp(bounds.exactCenterX() / density, bounds.exactCenterY() / density)
        Thread.sleep(1_500)
    }

    private fun applyVisualFullscreen(activity: Activity, hideKeyboard: Boolean = true) {
        activity.window.setSoftInputMode(
            (if (hideKeyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN else 0) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
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
                val hiddenTypes =
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        if (hideKeyboard) WindowInsets.Type.ime() else 0
                controller.hide(hiddenTypes)
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
        // Modal content lives in its own dialog window, which neither the
        // activity's soft-input mode nor its immersive flags reach. An
        // auto-focused field there (Create Agent's name input) raised the IME,
        // shrank the dialog above it, and left a composing underline; the React
        // baseline is captured unfocused with no keyboard or system bars.
        fixtureOwnedWindowViews(activity).forEach { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            val hiddenSoftInput =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (params.softInputMode != hiddenSoftInput) {
                params.softInputMode = hiddenSoftInput
                try {
                    activity.windowManager.updateViewLayout(view, params)
                } catch (t: IllegalArgumentException) {
                    Log.w(VISUAL_LOG_TAG, "fixture window soft-input update skipped: ${t.message}")
                }
            }
            view.findFocus()?.clearFocus()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.let { controller ->
                    controller.hide(
                        WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars() or
                            WindowInsets.Type.ime()
                    )
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
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

    /**
     * [captureActivityScreenshot] plus every window the fixture activity owns
     * (Compose `Dialog` windows behind `Modal`, and popups anchored to them),
     * composited in z-order. `Modal` rehosts its content in a separate native
     * window, so drawing only the activity decor dropped open dialogs entirely
     * (screens.settings.server-danger-modal captured the page with no modal) or
     * left a blank frame that fell through to the UiAutomation screenshot, which
     * carries the device status/navigation bars and the soft keyboard the React
     * baseline never has (components.members.create-agent.*,
     * components.home.create-channel.dialog, components.channel.*). System
     * windows (bars, IME) are never part of the composite.
     */
    private fun captureAppWindowsScreenshot(caseId: String): Bitmap {
        lastCaptureCompositedWindowCount = 0
        var bitmap: Bitmap? = null
        onActivityNoIdle { activity ->
            val root = activity.window.decorView.rootView
            if (root.width > 0 && root.height > 0) {
                bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                    val canvas = Canvas(output)
                    root.draw(canvas)
                    lastCaptureCompositedWindowCount = drawFixtureOwnedWindows(activity, canvas)
                }
            }
        }
        return bitmap ?: captureActivityScreenshot(caseId)
    }

    /** Windows (other than the activity decor) attached to [activity]'s app token, bottom to top. */
    private fun fixtureOwnedWindowViews(activity: Activity): List<View> {
        val activityRoot = activity.window.decorView.rootView
        val ownerTokens = mutableSetOf<android.os.IBinder>()
        activity.window.attributes.token?.let(ownerTokens::add)
        activityRoot.windowToken?.let(ownerTokens::add)
        // getGlobalWindowViews is in add order; a stable sort by window type keeps
        // that order within a layer and puts sub-window panels above dialogs.
        val candidates =
            WindowInspector.getGlobalWindowViews()
                .filter { it !== activityRoot && it.isAttachedToWindow }
                .sortedBy { (it.layoutParams as? WindowManager.LayoutParams)?.type ?: 0 }
        val owned = mutableListOf<View>()
        for (view in candidates) {
            val token = (view.layoutParams as? WindowManager.LayoutParams)?.token ?: continue
            if (token !in ownerTokens) continue
            view.windowToken?.let(ownerTokens::add)
            owned += view
        }
        return owned
    }

    private fun drawFixtureOwnedWindows(activity: Activity, canvas: Canvas): Int {
        val rootLocation = IntArray(2).also(activity.window.decorView.rootView::getLocationOnScreen)
        var drawn = 0
        for (view in fixtureOwnedWindowViews(activity)) {
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
            val params = view.layoutParams as WindowManager.LayoutParams
            if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(Color.argb((params.dimAmount.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0))
            }
            val location = IntArray(2).also(view::getLocationOnScreen)
            val checkpoint = canvas.save()
            canvas.translate(
                (location[0] - rootLocation[0]).toFloat(),
                (location[1] - rootLocation[1]).toFloat()
            )
            view.draw(canvas)
            canvas.restoreToCount(checkpoint)
            drawn += 1
        }
        return drawn
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
        if (bitmap == null) {
            // Last-resort system-surface grab: like the validated fallback, it
            // can capture the ANR dialog instead of content — fail loud rather
            // than let it false-pass validation (task #398/#399).
            anrDialogProblem()?.let { problem ->
                throw AssertionError("$caseId capture aborted: $problem")
            }
        }
        return bitmap
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                "Unable to capture KMP visual screenshot for $caseId"
            }
    }

    /**
     * Detect the system "<app> isn't responding" ANR dialog (task #398/#399).
     * The UiAutomation fallback screenshots the real system surface, so when the
     * app window ANRs the fallback frame is the ANR dialog over a dimmed page —
     * rich enough in colors to pass [visualValidationProblems] and false-pass as
     * content (seen on screens.settings.connected-apps.full, run 28860071543).
     * The dialog is a system window the software root.draw() path can never see,
     * so this check only matters on the UiAutomation path. Accessibility-based:
     * when the ANR dialog shows it is the active window, and its message always
     * contains "isn't responding" (CI emulators run the English locale).
     * Best-effort: any failure means "not detected", never a capture failure.
     */
    private fun anrDialogProblem(): String? =
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("isn't responding")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "system ANR dialog visible (\"isn't responding\")" }
        } catch (t: Throwable) {
            null
        }

    private fun captureUiAutomationFallbackBitmap(caseId: String, crop: VisualCrop): Bitmap? {
        val anrProblem = anrDialogProblem()
        if (anrProblem != null) {
            Log.w(VISUAL_LOG_TAG, "$caseId UiAutomation fallback rejected: $anrProblem")
            return null
        }
        val screenshot =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: return null
        val boundsProblem = cropBoundsProblem(screenshot, crop)
        if (boundsProblem != null) {
            Log.w(VISUAL_LOG_TAG, "$caseId UiAutomation fallback $boundsProblem")
            screenshot.recycle()
            return null
        }
        val cropped = screenshot.cropDp(crop)
        val stats = cropped.visualStats()
        val problems = visualValidationProblems(cropped, stats, crop)
        if (problems.isEmpty()) {
            if (cropped !== screenshot) {
                screenshot.recycle()
            }
            Log.i(VISUAL_LOG_TAG, "$caseId using UiAutomation screenshot after root-draw blank frame")
            return cropped
        }
        Log.w(
            VISUAL_LOG_TAG,
            "$caseId UiAutomation fallback also failed validation: ${problems.joinToString("; ")}"
        )
        cropped.recycle()
        if (cropped !== screenshot) {
            screenshot.recycle()
        }
        return null
    }

    /** Captures Kuikly's hardware-backed in-window Modal surface at the app window's dimensions. */
    private fun captureWindowPixelCopyBitmap(caseId: String, crop: VisualCrop): Bitmap? {
        val anrProblem = anrDialogProblem()
        if (anrProblem != null) {
            Log.w(VISUAL_LOG_TAG, "$caseId PixelCopy rejected: $anrProblem")
            return null
        }
        val copied = CountDownLatch(1)
        var screenshot: Bitmap? = null
        var copyResult = PixelCopy.ERROR_UNKNOWN
        onActivityNoIdle { activity ->
            val root = activity.window.decorView.rootView
            if (root.width > 0 && root.height > 0) {
                screenshot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    activity.window,
                    requireNotNull(screenshot),
                    { result ->
                        copyResult = result
                        copied.countDown()
                    },
                    Handler(Looper.getMainLooper())
                )
            } else {
                copied.countDown()
            }
        }
        if (!copied.await(5, TimeUnit.SECONDS) || copyResult != PixelCopy.SUCCESS) {
            Log.w(VISUAL_LOG_TAG, "$caseId PixelCopy failed: result=$copyResult")
            screenshot?.recycle()
            return captureUiAutomationFallbackBitmap(caseId, crop)
        }
        val captured = requireNotNull(screenshot)
        val boundsProblem = cropBoundsProblem(captured, crop)
        if (boundsProblem != null) {
            Log.w(VISUAL_LOG_TAG, "$caseId PixelCopy $boundsProblem")
            captured.recycle()
            return null
        }
        val cropped = captured.cropDp(crop)
        val problems = visualValidationProblems(cropped, cropped.visualStats(), crop)
        if (problems.isEmpty()) {
            if (cropped !== captured) captured.recycle()
            return cropped
        }
        Log.w(
            VISUAL_LOG_TAG,
            "$caseId PixelCopy failed validation: ${problems.joinToString("; ")}"
        )
        cropped.recycle()
        if (cropped !== captured) captured.recycle()
        return null
    }

    private fun captureStableVisualBitmap(
        context: Context,
        caseId: String,
        crop: VisualCrop,
        requireReactionAtlas: Boolean = false,
        requiredStableFrames: Int = 1
    ): Bitmap {
        lastCaptureClampedToShortWindow = false
        lastCaptureMethod = crop.captureMethod
        lastStableFrameCount = 0
        if (requireReactionAtlas) {
            awaitReactionAtlasReady(caseId)
        }
        val deadline = SystemClock.uptimeMillis() + STABLE_CAPTURE_TIMEOUT_MS
        var lastBitmap: Bitmap? = null
        var lastStats: VisualBitmapStats? = null
        var lastProblems: List<String> = emptyList()

        do {
            waitForIdleBounded()
            val screenshot = captureAppWindowsScreenshot(caseId)
            lastCaptureMethod =
                if (lastCaptureCompositedWindowCount > 0) {
                    crop.captureMethod + APP_DIALOG_WINDOWS_CAPTURE_SUFFIX
                } else {
                    crop.captureMethod
                }
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
            var normalized = cropped
            var stats = normalized.visualStats()
            var problems = visualValidationProblems(normalized, stats, crop)
            var sourceFrameRecycled = false
            val uiAutomationFallback =
                if (problems.isNotEmpty() && shouldTryUiAutomationFallback(stats)) {
                    captureUiAutomationFallbackBitmap(caseId, crop)
                } else {
                    null
                }
            if (uiAutomationFallback != null) {
                if (cropped !== screenshot && cropped !== normalized) {
                    cropped.recycle()
                }
                if (screenshot !== normalized) {
                    screenshot.recycle()
                }
                normalized.recycle()
                sourceFrameRecycled = true
                lastCaptureMethod = UI_AUTOMATION_CAPTURE_METHOD
                normalized = uiAutomationFallback
                stats = normalized.visualStats()
                problems = visualValidationProblems(normalized, stats, crop)
            }
            if (problems.isEmpty()) {
                if (!sourceFrameRecycled && cropped !== screenshot && cropped !== normalized) {
                    cropped.recycle()
                }
                if (!sourceFrameRecycled && screenshot !== normalized) {
                    screenshot.recycle()
                }
                if (requiredStableFrames <= 1) {
                    lastStableFrameCount = 1
                    lastBitmap?.recycle()
                    return normalized
                }
                val prior = lastBitmap
                if (prior != null && prior.sameAs(normalized)) {
                    lastStableFrameCount = 2
                    prior.recycle()
                    return normalized
                }
                prior?.recycle()
                lastBitmap = normalized
                lastStats = stats
                lastProblems = listOf("stable-frame oracle has not observed two identical consecutive frames")
                Thread.sleep(STABLE_CAPTURE_RETRY_MS)
                continue
            }

            lastBitmap?.recycle()
            lastBitmap = normalized
            lastStats = stats
            lastProblems = problems
            if (!sourceFrameRecycled && cropped !== screenshot && cropped !== normalized) {
                cropped.recycle()
            }
            if (!sourceFrameRecycled && screenshot !== normalized) {
                screenshot.recycle()
            }
            Thread.sleep(STABLE_CAPTURE_RETRY_MS)
        } while (SystemClock.uptimeMillis() < deadline)

        if (lastBitmap == null) {
            Log.w(VISUAL_LOG_TAG, "$caseId window never reached full crop size within ${STABLE_CAPTURE_TIMEOUT_MS}ms; rejecting clamped frame")
            lastCaptureClampedToShortWindow = true
            val screenshot = captureActivityScreenshot(caseId)
            val clamped = screenshot.cropDp(crop)
            if (clamped !== screenshot) {
                screenshot.recycle()
            }
            val stats = clamped.visualStats()
            val problems = listOf("full viewport unavailable; clamped capture is not admissible")
            writeFailureArtifacts(context, caseId, clamped, crop, stats, problems)
            clamped.recycle()
            throw AssertionError("$caseId full viewport unavailable; clamped capture rejected")
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

    private fun awaitVisualReadyToken(
        caseId: String,
        token: String
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + VISUAL_READY_TIMEOUT_MS
        do {
            if (logcatContainsToken(token)) {
                Log.i(VISUAL_LOG_TAG, "$caseId observed ready token: $token")
                return true
            }
            Thread.sleep(STABLE_CAPTURE_RETRY_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$caseId did not emit required visual ready token: $token")
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
    /**
     * The matching logcat line for [token], or null.
     *
     * [logcatContainsToken] answers only yes/no, and the fixture writes its token
     * under a tag the archived logcat does not keep (it retains VisualCapture,
     * TestRunner, System.out and Task139Reuse). So a criterion that reads the
     * token live leaves NO trace in the evidence: the run says the menu opened
     * and the artifact cannot show it. Returning the line lets the caller copy it
     * into the visual log, where it is archived - which matters because this
     * cannot be reconstructed after the run.
     */
    private fun logcatFindToken(token: String): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-s", REACTION_ATLAS_LOG_TAG)
            )
            process.inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.contains(token) }
            }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "logcatFindToken failed: ${t.message}")
            null
        } finally {
            process?.destroy()
        }
    }

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

    /** Case ids flagged `capture.fullLength` in slock sharedCases.json (instrumentation arg). */
    private val fullLengthCases: Set<String> by lazy {
        VisualFullLength.requestedCases(
            InstrumentationRegistry.getArguments().getString(VisualFullLength.ARG_CASES)
        )
    }

    private var lastFullLength: FullLengthResult? = null

    private data class FullLengthResult(
        val baseHeightPx: Int,
        val expandedHeightPx: Int,
        val density: Float,
        val initialOverflowPx: Int,
        val residualOverflowPx: Int,
        val iterations: Int,
        val capped: Boolean,
        val maxHeightDp: Int,
        val scroller: String?
    ) {
        val addedDp: Int get() = ((expandedHeightPx - baseHeightPx) / density).roundToInt()

        /**
         * A crop that reached the bottom of the base viewport (FULL_VIEWPORT_CROP
         * and other bottom-anchored crops) grows with the display; a component
         * crop above the fold is left alone.
         */
        fun extend(crop: VisualCrop): VisualCrop {
            if (addedDp <= 0) return crop
            val baseHeightDp = (baseHeightPx / density).roundToInt()
            if (crop.topDp + crop.heightDp < baseHeightDp) return crop
            return crop.copy(
                heightDp = crop.heightDp + addedDp,
                captureMethod = crop.captureMethod + "-full-length"
            )
        }

        fun toJson(): String =
            JSONObject()
                .put("requested", true)
                .put("baseViewportHeight", (baseHeightPx / density).roundToInt())
                .put("expandedViewportHeight", (expandedHeightPx / density).roundToInt())
                .put("initialOverflowDp", kotlin.math.ceil(initialOverflowPx / density).toInt())
                .put("residualOverflowDp", kotlin.math.ceil(residualOverflowPx / density).toInt())
                .put("iterations", iterations)
                .put("capped", capped)
                .put("maxHeight", maxHeightDp)
                .put("scroller", scroller ?: JSONObject.NULL)
                .toString()
    }

    private fun currentDisplaySizePx(): Pair<Int, Int> {
        var size = 0 to 0
        onActivityNoIdle { activity ->
            val root = activity.window.decorView.rootView
            size = root.width to root.height
        }
        // The run script pins 1170x2532 @ 480dpi; fall back to it if the window
        // has not reported a size.
        return if (size.first > 0 && size.second > 0) size else 1170 to 2532
    }

    private fun windowRoots(activity: Activity): List<View> =
        listOf(activity.window.decorView.rootView) + fixtureOwnedWindowViews(activity)

    private fun mainScroller(): VisualFullLength.Scroller? {
        var scroller: VisualFullLength.Scroller? = null
        onActivityNoIdle { scroller = VisualFullLength.mainScroller(windowRoots(it)) }
        return scroller
    }

    private fun scrollerAnchors(scrollerId: Int): Map<Int, Float> {
        var anchors: Map<Int, Float> = emptyMap()
        onActivityNoIdle { anchors = VisualFullLength.anchorTops(windowRoots(it), scrollerId) }
        return anchors
    }

    /**
     * Pixels of content outside the viewport of [scroller] (below the fold, or
     * above it for a bottom-anchored reversed list). `verticalScroll` reports
     * them exactly; a lazy list is scrolled forward in half-viewport steps and
     * the travelled distance is read from how far its composed descendants move
     * (in either direction), then scrolled back to its start.
     */
    private fun measureOverflowPx(caseId: String, scroller: VisualFullLength.Scroller, limitPx: Int): Int {
        if (!scroller.lazy) return kotlin.math.ceil(scroller.maxValue).toInt()
        val stepPx = (scroller.heightPx * 0.5f).coerceAtLeast(48f)
        var travelled = 0f
        for (step in 0 until FULL_LENGTH_MAX_SCROLL_STEPS) {
            val before = scrollerAnchors(scroller.nodeId)
            if (before.isEmpty()) {
                throw AssertionError("$caseId full-length: scroller ${scroller.label} has no composed descendants to track")
            }
            onActivityNoIdle { VisualFullLength.scrollBy(windowRoots(it), scroller.nodeId, stepPx) }
            waitForIdleBounded()
            Thread.sleep(FULL_LENGTH_SCROLL_SETTLE_MS)
            val moved =
                VisualFullLength.displacement(before, scrollerAnchors(scroller.nodeId))
                    ?: throw AssertionError("$caseId full-length: lost every anchor of ${scroller.label} after a ${stepPx}px step")
            val magnitude = kotlin.math.abs(moved)
            if (magnitude < 0.5f) break
            travelled += magnitude
            if (travelled >= limitPx) break
        }
        onActivityNoIdle { VisualFullLength.scrollToTop(windowRoots(it), scroller.nodeId) }
        waitForIdleBounded()
        Thread.sleep(FULL_LENGTH_SCROLL_SETTLE_MS)
        return kotlin.math.ceil(travelled).toInt()
    }

    private fun resizeDisplay(caseId: String, widthPx: Int, heightPx: Int) {
        runShellCommand("wm size ${widthPx}x$heightPx")
        val deadline = SystemClock.uptimeMillis() + FULL_LENGTH_RESIZE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentDisplaySizePx().second == heightPx) break
            Thread.sleep(FULL_HEIGHT_POLL_MS)
        }
        val actual = currentDisplaySizePx()
        if (actual.second != heightPx) {
            throw AssertionError("$caseId full-length: window is ${actual.first}x${actual.second} after wm size ${widthPx}x$heightPx")
        }
        waitForIdleBounded()
        Thread.sleep(FULL_LENGTH_RESIZE_SETTLE_MS)
    }

    /** Widens the display to [widthPx] (height kept), waiting until the window reports it. */
    private fun resizeDisplayWidth(caseId: String, widthPx: Int, heightPx: Int) {
        runShellCommand("wm size ${widthPx}x$heightPx")
        val deadline = SystemClock.uptimeMillis() + FULL_LENGTH_RESIZE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentDisplaySizePx().first == widthPx) break
            Thread.sleep(FULL_HEIGHT_POLL_MS)
        }
        val actual = currentDisplaySizePx()
        if (actual.first != widthPx) {
            throw AssertionError("$caseId: window is ${actual.first}x${actual.second} after wm size ${widthPx}x$heightPx")
        }
        waitForIdleBounded()
        Thread.sleep(FULL_LENGTH_RESIZE_SETTLE_MS)
    }

    private fun restoreDisplaySize(caseId: String, base: Pair<Int, Int>) {
        // Restore the run script's pinned size, never `wm size reset` (that is
        // the emulator's physical 1440x3120 and would break every later case).
        runShellCommand("wm size ${base.first}x${base.second}")
        Thread.sleep(FULL_LENGTH_RESIZE_SETTLE_MS)
        Log.i(VISUAL_LOG_TAG, "$caseId full-length display restored to ${base.first}x${base.second}")
    }

    /**
     * Grow the display until the main scroller has nothing below the fold (see
     * [VisualFullLength]). Fails loud when a flagged case cannot be measured: a
     * silent one-screen fallback would publish a short image that looks green.
     */
    private fun expandToFullLength(caseId: String, base: Pair<Int, Int>): FullLengthResult {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val maxHeightPx = (VisualFullLength.DEFAULT_MAX_HEIGHT_DP * density).roundToInt()
        var heightPx = base.second
        var initialOverflow = 0
        var residual = 0
        var capped = false
        var iterations = 0
        var label: String? = null
        while (iterations < VisualFullLength.MAX_ITERATIONS) {
            val scroller = mainScroller()
            if (scroller == null) {
                residual = 0
                break
            }
            label = label ?: scroller.label
            val overflowPx = measureOverflowPx(caseId, scroller, maxHeightPx)
            if (iterations == 0) initialOverflow = overflowPx
            residual = overflowPx
            if (overflowPx <= 0) break
            // Grow in whole dp so the image height is a dp multiple, like React.
            val growPx = (kotlin.math.ceil(overflowPx / density) * density).roundToInt()
            val next = minOf(maxHeightPx, heightPx + growPx)
            if (next <= heightPx) {
                capped = true
                break
            }
            onActivityNoIdle { VisualFullLength.scrollToTop(windowRoots(it), scroller.nodeId) }
            resizeDisplay(caseId, base.first, next)
            heightPx = next
            iterations += 1
        }
        if (heightPx != base.second) {
            mainScroller()?.let { scroller -> onActivityNoIdle { VisualFullLength.scrollToTop(windowRoots(it), scroller.nodeId) } }
            waitForIdleBounded()
        }
        val result =
            FullLengthResult(
                baseHeightPx = base.second,
                expandedHeightPx = heightPx,
                density = density,
                initialOverflowPx = initialOverflow,
                residualOverflowPx = residual,
                iterations = iterations,
                capped = capped,
                maxHeightDp = VisualFullLength.DEFAULT_MAX_HEIGHT_DP,
                scroller = label
            )
        Log.i(
            VISUAL_LOG_TAG,
            "full-length case=$caseId base=${base.first}x${base.second} expanded=$heightPx " +
                "initialOverflowPx=$initialOverflow residualPx=$residual iterations=$iterations capped=$capped scroller=$label"
        )
        return result
    }

    /**
     * Component-bounds crop for the content-sized markdown message rows: the
     * React baseline crops the MessageItem element itself, so wait for the
     * host's reported bottom edge rather than cropping a full viewport.
     */
    private fun awaitThreadRowContentCrop(
        caseId: String,
        instanceId: String,
        widthDp: Int,
        insetDp: Int = 0
    ): VisualCrop {
        val token = "thread_visual_content_bounds instanceId=$instanceId "
        val deadline = SystemClock.uptimeMillis() + FULL_HEIGHT_TOKEN_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val bottomPx =
                logcatLastTokenLine(token)
                    ?.substringAfter("contentBottomPx=", "")
                    ?.trim()
                    ?.takeWhile { it.isDigit() }
                    ?.toIntOrNull()
            if (bottomPx != null && bottomPx > 0) {
                val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                // [insetDp]: the host's own padding around the row (React's p-4
                // page), cropped away on the top/left and off the bottom.
                val heightDp = (kotlin.math.ceil(bottomPx / density).toInt() - insetDp * 2).coerceIn(1, 844 - insetDp)
                Log.i(VISUAL_LOG_TAG, "$caseId content bottom ${bottomPx}px -> ${widthDp}x${heightDp}dp crop")
                return VisualCrop(leftDp = insetDp, topDp = insetDp, widthDp = widthDp, heightDp = heightDp)
            }
            Thread.sleep(FULL_HEIGHT_POLL_MS)
        }
        throw AssertionError("$caseId content-bounds token '$token' not seen within ${FULL_HEIGHT_TOKEN_TIMEOUT_MS}ms")
    }

    /** Last logcat line containing [token] (same tag-filtered snapshot read as [logcatContainsToken]). */
    private fun logcatLastTokenLine(token: String): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-s", REACTION_ATLAS_LOG_TAG)
            )
            process.inputStream.bufferedReader().useLines { lines ->
                lines.lastOrNull { it.contains(token) }
            }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "logcatLastTokenLine failed: ${t.message}")
            null
        } finally {
            process?.destroy()
        }
    }

    /** Best-effort shell command via UiAutomation (drains the fd so the command completes). */
    private fun runShellCommand(command: String) {
        try {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "shell command '$command' failed: ${t.message}")
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
                        .put(
                            server(
                                "srv_design",
                                "Slock Design",
                                "slock-design",
                                24,
                                avatarUrl = "https://avatars.githubusercontent.com/u/9919?s=64&v=4"
                            )
                        )
                        .put(
                            server(
                                "srv_android",
                                "Android Artifacts",
                                "android-artifacts",
                                12,
                                avatarUrl = "https://invalid.invalid/server-avatar.png"
                            )
                        )
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

    private fun server(
        id: String,
        name: String,
        slug: String,
        unreadCount: Int,
        avatarUrl: String? = null
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("slug", slug)
            .put("unreadCount", unreadCount)
            .also { server -> avatarUrl?.let { server.put("avatarUrl", it) } }

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

    private fun visualValidationProblems(
        bitmap: Bitmap,
        stats: VisualBitmapStats,
        crop: VisualCrop? = null
    ): List<String> {
        val problems = mutableListOf<String>()
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            problems += "bitmap is too small: ${bitmap.width}x${bitmap.height}"
        }
        if (stats.uniqueColors <= 2) {
            problems += "bitmap has only ${stats.uniqueColors} unique color(s)"
        }
        val requiredSolidColors = crop?.minSolidContentColors ?: 0
        if (requiredSolidColors > 0) {
            // A case whose web baseline is mostly background declares what its
            // content must paint instead of relying on the one-colour proxy.
            val solidColors = bitmap.solidContentColorCount()
            if (solidColors < requiredSolidColors) {
                problems += "bitmap paints $solidColors solid content color(s) " +
                    "(>= $SOLID_CONTENT_COLOR_MIN_PIXELS px each besides the background); " +
                    "the case declares $requiredSolidColors"
            }
        } else if (stats.dominantRatio >= 0.985f) {
            problems += "bitmap is ${"%.2f".format(stats.dominantRatio * 100)}% one color"
        }
        return problems
    }

    /**
     * Colours other than the most common one (the background) that each cover at
     * least [SOLID_CONTENT_COLOR_MIN_PIXELS] pixels: the fills and ink a sparse
     * component really painted, not anti-aliased edge blends. Counts every pixel,
     * so it is meant for small component crops only.
     */
    private fun Bitmap.solidContentColorCount(): Int {
        val counts = HashMap<Int, Int>()
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                val opaque = Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                counts[opaque] = (counts[opaque] ?: 0) + 1
            }
        }
        val background = counts.maxByOrNull { it.value }?.key
        return counts.count { (color, pixels) -> color != background && pixels >= SOLID_CONTENT_COLOR_MIN_PIXELS }
    }

    private fun shouldTryUiAutomationFallback(stats: VisualBitmapStats): Boolean =
        stats.uniqueColors <= 2 || stats.dominantRatio >= 0.985f

    private fun Bitmap.visualStats(): VisualBitmapStats {
        // Bounded sampling (task #370): a FULL_VIEWPORT frame is 1170x2532 ≈ 3M
        // pixels, and putting every one into a boxed HashMap<Int,Int> took
        // 88-150s per call on a degraded CI runner — and visualStats runs every
        // stable-capture iteration + again in visualMetadata, so member cases
        // blew past the 150s per-case timeout (TestTimedOutException in
        // HashMap.put, the real tail-drop hang; NOT launch/runOnMainSync). The
        // stats only feed the blank check (uniqueColors<=2, dominantRatio>=0.985
        // in visualValidationProblems; #393 also keys blank off uniqueColors=1),
        // for which a bounded sample is statistically equivalent. INVARIANT
        // preserved: a pure/blank frame samples uniqueColors=1 at ANY stride
        // (every sample is the same color), so the blank signal is exact; only
        // the absolute uniqueColors of a RICH frame is sample-relative (a rich
        // frame still samples >2 distinct colors, so the <=2 threshold — the sole
        // consumer — is unaffected). dominantRatio is accurate to <0.1% here.
        // Stride so total samples cap at ~SAMPLE_TARGET (small crops fall through
        // to stride 1 = exact, unchanged).
        val totalPixels = width.toLong() * height.toLong()
        val stride =
            if (totalPixels <= VISUAL_STATS_SAMPLE_TARGET) 1
            else maxOf(1, kotlin.math.sqrt(totalPixels.toDouble() / VISUAL_STATS_SAMPLE_TARGET).toInt())
        val counts = HashMap<Int, Int>()
        val row = IntArray(width)
        var sampled = 0L
        var y = 0
        while (y < height) {
            getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                val pixel = row[x]
                val opaque = Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                counts[opaque] = (counts[opaque] ?: 0) + 1
                sampled++
                x += stride
            }
            y += stride
        }
        val dominant = counts.values.maxOrNull() ?: 0
        return VisualBitmapStats(
            uniqueColors = counts.size,
            dominantRatio = if (sampled == 0L) 1f else dominant.toFloat() / sampled.toFloat()
        )
    }

    private fun tapDp(xDp: Float, yDp: Float) {
        awaitFixtureIsActiveInputWindow("Tap target fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val y = yDp * density
        val downTime = SystemClock.uptimeMillis()
        val downInjected =
            instrumentation.uiAutomation.injectInputEvent(
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0),
                true
            )
        Thread.sleep(TAP_DWELL_MS)
        val upInjected =
            instrumentation.uiAutomation.injectInputEvent(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0),
                true
            )
        require(downInjected && upInjected) {
            "UiAutomation rejected tap at (${xDp}dp, ${yDp}dp): down=$downInjected up=$upInjected"
        }
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
              "clampedToShortWindow": $lastCaptureClampedToShortWindow,
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
        captureMethod: String,
        routePageName: String? = null,
        fixtureKey: String? = null,
        fixtureRevision: String? = null,
        renderedGoalSha256: String? = null
    ): String {
        val stats = bitmap.visualStats()
        val diagnostics = collectDiagnosticsJson(context)
        val capturedAtEpochMs = System.currentTimeMillis()
        val capturedAtMonotonicNs = SystemClock.elapsedRealtimeNanos()
        val attempt = visualAttempt()
        val generation = visualGeneration()
        val bootId = visualBootId()
        val appLocale = activeCaptureLocale?.languageTag?.let { "\"$it\"" } ?: "null"
        val stringDescLocale =
            activeCaptureLocale?.kmmResourceLocaleTag?.let { "\"$it\"" } ?: "null"
        val route =
            JSONObject()
                .put("pageName", routePageName)
                .put("fixtureKey", fixtureKey)
                .put("fixtureRevision", fixtureRevision)
                .put("renderedGoalSha256", renderedGoalSha256)
                .put("realProductRoute", routePageName == "compose_settings_route_visual")
        return """
            {
              "provider": "android",
              "providerType": "android-kuikly-shared-page",
              "source": "kmp-shared",
              "caseId": "$caseId",
              "appLocale": $appLocale,
              "stringDescLocale": $stringDescLocale,
              "route": $route,
              "image": "${imageFile.absolutePath}",
              "viewport": { "width": ${bitmap.width}, "height": ${bitmap.height}, "density": 1 },
              "systemBars": "hidden",
              "crop": {
                "mode": "manual-rect",
                "contract": "${crop.contract}",
                "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
              },
              "capture": {
                "method": "${lastCaptureMethod ?: captureMethod}",
                "fullscreen": true,
                "systemBars": "hidden",
                "validation": {
                  "uniqueColors": ${stats.uniqueColors},
                  "dominantRatio": ${stats.dominantRatio}
                },
                "readyTokenObserved": $lastReadyTokenObserved,
                "readyTokenRequired": $lastReadyTokenRequired,
                "stableFrameCount": $lastStableFrameCount
              },
              "diagnostics": $diagnostics,
              "captureIdentity": {
                "attempt": $attempt,
                "generation": "$generation",
                "bootId": "$bootId",
                "capturedAtEpochMs": $capturedAtEpochMs,
                "capturedAtMonotonicNs": $capturedAtMonotonicNs
              },
              "clampedToShortWindow": $lastCaptureClampedToShortWindow,
              "fullLength": ${lastFullLength?.toJson() ?: "null"},
              "capturedAt": "$capturedAtEpochMs"
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
                "visibleContentLeafViews": ${counts[2]},
                "targetPackage": "${context.packageName}",
                "processId": ${android.os.Process.myPid()},
                "processUid": ${android.os.Process.myUid()}
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

    // Failure-path evidence (wg-nightly task #16). When the render-wait gate
    // gives up, the caller's own assert usually fires next and the
    // exception/timeout path preserves no pixels — so the run where evidence
    // matters most used to publish none (nightly 31050654692: eight polls of
    // changedRatio≈0 could not distinguish "tap coordinates missed the row"
    // from "row hit but the open was gated elsewhere"). Persist the
    // before/last-poll frames and a bounded view-tree dump so that distinction
    // is one artifact read. Best-effort by design: this writer must never
    // throw over the caller's own (more precise) assertion failure.
    private fun writeRenderFailDiagnostics(
        label: String,
        before: Bitmap,
        lastPoll: Bitmap,
        polls: Int,
        elapsedMs: Long,
        lastChangedRatio: Float,
        retried: Boolean,
    ) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = visualOutputDir(context)
            dir.mkdirs()
            val beforeFile = File(dir, "$label.render-fail.before.png")
            beforeFile.outputStream().use { out ->
                before.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val afterFile = File(dir, "$label.render-fail.after.png")
            afterFile.outputStream().use { out ->
                lastPoll.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // View bounds answer "what was actually under the gesture point".
            // The walk is main-thread work; a wedged main degrades to
            // viewTree=null instead of blocking the shard (runOnMainSyncBounded
            // throws, caught here).
            var viewTree: JSONArray? = null
            var viewTreeNote = "ok"
            try {
                onActivityNoIdle { activity ->
                    viewTree = dumpViewTreeJson(activity.window.decorView.rootView)
                }
                if (viewTree == null) viewTreeNote = "no RESUMED activity"
            } catch (t: Throwable) {
                viewTreeNote = "view-tree walk unavailable: ${t.message}"
            }
            val dm = context.resources.displayMetrics
            val file = File(dir, "$label.render-fail.json")
            file.writeText(
                JSONObject()
                    .put("provider", "android")
                    .put("label", label)
                    .put("status", "render-gate-timeout")
                    .put("polls", polls)
                    .put("elapsedMs", elapsedMs)
                    .put("lastChangedRatio", lastChangedRatio.toDouble())
                    .put("retriedGesture", retried)
                    .put("beforeImage", beforeFile.name)
                    .put("afterImage", afterFile.name)
                    .put("displayWidthPx", dm.widthPixels)
                    .put("displayHeightPx", dm.heightPixels)
                    .put("densityDpi", dm.densityDpi)
                    .put("viewTreeNote", viewTreeNote)
                    .put("viewTree", viewTree ?: JSONObject.NULL)
                    // Attempt/generation let a reader match this evidence to
                    // the final failure.json when the workflow-level retry ran
                    // the case more than once (stale-evidence disambiguation).
                    .put("attempt", visualAttempt())
                    .put("generation", visualGeneration())
                    .put("capturedAt", System.currentTimeMillis())
                    .toString(2) + "\n"
            )
            mirrorFileForAdbPull(context, beforeFile)
            mirrorFileForAdbPull(context, afterFile)
            mirrorFileForAdbPull(context, file)
            Log.i(
                VISUAL_LOG_TAG,
                "$label render gate timed out; wrote render-fail evidence " +
                    "(${beforeFile.name}, ${afterFile.name}, ${file.name})"
            )
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "writeRenderFailDiagnostics failed for $label: ${t.message}")
        }
    }

    // Exact artifact names only, never a label/caseId prefix match (#461's
    // lesson: a prefix would delete a longer sibling case's files).
    private fun renderFailArtifactNames(label: String): List<String> =
        listOf(
            "$label.render-fail.before.png",
            "$label.render-fail.after.png",
            "$label.render-fail.json",
        )

    // A green gate erases the stale evidence a prior red attempt left (the
    // workflow retries whole cases), so a published artifact containing
    // render-fail files always describes the shard's final state.
    private fun deleteRenderFailDiagnostics(label: String) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = visualOutputDir(context)
            val names = renderFailArtifactNames(label)
            names.forEach { name -> File(dir, name).delete() }
            VisualAndroidArtifacts.deleteMirroredFiles(names)
        } catch (t: Throwable) {
            Log.w(VISUAL_LOG_TAG, "deleteRenderFailDiagnostics failed for $label: ${t.message}")
        }
    }

    private fun dumpViewTreeJson(root: View): JSONArray {
        val out = JSONArray()
        val loc = IntArray(2)
        fun walk(view: View, depth: Int) {
            if (out.length() >= RENDER_FAIL_VIEW_TREE_MAX_NODES) return
            view.getLocationOnScreen(loc)
            out.put(
                JSONObject()
                    .put("depth", depth)
                    .put("class", view.javaClass.simpleName)
                    .put("xPx", loc[0])
                    .put("yPx", loc[1])
                    .put("widthPx", view.width)
                    .put("heightPx", view.height)
                    .put("visible", view.visibility == View.VISIBLE)
            )
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return out
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
        val captureMethod: String = "app-window-root-draw-manual-crop-density-resize",
        // > 0 for a component whose web case is genuinely mostly background: the
        // blank-frame check then requires this many solid content colours
        // instead of rejecting any frame that is >= 98.5% one colour.
        val minSolidContentColors: Int = 0
    )

    private data class VisualBitmapStats(
        val uniqueColors: Int,
        val dominantRatio: Float
    )

    companion object {
        private const val PENDING_MENTION_VIEW_ACTION_TIMEOUT_MS = 10_000L
        private const val ACCESSIBILITY_TREE_MAX_DEPTH = 60
        // A 4dp x 4dp solid patch at the pinned 480dpi (3x). Measured on the
        // status dots of the former components.ui.status-dot.states case: the
        // smallest dot fill is 196 px and the largest anti-aliased edge blend 63 px.
        private const val SOLID_CONTENT_COLOR_MIN_PIXELS = 144
        private const val UI_AUTOMATION_CAPTURE_METHOD =
            "ui-automation-take-screenshot-viewport-crop"

        private val FULL_VIEWPORT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 844,
                contract = "viewport-bounds",
                captureMethod = "app-window-root-draw-viewport-density-resize"
            )
        // The wide lane's display: 1280x800dp (2560x1600 at 320dpi), the web's wide size.
        private val WIDE_VIEWPORT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 1280,
                heightDp = 800,
                contract = "viewport-bounds",
                captureMethod = "app-window-root-draw-viewport-density-resize"
            )
        private val THREAD_COMPOSER_MODAL_VIEWPORT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 844,
                contract = "viewport-bounds",
                captureMethod = "pixel-copy-app-window-viewport-crop"
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
                heightDp = 60
            )
        private val ANDROID_FLOATING_TABBAR_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 92
            )
        private val ANDROID_FLOATING_TABBAR_SAFE_AREA_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 116
            )
        private val LUCIDE_CATALOG_ATLAS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 798,
            )
        private val LUCIDE_STATE_ATLAS_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 84,
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
                heightDp = 430
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
        private val UI_CARD_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 190
            )
        private val UI_FORM_FIELD_CROP =
            // 296dp matches the React form-field frame and contains all three states + bottom
            // padding in both registers (XiShi capture-contract, run 30837350660); the prior
            // 226dp truncated the 3rd field's error input + "Name is required".
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 296
            )
        private val UI_TEXTAREA_CROP =
            // 252dp = max(brutal) textarea content height; contains both textareas' full error/
            // counter rows + bottom padding in all three platforms (XiShi capture-contract). The
            // prior 240dp dropped the 2nd error row ("Agreement body must be shorter." + 57/40) on
            // iOS. Matches the UiTextareaVisualContent 252dp frame.
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 252
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
        private val UI_TYPOGRAPHY_SPECIMEN_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 300
            )
        private val UI_SKELETON_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 150
            )
        private val UI_SLUG_INPUT_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 342,
                heightDp = 122
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
                heightDp = 178
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
        // The host lays the composer out as web's 342x100 canvas does (20dp in, 8dp down),
        // so the window is that canvas: the 342dp component box, 24dp from the screen edge.
        // The web case's box follows the composer (slock VisualTestingCases `height: "auto"`):
        // 108dp with the one-line placeholder, 132dp with the two-line seed draft. A fixed
        // 100dp cut the two-line composer's toolbar and bottom edge.
        private val THREAD_COMPOSER_EMPTY_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 108
            )
        private val THREAD_COMPOSER_TWO_LINE_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 132
            )
        private val THREAD_COMPOSER_PENDING_MENTION_ACTIONS_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 290
            )
        private val THREAD_COMPOSER_SUGGESTIONS_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 320
            )
        // React `components.thread.composer.member-suggestions`: `composerOffsetTop: 250` with
        // the height following the composer, a 342x358 capture.
        private val THREAD_COMPOSER_MEMBER_SUGGESTIONS_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 358
            )
        // slock VisualTestingCases: the image-preview case box is 210px tall (file-previews 270).
        private val THREAD_COMPOSER_IMAGE_PREVIEW_CROP =
            VisualCrop(
                leftDp = 24,
                topDp = 0,
                widthDp = 342,
                heightDp = 210
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
        // 374x109dp) — the row card inside the fixture's RaftAppSpacing.Md (16dp)
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
        private val THREAD_MESSAGE_ROW_SAVED_CROP =
            VisualCrop(
                leftDp = 16,
                topDp = 16,
                widthDp = 374,
                heightDp = 140
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
        // Full 390dp screen width (task #400): the message row lays out across
        // the whole screen, so the old 342dp crop cut its right edge and shifted
        // every wrap point vs React. React's harness container is 390px to match.
        private val THREAD_MESSAGE_ROW_LONG_INLINE_CODE_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 390,
                heightDp = 844
            )
        // Narrow 260dp column (matching the React 260px harness container) for
        // the markdown message-row wrap cases: the narrow width forces a
        // channel/task chip to wrap to the next line (inline-box line-wrap bug).
        private val THREAD_MESSAGE_ROW_MD_WRAP_CROP =
            VisualCrop(
                leftDp = 0,
                topDp = 0,
                widthDp = 260,
                heightDp = 844
            )

        @BeforeClass
        @JvmStatic
        fun clearVisualOutputDirectory() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // Targeted capture normally runs one case per instrumentation
            // process. Every process executes @BeforeClass, but all processes
            // share one public artifact directory that the host pulls only
            // after the full selector loop. Resetting here unconditionally
            // therefore erased every case except the last one. The harness
            // explicitly sends true for process 0 and false thereafter;
            // absent/malformed values retain the safe legacy reset behavior.
            val shouldResetPublicOutput =
                InstrumentationRegistry.getArguments()
                    .getString(VISUAL_RESET_PUBLIC_OUTPUT_ARG)
                    ?.equals("false", ignoreCase = true) != true
            if (shouldResetPublicOutput) {
                VisualAndroidArtifacts.resetPublicOutput()
                // Start the shard's aggregate per-case timing log clean only
                // once. Later processes append their START/OK lines.
                File(
                    instrumentation.targetContext.filesDir,
                    "visual-testing/android/$CAPTURE_TIMING_FILE"
                ).delete()
            } else {
                VisualAndroidArtifacts.ensurePublicOutput()
            }
            // Suppress the immersive-fullscreen education overlay before any case
            // enters immersive (task #395).
            confirmImmersiveEducationDismissed()
        }

        /**
         * Mark Android's immersive-mode education overlay as already confirmed so
         * it never appears during capture (task #395).
         *
         * [applyVisualFullscreen] forces immersive-sticky fullscreen (hidden
         * status/nav bars via BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) so the decor
         * view fills the full 1170x2532 display and the FULL_VIEWPORT crop lines
         * up (task #370). The first time an app enters immersive-sticky on a
         * device, SystemUI shows a transient "Viewing full screen / swipe down to
         * exit / Got it" education overlay that also dims the page. Software
         * root.draw() misses it, but Kuikly's hardware-rendered member-detail
         * pages fall through to the UiAutomation.takeScreenshot() fallback in
         * [captureActivityScreenshot], which grabs the real system surface — so
         * the overlay leaked into those captures (e.g. screens.members.agent-
         * detail.profile) and is a baseline-flake source.
         *
         * Setting the `immersive_mode_confirmations` secure value to `confirmed`
         * tells SystemUI the education was already acknowledged, so the overlay is
         * suppressed device-wide for the run. It is global device state, so once
         * per shard JVM (before the first case enters immersive) is sufficient and
         * adds zero per-case cost. Best-effort: a failure here only means the
         * overlay might reappear, never a capture failure.
         */
        private fun confirmImmersiveEducationDismissed() {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            try {
                val fd = uiAutomation.executeShellCommand(
                    "settings put secure immersive_mode_confirmations confirmed"
                )
                // Drain + close the pipe so the shell command completes and the fd
                // is not leaked.
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            } catch (t: Throwable) {
                Log.w(VISUAL_LOG_TAG, "immersive education suppression failed: ${t.message}")
            }
        }

        private const val STABLE_CAPTURE_TIMEOUT_MS = 20_000L
        private const val STABLE_CAPTURE_RETRY_MS = 750L
        private const val APP_DIALOG_WINDOWS_CAPTURE_SUFFIX = "+app-dialog-windows"
        private const val DIALOG_IME_DISMISS_SETTLE_MS = 1_000L
        private const val EDITABLE_FIELD_LOOKUP_TIMEOUT_MS = 10_000L
        private const val VISUAL_READY_TIMEOUT_MS = 10_000L

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
        // Kuikly bridge host tag the token rides (RaftCmpActivity ->
        // RaftAndroidLocalDiagnostics.recordLog(level, "SlockKuikly", content)).
        private const val REACTION_ATLAS_LOG_TAG = "SlockKuikly"
        private const val REACTION_ATLAS_TIMEOUT_MS = 20_000L
        private const val REACTION_ATLAS_POLL_MS = 250L
        // Brief settle after the token so the next recomposition swaps emoji->
        // sprite before we snapshot (the token means "atlas ready", the draw
        // switches on the following frame).
        private const val REACTION_ATLAS_SETTLE_AFTER_MS = 600L

        // Full-height Settings captures (task #388): display grown so the whole
        // route composes in one frame; crop height comes from the render-side
        // sentinel token. 7500px ≈ 3 viewports, under common GL texture limits.
        private const val FULL_HEIGHT_TOKEN_TIMEOUT_MS = 20_000L
        private const val FULL_HEIGHT_POLL_MS = 250L
        // Full-length capture (sharedCases.json capture.fullLength).
        private const val FULL_LENGTH_MAX_SCROLL_STEPS = 40
        private const val FULL_LENGTH_SCROLL_SETTLE_MS = 350L
        private const val FULL_LENGTH_RESIZE_TIMEOUT_MS = 8_000L
        private const val FULL_LENGTH_RESIZE_SETTLE_MS = 800L

        // Logcat tag for per-case timing (CASE_START/CASE_END) and idle-wait
        // warnings — grep this out of the uploaded logcat artifact (task #362).
        private const val VISUAL_LOG_TAG = "VisualCapture"

        private const val FORWARD_PROBE_COMPARE_TOP_FRACTION = 0.30f
        private const val FORWARD_PROBE_COMPARE_BOTTOM_FRACTION = 0.96f
        private const val FORWARD_PROBE_MIN_SCROLL_CHANGED_RATIO = 0.04f
        private const val FORWARD_PROBE_MIN_TAP_CHANGED_RATIO = 0.04f
        private const val FORWARD_PROBE_MIN_COLOR_DISTANCE = 36
        private const val FORWARD_PROBE_YELLOW_TOLERANCE = 20
        private const val FORWARD_PROBE_PIXEL_STRIDE = 4
        private const val THREAD_TAP_PROBE_MIN_ADDED_YELLOW_SAMPLES = 100
        private const val THREAD_CHILD_ACTION_PROBE_TOLERANCE = 12
        private const val THREAD_CHILD_ACTION_PROBE_MIN_ADDED_SAMPLES = 100
        // Bounds for captureAfterLongPressRendered: wait for the long-press UI to
        // actually render instead of a fixed sleep. Timeout must comfortably
        // exceed a loaded-shard menu render; retry re-issues the press once.
        // task #228: the first observation alone costs ~2-3s (700ms press + fixture
        // readiness + capture), and the menu's black chrome paints after the press
        // ripple. A 4s budget left only one or two polls, so whether the gate saw
        // the finished menu came down to scheduling jitter. Poll to a generous
        // deadline instead: polling early is free, polling late does not misjudge,
        // and no constant has to encode "how long the menu takes" - a quantity we
        // cannot measure reliably, since readings from different runs cannot be
        // combined (per-run variance is the defect under investigation).
        private const val LONG_PRESS_RENDER_TIMEOUT_MS = 12_000L
        private const val LONG_PRESS_RENDER_POLL_MS = 200L
        // Kept only as a floor for the no-response case; the retry is suppressed
        // once the screen has reacted (see LONG_PRESS_RETRY_SUPPRESS_CHANGED_RATIO),
        // because re-pressing then disturbs the state the gate is waiting on.
        private const val LONG_PRESS_RENDER_RETRY_AT_MS = 6_000L
        private const val LONG_PRESS_INPUT_READY_SETTLE_MS = 100L

        private const val LONG_PRESS_RETRY_SUPPRESS_CHANGED_RATIO = 0.002f

        private const val SEARCH_PIN_MENU_MIN_CHANGED_RATIO = 0.004f
        private const val THREAD_OPEN_PROBE_TOP_BAND_FRACTION = 0.15f
        private const val FORWARD_PROBE_SWIPE_STEPS = 12
        private const val FORWARD_PROBE_SWIPE_DURATION_MS = 360L

        // Shard-scoped per-case timing log carried in the results artifact.
        private const val CAPTURE_TIMING_FILE = "_capture-timing.log"
        private const val VISUAL_RESET_PUBLIC_OUTPUT_ARG = "visualResetPublicOutput"

        // Cap the diagnostics view-tree walk so it never dominates a capture.
        private const val DIAGNOSTICS_MAX_VIEWS = 2_000

        // Render-fail view-tree dump cap (wg-nightly task #16): enough nodes to
        // locate what sat under a gesture point, small enough that the JSON
        // stays readable in a published artifact.
        private const val RENDER_FAIL_VIEW_TREE_MAX_NODES = 400

        // Cap visualStats pixel sampling (task #370). A FULL_VIEWPORT frame is
        // ~3M pixels; hashing every one took 88-150s on degraded CI runners and
        // tripped the per-case timeout. 200k samples keep the blank check
        // accurate while cutting the work ~15x (small crops stay exact).
        private const val VISUAL_STATS_SAMPLE_TARGET = 200_000L

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
        private const val TAP_DWELL_MS = 80L
        private const val ACTIVE_INPUT_WINDOW_TIMEOUT_MS = 10_000L
        private const val ACTIVE_INPUT_WINDOW_POLL_MS = 250L
        // After this many failed BACK dismisses (~2s at 250ms poll) a system
        // dialog is treated as a hard-hung app and its owner is force-stopped.
        private const val PERSISTENT_DIALOG_FORCE_STOP_AFTER = 8
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
