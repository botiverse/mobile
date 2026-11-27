package build.raft.app.thread

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.RaftEditorKeyboardIntent
import build.raft.app.RaftInputFocusOwnerRegistry
import build.raft.app.ThreadComposerEditorOwner
import build.raft.app.ThreadComposerQuoteController
import build.raft.app.ThreadComposerSuggestionSelectionDispatcher
import build.raft.app.data.VoiceDictationTextRange
import build.raft.app.data.VoiceDictationV2State
import build.raft.app.data.extractVoiceDictationDraftBeforeCaret
import build.raft.app.data.trimVoiceDictationProvisionalRange
import build.raft.app.get
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Composing dotted-underline visual state and composer text-session bridge for uncommitted
// streaming voice dictation text (`provisionalRange`). Mirrors web
// `ComposerVoiceDictationComposingOverlay.tsx` + `voiceDictationComposingMirror.ts`:
// `underline decoration-dotted decoration-foreground-hint decoration-1 underline-offset-2`.

// not a token: web ComposerVoiceDictationComposingOverlay `decoration-1` (1 px stroke)
private val VoiceDictationComposingUnderlineStroke = 1.dp

// not a token: web ComposerVoiceDictationComposingOverlay `underline-offset-2` (2 px below baseline)
private val VoiceDictationComposingUnderlineOffset = 2.dp

// not a token: web `decoration-dotted` dot dash/gap tuned for StrokeCap.Round (~1.5 dp dot, ~1.5 dp gap)
private val VoiceDictationComposingDotLength = 0.5.dp
private val VoiceDictationComposingDotGap = 2.5.dp

/**
 * Semantics property published on the composer inner text field strictly while uncommitted
 * streaming voice dictation text is rendered in composing state (`trimmedRange != null`).
 */
internal val VoiceDictationProvisionalRangeSemanticsKey: SemanticsPropertyKey<TextRange> =
    SemanticsPropertyKey("VoiceDictationProvisionalRange")

internal var SemanticsPropertyReceiver.voiceDictationProvisionalRange: TextRange
    get() = throw UnsupportedOperationException("VoiceDictationProvisionalRange is write-only on SemanticsPropertyReceiver")
    set(value) {
        this[VoiceDictationProvisionalRangeSemanticsKey] = value
    }

/**
 * One dotted composing underline segment in unscrolled text-layout pixel coordinates for a single
 * wrapped line intersecting the active voice dictation `provisionalRange`.
 */
internal data class VoiceDictationUnderlineSegment(
    val lineIndex: Int,
    val startX: Float,
    val endX: Float,
    val y: Float,
)

/**
 * Minimal layout-metrics adapter around [TextLayoutResult] so line-segment geometry and bounds
 * safety can be unit-tested directly in `commonTest`.
 */
internal interface VoiceDictationTextLayoutMetrics {
    val textLength: Int
    val lineCount: Int

    fun lineForOffset(offset: Int): Int

    fun lineStart(lineIndex: Int): Int

    fun lineVisibleEnd(lineIndex: Int): Int

    fun lineTop(lineIndex: Int): Float

    fun lineBaseline(lineIndex: Int): Float

    fun lineBottom(lineIndex: Int): Float

    fun charLeft(offset: Int): Float

    fun charRight(offset: Int): Float

    fun horizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float
}

private class ComposeTextLayoutMetrics(
    private val layout: TextLayoutResult,
) : VoiceDictationTextLayoutMetrics {
    override val textLength: Int
        get() = layout.layoutInput.text.length

    override val lineCount: Int
        get() = layout.lineCount

    override fun lineForOffset(offset: Int): Int = layout.getLineForOffset(offset)

    override fun lineStart(lineIndex: Int): Int = layout.getLineStart(lineIndex)

    override fun lineVisibleEnd(lineIndex: Int): Int = layout.getLineEnd(lineIndex, visibleEnd = true)

    override fun lineTop(lineIndex: Int): Float = layout.getLineTop(lineIndex)

    override fun lineBaseline(lineIndex: Int): Float = layout.getLineBaseline(lineIndex)

    override fun lineBottom(lineIndex: Int): Float = layout.getLineBottom(lineIndex)

    override fun charLeft(offset: Int): Float = layout.getBoundingBox(offset).left

    override fun charRight(offset: Int): Float = layout.getBoundingBox(offset).right

    override fun horizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float =
        layout.getHorizontalPosition(offset, usePrimaryDirection)
}

