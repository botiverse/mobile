package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftKbd
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/*
 * Quote Reply v1 one-line quote chips (botiverse/slock #16210, `QuoteChip.tsx` + `quoteChipModel.ts` +
 * `QuoteReplyPreview.tsx` + `ComposerQuoteBlocks.tsx`):
 *
 * One chip in both message rows and the composer:
 * - Author's 14dp (`RaftAvatarSize.Xxxs`, web `preview-mini` / `3xs`) avatar when the author is
 *   certain (loaded message by id, else exact single display-name match; never guess between
 *   ambiguous names), semibold author capped at 144dp, and single-line truncated excerpt (`…` prefix
 *   for selected passages) in `footnote` `foregroundMuted` on `fillMuted`.
 * - Phone / touch (`!isDesktopMouse`): full width, 30dp tall so trailing × buttons align in one
 *   column. Desktop + mouse (`RaftScale.Wide && RaftPointerInput.Fine`): hugs content up to 360dp,
 *   26dp tall.
 * - Composer chips add inline icon controls inside the chip frame: optional `Reply below`
 *   (`MessageSquarePlus`, only when directly followed by another quote) before `×` (`X`).
 * - Armed state (1st Backspace at caret 0 below a chip): `dangerSoft` fill + 1dp `dangerStrong`
 *   border + inline armed hint (`Press <kbd> again to remove`) beside/below the chip.
 */

// not a token: web `quoteChipModel.ts` `h-[30px]` / `md:pointer-fine:h-[26px]` / `max-w-[360px]` / `max-w-36`
private val QuoteChipHeightPhone = 30.dp
private val QuoteChipHeightDesktop = 26.dp
private val QuoteChipDesktopMaxWidth = 360.dp
private val QuoteChipAuthorMaxWidth = 144.dp
private val QuoteChipIconButtonSizePhone = 28.dp
private val QuoteChipIconButtonSizeDesktop = 22.dp
private val QuoteChipIconGlyphSizePhone = 16.dp
private val QuoteChipIconGlyphSizeDesktop = 14.dp
private const val QuietQuoteBadgeThreshold = 7
private const val BackspaceKeyPlaceholder = "\u0000BACKSPACE_KEY\u0000"
private const val BackspaceGlyphApple = "⌫"
private const val BackspaceGlyphDefault = "Backspace"

internal fun quoteChipAvatarTestTag(): String = "quote_chip_avatar"

internal fun threadQuoteReplyChipTestTag(blockIndex: Int): String = "thread_quote_reply_chip_$blockIndex"

internal fun composerQuoteRowTestTag(quoteOrdinal: Int, quoteCount: Int): String =
    if (quoteOrdinal == 0 && quoteCount == 1) "composer_quote_bar" else "composer_quote_card_$quoteOrdinal"

internal fun composerQuoteChipTestTag(quoteOrdinal: Int): String = "composer_quote_chip_$quoteOrdinal"

internal fun composerQuoteRemoveTestTag(quoteOrdinal: Int): String =
    if (quoteOrdinal == 0) "composer_quote_clear" else "composer_quote_remove_$quoteOrdinal"

internal fun composerQuoteInsertBelowTestTag(quoteOrdinal: Int): String = "composer_quote_insert_below_$quoteOrdinal"

internal fun composerQuoteArmedHintTestTag(quoteOrdinal: Int): String = "composer_quote_armed_hint_$quoteOrdinal"

internal const val ComposerQuoteBlocksTestTag = "composer_quote_blocks"
internal const val ComposerQuoteCountBadgeTestTag = "composer_quote_count"

/** Resolved author avatar for a quote chip (web `QuoteChipAvatar`). */
@Immutable
internal data class QuoteChipAuthorAvatar(
    val kind: RaftAvatarKind,
    val avatarWireValue: String?,
    val label: String,
)

/** Lookup function resolving a quote chip's author avatar, or null when uncertain. */
@Immutable
internal fun interface ThreadQuoteChipAvatarLookup {
    fun resolve(messageId: String, authorName: String, authorType: String): QuoteChipAuthorAvatar?

    companion object {
        val Empty = ThreadQuoteChipAvatarLookup { _, _, _ -> null }
    }
}

