package build.raft.app.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.AgentVoiceCallFeedbackRequest
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.VOICE_FEEDBACK_NOTE_MAX_CHARS
import build.raft.app.data.VoiceFeedbackRating
import build.raft.app.data.VoiceFeedbackSubmitFailure
import build.raft.app.data.VoiceFeedbackSubmitOutcome
import build.raft.app.data.VoiceFeedbackTag
import build.raft.app.data.voiceFeedbackAllowed
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.app.resources.*
import build.raft.app.voice.VoiceFeedbackTagChip
import build.raft.app.voice.voiceFeedbackFailureMessage
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Post-call 1-tap ThumbsUp/ThumbsDown + optional tag/note feedback section inside the Ended state
// of [AgentVoiceCallCard] (`#wg-call:#7`, mirroring web `AgentVoiceCallFeedback` in
// `AgentVoiceCallPanel.tsx`, `botiverse/slock#17246`).

internal const val AgentVoiceCallFeedbackTestTag: String = "agent-voice-call-feedback"
internal const val AgentVoiceCallFeedbackGoodTestTag: String = "agent-voice-call-feedback-good"
internal const val AgentVoiceCallFeedbackBadTestTag: String = "agent-voice-call-feedback-bad"
internal const val AgentVoiceCallFeedbackDetailsTestTag: String = "agent-voice-call-feedback-details"
internal const val AgentVoiceCallFeedbackNoteTestTag: String = "agent-voice-call-feedback-note"
internal const val AgentVoiceCallFeedbackSubmitTestTag: String = "agent-voice-call-feedback-submit"
internal const val AgentVoiceCallFeedbackSavedTestTag: String = "agent-voice-call-feedback-saved"
internal const val AgentVoiceCallFeedbackErrorTestTag: String = "agent-voice-call-feedback-error"

internal fun agentVoiceCallFeedbackTagTestTag(tag: VoiceFeedbackTag): String =
    "agent-voice-call-feedback-tag-${tag.wire}"

/** Closed tag options shown when refining a negative voice call rating (web parity order). */
internal val AgentVoiceCallFeedbackTagOptions: List<VoiceFeedbackTag> =
    listOf(
        VoiceFeedbackTag.WrongWord,
        VoiceFeedbackTag.TooSlow,
        VoiceFeedbackTag.WrongAnswer,
        VoiceFeedbackTag.CutOff,
        VoiceFeedbackTag.Interrupted,
        VoiceFeedbackTag.Other,
    )

/**
 * Fail-closed visibility gate for post-call rating UI in [AgentVoiceCallCard]:
 * shown ONLY in [AgentVoiceCallPhase.Ended] when a server-minted [callId] is present, a submit
 * handler is wired, and `RAFT_PRIVACY_MODE` is explicitly [RaftPrivacyMode.Off].
 */
internal fun canShowAgentVoiceCallFeedback(
    phase: AgentVoiceCallPhase,
    callId: String?,
    privacyMode: RaftPrivacyMode,
    hasSubmitHandler: Boolean,
): Boolean =
    phase == AgentVoiceCallPhase.Ended &&
        !callId.isNullOrBlank() &&
        hasSubmitHandler &&
        voiceFeedbackAllowed(privacyMode)

