package build.raft.app.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.RaftAspectFitImagePreviewSurface
import build.raft.app.RaftImageThumbnailContentScale
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.LocalRaftPageArgs
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.NetworkSettingsFeedbackSubmitRepository
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SettingsFeedbackDiagnosticsUploader
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.fill
import build.raft.app.format.RaftFormat
import build.raft.app.get
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.origin
import build.raft.app.post
import build.raft.app.raftArgbHex
import build.raft.app.raftFeedbackWebViewClientKind
import build.raft.app.raftLogInfo
import build.raft.app.raftShellStoreScope
import build.raft.app.resources.Res
import build.raft.app.resources.artifacts_column_title
import build.raft.app.resources.artifacts_dashboard_title
import build.raft.app.resources.channel_details_channel_settings
import build.raft.app.resources.common_cancel
import build.raft.app.resources.common_close
import build.raft.app.resources.common_dismiss
import build.raft.app.resources.drafts_row_task_untitled
import build.raft.app.resources.drafts_title
import build.raft.app.resources.file_preview_title
import build.raft.app.resources.home_nav_search
import build.raft.app.resources.member_label_profile
import build.raft.app.resources.members_page_title
import build.raft.app.resources.raftCurrentUiLocaleTag
import build.raft.app.resources.saved_title
import build.raft.app.resources.screenshot_feedback_context_page
import build.raft.app.resources.screenshot_feedback_diagnostics_preparing
import build.raft.app.resources.screenshot_feedback_diagnostics_ready
import build.raft.app.resources.screenshot_feedback_diagnostics_unavailable
import build.raft.app.resources.screenshot_feedback_note_placeholder
import build.raft.app.resources.screenshot_feedback_prompt_action
import build.raft.app.resources.screenshot_feedback_remove_screenshot
import build.raft.app.resources.screenshot_feedback_screenshot_label
import build.raft.app.resources.screenshot_feedback_screenshot_removed
import build.raft.app.resources.screenshot_feedback_sheet_title
import build.raft.app.resources.screenshot_feedback_submit
import build.raft.app.resources.screenshot_feedback_submitted_toast
import build.raft.app.resources.screenshot_feedback_submitting
import build.raft.app.resources.settings_account_title
import build.raft.app.resources.settings_computers_title
import build.raft.app.resources.settings_feedback_title
import build.raft.app.resources.settings_release_notes_title
import build.raft.app.resources.settings_storage_title
import build.raft.app.resources.settings_title
import build.raft.app.resources.stringResource
import build.raft.app.resources.tab_activity
import build.raft.app.resources.tab_apps
import build.raft.app.resources.tab_computers
import build.raft.app.resources.tab_home
import build.raft.app.resources.tab_members
import build.raft.app.resources.tab_settings
import build.raft.app.resources.tab_tasks
import build.raft.app.resources.thread_members_heading
import build.raft.app.resources.thread_reply_title
import build.raft.app.submit
import build.raft.app.thread.icon
import build.raft.app.toKuiklyJson
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftCloseButtonSize
import build.raft.designsystem.components.RaftComposerAttachment
import build.raft.designsystem.components.RaftComposerAttachmentImage
import build.raft.designsystem.components.RaftComposerAttachmentRemove
import build.raft.designsystem.components.RaftComposerAttachmentRemoveOverhang
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.rememberRaftStableWindowInsets
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.StringResource

internal const val RaftScreenshotFeedbackPromptPillTag = "screenshot_feedback_prompt_pill"
internal const val RaftScreenshotFeedbackPromptActionTag = "screenshot_feedback_prompt_action"
internal const val RaftScreenshotFeedbackPromptDismissTag = "screenshot_feedback_prompt_dismiss"
internal const val RaftScreenshotFeedbackSheetTag = "screenshot_feedback_sheet"
internal const val RaftScreenshotFeedbackThumbnailTag = "screenshot_feedback_thumbnail"
internal const val RaftScreenshotFeedbackRemoveScreenshotTag = "screenshot_feedback_remove_screenshot"
internal const val RaftScreenshotFeedbackContextTag = "screenshot_feedback_context"
internal const val RaftScreenshotFeedbackDiagnosticsTag = "screenshot_feedback_diagnostics"
internal const val RaftScreenshotFeedbackNoteInputTag = "screenshot_feedback_note_input"
internal const val RaftScreenshotFeedbackSubmitButtonTag = "screenshot_feedback_submit"
internal const val RaftScreenshotFeedbackCancelButtonTag = "screenshot_feedback_cancel"
internal const val RaftScreenshotFeedbackErrorTag = "screenshot_feedback_error"

