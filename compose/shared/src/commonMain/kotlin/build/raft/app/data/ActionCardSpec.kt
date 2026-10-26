package build.raft.app.data

import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * `action-card.v1`: Raft's first-party action cards described as data (`presentation.card`).
 *
 * Contract: botiverse/slock 2d901b90 (S0 #11226, S0.1 #11232, S0.2 #11234)
 * `packages/shared/src/actionCardSpec.ts`, pinned by
 * the vendored `actionCard.v1.vector.json` (scripts/check_action_card_v1_vector.sh). An action
 * card keeps its durable row, `confirmationVersion`, state machine and `/api/actions` endpoints;
 * `presentation.card` only describes how it is shown, in a closed, inert catalog: no expressions,
 * bindings or remote resources, every string plain text. Inputs exist only in form sheets
 * ([ActionCardSpecForm]), never in the card body.
 *
 * The frame is the client's: it paints the header ([ActionCardSpecCard.icon] +
 * [ActionCardSpecCard.title]) and the status line from `actionMetadata`.
 *
 * This client reads cards in the contract's "read" mode ([parseActionCardSpec]): the server's
 * strict schema with seven leniencies, so an older App still shows a newer card and turns only the
 * button it cannot handle into "Open in web" ([stripActionCardSpec]). Unknown keys are dropped,
 * except where they could change what a press does (a Button's props, its action, the confirm it
 * opens): there the button becomes "Open in web". Any other problem
 * makes the whole card unreadable (null) and the App shows the legacy presentation. The
 * emit-only rules (the 24,576-byte size) are the server's and are not checked here.
 *
 * Lengths count Unicode code points ([cardCodePoints]).
 */

const val ACTION_CARD_SPEC_CATALOG = "action-card.v1"

/**
 * Every capability the contract names (`ACTION_CARD_SPEC_CAPABILITIES`), in its order. A new
 * field type adds a `field.*` capability; a new catalog is a new name (`action-card.v2`).
 */
val ACTION_CARD_SPEC_CAPABILITIES: List<String> =
    listOf(
        "card.v1", "confirm.v1", "form.v1",
        "field.text_input", "field.text_area", "field.select", "field.toggle", "field.member_picker", "field.channel_picker",
        "field.agent_picker",
        "intent.create_channel", "intent.add_members", "intent.create_agent", "intent.feedback_form", "intent.upgrade_dialog",
    )

/**
 * What this App can do with a card, declared by the App itself (the card is pushed to the whole
 * channel, so the server cannot strip per viewer). The single list [stripActionCardSpec] reads.
 *
 * Rule: a capability is listed only once the App really renders / handles it, in the same PR
 * that ships the feature; a declared capability without its UI would keep a button that does
 * nothing, where strip would have made it "Open in web". `card.v1` covers the elements and the
 * plain execute / navigate buttons; then `confirm.v1` comes with the confirm dialog, `form.v1`
 * and each `field.*` with the form sheet and that field (A3), each `intent.*` when the button
 * dispatch (A4) opens that native dialog. Anything not listed is "Open in web".
 */
val RAFT_APP_ACTION_CARD_CAPABILITIES: List<String> = listOf("card.v1")

/** Tree and size limits (`ACTION_CARD_SPEC_LIMITS`; the root is at depth 0). */
object ActionCardSpecLimits {
    const val MAX_ELEMENTS = 24
    const val MAX_CONFIRM_ELEMENTS = 12
    const val MAX_DEPTH = 4
    const val MAX_BUTTONS = 2
    const val MAX_CONFIRMS = 2
    const val MAX_FORMS = 2
    const val MAX_FIELDS = 12
    const val MAX_OPTIONS = 32
    const val MAX_PARAMS = 8
    const val MAX_PICKER_ITEMS = 50
}

/** The display locales a Msg may carry (`DISPLAY_LOCALES`); `en` is always there. */
val ACTION_CARD_SPEC_LOCALES: List<String> = listOf("en", "es", "pt-br", "ko", "ja", "zh-cn", "zh-tw")

/** Header icons; the frame paints them. An icon this App does not know reads as no icon. */
enum class ActionCardSpecIcon(val wire: String) {
    Hash("hash"),
    Bot("bot"),
    UserPlus("user_plus"),
    Key("key"),
    App("app"),
    Package("package"),
    Plug("plug"),
    Message("message"),
    Sparkles("sparkles"),
    Shield("shield"),
    CreditCard("credit_card"),
}

/** The native dialogs an `open` button opens, prefilled from `metadata.action` as today. */
enum class ActionCardSpecIntent(val wire: String) {
    CreateChannel("create_channel"),
    AddMembers("add_members"),
    CreateAgent("create_agent"),
    FeedbackForm("feedback_form"),
    UpgradeDialog("upgrade_dialog"),
    ;

    /** `intent.<wire>`. */
    val capability: String get() = "intent.$wire"
}

// ---------------------------------------------------------------------------
// Messages.

enum class ActionCardSpecEntityKind(val wire: String) {
    User("user"),
    Agent("agent"),
    Channel("channel"),
    Computer("computer"),
}

/**
 * An entity shown by reference. The client shows the name from its own store when it can see the
 * entity, else [fallback]. [id] is a lowercase canonical UUID. [serverId] absent = the card's own
 * server (`actionMetadata.sourceServerId`), not the viewer's current one: see [serverIdOr].
 */
data class ActionCardSpecEntityRef(
    val kind: ActionCardSpecEntityKind,
    val id: String,
    val serverId: String? = null,
    val fallback: String,
) {
    /** The server the entity lives on: [serverId], else the card's [cardSourceServerId]. */
    fun serverIdOr(cardSourceServerId: String?): String? = serverId ?: cardSourceServerId
}

/** A Msg param: a string (0-500 code points), a safe integer, or an entity. */
sealed interface ActionCardSpecMsgParam {
    data class Text(val value: String) : ActionCardSpecMsgParam

    data class Integer(val value: Long) : ActionCardSpecMsgParam

    data class Entity(val ref: ActionCardSpecEntityRef) : ActionCardSpecMsgParam
}

/**
 * Card copy shown in the viewer's language. The card is broadcast to a whole channel, so it
 * carries its translations: [text] holds `en` always and the other display locales the server
 * has (lowercase keys: `zh-cn`, `pt-br`). A client never looks [key] up (it is for analytics).
 * Templates use `{name}` placeholders filled from [params]; `{{` and `}}` are literal braces.
 * [params] is null when the card did not write the key. Render with [renderActionCardMsg].
 */
data class ActionCardSpecMsg(
    val key: String,
    val text: Map<String, String>,
    val params: Map<String, ActionCardSpecMsgParam>? = null,
)

/** One piece of a rendered Msg ([renderActionCardMsg]). */
sealed interface ActionCardMsgSegment {
    data class Text(val text: String) : ActionCardMsgSegment

    /** A mention / channel chip where the slot allows rich content, the plain name elsewhere (Button labels). */
    data class Ref(val ref: ActionCardSpecEntityRef) : ActionCardMsgSegment
}

/**
 * The contract locale key (`DISPLAY_LOCALES`: `en`, `es`, `pt-br`, `ko`, `ja`, `zh-cn`, `zh-tw`) for
 * a BCP 47 [languageTag] such as [AppLocale.displayLanguageTag]'s: `zh-Hans` / `zh-CN` / `zh` ->
 * `zh-cn`, `zh-Hant` / `zh-TW` / `zh-HK` -> `zh-tw`, `pt` / `pt-BR` -> `pt-br`, otherwise the
 * language subtag when it is a display locale, else `en`.
 */
fun actionCardDisplayLocale(languageTag: String?): String {
    val tag = languageTag?.trim()?.lowercase()?.replace('_', '-').orEmpty()
    if (tag in ACTION_CARD_SPEC_LOCALES) return tag
    val parts = tag.split('-')
    return when (parts.first()) {
        "zh" -> if (parts.drop(1).any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }) "zh-tw" else "zh-cn"
        "pt" -> "pt-br"
        else -> parts.first().takeIf { it in ACTION_CARD_SPEC_LOCALES } ?: "en"
    }
}

/** The contract locale key for the language the App UI is shown in ([AppLocale.displayLanguageTag]). */
fun AppLocale.actionCardDisplayLocale(systemLanguage: String?): String = build.raft.app.data.actionCardDisplayLocale(displayLanguageTag(systemLanguage))

/** The template a viewer reads (`pickActionCardTemplate`): their display locale's, else English. */
fun pickActionCardTemplate(msg: ActionCardSpecMsg, displayLocale: String): String =
    msg.text[displayLocale] ?: msg.text.getValue("en")

