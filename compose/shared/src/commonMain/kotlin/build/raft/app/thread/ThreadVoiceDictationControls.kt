package build.raft.app.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.components.RaftSlotIcon
import build.raft.app.currentRaftEffectiveDateTimeContext
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.RaftVoiceDictationV2PermitApi
import build.raft.app.data.RaftVoiceFeedbackNetworkApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.VoiceDictationButtonGesture
import build.raft.app.data.VoiceDictationGestureMode
import build.raft.app.data.VoiceDictationV2Controller
import build.raft.app.data.VoiceDictationV2Error
import build.raft.app.data.VoiceDictationV2ErrorCode
import build.raft.app.data.VoiceDictationV2PermitApi
import build.raft.app.data.VoiceDictationV2PermitFailure
import build.raft.app.data.VoiceDictationV2Phase
import build.raft.app.data.VoiceDictationV2Quota
import build.raft.app.data.VoiceDictationV2State
import build.raft.app.data.VoiceDictationV2StreamClient
import build.raft.app.data.VoiceDictationV2StreamConnection
import build.raft.app.data.VoiceDictationV2StreamConnector
import build.raft.app.data.VoiceFeedbackApi
import build.raft.app.data.normalizeVoiceDictationLanguage
import build.raft.app.data.offsetMinutesAt
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.floorDiv
import build.raft.app.model.formatRaftLocalClock
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.app.resources.*
import build.raft.app.traceId
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.realtime.socket.KtorRaftWebSocketConnector
import build.raft.realtime.socket.createRaftRealtimeHttpClient
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Composer streaming voice dictation UI controls, gating, dual-mode gesture, hold pill, and
// localized feedback (`#wg-mobile-sync:#334`). Mirrors web `ComposerVoiceDictationControls.tsx`,
// `ComposerVoiceDictationNotice.tsx`, `voiceDictationGesture.ts`, and `voiceDictationPresentation.ts`.

internal const val ThreadVoiceDictationButtonTestTag: String = "composer-voice-dictation-button"
internal const val ThreadVoiceDictationHaloTestTag: String = "composer-voice-dictation-halo"
internal const val ThreadVoiceDictationHoldPillTestTag: String = "composer-voice-dictation-hold-popover"
internal const val ThreadVoiceDictationErrorTestTag: String = "composer-voice-dictation-error"
internal const val ThreadVoiceDictationQuotaTestTag: String = "composer-voice-dictation-quota"

/** Resting outer halo scale around the 28 dp active mic circle (32 dp outer, 2 dp flat ring). */
private const val VOICE_DICTATION_HALO_SCALE_MIN: Float = 32f / 28f

/** Additional outward expansion at peak speech (+6 dp per side -> 44 dp outer). */
private const val VOICE_DICTATION_HALO_SCALE_RANGE: Float = 12f / 28f

/** Fixed still halo scale under reduced motion (34 dp outer around 28 dp disc; matches web HALO_SCALE_REDUCED_MOTION). */
private const val VOICE_DICTATION_HALO_SCALE_REDUCED_MOTION: Float = 34f / 28f

/** Outer halo alpha range matching web Kirby spec (`0.25` at rest -> `0.60` at peak speech). */
private const val VOICE_DICTATION_HALO_ALPHA_MIN: Float = 0.25f
private const val VOICE_DICTATION_HALO_ALPHA_MAX: Float = 0.60f

/** Fixed still halo alpha under reduced motion (matches web HALO_OPACITY_REDUCED_MOTION). */
private const val VOICE_DICTATION_HALO_ALPHA_REDUCED_MOTION: Float = 0.35f

/** Low-quota warning thresholds (in seconds): remaining > 0 and <= ~10% of limit, clamped to [150 s, 600 s]. */
private const val VOICE_DICTATION_MIN_LOW_QUOTA_SECONDS: Int = 150
private const val VOICE_DICTATION_MAX_LOW_QUOTA_SECONDS: Int = 600
private const val VOICE_DICTATION_CRITICAL_LOW_QUOTA_SECONDS: Int = 120
private const val VOICE_DICTATION_DEGRADED_FAIL_OPEN_CAP_SECONDS: Int = 180
private const val VOICE_DICTATION_LOW_QUOTA_FRACTION: Double = 0.10

private const val VOICE_DICTATION_HOLD_CLOCK_TICK_MS: Long = 250L
private const val VOICE_DICTATION_MAX_RESET_TIMER_MS: Long = 86_400_000L

// not a token: web ComposerVoiceDictationControls size-7 (28 px) active mic circle keeping full idle glyph size (slock #17225)
private val VoiceDictationActiveCircleSize = 28.dp

// not a token: web ComposerVoiceDictationControls hold popover dot (`size-2` = 8 px) & `sideOffset={10}`
private val VoiceDictationHoldDotSize = 8.dp
private val VoiceDictationHoldPillAnchorGap = 10.dp
private val VoiceDictationHoldPillEdgeMargin = 8.dp

/**
 * Pure visibility gate for the composer voice dictation mic button:
 * requires `voice_dictation_v1` ON for the server, a non-guest member, an installed native
 * audio recorder, and an editable (non-archived, non-read-only) composer.
 */
