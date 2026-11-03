package build.raft.app.data

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.json.Json
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

/**
 * A card message's card as this client draws it (web `ReadMessageCard`): the element tree (the
 * link card v2 tree, so the v2 renderer draws it), its [revision] (1 when posted) and when it was
 * posted or last replaced ([updatedAt], ISO UTC; null when it isn't a time this client reads).
 */
data class RaftMessageCard(
    val card: RaftAppLinkCardV2,
    val revision: Int,
    val updatedAt: String?,
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
    }
}

/** A projected message's metadata from its wire `actionMetadata`: Raft's action card, else a card message's card, else none. */
internal fun WireActionCardMetadata?.toMessageItemMetadata(): Map<String, String> {
    val meta = this ?: return emptyMap()
    return meta.toThreadActionCard()?.toMessageMetadata() ?: meta.messageCardMetadata()
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

private data class MessageCardCacheKey(val card: String, val revision: String, val updatedAt: String?)

private val messageCardCache = atomic(LinkedHashMap<MessageCardCacheKey, RaftMessageCard?>())

/**
 * The card to draw for a projected message's [metadata]; null: draw its text (web
 * `readMessageCard`). Reads the card with [parseRaftMessageCard] (cached by its JSON, a list
 * recomposes often).
 */
internal fun readMessageCardFromMetadata(metadata: Map<String, String>): RaftMessageCard? {
    val json = metadata[MessageCardMetadataCard] ?: return null
    val revisionText = metadata[MessageCardMetadataRevision] ?: return null
    val key = MessageCardCacheKey(json, revisionText, metadata[MessageCardMetadataUpdatedAt])
    val cache = messageCardCache.value
    if (cache.containsKey(key)) return cache[key]
    val revision = revisionText.toIntOrNull()?.takeIf { it >= 1 }
    val card = revision?.let { runCatching { Json.parseToJsonElement(json) }.getOrNull()?.let(::parseRaftMessageCard) }
    val read =
        card?.let {
            RaftMessageCard(
                card = it,
                revision = revision,
                updatedAt = key.updatedAt?.takeIf(::isMessageCardTime),
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

/**
 * What a message row draws as a card: its card when the Server's `message_cards` flag is on
 * ([cardsEnabled]) and the card reads; otherwise null, and the row draws the message's text (the
 * fallback), as a client without card support does.
 */
internal fun messageCardToRender(metadata: Map<String, String>, cardsEnabled: Boolean): RaftMessageCard? =
    if (cardsEnabled) readMessageCardFromMetadata(metadata) else null

/** `updatedAt` as the server writes it (`Date.toISOString()`); the web draws "Updated" only for a time `Date.parse` reads. */
private fun isMessageCardTime(value: String): Boolean = isStrictRfc3339Utc(value)

/**
 * Reads a stored card as `messageCardSchema` does: strict `{ version: 1, root, elements }`, the
 * link card v2 elements (Stack, Text, Badge, RelativeTime, Duration, Button) with their bounds (16
 * elements, depth 4, one tree, 12 children a Stack, at most 4 Buttons), every Button a `navigate`
 * to a public HTTPS URL (any host: a card is not tied to a link's origin). An element type this
 * client doesn't know refuses the whole card (the fallback text is drawn). Like the link card
 * reader, a Badge tone it doesn't know reads neutral (every badge is drawn neutral).
 */
fun parseRaftMessageCard(element: JsonElement): RaftAppLinkCardV2? {
    val obj = element as? JsonObject ?: return null
    if (obj.keys != setOf("version", "root", "elements")) return null
    if (obj["version"].cardSafeInteger() != MESSAGE_CARD_VERSION.toLong()) return null
    val root = obj["root"].cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null
    val rawElements = obj["elements"] as? JsonObject ?: return null
    val elements = LinkedHashMap<String, CardElement>()
    for ((id, raw) in rawElements) {
        if (!CARD_ELEMENT_ID.matches(id)) return null
        elements[id] = readLinkCardV2Element(raw, ::readMessageCardButtonAction) ?: return null
    }
    if (checkCardBoundedTree(root, elements, RAFT_APP_LINK_CARD_MAX_ELEMENTS, RAFT_APP_LINK_CARD_MAX_DEPTH) != null) return null
    if (elements.values.count { it is RaftLinkCardV2Element.Button } > MESSAGE_CARD_MAX_BUTTONS) return null
    return RaftAppLinkCardV2(root, elements)
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