/**
 * A Msg's template with its params filled in, as segments (`renderActionCardMsg`): text (a string
 * param, or an integer as plain base-10 digits) and entity references. Adjacent text is merged
 * and empty text dropped. [displayLocale] is a contract locale key (`en`, `zh-cn`, `ja`, ...).
 */
fun renderActionCardMsg(msg: ActionCardSpecMsg, displayLocale: String): List<ActionCardMsgSegment> {
    val template = pickActionCardTemplate(msg, displayLocale)
    val segments = ArrayList<ActionCardMsgSegment>()
    fun pushText(text: String) {
        if (text.isEmpty()) return
        val last = segments.lastOrNull()
        if (last is ActionCardMsgSegment.Text) segments[segments.lastIndex] = ActionCardMsgSegment.Text(last.text + text)
        else segments.add(ActionCardMsgSegment.Text(text))
    }
    for (token in actionCardTemplateTokens(template) ?: listOf(TemplateToken.Text(template))) {
        when (token) {
            is TemplateToken.Text -> pushText(token.text)
            is TemplateToken.Param ->
                when (val param = msg.params?.get(token.name)) {
                    null -> pushText("{${token.name}}")
                    is ActionCardSpecMsgParam.Text -> pushText(param.value)
                    is ActionCardSpecMsgParam.Integer -> pushText(param.value.toString())
                    is ActionCardSpecMsgParam.Entity -> segments.add(ActionCardMsgSegment.Ref(param.ref))
                }
        }
    }
    return segments
}

/** [renderActionCardMsg] as plain text: entities as their [ActionCardSpecEntityRef.fallback] unless [name] knows them. */
fun renderActionCardMsgText(
    msg: ActionCardSpecMsg,
    displayLocale: String,
    name: (ActionCardSpecEntityRef) -> String? = { null },
): String =
    renderActionCardMsg(msg, displayLocale).joinToString("") { segment ->
        when (segment) {
            is ActionCardMsgSegment.Text -> segment.text
            is ActionCardMsgSegment.Ref -> name(segment.ref) ?: segment.ref.fallback
        }
    }

internal sealed interface TemplateToken {
    data class Text(val text: String) : TemplateToken

    data class Param(val name: String) : TemplateToken
}

/** Left to right, no backtracking: `{{` / `}}` are literal braces, `{name}` a placeholder, any other brace invalid (null). */
internal fun actionCardTemplateTokens(template: String): List<TemplateToken>? {
    val tokens = ArrayList<TemplateToken>()
    val text = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if ((c == '{' || c == '}') && i + 1 < template.length && template[i + 1] == c) {
            text.append(c)
            i += 2
            continue
        }
        if (c == '}') return null
        if (c == '{') {
            var j = i + 1
            while (j < template.length && template[j].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) j++
            val name = template.substring(i + 1, j)
            if (j >= template.length || template[j] != '}' || !PARAM_NAME.matches(name)) return null
            if (text.isNotEmpty()) tokens.add(TemplateToken.Text(text.toString()))
            text.clear()
            tokens.add(TemplateToken.Param(name))
            i = j + 1
            continue
        }
        text.append(c)
        i++
    }
    if (text.isNotEmpty()) tokens.add(TemplateToken.Text(text.toString()))
    return tokens
}

// ---------------------------------------------------------------------------
// Elements.

/** `string | Msg`: a plain string (data: a name, an agent's reason) or card copy. */
sealed interface ActionCardSpecContent {
    data class Plain(val text: String) : ActionCardSpecContent

    data class Message(val msg: ActionCardSpecMsg) : ActionCardSpecContent
}

/** A `Field` element's value: a plain string, card copy, or 1-50 entities. */
sealed interface ActionCardSpecFieldValue {
    data class Plain(val text: String) : ActionCardSpecFieldValue

    data class Message(val msg: ActionCardSpecMsg) : ActionCardSpecFieldValue

    data class Entities(val refs: List<ActionCardSpecEntityRef>) : ActionCardSpecFieldValue
}

enum class ActionCardSpecTextTone(val wire: String) {
    Default("default"),
    Muted("muted"),
    Warning("warning"),
}

enum class ActionCardSpecNoticeTone(val wire: String) {
    Info("info"),
    Warning("warning"),
}

/** raft-ui accent / outline / destructive. At most one primary per tree; destructive only on execute with a confirm. */
enum class ActionCardSpecEmphasis(val wire: String) {
    Primary("primary"),
    Secondary("secondary"),
    Destructive("destructive"),
}

/** One element of an [ActionCardSpecTree]: a display element or an [ActionCardSpecButton]. */
sealed interface ActionCardSpecElement : CardElement

/**
 * The elements that only show something. The shared [CardStack], [CardRelativeTime] and
 * [CardDuration] (CardElements.kt) are members too, as in the shared schema.
 */
sealed interface ActionCardSpecDisplayElement : ActionCardSpecElement {
    /** `Text`: plain text (1-2000) or a Msg (500); [clamp] 1-6 lines when set. */
    data class Text(
        val text: ActionCardSpecContent,
        val tone: ActionCardSpecTextTone = ActionCardSpecTextTone.Default,
        val mono: Boolean = false,
        val clamp: Int? = null,
    ) : ActionCardSpecDisplayElement

    /** `Badge`: 1-60 code points, plain or a Msg. */
    data class Badge(
        val text: ActionCardSpecContent,
        val tone: CardBadgeTone = CardBadgeTone.Neutral,
    ) : ActionCardSpecDisplayElement

    /**
     * `Field`: a property row (what `displayItems` were). The flags are null when the card did not
     * write them; [clamp], as on Text: the value shows at most that many lines (1-6).
     */
    data class Field(
        val label: ActionCardSpecMsg,
        val value: ActionCardSpecFieldValue,
        val mono: Boolean? = null,
        val muted: Boolean? = null,
        val breakAll: Boolean? = null,
        val clamp: Int? = null,
    ) : ActionCardSpecDisplayElement

    /** `EntityRef`: one entity by reference. */
    data class EntityRef(val ref: ActionCardSpecEntityRef) : ActionCardSpecDisplayElement

    /**
     * `ScopeList`: 1-64 unique scope ids and, when set, 1-32 unique permission group ids ([groups]:
     * `server`, `agent`, `channel`, `computer`, `agent_reminder_write` today, see
     * [actionCardScopeGroupLabel]); the client names each itself.
     */
    data class ScopeList(
        val scopes: List<String>,
        val groups: List<String>? = null,
        val agentName: String? = null,
    ) : ActionCardSpecDisplayElement

    /** `Notice`: an info or warning callout. */
    data class Notice(
        val text: ActionCardSpecMsg,
        val tone: ActionCardSpecNoticeTone = ActionCardSpecNoticeTone.Info,
    ) : ActionCardSpecDisplayElement
}

/**
 * The App's own name for a `ScopeList` permission group id (`APP_NOTIFICATION_GROUPS`), worded as
 * the web's `permissionGroupLabel`: "<resource> · read", except `agent_reminder_write`, which is
 * written as is. Null for a group this App does not know.
 */
fun actionCardScopeGroupLabel(group: String): StringResource? =
    when (group) {
        "server" -> Res.string.thread_action_card_scope_group_server
        "agent" -> Res.string.thread_action_card_scope_group_agent
        "channel" -> Res.string.thread_action_card_scope_group_channel
        "computer" -> Res.string.thread_action_card_scope_group_computer
        "agent_reminder_write" -> Res.string.thread_action_card_scope_group_agent_reminder_write
        else -> null
    }

/** `Button`: [label] is a Msg of 1-24 code points; [disabledReason], when set, disables it and says why. */
data class ActionCardSpecButton(
    val label: ActionCardSpecMsg,
    val emphasis: ActionCardSpecEmphasis = ActionCardSpecEmphasis.Secondary,
    val action: ActionCardSpecAction,
    val disabledReason: ActionCardSpecMsg? = null,
) : ActionCardSpecElement

/** Where a `navigate` button goes inside Raft. [channelId] is set exactly for a message or a task. */
data class ActionCardSpecNavigateTarget(
    val kind: Kind,
    val id: String,
    val channelId: String? = null,
    val serverId: String? = null,
) {
    enum class Kind(val wire: String, val needsChannel: Boolean) {
        Channel("channel", false),
        Agent("agent", false),
        User("user", false),
        Message("message", true),
        Task("task", true),
    }

    /** The server the target lives on: [serverId], else the card's [cardSourceServerId]. */
    fun serverIdOr(cardSourceServerId: String?): String? = serverId ?: cardSourceServerId
}

