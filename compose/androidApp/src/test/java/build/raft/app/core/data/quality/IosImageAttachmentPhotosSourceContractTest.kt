package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosImageAttachmentPhotosSourceContractTest {
    @Test
    fun saveToPhotosUsesAuthenticatedDownloadAndAddOnlyPhotoKit() {
        val root = findRepoRoot()
        val thread = File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt").readText()
        val preview = File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt").readText()
        val bridge = File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/base/BridgeModule.kt").readText()
        val ios = File(root, "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m").readText()
        val info = File(root, "compose/iosApp/iosApp/Info.plist").readText()
        val iosCapabilities =
            File(root, "compose/shared/src/iosMain/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.ios.kt")
                .readText()
        val androidCapabilities =
            File(root, "compose/shared/src/androidMain/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.android.kt")
                .readText()
        val ohosCapabilities =
            File(root, "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.ohos.kt")
                .readText()

        assertTrue(preview.contains("threadApi.attachmentUrl(attachmentId, disposition = \"attachment\")"))
        assertTrue(preview.contains("bridge.saveImageToPhotos("))
        assertTrue(preview.contains("SlockFilePreviewSaveToPhotosActionId"))
        assertTrue(preview.contains("slockFilePreviewPhotosActionState("))
        assertTrue(preview.contains("SlockFilePreviewExternalOperationIdentity"))
        assertTrue(preview.contains("slockFilePreviewShouldRetireExternalOperation("))
        assertTrue(preview.contains("val activeExternalOperationJob = remember { mutableStateOf<Job?>(null) }"))
        assertTrue(preview.contains("DisposableEffect(Unit)"))
        assertTrue(preview.contains("activeAttachmentOperation = null\n            activeExternalOperationIdentity = null"))
        assertTrue(preview.contains("retiringJob?.cancel()"))
        assertTrue(preview.contains("coroutineScope.launch(start = CoroutineStart.LAZY)"))
        assertFalse(thread.contains("SaveToPhotosActionId"))
        assertFalse(thread.contains("bridge.saveImageToPhotos("))
        assertTrue(bridge.contains("const val SAVE_IMAGE_TO_PHOTOS = \"saveImageToPhotos\""))
        assertTrue(bridge.contains("methodArgs.putAttachmentOwner(owner)"))
        assertTrue(bridge.contains("AttachmentDownloadCoordinator.control("))
        assertTrue(bridge.contains("CANCEL_ATTACHMENT_EXPORT"))

        assertTrue(ios.contains("SharedAttachmentDownloadCoordinator.shared"))
        assertTrue(ios.contains("PHAccessLevelAddOnly"))
        assertTrue(ios.contains("CGImageSourceCreateWithURL"))
        assertTrue(ios.contains("CGImageSourceGetType"))
        assertTrue(ios.contains("conformsToType:UTTypeImage"))
        assertTrue(ios.contains("kCGImagePropertyOrientation"))
        assertTrue(ios.contains("PHAssetCreationRequest"))
        assertTrue(ios.contains("addResourceWithType:PHAssetResourceTypePhoto fileURL:targetURL"))
        assertTrue(ios.contains("cancelAttachmentExport"))
        assertTrue(ios.contains("slock_attachmentExportDownloadId isEqualToString:capturedDownloadId"))
        assertFalse(ios.contains("UIImageWriteToSavedPhotosAlbum"))
        assertTrue(info.contains("NSPhotoLibraryAddUsageDescription"))

        assertTrue(iosCapabilities.contains("slockPlatformSupportsSaveImageToPhotos(): Boolean = true"))
        assertTrue(androidCapabilities.contains("slockPlatformSupportsSaveImageToPhotos(): Boolean = false"))
        assertTrue(ohosCapabilities.contains("slockPlatformSupportsSaveImageToPhotos(): Boolean = false"))
    }

    private fun findRepoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository root from ${System.getProperty("user.dir")}")
    }
}
