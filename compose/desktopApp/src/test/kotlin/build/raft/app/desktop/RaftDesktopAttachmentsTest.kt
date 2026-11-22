package build.raft.app.desktop

import build.raft.app.network.AttachmentDownloadObserver
import build.raft.app.network.AttachmentDownloadResult
import build.raft.app.parseRaftJsonObjectOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The bridge side with a fake panel: native dialogs cannot run headless. */
class RaftDesktopAttachmentsTest {
    private val root = Files.createTempDirectory("raft-attach-test").toFile()

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private class FakeDialogs(
        var open: suspend (Boolean) -> List<File> = { emptyList() },
        var save: suspend (String) -> File? = { null },
    ) : RaftDesktopFileDialogs {
        val imagesOnlyCalls = mutableListOf<Boolean>()
        val savedNames = mutableListOf<String>()

        override suspend fun openFiles(imagesOnly: Boolean): List<File> = open(imagesOnly).also { imagesOnlyCalls += imagesOnly }

        override suspend fun saveFile(suggestedName: String): File? = save(suggestedName).also { savedNames += suggestedName }
    }

    private fun params(vararg pairs: Pair<String, Any>): JsonObject =
        JsonObject(
            pairs.associate { (k, v) ->
                k to
                    when (v) {
                        is Boolean -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
            }
        )

    private fun call(block: ((String?) -> Unit) -> Unit): JsonObject =
        runBlocking {
            val result = CompletableDeferred<String?>()
            block { if (!result.isCompleted) result.complete(it) }
            assertNotNull(parseRaftJsonObjectOrNull(assertNotNull(withTimeout(10_000) { result.await() })))
        }

    @Test fun `picked files come back in the picker envelope the composer parses`() {
        val photo = File(root, "photo.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val doc = File(root, "notes.pdf").apply { writeBytes(byteArrayOf(4, 5)) }
        val dialogs = FakeDialogs(open = { listOf(photo, doc) })
        val attachments = RaftDesktopAttachments(dialogs, CoroutineScope(Dispatchers.IO))
        val response = call { attachments.threadAttachmentUpload(params("channelId" to "c", "imageOnly" to false), it) }
        assertEquals(listOf(false), dialogs.imagesOnlyCalls)
        assertEquals(200, response["code"]!!.jsonPrimitive.int)
        val files = response["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("photo.webp", "notes.pdf"), files.map { it["fileName"]!!.jsonPrimitive.content })
        assertEquals(listOf("image/webp", "application/pdf"), files.map { it["mimeType"]!!.jsonPrimitive.content })
        assertEquals(listOf(photo.canonicalPath, doc.canonicalPath), files.map { it["fileRef"]!!.jsonPrimitive.content })
        assertEquals(listOf("3", "2"), files.map { it["sizeBytes"]!!.jsonPrimitive.content })
        assertTrue(files.all { it["source"]!!.jsonPrimitive.content == "desktop_picker" })
        // The same files inside bodyText (Android puts them in both places).
        val body = assertNotNull(parseRaftJsonObjectOrNull(response["bodyText"]!!.jsonPrimitive.content))
        assertEquals(2, body["files"]!!.jsonArray.size)
    }

    @Test fun `dropped files answer as a pick of the same files, unreadable and empty ones skipped`() {
        val photo = File(root, "photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val doc = File(root, "notes.pdf").apply { writeBytes(byteArrayOf(4, 5)) }
        val empty = File(root, "empty.txt").apply { createNewFile() }
        val dialogs = FakeDialogs()
        val attachments = RaftDesktopAttachments(dialogs, CoroutineScope(Dispatchers.IO))
        val refs = JsonArray(listOf(photo, doc, empty, File(root, "missing.bin")).map { JsonPrimitive(it.absolutePath) })
        val response = call { attachments.threadAttachmentDrop(JsonObject(mapOf("fileRefs" to refs)), it) }
        assertTrue(dialogs.imagesOnlyCalls.isEmpty(), "a drop must not open the panel")
        assertEquals(206, response["code"]!!.jsonPrimitive.int)
        assertEquals(2, response["skippedCount"]!!.jsonPrimitive.int)
        val files = response["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(photo.canonicalPath, doc.canonicalPath), files.map { it["fileRef"]!!.jsonPrimitive.content })
        assertEquals(listOf("image/png", "application/pdf"), files.map { it["mimeType"]!!.jsonPrimitive.content })
        assertTrue(files.all { it["source"]!!.jsonPrimitive.content == "desktop_picker" })
        val nothing = call { attachments.threadAttachmentDrop(JsonObject(emptyMap()), it) }
        assertEquals(204, nothing["code"]!!.jsonPrimitive.int)
    }

    @Test fun `save downloads to the chosen file and answers saved`() {
        val target = File(root, "report.pdf")
        var request: Triple<String, File, String>? = null
        val attachments =
            RaftDesktopAttachments(FakeDialogs(save = { target }), CoroutineScope(Dispatchers.IO)) { id, file, url, observer: AttachmentDownloadObserver ->
                request = Triple(id, file, url)
                file.writeText("pdf")
                observer.onComplete(AttachmentDownloadResult(id, "t", "complete", 3, 3, "application/pdf", null, null))
            }
        val response = call { attachments.saveAttachment(params("url" to "https://x/y", "fileName" to "report.pdf", "downloadId" to "d1"), it) }
        assertEquals(Triple("d1", target, "https://x/y"), request)
        assertEquals("saved", response["status"]!!.jsonPrimitive.content)
        assertEquals(target.toURI().toString(), response["uri"]!!.jsonPrimitive.content)
    }

    @Test fun `a share image outside the PNG cache is never deleted`() {
        val snapshots = File(root, "raft_snapshots").apply { mkdirs() }
        val elsewhere = File(root, "mine.png").apply { writeBytes(byteArrayOf(1)) }
        val attachments = RaftDesktopAttachments(FakeDialogs(save = { null }), CoroutineScope(Dispatchers.IO), snapshotsDir = { snapshots })
        call { attachments.saveThreadShareImage(params("path" to elsewhere.absolutePath), it) }
        assertTrue(elsewhere.exists())
    }

    @Test fun `save panel names drop paths and control characters`() {
        assertEquals("report.pdf", raftDesktopSaveName("a/b\\report.pdf"))
        assertEquals("attachment", raftDesktopSaveName("  "))
    }
}
