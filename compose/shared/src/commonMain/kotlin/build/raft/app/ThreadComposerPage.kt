package build.raft.app

import build.raft.app.assistant.threadComposerComposing
import build.raft.app.resources.*
import kotlinx.serialization.Transient
import build.raft.app.transfer.raftComposerUploadDisplay
import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftComposerUploadDisplay
import build.raft.app.transfer.RaftComposerUploadPhase
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.ui.graphics.isSpecified
import build.raft.designsystem.components.RaftEmbeddedControls
import build.raft.designsystem.components.RaftComposerAttachment
import build.raft.designsystem.components.RaftComposerAttachmentFile
import build.raft.designsystem.components.RaftComposerAttachmentGap
import build.raft.designsystem.components.RaftComposerAttachmentImage
import build.raft.designsystem.components.RaftComposerAttachmentRemove
import build.raft.designsystem.components.RaftComposerAttachmentRemoveOverhang
import build.raft.designsystem.components.RaftComposerAttachmentOverlay
import build.raft.designsystem.components.RaftComposerAttachmentPhase
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.base.Utils
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import build.raft.app.components.RaftAppCheckbox
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.app.components.raftCardSurface
import build.raft.app.components.raftControlSurface
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.format.RaftFormat
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

private const val THREAD_COMPOSER_PAGE_DATA_EVENT = "slockPageData"
internal const val ThreadComposerAttachImageAction = "attachImage"
internal const val ThreadComposerCaptureImageAction = "captureImage"

internal fun threadComposerImageAction(longPress: Boolean): String =
    if (longPress) ThreadComposerCaptureImageAction else ThreadComposerAttachImageAction
private val ThreadComposerStandaloneTopPadding = 10.dp
private val ThreadComposerStandaloneBottomPadding = 8.dp
private val ThreadComposerHostKeyboardBasePadding = 12.dp
// web composer cases: a 342px canvas with the composer 20px in from each side and 8px down.
private val ThreadComposerVisualInputWidth = 302.dp
private const val ThreadComposerVisualInputTop = 8f
// raft-ui `ComposerInput` `min-h-6`.
private val ThreadComposerTextFieldMinHeight = 24.dp
private val ThreadComposerTextFieldMaxHeight = 80.dp
// raft-ui `ComposerIconButton` / `ComposerSubmit size="icon-sm"`: 32dp chip-radius boxes with
// an 18dp glyph (`iconMd`, whose `after:-inset-1.5` hit area reaches 44dp; Compose's minimum
// touch target extends the pointer bounds the same way).
private val ThreadComposerToolbarMinHeight = 32.dp
private val ThreadComposerIconButtonSize = 32.dp
private val ThreadComposerIconSize = 18.dp
private val ThreadComposerActionShadowOffset = 2.dp
// raft-ui `Composer`: the card's padding round the text, and `--control-embedded-inset`, how far
// the toolbar's buttons sit inside the card's outer edge (raft-design Shape > Inside a frame).
private val ThreadComposerCardPadding = 8.dp
private val ThreadComposerEmbeddedInset = 6.dp
// raft-ui `ComposerActions` / `ComposerMeta`: `gap-embedded-inset`. Buttons embedded in the frame
// sit as far apart as they sit from its edge (raft-design #810, shape.md "The space is even on
// every side"), so 6dp, not the old `gap-2` 8dp. Raft chrome casts no offset shadow, so this is
// the visible gap.
private val ThreadComposerLeftActionGroupSpacing = ThreadComposerEmbeddedInset
private val ThreadComposerRightActionGroupSpacing = ThreadComposerEmbeddedInset
// raft-ui `ComposerAttachments`: the composer's 321dp less the tray's 6dp `pe-1.5`; 64dp image
// tiles and 176dp file tiles (`size-16`, `w-44`), 8dp apart (`gap-2`).
private const val ThreadComposerAttachmentTrayMaxWidthDp = 315
private const val ThreadComposerAttachmentImageTileSizeDp = 64
private const val ThreadComposerAttachmentFileTileWidthDp = 176
private const val ThreadComposerAttachmentRowGapDp = 8
private const val ThreadComposerMentionActionNoticeDurationMillis = 1800L
private const val ThreadComposerSuggestionQueryDebounceMillis = 24L
private const val ThreadComposerNativeSnapshotTimeoutMillis = 1_000L
// raft-ui 0.6.0 `ComposerSuggestionList` geometry: `max-h-60` root with `p-1.5`, opening
// `mb-2` above the composer; `min-h-9 px-2.5 py-1 gap-2.5` options with a `gap-1.5`
// title/meta content row; `px-2.5 pt-2 pb-1` caption group labels; 19dp compact avatars
// and framed channel tiles (11dp glyph).
private val ComposerSuggestionPanelMaxHeight = 240.dp
private val ComposerSuggestionPanelInset = 6.dp
private val ComposerSuggestionPopoverGap = 8.dp
private val ComposerSuggestionHeaderEstimatedHeight = 28.dp
private val ComposerMemberSuggestionRowMinHeight = 36.dp
private val ComposerChannelSuggestionRowMinHeight = 36.dp
private val ComposerSuggestionStatusRowMinHeight = 36.dp
private val ComposerSuggestionItemPaddingHorizontal = 10.dp
private val ComposerSuggestionItemPaddingVertical = 4.dp
private val ComposerSuggestionItemGap = 10.dp
private val ComposerSuggestionContentGap = 6.dp
private val ComposerMemberSuggestionAvatarSize = 19.dp
private val ComposerMemberSuggestionAvatarNameGap = ComposerSuggestionItemGap
// web `AgentActivityDot size="sm"` on the 19dp compact avatar.
private val ComposerMemberSuggestionStatusSize = 7.dp
// web `ComposerSuggestionTitle max-w-[12rem]`, server meta `max-w-[10rem]`, handle `max-w-[7rem]`.
private val ComposerSuggestionTitleMaxWidth = 192.dp
private val ComposerSuggestionWhitespaceRegex = Regex("\\s+")
private val ComposerSuggestionServerMaxWidth = 160.dp
private val ComposerSuggestionHandleMaxWidth = 112.dp
private val ComposerSuggestionTitleStyle: TextStyle
    get() = RaftType.Button.copy(fontSize = 14.sp, lineHeight = 20.sp)
private val ComposerSuggestionMetaStyle: TextStyle
    get() = RaftType.Caption
private val ComposerChannelSuggestionIconSize = 19.dp
private val ComposerChannelSuggestionGlyphSize = 11.dp
private val ThreadComposerVisualComponentWidth = 342.dp
private val ThreadComposerVisualComponentMinHeight = 100.dp
private const val ThreadComposerVisualSuggestionAnchorOffset = 216f
private const val ThreadComposerVisualSuggestionRepositionOffset = 336f
private const val ThreadComposerSuggestionScrollOracleFixture = "member_suggestions_scroll_oracle"
private const val ThreadComposerSuggestionRepositionOracleFixture = "member_suggestions_reposition_oracle"
private const val ThreadComposerSuggestionInteractionOracleFixture = "suggestion_interaction_hit_test_oracle"
private const val ThreadComposerSuggestionRepositionDelayMillis = 8_000L
private const val ThreadComposerMentionBatchDetailsVisualFixture = "pending_mention_actions_batch_details"
private const val ThreadComposerMentionBatchPartialNoticeVisualMarker =
    "__visual_pending_mention_batch_partial_notice__"
private const val ThreadComposerMentionBatchPartialNoticeTitleVisualMarker =
    "__visual_pending_mention_batch_partial_notice_title__"
private val ThreadComposerSuggestionScrollOracleUnderlay = Color(0xFF22D3EE) // raft-color: visual-test oracle colour
private val ThreadComposerSuggestionScrollOracleLeak = Color(0xFFEF4444) // raft-color: visual-test oracle colour
private val ThreadComposerSuggestionInteractionOracleSuccess = Color(0xFF22C55E) // raft-color: visual-test oracle colour
private const val ThreadComposerSuggestionInteractionOracleMarker = "ATTACH_IMAGE_RECEIVED"
internal val ThreadComposerSuggestionOutsidePointerMode =
    RaftFloatingOverlayOutsidePointerMode.PassThrough
private const val ThreadComposerVisualPreviewUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20128%20128%22%3E%3Crect%20width%3D%22128%22%20height%3D%22128%22%20fill%3D%22%23FDE047%22%2F%3E%3Ccircle%20cx%3D%2288%22%20cy%3D%2240%22%20r%3D%2220%22%20fill%3D%22%23F472B6%22%2F%3E%3Cpath%20d%3D%22M16%20104L48%2068l22%2024%2018-16%2024%2028H16z%22%20fill%3D%22%23000000%22%2F%3E%3C%2Fsvg%3E"
private val threadComposerJson = Json { ignoreUnknownKeys = true }

internal class ThreadComposerSuggestionPortalOwner {
    private var generation: Long = 0L
    private var active: Boolean = true

    fun suspendForFocusLoss(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        active = false
        return true
    }

    fun resumeForEditing(expectedGeneration: Long): Long {
        generation = maxOf(generation, expectedGeneration) + 1L
        active = true
        return generation
    }

    fun accepts(expectedGeneration: Long): Boolean =
        active && generation == expectedGeneration

    fun release() {
        generation += 1L
        active = false
    }
}

internal class ThreadComposerSuggestionPortalState {
    var presentation by mutableStateOf<ThreadComposerSuggestionPresentation?>(null)
        private set

    fun show(next: ThreadComposerSuggestionPresentation) {
        if (!next.owner.accepts(next.generation)) return
        if (presentation != next) presentation = next
    }

    fun clear(owner: ThreadComposerSuggestionPortalOwner, generation: Long) {
        if (!owner.accepts(generation)) return
        clearPresentation(owner)
    }

    fun suspendForFocusLoss(owner: ThreadComposerSuggestionPortalOwner, generation: Long) {
        if (!owner.suspendForFocusLoss(generation)) return
        clearPresentation(owner)
    }

    fun resumeForEditing(owner: ThreadComposerSuggestionPortalOwner, generation: Long): Long =
        owner.resumeForEditing(generation)

    fun retireForEditingContinuation(owner: ThreadComposerSuggestionPortalOwner, generation: Long): Long {
        val nextGeneration = resumeForEditing(owner, generation)
        clearPresentation(owner)
        return nextGeneration
    }

    fun acceptsEditingContinuation(owner: ThreadComposerSuggestionPortalOwner, generation: Long): Boolean =
        owner.accepts(generation)

    fun release(owner: ThreadComposerSuggestionPortalOwner) {
        owner.release()
        clearPresentation(owner)
    }

    private fun clearPresentation(owner: ThreadComposerSuggestionPortalOwner) {
        if (presentation?.owner === owner) presentation = null
    }
}

/**
 * Retires both suggestion visibility owners in the same focus-loss callback.
 *
 * iOS can publish the native editor blur before the child composition that owns `focused`
 * reaches its next SideEffect. Suspending this exact owner generation clears the lifted portal
 * and rejects delayed publication from the retired composition.
 */
internal fun dismissThreadComposerSuggestionsForFocusLoss(
    portalState: ThreadComposerSuggestionPortalState?,
    owner: ThreadComposerSuggestionPortalOwner,
    generation: Long,
    onFocusedChanged: (Boolean) -> Unit
) {
    if (portalState == null) {
        owner.suspendForFocusLoss(generation)
    } else {
        portalState.suspendForFocusLoss(owner, generation)
    }
    onFocusedChanged(false)
}

internal fun resumeThreadComposerSuggestionsForEditing(
    portalState: ThreadComposerSuggestionPortalState?,
    owner: ThreadComposerSuggestionPortalOwner,
    generation: Long,
    onFocusedChanged: (Boolean) -> Unit
): Long {
    val nextGeneration =
        portalState?.resumeForEditing(owner, generation) ?: owner.resumeForEditing(generation)
    onFocusedChanged(true)
    return nextGeneration
}

internal fun continueThreadComposerSuggestionsForEditing(
    portalState: ThreadComposerSuggestionPortalState?,
    owner: ThreadComposerSuggestionPortalOwner,
    generation: Long,
    onFocusedChanged: (Boolean) -> Unit
) {
    val accepted =
        portalState?.acceptsEditingContinuation(owner, generation) ?: owner.accepts(generation)
    if (accepted) {
        onFocusedChanged(true)
    }
}

internal fun hideThreadComposerSuggestionsForEditingContinuation(
    portalState: ThreadComposerSuggestionPortalState?,
    owner: ThreadComposerSuggestionPortalOwner,
    generation: Long,
    onFocusedChanged: (Boolean) -> Unit
): Long {
    val nextGeneration =
        portalState?.retireForEditingContinuation(owner, generation) ?: owner.resumeForEditing(generation)
    onFocusedChanged(false)
    return nextGeneration
}

internal data class ThreadComposerSuggestionPresentation(
    val owner: ThreadComposerSuggestionPortalOwner,
    val generation: Long,
    val anchorResolver: RaftPopoverAnchorResolver,
    val selectionContext: ThreadComposerSuggestionSelectionContext,
    val contentSize: Size,
    val panelState: ThreadComposerSuggestionPanelState,
    val surface: ThreadComposerSurface,
    val memberSuggestions: List<RaftComposerMemberItem>,
    val memberSelectedIndex: Int,
    val memberErrorCode: String?,
    val channelSuggestions: List<RaftComposerChannelItem>,
    val channelSelectedIndex: Int,
    val channelErrorCode: String?,
    val agentStatusResolver: RaftAgentStatusResolver,
    val onDismiss: () -> Unit,
    val onMemberClick: (ThreadComposerSuggestionSelectionContext, RaftComposerMemberItem) -> Unit,
    val onChannelClick: (ThreadComposerSuggestionSelectionContext, RaftComposerChannelItem) -> Unit
)

internal val LocalThreadComposerSuggestionPortal =
    compositionLocalOf<ThreadComposerSuggestionPortalState?> { null }

@Composable
internal fun ThreadComposerSuggestionPortalHost(
    portalState: ThreadComposerSuggestionPortalState
) {
    val presentation = portalState.presentation ?: return
    val anchor = presentation.anchorResolver.resolveCurrentPlacement() ?: return
    val density = LocalDensity.current
    val panelWidth = with(density) { presentation.contentSize.width.toDp() }
    val panelHeight = with(density) { presentation.contentSize.height.toDp() }
    val viewportGapPx = with(density) { RaftFloatingDefaultViewportGap.dp.toPx() }
    val popoverGapPx = with(density) { ComposerSuggestionPopoverGap.toPx() }
    val currentOnDismiss by rememberUpdatedState(presentation.onDismiss)
    RaftAnchoredPopover(
        anchor = anchor,
        contentSize = presentation.contentSize,
        onDismiss = currentOnDismiss,
        viewportGap = viewportGapPx,
        verticalOffset = popoverGapPx,
        verticalOffsetAbove = popoverGapPx,
        verticalPlacement = RaftFloatingVerticalPlacement.Above,
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis,
        // Suggestions are part of the active composer, not a modal menu. The shared focus host
        // already dismisses them when a real outside target takes focus; a full-screen dismiss
        // barrier would instead consume taps meant for the editor and attachment controls.
        outsidePointerMode = ThreadComposerSuggestionOutsidePointerMode
    ) {
        Box(
            modifier =
                Modifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .retainInputFocusForInteractions()
                    .clickable(onClick = {})
        ) {
            ComposerSuggestionPanelContent(
                panelState = presentation.panelState,
                surface = presentation.surface,
                memberSuggestions = presentation.memberSuggestions,
                memberSelectedIndex = presentation.memberSelectedIndex,
                memberErrorCode = presentation.memberErrorCode,
                channelSuggestions = presentation.channelSuggestions,
                channelSelectedIndex = presentation.channelSelectedIndex,
                channelErrorCode = presentation.channelErrorCode,
                agentStatusResolver = presentation.agentStatusResolver,
                onMemberClick = { member ->
                    presentation.onMemberClick(presentation.selectionContext, member)
                },
                onChannelClick = { channel ->
                    presentation.onChannelClick(presentation.selectionContext, channel)
                }
            )
        }
    }
}
private val threadComposerContentPayloadKeys =
    setOf(
        "placeholder",
        "enabled",
        "attachmentActionsEnabled",
        "originalImageOptionEnabled",
        "originalImage",
        "showAsTaskToggle",
        "composerMode",
        "statusLabel",
        "pendingAttachments",
        "pendingMentionActions",
        "mentionActionNoticeTitle",
        "mentionActionNotice",
        "mentionActionStates",
        "removingMentionActionIds",
        "executingMentionActionStates",
        "memberSuggestions",
        "channelSuggestions",
        "hookLabels"
    )

@Serializable
internal data class RaftThreadComposerPayload(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    @kotlinx.serialization.Transient val editorCommand: ThreadComposerEditorCommand? = null,
    val focusRequestNonce: Int = 0,
    val placeholder: String = "Message",
    val enabled: Boolean = true,
    val attachmentActionsEnabled: Boolean = true,
    /** Whether this host surface owns the image policy checkbox. */
    val originalImageOptionEnabled: Boolean = true,
    /**
     * Session-only preference for image attachments.  The draft persistence slice deliberately
     * does not serialize this value; a new composer starts with compressed images by default.
     */
    val originalImage: Boolean = false,
    val showAsTaskToggle: Boolean = true,
    val composerMode: String = "idle",
    val statusLabel: String? = null,
    val pendingAttachments: List<RaftPendingAttachmentItem> = emptyList(),
    val pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList(),
    val mentionActionNoticeTitle: String? = null,
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionStates: Map<String, String> = emptyMap(),
    val memberSuggestions: List<RaftComposerMemberItem> = emptyList(),
    val channelSuggestions: List<RaftComposerChannelItem> = emptyList(),
    val suggestionsOpen: Boolean = false,
    val surfaceKind: String = RaftThreadSurfaceKind.Channel,
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
    val hookLabels: List<String> = emptyList()
)

@Serializable
internal data class WireThreadComposerLaunchPayload(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val surfaceKind: String = RaftThreadSurfaceKind.Channel,
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null
)

internal data class ThreadComposerSurface(
    val kind: String = RaftThreadSurfaceKind.Channel,
    val channelId: String,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val mentionChannelId: String? = null,
    val mentionChannelLabel: String? = null,
    val memberSuggestionHeadersVisible: Boolean = kind != RaftThreadSurfaceKind.DirectMessage,
    val showTaskButton: Boolean = true,
    /** False under task v2: nothing this composer sends is a task ([withTaskV2]). */
    val sendsAsTask: Boolean = true
) {
    val sendChannelId: String?
        get() = streamChannelId?.takeIf { it.isNotBlank() } ?: channelId.takeIf { it.isNotBlank() }

    val mentionScopeChannelId: String?
        get() =
            mentionChannelId?.takeIf { it.isNotBlank() }
                ?: parentChannelId?.takeIf { it.isNotBlank() }
                ?: streamChannelId?.takeIf { it.isNotBlank() }
                ?: channelId.takeIf { it.isNotBlank() }
}

/**
 * Task v2 (web ChatPanel / ThreadPanel `showTaskButton={... && !taskV2}`, credited to stdrc): people
 * send a normal message and create the task from it, so the composer has no "As Task" toggle and
 * nothing it sends is a task. Off (or unresolved), the surface keeps its toggle as before.
 */
internal fun ThreadComposerSurface.withTaskV2(taskV2Enabled: Boolean): ThreadComposerSurface =
    if (taskV2Enabled) copy(showTaskButton = false, sendsAsTask = false) else this

internal fun ThreadComposerSurface.threadComposerDraftKey(): String? {
    val sendTarget = sendChannelId?.takeIf { it.isNotBlank() } ?: return null
    return listOf(
        "send" to sendTarget,
        "parent" to parentMessageId.orEmpty(),
        "mention" to mentionScopeChannelId.orEmpty()
    ).joinToString(separator = "|") { (name, value) ->
        "$name=${value.threadComposerDraftKeyPart()}"
    }
}

private fun String.threadComposerDraftKeyPart(): String =
    if (isBlank()) {
        "_"
    } else {
        replace("%", "%25")
            .replace("|", "%7C")
            .replace("=", "%3D")
    }

internal data class ThreadComposerUiState(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val editorCommand: ThreadComposerEditorCommand? = null,
    val focusRequestNonce: Int = 0,
    val placeholder: String = "Message",
    val enabled: Boolean = true,
    val attachmentActionsEnabled: Boolean = true,
    val originalImageOptionEnabled: Boolean = true,
    val originalImage: Boolean = false,
    val composerMode: String = "idle",
    val statusLabel: String? = null,
    val pendingAttachments: List<RaftPendingAttachmentItem> = emptyList(),
    val pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList(),
    val mentionActionNoticeTitle: String? = null,
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionStates: Map<String, String> = emptyMap(),
    val memberSuggestions: List<RaftComposerMemberItem> = emptyList(),
    val memberSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val memberSuggestionsErrorCode: String? = null,
    val channelSuggestions: List<RaftComposerChannelItem> = emptyList(),
    val channelSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val channelSuggestionsErrorCode: String? = null,
    val suggestionsEnabled: Boolean = true,
    val suggestionsOpen: Boolean = false,
    val hookLabels: List<String> = emptyList()
)

internal enum class ThreadComposerEditorCommandKind {
    Clear,
    Replace,
}

internal data class ThreadComposerEditorCommand(
    val id: Int,
    val kind: ThreadComposerEditorCommandKind,
    val text: String = "",
    val asTask: Boolean = false,
    val expectedText: String? = null,
    val expectedAsTask: Boolean? = null,
)

internal fun shouldApplyThreadComposerEditorCommand(
    localText: String,
    localAsTask: Boolean,
    command: ThreadComposerEditorCommand,
): Boolean =
    (command.expectedText == null || command.expectedText == localText) &&
        (command.expectedAsTask == null || command.expectedAsTask == localAsTask)

