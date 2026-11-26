package build.raft.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.RaftVoiceDictationV2PermitApi
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.VoiceDictationV2Controller
import build.raft.app.data.VoiceDictationV2Error
import build.raft.app.data.VoiceDictationV2ErrorCode
import build.raft.app.data.VoiceDictationV2PermitFailure
import build.raft.app.data.VoiceDictationV2Phase
import build.raft.app.data.VoiceDictationV2Quota
import build.raft.app.data.VoiceDictationV2State
import build.raft.app.data.VoiceDictationV2StreamClient
import build.raft.app.data.VoiceDictationV2StreamConnection
import build.raft.app.data.VoiceDictationV2StreamConnector
import build.raft.app.data.normalizeVoiceDictationLanguage
import build.raft.app.data.offsetMinutesAt
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.floorDiv
import build.raft.app.model.formatRaftLocalClock
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.realtime.socket.KtorRaftWebSocketConnector
import build.raft.realtime.socket.createRaftRealtimeHttpClient
import kotlin.math.ceil

// Composer streaming voice dictation UI controls, gating, and localized feedback
// (`#wg-mobile-sync:#334`). Mirrors web `ComposerVoiceDictationControls.tsx`,
// `ComposerVoiceDictationNotice.tsx`, and `voiceDictationPresentation.ts`.

internal const val ThreadVoiceDictationButtonTestTag: String = "composer-voice-dictation-button"
internal const val ThreadVoiceDictationHaloTestTag: String = "composer-voice-dictation-halo"
internal const val ThreadVoiceDictationStoppingTestTag: String = "composer-voice-dictation-stopping"
internal const val ThreadVoiceDictationErrorTestTag: String = "composer-voice-dictation-error"
internal const val ThreadVoiceDictationQuotaTestTag: String = "composer-voice-dictation-quota"

/** Resting outer halo scale around the 24 dp active mic circle (28 dp outer, 2 dp flat ring). */
private const val VOICE_DICTATION_HALO_SCALE_MIN: Float = 28f / 24f

/** Additional outward expansion at peak speech (+6 dp per side -> 40 dp outer). */
private const val VOICE_DICTATION_HALO_SCALE_RANGE: Float = 12f / 24f

/** Outer halo alpha range matching web Kirby spec (`0.25` at rest -> `0.60` at peak speech). */
private const val VOICE_DICTATION_HALO_ALPHA_MIN: Float = 0.25f
private const val VOICE_DICTATION_HALO_ALPHA_MAX: Float = 0.60f

/** Low-quota warning thresholds (in seconds): remaining > 0 and <= ~10% of limit, clamped to [150 s, 600 s]. */
private const val VOICE_DICTATION_MIN_LOW_QUOTA_SECONDS: Int = 150
private const val VOICE_DICTATION_MAX_LOW_QUOTA_SECONDS: Int = 600
private const val VOICE_DICTATION_LOW_QUOTA_FRACTION: Double = 0.10

// not a token: web ComposerVoiceDictationControls size-6 (24 px) active mic circle
private val VoiceDictationActiveCircleSize = 24.dp

// not a token: web ComposerVoiceDictationControls size-3.5 (14 px) mic glyph inside the active circle
private val VoiceDictationActiveGlyphSize = 14.dp

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

