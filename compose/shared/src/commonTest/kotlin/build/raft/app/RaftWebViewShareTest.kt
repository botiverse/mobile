package build.raft.app

import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** slock #15772 / #proj-workspace:#346: `navigator.share` with files in the app's web container. */
@OptIn(ExperimentalEncodingApi::class)
class RaftWebViewShareTest {
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
    fun textOnlyShareNeedsNoSink() {
        val receiver = receiver()
        assertEquals(emptyList(), receiver.accept(start("s1")))
        val ready = assertIs<RaftWebViewShareSignal.Ready>(assertNotNull(receiver.accept(end("s1"))).single())
        assertTrue(ready.request.files.isEmpty())
        assertTrue(sink.files.isEmpty())
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
}