private const val APP_SHELL_ROUTE_NAME = "compose_slock_app_shell"
internal const val SCREENSHOT_FEEDBACK_PROMPT_DEFAULT_BOTTOM_PADDING_DP = 20
internal const val SCREENSHOT_FEEDBACK_PROMPT_FALLBACK_TAB_BAR_CLEARANCE_DP = 72
internal const val SCREENSHOT_FEEDBACK_PROMPT_ABOVE_TAB_BAR_GAP_DP = 16

internal fun isScreenshotFeedbackAppShellHostPage(pageName: String?): Boolean {
    val normalized = pageName?.trim().orEmpty()
    return normalized == APP_SHELL_ROUTE_NAME || normalized == RAFT_APP_SHELL_CHILD_TAB_PAGE
}

/**
 * Resolves the prompt pill's bottom padding from the window's bottom edge for the **currently
 * visible** host page ([hostPageName]), never the page where the screenshot was taken
 * (`state.pageContext.pageName`):
 * - Non-shell pages (Settings such as `compose_settings_home` / `compose_app_shell_server_page`,
 *   Thread, Feedback, ...) sit [SCREENSHOT_FEEDBACK_PROMPT_DEFAULT_BOTTOM_PADDING_DP] above [safeBottom].
 * - App-shell pages (`compose_slock_app_shell` and defensive match `compose_app_shell_child_tab`)
 *   clear [activeShellBottomBarBandHeight] (already measured from the window bottom edge, including
 *   the home indicator / Liquid Glass floating `UITabBar`) by [SCREENSHOT_FEEDBACK_PROMPT_ABOVE_TAB_BAR_GAP_DP]
 *   when a bottom tab bar is showing, drop to the base padding when the shell reports `0.dp`
 *   (wide rail / desktop / hidden bar), and fall back to `safeBottom + 72.dp` when no live shell
 *   band has been reported yet.
 */
internal fun resolveScreenshotFeedbackPromptBottomPadding(
    hostPageName: String?,
    safeBottom: Dp,
    activeShellBottomBarBandHeight: Dp? = null,
): Dp {
    val clampedSafeBottom = safeBottom.coerceAtLeast(0.dp)
    val basePadding = clampedSafeBottom + SCREENSHOT_FEEDBACK_PROMPT_DEFAULT_BOTTOM_PADDING_DP.dp
    if (!isScreenshotFeedbackAppShellHostPage(hostPageName)) return basePadding
    return when {
        activeShellBottomBarBandHeight == null ->
            clampedSafeBottom + SCREENSHOT_FEEDBACK_PROMPT_FALLBACK_TAB_BAR_CLEARANCE_DP.dp
        activeShellBottomBarBandHeight > 0.dp ->
            maxOf(basePadding, activeShellBottomBarBandHeight + SCREENSHOT_FEEDBACK_PROMPT_ABOVE_TAB_BAR_GAP_DP.dp)
        else -> basePadding
    }
}

/**
 * Mounted globally inside every `RaftCmpRoot` (via `RaftCmpRouter.installOverlayProvider`).
 * Only the single foreground active root renders the floating prompt pill and bottom sheet,
 * ensuring the user never leaves the current page across Home, Thread, Task, and Settings.
 */
