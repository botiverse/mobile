package build.raft.app.data

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Card messages (RFC 082): an agent (an App's bot included) posts a message with a card, a small
 * display-only element tree, and may later replace it by message id. `content` is the card's
 * plain-text fallback, kept current by the server with every update.
 *
 * Contract: botiverse/slock staging 1e9266662c9a7853f798a919a157f8d0c4b5b1af
 *  - `packages/shared/src/messageCard.ts`: `MESSAGE_CARD_KIND`, `messageCardSchema` (the link card
 *    v2 elements and bounds, `version: 1`, Buttons that only navigate to a public HTTPS URL),
 *    `MessageCardMetadata` (`actionMetadata = { kind: "message-card", card, revision, updatedAt }`).
 *  - `packages/web/src/components/message/messageCardRead.ts` (what the web draws as a card, else
 *    the fallback) and `packages/web/src/store/messageCardRevision.ts` (an older revision never
 *    replaces a newer one).
 *  - `packages/server/src/routes/internalAgentApi.ts` (an update emits `message:updated` with the
 *    whole message) and `routes/sync.ts` (the `message` entity of `GET /api/sync/changes`: the
 *    message list's row).
 *
 * The card travels on the projected message as metadata strings (like an action card's): the
 * card's JSON, its revision and when it was last updated. It is read again, strictly, only where
 * it is drawn ([readMessageCardFromMetadata]); anything this client can't read is drawn as the
 * fallback text, never a blank card and never as one of Raft's own action cards.
 *
 * v2.0 (RFC 082 §4.8; botiverse/slock #13224 + #13237, staging @ acf01e4f9703975febb80183251c3d7d22b81e01,
 * `messageCard.ts` `messageCardHeaderSchema`, `messageCardMarkdownElement`,
 * `messageCardFieldsElement`, `messageCardDividerElement`): an optional card `header` next to
 * `root` ([RaftMessageCardHeader]) and the `Markdown`, `Fields` and `Divider` elements
 * ([RaftMessageCardElement]). The card stays version 1; this reader stays all-or-nothing, so
 * anything it doesn't know still shows the whole fallback. The shared contract vectors
 * (`messageCard.v1.vector.json`, vendored for the unit tests) pin it.
 */

/** `actionMetadata.kind` of a card message (`MESSAGE_CARD_KIND`). */
internal const val MESSAGE_CARD_KIND = "message-card"

/** `card.version` (`MESSAGE_CARD_VERSION`). */
internal const val MESSAGE_CARD_VERSION = 1

/** Buttons in one card (`MESSAGE_CARD_LIMITS.maxButtons`). */
internal const val MESSAGE_CARD_MAX_BUTTONS = 4

private const val MessageCardMetadataPrefix = "messageCard."
private const val MessageCardMetadataCard = "${MessageCardMetadataPrefix}card"
private const val MessageCardMetadataRevision = "${MessageCardMetadataPrefix}revision"
private const val MessageCardMetadataUpdatedAt = "${MessageCardMetadataPrefix}updatedAt"

/** RFC 086: the card message's `actions` (JSON), as stored; read with [readMessageCardActingButtons]. */
internal const val MessageCardMetadataActions = "${MessageCardMetadataPrefix}actions"

/** RFC 086: the card message's `for` (a user id). */
internal const val MessageCardMetadataFor = "${MessageCardMetadataPrefix}for"

/**
 * A card message's card as this client draws it (web `ReadMessageCard`): the element tree (the
 * link card v2 tree, so the v2 renderer draws it), its [revision] (1 when posted) and when it was
 * posted or last replaced ([updatedAt], ISO UTC; null when it isn't a time this client reads).
 */
data class RaftMessageCard(
    val card: RaftAppLinkCardV2,
    val revision: Int,
    val updatedAt: String?,
    /** The sender's header (v2.0), drawn at the top of the card; null when the card has none. */
    val header: RaftMessageCardHeader? = null,
    /**
     * RFC 086: an App's acting buttons (its `actions`, read against this card) and the one person
     * they are for; null when every Button only navigates.
     */
    val acting: RaftMessageCardActingButtons? = null,
) {
    /** "Updated 3 min ago" once the card has been replaced (RFC 082 §5, web `messageCardShowsUpdated`). */
    val showsUpdated: Boolean get() = revision > 1 && updatedAt != null
}

/**
 * The metadata strings a card message's `actionMetadata` projects to (`isMessageCardMetadata`: the
 * kind, a revision >= 1 and a card object; the card itself is read when drawn). Empty for anything
 * else.
 */
internal fun WireActionCardMetadata.messageCardMetadata(): Map<String, String> {
    if (kind != MESSAGE_CARD_KIND) return emptyMap()
    val card = card as? JsonObject ?: return emptyMap()
    val revision = revision?.takeIf { it >= 1 } ?: return emptyMap()
    return buildMap {
        put(MessageCardMetadataCard, card.toString())
        put(MessageCardMetadataRevision, revision.toString())
        updatedAt?.let { put(MessageCardMetadataUpdatedAt, it) }
        // RFC 086: kept as stored; read against the card where it is drawn.
        (actions as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put(MessageCardMetadataActions, it.toString()) }
        forUserId.cardString()?.takeIf { it.isNotEmpty() }?.let { put(MessageCardMetadataFor, it) }
    }
}

/**
 * A projected message's metadata from its wire `actionMetadata`: Raft's action card, else a card message's card,
 * else a channel announcement's change line, a task's assignment line or a delegated-send receipt, else none.
 */
internal fun WireActionCardMetadata?.toMessageItemMetadata(): Map<String, String> {
    val meta = this ?: return emptyMap()
    return meta.toThreadActionCard()?.toMessageMetadata()
        ?: meta.messageCardMetadata()
            .ifEmpty { meta.channelAnnouncementNotice()?.toMessageMetadata().orEmpty() }
            .ifEmpty { meta.taskAssignmentNotice()?.toMessageMetadata().orEmpty() }
            .ifEmpty { meta.delegatedReceiptMetadata() }
}

/** The card revision a projected message carries; null when it is not a card message. */
internal fun messageCardRevisionOf(metadata: Map<String, String>): Int? = metadata[MessageCardMetadataRevision]?.toIntOrNull()

/**
 * Web `isOlderCardRevision`: both copies are card revisions and the incoming one is older than the
 * one held. The live update and the sync log's copy can cross; the older never replaces the newer.
 */
internal fun isOlderMessageCardRevision(held: Map<String, String>, incoming: Map<String, String>): Boolean {
    val heldRevision = messageCardRevisionOf(held) ?: return false
    val incomingRevision = messageCardRevisionOf(incoming) ?: return false
    return incomingRevision < heldRevision
}

private const val MESSAGE_CARD_CACHE_SIZE = 64

private data class MessageCardCacheKey(
    val card: String,
    val revision: String,
    val updatedAt: String?,
    val actions: String?,
    val forUserId: String?,
)

private val messageCardCache = atomic(LinkedHashMap<MessageCardCacheKey, RaftMessageCard?>())

/**
 * The card to draw for a projected message's [metadata]; null: draw its text (web
 * `readMessageCard`). Reads the card with [parseRaftMessageCard] (cached by its JSON, a list
 * recomposes often).
 */
internal fun readMessageCardFromMetadata(metadata: Map<String, String>): RaftMessageCard? {
    val json = metadata[MessageCardMetadataCard] ?: return null
    val revisionText = metadata[MessageCardMetadataRevision] ?: return null
    val key =
        MessageCardCacheKey(
            json,
            revisionText,
            metadata[MessageCardMetadataUpdatedAt],
            metadata[MessageCardMetadataActions],
            metadata[MessageCardMetadataFor],
        )
    val cache = messageCardCache.value
    if (cache.containsKey(key)) return cache[key]
    val revision = revisionText.toIntOrNull()?.takeIf { it >= 1 }
    val card = revision?.let { runCatching { Json.parseToJsonElement(json) }.getOrNull()?.let(::parseRaftMessageCard) }
    val read =
        card?.let {
            RaftMessageCard(
                card = it.card,
                revision = revision,
                updatedAt = key.updatedAt?.takeIf(::isMessageCardTime),
                header = it.header,
                acting = readMessageCardActingButtons(it.card, key.actions, key.forUserId),
            )
        }
    messageCardCache.update { current ->
        LinkedHashMap(current).apply {
            put(key, read)
            while (size > MESSAGE_CARD_CACHE_SIZE) remove(keys.first())
        }
    }
    return read
}

/** `updatedAt` as the server writes it (`Date.toISOString()`); the web draws "Updated" only for a time `Date.parse` reads. */
private fun isMessageCardTime(value: String): Boolean = isStrictRfc3339Utc(value)

/** A stored card as [parseRaftMessageCard] reads it: the element tree and the optional header (v2.0). */
data class RaftParsedMessageCard(
    val card: RaftAppLinkCardV2,
    val header: RaftMessageCardHeader? = null,
)

/**
 * Reads a stored card as `messageCardSchema` does: strict `{ version: 1, header?, root, elements }`,
 * the link card v2 elements (Stack, Text, Badge, RelativeTime, Duration, Button) with their bounds
 * (16 elements, depth 4, one tree, 12 children a Stack, at most 4 Buttons), every Button a
 * `navigate` to a public HTTPS URL (any host: a card is not tied to a link's origin) with an
 * optional `open` (`tab` or `beside`, botiverse/slock #14133; read, never acted on), and the v2.0
 * parts: the header ([readMessageCardHeader]; no element, so not one of the 16) and the
 * `Markdown`, `Fields` and `Divider` elements ([readMessageCardOwnElement]). An element type or a
 * key this client doesn't know refuses the whole card (the fallback text is drawn). Like the link
 * card reader, a Badge tone it doesn't know reads neutral (every badge is drawn neutral).
 *
 * Two of the Server's checks are the Server's alone: the 8 KiB size (of the JSON the sender sent;
 * a stored card carries its defaults too) and the Markdown subset, which a client enforces as it
 * draws instead ([build.raft.app.markdown.raftCardMarkdownViolations]: a node outside it is drawn
 * as its source text, RFC 082 §4.8), so a stored card never depends on the Server having been right.
 */
fun parseRaftMessageCard(element: JsonElement): RaftParsedMessageCard? {
    val obj = element as? JsonObject ?: return null
    if (obj.keys - MessageCardHeaderKey != setOf("version", "root", "elements")) return null
    if (obj["version"].cardSafeInteger() != MESSAGE_CARD_VERSION.toLong()) return null
    val header = if (MessageCardHeaderKey in obj) readMessageCardHeader(obj[MessageCardHeaderKey]) ?: return null else null
    val root = obj["root"].cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null
    val rawElements = obj["elements"] as? JsonObject ?: return null
    val elements = LinkedHashMap<String, CardElement>()
    for ((id, raw) in rawElements) {
        if (!CARD_ELEMENT_ID.matches(id)) return null
        elements[id] =
            when ((raw as? JsonObject)?.get("type").cardString()) {
                "Markdown", "Fields", "Divider" -> readMessageCardOwnElement(raw as JsonObject)
                else -> readLinkCardV2Element(raw, buttonOpen = true, readAction = ::readMessageCardButtonAction)
            } ?: return null
    }
    if (checkCardBoundedTree(root, elements, RAFT_APP_LINK_CARD_MAX_ELEMENTS, RAFT_APP_LINK_CARD_MAX_DEPTH) != null) return null
    if (elements.values.count { it is RaftLinkCardV2Element.Button } > MESSAGE_CARD_MAX_BUTTONS) return null
    return RaftParsedMessageCard(RaftAppLinkCardV2(root, elements), header)
}

private const val MessageCardHeaderKey = "header"

/** Tags in a card's header at most (`MESSAGE_CARD_LIMITS.maxHeaderTags`). */
internal const val MESSAGE_CARD_MAX_HEADER_TAGS = 3

/** Rows of one Fields element at most (`MESSAGE_CARD_LIMITS.maxFields`). */
internal const val MESSAGE_CARD_MAX_FIELDS = 8

/** A Markdown element's content at most, in code points (`MESSAGE_CARD_LIMITS.markdownMaxCodePoints`). */
internal const val MESSAGE_CARD_MARKDOWN_MAX_CODE_POINTS = 2000

/**
 * A card's header (RFC 082 §4.8, `messageCardHeaderSchema`): a [title] (1-100 code points), a
 * one-line [subtitle] (1-100) and at most three [tags], all plain text. Not an element. The header
 * itself has no colour: only its tags carry a tone.
 */
data class RaftMessageCardHeader(
    val title: String,
    val subtitle: String? = null,
    val tags: List<RaftMessageCardTag> = emptyList(),
)

/** One header tag: [text] 1-24 code points in a status [tone]. */
data class RaftMessageCardTag(val text: String, val tone: RaftMessageCardTagTone)

/** `MESSAGE_CARD_HEADER_TAG_TONES`: the design system's status tones, nothing else (no pink, no yellow). */
enum class RaftMessageCardTagTone(val wire: String) {
    Neutral("neutral"),
    Info("info"),
    Success("success"),
    Warning("warning"),
    Danger("danger"),
    ;

    companion object {
        fun fromWire(value: String?): RaftMessageCardTagTone? = entries.firstOrNull { it.wire == value }
    }
}

/** The message card's own elements (v2.0): link cards and action cards don't have them. */
sealed interface RaftMessageCardElement : CardElement {
    /** `Markdown`: [content] (1-2000 code points) in the card Markdown subset, drawn restricted to it. */
    data class Markdown(val content: String) : RaftMessageCardElement

    /** `Fields`: 1-8 label and value rows, plain text. */
    data class Fields(val items: List<RaftMessageCardField>) : RaftMessageCardElement

    /** `Divider`: a separator. */
    data object Divider : RaftMessageCardElement
}

/** One Fields row: [label] 1-40 and [value] 1-200 code points; [mono] for machine-written values. */
data class RaftMessageCardField(val label: String, val value: String, val mono: Boolean = false)

/** Every separator a universal-newline reader splits on (`CARD_LINE_BREAK`): a subtitle is one line. */
private val MESSAGE_CARD_LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]")

/** `messageCardHeaderSchema` (strict): `{ title, subtitle?, tags?: [{ text, tone }] }`; no `tone` of its own. */
private fun readMessageCardHeader(raw: JsonElement?): RaftMessageCardHeader? {
    val obj = raw as? JsonObject ?: return null
    if (!obj.hasOnlyKeys(setOf("title", "subtitle", "tags"))) return null
    val title = obj["title"].cardString()?.takeIf { isCardText(it, 1, 100) } ?: return null
    val subtitle =
        if ("subtitle" in obj) {
            obj["subtitle"].cardString()?.takeIf { isCardText(it, 1, 100) && !MESSAGE_CARD_LINE_BREAK.containsMatchIn(it) } ?: return null
        } else {
            null
        }
    val tags =
        if ("tags" in obj) {
            val array = obj["tags"] as? JsonArray ?: return null
            if (array.size > MESSAGE_CARD_MAX_HEADER_TAGS) return null
            array.map { item ->
                val tag = item as? JsonObject ?: return null
                if (tag.keys != setOf("text", "tone")) return null
                val text = tag["text"].cardString()?.takeIf { isCardText(it, 1, 24) } ?: return null
                val tone = RaftMessageCardTagTone.fromWire(tag["tone"].cardString()) ?: return null
                RaftMessageCardTag(text, tone)
            }
        } else {
            emptyList()
        }
    return RaftMessageCardHeader(title, subtitle, tags)
}

/** `Markdown`, `Fields` or `Divider` (strict, as `messageCardElementSchema`), or null. */
private fun readMessageCardOwnElement(obj: JsonObject): CardElement? =
    when (obj["type"].cardString()) {
        "Markdown" -> {
            val props = obj.elementProps(setOf("content")) ?: return null
            val content = props["content"].cardString()?.takeIf { isCardText(it, 1, MESSAGE_CARD_MARKDOWN_MAX_CODE_POINTS) } ?: return null
            RaftMessageCardElement.Markdown(content)
        }
        "Fields" -> {
            val props = obj.elementProps(setOf("items")) ?: return null
            val items = props["items"] as? JsonArray ?: return null
            if (items.size !in 1..MESSAGE_CARD_MAX_FIELDS) return null
            RaftMessageCardElement.Fields(
                items.map { raw ->
                    val item = raw as? JsonObject ?: return null
                    if (!item.hasOnlyKeys(setOf("label", "value", "mono"))) return null
                    val label = item["label"].cardString()?.takeIf { isCardText(it, 1, 40) } ?: return null
                    val value = item["value"].cardString()?.takeIf { isCardText(it, 1, 200) } ?: return null
                    val mono = if ("mono" in item) item["mono"].cardBoolean() ?: return null else false
                    RaftMessageCardField(label, value, mono)
                },
            )
        }
        "Divider" -> {
            val props = obj.elementProps(emptySet()) ?: return null
            if (props.isNotEmpty()) return null
            RaftMessageCardElement.Divider
        }
        else -> null
    }

/** `messageCardNavigateAction`: `{ kind: "navigate", url }`, a public HTTPS URL. Nothing else: a card is display only. */
private fun readMessageCardButtonAction(raw: JsonElement?): RaftLinkCardButtonAction? {
    val obj = raw as? JsonObject ?: return null
    if (obj.keys != setOf("kind", "url") || obj["kind"].cardString() != "navigate") return null
    val url = obj["url"].cardString()?.takeIf { isCardPublicUrl(it) } ?: return null
    return RaftLinkCardButtonAction.Navigate(url)
}

/** The card's Buttons in tree order (web `messageCardButtons`), with their element ids. */
internal fun RaftAppLinkCardV2.messageCardButtons(): List<Pair<String, RaftLinkCardV2Element.Button>> {
    val buttons = mutableListOf<Pair<String, RaftLinkCardV2Element.Button>>()
    fun visit(id: String, depth: Int) {
        if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
        when (val node = elements[id]) {
            is RaftLinkCardV2Element.Button -> buttons += id to node
            is CardStack -> node.childIds.forEach { visit(it, depth + 1) }
            else -> Unit
        }
    }
    visit(root, 0)
    return buttons
}