internal enum class ThreadComposerEditorCommandOutcome {
    Applied,
    Stale,
    AlreadyHandled,
}

internal fun acceptThreadComposerEditorSessionUiEvent(
    sourceSessionId: Long,
    isActiveSession: (Long) -> Boolean,
    onAccepted: () -> Unit,
): Boolean {
    if (!isActiveSession(sourceSessionId)) return false
    onAccepted()
    return true
}

internal data class ThreadComposerSessionFocusRestorePlan(
    val requestFocus: Boolean,
    val showKeyboard: Boolean,
)

internal fun threadComposerSessionFocusRestorePlan(
    explicitFocusRequest: Boolean,
    editorSessionReplaced: Boolean,
    editorFocused: Boolean,
    keyboardInsetDp: Float,
): ThreadComposerSessionFocusRestorePlan {
    val transferVisibleEditor = editorSessionReplaced && editorFocused && keyboardInsetDp > 0f
    return ThreadComposerSessionFocusRestorePlan(
        requestFocus = explicitFocusRequest || transferVisibleEditor,
        showKeyboard = explicitFocusRequest || transferVisibleEditor,
    )
}

internal fun dispatchThreadComposerHostKeyboardInset(
    nextInsetDp: Float,
    onKeyboardHeightChange: (Float) -> Unit,
): Float {
    val nextInset = nextInsetDp.coerceAtLeast(0f)
    onKeyboardHeightChange(nextInset)
    return nextInset
}

/**
 * Kuikly 2.24's legacy text callback has no selection payload. When that callback arrives before
 * the complete editing-state callback, it represents the unknown selection as [TextRange.Zero].
 * Repair only the subset whose edit location is proven by the previously controlled selection:
 * a non-empty insertion that replaced exactly that range. The returned copy intentionally has a
 * new identity so Kuikly writes the repaired selection back to the native editor.
 */
internal fun repairThreadComposerLegacyZeroSelection(
    previous: TextFieldValue,
    incoming: TextFieldValue,
): TextFieldValue {
    if (
        incoming.selection != TextRange.Zero ||
            incoming.text == previous.text ||
            previous.composition != null ||
            incoming.composition != null
    ) {
        return incoming
    }

    val previousStart =
        minOf(previous.selection.start, previous.selection.end).coerceIn(0, previous.text.length)
    val previousEnd =
        maxOf(previous.selection.start, previous.selection.end).coerceIn(previousStart, previous.text.length)
    val unchangedPrefix = previous.text.substring(0, previousStart)
    val unchangedSuffix = previous.text.substring(previousEnd)
    if (!incoming.text.startsWith(unchangedPrefix) || !incoming.text.endsWith(unchangedSuffix)) {
        return incoming
    }

    val insertedLength = incoming.text.length - unchangedPrefix.length - unchangedSuffix.length
    if (insertedLength <= 0) return incoming

    return incoming.copy(selection = TextRange(previousStart + insertedLength))
}

internal class ThreadComposerControlledEditorOwner(
    seedText: String,
    seedAsTask: Boolean,
) {
    var value by mutableStateOf(
        TextFieldValue(seedText, selection = TextRange(seedText.length)),
        referentialEqualityPolicy(),
    )
        private set
    var asTask by mutableStateOf(seedAsTask)
        private set
    var handledCommandId by mutableStateOf(0)
        private set
    var editorSessionId by mutableStateOf(1L)
        private set

    fun isActiveSession(sourceSessionId: Long): Boolean =
        sourceSessionId == editorSessionId

    fun acceptEditorValue(sourceSessionId: Long, next: TextFieldValue): Boolean {
        if (!isActiveSession(sourceSessionId)) return false
        value = next
        return true
    }

    fun acceptNativeEditorValue(
        sourceSessionId: Long,
        incoming: TextFieldValue,
    ): TextFieldValue? {
        if (!isActiveSession(sourceSessionId)) return null
        val accepted =
            repairThreadComposerLegacyZeroSelection(
                previous = value,
                incoming = incoming,
            )
        value = accepted
        return accepted
    }

    fun acceptAsTask(sourceSessionId: Long, next: Boolean): Boolean {
        if (!isActiveSession(sourceSessionId)) return false
        asTask = next
        return true
    }

    fun apply(command: ThreadComposerEditorCommand): ThreadComposerEditorCommandOutcome {
        if (command.id <= handledCommandId) return ThreadComposerEditorCommandOutcome.AlreadyHandled
        if (!shouldApplyThreadComposerEditorCommand(value.text, asTask, command)) {
            handledCommandId = command.id
            return ThreadComposerEditorCommandOutcome.Stale
        }
        val nextText = if (command.kind == ThreadComposerEditorCommandKind.Clear) "" else command.text
        if (command.kind == ThreadComposerEditorCommandKind.Clear) {
            editorSessionId += 1
        }
        value = TextFieldValue(nextText, selection = TextRange(nextText.length))
        asTask = if (command.kind == ThreadComposerEditorCommandKind.Clear) false else command.asTask
        handledCommandId = command.id
        return ThreadComposerEditorCommandOutcome.Applied
    }
}

internal data class ThreadComposerSendRequest(
    val editorSessionId: Long,
    val value: TextFieldValue,
    val asTask: Boolean,
)

internal enum class ThreadComposerSendAdmission {
    Started,
    Deferred,
    Rejected,
}

/**
 * Owns the one in-flight native snapshot request. A timeout or unavailable editor consumes the
 * same opaque token as success, so a late callback cannot be mistaken for a later Send attempt.
 */
internal class ThreadComposerSendSnapshotGate {
    var pendingToken: Any? by mutableStateOf(null)
        private set

    val isPending: Boolean
        get() = pendingToken != null

    fun begin(): Any {
        check(!isPending)
        return Any().also { pendingToken = it }
    }

    fun finish(token: Any): Boolean {
        if (pendingToken !== token) return false
        pendingToken = null
        return true
    }
}

/**
 * Fences the one native callback that may already be queued for the same editing state captured
 * at Send. A genuinely newer edit has different text and must keep the existing stale-Clear
 * protection, so it is accepted and retires this fence immediately.
 */
internal class ThreadComposerSubmittedEchoFence {
    private var submitted: ThreadComposerSendRequest? = null

    fun begin(request: ThreadComposerSendRequest) {
        submitted = request
    }

    fun finishAdmission(admission: ThreadComposerSendAdmission) {
        if (admission != ThreadComposerSendAdmission.Started) submitted = null
    }

    fun shouldIgnore(
        sourceSessionId: Long,
        next: TextFieldValue,
    ): Boolean {
        val baseline = submitted ?: return false
        if (baseline.editorSessionId != sourceSessionId) {
            submitted = null
            return false
        }
        if (baseline.value.text == next.text) {
            submitted = null
            return true
        }
        submitted = null
        return false
    }
}

internal data class ThreadComposerInsertMentionRequest(
    val nonce: Int,
    val handle: String
)

internal enum class ThreadComposerSuggestionPanelState {
    Hidden,
    MemberItems,
    MemberLoading,
    MemberEmpty,
    MemberError,
    ChannelItems,
    ChannelLoading,
    ChannelEmpty,
    ChannelError
}

internal enum class ThreadComposerSuggestionRefreshRequest {
    Member,
    Channel
}

internal enum class ThreadComposerEnterAction {
    AcceptMemberSuggestion,
    AcceptChannelSuggestion,
    CommitSuggestionToken,
    Send,
    Noop
}

internal enum class ThreadComposerSuggestionSelectionMove {
    Up,
    Down
}

internal fun threadComposerSuggestionSelectedIndex(
    selectedIndex: Int,
    itemCount: Int
): Int =
    if (itemCount <= 0) {
        0
    } else {
        selectedIndex.coerceIn(0, itemCount - 1)
    }

internal fun threadComposerSuggestionSelectedIndexAfterMove(
    selectedIndex: Int,
    itemCount: Int,
    move: ThreadComposerSuggestionSelectionMove
): Int {
    if (itemCount <= 0) return 0
    val current = threadComposerSuggestionSelectedIndex(selectedIndex, itemCount)
    return when (move) {
        ThreadComposerSuggestionSelectionMove.Up ->
            if (current == 0) itemCount - 1 else current - 1
        ThreadComposerSuggestionSelectionMove.Down ->
            if (current == itemCount - 1) 0 else current + 1
    }
}

/**
 * Only a hardware Enter defers to an open IME composition (Enter commits the conversion). The soft
 * keyboard's Send key always acts: Gboard keeps the Latin word being typed in composition, so
 * gating it would make Send a no-op; the Send snapshot reader commits the composition instead
 * (raftWithFinishedComposition).
 */
internal fun threadComposerEnterDeferredByComposition(
    fromHardwareEnter: Boolean,
    composition: TextRange?,
): Boolean = fromHardwareEnter && composition != null

internal fun threadComposerEnterAction(
    trigger: RaftComposerSuggestionTrigger,
    panelState: ThreadComposerSuggestionPanelState,
    memberCount: Int,
    channelCount: Int,
    canSend: Boolean,
    isImeComposing: Boolean
): ThreadComposerEnterAction {
    if (isImeComposing) return ThreadComposerEnterAction.Noop
    return when (trigger) {
        is RaftComposerSuggestionTrigger.MemberTrigger ->
            when (panelState) {
                ThreadComposerSuggestionPanelState.MemberItems ->
                    if (memberCount > 0) ThreadComposerEnterAction.AcceptMemberSuggestion else ThreadComposerEnterAction.CommitSuggestionToken
                ThreadComposerSuggestionPanelState.MemberLoading,
                ThreadComposerSuggestionPanelState.MemberEmpty,
                ThreadComposerSuggestionPanelState.MemberError ->
                    ThreadComposerEnterAction.CommitSuggestionToken
                else ->
                    if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
            }

        is RaftComposerSuggestionTrigger.ChannelTrigger ->
            when (panelState) {
                ThreadComposerSuggestionPanelState.ChannelItems ->
                    if (channelCount > 0) ThreadComposerEnterAction.AcceptChannelSuggestion else ThreadComposerEnterAction.CommitSuggestionToken
                ThreadComposerSuggestionPanelState.ChannelLoading,
                ThreadComposerSuggestionPanelState.ChannelEmpty,
                ThreadComposerSuggestionPanelState.ChannelError ->
                    ThreadComposerEnterAction.CommitSuggestionToken
                else ->
                    if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
            }

        RaftComposerSuggestionTrigger.None ->
            if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
    }
}

/**
 * Only the "return key sends" preference decides whether the editor claims an IME action.
 *
 * A visible suggestion panel used to raise the action too, so Enter could accept a candidate even
 * in newline mode. Device measurement showed that leaves the editor permanently broken: the action
 * is pushed down when the panel opens, nothing withdraws it when the panel closes (Kuikly re-sends
 * returnKeyType only for single-line fields), and the stale Send then swallows Enter entirely —
 * no newline, no send — until the page is re-entered. Users would experience it as "this box
 * sometimes won't break lines" with no connection to having typed an @.
 *
 * Never raising the action in newline mode is how 1.9 behaved and is what the current contract
 * asks for: newline mode inserts a newline, suggestions are accepted by tapping them.
 *
 * The underlying defect is avoided here, not repaired: Kuikly still re-sends returnKeyType only
 * for single-line fields, so on a multi-line editor an action that goes down never comes back up.
 * Anything that changes this editor's imeAction *while it is mounted* re-enters that state and
 * reproduces the swallowed Enter — with no exception, no failing test, and nothing visible short
 * of reading imeOptions on a device. Returning a value that can vary during the editor's lifetime
 * is what makes this function dangerous, which is why it now depends on the preference alone.
 */
internal fun threadComposerUsesImeAction(
    composerSendKeyEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") panelState: ThreadComposerSuggestionPanelState,
    @Suppress("UNUSED_PARAMETER") memberCount: Int,
    @Suppress("UNUSED_PARAMETER") channelCount: Int
): Boolean = composerSendKeyEnabled

internal fun threadComposerImeAction(composerUsesImeAction: Boolean): ImeAction =
    if (composerUsesImeAction) ImeAction.Send else ImeAction.Default

internal fun threadComposerSuggestionRefreshRequest(
    focused: Boolean,
    trigger: RaftComposerSuggestionTrigger
): ThreadComposerSuggestionRefreshRequest? {
    if (!focused) return null
    return when (trigger) {
        is RaftComposerSuggestionTrigger.MemberTrigger -> ThreadComposerSuggestionRefreshRequest.Member
        is RaftComposerSuggestionTrigger.ChannelTrigger -> ThreadComposerSuggestionRefreshRequest.Channel
        RaftComposerSuggestionTrigger.None -> null
    }
}

internal fun threadComposerEffectiveSuggestionStatus(
    triggerActive: Boolean,
    rankingReady: Boolean,
    usesImmediateSuggestions: Boolean,
    sourceStatus: SharedMembersLoadStatus
): SharedMembersLoadStatus {
    if (!triggerActive || rankingReady || usesImmediateSuggestions) return sourceStatus
    return when (sourceStatus) {
        SharedMembersLoadStatus.InFlight -> SharedMembersLoadStatus.InFlight
        else -> sourceStatus
    }
}

internal fun threadComposerSuggestionPanelState(
    focused: Boolean,
    trigger: RaftComposerSuggestionTrigger,
    memberCount: Int,
    memberStatus: SharedMembersLoadStatus,
    channelCount: Int,
    channelStatus: SharedMembersLoadStatus
): ThreadComposerSuggestionPanelState {
    if (!focused) return ThreadComposerSuggestionPanelState.Hidden
    return when (trigger) {
        is RaftComposerSuggestionTrigger.MemberTrigger ->
            when {
                memberCount > 0 -> ThreadComposerSuggestionPanelState.MemberItems
                memberStatus == SharedMembersLoadStatus.Error -> ThreadComposerSuggestionPanelState.MemberError
                memberStatus == SharedMembersLoadStatus.InFlight -> ThreadComposerSuggestionPanelState.MemberLoading
                memberStatus == SharedMembersLoadStatus.Loaded -> ThreadComposerSuggestionPanelState.MemberEmpty
                else -> ThreadComposerSuggestionPanelState.MemberEmpty
            }

        is RaftComposerSuggestionTrigger.ChannelTrigger ->
            when {
                channelCount > 0 -> ThreadComposerSuggestionPanelState.ChannelItems
                channelStatus == SharedMembersLoadStatus.Error -> ThreadComposerSuggestionPanelState.ChannelError
                channelStatus == SharedMembersLoadStatus.InFlight -> ThreadComposerSuggestionPanelState.ChannelLoading
                channelStatus == SharedMembersLoadStatus.Loaded -> ThreadComposerSuggestionPanelState.ChannelEmpty
                else -> ThreadComposerSuggestionPanelState.ChannelEmpty
            }

        RaftComposerSuggestionTrigger.None -> ThreadComposerSuggestionPanelState.Hidden
    }
}

internal fun <T> threadComposerRenderedSuggestions(
    loadedSuggestions: List<T>,
    retainedSuggestions: List<T>,
    fallbackSuggestions: List<T>,
    status: SharedMembersLoadStatus
): List<T> =
    loadedSuggestions.takeIf { it.isNotEmpty() || status == SharedMembersLoadStatus.Loaded }
        ?: retainedSuggestions.takeIf { it.isNotEmpty() }
        ?: fallbackSuggestions

internal fun restoreComposerInputAfterExternalMention(
    focusOwnerRegistry: RaftInputFocusOwnerRegistry?,
    requestFocus: () -> Unit,
    showKeyboard: () -> Unit
) {
    focusOwnerRegistry?.cancelPendingFocusDismissalForExplicitInputIntent()
    requestFocus()
    showKeyboard()
}

@Serializable
internal data class RaftPendingAttachmentItem(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val previewUri: String? = null,
    val status: String = "ready",
    val errorCode: String? = null,
    val source: String? = null,
    val externalShareToken: String? = null,
    val externalShareAccountId: String? = null,
    val externalShareServerId: String? = null,
    /** The upload meter's state; null until the upload starts sending. Not serialized. */
    @Transient val uploadTransfer: RaftTransferProgress? = null
) {
    /** What the tray shows for this attachment's upload; null when it is ready. */
    val uploadDisplay: RaftComposerUploadDisplay?
        get() = raftComposerUploadDisplay(status, uploadTransfer)
}

