package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ActionCardMsgSegment
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecContent
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecEmphasis
import build.raft.app.data.ActionCardSpecEntityKind
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.data.ActionCardSpecFieldValue
import build.raft.app.data.ActionCardSpecIcon
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecNoticeTone
import build.raft.app.data.ActionCardSpecTextTone
import build.raft.app.data.AppLocale
import build.raft.app.data.actionCardDisplayLocale
import build.raft.app.data.currentSystemLanguageTag
import build.raft.app.data.CardDuration
import build.raft.app.data.CardElement
import build.raft.app.data.CardRelativeTime
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.CardStackRole
import build.raft.app.data.RAFT_APP_LINK_CARD_MAX_DEPTH
import build.raft.app.data.RaftAppLinkCardElement
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.app.data.appLinkDomain
import build.raft.app.data.actionCardScopeGroupLabel
import build.raft.app.data.renderActionCardMsg
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftMessageActionCardHint
import build.raft.designsystem.components.RaftMessageActionCardNotice
import build.raft.designsystem.components.RaftMessageActionCardProperties
import build.raft.designsystem.components.RaftMessageActionCardProperty
import build.raft.designsystem.components.RaftMessageActionCardScope
import build.raft.designsystem.components.RaftMessageActionCardScopeList
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Clock
import kotlinx.coroutines.delay

/**
 * What a card tree needs from the place that draws it. Link cards ([RaftAppLinkCardBody]) use
 * the defaults; action cards ([RaftActionCardView]) pass the viewer's locale, the store's names
 * and the frame's button state.
 *
 * - [displayLocale]: the contract locale key a [ActionCardSpecMsg] is read in (`en`, `zh-cn`,
 *   `ja`), see [rememberActionCardDisplayLocale].
 * - [entityName]: the name the app's store has for an entity (member, agent, channel or computer),
 *   or null when this viewer can't see it, which shows the card's `fallback`. Never a UUID.
 * - [onAction]: a Button was tapped; its element id.
 * - [buttonState]: whether the frame shows, enables or busies a button (a frozen card's, a
 *   view-only viewer's, the one running).
 */
@Stable
internal class RaftCardTreeContext(
    val displayLocale: String = "en",
    val entityName: (ActionCardSpecEntityRef) -> String? = { null },
    val onAction: (elementId: String) -> Unit = {},
    val columnGap: Dp = 6.dp,
    val buttonState: (elementId: String, button: ActionCardSpecButton) -> RaftCardButtonState = { _, _ -> RaftCardButtonState.Enabled },
)

/** How the frame lets a card Button show. */
internal enum class RaftCardButtonState { Enabled, Disabled, Busy, Hidden }

/**
 * A json-render card tree from [root], one renderer for both catalogs (link cards:
 * `appPreviewCardSchema` v1, [RaftAppLinkCardElement]; action cards: `action-card.v1`, the
 * [ActionCardSpecTree][build.raft.app.data.ActionCardSpecTree] elements and the shared
 * [CardStack] / [CardRelativeTime] / [CardDuration]). The tree arrives validated (the parse
 * layer refuses anything else), so a missing id, a node past depth 4 or an element this client
 * doesn't know draws nothing.
 *
 * - Link card v1: a Stack is a column [RaftCardTreeContext.columnGap] apart; a Text is callout in
 *   `foreground` or, muted, `foreground-muted`; a Badge a neutral soft badge (DS PreviewCard).
 * - `action-card.v1` (web `LinkCardElements`, raft-ui MessageActionCard parts): a column Stack
 *   lays consecutive Fields out as one [RaftMessageActionCardProperties] and consecutive
 *   Buttons as one [RaftMessageActionCardActions]; a row Stack of Buttons is the actions row,
 *   any other row keeps its children on one line (texts truncate) or wraps when asked. A Stack
 *   with `role` (schemaVersion 6) is that part of the card: a `reason` column of one or two
 *   Texts is the quoted hint ([RaftMessageActionCardHint]), an `actions` row of Buttons the
 *   actions row; another shape under a role is the plain Stack. Text is
 *   callout (mono: `text-mono-meta`) in its tone, cut at `clamp` lines; a Badge is neutral and
 *   soft whatever its tone (DS PreviewCard); RelativeTime and Duration are `text-mono-meta`
 *   muted; an EntityRef is its name; ScopeList and Notice are the card's scope list and notice.
 *   See [RaftCardButton] for Buttons.
 */