internal fun voiceDictationTextMatches(a: CharSequence, b: CharSequence): Boolean {
    if (a.length != b.length) return false
    for (i in 0 until a.length) {
        if (a[i] != b[i]) return false
    }
    return true
}

/**
 * Resolves the safe, whitespace-trimmed provisional range to render in composing state for the
 * active composer text field, or `null` when dictation is inactive, IME composition is active,
 * no provisional text exists, or the draft text has diverged from the controller's last update.
 */
internal fun resolveActiveVoiceDictationProvisionalRange(
    state: VoiceDictationV2State,
    activeText: CharSequence,
    imeComposing: Boolean = false,
): VoiceDictationTextRange? {
    if (imeComposing || !state.isActive) return null
    val rawRange = state.provisionalRange ?: return null
    val lastUpdate = state.lastUpdate
    if (lastUpdate != null && !voiceDictationTextMatches(lastUpdate.composerText, activeText)) {
        return null
    }
    return trimVoiceDictationProvisionalRange(activeText, rawRange)
}

/**
 * Computes per-line dotted underline segments in unscrolled layout pixel coordinates for
 * [provisionalRange] inside [text].
 *
 * - Guards all bounds against stale layouts or diverged text lengths (never throws).
 * - Trims leading/trailing whitespace across the whole span and per wrapped line so joining spaces
 *   and blank lines never receive an underline.
 * - Uses per-character bounding-box union per line so LTR, CJK, RTL, mixed BiDi, and emoji
 *   surrogate pairs all underline accurately, falling back to horizontal positions if glyph boxes
 *   are degenerate.
 */
internal fun computeVoiceDictationUnderlineSegments(
    text: CharSequence,
    provisionalRange: VoiceDictationTextRange?,
    metrics: VoiceDictationTextLayoutMetrics,
    underlineOffsetPx: Float,
    strokeWidthPx: Float,
): List<VoiceDictationUnderlineSegment> {
    if (text.isEmpty() || metrics.lineCount <= 0 || metrics.textLength != text.length) return emptyList()
    val trimmed = trimVoiceDictationProvisionalRange(text, provisionalRange) ?: return emptyList()
    val start = trimmed.start
    val endExclusive = trimmed.endExclusive
    if (start < 0 || endExclusive > metrics.textLength || start >= endExclusive) return emptyList()

    val firstLine = metrics.lineForOffset(start).coerceIn(0, metrics.lineCount - 1)
    val lastLine = metrics.lineForOffset(endExclusive - 1).coerceIn(firstLine, metrics.lineCount - 1)
    val halfStroke = (strokeWidthPx / 2f).coerceAtLeast(0f)
    val segments = ArrayList<VoiceDictationUnderlineSegment>(lastLine - firstLine + 1)

    for (line in firstLine..lastLine) {
        val lineStart = metrics.lineStart(line).coerceIn(0, metrics.textLength)
        val lineEnd = metrics.lineVisibleEnd(line).coerceIn(lineStart, metrics.textLength)
        var segStart = maxOf(start, lineStart)
        var segEnd = minOf(endExclusive, lineEnd)
        while (segStart < segEnd && text[segStart].isWhitespace()) segStart += 1
        while (segEnd > segStart && text[segEnd - 1].isWhitespace()) segEnd -= 1
        if (segStart >= segEnd) continue

        var minLeft = Float.POSITIVE_INFINITY
        var maxRight = Float.NEGATIVE_INFINITY
        for (offset in segStart until segEnd) {
            val left = metrics.charLeft(offset)
            val right = metrics.charRight(offset)
            if (left.isFinite() && right.isFinite() && right > left) {
                if (left < minLeft) minLeft = left
                if (right > maxRight) maxRight = right
            }
        }
        if (!minLeft.isFinite() || !maxRight.isFinite() || maxRight <= minLeft) {
            val p1 = metrics.horizontalPosition(segStart, usePrimaryDirection = true)
            val p2 = metrics.horizontalPosition(segEnd, usePrimaryDirection = true)
            if (p1.isFinite() && p2.isFinite()) {
                minLeft = minOf(p1, p2)
                maxRight = maxOf(p1, p2)
            }
        }
        if (!minLeft.isFinite() || !maxRight.isFinite() || maxRight <= minLeft) continue

        val lineTop = metrics.lineTop(line)
        val lineBottom = metrics.lineBottom(line)
        val baseline = metrics.lineBaseline(line)
        val minY = lineTop + halfStroke
        val maxY = (lineBottom - halfStroke).coerceAtLeast(minY)
        val y = (baseline + underlineOffsetPx).coerceIn(minY, maxY)

        segments +=
            VoiceDictationUnderlineSegment(
                lineIndex = line,
                startX = minLeft,
                endX = maxRight,
                y = y,
            )
    }
    return segments
}

