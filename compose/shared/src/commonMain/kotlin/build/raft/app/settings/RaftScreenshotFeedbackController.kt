package build.raft.app.settings

import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftTelemetryPrivacy
import build.raft.app.data.SettingsFeedbackProxyAttempt
import build.raft.app.data.SettingsFeedbackProxyOutcome
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.ServerId
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.optString
import build.raft.app.raftFeedbackWebViewClientKind
import build.raft.app.raftLogInfo
import build.raft.app.submit
import build.raft.app.thread.key
import build.raft.app.traceId
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal const val SCREENSHOT_FEEDBACK_DEBOUNCE_MILLIS: Long = 1_500L
internal const val SCREENSHOT_FEEDBACK_PROMPT_AUTO_DISMISS_MILLIS: Long = 7_000L
internal const val SCREENSHOT_FEEDBACK_MESSAGE_REQUIRED: String = "settings_feedback_message_required"

/** Inputs deciding whether screenshot -> quick feedback may observe, prompt, capture, or submit. */
data class RaftScreenshotFeedbackEligibilityInput(
    val serverFeatureFlagEnabled: Boolean,
    val buildConfigAlphaEnabled: Boolean,
    val localOverrideMode: DiagnosticFeatureFlagOverrideMode = DiagnosticFeatureFlagOverrideMode.Server,
    val signedIn: Boolean,
    val privacyMode: RaftPrivacyMode,
)

/**
 * Product eligibility gate (#wg-mobile-feedback:#276):
 * `(serverFeatureFlagEnabled || buildConfigAlphaEnabled) && signedIn && privacyMode == Off`.
 *
 * Signed-out and privacy mode (`On` or `Unknown`) are non-overridable hard blocks. On entitled
 * developer builds, a local ForceOn/ForceOff override can toggle the flag branch without bypassing
 * the signed-in or privacy gates.
 */
fun isScreenshotFeedbackEligible(input: RaftScreenshotFeedbackEligibilityInput): Boolean {
    if (!input.signedIn) return false
    if (input.privacyMode != RaftPrivacyMode.Off) return false
    return when (input.localOverrideMode) {
        DiagnosticFeatureFlagOverrideMode.ForceOn -> true
        DiagnosticFeatureFlagOverrideMode.ForceOff -> false
        DiagnosticFeatureFlagOverrideMode.Server ->
            input.serverFeatureFlagEnabled || input.buildConfigAlphaEnabled
    }
}

/**
 * Resolves live screenshot feedback eligibility against [serverFeatureFlags] and the process-local
 * diagnostic override [registry], recording consumer evidence when a full server scope is present.
 */
internal fun resolveMobileScreenshotFeedbackEligible(
    serverFeatureFlags: SharedServerFeatureFlags?,
    registry: DiagnosticFeatureFlagOverrideRegistry,
    environmentId: String,
    accountId: String,
    serverId: String,
    buildConfigAlphaEnabled: Boolean,
    privacyMode: RaftPrivacyMode,
): Boolean {
    val signedIn = accountId.isNotBlank()
    if (!signedIn || privacyMode != RaftPrivacyMode.Off) return false
    val normalizedServerId = serverId.trim()
    val serverEffective =
        if (serverFeatureFlags != null && normalizedServerId.isNotEmpty()) {
            serverFeatureFlags.isEnabled(ServerId(normalizedServerId), MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY)
        } else {
            false
        }
    val normalizedEnv = environmentId.trim().trimEnd('/')
    val localMode =
        if (normalizedEnv.isNotEmpty() && normalizedServerId.isNotEmpty()) {
            registry.decide(
                key = MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY,
                serverEffective = serverEffective,
                scope =
                    DiagnosticFeatureFlagScope(
                        environmentId = normalizedEnv,
                        accountId = accountId,
                        serverId = normalizedServerId,
                    ),
            ).clientOverride
        } else {
            val snapshot = registry.snapshot(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY)
            if (snapshot.entitled) snapshot.mode else DiagnosticFeatureFlagOverrideMode.Server
        }
    return isScreenshotFeedbackEligible(
        RaftScreenshotFeedbackEligibilityInput(
            serverFeatureFlagEnabled = serverEffective,
            buildConfigAlphaEnabled = buildConfigAlphaEnabled,
            localOverrideMode = localMode,
            signedIn = true,
            privacyMode = privacyMode,
        )
    )
}

