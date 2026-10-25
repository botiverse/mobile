package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #491 G6 (KMP side) — the mobile mirror of the server-side reverse-closure gate (slock #4589 /
 * producerRegistryAnchor #4593). It consumes the SAME generated `canonicalMessageManifest.json`
 * (imported here as a SHA-pinned test-resource snapshot) that the web/server tests consume, so both
 * ends validate against one source of truth — not two hand-written sets.
 *
 * Enforces payload-field-level closure on the KMP row: [CanonicalMessageRow]'s fields must equal the
 * manifest's canonical field set (CanonicalRequired ∪ OptionalAggregate), and NO excluded field
 * (task-domain / emittedUnconsumed / viewer-scoped / storage-sealed / client-only) may appear on the
 * canonical row. A future wire/manifest field that KMP fails to classify — or an excluded field that
 * silently leaks into the row — makes this test RED.
 *
 * The SHA pin ties this snapshot to the exact reviewed manifest head; when the manifest changes,
 * update the resource + this hash (and re-review), so the snapshot can never silently drift.
 */
class MessageCanonicalRowManifestGateTest {

    private val manifestBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sync/canonicalMessageManifest.v7.json")) {
            "canonicalMessageManifest.v7.json snapshot missing from androidUnitTest resources"
        }.readBytes()
    }

    private val manifest by lazy { Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject }

    private fun canonicalFieldNames(): Set<String> =
        manifest["fields"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["class"]!!.jsonPrimitive.content in setOf("canonicalRequired", "optionalAggregate") }
            .map { it["name"]!!.jsonPrimitive.content }
            .toSet() - V1_DEFERRED_CANONICAL_FIELDS

    /** All excluded field names across the 5 exclusion classes (nested `attachment.commentCount` -> its leaf). */
    private fun excludedFieldNames(): Set<String> {
        val out = mutableSetOf<String>()
        manifest["exclusions"]!!.jsonObject.values.forEach { cls ->
            cls.jsonObject["fields"]!!.jsonArray.forEach { out += it.jsonPrimitive.content.substringAfterLast('.') }
        }
        manifest["excludedClientOnly"]?.jsonArray?.forEach { out += it.jsonPrimitive.content }
        return out
    }

    private fun canonicalRowFieldNames(): Set<String> =
        CanonicalMessageRow::class.java.declaredFields
            .asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .filterNot { it == "Companion" || it == "serialVersionUID" }
            .toSet()

    @Test
    fun snapshotShaIsPinnedToReviewedManifestHead() {
        val sha = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "18e654cc96a0be4d274494403a4ff65ff59665c44e5a1d649efb5f768557ed09",
            sha,
            "canonicalMessageManifest snapshot drifted from the reviewed head (slock 704d69f4, manifest v7). " +
                "Re-review + update the pinned SHA before changing the resource.",
        )
    }

    @Test
    fun canonicalRowFieldsEqualManifestCanonicalSet() {
        val row = canonicalRowFieldNames()
        val canonical = canonicalFieldNames()
        assertEquals(
            canonical, row,
            "CanonicalMessageRow field set must EQUAL the manifest canonical set (21 minus deferred). Missing from row " +
                "(manifest-has/row-no): ${canonical - row}. Extra on row (row-has/manifest-no): ${row - canonical}.",
        )
    }

    @Test
    fun nestedWireDtoFieldsEqualManifestNestedWireShapes() {
        // Weld the nested drift class (LiBai §6.3): each wire DTO's field set must EQUAL the manifest
        // nestedWireShapes entry, so a future field on a nested DTO (or the manifest) can't silently
        // diverge the persisted canonical shape.
        val nested = manifest["nestedWireShapes"]!!.jsonObject
        assertEquals(
            nested.keys - V1_DEFERRED_CANONICAL_FIELDS,
            setOf("attachment", "commentRef", "commentRefHostSource", "conversationContext", "mention", "reaction"),
            "every non-deferred nested wire shape must have a V1 wire DTO below",
        )
        val dtoToShape = mapOf(
            CanonicalAttachmentWire::class.java to "attachment",
            CanonicalCommentRefWire::class.java to "commentRef",
            CanonicalCommentHostSourceWire::class.java to "commentRefHostSource",
            CanonicalConversationContextWire::class.java to "conversationContext",
            CanonicalMentionWire::class.java to "mention",
            CanonicalReactionWire::class.java to "reaction",
        )
        dtoToShape.forEach { (dto, shapeKey) ->
            val manifestFields = nested[shapeKey]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            val dtoFields = dto.declaredFields.asSequence()
                .filterNot { it.isSynthetic }.map { it.name }.filterNot { it.startsWith("$") }.toSet()
            assertEquals(
                manifestFields, dtoFields,
                "$shapeKey wire DTO field set must EQUAL manifest nestedWireShapes.$shapeKey. " +
                    "Missing (manifest-has/dto-no): ${manifestFields - dtoFields}. Extra (dto-has/manifest-no): ${dtoFields - manifestFields}.",
            )
        }
    }

    @Test
    fun noExcludedFieldLeaksOntoCanonicalRow() {
        val leaked = canonicalRowFieldNames() intersect excludedFieldNames()
        assertTrue(
            leaked.isEmpty(),
            "Excluded field(s) leaked onto CanonicalMessageRow: $leaked. task*/updatedAt/commentCount/" +
                "storage-sealed/client-only must NEVER be canonical row fields (they route to task-domain / " +
                "overlay / are sealed server-side).",
        )
    }
}

/**
 * Manifest v7 (slock 704d69f4) canonical fields the shadow-only V1 fold row / persisted envelope do not
 * carry yet. `externalAuthor` (manifest v5/v6) is consumed by the realtime/REST mappers
 * (`WireExternalAuthor`) but has no V1 fold DTO; V1 is shadow-only (`CANONICAL_FACT_SOLE_APPLY_ELIGIBLE`
 * = false), so it is deferred here by name — a NEW unclassified manifest field still turns both gates red.
 * `deletedAt` (v7) is NOT deferred: the row and envelope carry it.
 */
internal val V1_DEFERRED_CANONICAL_FIELDS: Set<String> = setOf("externalAuthor")