/**
 * Observes [host] cleanly without bloating [ThreadComposerContentLeafBody] and returns a modifier
 * that paints the dotted composing underline beneath uncommitted streaming dictation words.
 * Returns [Modifier] unchanged whenever no uncommitted provisional range is active.
 */
@Composable
internal fun rememberVoiceDictationComposingUnderlineModifier(
    host: ThreadVoiceDictationHost?,
    activeText: CharSequence,
    imeComposing: Boolean,
    layoutResultProvider: () -> TextLayoutResult?,
    scrollState: ScrollState,
    singleLine: Boolean = false,
): Modifier {
    if (host == null || imeComposing) return Modifier
    val state by host.controller.state.collectAsState()
    val trimmed =
        resolveActiveVoiceDictationProvisionalRange(
            state = state,
            activeText = activeText,
            imeComposing = imeComposing,
        ) ?: return Modifier
    return Modifier.voiceDictationComposingUnderline(
        provisionalRange = trimmed,
        text = activeText,
        layoutResultProvider = layoutResultProvider,
        scrollState = scrollState,
        singleLine = singleLine,
        color = RaftTheme.colors.foregroundHint,
    )
}

/**
 * Draws a crisp dotted composing underline beneath every wrapped line segment of [provisionalRange]
 * inside [text], accounting for [scrollState] and clipping cleanly to the inner text field bounds.
 * Returns `this` unchanged when [provisionalRange] trims to null.
 */
internal fun Modifier.voiceDictationComposingUnderline(
    provisionalRange: VoiceDictationTextRange?,
    text: CharSequence,
    layoutResultProvider: () -> TextLayoutResult?,
    scrollState: ScrollState,
    singleLine: Boolean,
    color: Color,
): Modifier {
    val trimmed = trimVoiceDictationProvisionalRange(text, provisionalRange) ?: return this
    val targetText = text.toString()
    val semanticRange = TextRange(trimmed.start, trimmed.endExclusive)
    return this
        .semantics { voiceDictationProvisionalRange = semanticRange }
        .drawWithContent {
            drawContent()
            val layout = layoutResultProvider() ?: return@drawWithContent
            if (!voiceDictationTextMatches(layout.layoutInput.text, targetText)) return@drawWithContent
            val strokeWidthPx = VoiceDictationComposingUnderlineStroke.toPx()
            val underlineOffsetPx = VoiceDictationComposingUnderlineOffset.toPx()
            val segments =
                computeVoiceDictationUnderlineSegments(
                    text = targetText,
                    provisionalRange = trimmed,
                    metrics = ComposeTextLayoutMetrics(layout),
                    underlineOffsetPx = underlineOffsetPx,
                    strokeWidthPx = strokeWidthPx,
                )
            if (segments.isEmpty()) return@drawWithContent
            val dashEffect =
                PathEffect.dashPathEffect(
                    intervals =
                        floatArrayOf(
                            VoiceDictationComposingDotLength.toPx(),
                            VoiceDictationComposingDotGap.toPx(),
                        ),
                    phase = 0f,
                )
            val scrollOffsetX =
                if (singleLine) {
                    if (layoutDirection == LayoutDirection.Rtl) {
                        size.width - layout.size.width.toFloat() + scrollState.value.toFloat()
                    } else {
                        -scrollState.value.toFloat()
                    }
                } else {
                    0f
                }
            val scrollOffsetY = if (singleLine) 0f else -scrollState.value.toFloat()
            clipRect {
                for (segment in segments) {
                    val y = segment.y + scrollOffsetY
                    if (y < -strokeWidthPx || y > size.height + strokeWidthPx) continue
                    drawLine(
                        color = color,
                        start = Offset(segment.startX + scrollOffsetX, y),
                        end = Offset(segment.endX + scrollOffsetX, y),
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )
                }
            }
        }
}