@Serializable
internal data class RaftPendingMentionActionItem(
    val resolutionId: String,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val targetAvatarUrl: String? = null,
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
internal data class RaftComposerMemberItem(
    val displayName: String,
    val handle: String,
    val inMentionChannel: Boolean = true,
    val inThread: Boolean = false,
    val sourceServerId: String? = null,
    val isLocalServer: Boolean = true,
    val sourceServerLabel: String? = null,
    val kind: MemberKind = MemberKind.Human,
    val description: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    val memberId: String? = null
)

@Serializable
internal data class RaftComposerChannelItem(
    val name: String,
    val subtitle: String? = null
)

data class SharedThreadComposerSnapshot(
    val value: String = "",
    val asTask: Boolean = false
)

data class SharedThreadComposerFlowState(
    val hostDraftText: String,
    val liveDraftText: String,
    val payloadTextSeed: String,
    val hostDraftAsTask: Boolean,
    val liveDraftAsTask: Boolean,
    val payloadAsTaskSeed: Boolean,
    val syncNonce: Int = 0
) {
    companion object {
        fun initial(snapshot: SharedThreadComposerSnapshot): SharedThreadComposerFlowState =
            SharedThreadComposerFlowState(
                hostDraftText = snapshot.value,
                liveDraftText = snapshot.value,
                payloadTextSeed = snapshot.value,
                hostDraftAsTask = snapshot.asTask,
                liveDraftAsTask = snapshot.asTask,
                payloadAsTaskSeed = snapshot.asTask
            )
    }

    fun applyHostSnapshot(snapshot: SharedThreadComposerSnapshot): SharedThreadComposerFlowState =
        if (snapshot.value != hostDraftText || snapshot.asTask != hostDraftAsTask) {
            copy(
                hostDraftText = snapshot.value,
                liveDraftText = snapshot.value,
                payloadTextSeed = snapshot.value,
                hostDraftAsTask = snapshot.asTask,
                liveDraftAsTask = snapshot.asTask,
                payloadAsTaskSeed = snapshot.asTask,
                syncNonce = syncNonce + 1
            )
        } else {
            this
        }

    fun applyLocalChange(value: String, asTask: Boolean = liveDraftAsTask): SharedThreadComposerFlowState =
        copy(liveDraftText = value, liveDraftAsTask = asTask)
}

@Serializable
data class SharedThreadComposerAttachment(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val previewUri: String? = null,
    val status: String = "ready",
    val errorCode: String? = null,
    val fileRef: String? = null,
    val source: String? = null,
    val externalShareToken: String? = null,
    val externalShareAccountId: String? = null,
    val externalShareServerId: String? = null,
    /**
     * The upload meter's state (owned by the upload attempt in the Thread page): null until the
     * upload starts sending, Done once the server confirmed it. Not persisted: a restored draft
     * re-uploads.
     */
    @Transient val uploadTransfer: RaftTransferProgress? = null
)

/** Composer UI formatting boundary; raw bytes win over old persisted display labels. */
internal fun RaftPendingAttachmentItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let { RaftFormat.bytes(it) } ?: sizeLabel

internal fun SharedThreadComposerAttachment.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let { RaftFormat.bytes(it) } ?: sizeLabel

data class SharedThreadComposerMemberSuggestion(
    val displayName: String,
    val handle: String
)

data class SharedThreadComposerChannelSuggestion(
    val name: String,
    val subtitle: String? = null
)

internal sealed class RaftComposerSuggestionTrigger {
    data class MemberTrigger(val query: String, val triggerStart: Int) : RaftComposerSuggestionTrigger()
    data class ChannelTrigger(val query: String, val triggerStart: Int) : RaftComposerSuggestionTrigger()
    object None : RaftComposerSuggestionTrigger()
}

internal enum class ThreadComposerSuggestionSelectionKind {
    Member,
    Channel
}

internal data class ThreadComposerSuggestionSelectionContext(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val kind: ThreadComposerSuggestionSelectionKind,
    val triggerStart: Int,
    val query: String
)

internal fun threadComposerSuggestionSelectionContext(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    suggestionsEnabled: Boolean
): ThreadComposerSuggestionSelectionContext? {
    val safeStart = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val safeEnd = maxOf(selectionStart, selectionEnd).coerceIn(safeStart, text.length)
    return when (val trigger = threadComposerSuggestionTrigger(text, safeStart, suggestionsEnabled)) {
        is RaftComposerSuggestionTrigger.MemberTrigger ->
            ThreadComposerSuggestionSelectionContext(
                text = text,
                selectionStart = safeStart,
                selectionEnd = safeEnd,
                kind = ThreadComposerSuggestionSelectionKind.Member,
                triggerStart = trigger.triggerStart,
                query = trigger.query
            )
        is RaftComposerSuggestionTrigger.ChannelTrigger ->
            ThreadComposerSuggestionSelectionContext(
                text = text,
                selectionStart = safeStart,
                selectionEnd = safeEnd,
                kind = ThreadComposerSuggestionSelectionKind.Channel,
                triggerStart = trigger.triggerStart,
                query = trigger.query
            )
        RaftComposerSuggestionTrigger.None -> null
    }
}

/**
 * Composition-local bridge between one stable portal callback and the latest committed composer
 * state. Native editor changes update [draft] synchronously, while [updateComposition] publishes
 * the candidate set and commit edge only after a successful composition. Selection therefore
 * fails closed if the visible presentation belongs to an older text/range or candidate identity.
 */
internal class ThreadComposerSuggestionSelectionDispatcher(
    initialText: String,
    initialSelectionStart: Int,
    initialSelectionEnd: Int,
    initialSuggestionsEnabled: Boolean
) {
    private data class Draft(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private var draft = normalizedDraft(initialText, initialSelectionStart, initialSelectionEnd)
    private var suggestionsEnabled = initialSuggestionsEnabled
    private var memberSuggestions: List<RaftComposerMemberItem> = emptyList()
    private var channelSuggestions: List<RaftComposerChannelItem> = emptyList()
    private var onCommit: (RaftComposerSuggestionEdit) -> Unit = {}

    fun updateDraft(text: String, selectionStart: Int, selectionEnd: Int) {
        draft = normalizedDraft(text, selectionStart, selectionEnd)
    }

    fun updateComposition(
        suggestionsEnabled: Boolean,
        memberSuggestions: List<RaftComposerMemberItem>,
        channelSuggestions: List<RaftComposerChannelItem>,
        onCommit: (RaftComposerSuggestionEdit) -> Unit
    ) {
        this.suggestionsEnabled = suggestionsEnabled
        this.memberSuggestions = memberSuggestions
        this.channelSuggestions = channelSuggestions
        this.onCommit = onCommit
    }

    fun selectMember(
        expectedContext: ThreadComposerSuggestionSelectionContext,
        member: RaftComposerMemberItem
    ): Boolean {
        val currentContext = currentContext() ?: return false
        if (currentContext != expectedContext || currentContext.kind != ThreadComposerSuggestionSelectionKind.Member) {
            return false
        }
        if (memberSuggestions.none { current -> current.hasSameSuggestionIdentity(member) }) return false
        val edit =
            applyRaftComposerSuggestionEdit(
                text = currentContext.text,
                selectionStart = currentContext.selectionStart,
                selectionEnd = currentContext.selectionEnd,
                triggerStart = currentContext.triggerStart,
                replacement = "@${member.handle} "
            ) ?: return false
        onCommit(edit)
        return true
    }

    fun selectChannel(
        expectedContext: ThreadComposerSuggestionSelectionContext,
        channel: RaftComposerChannelItem
    ): Boolean {
        val currentContext = currentContext() ?: return false
        if (currentContext != expectedContext || currentContext.kind != ThreadComposerSuggestionSelectionKind.Channel) {
            return false
        }
        if (channelSuggestions.none { current -> current.name == channel.name }) return false
        val edit =
            applyRaftComposerSuggestionEdit(
                text = currentContext.text,
                selectionStart = currentContext.selectionStart,
                selectionEnd = currentContext.selectionEnd,
                triggerStart = currentContext.triggerStart,
                replacement = "#${channel.name} "
            ) ?: return false
        onCommit(edit)
        return true
    }

    private fun currentContext(): ThreadComposerSuggestionSelectionContext? =
        threadComposerSuggestionSelectionContext(
            text = draft.text,
            selectionStart = draft.selectionStart,
            selectionEnd = draft.selectionEnd,
            suggestionsEnabled = suggestionsEnabled
        )

    private fun normalizedDraft(text: String, selectionStart: Int, selectionEnd: Int): Draft {
        val safeStart = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val safeEnd = maxOf(selectionStart, selectionEnd).coerceIn(safeStart, text.length)
        return Draft(text = text, selectionStart = safeStart, selectionEnd = safeEnd)
    }
}

private fun RaftComposerMemberItem.hasSameSuggestionIdentity(other: RaftComposerMemberItem): Boolean {
    val currentMemberId = memberId?.takeIf { it.isNotBlank() }
    val otherMemberId = other.memberId?.takeIf { it.isNotBlank() }
    return if (currentMemberId != null || otherMemberId != null) {
        currentMemberId != null &&
            currentMemberId == otherMemberId &&
            sourceServerId == other.sourceServerId
    } else {
        handle == other.handle && sourceServerId == other.sourceServerId
    }
}

@Composable
internal fun ThreadComposerScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    var payloadText by remember { mutableStateOf(params.optString("payload")) }
    var instanceId by remember { mutableStateOf(params.optString("instanceId")) }
    var visualFixture by remember { mutableStateOf(params.optString("visualFixture")) }
    val visualMentionDrawerStartsClosed = params.optBoolean("androidVisualMentionDrawerStartsClosed", false)
    androidx.compose.runtime.DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_COMPOSER_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                    eventData.optString("visualFixture").takeIf { it.isNotBlank() }?.let {
                        visualFixture = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payloadTrace =
        rememberThreadComposerPayloadTrace(
            payload = payloadText,
            fallbackPageData = params.toString(),
            instanceId = instanceId,
            visualFixture = visualFixture
        )
    val payload = payloadTrace.payload
    LaunchedEffect(payloadText, instanceId, visualFixture) {
        bridge.log(
            "thread_composer_launch_payload_parse source=${payloadTrace.source} result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    LaunchedEffect(payload.instanceId, payload.syncNonce) {
        bridge.log(
            "thread_composer_render instanceId=${payload.instanceId} syncNonce=${payload.syncNonce} attachments=${payload.pendingAttachments.size} mode=${payload.composerMode}"
        )
    }
    val suggestionPortal = remember(payload.instanceId) { ThreadComposerSuggestionPortalState() }
    var suggestionScrollOracleLeak by remember(payload.instanceId) { mutableStateOf(false) }
    var suggestionInteractionOracleActionReceived by remember(payload.instanceId) { mutableStateOf(false) }
    var suggestionRepositioned by remember(payload.instanceId) { mutableStateOf(false) }
    val suggestionScrollOracleEnabled =
        visualFixture == ThreadComposerSuggestionScrollOracleFixture ||
            visualFixture == ThreadComposerSuggestionRepositionOracleFixture
    val suggestionInteractionOracleEnabled =
        visualFixture == ThreadComposerSuggestionInteractionOracleFixture
    val suggestionUnderlayOracleEnabled =
        suggestionScrollOracleEnabled || suggestionInteractionOracleEnabled
    val suggestionRepositionOracleEnabled = visualFixture == ThreadComposerSuggestionRepositionOracleFixture
    LaunchedEffect(payload.instanceId, suggestionRepositionOracleEnabled) {
        suggestionRepositioned = false
        if (suggestionRepositionOracleEnabled) {
            delay(ThreadComposerSuggestionRepositionDelayMillis)
            suggestionRepositioned = true
        }
    }
    RaftInputFocusDismissHost { focusDismissModifier ->
        Box(
            modifier = focusDismissModifier.fillMaxSize().background(RaftColor.Canvas),
            contentAlignment = Alignment.TopCenter
        ) {
            if (suggestionUnderlayOracleEnabled) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(ThreadComposerVisualInputWidth)
                            .height(ComposerSuggestionPanelMaxHeight)
                            .background(ThreadComposerSuggestionScrollOracleUnderlay)
                            .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    RaftMarkdownBody(
                        body = "[UNDERLYING MESSAGE](https://raft.test/composer-suggestion-scroll-oracle)",
                        textStyle = RaftType.Body.withRaftFontWeight(FontWeight.Bold),
                        onOpenUrl = { suggestionScrollOracleLeak = true }
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(
                                when {
                                    suggestionScrollOracleLeak -> ThreadComposerSuggestionScrollOracleLeak
                                    suggestionInteractionOracleActionReceived ->
                                        ThreadComposerSuggestionInteractionOracleSuccess
                                    else -> RaftColor.Canvas
                                }
                            ),
                    contentAlignment = Alignment.Center
                ) {
                    if (suggestionInteractionOracleActionReceived) {
                        Text(
                            text = ThreadComposerSuggestionInteractionOracleMarker,
                            style = RaftType.Caption,
                            color = RaftColor.TextBlack
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides suggestionPortal) {
                Box(
                    modifier =
                        Modifier
                            .width(ThreadComposerVisualComponentWidth)
                            .heightIn(min = ThreadComposerVisualComponentMinHeight)
                            .offset(
                                x = 0f,
                                y =
                                    when {
                                        suggestionRepositionOracleEnabled && suggestionRepositioned ->
                                            ThreadComposerVisualSuggestionRepositionOffset
                                        payload.suggestionsOpen -> ThreadComposerVisualSuggestionAnchorOffset
                                        else -> 0f
                                    }
                            )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .width(ThreadComposerVisualInputWidth)
                            .offset(x = 0f, y = ThreadComposerVisualInputTop),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ThreadComposerContent(
                            payload = payload,
                            initialMentionActionDetailsVisible =
                                !visualMentionDrawerStartsClosed &&
                                    (visualFixture == ThreadComposerMentionBatchDetailsVisualFixture ||
                                        visualFixture == "pending_mention_actions_batch_partial"),
                            attachmentPreviewEnabled = false,
                            onEvent = { action, _, _, attachmentId ->
                                if (
                                    suggestionInteractionOracleEnabled &&
                                    action == "attachImage"
                                ) {
                                    suggestionInteractionOracleActionReceived = true
                                }
                                bridge.log(
                                    "thread_composer_visual_event instanceId=${payload.instanceId} action=$action attachmentId=${attachmentId.orEmpty()}"
                                )
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = ThreadComposerStandaloneBottomPadding
                        )
                    }
                }
            }
            ThreadComposerSuggestionPortalHost(suggestionPortal)
        }
    }
}

@Composable
private fun rememberThreadComposerPayloadTrace(
    payload: String,
    fallbackPageData: String,
    instanceId: String,
    visualFixture: String
): ThreadComposerPayloadParseTrace =
    remember(payload, fallbackPageData, instanceId, visualFixture) {
        visualFixture.takeIf { it.isNotBlank() }?.let { fixture ->
            ThreadComposerPayloadParseTrace(
                payload = threadComposerVisualFixture(fixture, instanceId),
                durationMs = 0,
                responseBytes = fixture.encodeToByteArray().size,
                result = "visual_fixture",
                source = "visualFixture"
            )
        } ?: parseThreadComposerPayloadWithTrace(
            payload = payload,
            fallbackPageData = fallbackPageData,
            instanceId = instanceId
        )
    }

internal fun parseThreadComposerPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): RaftThreadComposerPayload {
    val primaryPayload = payload.trim()
    if (primaryPayload.isEmpty()) {
        return parseThreadComposerLaunchPayload(
            source = fallbackPageData,
            instanceId = instanceId,
            rejectAsFailure = true
        )
    }
    return parseThreadComposerLaunchPayload(
        source = primaryPayload,
        instanceId = instanceId,
        rejectAsFailure = true
    )
}

private fun parseThreadComposerPayloadWithTrace(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): ThreadComposerPayloadParseTrace {
    val startedAt = TimeSource.Monotonic.markNow()
    val primaryPayload = payload.trim()
    val source = if (primaryPayload.isEmpty()) fallbackPageData else primaryPayload
    val parsed = parseThreadComposerPayload(payload, fallbackPageData, instanceId)
    return ThreadComposerPayloadParseTrace(
        payload = parsed,
        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        responseBytes = source.encodeToByteArray().size,
        result = if (parsed.composerMode == "failed") "failure" else "success",
        source = if (primaryPayload.isEmpty()) "fallbackPageData" else "payload"
    )
}

private data class ThreadComposerPayloadParseTrace(
    val payload: RaftThreadComposerPayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String,
    val source: String
)

private fun failedThreadComposerPayload(instanceId: String): RaftThreadComposerPayload =
    RaftThreadComposerPayload(
        instanceId = instanceId,
        composerMode = "failed",
        statusLabel = "Composer payload parse failed"
    )

private fun String.hasThreadComposerContentKeys(): Boolean =
    runCatching {
        val element = threadComposerJson.parseToJsonElement(this)
        (element as? JsonObject)?.keys.orEmpty().any { key -> key in threadComposerContentPayloadKeys }
    }.getOrDefault(false)

private fun parseThreadComposerLaunchPayload(
    source: String,
    instanceId: String,
    rejectAsFailure: Boolean
): RaftThreadComposerPayload {
    val launchSource = source.trim().takeIf { it.isNotEmpty() } ?: "{}"
    if (launchSource.hasThreadComposerContentKeys()) {
        return if (rejectAsFailure) {
            failedThreadComposerPayload(instanceId)
        } else {
            RaftThreadComposerPayload(instanceId = instanceId)
        }
    }
    return runCatching {
        threadComposerJson
            .decodeFromString(WireThreadComposerLaunchPayload.serializer(), launchSource)
            .toThreadComposerContentPayload(instanceId)
    }.getOrElse {
        if (rejectAsFailure) {
            failedThreadComposerPayload(instanceId)
        } else {
            RaftThreadComposerPayload(instanceId = instanceId)
        }
    }
}

private fun threadComposerVisualFixture(
    fixture: String,
    instanceId: String
): RaftThreadComposerPayload {
    val resolvedInstanceId = instanceId.ifBlank { "visual-thread-composer" }
    val base =
        RaftThreadComposerPayload(
            instanceId = resolvedInstanceId,
            // components.thread.composer.states draft: web's `fxMessages.composerSeed`
            // (VisualFixtureData.Messages.COMPOSER_SEED).
            seedText = "Review the Android composer crop before release.",
            syncNonce = 1,
            focusRequestNonce = 0,
            placeholder = "Message design",
            memberSuggestions =
                listOf(
                    RaftComposerMemberItem(
                        displayName = "Cindy",
                        handle = "Cindy",
                        inMentionChannel = true,
                        kind = MemberKind.Agent,
                        description = "Keeps visual testing release notes aligned.",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "C"
                    ),
                    RaftComposerMemberItem(
                        displayName = "Product UX Designer",
                        handle = "Product-UX-Designer",
                        inMentionChannel = true,
                        kind = MemberKind.Agent,
                        description = "Turns screenshots into actionable UI specs.",
                        avatarWireValue = "pixel:paint",
                        avatarLabel = "P"
                    ),
                    RaftComposerMemberItem(
                        displayName = "Android Developer",
                        handle = "Android-Developer-4",
                        inMentionChannel = false,
                        kind = MemberKind.Agent,
                        description = "Implements shared Android/KMP UI fixes.",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "A"
                    )
                ),
            channelSuggestions =
                listOf(
                    RaftComposerChannelItem(name = "design", subtitle = "Product and UI decisions"),
                    RaftComposerChannelItem(name = "product", subtitle = "Release tracking and acceptance"),
                    RaftComposerChannelItem(name = "android-artifacts", subtitle = "Builds and screenshots")
                )
        )
    return when (fixture) {
        "empty" -> base.copy(seedText = "")
        "as_task_selected" -> base.copy(seedAsTask = true)
        "pending_mention_actions" ->
            base.copy(
                seedText = "",
                pendingMentionActions =
                    listOf(
                        RaftPendingMentionActionItem(
                            resolutionId = "visual-pending-mention-resolution",
                            messageId = "visual-pending-mention-message",
                            channelId = "visual-design",
                            channelName = "design",
                            targetType = "agent",
                            targetHandle = "Android-Developer-4",
                            targetAvatarUrl = "pixel:robot",
                            reason = "target_not_in_channel",
                            availableActions = listOf("add", "notify")
                        )
                    )
            )
        "pending_mention_actions_batch",
        ThreadComposerMentionBatchDetailsVisualFixture,
        "pending_mention_actions_batch_executing",
        "pending_mention_actions_batch_partial" -> {
            val actions =
                listOf(
                    RaftPendingMentionActionItem(
                        resolutionId = "visual-pending-mention-alex",
                        messageId = "visual-pending-mention-message",
                        channelId = "visual-design",
                        channelName = "design",
                        targetType = "user",
                        targetHandle = "Alex",
                        targetAvatarUrl = "pixel:human",
                        reason = "target_not_in_channel",
                        availableActions = listOf("add", "notify")
                    ),
                    RaftPendingMentionActionItem(
                        resolutionId = "visual-pending-mention-bryn",
                        messageId = "visual-pending-mention-message",
                        channelId = "visual-design",
                        channelName = "design",
                        targetType = "agent",
                        targetHandle = "Bryn",
                        targetAvatarUrl = "pixel:robot",
                        reason = "target_not_in_channel",
                        availableActions = listOf("add", "notify")
                    ),
                    RaftPendingMentionActionItem(
                        resolutionId = "visual-pending-mention-casey",
                        messageId = "visual-pending-mention-message",
                        channelId = "visual-design",
                        channelName = "design",
                        targetType = "user",
                        targetHandle = "Casey",
                        targetAvatarUrl = "pixel:human",
                        reason = "target_not_in_channel",
                        availableActions = listOf("add", "notify")
                    )
                )
            when (fixture) {
                "pending_mention_actions_batch_executing" ->
                    base.copy(
                        seedText = "",
                        pendingMentionActions = actions,
                        executingMentionActionIds = actions.map { it.resolutionId },
                        executingMentionActionStates = actions.associate { it.resolutionId to "added" }
                    )
                "pending_mention_actions_batch_partial" ->
                    base.copy(
                        seedText = "",
                        pendingMentionActions = actions.drop(1),
                        mentionActionNoticeTitle = ThreadComposerMentionBatchPartialNoticeTitleVisualMarker,
                        mentionActionNotice = ThreadComposerMentionBatchPartialNoticeVisualMarker
                    )
                else -> base.copy(seedText = "", pendingMentionActions = actions)
            }
        }
        "member_suggestions" -> base.copy(seedText = "@", suggestionsOpen = true)
        ThreadComposerSuggestionInteractionOracleFixture ->
            base.copy(seedText = "#", suggestionsOpen = true)
        ThreadComposerSuggestionScrollOracleFixture,
        ThreadComposerSuggestionRepositionOracleFixture ->
            base.copy(
                seedText = "@",
                suggestionsOpen = true,
                memberSuggestions =
                    (1..14).map { index ->
                        RaftComposerMemberItem(
                            displayName = "Scrollable candidate $index",
                            handle = "scroll-candidate-$index",
                            inMentionChannel = true,
                            kind = MemberKind.Agent,
                            description = "Suggestion row $index",
                            avatarWireValue = "pixel:robot"
                        )
                    }
            )
        "channel_suggestions" -> base.copy(seedText = "#", suggestionsOpen = true)
        "image_preview" ->
            base.copy(
                seedText = "Uploading the design crop for review.",
                pendingAttachments =
                    listOf(
                        RaftPendingAttachmentItem(
                            id = "visual-att-image-preview",
                            fileName = "composer-preview.png",
                            sizeLabel = "128 KB",
                            mimeType = "image/png",
                            previewUri = ThreadComposerVisualPreviewUri,
                            status = "ready"
                        )
                    )
            )
        "file_previews" ->
            base.copy(
                seedText = "Attaching build artifacts and logs.",
                pendingAttachments =
                    listOf(
                        RaftPendingAttachmentItem(
                            id = "visual-att-report",
                            fileName = "release-notes.pdf",
                            sizeLabel = "428 KB",
                            mimeType = "application/pdf",
                            status = "ready"
                        ),
                        RaftPendingAttachmentItem(
                            id = "picked-logs",
                            fileName = "android-smoke-log.zip",
                            sizeLabel = "1.8 MB",
                            mimeType = "application/zip",
                            status = "uploading",
                            uploadTransfer = RaftTransferMeter(bytesTotal = 100L).bytes(64L)
                        ),
                        RaftPendingAttachmentItem(
                            id = "picked-trace",
                            fileName = "oversized-trace.jsonl",
                            sizeLabel = "8.4 MB",
                            mimeType = "application/json",
                            status = "failed",
                            errorCode = "attachment_too_large"
                        )
                    )
            )
        else -> base
    }
}

private fun WireThreadComposerLaunchPayload.toThreadComposerContentPayload(
    instanceIdFallback: String
): RaftThreadComposerPayload =
    RaftThreadComposerPayload(
        instanceId = instanceId.ifBlank { instanceIdFallback },
        seedText = seedText,
        seedAsTask = seedAsTask,
        syncNonce = syncNonce,
        focusRequestNonce = focusRequestNonce,
        surfaceKind = surfaceKind,
        mentionChannelId = mentionChannelId,
        parentMessageId = parentMessageId,
        streamChannelId = streamChannelId
    )

internal fun RaftThreadComposerPayload.isDefaultThreadComposer(): Boolean =
    this == RaftThreadComposerPayload()

internal fun RaftThreadComposerPayload.toThreadComposerSurface(
    channelId: String,
    parentChannelId: String? = null,
    mentionChannelLabel: String? = null,
    memberSuggestionHeadersVisible: Boolean = surfaceKind != RaftThreadSurfaceKind.DirectMessage
): ThreadComposerSurface =
    ThreadComposerSurface(
        kind = surfaceKind,
        channelId = channelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        mentionChannelId = mentionChannelId,
        mentionChannelLabel = mentionChannelLabel,
        memberSuggestionHeadersVisible = memberSuggestionHeadersVisible,
        showTaskButton = showAsTaskToggle
    )

internal fun RaftThreadComposerPayload.toThreadComposerUiState(): ThreadComposerUiState =
    ThreadComposerUiState(
        instanceId = instanceId,
        seedText = seedText,
        seedAsTask = seedAsTask,
        syncNonce = syncNonce,
        editorCommand = editorCommand,
        focusRequestNonce = focusRequestNonce,
        placeholder = placeholder,
        enabled = enabled,
        attachmentActionsEnabled = attachmentActionsEnabled,
        originalImageOptionEnabled = originalImageOptionEnabled,
        originalImage = originalImage,
        composerMode = composerMode,
        statusLabel = statusLabel,
        pendingAttachments = pendingAttachments,
        pendingMentionActions = pendingMentionActions,
        mentionActionNoticeTitle = mentionActionNoticeTitle,
        mentionActionNotice = mentionActionNotice,
        mentionActionStates = mentionActionStates,
        removingMentionActionIds = removingMentionActionIds,
        executingMentionActionIds = executingMentionActionIds,
        executingMentionActionStates = executingMentionActionStates,
        memberSuggestions = memberSuggestions,
        channelSuggestions = channelSuggestions,
        suggestionsOpen = suggestionsOpen,
        hookLabels = hookLabels
    )

internal fun threadComposerHostKeyboardLiftBottomPadding(
    keyboardInsetDp: Float,
    bottomContentPadding: Dp
): Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    return if (keyboardInsetDp > 0f) {
        ThreadComposerHostKeyboardBasePadding + keyboardInset
    } else {
        bottomContentPadding
    }
}

internal fun RaftThreadScreenPayload.resolveThreadComposer(displayName: String = title): RaftThreadComposerPayload {
    val resolvedSurfaceKind =
        if (composer.surfaceKind == RaftThreadSurfaceKind.Channel && surfaceKind != RaftThreadSurfaceKind.Channel) {
            surfaceKind
        } else {
            composer.surfaceKind
        }
    val sourceStreamCandidate =
        sourceThreadId?.takeUnless {
            surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                parentChannelId != null &&
                it == parentChannelId
        }
    // Phase 3 #3 #3 (#280): replace composer > screen > origin 3-level ?:` chains with
    // explicit per-source resolution. Composer-scope value wins; if absent, screen-scope
    // (from the parent RaftThreadScreenPayload) wins; if absent, fall back to the
    // origin hint only when the surface is not ReplyThread (ReplyThread must use the
    // parent channel id, not the source thread id).
    val resolvedStreamChannelId = resolveComposerStreamChannelId(
        composerValue = composer.streamChannelId,
        screenValue = streamChannelId,
        originCandidate = sourceStreamCandidate,
        surfaceKind = resolvedSurfaceKind
    )
    val resolvedParentMessageId = resolveComposerParentMessageId(
        composerValue = composer.parentMessageId,
        screenValue = parentMessageId,
        originValue = originParentMessageId,
        surfaceKind = resolvedSurfaceKind
    )
    val resolvedMentionChannelId = composer.mentionChannelId ?: parentChannelId
    val resolvedPlaceholder =
        if (composer.placeholder == "Message") {
            threadSurfaceComposerPlaceholder(
                surfaceKind = resolvedSurfaceKind,
                displayName = displayName,
                fallbackId = sourceThreadId ?: instanceId,
                channelKind = channelKind
            )
        } else {
            composer.placeholder
        }
    return composer.copy(
        instanceId = composer.instanceId.ifBlank { instanceId },
        placeholder = resolvedPlaceholder,
        enabled = canSend && composer.enabled,
        showAsTaskToggle = composer.showAsTaskToggle && resolvedSurfaceKind != RaftThreadSurfaceKind.ReplyThread,
        surfaceKind = resolvedSurfaceKind,
        mentionChannelId = resolvedMentionChannelId,
        parentMessageId = resolvedParentMessageId,
        streamChannelId = resolvedStreamChannelId
    )
}

/**
 * Per-surface composer stream channel resolver. Replaces the prior 3-level
 * \`composer.streamChannelId ?: streamChannelId ?: sourceStreamCandidate\` (反例 8).
 *
 * - Composer value always wins (UI composer can override).
 * - ReplyThread surfaces fall back to screen value only; origin hint is rejected
 *   (origin is the parent channel id, not the thread stream id).
 * - Other surfaces fall back to screen value, then to origin candidate.
 */
private fun resolveComposerStreamChannelId(
    composerValue: String?,
    screenValue: String?,
    originCandidate: String?,
    surfaceKind: String
): String? = when {
    !composerValue.isNullOrBlank() -> composerValue
    surfaceKind == RaftThreadSurfaceKind.ReplyThread -> screenValue?.takeIf { it.isNotBlank() }
    !screenValue.isNullOrBlank() -> screenValue
    else -> originCandidate?.takeIf { it.isNotBlank() }
}

/**
 * Per-surface composer parent message resolver. Replaces the prior 3-level
 * \`composer.parentMessageId ?: parentMessageId ?: originParentMessageId\` (反例 9).
 *
 * Composer value always wins; otherwise falls back to screen value, then origin value.
 * No surface-kind branching needed because parent message id is always the same kind
 * across all surfaces (the message the thread is anchored to).
 */
private fun resolveComposerParentMessageId(
    composerValue: String?,
    screenValue: String?,
    originValue: String?,
    surfaceKind: String
): String? = when {
    !composerValue.isNullOrBlank() -> composerValue
    !screenValue.isNullOrBlank() -> screenValue
    else -> originValue?.takeIf { it.isNotBlank() }
}

internal enum class ThreadComposerSuggestionDemand {
    None,
    Members,
    Channels
}

@Composable
internal fun ThreadComposerContent(
    payload: RaftThreadComposerPayload,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onSendRequest: ((ThreadComposerSendRequest) -> ThreadComposerSendAdmission)? = null,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding,
    hostPaddingHorizontal: Dp = 0.dp,
    hostPaddingTop: Dp = 0.dp,
    keyboardLiftBottomContentPadding: Dp? = null,
    composerSendKeyEnabled: Boolean = false,
    dismissKeyboardOnBlur: Boolean = false,
    attachmentPreviewEnabled: Boolean,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    initialMentionActionDetailsVisible: Boolean = false,
    /**
     * Web MessageInput `frameEdge`: content on the composer frame's top edge, composed in the
     * frame's own box after the frame, given whether the composer holds text or an attachment
     * and whether its editor is focused (the assistant's otter, [build.raft.app.assistant.ThreadAssistantOtterFrameEdge]).
     */
    frameEdge: (@Composable (composing: Boolean, focused: Boolean) -> Unit)? = null,
) {
    ThreadComposerContent(
        surface =
            payload.toThreadComposerSurface(
                channelId = payload.streamChannelId?.takeIf { it.isNotBlank() } ?: payload.instanceId
            ),
        state = payload.toThreadComposerUiState(),
        onEvent = onEvent,
        onSendRequest = onSendRequest,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom,
        hostPaddingHorizontal = hostPaddingHorizontal,
        hostPaddingTop = hostPaddingTop,
        keyboardLiftBottomContentPadding = keyboardLiftBottomContentPadding,
        composerSendKeyEnabled = composerSendKeyEnabled,
        dismissKeyboardOnBlur = dismissKeyboardOnBlur,
        attachmentPreviewEnabled = attachmentPreviewEnabled,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        onSuggestionDemandChanged = onSuggestionDemandChanged,
        initialMentionActionDetailsVisible = initialMentionActionDetailsVisible,
        frameEdge = frameEdge,
    )
}

@Composable
internal fun ThreadComposerContent(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onSendRequest: ((ThreadComposerSendRequest) -> ThreadComposerSendAdmission)? = null,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding,
    hostPaddingHorizontal: Dp = 0.dp,
    hostPaddingTop: Dp = 0.dp,
    keyboardLiftBottomContentPadding: Dp? = null,
    composerSendKeyEnabled: Boolean = false,
    dismissKeyboardOnBlur: Boolean = false,
    attachmentPreviewEnabled: Boolean,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    initialMentionActionDetailsVisible: Boolean = false,
    /**
     * A4 adoption (task #18): lets the host page share the native-editing reader bound to this
     * composer's editor so a migration can read the latest value without a second UIKit walk.
     */
    externalNativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
    /**
     * Web MessageInput `frameEdge`: content on the composer frame's top edge, composed in the
     * frame's own box after the frame, given whether the composer holds text or an attachment
     * and whether its editor is focused (the assistant's otter, [build.raft.app.assistant.ThreadAssistantOtterFrameEdge]).
     */
    frameEdge: (@Composable (composing: Boolean, focused: Boolean) -> Unit)? = null,
) {
    val editorOwner = remember(state.instanceId) {
        ThreadComposerControlledEditorOwner(state.seedText, state.seedAsTask)
    }
    LaunchedEffect(state.instanceId, state.editorCommand?.id) {
        val command = state.editorCommand ?: return@LaunchedEffect
        if (editorOwner.apply(command) == ThreadComposerEditorCommandOutcome.Stale) {
            onEvent("staleEditorCommandIgnored", editorOwner.value.text, editorOwner.asTask, null)
        }
    }
    ThreadComposerContentLeaf(
        surface = surface,
        state = state,
        editorSessionId = editorOwner.editorSessionId,
        editorValue = editorOwner.value,
        onEditorValueChange = editorOwner::acceptEditorValue,
        onNativeEditorValueChange = editorOwner::acceptNativeEditorValue,
        isEditorSessionActive = editorOwner::isActiveSession,
        editorAsTask = editorOwner.asTask,
        onEditorAsTaskChange = editorOwner::acceptAsTask,
        onEvent = onEvent,
        onSendRequest = onSendRequest,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom,
        hostPaddingHorizontal = hostPaddingHorizontal,
        hostPaddingTop = hostPaddingTop,
        keyboardLiftBottomContentPadding = keyboardLiftBottomContentPadding,
        composerSendKeyEnabled = composerSendKeyEnabled,
        dismissKeyboardOnBlur = dismissKeyboardOnBlur,
        attachmentPreviewEnabled = attachmentPreviewEnabled,
        agentStatusResolver = agentStatusResolver,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        onSuggestionDemandChanged = onSuggestionDemandChanged,
        initialMentionActionDetailsVisible = initialMentionActionDetailsVisible,
        externalNativeEditingStateReader = externalNativeEditingStateReader,
        frameEdge = frameEdge,
    )
}

@Composable
internal fun ThreadComposerContentLeaf(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    editorSessionId: Long,
    editorValue: TextFieldValue,
    onEditorValueChange: (Long, TextFieldValue) -> Boolean,
    onNativeEditorValueChange: (Long, TextFieldValue) -> TextFieldValue?,
    isEditorSessionActive: (Long) -> Boolean,
    editorAsTask: Boolean,
    onEditorAsTaskChange: (Long, Boolean) -> Boolean,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onSendRequest: ((ThreadComposerSendRequest) -> ThreadComposerSendAdmission)? = null,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding,
    hostPaddingHorizontal: Dp = 0.dp,
    hostPaddingTop: Dp = 0.dp,
    keyboardLiftBottomContentPadding: Dp? = null,
    composerSendKeyEnabled: Boolean = false,
    dismissKeyboardOnBlur: Boolean = false,
    attachmentPreviewEnabled: Boolean,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    initialMentionActionDetailsVisible: Boolean = false,
    externalNativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
    frameEdge: (@Composable (composing: Boolean, focused: Boolean) -> Unit)? = null,
) {
    // #192 trace scope extension: wrap the full composer render. IME-lift / state
    // updates / suggestion list filtering are all under this; per-phase breakdown
    // can come later if this marker shows hot. bodyLength proxy uses seedText length.
    traceMarkdownSection(RaftComposerTraceNames.COMPOSER_RENDER, state.seedText.length) {
    val fieldValue = editorValue
    val text = fieldValue.text
    // Task v2: never sends as a task, whatever a restored draft held.
    val asTask = editorAsTask && surface.sendsAsTask
    val ownedNativeEditingStateReader =
        remember(state.instanceId, editorSessionId) { RaftTextFieldNativeEditingStateReader() }
    val nativeEditingStateReader = externalNativeEditingStateReader ?: ownedNativeEditingStateReader
    val sendSnapshotGate =
        remember(state.instanceId, editorSessionId) { ThreadComposerSendSnapshotGate() }
    val submittedEchoFence = remember(state.instanceId) { ThreadComposerSubmittedEchoFence() }
    val currentOnEvent by rememberUpdatedState(onEvent)
    var focused by remember(state.instanceId) { mutableStateOf(state.suggestionsOpen) }
    var handledFocusRequestNonce by remember(state.instanceId) { mutableStateOf(state.focusRequestNonce) }
    var handledEditorSessionId by remember(state.instanceId) { mutableStateOf(editorSessionId) }
    var keyboardInsetDp by remember(state.instanceId) { mutableStateOf(0f) }
    // Keep the native editor identity stable while a send clears the controlled value. The
    // logical session still fences stale callbacks below, but recreating the FocusRequester makes
    // the host text field lose first-responder status before the restore effect can run.
    val composerFocusRequester = remember(state.instanceId) { FocusRequester() }
    val focusOwnerRegistry = LocalRaftInputFocusOwnerRegistry.current
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val suggestionPortal = LocalThreadComposerSuggestionPortal.current
    val suggestionPortalOwner = remember(state.instanceId) { ThreadComposerSuggestionPortalOwner() }
    var suggestionPortalGeneration by remember(state.instanceId, suggestionPortalOwner) { mutableStateOf(0L) }
    val suggestionSelectionDispatcher =
        remember(state.instanceId) {
            ThreadComposerSuggestionSelectionDispatcher(
                initialText = state.seedText,
                initialSelectionStart = state.seedText.length,
                initialSelectionEnd = state.seedText.length,
                initialSuggestionsEnabled = state.suggestionsEnabled
            )
        }
    val pendingSendSnapshotToken = sendSnapshotGate.pendingToken
    LaunchedEffect(state.instanceId, editorSessionId, pendingSendSnapshotToken) {
        val requestToken = pendingSendSnapshotToken ?: return@LaunchedEffect
        delay(ThreadComposerNativeSnapshotTimeoutMillis)
        if (sendSnapshotGate.finish(requestToken)) {
            currentOnEvent("sendSnapshotUnavailable", "timeout", null, null)
        }
    }
    SideEffect {
        suggestionSelectionDispatcher.updateDraft(
            text = fieldValue.text,
            selectionStart = fieldValue.selection.start,
            selectionEnd = fieldValue.selection.end,
        )
    }
    val suggestionAnchorRef = remember(state.instanceId) { RaftLiveRootAnchorRef(suggestionPortalOwner) }
    val suggestionAnchorResolver =
        remember(state.instanceId, suggestionAnchorRef) {
            suggestionAnchorRef.resolver(RaftPopoverLocalPoint.Exact(Offset.Zero))
        }
    var composerCardSize by remember(state.instanceId) { mutableStateOf(IntSize.Zero) }
    val shouldAutoFocus = state.focusRequestNonce > handledFocusRequestNonce
    LaunchedEffect(state.instanceId, editorSessionId, state.focusRequestNonce) {
        val explicitFocusRequest = state.focusRequestNonce > handledFocusRequestNonce
        val editorSessionReplaced = editorSessionId != handledEditorSessionId
        val focusRestorePlan =
            threadComposerSessionFocusRestorePlan(
                explicitFocusRequest = explicitFocusRequest,
                editorSessionReplaced = editorSessionReplaced,
                editorFocused = focused,
                keyboardInsetDp = keyboardInsetDp,
            )
        handledEditorSessionId = editorSessionId
        if (explicitFocusRequest) {
            handledFocusRequestNonce = state.focusRequestNonce
        }
        if (focusRestorePlan.requestFocus) {
            suggestionPortalGeneration =
                resumeThreadComposerSuggestionsForEditing(
                    portalState = suggestionPortal,
                    owner = suggestionPortalOwner,
                    generation = suggestionPortalGeneration,
                    onFocusedChanged = { focused = it }
                )
            composerFocusRequester.requestFocus()
            // Session retirement transfers the focused editor without overriding a newer user
            // decision to hide the IME. Only preserve a keyboard that is still visibly open at
            // handoff; an explicit focus request continues to restore both focus and IME.
            if (focusRestorePlan.showKeyboard) {
                softwareKeyboardController?.show()
            }
        }
    }
    val selectionStart = minOf(fieldValue.selection.start, fieldValue.selection.end).coerceIn(0, text.length)
    val selectionEnd = maxOf(fieldValue.selection.start, fieldValue.selection.end).coerceIn(0, text.length)
    val suggestionTrigger =
        remember(text, selectionStart, state.suggestionsEnabled) {
            threadComposerSuggestionTrigger(
                text = text,
                cursor = selectionStart,
                suggestionsEnabled = state.suggestionsEnabled
            )
        }
    val suggestionDemand =
        when (suggestionTrigger) {
            is RaftComposerSuggestionTrigger.MemberTrigger -> ThreadComposerSuggestionDemand.Members
            is RaftComposerSuggestionTrigger.ChannelTrigger -> ThreadComposerSuggestionDemand.Channels
            RaftComposerSuggestionTrigger.None -> ThreadComposerSuggestionDemand.None
        }
    val latestSuggestionDemandChanged by rememberUpdatedState(onSuggestionDemandChanged)
    LaunchedEffect(state.instanceId, suggestionDemand) {
        latestSuggestionDemandChanged(suggestionDemand)
    }
    DisposableEffect(state.instanceId) {
        onDispose { latestSuggestionDemandChanged(ThreadComposerSuggestionDemand.None) }
    }
    var indexedMemberSuggestions by remember(state.instanceId) {
        mutableStateOf<ThreadComposerIndexedSuggestions<RaftComposerMemberItem>?>(null)
    }
    var indexedChannelSuggestions by remember(state.instanceId) {
        mutableStateOf<ThreadComposerIndexedSuggestions<RaftComposerChannelItem>?>(null)
    }
    LaunchedEffect(state.instanceId, state.memberSuggestions) {
        val source = state.memberSuggestions
        val entries = withContext(Dispatchers.Default) { source.toThreadComposerMemberSuggestionSearchEntries() }
        indexedMemberSuggestions = ThreadComposerIndexedSuggestions(source = source, entries = entries)
    }
    LaunchedEffect(state.instanceId, state.channelSuggestions) {
        val source = state.channelSuggestions
        val entries = withContext(Dispatchers.Default) { source.toThreadComposerChannelSuggestionSearchEntries() }
        indexedChannelSuggestions = ThreadComposerIndexedSuggestions(source = source, entries = entries)
    }
    val memberRankingRequest =
        remember(suggestionTrigger, indexedMemberSuggestions, state.memberSuggestions) {
            (suggestionTrigger as? RaftComposerSuggestionTrigger.MemberTrigger)
                ?.takeIf { trigger -> trigger.query.isNotBlank() }
                ?.let { trigger ->
                    indexedMemberSuggestions
                        ?.takeIf { indexed -> indexed.matches(state.memberSuggestions) }
                        ?.let { indexed -> ThreadComposerSuggestionRankingRequest(trigger.query, indexed.entries) }
                }
        }
    val channelRankingRequest =
        remember(suggestionTrigger, indexedChannelSuggestions, state.channelSuggestions) {
            (suggestionTrigger as? RaftComposerSuggestionTrigger.ChannelTrigger)
                ?.takeIf { trigger -> trigger.query.isNotBlank() }
                ?.let { trigger ->
                    indexedChannelSuggestions
                        ?.takeIf { indexed -> indexed.matches(state.channelSuggestions) }
                        ?.let { indexed -> ThreadComposerSuggestionRankingRequest(trigger.query, indexed.entries) }
                }
        }
    var memberRankingResult by remember(state.instanceId) {
        mutableStateOf<ThreadComposerSuggestionRankingResult<RaftComposerMemberItem>?>(null)
    }
    var channelRankingResult by remember(state.instanceId) {
        mutableStateOf<ThreadComposerSuggestionRankingResult<RaftComposerChannelItem>?>(null)
    }
    LaunchedEffect(state.instanceId, memberRankingRequest) {
        if (memberRankingRequest == null) {
            memberRankingResult = null
        } else {
            delay(ThreadComposerSuggestionQueryDebounceMillis)
            val suggestions =
                withContext(Dispatchers.Default) {
                    rankThreadComposerMemberSuggestionEntriesCancellable(
                        query = memberRankingRequest.query,
                        entries = memberRankingRequest.entries
                    )
                }
            memberRankingResult = ThreadComposerSuggestionRankingResult(memberRankingRequest, suggestions)
        }
    }
    LaunchedEffect(state.instanceId, channelRankingRequest) {
        if (channelRankingRequest == null) {
            channelRankingResult = null
        } else {
            delay(ThreadComposerSuggestionQueryDebounceMillis)
            val suggestions =
                withContext(Dispatchers.Default) {
                    rankThreadComposerChannelSuggestionEntriesCancellable(
                        query = channelRankingRequest.query,
                        entries = channelRankingRequest.entries
                    )
                }
            channelRankingResult = ThreadComposerSuggestionRankingResult(channelRankingRequest, suggestions)
        }
    }
    val memberRankingReady =
        memberRankingRequest != null && memberRankingResult?.request === memberRankingRequest
    val channelRankingReady =
        channelRankingRequest != null && channelRankingResult?.request === channelRankingRequest
    val immediateMemberSuggestions =
        (suggestionTrigger as? RaftComposerSuggestionTrigger.MemberTrigger)
            ?.let { trigger -> immediateThreadComposerMemberSuggestions(trigger.query, state.memberSuggestions) }
            .orEmpty()
    val immediateChannelSuggestions =
        (suggestionTrigger as? RaftComposerSuggestionTrigger.ChannelTrigger)
            ?.let { trigger -> immediateThreadComposerSuggestions(trigger.query, state.channelSuggestions) }
            .orEmpty()
    val memberUsesImmediateSuggestions =
        (suggestionTrigger as? RaftComposerSuggestionTrigger.MemberTrigger)?.query?.isBlank() == true
    val channelUsesImmediateSuggestions =
        (suggestionTrigger as? RaftComposerSuggestionTrigger.ChannelTrigger)?.query?.isBlank() == true
    val filteredMemberSuggestions =
        if (suggestionTrigger is RaftComposerSuggestionTrigger.MemberTrigger) {
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = memberUsesImmediateSuggestions,
                rankingReady = memberRankingReady,
                rankingResultSuggestions = memberRankingResult?.suggestions.orEmpty(),
                immediateSuggestions = immediateMemberSuggestions,
                pendingFallbackSuggestions = immediateThreadComposerMemberSuggestions("", state.memberSuggestions)
            )
        } else {
            emptyList()
        }
    val filteredChannelSuggestions =
        if (suggestionTrigger is RaftComposerSuggestionTrigger.ChannelTrigger) {
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = channelUsesImmediateSuggestions,
                rankingReady = channelRankingReady,
                rankingResultSuggestions = channelRankingResult?.suggestions.orEmpty(),
                immediateSuggestions = immediateChannelSuggestions,
                pendingFallbackSuggestions = immediateThreadComposerSuggestions("", state.channelSuggestions)
            )
        } else {
            emptyList()
        }
    val effectiveMemberSuggestionsStatus =
        threadComposerEffectiveSuggestionStatus(
            triggerActive = suggestionTrigger is RaftComposerSuggestionTrigger.MemberTrigger,
            rankingReady = memberRankingReady,
            usesImmediateSuggestions = memberUsesImmediateSuggestions,
            sourceStatus = state.memberSuggestionsStatus
        )
    val effectiveChannelSuggestionsStatus =
        threadComposerEffectiveSuggestionStatus(
            triggerActive = suggestionTrigger is RaftComposerSuggestionTrigger.ChannelTrigger,
            rankingReady = channelRankingReady,
            usesImmediateSuggestions = channelUsesImmediateSuggestions,
            sourceStatus = state.channelSuggestionsStatus
        )
    val suggestionPanelState =
        threadComposerSuggestionPanelState(
            focused = focused,
            trigger = suggestionTrigger,
            memberCount = filteredMemberSuggestions.size,
            memberStatus = effectiveMemberSuggestionsStatus,
            channelCount = filteredChannelSuggestions.size,
            channelStatus = effectiveChannelSuggestionsStatus
        )
    val suggestionSelectionContext =
        threadComposerSuggestionSelectionContext(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            suggestionsEnabled = state.suggestionsEnabled
        )
    var memberSuggestionSelectedIndex by remember(state.instanceId) { mutableStateOf(0) }
    var channelSuggestionSelectedIndex by remember(state.instanceId) { mutableStateOf(0) }
    LaunchedEffect(suggestionTrigger, suggestionPanelState, filteredMemberSuggestions) {
        memberSuggestionSelectedIndex = 0
    }
    LaunchedEffect(suggestionTrigger, suggestionPanelState, filteredChannelSuggestions) {
        channelSuggestionSelectedIndex = 0
    }
    val suggestionRefreshRequest =
        remember(focused, suggestionTrigger) {
            threadComposerSuggestionRefreshRequest(
                focused = focused,
                trigger = suggestionTrigger
            )
        }
    LaunchedEffect(state.instanceId, suggestionRefreshRequest) {
        when (suggestionRefreshRequest) {
            ThreadComposerSuggestionRefreshRequest.Member -> onEvent("refreshMemberSuggestions", null, null, null)
            ThreadComposerSuggestionRefreshRequest.Channel -> onEvent("refreshChannelSuggestions", null, null, null)
            null -> Unit
        }
    }
    LaunchedEffect(state.instanceId, state.mentionActionNotice) {
        if (!state.mentionActionNotice.isNullOrBlank()) {
            delay(ThreadComposerMentionActionNoticeDurationMillis)
            onEvent("dismissMentionNotice", null, null, null)
        }
    }
    val composerEnabled = state.enabled && surface.sendChannelId != null
    val canSend =
        composerEnabled &&
            !sendSnapshotGate.isPending &&
            state.composerMode != "sending" &&
            state.composerMode != "uploading" &&
            state.composerMode != "no_permission" &&
            !state.pendingAttachments.hasFailedThreadAttachmentUpload() &&
            (text.isNotBlank() || state.pendingAttachments.isNotEmpty())
    val composerUsesImeAction =
        threadComposerUsesImeAction(
            composerSendKeyEnabled = composerSendKeyEnabled,
            panelState = suggestionPanelState,
            memberCount = filteredMemberSuggestions.size,
            channelCount = filteredChannelSuggestions.size
        )
    // None is serialized by the Kuikly iOS bridge as a terminal "done" key. Default keeps the
    // multiline editor's native return/newline affordance when neither send nor a visible
    // suggestion owns Enter.
    val composerKeyboardOptions = KeyboardOptions(imeAction = threadComposerImeAction(composerUsesImeAction))

    fun emitChange(nextText: String = text, nextAsTask: Boolean = asTask) {
        onEvent("change", nextText, nextAsTask, null)
    }

    fun insertMentionAtCursor(handle: String): Boolean {
        val edit = applyRaftComposerDirectMentionInsert(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            handle = handle
        ) ?: return false
        val nextFieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
        if (!onEditorValueChange(editorSessionId, nextFieldValue)) return false
        suggestionSelectionDispatcher.updateDraft(
            text = nextFieldValue.text,
            selectionStart = nextFieldValue.selection.start,
            selectionEnd = nextFieldValue.selection.end
        )
        suggestionPortalGeneration =
            resumeThreadComposerSuggestionsForEditing(
                portalState = suggestionPortal,
                owner = suggestionPortalOwner,
                generation = suggestionPortalGeneration,
                onFocusedChanged = { focused = it }
            )
        emitChange(nextText = edit.text)
        return true
    }

    LaunchedEffect(insertMentionRequest?.nonce) {
        val request = insertMentionRequest ?: return@LaunchedEffect
        if (insertMentionAtCursor(request.handle)) {
            // The avatar long-press starts outside the composer, so the shared root may already
            // have armed an outside-focus dismissal for this pointer sequence. Mention insertion
            // is a newer explicit editing intent; invalidate that old token before refocusing so
            // pointer-up cannot clear the focus and IME that this action just restored.
            restoreComposerInputAfterExternalMention(
                focusOwnerRegistry = focusOwnerRegistry,
                requestFocus = composerFocusRequester::requestFocus,
                showKeyboard = {
                    // Android Back can dismiss the IME while the editor remains focused. A
                    // repeated avatar mention is an explicit input intent, so reassert both
                    // logical focus and the software keyboard instead of relying on a focus
                    // transition that may be a no-op.
                    softwareKeyboardController?.show()
                }
            )
        }
        onInsertMentionConsumed(request)
    }

    fun applyMemberSuggestion(member: RaftComposerMemberItem) {
        val context = suggestionSelectionContext ?: return
        suggestionSelectionDispatcher.selectMember(context, member)
    }

    fun applyChannelSuggestion(channel: RaftComposerChannelItem) {
        val context = suggestionSelectionContext ?: return
        suggestionSelectionDispatcher.selectChannel(context, channel)
    }

    fun commitSuggestionToken() {
        val triggerStart =
            when (val trigger = suggestionTrigger) {
                is RaftComposerSuggestionTrigger.MemberTrigger -> trigger.triggerStart
                is RaftComposerSuggestionTrigger.ChannelTrigger -> trigger.triggerStart
                RaftComposerSuggestionTrigger.None -> return
            }
        commitRaftComposerSuggestionToken(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            triggerStart = triggerStart
        )?.let { edit ->
            val nextFieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
            if (!onEditorValueChange(editorSessionId, nextFieldValue)) return@let
            suggestionSelectionDispatcher.updateDraft(
                text = nextFieldValue.text,
                selectionStart = nextFieldValue.selection.start,
                selectionEnd = nextFieldValue.selection.end
            )
            suggestionPortalGeneration =
                hideThreadComposerSuggestionsForEditingContinuation(
                    portalState = suggestionPortal,
                    owner = suggestionPortalOwner,
                    generation = suggestionPortalGeneration,
                    onFocusedChanged = { focused = it }
                )
            emitChange(nextText = edit.text)
        }
    }

    fun sendComposerMessage() {
        if (sendSnapshotGate.isPending) return
        val sourceSessionId = editorSessionId
        val requestToken = sendSnapshotGate.begin()
        nativeEditingStateReader.readCurrentValue { readResult ->
            if (!sendSnapshotGate.finish(requestToken)) return@readCurrentValue
            if (!isEditorSessionActive(sourceSessionId)) return@readCurrentValue
            val nativeValue =
                when (readResult) {
                    is RaftTextFieldNativeEditingStateReadResult.Available -> readResult.value
                    RaftTextFieldNativeEditingStateReadResult.Unavailable -> {
                        onEvent("sendSnapshotUnavailable", "native_editor_unavailable", null, null)
                        return@readCurrentValue
                    }
                }
            if (!onEditorValueChange(sourceSessionId, nativeValue)) return@readCurrentValue
            suggestionSelectionDispatcher.updateDraft(
                text = nativeValue.text,
                selectionStart = nativeValue.selection.start,
                selectionEnd = nativeValue.selection.end,
            )
            val request =
                ThreadComposerSendRequest(
                    editorSessionId = sourceSessionId,
                    value = nativeValue,
                    asTask = asTask,
                )
            submittedEchoFence.begin(request)
            val admission =
                onSendRequest?.invoke(request)
                    ?: run {
                        onEvent("send", nativeValue.text, asTask, null)
                        ThreadComposerSendAdmission.Rejected
                    }
            submittedEchoFence.finishAdmission(admission)
        }
    }

    fun handleKeyboardSend(fromHardwareEnter: Boolean) {
        when (
            threadComposerEnterAction(
                trigger = suggestionTrigger,
                panelState = suggestionPanelState,
                memberCount = filteredMemberSuggestions.size,
                channelCount = filteredChannelSuggestions.size,
                canSend = canSend,
                isImeComposing = threadComposerEnterDeferredByComposition(fromHardwareEnter, fieldValue.composition)
            )
        ) {
            ThreadComposerEnterAction.AcceptMemberSuggestion ->
                filteredMemberSuggestions
                    .composerMemberSuggestionCandidateAt(
                        selectedIndex = memberSuggestionSelectedIndex,
                        groupByMentionChannel = surface.memberSuggestionHeadersVisible
                    )
                    ?.let(::applyMemberSuggestion)
            ThreadComposerEnterAction.AcceptChannelSuggestion ->
                filteredChannelSuggestions
                    .getOrNull(threadComposerSuggestionSelectedIndex(channelSuggestionSelectedIndex, filteredChannelSuggestions.size))
                    ?.let(::applyChannelSuggestion)
            ThreadComposerEnterAction.CommitSuggestionToken -> commitSuggestionToken()
            ThreadComposerEnterAction.Send -> sendComposerMessage()
            ThreadComposerEnterAction.Noop -> Unit
        }
    }
    val composerKeyboardActions =
        if (composerUsesImeAction) {
            KeyboardActions(onSend = { handleKeyboardSend(fromHardwareEnter = this === RaftHardwareEnterKeyboardActionScope) })
        } else {
            KeyboardActions.Default
        }

    val memberSuggestionTitle = surface.memberSuggestionTitle()
    val memberPanelEntryCount =
        remember(surface.memberSuggestionHeadersVisible, memberSuggestionTitle, filteredMemberSuggestions) {
            if (surface.memberSuggestionHeadersVisible) {
                composerMemberSuggestionPanelEntries(memberSuggestionTitle, filteredMemberSuggestions).entries.size
            } else {
                filteredMemberSuggestions.size
            }
        }
    val suggestionPanelHeight =
        remember(
            suggestionPanelState,
            surface.memberSuggestionHeadersVisible,
            memberPanelEntryCount,
            filteredChannelSuggestions.size
        ) {
            composerSuggestionPanelHeight(
                panelState = suggestionPanelState,
                memberCount = filteredMemberSuggestions.size,
                channelCount = filteredChannelSuggestions.size,
                includeMemberHeader = surface.memberSuggestionHeadersVisible,
                memberPanelEntryCount = memberPanelEntryCount
            )
        }
    val density = LocalDensity.current
    val currentDismissSuggestions by rememberUpdatedState<() -> Unit> {
        dismissThreadComposerSuggestionsForFocusLoss(
            portalState = suggestionPortal,
            owner = suggestionPortalOwner,
            generation = suggestionPortalGeneration,
            onFocusedChanged = { focused = it }
        )
        focusManager.clearFocus(force = true)
    }
    val dismissSuggestionPopover =
        remember(suggestionPortalOwner) { { currentDismissSuggestions() } }
    val selectMemberSuggestion =
        remember(suggestionPortalOwner, suggestionSelectionDispatcher) {
            { context: ThreadComposerSuggestionSelectionContext, member: RaftComposerMemberItem ->
                suggestionSelectionDispatcher.selectMember(context, member)
                Unit
            }
        }
    val selectChannelSuggestion =
        remember(suggestionPortalOwner, suggestionSelectionDispatcher) {
            { context: ThreadComposerSuggestionSelectionContext, channel: RaftComposerChannelItem ->
                suggestionSelectionDispatcher.selectChannel(context, channel)
                Unit
            }
        }
    val suggestionPresentation =
        if (
            suggestionPanelState != ThreadComposerSuggestionPanelState.Hidden &&
            suggestionSelectionContext != null &&
            composerCardSize.width > 0 &&
            composerCardSize.height > 0
        ) {
            ThreadComposerSuggestionPresentation(
                owner = suggestionPortalOwner,
                generation = suggestionPortalGeneration,
                anchorResolver = suggestionAnchorResolver,
                selectionContext = suggestionSelectionContext,
                contentSize =
                    Size(
                        width = composerCardSize.width.toFloat(),
                        height = with(density) { suggestionPanelHeight.toPx() }
                    ),
                panelState = suggestionPanelState,
                surface = surface,
                memberSuggestions = filteredMemberSuggestions,
                memberSelectedIndex = memberSuggestionSelectedIndex,
                memberErrorCode = state.memberSuggestionsErrorCode,
                channelSuggestions = filteredChannelSuggestions,
                channelSelectedIndex = channelSuggestionSelectedIndex,
                channelErrorCode = state.channelSuggestionsErrorCode,
                agentStatusResolver = agentStatusResolver,
                onDismiss = dismissSuggestionPopover,
                onMemberClick = selectMemberSuggestion,
                onChannelClick = selectChannelSuggestion
            )
        } else {
            null
        }
    SideEffect {
        suggestionSelectionDispatcher.updateComposition(
            suggestionsEnabled = state.suggestionsEnabled,
            memberSuggestions = filteredMemberSuggestions,
            channelSuggestions = filteredChannelSuggestions,
            onCommit = onCommit@ { edit ->
                val nextFieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
                if (!onEditorValueChange(editorSessionId, nextFieldValue)) return@onCommit
                suggestionSelectionDispatcher.updateDraft(
                    text = nextFieldValue.text,
                    selectionStart = nextFieldValue.selection.start,
                    selectionEnd = nextFieldValue.selection.end
                )
                onEvent("change", edit.text, asTask, null)
            }
        )
        if (suggestionPresentation == null) {
            suggestionPortal?.clear(suggestionPortalOwner, suggestionPortalGeneration)
        } else {
            suggestionPortal?.show(suggestionPresentation)
        }
    }
    DisposableEffect(suggestionPortal, suggestionPortalOwner) {
        onDispose { suggestionPortal?.release(suggestionPortalOwner) }
    }

    val hostPaddingBottom =
        keyboardLiftBottomContentPadding?.let { bottomContentPadding ->
            threadComposerHostKeyboardLiftBottomPadding(
                keyboardInsetDp = keyboardInsetDp,
                bottomContentPadding = bottomContentPadding
            )
        } ?: 0.dp

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .retainInputFocusForInteractions()
                .background(RaftColor.Canvas)
                .padding(
                    start = hostPaddingHorizontal,
                    top = hostPaddingTop,
                    end = hostPaddingHorizontal,
                    bottom = hostPaddingBottom
                ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = contentPaddingTop, bottom = contentPaddingBottom),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.statusLabel?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = localizedThreadComposerStatusLabel(status),
                    style = RaftType.Caption.copy(
                        color = if (state.composerMode == "failed") RaftColor.AccentRed else RaftColor.TextMutedGray
                    ),
                    color = if (state.composerMode == "failed") RaftColor.AccentRed else RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.pendingAttachments.isNotEmpty()) {
                ComposerAttachmentTray(
                    attachments = state.pendingAttachments,
                    enabled = state.enabled,
                    previewEnabled = state.enabled && attachmentPreviewEnabled
                ) { action, attachmentId ->
                    onEvent(action, null, null, attachmentId)
                }
            }
            val localizedMentionActionNotice =
                state.mentionActionNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                    if (notice == ThreadComposerMentionBatchPartialNoticeVisualMarker) {
                        stringResource(Res.string.thread_composer_mention_batch_error_member_authority)
                    } else {
                        notice
                    }
                }
            val localizedMentionActionNoticeTitle =
                state.mentionActionNoticeTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    if (title == ThreadComposerMentionBatchPartialNoticeTitleVisualMarker) {
                        stringResource(Res.string.thread_composer_mention_batch_result_summary, 1, 3)
                    } else {
                        title
                    }
                }
            localizedMentionActionNotice?.let { notice ->
                MentionActionNotice(title = localizedMentionActionNoticeTitle, detail = notice)
            }
            if (state.pendingMentionActions.isNotEmpty()) {
                PendingMentionActionBanner(
                    actions = state.pendingMentionActions,
                    memberSuggestions = state.memberSuggestions,
                    channelLabel = surface.localizedMentionScopeChannelLabel(),
                    actionStates = state.mentionActionStates,
                    removingIds = state.removingMentionActionIds.toSet(),
                    executingIds = state.executingMentionActionIds.toSet(),
                    executingStates = state.executingMentionActionStates,
                    onInvite = { resolutionId -> onEvent("inviteMention", resolutionId, null, null) },
                    onNotify = { resolutionId -> onEvent("notifyMention", resolutionId, null, null) },
                    onDismiss = { resolutionId -> onEvent("dismissMention", resolutionId, null, null) },
                    onAddAll = { onEvent("addAllMentions", null, null, null) },
                    mentionActionNoticeTitle = localizedMentionActionNoticeTitle,
                    mentionActionNotice = localizedMentionActionNotice,
                    initialDetailsVisible = initialMentionActionDetailsVisible,
                    onOpenDetails = {
                        currentDismissSuggestions()
                        softwareKeyboardController?.hide()
                    }
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .raftCaptureLiveRootAnchor(suggestionAnchorRef) { nextSize ->
                            if (composerCardSize != nextSize) composerCardSize = nextSize
                        }
            ) {
                // raft-ui `Composer`: card layer, container radius, 1.5dp line-strong edge,
                // no shadow.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .raftControlSurface(
                            fill = RaftColor.Card,
                            border = RaftColor.BorderBlack,
                            radius = RaftAppRadius.Container
                        )
                        // Blank-area tap only forwards focus to the editor. No indication: the
                        // default one paints the whole card gray (pressed/focused overlay); raft-ui
                        // shows composer focus through the editor caret, not a fill.
                        .clickable(
                            enabled = composerEnabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { composerFocusRequester.requestFocus() }
                        .padding(
                            start = ThreadComposerCardPadding,
                            top = ThreadComposerCardPadding,
                            end = ThreadComposerCardPadding,
                            bottom = ThreadComposerEmbeddedInset
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Clear retires the logical callback session, not the native editor view. A
                    // session-keyed subtree destroys the first responder and produces a visible
                    // IME dismiss→reopen cycle on iOS.
                    key(state.instanceId) {
                        val sourceSessionId = editorSessionId
                        TextField(
                            value = fieldValue,
                            placeholder = localizedThreadComposerPlaceholder(state.placeholder),
                            autoFocus = shouldAutoFocus,
                            onValueChange = onValueChange@ { next ->
                                if (submittedEchoFence.shouldIgnore(sourceSessionId, next)) {
                                    return@onValueChange
                                }
                                val acceptedValue =
                                    onNativeEditorValueChange(sourceSessionId, next)
                                        ?: return@onValueChange
                                val textChanged = acceptedValue.text != fieldValue.text
                                suggestionSelectionDispatcher.updateDraft(
                                    text = acceptedValue.text,
                                    selectionStart = acceptedValue.selection.start,
                                    selectionEnd = acceptedValue.selection.end
                                )
                                continueThreadComposerSuggestionsForEditing(
                                    portalState = suggestionPortal,
                                    owner = suggestionPortalOwner,
                                    generation = suggestionPortalGeneration,
                                    onFocusedChanged = { focused = it }
                                )
                                if (textChanged) {
                                    emitChange(nextText = acceptedValue.text)
                                }
                            },
                            onFocus = onFocus@ {
                                acceptThreadComposerEditorSessionUiEvent(
                                    sourceSessionId = sourceSessionId,
                                    isActiveSession = isEditorSessionActive,
                                ) {
                                    suggestionPortalGeneration =
                                        resumeThreadComposerSuggestionsForEditing(
                                            portalState = suggestionPortal,
                                            owner = suggestionPortalOwner,
                                            generation = suggestionPortalGeneration,
                                            onFocusedChanged = { focused = it }
                                        )
                                    if (shouldAutoFocus) {
                                        handledFocusRequestNonce = state.focusRequestNonce
                                    }
                                }
                            },
                            onBlur = onBlur@ {
                                acceptThreadComposerEditorSessionUiEvent(
                                    sourceSessionId = sourceSessionId,
                                    isActiveSession = isEditorSessionActive,
                                ) {
                                    dismissThreadComposerSuggestionsForFocusLoss(
                                        portalState = suggestionPortal,
                                        owner = suggestionPortalOwner,
                                        generation = suggestionPortalGeneration,
                                        onFocusedChanged = { focused = it }
                                    )
                                    if (dismissKeyboardOnBlur) {
                                        softwareKeyboardController?.hide()
                                    }
                                    onEvent("blur", null, null, null)
                                }
                            },
                            maxLines = 4,
                            // Only the preference decides the line mode. A multi-line field
                            // suppresses the IME action key, so with "return key sends" off the
                            // editor stays multi-line and Enter inserts a newline; the suggestion
                            // panel raising imeAction to Send has no effect in that mode, which is
                            // the agreed contract (accept a suggestion by tapping it instead).
                            //
                            // Known limitation: the native editor fixes its line mode once, so
                            // toggling the preference while this composer is alive does not take
                            // effect until the subtree is recreated. Changing it emits a warning
                            // from the native side rather than silently serving the old mode.
                            imeActionRequested = composerSendKeyEnabled,
                            keyboardOptions = composerKeyboardOptions,
                            keyboardActions = composerKeyboardActions,
                            // RaftType.Input uses the shared content size, same as message text.
                            textStyle = RaftType.Input.copy(
                                color = if (composerEnabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
                            ),
                            // Kuikly produces keyboardHeightChange from the native input node on
                            // every platform. The height is host/window geometry, not editor
                            // content state: restore the pre-session-retirement contract and never
                            // put this callback behind the text-session gate.
                            keyboardHeightChange = { params ->
                                val nextInset =
                                    dispatchThreadComposerHostKeyboardInset(
                                        nextInsetDp = params.height,
                                        onKeyboardHeightChange = onKeyboardHeightChange,
                                    )
                                if (nextInset != keyboardInsetDp) {
                                    keyboardInsetDp = nextInset
                                }
                            },
                            placeholderColor = RaftColor.ForegroundHint,
                            cursorBrush = SolidColor(RaftColor.TextBlack),
                            systemNewlineAction = true,
                            nativeEditingStateReader = nativeEditingStateReader,
                            // React mobile uses a one-line textarea; keep the toolbar as the touch target.
                            focusRequester = composerFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = ThreadComposerTextFieldMinHeight,
                                    max = ThreadComposerTextFieldMaxHeight
                                )
                                .threadComposerPasteAttachments {
                                    onEvent(ThreadComposerPasteAttachmentsAction, null, null, null)
                                }
                        )
                    }
                    if (state.hookLabels.isNotEmpty()) {
                        Text(
                            text = state.hookLabels.joinToString(" / "),
                            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // raft-ui `ComposerToolbar`: its buttons are embedded in the composer's frame.
                    RaftEmbeddedControls {
                        Row(
                            modifier = Modifier.composerToolbarBleed().fillMaxWidth().heightIn(min = ThreadComposerToolbarMinHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .heightIn(min = ThreadComposerToolbarMinHeight),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ThreadComposerLeftActionGroupSpacing)
                            ) {
                                if (state.attachmentActionsEnabled) {
                                    // Gallery / camera / file, in that order (artin, task #1096). Camera
                                    // used to exist only as this button's long-press, which meant nobody
                                    // found it; the long-press stays so existing muscle memory keeps
                                    // working, and the dedicated button is what makes it discoverable.
                                    ComposerIconButton(
                                        icon = RaftLucideIconName.ImagePlus,
                                        enabled = composerEnabled,
                                        contentDescription = stringResource(Res.string.composer_add_image),
                                        longClickLabel = stringResource(Res.string.composer_take_photo),
                                        onLongClick = {
                                            onEvent(threadComposerImageAction(longPress = true), null, null, null)
                                        },
                                        onClick = {
                                            onEvent(threadComposerImageAction(longPress = false), null, null, null)
                                        }
                                    )
                                    ComposerIconButton(
                                        icon = RaftLucideIconName.Camera,
                                        enabled = composerEnabled,
                                        contentDescription = stringResource(Res.string.composer_take_photo),
                                        onClick = {
                                            // longPress = true is what makes this the camera: it emits
                                            // captureImage, so capture keeps its existing confirm/cancel
                                            // editor step and nothing reaches the pending tray until the
                                            // user confirms. Flipping it to false silently turns this
                                            // button into a second gallery entry.
                                            //
                                            // No test covers this call site. commonTest cannot drive
                                            // Compose and the composer has no visual host, so nothing in
                                            // the repo can reach ComposerIconButton. The helper's mapping
                                            // is tested (ThreadPayloadTest), but the argument passed here
                                            // is not -- do not read that test as guarding this line.
                                            onEvent(threadComposerImageAction(longPress = true), null, null, null)
                                        }
                                    )
                                    ComposerIconButton(
                                        icon = RaftLucideIconName.Paperclip,
                                        enabled = composerEnabled,
                                        contentDescription = stringResource(Res.string.composer_attach_file),
                                        onClick = { onEvent("attachFile", null, null, null) }
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ThreadComposerRightActionGroupSpacing)
                            ) {
                                if (surface.showTaskButton) {
                                    Row(
                                        modifier = Modifier
                                            .clickable(enabled = composerEnabled) {
                                                val newValue = !asTask
                                                if (onEditorAsTaskChange(editorSessionId, newValue)) {
                                                    onEvent("toggleAsTask", null, newValue, null)
                                                }
                                            }
                                            .heightIn(min = 18.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        RaftAppCheckbox(
                                            checked = asTask,
                                            onCheckedChange = { checked ->
                                                if (onEditorAsTaskChange(editorSessionId, checked)) {
                                                    onEvent("toggleAsTask", null, checked, null)
                                                }
                                            },
                                            enabled = composerEnabled,
                                            size = 14.dp
                                        )
                                        // raft-ui `ComposerToolbarLabel`: text-callout (14/20) semibold on
                                        // foreground, foreground-disabled when the composer is off.
                                        val labelColor = if (composerEnabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled
                                        Text(
                                            text = stringResource(Res.string.thread_composer_as_task),
                                            style =
                                                RaftType.Caption
                                                    .withRaftFontWeight(FontWeight.SemiBold)
                                                    .copy(fontSize = 14.sp, lineHeight = 20.sp, color = labelColor),
                                            color = labelColor
                                        )
                                    }
                                }
                                ComposerSendButton(
                                    enabled = canSend,
                                    sending = state.composerMode == "sending" || state.composerMode == "uploading"
                                ) {
                                    sendComposerMessage()
                                }
                            }
                        }
                    }
                }
                // Web MessageInput `frameEdge`: drawn on the frame's top edge, after the frame (the
                // assistant's otter). Neither this box nor its parents clip, so it reaches up over the list.
                frameEdge?.invoke(threadComposerComposing(text, state.pendingAttachments.size), focused)
            }
        }
    }
    }
}

/**
 * The toolbar reaches past the card's [ThreadComposerCardPadding] on both sides, so its first
 * and last buttons sit [ThreadComposerEmbeddedInset] inside the card's outer edge, as far as
 * the bottom one (raft-ui `ComposerToolbar` `mx-[calc(var(--control-embedded-inset)-…)]`).
 */
private fun Modifier.composerToolbarBleed(): Modifier =
    layout { measurable, constraints ->
        val bleed = (ThreadComposerCardPadding - ThreadComposerEmbeddedInset).roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = bleed * 2))
        layout(placeable.width - bleed * 2, placeable.height) { placeable.place(-bleed, 0) }
    }

