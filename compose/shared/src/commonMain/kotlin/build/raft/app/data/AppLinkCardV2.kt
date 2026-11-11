package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * App link cards, version 2 (RFC 076; protocol v3.2.3 §4.3, §4.4), read-only step: the batched
 * link-card read's answer and the v2 card catalog, as the web reads them.
 *
 * Contract: botiverse/slock staging 8de484350501d0c7e0962f90ae7883762f447e7e
 *  - `packages/shared/src/appLinkPreview.ts`: `linkCardV2Schema` (the v2 element union),
 *    `linkCardInlineSchema`, `appLinkPreviewV2ResponseSchema`, `parseAppLinkPreviewV2` (navigate
 *    URLs stay on the link's origin), `linkCardReadItemSchema`, `parseLinkCardReadAnswer`.
 *  - `packages/shared/src/cardElements.ts`: Stack, RelativeTime, Duration, badge tones, the tree check.
 *  - `packages/server/src/services/linkCardReadService.ts` (`parseLinkCardReadBody`, `readLinkCards`),
 *    `packages/server/src/routes/messages.ts` (`POST /:messageId/link-cards/read`).
 *  - `packages/web/src/components/message/linkCardRead.ts` (what the web sends),
 *    `useAppLinkPreviews.ts` `readLinkCardAnswer` (items to per-link results).
 *
 * Where this client is more lenient than the shared schema (Tenny's rule for step 1, 2026-11-03): a
 * Badge or inline badge tone it does not know reads `neutral` instead of refusing the card. Every
 * other check is the schema's, so a card the web would refuse is a plain link here too. A kind of
 * item this client does not know leaves only that link plain.
 */

/**
 * Capabilities this client declares on the batched read: v2 cards, their inline titles and the
 * inline badge's card tones (`inline.tone.v2`: every tone word is read, and every badge is drawn
 * neutral, as the web's CardBadge); `preview.v3`, so Raft answers version 3 items whichever version
 * the App sent (RFC 076 section 14; the block card is drawn after its paragraph, raft-design #1010);
 * and, as AppFather decided for touch clients (#proj-raft-ui), the full list `button.v1`,
 * `confirm.v1` and `undo.v1`: a block card's buttons work by touch, with Raft's confirmation and a
 * plain Undo button (the hover card and the popover stay read-only). `block.v1`, as the web declares
 * it (it changes nothing in a version 3 answer). The same list and order as the web's desktop one,
 * then `unreviewed_app.v1` (below).
 */
internal val APP_LINK_CARD_READ_CAPABILITIES: List<String> = listOf("card.v2", "inline.v1", "inline.tone.v2", "button.v1", "confirm.v1", "undo.v1", "block.v1", "preview.v3", APP_LINK_UNREVIEWED_APP_CAPABILITY)

/**
 * `unreviewed_app.v1` (RFC 076 section 17; botiverse/slock #13945 `UNREVIEWED_APP_CAPABILITY`): this
 * client shows Raft's confirmation, naming the App as not reviewed by Raft, before every acting
 * button of an App whose read item says `app.reviewed: false`, undo-window buttons included
 * ([build.raft.app.ThreadAppLinkCardPress]). Without it Raft draws such an App's buttons as navigate
 * only; a handle read without it is refused (410) at prepare and execute.
 */
internal const val APP_LINK_UNREVIEWED_APP_CAPABILITY = "unreviewed_app.v1"

/** The batched read's links per message with the flag on (`LINK_CARD_READ_MAX_URLS`). */
internal const val APP_LINK_CARD_READ_MAX_URLS = 10

/** Buttons in one v2 card (`MAX_BUTTONS`). */
internal const val APP_LINK_CARD_V2_MAX_BUTTONS = 4

private val LINK_CARD_ACTION_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

/** The request's `locale` (`parseLinkCardReadBody`: BCP 47, at most 35 characters). */
private val LINK_CARD_READ_LOCALE = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")

/** [tag] when the server takes it as the read's `locale`, else `en`. */
internal fun appLinkCardReadLocale(tag: String?): String =
    tag?.trim()?.replace('_', '-')?.takeIf { it.length <= 35 && LINK_CARD_READ_LOCALE.matches(it) } ?: "en"

/**
 * The body of `POST messages/:messageId/link-cards/read` (`parseLinkCardReadBody`, strict: exactly
 * these keys). [urls] reads only those links again; [tickets] are the view's, by the item `url`
 * they were issued for. Both left out when empty.
 */
@kotlinx.serialization.Serializable
internal data class AppLinkCardReadRequestBody(
    @kotlinx.serialization.SerialName("view_instance_id") val viewInstanceId: String,
    val capabilities: List<String>,
    val locale: String,
    val urls: List<String>? = null,
    val tickets: Map<String, String>? = null,
)

private val appLinkCardReadRequestJson = Json { explicitNulls = false }

internal fun AppLinkCardReadRequestBody.toJson(): String =
    appLinkCardReadRequestJson.encodeToString(AppLinkCardReadRequestBody.serializer(), this)

/** A v2 card: one bounded tree of [elements] from [root] (`linkCardV2Schema`), read through [parseRaftAppLinkCardV2]. */
data class RaftAppLinkCardV2(
    val root: String,
    /** By element id, in the App's order: [CardStack], [CardRelativeTime], [CardDuration] or a [RaftLinkCardV2Element]. */
    val elements: Map<String, CardElement>,
)

/** The v2 elements of its own (the shared Stack, RelativeTime and Duration are [CardElement]s already). */
sealed interface RaftLinkCardV2Element : CardElement {
    /** `Text`: 1-500 code points; [mono] for branch names, SHAs and the like. */
    data class Text(val text: String, val tone: RaftLinkCardTextTone = RaftLinkCardTextTone.Default, val mono: Boolean = false) : RaftLinkCardV2Element

    /** `Badge`: 1-60 code points. The web draws it neutral whatever [tone] (DS PreviewCard: an App's states are its own). */
    data class Badge(val text: String, val tone: CardBadgeTone = CardBadgeTone.Neutral) : RaftLinkCardV2Element

    /**
     * `Button`: 1-40 code points of [label]. Read here only so a card that carries one still reads;
     * this client declares no `button.v1`, so Raft strips them before they reach it, and it draws none.
     */
    data class Button(
        val label: String,
        val variant: RaftLinkCardButtonVariant,
        val action: RaftLinkCardButtonAction,
        val disabledReason: String? = null,
        /**
         * A message card Button's `open` (`MESSAGE_CARD_BUTTON_OPEN`, botiverse/slock #14133): where the
         * web opens the page (`beside`: its preview panel). Read only so such a card still reads; this
         * client opens every Button the same way whatever it says. Always null on a link card.
         */
        val open: RaftCardButtonOpen? = null,
    ) : RaftLinkCardV2Element
}

/** `MESSAGE_CARD_BUTTON_OPEN`: `tab` or `beside` (web preview). The App opens either the same way. */
enum class RaftCardButtonOpen { Tab, Beside }

enum class RaftLinkCardTextTone { Default, Muted, Warning }

enum class RaftLinkCardButtonVariant { Default, Ghost }

/** `linkCardButtonActionSchema`. */
sealed interface RaftLinkCardButtonAction {
    data class Navigate(val url: String) : RaftLinkCardButtonAction

    data object Read : RaftLinkCardButtonAction

    /** [confirm]: `confirm: "prepare"`; otherwise [undoWindowMs] (1000-10000). */
    data class Execute(val actionId: String, val confirm: Boolean, val undoWindowMs: Long? = null) : RaftLinkCardButtonAction
}

/** A version 3 `card` item with `placement: "block"`: [title] (the card's, up to 160) and its tree, drawn after the link's paragraph. */
data class AppLinkBlockCard(val title: String, val card: RaftAppLinkCardV2, val businessRevision: String = "")

/** A v2 answer's inline label (`linkCardInlineSchema`): [title] replaces the chip's words; the badge is never drawn on the chip. */
data class AppLinkInline(val title: String, val badge: AppLinkInlineBadge? = null)

/** [tone] is already the display tone (`inlineBadgeDisplayTone`: success is done, danger is closed). */
data class AppLinkInlineBadge(val text: String, val tone: CardBadgeTone)

/** The inline badge's words (`LINK_CARD_INLINE_BADGE_TONES`) as the card tone it is drawn with; anything else neutral. */
internal fun appLinkInlineBadgeDisplayTone(wire: String?): CardBadgeTone =
    when (wire) {
        "success" -> CardBadgeTone.Done
        "danger" -> CardBadgeTone.Closed
        else -> CardBadgeTone.fromWire(wire) ?: CardBadgeTone.Neutral
    }

/**
 * Reads a v2 `card` as `linkCardV2Schema` does (strict objects, known element types, lengths in
 * code points, at most 16 elements, depth 4, one tree, at most 4 Buttons, execute ids unique) and
 * `parseAppLinkPreviewV2` (every navigate URL on [origin]), or null. A Badge tone it does not
 * know reads neutral.
 */
fun parseRaftAppLinkCardV2(element: JsonElement, origin: String): RaftAppLinkCardV2? {
    val obj = element as? JsonObject ?: return null
    if (!obj.hasOnlyKeys(setOf("root", "elements"))) return null
    val root = obj["root"].cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null
    val rawElements = obj["elements"] as? JsonObject ?: return null
    val elements = LinkedHashMap<String, CardElement>()
    for ((id, raw) in rawElements) {
        if (!CARD_ELEMENT_ID.matches(id)) return null
        elements[id] = readLinkCardV2Element(raw) { action -> readLinkCardButtonAction(action, origin) } ?: return null
    }
    if (checkCardBoundedTree(root, elements, RAFT_APP_LINK_CARD_MAX_ELEMENTS, RAFT_APP_LINK_CARD_MAX_DEPTH) != null) return null
    val buttons = elements.values.filterIsInstance<RaftLinkCardV2Element.Button>()
    if (buttons.size > APP_LINK_CARD_V2_MAX_BUTTONS) return null
    val actionIds = buttons.mapNotNull { (it.action as? RaftLinkCardButtonAction.Execute)?.actionId }
    if (actionIds.toSet().size != actionIds.size) return null
    return RaftAppLinkCardV2(root, elements)
}

/**
 * One v2 element (strict, as `linkCardV2Schema`); a Button's action through [readAction], which
 * differs per catalog (a link card's navigate stays on its origin; a message card only navigates).
 * [buttonOpen]: a Button may carry `open` (`tab` or `beside`), as `messageCardButtonElement` allows
 * and `linkCardV2Schema` does not.
 */
internal fun readLinkCardV2Element(
    raw: JsonElement,
    buttonOpen: Boolean = false,
    readAction: (JsonElement?) -> RaftLinkCardButtonAction?,
): CardElement? {
    val obj = raw as? JsonObject ?: return null
    return when (obj["type"].cardString()) {
        "Stack" -> readCardStack(obj)
        "RelativeTime" -> readCardRelativeTime(obj)
        "Duration" -> readCardDuration(obj)
        "Text" -> {
            val props = obj.elementProps(setOf("text", "tone", "mono")) ?: return null
            val text = props["text"].cardString()?.takeIf { isCardText(it, 1, 500) } ?: return null
            val tone =
                if ("tone" in props) {
                    when (props["tone"].cardString()) {
                        "default" -> RaftLinkCardTextTone.Default
                        "muted" -> RaftLinkCardTextTone.Muted
                        "warning" -> RaftLinkCardTextTone.Warning
                        else -> return null
                    }
                } else {
                    RaftLinkCardTextTone.Default
                }
            val mono = if ("mono" in props) props["mono"].cardBoolean() ?: return null else false
            RaftLinkCardV2Element.Text(text, tone, mono)
        }
        "Badge" -> {
            val props = obj.elementProps(setOf("text", "tone")) ?: return null
            val text = props["text"].cardString()?.takeIf { isCardText(it, 1, 60) } ?: return null
            // Lenient: a tone this client does not know yet reads neutral (drawn neutral anyway).
            RaftLinkCardV2Element.Badge(text, CardBadgeTone.fromWire(props["tone"].cardString()) ?: CardBadgeTone.Neutral)
        }
        "Button" -> {
            val allowed = setOf("label", "variant", "action", "disabledReason")
            val props = obj.elementProps(if (buttonOpen) allowed + "open" else allowed) ?: return null
            val label = props["label"].cardString()?.takeIf { isCardText(it, 1, 40) } ?: return null
            val variant =
                if ("variant" in props) {
                    when (props["variant"].cardString()) {
                        "default" -> RaftLinkCardButtonVariant.Default
                        "ghost" -> RaftLinkCardButtonVariant.Ghost
                        else -> return null
                    }
                } else {
                    RaftLinkCardButtonVariant.Default
                }
            val action = readAction(props["action"]) ?: return null
            val disabledReason =
                if ("disabledReason" in props) props["disabledReason"].cardString()?.takeIf { isCardText(it, 1, 200) } ?: return null else null
            val open =
                if ("open" in props) {
                    when (props["open"].cardString()) {
                        "tab" -> RaftCardButtonOpen.Tab
                        "beside" -> RaftCardButtonOpen.Beside
                        else -> return null
                    }
                } else {
                    null
                }
            RaftLinkCardV2Element.Button(label, variant, action, disabledReason, open)
        }
        else -> null
    }
}

/** `{type, props}` with props among [allowed] (strict), or null. */
internal fun JsonObject.elementProps(allowed: Set<String>): JsonObject? {
    if (!hasOnlyKeys(setOf("type", "props"))) return null
    val props = this["props"] as? JsonObject ?: return null
    return props.takeIf { it.hasOnlyKeys(allowed) }
}

private fun readLinkCardButtonAction(raw: JsonElement?, origin: String): RaftLinkCardButtonAction? {
    val obj = raw as? JsonObject ?: return null
    return when (obj["kind"].cardString()) {
        "navigate" -> {
            if (!obj.hasOnlyKeys(setOf("kind", "url"))) return null
            val url = obj["url"].cardString()?.takeIf { isCardPublicUrl(it) } ?: return null
            if (appLinkHrefOrigin(url) != origin) return null
            RaftLinkCardButtonAction.Navigate(url)
        }
        "read" -> if (obj.hasOnlyKeys(setOf("kind"))) RaftLinkCardButtonAction.Read else null
        "execute" -> {
            val actionId = obj["action_id"].cardString()?.takeIf(LINK_CARD_ACTION_ID::matches) ?: return null
            when {
                obj.keys == setOf("kind", "action_id", "confirm") ->
                    if (obj["confirm"].cardString() == "prepare") RaftLinkCardButtonAction.Execute(actionId, confirm = true) else null
                obj.keys == setOf("kind", "action_id", "undoWindowMs") -> {
                    val window = obj["undoWindowMs"].cardSafeInteger()?.takeIf { it in 1000..10000 } ?: return null
                    RaftLinkCardButtonAction.Execute(actionId, confirm = false, undoWindowMs = window)
                }
                else -> null
            }
        }
        else -> null
    }
}

/** `https://host` of a public URL, lower-cased (WHATWG `origin` for the URLs [isCardPublicUrl] takes). */
internal fun appLinkHrefOrigin(url: String): String {
    val rest = url.substring("https://".length)
    val authority = rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
    return "https://" + authority.substringBefore(':').lowercase()
}

/**
 * One batched read answer (`{ items, prompts }`), as one result per link (web `readLinkCardAnswer`)
 * plus what the view keeps: [limited], the links answered `rate_limited` (the item `url` -> its
 * `retry_after` seconds), to read again; [tickets], the ticket per item `url` (null: the view drops
 * its ticket for that link).
 */
internal data class AppLinkCardReadAnswer(
    val results: Map<String, AppLinkResult>,
    val limited: Map<String, Int>,
    val tickets: Map<String, String?>,
)

/**
 * Reads a batched read answer, or null when the body is not a JSON object. Not strict at the top
 * level; each item on its own (strict, like `linkCardReadItemSchema`), so an item this client can't
 * read, a kind added later included, leaves only its link plain. `display: "link"` and (step 1)
 * `connect_account` are no result. At most 10 items. The prompts are read as the GET's.
 */
internal fun parseAppLinkCardReadAnswer(
    bodyText: String,
    resolveIcon: (String) -> String? = { it },
): AppLinkCardReadAnswer? {
    val body = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    val items = (body["items"] as? JsonArray).orEmpty().take(APP_LINK_CARD_READ_MAX_URLS)
    val results = LinkedHashMap<String, AppLinkResult>()
    val limited = LinkedHashMap<String, Int>()
    val limitedKeys = mutableSetOf<String>()
    val tickets = LinkedHashMap<String, String?>()
    for (raw in items) {
        val item = readLinkCardReadItem(raw) ?: continue
        val key = appLinkPreviewHref(item.url) ?: continue
        if (key in results || key in limitedKeys) continue
        when (item) {
            is LinkCardReadItem.RateLimited -> {
                limited[item.url] = item.retryAfter
                limitedKeys += key
            }
            is LinkCardReadItem.Card -> {
                tickets[item.url] = item.ticket
                results[key] = item.result.copy(url = key, app = item.result.app.copy(icon = item.result.app.icon?.let(resolveIcon)))
            }
            is LinkCardReadItem.V1 ->
                results[key] = item.result.copy(url = key, app = item.result.app.copy(icon = item.result.app.icon?.let(resolveIcon)))
        }
    }
    val prompts = (body["prompts"] as? JsonArray)?.toList().orEmpty()
    return AppLinkCardReadAnswer(appLinkResultsWithPrompts(results, prompts, resolveIcon), limited, tickets)
}

private sealed interface LinkCardReadItem {
    val url: String

    data class V1(override val url: String, val result: AppLinkResult.Card) : LinkCardReadItem

    data class Card(override val url: String, val result: AppLinkResult.Card, val ticket: String?) : LinkCardReadItem

    data class RateLimited(override val url: String, val retryAfter: Int) : LinkCardReadItem
}

private fun readLinkCardReadItem(raw: JsonElement): LinkCardReadItem? {
    val obj = raw as? JsonObject ?: return null
    val url = obj["url"].cardString()?.takeIf { appLinkPreviewHref(it) != null } ?: return null
    return when (obj["kind"].cardString()) {
        "rate_limited" -> {
            if (!obj.hasOnlyKeys(setOf("kind", "url", "retry_after"))) return null
            val retryAfter = obj["retry_after"].cardSafeInteger()?.takeIf { it in 0..3600 } ?: return null
            LinkCardReadItem.RateLimited(url, retryAfter.toInt())
        }
        "v1" -> {
            if (!obj.hasOnlyKeys(setOf("kind", "url", "app", "preview"))) return null
            val app = readLinkCardReadApp(obj["app"]) ?: return null
            val preview = obj["preview"] as? JsonObject ?: return null
            val content = readAppLinkPreviewContent(preview, APP_LINK_PREVIEW_CONTENT_KEYS) ?: return null
            LinkCardReadItem.V1(url, AppLinkResult.Card(url = url, app = app, title = content.first, card = content.second))
        }
        "v2", "v3" -> {
            if (obj.keys != setOf("kind", "url", "app", "preview", "ticket", "actions")) return null
            val app = readLinkCardReadApp(obj["app"]) ?: return null
            val ticket =
                when (val value = obj["ticket"]) {
                    is JsonNull -> null
                    else -> value.cardString()?.takeIf { it.length in 1..4096 } ?: return null
                }
            val actions = obj["actions"] as? JsonArray ?: return null
            if (actions.size > APP_LINK_CARD_V2_MAX_BUTTONS) return null
            val handles = LinkedHashMap<String, String>()
            for (action in actions) {
                val entry = action as? JsonObject ?: return null
                if (entry.keys != setOf("action_id", "handle")) return null
                val actionId = entry["action_id"].cardString()?.takeIf(LINK_CARD_ACTION_ID::matches) ?: return null
                handles[actionId] = entry["handle"].cardString()?.takeIf { it.length in 1..4096 } ?: return null
            }
            val preview = obj["preview"] as? JsonObject ?: return null
            if (obj["kind"].cardString() == "v3") return readLinkCardV3Preview(url, app, ticket, preview, handles)
            if (!preview["version"].isJsonNumberLiteral(2.0)) return null
            when (preview["display"].cardString()) {
                "card" -> {
                    val keys = setOf("version", "display", "title", "card_revision", "business_revision", "card", "inline")
                    if (!preview.hasOnlyKeys(keys)) return null
                    val title = preview["title"].cardString()?.takeIf { isCardText(it, 1, 160) } ?: return null
                    if (preview["card_revision"].cardString()?.length !in 1..512) return null
                    if (preview["business_revision"].cardString()?.length !in 1..512) return null
                    val card = parseRaftAppLinkCardV2(preview["card"] ?: return null, appLinkHrefOrigin(url)) ?: return null
                    val inline = if ("inline" in preview) readAppLinkInline(preview["inline"]) ?: return null else null
                    LinkCardReadItem.Card(url, AppLinkResult.Card(url = url, app = app, title = title, cardV2 = card, inline = inline), ticket)
                }
                // The link stays plain: `link` is no item, as on the web, and a version 2 connect_account is not drawn
                // (a client that declares `preview.v3` gets it as a version 3 item).
                else -> null
            }
        }
        else -> null
    }
}

/**
 * `app` of an item (strict `{ id, name, icon }` plus an optional `reviewed`, `LinkCardReadApp`).
 * `reviewed` never drops the card (the web's strict `app` once dropped every unreviewed card, slock
 * #13956). Raft sends it only as `false` (RFC 076 section 17); fail-safe, any value present reads as
 * unreviewed (`reviewed = false`: Raft's confirmation first), and only its absence as reviewed.
 */
private fun readLinkCardReadApp(raw: JsonElement?): AppLinkApp? {
    val obj = raw as? JsonObject ?: return null
    if (obj.keys != setOf("id", "name", "icon") && obj.keys != setOf("id", "name", "icon", "reviewed")) return null
    val reviewed = if ("reviewed" in obj) false else null
    if (obj["id"].cardString()?.length !in 1..200) return null
    val name = obj["name"].cardString()?.takeIf { it.length <= 300 } ?: return null
    val icon =
        when (val value = obj["icon"]) {
            is JsonNull -> null
            else -> value.cardString()?.takeIf { it.length <= APP_LINK_PREVIEW_URL_MAX_LENGTH } ?: return null
        }
    return AppLinkApp(name = name, icon = icon, reviewed = reviewed)
}

/** `linkCardInlineSchema`; a badge tone this client does not know reads neutral. */
private fun readAppLinkInline(raw: JsonElement?, typed: Boolean = false): AppLinkInline? {
    val obj = raw as? JsonObject ?: return null
    if (!obj.hasOnlyKeys(if (typed) setOf("type", "title", "badge") else setOf("title", "badge"))) return null
    val title = obj["title"].cardString()?.takeIf { isCardText(it, 1, 80) } ?: return null
    val badge =
        if ("badge" in obj) {
            val badge = obj["badge"] as? JsonObject ?: return null
            if (!badge.hasOnlyKeys(setOf("text", "tone"))) return null
            val text = badge["text"].cardString()?.takeIf { isCardText(it, 1, 24) } ?: return null
            AppLinkInlineBadge(text, appLinkInlineBadgeDisplayTone(badge["tone"].cardString()))
        } else {
            null
        }
    return AppLinkInline(title, badge)
}

/** A version 3 answer's items at most (`APP_LINK_PREVIEW_MAX_ITEMS`). */
internal const val APP_LINK_PREVIEW_V3_MAX_ITEMS = 8

/** Every item's `type`, known or not (`APP_LINK_PREVIEW_ITEM_TYPE`). */
private val APP_LINK_V3_ITEM_TYPE = Regex("^[a-z0-9._]{1,32}$")

/**
 * A version 3 answer (RFC 076 §14; botiverse/slock #13109 `appLinkPreviewV3ResponseSchema` and
 * `parseAppLinkPreviewV3`): `{ version: 3, items }`, at most 8 items, each an object with a `type` of
 * 1-32 characters from `a-z 0-9 . _`. A type this client does not know is skipped and the rest
 * stands (display only: executable content comes with a capability of its own); a known item is as
 * strict as in version 2. At most one `inline` and one `card`, and a `connect_account` only alone.
 * Any failure leaves the link plain. A top-level `title` (the design system's example has one; the
 * server's schema has none) is tolerated.
 *
 * What the items draw (raft-design #1010, "What an app's answer lists"):
 *  - `inline`: the link is the chip with the item's title ([AppLinkResult.Card.inline]);
 *  - `card` `hover`: the chip's card ([AppLinkResult.Card.cardV2]); without `inline` the chip takes the card's title;
 *  - `card` `block`: [AppLinkResult.Card.block], drawn after the link's paragraph; the link is the
 *    chip only when the answer lists `inline` ([AppLinkResult.Card.chip]), else the author's plain link;
 *  - `connect_account`: [AppLinkResult.Card.connectUrl];
 *  - nothing known (`[]`): no result, the author's plain link.
 */
private fun readLinkCardV3Preview(url: String, app: AppLinkApp, ticket: String?, preview: JsonObject, handles: Map<String, String> = emptyMap()): LinkCardReadItem? {
    if (!preview["version"].isJsonNumberLiteral(3.0)) return null
    if (!preview.hasOnlyKeys(setOf("version", "title", "items"))) return null
    if ("title" in preview && preview["title"].cardString()?.let { isCardText(it, 1, 160) } != true) return null
    val items = preview["items"] as? JsonArray ?: return null
    if (items.size > APP_LINK_PREVIEW_V3_MAX_ITEMS) return null
    val origin = appLinkHrefOrigin(url)
    var inline: AppLinkInline? = null
    var card: AppLinkV3Card? = null
    var connectUrl: String? = null
    for (raw in items) {
        val item = raw as? JsonObject ?: return null
        val type = item["type"].cardString()?.takeIf(APP_LINK_V3_ITEM_TYPE::matches) ?: return null
        when (type) {
            "inline" -> {
                if (inline != null) return null
                inline = readAppLinkInline(item, typed = true) ?: return null
            }
            "card" -> {
                if (card != null) return null
                card = readAppLinkV3Card(item, origin) ?: return null
            }
            "connect_account" -> {
                if (connectUrl != null || !item.hasOnlyKeys(setOf("type", "connect_url"))) return null
                val target = item["connect_url"].cardString()?.takeIf { isCardPublicUrl(it) } ?: return null
                if (appLinkHrefOrigin(target) != origin) return null
                connectUrl = target
            }
            // An item type this client does not know: skipped.
            else -> Unit
        }
    }
    val chipLabel = inline
    val blockOrHover = card
    if (connectUrl != null && (chipLabel != null || blockOrHover != null)) return null
    val result =
        if (connectUrl != null) {
            // The chip is the shortened address, as the web draws it (slock #13176).
            AppLinkResult.Card(url = url, app = app, title = appLinkShortAddress(url) ?: app.name, connectUrl = connectUrl)
        } else if (blockOrHover == null) {
            // An `inline` alone is the chip and nothing to open; no known item at all is the plain link.
            if (chipLabel == null) return null
            AppLinkResult.Card(url = url, app = app, title = chipLabel.title, inline = chipLabel)
        } else if (blockOrHover.block) {
            AppLinkResult.Card(
                url = url,
                app = app,
                title = blockOrHover.title,
                inline = chipLabel,
                block = AppLinkBlockCard(blockOrHover.title, blockOrHover.card, blockOrHover.businessRevision),
                chip = chipLabel != null,
                handles = handles,
            )
        } else {
            AppLinkResult.Card(url = url, app = app, title = blockOrHover.title, cardV2 = blockOrHover.card, inline = chipLabel)
        }
    return LinkCardReadItem.Card(url, result, ticket)
}

/** A version 3 `card` item: [block] false is `hover`. */
private class AppLinkV3Card(val block: Boolean, val title: String, val card: RaftAppLinkCardV2, val businessRevision: String)

private fun readAppLinkV3Card(item: JsonObject, origin: String): AppLinkV3Card? {
    if (!item.hasOnlyKeys(setOf("type", "placement", "title", "card_revision", "business_revision", "card"))) return null
    val block =
        when (item["placement"].cardString()) {
            "block" -> true
            "hover" -> false
            else -> return null
        }
    val title = item["title"].cardString()?.takeIf { isCardText(it, 1, 160) } ?: return null
    if (item["card_revision"].cardString()?.length !in 1..512) return null
    val businessRevision = item["business_revision"].cardString()?.takeIf { it.length in 1..512 } ?: return null
    val card = parseRaftAppLinkCardV2(item["card"] ?: return null, origin) ?: return null
    return AppLinkV3Card(block, title, card, businessRevision)
}

/** A JSON number equal to [value] (zod `z.literal(2)`). */
private fun JsonElement?.isJsonNumberLiteral(value: Double): Boolean {
    val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return false
    if (primitive.isString || primitive is JsonNull) return false
    return primitive.content.toDoubleOrNull() == value
}

/**
 * The wait before a view's next re-read of its rate_limited links (web `rateLimitedBackoffMs`,
 * `nextLinkCardRetry`), without the jitter: the first is Raft's `retry_after` clamped to 1-60 s;
 * each later one at least double the last and never sooner than Raft asked ([retryAfterSeconds],
 * clamped likewise; null when the re-read got no answer), 60 s at most.
 */
internal fun appLinkRereadBackoffMs(lastBackoffMs: Long, retryAfterSeconds: Int?): Long {
    val asked = retryAfterSeconds?.coerceIn(1, 60)?.times(1000L)
    if (lastBackoffMs <= 0L) return asked ?: 1000L
    return minOf(60_000L, maxOf(asked ?: 0L, lastBackoffMs * 2))
}

/** Re-reads of rate_limited links per message view (`LINK_CARD_READ_RETRIES`). */
internal const val APP_LINK_CARD_READ_RETRIES = 10

/** Up to this much is added to a re-read's wait at random (`RETRY_JITTER_MS`), 60 s at most in all. */
internal const val APP_LINK_CARD_RETRY_JITTER_MS = 1000L
