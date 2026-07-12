/*
 * Thread-replies latest-N read-model — cross-end conformance + offline copy-integrity (RFC 043 §B.10).
 *
 * The canonical vector lives in the WEB repo (botiverse/slock packages/shared/src/testVectors/
 * threadRepliesReadModel.vectors.json). Mobile is a SEPARATE repo, so this is a SYNCED copy. Both ends fold
 * THIS SAME vector and must agree step-by-step — the KMP half of 赵's cross-end equal-verdict gate (#731).
 *
 * Two protections, kept honestly distinct:
 *  - `syncedVectorMatchesPinnedHash` is an OFFLINE integrity check: the synced copy still matches the hash it
 *    was pinned to, catching a LOCAL accidental edit. It is NOT the cross-repo gate — two independent
 *    hardcoded hashes can both stay green on DIFFERENT bytes (AD2's finding #2). The real mechanical cross-
 *    repo gate is a CI step that FETCHES the canonical bytes from slock staging and compares; it is added
 *    once web lands staging (tracked separately). A second hardcoded hash would be a gate with the very
 *    blind spot it exists to catch, so this test does not claim to be one.
 *
 * Scope is create-only: reply delete/edit are unsupported server-side in v1 (no deletedAt/revision column,
 * no message:deleted event), so the vector carries no tombstone/edit cases — a vector over an unwritten
 * server contract would only pin our own guesses.
 */
package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadRepliesPreviewConformanceTest {

    private val pinnedHash = "e0af07aa112aff7f8dc653ed1ce5f23211fa6884c780a2e92fff7452b959e98a"

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }.readBytes()

    private fun replyOf(messageId: String, seq: Long): ReplyPreview =
        ReplyPreview(messageId, seq, "", "", "", "", null, "")

    /** Offline copy-integrity: the synced mobile copy still matches its pinned hash (NOT the cross-repo gate). */
    @Test
    fun syncedVectorMatchesPinnedHash() {
        val bytes = resourceBytes("/sync/threadRepliesReadModel.vectors.json")
        val fileHash = resourceBytes("/sync/threadRepliesReadModel.vectors.sha256").decodeToString().trim()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        assertEquals(pinnedHash, actual, "synced vector drifted from its pinned hash — re-sync from botiverse/slock")
        assertEquals(fileHash, actual, "vector bytes disagree with the pinned .sha256 companion")
    }

    @Test
    fun kmpReducerMatchesTheCrossEndVectorStepByStep() {
        val vector = Json.parseToJsonElement(
            resourceBytes("/sync/threadRepliesReadModel.vectors.json").decodeToString(),
        ).jsonObject
        val cap = vector.getValue("cap").jsonPrimitive.int

        for (caseElement in vector.getValue("cases").jsonArray) {
            val case = caseElement.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val snapshot = case.getValue("snapshot").jsonObject

            // Snapshot replies are bare seqs (messageId `m-<seq>`).
            val initial = initialThreadRepliesReadModel(
                replies = snapshot.getValue("replies").jsonArray.map {
                    val seq = it.jsonPrimitive.long
                    replyOf("m-$seq", seq)
                },
                replyCount = snapshot.getValue("replyCount").jsonPrimitive.int,
                limit = cap,
            )

            // Each frame is `{seq, replyCount, [messageId]}` — it carries the AUTHORITATIVE replyCount.
            val frames = case.getValue("frames").jsonArray
            var model = initial
            var beforeLast = initial
            frames.forEachIndexed { i, frameElement ->
                if (i == frames.size - 1) beforeLast = model
                val frame = frameElement.jsonObject
                val seq = frame.getValue("seq").jsonPrimitive.long
                val messageId = frame["messageId"]?.jsonPrimitive?.content ?: "m-$seq"
                val frameReplyCount = frame.getValue("replyCount").jsonPrimitive.int
                model = model.applyReplyFrame(replyOf(messageId, seq), frameReplyCount, cap)
            }

            val expect = case.getValue("expect").jsonObject
            assertEquals(
                expect.getValue("replies").jsonArray.map { it.jsonPrimitive.long },
                model.replies.map { it.seq },
                "$name: window (by seq) diverged",
            )
            assertEquals(expect.getValue("replyCount").jsonPrimitive.int, model.replyCount, "$name: replyCount diverged")
            assertEquals(expect.getValue("snapshotSeq").jsonPrimitive.long, model.snapshotSeq, "$name: snapshotSeq diverged")

            // Gate 4 — a whole-sequence no-op returns the initial instance (render isolation).
            if (expect.getValue("unchanged").jsonPrimitive.boolean) {
                assertSame(initial, model, "$name: unchanged sequence must return the same instance")
            } else {
                assertNotSame(initial, model, "$name: changed sequence must produce a new instance")
            }
            // Gate 4 — the LAST frame alone is a no-op (e.g. an evicted-reply replay): same reference as the
            // model just before it. A value-only check would pass a rebuild-every-frame implementation.
            expect["lastFrameUnchanged"]?.let {
                if (it.jsonPrimitive.boolean) {
                    assertSame(beforeLast, model, "$name: last frame must be a reference-stable no-op")
                }
            }
        }
        assertTrue(vector.getValue("cases").jsonArray.size >= 10, "expected the full 10-case vector")
    }
}
