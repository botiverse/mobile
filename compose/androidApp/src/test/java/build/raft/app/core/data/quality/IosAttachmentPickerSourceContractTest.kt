package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IosAttachmentPickerSourceContractTest {
    @Test
    fun photoPickerPreservesConcreteImageTypeAndDisplayNameExtension() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        assertTrue(source.contains("SlockPreferredPickedImageType(provider)"))
        assertTrue(source.contains("loadFileRepresentationForTypeIdentifier:typeIdentifier"))
        assertTrue(source.contains("mimeType:imageType.preferredMIMEType"))
        assertTrue(
            source.contains("SlockPickedAttachmentDisplayName(suggestedName, sourceURL, mimeType)")
        )
        assertTrue(
            source.contains("[UTType typeWithMIMEType:mimeType].preferredFilenameExtension")
        )
        assertTrue(
            source.contains("displayName = [displayName stringByAppendingPathExtension:extension]")
        )
    }

    private fun sourceFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
