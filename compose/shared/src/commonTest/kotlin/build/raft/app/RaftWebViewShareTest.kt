package build.raft.app

import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** slock #15772 / #proj-workspace:#346: `navigator.share` with files in the app's web container. */
@OptIn(ExperimentalEncodingApi::class)
class RaftWebViewShareTest {
    @Test
    fun shimAnswersCanShareAndPostsToTheOriginBoundPort() {
        val script = raftWebViewShareShimScript(maxBytes = 1234)
        assertTrue("n.canShare=canShare" in script)
        assertTrue("n.share=function(d){return new Promise" in script)
        assertTrue("window.$RaftWebViewDownloadPortName" in script)
        assertTrue("total<=1234" in script)
        // A WebView that already has the API keeps its own.
        assertTrue("typeof n.share==='function'&&typeof n.canShare==='function')return" in script)
    }

    @Test
    fun settleScriptResolvesOrRejectsById() {
        assertEquals(
            "(function(){var s=window.__raftShareSettle;if(s)s(\"s1_9\",null);})();",
            raftWebViewShareSettleScript("s1_9", null),
        )
        assertTrue("\"AbortError\"" in raftWebViewShareSettleScript("s1_9", "AbortError"))
    }

    @Test
    fun shimGuardsActivationInFlightAndTopFrame() {
        val script = raftWebViewShareShimScript()
        // Not in a subframe: the app only takes the port's messages from the main frame.
        assertTrue("try{if(window.top!==window)return;}catch(e){return;}" in script)
        assertTrue(script.indexOf("window.top!==window") < script.indexOf("n.canShare=canShare"))
        // One share at a time, and only on a user gesture, before the data is looked at (Web Share's order).
        val inFlight = script.indexOf("if(active!==null){rej(err('InvalidStateError'")
        val activation = script.indexOf("var ua=n.userActivation;if(ua&&ua.isActive===false){rej(err('NotAllowedError'")
        val data = script.indexOf("if(!canShare(d)){rej(new TypeError(")
        assertTrue(inFlight > 0 && activation > inFlight && data > activation)
        assertTrue("pending[id]=entry;active=id;" in script)
        assertTrue("if(active===id)active=null;" in script)
        // A chunk is read only after the app has written the one before.
        assertTrue("'__raftShareAck'" in script)
        assertTrue("entry.next=function(){ci++;off+=size;next();};" in script)
        assertTrue("f.length>$RaftWebViewShareMaxFiles" in script)
        assertEquals("(function(){var a=window.__raftShareAck;if(a)a(\"s1_9\");})();", raftWebViewShareAckScript("s1_9"))
    }

    private class FakeSink : RaftWebViewShareSink {
        val files = LinkedHashMap<String, List<Pair<String, ArrayList<Byte>>>>()
        val discarded = ArrayList<String>()
        var failWrites = false

        override fun open(id: String, fileNames: List<String>) {
            files[id] = fileNames.map { it to ArrayList<Byte>() }
        }

        override fun append(id: String, fileIndex: Int, bytes: ByteArray) {
            if (failWrites) error("disk full")
            files.getValue(id)[fileIndex].second.addAll(bytes.toList())
        }

        override fun discard(id: String) {
            discarded += id
            files.remove(id)
        }

        fun bytes(id: String, fileIndex: Int): ByteArray = files.getValue(id)[fileIndex].second.toByteArray()
    }

    private var now = 1_000L
    private val sink = FakeSink()

    private fun receiver(maxBytes: Long = RaftWebViewShareMaxBytes) =
        RaftWebViewShareReceiver(sink = sink, nowMillis = { now }, maxBytes = maxBytes)

    private fun msg(vararg pairs: Pair<String, Any>): String = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    private fun start(id: String, vararg files: Triple<String, String, Long>): String =
        msg(
            "s" to id, "k" to "share-start", "title" to "周报", "text" to "", "url" to "",
            "files" to JSONArray().apply {
                files.forEach { (name, type, size) -> put(JSONObject().put("name", name).put("type", type).put("size", size)) }
            },
        )

