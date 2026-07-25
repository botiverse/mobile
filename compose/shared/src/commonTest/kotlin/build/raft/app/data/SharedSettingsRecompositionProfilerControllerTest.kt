package build.raft.app.data

import com.tencent.kuikly.compose.profiler.RecompositionConfigBuilder
import com.tencent.kuikly.compose.profiler.RecompositionProfilerFileOutputResult
import com.tencent.kuikly.compose.profiler.RecompositionReport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSettingsRecompositionProfilerControllerTest {
    @Test
    fun stopWaitsForNativeAcknowledgementBeforeReportingSavedFeedback() = runBlocking {
        val engine = ControllableProfilerEngine(enabled = true)
        val controller = controller(engine)

        val pending = async(start = CoroutineStart.UNDISPATCHED) { controller.stop() }

        assertTrue(pending.isActive)
        assertFalse(engine.isEnabled)
        assertEquals("In memory", controller.buildSnapshot().report.generatedLabel)

        engine.completeStop(RecompositionProfilerFileOutputResult.Success(TEST_SESSION_ID))
        val result = pending.await()

        assertNull(result.errorCode)
        assertTrue(result.feedback.contains("committed"))
        assertEquals("Saved", controller.buildSnapshot().report.generatedLabel)
    }

    @Test
    fun exportWaitsForNativeAcknowledgementBeforeReportingSavedFeedback() = runBlocking {
        val engine = ControllableProfilerEngine(enabled = true)
        val controller = controller(engine)

        val pending = async(start = CoroutineStart.UNDISPATCHED) { controller.exportReport() }

        assertTrue(pending.isActive)
        assertEquals("In memory", controller.buildSnapshot().report.generatedLabel)

        engine.completeExport(RecompositionProfilerFileOutputResult.Success(TEST_SESSION_ID))
        val result = pending.await()

        assertNull(result.errorCode)
        assertTrue(result.feedback.contains("Report exported"))
        assertEquals("Saved", controller.buildSnapshot().report.generatedLabel)
    }

    @Test
    fun exportFailureSurfacesNonRetryableErrorAndDoesNotMarkArtifactSaved() = runBlocking {
        val engine = ControllableProfilerEngine(enabled = true)
        val controller = controller(engine)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
                recompositionProfilerController = controller
            )

        val pending =
            async(start = CoroutineStart.UNDISPATCHED) {
                dataSource.exportRecompositionProfilerReport()
            }
        engine.completeExport(
            RecompositionProfilerFileOutputResult.Failure(
                sessionId = TEST_SESSION_ID,
                reason = "report denied"
            )
        )
        val result = assertIs<DataResult.Error>(pending.await())

        assertEquals(FILE_OUTPUT_ERROR_CODE, result.error.code)
        assertFalse(result.error.retryable)
        assertEquals("In memory", controller.buildSnapshot().report.generatedLabel)
    }

    @Test
    fun stopFailureClearsPersistedSwitchAfterEngineHasStopped() = runBlocking {
        val engine = ControllableProfilerEngine(enabled = true)
        val controller = controller(engine)
        val preferenceRepository = RecordingProfilerPreferenceRepository(enabled = true)
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
                recompositionProfilerPreferenceRepository = preferenceRepository,
                recompositionProfilerController = controller
            )

        val pending =
            async(start = CoroutineStart.UNDISPATCHED) {
                dataSource.stopRecompositionProfiler()
            }

        assertTrue(pending.isActive)
        assertFalse(engine.isEnabled)
        assertTrue(preferenceRepository.enabled)

        engine.completeStop(
            RecompositionProfilerFileOutputResult.Failure(
                sessionId = TEST_SESSION_ID,
                reason = "context unavailable"
            )
        )
        val result = assertIs<DataResult.Error>(pending.await())

        assertEquals(FILE_OUTPUT_ERROR_CODE, result.error.code)
        assertFalse(result.error.retryable)
        assertFalse(preferenceRepository.enabled)
        assertEquals(listOf(false), preferenceRepository.writes)
        assertEquals("In memory", controller.buildSnapshot().report.generatedLabel)
    }

    private fun controller(
        engine: SharedSettingsRecompositionProfilerEngine
    ): SharedSettingsRecompositionProfilerController =
        SharedSettingsRecompositionProfilerController(
            engine = engine,
            debugGate = SupportedProfilerDebugGate
        )

    private class ControllableProfilerEngine(
        enabled: Boolean
    ) : SharedSettingsRecompositionProfilerEngine {
        private var enabledState: Boolean = enabled
        private var stopCompletion: ((RecompositionProfilerFileOutputResult) -> Unit)? = null
        private var exportCompletion: ((RecompositionProfilerFileOutputResult) -> Unit)? = null

        override val isEnabled: Boolean
            get() = enabledState

        override fun clearCustomFilters() = Unit

        override fun configure(block: RecompositionConfigBuilder.() -> Unit) = Unit

        override fun start() {
            enabledState = true
        }

        override fun stop(completion: (RecompositionProfilerFileOutputResult) -> Unit) {
            check(stopCompletion == null) { "stop acknowledgement is already pending" }
            enabledState = false
            stopCompletion = completion
        }

        override fun reset() = Unit

        override fun getReportSnapshot(): RecompositionReport =
            testReport()

        override fun exportReport(
            completion: (RecompositionProfilerFileOutputResult) -> Unit
        ): RecompositionReport {
            check(exportCompletion == null) { "export acknowledgement is already pending" }
            exportCompletion = completion
            return testReport()
        }

        fun completeStop(result: RecompositionProfilerFileOutputResult) {
            val completion = checkNotNull(stopCompletion) { "no stop acknowledgement is pending" }
            stopCompletion = null
            completion(result)
        }

        fun completeExport(result: RecompositionProfilerFileOutputResult) {
            val completion = checkNotNull(exportCompletion) { "no export acknowledgement is pending" }
            exportCompletion = null
            completion(result)
        }
    }

    private class RecordingProfilerPreferenceRepository(
        var enabled: Boolean
    ) : RecompositionProfilerPreferenceRepository {
        val writes = mutableListOf<Boolean>()

        override suspend fun getPreference(): RecompositionProfilerPreferenceSnapshot =
            RecompositionProfilerPreferenceSnapshot(enabled)

        override suspend fun setEnabled(enabled: Boolean): RecompositionProfilerPreferenceSnapshot {
            this.enabled = enabled
            writes += enabled
            return RecompositionProfilerPreferenceSnapshot(enabled)
        }
    }

    private object SupportedProfilerDebugGate : SharedSettingsRecompositionProfilerDebugGate {
        override val supported: Boolean = true
        override val gateLabel: String = "Connected test"
        override val unavailableDetail: String = "Profiler unavailable"
    }

    private companion object {
        const val TEST_SESSION_ID = "session-ack"
        const val FILE_OUTPUT_ERROR_CODE = "settings_profiler_file_output_failed"

        fun testReport(): RecompositionReport =
            RecompositionReport(
                sessionId = TEST_SESSION_ID,
                startTimestampMs = 1L,
                durationMs = 10L,
                totalFrames = 1L,
                totalRecompositions = 1,
                composables = emptyList(),
                hotspots = emptyList(),
                stateChanges = emptyList()
            )
    }
}
