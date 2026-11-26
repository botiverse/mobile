package build.raft.app.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.RaftAppAvatarSticker
import build.raft.app.RaftAppBottomTabBarHostZIndex
import build.raft.app.RaftAvatarKind
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.data.AGENT_VOICE_CALL_MAX_MINUTES
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallProblem
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.SharedAgentVoiceCallStore
import build.raft.app.data.formatAgentVoiceCallClock
import build.raft.app.data.raftNowEpochMillis
import build.raft.app.model.MemberKind
import build.raft.app.raftBottomSafeAreaPadding
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlinx.coroutines.delay

// Floating Agent Voice call card overlay (`#wg-mobile-sync:#95`), mirroring web
// `AgentVoiceCallWindow.tsx`, `AgentVoiceCallCard.tsx`, and `AgentVoiceCallPanel.tsx`.
// Mounted once at the app shell root (and on standalone pushed pages when outside the shell)
// so an active call survives tab switches, navigation, and server switches without blocking
// interaction with the conversation behind it.

/** True inside an ancestor host that already mounts [AgentVoiceCallOverlayHost]. */
internal val LocalAgentVoiceCallHostMounted = compositionLocalOf { false }

/** Above content and bottom tab bar, below modals/popovers/banners/toasts (mirrors web `z-app`). */
internal const val AgentVoiceCallOverlayZIndex: Float = RaftAppBottomTabBarHostZIndex + 0.25f

private val AgentVoiceCallCardMaxWidth = 360.dp
private val AgentVoiceCallCardHorizontalInset = 12.dp
private val AgentVoiceCallCardBottomGap = 16.dp
private val AgentVoiceCallSpeakingRingStroke = 2.dp
private val AgentVoiceCallSpeakingRingPadding = 2.dp

/**
 * Observes [store] and floats [AgentVoiceCallCard] above the bottom tab bar / safe-area inset
 * while a call is active, ended, or failed. Emits nothing while idle.
 */
@Composable
internal fun AgentVoiceCallOverlayHost(
    store: SharedAgentVoiceCallStore?,
    bottomClearance: Dp = raftBottomSafeAreaPadding(),
    modifier: Modifier = Modifier,
) {
    if (store == null) return
    val call by store.call.collectAsState()
    val currentCall = call ?: return
    val runningClock = currentCall.phase == AgentVoiceCallPhase.Live || currentCall.phase == AgentVoiceCallPhase.Reconnecting
    var nowEpochMs by remember(currentCall.callId, currentCall.connectedAtEpochMs) {
        mutableStateOf(raftNowEpochMillis())
    }
    LaunchedEffect(runningClock, currentCall.callId, currentCall.connectedAtEpochMs) {
        if (!runningClock) return@LaunchedEffect
        nowEpochMs = raftNowEpochMillis()
        while (true) {
            delay(1_000L)
            nowEpochMs = raftNowEpochMillis()
        }
    }
    val alignment = if (RaftTheme.scale == RaftScale.Wide) Alignment.BottomEnd else Alignment.BottomCenter
    Box(
        modifier = modifier.fillMaxSize().zIndex(AgentVoiceCallOverlayZIndex),
        contentAlignment = alignment,
    ) {
        AgentVoiceCallCard(
            call = currentCall,
            nowEpochMs = nowEpochMs,
            onToggleMute = store::toggleMute,
            onToggleSpeaker = store::toggleSpeaker,
            onHangUp = store::hangUp,
            onClose = store::dismissEnded,
            onRetry = store::retry,
            modifier =
                Modifier
                    .padding(
                        start = AgentVoiceCallCardHorizontalInset,
                        end = AgentVoiceCallCardHorizontalInset,
                        bottom = bottomClearance.coerceAtLeast(0.dp) + AgentVoiceCallCardBottomGap,
                    )
                    .widthIn(max = AgentVoiceCallCardMaxWidth)
                    .fillMaxWidth(),
        )
    }
}

/**
 * Pure presentation for the floating Agent Voice call card (mirrors web `AgentVoiceCallPanel.tsx`):
 * agent avatar (with a still `presence-online` ring while speaking), agent name + live phase / clock,
 * last two caption lines, mid-call mic warning banner, typed failure/end notes, and Mute / Speaker /
 * Hang up (or Retry / Close once over).
 */
