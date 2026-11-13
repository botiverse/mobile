package build.raft.app.data

import build.raft.app.markdown.raftCardMarkdownCheck
import build.raft.app.markdown.raftCardMarkdownLineColumn
import build.raft.app.markdown.raftCardMarkdownRenderSource
import build.raft.app.markdown.raftCardMarkdownTagValid
import build.raft.app.markdown.threadInlineTagMatches
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs every case of the vendored shared message-card.v1 vector (RFC 082 v2.0 §4.8;
 * botiverse/slock #13224 + #13237, and #14723 (RFC 086 step 1: `acting_buttons_as_stored`) @
 * 3a036f809943760a0fee9b6250784a3964b2b720,
 * `packages/shared/src/messageCard.v1.vector.json`, pinned by
 * scripts/check_message_card_v1_vector.sh) through this App's card reader and its card Markdown
 * subset check. Compared as JSON values: key order is irrelevant.
 *
 * - `cards[]`: the reader is the client's strict schema. An accepted card reads (as sent and as
 *   stored) with its header and its Markdown, Fields and Divider as stored; a refused one makes
 *   the App show the whole fallback. Two refusals are the Server's alone: `size` (8 KiB of the
 *   JSON the sender sent) and `markdown` (a client draws a node outside the subset as its source
 *   instead of dropping the card, §4.8), so the App reads those cards, and draws the refused node
 *   as text. `agentText` and `fallback` are Raft's (the Server writes them); the App draws the
 *   stored message content as the fallback and generates neither.
 * - `markdown[]`: this App's own parse must find the same nodes outside the subset (type, source,
 *   line and column) and, for an accepted one, the same node types; what it draws (the render
 *   source) never holds a node outside the subset.
 */
class MessageCardVectorTest {
    private val vectorBytes by lazy { resource("/messagecard/messageCard.v1.vector.json") }
    private val vector by lazy { Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject }

    @Test
    fun vendoredVectorIsThePinnedSharedArtifact() {
        val digest = sha256(vectorBytes)
        assertEquals("19ec57295301cd1d9edde274776211c99e008020e446b0d560ea70424ee034c1", digest)
        assertEquals(digest, resource("/messagecard/messageCard.v1.vector.sha256").decodeToString().substringBefore(' ').trim())
        assertEquals("message-card.v1", vector.string("contract"))
        assertEquals("1", vector.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(listOf(49, 61), listOf("cards", "markdown").map { vector.getValue(it).jsonArray.size })
    }

    @Test
    fun everyCardCaseReadsAsTheClientSchemaReadsIt() {
        val failures = mutableListOf<String>()
        var accepted = 0
        var refused = 0
        for (case in vector.getValue("cards").jsonArray.map { it.jsonObject }) {
            val name = case.string("name")
            val sent = parseRaftMessageCard(case.getValue("card"))
            when (case.string("verdict")) {
                "accept" -> {
                    val stored = case.getValue("parsed").jsonObject
                    val read = parseRaftMessageCard(stored)
                    if (sent == null) failures += "$name: refused as sent"
                    if (read == null) {
                        failures += "$name: refused as stored"
                        continue
                    }
                    if (sent != null && sent != read) failures += "$name: reads differently as sent and as stored"
                    failures += newPartsDiffer(name, stored, read)
                    accepted++
                }
                "refuse" -> {
                    val problem = case.getValue("problem").jsonObject
                    when (problem.string("limit")) {
                        // The Server's alone: the App reads the card (and draws the refused node as its source).
                        "size" -> if (sent == null) failures += "$name: refused, the App reads cards of any size" else accepted++
                        "markdown" -> {
                            if (sent == null) {
                                failures += "$name: refused, the App draws Markdown outside the subset as its source"
                                continue
                            }
                            val id = problem.string("path").removePrefix("card.elements.").substringBefore('.')
                            val markdown = sent.card.elements[id] as? RaftMessageCardElement.Markdown
                            val found = markdown?.let { raftCardMarkdownCheck(it.content).violations.firstOrNull()?.node }
                            if (found != problem.string("node")) failures += "$name: first node outside the subset $found, the Server's ${problem.string("node")}"
                            accepted++
                        }
                        else -> if (sent != null) failures += "$name: read, the client schema refuses it (${problem.string("path")})" else refused++
                    }
                }
                else -> failures += "$name: unknown verdict"
            }
        }
        assertEquals(emptyList(), failures)
        assertEquals(listOf(23, 25), listOf(accepted, refused))
    }

    @Test
    fun everyMarkdownCaseFindsTheSharedNodes() {
        val failures = mutableListOf<String>()
        val cases = vector.getValue("markdown").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val name = case.string("name")
            val content = case.string("content")
            val check = raftCardMarkdownCheck(content)
            // An allowed client difference (Tenny): how it is drawn isn't compared, only the hard rule.
            val mayDiffer = case["display"]?.jsonPrimitive?.content == "may_differ"
            val found =
                check.violations.map { violation ->
                    val (line, column) = raftCardMarkdownLineColumn(content, violation.start)
                    "${violation.node} ${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(content.substring(violation.start, violation.end)))} $line:$column"
                }
            when (case.string("verdict")) {
                "accept" -> {
                    if (found.isNotEmpty()) failures += "$name: found $found, the Server accepts it"
                    val nodes = case.getValue("nodes").jsonArray.map { it.jsonPrimitive.content }
                    if (mayDiffer) {
                        failures += hardRule(name, content, nodes)
                    } else if (check.nodeTypes.sorted() != nodes) {
                        failures += "$name: nodes ${check.nodeTypes.sorted()}, the Server's $nodes"
                    }
                }
                "refuse" -> {
                    val expected =
                        case.getValue("found").jsonArray.map { it.jsonObject }.map { node ->
                            "${node.string("node")} ${node.getValue("source")} ${node.string("line")}:${node.string("column")}"
                        }
                    if (found != expected) failures += "$name: found $found, the Server's $expected"
                    val problem = case.getValue("problem").jsonObject.string("node")
                    if (check.violations.firstOrNull()?.node != problem) failures += "$name: first ${check.violations.firstOrNull()?.node}, the Server refuses $problem"
                }
                else -> failures += "$name: unknown verdict"
            }
            // What the App draws: every node outside the subset as text, so the drawn source holds none.
            val drawn = raftCardMarkdownRenderSource(content)
            val left = raftCardMarkdownCheck(drawn).violations.map { "${it.node} ${drawn.substring(it.start, it.end)}" }
            if (left.isNotEmpty()) failures += "$name: drawn source ${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(drawn))} still holds $left"
        }
        assertEquals(emptyList(), failures)
        assertEquals(61, cases.size)
    }

    /**
     * The hard rule for a `display: "may_differ"` case (an allowed client difference, Tenny: 4-space
     * indented lines, an undefined `[ref]`, entities, which this App draws as written): how it is
     * drawn isn't compared, but all of its text stays visible (every word of the source is in what
     * is drawn), nothing in it becomes a link the Server's tree has none of, nothing outside the
     * subset is drawn (checked for every case), and no mention chip: card Markdown accepts no tag
     * ([raftCardMarkdownTagValid]), `&#64;lagoon` included. Markdown never makes a button.
     */
    /**
     * RFC 086 (`acting_buttons_as_stored`, slock #14723): the stored card reads unchanged, every acting
     * button a disabled navigate Button with the reason Raft writes for older clients; with the App's
     * `actions` and `for` beside it (the server test's `ACTIONS`) this client draws them as acting buttons.
     */
    @Test
    fun actingButtonsAsStoredReadAsTheCardAndActFromTheirActions() {
        val case = vector.getValue("cards").jsonArray.map { it.jsonObject }.single { it.string("name") == "acting_buttons_as_stored" }
        val stored = case.getValue("parsed").jsonObject
        val read = checkNotNull(parseRaftMessageCard(stored))
        val buttons = read.card.messageCardButtons()
        assertEquals(listOf("approve", "deny"), buttons.map { it.first })
        for ((_, button) in buttons) {
            assertEquals(MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON, button.disabledReason)
            assertEquals(RaftLinkCardButtonAction.Navigate("https://vault.example.com/requests/42"), button.action)
        }
        val metadata =
            Json { ignoreUnknownKeys = true }.decodeFromString(
                WireActionCardMetadata.serializer(),
                """{"kind":"message-card","card":$stored,"revision":1,"updatedAt":"2026-11-13T09:00:00.000Z",""" +
                    """"actions":{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare"},"deny":{"kind":"execute","action_id":"deny","undoWindowMs":5000}},"for":"user-alice"}""",
            ).toMessageItemMetadata()
        val card = checkNotNull(readMessageCardFromMetadata(metadata))
        assertEquals(read.card, card.card)
        assertEquals(
            mapOf(
                "approve" to RaftLinkCardButtonAction.Execute("approve", confirm = true),
                "deny" to RaftLinkCardButtonAction.Execute("deny", confirm = false, undoWindowMs = 5000),
            ),
            card.acting?.actions,
        )
        assertEquals("user-alice", card.acting?.forUserId)
        // The notification of such a card leaves out the stored reason.
        assertEquals(
            "**Access request: deploy key**\n\nbuilder-bot asks to use the deploy key until 18:00 UTC.\n\nApprove · Deny",
            messageNotificationText(case.string("fallback"), metadata),
        )
    }