    private fun chunk(id: String, file: Int, index: Int, bytes: ByteArray): String =
        msg("s" to id, "k" to "share-chunk", "f" to file, "i" to index, "d" to Base64.encode(bytes))

    private fun end(id: String): String = msg("s" to id, "k" to "share-end")

    private fun List<RaftWebViewShareSignal>?.failure(): RaftWebViewShareSignal.Failed =
        assertIs<RaftWebViewShareSignal.Failed>(assertNotNull(this).single())

    @Test
    fun streamsTwoFilesToTheSinkAndDeduplicatesNames() {
        val receiver = receiver()
        val a = "第一个".encodeToByteArray()
        val b = byteArrayOf(1, 2, 3)
        assertEquals(
            emptyList(),
            receiver.accept(start("s1", Triple("周报.md", "text/markdown", a.size.toLong()), Triple("周报.md", "text/markdown", b.size.toLong()))),
        )
        assertEquals(listOf("周报.md", "周报 (2).md"), sink.files.getValue("s1").map { it.first })
        // Each chunk is written as it comes and acknowledged, so the page posts the next.
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("s1")), receiver.accept(chunk("s1", 0, 0, a.copyOfRange(0, 4))))
        assertEquals(4, sink.bytes("s1", 0).size)
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("s1")), receiver.accept(chunk("s1", 0, 1, a.copyOfRange(4, a.size))))
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("s1")), receiver.accept(chunk("s1", 1, 0, b)))
        val ready = assertIs<RaftWebViewShareSignal.Ready>(assertNotNull(receiver.accept(end("s1"))).single())
        assertEquals(listOf("周报.md", "周报 (2).md"), ready.request.files.map { it.name })
        assertEquals(listOf(a.size.toLong(), 3L), ready.request.files.map { it.size })
        assertEquals("第一个", sink.bytes("s1", 0).decodeToString())
        assertEquals(listOf<Byte>(1, 2, 3), sink.bytes("s1", 1).toList())
        assertEquals("周报", ready.request.title)
        assertEquals(0, receiver.pendingCount)
        assertTrue(sink.discarded.isEmpty())
    }

    @Test
    fun notAShareMessageIsLeftToTheDownloadReader() {
        val receiver = receiver()
        assertNull(receiver.accept(msg("t" to "d1", "k" to "chunk", "d" to "\"share-")))
        assertNull(receiver.accept("not json"))
    }

    @Test
    fun aShareMessageIsToldByItsLeadingKindAlone() {
        // As the shim serialises them: the kind first, the id last.
        assertTrue(raftWebViewIsShareMessage("{\"k\":\"share-start\",\"title\":\"\",\"files\":[],\"s\":\"s1_9\"}"))
        assertTrue(raftWebViewIsShareMessage("{\"k\":\"share-chunk\",\"f\":0,\"i\":0,\"d\":\"AAAA\",\"s\":\"s1_9\"}"))
        val script = raftWebViewShareShimScript()
        assertTrue("function post(o){o.s=id;" in script)
        listOf("share-start", "share-chunk", "share-end", "share-error").forEach { assertTrue("post({k:'$it'" in script) }
        // A download read is never one, whatever its file name or bytes hold.
        assertFalse(raftWebViewIsShareMessage("{\"k\":\"start\",\"name\":\"share-notes.md\",\"t\":\"d1\"}"))
        assertFalse(raftWebViewIsShareMessage("{\"name\":\"\\\"k\\\":\\\"share-x\",\"t\":\"d1\"}"))
        assertFalse(raftWebViewIsShareMessage("{\"t\":\"d1\",\"k\":\"share-start\"}"))
        assertFalse(raftWebViewIsShareMessage(""))
        // The data URL is split at its last comma: a Blob type may hold one, base64 never does.
        assertTrue("var c=s.lastIndexOf(',');" in script)
    }

    @Test
    fun textOnlyShareNeedsNoSink() {
        val receiver = receiver()
        assertEquals(emptyList(), receiver.accept(start("s1")))
        val ready = assertIs<RaftWebViewShareSignal.Ready>(assertNotNull(receiver.accept(end("s1"))).single())
        assertTrue(ready.request.files.isEmpty())
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun missingBytesOrOversizeFail() {
        val receiver = receiver(maxBytes = 10)
        assertEquals("too_large", receiver.accept(start("big", Triple("a.zip", "application/zip", 11L))).failure().reason)
        assertEquals(emptyList(), receiver.accept(start("s2", Triple("a.txt", "text/plain", 4L))))
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("s2")), receiver.accept(chunk("s2", 0, 0, ByteArray(2))))
        val failed = receiver.accept(end("s2")).failure()
        assertEquals(RaftWebViewShareSignal.Failed("s2", "DataError", "size_mismatch"), failed)
        assertEquals(listOf("s2"), sink.discarded.filter { it == "s2" })
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun sizesThatAreNegativeOrOverflowTheSumAreRefused() {
        val receiver = receiver()
        assertEquals("file_meta", receiver.accept(start("neg", Triple("a", "", -1L))).failure().reason)
        assertEquals("file_meta", receiver.accept(start("min", Triple("a", "", Long.MIN_VALUE))).failure().reason)
        assertEquals("too_large", receiver.accept(start("one", Triple("a", "", RaftWebViewShareMaxBytes + 1L))).failure().reason)
        // Two sizes whose sum wraps negative.
        assertEquals(
            "too_large",
            receiver.accept(start("wrap", Triple("a", "", Long.MAX_VALUE), Triple("b", "", Long.MAX_VALUE))).failure().reason,
        )
        assertEquals(
            "too_large",
            receiver.accept(start("sum", Triple("a", "", RaftWebViewShareMaxBytes), Triple("b", "", 1L))).failure().reason,
        )
        assertEquals("file_meta", receiver.accept(msg("s" to "bad", "k" to "share-start", "files" to JSONArray().put("x"))).failure().reason)
        assertEquals(0, receiver.pendingCount)
        assertTrue(sink.files.isEmpty())
        // Exactly the limit is fine.
        assertEquals(emptyList(), receiver.accept(start("max", Triple("a", "", RaftWebViewShareMaxBytes - 1L), Triple("b", "", 1L))))
    }

    @Test
    fun tooManyFilesAreRefused() {
        val files = Array(RaftWebViewShareMaxFiles + 1) { Triple("f$it.txt", "text/plain", 0L) }
        assertEquals("too_many_files", receiver().accept(start("s1", *files)).failure().reason)
        assertEquals(emptyList(), receiver().accept(start("s2", *files.copyOfRange(0, RaftWebViewShareMaxFiles))))
    }

    @Test
    fun chunksOutOfOrderFailTheShare() {
        fun started(id: String): RaftWebViewShareReceiver =
            receiver().also { assertEquals(emptyList(), it.accept(start(id, Triple("a", "", 4L), Triple("b", "", 0L), Triple("c", "", 2L)))) }
        // A chunk index skipped, and one repeated.
        assertEquals("chunk_order", started("o1").accept(chunk("o1", 0, 1, ByteArray(2))).failure().reason)
        started("o2").let {
            assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("o2")), it.accept(chunk("o2", 0, 0, ByteArray(2))))
            assertEquals("chunk_order", it.accept(chunk("o2", 0, 0, ByteArray(2))).failure().reason)
            // The share is gone: what the page still posts for it is ignored.
            assertEquals(emptyList(), it.accept(chunk("o2", 0, 1, ByteArray(2))))
            assertEquals(emptyList(), it.accept(end("o2")))
        }
        // The next file before this one is complete; a file that does not exist; going back.
        assertEquals("chunk_order", started("o3").accept(chunk("o3", 2, 0, ByteArray(2))).failure().reason)
        assertEquals("chunk_order", started("o4").accept(chunk("o4", 3, 0, ByteArray(1))).failure().reason)
        assertEquals("chunk_order", started("o5").accept(chunk("o5", -1, 0, ByteArray(1))).failure().reason)
        started("o6").let {
            assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("o6")), it.accept(chunk("o6", 0, 0, ByteArray(4))))
            // The empty middle file sends nothing.
            assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("o6")), it.accept(chunk("o6", 2, 0, ByteArray(2))))
            assertEquals("chunk_order", it.accept(chunk("o6", 0, 1, ByteArray(1))).failure().reason)
        }
        // More bytes than announced, bad base64, an empty chunk.
        assertEquals("size_mismatch", started("o7").accept(chunk("o7", 0, 0, ByteArray(5))).failure().reason)
        assertEquals("size_mismatch", started("o8").accept(chunk("o8", 0, 0, ByteArray(4096))).failure().reason)
        assertEquals(
            "chunk_decode",
            started("o9").accept(msg("s" to "o9", "k" to "share-chunk", "f" to 0, "i" to 0, "d" to "!!")).failure().reason,
        )
        assertEquals("chunk_decode", started("o10").accept(chunk("o10", 0, 0, ByteArray(0))).failure().reason)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun aFailedWriteSettlesTheShare() {
        val receiver = receiver()
        assertEquals(emptyList(), receiver.accept(start("s1", Triple("a.txt", "text/plain", 4L))))
        sink.failWrites = true
        assertEquals(RaftWebViewShareSignal.Failed("s1", "DataError", "stage_write"), receiver.accept(chunk("s1", 0, 0, ByteArray(4))).failure())
        assertEquals(0, receiver.pendingCount)
        assertTrue("s1" in sink.discarded)
    }

    @Test
    fun hostileNamesBecomeOneSafeComponent() {
        val used = HashSet<String>()
        assertEquals("x.txt", raftWebViewShareFileName("../x", "text/plain", used))
        assertEquals("evil.pdf", raftWebViewShareFileName("..\\..\\evil.pdf", "", used))
        assertEquals("a_b.txt", raftWebViewShareFileName("a:b.txt", "", used))
        // No name at all: `download`, with the type's extension; a second one is told apart.
        assertEquals("download.md", raftWebViewShareFileName("", "text/markdown;charset=utf-8", used))
        assertEquals("download (2).md", raftWebViewShareFileName("  ", "text/markdown", used))
        assertEquals("download (3).md", raftWebViewShareFileName("..", "text/markdown", used))
        // Names differing only in case share a file on some filesystems.
        assertEquals("X (2).txt", raftWebViewShareFileName("X.txt", "", used))
        val long = raftWebViewShareFileName("长".repeat(100) + ".pdf", "application/pdf", used)
        assertEquals(300 + 4, ("长".repeat(100) + ".pdf").encodeToByteArray().size)
        assertTrue(long.encodeToByteArray().size <= 200)
        assertTrue(long.endsWith(".pdf"))
        val again = raftWebViewShareFileName("长".repeat(100) + ".pdf", "application/pdf", used)
        assertTrue(again.encodeToByteArray().size <= 200)
        assertTrue(again.endsWith(" (2).pdf"))
        assertTrue(again != long)
        listOf(long, again).forEach { name -> assertTrue('/' !in name && '\\' !in name && !name.startsWith(".")) }
        // Through the receiver, every announced file gets such a name.
        val receiver = receiver()
        assertEquals(
            emptyList(),
            receiver.accept(start("s1", Triple("../../etc/passwd", "", 0L), Triple("", "", 0L), Triple("a".repeat(300), "", 0L))),
        )
        val names = sink.files.getValue("s1").map { it.first }
        assertEquals(listOf("passwd", "download"), names.take(2))
        assertEquals(200, names[2].length)
    }

    @Test
    fun anIdThatIsNotAShimIdIsRefused() {
        val receiver = receiver()
        listOf("-", "../x", "a b", "s1/..", "s".repeat(65)).forEach { id ->
            assertFalse(raftWebViewShareIdIsValid(id))
            assertEquals(RaftWebViewShareSignal.Failed(id, "DataError", "id"), receiver.accept(start(id, Triple("a", "", 1L))).failure())
        }
        assertFalse(raftWebViewShareIdIsValid(""))
        // Nothing to settle without an id.
        assertEquals(emptyList(), receiver.accept(start("", Triple("a", "", 1L))))
        assertEquals(emptyList(), receiver.accept(start("  ", Triple("a", "", 1L))))
        assertTrue(raftWebViewShareIdIsValid("s3_1700000000000"))
        assertEquals(0, receiver.pendingCount)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun evictedExpiredAndAbandonedSharesAreAborted() {
        val receiver = receiver()
        (1..RaftWebViewShareMaxPending).forEach { assertEquals(emptyList(), receiver.accept(start("s$it", Triple("a", "", 1L)))) }
        // One more: the oldest is given up, and its page told.
        assertEquals(RaftWebViewShareSignal.Failed("s1", "AbortError", "evicted"), receiver.accept(start("s5", Triple("a", "", 1L))).failure())
        assertTrue("s1" in sink.discarded && "s1" !in sink.files)
        assertEquals(RaftWebViewShareMaxPending, receiver.pendingCount)
        // A share that goes silent expires; one that keeps posting does not.
        now += RaftWebViewShareIdleTimeoutMs
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Ack("s5")), receiver.accept(chunk("s5", 0, 0, ByteArray(1))))
        now += 1L
        assertEquals(
            listOf("s2", "s3", "s4").map { RaftWebViewShareSignal.Failed(it, "AbortError", "expired") },
            receiver.expire(),
        )
        assertEquals(1, receiver.pendingCount)
        assertEquals(emptyList(), receiver.expire())
        // The page going away ends the rest.
        assertEquals(listOf<RaftWebViewShareSignal>(RaftWebViewShareSignal.Failed("s5", "AbortError", "closed")), receiver.abortAll())
        assertEquals(0, receiver.pendingCount)
        assertTrue(sink.files.isEmpty())
        // A second start of an id in flight fails it.
        assertEquals(emptyList(), receiver.accept(start("s9", Triple("a", "", 1L))))
        assertEquals("restart", receiver.accept(start("s9", Triple("a", "", 1L))).failure().reason)
        // The page's own read error ends its share without a signal: it has settled it itself.
        assertEquals(emptyList(), receiver.accept(start("s10", Triple("a", "", 1L))))
        assertEquals(emptyList(), receiver.accept(msg("s" to "s10", "k" to "share-error")))
        assertEquals(0, receiver.pendingCount)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun intentTypeIsTheCommonTypeOrAWildcard() {
        assertEquals("text/markdown", raftWebViewShareIntentType(listOf("text/markdown")))
        assertEquals("image/png", raftWebViewShareIntentType(listOf("image/png", "image/png")))
        assertEquals("image/*", raftWebViewShareIntentType(listOf("image/png", "image/jpeg")))
        assertEquals("*/*", raftWebViewShareIntentType(listOf("image/png", "application/pdf")))
        assertEquals("*/*", raftWebViewShareIntentType(emptyList()))
    }

    @Test
    fun stagingEntriesFollowTheAttachmentSharesLifetime() {
        val day = RaftWebViewShareStagingMaxAgeMs
        val at = 10L * day
        fun uuid(index: Int) = "00000000-0000-0000-0000-" + index.toString().padStart(12, '0')
        assertTrue(raftWebViewShareStagingIsEntryName(uuid(1)))
        assertFalse(raftWebViewShareStagingIsEntryName("web-share"))
        assertFalse(raftWebViewShareStagingIsEntryName(uuid(1).uppercase().replace('0', 'A')))
        // A fresh entry stays (a receiver may still be reading it); a day-old one goes; others are not ours to judge.
        assertEquals(
            listOf(uuid(2)),
            raftWebViewShareStaleStagingEntries(
                listOf(uuid(1) to at - 60_000L, uuid(2) to at - day - 1L, "not-a-share-entry" to 0L, uuid(3) to at + 5_000L),
                nowMillis = at,
            ),
        )
        // All but the newest ones go.
        assertEquals(
            listOf(uuid(3), uuid(4)),
            raftWebViewShareStaleStagingEntries((0 until 5).map { uuid(it) to at - it * 1_000L }, nowMillis = at, maxEntries = 3),
        )
        assertTrue(raftWebViewShareStaleStagingEntries((0 until RaftWebViewShareStagingMaxEntries).map { uuid(it) to at }, at).isEmpty())
    }

    @Test
    fun textOnlyShareText() {
        assertEquals("hi\nhttps://x.y", raftWebViewShareText("t", "hi", "https://x.y"))
        assertEquals("t", raftWebViewShareText("t", "", ""))
    }
}