/**
 * What a Button does (capability in brackets):
 * - [Execute]: POST /api/actions/:messageId/execute with expectedState and
 *   expectedConfirmationVersion [card.v1]; with [Execute.confirm], the generic confirm dialog
 *   with `confirms[confirm]` first [confirm.v1];
 * - [OpenForm]: the generic form sheet for `forms[form]` [form.v1 + each field's `field.*`];
 * - [Open]: the existing native dialog, prefilled from `metadata.action` [intent.<intent>];
 * - [Navigate] / [NavigateUrl]: open the entity natively, or a public HTTPS URL outside Raft
 *   [card.v1].
 *
 * [Unsupported] appears only in a card as read: an action kind or `open` intent this App does not
 * know, or a press a key it does not know could change (on the action, the Button's props, or the
 * confirm an execute opens: that key may be a new safety check it must not ignore). [OpenInWeb] only after [stripActionCardSpec], which turns every button the App cannot
 * handle into one: open this message's web link in a browser (the builder is A4's, symmetric to
 * the `RaftMessageLink` parser).
 */
sealed interface ActionCardSpecAction {
    data class Execute(val confirm: String? = null) : ActionCardSpecAction

    data class OpenForm(val form: String) : ActionCardSpecAction

    /** [gate] only with [ActionCardSpecIntent.UpgradeDialog]. */
    data class Open(val intent: ActionCardSpecIntent, val gate: String? = null) : ActionCardSpecAction

    data class Navigate(val target: ActionCardSpecNavigateTarget) : ActionCardSpecAction

    /** A public HTTPS URL ([isCardPublicUrl]), opened outside Raft with noopener/noreferrer. */
    data class NavigateUrl(val url: String) : ActionCardSpecAction

    data class Unsupported(val originalKind: String, val originalIntent: String? = null) : ActionCardSpecAction

    data object OpenInWeb : ActionCardSpecAction
}

/** One bounded tree: [elements] by id, in the card's order, every one reached once from [root]. */
data class ActionCardSpecTree(
    val root: String,
    val elements: Map<String, ActionCardSpecElement>,
) {
    fun element(id: String): ActionCardSpecElement? = elements[id]

    val buttons: List<ActionCardSpecButton> get() = elements.values.filterIsInstance<ActionCardSpecButton>()
}

/** The generic confirm dialog: its body has no buttons, the dialog has its own. */
data class ActionCardSpecConfirm(
    val title: ActionCardSpecMsg,
    val confirmLabel: ActionCardSpecMsg,
    val spec: ActionCardSpecTree,
)

// ---------------------------------------------------------------------------
// Forms.

/** A form field. Optional props are null when the card did not write them. */
sealed interface ActionCardSpecFormField {
    val name: String

    /** A field type this App does not know (read leniency 3): a button opening its form becomes "Open in web". */
    data class Unsupported(override val name: String, val originalType: String) : ActionCardSpecFormField
}

/** A field this App knows; [capability] is the `field.*` it needs. */
sealed interface ActionCardSpecKnownField : ActionCardSpecFormField {
    val label: ActionCardSpecMsg
    val placeholder: ActionCardSpecMsg?
    val description: ActionCardSpecMsg?
    val capability: String
}

/**
 * A picker: its value is always a list of ids (a single picker: at most one); candidates come
 * from the App's own store; [emptyText] shows when it has none.
 */
sealed interface ActionCardSpecPickerField : ActionCardSpecKnownField {
    val required: Boolean?
    val emptyText: ActionCardSpecMsg?
    val multiple: Boolean
    val maxItems: Int?
    val default: List<ActionCardSpecEntityRef>?

    /** How many ids it takes: [maxItems] (else 50) when [multiple], else 1. */
    val maxSelectable: Int get() = if (multiple) maxItems ?: ActionCardSpecLimits.MAX_PICKER_ITEMS else 1
}

data class ActionCardSpecTextInputField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    val required: Boolean? = null,
    val maxLength: Int,
    val default: String? = null,
) : ActionCardSpecKnownField {
    override val capability: String get() = "field.text_input"
}

data class ActionCardSpecTextAreaField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    val required: Boolean? = null,
    val maxLength: Int,
    val rows: Int? = null,
    val default: String? = null,
) : ActionCardSpecKnownField {
    override val capability: String get() = "field.text_area"
}

data class ActionCardSpecSelectOption(val value: String, val label: ActionCardSpecMsg)

data class ActionCardSpecSelectField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    val required: Boolean? = null,
    val options: List<ActionCardSpecSelectOption>,
    val default: String? = null,
) : ActionCardSpecKnownField {
    override val capability: String get() = "field.select"
}

/** Always answered (false by default), so never required. */
data class ActionCardSpecToggleField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    val default: Boolean? = null,
) : ActionCardSpecKnownField {
    override val capability: String get() = "field.toggle"
}

enum class ActionCardSpecMemberKind(val wire: String) {
    Human("human"),
    Agent("agent"),
}

enum class ActionCardSpecMemberScope(val wire: String) {
    Server("server"),

    /** The card's channel. */
    Channel("channel"),
}

enum class ActionCardSpecChannelScope(val wire: String) {
    Joined("joined"),
    Visible("visible"),
}

/** [kinds] is `[human]`, `[agent]` or `[human, agent]`. */
data class ActionCardSpecMemberPickerField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    override val required: Boolean? = null,
    override val emptyText: ActionCardSpecMsg? = null,
    override val multiple: Boolean,
    override val maxItems: Int? = null,
    override val default: List<ActionCardSpecEntityRef>? = null,
    val kinds: List<ActionCardSpecMemberKind>,
    val scope: ActionCardSpecMemberScope,
) : ActionCardSpecPickerField {
    override val capability: String get() = "field.member_picker"
}

data class ActionCardSpecChannelPickerField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    override val required: Boolean? = null,
    override val emptyText: ActionCardSpecMsg? = null,
    override val multiple: Boolean,
    override val maxItems: Int? = null,
    override val default: List<ActionCardSpecEntityRef>? = null,
    val scope: ActionCardSpecChannelScope,
) : ActionCardSpecPickerField {
    override val capability: String get() = "field.channel_picker"
}

data class ActionCardSpecAgentPickerField(
    override val name: String,
    override val label: ActionCardSpecMsg,
    override val placeholder: ActionCardSpecMsg? = null,
    override val description: ActionCardSpecMsg? = null,
    override val required: Boolean? = null,
    override val emptyText: ActionCardSpecMsg? = null,
    override val multiple: Boolean,
    override val maxItems: Int? = null,
    override val default: List<ActionCardSpecEntityRef>? = null,
) : ActionCardSpecPickerField {
    override val capability: String get() = "field.agent_picker"
}

/** A form sheet: 1-12 fields with unique names. */
data class ActionCardSpecForm(
    val title: ActionCardSpecMsg,
    val submitLabel: ActionCardSpecMsg,
    val description: ActionCardSpecMsg? = null,
    val fields: List<ActionCardSpecFormField>,
)

/**
 * `presentation.card` as this App read it ([parseActionCardSpec]) or renders it
 * ([stripActionCardSpec]). The frame paints [icon] + [title] as the header; [spec] is the body;
 * [result] is shown once the card is executed (its buttons only navigate). [confirms] / [forms] are
 * null when the card did not write them.
 */
data class ActionCardSpecCard(
    val icon: ActionCardSpecIcon? = null,
    val title: ActionCardSpecMsg,
    val spec: ActionCardSpecTree,
    val confirms: Map<String, ActionCardSpecConfirm>? = null,
    val forms: Map<String, ActionCardSpecForm>? = null,
    val result: ActionCardSpecTree? = null,
) {
    val catalog: String get() = ACTION_CARD_SPEC_CATALOG
}

// ---------------------------------------------------------------------------
// Read.

private val ACTION_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
private val FIELD_TYPE_NAME = Regex("^[A-Z][A-Za-z0-9]{0,31}$")
private val MSG_KEY = Regex("^actionCard(\\.[A-Za-z0-9_]{1,64}){1,6}$")
private val PARAM_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,31}$")
private val LOCALE_KEY = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$")
private val LOWER_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SCOPE_ID = Regex("^[a-z][a-z0-9_.:-]{0,127}$")
private val GATE = Regex("^[a-z][a-z0-9_]{0,63}$")
private val FIELD_NAME = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")
private val OPTION_VALUE = Regex("^[A-Za-z0-9_.:-]{1,64}$")