@Composable
internal fun RaftScreenshotFeedbackRootOverlay() {
    val args = LocalRaftPageArgs.current
    val host = LocalRaftHost.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowSize = LocalWindowInfo.current.containerSize
    val hostToken = remember { RaftScreenshotFeedbackRuntime.nextHostToken() }

    var lifecycleState by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> lifecycleState = event.targetState }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val latestArgs by rememberUpdatedState(args)

    DisposableEffect(hostToken, isResumed) {
        if (isResumed) {
            RaftScreenshotFeedbackRuntime.activateHost(hostToken, latestArgs)
        } else {
            RaftScreenshotFeedbackRuntime.deactivateHost(hostToken)
        }
        onDispose {
            RaftScreenshotFeedbackRuntime.deactivateHost(hostToken)
        }
    }
    LaunchedEffect(hostToken, isResumed, args) {
        if (isResumed) {
            RaftScreenshotFeedbackRuntime.updateHostPageArgs(hostToken, args)
        }
    }

    val activeHostToken by RaftScreenshotFeedbackRuntime.activeHostToken.collectAsState()
    val isOwner = isResumed && activeHostToken == hostToken

    val eligibilityFlow = remember { RaftScreenshotFeedbackRuntime.observeEligibility() }
    val eligible by eligibilityFlow.collectAsState(initial = RaftScreenshotFeedbackRuntime.isCurrentlyEligible())

    LaunchedEffect(isOwner, eligible) {
        if (isOwner && !eligible) {
            RaftScreenshotFeedbackRuntime.controller.onEligibilityLost()
        }
    }

    val latestWindowSize by rememberUpdatedState(windowSize)
    RaftPlatformScreenshotFeedbackObserverEffect(
        enabled = isOwner && eligible,
        pageContextProvider = {
            val session = RaftSharedStoreRegistry.currentOrNull()?.authStore?.readCommittedSessionSnapshot()
            extractScreenshotFeedbackPageContext(
                pageName = latestArgs.pageName,
                pageData = latestArgs.pageData,
                activeTab = RaftScreenshotFeedbackRuntime.currentActiveShellTab(),
                fallbackServerId = session?.currentServerId,
                viewportWidthPx = latestWindowSize.width,
                viewportHeightPx = latestWindowSize.height,
            )
        },
    )

    if (!isOwner) return
    val state by RaftScreenshotFeedbackRuntime.controller.state.collectAsState()
    if (state is RaftScreenshotFeedbackState.Idle) return
    val bottomBarBandRevision by RaftScreenshotFeedbackRuntime.shellBottomBarBandRevision.collectAsState()
    val activeBottomBarBandDp =
        remember(host, bottomBarBandRevision) {
            RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(host)
        }

    val log: (String) -> Unit = remember { { line -> raftLogInfo("RaftScreenshotFeedback", line) } }
    val diagnosticsUploader: SettingsFeedbackDiagnosticsUploader =
        remember(host.capabilities) {
            BridgeSettingsPlatformCapabilities(
                host = host.capabilities,
                copiedMessage = "",
                bridgeCallContext = Dispatchers.Main.immediate,
            ).feedbackDiagnosticsUploader()
        }

    RaftDesignSystemTheme {
        val submittedToastMessage = stringResource(Res.string.screenshot_feedback_submitted_toast)
        val latestSubmittedToastMessage by rememberUpdatedState(submittedToastMessage)

        RaftScreenshotFeedbackOverlayContent(
            state = state,
            onPromptTap = {
                val accountId =
                    RaftSharedStoreRegistry.currentOrNull()
                        ?.authStore
                        ?.readCommittedSessionSnapshot()
                        ?.user
                        ?.id
                        ?.trim()
                        .orEmpty()
                val startDiagnostics: (suspend () -> FeedbackDiagnosticsReference?)? =
                    if (accountId.isNotEmpty()) {
                        { diagnosticsUploader.upload(accountId) }
                    } else {
                        null
                    }
                RaftScreenshotFeedbackRuntime.controller.openSheet(
                    eligible = RaftScreenshotFeedbackRuntime.isCurrentlyEligible(),
                    startDiagnostics = startDiagnostics,
                )
            },
            onPromptDismiss = { RaftScreenshotFeedbackRuntime.controller.dismissPrompt() },
            onNoteChange = { RaftScreenshotFeedbackRuntime.controller.updateNote(it) },
            onFeedbackTypeChange = { RaftScreenshotFeedbackRuntime.controller.updateFeedbackType(it) },
            onDetachScreenshot = { RaftScreenshotFeedbackRuntime.controller.detachScreenshot() },
            onSheetDismiss = { RaftScreenshotFeedbackRuntime.controller.dismissSheet() },
            onSubmit = {
                val provider = RaftSharedStoreRegistry.get(host.capabilities)
                val session = provider.authStore.readCommittedSessionSnapshot()
                val accountId = session?.user?.id?.trim().orEmpty()
                val storeScope =
                    args.pageData.toKuiklyJson().raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                val networkClient = provider.networkClient(storeScope)
                val submitRepository =
                    NetworkSettingsFeedbackSubmitRepository(
                        networkClient = networkClient,
                        clientKind = raftFeedbackWebViewClientKind(),
                        clientVersion = RaftAppIdentityRegistry.current()?.version.orEmpty(),
                        logger = log,
                    )
                val submitter =
                    SettingsFeedbackProxySubmitter(
                        repository = submitRepository,
                        diagnostics = diagnosticsUploader,
                        accountId = accountId,
                        locale = ::raftCurrentUiLocaleTag,
                        logger = log,
                    )
                RaftScreenshotFeedbackRuntime.controller.submit(
                    eligible = RaftScreenshotFeedbackRuntime.isCurrentlyEligible(),
                    submitter = submitter,
                    onFiled = {
                        RaftCmpToasts.post(
                            message = latestSubmittedToastMessage,
                            origin = host.capabilities,
                            intent = RaftToastIntent.Success,
                        )
                    },
                )
            },
            hostPageName = args.pageName,
            activeShellBottomBarBandHeight = activeBottomBarBandDp?.dp,
        )
    }
}