internal fun canShowComposerVoiceDictation(
    flagEnabled: Boolean,
    viewerIsGuest: Boolean,
    recorderAvailable: Boolean,
    composerEditable: Boolean,
): Boolean = flagEnabled && !viewerIsGuest && recorderAvailable && composerEditable

/** Formats an elapsed dictation duration (ms) as `m:ss`, e.g. `0:04` or `1:23`. */
internal fun formatVoiceDictationClock(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${if (seconds < 10L) "0" else ""}$seconds"
}

/**
 * Computes rounded remaining minutes for the low-quota banner, matching web `voiceDictationLowQuotaMessage`:
 * returns `null` when `< 60 s` (triggers `"Less than 1 minute"` copy), otherwise `max(1, round(remainingSeconds / 60))`
 * using half-up rounding (`(remainingSeconds + 30) / 60`).
 */
internal fun voiceDictationLowQuotaRemainingMinutes(remainingSeconds: Int): Int? {
    if (remainingSeconds < 60) return null
    return ((remainingSeconds + 30) / 60).coerceAtLeast(1)
}

/** Computes outer-halo scale (`1.167..1.667`, or fixed `1.25` under reduced motion). */
internal fun voiceDictationHaloScale(
    audioLevel: Float,
    reducedMotion: Boolean = false,
): Float {
    if (reducedMotion) return VOICE_DICTATION_HALO_SCALE_REDUCED_MOTION
    val clamped = if (audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f
    return VOICE_DICTATION_HALO_SCALE_MIN + clamped * VOICE_DICTATION_HALO_SCALE_RANGE
}

/** Computes outer-halo alpha (`0.25..0.60`, or fixed `0.35` under reduced motion). */
internal fun voiceDictationHaloAlpha(
    audioLevel: Float,
    reducedMotion: Boolean = false,
): Float {
    if (reducedMotion) return VOICE_DICTATION_HALO_ALPHA_REDUCED_MOTION
    val clamped = if (audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f
    return VOICE_DICTATION_HALO_ALPHA_MIN + clamped * (VOICE_DICTATION_HALO_ALPHA_MAX - VOICE_DICTATION_HALO_ALPHA_MIN)
}

/**
 * Returns true when daily dictation quota is nearly exhausted (remaining > 0 and <= ~10% of limit,
 * clamped to `[150 s, 600 s]`), suppressing degraded fail-open snapshots (`quotaDegraded == true`
 * or `usedSeconds == 0 && remainingSeconds <= 180 && limitSeconds > remainingSeconds`).
 */
internal fun isVoiceDictationV2QuotaLow(
    quota: VoiceDictationV2Quota?,
    quotaDegraded: Boolean = false,
): Boolean {
    if (quota == null || quotaDegraded) return false
    val limit = quota.limitSeconds
    val remaining = quota.remainingSeconds
    if (limit <= 0 || remaining <= 0) return false
    if (
        quota.usedSeconds == 0 &&
        remaining <= VOICE_DICTATION_DEGRADED_FAIL_OPEN_CAP_SECONDS &&
        limit > remaining
    ) {
        return false
    }
    val proportional = ceil(limit * VOICE_DICTATION_LOW_QUOTA_FRACTION).toInt()
    val threshold = proportional.coerceIn(VOICE_DICTATION_MIN_LOW_QUOTA_SECONDS, VOICE_DICTATION_MAX_LOW_QUOTA_SECONDS)
    return remaining <= threshold
}

/** Returns the banner status for a [VoiceDictationV2Error]: Info for session length limit, Warning otherwise. */
internal fun voiceDictationErrorBannerStatus(error: VoiceDictationV2Error): RaftBannerStatus =
    if (error is VoiceDictationV2Error.StreamError && error.code == VoiceDictationV2ErrorCode.SessionLimitReached) {
        RaftBannerStatus.Info
    } else {
        RaftBannerStatus.Warning
    }

/** Formatted daily quota reset clock time + local today/tomorrow distinction. */
internal data class VoiceDictationResetTimeDisplay(
    val clockText: String,
    val isTomorrow: Boolean,
)

/**
 * Formats an ISO-8601 `resetsAt` timestamp in the viewer's [RaftTimeContext] (respecting timezone,
 * DST offset, and 12h/24h preference). Returns `null` when missing or unparseable.
 */
internal fun formatVoiceDictationResetTime(
    resetsAtIso: String?,
    timeContext: RaftTimeContext,
): VoiceDictationResetTimeDisplay? {
    val trimmed = resetsAtIso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val epochMillis = parseRaftTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return null
    val offsetMinutes = timeContext.offsetMinutesAt(epochMillis)
    val clockText = formatRaftLocalClock(epochMillis, offsetMinutes, timeContext.timeFormat)
    val todayEpochDay = timeContext.resolvedTodayEpochDay()
    val resetEpochDay = floorDiv(epochMillis + offsetMinutes.toLong() * MillisPerMinute, MillisPerDay)
    val isTomorrow = todayEpochDay != null && resetEpochDay == todayEpochDay + 1L
    return VoiceDictationResetTimeDisplay(clockText = clockText, isTomorrow = isTomorrow)
}

/** Extracts the server trace ID from a [VoiceDictationV2Error] when present. */
internal fun voiceDictationErrorTraceId(error: VoiceDictationV2Error): String? =
    when (error) {
        is VoiceDictationV2Error.PermitRefused -> error.failure.traceId
        else -> null
    }

/** Lazy process-scoped WebSocket connector so no HttpClient is allocated until dictation starts. */
private object SharedVoiceDictationStreamConnector : VoiceDictationV2StreamConnector {
    private val delegate: VoiceDictationV2StreamClient by lazy {
        VoiceDictationV2StreamClient(KtorRaftWebSocketConnector(createRaftRealtimeHttpClient()))
    }

    override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection =
        delegate.connect(url, permit)

    override suspend fun connect(
        url: String,
        permit: String,
        traceId: String?,
    ): VoiceDictationV2StreamConnection = delegate.connect(url = url, permit = permit, traceId = traceId)
}

/**
 * Conversation-scoped voice dictation host provided to the composer via
 * [LocalThreadVoiceDictationHost].
 */
internal class ThreadVoiceDictationHost(
    val flagEnabled: Boolean,
    val viewerIsGuest: Boolean,
    val recorderAvailable: Boolean,
    val serverId: String?,
    val channelId: String?,
    val threadId: String?,
    val languageHint: String?,
    val controller: VoiceDictationV2Controller,
) {
    fun canShow(composerEditable: Boolean): Boolean =
        canShowComposerVoiceDictation(
            flagEnabled = flagEnabled,
            viewerIsGuest = viewerIsGuest,
            recorderAvailable = recorderAvailable,
            composerEditable = composerEditable,
        )
}

internal val LocalThreadVoiceDictationHost =
    compositionLocalOf<ThreadVoiceDictationHost?> { null }

/** Observes [VoiceDictationV2State.awaitingSubmitFinal] cleanly without bloating [ThreadComposerContent]. */
@Composable
internal fun rememberVoiceDictationAwaitingSubmitFinal(host: ThreadVoiceDictationHost?): Boolean {
    if (host == null) return false
    val state by host.controller.state.collectAsState()
    return state.awaitingSubmitFinal
}

/**
 * Creates and lifecycle-manages a [ThreadVoiceDictationHost] for any composer surface (chat thread,
 * feedback form, ticket reply, etc.): observes `voice_dictation_v1` reactively (optionally
 * prefetching when the resolved server's snapshot is not cached yet), tracks server/channel/thread/
 * sign-out/active/editable changes, and aborts any active microphone capture or WebSocket on owner
 * switch, deactivation, or disposal.
 */
@Composable
internal fun rememberComposerVoiceDictationHost(
    serverFeatureFlags: SharedServerFeatureFlags,
    permitApi: VoiceDictationV2PermitApi,
    active: Boolean,
    currentSession: StoredAuthSession?,
    serverId: String?,
    channelId: String? = null,
    threadId: String? = null,
    viewerIsGuest: Boolean = currentSession.threadViewerIsGuest(serverId),
    surfaceEditable: Boolean = true,
    prefetchIfUnresolved: Boolean = false,
    streamConnector: VoiceDictationV2StreamConnector = SharedVoiceDictationStreamConnector,
    audioRecorderProvider: () -> RaftVoiceDictationAudioRecorder? =
        RaftVoiceDictationAudioRecorderRegistry::currentOrNull,
    feedbackApi: VoiceFeedbackApi? = null,
): ThreadVoiceDictationHost {
    val flagRevision by key(serverFeatureFlags) { serverFeatureFlags.snapshotRevision.collectAsState() }
    val effectiveServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    val serverIdObj = remember(effectiveServerId) { effectiveServerId?.let(::ServerId) }
    val signedInUserId = currentSession?.user?.id?.trim()?.takeIf { it.isNotEmpty() }

    LaunchedEffect(serverFeatureFlags, serverIdObj, signedInUserId, prefetchIfUnresolved) {
        if (!prefetchIfUnresolved || signedInUserId == null || serverIdObj == null) return@LaunchedEffect
        if (!serverFeatureFlags.snapshotFor(serverIdObj).resolved) {
            runCatchingPreservingCancellation {
                serverFeatureFlags.prefetch(serverIdObj)
            }
        }
    }

    val flagEnabled =
        remember(serverFeatureFlags, serverIdObj, flagRevision) {
            serverFeatureFlags.isVoiceDictationEnabled(serverIdObj)
        }
    val recorderAvailable = audioRecorderProvider() != null
    val effectiveChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
    val localeTag = rememberRaftUiLocaleTag()
    val languageHint = remember(localeTag) { normalizeVoiceDictationLanguage(localeTag) }

    val dictationScope = rememberCoroutineScope()
    val controller =
        remember(permitApi, streamConnector, dictationScope, audioRecorderProvider, feedbackApi) {
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = streamConnector,
                scope = dictationScope,
                audioRecorderProvider = audioRecorderProvider,
                onOutcomeReport =
                    feedbackApi?.let { api ->
                        { report ->
                            dictationScope.launch {
                                runCatching {
                                    api.submitDictationFeedback(
                                        serverId = report.serverId,
                                        request = report.toFeedbackRequest(),
                                    )
                                }
                            }
                        }
                    },
            )
        }

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }

    LaunchedEffect(controller, effectiveServerId, effectiveChannelId, effectiveThreadId, signedInUserId) {
        if (signedInUserId == null || effectiveServerId == null) {
            controller.onOwnerChanged(serverId = null)
        } else {
            controller.onOwnerChanged(
                serverId = effectiveServerId,
                channelId = effectiveChannelId,
                threadId = effectiveThreadId,
            )
        }
    }

    LaunchedEffect(controller, active, flagEnabled, viewerIsGuest, recorderAvailable, surfaceEditable) {
        if (!active || !flagEnabled || viewerIsGuest || !recorderAvailable || !surfaceEditable) {
            controller.cancel()
        }
    }

    return remember(
        flagEnabled,
        viewerIsGuest,
        recorderAvailable,
        effectiveServerId,
        effectiveChannelId,
        effectiveThreadId,
        signedInUserId,
        languageHint,
        controller,
        surfaceEditable,
    ) {
        ThreadVoiceDictationHost(
            flagEnabled = flagEnabled && surfaceEditable && effectiveServerId != null && signedInUserId != null,
            viewerIsGuest = viewerIsGuest,
            recorderAvailable = recorderAvailable,
            serverId = effectiveServerId,
            channelId = effectiveChannelId,
            threadId = effectiveThreadId,
            languageHint = languageHint,
            controller = controller,
        )
    }
}

