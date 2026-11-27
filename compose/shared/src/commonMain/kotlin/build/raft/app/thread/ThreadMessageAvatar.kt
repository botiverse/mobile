package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.withRaftFontWeight
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.rememberRaftPreviewCardState
import build.raft.app.components.raftControlSurface
import build.raft.app.fill
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.raftGesturePointerInput
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.raftOriginServerNames
import build.raft.app.rememberRaftGestureOwner
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberServerRef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarTrigger
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.RaftMessageDelegatedAvatar
import build.raft.designsystem.components.RaftMessageDelegatedAvatarDefaults
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftAvatarKind
import build.raft.app.RaftAvatarRowDiagnostics
import build.raft.app.RaftAvatarWithOriginCorner
import build.raft.app.RaftAvatarWithStatus
import build.raft.app.RaftColor
import build.raft.app.RaftGestureSession
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftType
import kotlinx.coroutines.channels.Channel
import build.raft.designsystem.components.raftClickable

@Composable
internal fun ThreadMessageAvatarSlot(
    interactionIdentity: String,
    showAvatar: Boolean,
    showTimestamp: Boolean,
    compactTimestampGutter: Boolean,
    timestampLabel: String,
    timestampAlignment: Alignment,
    timestampTextAlign: TextAlign,
    avatarLabel: String,
    avatarKind: RaftAvatarKind,
    avatarWireValue: String?,
    avatarPending: Boolean = false,
    avatarDiagnostics: RaftAvatarRowDiagnostics? = null,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject? = null,
    status: RaftAgentStatusIndicatorState?,
    muted: Boolean = false,
    delegatedBy: MessageDelegatedBy? = null,
    delegatedSurface: Color = RaftColor.Canvas,
    /** A sender from another server: the servers it is from; the first one's tile goes on the corner. */
    originServers: List<MemberServerRef> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    /** [onClick] opens the profile and [onLongPress] mentions (false: both toggle a share selection). */
    profileActions: Boolean = true,
    /** The author's profile card under a mouse or trackpad pointer resting on the avatar; null: none. */
    previewCard: (@Composable ColumnScope.(RaftPreviewCardState) -> Unit)? = null,
    /**
     * The avatar's own click (its tap, by any pointer, a finger too; its screen-reader action)
     * pins [previewCard] instead of running [onClick] (an external author: the card is all there
     * is to show).
     */
    previewCardPinOnClick: Boolean = false,
    /**
     * A compact row without its header: the message's time for screen readers, whether or not
     * [showTimestamp] shows it (it shows only while the row is pressed). Null: the header has it.
     */
    timestampAccessibilityLabel: String? = null,
) {
    val slotLayout = threadMessageMetadataSlotLayout(showAvatar, compactTimestampGutter, showTimestamp)
    // Wide enough for the whole compact clock at any font scale, the same on every row.
    val slotWidth = rememberThreadMessageGutterWidth(slotLayout.widthDp.dp)
    // Reduced-motion drag-to-see-times (ThreadMessageTimeReveal.kt): a continuation row's time fades in
    // here, where the pressed (desktop hover) time sits. Only this slot reads `active`.
    val gutterReveal = LocalThreadTimeRevealGutter.current
    val gutterTimeShowing =
        gutterReveal != null && gutterReveal.active && !showAvatar && compactTimestampGutter &&
            !slotLayout.timestampVisible && timestampLabel.isNotBlank()
    Box(
        modifier =
            Modifier
                .width(slotWidth)
                .height(slotLayout.heightDp.dp)
                .then(
                    if (!showAvatar && timestampAccessibilityLabel != null) {
                        Modifier.clearAndSetSemantics {
                            contentDescription = timestampAccessibilityLabel
                            // Tests only: the revealed time has no semantics of its own.
                            if (gutterTimeShowing) testTag = ThreadTimeRevealLabelTestTag
                        }
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = timestampAlignment
    ) {
        when {
            showAvatar ->
                // React parity: MessageSenderAvatar carries mt-0.5 (2px) so the
                // avatar top optically aligns with the name glyphs.
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    val previewCardState = rememberRaftPreviewCardState()
                    val pins = previewCard != null && previewCardPinOnClick
                    val avatar: @Composable () -> Unit = {
                        ThreadMessageAvatar(
                            interactionIdentity = interactionIdentity,
                            avatarLabel = avatarLabel,
                            avatarKind = avatarKind,
                            avatarWireValue = avatarWireValue,
                            avatarPending = avatarPending,
                            avatarDiagnostics = avatarDiagnostics,
                            cacheSubject = cacheSubject,
                            status = status,
                            muted = muted,
                            delegatedBy = delegatedBy,
                            delegatedSurface = delegatedSurface,
                            originServers = originServers,
                            onClick = if (pins) previewCardState::pin else onClick,
                            onLongPress = onLongPress,
                            profileActions = profileActions
                        )
                    }
                    if (previewCard == null) {
                        avatar()
                    } else {
                        RaftPreviewCard(
                            card = { previewCard(previewCardState) },
                            state = previewCardState,
                            pinOnClick = previewCardPinOnClick,
                            trigger = avatar,
                        )
                    }
                }
            slotLayout.timestampVisible || gutterTimeShowing ->
                Text(
                    text = timestampLabel,
                    style = ThreadMessageGutterClockStyle,
                    color = RaftColor.TextBlack.copy(alpha = 0.38f),
                    maxLines = 1,
                    // The whole clock, never "23:…": it may run a few dp into the gap before the body.
                    overflow = TextOverflow.Visible,
                    softWrap = false,
                    textAlign = timestampTextAlign,
                    modifier =
                        Modifier
                            .align(timestampAlignment)
                            .wrapContentWidth(
                                align = if (timestampTextAlign == TextAlign.End) Alignment.End else Alignment.Start,
                                unbounded = true,
                            )
                            .then(
                                if (!slotLayout.timestampVisible && gutterReveal != null) {
                                    Modifier.graphicsLayer { alpha = gutterReveal.fraction }
                                } else {
                                    Modifier
                                }
                            )
                )
        }
    }
}

/** Tests and UI automation: a message's sender avatar (tap: profile; the name next to it mentions). */
internal const val ThreadMessageAvatarTestTag = "message-avatar"

@Composable
internal fun ThreadMessageAvatar(
    interactionIdentity: String,
    avatarLabel: String,
    avatarKind: RaftAvatarKind,
    avatarWireValue: String?,
    avatarPending: Boolean = false,
    avatarDiagnostics: RaftAvatarRowDiagnostics? = null,
    cacheSubject: build.raft.app.data.RaftAvatarCacheSubject? = null,
    status: RaftAgentStatusIndicatorState?,
    muted: Boolean = false,
    delegatedBy: MessageDelegatedBy? = null,
    delegatedSurface: Color = RaftColor.Canvas,
    originServers: List<MemberServerRef> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    /**
     * [onClick] opens the author's profile and [onLongPress] mentions them: screen readers hear
     * "View profile of …" and get "Mention …" as an action. False (selecting messages): the avatar
     * is a button named after the author.
     */
    profileActions: Boolean = true,
) {
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "thread-message-avatar",
            semanticIdentity = interactionIdentity,
            callbacks = ThreadMessageAvatarGestureCallbacks(onClick, onLongPress)
        )
    // VoiceOver / TalkBack reach the tap (the profile) and the long press (a mention) too; the
    // pointer gestures below don't publish either.
    val accessibilityName =
        if (profileActions) stringResource(Res.string.thread_message_avatar_view_profile, avatarLabel) else avatarLabel
    val mentionActionLabel = stringResource(Res.string.thread_message_avatar_mention, avatarLabel)
    // Web `group/avatar-trigger` on the message avatar's button: hover and press lay `ink-8` over the face.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .size(RaftAvatarSize.Md.edge)
                .semantics(mergeDescendants = true) {
                    testTag = ThreadMessageAvatarTestTag
                    role = Role.Button
                    contentDescription = accessibilityName
                    // `this.`: the composable's own onClick parameter shadows the semantics action.
                    this.onClick(label = null) {
                        gestureOwner.beginGesture().currentCallbacksOrNull()?.onClick()
                        true
                    }
                    if (profileActions) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(mentionActionLabel) {
                                    gestureOwner.beginGesture().currentCallbacksOrNull()?.onLongPress()
                                    true
                                }
                            )
                    }
                }
                .raftPointerHover(interactionSource)
                .raftGesturePointerInput(gestureOwner) { owner ->
                    var gesture: RaftGestureSession<ThreadMessageAvatarGestureCallbacks>? = null
                    // raw-click: the avatar emits its own hover and press (raftPointerHover + PressInteraction into RaftAvatarTrigger) and owns tap + long press
                    detectTapGestures(
                        onPress = { position ->
                            gesture = owner.beginGesture()
                            val press = PressInteraction.Press(position)
                            interactionSource.tryEmit(press)
                            var released = false
                            try {
                                released = tryAwaitRelease()
                            } finally {
                                interactionSource.tryEmit(
                                    if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                                )
                            }
                        },
                        onTap = {
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick()
                        },
                        onLongPress = {
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onLongPress()
                        }
                    )
                },
        contentAlignment = Alignment.Center
    ) {
        val avatarWithStatus: @Composable () -> Unit = {
            RaftAvatarWithStatus(
                // The assistant's face takes the corner a presence light would (raft-ui
                // MessageItemDelegatedAvatar; only on a person's avatar).
                statusColor = if (muted || delegatedBy != null) null else status?.color,
                avatarSize = RaftAvatarSize.Md.edge,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    RaftAvatarTrigger(interactionSource) {
                        RaftAppAvatar(
                            kind = avatarKind,
                            avatarWireValue = avatarWireValue,
                            label = avatarLabel,
                            diagnostics = avatarDiagnostics,
                            modifier = if (muted) Modifier.alpha(0.62f) else Modifier,
                            avatarSize = RaftAvatarSize.Md.edge,
                            cacheSubject = cacheSubject,
                            avatarPending = avatarPending,
                        )
                    }
                    if (muted) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(RaftColor.EmptyStateGray.copy(alpha = 0.38f), RoundedCornerShape(RaftAppRadius.Sticker))
                        )
                    }
                }
            }
        }
        // Member labels (web #14535 AvatarOriginCorner): another server's tile on the top-left
        // corner, clear of the presence light and the delegated face. The avatar names no one, so
        // the corner says where the sender is from ("From Acme Corp", web's text after the name).
        val originDescription =
            raftOriginServerNames(originServers).takeIf { it.isNotEmpty() }?.let { names ->
                stringResource(Res.string.profile_preview_from_servers, names)
            }
        RaftAvatarWithOriginCorner(
            servers = originServers,
            avatarEdge = RaftAvatarSize.Md.edge,
            contentDescription = originDescription,
        ) {
            if (delegatedBy == null) {
                avatarWithStatus()
            } else {
                RaftAvatarBadgeBox(
                    size = RaftAvatarSize.Md,
                    badge = { ThreadMessageDelegatedFace(delegatedBy, delegatedSurface) }
                ) { avatarWithStatus() }
            }
        }
    }
}

