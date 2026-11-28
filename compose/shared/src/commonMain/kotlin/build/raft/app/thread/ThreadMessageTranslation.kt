package build.raft.app.thread

import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.app.resources.*
import build.raft.app.data.SharedMessageTranslationCandidate
import build.raft.app.data.SharedMessageTranslationEntry
import build.raft.app.data.SharedMessageTranslationSettings
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStatus
import build.raft.app.data.TranslationDisplayPreference
import build.raft.app.data.TranslationModePreference
import build.raft.app.data.isSilentTranslationEntry
import build.raft.app.raftArgbHex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
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
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.available
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

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
    val bilingualOriginalBody: String? = null,
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
    // Web MessageItem parity: per-message toggle overrides account default view; toggling from
    // "original" default flips to "translated", while toggling from "bilingual" flips between
    // "original" and "bilingual".
    val preferredDisplay = translationState.settings.preferredTranslationDisplay
    val activeDisplay =
        when (entry.showOriginal) {
            true -> TranslationDisplayPreference.Original
            false ->
                if (preferredDisplay == TranslationDisplayPreference.Original) {
                    TranslationDisplayPreference.Translated
                } else {
                    preferredDisplay
                }
            null -> preferredDisplay
        }
    val showOriginal = activeDisplay == TranslationDisplayPreference.Original
    val translatedBody =
        entry.translatedContent?.takeIf {
            entry.status == SharedMessageTranslationStatus.Translated && !showOriginal && it.isNotBlank()
        }
    val bilingualOriginalBody =
        message.body.takeIf {
            translatedBody != null &&
                activeDisplay == TranslationDisplayPreference.Bilingual &&
                it.isNotBlank()
        }
    val pendingPlaceholder =
        entry.status == SharedMessageTranslationStatus.Pending && !showOriginal
    return ThreadMessageTranslationUi(
        messageId = message.id,
        displayBody = translatedBody,
        bilingualOriginalBody = bilingualOriginalBody,
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
            indicator.pending -> stringResource(Res.string.translation_state_in_progress)
            indicator.failed -> stringResource(Res.string.translation_state_unavailable)
            else -> indicator.message
        }
    val localizedActionLabel =
        when (indicator.action) {
            ThreadMessageTranslationIndicatorAction.Retry -> stringResource(Res.string.common_retry)
            ThreadMessageTranslationIndicatorAction.ToggleOriginal ->
                if (indicator.actionLabel == "Show translation") {
                    stringResource(Res.string.translation_state_show_translation)
                } else {
                    stringResource(Res.string.translation_state_show_original)
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
                if (onAction != null) {
                    // A link inside the indicator's sentence: TextLink strong (ink, semibold).
                    RaftTextLink(
                        text = localizedActionLabel,
                        onClick = onAction,
                        variant = RaftTextLinkVariant.Strong,
                        style = typography.caption,
                        maxLines = 1,
                    )
                } else {
                    RaftText(
                        text = localizedActionLabel,
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThreadMessageTranslationPendingPlaceholder() {
    RaftSkeleton(modifier = Modifier.width(ThreadTranslationPlaceholderLastLineWidth), variant = RaftSkeletonVariant.Line)
}

/** The web's single placeholder line; here also the width of the last line of a taller block. */
private val ThreadTranslationPlaceholderLastLineWidth = 176.dp
private val ThreadTranslationPlaceholderLineHeight = 12.dp
private val ThreadTranslationPlaceholderLineGap = 8.dp

/** At most this many bars are drawn; a taller original keeps its height with the rest left empty. */
internal const val ThreadTranslationPlaceholderMaxLines = 16

/**
 * How many 12dp placeholder bars, [gapPx] apart, fit in the original body's [reservedHeightPx]:
 * one per text line, roughly, and never fewer than one.
 */
internal fun threadTranslationPlaceholderLineCount(reservedHeightPx: Int, lineHeightPx: Int, gapPx: Int): Int {
    val pitch = lineHeightPx + gapPx
    if (pitch <= 0 || reservedHeightPx < lineHeightPx) return 1
    return ((reservedHeightPx + gapPx) / pitch).coerceIn(1, ThreadTranslationPlaceholderMaxLines)
}

/**
 * A message body while its automatic translation is read (#proj-mobile:#201). The web draws only
 * the 176dp placeholder line there, so a long message shrinks to one line and grows again when the
 * translation comes, moving everything above it on the reversed list. Here the original [body] is
 * still composed and measured, so the row keeps the original's height, but it is not placed: it
 * draws nothing, takes no taps and is not read out, and the reader sees placeholder bars, not the
 * untranslated text (as on the web). The bars fill that height, one per line of the original
 * (artin: a tall row with one bar at the top looked empty), full width with a 176dp last line.
 * When [pending] is false the body is simply the body.
 */
@Composable
internal fun ThreadMessageTranslationPendingFrame(
    pending: Boolean,
    alignEnd: Boolean,
    body: @Composable () -> Unit,
) {
    if (!pending) {
        body()
        return
    }
    val measuredOnly: @Composable () -> Unit = { Box(Modifier.clearAndSetSemantics { }) { body() } }
    val bars: @Composable () -> Unit = {
        repeat(ThreadTranslationPlaceholderMaxLines) {
            RaftSkeleton(modifier = Modifier.fillMaxWidth(), variant = RaftSkeletonVariant.Line)
        }
    }
    Layout(contents = listOf(measuredOnly, bars)) { measurables, constraints ->
        val reserved = measurables[0].map { it.measure(constraints) }
        val reservedWidth = reserved.maxOfOrNull { it.width } ?: 0
        val reservedHeight = reserved.maxOfOrNull { it.height } ?: 0
        val lineHeight = ThreadTranslationPlaceholderLineHeight.roundToPx()
        val gap = ThreadTranslationPlaceholderLineGap.roundToPx()
        val lastLineWidth = ThreadTranslationPlaceholderLastLineWidth.roundToPx()
        val lineCount = threadTranslationPlaceholderLineCount(reservedHeight, lineHeight, gap)
        val fullWidth = maxOf(reservedWidth, lastLineWidth).coerceAtMost(constraints.maxWidth)
        // Only the bars that are placed are measured; the others draw nothing.
        val placed =
            measurables[1].take(lineCount).mapIndexed { index, bar ->
                val barWidth = if (index == lineCount - 1) minOf(lastLineWidth, fullWidth) else fullWidth
                bar.measure(Constraints.fixedWidth(barWidth).copy(maxHeight = constraints.maxHeight))
            }
        val barsHeight = placed.sumOf { it.height } + gap * (placed.size - 1).coerceAtLeast(0)
        val width =
            maxOf(reservedWidth, placed.maxOfOrNull { it.width } ?: 0)
                .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(reservedHeight, barsHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // The original is measured only: an unplaced child draws nothing and is not hit.
            var y = 0
            placed.forEach { bar ->
                bar.place(if (alignEnd) width - bar.width else 0, y)
                y += bar.height + gap
            }
        }
    }
}

/**
 * Bilingual view's original-text block under the translated message body (web `MessageItem`
 * `MessageItemTranslationOriginal` + `MessageItemTranslationOriginalLabel`).
 */
@Composable
internal fun ThreadMessageBilingualOriginalBlock(
    messageId: String,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        val borderColor = colors.lineSeam
        Column(
            modifier =
                modifier
                    .padding(top = 2.dp)
                    .testTag("message-translation-bilingual-original-$messageId")
                    .drawBehind {
                        val barWidth = 2.dp.toPx()
                        val x = if (alignEnd) size.width - barWidth else 0f
                        drawRect(
                            color = borderColor,
                            topLeft = Offset(x, 0f),
                            size = Size(barWidth, size.height),
                        )
                    }
                    .padding(
                        start = if (alignEnd) 0.dp else 10.dp,
                        end = if (alignEnd) 10.dp else 0.dp,
                        top = 2.dp,
                        bottom = 2.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            RaftText(
                text = stringResource(Res.string.message_bilingual_original),
                style = typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
            )
            content()
        }
    }
}
