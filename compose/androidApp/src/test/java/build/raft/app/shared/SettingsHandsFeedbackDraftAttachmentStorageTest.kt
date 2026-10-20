package build.raft.app.shared

import java.io.File
import java.net.URI
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsHandsFeedbackDraftAttachmentStorageTest {
    @Test
    fun promotedGroupUsesActualBytesAndSurvivesStorageRecreationForSubmit() {
        val sandbox = Files.createTempDirectory("feedback-draft-storage").toFile()
        try {
            val source = File(sandbox, "editor-temp.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val root = File(sandbox, "durable")
            val request = source("edited", source)
            val first = storage(root).promoteGroup("account-a", "group-a", listOf(request), maxBytes = 10)

            assertEquals(5L, first.single().sizeBytes)
            assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(), file(first.single().fileRef).readBytes().toList())

            val recreated =
                SettingsHandsFeedbackDraftAttachmentStorage(root) {
                    error("A committed group must reopen without its editor-temp source")
                }
            val restored = recreated.promoteGroup("account-a", "group-a", listOf(request), maxBytes = 10)
            val submitFile = HandsFeedbackAttachmentPolicy.requireValidFile(file(restored.single().fileRef))

            assertEquals(first, restored)
            assertEquals(5L, submitFile.length())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun promotedEditorPngUsesRenderableUriAndCoherentFileIdentity() {
        val sandbox = Files.createTempDirectory("feedback-draft-storage-png").toFile()
        try {
            val pngBytes =
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4e,
                    0x47,
                    0x0d,
                    0x0a,
                    0x1a,
                    0x0a
                )
            val source = File(sandbox, "editor-output.png").apply { writeBytes(pngBytes) }
            val promoted =
                storage(File(sandbox, "durable"))
                    .promoteGroup(
                        accountId = "account-a",
                        groupId = "group-png",
                        sources =
                            listOf(
                                SettingsHandsFeedbackDraftAttachmentSource(
                                    id = "edited",
                                    fileName = "photo.jpg",
                                    mimeType = "image/png",
                                    fileRef = source.toURI().toString()
                                )
                            ),
                        maxBytes = 32
                    )
                    .single()

            assertEquals("photo.png", promoted.fileName)
            assertEquals("image/png", promoted.mimeType)
            assertEquals(promoted.fileRef, promoted.previewUri)
            assertTrue(promoted.fileRef.startsWith("file:///"))
            assertTrue(promoted.fileRef.endsWith(".png"))
            assertTrue(isRaftRenderableLocalImageSource(promoted.previewUri))
            assertFalse(isRaftRenderableLocalImageSource("file:/data/user/0/build.raft.mobile/photo.png"))
            assertEquals(pngBytes.toList(), file(promoted.fileRef).readBytes().toList())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun anyCopyFailureLeavesNoCommittedOrStagedGroup() {
        val sandbox = Files.createTempDirectory("feedback-draft-storage-failure").toFile()
        try {
            val small = File(sandbox, "small.png").apply { writeBytes(byteArrayOf(1, 2)) }
            val large = File(sandbox, "large.png").apply { writeBytes(ByteArray(11) { 7 }) }
            val root = File(sandbox, "durable")

            try {
                storage(root).promoteGroup(
                    accountId = "account-a",
                    groupId = "group-failure",
                    sources = listOf(source("small", small), source("large", large)),
                    maxBytes = 10
                )
                fail("Expected oversized group promotion to fail")
            } catch (_: FeedbackAttachmentTooLargeException) {
            }

            assertTrue(root.walkTopDown().filter(File::isFile).toList().isEmpty())
            assertFalse(root.walkTopDown().any { it.name == "group-failure" })
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun discardOnlyDeletesOwnedAccountFiles() {
        val sandbox = Files.createTempDirectory("feedback-draft-storage-discard").toFile()
        try {
            val source = File(sandbox, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val root = File(sandbox, "durable")
            val storage = storage(root)
            val promoted = storage.promoteGroup("account-a", "group-a", listOf(source("edited", source)), 10)

            storage.discard("account-b", listOf(promoted.single().fileRef, source.toURI().toString()))
            assertTrue(file(promoted.single().fileRef).isFile)
            assertTrue(source.isFile)

            storage.discard("account-a", listOf(promoted.single().fileRef, source.toURI().toString()))
            assertFalse(file(promoted.single().fileRef).exists())
            assertTrue(source.isFile)
        } finally {
            sandbox.deleteRecursively()
        }
    }

    private fun storage(root: File): SettingsHandsFeedbackDraftAttachmentStorage =
        SettingsHandsFeedbackDraftAttachmentStorage(root) { ref -> file(ref).inputStream() }

    private fun source(id: String, file: File): SettingsHandsFeedbackDraftAttachmentSource =
        SettingsHandsFeedbackDraftAttachmentSource(
            id = id,
            fileName = "$id.png",
            mimeType = "image/png",
            fileRef = file.toURI().toString()
        )

    private fun file(ref: String): File = File(URI(ref))
}