/** Structured non-PII context captured when a screenshot is taken. */
data class RaftScreenshotFeedbackPageContext(
    val pageName: String,
    val activeTab: String? = null,
    val serverId: String? = null,
    val channelId: String? = null,
    val threadId: String? = null,
    val taskId: String? = null,
    val taskNumber: String? = null,
    val viewportSize: String? = null,
    val orientation: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val platform: String? = null,
) {
    /** Compact display label shown inside the quick feedback sheet. */
    fun displayPageLabel(): String {
        val normalizedPage = pageName.trim()
        val normalizedTab = activeTab?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedPage == APP_SHELL_PAGE_NAME && normalizedTab != null) {
            return formatTabDisplayName(normalizedTab)
        }
        return KnownPageLabels[normalizedPage]
            ?: normalizedPage
                .removePrefix("compose_slock_")
                .removePrefix("compose_")
                .split('_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
                .ifBlank { "App" }
    }

    /** Clean structured context block appended to the feedback message on submit. */
    fun formatSubmissionMetadataBlock(): String {
        val lines =
            buildList {
                add("Source: screenshot_feedback")
                val pageParts =
                    buildList {
                        pageName.trim().takeIf { it.isNotEmpty() }?.let(::add)
                        activeTab?.trim()?.takeIf { it.isNotEmpty() }?.let { add("tab=$it") }
                    }
                if (pageParts.isNotEmpty()) add("Page: ${pageParts.joinToString(" · ")}")
                serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Server: $it") }
                val targetParts =
                    buildList {
                        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("channel=$it") }
                        threadId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("thread=$it") }
                        taskId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("task=$it") }
                        taskNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { add("taskNumber=$it") }
                    }
                if (targetParts.isNotEmpty()) add("Target: ${targetParts.joinToString(", ")}")
                val viewportParts =
                    buildList {
                        viewportSize?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        orientation?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                    }
                if (viewportParts.isNotEmpty()) add("Viewport: ${viewportParts.joinToString(" ")}")
                val appParts =
                    buildList {
                        val version = appVersion?.trim().orEmpty()
                        val build = buildNumber?.trim().orEmpty()
                        when {
                            version.isNotEmpty() && build.isNotEmpty() -> add("$version ($build)")
                            version.isNotEmpty() -> add(version)
                            build.isNotEmpty() -> add("build $build")
                        }
                        platform?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                    }
                if (appParts.isNotEmpty()) add("App: ${appParts.joinToString(" · ")}")
            }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("---")
            appendLine("Context")
            lines.forEach(::appendLine)
        }.trimEnd()
    }

    private companion object {
        const val APP_SHELL_PAGE_NAME = "compose_slock_app_shell"

        val KnownPageLabels: Map<String, String> =
            mapOf(
                APP_SHELL_PAGE_NAME to "Home",
                "compose_thread" to "Thread",
                "compose_thread_members" to "Channel members",
                "compose_channel_settings" to "Channel settings",
                "compose_home_activity" to "Activity",
                "compose_home_saved" to "Saved",
                "compose_home_drafts" to "Drafts",
                "compose_members" to "Members",
                "compose_member_detail" to "Member detail",
                "compose_tasks" to "Tasks",
                "compose_task_detail" to "Task",
                "compose_settings_home" to "Settings",
                "compose_settings_account" to "Account settings",
                "compose_settings_native_detail" to "Settings",
                "compose_settings_storage" to "Storage",
                "compose_settings_computers" to "Computers",
                "compose_settings_hands_feedback" to "Feedback",
                "compose_settings_feedback_tickets" to "Feedback",
                "compose_search" to "Search",
                "compose_file_preview" to "File preview",
                "compose_artifact_viewer" to "Artifact",
            )

        fun formatTabDisplayName(tab: String): String =
            when (tab.lowercase()) {
                "home", "chat" -> "Home"
                "activity", "inbox" -> "Activity"
                "tasks" -> "Tasks"
                "members" -> "Members"
                "more", "settings" -> "Settings"
                "search" -> "Search"
                else -> tab.replaceFirstChar { it.uppercase() }
            }
    }
}

/** Combines the user's feedback note with structured page-state metadata for submission. */
internal fun composeScreenshotFeedbackSubmissionMessage(
    note: String,
    context: RaftScreenshotFeedbackPageContext,
): String {
    val trimmedNote = note.trim()
    val metadataBlock = context.formatSubmissionMetadataBlock()
    return when {
        trimmedNote.isEmpty() -> trimmedNote
        metadataBlock.isBlank() -> trimmedNote
        else -> "$trimmedNote\n\n$metadataBlock"
    }
}

