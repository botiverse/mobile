package build.raft.app.core.data.diagnostics

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTraceProductionContractTest {
    @Test
    fun feedbackArchiveIncludesRecentPerformanceTraceSnapshot() {
        val source = projectFile(
            "androidApp/src/main/java/build/raft/app/core/data/diagnostics/SlockAndroidLocalDiagnostics.kt"
        ).readText()

        assertTrue(source.contains("SlockPerformanceTraceRing.exportRecentJsonl()"))
        assertTrue(source.contains("performance/recent-15s.jsonl"))
    }

    @Test
    fun alphaEnablesTraceRingWhileReleaseRequiresExplicitDiagnosticFlag() {
        val source = projectFile("androidApp/build.gradle.kts").readText()

        assertTrue(
            source.contains(
                "slockBuildConfigBoolean(\"SLOCK_PERFORMANCE_TRACE_RING_ENABLED\", false).toString()"
            )
        )
        assertTrue(
            source.contains(
                "buildConfigField(\"Boolean\", \"PERFORMANCE_TRACE_RING_ENABLED\", \"true\")"
            )
        )
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val composeRoot =
            generateSequence(workingDirectory) { it.parentFile }
                .firstOrNull { File(it, "androidApp/build.gradle.kts").isFile }
                ?: error("Unable to locate compose project root from $workingDirectory")
        return File(composeRoot, relativePath)
    }
}
