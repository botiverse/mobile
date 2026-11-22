package build.raft.app.shared

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewTemporaryFileRetirementTest {
    @Test
    fun deletesOnlyRegularFilesBelowCanonicalCacheRoots() {
        val root = Files.createTempDirectory("file-preview-cache").toFile()
        val externalRoot = Files.createTempDirectory("file-preview-external-cache").toFile()
        val outside = Files.createTempFile("file-preview-outside", ".png").toFile()
        val inside = File(root, "picker/inside image.png").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("inside")
        }
        val externalInside = File(externalRoot, "camera.jpg").apply { writeText("external") }
        val directory = File(root, "not-a-file").apply { mkdirs() }

        val result =
            retireFilePreviewTemporaryFiles(
                cacheRoots = listOf(root, externalRoot),
                fileRefs =
                    listOf(
                        inside.toURI().toString(),
                        externalInside.absolutePath,
                        outside.toURI().toString(),
                        directory.toURI().toString(),
                        "content://provider/not-owned",
                        inside.toURI().toString()
                    )
            )

        assertEquals(5, result.requestedCount)
        assertEquals(2, result.admittedCount)
        assertEquals(2, result.deletedCount)
        assertFalse(inside.exists())
        assertFalse(externalInside.exists())
        assertTrue(outside.isFile)
        assertTrue(directory.isDirectory)
        outside.delete()
        directory.delete()
        root.delete()
        externalRoot.delete()
    }

    @Test
    fun rejectsSymlinkEscapeFromCacheRoot() {
        val root = Files.createTempDirectory("file-preview-cache-link").toFile()
        val outside = Files.createTempFile("file-preview-link-target", ".png").toFile()
        val link = File(root, "escape.png")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .getOrElse {
                outside.delete()
                root.delete()
                return
            }

        val result =
            retireFilePreviewTemporaryFiles(
                cacheRoots = listOf(root),
                fileRefs = listOf(link.toURI().toString())
            )

        assertEquals(1, result.requestedCount)
        assertEquals(0, result.admittedCount)
        assertEquals(0, result.deletedCount)
        assertTrue(outside.isFile)
        link.delete()
        outside.delete()
        root.delete()
    }
}