internal fun composerSuggestionPanelHeight(
    panelState: ThreadComposerSuggestionPanelState,
    memberCount: Int,
    channelCount: Int,
    includeMemberHeader: Boolean = true,
    memberPanelEntryCount: Int = memberCount
): Dp {
    val estimated =
        when (panelState) {
            ThreadComposerSuggestionPanelState.MemberItems ->
                composerSuggestionPanelContentHeight(
                    itemCount = memberPanelEntryCount,
                    rowMinHeight = ComposerMemberSuggestionRowMinHeight,
                    includeHeader = includeMemberHeader
                )
            ThreadComposerSuggestionPanelState.ChannelItems ->
                composerSuggestionPanelContentHeight(
                    itemCount = channelCount,
                    rowMinHeight = ComposerChannelSuggestionRowMinHeight,
                    includeHeader = false
                )
            ThreadComposerSuggestionPanelState.MemberLoading,
            ThreadComposerSuggestionPanelState.MemberEmpty,
            ThreadComposerSuggestionPanelState.MemberError ->
                composerSuggestionPanelContentHeight(
                    itemCount = 1,
                    rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                    includeHeader = includeMemberHeader
                )
            ThreadComposerSuggestionPanelState.ChannelLoading,
            ThreadComposerSuggestionPanelState.ChannelEmpty,
            ThreadComposerSuggestionPanelState.ChannelError ->
                composerSuggestionPanelContentHeight(
                    itemCount = 1,
                    rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                    includeHeader = false
                )
            ThreadComposerSuggestionPanelState.Hidden ->
                0.dp
    }
    return estimated
}