/**
 * Pure presentation for Step 1 (floating bottom pill) and Step 2 (in-place bottom sheet).
 * Composable on its own for deterministic Robolectric UI testing.
 */
@Composable
internal fun RaftScreenshotFeedbackOverlayContent(
    state: RaftScreenshotFeedbackState,
    onPromptTap: () -> Unit,
    onPromptDismiss: () -> Unit,
    onNoteChange: (String) -> Unit,
    onFeedbackTypeChange: (SettingsFeedbackType) -> Unit,
    onDetachScreenshot: () -> Unit,
    onSheetDismiss: () -> Unit,
    onSubmit: () -> Unit,
    hostPageName: String? = null,
    activeShellBottomBarBandHeight: Dp? = null,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RaftScreenshotFeedbackState.Idle -> Unit
        is RaftScreenshotFeedbackState.Prompting -> {
            RaftScreenshotFeedbackPromptPill(
                state = state,
                hostPageName = hostPageName,
                activeShellBottomBarBandHeight = activeShellBottomBarBandHeight,
                onTap = onPromptTap,
                onDismiss = onPromptDismiss,
                modifier = modifier,
            )
        }
        is RaftScreenshotFeedbackState.Composing -> {
            RaftScreenshotFeedbackSheet(
                state = state,
                onNoteChange = onNoteChange,
                onFeedbackTypeChange = onFeedbackTypeChange,
                onDetachScreenshot = onDetachScreenshot,
                onDismiss = onSheetDismiss,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun RaftScreenshotFeedbackPromptPill(
    state: RaftScreenshotFeedbackState.Prompting,
    hostPageName: String?,
    activeShellBottomBarBandHeight: Dp?,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val reducedMotion = rememberRaftReducedMotion()
    val entry = remember(state.sessionId) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(state.sessionId, reducedMotion) {
        if (reducedMotion) {
            entry.snapTo(1f)
        } else if (entry.value < 1f) {
            entry.animateTo(1f, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
        }
    }
    val safeDrawing = WindowInsets.safeDrawing
    val horizontalSafeInsets =
        rememberRaftStableWindowInsets(safeDrawing.only(WindowInsetsSides.Horizontal))
    val pillShape = CircleShape

    // Non-blocking container: no full-screen click/pointer handler so touches pass through to the page.
    // Bottom clearance is resolved during layout (never reading rememberRaftStableWindowInsets during
    // composition, which would loop recomposition on iOS where WindowInsets.safeDrawing allocates per read).
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(horizontalSafeInsets)
                .padding(horizontal = RaftSpacing.Lg),
    ) {
        Box(
            modifier =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    val layoutHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                    layout(layoutWidth, layoutHeight) {
                        val safeBottom = safeDrawing.getBottom(this@layout).toDp()
                        val bottomPaddingPx =
                            resolveScreenshotFeedbackPromptBottomPadding(
                                hostPageName = hostPageName,
                                safeBottom = safeBottom,
                                activeShellBottomBarBandHeight = activeShellBottomBarBandHeight,
                            ).roundToPx()
                        val x =
                            Alignment.CenterHorizontally
                                .align(placeable.width, layoutWidth, layoutDirection)
                                .coerceAtLeast(0)
                        val y = (layoutHeight - placeable.height - bottomPaddingPx).coerceAtLeast(0)
                        placeable.place(x, y)
                    }
                },
        ) {
            Row(
                modifier =
                    Modifier
                        .testTag(RaftScreenshotFeedbackPromptPillTag)
                        .widthIn(max = 384.dp)
                        .graphicsLayer {
                            val hidden = 1f - entry.value
                            alpha = entry.value
                            translationY = 24.dp.toPx() * hidden
                        }
                        .shadow(elevation = if (RaftTheme.isDark) 8.dp else 4.dp, shape = pillShape)
                        .clip(pillShape)
                        .background(colors.layerPopover, pillShape)
                        .border(RaftStroke.Hairline, colors.lineSeam, pillShape)
                        .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RaftButton(
                    text = stringResource(Res.string.screenshot_feedback_prompt_action),
                    onClick = onTap,
                    modifier = Modifier.testTag(RaftScreenshotFeedbackPromptActionTag).weight(1f, fill = false),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Feedback) },
                )
                RaftCloseButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(RaftScreenshotFeedbackPromptDismissTag),
                    label = stringResource(Res.string.common_dismiss),
                    size = RaftCloseButtonSize.Dialog,
                )
            }
        }
    }
}