/**
 * Convenience overload of [rememberComposerVoiceDictationHost] wired to [RaftSharedStoreProvider]
 * and [RaftShellStoreScope].
 */
@Composable
internal fun rememberComposerVoiceDictationHost(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    active: Boolean,
    currentSession: StoredAuthSession?,
    serverId: String?,
    channelId: String? = null,
    threadId: String? = null,
    viewerIsGuest: Boolean = currentSession.threadViewerIsGuest(serverId),
    surfaceEditable: Boolean = true,
    prefetchIfUnresolved: Boolean = false,
    audioRecorderProvider: () -> RaftVoiceDictationAudioRecorder? =
        RaftVoiceDictationAudioRecorderRegistry::currentOrNull,
): ThreadVoiceDictationHost {
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val permitApi = remember(networkClient) { RaftVoiceDictationV2PermitApi(networkClient) }
    val feedbackApi = remember(networkClient) { RaftVoiceFeedbackNetworkApi(networkClient) }
    return rememberComposerVoiceDictationHost(
        serverFeatureFlags = sharedStores.serverFeatureFlags,
        permitApi = permitApi,
        active = active,
        currentSession = currentSession,
        serverId = serverId,
        channelId = channelId,
        threadId = threadId,
        viewerIsGuest = viewerIsGuest,
        surfaceEditable = surfaceEditable,
        prefetchIfUnresolved = prefetchIfUnresolved,
        audioRecorderProvider = audioRecorderProvider,
        feedbackApi = feedbackApi,
    )
}