    private fun hardRule(name: String, content: String, nodes: List<String>): List<String> {
        val failures = mutableListOf<String>()
        val drawn = raftCardMarkdownCheck(raftCardMarkdownRenderSource(content))
        if ("link" !in nodes && "link" in drawn.nodeTypes) failures += "$name: may differ, but it draws a link the Server's tree has none of"
        val visible = drawn.content.replace("\\", "").replace("\u2060", "")
        for (word in Regex("[\\p{L}\\p{N}]+").findAll(content).map { it.value }) {
            if (word !in visible) failures += "$name: may differ, but \"$word\" is not drawn"
        }
        val chips = drawn.content.threadInlineTagMatches().filter { raftCardMarkdownTagValid(it.tag) }
        if (chips.isNotEmpty()) failures += "$name: may differ, but ${chips.map { it.tag.label }} would be chips"
        return failures
    }

    /** The header and the Markdown, Fields and Divider elements as read, against the stored card. */
    private fun newPartsDiffer(name: String, stored: JsonObject, read: RaftParsedMessageCard): List<String> {
        val failures = mutableListOf<String>()
        val header = read.header?.toJson()
        // `tags: []` and no tags read the same (no tags drawn).
        val storedHeader = stored["header"]?.jsonObject?.let { if (it["tags"]?.jsonArray?.isEmpty() == true) JsonObject(it - "tags") else it }
        if (header != storedHeader) failures += "$name: header $header != $storedHeader"
        val elements = stored.getValue("elements").jsonObject
        if (elements.keys != read.card.elements.keys) failures += "$name: elements ${read.card.elements.keys} != ${elements.keys}"
        for ((id, element) in elements) {
            val type = element.jsonObject.string("type")
            val model = read.card.elements[id]
            when (type) {
                "Markdown", "Fields", "Divider" -> {
                    val json = (model as? RaftMessageCardElement)?.toJson()
                    if (json != element) failures += "$name: $id read as $json, stored $element"
                }
                else -> if (model == null || model is RaftMessageCardElement) failures += "$name: $id ($type) read as $model"
            }
        }
        return failures
    }

    private fun RaftMessageCardHeader.toJson(): JsonObject =
        buildJsonObject {
            put("title", title)
            subtitle?.let { put("subtitle", it) }
            if (tags.isNotEmpty()) {
                put("tags", JsonArray(tags.map { tag -> buildJsonObject { put("text", tag.text); put("tone", tag.tone.wire) } }))
            }
        }

    private fun RaftMessageCardElement.toJson(): JsonElement =
        when (this) {
            is RaftMessageCardElement.Markdown -> buildJsonObject {
                put("type", "Markdown")
                put("props", buildJsonObject { put("content", content) })
            }
            is RaftMessageCardElement.Fields -> buildJsonObject {
                put("type", "Fields")
                put(
                    "props",
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(items.map { item -> buildJsonObject { put("label", item.label); put("value", item.value); put("mono", item.mono) } }),
                        )
                    },
                )
            }
            RaftMessageCardElement.Divider -> buildJsonObject {
                put("type", "Divider")
                put("props", buildJsonObject { })
            }
        }

    private fun resource(path: String): ByteArray = checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.readBytes()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
