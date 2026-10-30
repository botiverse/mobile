package build.raft.app

import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ThreadComposerPasteCompressionTest {
    private val pastedImage =
        PlatformFile(id = "p", fileName = "image.png", mimeType = "image/png", fileRef = "/cache/clipboard/pasted-1.png", source = ThreadComposerClipboardImageSource)
    private val finderFile =
        PlatformFile(id = "f", fileName = "photo.jpg", mimeType = "image/jpeg", fileRef = "/Users/me/photo.jpg", source = "paste")
    private val compressed =
        PlatformFile(id = "c", fileName = "image.jpg", mimeType = "image/jpeg", fileRef = "/cache/clipboard/composer-image-1.jpg", source = "composer_image_compressor")

    @Test
    fun aCopiedImageIsCompressedAndItsPngRetiredWhileFinderFilesKeepTheirBytes() = runTest {
        val compressCalls = mutableListOf<String>()
        val retired = mutableListOf<List<String>>()
        val result =
            threadComposerCompressPastedImages(
                files = listOf(pastedImage, finderFile),
                originalImage = false,
                compress = { compressCalls += it.id; compressed },
                retire = { retired += it },
            )
        assertEquals(listOf(compressed, finderFile), result)
        assertEquals(listOf("p"), compressCalls)
        assertEquals(listOf(listOf("/cache/clipboard/pasted-1.png")), retired)
    }

    @Test
    fun theOriginalImageOptionSkipsCompression() = runTest {
        var called = false
        val result =
            threadComposerCompressPastedImages(listOf(pastedImage), originalImage = true, compress = { called = true; compressed }, retire = {})
        assertEquals(listOf(pastedImage), result)
        assertEquals(false, called)
    }

    @Test
    fun aFailedOrEchoingCompressionKeepsThePastedPng() = runTest {
        val retired = mutableListOf<List<String>>()
        assertEquals(
            listOf(pastedImage),
            threadComposerCompressPastedImages(listOf(pastedImage), originalImage = false, compress = { null }, retire = { retired += it }),
        )
        assertEquals(
            listOf(pastedImage),
            threadComposerCompressPastedImages(listOf(pastedImage), originalImage = false, compress = { it }, retire = { retired += it }),
        )
        assertEquals(emptyList(), retired)
    }
}