/**
 * Compact 1-tap post-call rating bar with optional issue tags + short note refinement.
 * Both ThumbsUp (`Good`) and ThumbsDown (`Bad`) submit immediately via Raft's idempotent proxy
 * route, and ThumbsDown expands optional tags/note controls to refine and overwrite the rating.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentVoiceCallFeedbackSection(
    callId: String,
    onSubmitFeedback: suspend (AgentVoiceCallFeedbackRequest) -> VoiceFeedbackSubmitOutcome,
    modifier: Modifier = Modifier,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmitFeedback)
    val scope = rememberCoroutineScope()

    key(callId) {
        var rating by remember { mutableStateOf<VoiceFeedbackRating?>(null) }
        var selectedTags by remember { mutableStateOf<List<VoiceFeedbackTag>>(emptyList()) }
        var note by remember { mutableStateOf("") }
        var submitting by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<VoiceFeedbackSubmitFailure?>(null) }

        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        val goodLabel = stringResource(Res.string.agent_voice_call_feedback_good)
        val badLabel = stringResource(Res.string.agent_voice_call_feedback_bad)

        fun dispatchSubmit(request: AgentVoiceCallFeedbackRequest) {
            if (submitting) return
            submitting = true
            saved = false
            failure = null
            scope.launch {
                val outcome =
                    try {
                        currentOnSubmit(request)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        VoiceFeedbackSubmitOutcome.Refused(
                            VoiceFeedbackSubmitFailure.Failed(code = "voice_feedback_network_error"),
                        )
                    }
                submitting = false
                when (outcome) {
                    VoiceFeedbackSubmitOutcome.Submitted -> {
                        saved = true
                        failure = null
                    }
                    is VoiceFeedbackSubmitOutcome.Refused -> {
                        saved = false
                        failure = outcome.failure
                    }
                }
            }
        }

        fun buildBadRequest(): AgentVoiceCallFeedbackRequest {
            val trimmedNote = note.trim().takeIf { it.isNotEmpty() }?.take(VOICE_FEEDBACK_NOTE_MAX_CHARS)
            return AgentVoiceCallFeedbackRequest(
                rating = VoiceFeedbackRating.Bad,
                tags = selectedTags,
                note = trimmedNote,
            )
        }

        Column(
            modifier = modifier.fillMaxWidth().testTag(AgentVoiceCallFeedbackTestTag),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(RaftStroke.Hairline)
                        .background(colors.lineHairline),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = stringResource(Res.string.agent_voice_call_feedback_prompt),
                    style = typography.footnote,
                    color = colors.foregroundMuted,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftIconToggleButton(
                        checked = rating == VoiceFeedbackRating.Good,
                        onCheckedChange = {
                            if (!submitting) {
                                rating = VoiceFeedbackRating.Good
                                dispatchSubmit(AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good))
                            }
                        },
                        tooltip = goodLabel,
                        size = RaftIconButtonSize.Sm,
                        enabled = !submitting,
                        modifier = Modifier.testTag(AgentVoiceCallFeedbackGoodTestTag),
                    ) {
                        RaftContentLucideIcon(
                            name = RaftLucideIconName.ThumbsUp,
                            size = 14.dp,
                        )
                    }
                    RaftIconToggleButton(
                        checked = rating == VoiceFeedbackRating.Bad,
                        onCheckedChange = {
                            if (!submitting) {
                                val shouldSubmitImmediately = rating != VoiceFeedbackRating.Bad || failure != null
                                rating = VoiceFeedbackRating.Bad
                                if (shouldSubmitImmediately) {
                                    dispatchSubmit(buildBadRequest())
                                }
                            }
                        },
                        tooltip = badLabel,
                        size = RaftIconButtonSize.Sm,
                        enabled = !submitting,
                        modifier = Modifier.testTag(AgentVoiceCallFeedbackBadTestTag),
                    ) {
                        RaftContentLucideIcon(
                            name = RaftLucideIconName.ThumbsDown,
                            size = 14.dp,
                        )
                    }
                }
            }

            if (rating == VoiceFeedbackRating.Bad) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(AgentVoiceCallFeedbackDetailsTestTag),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AgentVoiceCallFeedbackTagOptions.forEach { tag ->
                            val selected = tag in selectedTags
                            VoiceFeedbackTagChip(
                                label = callFeedbackTagLabel(tag),
                                selected = selected,
                                enabled = !submitting,
                                onClick = {
                                    selectedTags =
                                        if (selected) {
                                            selectedTags - tag
                                        } else {
                                            selectedTags + tag
                                        }
                                    saved = false
                                    failure = null
                                },
                                modifier = Modifier.testTag(agentVoiceCallFeedbackTagTestTag(tag)),
                            )
                        }
                    }

                    RaftTextField(
                        value = note,
                        onValueChange = { next ->
                            note = next.take(VOICE_FEEDBACK_NOTE_MAX_CHARS)
                            saved = false
                            failure = null
                        },
                        placeholder = stringResource(Res.string.agent_voice_call_feedback_note_placeholder),
                        enabled = !submitting,
                        size = RaftTextFieldSize.Md,
                        modifier = Modifier.fillMaxWidth().testTag(AgentVoiceCallFeedbackNoteTestTag),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftButton(
                            text = stringResource(Res.string.agent_voice_call_feedback_submit),
                            onClick = { dispatchSubmit(buildBadRequest()) },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            enabled = !submitting,
                            loading = submitting,
                            loadingLabel = stringResource(Res.string.agent_voice_call_feedback_submitting),
                            modifier = Modifier.testTag(AgentVoiceCallFeedbackSubmitTestTag),
                        )
                    }
                }
            }

            val activeFailure = failure
            when {
                saved -> {
                    RaftText(
                        text = stringResource(Res.string.agent_voice_call_feedback_saved),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                        modifier = Modifier.testTag(AgentVoiceCallFeedbackSavedTestTag),
                    )
                }

                activeFailure != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftText(
                            text =
                                voiceFeedbackFailureMessage(
                                    failure = activeFailure,
                                    fallback = stringResource(Res.string.agent_voice_call_feedback_failed),
                                ),
                            style = typography.footnote,
                            color = colors.dangerStrong,
                            modifier = Modifier.testTag(AgentVoiceCallFeedbackErrorTestTag),
                        )
                        val validTraceId = raftValidTraceIdOrNull(activeFailure.traceId)
                        if (validTraceId != null) {
                            RaftTraceIdNote(
                                traceId = validTraceId,
                                uiErrorRoute = "agents/voice/calls/feedback",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun callFeedbackTagLabel(tag: VoiceFeedbackTag): String =
    when (tag) {
        VoiceFeedbackTag.WrongWord -> stringResource(Res.string.agent_voice_call_feedback_tag_wrong_word)
        VoiceFeedbackTag.TooSlow -> stringResource(Res.string.agent_voice_call_feedback_tag_too_slow)
        VoiceFeedbackTag.WrongAnswer -> stringResource(Res.string.agent_voice_call_feedback_tag_wrong_answer)
        VoiceFeedbackTag.CutOff -> stringResource(Res.string.agent_voice_call_feedback_tag_cut_off)
        VoiceFeedbackTag.Interrupted -> stringResource(Res.string.agent_voice_call_feedback_tag_interrupted)
        VoiceFeedbackTag.BadRefine,
        VoiceFeedbackTag.Other -> stringResource(Res.string.agent_voice_call_feedback_tag_other)
    }