/**
 * Pure avatar disambiguation rule ported verbatim from web `quoteChipModel.ts` `useQuoteAuthorAvatar`:
 * 1. If the quoted message is loaded (exact UUID match, or unique shortId prefix match), use its
 *    sender by id (a missing profile still draws that kind's placeholder: the author is known).
 * 2. Else if exactly one agent (for `authorType == "agent"`) or one person (for `authorType == "user"`)
 *    has that exact display name, use that one (preferring [currentUser]'s own profile avatar).
 * 3. Else (no match or ambiguous duplicate names), return null and draw no avatar. Never guess.
 */
internal fun resolveQuoteAuthorAvatar(
    messageId: String,
    authorName: String,
    authorType: String,
    loadedMessages: List<RaftThreadMessageItem>,
    members: List<MemberProfile>,
    currentUser: StoredAuthUser?,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
): QuoteChipAuthorAvatar? {
    val cleanId = messageId.trim()
    val loadedSender =
        if (cleanId.isNotEmpty()) {
            loadedMessages.firstOrNull { message ->
                message.id.equals(cleanId, ignoreCase = true) ||
                    (cleanId.length == 8 && message.id.startsWith(cleanId, ignoreCase = true))
            }
        } else {
            null
        }
    if (loadedSender != null) {
        val senderKind = loadedSender.authorKind.lowercase()
        return when {
            senderKind == "agent" ->
                QuoteChipAuthorAvatar(
                    kind = RaftAvatarKind.Agent,
                    avatarWireValue = memberAvatarResolver.current(loadedSender.authorId) ?: loadedSender.avatarWireValue,
                    label = authorName,
                )
            senderKind == "human" || senderKind == "user" -> {
                val url =
                    if (currentUser != null && loadedSender.authorId == currentUser.id) {
                        currentUser.avatarUrl
                    } else {
                        memberAvatarResolver.current(loadedSender.authorId) ?: loadedSender.avatarWireValue
                    }
                QuoteChipAuthorAvatar(
                    kind = RaftAvatarKind.Human,
                    avatarWireValue = url,
                    label = authorName,
                )
            }
            else ->
                QuoteChipAuthorAvatar(
                    kind = RaftAvatarKind.Labeled,
                    avatarWireValue = loadedSender.externalAuthor?.avatarUrl ?: loadedSender.avatarWireValue,
                    label = authorName,
                )
        }
    }

    val cleanName = authorName.trim()
    if (cleanName.isEmpty()) return null

    if (authorType.equals("agent", ignoreCase = true)) {
        val matches =
            members.filter { member ->
                member.kind == MemberKind.Agent && member.quoteChipDisplayName() == cleanName
            }
        if (matches.size != 1) return null
        val only = matches.first()
        return QuoteChipAuthorAvatar(
            kind = RaftAvatarKind.Agent,
            avatarWireValue = memberAvatarResolver.current(only.id.value) ?: only.avatar?.wireValue,
            label = cleanName,
        )
    }

    if (authorType.equals("user", ignoreCase = true) || authorType.equals("human", ignoreCase = true)) {
        val candidates =
            members
                .filter { member ->
                    member.kind == MemberKind.Human && member.quoteChipDisplayName() == cleanName
                }
                .map { member -> member.id.value to (memberAvatarResolver.current(member.id.value) ?: member.avatar?.wireValue) }
                .toMutableList()
        if (
            currentUser != null &&
            currentUser.bestDisplayName.trim() == cleanName &&
            candidates.none { (id, _) -> id == currentUser.id }
        ) {
            candidates += currentUser.id to currentUser.avatarUrl
        }
        if (candidates.size != 1) return null
        val (onlyId, onlyUrl) = candidates.first()
        val finalUrl =
            if (currentUser != null && onlyId == currentUser.id) {
                currentUser.avatarUrl
            } else {
                onlyUrl
            }
        return QuoteChipAuthorAvatar(
            kind = RaftAvatarKind.Human,
            avatarWireValue = finalUrl,
            label = cleanName,
        )
    }

    return null
}