@Composable
internal fun AgentVoiceCallCard(
    call: AgentVoiceCallState,
    nowEpochMs: Long,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val agentName = call.agent.name
    val phase = call.phase
    val inCall = phase == AgentVoiceCallPhase.Live || phase == AgentVoiceCallPhase.Reconnecting
    val over = !call.isActive

    val announcement =
        when (phase) {
            AgentVoiceCallPhase.Starting,
            AgentVoiceCallPhase.Connecting -> stringResource(Res.string.agent_voice_call_connecting)
            AgentVoiceCallPhase.Reconnecting -> stringResource(Res.string.agent_voice_call_reconnecting)
            AgentVoiceCallPhase.Live -> stringResource(Res.string.agent_voice_call_connected)
            AgentVoiceCallPhase.Ended ->
                stringResource(Res.string.agent_voice_call_ended, formatAgentVoiceCallClock(call.durationMs ?: 0L))
            AgentVoiceCallPhase.Failed -> stringResource(Res.string.agent_voice_call_not_connected)
        }
    val stateLine =
        if (phase == AgentVoiceCallPhase.Live) {
            val liveSince = call.connectedAtEpochMs ?: nowEpochMs
            formatAgentVoiceCallClock((nowEpochMs - liveSince).coerceAtLeast(0L))
        } else {
            announcement
        }

    val blockedMicReason = call.micBlockedReason?.takeIf { it.isNotBlank() }
    val micWarningText =
        if (inCall && blockedMicReason != null) {
            agentVoiceLiveMicWarningText(blockedMicReason, agentName)
        } else {
            null
        }

    val notes = buildCallNotes(call = call, agentName = agentName)
    val windowLabel = stringResource(Res.string.agent_voice_call_window, agentName)
    val muteLabel = stringResource(Res.string.agent_voice_call_mute)
    val soundOffLabel = stringResource(Res.string.agent_voice_call_sound_off)
    val micUnavailableLabel = stringResource(Res.string.agent_voice_call_mic_unavailable)
    val hangUpLabel = stringResource(Res.string.agent_voice_call_hang_up)
    val youLabel = stringResource(Res.string.agent_voice_call_you)
    val cardShape = RoundedCornerShape(RaftRadius.Container)

    CompositionLocalProvider(
        LocalRaftContentColor provides colors.foreground,
        LocalRaftSurfaceColor provides colors.layerPopover,
    ) {
        Column(
            modifier =
                modifier
                    .testTag("agent-voice-call-window")
                    .semantics { contentDescription = windowLabel }
                    .shadow(elevation = if (RaftTheme.isDark) 8.dp else 4.dp, shape = cardShape)
                    .clip(cardShape)
                    .background(colors.layerPopover, cardShape)
                    .border(RaftStroke.Hairline, colors.lineSeam, cardShape)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ringShape = RoundedCornerShape(RaftRadius.Control)
                val ringModifier =
                    if (call.agentSpeaking) {
                        Modifier
                            .testTag("agent-voice-call-speaking-ring")
                            .border(AgentVoiceCallSpeakingRingStroke, RaftUiPalette.presenceOnline, ringShape)
                            .padding(AgentVoiceCallSpeakingRingPadding)
                    } else {
                        Modifier.padding(AgentVoiceCallSpeakingRingPadding)
                    }
                Box(modifier = ringModifier) {
                    RaftAppAvatarSticker(
                        kind = RaftAvatarKind.Agent,
                        avatarWireValue = call.agent.avatarWireValue,
                        label = agentName,
                        size = RaftAvatarSize.Md,
                        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, call.agent.id),
                        cacheServerId = call.serverId,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RaftText(
                        text = agentName,
                        modifier = Modifier.testTag("agent-voice-call-name"),
                        style = typography.cardTitle,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftText(
                        text = stateLine,
                        modifier =
                            Modifier
                                .testTag("agent-voice-call-state")
                                .semantics {
                                    if (phase != AgentVoiceCallPhase.Live) {
                                        liveRegion = LiveRegionMode.Polite
                                    }
                                },
                        style = typography.callout.copy(fontFeatureSettings = "tnum"),
                        color = colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (call.captions.isNotEmpty() && !over) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("agent-voice-call-captions"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    call.captions.forEach { line ->
                        val who =
                            stringResource(
                                Res.string.agent_voice_call_caption_who,
                                if (line.mine) youLabel else agentName,
                            )
                        val annotated =
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(who)
                                }
                                append(' ')
                                append(line.text)
                            }
                        RaftText(
                            text = annotated,
                            style = typography.footnote,
                            color = if (line.mine) colors.foregroundMuted else colors.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (micWarningText != null) {
                RaftBanner(
                    description = micWarningText,
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    showIcon = true,
                    modifier = Modifier.testTag("agent-voice-call-mic"),
                )
            }

            if (notes.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("agent-voice-call-notes"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    notes.forEach { note ->
                        RaftText(
                            text = note.text,
                            style = typography.footnote,
                            color = if (note.muted) colors.foregroundMuted else colors.foreground,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (over) {
                    if (phase == AgentVoiceCallPhase.Failed && call.problem?.recoverable != false) {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = onRetry,
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            modifier = Modifier.testTag("agent-voice-call-retry"),
                        )
                    }
                    RaftButton(
                        text = stringResource(Res.string.common_close),
                        onClick = onClose,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag("agent-voice-call-close"),
                    )
                } else {
                    if (inCall) {
                        val micBlocked = blockedMicReason != null
                        RaftIconToggleButton(
                            checked = call.muted,
                            onCheckedChange = { onToggleMute() },
                            tooltip = if (micBlocked) micUnavailableLabel else muteLabel,
                            size = RaftIconButtonSize.Lg,
                            enabled = !micBlocked,
                            modifier = Modifier.testTag("agent-voice-call-mute"),
                        ) {
                            RaftContentLucideIcon(
                                name = if (call.muted || micBlocked) RaftLucideIconName.MicOff else RaftLucideIconName.Mic,
                                size = RaftTheme.controls.iconSize,
                            )
                        }
                        RaftIconToggleButton(
                            checked = !call.speakerOn,
                            onCheckedChange = { onToggleSpeaker() },
                            tooltip = soundOffLabel,
                            size = RaftIconButtonSize.Lg,
                            modifier = Modifier.testTag("agent-voice-call-sound"),
                        ) {
                            RaftContentLucideIcon(
                                name = if (!call.speakerOn) RaftLucideIconName.VolumeOff else RaftLucideIconName.Volume2,
                                size = RaftTheme.controls.iconSize,
                            )
                        }
                    }
                    RaftIconButton(
                        onClick = onHangUp,
                        tooltip = hangUpLabel,
                        variant = RaftButtonVariant.Warning,
                        size = RaftIconButtonSize.Lg,
                        modifier = Modifier.testTag("agent-voice-call-hang-up"),
                    ) {
                        RaftContentLucideIcon(
                            name = RaftLucideIconName.PhoneOff,
                            size = RaftTheme.controls.iconSize,
                        )
                    }
                }
            }
        }
    }
}

private data class AgentVoiceCallNoteLine(
    val text: String,
    val muted: Boolean,
)

@Composable
private fun buildCallNotes(
    call: AgentVoiceCallState,
    agentName: String,
): List<AgentVoiceCallNoteLine> =
    buildList {
        if (call.phase == AgentVoiceCallPhase.Failed) {
            val problem = call.problem ?: AgentVoiceCallProblem.Failed
            add(
                AgentVoiceCallNoteLine(
                    text = agentVoiceProblemText(problem, agentName),
                    muted = problem.recoverable,
                )
            )
        }
        if (call.phase == AgentVoiceCallPhase.Ended) {
            if (call.endReason == AgentVoiceCallEndReason.Lost) {
                add(
                    AgentVoiceCallNoteLine(
                        text = stringResource(Res.string.agent_voice_call_connection_lost),
                        muted = false,
                    )
                )
            }
            if (call.limitReached) {
                add(
                    AgentVoiceCallNoteLine(
                        text = stringResource(Res.string.agent_voice_call_limit, AGENT_VOICE_CALL_MAX_MINUTES),
                        muted = false,
                    )
                )
            }
            add(
                AgentVoiceCallNoteLine(
                    text = stringResource(Res.string.agent_voice_call_record, agentName),
                    muted = false,
                )
            )
        }
    }

@Composable
private fun agentVoiceProblemText(
    problem: AgentVoiceCallProblem,
    agentName: String,
): String =
    when (problem) {
        AgentVoiceCallProblem.NotAllowed -> stringResource(Res.string.agent_voice_call_problem_not_allowed)
        AgentVoiceCallProblem.AgentOffline -> stringResource(Res.string.agent_voice_call_problem_agent_offline, agentName)
        AgentVoiceCallProblem.Unavailable -> stringResource(Res.string.agent_voice_call_problem_unavailable)
        AgentVoiceCallProblem.UnsupportedRuntime -> stringResource(Res.string.agent_voice_call_problem_unsupported_runtime, agentName)
        AgentVoiceCallProblem.RateLimited -> stringResource(Res.string.agent_voice_call_problem_rate_limited)
        AgentVoiceCallProblem.Failed -> stringResource(Res.string.agent_voice_call_problem_failed)
        AgentVoiceCallProblem.MicPermission -> stringResource(Res.string.agent_voice_call_mic_permission)
        AgentVoiceCallProblem.MicBusy -> stringResource(Res.string.agent_voice_call_mic_busy)
        AgentVoiceCallProblem.MicNone -> stringResource(Res.string.agent_voice_call_mic_none)
    }

@Composable
private fun agentVoiceLiveMicWarningText(
    reason: String,
    agentName: String,
): String =
    when (reason.trim().lowercase()) {
        "busy", "mic_busy", "in_use" -> stringResource(Res.string.agent_voice_call_mic_live_busy, agentName)
        "none", "mic_none", "unavailable", "disconnected" -> stringResource(Res.string.agent_voice_call_mic_live_none, agentName)
        else -> stringResource(Res.string.agent_voice_call_mic_live_permission, agentName)
    }