/**
 * Creates and lifecycle-manages the conversation's [ThreadVoiceDictationHost]:
 * observes `voice_dictation_v1` reactively, tracks server/channel/thread/sign-out/active changes,
 * and aborts any active microphone capture or WebSocket on owner switch or disposal.
 */
@Composable
internal fun rememberThreadVoiceDictationHost(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    active: Boolean,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    headerProjectionServerId: ServerId?,
    renderedThread: RaftThreadScreenPayload,
    viewerIsGuest: Boolean,
    audioRecorderProvider: () -> RaftVoiceDictationAudioRecorder? =
        RaftVoiceDictationAudioRecorderRegistry::currentOrNull,
): ThreadVoiceDictationHost {
    val effectiveServerId = headerProjectionServerId?.value ?: currentServerId
    val effectiveChannelId =
        renderedThread.streamChannelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: renderedThread.parentChannelId?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveThreadId =
        renderedThread.parentMessageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: renderedThread.sourceThreadId?.trim()?.takeIf { it.isNotEmpty() }
    return rememberComposerVoiceDictationHost(
        sharedStores = sharedStores,
        storeScope = storeScope,
        active = active,
        currentSession = currentSession,
        serverId = effectiveServerId,
        channelId = effectiveChannelId,
        threadId = effectiveThreadId,
        viewerIsGuest = viewerIsGuest,
        surfaceEditable = !renderedThread.channelArchived,
        prefetchIfUnresolved = false,
        audioRecorderProvider = audioRecorderProvider,
    )
}

/**
 * Renders the composer voice dictation dismissible error or low-quota banner above the composer
 * card when [host] has a notice to show. Emits nothing when idle with no error.
 */