/** `open_web` comes only from strip, and `unsupported` only from a read; neither may arrive. */
private val KNOWN_ACTION_KINDS = setOf("execute", "open_form", "open", "navigate", "open_web", "unsupported")

/** The keys each action kind takes (`ACTION_KEYS`); a read takes one with any other key as unsupported (leniency 7). */
private val ACTION_KEYS: Map<String, Set<String>> =
    mapOf(
        "execute" to setOf("kind", "confirm"),
        "open_form" to setOf("kind", "form"),
        "open" to setOf("kind", "intent", "gate"),
        "navigate" to setOf("kind", "target", "url"),
    )
private val BUTTON_PROPS = setOf("label", "emphasis", "action", "disabledReason")
private val CONFIRM_KEYS = setOf("title", "confirmLabel", "spec")
private val KNOWN_FIELD_TYPES = setOf("TextInput", "TextArea", "Select", "Toggle", "MemberPicker", "ChannelPicker", "AgentPicker")

/**
 * Reads `presentation.card` as the contract's read mode does (`parseActionCardSpec(raw, { mode:
 * "read" })`), or null: the App then shows the legacy presentation. The emit schema plus exactly
 * these leniencies:
 * 1. an action kind it does not know (shaped `^[a-z][a-z0-9_]{0,31}$`, on an object) reads as
 *    [ActionCardSpecAction.Unsupported], other props ignored;
 * 2. so does an `open` intent it does not know (same shape), with `originalIntent`;
 * 3. a field type it does not know (`^[A-Z][A-Za-z0-9]{0,31}$`) reads as
 *    [ActionCardSpecFormField.Unsupported]: `name` still checked and unique, other props ignored,
 *    the form kept;
 * 4. an envelope key it does not know is dropped, and so is an icon;
 * 5. a Msg locale it does not know (`^[a-z]{2,3}(-[a-z0-9]{2,8})?$`) is dropped, its template
 *    unchecked;
 * 6. any other object's key it does not know is dropped, its value unchecked: on an element
 *    (besides type, props and a Stack's children) and the props of every element but a Button, a
 *    Msg, an EntityRef (element, Field value, param, picker default), a navigate target (chosen by
 *    kind), a tree, a form, a known field and a Select option. Known keys keep their rules, and
 *    `children` on an element other than a Stack is still refused;
 * 7. except where a key could change what a press does: a key outside a Button's props, outside
 *    its action's kind ([ACTION_KEYS]), or outside a confirm (title, confirmLabel, spec). The known
 *    keys are checked as usual, then the button's action reads as [ActionCardSpecAction.Unsupported]
 *    (an `open` keeps `originalIntent`; a marker stays as it is), so strip makes it "Open in web".
 *    Such a confirm is dropped from `confirms` (which stays, possibly empty) once the rest of the
 *    card is valid, and every execute in `spec` naming it is marked.
 * The tree rules and the confirm / form references see the markers 7 made from a Button's props
 * or action, not the action they replace; a dropped confirm changes no verdict. Everything else, an
 * unknown element type included, makes the whole card invalid. Never throws.
 */
fun parseActionCardSpec(raw: JsonElement): ActionCardSpecCard? = actionCardSpecIssue(raw).first

/** [parseActionCardSpec] with the reason a card is refused (for tests and logs, never shown). */
internal fun actionCardSpecIssue(raw: JsonElement): Pair<ActionCardSpecCard?, String?> =
    try {
        ActionCardSpecReader.card(raw) to null
    } catch (invalid: ActionCardSpecInvalid) {
        null to invalid.message
    }

/** One form sheet read in read mode (for the shared form-value vectors). */
internal fun parseActionCardSpecForm(raw: JsonElement): ActionCardSpecForm? =
    try {
        ActionCardSpecReader.form(raw)
    } catch (_: ActionCardSpecInvalid) {
        null
    }

/** One Msg read in read mode with the slot limit [max]. */
internal fun parseActionCardSpecMsg(raw: JsonElement, max: Int = 500): ActionCardSpecMsg? =
    try {
        ActionCardSpecReader.msg(raw, max)
    } catch (_: ActionCardSpecInvalid) {
        null
    }

private class ActionCardSpecInvalid(message: String) : Exception(message)

private object ActionCardSpecReader {
    private fun invalid(message: String): Nothing = throw ActionCardSpecInvalid(message)

    private fun JsonElement?.obj(what: String): JsonObject = this as? JsonObject ?: invalid("$what: expected an object")

    private fun JsonObject.strict(what: String, vararg keys: String): JsonObject {
        val unknown = this.keys - keys.toSet()
        if (unknown.isNotEmpty()) invalid("$what: unknown keys $unknown")
        return this
    }

    /** Whether this object has a key outside [keys] (`hasKeyOutside`). */
    private fun JsonObject.hasKeyOutside(keys: Set<String>): Boolean = this.keys.any { it !in keys }

    /** Only the [keys] of this object (the others dropped, as zod's stripping object does). */
    private fun JsonObject.only(keys: Set<String>): JsonObject = JsonObject(filterKeys { it in keys })

    /** The marker for an action a read will not perform as written (`unsupportedAction`); a marker stays as it is. */
    private fun unsupported(action: ActionCardSpecAction): ActionCardSpecAction =
        when (action) {
            is ActionCardSpecAction.Unsupported -> action
            is ActionCardSpecAction.Open -> ActionCardSpecAction.Unsupported("open", action.intent.wire)
            is ActionCardSpecAction.Execute -> ActionCardSpecAction.Unsupported("execute")
            is ActionCardSpecAction.OpenForm -> ActionCardSpecAction.Unsupported("open_form")
            is ActionCardSpecAction.Navigate, is ActionCardSpecAction.NavigateUrl -> ActionCardSpecAction.Unsupported("navigate")
            ActionCardSpecAction.OpenInWeb -> invalid("open_web never arrives")
        }

    private fun JsonElement?.str(what: String): String = cardString() ?: invalid("$what: expected a string")

    private fun JsonElement?.bool(what: String): Boolean = cardBoolean() ?: invalid("$what: expected a boolean")

    private fun JsonElement?.int(what: String, range: IntRange): Int {
        val value = cardSafeInteger() ?: invalid("$what: expected an integer")
        if (value < range.first || value > range.last) invalid("$what: $value outside $range")
        return value.toInt()
    }

    private fun JsonElement?.array(what: String, sizes: IntRange): JsonArray {
        val array = this as? JsonArray ?: invalid("$what: expected an array")
        if (array.size !in sizes) invalid("$what: ${array.size} items outside $sizes")
        return array
    }

    private fun text(raw: JsonElement?, what: String, min: Int, max: Int): String {
        val value = raw.str(what)
        if (!isCardText(value, min, max)) invalid("$what: expected $min-$max characters")
        return value
    }

    private fun matching(raw: JsonElement?, what: String, pattern: Regex): String =
        raw.str(what).also { if (!pattern.matches(it)) invalid("$what: \"$it\" does not match") }

    /** `name(pattern)`: matches and is not a reserved name. */
    private fun name(raw: JsonElement?, what: String, pattern: Regex): String =
        matching(raw, what, pattern).also { if (it in CARD_RESERVED_IDS) invalid("$what: reserved name") }

    private inline fun <T : Any> JsonObject.opt(key: String, read: (JsonElement) -> T): T? = this[key]?.let(read)

    private inline fun <reified E : Enum<E>> enumOf(raw: JsonElement?, what: String, wire: (E) -> String): E {
        val value = raw.str(what)
        return enumValues<E>().firstOrNull { wire(it) == value } ?: invalid("$what: unknown \"$value\"")
    }

    /** `record(key, value, max)`: an object without a raw `__proto__` key, every key valid. */
    private fun <V> record(
        raw: JsonElement?,
        what: String,
        max: Int = Int.MAX_VALUE,
        key: (String) -> Unit,
        value: (String, JsonElement) -> V,
    ): Map<String, V> {
        val obj = raw.obj(what)
        if ("__proto__" in obj) invalid("$what: reserved key")
        if (obj.size > max) invalid("$what: at most $max entries")
        val out = LinkedHashMap<String, V>()
        for ((k, v) in obj) {
            key(k)
            out[k] = value(k, v)
        }
        return out
    }

