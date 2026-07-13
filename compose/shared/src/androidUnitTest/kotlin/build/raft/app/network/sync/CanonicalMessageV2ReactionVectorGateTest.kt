package build.raft.app.network.sync

import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
            "9ac66a83515df1e7eada19b864d5b41c6038a1a559de18249479455af4e9086e",
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
                    parentScopeKey = SyncScopeKey(
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
                    parentScopeKey = SyncScopeKey(scope.string("serverId"), scope.string("scopeId"), scope.string("scopeKind")),
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