internal fun composerSuggestionPanelContentHeight(
    itemCount: Int,
    rowMinHeight: Dp,
    includeHeader: Boolean = true
): Dp {
    val headerHeight = if (includeHeader) ComposerSuggestionHeaderEstimatedHeight else 0.dp
    val estimated =
        ComposerSuggestionPanelInset * 2 + headerHeight + rowMinHeight * itemCount.coerceAtLeast(1)
    return if (estimated > ComposerSuggestionPanelMaxHeight) ComposerSuggestionPanelMaxHeight else estimated
}

@Composable
private fun ComposerSuggestionPanelContent(
    panelState: ThreadComposerSuggestionPanelState,
    surface: ThreadComposerSurface,
    memberSuggestions: List<RaftComposerMemberItem>,
    memberSelectedIndex: Int,
    memberErrorCode: String?,
    channelSuggestions: List<RaftComposerChannelItem>,
    channelSelectedIndex: Int,
    channelErrorCode: String?,
    agentStatusResolver: RaftAgentStatusResolver,
    onMemberClick: (RaftComposerMemberItem) -> Unit,
    onChannelClick: (RaftComposerChannelItem) -> Unit
) {
    when (panelState) {
        ThreadComposerSuggestionPanelState.MemberItems -> {
            ComposerMemberSuggestionPanel(
                title = surface.memberSuggestionTitle(),
                showMembershipSections = surface.memberSuggestionHeadersVisible,
                members = memberSuggestions,
                selectedIndex = memberSelectedIndex,
                agentStatusResolver = agentStatusResolver,
                onClick = onMemberClick
            )
        }

        ThreadComposerSuggestionPanelState.MemberLoading -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = stringResource(Res.string.thread_state_loading)
            )
        }

        ThreadComposerSuggestionPanelState.MemberEmpty -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = stringResource(Res.string.composer_state_no_matching_members)
            )
        }

        ThreadComposerSuggestionPanelState.MemberError -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = memberErrorCode?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.thread_composer_load_members_failed)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelItems -> {
            ComposerSuggestionPanel(
                title = null,
                showTitleDivider = false,
                panelHeight =
                    composerSuggestionPanelContentHeight(
                        itemCount = channelSuggestions.size,
                        rowMinHeight = ComposerChannelSuggestionRowMinHeight,
                        includeHeader = false
                    ),
                itemCount = channelSuggestions.size,
                itemKey = { index -> "channel-$index-${channelSuggestions[index].name}" }
            ) { index ->
                val channel = channelSuggestions[index]
                val selectedIndex =
                    threadComposerSuggestionSelectedIndex(channelSelectedIndex, channelSuggestions.size)
                ComposerChannelSuggestionRow(
                    channel = channel,
                    selected = index == selectedIndex,
                    onClick = { onChannelClick(channel) }
                )
            }
        }

        ThreadComposerSuggestionPanelState.ChannelLoading -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = stringResource(Res.string.thread_state_loading)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelEmpty -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = stringResource(Res.string.composer_state_no_matching_channels)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelError -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = channelErrorCode?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.thread_composer_load_channels_failed)
            )
        }

        ThreadComposerSuggestionPanelState.Hidden -> Unit
    }
}

