package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        checkNotNull(javaClass.getResourceAsStream("/sync/canonicalMessageManifest.v4.json")) {
            "canonicalMessageManifest.v4.json snapshot missing from androidUnitTest resources"
        }.readBytes()
    }

    private val manifest by lazy { Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }

    private fun canonicalFieldNames(): Set<String> =
        manifest["fields"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["class"]!!.jsonPrimitive.content in setOf("canonicalRequired", "optionalAggregate") }
            .map { it["name"]!!.jsonPrimitive.content }
            .toSet()

    private fun declaredFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields.asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .filterNot { it == "Companion" || it == "serialVersionUID" }
            .toSet()

    @Test
    fun manifestSnapshotShaMatchesG6PinnedHead() {
        // Same reviewed manifest head as G6 (#4589 @252dfebf); if it drifts, re-review both gates.
        val sha = MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) }
        assertEquals("f974c41d75acb813d2dba3246a011819a9d290442091982ca70e7622e7381340", sha)
    }

    @Test
    fun gateIsNonVacuousExactEquality_redSpecimen() {
        // Mandatory RED specimen (赵): prove the reverse-closure gate can actually go red, and is not a
        // false-green when the manifest fails to load. (1) the canonical set is the pinned 19 — an empty
        // set (resource missing / parse error) would false-green an equality against an also-empty side.
        val canonical = canonicalFieldNames()
        assertEquals(19, canonical.size, "manifest canonical set must be the pinned 19 (10 required + 9 aggregate)")
        // (2) exact equality, not subset: a field MISSING from one side OR an EXTRA field both diverge —
        // so the gate cannot pass on a partial match (the drift (甲) exists to catch).
        assertNotEquals(canonical, canonical - canonical.first(), "a missing canonical field must diverge")
        assertNotEquals(canonical, canonical + "phantomCanonicalField", "an extra field must diverge")
    }

    @Test
    fun envelopeFieldsEqualManifestCanonicalSet() {
        val envelope = declaredFieldNames(CanonicalMessagePersistedV1::class.java) - "version" // version = codec meta
        val canonical = canonicalFieldNames()
        assertEquals(
            canonical, envelope,
            "CanonicalMessagePersistedV1 (minus version) must EQUAL the manifest canonical set (19). " +
                "Missing from envelope (manifest-has/envelope-no): ${canonical - envelope}. " +
                "Extra on envelope (envelope-has/manifest-no): ${envelope - canonical}.",
        )
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
            assertEquals(
                manifestFields, declaredFieldNames(dto),
                "$shapeKey V1 DTO field set must EQUAL manifest nestedWireShapes.$shapeKey.",
            )
        }
    }
}
