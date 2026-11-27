package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.VOICE_DICTATION_FEEDBACK_PATH
import build.raft.app.data.VOICE_FEEDBACK_NOTE_MAX_CHARS
import build.raft.app.data.VoiceDictationFeedbackRequest
import build.raft.app.data.VoiceDictationFeedbackTarget
import build.raft.app.data.VoiceFeedbackRating
import build.raft.app.data.VoiceFeedbackSample
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
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Internal-only post-dictation opt-in bad-case reporting strip (`voice_feedback_sample_v0`,
// `#wg-call:#7`), mirroring web `DictationBadCaseStrip` in `ComposerVoiceDictationNotice.tsx`
// (`botiverse/slock#17246`).

internal const val ThreadVoiceDictationFeedbackStripTestTag: String = "composer-voice-dictation-feedback"
internal const val ThreadVoiceDictationFeedbackOpenTestTag: String = "composer-voice-dictation-feedback-open"
internal const val ThreadVoiceDictationFeedbackDismissTestTag: String = "composer-voice-dictation-feedback-dismiss"
internal const val ThreadVoiceDictationFeedbackNoteTestTag: String = "composer-voice-dictation-feedback-note"
internal const val ThreadVoiceDictationFeedbackConsentTestTag: String = "composer-voice-dictation-feedback-consent"
internal const val ThreadVoiceDictationFeedbackSubmitTestTag: String = "composer-voice-dictation-feedback-submit"
internal const val ThreadVoiceDictationFeedbackCancelTestTag: String = "composer-voice-dictation-feedback-cancel"
internal const val ThreadVoiceDictationFeedbackSavedTestTag: String = "composer-voice-dictation-feedback-saved"
internal const val ThreadVoiceDictationFeedbackErrorTestTag: String = "composer-voice-dictation-feedback-error"

internal fun threadVoiceDictationFeedbackTagTestTag(tag: VoiceFeedbackTag): String =
    "composer-voice-dictation-feedback-tag-${tag.wire}"

/** Closed tag options shown in the post-dictation bad-case reporting strip (web parity order). */
internal val VoiceDictationFeedbackTagOptions: List<VoiceFeedbackTag> =
    listOf(
        VoiceFeedbackTag.WrongWord,
        VoiceFeedbackTag.BadRefine,
        VoiceFeedbackTag.TooSlow,
        VoiceFeedbackTag.CutOff,
        VoiceFeedbackTag.Other,
    )

/**
 * Strict fail-closed visibility gate for the internal-only post-dictation bad-case strip:
 * visible ONLY when a recent dictation target exists, `voice_feedback_sample_v0` is ON for the
 * server, `RAFT_PRIVACY_MODE` is explicitly [RaftPrivacyMode.Off], and no higher-priority dictation
 * error, low-quota banner, or active dictation session is on screen.
 */
internal fun canShowVoiceDictationFeedbackStrip(
    recentFeedbackTarget: VoiceDictationFeedbackTarget?,
    sampleFeedbackEnabled: Boolean,
    privacyMode: RaftPrivacyMode,
    hasError: Boolean,
    isDictationActive: Boolean,
    showLowQuota: Boolean,
): Boolean =
    recentFeedbackTarget != null &&
        sampleFeedbackEnabled &&
        voiceFeedbackAllowed(privacyMode) &&
        !hasError &&
        !isDictationActive &&
        !showLowQuota

/**
 * Compact dismissible post-dictation issue reporting strip mounted above the composer.
 * Per-session form state resets whenever [VoiceDictationFeedbackTarget.sessionId] changes, and the
 * optional sample consent checkbox always defaults to unchecked (`sample == null` unless toggled).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadVoiceDictationFeedbackStrip(
    target: VoiceDictationFeedbackTarget,
    onDismiss: () -> Unit,
    onSubmit: suspend (serverId: String, request: VoiceDictationFeedbackRequest) -> VoiceFeedbackSubmitOutcome,
    modifier: Modifier = Modifier,
) {
    val currentTarget by rememberUpdatedState(target)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val scope = rememberCoroutineScope()

    key(target.sessionId) {
        var expanded by remember { mutableStateOf(false) }
        var selectedTags by remember { mutableStateOf<List<VoiceFeedbackTag>>(emptyList()) }
        var note by remember { mutableStateOf("") }
        var includeSample by remember { mutableStateOf(false) }
        var submitting by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<VoiceFeedbackSubmitFailure?>(null) }

        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        val shape = RoundedCornerShape(RaftRadius.Control)
        val dismissLabel = stringResource(Res.string.thread_voice_dictation_feedback_dismiss)

        fun submitFeedback() {
            if (submitting) return
            val snapshot = currentTarget
            val trimmedNote = note.trim().takeIf { it.isNotEmpty() }?.take(VOICE_FEEDBACK_NOTE_MAX_CHARS)
            val sample =
                if (includeSample) {
                    VoiceFeedbackSample(
                        consent = true,
                        raw = snapshot.raw,
                        refined = snapshot.refined,
                        sent = snapshot.sent,
                    )
                } else {
                    null
                }
            val request =
                VoiceDictationFeedbackRequest(
                    sessionId = snapshot.sessionId,
                    rating = VoiceFeedbackRating.Bad,
                    tags = selectedTags,
                    note = trimmedNote,
                    sample = sample,
                )
            submitting = true
            failure = null
            scope.launch {
                val outcome =
                    try {
                        currentOnSubmit(snapshot.serverId, request)
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

        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .testTag(ThreadVoiceDictationFeedbackStripTestTag)
                    .clip(shape)
                    .background(colors.layerCard, shape)
                    .border(RaftStroke.Hairline, colors.lineHairline, shape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                saved -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_thanks),
                            style = typography.footnote,
                            color = colors.foregroundMuted,
                            modifier = Modifier.weight(1f).testTag(ThreadVoiceDictationFeedbackSavedTestTag),
                        )
                        RaftIconButton(
                            onClick = onDismiss,
                            tooltip = null,
                            contentDescription = dismissLabel,
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Xs,
                            modifier = Modifier.testTag(ThreadVoiceDictationFeedbackDismissTestTag),
                        ) {
                            RaftSlotIcon(name = RaftLucideIconName.X)
                        }
                    }
                }

                !expanded -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_prompt),
                            style = typography.footnote,
                            color = colors.foregroundMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RaftButton(
                                text = stringResource(Res.string.thread_voice_dictation_feedback_report),
                                onClick = { expanded = true },
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                modifier = Modifier.testTag(ThreadVoiceDictationFeedbackOpenTestTag),
                            )
                            RaftIconButton(
                                onClick = onDismiss,
                                tooltip = null,
                                contentDescription = dismissLabel,
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Xs,
                                modifier = Modifier.testTag(ThreadVoiceDictationFeedbackDismissTestTag),
                            ) {
                                RaftSlotIcon(name = RaftLucideIconName.X)
                            }
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftText(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_title),
                            style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground,
                            modifier = Modifier.weight(1f),
                        )
                        RaftIconButton(
                            onClick = onDismiss,
                            tooltip = null,
                            contentDescription = dismissLabel,
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Xs,
                            modifier = Modifier.testTag(ThreadVoiceDictationFeedbackDismissTestTag),
                        ) {
                            RaftSlotIcon(name = RaftLucideIconName.X)
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        VoiceDictationFeedbackTagOptions.forEach { tag ->
                            val selected = tag in selectedTags
                            VoiceFeedbackTagChip(
                                label = dictationFeedbackTagLabel(tag),
                                selected = selected,
                                enabled = !submitting,
                                onClick = {
                                    selectedTags =
                                        if (selected) {
                                            selectedTags - tag
                                        } else {
                                            selectedTags + tag
                                        }
                                },
                                modifier = Modifier.testTag(threadVoiceDictationFeedbackTagTestTag(tag)),
                            )
                        }
                    }

                    RaftTextField(
                        value = note,
                        onValueChange = { next -> note = next.take(VOICE_FEEDBACK_NOTE_MAX_CHARS) },
                        placeholder = stringResource(Res.string.thread_voice_dictation_feedback_note_placeholder),
                        enabled = !submitting,
                        size = RaftTextFieldSize.Md,
                        modifier = Modifier.fillMaxWidth().testTag(ThreadVoiceDictationFeedbackNoteTestTag),
                    )

                    val consentShape = RoundedCornerShape(RaftRadius.Chip)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(consentShape)
                                .raftClickable(
                                    onClick = { includeSample = !includeSample },
                                    enabled = !submitting,
                                    role = Role.Checkbox,
                                    shape = consentShape,
                                )
                                .semantics { toggleableState = ToggleableState(includeSample) }
                                .padding(vertical = 4.dp)
                                .testTag(ThreadVoiceDictationFeedbackConsentTestTag),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftCheckboxIndicator(checked = includeSample, enabled = !submitting)
                        RaftText(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_consent),
                            style = typography.footnote,
                            color = colors.foregroundMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    val activeFailure = failure
                    if (activeFailure != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RaftText(
                                text =
                                    voiceFeedbackFailureMessage(
                                        failure = activeFailure,
                                        fallback = stringResource(Res.string.thread_voice_dictation_feedback_failed),
                                    ),
                                style = typography.footnote,
                                color = colors.dangerStrong,
                                modifier = Modifier.testTag(ThreadVoiceDictationFeedbackErrorTestTag),
                            )
                            val validTraceId = raftValidTraceIdOrNull(activeFailure.traceId)
                            if (validTraceId != null) {
                                RaftTraceIdNote(
                                    traceId = validTraceId,
                                    uiErrorRoute = VOICE_DICTATION_FEEDBACK_PATH,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftButton(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_cancel),
                            onClick = { expanded = false },
                            variant = RaftButtonVariant.Ghost,
                            size = RaftButtonSize.Md,
                            enabled = !submitting,
                            modifier = Modifier.testTag(ThreadVoiceDictationFeedbackCancelTestTag),
                        )
                        RaftButton(
                            text = stringResource(Res.string.thread_voice_dictation_feedback_submit),
                            onClick = { submitFeedback() },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            enabled = !submitting,
                            loading = submitting,
                            loadingLabel = stringResource(Res.string.thread_voice_dictation_feedback_submitting),
                            modifier = Modifier.testTag(ThreadVoiceDictationFeedbackSubmitTestTag),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun dictationFeedbackTagLabel(tag: VoiceFeedbackTag): String =
    when (tag) {
        VoiceFeedbackTag.WrongWord -> stringResource(Res.string.thread_voice_dictation_feedback_tag_wrong_word)
        VoiceFeedbackTag.BadRefine -> stringResource(Res.string.thread_voice_dictation_feedback_tag_bad_refine)
        VoiceFeedbackTag.TooSlow -> stringResource(Res.string.thread_voice_dictation_feedback_tag_too_slow)
        VoiceFeedbackTag.CutOff -> stringResource(Res.string.thread_voice_dictation_feedback_tag_cut_off)
        VoiceFeedbackTag.WrongAnswer,
        VoiceFeedbackTag.Interrupted,
        VoiceFeedbackTag.Other -> stringResource(Res.string.thread_voice_dictation_feedback_tag_other)
    }
