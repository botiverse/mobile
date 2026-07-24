package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import build.raft.app.R
import build.raft.app.data.DataResult
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KuiklyProfilerRuntimeSmokeTest {
    @Test
    fun sharedProfilerExportsArtifactsFromKuiklyLoginPage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        clearKnownProfilerArtifacts(context)
        val dataSource = SharedSettingsDataSource(sessionStore = InMemorySlockAuthSessionStore())
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_LOGIN)
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            waitUntil("Kuikly login page attaches to the host container") {
                var attached = false
                scenario.onActivity { activity ->
                    val container = activity.findViewById<ViewGroup>(R.id.slock_kuikly_container)
                    attached = container.childCount > 0
                }
                attached
            }

            scenario.onActivity { activity ->
                assertEquals(SlockKuiklyActivity.PAGE_LOGIN, activity.intent.getStringExtra("pageName"))
            }

            val initialDiagnostics = requireContent(dataSource.loadDiagnostics())
            assertTrue(
                "Expected shared profiler debug gate to be available in connectedTest host.",
                initialDiagnostics.profiler.supported
            )

            val startDiagnostics = requireContent(dataSource.startRecompositionProfiler())
            assertTrue(startDiagnostics.profiler.supported)
            assertTrue(
                "Expected shared profiler default config to enable overlay.",
                startDiagnostics.profiler.defaults.enableOverlay
            )
            assertTrue(
                "Expected start feedback to mention the overlay runtime hint.",
                startDiagnostics.profiler.actionFeedback.orEmpty().contains("overlay", ignoreCase = true)
            )

            instrumentation.waitForIdleSync()
            Thread.sleep(1_200)

            val resetDiagnostics = requireContent(dataSource.resetRecompositionProfiler())
            assertTrue(resetDiagnostics.profiler.supported)

            instrumentation.waitForIdleSync()
            Thread.sleep(1_200)

            val stopDiagnostics = requireContent(dataSource.stopRecompositionProfiler())
            assertTrue(
                "Expected stop feedback to mention profiler export file names.",
                stopDiagnostics.profiler.actionFeedback.orEmpty().contains("profiler_report.json")
            )

            val exportDiagnostics = requireContent(dataSource.exportRecompositionProfilerReport())
            assertTrue(exportDiagnostics.profiler.supported)
            assertTrue(
                "Expected export feedback to mention profiler_report.json.",
                exportDiagnostics.profiler.actionFeedback.orEmpty().contains("profiler_report.json")
            )

            val artifacts = waitForCompleteProfilerArtifactSet(context)
            val artifactSummary = artifacts.joinToString { artifact ->
                "${artifact.name}:${artifact.length()}"
            }
            assertTrue(
                "Expected a non-empty profiler_report.json, artifacts=$artifactSummary",
                artifacts.any { it.name == PROFILER_REPORT_FILE_NAME && it.length() > 0L }
            )
            assertTrue(
                "Expected a non-empty profiler_frames.jsonl beside the report, artifacts=$artifactSummary",
                artifacts.any { it.name == PROFILER_FRAMES_FILE_NAME && it.length() > 0L }
            )
        }
    }

    private fun requireContent(
        result: DataResult<SharedSettingsDiagnosticsSnapshot>
    ): SharedSettingsDiagnosticsSnapshot =
        when (result) {
            is DataResult.Content -> result.data
            is DataResult.Empty ->
                throw AssertionError("Expected diagnostics content, got Empty(${result.reason})")
            is DataResult.Error ->
                throw AssertionError("Expected diagnostics content, got Error(${result.error.code})")
            is DataResult.NoPermission ->
                throw AssertionError("Expected diagnostics content, got NoPermission(${result.blocker.code})")
        }

    private fun waitUntil(
        message: String,
        timeoutMs: Long = 12_000L,
        stepMs: Long = 250L,
        condition: () -> Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) {
                return
            }
            Thread.sleep(stepMs)
        }
        throw AssertionError(message)
    }

    private fun waitForCompleteProfilerArtifactSet(context: Context): List<File> {
        var artifacts = emptyList<File>()
        waitUntil(
            "Expected non-empty profiler_report.json and profiler_frames.jsonl in the same " +
                "app-sandbox directory, searched=${profilerSearchRoots(context).map(File::getAbsolutePath)}"
        ) {
            artifacts = findCompleteProfilerArtifactSet(context)
            artifacts.isNotEmpty()
        }
        return artifacts
    }

    private fun findCompleteProfilerArtifactSet(context: Context): List<File> {
        val targetNames = setOf(PROFILER_REPORT_FILE_NAME, PROFILER_FRAMES_FILE_NAME)
        return findProfilerArtifacts(context)
            .filter { artifact -> artifact.length() > 0L }
            .groupBy { artifact -> artifact.parentFile?.absolutePath.orEmpty() }
            .values
            .firstOrNull { artifacts -> artifacts.map(File::getName).toSet().containsAll(targetNames) }
            .orEmpty()
    }

    private fun clearKnownProfilerArtifacts(context: Context) {
        findProfilerArtifacts(context).forEach { artifact ->
            artifact.delete()
        }
    }

    private fun findProfilerArtifacts(context: Context): List<File> {
        val targetNames = setOf(PROFILER_REPORT_FILE_NAME, PROFILER_FRAMES_FILE_NAME)
        return profilerSearchRoots(context)
            .asSequence()
            .filter { it.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .maxDepth(5)
                    .filter { file -> file.isFile && file.name in targetNames }
            }
            .distinctBy(File::getAbsolutePath)
            .toList()
    }

    private fun profilerSearchRoots(context: Context): List<File> =
        buildList {
            add(context.cacheDir)
            add(context.filesDir)
            add(context.codeCacheDir)
            context.externalCacheDir?.let(::add)
            context.dataDir?.let(::add)
            context.cacheDir.parentFile?.let(::add)
        }.distinctBy(File::getAbsolutePath)

    companion object {
        private const val PROFILER_REPORT_FILE_NAME = "profiler_report.json"
        private const val PROFILER_FRAMES_FILE_NAME = "profiler_frames.jsonl"
    }
}