    private fun entityRef(raw: JsonElement?, what: String): ActionCardSpecEntityRef {
        val obj = raw.obj(what)
        return ActionCardSpecEntityRef(
            kind = enumOf(obj["kind"], "$what.kind", ActionCardSpecEntityKind::wire),
            id = matching(obj["id"], "$what.id", LOWER_UUID),
            serverId = obj.opt("serverId") { matching(it, "$what.serverId", LOWER_UUID) },
            fallback = text(obj["fallback"], "$what.fallback", 1, 200),
        )
    }

    private fun template(raw: JsonElement?, what: String, max: Int): String {
        val value = text(raw, what, 1, max)
        if (actionCardTemplateTokens(value) == null) invalid("$what: use {name} placeholders, and {{ or }} for a brace")
        return value
    }

    fun msg(raw: JsonElement?, max: Int, what: String = "msg"): ActionCardSpecMsg {
        val obj = raw.obj(what)
        val key = matching(obj["key"], "$what.key", MSG_KEY)
        val rawText = obj["text"].obj("$what.text")
        rawText.keys.firstOrNull { it !in ACTION_CARD_SPEC_LOCALES && !LOCALE_KEY.matches(it) }?.let { invalid("$what.text: bad locale key $it") }
        val text = LinkedHashMap<String, String>()
        text["en"] = template(rawText["en"] ?: invalid("$what.text.en: required"), "$what.text.en", max)
        for (locale in ACTION_CARD_SPEC_LOCALES) {
            if (locale != "en" && locale in rawText) text[locale] = template(rawText[locale], "$what.text.$locale", max)
        }
        val params =
            obj.opt("params") { rawParams ->
                record(
                    rawParams,
                    "$what.params",
                    ActionCardSpecLimits.MAX_PARAMS,
                    key = { name(JsonPrimitive(it), "$what.params key", PARAM_NAME) },
                ) { name, value -> param(value, "$what.params.$name") }
            }
        for ((locale, template) in text) {
            for (token in actionCardTemplateTokens(template).orEmpty()) {
                if (token is TemplateToken.Param && params?.containsKey(token.name) != true) invalid("$what.text.$locale: no param for {${token.name}}")
            }
        }
        return ActionCardSpecMsg(key, text, params)
    }

    private fun param(raw: JsonElement, what: String): ActionCardSpecMsgParam =
        when {
            raw is JsonObject -> ActionCardSpecMsgParam.Entity(entityRef(raw, what))
            raw.cardString() != null -> ActionCardSpecMsgParam.Text(text(raw, what, 0, 500))
            else -> ActionCardSpecMsgParam.Integer(raw.cardSafeInteger() ?: invalid("$what: a string, a safe integer or an EntityRef"))
        }

    private fun content(raw: JsonElement?, what: String, plainMax: Int, msgMax: Int): ActionCardSpecContent =
        if (raw.cardString() != null) {
            ActionCardSpecContent.Plain(text(raw, what, 1, plainMax))
        } else {
            ActionCardSpecContent.Message(msg(raw, msgMax, what))
        }

    private fun navigateTarget(raw: JsonElement?, what: String): ActionCardSpecNavigateTarget {
        val obj = raw.obj(what)
        val kind = enumOf(obj["kind"], "$what.kind", ActionCardSpecNavigateTarget.Kind::wire)
        return ActionCardSpecNavigateTarget(
            kind = kind,
            id = matching(obj["id"], "$what.id", LOWER_UUID),
            channelId = if (kind.needsChannel) matching(obj["channelId"], "$what.channelId", LOWER_UUID) else null,
            serverId = obj.opt("serverId") { matching(it, "$what.serverId", LOWER_UUID) },
        )
    }

    private fun action(raw: JsonElement?, what: String): ActionCardSpecAction {
        val obj = raw.obj(what)
        val kind = obj["kind"].cardString()
        val keys = ACTION_KEYS[kind]
        if (keys != null && obj.hasKeyOutside(keys)) {
            // Leniency 7. An open with an intent this App does not know stays leniency 2, other keys ignored.
            val rawIntent = obj["intent"].cardString()
            if (kind == "open" && rawIntent != null && ActionCardSpecIntent.entries.none { it.wire == rawIntent } && ACTION_ID.matches(rawIntent)) {
                return ActionCardSpecAction.Unsupported("open", rawIntent)
            }
            return unsupported(knownAction(obj.only(keys), what))
        }
        return knownAction(obj, what)
    }

    private fun knownAction(obj: JsonObject, what: String): ActionCardSpecAction {
        return when (val kind = obj["kind"].cardString()) {
            "execute" -> {
                obj.strict(what, "kind", "confirm")
                ActionCardSpecAction.Execute(obj.opt("confirm") { name(it, "$what.confirm", ACTION_ID) })
            }
            "open_form" -> {
                obj.strict(what, "kind", "form")
                ActionCardSpecAction.OpenForm(name(obj["form"], "$what.form", ACTION_ID))
            }
            "open" -> {
                val rawIntent = obj["intent"].cardString()
                val intent = ActionCardSpecIntent.entries.firstOrNull { it.wire == rawIntent }
                when {
                    intent != null -> {
                        obj.strict(what, "kind", "intent", "gate")
                        val gate = obj.opt("gate") { matching(it, "$what.gate", GATE) }
                        if (gate != null && intent != ActionCardSpecIntent.UpgradeDialog) invalid("$what: only upgrade_dialog takes a gate")
                        ActionCardSpecAction.Open(intent, gate)
                    }
                    rawIntent != null && ACTION_ID.matches(rawIntent) -> ActionCardSpecAction.Unsupported("open", rawIntent)
                    else -> invalid("$what: bad intent")
                }
            }
            "navigate" ->
                if ("target" in obj) {
                    obj.strict(what, "kind", "target")
                    ActionCardSpecAction.Navigate(navigateTarget(obj["target"], "$what.target"))
                } else {
                    obj.strict(what, "kind", "url")
                    val url = obj["url"].str("$what.url")
                    if (url.length > 2000 || !isCardPublicUrl(url)) invalid("$what.url: expected a public HTTPS URL")
                    ActionCardSpecAction.NavigateUrl(url)
                }
            else ->
                if (kind != null && kind !in KNOWN_ACTION_KINDS && ACTION_ID.matches(kind)) {
                    ActionCardSpecAction.Unsupported(kind)
                } else {
                    invalid("$what: bad kind $kind")
                }
        }
    }

    /** A display element's props (leniency 6: unknown keys dropped, `children` refused). */
    private fun props(element: JsonObject, what: String): JsonObject {
        if (!CardUnknownKeys.Strip.acceptsElement(element)) invalid("$what: only a Stack has children")
        return element["props"].obj("$what.props")
    }

