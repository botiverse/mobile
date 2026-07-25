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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

            val receipts =
                (1..2).map { cycle ->
                    runProfilerCycle(
                        cycle = cycle,
                        instrumentation = instrumentation,
                        context = context,
                        dataSource = dataSource
                    )
                }

            assertEquals("Expected both profiler artifact-set receipts in memory.", 2, receipts.size)
            receipts.forEach { receipt ->
                assertTrue(
                    "Expected non-empty report bytes for cycle ${receipt.cycle}.",
                    receipt.reportBytes.isNotEmpty()
                )
                assertTrue(
                    "Expected non-empty frames bytes for cycle ${receipt.cycle}.",
                    receipt.framesBytes.isNotEmpty()
                )
                assertEquals(
                    "Expected matching report/frames session IDs for cycle ${receipt.cycle}.",
                    receipt.reportSessionId,
                    receipt.framesSessionId
                )
            }
            assertNotEquals(
                "Back-to-back profiler cycles must not reuse a session ID.",
                receipts[0].reportSessionId,
                receipts[1].reportSessionId
            )
        }
    }

    private suspend fun runProfilerCycle(
        cycle: Int,
        instrumentation: android.app.Instrumentation,
        context: Context,
        dataSource: SharedSettingsDataSource
    ): ProfilerArtifactSetReceipt {
        val startDiagnostics =
            awaitProfilerAction("cycle $cycle start") {
                dataSource.startRecompositionProfiler()
            }
        assertTrue(startDiagnostics.profiler.supported)
        assertTrue(
            "Expected shared profiler default config to enable overlay in cycle $cycle.",
            startDiagnostics.profiler.defaults.enableOverlay
        )
        assertTrue(
            "Expected start feedback to mention the overlay runtime hint in cycle $cycle.",
            startDiagnostics.profiler.actionFeedback.orEmpty().contains("overlay", ignoreCase = true)
        )

        instrumentation.waitForIdleSync()
        Thread.sleep(CAPTURE_SETTLE_MS)

        val resetDiagnostics =
            awaitProfilerAction("cycle $cycle reset") {
                dataSource.resetRecompositionProfiler()
            }
        assertTrue(resetDiagnostics.profiler.supported)

        instrumentation.waitForIdleSync()
        Thread.sleep(CAPTURE_SETTLE_MS)

        val exportDiagnostics =
            awaitProfilerAction("cycle $cycle export native commit") {
                dataSource.exportRecompositionProfilerReport()
            }
        assertTrue(exportDiagnostics.profiler.supported)
        assertTrue(
            "Expected export feedback only after profiler_report.json commit in cycle $cycle.",
            exportDiagnostics.profiler.actionFeedback.orEmpty().contains("profiler_report.json")
        )

        val stopDiagnostics =
            awaitProfilerAction("cycle $cycle stop native commit") {
                dataSource.stopRecompositionProfiler()
            }
        assertTrue(
            "Expected stop feedback only after profiler_report.json commit in cycle $cycle.",
            stopDiagnostics.profiler.actionFeedback.orEmpty().contains("profiler_report.json")
        )

        return captureProfilerArtifactSet(
            cycle = cycle,
            artifacts = waitForCompleteProfilerArtifactSet(context)
        )
    }

    private suspend fun awaitProfilerAction(
        label: String,
        action: suspend () -> DataResult<SharedSettingsDiagnosticsSnapshot>
    ): SharedSettingsDiagnosticsSnapshot =
        withTimeoutOrNull(PROFILER_ACTION_TIMEOUT_MS) {
            requireContent(action())
        } ?: throw AssertionError(
            "Timed out waiting for $label acknowledgement after ${PROFILER_ACTION_TIMEOUT_MS}ms"
        )

    private fun captureProfilerArtifactSet(
        cycle: Int,
        artifacts: List<File>
    ): ProfilerArtifactSetReceipt {
        val artifactsByName = artifacts.associateBy(File::getName)
        val reportFile = checkNotNull(artifactsByName[PROFILER_REPORT_FILE_NAME]) {
            "Cycle $cycle artifact set did not include $PROFILER_REPORT_FILE_NAME"
        }
        val framesFile = checkNotNull(artifactsByName[PROFILER_FRAMES_FILE_NAME]) {
            "Cycle $cycle artifact set did not include $PROFILER_FRAMES_FILE_NAME"
        }
        assertEquals(
            "Cycle $cycle report and frames must share one directory.",
            reportFile.parentFile?.absolutePath,
            framesFile.parentFile?.absolutePath
        )

        val reportBytes = reportFile.readBytes()
        val framesBytes = framesFile.readBytes()
        val reportSessionId = JSONObject(reportBytes.toString(Charsets.UTF_8)).optString("sessionId")
        val framesHeader =
            framesBytes.toString(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { line -> line.isNotBlank() }
                ?.let { line -> JSONObject(line) }
                ?: throw AssertionError("Cycle $cycle frames file did not contain a JSONL session header")
        assertEquals(
            "Cycle $cycle frames first row must be a session header.",
            "session",
            framesHeader.optString("type")
        )
        val framesSessionId = framesHeader.optString("sessionId")
        assertTrue("Cycle $cycle report session ID must be non-empty.", reportSessionId.isNotBlank())
        assertTrue("Cycle $cycle frames session ID must be non-empty.", framesSessionId.isNotBlank())

        return ProfilerArtifactSetReceipt(
            cycle = cycle,
            reportBytes = reportBytes,
            framesBytes = framesBytes,
            reportSessionId = reportSessionId,
            framesSessionId = framesSessionId
        )
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
        private const val CAPTURE_SETTLE_MS = 1_200L
        private const val PROFILER_ACTION_TIMEOUT_MS = 20_000L
    }

    private data class ProfilerArtifactSetReceipt(
        val cycle: Int,
        val reportBytes: ByteArray,
        val framesBytes: ByteArray,
        val reportSessionId: String,
        val framesSessionId: String
    )
}
