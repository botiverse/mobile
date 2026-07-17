package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosAttachmentPickerSourceContractTest {
    @Test
    fun iosThreadAttachmentPickerReturnsCachedPlatformFilesInsteadOfUnavailableStub() {
        val iosSource =
            File(findRepoRoot(), "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()
        val uploadSource =
            File(
                    findRepoRoot(),
                    "compose/shared/src/commonMain/kotlin/build/raft/app/data/ConversationRepositoryDataSource.kt",
                )
                .readText()

        assertTrue(iosSource.contains("PHPickerViewController *pickerController"))
        assertTrue(iosSource.contains("PHPickerFilter.imagesFilter"))
        assertTrue(iosSource.contains("PHPickerFilter.videosFilter"))
        assertTrue(iosSource.contains("selectionLimit = SlockAttachmentPickerMaxFiles"))
        assertTrue(iosSource.contains("loadFileRepresentationForTypeIdentifier"))
        assertTrue(iosSource.contains("initForOpeningContentTypes:contentTypes asCopy:YES"))
        assertTrue(iosSource.contains("pickerController.allowsMultipleSelection = YES"))
        assertTrue(iosSource.contains("startAccessingSecurityScopedResource"))
        assertTrue(iosSource.contains("copyItemAtURL:sourceURL toURL:targetURL"))
        assertTrue(iosSource.contains("pickedURLsByIndex[@(index)] = cachedURL"))
        assertTrue(iosSource.contains("@\"errorCode\" : @\"attachment_type_unsupported\""))
        assertTrue(iosSource.contains("resolvedURLs.count == 0 && resolvedErrors.count > 0"))
        assertTrue(iosSource.contains("@\"fileRef\" : fileRef"))
        assertTrue(iosSource.contains("@\"files\" : files"))
        assertTrue(iosSource.contains("@\"source\" : @\"platform_picker\""))
        assertTrue(uploadSource.contains("name = \"files\""))
        assertFalse(iosSource.contains("Attachment picker unavailable on iOS P0 host bridge"))
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
