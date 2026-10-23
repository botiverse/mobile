package build.raft.app

import build.raft.app.data.SharedMessageTranslationCandidate
import build.raft.app.data.SharedMessageTranslationEntry
import build.raft.app.data.SharedMessageTranslationSettings
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStatus
import build.raft.app.data.TranslationModePreference
import build.raft.app.data.isSilentTranslationEntry
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Per-message translation render model, aligned with the React `MessageItem`
 * translation behavior:
 *  - translated + showing translation → substitute the body
 *  - pending + showing translation → placeholder block instead of the body
 *  - indicator row under the content: Translating… / Show original /
 *    Show translation / Translation unavailable · Retry
 *  - silent skip reasons render nothing at all
 */
internal enum class ThreadMessageTranslationIndicatorAction {
    ToggleOriginal,
    Retry,
    None,
}

internal data class ThreadMessageTranslationIndicatorUi(
    val actionLabel: String,
    val message: String,
    val pending: Boolean = false,
    val failed: Boolean = false,
    val action: ThreadMessageTranslationIndicatorAction = ThreadMessageTranslationIndicatorAction.None,
)

internal data class ThreadMessageTranslationUi(
    val messageId: String,
    val displayBody: String? = null,
    val pendingPlaceholder: Boolean = false,
    val showOriginal: Boolean = false,
    val indicator: ThreadMessageTranslationIndicatorUi? = null,
)

internal fun RaftThreadMessageItem.toTranslationCandidate(): SharedMessageTranslationCandidate =
    SharedMessageTranslationCandidate(
        messageId = id,
        body = body,
        isOwn = isOwn,
        isSystem = isSystemMessage(),
    )

internal fun RaftThreadMessageItem.isTranslationEligible(settings: SharedMessageTranslationSettings): Boolean =
    settings.available &&
        settings.translationMode != TranslationModePreference.Original &&
        settings.effectiveLanguage != null &&
        !isSystemMessage() &&
        !isOwn &&
        !hasLocalSendState() &&
        body.isNotBlank()

/** The long-press menu's Translate entry (web MessageItem `showManualTranslationAction`). */
internal enum class ThreadMessageMenuTranslateState {
    Hidden,
    Available,
    /** Web renders the entry disabled with "Translating…" while the request is in flight. */
    Pending
}

/**
 * Web parity: the entry needs an eligible, non-empty, non-action-card message and disappears once
 * the message already has a usable translation or the translation failed. A stale entry (the
 * message was edited after it was produced) is ignored, as on web.
 */
internal fun threadMessageMenuTranslateState(
    message: RaftThreadMessageItem,
    translationState: SharedMessageTranslationState,
    translationAvailable: Boolean
): ThreadMessageMenuTranslateState {
    if (!translationAvailable) return ThreadMessageMenuTranslateState.Hidden
    if (!message.isTranslationEligible(translationState.settings)) return ThreadMessageMenuTranslateState.Hidden
    if (message.body.isBlank() || message.isThreadActionCardMessage()) return ThreadMessageMenuTranslateState.Hidden
    val entry =
        translationState.entries[message.id]
            ?.takeIf { it.originalContent.isEmpty() || it.originalContent == message.body }
    return when (entry?.status) {
        SharedMessageTranslationStatus.Pending -> ThreadMessageMenuTranslateState.Pending
        SharedMessageTranslationStatus.Translated ->
            if (entry.translatedContent.isNullOrBlank()) {
                ThreadMessageMenuTranslateState.Available
            } else {
                ThreadMessageMenuTranslateState.Hidden
            }
        SharedMessageTranslationStatus.Failed -> ThreadMessageMenuTranslateState.Hidden
        else -> ThreadMessageMenuTranslateState.Available
    }
}

internal fun resolveThreadMessageTranslationUi(
    message: RaftThreadMessageItem,
    translationState: SharedMessageTranslationState,
): ThreadMessageTranslationUi? {
    if (!message.isTranslationEligible(translationState.settings)) return null
    val entry = translationState.entries[message.id] ?: return null
    if (entry.originalContent.isNotEmpty() && entry.originalContent != message.body) {
        // Message content changed since this entry was produced; wait for the
        // next batch to refresh it instead of rendering a stale translation.
        return null
    }
    if (entry.isSilentTranslationEntry()) return null
    // With the local translation mode as the single signal there is no
    // account-level "prefer original" display anymore: entries only exist in
    // Automatic/Manual modes, where the translation is shown unless the user
    // toggled this message back to the original.
    val showOriginal = entry.showOriginal ?: false
    val translatedBody =
        entry.translatedContent?.takeIf {
            entry.status == SharedMessageTranslationStatus.Translated && !showOriginal
        }
    val pendingPlaceholder =
        entry.status == SharedMessageTranslationStatus.Pending && !showOriginal
    return ThreadMessageTranslationUi(
        messageId = message.id,
        displayBody = translatedBody,
        pendingPlaceholder = pendingPlaceholder,
        showOriginal = showOriginal,
        indicator = entry.toTranslationIndicatorUi(showOriginal),
    )
}

