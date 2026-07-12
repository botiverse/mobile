/*
 * Thread-replies latest-N read-model — cross-end conformance + cross-repo checksum (RFC 043 App B addendum).
 *
 * The canonical vector lives in the WEB repo (`botiverse/slock` packages/shared/src/testVectors/
 * threadRepliesReadModel.vectors.json). Mobile is a SEPARATE repo, so KMP cannot read that path — the vector
 * is SYNCED into this repo as a test resource and pinned by sha256 against the same authoritative hash the
 * web side asserts. Either copy drifting → both repos red (a copy that could silently drift would defeat the
 * equal-verdict gate itself). Then this same vector is fed to the KMP reducer and asserted logically
 * deep-equal to the expected snapshots — the KMP half of 赵's cross-end equal-verdict (#731 discipline).
 */
package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }.readBytes()

    /** Cross-repo checksum: the mobile-synced copy must match the same sha256 both repos pin. */
    @Test
    fun syncedVectorMatchesCanonicalWebChecksum() {
        val bytes = resourceBytes("/sync/threadRepliesReadModel.vectors.json")
        val expected = resourceBytes("/sync/threadRepliesReadModel.vectors.sha256").decodeToString().trim()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        assertEquals(
            "fa7d356c00f0344d9df33af244a4eec565fa462f109a9a473be6fbee14fdc9b2",
            actual,
            "synced vector drifted from the canonical web vector — re-sync from botiverse/slock",
        )
        assertEquals(expected, actual, "vector bytes disagree with the pinned .sha256 companion")
    }

    /** A vector reply/frame is a bare seq (messageId `m-<seq>`) or a `{seq, messageId}` override. */
    private fun replyOf(element: kotlinx.serialization.json.JsonElement): ReplyPreview =
        if (element is JsonPrimitive) {
            val seq = element.long
            ReplyPreview("m-$seq", seq, "", "", "", "", null, "")
        } else {
            val obj = element.jsonObject
            val seq = obj.getValue("seq").jsonPrimitive.long
            ReplyPreview(obj.getValue("messageId").jsonPrimitive.content, seq, "", "", "", "", null, "")
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

            val initial = initialThreadRepliesReadModel(
                replies = snapshot.getValue("replies").jsonArray.map(::replyOf),
                replyCount = snapshot.getValue("replyCount").jsonPrimitive.int,
                limit = cap,
            )
            var model = initial
            for (frame in case.getValue("frames").jsonArray) {
                model = model.applyReplyFrame(replyOf(frame), cap)
            }

            val expect = case.getValue("expect").jsonObject
            assertEquals(
                expect.getValue("replies").jsonArray.map { it.jsonPrimitive.long },
                model.replies.map { it.seq },
                "$name: window (by seq) diverged",
            )
            assertEquals(expect.getValue("replyCount").jsonPrimitive.int, model.replyCount, "$name: replyCount diverged")
            assertEquals(expect.getValue("snapshotSeq").jsonPrimitive.long, model.snapshotSeq, "$name: snapshotSeq diverged")

            // Gate 4 — reference-stable no-op: a fully-unchanged frame sequence returns the SAME instance.
            if (expect.getValue("unchanged").jsonPrimitive.boolean) {
                assertSame(initial, model, "$name: unchanged case must return the same instance (render isolation)")
            } else {
                assertNotSame(initial, model, "$name: changed case must produce a new instance")
            }
        }
        assertTrue(vector.getValue("cases").jsonArray.size >= 9, "expected the full 9-case vector")
    }
}
