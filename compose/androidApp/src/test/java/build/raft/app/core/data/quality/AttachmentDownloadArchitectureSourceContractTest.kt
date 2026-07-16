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
        val jsSink =
            File(root, "compose/shared/src/jsMain/kotlin/build/raft/app/network/AttachmentDownloadAtomicSink.js.kt")
                .readText()
        val bridge =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/base/BridgeModule.kt")
                .readText()
        val atomicSink =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadAtomicFileSink.kt")
                .readText()

        assertTrue(common.contains("client.downloadStream("))
        assertTrue(common.contains("object AttachmentDownloadCoordinator"))
        assertTrue(android.contains("AttachmentDownloadCoordinator.start("))
        assertTrue(android.contains("AttachmentDownloadCoordinator.cancelTargetAndAwaitCleanup"))
        assertTrue(android.contains("AttachmentDownloadCoordinator.finishTargetCleanup"))
        assertTrue(android.contains("!it.name.contains(\".part\")"))
        assertTrue(ios.contains("SharedAttachmentDownloadCoordinator.shared"))
        assertTrue(ohos.contains("slockOhosAttachmentDownloadSink.download("))
        assertFalse(ios.contains("NSURLSession.sharedSession downloadTaskWithURL"))
        assertFalse(ohos.contains("requestInStream"))
        assertFalse(ohos.contains("createHttp"))
        assertTrue(ohos.contains("!name.includes('.part')"))
        assertTrue(jsSink.contains("override fun begin(): Boolean = false"))
        assertTrue(bridge.contains("continuation.invokeOnCancellation"))
        assertTrue(bridge.contains("AttachmentDownloadCoordinator.control("))
        assertTrue(bridge.contains("expectedAttemptToken = activeAttemptToken"))
        assertTrue(atomicSink.contains("while (offset < bytes.size)"))
        assertTrue(atomicSink.contains("ops.fsync(activeHandle)"))
        assertTrue(atomicSink.contains("ops.close(activeHandle)"))
        assertTrue(atomicSink.contains("ops.rename(stagingPath, targetPath)"))
        assertFalse(atomicSink.contains("isFile"))
        assertFalse(atomicSink.contains("exists("))
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