/** Extracts safe route/page state metadata from CMP page args and viewport dimensions. */
fun extractScreenshotFeedbackPageContext(
    pageName: String,
    pageData: JsonObject,
    activeTab: String? = null,
    fallbackServerId: String? = null,
    viewportWidthPx: Int = 0,
    viewportHeightPx: Int = 0,
    appVersion: String? = RaftAppIdentityRegistry.current()?.version,
    buildNumber: String? = RaftAppIdentityRegistry.current()?.buildNumber,
    platform: String? = raftFeedbackWebViewClientKind(),
): RaftScreenshotFeedbackPageContext {
    fun firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> pageData.optString(key).trim().takeIf { it.isNotEmpty() } }

    val serverId =
        firstNonBlank("serverId", "slockShellServerId", "slockRouteServerId", "viewingServerId")
            ?: fallbackServerId?.trim()?.takeIf { it.isNotEmpty() }
    val channelId = firstNonBlank("channelId", "streamChannelId", "parentChannelId")
    val threadId = firstNonBlank("threadId", "sourceThreadId", "parentMessageId")
    val taskId = firstNonBlank("taskId")
    val taskNumber = firstNonBlank("taskNumber")
    val resolvedTab =
        activeTab?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstNonBlank("initialTab", "selectedTab")
    val viewportSize =
        if (viewportWidthPx > 0 && viewportHeightPx > 0) "${viewportWidthPx}x${viewportHeightPx}" else null
    val orientation =
        when {
            viewportWidthPx <= 0 || viewportHeightPx <= 0 -> null
            viewportWidthPx > viewportHeightPx -> "landscape"
            else -> "portrait"
        }
    return RaftScreenshotFeedbackPageContext(
        pageName = pageName.trim(),
        activeTab = resolvedTab,
        serverId = serverId,
        channelId = channelId,
        threadId = threadId,
        taskId = taskId,
        taskNumber = taskNumber,
        viewportSize = viewportSize,
        orientation = orientation,
        appVersion = appVersion?.trim()?.takeIf { it.isNotEmpty() },
        buildNumber = buildNumber?.trim()?.takeIf { it.isNotEmpty() },
        platform = platform?.trim()?.takeIf { it.isNotEmpty() },
    )
}

enum class RaftScreenshotFeedbackDiagnosticsState {
    Idle,
    Preparing,
    Ready,
    Unavailable,
}

sealed interface RaftScreenshotFeedbackState {
    data object Idle : RaftScreenshotFeedbackState

    data class Prompting(
        val sessionId: Long,
        val screenshot: PlatformFile?,
        val pageContext: RaftScreenshotFeedbackPageContext,
    ) : RaftScreenshotFeedbackState

    data class Composing(
        val sessionId: Long,
        val screenshot: PlatformFile?,
        val screenshotRemoved: Boolean,
        val pageContext: RaftScreenshotFeedbackPageContext,
        val note: String,
        val feedbackType: SettingsFeedbackType,
        val diagnosticsState: RaftScreenshotFeedbackDiagnosticsState,
        val diagnosticsRef: FeedbackDiagnosticsReference?,
        val submitting: Boolean,
        val errorCode: String?,
        val errorRetryable: Boolean,
        val errorTraceId: String?,
        val pendingRetryAttempt: SettingsFeedbackProxyAttempt?,
    ) : RaftScreenshotFeedbackState
}

/**
 * Shared state machine for screenshot -> floating prompt pill -> in-place quick feedback sheet.
 *
 * Owns debounce, prompt auto-dismiss, temp screenshot file cleanup, pre-prepared diagnostics
 * upload lifecycle (started only when the sheet opens; cancelled on dismiss), and proxy submit
 * with retry preservation.
 */