/**
 * RFC 077: the face of the assistant that sent the message, on its owner's avatar (raft-ui
 * `MessageItemDelegatedAvatar` via [RaftMessageDelegatedAvatar]): the assistant's 2xs avatar,
 * or the default agent face once the assistant is deleted (grayed and faded).
 */
@Composable
private fun ThreadMessageDelegatedFace(delegatedBy: MessageDelegatedBy, surface: Color) {
    RaftMessageDelegatedAvatar(
        deleted = delegatedBy.deleted,
        surfaceColor = surface,
        dark = RaftAppThemeState.isDark
    ) {
        RaftAppAvatar(
            // A deleted assistant's own face is gone: the default robot.
            kind = RaftAvatarKind.Agent,
            avatarWireValue = delegatedBy.avatarUrl.takeUnless { delegatedBy.deleted },
            label = delegatedBy.name,
            avatarSize = RaftMessageDelegatedAvatarDefaults.Size,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, delegatedBy.id),
        )
    }
}

private data class ThreadMessageAvatarGestureCallbacks(
    val onClick: () -> Unit,
    val onLongPress: () -> Unit
)

internal fun RaftThreadMessageItem.showsAgentStatusIndicator(): Boolean =
    isThreadAgentAuthor() && threadAuthorBadgeLabel() == null

