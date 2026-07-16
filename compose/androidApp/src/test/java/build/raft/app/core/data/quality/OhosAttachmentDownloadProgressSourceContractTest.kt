package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhosAttachmentDownloadProgressSourceContractTest {
    @Test
    fun arkTsIsAtomicSinkOnlyAndCannotRestoreAPlatformHttpTransport() {
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

        assertTrue(moduleSource.contains("slockOhosAttachmentDownloadSink.download("))
        assertFalse(moduleSource.contains("requestInStream"))
        assertFalse(moduleSource.contains("createHttp"))
        assertFalse(moduleSource.contains("@kit.NetworkKit"))
        assertTrue(sinkSource.contains("slockAttachmentDownloadStart"))
        assertTrue(sinkSource.contains("fs.writeSync"))
        assertTrue(sinkSource.contains("fs.fsyncSync"))
        assertTrue(sinkSource.contains("fs.renameSync"))
        assertTrue(sinkSource.contains(".part-${'$'}{this.safeToken(attemptToken)}"))
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