class RaftScreenshotFeedbackController(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val debounceMillis: Long = SCREENSHOT_FEEDBACK_DEBOUNCE_MILLIS,
    private val promptAutoDismissMillis: Long = SCREENSHOT_FEEDBACK_PROMPT_AUTO_DISMISS_MILLIS,
    private val deleteTempFile: (String) -> Unit = ::raftDeleteScreenshotFeedbackTempFile,
    private val logger: (String) -> Unit = { line -> raftLogInfo("RaftScreenshotFeedback", line) },
) {
    private val lock = SynchronizedObject()
    private val mutableState = MutableStateFlow<RaftScreenshotFeedbackState>(RaftScreenshotFeedbackState.Idle)
    val state: StateFlow<RaftScreenshotFeedbackState> = mutableState.asStateFlow()

    private var nextSessionId: Long = 0L
    private var lastAcceptedAtMillis: Long? = null
    private var autoDismissJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var submitJob: Job? = null

    /**
     * Handles a captured system screenshot. Returns true if the floating prompt pill was shown.
     * Any rejected or superseded temp screenshot file is deleted immediately.
     */
    fun onScreenshotCaptured(
        screenshot: PlatformFile?,
        pageContext: RaftScreenshotFeedbackPageContext,
        eligible: Boolean,
    ): Boolean {
        if (!eligible) {
            cleanupFile(screenshot)
            return false
        }
        val now = nowMillis()
        var previousFileToDelete: PlatformFile? = null
        var previousDismissJob: Job? = null
        val sessionId =
            synchronized(lock) {
                val current = mutableState.value
                if (current is RaftScreenshotFeedbackState.Composing) {
                    cleanupFile(screenshot)
                    return false
                }
                val last = lastAcceptedAtMillis
                if (last != null && now - last < debounceMillis) {
                    cleanupFile(screenshot)
                    return false
                }
                lastAcceptedAtMillis = now
                if (current is RaftScreenshotFeedbackState.Prompting) {
                    previousFileToDelete = current.screenshot
                    previousDismissJob = autoDismissJob
                    autoDismissJob = null
                }
                val id = ++nextSessionId
                mutableState.value =
                    RaftScreenshotFeedbackState.Prompting(
                        sessionId = id,
                        screenshot = screenshot,
                        pageContext = pageContext,
                    )
                id
            }
        previousDismissJob?.cancel()
        cleanupFile(previousFileToDelete)
        logger(
            "screenshot_feedback_prompt_shown session=$sessionId page=${pageContext.pageName} " +
                "hasScreenshot=${screenshot != null}"
        )
        val timerJob =
            scope.launch {
                delay(promptAutoDismissMillis)
                expirePrompt(sessionId)
            }
        synchronized(lock) {
            val current = mutableState.value
            if (current is RaftScreenshotFeedbackState.Prompting && current.sessionId == sessionId) {
                autoDismissJob = timerJob
            } else {
                timerJob.cancel()
            }
        }
        return true
    }

    /** Dismisses the floating prompt pill (Step 1) and cleans up its temp screenshot file. */
    fun dismissPrompt() {
        var fileToDelete: PlatformFile? = null
        var dismissJob: Job? = null
        synchronized(lock) {
            val current = mutableState.value as? RaftScreenshotFeedbackState.Prompting ?: return
            fileToDelete = current.screenshot
            dismissJob = autoDismissJob
            autoDismissJob = null
            mutableState.value = RaftScreenshotFeedbackState.Idle
        }
        dismissJob?.cancel()
        cleanupFile(fileToDelete)
    }

    /**
     * Transitions from Step 1 (floating pill) to Step 2 (in-place bottom sheet) and begins
     * preparing diagnostics logs in the background.
     */
    fun openSheet(
        eligible: Boolean,
        startDiagnostics: (suspend () -> FeedbackDiagnosticsReference?)? = null,
    ): Boolean {
        var fileToDelete: PlatformFile? = null
        var dismissJob: Job? = null
        var openedSessionId: Long? = null
        synchronized(lock) {
            val current = mutableState.value as? RaftScreenshotFeedbackState.Prompting ?: return false
            dismissJob = autoDismissJob
            autoDismissJob = null
            if (!eligible) {
                fileToDelete = current.screenshot
                mutableState.value = RaftScreenshotFeedbackState.Idle
                openedSessionId = null
            } else {
                openedSessionId = current.sessionId
                mutableState.value =
                    RaftScreenshotFeedbackState.Composing(
                        sessionId = current.sessionId,
                        screenshot = current.screenshot,
                        screenshotRemoved = false,
                        pageContext = current.pageContext,
                        note = "",
                        feedbackType = SettingsFeedbackType.Problem,
                        diagnosticsState =
                            if (startDiagnostics != null) {
                                RaftScreenshotFeedbackDiagnosticsState.Preparing
                            } else {
                                RaftScreenshotFeedbackDiagnosticsState.Unavailable
                            },
                        diagnosticsRef = null,
                        submitting = false,
                        errorCode = null,
                        errorRetryable = false,
                        errorTraceId = null,
                        pendingRetryAttempt = null,
                    )
            }
        }
        dismissJob?.cancel()
        if (fileToDelete != null) {
            cleanupFile(fileToDelete)
            return false
        }
        val sessionId = openedSessionId ?: return false
        logger("screenshot_feedback_sheet_opened session=$sessionId")
        if (startDiagnostics != null) {
            val job =
                scope.launch {
                    val ref =
                        try {
                            startDiagnostics()?.takeIf { it.isValid() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            logger("screenshot_feedback_diagnostics_failed error=${error::class.simpleName}")
                            null
                        }
                    synchronized(lock) {
                        val current = mutableState.value
                        if (current is RaftScreenshotFeedbackState.Composing && current.sessionId == sessionId) {
                            mutableState.value =
                                current.copy(
                                    diagnosticsRef = ref,
                                    diagnosticsState =
                                        if (ref != null) {
                                            RaftScreenshotFeedbackDiagnosticsState.Ready
                                        } else {
                                            RaftScreenshotFeedbackDiagnosticsState.Unavailable
                                        },
                                )
                        }
                    }
                }
            synchronized(lock) {
                val current = mutableState.value
                if (current is RaftScreenshotFeedbackState.Composing && current.sessionId == sessionId) {
                    diagnosticsJob = job
                } else {
                    job.cancel()
                }
            }
        }
        return true
    }

    fun updateNote(note: String) {
        synchronized(lock) {
            val current = mutableState.value as? RaftScreenshotFeedbackState.Composing ?: return
            if (current.submitting) return
            val noteChanged = current.note != note
            mutableState.value =
                current.copy(
                    note = note,
                    errorCode = if (noteChanged) null else current.errorCode,
                    errorRetryable = if (noteChanged) false else current.errorRetryable,
                    errorTraceId = if (noteChanged) null else current.errorTraceId,
                    pendingRetryAttempt = if (noteChanged) null else current.pendingRetryAttempt,
                )
        }
    }

    fun updateFeedbackType(type: SettingsFeedbackType) {
        synchronized(lock) {
            val current = mutableState.value as? RaftScreenshotFeedbackState.Composing ?: return
            if (current.submitting || current.feedbackType == type) return
            mutableState.value =
                current.copy(
                    feedbackType = type,
                    pendingRetryAttempt = null,
                )
        }
    }

    /** Detaches and deletes the captured screenshot without closing the sheet. */
    fun detachScreenshot() {
        var fileToDelete: PlatformFile? = null
        synchronized(lock) {
            val current = mutableState.value as? RaftScreenshotFeedbackState.Composing ?: return
            if (current.submitting) return
            fileToDelete = current.screenshot
            mutableState.value =
                current.copy(
                    screenshot = null,
                    screenshotRemoved = true,
                    pendingRetryAttempt = null,
                )
        }
        cleanupFile(fileToDelete)
    }

    /** Closes the sheet, cancels any in-flight diagnostics/submit work, and deletes the temp file. */
    fun dismissSheet() {
        teardown("dismissed")
    }

    /** Called when signed-in, privacy mode, or feature flag eligibility turns off. */
    fun onEligibilityLost() {
        teardown("eligibility_lost")
    }

    /**
     * Submits the quick feedback note + optional screenshot + pre-prepared diagnostics + page state
     * metadata via [submitter]. On success closes the sheet, deletes the temp screenshot file, and
     * invokes [onFiled]. On failure keeps the sheet open with the user's text intact for retry.
     */
    fun submit(
        eligible: Boolean,
        submitter: SettingsFeedbackProxySubmitter,
        onFiled: (ticketId: String) -> Unit = {},
    ) {
        val (sessionId, activeDiagnosticsJob) =
            synchronized(lock) {
                val current = mutableState.value as? RaftScreenshotFeedbackState.Composing ?: return
                if (current.submitting) return
                if (!eligible) {
                    mutableState.value =
                        current.copy(
                            errorCode = "settings_feedback_auth_failed",
                            errorRetryable = false,
                            errorTraceId = null,
                            pendingRetryAttempt = null,
                        )
                    return
                }
                if (current.note.trim().isEmpty()) {
                    mutableState.value =
                        current.copy(
                            errorCode = SCREENSHOT_FEEDBACK_MESSAGE_REQUIRED,
                            errorRetryable = false,
                            errorTraceId = null,
                        )
                    return
                }
                mutableState.value =
                    current.copy(
                        submitting = true,
                        errorCode = null,
                        errorRetryable = false,
                        errorTraceId = null,
                    )
                current.sessionId to diagnosticsJob
            }
        val job =
            scope.launch {
                // Wait for any already-running diagnostics preparation instead of starting a second upload.
                if (activeDiagnosticsJob != null && activeDiagnosticsJob.isActive) {
                    try {
                        activeDiagnosticsJob.join()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                    }
                }
                val snapshot =
                    synchronized(lock) {
                        (mutableState.value as? RaftScreenshotFeedbackState.Composing)
                            ?.takeIf { it.sessionId == sessionId }
                    } ?: return@launch
                val message = composeScreenshotFeedbackSubmissionMessage(snapshot.note, snapshot.pageContext)
                val attachments = listOfNotNull(snapshot.screenshot.takeUnless { snapshot.screenshotRemoved })
                val attempt =
                    snapshot.pendingRetryAttempt
                        ?: submitter
                            .newAttempt(
                                message = message,
                                attachments = attachments,
                                type = snapshot.feedbackType,
                            ).copy(
                                diagnosticsPrepared = true,
                                diagnostics = snapshot.diagnosticsRef,
                            )
                val outcome =
                    try {
                        submitter.submit(attempt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logger("screenshot_feedback_submit_exception error=${error::class.simpleName}")
                        SettingsFeedbackProxyOutcome.Failed(
                            code = "settings_feedback_submit_failed",
                            retryable = true,
                            retry = attempt,
                        )
                    }
                when (outcome) {
                    is SettingsFeedbackProxyOutcome.Filed -> {
                        var fileToDelete: PlatformFile? = null
                        val closed =
                            synchronized(lock) {
                                val current = mutableState.value
                                if (current is RaftScreenshotFeedbackState.Composing && current.sessionId == sessionId) {
                                    fileToDelete = current.screenshot
                                    diagnosticsJob = null
                                    submitJob = null
                                    mutableState.value = RaftScreenshotFeedbackState.Idle
                                    true
                                } else {
                                    false
                                }
                            }
                        cleanupFile(fileToDelete)
                        if (closed) {
                            logger("screenshot_feedback_submitted session=$sessionId ticket=${outcome.ticketId.take(8)}")
                            onFiled(outcome.ticketId)
                        }
                    }
                    is SettingsFeedbackProxyOutcome.Failed -> {
                        synchronized(lock) {
                            val current = mutableState.value
                            if (current is RaftScreenshotFeedbackState.Composing && current.sessionId == sessionId) {
                                submitJob = null
                                mutableState.value =
                                    current.copy(
                                        submitting = false,
                                        errorCode = outcome.code,
                                        errorRetryable = outcome.retryable,
                                        errorTraceId = outcome.traceId,
                                        pendingRetryAttempt = outcome.retry,
                                    )
                            }
                        }
                        logger("screenshot_feedback_submit_failed session=$sessionId code=${outcome.code}")
                    }
                }
            }
        synchronized(lock) {
            val current = mutableState.value
            if (current is RaftScreenshotFeedbackState.Composing && current.sessionId == sessionId) {
                submitJob = job
            } else {
                job.cancel()
            }
        }
    }

    internal fun resetForTests() {
        teardown("test_reset")
        synchronized(lock) {
            lastAcceptedAtMillis = null
            nextSessionId = 0L
        }
    }

    private fun expirePrompt(sessionId: Long) {
        var fileToDelete: PlatformFile? = null
        synchronized(lock) {
            val current = mutableState.value
            if (current is RaftScreenshotFeedbackState.Prompting && current.sessionId == sessionId) {
                fileToDelete = current.screenshot
                autoDismissJob = null
                mutableState.value = RaftScreenshotFeedbackState.Idle
            }
        }
        if (fileToDelete != null) {
            logger("screenshot_feedback_prompt_expired session=$sessionId")
            cleanupFile(fileToDelete)
        }
    }

    private fun teardown(reason: String) {
        var fileToDelete: PlatformFile? = null
        var dismissToCancel: Job? = null
        var diagToCancel: Job? = null
        var submitToCancel: Job? = null
        var wasActive = false
        synchronized(lock) {
            val current = mutableState.value
            if (current != RaftScreenshotFeedbackState.Idle) {
                wasActive = true
                fileToDelete =
                    when (current) {
                        is RaftScreenshotFeedbackState.Prompting -> current.screenshot
                        is RaftScreenshotFeedbackState.Composing -> current.screenshot
                        RaftScreenshotFeedbackState.Idle -> null
                    }
                mutableState.value = RaftScreenshotFeedbackState.Idle
            }
            dismissToCancel = autoDismissJob
            autoDismissJob = null
            diagToCancel = diagnosticsJob
            diagnosticsJob = null
            submitToCancel = submitJob
            submitJob = null
        }
        dismissToCancel?.cancel()
        diagToCancel?.cancel()
        submitToCancel?.cancel()
        cleanupFile(fileToDelete)
        if (wasActive) logger("screenshot_feedback_closed reason=$reason")
    }

    private fun cleanupFile(file: PlatformFile?) {
        val ref = file?.fileRef?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { deleteTempFile(ref) }
    }
}

/**
 * Process-wide runtime wiring for screenshot -> quick feedback across Android, iOS, and Desktop.
 * Uses only `MutableStateFlow` / `atomic` / `SynchronizedObject` (never top-level Compose snapshot
 * state) so background initialization can never trigger `SnapshotKt.readError`.
 */
object RaftScreenshotFeedbackRuntime {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostLock = SynchronizedObject()
    private val hostTokenCounter = atomic(0L)
    private val activeHosts = linkedMapOf<Long, ActiveHostEntry>()

    private data class ActiveHostEntry(
        val token: Long,
        val activationOrder: Long,
        val pageArgs: RaftCmpPageArgs,
    )

    private val activationCounter = atomic(0L)
    private val activeHostTokenState = MutableStateFlow<Long?>(null)
    val activeHostToken: StateFlow<Long?> = activeHostTokenState.asStateFlow()

    private val eligibilitySignalState = MutableStateFlow(0L)
    private val activeShellTabState = MutableStateFlow<String?>(null)
    private val bottomBarBandLock = SynchronizedObject()
    private val bottomBarBandsByHost = linkedMapOf<Any, LinkedHashMap<Any, Float>>()
    private val shellBottomBarBandRevisionState = MutableStateFlow(0L)
    internal val shellBottomBarBandRevision: StateFlow<Long> = shellBottomBarBandRevisionState.asStateFlow()

    /** Set by Android host from `BuildConfig.SCREENSHOT_FEEDBACK_ENABLED`; false on iOS/Desktop/release. */
    @Volatile
    var buildConfigAlphaEnabled: Boolean = false

    val controller: RaftScreenshotFeedbackController =
        RaftScreenshotFeedbackController(scope = runtimeScope)

    /** Called when feature flags, auth session, or selected server state changes. */
    fun notifyEligibilitySignalChanged() {
        eligibilitySignalState.value += 1L
    }

    internal fun nextHostToken(): Long = hostTokenCounter.incrementAndGet()

    internal fun activateHost(token: Long, pageArgs: RaftCmpPageArgs) {
        val order = activationCounter.incrementAndGet()
        synchronized(hostLock) {
            activeHosts[token] = ActiveHostEntry(token = token, activationOrder = order, pageArgs = pageArgs)
            activeHostTokenState.value = activeHosts.values.maxByOrNull { it.activationOrder }?.token
        }
    }

    internal fun updateHostPageArgs(token: Long, pageArgs: RaftCmpPageArgs) {
        synchronized(hostLock) {
            val existing = activeHosts[token] ?: return
            activeHosts[token] = existing.copy(pageArgs = pageArgs)
        }
    }

    internal fun deactivateHost(token: Long) {
        val noActiveHosts =
            synchronized(hostLock) {
                activeHosts.remove(token)
                val next = activeHosts.values.maxByOrNull { it.activationOrder }?.token
                activeHostTokenState.value = next
                next == null
            }
        // When the foreground page leaves or the app backgrounds, retire an open prompt pill
        // (and its temp screenshot file); an actively composed sheet stays open across transient focus loss.
        if (noActiveHosts) {
            controller.dismissPrompt()
        }
    }

    internal fun updateActiveShellTab(tab: String?) {
        val normalized = tab?.trim()?.takeIf { it.isNotEmpty() }
        if (activeShellTabState.value != normalized) {
            activeShellTabState.value = normalized
        }
    }

    internal fun setActiveShellBottomBarBandDp(hostKey: Any, token: Any, heightDp: Float?) {
        val normalized = heightDp?.takeIf { it.isFinite() && it >= 0f }
        synchronized(bottomBarBandLock) {
            val existingMap = bottomBarBandsByHost[hostKey]
            val previousMax = existingMap?.values?.maxOrNull()
            if (normalized == null) {
                if (existingMap == null || existingMap.remove(token) == null) return
                if (existingMap.isEmpty()) {
                    bottomBarBandsByHost.remove(hostKey)
                }
            } else {
                val perHost = existingMap ?: LinkedHashMap<Any, Float>().also { bottomBarBandsByHost[hostKey] = it }
                if (perHost[token] == normalized) return
                perHost[token] = normalized
            }
            val nextMax = bottomBarBandsByHost[hostKey]?.values?.maxOrNull()
            if (nextMax != previousMax) {
                shellBottomBarBandRevisionState.value += 1L
            }
        }
    }

    internal fun activeShellBottomBarBandDpForHost(hostKey: Any): Float? =
        synchronized(bottomBarBandLock) {
            bottomBarBandsByHost[hostKey]?.values?.maxOrNull()
        }

    fun currentActiveShellTab(): String? = activeShellTabState.value

    fun currentActivePageArgs(): RaftCmpPageArgs? =
        synchronized(hostLock) {
            activeHosts.values.maxByOrNull { it.activationOrder }?.pageArgs
        }

    fun isCurrentlyEligible(
        buildAlphaEnabled: Boolean = buildConfigAlphaEnabled,
    ): Boolean {
        val privacyMode = RaftTelemetryPrivacy.state.value
        if (privacyMode != RaftPrivacyMode.Off) return false
        val provider = RaftSharedStoreRegistry.currentOrNull() ?: return false
        val session = provider.authStore.readCommittedSessionSnapshot() ?: return false
        val accountId = session.user.id.trim()
        if (accountId.isEmpty()) return false
        val activePage = currentActivePageArgs()
        val pageServerId =
            activePage?.pageData?.let { data ->
                listOf("serverId", "slockShellServerId", "slockRouteServerId", "viewingServerId")
                    .firstNotNullOfOrNull { key -> data.optString(key).trim().takeIf { it.isNotEmpty() } }
            }
        val sessionServerId = session.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
        val serverId =
            if (isScreenshotFeedbackAppShellHostPage(activePage?.pageName)) {
                sessionServerId ?: pageServerId ?: session.servers.firstOrNull()?.id?.trim().orEmpty()
            } else {
                pageServerId ?: sessionServerId ?: session.servers.firstOrNull()?.id?.trim().orEmpty()
            }
        val environmentId = runCatching { provider.apiEnvironmentStore.current().apiBaseUrl }.getOrDefault("")
        return resolveMobileScreenshotFeedbackEligible(
            serverFeatureFlags = provider.serverFeatureFlags,
            registry = ProcessDiagnosticFeatureFlagOverrides.registry,
            environmentId = environmentId,
            accountId = accountId,
            serverId = serverId,
            buildConfigAlphaEnabled = buildAlphaEnabled,
            privacyMode = privacyMode,
        )
    }

    fun observeEligibility(
        buildAlphaEnabled: Boolean = buildConfigAlphaEnabled,
    ): Flow<Boolean> =
        combine(
            eligibilitySignalState,
            RaftTelemetryPrivacy.state,
            ProcessDiagnosticFeatureFlagOverrides.registry.revisionFor(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY),
            activeHostTokenState,
        ) { _, _, _, _ ->
            isCurrentlyEligible(buildAlphaEnabled = buildAlphaEnabled)
        }.distinctUntilChanged()

    /** Called by platform observers (Android Activity / iOS notification) when a screenshot is captured. */
    fun onScreenshotCaptured(
        screenshot: PlatformFile?,
        pageContext: RaftScreenshotFeedbackPageContext,
        buildAlphaEnabled: Boolean = buildConfigAlphaEnabled,
    ): Boolean =
        controller.onScreenshotCaptured(
            screenshot = screenshot,
            pageContext = pageContext,
            eligible = isCurrentlyEligible(buildAlphaEnabled = buildAlphaEnabled),
        )
}
