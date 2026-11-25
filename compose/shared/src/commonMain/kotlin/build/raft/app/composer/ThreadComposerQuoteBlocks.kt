package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.quoteChipExcerpt
import build.raft.app.markdown.raftTextDirection
import build.raft.app.markdown.toComposeLayoutDirection
import build.raft.app.markdown.toComposeTextDirection
import build.raft.app.resources.*

/*
 * Interleaved multi-quote composer body (botiverse/slock #16027 / #16059 / #16210,
 * `ComposerQuoteBlocks.tsx`): rendered inside the composer card whenever
 * `controller.isSegmented` (`quoteCount >= 1`).
 */

// not a token: matches web `ComposerQuoteBlocks` `max-h-[34dvh] sm:max-h-[38vh]` bounded scroll container.
private val ComposerQuoteBlocksMaxHeight = 260.dp
private val ComposerQuoteBlocksSpacing = 6.dp
private val ComposerQuoteSegmentTextMinHeight = 24.dp
private val ComposerQuoteSegmentTextMaxHeight = 160.dp

internal fun composerTextBlockTestTag(blockIndex: Int, isPrimary: Boolean): String =
    if (isPrimary) "composer_textarea" else "composer_segment_textarea_$blockIndex"

@Composable
internal fun ThreadComposerQuoteBlocks(
    controller: ThreadComposerQuoteController,
    avatarLookup: ThreadQuoteChipAvatarLookup,
    placeholder: String,
    composerEnabled: Boolean,
    effectiveImeActionRequested: Boolean,
    composerKeyboardOptions: KeyboardOptions,
    composerKeyboardActions: KeyboardActions,
    hardwareEnterConfig: RaftHardwareEnterConfig,
    nativeEditingStateReader: RaftTextFieldNativeEditingStateReader?,
    inputTransformation: InputTransformation? = null,
    suggestionKeyPresses: ThreadComposerSuggestionKeyPresses,
    onSuggestionKeyDown: (Key, Boolean) -> Boolean,
    onKeyboardHeightChange: (Float) -> Unit,
    onActiveTextChange: (blockId: String, state: TextFieldState, serialized: String) -> Unit,
    onActiveFocus: (blockId: String) -> Unit,
    onActiveBlur: (blockId: String, collapsedSerialized: String?) -> Unit,
    onSerializedCommit: (serialized: String, focusOffset: Int?) -> Unit,
    onJumpToQuotedMessage: ((String) -> Unit)?,
    onPasteAttachments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = controller.blocks
    val quoteCount = controller.quoteCount
    val isSingleSimple =
        quoteCount == 1 &&
            blocks.size == 2 &&
            blocks[0] is ComposerBlock.Quote &&
            blocks[1] is ComposerBlock.Text

    val focusRequesters = remember { linkedMapOf<String, FocusRequester>() }
    val activeTextIds = remember(blocks) { blocks.filterIsInstance<ComposerBlock.Text>().mapTo(linkedSetOf()) { it.id } }
    // Drop stale FocusRequesters for removed text blocks.
    remember(activeTextIds) {
        val stale = focusRequesters.keys.filterNot { it in activeTextIds }
        stale.forEach { focusRequesters.remove(it) }
        true
    }
    fun focusRequesterFor(blockId: String): FocusRequester =
        focusRequesters.getOrPut(blockId) { FocusRequester() }

    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val quoteKeyboardIntent = remember { RaftEditorKeyboardIntent() }
    val pendingFocus = controller.pendingFocusRequest
    LaunchedEffect(pendingFocus?.nonce) {
        val request = pendingFocus ?: return@LaunchedEffect
        val requester = focusRequesters[request.blockId]
        if (requester != null) {
            if (request.raiseKeyboard) {
                quoteKeyboardIntent.focusWithKeyboard(
                    focusRequester = requester,
                    softwareKeyboardController = softwareKeyboardController,
                )
            } else {
                runCatching { requester.requestFocus() }
            }
        }
        controller.consumeFocusRequest(request.nonce)
    }

    val quoteOrdinals =
        remember(blocks) {
            buildMap {
                var ordinal = 0
                for (block in blocks) {
                    if (block is ComposerBlock.Quote) {
                        put(block.id, ordinal++)
                    }
                }
            }
        }
    val firstTextId = blocks.firstOrNull { it is ComposerBlock.Text }?.id.orEmpty()
    val effectivePrimaryId =
        if (blocks.any { it is ComposerBlock.Text && it.id == controller.activeTextBlockId }) {
            controller.activeTextBlockId
        } else {
            firstTextId
        }

    val containerModifier =
        if (isSingleSimple) {
            modifier.fillMaxWidth().testTag(ComposerQuoteBlocksTestTag)
        } else {
            modifier
                .fillMaxWidth()
                .heightIn(max = ComposerQuoteBlocksMaxHeight)
                .verticalScroll(rememberScrollState())
                .testTag(ComposerQuoteBlocksTestTag)
        }

    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(ComposerQuoteBlocksSpacing),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ComposerBlock.Quote -> {
                    key(block.id) {
                        val ordinal = quoteOrdinals[block.id] ?: 0
                        val isStackedBeforeQuote = blocks.getOrNull(index + 1) is ComposerBlock.Quote
                        ComposerQuoteChipRow(
                            block = block,
                            quoteOrdinal = ordinal,
                            quoteCount = quoteCount,
                            isStackedBeforeQuote = isStackedBeforeQuote,
                            isArmed = controller.armedDeleteQuoteId == block.id,
                            isFlashing = controller.duplicateFlashBlockId == block.id,
                            avatarLookup = avatarLookup,
                            onJump = onJumpToQuotedMessage?.let { jump -> { jump(block.quote.messageId) } },
                            onInsertBelow = {
                                val serialized = controller.insertGapAfterQuote(block.id)
                                onSerializedCommit(serialized, null)
                            },
                            onRemove = {
                                val (serialized, focusOffset) = controller.removeQuote(block.id)
                                onSerializedCommit(serialized, focusOffset)
                            },
                        )
                    }
                }

                is ComposerBlock.Text -> {
                    key(block.id) {
                        val isPrimary = block.id == effectivePrimaryId
                        val isTrailing = index == blocks.lastIndex
                        val prevBlock = blocks.getOrNull(index - 1)
                        val nextBlock = blocks.getOrNull(index + 1)
                        val ariaLabel =
                            when {
                                prevBlock is ComposerBlock.Quote ->
                                    stringResource(
                                        Res.string.thread_quote_reply_below_aria,
                                        prevBlock.quote.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) },
                                    )
                                nextBlock is ComposerBlock.Quote ->
                                    stringResource(
                                        Res.string.thread_quote_reply_before_aria,
                                        nextBlock.quote.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) },
                                    )
                                else -> placeholder
                            }
                        val blockFieldState = controller.textFieldStateFor(block)
                        ComposerSegmentedTextBlockRow(
                            block = block,
                            blockIndex = index,
                            isPrimary = isPrimary,
                            isTrailing = isTrailing,
                            ariaLabel = ariaLabel,
                            placeholder = if (isTrailing) placeholder else "",
                            fieldState = blockFieldState,
                            composerEnabled = composerEnabled,
                            effectiveImeActionRequested = effectiveImeActionRequested,
                            composerKeyboardOptions = composerKeyboardOptions,
                            composerKeyboardActions = composerKeyboardActions,
                            hardwareEnterConfig = hardwareEnterConfig,
                            focusRequester = focusRequesterFor(block.id),
                            keyboardIntent = quoteKeyboardIntent,
                            nativeEditingStateReader = if (isPrimary) nativeEditingStateReader else null,
                            inputTransformation = inputTransformation,
                            suggestionKeyPresses = suggestionKeyPresses,
                            onSuggestionKeyDown = onSuggestionKeyDown,
                            onKeyboardHeightChange = onKeyboardHeightChange,
                            onPasteAttachments = onPasteAttachments,
                            onTextChange = { nextText ->
                                val serialized = controller.onTextBlockTextChanged(block.id, nextText)
                                onActiveTextChange(block.id, blockFieldState, serialized)
                            },
                            onFocus = {
                                controller.onTextBlockFocus(block.id)
                                onActiveFocus(block.id)
                            },
                            onBlur = {
                                val collapsed = controller.onTextBlockBlur(block.id)
                                onActiveBlur(block.id, collapsed)
                            },
                            onKeyDecision = { decision ->
                                when (decision) {
                                    ThreadComposerQuoteKeyDecision.PassThrough -> false
                                    ThreadComposerQuoteKeyDecision.DisarmAndPassThrough -> {
                                        controller.disarmQuote()
                                        false
                                    }
                                    is ThreadComposerQuoteKeyDecision.CollapseEmptyTextBlock -> {
                                        val serialized = controller.removeEmptyTextBlock(decision.textBlockId)
                                        onSerializedCommit(serialized, null)
                                        true
                                    }
                                    is ThreadComposerQuoteKeyDecision.ArmQuote -> {
                                        controller.armQuote(decision.quoteBlockId)
                                        true
                                    }
                                    is ThreadComposerQuoteKeyDecision.RemoveArmedQuote -> {
                                        val (serialized, focusOffset) = controller.removeQuote(decision.quoteBlockId)
                                        onSerializedCommit(serialized, focusOffset)
                                        true
                                    }
                                    is ThreadComposerQuoteKeyDecision.FocusTextBlock -> {
                                        controller.requestFocusBlock(decision.textBlockId, decision.offset)
                                        true
                                    }
                                    ThreadComposerQuoteKeyDecision.InsertIntroGapBeforeFirstQuote -> {
                                        val serialized = controller.insertGapBeforeIndex(0)
                                        onSerializedCommit(serialized, null)
                                        true
                                    }
                                }
                            },
                            currentBlocks = { controller.blocks },
                            currentArmedQuoteId = { controller.armedDeleteQuoteId },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerQuoteChipRow(
    block: ComposerBlock.Quote,
    quoteOrdinal: Int,
    quoteCount: Int,
    isStackedBeforeQuote: Boolean,
    isArmed: Boolean,
    isFlashing: Boolean,
    avatarLookup: ThreadQuoteChipAvatarLookup,
    onJump: (() -> Unit)?,
    onInsertBelow: () -> Unit,
    onRemove: () -> Unit,
) {
    val q = block.quote
    val authorName = q.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) }
    val excerpt =
        quoteChipExcerpt(q.excerpt, q.isPartial)
            .ifBlank { stringResource(Res.string.thread_quote_reply_empty_excerpt) }
    val groupLabel = stringResource(Res.string.thread_quote_reply_chip_group, authorName, excerpt.take(120))
    val jumpLabel = stringResource(Res.string.thread_quote_reply_composer_jump, authorName)
    val avatar =
        remember(avatarLookup, q.messageId, authorName, q.authorType) {
            avatarLookup.resolve(q.messageId, authorName, q.authorType)
        }

    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(composerQuoteRowTestTag(quoteOrdinal, quoteCount)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ThreadQuoteChipFrame(
            authorName = authorName,
            excerpt = excerpt,
            avatar = avatar,
            chipLabel = groupLabel,
            modifier = Modifier.testTag(composerQuoteChipTestTag(quoteOrdinal)),
            onJump = onJump,
            jumpLabel = jumpLabel,
            armed = isArmed,
            flashing = isFlashing,
            showInsertBelow = isStackedBeforeQuote,
            insertBelowModifier = Modifier.testTag(composerQuoteInsertBelowTestTag(quoteOrdinal)),
            onInsertBelow = onInsertBelow,
            showRemove = true,
            removeModifier = Modifier.testTag(composerQuoteRemoveTestTag(quoteOrdinal)),
            onRemove = onRemove,
        )
        if (isArmed) {
            ThreadQuoteArmedHint(
                modifier = Modifier.testTag(composerQuoteArmedHintTestTag(quoteOrdinal)),
            )
        }
    }
}