@Composable
private fun ThreadComposerSurface.memberSuggestionTitle(): String? =
    if (!memberSuggestionHeadersVisible) {
        null
    } else {
        when (kind) {
            RaftThreadSurfaceKind.ReplyThread -> stringResource(Res.string.thread_composer_members_parent_channel)
            else -> stringResource(Res.string.thread_composer_members_this_channel)
        }
    }

@Composable
private fun ThreadComposerSurface.localizedMentionScopeChannelLabel(): String =
    mentionChannelLabel.toComposerChannelMentionLabel()
        ?: stringResource(Res.string.thread_composer_scope_this_channel)

@Composable
private fun localizedThreadComposerPlaceholder(rawPlaceholder: String): String {
    val normalized = rawPlaceholder.trim()
    return when {
        normalized == "Message" -> stringResource(Res.string.thread_composer_placeholder_message)
        normalized == "Reply in thread" -> stringResource(Res.string.thread_composer_placeholder_reply)
        normalized == ThreadTaskComposerPlaceholder -> stringResource(Res.string.task_conversation_composer_placeholder)
        normalized.startsWith("Message @") ->
            stringResource(
                Res.string.thread_composer_placeholder_dm,
                normalized.removePrefix("Message @")
            )
        normalized.startsWith("Message #") ->
            stringResource(
                Res.string.thread_composer_placeholder_channel,
                normalized.removePrefix("Message #")
            )
        else -> rawPlaceholder
    }
}

@Composable
private fun localizedThreadComposerStatusLabel(status: String): String =
    if (status == "Composer payload parse failed") {
        stringResource(Res.string.thread_composer_payload_failed)
    } else {
        status
    }

internal fun ThreadComposerSurface.mentionScopeChannelLabel(): String =
    mentionChannelLabel.toComposerChannelMentionLabel()
        ?: "this channel"

internal fun String?.toComposerChannelMentionLabel(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("#") || raw.startsWith("@")) return raw
    val display = threadHeaderSafeTitleOrNull(raw) ?: return null
    return "#${display.removePrefix("#")}"
}

internal fun List<RaftComposerMemberItem>.firstComposerMemberSuggestionCandidate(): RaftComposerMemberItem? =
    composerMemberSuggestionCandidateAt(0)

internal fun List<RaftComposerMemberItem>.composerMemberSuggestionCandidateAt(
    selectedIndex: Int,
    groupByMentionChannel: Boolean = true
): RaftComposerMemberItem? {
    if (isEmpty()) return null
    if (!groupByMentionChannel) {
        return getOrNull(threadComposerSuggestionSelectedIndex(selectedIndex, size))
    }
    val groups = composerMemberSuggestionGroups()
    val ordered = groups.inThread + groups.inMentionChannel + groups.notInMentionChannel
    val index = threadComposerSuggestionSelectedIndex(selectedIndex, ordered.size)
    return ordered.getOrNull(index)
}

internal data class ComposerMemberSuggestionGroups(
    val inThread: List<RaftComposerMemberItem>,
    val inMentionChannel: List<RaftComposerMemberItem>,
    val notInMentionChannel: List<RaftComposerMemberItem>
)

internal fun List<RaftComposerMemberItem>.composerMemberSuggestionGroups(): ComposerMemberSuggestionGroups {
    val inThread = filter { it.inThread }
    val inThreadKeys = inThread.mapTo(mutableSetOf()) { it.composerMemberSuggestionKey() }
    val inMentionChannel =
        filter { item -> item.inMentionChannel && item.composerMemberSuggestionKey() !in inThreadKeys }
    val inMentionChannelKeys = inMentionChannel.mapTo(mutableSetOf()) { it.composerMemberSuggestionKey() }
    val notInMentionChannel =
        filter { item ->
            val key = item.composerMemberSuggestionKey()
            key !in inThreadKeys && key !in inMentionChannelKeys
        }
    return ComposerMemberSuggestionGroups(
        inThread = inThread,
        inMentionChannel = inMentionChannel,
        notInMentionChannel = notInMentionChannel
    )
}

private fun RaftComposerMemberItem.composerMemberSuggestionKey(): String =
    memberId?.trim()?.takeIf { it.isNotBlank() }
        ?.let { "${kind.name}:$it" }
        ?: handle.trim().lowercase().takeIf { it.isNotBlank() }?.let { "${kind.name}:$it" }
        ?: "${kind.name}:${displayName.trim().lowercase()}"

internal fun List<RaftPendingAttachmentItem>.readyThreadAttachmentIdsForSend(): List<String> =
    filter { attachment -> attachment.isReadyThreadAttachmentForSend() }
        .map { attachment -> attachment.id }

internal fun List<RaftPendingAttachmentItem>.hasBlockingThreadAttachmentUpload(): Boolean =
    any { attachment -> attachment.id.isNotBlank() && !attachment.isReadyThreadAttachmentForSend() }

internal fun List<RaftPendingAttachmentItem>.hasUploadingThreadAttachment(): Boolean =
    any { attachment ->
        attachment.id.isNotBlank() &&
            attachment.status == "uploading"
    }

internal fun List<RaftPendingAttachmentItem>.hasFailedThreadAttachmentUpload(): Boolean =
    any { attachment -> attachment.id.isNotBlank() && attachment.status == "failed" }

internal fun RaftPendingAttachmentItem.isReadyThreadAttachmentForSend(): Boolean =
    id.isNotBlank() && status == "ready" && !id.startsWith("picked-")

internal fun RaftPendingAttachmentItem.canOpenComposerAttachmentPreview(composerEnabled: Boolean): Boolean =
    composerEnabled &&
        (
            isReadyThreadAttachmentForSend() ||
                (status == "queued" && source == "external_share" && !previewUri.isNullOrBlank())
        )

internal fun RaftPendingMentionActionItem.supportsMentionAction(verb: String): Boolean =
    availableActions.any { action ->
        when (verb) {
            "notify" -> action == "notify" || action == "notify_only"
            "invite" -> action == "invite" || action == "add"
            else -> false
        }
    }

internal fun RaftPendingMentionActionItem.targetLabel(): String {
    val handle = targetHandle.ifBlank { targetType }
    return if ((targetType == "user" || targetType == "agent") && handle.isNotBlank() && !handle.startsWith("@")) {
        "@$handle"
    } else {
        handle.ifBlank { "@unknown" }
    }
}

// The target's handle for the avatar's initials; no handle shows the placeholder icon
// (never "?" or the target type).
private fun RaftPendingMentionActionItem.targetAvatarName(): String = targetHandle.trim()

/** The mention target's face: the matching suggestion's member when there is one, else just its kind. */
private fun RaftPendingMentionActionItem.targetAvatarIdentity(suggestion: RaftComposerMemberItem?): RaftMemberAvatarIdentity =
    RaftMemberAvatarIdentity(
        kind = suggestion?.kind ?: if (targetType == "agent") MemberKind.Agent else MemberKind.Human,
        memberId = suggestion?.memberId,
        serverId = suggestion?.sourceServerId,
    )

internal fun RaftPendingMentionActionItem.targetAvatarWireValue(): String? =
    targetAvatarUrl?.takeIf { it.isNotBlank() }

internal fun RaftPendingMentionActionItem.matchingTargetSuggestion(
    suggestions: List<RaftComposerMemberItem>
): RaftComposerMemberItem? {
    val target = targetHandle.normalizedPendingMentionTargetHandle() ?: return null
    return suggestions.firstOrNull { suggestion ->
        suggestion.handle.normalizedPendingMentionTargetHandle() == target
    } ?: suggestions.firstOrNull { suggestion ->
        suggestion.displayName.normalizedPendingMentionTargetHandle() == target
    }
}

