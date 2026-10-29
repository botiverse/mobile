package build.raft.app.data

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every case of the vendored shared action-card.v1 vector (botiverse/slock 05e38737, #11937 schemaVersion 7
 * `packages/shared/src/actionCard.v1.vector.json`, pinned by scripts/check_action_card_v1_vector.sh)
 * through the Kotlin read parse, strip, Msg rendering and client form checks. Compared as JSON
 * values: key order is irrelevant, an absent key differs from a null one. Emit-mode verdicts are
 * the server's and are not run here.
 */
class ActionCardSpecVectorTest {
    private val vectorBytes by lazy { resource("/actioncard/actionCard.v1.vector.json") }
    private val vector by lazy { Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject }
    private val capabilitySets by lazy {
        vector.getValue("capabilitySets").jsonObject.mapValues { (_, set) -> set.jsonArray.map { it.jsonPrimitive.content } }
    }

    @Test
    fun vendoredVectorIsThePinnedSharedArtifact() {
        val digest = sha256(vectorBytes)
        assertEquals("df99645e2c3612d83aa4615bdbb345cc1d8f04f82da16affdf6e74389710a0e8", digest)
        assertEquals(digest, resource("/actioncard/actionCard.v1.vector.sha256").decodeToString().substringBefore(' ').trim())
        assertEquals(ACTION_CARD_SPEC_CATALOG, vector.string("contract"))
        assertEquals("7", vector.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(listOf(228, 14, 10), listOf("cards", "formValues", "messages").map { vector.getValue(it).jsonArray.size })
    }

    @Test
    fun capabilitiesMatchTheSharedListAndTheAppDeclaresOnlyKnownOnes() {
        assertEquals(ACTION_CARD_SPEC_CAPABILITIES, capabilitySets.getValue("all"))
        assertTrue(ACTION_CARD_SPEC_CAPABILITIES.containsAll(RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertTrue("card.v1" in RAFT_APP_ACTION_CARD_CAPABILITIES)
    }

    @Test
    fun everyCardCaseReadsAndStripsAsTheSharedParse() {
        val failures = mutableListOf<String>()
        var accepted = 0
        var rejected = 0
        var strips = 0
        for (case in vector.getValue("cards").jsonArray.map { it.jsonObject }) {
            val name = case.string("name")
            val (card, issue) = actionCardSpecIssue(case.getValue("card"))
            when (case.string("read")) {
                "accept" -> {
                    if (card == null) {
                        failures += "$name: refused ($issue), the shared read accepts it"
                        continue
                    }
                    case["parsed"]?.let { parsed ->
                        if (card.toJsonObject() != parsed) failures += "$name: parsed ${card.toJsonObject()} != $parsed"
                    }
                    case["strip"]?.jsonObject?.forEach { (set, expected) ->
                        val stripped = stripActionCardSpec(card, capabilitySets.getValue(set))?.toJsonObject() ?: JsonNull
                        if (stripped != expected) failures += "$name: strip[$set] $stripped != $expected" else strips++
                    }
                    accepted++
                }
                "reject" -> if (card != null) failures += "$name: accepted, the shared read refuses it" else rejected++
                else -> failures += "$name: unknown verdict"
            }
        }
        assertEquals(emptyList(), failures)
        assertEquals(listOf(85, 143), listOf(accepted, rejected))
        assertEquals(vector.getValue("cards").jsonArray.sumOf { it.jsonObject["strip"]?.jsonObject?.size ?: 0 }, strips)
    }

    @Test
    fun everyFormValueCaseGivesTheSharedClientIssues() {
        val cases = vector.getValue("formValues").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val form = checkNotNull(parseActionCardSpecForm(case.getValue("form"))) { "${case.string("name")}: form refused" }
            assertEquals(
                case.getValue("client"),
                JsonArray(checkActionCardFormValuesJson(form, case.getValue("values")).map { it.toJsonObject() }),
                case.string("name"),
            )
        }
        assertEquals(14, cases.size)
    }

    @Test
    fun everyMessageCaseRendersTheSharedSegments() {
        val cases = vector.getValue("messages").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val msg = checkNotNull(parseActionCardSpecMsg(case.getValue("msg"))) { "${case.string("name")}: Msg refused" }
            assertEquals(
                case.getValue("segments"),
                JsonArray(renderActionCardMsg(msg, case.string("locale")).map { it.toJsonObject() }),
                case.string("name"),
            )
        }
        assertEquals(10, cases.size)
    }

    private fun resource(path: String): ByteArray = checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.readBytes()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