    private fun element(raw: JsonElement?, what: String): ActionCardSpecElement {
        val obj = raw.obj(what)
        return when (obj["type"].cardString()) {
            "Stack" -> readCardStack(obj, CardUnknownKeys.Strip) ?: invalid("$what: bad Stack")
            "RelativeTime" -> readCardRelativeTime(obj, CardUnknownKeys.Strip) ?: invalid("$what: bad RelativeTime")
            "Duration" -> readCardDuration(obj, CardUnknownKeys.Strip) ?: invalid("$what: bad Duration")
            "Text" -> {
                val props = props(obj, what)
                ActionCardSpecDisplayElement.Text(
                    text = content(props["text"], "$what.text", 2000, 500),
                    tone = props.opt("tone") { enumOf(it, "$what.tone", ActionCardSpecTextTone::wire) } ?: ActionCardSpecTextTone.Default,
                    mono = props.opt("mono") { it.bool("$what.mono") } ?: false,
                    clamp = props.opt("clamp") { it.int("$what.clamp", 1..6) },
                )
            }
            "Badge" -> {
                val props = props(obj, what)
                ActionCardSpecDisplayElement.Badge(
                    text = content(props["text"], "$what.text", 60, 60),
                    tone = props.opt("tone") { enumOf(it, "$what.tone", CardBadgeTone::wire) } ?: CardBadgeTone.Neutral,
                )
            }
            "Field" -> {
                val props = props(obj, what)
                val rawValue = props["value"]
                val value =
                    when {
                        rawValue.cardString() != null -> ActionCardSpecFieldValue.Plain(text(rawValue, "$what.value", 1, 2000))
                        rawValue is JsonArray ->
                            ActionCardSpecFieldValue.Entities(
                                rawValue.array("$what.value", 1..50).mapIndexed { i, ref -> entityRef(ref, "$what.value[$i]") },
                            )
                        else -> ActionCardSpecFieldValue.Message(msg(rawValue, 500, "$what.value"))
                    }
                ActionCardSpecDisplayElement.Field(
                    label = msg(props["label"], 40, "$what.label"),
                    value = value,
                    mono = props.opt("mono") { it.bool("$what.mono") },
                    muted = props.opt("muted") { it.bool("$what.muted") },
                    breakAll = props.opt("breakAll") { it.bool("$what.breakAll") },
                    clamp = props.opt("clamp") { it.int("$what.clamp", 1..6) },
                )
            }
            "EntityRef" -> ActionCardSpecDisplayElement.EntityRef(entityRef(props(obj, what), "$what.props"))
            "ScopeList" -> {
                val props = props(obj, what)
                val scopes = props["scopes"].array("$what.scopes", 1..64).map { matching(it, "$what.scopes[]", SCOPE_ID) }
                if (scopes.toSet().size != scopes.size) invalid("$what.scopes: must be unique")
                val groups =
                    props.opt("groups") { raw ->
                        raw.array("$what.groups", 1..32).map { matching(it, "$what.groups[]", SCOPE_ID) }
                            .also { if (it.toSet().size != it.size) invalid("$what.groups: must be unique") }
                    }
                ActionCardSpecDisplayElement.ScopeList(scopes, groups, props.opt("agentName") { text(it, "$what.agentName", 1, 100) })
            }
            "Notice" -> {
                val props = props(obj, what)
                ActionCardSpecDisplayElement.Notice(
                    text = msg(props["text"], 500, "$what.text"),
                    tone = props.opt("tone") { enumOf(it, "$what.tone", ActionCardSpecNoticeTone::wire) } ?: ActionCardSpecNoticeTone.Info,
                )
            }
            "Button" -> {
                // The element object strips (leniency 6); its props do not (leniency 7).
                val props = props(obj, what)
                val read =
                    ActionCardSpecButton(
                        label = msg(props["label"], 24, "$what.label"),
                        emphasis = props.opt("emphasis") { enumOf(it, "$what.emphasis", ActionCardSpecEmphasis::wire) } ?: ActionCardSpecEmphasis.Secondary,
                        action = action(props["action"], "$what.action"),
                        disabledReason = props.opt("disabledReason") { msg(it, 200, "$what.disabledReason") },
                    )
                val button = if (props.hasKeyOutside(BUTTON_PROPS)) read.copy(action = unsupported(read.action)) else read
                val action = button.action
                val destructiveAllowed = action is ActionCardSpecAction.Unsupported || (action is ActionCardSpecAction.Execute && action.confirm != null)
                if (button.emphasis == ActionCardSpecEmphasis.Destructive && !destructiveAllowed) invalid("$what: only an execute button with a confirm can be destructive")
                button
            }
            else -> invalid("$what: unknown element type")
        }
    }

    private enum class TreeRule { Spec, Result, Confirm }

    private fun tree(raw: JsonElement?, what: String, rule: TreeRule): ActionCardSpecTree {
        val obj = raw.obj(what)
        val root = matching(obj["root"], "$what.root", CARD_ELEMENT_ID)
        val elements =
            record(obj["elements"], "$what.elements", key = { matching(JsonPrimitive(it), "$what element id", CARD_ELEMENT_ID) }) { id, value ->
                element(value, "$what.$id")
            }
        val maxElements = if (rule == TreeRule.Confirm) ActionCardSpecLimits.MAX_CONFIRM_ELEMENTS else ActionCardSpecLimits.MAX_ELEMENTS
        checkCardBoundedTree(root, elements, maxElements, ActionCardSpecLimits.MAX_DEPTH)?.let { invalid("$what: tree $it") }
        val buttons = elements.values.filterIsInstance<ActionCardSpecButton>()
        if (rule == TreeRule.Confirm && buttons.isNotEmpty()) invalid("$what: a confirmation has no buttons")
        if (buttons.size > ActionCardSpecLimits.MAX_BUTTONS) invalid("$what: too many buttons")
        if (rule == TreeRule.Spec && buttons.count { it.action is ActionCardSpecAction.Execute } > 1) invalid("$what: at most one execute button")
        if (rule == TreeRule.Result && buttons.any { it.action !is ActionCardSpecAction.Navigate && it.action !is ActionCardSpecAction.NavigateUrl && it.action !is ActionCardSpecAction.Unsupported }) {
            invalid("$what: result buttons only navigate")
        }
        if (buttons.count { it.emphasis == ActionCardSpecEmphasis.Primary } > 1) invalid("$what: at most one primary button")
        return ActionCardSpecTree(root, elements)
    }

    private fun entityRefs(raw: JsonElement, what: String): List<ActionCardSpecEntityRef> =
        (raw as? JsonArray ?: invalid("$what: expected an array")).mapIndexed { i, ref -> entityRef(ref, "$what[$i]") }

    private fun checkPicker(field: ActionCardSpecPickerField, what: String, fits: (ActionCardSpecEntityKind) -> Boolean) {
        if (!field.multiple && field.maxItems != null && field.maxItems != 1) invalid("$what: a single picker takes one item")
        val defaults = field.default.orEmpty()
        if (defaults.size > field.maxSelectable) invalid("$what: more defaults than the picker takes")
        if (defaults.any { !fits(it.kind) }) invalid("$what: a default does not fit the picker")
        if (defaults.map { it.id }.toSet().size != defaults.size) invalid("$what: defaults must be unique")
    }

    private fun field(raw: JsonElement?, what: String): ActionCardSpecFormField {
        val obj = raw.obj(what)
        val type = obj["type"].cardString()
        fun fieldName() = name(obj["name"], "$what.name", FIELD_NAME)
        fun label() = msg(obj["label"], 40, "$what.label")
        fun placeholder() = obj.opt("placeholder") { msg(it, 120, "$what.placeholder") }
        fun description() = obj.opt("description") { msg(it, 300, "$what.description") }
        fun required() = obj.opt("required") { it.bool("$what.required") }
        fun emptyText() = obj.opt("emptyText") { msg(it, 120, "$what.emptyText") }
        fun multiple() = obj["multiple"].bool("$what.multiple")
        fun maxItems() = obj.opt("maxItems") { it.int("$what.maxItems", 1..50) }
        fun defaults() = obj.opt("default") { entityRefs(it, "$what.default") }
        return when (type) {
            "TextInput", "TextArea" -> {
                val maxLength = obj["maxLength"].int("$what.maxLength", if (type == "TextInput") 1..500 else 1..8000)
                val default = obj.opt("default") { it.str("$what.default") }
                if (default != null && cardCodePoints(default) > maxLength) invalid("$what.default: longer than maxLength")
                if (type == "TextInput") {
                    ActionCardSpecTextInputField(fieldName(), label(), placeholder(), description(), required(), maxLength, default)
                } else {
                    val rows = obj.opt("rows") { it.int("$what.rows", 2..10) }
                    ActionCardSpecTextAreaField(fieldName(), label(), placeholder(), description(), required(), maxLength, rows, default)
                }
            }
            "Select" -> {
                val options =
                    obj["options"].array("$what.options", 1..ActionCardSpecLimits.MAX_OPTIONS).mapIndexed { i, rawOption ->
                        val option = rawOption.obj("$what.options[$i]")
                        ActionCardSpecSelectOption(matching(option["value"], "$what.options[$i].value", OPTION_VALUE), msg(option["label"], 40, "$what.options[$i].label"))
                    }
                if (options.map { it.value }.toSet().size != options.size) invalid("$what.options: values must be unique")
                val default = obj.opt("default") { it.str("$what.default") }
                if (default != null && options.none { it.value == default }) invalid("$what.default: must be an option")
                ActionCardSpecSelectField(fieldName(), label(), placeholder(), description(), required(), options, default)
            }
            "Toggle" -> {
                ActionCardSpecToggleField(fieldName(), label(), placeholder(), description(), obj.opt("default") { it.bool("$what.default") })
            }
            "MemberPicker" -> {
                val kinds = obj["kinds"].array("$what.kinds", 1..2).map { enumOf(it, "$what.kinds[]", ActionCardSpecMemberKind::wire) }
                if (kinds != listOf(ActionCardSpecMemberKind.Human) && kinds != listOf(ActionCardSpecMemberKind.Agent) &&
                    kinds != listOf(ActionCardSpecMemberKind.Human, ActionCardSpecMemberKind.Agent)
                ) {
                    invalid("$what.kinds: [human], [agent] or [human, agent]")
                }
                val field =
                    ActionCardSpecMemberPickerField(
                        fieldName(), label(), placeholder(), description(), required(), emptyText(), multiple(), maxItems(), defaults(),
                        kinds = kinds,
                        scope = enumOf(obj["scope"], "$what.scope", ActionCardSpecMemberScope::wire),
                    )
                checkPicker(field, what) { kind ->
                    kinds.any { (if (it == ActionCardSpecMemberKind.Human) ActionCardSpecEntityKind.User else ActionCardSpecEntityKind.Agent) == kind }
                }
                field
            }
            "ChannelPicker" -> {
                val field =
                    ActionCardSpecChannelPickerField(
                        fieldName(), label(), placeholder(), description(), required(), emptyText(), multiple(), maxItems(), defaults(),
                        scope = enumOf(obj["scope"], "$what.scope", ActionCardSpecChannelScope::wire),
                    )
                checkPicker(field, what) { it == ActionCardSpecEntityKind.Channel }
                field
            }
            "AgentPicker" -> {
                val field = ActionCardSpecAgentPickerField(fieldName(), label(), placeholder(), description(), required(), emptyText(), multiple(), maxItems(), defaults())
                checkPicker(field, what) { it == ActionCardSpecEntityKind.Agent }
                field
            }
            else ->
                if (type != null && type !in KNOWN_FIELD_TYPES && FIELD_TYPE_NAME.matches(type)) {
                    ActionCardSpecFormField.Unsupported(fieldName(), type)
                } else {
                    invalid("$what: bad field type $type")
                }
        }
    }