private fun MemberProfile.quoteChipDisplayName(): String =
    displayName.trim().ifEmpty { handle.trim().removePrefix("@") }

/**
 * Shared one-line quote chip used in both message rows ([ThreadQuoteReplyChip]) and the multi-quote
 * composer ([ThreadComposerQuoteBlocks]).
 */
@Composable
internal fun ThreadQuoteChipFrame(
    authorName: String,
    excerpt: String,
    avatar: QuoteChipAuthorAvatar?,
    chipLabel: String,
    modifier: Modifier = Modifier,
    mainModifier: Modifier = Modifier,
    onJump: (() -> Unit)? = null,
    jumpLabel: String? = null,
    armed: Boolean = false,
    flashing: Boolean = false,
    showInsertBelow: Boolean = false,
    insertBelowModifier: Modifier = Modifier,
    onInsertBelow: (() -> Unit)? = null,
    showRemove: Boolean = false,
    removeModifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val chipShape = RaftTheme.shapes.chip
    val stickerShape = RaftTheme.shapes.sticker
    val isDesktopMouse = RaftTheme.scale == RaftScale.Wide && RaftTheme.pointerInput == RaftPointerInput.Fine
    val chipHeight = if (isDesktopMouse) QuoteChipHeightDesktop else QuoteChipHeightPhone
    val iconButtonSize = if (isDesktopMouse) QuoteChipIconButtonSizeDesktop else QuoteChipIconButtonSizePhone
    val iconGlyphSize = if (isDesktopMouse) QuoteChipIconGlyphSizeDesktop else QuoteChipIconGlyphSizePhone

    val mainInteraction = remember { MutableInteractionSource() }
    val mainHovered by mainInteraction.collectIsHoveredAsState()
    val mainPressed by mainInteraction.collectIsPressedAsState()
    val interactive = onJump != null

    val fillColor =
        when {
            armed -> colors.dangerSoft
            flashing -> colors.infoSoft
            interactive && (mainHovered || mainPressed) -> colors.fillStrong
            else -> colors.fillMuted
        }
    val hasTrailingControls = (showInsertBelow && onInsertBelow != null) || (showRemove && onRemove != null)

    val frameSizeModifier =
        if (isDesktopMouse) {
            Modifier.height(chipHeight).widthIn(max = QuoteChipDesktopMaxWidth)
        } else {
            Modifier.fillMaxWidth().height(chipHeight)
        }

    Row(
        modifier =
            modifier
                .then(frameSizeModifier)
                .clip(chipShape)
                .background(fillColor, chipShape)
                .then(
                    if (armed) {
                        Modifier.border(RaftStroke.Hairline, colors.dangerStrong, chipShape)
                    } else {
                        Modifier
                    }
                )
                .padding(end = if (hasTrailingControls) 2.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mainRowModifier =
            mainModifier
                .fillMaxHeight()
                .then(if (isDesktopMouse) Modifier.weight(1f, fill = false) else Modifier.weight(1f))
                .clip(chipShape)
                .then(
                    if (onJump != null) {
                        Modifier.raftClickable(
                            onClick = onJump,
                            role = Role.Button,
                            onClickLabel = jumpLabel ?: chipLabel,
                            shape = chipShape,
                            tier = RaftHoverTier.None,
                            interactionSource = mainInteraction,
                        )
                    } else {
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = chipLabel
                        }
                    }
                )
                .padding(horizontal = 6.dp)

        Row(
            modifier = mainRowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (avatar != null) {
                Box(
                    modifier =
                        Modifier
                            .testTag(quoteChipAvatarTestTag())
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    RaftAppAvatarSticker(
                        kind = avatar.kind,
                        avatarWireValue = avatar.avatarWireValue,
                        label = avatar.label,
                        size = RaftAvatarSize.Xxxs,
                    )
                }
            }
            Text(
                text = authorName,
                modifier = Modifier.widthIn(max = QuoteChipAuthorMaxWidth),
                style = typography.footnote.withRaftFontWeight(FontWeight.SemiBold),
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = excerpt,
                modifier = if (isDesktopMouse) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (showInsertBelow && onInsertBelow != null) {
            val insertLabel = stringResource(Res.string.thread_quote_reply_insert_below)
            QuoteChipIconButton(
                icon = RaftLucideIconName.MessageSquarePlus,
                contentDescription = insertLabel,
                buttonSize = iconButtonSize,
                glyphSize = iconGlyphSize,
                tint = colors.foregroundHint,
                shape = stickerShape,
                modifier = insertBelowModifier,
                onClick = onInsertBelow,
            )
        }

        if (showRemove && onRemove != null) {
            val removeLabel = stringResource(Res.string.thread_quote_reply_clear_quote)
            QuoteChipIconButton(
                icon = RaftLucideIconName.X,
                contentDescription = removeLabel,
                buttonSize = iconButtonSize,
                glyphSize = iconGlyphSize,
                tint = if (armed) colors.dangerStrong else colors.foregroundHint,
                shape = stickerShape,
                modifier = removeModifier,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun QuoteChipIconButton(
    icon: RaftLucideIconName,
    contentDescription: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    glyphSize: androidx.compose.ui.unit.Dp,
    tint: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = RaftTheme.colors
    val iconColor = if (hovered) colors.foreground else tint

    Box(
        modifier =
            modifier
                .size(buttonSize)
                .clip(shape)
                .focusProperties { canFocus = false }
                .raftClickable(
                    onClick = onClick,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    shape = shape,
                    tier = RaftHoverTier.Medium,
                    interactionSource = interaction,
                )
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        RaftSlotIcon(name = icon, size = glyphSize, color = iconColor)
    }
}

/**
 * Armed Backspace delete hint (web `ComposerQuoteBlocks` `backspaceAgainToRemove`, #16059 / #16210):
 * renders localized `"Press <Kbd> again to remove"` with [RaftKbd] placed where `%1$s` sits in the
 * locale's grammar.
 */
@Composable
internal fun ThreadQuoteArmedHint(
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val keyGlyph = if (raftPlatformShortcutsUseCommand()) BackspaceGlyphApple else BackspaceGlyphDefault
    val template = stringResource(Res.string.thread_quote_reply_backspace_again, BackspaceKeyPlaceholder)
    val parts = remember(template) { template.split(BackspaceKeyPlaceholder, limit = 2) }
    val before = parts.getOrNull(0).orEmpty().trim()
    val after = parts.getOrNull(1).orEmpty().trim()
    val textStyle = typography.caption.withRaftFontWeight(FontWeight.Medium)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (before.isNotEmpty()) {
            Text(text = before, style = textStyle, color = colors.dangerStrong, maxLines = 1)
        }
        RaftKbd(keyGlyph)
        if (after.isNotEmpty()) {
            Text(text = after, style = textStyle, color = colors.dangerStrong, maxLines = 1)
        }
    }
}

/**
 * Quiet toolbar quote counter (web `ComposerQuoteCountBadge`): stays hidden until approaching the
 * 10-quote cap (`>= 7`), switching to warning at `10/10`.
 */
@Composable
internal fun ThreadComposerQuoteCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count < QuietQuoteBadgeThreshold) return
    val atLimit = count >= MAX_INLINE_QUOTES_PER_MESSAGE
    val badgeText = "$count/$MAX_INLINE_QUOTES_PER_MESSAGE"
    val label = stringResource(Res.string.thread_quote_reply_count_label, count, MAX_INLINE_QUOTES_PER_MESSAGE)
    RaftBadge(
        text = badgeText,
        appearance = RaftBadgeAppearance.Soft,
        variant = if (atLimit) RaftBadgeVariant.Warning else RaftBadgeVariant.Muted,
        modifier =
            modifier
                .testTag(ComposerQuoteCountBadgeTestTag)
                .semantics { contentDescription = label },
    )
}