@Composable
private fun ComposerSegmentedTextBlockRow(
    block: ComposerBlock.Text,
    blockIndex: Int,
    isPrimary: Boolean,
    isTrailing: Boolean,
    ariaLabel: String,
    placeholder: String,
    fieldState: TextFieldState,
    composerEnabled: Boolean,
    effectiveImeActionRequested: Boolean,
    composerKeyboardOptions: KeyboardOptions,
    composerKeyboardActions: KeyboardActions,
    hardwareEnterConfig: RaftHardwareEnterConfig,
    focusRequester: FocusRequester,
    keyboardIntent: RaftEditorKeyboardIntent,
    nativeEditingStateReader: RaftTextFieldNativeEditingStateReader?,
    inputTransformation: InputTransformation?,
    suggestionKeyPresses: ThreadComposerSuggestionKeyPresses,
    onSuggestionKeyDown: (Key, Boolean) -> Boolean,
    onKeyboardHeightChange: (Float) -> Unit,
    onPasteAttachments: () -> Unit,
    onTextChange: (String) -> Unit,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onKeyDecision: (ThreadComposerQuoteKeyDecision) -> Boolean,
    currentBlocks: () -> List<ComposerBlock>,
    currentArmedQuoteId: () -> String?,
) {
    val currentText = fieldState.text.toString()
    val direction = remember(currentText) { raftTextDirection(currentText) }
    val layoutDirection = direction.toComposeLayoutDirection(LocalLayoutDirection.current)
    val quoteKeyPresses = remember(block.id) { ThreadComposerSuggestionKeyPresses() }
    val latestOnTextChange by rememberUpdatedState(onTextChange)
    val latestCurrentBlocks by rememberUpdatedState(currentBlocks)
    LaunchedEffect(block.id, fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect { nextText ->
            val currentBlock =
                latestCurrentBlocks().firstOrNull { it is ComposerBlock.Text && it.id == block.id } as? ComposerBlock.Text
            if (currentBlock != null && nextText != currentBlock.text) {
                latestOnTextChange(nextText)
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        TextField(
            state = fieldState,
            placeholder = placeholder,
            autoFocus = false,
            onFocus = onFocus,
            onBlur = onBlur,
            enabled = composerEnabled,
            maxLines = 4,
            imeActionRequested = effectiveImeActionRequested,
            keyboardOptions = composerKeyboardOptions,
            keyboardActions = composerKeyboardActions,
            hardwareEnterConfig = hardwareEnterConfig,
            inputTransformation = inputTransformation,
            traceName = "ThreadComposerQuoteSegment",
            textStyle =
                RaftType.Input.copy(
                    color = if (composerEnabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled,
                    textDirection = direction.toComposeTextDirection(),
                ),
            keyboardHeightChange = { params -> onKeyboardHeightChange(params.height) },
            placeholderColor = RaftColor.ForegroundHint,
            cursorBrush = SolidColor(RaftColor.TextBlack),
            nativeEditingStateReader = nativeEditingStateReader,
            focusRequester = focusRequester,
            keyboardIntent = keyboardIntent,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
                    .heightIn(min = ComposerQuoteSegmentTextMinHeight, max = ComposerQuoteSegmentTextMaxHeight)
                    .testTag(composerTextBlockTestTag(blockIndex, isPrimary))
                    .semantics {
                        if (!isPrimary || !isTrailing) {
                            contentDescription = ariaLabel
                        }
                    }
                    .threadComposerPasteAttachments(onPasteAttachments)
                    .threadComposerSuggestionKeys(suggestionKeyPresses) { key, shift ->
                        onSuggestionKeyDown(key, shift)
                    }
                    .onPreviewKeyEvent { event ->
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                val currentSelection = fieldState.selection
                                val decision =
                                    threadComposerQuoteBlockKeyDecision(
                                        blocks = currentBlocks(),
                                        blockIndex = blockIndex,
                                        key = event.key,
                                        isComposing = fieldState.composition != null,
                                        shift = event.isShiftPressed,
                                        alt = event.isAltPressed,
                                        ctrl = event.isCtrlPressed,
                                        meta = event.isMetaPressed,
                                        selectionStart = currentSelection.start,
                                        selectionEnd = currentSelection.end,
                                        textLength = fieldState.text.length,
                                        armedDeleteQuoteId = currentArmedQuoteId(),
                                    )
                                val consumed = onKeyDecision(decision)
                                quoteKeyPresses.down(event.key, consumed)
                                consumed
                            }
                            KeyEventType.KeyUp -> quoteKeyPresses.up(event.key)
                            else -> false
                        }
                    },
        )
    }
}
