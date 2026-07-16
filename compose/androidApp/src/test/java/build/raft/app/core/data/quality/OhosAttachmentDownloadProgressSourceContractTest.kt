package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhosAttachmentDownloadProgressSourceContractTest {
    @Test
    fun arkTsIsLowFrequencyObserverOnlyAndHotBytesStayInOhosArm64PosixSink() {
        val moduleSource =
            File(
                findRepoRoot(),
                "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
            ).readText()
        val sinkSource =
            File(
                findRepoRoot(),
                "compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosAttachmentDownloadSink.ets"
            ).readText()
        val posixSinkSource =
            File(
                findRepoRoot(),
                "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/network/AttachmentDownloadAtomicSink.ohos.kt"
            ).readText()

        assertTrue(moduleSource.contains("slockOhosAttachmentDownloadSink.download("))
        assertFalse(moduleSource.contains("requestInStream"))
        assertFalse(moduleSource.contains("createHttp"))
        assertFalse(moduleSource.contains("@kit.NetworkKit"))
        assertTrue(sinkSource.contains("slockAttachmentDownloadStart"))
        assertFalse(sinkSource.contains("fs.writeSync"))
        assertFalse(sinkSource.contains("fs.fsyncSync"))
        assertFalse(sinkSource.contains("fs.renameSync"))
        assertTrue(posixSinkSource.contains("AttachmentDownloadAtomicFileSink"))
        assertTrue(posixSinkSource.contains("OhosAttachmentDownloadFileOps"))
        assertTrue(posixSinkSource.contains("platform.posix.write"))
        assertFalse(sinkSource.contains("requestInStream"))
        assertFalse(sinkSource.contains("createHttp"))
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
