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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.ui.unit.sp

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

internal fun KuiklyThreadMessageItem.toTranslationCandidate(): SharedMessageTranslationCandidate =
    SharedMessageTranslationCandidate(
        messageId = id,
        body = body,
        isOwn = isOwn,
        isSystem = isSystemMessage(),
    )

internal fun KuiklyThreadMessageItem.isTranslationEligible(settings: SharedMessageTranslationSettings): Boolean =
    settings.available &&
        settings.translationMode != TranslationModePreference.Original &&
        settings.effectiveLanguage != null &&
        !isSystemMessage() &&
        !isOwn &&
        !hasLocalSendState() &&
        body.isNotBlank()

internal fun resolveThreadMessageTranslationUi(
    message: KuiklyThreadMessageItem,
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

@Composable
internal fun ThreadMessageTranslationIndicator(
    indicator: ThreadMessageTranslationIndicatorUi,
    onToggleOriginal: () -> Unit,
    onRetry: () -> Unit,
) {
    val tint = if (indicator.failed) SlockColor.AccentOrange else SlockColor.TextMutedGray
    val tintArgb = if (indicator.failed) SlockColor.AccentOrangeArgb else SlockColor.TextMutedGrayArgb
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SlockLucideIcon(
            name = if (indicator.failed) SlockLucideIconName.TriangleAlert else SlockLucideIconName.Languages,
            modifier = Modifier.size(12.dp),
            color = tintArgb,
            spinning = indicator.pending
        )
        if (localizedMessage.isNotBlank()) {
            Text(
                text = localizedMessage,
                style = SlockType.Caption.copy(fontSize = 11.sp, color = tint),
                color = tint
            )
        }
        if (localizedActionLabel.isNotBlank()) {
            val onAction =
                when (indicator.action) {
                    ThreadMessageTranslationIndicatorAction.Retry -> onRetry
                    ThreadMessageTranslationIndicatorAction.ToggleOriginal -> onToggleOriginal
                    ThreadMessageTranslationIndicatorAction.None -> null
                }
            Text(
                text = localizedActionLabel,
                style = SlockType.Caption.copy(fontSize = 11.sp, color = tint),
                color = tint,
                modifier =
                    if (onAction != null) {
                        Modifier.clickable { onAction() }
                    } else {
                        Modifier
                    }
            )
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
                .background(SlockColor.AccentStone)
    )
}