@Composable
internal fun RaftCardTree(root: String, elements: Map<String, CardElement>, context: RaftCardTreeContext) {
    RaftCardNode(elements, root, depth = 0, inRow = false, context)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaftCardNode(
    elements: Map<String, CardElement>,
    id: String,
    depth: Int,
    inRow: Boolean,
    context: RaftCardTreeContext,
) {
    if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    when (val node = elements[id]) {
        // Link card v1.
        is RaftAppLinkCardElement.Stack ->
            Column(verticalArrangement = Arrangement.spacedBy(context.columnGap)) {
                node.children.forEach { RaftCardNode(elements, it, depth + 1, inRow = false, context) }
            }
        is RaftAppLinkCardElement.Text ->
            RaftText(
                text = node.text,
                style = typography.callout,
                color = if (node.tone == RaftAppLinkTextTone.Muted) colors.foregroundMuted else colors.foreground,
            )
        is RaftAppLinkCardElement.Badge -> RaftBadge(text = node.text, appearance = RaftBadgeAppearance.Soft)
        // Shared with link card v2, then action-card.v1.
        is CardStack ->
            when (val layout = actionCardStackLayout(node, elements)) {
                is RaftCardStackLayout.Reason -> RaftCardReason(layout.texts, context)
                RaftCardStackLayout.Actions -> RaftCardButtons(elements, node.childIds, context)
                RaftCardStackLayout.Column -> RaftCardColumn(elements, node.childIds, depth, context)
                RaftCardStackLayout.WrapRow ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        node.childIds.forEach { RaftCardNode(elements, it, depth + 1, inRow = true, context) }
                    }
                RaftCardStackLayout.Row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        node.childIds.forEach { RaftCardNode(elements, it, depth + 1, inRow = true, context) }
                    }
            }
        is CardRelativeTime ->
            RaftText(
                text = raftRelativeTimestampLabel(node.at, clampFuture = true),
                style = typography.monoMeta,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        is CardDuration -> RaftCardDuration(node)
        is ActionCardSpecDisplayElement.Text -> {
            val color =
                when (node.tone) {
                    ActionCardSpecTextTone.Default -> colors.foreground
                    ActionCardSpecTextTone.Muted -> colors.foregroundMuted
                    // As raft-ui's own warning lines (web LinkCardElements): warning-strong.
                    ActionCardSpecTextTone.Warning -> colors.warningStrong
                }
            RaftText(
                text = actionCardContentText(node.text, context),
                style = if (node.mono) typography.monoMeta else typography.callout,
                color = color,
                maxLines = if (inRow) 1 else node.clamp ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is ActionCardSpecDisplayElement.Badge ->
            RaftBadge(text = actionCardContentText(node.text, context).text, appearance = RaftBadgeAppearance.Soft)
        is ActionCardSpecDisplayElement.EntityRef ->
            RaftText(
                text = buildAnnotatedString { appendActionCardEntity(node.ref, context) },
                style = typography.callout,
                color = colors.foreground,
                maxLines = if (inRow) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        is ActionCardSpecDisplayElement.Field -> RaftCardFields(listOf(node), context)
        is ActionCardSpecDisplayElement.ScopeList -> RaftCardScopeList(node)
        is ActionCardSpecDisplayElement.Notice -> RaftCardNotice(node, context)
        is ActionCardSpecButton -> RaftCardButtons(elements, listOf(id), context)
        // A missing id, or an element of a later catalog (the parse layer never lets one through).
        else -> Unit
    }
}

/** How [RaftCardNode] draws a [CardStack] ([actionCardStackLayout]). */
internal sealed interface RaftCardStackLayout {
    /** A `reason` Stack: the quoted hint. */
    data class Reason(val texts: RaftCardReasonTexts) : RaftCardStackLayout

    /** The card's actions row (raft-ui MessageActionCardActions: bottom right). */
    data object Actions : RaftCardStackLayout

    /** A column: runs of Fields and Buttons grouped ([RaftCardColumn]). */
    data object Column : RaftCardStackLayout

    data object WrapRow : RaftCardStackLayout

    data object Row : RaftCardStackLayout
}

/**
 * How [stack] is drawn. A `role` (schemaVersion 6; the parse keeps it only on its own
 * direction) comes first: a `reason` of one or two Texts is the hint, an `actions` row of
 * Buttons the actions row; any other shape under a role is the plain Stack. Only an explicit
 * role makes a hint: a Stack without one is never taken for a reason by its shape (unlike the
 * web's reading of cards stored before schemaVersion 6). A row of only Buttons was the actions
 * row before roles and still is.
 */
internal fun actionCardStackLayout(stack: CardStack, elements: Map<String, CardElement>): RaftCardStackLayout {
    val allButtons = stack.childIds.all { elements[it] is ActionCardSpecButton }
    return when {
        stack.role == CardStackRole.Reason -> actionCardReasonTexts(elements, stack.childIds)?.let { RaftCardStackLayout.Reason(it) }
        stack.role == CardStackRole.Actions && allButtons -> RaftCardStackLayout.Actions
        else -> null
    } ?: when {
        stack.direction == CardStackDirection.Column -> RaftCardStackLayout.Column
        allButtons -> RaftCardStackLayout.Actions
        stack.wrap -> RaftCardStackLayout.WrapRow
        else -> RaftCardStackLayout.Row
    }
}

/** A `reason` Stack's texts: its note, under an optional label. */
internal data class RaftCardReasonTexts(
    val label: ActionCardSpecDisplayElement.Text?,
    val note: ActionCardSpecDisplayElement.Text,
)

/**
 * The texts of a `reason` Stack ([children]): one Text (the note) or two (a label, then the note),
 * as web `reasonHint`. Null for any other shape, which is drawn as the plain column it is.
 */
internal fun actionCardReasonTexts(elements: Map<String, CardElement>, children: List<String>): RaftCardReasonTexts? {
    val texts = children.map { elements[it] as? ActionCardSpecDisplayElement.Text ?: return null }
    return when (texts.size) {
        1 -> RaftCardReasonTexts(label = null, note = texts[0])
        2 -> RaftCardReasonTexts(label = texts[0], note = texts[1])
        else -> null
    }
}

/**
 * A `reason` Stack (web `reasonHint`): the agent's words as the DS quote, raft-ui
 * MessageActionCardHint ([RaftMessageActionCardHint]), its label (plain) above. The note keeps
 * its `clamp`; the tones are the hint's own.
 */
@Composable
private fun RaftCardReason(texts: RaftCardReasonTexts, context: RaftCardTreeContext) {
    RaftMessageActionCardHint(
        text = actionCardContentText(texts.note.text, context),
        label = texts.label?.let { actionCardContentText(it.text, context).text },
        maxLines = texts.note.clamp ?: Int.MAX_VALUE,
    )
}

/** A column Stack: runs of Fields are one property list, runs of Buttons one actions row. */
@Composable
private fun RaftCardColumn(
    elements: Map<String, CardElement>,
    children: List<String>,
    depth: Int,
    context: RaftCardTreeContext,
) {
    if (depth + 1 > RAFT_APP_LINK_CARD_MAX_DEPTH) return
    val runs = remember(children, elements) { actionCardColumnRuns(elements, children) }
    Column(verticalArrangement = Arrangement.spacedBy(context.columnGap)) {
        runs.forEach { run ->
            when (run) {
                is RaftCardColumnRun.Fields ->
                    RaftCardFields(run.ids.mapNotNull { elements[it] as? ActionCardSpecDisplayElement.Field }, context)
                is RaftCardColumnRun.Buttons -> RaftCardButtons(elements, run.ids, context)
                is RaftCardColumnRun.One -> RaftCardNode(elements, run.id, depth + 1, inRow = false, context)
            }
        }
    }
}

internal sealed interface RaftCardColumnRun {
    data class Fields(val ids: List<String>) : RaftCardColumnRun

    data class Buttons(val ids: List<String>) : RaftCardColumnRun

    data class One(val id: String) : RaftCardColumnRun
}

/** A column's children, consecutive Fields and consecutive Buttons each grouped. */
internal fun actionCardColumnRuns(elements: Map<String, CardElement>, children: List<String>): List<RaftCardColumnRun> {
    val runs = mutableListOf<RaftCardColumnRun>()
    children.forEach { id ->
        val last = runs.lastOrNull()
        when (elements[id]) {
            is ActionCardSpecDisplayElement.Field ->
                if (last is RaftCardColumnRun.Fields) runs[runs.lastIndex] = last.copy(ids = last.ids + id) else runs += RaftCardColumnRun.Fields(listOf(id))
            is ActionCardSpecButton ->
                if (last is RaftCardColumnRun.Buttons) runs[runs.lastIndex] = last.copy(ids = last.ids + id) else runs += RaftCardColumnRun.Buttons(listOf(id))
            else -> runs += RaftCardColumnRun.One(id)
        }
    }
    return runs
}

/** Fields as one raft-ui MessageActionCardProperties list (label over value on a phone). */
@Composable
private fun RaftCardFields(fields: List<ActionCardSpecDisplayElement.Field>, context: RaftCardTreeContext) {
    if (fields.isEmpty()) return
    val colors = RaftTheme.colors
    RaftMessageActionCardProperties {
        fields.forEach { field ->
            RaftMessageActionCardProperty(
                label = actionCardMsgText(field.label, context).text,
                value =
                    when (val value = field.value) {
                        is ActionCardSpecFieldValue.Plain -> value.text
                        is ActionCardSpecFieldValue.Message -> actionCardMsgText(value.msg, context).text
                        is ActionCardSpecFieldValue.Entities -> value.refs.joinToString(", ") { actionCardEntityMention(it, context) }
                    },
                mono = field.mono == true,
                valueColor = if (field.muted == true) colors.foregroundHint else Color.Unspecified,
                // S0.1: at most `clamp` lines (1-6), with an ellipsis.
                valueMaxLines = field.clamp ?: Int.MAX_VALUE,
            )
        }
    }
}

/**
 * The permissions an app asks for (web `ActionCardScopes`): "Permissions (N)", then each scope
 * with Raft's sentence for it; when Raft describes none of them, their names on one mono line.
 * S0.2 `groups` (permission groups, A1's [actionCardScopeGroupLabel]: "Servers · read") follow
 * as rows of their own, a group this build has no name for under its id. The web does not draw
 * them yet; nothing emits them before S1.
 */
@Composable
private fun RaftCardScopeList(node: ActionCardSpecDisplayElement.ScopeList) {
    val scopes = remember(node.scopes) { node.scopes.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
    val groups = node.groups.orEmpty()
    val label = stringResource(Res.string.thread_action_card_permissions, scopes.size)
    val described = scopes.any { it.lowercase() in RaftCardDescribedScopes }
    RaftMessageActionCardScopeList(label = label, viewportDescription = label.takeIf { described || groups.isNotEmpty() }) {
        if (described) {
            scopes.forEach { scope -> RaftMessageActionCardScope(scope, raftCardScopeDescription(scope)) }
        } else {
            RaftText(
                text = scopes.joinToString(", "),
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foreground,
            )
        }
        groups.forEach { group ->
            RaftMessageActionCardScope(name = actionCardScopeGroupLabel(group)?.let { stringResource(it) } ?: group)
        }
    }
}

private val RaftCardDescribedScopes = setOf("identity", "openid", "profile")

@Composable
private fun raftCardScopeDescription(scope: String): String =
    when (scope.lowercase()) {
        "identity" -> stringResource(Res.string.thread_action_card_scope_identity)
        "openid" -> stringResource(Res.string.thread_action_card_scope_openid)
        "profile" -> stringResource(Res.string.thread_action_card_scope_profile)
        else -> stringResource(Res.string.thread_action_card_scope_other)
    }

/** raft-ui MessageActionCardNotice: neutral; a warning one leads with the alert icon. */
@Composable
private fun RaftCardNotice(node: ActionCardSpecDisplayElement.Notice, context: RaftCardTreeContext) {
    val muted = raftArgbHex(RaftTheme.colors.foregroundMuted)
    RaftMessageActionCardNotice(
        text = actionCardMsgText(node.text, context).text,
        icon =
            if (node.tone == ActionCardSpecNoticeTone.Warning) {
                { RaftLucideIcon(RaftLucideIconName.TriangleAlert, color = muted, modifier = Modifier.size(14.dp)) }
            } else {
                null
            },
    )
}

/** Buttons as one raft-ui MessageActionCardActions row; the first reason a button is disabled is its note. */
@Composable
private fun RaftCardButtons(elements: Map<String, CardElement>, ids: List<String>, context: RaftCardTreeContext) {
    val buttons =
        ids.mapNotNull { id ->
            val button = elements[id] as? ActionCardSpecButton ?: return@mapNotNull null
            // Strip has made every button this App can't handle "Open in web"; none is left unsupported.
            if (button.action is ActionCardSpecAction.Unsupported) return@mapNotNull null
            val state = context.buttonState(id, button)
            if (state == RaftCardButtonState.Hidden) null else Triple(id, button, state)
        }
    if (buttons.isEmpty()) return
    val note = buttons.firstNotNullOfOrNull { (_, button, _) -> button.disabledReason }?.let { actionCardMsgText(it, context).text }
    RaftMessageActionCardActions(note = note) {
        buttons.forEach { (id, button, state) -> RaftCardButton(id, button, state, context) }
    }
}

/**
 * A card Button: its label on one line, truncated when the card is narrow, the whole label in a
 * tooltip on a long press (and in semantics). A button to a URL outside Raft shows the link's
 * domain after its label, as link previews write it (no `www.`, its end kept). A tap reports
 * the element id to [RaftCardTreeContext.onAction]. A `disabledReason` disables it.
 *
 * Emphasis: `primary` is the default ink-outline button (raft-ui MessageActionCardActions: the
 * confirm is never pink), `secondary` a ghost button, `destructive` the red one.
 */
@Composable
private fun RaftCardButton(id: String, button: ActionCardSpecButton, state: RaftCardButtonState, context: RaftCardTreeContext) {
    val label = actionCardMsgPlain(button.label, context)
    val domain = (button.action as? ActionCardSpecAction.NavigateUrl)?.let { appLinkDomain(it.url) }?.let { raftAppLinkKeepEnd(it, 40) }
    RaftTooltip(text = if (domain != null) "$label · $domain" else label) {
        RaftButton(
            text = label,
            trailingText = domain,
            onClick = { context.onAction(id) },
            variant =
                when (button.emphasis) {
                    ActionCardSpecEmphasis.Primary -> RaftButtonVariant.Default
                    ActionCardSpecEmphasis.Secondary -> RaftButtonVariant.Ghost
                    ActionCardSpecEmphasis.Destructive -> RaftButtonVariant.Danger
                },
            enabled = button.disabledReason == null && state != RaftCardButtonState.Disabled,
            loading = state == RaftCardButtonState.Busy,
        )
    }
}

/** How long something took (web `LinkCardDuration`): ticking each second while it runs. */
@Composable
private fun RaftCardDuration(node: CardDuration) {
    val start = remember(node.start) { parseRaftTimestampEpochMillisOrNull(node.start, localOffsetMinutes = 0) } ?: return
    val end = remember(node.end) { node.end?.let { parseRaftTimestampEpochMillisOrNull(it, localOffsetMinutes = 0) } }
    val clock = LocalRaftTimeContextProvider.current?.current?.nowEpochMillis
    var now by remember { mutableLongStateOf(clock ?: Clock.System.now().toEpochMilliseconds()) }
    if (end == null && clock == null) {
        LaunchedEffect(node.start) {
            while (true) {
                delay(1_000)
                now = Clock.System.now().toEpochMilliseconds()
            }
        }
    }
    val seconds = (((end ?: clock ?: now) - start).coerceAtLeast(0) / 1_000).toInt()
    val minutes = seconds / 60
    RaftText(
        text =
            when {
                seconds < 60 -> stringResource(Res.string.member_activity_overview_duration_seconds, seconds)
                minutes < 60 -> stringResource(Res.string.member_activity_overview_duration_minutes, minutes, seconds % 60)
                else -> stringResource(Res.string.member_activity_overview_duration_hours, minutes / 60, minutes % 60)
            },
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundMuted,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------
// Msg text and entity names.

/** The contract locale key a card Msg is read in: the app's UI language (A1's [actionCardDisplayLocale]). */
@Composable
internal fun rememberActionCardDisplayLocale(): String {
    val appLocale by KmmResourceLocaleRuntime.locale.collectAsState()
    return remember(appLocale) {
        (appLocale ?: AppLocale.System).actionCardDisplayLocale(runCatching { currentSystemLanguageTag() }.getOrNull())
    }
}

/** An entity's name from the store, else the card's fallback; never its id. */
internal fun actionCardEntityName(ref: ActionCardSpecEntityRef, context: RaftCardTreeContext): String =
    context.entityName(ref)?.trim()?.removePrefix("@")?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: ref.fallback

/** An entity as a mention: `@name` for people and agents, `#name` for channels, a computer's name. */
internal fun actionCardEntityMention(ref: ActionCardSpecEntityRef, context: RaftCardTreeContext): String {
    val name = actionCardEntityName(ref, context)
    return when (ref.kind) {
        ActionCardSpecEntityKind.User, ActionCardSpecEntityKind.Agent -> "@$name"
        ActionCardSpecEntityKind.Channel -> "#$name"
        ActionCardSpecEntityKind.Computer -> name
    }
}

/** A Msg where a slot takes rich text (title, Text, Notice, Field): entities as semibold mentions. */
internal fun actionCardMsgText(msg: ActionCardSpecMsg, context: RaftCardTreeContext): AnnotatedString =
    buildAnnotatedString {
        renderActionCardMsg(msg, context.displayLocale).forEach { segment ->
            when (segment) {
                is ActionCardMsgSegment.Text -> append(segment.text)
                is ActionCardMsgSegment.Ref -> appendActionCardEntity(segment.ref, context)
            }
        }
    }

/** A Msg where a slot takes plain words (a Button label): entities as their plain names. */
internal fun actionCardMsgPlain(msg: ActionCardSpecMsg, context: RaftCardTreeContext): String =
    renderActionCardMsg(msg, context.displayLocale).joinToString("") { segment ->
        when (segment) {
            is ActionCardMsgSegment.Text -> segment.text
            is ActionCardMsgSegment.Ref -> actionCardEntityName(segment.ref, context)
        }
    }

private fun actionCardContentText(content: ActionCardSpecContent, context: RaftCardTreeContext): AnnotatedString =
    when (content) {
        is ActionCardSpecContent.Plain -> AnnotatedString(content.text)
        is ActionCardSpecContent.Message -> actionCardMsgText(content.msg, context)
    }

/** A name is user content: semibold, with the user-content locale (Simplified Chinese Han forms). */
private fun AnnotatedString.Builder.appendActionCardEntity(ref: ActionCardSpecEntityRef, context: RaftCardTreeContext) {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, localeList = RaftUserContentLocaleList)) {
        append(actionCardEntityMention(ref, context))
    }
}

/** The header icon the frame paints (web `SPEC_ICONS`); none draws the default (zap). */
internal fun actionCardIconName(icon: ActionCardSpecIcon?): RaftLucideIconName? =
    when (icon) {
        ActionCardSpecIcon.Hash -> RaftLucideIconName.Hash
        ActionCardSpecIcon.Bot -> RaftLucideIconName.Bot
        ActionCardSpecIcon.UserPlus -> RaftLucideIconName.UserPlus
        ActionCardSpecIcon.Key -> RaftLucideIconName.KeyRound
        ActionCardSpecIcon.App -> RaftLucideIconName.AppWindow
        ActionCardSpecIcon.Package -> RaftLucideIconName.Package
        ActionCardSpecIcon.Message -> RaftLucideIconName.MessageSquare
        ActionCardSpecIcon.Sparkles -> RaftLucideIconName.Sparkles
        ActionCardSpecIcon.Plug -> RaftLucideIconName.Plug
        // As the web's typed cards draw them (ActionTypeIcon): restoring an App owner, upgrading.
        ActionCardSpecIcon.Shield -> RaftLucideIconName.ShieldAlert
        ActionCardSpecIcon.CreditCard -> RaftLucideIconName.CreditCard
        // No icon, or one a later contract adds: the frame's default.
        else -> null
    }
