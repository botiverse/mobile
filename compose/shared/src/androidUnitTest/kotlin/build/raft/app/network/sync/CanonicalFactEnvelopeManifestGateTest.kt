package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DoD ⑤ gate (甲) — MECHANICAL manifest→envelope reverse-closure for the T-wrapper canonical fact BLOB.
 *
 * The sibling [CanonicalFactCodecTest] round-trip proves a hand-built row survives; it CANNOT prove the
 * envelope covers the whole manifest (a canonical field added to the manifest but forgotten in the envelope
 * would just be absent from the hand-built row). This gate closes that: [CanonicalMessagePersistedV1]'s
 * field set (minus the envelope-meta `version`) must EQUAL the manifest canonical set, and each nested V1
 * DTO must EQUAL its manifest `nestedWireShapes` entry — the same reverse-closure G6 applies to the fold
 * row ([MessageCanonicalRowManifestGateTest]), pinned to the same reviewed manifest head.
 */
class CanonicalFactEnvelopeManifestGateTest {

    private val manifestBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sync/canonicalMessageManifest.v7.json")) {
            "canonicalMessageManifest.v7.json snapshot missing from androidUnitTest resources"
        }.readBytes()
    }

    private val manifest by lazy { Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }

    private fun manifestCanonicalFieldNames(): Set<String> =
        manifest["fields"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["class"]!!.jsonPrimitive.content in setOf("canonicalRequired", "optionalAggregate") }
            .map { it["name"]!!.jsonPrimitive.content }
            .toSet()

    /** The manifest canonical set the shadow-only V1 envelope carries (see [V1_DEFERRED_CANONICAL_FIELDS]). */
    private fun canonicalFieldNames(): Set<String> = manifestCanonicalFieldNames() - V1_DEFERRED_CANONICAL_FIELDS

    private fun declaredFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields.asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .filterNot { it == "Companion" || it == "serialVersionUID" }
            .toSet()

    /**
     * THE gate comparison — EXACT bidirectional equality; null = closed, else the divergence message.
     * Both the real gates and the RED specimen route through this one function, so a weakening (e.g. to a
     * subset check that ignores extra fields) is caught by the specimen, not just by hand-set arithmetic.
     */
    private fun closureError(label: String, actual: Set<String>, expected: Set<String>): String? {
        val missing = expected - actual
        val extra = actual - expected
        return if (missing.isEmpty() && extra.isEmpty()) null
        else "$label closure failed. Missing (expected-has/actual-no): $missing. Extra (actual-has/expected-no): $extra."
    }

    @Test
    fun manifestSnapshotShaMatchesG6PinnedHead() {
        // Same reviewed manifest head as G6 (slock 704d69f4, manifest v7); if it drifts, re-review both gates.
        val sha = MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) }
        assertEquals("18e654cc96a0be4d274494403a4ff65ff59665c44e5a1d649efb5f768557ed09", sha)
    }

    @Test
    fun gateRedSpecimen_exercisesTheActualClosureCheck() {
        // Mandatory RED specimen (赵/AD2): drive the SAME [closureError] the gates use with tampered inputs,
        // so a future weakening of that function (e.g. subset instead of exact equality) is caught HERE —
        // NOT via bare Set arithmetic, which would stay green even if the gate itself were made vacuous.
        val canonical = canonicalFieldNames()
        // manifest-load guard: an empty set (resource missing / parse error) would false-green the gate.
        assertEquals(21, manifestCanonicalFieldNames().size, "manifest v7 canonical set must be the pinned 21 (10 required + 11 aggregate)")
        assertEquals(V1_DEFERRED_CANONICAL_FIELDS, V1_DEFERRED_CANONICAL_FIELDS intersect manifestCanonicalFieldNames(), "every deferred field must be a manifest canonical field")
        assertEquals(20, canonical.size, "the V1 envelope closes over 20 (21 minus the deferred externalAuthor)")
        assertNull(closureError("positive-control", canonical, canonical), "identical sets must be closed")
        assertNotNull(closureError("missing", canonical - canonical.first(), canonical), "a missing field must fail closure")
        assertNotNull(closureError("extra", canonical + "phantomCanonicalField", canonical), "an extra field must fail closure")
    }

    @Test
    fun envelopeFieldsEqualManifestCanonicalSet() {
        val envelope = declaredFieldNames(CanonicalMessagePersistedV1::class.java) - "version" // version = codec meta
        assertNull(closureError("CanonicalMessagePersistedV1 (minus version) vs manifest canonical(21 - deferred)", envelope, canonicalFieldNames()))
    }

    @Test
    fun nestedV1DtoFieldsEqualManifestNestedWireShapes() {
        val nested = manifest["nestedWireShapes"]!!.jsonObject
        val dtoToShape = mapOf(
            CanonicalAttachmentV1::class.java to "attachment",
            CanonicalCommentRefV1::class.java to "commentRef",
            CanonicalCommentHostSourceV1::class.java to "commentRefHostSource",
            CanonicalConversationContextV1::class.java to "conversationContext",
            CanonicalMentionV1::class.java to "mention",
            CanonicalReactionV1::class.java to "reaction",
        )
        dtoToShape.forEach { (dto, shapeKey) ->
            val manifestFields = nested[shapeKey]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertNull(closureError("$shapeKey V1 DTO vs manifest nestedWireShapes.$shapeKey", declaredFieldNames(dto), manifestFields))
        }
    }
}
