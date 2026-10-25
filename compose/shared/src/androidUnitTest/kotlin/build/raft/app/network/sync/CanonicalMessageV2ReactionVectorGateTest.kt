package build.raft.app.network.sync

import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalMessageV2ReactionVectorGateTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
    private val vectorBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sync/canonical-message-v2-reaction.vector.json")).readBytes()
    }
    private val vector by lazy { Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject }
    private val cases by lazy { vector["cases"]!!.jsonArray.map { it.jsonObject } }

    @Test
    fun vendoredVectorIsTheReviewedImmutableWebArtifact() {
        assertEquals("legacy-reaction-roster-v1-to-normalized-message-v2", vector.string("contract"))
        assertEquals(CANONICAL_MESSAGE_V2_SCHEMA_VERSION, vector.int("schemaVersion"))
        assertEquals(4, cases.size)
        assertEquals(
            "905812d366f8a700753596c01c678262f0aa5b5eb158859e9b791fb3c3a7fa40",
            sha256(vectorBytes),
        )
    }

    @Test
    fun allNeutralCasesExecuteThroughTheRealKotlinNormalizer() {
        cases.forEach { case ->
            val input = case["input"]!!.jsonObject
            val scope = input["parentScopeKey"]!!.jsonObject
            val authority = when (input.string("source")) {
                "channel-room" -> ReactionIngressAuthority.SharedRoom
                "receiver-private" -> ReactionIngressAuthority.ReceiverPrivate(input.string("viewerUserId"))
                else -> error("Unknown vector source in ${case.string("name")}")
            }
            val reactions = input["reactions"]!!.jsonArray.map { element ->
                val reaction = element.jsonObject
                LegacyReactionRosterDto(
                    emoji = reaction.string("emoji"),
                    count = reaction.int("count"),
                    reactorIds = reaction.stringList("reactorIds"),
                    reactorNames = reaction.stringList("reactorNames"),
                )
            }
            val outcome = normalizeLegacyReactionRoster(
                context = ReactionNormalizationContext(
                    serverId = input.string("serverId"),
                    messageId = input.string("messageId"),
                    parentScopeKey = ReactionParentScopeKey(
                        serverId = scope.string("serverId"),
                        scopeKind = scope.string("scopeKind"),
                        scopeId = scope.string("scopeId"),
                    ),
                    authority = authority,
                ),
                reactions = reactions,
            )
            val actual = assertIs<ReactionNormalizationOutcome.Normalized>(outcome).value
            val expected = case["expected"]!!.jsonObject

            assertEquals(
                expected["sharedFact"],
                Json.parseToJsonElement(encodeReactionFactsCanonical(actual.sharedFact).trimEnd()),
                "sharedFact mismatch for ${case.string("name")}",
            )
            assertEquals(
                expected["viewerOverlay"],
                Json.parseToJsonElement(encodeReactionViewerOverlayCanonical(actual.viewerOverlay).trimEnd()),
                "viewerOverlay mismatch for ${case.string("name")}",
            )
            assertEquals(
                expected["readCacheSeed"],
                actual.readCacheSeed.toJson(),
                "readCacheSeed mismatch for ${case.string("name")}",
            )
            assertEquals(
                expected["violations"],
                Json.parseToJsonElement(json.encodeToString(actual.violations)),
                "violations mismatch for ${case.string("name")}",
            )
        }
    }

    @Test
    fun canonicalFactAndOverlayBytesMatchWebIncludingLfAndSha() {
        cases.forEach { case ->
            val actual = normalizeCase(case)
            val factBytes = encodeReactionFactsCanonical(actual.sharedFact)
            val overlayBytes = encodeReactionViewerOverlayCanonical(actual.viewerOverlay)

            assertEquals(case.string("canonicalFactJson"), factBytes, "fact bytes mismatch for ${case.string("name")}")
            assertEquals(case.string("canonicalFactSha256"), sha256(factBytes.encodeToByteArray()))
            assertEquals(case.string("viewerOverlayJson"), overlayBytes, "overlay bytes mismatch for ${case.string("name")}")
            assertEquals(case.string("viewerOverlaySha256"), sha256(overlayBytes.encodeToByteArray()))
        }
    }

    @Test
    fun retractionCasesNormalizeLikeWebIncludingBytesAndSha() {
        val retractionCases = vector["retractionCases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, retractionCases.size)
        retractionCases.forEach { case ->
            val name = case.string("name")
            val raw = case["legacyRow"]!!.jsonObject["deletedAt"]
            val legacy = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            val normalized = normalizeCanonicalRetraction(legacy)
            assertEquals(case["expectedFact"]!!.jsonObject["deletedAt"]?.jsonPrimitive?.content, normalized, name)
            // The V2 node's own encoding of the key: present only when retracted, never null.
            val nodeJson = Json { encodeDefaults = true; explicitNulls = true }.encodeToString(
                CanonicalMessageNodeV2.serializer(),
                CanonicalMessageNodeV2(
                    id = "m", seq = 1, senderType = "agent", senderId = "a", content = "", messageType = "chat",
                    createdAt = "2026-10-20T08:00:00.000Z", senderName = "A", deletedAt = normalized,
                ),
            )
            val retractionSlice = Json.parseToJsonElement(nodeJson).jsonObject
                .filterKeys { it == "deletedAt" }
                .let { JsonObject(it) }
            val bytes = Json.encodeToString(JsonObject.serializer(), retractionSlice) + "\n"
            assertEquals(case.string("retractionFactJson"), bytes, "retraction bytes mismatch for $name")
            assertEquals(case.string("retractionFactSha256"), sha256(bytes.encodeToByteArray()), name)
        }
    }

    private fun normalizeCase(case: JsonObject): NormalizedReactionIngress {
        val input = case["input"]!!.jsonObject
        val scope = input["parentScopeKey"]!!.jsonObject
        val authority = when (input.string("source")) {
            "channel-room" -> ReactionIngressAuthority.SharedRoom
            "receiver-private" -> ReactionIngressAuthority.ReceiverPrivate(input.string("viewerUserId"))
            else -> error("Unknown vector source in ${case.string("name")}")
        }
        return assertIs<ReactionNormalizationOutcome.Normalized>(
            normalizeLegacyReactionRoster(
                ReactionNormalizationContext(
                    serverId = input.string("serverId"),
                    messageId = input.string("messageId"),
                    parentScopeKey = ReactionParentScopeKey(
                        scope.string("serverId"),
                        scope.string("scopeId"),
                        scope.string("scopeKind"),
                    ),
                    authority = authority,
                ),
                input["reactions"]!!.jsonArray.map { element ->
                    element.jsonObject.let { reaction ->
                        LegacyReactionRosterDto(
                            reaction.string("emoji"),
                            reaction.int("count"),
                            reaction.stringList("reactorIds"),
                            reaction.stringList("reactorNames"),
                        )
                    }
                },
            ),
        ).value
    }

    private fun List<ReactionActorsReadCacheSeed>.toJson(): JsonArray = buildJsonArray {
        this@toJson.forEach { seed ->
            add(buildJsonObject {
                put("parentScopeKey", buildJsonObject {
                    put("serverId", seed.parentScopeKey.serverId)
                    put("scopeKind", seed.parentScopeKey.scopeKind)
                    put("scopeId", seed.parentScopeKey.scopeId)
                })
                put("messageId", seed.messageId)
                put("emoji", seed.emoji)
                put("actors", buildJsonArray {
                    seed.actors.forEach { actor ->
                        add(buildJsonObject {
                            put("id", actor.id)
                            put("displayName", actor.displayName)
                        })
                    }
                })
                put("completeness", seed.completeness)
            })
        }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.int(name: String): Int = string(name).toInt()
    private fun JsonObject.stringList(name: String): List<String> =
        getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