private fun String?.normalizedPendingMentionTargetHandle(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()

internal fun RaftPendingMentionActionItem.pendingMentionActionDescription(
    channelLabel: String,
    localState: String? = null
): String {
    val target = targetLabel()
    if (localState == "added") {
        return "$target was added to $channelLabel"
    }
    if (localState == "notified") {
        return "Notified $target once - still not in $channelLabel"
    }
    val actionHint =
        when {
            supportsMentionAction("invite") && supportsMentionAction("notify") ->
                "Add them to $channelLabel or send a one-time notify."
            supportsMentionAction("invite") ->
                "Add them to $channelLabel so future mentions notify them."
            supportsMentionAction("notify") ->
                "Send a one-time notify without adding them."
            else ->
                "Dismiss this reminder when no action is needed."
        }
    return "$target isn't in $channelLabel. $actionHint"
}

@Composable
private fun RaftPendingMentionActionItem.localizedPendingMentionActionDescription(
    channelLabel: String,
    localState: String?
): String {
    val target = targetLabel()
    if (localState == "added") {
        return stringResource(Res.string.thread_composer_mention_added, target, channelLabel)
    }
    if (localState == "notified") {
        return stringResource(Res.string.thread_composer_mention_notified, target, channelLabel)
    }
    val actionHint =
        when {
            supportsMentionAction("invite") && supportsMentionAction("notify") ->
                stringResource(Res.string.thread_composer_mention_add_or_notify, channelLabel)
            supportsMentionAction("invite") ->
                stringResource(Res.string.thread_composer_mention_add_hint, channelLabel)
            supportsMentionAction("notify") ->
                stringResource(Res.string.thread_composer_mention_notify_hint)
            else ->
                stringResource(Res.string.thread_composer_mention_dismiss_hint)
        }
    return stringResource(Res.string.thread_composer_mention_not_in_channel, target, channelLabel, actionHint)
}

@Composable
private fun ComposerMemberSuggestionPanel(
    title: String?,
    showMembershipSections: Boolean,
    members: List<RaftComposerMemberItem>,
    selectedIndex: Int,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: (RaftComposerMemberItem) -> Unit
) {
    if (!showMembershipSections) {
        val clampedSelectedIndex = threadComposerSuggestionSelectedIndex(selectedIndex, members.size)
        ComposerSuggestionPanel(
            title = null,
            showTitleDivider = false,
            panelHeight =
                composerSuggestionPanelContentHeight(
                    itemCount = members.size,
                    rowMinHeight = ComposerMemberSuggestionRowMinHeight,
                    includeHeader = false
                ),
            itemCount = members.size,
            itemKey = { index -> "member-${members[index].composerMemberSuggestionKey()}" }
        ) { index ->
            val member = members[index]
            ComposerMemberSuggestionRow(
                member = member,
                inChannel = true,
                selected = clampedSelectedIndex == index,
                agentStatusResolver = agentStatusResolver,
                onClick = { onClick(member) }
            )
        }
        return
    }
    val localizedParentChannelTitle = stringResource(Res.string.thread_composer_members_parent_channel)
    val localizedNotInParentChannelTitle = stringResource(Res.string.thread_composer_members_not_parent_channel)
    val localizedNotInThisChannelTitle = stringResource(Res.string.thread_composer_members_not_this_channel)
    val panelEntries =
        composerMemberSuggestionPanelEntries(
            title = title,
            members = members,
            inThreadTitle = stringResource(Res.string.thread_composer_members_this_thread),
            inThisChannelTitle = stringResource(Res.string.thread_composer_members_this_channel),
            notInParentChannelTitle = localizedNotInParentChannelTitle,
            notInThisChannelTitle = localizedNotInThisChannelTitle,
            notInScopeTitle =
                if (title == localizedParentChannelTitle) {
                    localizedNotInParentChannelTitle
                } else {
                    localizedNotInThisChannelTitle
                }
        )
    val clampedSelectedIndex = threadComposerSuggestionSelectedIndex(selectedIndex, panelEntries.selectableCount)
    ComposerSuggestionPanel(
        title = panelEntries.title,
        showTitleDivider = false,
        panelHeight = composerSuggestionPanelContentHeight(
            itemCount = panelEntries.entries.size,
            rowMinHeight = ComposerMemberSuggestionRowMinHeight,
            includeHeader = panelEntries.title != null
        ),
        itemCount = panelEntries.entries.size,
        itemKey = { index ->
            panelEntries.entries[index].key
        }
    ) { index ->
        val entry = panelEntries.entries[index]
        if (entry.headerText != null) {
            ComposerSuggestionHeader(
                text = entry.headerText,
                topDivider = entry.topDivider
            )
        } else {
            entry.member?.let { member ->
                ComposerMemberSuggestionRow(
                    member = member,
                    inChannel = entry.inChannel,
                    selected = clampedSelectedIndex == entry.selectableIndex,
                    agentStatusResolver = agentStatusResolver,
                    onClick = { onClick(member) }
                )
            }
        }
    }
}

private data class ComposerMemberSuggestionPanelModel(
    val title: String?,
    val entries: List<ComposerMemberSuggestionPanelEntry>,
    val selectableCount: Int
)

private data class ComposerMemberSuggestionPanelEntry(
    val key: String,
    val headerText: String? = null,
    val topDivider: Boolean = false,
    val member: RaftComposerMemberItem? = null,
    val selectableIndex: Int = -1,
    val inChannel: Boolean = true
)

private fun composerMemberSuggestionPanelEntries(
    title: String?,
    members: List<RaftComposerMemberItem>,
    inThreadTitle: String = "In this thread",
    inThisChannelTitle: String = "In this channel",
    notInParentChannelTitle: String = "Not in parent channel",
    notInThisChannelTitle: String = "Not in this channel",
    notInScopeTitle: String? = null
): ComposerMemberSuggestionPanelModel {
    val groups = members.composerMemberSuggestionGroups()
    val entries = mutableListOf<ComposerMemberSuggestionPanelEntry>()
    var selectableIndex = 0
    fun addRows(prefix: String, rows: List<RaftComposerMemberItem>, inChannel: Boolean) {
        rows.forEach { member ->
            entries +=
                ComposerMemberSuggestionPanelEntry(
                    key = "$prefix-${member.composerMemberSuggestionKey()}",
                    member = member,
                    selectableIndex = selectableIndex,
                    inChannel = inChannel
                )
            selectableIndex += 1
        }
    }
    val hasThreadSection = groups.inThread.isNotEmpty()
    addRows(prefix = "thread", rows = groups.inThread, inChannel = true)
    if (hasThreadSection && groups.inMentionChannel.isNotEmpty()) {
        entries +=
            ComposerMemberSuggestionPanelEntry(
                key = "mention-channel-header",
                headerText = title ?: inThisChannelTitle,
                topDivider = true
            )
    }
    addRows(prefix = "mention-channel", rows = groups.inMentionChannel, inChannel = true)
    if (groups.notInMentionChannel.isNotEmpty()) {
        entries +=
            ComposerMemberSuggestionPanelEntry(
                key = "not-in-mention-channel-header",
                headerText =
                    notInScopeTitle
                        ?: title.notInMentionScopeHeader(
                            notInParentChannelTitle = notInParentChannelTitle,
                            notInThisChannelTitle = notInThisChannelTitle
                        ),
                topDivider = entries.isNotEmpty()
            )
    }
    addRows(prefix = "not-in-mention-channel", rows = groups.notInMentionChannel, inChannel = false)
    return ComposerMemberSuggestionPanelModel(
        title = if (hasThreadSection) inThreadTitle else title,
        entries = entries,
        selectableCount = selectableIndex
    )
}

private fun String?.notInMentionScopeHeader(
    notInParentChannelTitle: String = "Not in parent channel",
    notInThisChannelTitle: String = "Not in this channel"
): String =
    // Scope titles are source-case resources now ("In parent channel"); match
    // case-insensitively so legacy uppercase payload titles keep working.
    when (this?.uppercase()) {
        "IN PARENT CHANNEL" -> notInParentChannelTitle
        "IN THIS CHANNEL" -> notInThisChannelTitle
        else -> notInThisChannelTitle
    }

@Composable
private fun ComposerSuggestionStatusPanel(
    title: String?,
    message: String
) {
    ComposerSuggestionPanel(
        title = title,
        showTitleDivider = false,
        panelHeight =
            composerSuggestionPanelContentHeight(
                itemCount = 1,
                rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                includeHeader = title != null
            ),
        itemCount = 1,
        itemKey = { "status-$message" }
    ) {
        ComposerSuggestionStatusRow(message)
    }
}

@Composable
private fun ComposerSuggestionStatusRow(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ComposerSuggestionStatusRowMinHeight)
                .clickable(onClick = {})
                .padding(horizontal = ComposerSuggestionPanelInset + ComposerSuggestionItemPaddingHorizontal),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message,
            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ComposerSuggestionHeader(
    text: String,
    topDivider: Boolean = false,
    bottomDivider: Boolean = false
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .clickable(onClick = {})
    ) {
        if (topDivider) {
            // web: `<Separator className="mt-1" />` then `<ComposerSuggestionGroup
            // className="pt-1">` -- the hairline spans the list's p-1.5 content box.
            Spacer(modifier = Modifier.height(4.dp))
            ComposerSuggestionDivider()
            Spacer(modifier = Modifier.height(4.dp))
        }
        // raft-ui `ComposerSuggestionGroupLabel`: text-caption bold on foreground-hint
        // in source case, px-2.5 pt-2 pb-1 inside the list's p-1.5 inset.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ComposerSuggestionPanelInset + ComposerSuggestionItemPaddingHorizontal,
                        top = 8.dp,
                        end = ComposerSuggestionPanelInset + ComposerSuggestionItemPaddingHorizontal,
                        bottom = 4.dp
                    )
        ) {
            Text(
                text = text,
                style =
                    RaftType.Caption
                        .withRaftFontWeight(FontWeight.Bold)
                        .copy(color = RaftColor.ForegroundHint),
                color = RaftColor.ForegroundHint
            )
        }
        if (bottomDivider) {
            ComposerSuggestionDivider()
        }
    }
}

/** The agent whose App Bot identity a mention candidate shows; null for people. */
internal fun RaftComposerMemberItem.mentionAppBotLookupMemberId(): String? =
    memberId?.trim()?.takeIf { kind == MemberKind.Agent && it.isNotBlank() }