private fun SharedMessageTranslationEntry.toTranslationIndicatorUi(
    showOriginal: Boolean
): ThreadMessageTranslationIndicatorUi? =
    when (status) {
        SharedMessageTranslationStatus.Translated ->
            ThreadMessageTranslationIndicatorUi(
                actionLabel = if (showOriginal) "Show translation" else "Show original",
                message = "",
                action = ThreadMessageTranslationIndicatorAction.ToggleOriginal,
            )
        SharedMessageTranslationStatus.Pending ->
            ThreadMessageTranslationIndicatorUi(
                actionLabel = "",
                message = "Translating…",
                pending = true,
            )
        SharedMessageTranslationStatus.Failed, SharedMessageTranslationStatus.Skipped ->
            ThreadMessageTranslationIndicatorUi(
                actionLabel = "Retry",
                message = "Translation unavailable",
                failed = true,
                action = ThreadMessageTranslationIndicatorAction.Retry,
            )
        SharedMessageTranslationStatus.NotFound -> null
    }

/**
 * The translation status line under a message body, on raft-ui `MessageItemTranslationStatus`
 * / `MessageItemTranslationFailedStatus` (web MessageItem `TranslationIndicator`): one line of
 * Space Mono meta in `foreground-hint` (`warning-strong` when it failed) with a 12dp icon 6dp
 * before the words, a "·" between the words and the action, and the action
 * (`MessageItemTranslationAction` / `RetryAction`) in caption 12/16 semibold `foreground`.
 * While translating, the icon pulses as the web's `animate-pulse`; [stillPulse] holds it at
 * rest (screenshots, where an endless animation never lets the clock idle).
 */
@Composable
internal fun ThreadMessageTranslationIndicator(
    indicator: ThreadMessageTranslationIndicatorUi,
    onToggleOriginal: () -> Unit,
    onRetry: () -> Unit,
    stillPulse: Boolean = false,
) {
    val localizedMessage =
        when {
            indicator.pending -> stringResource(MR.strings.translation_state_in_progress)
            indicator.failed -> stringResource(MR.strings.translation_state_unavailable)
            else -> indicator.message
        }
    val localizedActionLabel =
        when (indicator.action) {
            ThreadMessageTranslationIndicatorAction.Retry -> stringResource(MR.strings.common_retry)
            ThreadMessageTranslationIndicatorAction.ToggleOriginal ->
                if (indicator.actionLabel == "Show translation") {
                    stringResource(MR.strings.translation_state_show_translation)
                } else {
                    stringResource(MR.strings.translation_state_show_original)
                }
            ThreadMessageTranslationIndicatorAction.None -> indicator.actionLabel
        }
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        val tint = if (indicator.failed) colors.warningStrong else colors.foregroundHint
        val iconAlpha =
            if (indicator.pending && !stillPulse) {
                rememberInfiniteTransition(label = "translation-pulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.5f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis = 1000, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)),
                            repeatMode = RepeatMode.Reverse
                        ),
                    label = "translation-pulse-alpha"
                ).value
            } else {
                1f
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RaftLucideIcon(
                name = if (indicator.failed) RaftLucideIconName.TriangleAlert else RaftLucideIconName.Languages,
                modifier = Modifier.size(12.dp).alpha(iconAlpha),
                color = raftArgbHex(tint)
            )
            if (localizedMessage.isNotBlank()) {
                RaftText(text = localizedMessage, style = typography.monoMeta, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (localizedActionLabel.isNotBlank()) {
                if (localizedMessage.isNotBlank()) {
                    RaftText(text = "·", style = typography.monoMeta, color = tint, modifier = Modifier.clearAndSetSemantics {})
                }
                val onAction =
                    when (indicator.action) {
                        ThreadMessageTranslationIndicatorAction.Retry -> onRetry
                        ThreadMessageTranslationIndicatorAction.ToggleOriginal -> onToggleOriginal
                        ThreadMessageTranslationIndicatorAction.None -> null
                    }
                RaftText(
                    text = localizedActionLabel,
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    modifier =
                        if (onAction != null) {
                            Modifier.clickable(role = Role.Button, onClick = onAction)
                        } else {
                            Modifier
                        }
                )
            }
        }
    }
}

@Composable
internal fun ThreadMessageTranslationPendingPlaceholder() {
    Box(
        modifier =
            Modifier
                .width(176.dp)
                .height(16.dp)
                .background(RaftColor.AccentStone)
    )
}
