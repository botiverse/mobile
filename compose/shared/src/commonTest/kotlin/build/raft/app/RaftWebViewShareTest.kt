package build.raft.app

import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private fun msg(vararg pairs: Pair<String, Any>): String = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    private fun start(id: String, vararg files: Triple<String, String, Int>): String =
        msg(
            "s" to id, "k" to "share-start", "title" to "周报", "text" to "", "url" to "",
            "files" to JSONArray().apply {
                files.forEach { (name, type, size) -> put(JSONObject().put("name", name).put("type", type).put("size", size)) }
            },
        )

    @Test
    fun assemblesTwoFilesAndDeduplicatesNames() {
        val assembler = RaftWebViewShareAssembler()
        val a = "第一个".encodeToByteArray()
        val b = byteArrayOf(1, 2, 3)
        assertNull(assembler.accept(start("s1", Triple("周报.md", "text/markdown", a.size), Triple("周报.md", "text/markdown", b.size))))
        assertTrue(raftWebViewIsShareMessage(start("s1")))
        assertNull(assembler.accept(msg("s" to "s1", "k" to "share-chunk", "f" to 0, "i" to 0, "d" to Base64.encode(a))))
        assertNull(assembler.accept(msg("s" to "s1", "k" to "share-chunk", "f" to 1, "i" to 0, "d" to Base64.encode(b))))
        val ready = assertIs<RaftWebViewShareAssembly.Ready>(assembler.accept(msg("s" to "s1", "k" to "share-end")))
        assertEquals(listOf("周报.md", "周报 (2).md"), ready.request.files.map { it.name })
        assertEquals("第一个", ready.request.files[0].bytes.decodeToString())
        assertEquals("周报", ready.request.title)
    }

    @Test
    fun missingBytesOrOversizeFail() {
        val assembler = RaftWebViewShareAssembler(maxBytes = 10)
        assertIs<RaftWebViewShareAssembly.Failed>(assembler.accept(start("big", Triple("a.zip", "application/zip", 11))))
        assertNull(assembler.accept(start("s2", Triple("a.txt", "text/plain", 4))))
        assertNull(assembler.accept(msg("s" to "s2", "k" to "share-chunk", "f" to 0, "i" to 0, "d" to Base64.encode(ByteArray(2)))))
        val failed = assertIs<RaftWebViewShareAssembly.Failed>(assembler.accept(msg("s" to "s2", "k" to "share-end")))
        assertEquals("size_mismatch", failed.reason)
    }

    @Test
    fun textOnlyShareText() {
        assertEquals("hi\nhttps://x.y", raftWebViewShareText("t", "hi", "https://x.y"))
        assertEquals("t", raftWebViewShareText("t", "", ""))
    }
}