/** Computes outer-halo scale (`1.167..1.667`) from normalized audio level in `0f..1f`. */
internal fun voiceDictationHaloScale(audioLevel: Float): Float {
    val clamped = if (audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f
    return VOICE_DICTATION_HALO_SCALE_MIN + clamped * VOICE_DICTATION_HALO_SCALE_RANGE
}

/** Computes outer-halo alpha (`0.25..0.60`) from normalized audio level in `0f..1f`. */
internal fun voiceDictationHaloAlpha(audioLevel: Float): Float {
    val clamped = if (audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f
    return VOICE_DICTATION_HALO_ALPHA_MIN + clamped * (VOICE_DICTATION_HALO_ALPHA_MAX - VOICE_DICTATION_HALO_ALPHA_MIN)
}

/**
 * Returns true when daily dictation quota is nearly exhausted (remaining > 0 and <= ~10% of limit,
 * clamped to `[150 s, 600 s]`, matching web's 5..20 30-second permits).
 */
internal fun isVoiceDictationV2QuotaLow(quota: VoiceDictationV2Quota?): Boolean {
    if (quota == null) return false
    val limit = quota.limitSeconds
    val remaining = quota.remainingSeconds
    if (limit <= 0 || remaining <= 0) return false
    val proportional = ceil(limit * VOICE_DICTATION_LOW_QUOTA_FRACTION).toInt()
    val threshold = proportional.coerceIn(VOICE_DICTATION_MIN_LOW_QUOTA_SECONDS, VOICE_DICTATION_MAX_LOW_QUOTA_SECONDS)
    return remaining <= threshold
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
    val flagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    val effectiveServerId =
        (headerProjectionServerId?.value ?: currentServerId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val serverIdObj = remember(effectiveServerId) { effectiveServerId?.let(::ServerId) }
    val flagEnabled =
        remember(sharedStores.serverFeatureFlags, serverIdObj, flagRevision) {
            sharedStores.serverFeatureFlags.isVoiceDictationEnabled(serverIdObj)
        }
    val recorderAvailable = audioRecorderProvider() != null
    val signedInUserId = currentSession?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveChannelId =
        renderedThread.streamChannelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: renderedThread.parentChannelId?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveThreadId =
        renderedThread.parentMessageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: renderedThread.sourceThreadId?.trim()?.takeIf { it.isNotEmpty() }
    val localeTag = rememberRaftUiLocaleTag()
    val languageHint = remember(localeTag) { normalizeVoiceDictationLanguage(localeTag) }

    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val dictationScope = rememberCoroutineScope()
    val controller =
        remember(networkClient, dictationScope, audioRecorderProvider) {
            VoiceDictationV2Controller(
                permitApi = RaftVoiceDictationV2PermitApi(networkClient),
                streamConnector = SharedVoiceDictationStreamConnector,
                scope = dictationScope,
                audioRecorderProvider = audioRecorderProvider,
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

    LaunchedEffect(controller, active, flagEnabled, viewerIsGuest, recorderAvailable, renderedThread.channelArchived) {
        if (!active || !flagEnabled || viewerIsGuest || !recorderAvailable || renderedThread.channelArchived) {
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
        languageHint,
        controller,
        renderedThread.channelArchived,
    ) {
        ThreadVoiceDictationHost(
            flagEnabled = flagEnabled && !renderedThread.channelArchived,
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

    if (error == null && !host.canShow(composerEditable)) return

    val showLowQuota =
        error == null &&
            isVoiceDictationV2QuotaLow(quota) &&
            dismissedLowQuotaResetsAt != (quota?.resetsAtIso ?: "")

    ThreadVoiceDictationNotice(
        error = error,
        quota = quota,
        showLowQuota = showLowQuota,
        onDismissError = host.controller::clearError,
        onDismissLowQuota = { dismissedLowQuotaResetsAt = quota?.resetsAtIso ?: "" },
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
            status = RaftBannerStatus.Warning,
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
    ThreadVoiceDictationButton(
        state = state,
        enabled = composerEditable,
        onStart = onStart,
        onStop = host.controller::stop,
        onCancel = host.controller::cancel,
        modifier = modifier,
    )
}

/**
 * Pure presentation composable for the composer toolbar dictation button across all v2 phases:
 * - [VoiceDictationV2Phase.Idle]: quiet ghost Mic icon button.
 * - [VoiceDictationV2Phase.Connecting] / [VoiceDictationV2Phase.Listening]: active Raft-yellow
 *   circle with audio-level-driven halo; tap stops gracefully, long-press cancels immediately.
 * - [VoiceDictationV2Phase.Stopping]: compact non-blocking draining spinner while final refined
 *   transcript settles; tap cancels immediately.
 */
@Composable
internal fun ThreadVoiceDictationButton(
    state: VoiceDictationV2State,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val isConnecting = phase == VoiceDictationV2Phase.Connecting
    val isListening = phase == VoiceDictationV2Phase.Listening
    val isStopping = phase == VoiceDictationV2Phase.Stopping
    val isActiveMic = isConnecting || isListening

    val startLabel = stringResource(Res.string.thread_voice_dictation_start)
    val stopLabel = stringResource(Res.string.thread_voice_dictation_stop)
    val cancelLabel = stringResource(Res.string.thread_voice_dictation_cancel)
    val connectingLabel = stringResource(Res.string.thread_voice_dictation_connecting)
    val listeningLabel = stringResource(Res.string.thread_voice_dictation_listening)
    val finishingLabel = stringResource(Res.string.thread_voice_dictation_finishing)

    // Keep the halo still during Connecting until mic capture + `ready` are live.
    val targetLevel = if (isListening) state.audioLevel else 0f
    val targetScale = voiceDictationHaloScale(targetLevel)
    val targetAlpha = voiceDictationHaloAlpha(targetLevel)
    val animatedHaloScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 100),
    )
    val animatedHaloAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 100),
    )

    val phaseStateDescription =
        when (phase) {
            VoiceDictationV2Phase.Idle -> null
            VoiceDictationV2Phase.Connecting -> connectingLabel
            VoiceDictationV2Phase.Listening -> listeningLabel
            VoiceDictationV2Phase.Stopping -> finishingLabel
        }

    val buttonLabel =
        when (phase) {
            VoiceDictationV2Phase.Idle -> startLabel
            VoiceDictationV2Phase.Connecting,
            VoiceDictationV2Phase.Listening -> stopLabel
            VoiceDictationV2Phase.Stopping -> finishingLabel
        }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (isActiveMic) {
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
                when (phase) {
                    VoiceDictationV2Phase.Idle -> onStart()
                    VoiceDictationV2Phase.Connecting,
                    VoiceDictationV2Phase.Listening -> onStop()
                    VoiceDictationV2Phase.Stopping -> onCancel()
                }
            },
            tooltip = buttonLabel,
            contentDescription = buttonLabel,
            modifier =
                Modifier
                    .testTag(ThreadVoiceDictationButtonTestTag)
                    .then(
                        if (phaseStateDescription != null) {
                            Modifier.semantics { stateDescription = phaseStateDescription }
                        } else {
                            Modifier
                        },
                    ),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
            enabled = enabled || isActiveMic || isStopping,
            onLongClick = if (isActiveMic) onCancel else null,
            longClickLabel = if (isActiveMic) cancelLabel else null,
        ) {
            when {
                isStopping -> {
                    RaftSpinner(
                        modifier = Modifier.testTag(ThreadVoiceDictationStoppingTestTag),
                        size = RaftSpinnerSize.Sm,
                        color = RaftTheme.colors.foregroundMuted,
                        contentDescription = finishingLabel,
                    )
                }
                isActiveMic -> {
                    RaftSlotIcon(
                        name = RaftLucideIconName.Mic,
                        size = VoiceDictationActiveGlyphSize,
                        color = RaftUiPalette.stickerInk,
                    )
                }
                else -> {
                    RaftSlotIcon(
                        name = RaftLucideIconName.Mic,
                        color = RaftTheme.colors.foregroundMuted.takeIf { enabled },
                    )
                }
            }
        }
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

                VoiceDictationV2ErrorCode.Busy,
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
private fun voiceDictationLowQuotaMessage(
    quota: VoiceDictationV2Quota,
    timeContext: RaftTimeContext,
): String {
    val reset = formatVoiceDictationResetTime(resetsAtIso = quota.resetsAtIso, timeContext = timeContext)
    return when {
        reset == null ->
            stringResource(Res.string.thread_voice_dictation_low_quota_no_reset)
        reset.isTomorrow ->
            stringResource(Res.string.thread_voice_dictation_low_quota_tomorrow, reset.clockText)
        else ->
            stringResource(Res.string.thread_voice_dictation_low_quota, reset.clockText)
    }
}