    fun form(raw: JsonElement?, what: String = "form"): ActionCardSpecForm {
        val obj = raw.obj(what)
        val fields = obj["fields"].array("$what.fields", 1..ActionCardSpecLimits.MAX_FIELDS).mapIndexed { i, f -> field(f, "$what.fields[$i]") }
        if (fields.map { it.name }.toSet().size != fields.size) invalid("$what.fields: names must be unique")
        return ActionCardSpecForm(
            title = msg(obj["title"], 80, "$what.title"),
            submitLabel = msg(obj["submitLabel"], 24, "$what.submitLabel"),
            description = obj.opt("description") { msg(it, 500, "$what.description") },
            fields = fields,
        )
    }

    /** A confirm, and whether it has a key a read does not know (leniency 7: the card drops it). */
    private fun confirm(raw: JsonElement?, what: String): Pair<ActionCardSpecConfirm, Boolean> {
        val obj = raw.obj(what)
        val confirm =
            ActionCardSpecConfirm(
                title = msg(obj["title"], 80, "$what.title"),
                confirmLabel = msg(obj["confirmLabel"], 24, "$what.confirmLabel"),
                spec = tree(obj["spec"], "$what.spec", TreeRule.Confirm),
            )
        return confirm to obj.hasKeyOutside(CONFIRM_KEYS)
    }

    fun card(raw: JsonElement): ActionCardSpecCard {
        // Read mode: an envelope key this App does not know is dropped (leniency 4).
        val obj = raw.obj("card")
        if (obj["catalog"].cardString() != ACTION_CARD_SPEC_CATALOG) invalid("card.catalog: expected $ACTION_CARD_SPEC_CATALOG")
        val icon = obj.opt("icon") { it.str("card.icon") }?.let { wire -> ActionCardSpecIcon.entries.firstOrNull { it.wire == wire } }
        val actionIdKey = { what: String -> { key: String -> name(JsonPrimitive(key), what, ACTION_ID); Unit } }
        val rawConfirms =
            obj.opt("confirms") { record(it, "card.confirms", ActionCardSpecLimits.MAX_CONFIRMS, actionIdKey("confirm id")) { id, v -> confirm(v, "card.confirms.$id") } }
        val card =
            ActionCardSpecCard(
                icon = icon,
                title = msg(obj["title"], 80, "card.title"),
                spec = tree(obj["spec"], "card.spec", TreeRule.Spec),
                confirms = rawConfirms?.mapValues { it.value.first },
                forms = obj.opt("forms") { record(it, "card.forms", ActionCardSpecLimits.MAX_FORMS, actionIdKey("form id")) { id, v -> form(v, "card.forms.$id") } },
                result = obj.opt("result") { tree(it, "card.result", TreeRule.Result) },
            )
        // A button names an existing confirm / form; one nothing names is ignored.
        for ((id, node) in card.spec.elements) {
            val action = (node as? ActionCardSpecButton)?.action ?: continue
            if (action is ActionCardSpecAction.Execute && action.confirm != null && card.confirms?.containsKey(action.confirm) != true) invalid("card.spec.$id: no confirm ${action.confirm}")
            if (action is ActionCardSpecAction.OpenForm && card.forms?.containsKey(action.form) != true) invalid("card.spec.$id: no form ${action.form}")
        }
        // Last, once the card is otherwise valid (leniency 7): a confirm with a key a read does not know
        // is dropped, and each execute naming it reads as unsupported, so it is never shown with that key ignored.
        val dropped = rawConfirms.orEmpty().filterValues { it.second }.keys
        if (dropped.isEmpty()) return card
        return card.copy(
            spec = card.spec.copy(
                elements = card.spec.elements.mapValues { (_, node) ->
                    val action = (node as? ActionCardSpecButton)?.action
                    if (action is ActionCardSpecAction.Execute && action.confirm in dropped) node.copy(action = unsupported(action)) else node
                },
            ),
            confirms = card.confirms?.filterKeys { it !in dropped },
        )
    }
}

// ---------------------------------------------------------------------------
// Strip.

/** The label of every button [stripActionCardSpec] replaces (`ACTION_CARD_OPEN_IN_WEB_LABEL`). */
val ACTION_CARD_OPEN_IN_WEB_LABEL: ActionCardSpecMsg =
    ActionCardSpecMsg(
        key = "actionCard.openInWeb",
        text = mapOf(
            "en" to "Open in web", "es" to "Abrir en la web", "pt-br" to "Abrir na web", "ko" to "웹에서 열기",
            "ja" to "ウェブで開く", "zh-cn" to "在网页打开", "zh-tw" to "在網頁開啟",
        ),
    )

/**
 * What this App renders of a read card (`stripActionCardSpec`). Without `card.v1` in
 * [capabilities], nothing (null: show the legacy presentation). Otherwise each Button in `spec`
 * and `result` the App cannot handle (an unsupported kind or intent; a capability it lacks:
 * confirm.v1, form.v1, a field type of its form, the intent; a form with an unsupported field)
 * becomes, under the same element id, an "Open in web" button ([ActionCardSpecAction.OpenInWeb]):
 * same emphasis (destructive becomes secondary) and disabledReason. An execute with a confirm is
 * never downgraded to a plain execute. Everything else, forms and confirms included, is kept.
 */
fun stripActionCardSpec(
    card: ActionCardSpecCard,
    capabilities: Collection<String> = RAFT_APP_ACTION_CARD_CAPABILITIES,
): ActionCardSpecCard? {
    if ("card.v1" !in capabilities) return null
    fun supported(action: ActionCardSpecAction): Boolean =
        when (action) {
            is ActionCardSpecAction.Execute -> action.confirm == null || "confirm.v1" in capabilities
            is ActionCardSpecAction.OpenForm -> {
                val form = card.forms?.get(action.form)
                form != null && "form.v1" in capabilities &&
                    form.fields.all { it is ActionCardSpecKnownField && it.capability in capabilities }
            }
            is ActionCardSpecAction.Open -> action.intent.capability in capabilities
            is ActionCardSpecAction.Navigate, is ActionCardSpecAction.NavigateUrl -> true
            is ActionCardSpecAction.Unsupported, ActionCardSpecAction.OpenInWeb -> false
        }
    fun strip(tree: ActionCardSpecTree): ActionCardSpecTree =
        tree.copy(
            elements = tree.elements.mapValues { (_, node) ->
                if (node !is ActionCardSpecButton || supported(node.action)) {
                    node
                } else {
                    ActionCardSpecButton(
                        label = ACTION_CARD_OPEN_IN_WEB_LABEL,
                        emphasis = if (node.emphasis == ActionCardSpecEmphasis.Destructive) ActionCardSpecEmphasis.Secondary else node.emphasis,
                        action = ActionCardSpecAction.OpenInWeb,
                        disabledReason = node.disabledReason,
                    )
                }
            },
        )
    return card.copy(spec = strip(card.spec), result = card.result?.let(::strip))
}

// ---------------------------------------------------------------------------
// Forms: value checks (the client part of `checkActionCardFormValues`).

/** A value per field: text fields and Select a string, a Toggle a boolean, a picker its ids. */
sealed interface ActionCardFormValue {
    data class Text(val value: String) : ActionCardFormValue

    data class Toggle(val value: Boolean) : ActionCardFormValue