@Composable
internal fun ThreadVoiceDictationNoticeSlot(
    host: ThreadVoiceDictationHost?,
    composerEditable: Boolean,
    modifier: Modifier = Modifier,
) {
    if (host == null) return
    val state by host.controller.state.collectAsState()
    val error = state.error
    val quota = state.quota
    var dismissedLowQuotaResetsAt by remember(host.controller) { mutableStateOf<String?>(null) }
    var dismissedLowQuotaRemaining by remember(host.controller) { mutableStateOf<Int?>(null) }

    if (error == null && !host.canShow(composerEditable)) return

    val currentResetsKey = quota?.resetsAtIso ?: ""
    val dismissedForThisWindow =
        dismissedLowQuotaResetsAt == currentResetsKey &&
            dismissedLowQuotaRemaining != null &&
            !(
                quota != null &&
                    quota.remainingSeconds <= VOICE_DICTATION_CRITICAL_LOW_QUOTA_SECONDS &&
                    quota.remainingSeconds < (dismissedLowQuotaRemaining ?: 0)
            )
    val showLowQuota =
        error == null &&
            isVoiceDictationV2QuotaLow(quota = quota, quotaDegraded = state.quotaDegraded) &&
            !dismissedForThisWindow

    ThreadVoiceDictationNotice(
        error = error,
        quota = quota,
        showLowQuota = showLowQuota,
        onDismissError = host.controller::clearError,
        onDismissLowQuota = {
            dismissedLowQuotaResetsAt = currentResetsKey
            dismissedLowQuotaRemaining = quota?.remainingSeconds
        },
        modifier = modifier,
    )
}

/**
 * Pure presentation composable for the composer voice dictation warning/info banner.
 */
@Composable
internal fun ThreadVoiceDictationNotice(
    error: VoiceDictationV2Error?,
    quota: VoiceDictationV2Quota?,
    showLowQuota: Boolean,
    onDismissError: () -> Unit,
    onDismissLowQuota: () -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    val effectiveTimeContext = currentRaftEffectiveDateTimeContext(timeContext)
    val dismissLabel = stringResource(Res.string.thread_voice_dictation_dismiss_notice)

    if (error != null) {
        val message = voiceDictationErrorMessage(error = error, quota = quota, timeContext = effectiveTimeContext)
        val validTraceId = raftValidTraceIdOrNull(voiceDictationErrorTraceId(error))
        RaftBanner(
            description = AnnotatedString(message),
            status = voiceDictationErrorBannerStatus(error),
            size = RaftBannerSize.Sm,
            showIcon = true,
            onDismiss = onDismissError,
            dismissLabel = dismissLabel,
            modifier = modifier.testTag(ThreadVoiceDictationErrorTestTag),
            content =
                validTraceId?.let { traceId ->
                    {
                        RaftTraceIdNote(
                            traceId = traceId,
                            uiErrorRoute = "voice/dictation/permits",
                        )
                    }
                },
        )
        return
    }

    if (showLowQuota && quota != null) {
        val message = voiceDictationLowQuotaMessage(quota = quota, timeContext = effectiveTimeContext)
        RaftBanner(
            description = AnnotatedString(message),
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
            showIcon = true,
            live = false,
            onDismiss = onDismissLowQuota,
            dismissLabel = dismissLabel,
            modifier = modifier.testTag(ThreadVoiceDictationQuotaTestTag),
        )
    }
}

/**
 * Toolbar Mic control for composer streaming voice dictation.
 * Hidden unless [host] satisfies all visibility gates (`voice_dictation_v1` ON, non-guest,
 * platform recorder available, editable composer).
 */
@Composable
internal fun ThreadVoiceDictationToolbarButton(
    host: ThreadVoiceDictationHost?,
    composerEditable: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (host == null) return
    val visible = host.canShow(composerEditable = composerEditable)
    LaunchedEffect(host.controller, visible) {
        if (!visible) {
            host.controller.cancel()
        }
    }
    if (!visible) return

    val state by host.controller.state.collectAsState()
    val resetsAtEpochMs = state.quotaExhaustedResetsAtEpochMs
    LaunchedEffect(host.controller, state.quotaExhausted, resetsAtEpochMs) {
        if (!state.quotaExhausted || resetsAtEpochMs == null) return@LaunchedEffect
        val remainingMs = resetsAtEpochMs - host.controller.currentEpochMillis()
        if (remainingMs <= 0L) {
            host.controller.refreshQuotaExhaustedState()
        } else {
            delay(remainingMs.coerceAtMost(VOICE_DICTATION_MAX_RESET_TIMER_MS))
            host.controller.refreshQuotaExhaustedState()
        }
    }

    ThreadVoiceDictationButton(
        state = state,
        enabled = composerEditable,
        onStart = onStart,
        onStop = host.controller::stop,
        isActiveNow = { host.controller.state.value.isActive },
        nowEpochMs = host.controller.currentEpochMillis(),
        modifier = modifier,
    )
}

/**
 * Pure presentation composable for the single-button dual-mode composer dictation control:
 * - [VoiceDictationV2Phase.Idle]: quiet ghost Mic icon button (or greyed-out disabled Mic with
 *   reset-time tooltip/tap feedback when daily quota is exhausted, even after dismissing banner).
 * - [VoiceDictationV2Phase.Connecting] / [VoiceDictationV2Phase.Listening]: active Raft-yellow
 *   circle with audio-reactive halo (static resting ring during `Connecting`, fixed ring under
 *   reduced motion).
 * - Dual-mode 350 ms gesture: short tap locks continuous listening (next tap stops); press & hold
 *   >= 350 ms shows a non-modal floating pill (`red dot + "Release to stop" + m:ss`) and stops on
 *   release. Non-blocking stop returns immediately to idle with no blocking spinner.
 */
