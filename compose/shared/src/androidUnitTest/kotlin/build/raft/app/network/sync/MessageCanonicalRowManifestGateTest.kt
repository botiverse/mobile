package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

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
            .toSet()

    private fun canonicalRowFieldNames(): Set<String> =
        CanonicalMessageRow::class.java.declaredFields
            .asSequence()
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .filterNot { it == "Companion" || it == "serialVersionUID" }
            .toSet()

    @Test
    fun canonicalRowFieldsEqualManifestCanonicalSet() {
        val row = canonicalRowFieldNames()
        val canonical = canonicalFieldNames()
        assertEquals(
            canonical, row,
            "CanonicalMessageRow field set must EQUAL the manifest canonical set (21). Missing from row " +
                "(manifest-has/row-no): ${canonical - row}. Extra on row (row-has/manifest-no): ${row - canonical}.",
        )
    }
}
