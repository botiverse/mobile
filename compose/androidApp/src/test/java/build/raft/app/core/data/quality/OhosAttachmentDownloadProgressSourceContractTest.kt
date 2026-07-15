package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhosAttachmentDownloadProgressSourceContractTest {
    @Test
    fun producerCarriesAttemptIdentityAndDoesNotRestoreLegacyFakeZeroGate() {
        val source =
            File(
                findRepoRoot(),
                "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
            ).readText()

        assertTrue(source.contains("const downloadId = (records[\"downloadId\"] as string | undefined)"))
        assertTrue(source.contains("downloadId: string,"))
        assertTrue(source.contains("\"downloadId\": downloadId"))
        assertTrue(source.contains("\"sequence\": sequence"))
        assertTrue(source.contains("\"phase\": phase"))
        assertTrue(source.contains("\"receiving\""))
        assertTrue(source.contains("\"complete\""))
        assertTrue(source.contains("firstPositiveBytes || now - lastProgressAt >= 250"))
        assertTrue(source.contains("const tempPath = `${'$'}{path}.part-${'$'}{attemptToken}`;"))
        assertFalse(source.contains("emitAttachmentDownloadProgressIfNeeded"))
        assertFalse(source.contains("percent - lastPercent >= 2"))
        assertFalse(source.contains("now - lastProgressAt >= 750"))
        assertFalse(source.contains("emitAttachmentDownloadProgress(progressCallback, 0"))
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
