package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IosAttachmentPickerSourceContractTest {
    @Test
    fun photoPickerAllowsImagesAndVideosWithoutOpeningTheDocumentPicker() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        assertTrue(source.contains("[PHPickerFilter anyFilterMatchingSubfilters:@["))
        assertTrue(source.contains("PHPickerFilter.imagesFilter"))
        assertTrue(source.contains("PHPickerFilter.videosFilter"))
        assertTrue(source.contains("initForOpeningContentTypes:@[UTTypeItem]"))
    }

    @Test
    fun photoPickerPreservesConcreteMediaTypeAndDisplayNameExtension() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        assertTrue(source.contains("SlockPreferredPickedMediaType(provider)"))
        assertTrue(source.contains("[type conformsToType:UTTypeImage]"))
        assertTrue(source.contains("[type conformsToType:UTTypeMovie]"))
        assertTrue(source.contains("[type isEqual:UTTypeImage]"))
        assertTrue(source.contains("[type isEqual:UTTypeMovie]"))
        assertTrue(
            source.contains(
                "mediaType == nil || ![provider hasItemConformingToTypeIdentifier:typeIdentifier]"
            )
        )
        assertTrue(source.contains("loadFileRepresentationForTypeIdentifier:typeIdentifier"))
        assertTrue(source.contains("mimeType:mediaType.preferredMIMEType"))
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

    @Test
    fun photoPickerCachesProviderFileBeforeItsCompletionReturns() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        val completionStart = source.indexOf("loadFileRepresentationForTypeIdentifier:typeIdentifier")
        val synchronousCache = source.indexOf("file = [self slock_cachePickedAttachmentURL:url", completionStart)
        val completionLeave = source.indexOf("dispatch_group_leave(group);", synchronousCache)

        assertTrue(completionStart >= 0)
        assertTrue(synchronousCache > completionStart)
        assertTrue(completionLeave > synchronousCache)
    }

    @Test
    fun mediaPickerPreservesSelectionOrderAndReportsPartialReads() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        assertTrue(source.contains("configuration.selectionLimit = 0"))
        assertTrue(source.contains("orderedFiles[index] = file"))
        assertTrue(source.contains("errors.count > 0 ? @206 : @200"))
        assertTrue(source.contains("@\"attachment_read_partial\""))
    }

    @Test
    fun mediaPickerKeepsBusyPermissionAndCancellationBoundaries() {
        val source =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()

        assertTrue(source.contains("@\"attachment_picker_busy\""))
        assertTrue(source.contains("@\"attachment_permission_denied\""))
        assertTrue(source.contains("[self slock_finishAttachmentPicker:@{ @\"code\" : @204"))
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
