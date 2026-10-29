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

    @Test fun `the image button opens an images-only panel and a cancel answers 204`() {
        val dialogs = FakeDialogs()
        val attachments = RaftDesktopAttachments(dialogs, CoroutineScope(Dispatchers.IO))
        val response = call { attachments.threadAttachmentUpload(params("imageOnly" to true), it) }
        assertEquals(listOf(true), dialogs.imagesOnlyCalls)
        assertEquals(204, response["code"]!!.jsonPrimitive.int)
    }

    @Test fun `unreadable picks are skipped and counted`() {
        val good = File(root, "a.txt").apply { writeText("a") }
        val empty = File(root, "empty.txt").apply { createNewFile() }
        val attachments = RaftDesktopAttachments(FakeDialogs(open = { listOf(good, empty, root) }), CoroutineScope(Dispatchers.IO))
        val response = call { attachments.threadAttachmentUpload(params(), it) }
        assertEquals(206, response["code"]!!.jsonPrimitive.int)
        assertEquals(2, response["skippedCount"]!!.jsonPrimitive.int)
        assertEquals(1, response["files"]!!.jsonArray.size)
    }

    @Test fun `no camera on desktop and a second picker while one is open is busy`() {
        val gate = CompletableDeferred<Unit>()
        val attachments = RaftDesktopAttachments(FakeDialogs(open = { gate.await(); emptyList() }), CoroutineScope(Dispatchers.IO))
        val camera = call { attachments.threadAttachmentUpload(params("capture" to true, "imageOnly" to true), it) }
        assertEquals("attachment_picker_unavailable", camera["errorCode"]!!.jsonPrimitive.content)
        attachments.threadAttachmentUpload(params(), null)
        val busy = call { attachments.threadAttachmentUpload(params(), it) }
        assertEquals(409, busy["code"]!!.jsonPrimitive.int)
        gate.complete(Unit)
    }

    @Test fun `a dismissed save panel answers cancelled and downloads nothing`() {
        var started = false
        val dialogs = FakeDialogs(save = { null })
        val attachments = RaftDesktopAttachments(dialogs, CoroutineScope(Dispatchers.IO)) { _, _, _, _ -> started = true }
        val response = call { attachments.saveAttachment(params("url" to "https://x/y", "fileName" to "dir/report.pdf"), it) }
        assertEquals("cancelled", response["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("dir/report.pdf"), dialogs.savedNames)
        assertEquals(false, started)
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

    @Test fun `a cancel while the save panel is open drops its answer`() {
        val panel = CompletableDeferred<File?>()
        var started = false
        val attachments =
            RaftDesktopAttachments(FakeDialogs(save = { panel.await() }), CoroutineScope(Dispatchers.IO)) { _, _, _, _ -> started = true }
        val response =
            call { callback ->
                attachments.saveAttachment(params("url" to "https://x/y", "fileName" to "a.txt", "operationId" to "op1"), callback)
                attachments.cancelAttachmentExport(params("operationId" to "op1"))
                panel.complete(File(root, "a.txt"))
            }
        assertEquals("cancelled", response["status"]!!.jsonPrimitive.content)
        assertEquals(false, started)
    }

    @Test fun `download failures map to download_failed`() {
        val failed = parseRaftJsonObjectOrNull(
            RaftDesktopAttachments.saveResult(AttachmentDownloadResult("d", "t", "failed", 0, null, null, "http", "HTTP 403"), File(root, "x"))
        )!!
        assertEquals("download_failed", failed["status"]!!.jsonPrimitive.content)
        assertEquals("HTTP 403", failed["message"]!!.jsonPrimitive.content)
    }

    @Test fun `save panel names drop paths and control characters`() {
        assertEquals("report.pdf", raftDesktopSaveName("a/b\\report.pdf"))
        assertEquals("attachment", raftDesktopSaveName("  "))
    }
}