@Composable
internal fun ThreadVoiceDictationButton(
    state: VoiceDictationV2State,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    isActiveNow: (() -> Boolean)? = null,
    timeContext: RaftTimeContext? = null,
    nowEpochMs: Long? = null,
    gestureModeOverride: VoiceDictationGestureMode? = null,
    elapsedMsOverride: Long? = null,
) {
    val phase = state.phase
    val isConnecting = phase == VoiceDictationV2Phase.Connecting
    val isListening = phase == VoiceDictationV2Phase.Listening
    val isActive = isConnecting || isListening

    val effectiveTimeContext = currentRaftEffectiveDateTimeContext(timeContext)
    val effectiveNowMs = nowEpochMs ?: Clock.System.now().toEpochMilliseconds()
    val isQuotaExhausted = !isActive && state.isQuotaExhaustedAt(effectiveNowMs)
    val isDisabled = !isActive && (!enabled || isQuotaExhausted)

    val startLabel = stringResource(Res.string.thread_voice_dictation_start)
    val stopLabel = stringResource(Res.string.thread_voice_dictation_stop)
    val connectingLabel = stringResource(Res.string.thread_voice_dictation_connecting)
    val listeningLabel = stringResource(Res.string.thread_voice_dictation_listening)
    val releaseToStopLabel = stringResource(Res.string.thread_voice_dictation_release_to_stop)
    val exhaustedTooltip =
        if (isQuotaExhausted) {
            voiceDictationQuotaExhaustedTooltip(
                resetsAtIso = state.quotaExhaustedResetsAtIso ?: state.quota?.resetsAtIso,
                timeContext = effectiveTimeContext,
            )
        } else {
            null
        }

    val gestureScope = rememberCoroutineScope()
    val currentIsActive by rememberUpdatedState(isActive)
    val currentIsActiveNow by rememberUpdatedState(isActiveNow)
    val currentIsDisabled by rememberUpdatedState(isDisabled)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)
    var liveGestureMode by remember { mutableStateOf(VoiceDictationGestureMode.Idle) }
    val gesture =
        remember(gestureScope) {
            VoiceDictationButtonGesture(
                scope = gestureScope,
                isActive = { currentIsActiveNow?.invoke() ?: currentIsActive },
                onStart = { currentOnStart() },
                onStop = { currentOnStop() },
                onModeChange = { liveGestureMode = it },
            )
        }

    LaunchedEffect(gesture, isActive) {
        if (!isActive) {
            gesture.syncSessionInactive()
        }
    }
    DisposableEffect(gesture) {
        onDispose { gesture.dispose() }
    }

    val effectiveGestureMode = gestureModeOverride ?: liveGestureMode
    val isHolding = isActive && effectiveGestureMode == VoiceDictationGestureMode.Holding

    var sessionStartMark by remember { mutableStateOf<TimeMark?>(null) }
    var liveElapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isActive) {
        if (isActive) {
            sessionStartMark = TimeSource.Monotonic.markNow()
            liveElapsedMs = 0L
        } else {
            sessionStartMark = null
            liveElapsedMs = 0L
        }
    }
    LaunchedEffect(isActive, isHolding, elapsedMsOverride) {
        if (!isActive || !isHolding || elapsedMsOverride != null) return@LaunchedEffect
        val startMark = sessionStartMark ?: TimeSource.Monotonic.markNow().also { sessionStartMark = it }
        while (true) {
            liveElapsedMs = startMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
            delay(VOICE_DICTATION_HOLD_CLOCK_TICK_MS)
        }
    }
    val effectiveElapsedMs = elapsedMsOverride ?: liveElapsedMs

    val reducedMotion = rememberRaftReducedMotion()
    // Keep the halo flat and still during Connecting until mic capture is live.
    val targetLevel = if (isListening) state.audioLevel else 0f
    val targetScale = voiceDictationHaloScale(audioLevel = targetLevel, reducedMotion = reducedMotion)
    val targetAlpha = voiceDictationHaloAlpha(audioLevel = targetLevel, reducedMotion = reducedMotion)
    val haloAnimSpec = if (reducedMotion) snap<Float>() else tween(durationMillis = 100)
    val animatedHaloScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = haloAnimSpec,
    )
    val animatedHaloAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = haloAnimSpec,
    )

    val phaseStateDescription =
        when {
            isHolding -> releaseToStopLabel
            isConnecting -> connectingLabel
            isListening -> listeningLabel
            exhaustedTooltip != null -> exhaustedTooltip
            else -> null
        }

    val buttonLabel = if (isActive) stopLabel else startLabel
    val tooltipText =
        when {
            isActive -> stopLabel
            exhaustedTooltip != null -> exhaustedTooltip
            else -> startLabel
        }

    // Keep one persistent pointerInput + RaftTooltip tree across Idle -> PendingHold -> Holding ->
    // Locked -> Idle so touch capture is never cancelled when transitioning into hold-to-talk at 350 ms.
    val pointerGestureModifier =
        Modifier.pointerInput(gesture) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (currentIsDisabled) return@awaitEachGesture
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
                    return@awaitEachGesture
                }
                val pointerId = down.id.value
                if (!gesture.handlePointerDown(pointerId = pointerId, isPrimary = true)) {
                    return@awaitEachGesture
                }
                var endedNormally = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (change.changedToUp()) {
                                gesture.handlePointerUp(pointerId = pointerId)
                                endedNormally = true
                            }
                            break
                        }
                    }
                } finally {
                    if (!endedNormally) {
                        gesture.handlePointerCancel(pointerId = pointerId)
                    }
                }
            }
        }

    RaftTooltip(
        text = tooltipText,
        modifier = modifier,
        openOnLongPress = isQuotaExhausted,
        openOnTap = isQuotaExhausted,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                Box(
                    modifier =
                        Modifier
                            .size(VoiceDictationActiveCircleSize)
                            .testTag(ThreadVoiceDictationHaloTestTag)
                            .graphicsLayer {
                                scaleX = animatedHaloScale
                                scaleY = animatedHaloScale
                                alpha = animatedHaloAlpha
                            }
                            .background(RaftUiPalette.selected, CircleShape),
                )
                Box(
                    modifier =
                        Modifier
                            .size(VoiceDictationActiveCircleSize)
                            .background(RaftUiPalette.selected, CircleShape),
                )
            }
            RaftIconButton(
                onClick = {
                    if (!isDisabled) {
                        gesture.handleClick()
                    }
                },
                tooltip = null,
                contentDescription = buttonLabel,
                modifier =
                    Modifier
                        .testTag(ThreadVoiceDictationButtonTestTag)
                        .then(pointerGestureModifier)
                        .then(
                            if (phaseStateDescription != null) {
                                Modifier.semantics { stateDescription = phaseStateDescription }
                            } else {
                                Modifier
                            },
                        ),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                enabled = !isDisabled,
            ) {
                RaftSlotIcon(
                    name = RaftLucideIconName.Mic,
                    color =
                        if (isActive) {
                            RaftUiPalette.stickerInk
                        } else {
                            RaftTheme.colors.foregroundMuted.takeIf { !isDisabled }
                        },
                )
            }

            if (isHolding) {
                ThreadVoiceDictationHoldPill(
                    label = releaseToStopLabel,
                    elapsedMs = effectiveElapsedMs,
                )
            }
        }
    }
}