private data class ThreadPixelAvatarSpec(val background: Color, val grid: List<String>) {
    val spans: List<ThreadPixelAvatarSpan> =
        buildList {
            grid.forEachIndexed { rowIndex, row ->
                var spanColor: Color? = null
                var spanStart = 0
                var spanLength = 0
                fun flushSpan() {
                    val color = spanColor ?: return
                    add(
                        ThreadPixelAvatarSpan(
                            rowIndex = rowIndex,
                            startColumnIndex = spanStart,
                            columnCount = spanLength,
                            color = color
                        )
                    )
                    spanColor = null
                    spanLength = 0
                }
                row.forEachIndexed { columnIndex, key ->
                    val color = ThreadPixelAvatarPalette[key] ?: Color.Transparent
                    when {
                        color == Color.Transparent -> flushSpan()
                        color == spanColor -> spanLength += 1
                        else -> {
                            flushSpan()
                            spanColor = color
                            spanStart = columnIndex
                            spanLength = 1
                        }
                    }
                }
                flushSpan()
            }
        }
}

private data class ThreadPixelAvatarSpan(
    val rowIndex: Int,
    val startColumnIndex: Int,
    val columnCount: Int,
    val color: Color
)

private const val ThreadPixelAvatarGridCellCount = 8
private const val ThreadPixelAvatarProtocolPrefix = "pixel:"
private const val ThreadPixelAvatarRandomPrefix = "random:"
private const val ThreadPixelAvatarDefaultKey = "robot"

