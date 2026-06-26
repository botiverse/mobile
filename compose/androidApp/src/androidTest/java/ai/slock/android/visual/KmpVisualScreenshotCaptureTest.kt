package ai.slock.android.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import ai.slock.android.shared.SlockKuiklyActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.roundToInt

class KmpVisualScreenshotCaptureTest {
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
    fun captureMembersCreateAgentCodexDialog() {
        captureKmpComponentCase(
            caseId = "components.members.create-agent.dialog",
            pageName = "compose_create_agent_dialog_visual",
            pageData = createAgentDialogPageData(runtime = "codex"),
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
    fun captureMembersAgentDetailPermissionsScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.permissions",
            tab = "permissions"
        )
    }

    @Test
    fun captureMembersAgentDetailDmsScreen() {
        captureMembersAgentDetailScreen(
            caseId = "screens.members.agent-detail.dms",
            tab = "dms"
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
            tab = "apps"
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

    private fun captureKmpHomeCase(
        caseId: String,
        pageData: JSONObject,
        crop: VisualCrop = FULL_VIEWPORT_CROP
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_HOME)
                .putExtra("pageData", pageData.toString())
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(2_500)
            scenario.onActivity { suppressVisualKeyboard(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val screenshot = captureActivityScreenshot(scenario, caseId)
            validateCropBounds(caseId, screenshot, crop)
            val cropped = screenshot.cropDp(crop)
            val normalized = cropped.normalizeToDpPixels(context)
            validateVisualBitmap(context, caseId, normalized, crop)
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
            if (normalized !== cropped) {
                normalized.recycle()
            }
            if (cropped !== screenshot) {
                cropped.recycle()
            }
            screenshot.recycle()
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
                .apply {
                    if (pageData != null) {
                        putExtra("pageData", pageData.toString())
                    }
                }
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(settleMs)
            beforeScreenshot?.invoke()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { suppressVisualKeyboard(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val screenshot = captureActivityScreenshot(scenario, caseId)
            validateCropBounds(caseId, screenshot, crop)
            val cropped = screenshot.cropDp(crop)
            val normalized = cropped.normalizeToDpPixels(context)
            validateVisualBitmap(context, caseId, normalized, crop)
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
            if (normalized !== cropped) {
                normalized.recycle()
            }
            if (cropped !== screenshot) {
                cropped.recycle()
            }
            screenshot.recycle()
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
        command: String = ""
    ): JSONObject =
        JSONObject()
            .put("runtime", runtime)
            .put("providerMode", providerMode)
            .put("providerApiUrl", providerApiUrl)
            .put("providerApiKey", providerApiKey)
            .put("advancedOpen", advancedOpen)
            .put("command", command)

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
                        .put("permissions")
                        .put("dms")
                        .put("reminders")
                        .put("workspace")
                        .put("apps")
                        .put("activity")
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

    private fun Bitmap.normalizeToDpPixels(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val targetWidth = (width / density).roundToInt().coerceAtLeast(1)
        val targetHeight = (height / density).roundToInt().coerceAtLeast(1)
        if (targetWidth == width && targetHeight == height) return this
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
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

    private fun validateVisualBitmap(context: Context, caseId: String, bitmap: Bitmap, crop: VisualCrop) {
        val stats = bitmap.visualStats()
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
        if (problems.isNotEmpty()) {
            writeFailureArtifacts(context, caseId, bitmap, crop, stats, problems)
            throw AssertionError("$caseId visual capture failed validation: ${problems.joinToString("; ")}")
        }
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
        instrumentation.waitForIdleSync()
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
        }
    }
}