/**
 * Focuses the active composer editor when voice dictation starts from an unfocused composer,
 * suppressing soft-keyboard pop on touch-only hosts so press-and-hold never jumps the mic button
 * upward mid-gesture while still showing the blinking insertion caret and composing underline.
 */
internal fun focusThreadComposerForVoiceDictation(
    focusCoordinator: RaftFocusCoordinator,
    targetKey: Any,
    region: RaftFocusRegion?,
    modalToken: Any?,
    focusOwnerRegistry: RaftInputFocusOwnerRegistry?,
    composerFocusScope: CoroutineScope,
    composerKeyboardIntent: RaftEditorKeyboardIntent,
    composerFocusRequester: FocusRequester,
    quoteController: ThreadComposerQuoteController,
    segmentedBlockId: String?,
    selStart: Int,
    selEnd: Int,
) {
    val ticket =
        focusCoordinator.request(
            targetKey = targetKey,
            region = region,
            source = RaftFocusRequestSource.User("voice_dictation"),
            isText = true,
            modalToken = modalToken,
        )
    if (!focusCoordinator.evaluate(ticket, checkEpochs = false, consume = true).isAllowed) return
    focusOwnerRegistry?.cancelPendingFocusDismissalForExplicitInputIntent()
    if (segmentedBlockId != null) {
        quoteController.requestFocusBlock(
            blockId = segmentedBlockId,
            offset = selStart,
            raiseKeyboard = false,
            selectionEnd = selEnd,
        )
    } else {
        composerFocusScope.launch {
            composerKeyboardIntent.focusWithoutKeyboard(
                focusRequester = composerFocusRequester,
            )
        }
    }
}

/**
 * Starts streaming voice dictation anchored to the active composer text field (plain or segmented
 * quote block):
 * - Finishes any open IME composition cleanly before capturing the base draft so system IME
 *   marked text never clashes with streaming voice composing state.
 * - Auto-focuses an unfocused composer without popping the soft keyboard on touch hosts so the
 *   user gets immediate caret + composing feedback without shifting a held mic button.
 * - Pins the active segmented quote block ID when started in segmented mode so updates and the
 *   composing underline stay attached strictly to that block.
 * - Guards incoming updates against concurrent IME composition or composer mode switches.
 */