private val ThreadPixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111), // raft-color: pixel-art avatar palette
    'W' to Color(0xFFFFFFFF), // raft-color: pixel-art avatar palette
    'P' to Color(0xFFFE7DA8), // raft-color: pixel-art avatar palette
    'O' to Color(0xFFF8A16F), // raft-color: pixel-art avatar palette
    'Y' to Color(0xFFFFD440), // raft-color: pixel-art avatar palette
    'L' to Color(0xFFA9D877), // raft-color: pixel-art avatar palette
    'C' to Color(0xFF27CCF3), // raft-color: pixel-art avatar palette
    'V' to Color(0xFFBBAFE6), // raft-color: pixel-art avatar palette
    'B' to Color(0xFF576574), // raft-color: pixel-art avatar palette
    'R' to Color(0xFFF97264), // raft-color: pixel-art avatar palette
    'G' to Color(0xFF9DCAAA) // raft-color: pixel-art avatar palette
)

private val ThreadPixelAvatarFixedSpecs = mapOf(
    "robot" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
    "cat" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")),
    "ghost" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")),
    "skull" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("_KKKKKK_", "KWWWWWWK", "KWKWWKWK", "KWWWWWWK", "_KWWWWK_", "_KWKWKK_", "__KKKK__", "___KK___")),
    "alien" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")),
    "heart" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("________", "_KK__KK_", "KRRKKRRK", "KRRRRRRK", "KRRRRRRK", "_KRRRRK_", "__KRRK__", "___KK___")),
    "star" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("___KK___", "___KK___", "KKKKKKKK", "_KYYYYK_", "__KYYK__", "_KYKKYK_", "KYK__KYK", "KK____KK")),
    "flame" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___")),
    "diamond" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("___KK___", "__KCCK__", "_KCWCCK_", "KCWCCCCK", "KCCCCCCK", "_KCCCCK_", "__KCCK__", "___KK___")),
    "mushroom" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("__KKKK__", "_KRWWRK_", "KRRWWRRK", "KRRRRRRK", "_KKKKKK_", "__KWWK__", "__KWWK__", "_KKKKKK_")),
    "eye" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("________", "__KKKK__", "_KWWWWK_", "KWWKKWWK", "KWWKKWWK", "_KWWWWK_", "__KKKK__", "________")),
    "crown" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("________", "_K_KK_K_", "_KKKKKK_", "_KYYYYK_", "_KYYYYK_", "KKKKKKKK", "KYYYYYYK", "KKKKKKKK")),
    "cloud" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("________", "__KK____", "_KWWKK__", "KWWWWWK_", "KWWWWWWK", "KWWWWWWK", "_KKKKKK_", "________")),
    "sun" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('R'), listOf("_K_KK_K_", "K_KYYK_K", "_KYYYYK_", "KYYYYYYK", "KYYYYYYK", "_KYYYYK_", "K_KYYK_K", "_K_KK_K_")),
    "bell" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('B'), listOf("___KK___", "__KYYK__", "_KYYYYK_", "_KYYYYK_", "_KYYYYK_", "KYYYYYYK", "KKKKKKKK", "___KK___")),
    "tree" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("___KK___", "__KGGK__", "_KGGGGK_", "KGGGGGGK", "KGGGGGGK", "_KKGGKK_", "__KOOK__", "___KK___"))
)

private val ThreadPixelAvatarRandomOptions = listOf(
    ThreadPixelAvatarPalette.getValue('C') to 'K',
    ThreadPixelAvatarPalette.getValue('Y') to 'K',
    ThreadPixelAvatarPalette.getValue('L') to 'K',
    ThreadPixelAvatarPalette.getValue('P') to 'W',
    ThreadPixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'G', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'P', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'Y', // raft-color: pixel-art avatar palette
    Color(0xFF1E1E1C) to 'V', // raft-color: pixel-art avatar palette
    ThreadPixelAvatarPalette.getValue('O') to 'K',
    ThreadPixelAvatarPalette.getValue('C') to 'W'
)