/**
 * Non-modal floating pill anchored above the Mic button strictly during press-and-hold (`Holding`).
 * Uses `focusable = false` so it never steals composer focus or touch release events.
 */
@Composable
private fun ThreadVoiceDictationHoldPill(
    label: String,
    elapsedMs: Long,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { VoiceDictationHoldPillAnchorGap.roundToPx() }
    val edgePx = with(density) { VoiceDictationHoldPillEdgeMargin.roundToPx() }
    val positionProvider =
        remember(gapPx, edgePx) {
            ThreadVoiceDictationHoldPillPositionProvider(gapPx = gapPx, edgePx = edgePx)
        }
    val colors = RaftTheme.colors
    val elevation = if (RaftTheme.isDark) 8.dp else 4.dp

    Popup(
        popupPositionProvider = positionProvider,
        properties =
            PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .testTag(ThreadVoiceDictationHoldPillTestTag)
                    .shadow(elevation = elevation, shape = CircleShape)
                    .background(colors.layerPopover, CircleShape)
                    .border(RaftStroke.Hairline, colors.lineSeam, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(VoiceDictationHoldDotSize)
                        .background(RaftUiPalette.danger, CircleShape),
            )
            RaftText(
                text = label,
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
            )
            RaftText(
                text = formatVoiceDictationClock(elapsedMs),
                style =
                    RaftTheme.typography.caption.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Places the hold pill above and end-aligned with the Mic button, clamped within window bounds. */
private class ThreadVoiceDictationHoldPillPositionProvider(
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.right - popupContentSize.width
            } else {
                anchorBounds.left
            }
        val maxX = (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx)
        val x = preferredX.coerceIn(edgePx, maxX)
        val aboveY = anchorBounds.top - popupContentSize.height - gapPx
        val y =
            if (aboveY >= edgePx) {
                aboveY
            } else {
                (anchorBounds.bottom + gapPx).coerceAtMost(
                    (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(edgePx),
                )
            }
        return IntOffset(x = x, y = y)
    }
}

@Composable
private fun voiceDictationErrorMessage(
    error: VoiceDictationV2Error,
    quota: VoiceDictationV2Quota?,
    timeContext: RaftTimeContext,
): String =
    when (error) {
        VoiceDictationV2Error.PermissionDenied ->
            stringResource(Res.string.thread_voice_dictation_error_mic_permission)

        VoiceDictationV2Error.DeviceUnavailable ->
            stringResource(Res.string.thread_voice_dictation_error_mic_none)

        is VoiceDictationV2Error.AudioCaptureFailed ->
            stringResource(Res.string.thread_voice_dictation_error_failed)

        is VoiceDictationV2Error.PermitRefused -> {
            val failure = error.failure
            when (failure) {
                is VoiceDictationV2PermitFailure.Disabled ->
                    stringResource(Res.string.thread_voice_dictation_error_disabled)

                is VoiceDictationV2PermitFailure.QuotaExceeded ->
                    voiceDictationQuotaExceededMessage(
                        resetsAtIso = failure.resetsAtIso ?: quota?.resetsAtIso,
                        timeContext = timeContext,
                    )

                is VoiceDictationV2PermitFailure.RateLimited -> {
                    val seconds = failure.retryAfterSeconds
                    if (seconds != null && seconds > 0) {
                        stringResource(Res.string.thread_voice_dictation_error_rate_limited, seconds)
                    } else {
                        stringResource(Res.string.thread_voice_dictation_error_rate_limited_no_seconds)
                    }
                }

                is VoiceDictationV2PermitFailure.Unavailable ->
                    stringResource(Res.string.thread_voice_dictation_error_unavailable)

                is VoiceDictationV2PermitFailure.InvalidRequest,
                is VoiceDictationV2PermitFailure.Failed ->
                    stringResource(Res.string.thread_voice_dictation_error_failed)
            }
        }

        is VoiceDictationV2Error.StreamError -> {
            val code = error.code
            when (code) {
                VoiceDictationV2ErrorCode.SessionLimitReached ->
                    stringResource(Res.string.thread_voice_dictation_error_session_limit)

                VoiceDictationV2ErrorCode.QuotaExceeded ->
                    voiceDictationQuotaExceededMessage(
                        resetsAtIso = quota?.resetsAtIso,
                        timeContext = timeContext,
                    )

                VoiceDictationV2ErrorCode.Busy ->
                    stringResource(Res.string.thread_voice_dictation_error_busy)

                VoiceDictationV2ErrorCode.Unavailable ->
                    stringResource(Res.string.thread_voice_dictation_error_unavailable)

                VoiceDictationV2ErrorCode.InvalidPermit,
                VoiceDictationV2ErrorCode.ProtocolError,
                is VoiceDictationV2ErrorCode.Unknown ->
                    stringResource(Res.string.thread_voice_dictation_error_failed)
            }
        }

        is VoiceDictationV2Error.ConnectionLost ->
            stringResource(Res.string.thread_voice_dictation_error_connection_lost)
    }

@Composable
private fun voiceDictationQuotaExceededMessage(
    resetsAtIso: String?,
    timeContext: RaftTimeContext,
): String {
    val reset = formatVoiceDictationResetTime(resetsAtIso = resetsAtIso, timeContext = timeContext)
    return when {
        reset == null ->
            stringResource(Res.string.thread_voice_dictation_error_quota_exceeded_no_reset)
        reset.isTomorrow ->
            stringResource(Res.string.thread_voice_dictation_error_quota_exceeded_tomorrow, reset.clockText)
        else ->
            stringResource(Res.string.thread_voice_dictation_error_quota_exceeded, reset.clockText)
    }
}

@Composable
private fun voiceDictationQuotaExhaustedTooltip(
    resetsAtIso: String?,
    timeContext: RaftTimeContext,
): String {
    val reset = formatVoiceDictationResetTime(resetsAtIso = resetsAtIso, timeContext = timeContext)
    return when {
        reset == null ->
            stringResource(Res.string.thread_voice_dictation_quota_exhausted_tooltip_no_reset)
        reset.isTomorrow ->
            stringResource(Res.string.thread_voice_dictation_quota_exhausted_tooltip_tomorrow, reset.clockText)
        else ->
            stringResource(Res.string.thread_voice_dictation_quota_exhausted_tooltip, reset.clockText)
    }
}

@Composable
private fun voiceDictationLowQuotaMessage(
    quota: VoiceDictationV2Quota,
    timeContext: RaftTimeContext,
): String {
    val minutes = voiceDictationLowQuotaRemainingMinutes(quota.remainingSeconds)
    val reset = formatVoiceDictationResetTime(resetsAtIso = quota.resetsAtIso, timeContext = timeContext)
    if (minutes == null) {
        return when {
            reset == null ->
                stringResource(Res.string.thread_voice_dictation_low_quota_under_minute_no_reset)
            reset.isTomorrow ->
                stringResource(Res.string.thread_voice_dictation_low_quota_under_minute_tomorrow, reset.clockText)
            else ->
                stringResource(Res.string.thread_voice_dictation_low_quota_under_minute, reset.clockText)
        }
    }
    val one = minutes == 1
    return when {
        reset == null ->
            stringResource(
                if (one) {
                    Res.string.thread_voice_dictation_low_quota_no_reset_one
                } else {
                    Res.string.thread_voice_dictation_low_quota_no_reset_other
                },
                minutes,
            )
        reset.isTomorrow ->
            stringResource(
                if (one) {
                    Res.string.thread_voice_dictation_low_quota_tomorrow_one
                } else {
                    Res.string.thread_voice_dictation_low_quota_tomorrow_other
                },
                minutes,
                reset.clockText,
            )
        else ->
            stringResource(
                if (one) {
                    Res.string.thread_voice_dictation_low_quota_one
                } else {
                    Res.string.thread_voice_dictation_low_quota_other
                },
                minutes,
                reset.clockText,
            )
    }
}
