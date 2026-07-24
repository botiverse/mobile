package build.raft.app.shared

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentGalleryBridgeSourceContractTest {
    @Test
    fun operationAuthorityAndExistingOnlyCancelStayWiredAcrossHosts() {
        val root = findRepoRoot()
        val android =
            root.resolve("compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
                .readText()
        val sharedBridge =
            root.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/base/BridgeModule.kt")
                .readText()
        val ios =
            root.resolve("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()
        val ohosBridge =
            root.resolve(
                "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/network/" +
                    "AttachmentDownloadKnoiBridge.ohos.kt"
            ).readText()
        val ohosSink =
            root.resolve("compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosAttachmentDownloadSink.ets")
                .readText()
        val ohosModule =
            root.resolve("compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets")
                .readText()

        listOf(
            "attachmentDownloadsExportCoordinator.exportIfCurrent(" to android,
            "AttachmentDownloadCoordinator.controlExisting(" to android,
            "attachmentSystemDownloads.controlExisting(" to android,
            "attachmentDownloadsExportCoordinator.controlExisting(" to android,
            "AttachmentDownloadCoordinator.controlExisting(" to sharedBridge,
            "controlExistingDownloadId:downloadId" to ios,
            "fun slockAttachmentDownloadControlExisting(" to ohosBridge,
            "'slockAttachmentDownloadControlExisting'" to ohosSink,
            "slockOhosAttachmentDownloadSink.controlExisting(" to ohosModule
        ).forEach { (required, source) ->
            assertTrue("Missing gallery operation contract token: $required", source.contains(required))
        }

        val ohosCancel = ohosModule.substringAfter("private cancelAttachmentExport")
        assertTrue(
            "OHOS must finish existing-only download cleanup before releasing operation authority",
            ohosCancel.indexOf("controlExisting(") in 0 until ohosCancel.indexOf("activePhotoExportDownloadId = \"\"")
        )
    }

    private fun findRepoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { candidate ->
                candidate.resolve("compose/settings.gradle.kts").isFile &&
                    candidate.resolve("compose/androidApp").isDirectory
            } ?: error("Unable to locate repository root")
}