private fun String.toThreadPixelAvatarSpec(): ThreadPixelAvatarSpec? =
    trim()
        .takeIf { it.isNotBlank() }
        ?.toThreadPixelAvatarSpecFromNormalized()

private fun String.toThreadPixelAvatarSpecFromNormalized(): ThreadPixelAvatarSpec? =
    removeThreadPrefixOrNull(ThreadPixelAvatarProtocolPrefix)?.let { key ->
        key.removeThreadPrefixOrNull(ThreadPixelAvatarRandomPrefix)?.let { seed ->
            return seed.takeIf { it.isNotBlank() }?.let(::randomThreadPixelAvatar)
        }
        ThreadPixelAvatarFixedSpecs[key] ?: ThreadPixelAvatarFixedSpecs.getValue(ThreadPixelAvatarDefaultKey)
    }

private fun String.removeThreadPrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)

private fun randomThreadPixelAvatar(seed: String): ThreadPixelAvatarSpec {
    val random = ThreadPixelAvatarRandom(threadPixelHash(seed))
    val (background, fill) = ThreadPixelAvatarRandomOptions[(random.next() * ThreadPixelAvatarRandomOptions.size).toInt()]
    val grid = List(ThreadPixelAvatarGridCellCount) {
        val left = List(ThreadPixelAvatarGridCellCount / 2) {
            if (random.next() < 0.45) fill else '_'
        }
        (left + left.asReversed()).joinToString(separator = "")
    }
    return ThreadPixelAvatarSpec(background, grid)
}

private fun threadPixelHash(input: String): Int {
    var hash = 0
    input.forEach { character ->
        hash = (hash shl 5) - hash + character.code
    }
    return hash
}

private class ThreadPixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}

internal fun RaftThreadMessageCommentRef.hasThreadCommentHostSource(): Boolean {
    val source = hostSource ?: return false
    if (hostMessageId.isNullOrBlank() || source.channelId.isBlank()) return false
    return when (source.type) {
        RaftThreadSurfaceKind.Channel -> source.routeKind == RaftThreadSurfaceKind.Channel || source.routeKind == RaftThreadSurfaceKind.DirectMessage
        RaftThreadSurfaceKind.ReplyThread ->
            (source.routeKind == RaftThreadSurfaceKind.Channel || source.routeKind == RaftThreadSurfaceKind.DirectMessage) &&
                (!source.parentMessageId.isNullOrBlank() || !source.rootThreadChannelId.isNullOrBlank())
        else -> false
    }
}

@Composable
internal fun ThreadInlineMetaTag(label: String) {
    Box(
        modifier =
            Modifier
                // raft muted chip: fill-muted on the chip radius, no ink edge.
                .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Chip))
                .padding(horizontal = ThreadInlineMetaTagHorizontalPadding, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Anchor/Quote tag text inset (the retired legacy field's 10dp text inset).
private val ThreadInlineMetaTagHorizontalPadding: Dp = 10.dp

@Composable
internal fun ThreadMessageCommentRefChip(
    ref: RaftThreadMessageCommentRef,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val label =
        buildString {
            append("re: ")
            append(ref.filename)
            ref.anchorLabel?.takeIf { it.isNotBlank() }?.let { anchor ->
                append(" · ")
                append(anchor)
            }
        }
    Row(
        modifier =
            modifier
                // raft reference chip: fill-muted on the chip radius with a hairline. The fill
                // comes before the tap so the press shows over it.
                .raftControlSurface(
                    fill = RaftColor.FillMuted.copy(alpha = if (enabled) 1f else 0.7f),
                    border = RaftColor.BorderHairline,
                    radius = RaftAppRadius.Chip,
                    borderWidth = RaftAppStroke.Thin
                )
                .then(if (enabled) Modifier.raftClickable(onClick = onOpen, shape = RoundedCornerShape(RaftAppRadius.Chip)) else Modifier)
                .padding(horizontal = RaftAppSpacing.Xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.Comment,
            modifier = Modifier.size(12.dp),
            color = if (enabled) "#FF111827" else "#FF6B7280"
        )
        Text(
            text = label,
            style =
                RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray
                ),
            color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
