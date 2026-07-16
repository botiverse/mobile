package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadArchitectureSourceContractTest {
    @Test
    fun allHostsUseCommonCoordinatorAndKeepPlatformCodeSinkOnly() {
        val root = findRepoRoot()
        val common =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadCoordinator.kt")
                .readText()
        val android =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
                .readText()
        val ios =
            File(root, "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()
        val ohos =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets")
                .readText()

        assertTrue(common.contains("client.downloadStream("))
        assertTrue(common.contains("object AttachmentDownloadCoordinator"))
        assertTrue(android.contains("AttachmentDownloadCoordinator.start("))
        assertTrue(ios.contains("SharedAttachmentDownloadCoordinator.shared"))
        assertTrue(ohos.contains("slockOhosAttachmentDownloadSink.download("))
        assertFalse(ios.contains("NSURLSession.sharedSession downloadTaskWithURL"))
        assertFalse(ohos.contains("requestInStream"))
        assertFalse(ohos.contains("createHttp"))
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