@Composable
private fun RaftScreenshotFeedbackSheet(
    state: RaftScreenshotFeedbackState.Composing,
    onNoteChange: (String) -> Unit,
    onFeedbackTypeChange: (SettingsFeedbackType) -> Unit,
    onDetachScreenshot: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = RaftTheme.colors
    val attachmentMaxLabel = RaftFormat.byteLimit(settingsFeedbackImageMaxBytes())
    val feedbackCopy = settingsHandsFeedbackCopy(attachmentMaxLabel)
    val focusRequester = remember(state.sessionId) { FocusRequester() }
    val errorText =
        state.errorCode?.let { code ->
            if (code == SCREENSHOT_FEEDBACK_MESSAGE_REQUIRED) {
                feedbackCopy.messageRequired
            } else {
                feedbackCopy.submissionFailure(code, state.errorRetryable).body
            }
        }

    LaunchedEffect(state.sessionId) {
        runCatching { focusRequester.requestFocus() }
    }

    RaftSheet(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = stringResource(Res.string.screenshot_feedback_sheet_title),
        modifier = Modifier.testTag(RaftScreenshotFeedbackSheetTag),
        showClose = true,
        closeLabel = stringResource(Res.string.common_close),
        rowActions = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
                enabled = !state.submitting,
                modifier = Modifier.weight(1f).testTag(RaftScreenshotFeedbackCancelButtonTag),
            )
            RaftButton(
                text =
                    stringResource(
                        if (state.submitting) {
                            Res.string.screenshot_feedback_submitting
                        } else {
                            Res.string.screenshot_feedback_submit
                        }
                    ),
                onClick = onSubmit,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = !state.submitting && state.note.isNotBlank(),
                loading = state.submitting,
                modifier = Modifier.weight(1f).testTag(RaftScreenshotFeedbackSubmitButtonTag),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!errorText.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftBanner(
                        description = errorText,
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.testTag(RaftScreenshotFeedbackErrorTag),
                    )
                    RaftTraceIdNote(state.errorTraceId)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val screenshot = state.screenshot.takeUnless { state.screenshotRemoved }
                if (screenshot != null) {
                    val previewUri = screenshot.previewUri?.takeIf { it.isNotBlank() } ?: screenshot.fileRef
                    RaftComposerAttachment(
                        modifier =
                            Modifier
                                .padding(top = RaftComposerAttachmentRemoveOverhang, end = RaftComposerAttachmentRemoveOverhang)
                                .testTag(RaftScreenshotFeedbackThumbnailTag),
                    ) {
                        RaftComposerAttachmentImage {
                            if (!previewUri.isNullOrBlank()) {
                                RaftAspectFitImagePreviewSurface(
                                    source = previewUri,
                                    contentDescription = stringResource(Res.string.screenshot_feedback_screenshot_label),
                                    modifier = Modifier.fillMaxSize(),
                                    borderWidth = 0.dp,
                                    contentScale = RaftImageThumbnailContentScale,
                                )
                            } else {
                                RaftLucideIcon(
                                    RaftLucideIconName.Image,
                                    modifier = Modifier.size(20.dp),
                                    color = raftArgbHex(colors.foregroundMuted),
                                )
                            }
                        }
                        if (!state.submitting) {
                            RaftComposerAttachmentRemove(
                                onClick = onDetachScreenshot,
                                contentDescription = stringResource(Res.string.screenshot_feedback_remove_screenshot),
                                modifier = Modifier.testTag(RaftScreenshotFeedbackRemoveScreenshotTag),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RaftText(
                        text =
                            stringResource(
                                Res.string.screenshot_feedback_context_page,
                                localizedScreenshotFeedbackPageLabel(state.pageContext),
                            ),
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(RaftScreenshotFeedbackContextTag),
                    )
                    ScreenshotFeedbackDiagnosticsStatusRow(state.diagnosticsState)
                    if (state.screenshotRemoved) {
                        RaftText(
                            text = stringResource(Res.string.screenshot_feedback_screenshot_removed),
                            style = RaftTheme.typography.caption,
                            color = colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            RaftSegmentedControl {
                RaftSegment(
                    text = feedbackCopy.bug,
                    selected = state.feedbackType == SettingsFeedbackType.Problem,
                    onClick = { onFeedbackTypeChange(SettingsFeedbackType.Problem) },
                    enabled = !state.submitting,
                    icon = { ScreenshotFeedbackTypeIcon(RaftLucideIconName.Bug) },
                )
                RaftSegment(
                    text = feedbackCopy.idea,
                    selected = state.feedbackType == SettingsFeedbackType.Idea,
                    onClick = { onFeedbackTypeChange(SettingsFeedbackType.Idea) },
                    enabled = !state.submitting,
                    icon = { ScreenshotFeedbackTypeIcon(RaftLucideIconName.Sparkles) },
                )
            }

            RaftTextarea(
                value = state.note,
                onValueChange = onNoteChange,
                placeholder = stringResource(Res.string.screenshot_feedback_note_placeholder),
                enabled = !state.submitting,
                minHeight = 76.dp,
                maxHeight = 140.dp,
                inputModifier =
                    Modifier
                        .focusRequester(focusRequester)
                        .testTag(RaftScreenshotFeedbackNoteInputTag),
            )
        }
    }
}

@Composable
private fun ScreenshotFeedbackDiagnosticsStatusRow(
    status: RaftScreenshotFeedbackDiagnosticsState,
) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier.testTag(RaftScreenshotFeedbackDiagnosticsTag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            RaftScreenshotFeedbackDiagnosticsState.Preparing -> {
                RaftSpinner(size = RaftSpinnerSize.Xs, color = colors.foregroundMuted)
                RaftText(
                    text = stringResource(Res.string.screenshot_feedback_diagnostics_preparing),
                    style = RaftTheme.typography.caption,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RaftScreenshotFeedbackDiagnosticsState.Ready -> {
                RaftLucideIcon(
                    RaftLucideIconName.Check,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(colors.foregroundMuted),
                )
                RaftText(
                    text = stringResource(Res.string.screenshot_feedback_diagnostics_ready),
                    style = RaftTheme.typography.caption,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RaftScreenshotFeedbackDiagnosticsState.Idle,
            RaftScreenshotFeedbackDiagnosticsState.Unavailable -> {
                RaftLucideIcon(
                    RaftLucideIconName.Info,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(colors.foregroundHint),
                )
                RaftText(
                    text = stringResource(Res.string.screenshot_feedback_diagnostics_unavailable),
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotFeedbackTypeIcon(name: RaftLucideIconName) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
}

internal fun localizedScreenshotFeedbackPageLabelResource(
    context: RaftScreenshotFeedbackPageContext,
): StringResource? {
    val normalizedPage = context.pageName.trim()
    val normalizedTab = context.activeTab?.trim()?.takeIf { it.isNotEmpty() }
    if ((normalizedPage == APP_SHELL_ROUTE_NAME || normalizedPage == RAFT_APP_SHELL_CHILD_TAB_PAGE) && normalizedTab != null) {
        return when (normalizedTab.lowercase()) {
            "home", "chat" -> Res.string.tab_home
            "activity", "inbox" -> Res.string.tab_activity
            "tasks" -> Res.string.tab_tasks
            "members" -> Res.string.tab_members
            "more", "settings" -> Res.string.tab_settings
            "computers" -> Res.string.tab_computers
            "apps" -> Res.string.tab_apps
            "search" -> Res.string.home_nav_search
            "artifacts" -> Res.string.artifacts_dashboard_title
            else -> null
        }
    }
    return when (normalizedPage) {
        APP_SHELL_ROUTE_NAME, "compose_home", "compose_app_shell_server_page" -> Res.string.tab_home
        "compose_home_activity" -> Res.string.tab_activity
        "compose_home_saved" -> Res.string.saved_title
        "compose_home_drafts" -> Res.string.drafts_title
        "compose_home_search", "compose_search" -> Res.string.home_nav_search
        "compose_thread", "compose_thread_forward_bundle_page" -> Res.string.thread_reply_title
        "compose_thread_members" -> Res.string.thread_members_heading
        "compose_channel_settings", "compose_thread_settings" -> Res.string.channel_details_channel_settings
        "compose_tasks", "compose_task_pending" -> Res.string.tab_tasks
        "compose_task_detail" -> Res.string.drafts_row_task_untitled
        "compose_members" -> Res.string.members_page_title
        "compose_member_detail" -> Res.string.member_label_profile
        "compose_settings_home", "compose_settings_native_detail", "compose_settings_webview", "compose_settings_labs" ->
            Res.string.settings_title
        "compose_settings_account" -> Res.string.settings_account_title
        "compose_settings_storage" -> Res.string.settings_storage_title
        "compose_settings_computers" -> Res.string.settings_computers_title
        "compose_settings_hands_feedback", "compose_settings_feedback_tickets" -> Res.string.settings_feedback_title
        "compose_release_notes" -> Res.string.settings_release_notes_title
        "compose_artifacts_home" -> Res.string.artifacts_dashboard_title
        "compose_artifact_viewer" -> Res.string.artifacts_column_title
        "compose_file_preview" -> Res.string.file_preview_title
        else -> if (normalizedPage.startsWith("compose_settings_")) Res.string.settings_title else null
    }
}

@Composable
private fun localizedScreenshotFeedbackPageLabel(
    context: RaftScreenshotFeedbackPageContext,
): String =
    localizedScreenshotFeedbackPageLabelResource(context)?.let { stringResource(it) }
        ?: context.displayPageLabel()