internal fun startThreadComposerVoiceDictation(
    host: ThreadVoiceDictationHost?,
    composerEnabled: Boolean,
    editorOwner: ThreadComposerEditorOwner,
    quoteController: ThreadComposerQuoteController,
    suggestionSelectionDispatcher: ThreadComposerSuggestionSelectionDispatcher,
    onQuoteBlockIdChanged: (String?) -> Unit,
    onEmitChange: (String) -> Unit,
    isComposerFocused: Boolean = true,
    onRequestFocusWithoutKeyboard: ((segmentedBlockId: String?, selStart: Int, selEnd: Int) -> Unit)? = null,
) {
    if (host == null) return
    val serverId = host.serverId ?: return
    if (!host.canShow(composerEditable = composerEnabled)) return

    val segmentedAtStart = quoteController.isSegmented
    val targetBlock = if (segmentedAtStart) quoteController.activeTextBlockOrNull() else null
    val targetBlockId = targetBlock?.id
    val liveState =
        if (segmentedAtStart) {
            targetBlock?.let(quoteController::textFieldStateFor) ?: editorOwner.textFieldState
        } else {
            editorOwner.textFieldState
        }

    // Commit any open system IME composition before anchoring the dictation span so marked text
    // is preserved and never collides with streaming voice composing underlines.
    if (liveState.composition != null) {
        val committedSelection = liveState.selection
        liveState.edit {
            selection =
                TextRange(
                    committedSelection.start.coerceIn(0, length),
                    committedSelection.end.coerceIn(0, length),
                )
        }
        if (!segmentedAtStart) {
            editorOwner.markObserved()
        }
    }

    onQuoteBlockIdChanged(targetBlockId)
    val baseText = liveState.text.toString()
    val selStart = minOf(liveState.selection.start, liveState.selection.end).coerceIn(0, baseText.length)
    val selEnd = maxOf(liveState.selection.start, liveState.selection.end).coerceIn(selStart, baseText.length)
    val alreadyFocused =
        if (segmentedAtStart) {
            targetBlockId != null && quoteController.focusedTextBlockId == targetBlockId
        } else {
            isComposerFocused
        }
    if (!alreadyFocused) {
        onRequestFocusWithoutKeyboard?.invoke(targetBlockId, selStart, selEnd)
    }
    val startSessionId = editorOwner.editorSessionId
    val draftBefore = extractVoiceDictationDraftBeforeCaret(baseText, selStart)
    val contactHints = host.collectContactHints()

    host.controller.start(
        serverId = serverId,
        channelId = host.channelId,
        threadId = host.threadId,
        language = host.languageHint,
        baseText = baseText,
        insertStart = selStart,
        insertEnd = selEnd,
        draftBefore = draftBefore,
        contactHints = contactHints,
        onComposerUpdate = { update ->
            if (!editorOwner.isActiveSession(startSessionId)) return@start
            if (segmentedAtStart) {
                val resolvedBlockId = targetBlockId?.takeIf { quoteController.isSegmented }
                val blockState = resolvedBlockId?.let(quoteController::textFieldStateOrNull)
                if (resolvedBlockId == null || blockState == null || blockState.composition != null) {
                    host.controller.onUserEditedComposer(null)
                    return@start
                }
                val serialized =
                    quoteController.replaceTextBlock(
                        blockId = resolvedBlockId,
                        nextText = update.composerText,
                        nextCursor = update.caretOffset,
                        nextSelectionEnd = update.selectionEndOffset,
                    ) ?: run {
                        host.controller.onUserEditedComposer(null)
                        return@start
                    }
                if (editorOwner.applyProgrammaticText(startSessionId, serialized, cursor = serialized.length, publishAsSynced = true)) {
                    suggestionSelectionDispatcher.updateDraft(
                        text = update.composerText,
                        selectionStart = update.caretOffset,
                        selectionEnd = update.selectionEndOffset,
                    )
                    onEmitChange(serialized)
                }
            } else {
                if (quoteController.isSegmented || editorOwner.composition != null) {
                    host.controller.onUserEditedComposer(null)
                    return@start
                }
                if (
                    editorOwner.applyProgrammaticText(
                        sourceSessionId = startSessionId,
                        nextText = update.composerText,
                        cursor = update.caretOffset,
                        selectionEnd = update.selectionEndOffset,
                        publishAsSynced = true,
                    )
                ) {
                    suggestionSelectionDispatcher.updateDraft(
                        text = update.composerText,
                        selectionStart = update.caretOffset,
                        selectionEnd = update.selectionEndOffset,
                    )
                    onEmitChange(update.composerText)
                }
            }
        },
    )
}
