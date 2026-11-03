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
 * Capabilities this client declares on the batched read (step 1, read-only): v2 cards, their inline
 * titles and the inline badge's card tones (`inline.tone.v2`: every tone word is read, and every
 * badge is drawn neutral, as the web's CardBadge). No `button.v1`, `confirm.v1` or `undo.v1`, so Raft
 * strips the buttons and signs no handle.
 */
internal val APP_LINK_CARD_READ_CAPABILITIES: List<String> = listOf("card.v2", "inline.v1", "inline.tone.v2")

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
    ) : RaftLinkCardV2Element
}

enum class RaftLinkCardTextTone { Default, Muted, Warning }

enum class RaftLinkCardButtonVariant { Default, Ghost }

/** `linkCardButtonActionSchema`. */
sealed interface RaftLinkCardButtonAction {
    data class Navigate(val url: String) : RaftLinkCardButtonAction

    data object Read : RaftLinkCardButtonAction

    /** [confirm]: `confirm: "prepare"`; otherwise [undoWindowMs] (1000-10000). */
    data class Execute(val actionId: String, val confirm: Boolean, val undoWindowMs: Long? = null) : RaftLinkCardButtonAction
}

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
 */
internal fun readLinkCardV2Element(raw: JsonElement, readAction: (JsonElement?) -> RaftLinkCardButtonAction?): CardElement? {
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
            val props = obj.elementProps(setOf("label", "variant", "action", "disabledReason")) ?: return null
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
            RaftLinkCardV2Element.Button(label, variant, action, disabledReason)
        }
        else -> null
    }
}

/** `{type, props}` with props among [allowed] (strict), or null. */
private fun JsonObject.elementProps(allowed: Set<String>): JsonObject? {
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
        "v2" -> {
            if (obj.keys != setOf("kind", "url", "app", "preview", "ticket", "actions")) return null
            val app = readLinkCardReadApp(obj["app"]) ?: return null
            val ticket =
                when (val value = obj["ticket"]) {
                    is JsonNull -> null
                    else -> value.cardString()?.takeIf { it.length in 1..4096 } ?: return null
                }
            val actions = obj["actions"] as? JsonArray ?: return null
            if (actions.size > APP_LINK_CARD_V2_MAX_BUTTONS) return null
            for (action in actions) {
                val entry = action as? JsonObject ?: return null
                if (entry.keys != setOf("action_id", "handle")) return null
                if (entry["action_id"].cardString()?.let(LINK_CARD_ACTION_ID::matches) != true) return null
                if (entry["handle"].cardString()?.length !in 1..4096) return null
            }
            val preview = obj["preview"] as? JsonObject ?: return null
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
                // Step 1 draws no connect-account card: the link stays plain. `link` is no item, as on the web.
                else -> null
            }
        }
        else -> null
    }
}

/** `app` of an item (strict `{ id, name, icon }`). */
private fun readLinkCardReadApp(raw: JsonElement?): AppLinkApp? {
    val obj = raw as? JsonObject ?: return null
    if (obj.keys != setOf("id", "name", "icon")) return null
    if (obj["id"].cardString()?.length !in 1..200) return null
    val name = obj["name"].cardString()?.takeIf { it.length <= 300 } ?: return null
    val icon =
        when (val value = obj["icon"]) {
            is JsonNull -> null
            else -> value.cardString()?.takeIf { it.length <= APP_LINK_PREVIEW_URL_MAX_LENGTH } ?: return null
        }
    return AppLinkApp(name = name, icon = icon)
}

/** `linkCardInlineSchema`; a badge tone this client does not know reads neutral. */
private fun readAppLinkInline(raw: JsonElement?): AppLinkInline? {
    val obj = raw as? JsonObject ?: return null
    if (!obj.hasOnlyKeys(setOf("title", "badge"))) return null
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