@Composable
internal fun ComposerMemberSuggestionRow(
    member: RaftComposerMemberItem,
    inChannel: Boolean,
    selected: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: () -> Unit
) {
    // Web `MentionCandidateBody` (raft-ui ComposerSuggestion* parts): title, a soft muted
    // actor-type badge, the description and the source server as hint metas, and the
    // `@handle` in a mono aside. Not-in-channel rows are dimmed to 60%, their avatar to 60% of that.
    val description = member.description?.trim()?.replace(ComposerSuggestionWhitespaceRegex, " ")?.takeIf { it.isNotBlank() }
    val serverLabel = member.sourceServerLabel?.trim()?.takeIf { it.isNotBlank() }
    val actorTypeLabel =
        when (member.kind) {
            MemberKind.Human -> stringResource(Res.string.composer_mention_actor_human)
            MemberKind.Agent -> stringResource(Res.string.composer_mention_actor_agent)
            else -> null
        }
    val muted = !inChannel
    // web `MentionCandidateBody`: an App Bot is a Bot, not an Agent; the Bot badge takes the
    // kind tag's place, and its avatar has no activity light (`MentionCandidateAvatar`).
    val appBot = rememberRaftMemberAppBot(member.mentionAppBotLookupMemberId())
    // web `MentionCandidateAvatar`: the agent's activity light sits on the avatar corner
    // (`AgentActivityDot size="sm"`), only for members of this conversation.
    val agentStatus =
        if (inChannel && member.kind == MemberKind.Agent && appBot == null) {
            rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.memberId)
        } else {
            null
        }
    ComposerSuggestionInteractiveRow(
        selected = selected,
        muted = muted,
        selectedColor = RaftColor.BrandSoft,
        minHeight = ComposerMemberSuggestionRowMinHeight,
        mutedOverlay = true,
        onClick = onClick
    ) {
        // web `MentionCandidateAvatar muted`: the avatar dims once more (`opacity-60`) inside the row's.
        Box(modifier = if (muted) Modifier.alpha(0.6f) else Modifier) {
            RaftAvatarWithStatus(
                statusColor = agentStatus?.color,
                avatarSize = ComposerMemberSuggestionAvatarSize,
                statusSize = ComposerMemberSuggestionStatusSize,
                statusPulsing = agentStatus?.pulsing == true
            ) {
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(member.kind, member.memberId, member.sourceServerId),
                    label = member.displayName.ifBlank { member.avatarLabel },
                    avatarSize = ComposerMemberSuggestionAvatarSize,
                    carriedAvatarWireValue = member.avatarWireValue,
                )
            }
        }
        if (muted) {
            // web: `ComposerSuggestionIcon variant="auxiliary"` UserX after the avatar.
            Spacer(modifier = Modifier.width(ComposerSuggestionItemGap - 4.dp))
            RaftLucideIcon(
                RaftLucideIconName.UserMinus,
                modifier = Modifier.size(12.dp),
                color = raftArgbHex(RaftColor.ForegroundHint)
            )
        }
        Spacer(modifier = Modifier.width(ComposerMemberSuggestionAvatarNameGap))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ComposerSuggestionContentGap)
        ) {
            Text(
                text = member.displayName,
                // raft-ui suggestion item: text-callout (14/20) medium on foreground.
                style = ComposerSuggestionTitleStyle,
                color = RaftColor.TextBlack,
                // Web `max-w-48`: the title takes its natural width up to 192dp and is never
                // squeezed into an equal share with the metas; only description/server flex.
                modifier = Modifier.widthIn(max = ComposerSuggestionTitleMaxWidth),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (appBot != null) {
                RaftAppBotBadge(identity = appBot, botName = member.displayName)
            } else {
                actorTypeLabel?.let { ComposerSuggestionSoftBadge(it) }
            }
            description?.let {
                Text(
                    text = it,
                    style = ComposerSuggestionMetaStyle,
                    color = RaftColor.ForegroundHint,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            serverLabel?.let {
                Text(
                    text = it,
                    style = ComposerSuggestionMetaStyle,
                    color = RaftColor.ForegroundHint,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = ComposerSuggestionServerMaxWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // raft-ui `ComposerSuggestionAside` + `ComposerSuggestionMeta variant="code"`.
        Text(
            text = "@${member.handle}",
            style = ComposerSuggestionMetaStyle.copy(fontFamily = RaftMonoFamily),
            color = RaftColor.ForegroundHint,
            modifier = Modifier.padding(start = ComposerSuggestionContentGap).widthIn(max = ComposerSuggestionHandleMaxWidth),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** raft-ui `Badge appearance="soft" variant="muted"`: 22dp, chip radius, caption semibold. */
@Composable
private fun ComposerSuggestionSoftBadge(label: String) {
    Box(
        modifier =
            Modifier
                .height(22.dp)
                .background(RaftColor.InactiveSoft, RoundedCornerShape(RaftAppRadius.Chip))
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold),
            color = RaftColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun MentionActionNotice(
    title: String?,
    detail: String
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.AccentOrange.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.TriangleAlert,
            modifier = Modifier.size(14.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = RaftType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = detail,
                style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = RaftColor.TextBlack.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PendingMentionActionBanner(
    actions: List<RaftPendingMentionActionItem>,
    memberSuggestions: List<RaftComposerMemberItem>,
    channelLabel: String,
    actionStates: Map<String, String>,
    removingIds: Set<String>,
    executingIds: Set<String>,
    executingStates: Map<String, String>,
    onInvite: (String) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onAddAll: () -> Unit,
    mentionActionNoticeTitle: String?,
    mentionActionNotice: String?,
    initialDetailsVisible: Boolean,
    onOpenDetails: () -> Unit
) {
    var detailsVisible by remember { mutableStateOf(initialDetailsVisible && actions.size >= 2) }
    val visibleActions =
        actions
            .filterNot { it.resolutionId.trim() in removingIds }
            .map(RaftPendingMentionActionItem::withNormalizedResolutionId)
            .distinctBy(RaftPendingMentionActionItem::resolutionId)
    val displayedActions =
        visibleActions.ifEmpty {
            actions.take(1).map(RaftPendingMentionActionItem::withNormalizedResolutionId)
        }
    val eligibleActions =
        eligiblePendingMentionActionBatch(
            actions = displayedActions,
            actionStates = actionStates,
            removingIds = removingIds,
            executingIds = executingIds
        )
    val batchExecutingIds =
        displayedActions
            .map { it.resolutionId }
            .distinct()
            .filter { resolutionId ->
                resolutionId in executingIds && executingStates[resolutionId] == "added"
            }
    val batchExecuting = batchExecutingIds.size >= 2
    LaunchedEffect(displayedActions.size, detailsVisible) {
        if (detailsVisible && displayedActions.isEmpty()) detailsVisible = false
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        // raft-ui card: card layer, container radius, hairline, no shadow.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftCardSurface()
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (displayedActions.size == 1) {
                val action = displayedActions.single()
                PendingMentionActionRow(
                    action = action,
                    targetSuggestion = action.matchingTargetSuggestion(memberSuggestions),
                    channelLabel = action.channelName.toComposerChannelMentionLabel() ?: channelLabel,
                    executing = action.resolutionId in executingIds,
                    localState = actionStates[action.resolutionId],
                    removing = action.resolutionId in removingIds,
                    executingState = executingStates[action.resolutionId],
                    onInvite = { onInvite(action.resolutionId) },
                    onNotify = { onNotify(action.resolutionId) },
                    onDismiss = { onDismiss(action.resolutionId) }
                )
            } else {
                PendingMentionActionBatchSummary(
                    actions = displayedActions,
                    memberSuggestions = memberSuggestions,
                    channelLabel = channelLabel,
                    addAllVisible = eligibleActions.size >= 2 || batchExecuting,
                    batchExecuting = batchExecuting,
                    onAddAll = onAddAll,
                    onView = {
                        onOpenDetails()
                        detailsVisible = true
                    }
                )
            }
        }
    }
    // raft-ui Drawer as a bottom sheet (RaftSheet): the summary as its title, the actions in the
    // scrolling body, Add all / Done stacked at the bottom. Its RaftWindowDialog gives it the
    // theme's scrim and system bars and blocks the back swipe.
    if (detailsVisible) {
        val closeMentionSheet: () -> Unit = { detailsVisible = false }
        RaftSheet(
            onDismissRequest = closeMentionSheet,
            title = localizedPendingMentionBatchSummary(displayedActions.size, channelLabel),
            actions = {
                PendingMentionActionDetailsActions(
                    eligibleCount = eligibleActions.size,
                    batchExecuting = batchExecuting,
                    onAddAll = onAddAll,
                    onClose = closeMentionSheet
                )
            }
        ) {
            PendingMentionActionDetailsList(
                actions = displayedActions,
                memberSuggestions = memberSuggestions,
                channelLabel = channelLabel,
                actionStates = actionStates,
                removingIds = removingIds,
                executingIds = executingIds,
                executingStates = executingStates,
                mentionActionNoticeTitle = mentionActionNoticeTitle,
                mentionActionNotice = mentionActionNotice,
                onInvite = onInvite,
                onNotify = onNotify,
                onDismiss = onDismiss
            )
        }
    }
}

private fun RaftPendingMentionActionItem.withNormalizedResolutionId(): RaftPendingMentionActionItem {
    val normalized = resolutionId.trim()
    return if (normalized == resolutionId) this else copy(resolutionId = normalized)
}

@Composable
private fun PendingMentionActionBatchSummary(
    actions: List<RaftPendingMentionActionItem>,
    memberSuggestions: List<RaftComposerMemberItem>,
    channelLabel: String,
    addAllVisible: Boolean,
    batchExecuting: Boolean,
    onAddAll: () -> Unit,
    onView: () -> Unit
) {
    val summary = localizedPendingMentionBatchSummary(actions.size, channelLabel)
    val overflowCount = (actions.size - 2).coerceAtLeast(0)
    val visibleNames = actions.take(2).joinToString(separator = " · ") { it.targetLabel() }
    val namesLabel =
        if (overflowCount > 0) {
            "$visibleNames · ${stringResource(Res.string.thread_composer_mention_batch_more, overflowCount)}"
        } else {
            visibleNames
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PendingMentionActionAvatarStack(
            actions = actions,
            memberSuggestions = memberSuggestions,
            contentDescription = stringResource(Res.string.thread_composer_mention_batch_avatar_description, actions.size)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = summary,
                style = RaftType.Button.copy(fontSize = 12.sp),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = namesLabel,
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (addAllVisible) {
            ComposerTextChip(
                text =
                    if (batchExecuting) {
                        stringResource(Res.string.thread_composer_mention_batch_adding)
                    } else {
                        stringResource(Res.string.thread_composer_mention_batch_add_all)
                    },
                variant = ComposerTextChipVariant.Pink,
                enabled = !batchExecuting,
                loading = batchExecuting,
                leadingIcon = RaftLucideIconName.UserPlus,
                onClick = onAddAll
            )
        }
        ComposerTextChip(
            text = stringResource(Res.string.thread_composer_mention_batch_view),
            variant = ComposerTextChipVariant.White,
            enabled = true,
            leadingIcon = RaftLucideIconName.Users,
            onClick = onView
        )
    }
}

@Composable
private fun PendingMentionActionAvatarStack(
    actions: List<RaftPendingMentionActionItem>,
    memberSuggestions: List<RaftComposerMemberItem>,
    contentDescription: String
) {
    Row(
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.take(2).forEach { action ->
            val suggestion = action.matchingTargetSuggestion(memberSuggestions)
            RaftMemberAvatar(
                identity = action.targetAvatarIdentity(suggestion),
                label =
                    suggestion?.displayName?.takeIf(String::isNotBlank)
                        ?: suggestion?.avatarLabel?.takeIf(String::isNotBlank)
                        ?: action.targetAvatarName(),
                avatarSize = 24.dp,
                carriedAvatarWireValue = action.targetAvatarWireValue() ?: suggestion?.avatarWireValue,
            )
        }
        val overflowCount = (actions.size - 2).coerceAtLeast(0)
        if (overflowCount > 0) {
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Res.string.thread_composer_mention_batch_more, overflowCount),
                    style = RaftType.Caption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PendingMentionActionDetailsList(
    actions: List<RaftPendingMentionActionItem>,
    memberSuggestions: List<RaftComposerMemberItem>,
    channelLabel: String,
    actionStates: Map<String, String>,
    removingIds: Set<String>,
    executingIds: Set<String>,
    executingStates: Map<String, String>,
    mentionActionNoticeTitle: String?,
    mentionActionNotice: String?,
    onInvite: (String) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    mentionActionNotice?.takeIf { it.isNotBlank() }?.let { notice ->
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            MentionActionNotice(title = mentionActionNoticeTitle, detail = notice)
        }
    }
    // The sheet's body scrolls: a plain column (a mention batch is a handful of rows).
    actions.forEachIndexed { index, action ->
        key(action.resolutionId) {
            PendingMentionActionCompactRow(
                action = action,
                targetSuggestion = action.matchingTargetSuggestion(memberSuggestions),
                channelLabel = action.channelName.toComposerChannelMentionLabel() ?: channelLabel,
                executing = action.resolutionId in executingIds,
                localState = actionStates[action.resolutionId],
                removing = action.resolutionId in removingIds,
                executingState = executingStates[action.resolutionId],
                onInvite = { onInvite(action.resolutionId) },
                onNotify = { onNotify(action.resolutionId) },
                onDismiss = { onDismiss(action.resolutionId) }
            )
            if (index < actions.lastIndex) RaftSeparator()
        }
    }
}

@Composable
private fun PendingMentionActionDetailsActions(
    eligibleCount: Int,
    batchExecuting: Boolean,
    onAddAll: () -> Unit,
    onClose: () -> Unit
) {
    if (eligibleCount >= 2 || batchExecuting) {
        RaftButton(
            text =
                if (batchExecuting) {
                    stringResource(Res.string.thread_composer_mention_batch_adding)
                } else {
                    stringResource(Res.string.thread_composer_mention_batch_add_all)
                },
            onClick = onAddAll,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
            loading = batchExecuting,
            leadingIcon = {
                val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
                RaftLucideIcon(RaftLucideIconName.UserPlus, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
            }
        )
    }
    RaftButton(
        text = stringResource(Res.string.common_done),
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
        size = RaftButtonSize.Lg
    )
}

@Composable
private fun PendingMentionActionCompactRow(
    action: RaftPendingMentionActionItem,
    targetSuggestion: RaftComposerMemberItem?,
    channelLabel: String,
    executing: Boolean,
    localState: String?,
    removing: Boolean,
    executingState: String?,
    onInvite: () -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetAvatarLabel =
        targetSuggestion?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: targetSuggestion?.avatarLabel
                ?.takeIf { it.isNotBlank() }
            ?: action.targetAvatarName()
    val canInvite = action.supportsMentionAction("invite") && localState == null
    val canNotify = action.supportsMentionAction("notify") && localState == null
    val contentAlpha = if (removing) 0.45f else 1f
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftMemberAvatar(
            identity = action.targetAvatarIdentity(targetSuggestion),
            label = targetAvatarLabel,
            avatarSize = 28.dp,
            carriedAvatarWireValue = action.targetAvatarWireValue() ?: targetSuggestion?.avatarWireValue,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = action.targetLabel(),
                style = RaftType.Button.copy(fontSize = 12.sp),
                color = RaftColor.TextBlack.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(Res.string.thread_composer_mention_batch_row_status, channelLabel),
                style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = RaftColor.TextMutedGray.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (localState == "added") {
                ComposerTextChip(
                    stringResource(Res.string.thread_composer_mention_added_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = RaftLucideIconName.Check,
                    onClick = {}
                )
            } else if (canInvite) {
                ComposerTextChip(
                    if (executingState == "added") {
                        stringResource(Res.string.thread_composer_mention_adding_chip)
                    } else {
                        stringResource(Res.string.thread_composer_mention_add_chip)
                    },
                    variant = ComposerTextChipVariant.Pink,
                    enabled = !executing,
                    loading = executingState == "added",
                    leadingIcon = RaftLucideIconName.UserPlus,
                    onClick = onInvite
                )
            }
            if (localState == "notified") {
                ComposerTextChip(
                    stringResource(Res.string.thread_composer_mention_notified_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = RaftLucideIconName.Check,
                    onClick = {}
                )
            } else if (canNotify) {
                ComposerTextChip(
                    if (executingState == "notified") {
                        stringResource(Res.string.thread_composer_mention_notifying_chip)
                    } else {
                        stringResource(Res.string.thread_composer_mention_notify_chip)
                    },
                    variant = ComposerTextChipVariant.White,
                    enabled = !executing,
                    loading = executingState == "notified",
                    leadingIcon = RaftLucideIconName.BellRing,
                    onClick = onNotify
                )
            }
            ComposerTextChip(
                stringResource(Res.string.common_dismiss),
                variant = ComposerTextChipVariant.MutedText,
                enabled = !executing,
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun localizedPendingMentionBatchSummary(count: Int, channelLabel: String): String =
    if (count == 1) {
        stringResource(Res.string.thread_composer_mention_batch_summary_one, channelLabel)
    } else {
        stringResource(Res.string.thread_composer_mention_batch_summary_many, count, channelLabel)
    }

@Composable
private fun PendingMentionActionRow(
    action: RaftPendingMentionActionItem,
    targetSuggestion: RaftComposerMemberItem?,
    channelLabel: String,
    executing: Boolean,
    localState: String?,
    removing: Boolean,
    executingState: String?,
    onInvite: () -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = action.targetLabel()
    val targetAvatarLabel =
        targetSuggestion?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: targetSuggestion?.avatarLabel
                ?.takeIf { it.isNotBlank() }
            ?: action.targetAvatarName()
    val targetAvatarWireValue = action.targetAvatarWireValue() ?: targetSuggestion?.avatarWireValue
    val canInvite = action.supportsMentionAction("invite") && localState == null
    val canNotify = action.supportsMentionAction("notify") && localState == null
    val contentColor = if (removing) RaftColor.TextBlack.copy(alpha = 0.45f) else RaftColor.TextBlack
    val mutedColor = if (removing) RaftColor.TextMutedGray.copy(alpha = 0.45f) else RaftColor.TextMutedGray
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.UserPlus,
                    modifier = Modifier.size(16.dp),
                    color = if (removing) "#777777" else RaftColor.TextMutedGrayArgb
                )
            }
            RaftMemberAvatar(
                identity = action.targetAvatarIdentity(targetSuggestion),
                label = targetAvatarLabel,
                avatarSize = 24.dp,
                carriedAvatarWireValue = targetAvatarWireValue,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = targetLabel,
                    style = RaftType.Button.copy(fontSize = 12.sp),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = action.localizedPendingMentionActionDescription(channelLabel, localState),
                    style = RaftType.Caption.copy(color = mutedColor),
                    color = mutedColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (localState == "added") {
                ComposerTextChip(
                    stringResource(Res.string.thread_composer_mention_added_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = RaftLucideIconName.Check,
                    onClick = {}
                )
            } else if (canInvite) {
                ComposerTextChip(
                    if (executingState == "added") {
                        stringResource(Res.string.thread_composer_mention_adding_chip)
                    } else {
                        stringResource(Res.string.thread_composer_mention_add_chip)
                    },
                    variant = ComposerTextChipVariant.Pink,
                    enabled = !executing,
                    loading = executingState == "added",
                    leadingIcon = RaftLucideIconName.UserPlus,
                    onClick = onInvite
                )
            }
            if (localState == "notified") {
                ComposerTextChip(
                    stringResource(Res.string.thread_composer_mention_notified_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = RaftLucideIconName.Check,
                    onClick = {}
                )
            } else if (canNotify) {
                ComposerTextChip(
                    if (executingState == "notified") {
                        stringResource(Res.string.thread_composer_mention_notifying_chip)
                    } else {
                        stringResource(Res.string.thread_composer_mention_notify_chip)
                    },
                    variant = ComposerTextChipVariant.White,
                    enabled = !executing,
                    loading = executingState == "notified",
                    leadingIcon = RaftLucideIconName.BellRing,
                    onClick = onNotify
                )
            }
            ComposerTextChip(
                stringResource(Res.string.common_dismiss),
                variant = ComposerTextChipVariant.MutedText,
                enabled = !executing,
                onClick = onDismiss
            )
        }
    }
}

@Composable
internal fun ComposerAttachmentTray(
    attachments: List<RaftPendingAttachmentItem>,
    enabled: Boolean,
    previewEnabled: Boolean,
    onEvent: (action: String, attachmentId: String) -> Unit
) {
    // raft-ui `ComposerAttachments` (`flex-wrap gap-2 pt-1.5 pe-1.5`): the top and end room
    // keeps each tile's overhanging remove sticker inside the tray.
    RaftDesignSystemTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = RaftComposerAttachmentRemoveOverhang, end = RaftComposerAttachmentRemoveOverhang),
            verticalArrangement = Arrangement.spacedBy(RaftComposerAttachmentGap)
        ) {
            composerAttachmentRows(attachments).forEach { rowAttachments ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RaftComposerAttachmentGap)
                ) {
                    rowAttachments.forEach { attachment ->
                        ComposerAttachmentPreviewChip(
                            attachment = attachment,
                            enabled = enabled,
                            previewEnabled = previewEnabled,
                            onPreview = { onEvent("previewAttachment", attachment.id) },
                            onRemove = { onEvent("removeAttachment", attachment.id) },
                            onRetry = { onEvent("retryAttachments", attachment.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One tile of the tray on raft-ui `ComposerAttachment` (web MessageInput `SortableAttachmentChip`):
 * the image or file tile, the uploading or failed overlay, and the remove sticker.
 */
@Composable
private fun ComposerAttachmentPreviewChip(
    attachment: RaftPendingAttachmentItem,
    enabled: Boolean,
    previewEnabled: Boolean,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    val imagePreviewUri = attachment.previewUri?.takeIf { it.isNotBlank() }
    val onOpen = onPreview.takeIf { attachment.canOpenComposerAttachmentPreview(previewEnabled) }
    // The upload meter's chip state (phase, percent, words); a failed tile only while enabled.
    val upload = attachment.uploadDisplay?.takeIf { it.phase != RaftComposerUploadPhase.Failed || enabled }
    RaftComposerAttachment {
        if (attachment.isComposerImageAttachment()) {
            RaftComposerAttachmentImage(onClick = onOpen) {
                if (imagePreviewUri != null) {
                    RaftAspectFitImagePreviewSurface(
                        source = imagePreviewUri,
                        contentDescription = attachment.fileName,
                        modifier = Modifier.fillMaxSize(),
                        borderWidth = 0.dp,
                        contentScale = RaftImageThumbnailContentScale
                    )
                } else if (upload == null) {
                    // With no thumbnail, an overlay's spinner or text stands in for the glyph.
                    RaftLucideIcon(RaftLucideIconName.Image, modifier = Modifier.size(20.dp), color = raftArgbHex(RaftTheme.colors.foregroundMuted))
                }
            }
        } else {
            RaftComposerAttachmentFile(
                title = attachment.fileName,
                meta =
                    listOf(
                        stringResource(Res.string.thread_composer_attachment_file),
                        attachment.displaySizeLabel(),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                onClick = onOpen
            ) {
                RaftLucideIcon(RaftLucideIconName.FileText, modifier = Modifier.size(16.dp), color = raftArgbHex(RaftTheme.colors.foregroundMuted))
            }
        }
        // The web's overlays on file and image tiles alike; mobile keeps Retry on a failed one.
        if (upload != null) {
            val failed = upload.phase == RaftComposerUploadPhase.Failed
            RaftComposerAttachmentOverlay(
                phase = upload.phase.toComposerAttachmentPhase(),
                percent = upload.percent,
                label = upload.label.localized(),
                action = stringResource(Res.string.common_retry).takeIf { failed },
                onAction = onRetry.takeIf { failed }
            )
        }
        if (enabled) {
            RaftComposerAttachmentRemove(
                onClick = onRemove,
                contentDescription = "${stringResource(Res.string.common_remove)} ${attachment.fileName}"
            )
        }
    }
}

/**
 * The overlay a tile shows, or null at rest. A failed upload shows only while the composer is
 * enabled (Retry needs it). Main has no checking/finishing state yet; the transfer-progress
 * model maps onto [RaftComposerAttachmentPhase] here.
 */
/** The design system's overlay phase for the upload meter's chip phase (same four states). */
private fun RaftComposerUploadPhase.toComposerAttachmentPhase(): RaftComposerAttachmentPhase =
    when (this) {
        RaftComposerUploadPhase.Checking -> RaftComposerAttachmentPhase.Checking
        RaftComposerUploadPhase.Uploading -> RaftComposerAttachmentPhase.Uploading
        RaftComposerUploadPhase.Finishing -> RaftComposerAttachmentPhase.Finishing
        RaftComposerUploadPhase.Failed -> RaftComposerAttachmentPhase.Failed
    }

internal fun composerAttachmentRows(
    attachments: List<RaftPendingAttachmentItem>,
    maxWidthDp: Int = ThreadComposerAttachmentTrayMaxWidthDp
): List<List<RaftPendingAttachmentItem>> {
    if (attachments.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<RaftPendingAttachmentItem>>()
    var row = mutableListOf<RaftPendingAttachmentItem>()
    var rowWidthDp = 0
    attachments.forEach { attachment ->
        val widthDp = attachment.composerAttachmentPreviewWidthDp()
        val nextWidthDp =
            if (row.isEmpty()) {
                widthDp
            } else {
                rowWidthDp + ThreadComposerAttachmentRowGapDp + widthDp
            }
        if (row.isNotEmpty() && nextWidthDp > maxWidthDp) {
            rows += row
            row = mutableListOf()
            rowWidthDp = 0
        }
        row += attachment
        rowWidthDp =
            if (rowWidthDp == 0) {
                widthDp
            } else {
                rowWidthDp + ThreadComposerAttachmentRowGapDp + widthDp
            }
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

internal fun RaftPendingAttachmentItem.composerAttachmentPreviewWidthDp(): Int =
    if (isComposerImageAttachment()) {
        ThreadComposerAttachmentImageTileSizeDp
    } else {
        ThreadComposerAttachmentFileTileWidthDp
    }

internal fun RaftPendingAttachmentItem.isComposerImageAttachment(): Boolean {
    val normalizedMime = mimeType?.substringBefore(";")?.trim()?.lowercase().orEmpty()
    return normalizedMime.startsWith("image/") && normalizedMime != "image/svg+xml"
}

@Composable
private fun ComposerSuggestionPanel(
    title: String?,
    showTitleDivider: Boolean = true,
    panelHeight: Dp,
    itemCount: Int,
    itemKey: (Int) -> Any,
    itemContent: @Composable (Int) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(panelHeight)
                // raft-ui `ComposerSuggestionList`: popover layer, seam ring, float shadow.
                .raftLookOverlaySurface(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(panelHeight)
        ) {
            // `p-1.5` block inset; the side inset lives on the rows and labels.
            item(key = "inset-top", contentType = "inset") {
                Spacer(modifier = Modifier.height(ComposerSuggestionPanelInset))
            }
            if (title != null) {
                item(key = "header-$title", contentType = "header") {
                    ComposerSuggestionHeader(
                        text = title,
                        bottomDivider = showTitleDivider
                    )
                }
            }
            items(itemCount, key = itemKey) { index ->
                itemContent(index)
            }
            item(key = "inset-bottom", contentType = "inset") {
                Spacer(modifier = Modifier.height(ComposerSuggestionPanelInset))
            }
        }
    }
}

@Composable
private fun ComposerChannelSuggestionRow(
    channel: RaftComposerChannelItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    ComposerSuggestionInteractiveRow(
        selected = selected,
        muted = false,
        selectedColor = RaftColor.BrandSoft,
        minHeight = ComposerChannelSuggestionRowMinHeight,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ComposerChannelSuggestionIconSize)
                        // raft-ui framed suggestion icon: fill-muted sticker, muted glyph.
                        .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker)),
                contentAlignment = Alignment.Center
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.Hash,
                    modifier = Modifier.size(ComposerChannelSuggestionGlyphSize),
                    color = raftArgbHex(RaftColor.TextMuted)
                )
            }
            Spacer(modifier = Modifier.width(ComposerSuggestionItemGap))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerSuggestionContentGap)
            ) {
                Text(
                    text = channel.name,
                    style = ComposerSuggestionTitleStyle,
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                channel.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = RaftType.Caption.copy(color = RaftColor.ForegroundHint),
                        color = RaftColor.ForegroundHint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionInteractiveRow(
    selected: Boolean,
    muted: Boolean,
    selectedColor: Color,
    minHeight: Dp,
    mutedOverlay: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // raft-ui item: `hover:bg-brand-soft data-[highlighted=true]:bg-brand-soft`; on touch the
    // press takes the hover wash instead of a platform ripple.
    val background =
        when {
            selected || pressed -> selectedColor
            else -> Color.Transparent
        }
    // raft-ui `ComposerSuggestionList` item: inset 6dp (p-1.5) inside the
    // popover, rounded-control highlight, px-2.5.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ComposerSuggestionPanelInset)
                .background(background, RoundedCornerShape(RaftAppRadius.Control))
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .padding(
                        horizontal = ComposerSuggestionItemPaddingHorizontal,
                        vertical = ComposerSuggestionItemPaddingVertical
                    ),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                // web `opacity-60` on not-in-channel options. An overlay box sized with
                // `fillMaxSize` didn't reliably cover a row that only has a minimum height,
                // so those rows showed at full strength.
                modifier = Modifier.fillMaxWidth().then(if (muted && mutedOverlay) Modifier.alpha(0.6f) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ComposerSuggestionDivider() {
    Box(
        modifier =
            Modifier
                .padding(horizontal = ComposerSuggestionPanelInset)
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderHairline)
    )
}

@Composable
private fun ComposerIconButton(
    icon: RaftLucideIconName,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String,
    // raft-ui `ComposerIconButton`: ghost (no fill, no ring) on foreground-icon.
    variant: RaftButtonVariant = RaftButtonVariant.Ghost,
    loading: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick: () -> Unit
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = contentDescription.takeIf { it.isNotBlank() },
        modifier = modifier,
        variant = variant,
        size = RaftIconButtonSize.Md,
        enabled = enabled,
        loading = loading,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
    ) {
        RaftSlotIcon(icon)
    }
}

@Composable
private fun ComposerSendButton(
    enabled: Boolean,
    sending: Boolean,
    onClick: () -> Unit
) {
    // Busy keeps the pink under the spinner and ignores taps (raft-ui Button > States).
    ComposerIconButton(
        icon = RaftLucideIconName.Send,
        enabled = enabled || sending,
        contentDescription = stringResource(Res.string.common_send),
        variant = RaftButtonVariant.Primary,
        loading = sending,
        onClick = onClick
    )
}

internal fun threadComposerVisibleLeftActionGapDp(): Float =
    ThreadComposerLeftActionGroupSpacing.value

internal fun threadComposerVisibleRightActionGapDp(): Float =
    ThreadComposerRightActionGroupSpacing.value

internal fun threadComposerEmbeddedInsetDp(): Float = ThreadComposerEmbeddedInset.value

internal fun threadComposerIconButtonSizeDp(): Float = ThreadComposerIconButtonSize.value

internal fun threadComposerIconSizeDp(): Float = ThreadComposerIconSize.value

private enum class ComposerTextChipVariant {
    Pink,
    White,
    Disabled,
    MutedText
}

@Composable
private fun ComposerTextChip(
    text: String,
    variant: ComposerTextChipVariant,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: RaftLucideIconName? = null,
    onClick: () -> Unit
) {
    if (variant == ComposerTextChipVariant.MutedText) {
        val mutedColor = RaftColor.TextBlack.copy(alpha = if (enabled) 0.45f else 0.3f)
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier =
                Modifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = text,
                style = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp, color = mutedColor),
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    if (variant == ComposerTextChipVariant.Disabled) {
        Row(
            modifier =
                Modifier
                    // raft-ui disabled control: fill-muted, line-muted edge, control radius.
                    .raftControlSurface(fill = RaftColor.FillMuted, border = RaftColor.BorderHairline)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Success-state marks are Lucide icons, not "✓" text glyphs
            // (task #494 follow-up — the glyph used to live in the string
            // resource itself).
            leadingIcon?.let {
                RaftLucideIcon(
                    name = it,
                    color = RaftColor.TextMutedGrayArgb,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = text,
                style = RaftType.Caption.copy(color = RaftColor.TextBlack.copy(alpha = 0.4f)),
                color = RaftColor.TextBlack.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    RaftButton(
        text = text,
        onClick = onClick,
        variant = if (variant == ComposerTextChipVariant.Pink) RaftButtonVariant.Primary else RaftButtonVariant.Default,
        size = RaftButtonSize.Md,
        enabled = enabled,
        loading = loading,
        leadingIcon = leadingIcon?.let { icon -> { RaftSlotIcon(icon) } }
    )
}

internal data class RaftComposerSuggestionEdit(
    val text: String,
    val cursor: Int
)

internal fun applyRaftComposerSuggestionEdit(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    triggerStart: Int,
    replacement: String
): RaftComposerSuggestionEdit? {
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    if (triggerStart < 0 || triggerStart > safeStart) return null
    val suffixStart =
        if (replacement.lastOrNull()?.isWhitespace() == true && safeEnd < text.length && text[safeEnd].isWhitespace()) {
            safeEnd + 1
        } else {
            safeEnd
        }
    val nextText = text.substring(0, triggerStart) + replacement + text.substring(suffixStart)
    return RaftComposerSuggestionEdit(
        text = nextText,
        cursor = triggerStart + replacement.length
    )
}

internal fun commitRaftComposerSuggestionToken(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    triggerStart: Int
): RaftComposerSuggestionEdit? {
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    if (triggerStart < 0 || triggerStart > safeStart) return null
    val nextText =
        if (safeEnd < text.length && text[safeEnd].isWhitespace()) {
            text
        } else {
            text.substring(0, safeEnd) + " " + text.substring(safeEnd)
        }
    return RaftComposerSuggestionEdit(
        text = nextText,
        cursor = (safeEnd + 1).coerceIn(0, nextText.length)
    )
}

internal fun applyRaftComposerDirectMentionInsert(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    handle: String
): RaftComposerSuggestionEdit? {
    val token = handle.toThreadMentionToken() ?: return null
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    val prefix = text.substring(0, safeStart)
    val suffix = text.substring(safeEnd)
    val leadingSpace = if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) " " else ""
    val trailingSpace = if (suffix.isNotEmpty() && suffix.first().isWhitespace()) "" else " "
    val mention = "$leadingSpace@$token$trailingSpace"
    val nextText = prefix + mention + suffix
    return RaftComposerSuggestionEdit(
        text = nextText,
        cursor = prefix.length + mention.length
    )
}

internal fun detectRaftComposerSuggestionTrigger(
    text: String,
    cursor: Int = text.length
): RaftComposerSuggestionTrigger {
    if (text.isBlank()) {
        return RaftComposerSuggestionTrigger.None
    }
    val beforeCursor = text.substring(0, cursor.coerceIn(0, text.length))
    var triggerIndex = -1
    var triggerChar = ' '
    for (i in beforeCursor.indices.reversed()) {
        val current = beforeCursor[i]
        if (current.isWhitespace()) {
            break
        }
        if (current == '@' || current == '#') {
            triggerIndex = i
            triggerChar = current
            break
        }
    }
    if (triggerIndex < 0) {
        return RaftComposerSuggestionTrigger.None
    }
    val query = beforeCursor.substring(triggerIndex + 1)
    return when (triggerChar) {
        '@' -> RaftComposerSuggestionTrigger.MemberTrigger(query, triggerIndex)
        '#' -> RaftComposerSuggestionTrigger.ChannelTrigger(query, triggerIndex)
        else -> RaftComposerSuggestionTrigger.None
    }
}

internal fun threadComposerSuggestionTrigger(
    text: String,
    cursor: Int = text.length,
    suggestionsEnabled: Boolean = true
): RaftComposerSuggestionTrigger =
    if (suggestionsEnabled) {
        detectRaftComposerSuggestionTrigger(text, cursor)
    } else {
        RaftComposerSuggestionTrigger.None
    }

internal const val ThreadComposerPasteAttachmentsAction = "pasteAttachments"

/** Cmd+V (Ctrl+V off macOS) with no Shift / Alt: the paste shortcut. */
internal fun threadComposerIsPasteShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
): Boolean = keyDown && key == Key.V && (meta || ctrl) && !shift && !alt

/**
 * Desktop paste of files / an image (web MessageInput `handlePaste`): when the clipboard holds
 * them, the paste shortcut attaches them ([onPaste]) instead of pasting text. Plain text, and
 * every phone ([raftPlatformClipboardHasAttachments] is false there), paste as before.
 */
private fun Modifier.threadComposerPasteAttachments(onPaste: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val shortcut =
            threadComposerIsPasteShortcut(
                keyDown = event.type == KeyEventType.KeyDown,
                key = event.key,
                meta = event.isMetaPressed,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
            )
        if (!shortcut || !raftPlatformClipboardHasAttachments()) return@onPreviewKeyEvent false
        onPaste()
        true
    }