    data class Ids(val ids: List<String>) : ActionCardFormValue
}

enum class ActionCardFormIssueCode(val wire: String) {
    Required("required"),
    TooLong("too_long"),
    TooMany("too_many"),
    InvalidOption("invalid_option"),
    NotFound("not_found"),
    NotAllowed("not_allowed"),
    Conflict("conflict"),
    InvalidFormat("invalid_format"),
}

/** One problem with a form: under its [field], or at the top of the sheet when [field] is null. */
data class ActionCardFormIssue(
    val field: String?,
    val code: ActionCardFormIssueCode,
    val message: ActionCardSpecMsg,
)

/** The generic message per issue code, in every display locale (`ACTION_CARD_FORM_ISSUE_MESSAGES`); too_long and too_many take `{max}`. */
val ACTION_CARD_FORM_ISSUE_MESSAGES: Map<ActionCardFormIssueCode, ActionCardSpecMsg> =
    listOf(
        ActionCardFormIssueCode.Required to mapOf(
            "en" to "This field is required.", "es" to "Este campo es obligatorio.", "pt-br" to "Este campo é obrigatório.", "ko" to "필수 항목이에요.",
            "ja" to "この項目は必須です。", "zh-cn" to "此项为必填项。", "zh-tw" to "此欄位為必填。",
        ),
        ActionCardFormIssueCode.TooLong to mapOf(
            "en" to "Use {max} characters or fewer.", "es" to "Usa {max} caracteres o menos.", "pt-br" to "Use no máximo {max} caracteres.", "ko" to "{max}자 이하로 입력해 주세요.",
            "ja" to "{max} 文字以内で入力してください。", "zh-cn" to "最多 {max} 个字符。", "zh-tw" to "最多 {max} 個字元。",
        ),
        ActionCardFormIssueCode.TooMany to mapOf(
            "en" to "Choose {max} or fewer.", "es" to "Elige {max} o menos.", "pt-br" to "Escolha no máximo {max}.", "ko" to "{max}개 이하로 선택해 주세요.",
            "ja" to "{max} 個以内で選択してください。", "zh-cn" to "最多选择 {max} 项。", "zh-tw" to "最多選擇 {max} 項。",
        ),
        ActionCardFormIssueCode.InvalidOption to mapOf(
            "en" to "Choose one of the options.", "es" to "Elige una de las opciones.", "pt-br" to "Escolha uma das opções.", "ko" to "옵션 중에서 선택해 주세요.",
            "ja" to "選択肢から選択してください。", "zh-cn" to "请从选项中选择。", "zh-tw" to "請從選項中選擇。",
        ),
        ActionCardFormIssueCode.NotFound to mapOf(
            "en" to "This no longer exists.", "es" to "Ya no existe.", "pt-br" to "Isso não existe mais.", "ko" to "더 이상 존재하지 않아요.",
            "ja" to "これはもう存在しません。", "zh-cn" to "该项已不存在。", "zh-tw" to "該項目已不存在。",
        ),
        ActionCardFormIssueCode.NotAllowed to mapOf(
            "en" to "You can't choose this.", "es" to "No puedes elegir esto.", "pt-br" to "Você não pode escolher isso.", "ko" to "이 항목은 선택할 수 없어요.",
            "ja" to "これは選択できません。", "zh-cn" to "你不能选择此项。", "zh-tw" to "你無法選擇此項。",
        ),
        ActionCardFormIssueCode.Conflict to mapOf(
            "en" to "This is already taken.", "es" to "Ya está en uso.", "pt-br" to "Isso já está em uso.", "ko" to "이미 사용 중이에요.",
            "ja" to "すでに使用されています。", "zh-cn" to "已被占用。", "zh-tw" to "已被使用。",
        ),
        ActionCardFormIssueCode.InvalidFormat to mapOf(
            "en" to "This value is not valid.", "es" to "Este valor no es válido.", "pt-br" to "Este valor não é válido.", "ko" to "유효하지 않은 값이에요.",
            "ja" to "この値は無効です。", "zh-cn" to "此值无效。", "zh-tw" to "此值無效。",
        ),
    ).associate { (code, text) -> code to ActionCardSpecMsg(key = "actionCard.form.issue.${code.wire}", text = text) }

/** An issue with the generic message for its [code] (`actionCardFormIssue`). */
fun actionCardFormIssue(
    field: String?,
    code: ActionCardFormIssueCode,
    params: Map<String, ActionCardSpecMsgParam>? = null,
): ActionCardFormIssue = ActionCardFormIssue(field, code, ACTION_CARD_FORM_ISSUE_MESSAGES.getValue(code).copy(params = params))

/**
 * What "empty" means for a required text field: only these code points (JavaScript's `trim` set,
 * spelled out because Kotlin's `trim` removes a different one): U+0009-U+000D, U+0020, U+00A0,
 * U+1680, U+2000-U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF.
 */
internal fun isActionCardBlank(value: String): Boolean =
    value.all { c ->
        c in '\u0009'..'\u000d' || c == ' ' || c == ' ' || c == ' ' || c in ' '..' ' ||
            c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'
    }

/**
 * The client checks before a form is submitted (`checkActionCardFormValues(form, values, { mode:
 * "client" })`): at most one issue per field, in field order. `required` (text: blank, see
 * [isActionCardBlank]; Select: missing or ""; a picker: no ids), `too_long` (code points over
 * maxLength), `too_many` (more ids than the picker takes). A missing key is "not provided".
 * Unsupported fields are skipped. Whether a Select value is an option, ids' format, and whether
 * each id exists and may be chosen are the server's (422 `FORM_INVALID`).
 */
fun checkActionCardFormValues(form: ActionCardSpecForm, values: Map<String, ActionCardFormValue>): List<ActionCardFormIssue> =
    checkActionCardFormValuesJson(
        form,
        buildJsonObject {
            for ((name, value) in values) {
                when (value) {
                    is ActionCardFormValue.Text -> put(name, value.value)
                    is ActionCardFormValue.Toggle -> put(name, value.value)
                    is ActionCardFormValue.Ids -> put(name, buildJsonArray { value.ids.forEach { add(JsonPrimitive(it)) } })
                }
            }
        },
    )

/** [checkActionCardFormValues] over the wire `values` (a value of the wrong JSON type is left to the server). */
internal fun checkActionCardFormValuesJson(form: ActionCardSpecForm, values: JsonElement?): List<ActionCardFormIssue> {
    val submitted = values as? JsonObject ?: JsonObject(emptyMap())
    return form.fields.mapNotNull { field ->
        if (field !is ActionCardSpecKnownField) return@mapNotNull null
        val provided = field.name in submitted
        val value = submitted[field.name]
        when (field) {
            is ActionCardSpecTextInputField, is ActionCardSpecTextAreaField, is ActionCardSpecSelectField -> {
                if (provided && value.cardString() == null) return@mapNotNull null
                val text = value.cardString()
                val isSelect = field is ActionCardSpecSelectField
                val required = when (field) {
                    is ActionCardSpecTextInputField -> field.required
                    is ActionCardSpecTextAreaField -> field.required
                    is ActionCardSpecSelectField -> field.required
                    else -> null
                } == true
                val empty = text == null || (if (isSelect) text.isEmpty() else isActionCardBlank(text))
                when {
                    empty && required -> actionCardFormIssue(field.name, ActionCardFormIssueCode.Required)
                    text == null || isSelect -> null
                    else -> {
                        val maxLength = if (field is ActionCardSpecTextInputField) field.maxLength else (field as ActionCardSpecTextAreaField).maxLength
                        if (cardCodePoints(text) > maxLength) {
                            actionCardFormIssue(field.name, ActionCardFormIssueCode.TooLong, mapOf("max" to ActionCardSpecMsgParam.Integer(maxLength.toLong())))
                        } else {
                            null
                        }
                    }
                }
            }
            is ActionCardSpecToggleField -> null
            is ActionCardSpecPickerField -> {
                val ids = if (provided) (value as? JsonArray)?.takeIf { array -> array.all { it.cardString() != null } } ?: return@mapNotNull null else JsonArray(emptyList())
                when {
                    ids.isEmpty() -> if (field.required == true) actionCardFormIssue(field.name, ActionCardFormIssueCode.Required) else null
                    ids.size > field.maxSelectable ->
                        actionCardFormIssue(field.name, ActionCardFormIssueCode.TooMany, mapOf("max" to ActionCardSpecMsgParam.Integer(field.maxSelectable.toLong())))
                    else -> null
                }
            }
        }
    }
}
