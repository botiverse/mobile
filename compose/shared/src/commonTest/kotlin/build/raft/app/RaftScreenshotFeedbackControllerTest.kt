package build.raft.app

import build.raft.app.data.FeedbackErrorKind
import build.raft.app.data.FeedbackFailure
import build.raft.app.data.FeedbackResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftFeedbackAgentDraftSentRepository
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.SETTINGS_FEEDBACK_PRIVACY_MODE_ON
import build.raft.app.data.SettingsFeedbackDiagnosticsUploader
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.data.SettingsFeedbackSubmitRepository
import build.raft.app.data.SettingsFeedbackSubmitRequest
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SyncTestPreferences
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import build.raft.app.settings.RaftScreenshotFeedbackController
import build.raft.app.settings.RaftScreenshotFeedbackDiagnosticsState
import build.raft.app.settings.RaftScreenshotFeedbackPageContext
import build.raft.app.settings.RaftScreenshotFeedbackState
import build.raft.app.settings.extractScreenshotFeedbackPageContext
import build.raft.app.settings.resolveMobileScreenshotFeedbackEligible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class RaftScreenshotFeedbackControllerTest {
    private val sampleContext =
        RaftScreenshotFeedbackPageContext(
            pageName = "compose_thread",
            serverId = "srv-1",
            channelId = "ch-9",
            threadId = "th-42",
            viewportSize = "1080x2400",
            orientation = "portrait",
            appVersion = "1.29.0",
            buildNumber = "1290001",
            platform = "android",
        )

    private val sampleDiagnostics =
        FeedbackDiagnosticsReference(
            r2Key = "feedback/raft-android/presigned/11111111-2222-4333-8444-555555555555-slock-feedback.zip",
            filename = "slock-feedback.zip",
            contentType = "application/zip",
            size = 4096L,
        )

    private fun sampleFile(id: String) =
        PlatformFile(
            id = id,
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2048L,
            previewUri = "file:///cache/screenshot-feedback/$id.jpg",
            fileRef = "file:///cache/screenshot-feedback/$id.jpg",
            source = "screenshot_feedback",
        )

    private class FakeSubmitRepository(
        private val answers: MutableList<FeedbackResult<String>>,
    ) : SettingsFeedbackSubmitRepository {
        val requests = mutableListOf<SettingsFeedbackSubmitRequest>()

        override suspend fun submit(request: SettingsFeedbackSubmitRequest): FeedbackResult<String> {
            requests += request
            return answers.removeAt(0)
        }
    }

    private fun buildSubmitter(
        repository: FakeSubmitRepository,
        privacyMode: () -> RaftPrivacyMode = { RaftPrivacyMode.Off },
        diagnosticsCalls: MutableList<String> = mutableListOf(),
    ): SettingsFeedbackProxySubmitter {
        var ids = 0
        return SettingsFeedbackProxySubmitter(
            repository = repository,
            diagnostics = SettingsFeedbackDiagnosticsUploader { accountId ->
                diagnosticsCalls += accountId
                sampleDiagnostics
            },
            accountId = "user-1",
            locale = { "en" },
            newSubmissionId = { "sub-${++ids}" },
            agentDraftSent = RaftFeedbackAgentDraftSentRepository { SyncTestPreferences() },
            privacyMode = privacyMode,
        )
    }

    @Test
    fun eligibilityRequiresFlagOrAlphaPlusSignedInAndPrivacyOff() = runTest {
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val server = ServerId("srv-1")
        val flagsOff =
            SharedServerFeatureFlags(
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>) =
                        HomeNetworkResponse(code = 200, bodyText = """{"evaluations":[]}""")
                }
            )
        flagsOff.prefetch(server)
        val flagsOn =
            SharedServerFeatureFlags(
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>) =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText = """{"evaluations":[{"key":"$MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY","enabled":true}]}""",
                        )
                }
            )
        flagsOn.prefetch(server)

        // Default OFF on production
        assertFalse(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOff,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "user-1",
                serverId = "srv-1",
                buildConfigAlphaEnabled = false,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
        // Server flag ON enables production build
        assertTrue(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOn,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "user-1",
                serverId = "srv-1",
                buildConfigAlphaEnabled = false,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
        // Alpha fallback enables when signed in & privacy Off
        assertTrue(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOff,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "user-1",
                serverId = "srv-1",
                buildConfigAlphaEnabled = true,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
        // Local ForceOn enables even when server flag & alpha are off
        registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOff,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "user-1",
                serverId = "srv-1",
                buildConfigAlphaEnabled = false,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
        // Signed out or privacy On/Unknown ALWAYS refuse, even under ForceOn + Alpha
        for (privacy in listOf(RaftPrivacyMode.On, RaftPrivacyMode.Unknown)) {
            assertFalse(
                resolveMobileScreenshotFeedbackEligible(
                    serverFeatureFlags = flagsOn,
                    registry = registry,
                    environmentId = "https://api.example",
                    accountId = "user-1",
                    serverId = "srv-1",
                    buildConfigAlphaEnabled = true,
                    privacyMode = privacy,
                )
            )
        }
        assertFalse(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOn,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "",
                serverId = "srv-1",
                buildConfigAlphaEnabled = true,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
        // Local ForceOff disables even when server flag & alpha are on
        registry.setMode(MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(
            resolveMobileScreenshotFeedbackEligible(
                serverFeatureFlags = flagsOn,
                registry = registry,
                environmentId = "https://api.example",
                accountId = "user-1",
                serverId = "srv-1",
                buildConfigAlphaEnabled = true,
                privacyMode = RaftPrivacyMode.Off,
            )
        )
    }

    @Test
    fun debouncesCapturesAndAutoDismissesPromptWithTempFileCleanup() = runTest {
        var now = 10_000L
        val deleted = mutableListOf<String>()
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { now },
                debounceMillis = 1_500L,
                promptAutoDismissMillis = 7_000L,
                deleteTempFile = deleted::add,
                logger = {},
            )

        val shot1 = sampleFile("shot-1")
        val shot2 = sampleFile("shot-2")
        assertTrue(controller.onScreenshotCaptured(shot1, sampleContext, eligible = true))
        assertIs<RaftScreenshotFeedbackState.Prompting>(controller.state.value)

        // Second capture inside debounce window is suppressed and its temp file deleted immediately
        now = 11_000L
        assertFalse(controller.onScreenshotCaptured(shot2, sampleContext, eligible = true))
        assertEquals(listOf(shot2.fileRef), deleted)

        // Still showing just before 7s
        advanceTimeBy(6_999L)
        runCurrent()
        assertIs<RaftScreenshotFeedbackState.Prompting>(controller.state.value)

        // Auto-dismisses at 7s and cleans up shot-1
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(RaftScreenshotFeedbackState.Idle, controller.state.value)
        assertEquals(listOf(shot2.fileRef, shot1.fileRef), deleted)
    }

    @Test
    fun ineligibleCaptureAndEligibilityLossCleanUpAndCancelDiagnostics() = runTest {
        val deleted = mutableListOf<String>()
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { 10_000L },
                deleteTempFile = deleted::add,
                logger = {},
            )

        // Refused when ineligible (signed out / privacy On) -> temp file deleted, stays Idle
        val refusedShot = sampleFile("refused")
        assertFalse(controller.onScreenshotCaptured(refusedShot, sampleContext, eligible = false))
        assertEquals(RaftScreenshotFeedbackState.Idle, controller.state.value)
        assertEquals(listOf(refusedShot.fileRef), deleted)

        // Open sheet with in-flight diagnostics upload, then lose eligibility
        val activeShot = sampleFile("active")
        assertTrue(controller.onScreenshotCaptured(activeShot, sampleContext, eligible = true))
        val gate = CompletableDeferred<FeedbackDiagnosticsReference?>()
        var diagnosticsCancelled = false
        assertTrue(
            controller.openSheet(
                eligible = true,
                startDiagnostics = {
                    try {
                        gate.await()
                    } catch (cancelled: CancellationException) {
                        diagnosticsCancelled = true
                        throw cancelled
                    }
                },
            )
        )
        runCurrent()
        val composing = assertIs<RaftScreenshotFeedbackState.Composing>(controller.state.value)
        assertEquals(RaftScreenshotFeedbackDiagnosticsState.Preparing, composing.diagnosticsState)

        controller.onEligibilityLost()
        runCurrent()
        assertEquals(RaftScreenshotFeedbackState.Idle, controller.state.value)
        assertTrue(diagnosticsCancelled)
        assertEquals(listOf(refusedShot.fileRef, activeShot.fileRef), deleted)
    }

    @Test
    fun detachScreenshotDeletesFileWithoutClosingSheetOrLosingNote() = runTest {
        val deleted = mutableListOf<String>()
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { 10_000L },
                deleteTempFile = deleted::add,
                logger = {},
            )
        val shot = sampleFile("detach-me")
        assertTrue(controller.onScreenshotCaptured(shot, sampleContext, eligible = true))
        assertTrue(controller.openSheet(eligible = true, startDiagnostics = null))
        controller.updateNote("Scrolling freezes here")
        controller.detachScreenshot()

        val state = assertIs<RaftScreenshotFeedbackState.Composing>(controller.state.value)
        assertNull(state.screenshot)
        assertTrue(state.screenshotRemoved)
        assertEquals("Scrolling freezes here", state.note)
        assertEquals(listOf(shot.fileRef), deleted)
    }

    @Test
    fun submitAwaitsPreparedDiagnosticsAttachesMetadataDeletesFileAndCloses() = runTest {
        val deleted = mutableListOf<String>()
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { 10_000L },
                deleteTempFile = deleted::add,
                logger = {},
            )
        val shot = sampleFile("submit-shot")
        assertTrue(controller.onScreenshotCaptured(shot, sampleContext, eligible = true))

        val diagGate = CompletableDeferred<FeedbackDiagnosticsReference?>()
        assertTrue(controller.openSheet(eligible = true, startDiagnostics = { diagGate.await() }))
        runCurrent()

        controller.updateNote("Thread reply button clipped")
        controller.updateFeedbackType(SettingsFeedbackType.Problem)

        val repo = FakeSubmitRepository(mutableListOf(FeedbackResult.Ok("ticket-276")))
        val fallbackDiagCalls = mutableListOf<String>()
        val submitter = buildSubmitter(repo, diagnosticsCalls = fallbackDiagCalls)
        val filedTickets = mutableListOf<String>()

        // Submit while diagnostics is still preparing -> waits for in-flight upload
        controller.submit(eligible = true, submitter = submitter, onFiled = filedTickets::add)
        runCurrent()
        assertTrue(assertIs<RaftScreenshotFeedbackState.Composing>(controller.state.value).submitting)
        assertTrue(repo.requests.isEmpty())

        // Complete diagnostics -> submit proceeds without starting a second diagnostics upload
        diagGate.complete(sampleDiagnostics)
        runCurrent()

        assertEquals(RaftScreenshotFeedbackState.Idle, controller.state.value)
        assertEquals(listOf("ticket-276"), filedTickets)
        assertEquals(listOf(shot.fileRef), deleted)
        assertTrue(fallbackDiagCalls.isEmpty(), "must reuse pre-prepared diagnostics without a second upload")

        val request = repo.requests.single()
        assertEquals(SettingsFeedbackType.Problem, request.type)
        assertEquals(listOf(shot), request.attachments)
        assertEquals(sampleDiagnostics, request.diagnostics)
        assertTrue(request.message.startsWith("Thread reply button clipped"))
        assertTrue(request.message.contains("Source: screenshot_feedback"))
        assertTrue(request.message.contains("Page: compose_thread"))
        assertTrue(request.message.contains("Server: srv-1"))
        assertTrue(request.message.contains("channel=ch-9"))
        assertTrue(request.message.contains("thread=th-42"))
        assertTrue(request.message.contains("Viewport: 1080x2400 portrait"))
        assertTrue(request.message.contains("App: 1.29.0 (1290001) · android"))
    }

    @Test
    fun failedSubmitPreservesTypedNoteAndRetriesWithSameSubmissionId() = runTest {
        val deleted = mutableListOf<String>()
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { 10_000L },
                deleteTempFile = deleted::add,
                logger = {},
            )
        val shot = sampleFile("retry-shot")
        assertTrue(controller.onScreenshotCaptured(shot, sampleContext, eligible = true))
        assertTrue(controller.openSheet(eligible = true, startDiagnostics = { sampleDiagnostics }))
        runCurrent()
        controller.updateNote("Avatar failed to load")

        val repo =
            FakeSubmitRepository(
                mutableListOf(
                    FeedbackResult.Failed(
                        FeedbackFailure(
                            kind = FeedbackErrorKind.Unavailable,
                            code = "feedback_transport",
                            httpStatus = 0,
                            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                        )
                    ),
                    FeedbackResult.Ok("ticket-retry-ok"),
                )
            )
        val submitter = buildSubmitter(repo)
        val filed = mutableListOf<String>()

        controller.submit(eligible = true, submitter = submitter, onFiled = filed::add)
        runCurrent()

        val failedState = assertIs<RaftScreenshotFeedbackState.Composing>(controller.state.value)
        assertFalse(failedState.submitting)
        assertEquals("Avatar failed to load", failedState.note)
        assertEquals(shot, failedState.screenshot)
        assertEquals("settings_feedback_network_failed", failedState.errorCode)
        assertTrue(failedState.errorRetryable)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", failedState.errorTraceId)
        assertTrue(deleted.isEmpty(), "temp file stays available for retry")

        // Retry keeps same submissionId and diagnostics
        controller.submit(eligible = true, submitter = submitter, onFiled = filed::add)
        runCurrent()
        assertEquals(RaftScreenshotFeedbackState.Idle, controller.state.value)
        assertEquals(listOf("ticket-retry-ok"), filed)
        assertEquals(listOf(shot.fileRef), deleted)
        assertEquals(2, repo.requests.size)
        assertEquals(repo.requests[0].submissionId, repo.requests[1].submissionId)
        assertEquals(sampleDiagnostics, repo.requests[1].diagnostics)
    }

    @Test
    fun privacyModeFlipBlocksSubmissionAndKeepsNoNetworkTraffic() = runTest {
        val controller =
            RaftScreenshotFeedbackController(
                scope = backgroundScope,
                nowMillis = { 10_000L },
                deleteTempFile = {},
                logger = {},
            )
        assertTrue(controller.onScreenshotCaptured(null, sampleContext, eligible = true))
        assertTrue(controller.openSheet(eligible = true, startDiagnostics = null))
        controller.updateNote("Something broke")

        val repo = FakeSubmitRepository(mutableListOf(FeedbackResult.Ok("should-not-file")))
        val submitter = buildSubmitter(repo, privacyMode = { RaftPrivacyMode.On })
        controller.submit(eligible = true, submitter = submitter)
        runCurrent()

        val state = assertIs<RaftScreenshotFeedbackState.Composing>(controller.state.value)
        assertEquals(SETTINGS_FEEDBACK_PRIVACY_MODE_ON, state.errorCode)
        assertTrue(repo.requests.isEmpty())
    }

    @Test
    fun extractsPageContextForAppShellAndTaskPages() {
        val shellContext =
            extractScreenshotFeedbackPageContext(
                pageName = "compose_slock_app_shell",
                pageData = buildJsonObject { put("slockShellServerId", "srv-shell") },
                activeTab = "activity",
                viewportWidthPx = 1080,
                viewportHeightPx = 2400,
                appVersion = "1.29.0",
                buildNumber = "42",
                platform = "ios",
            )
        assertEquals("Activity", shellContext.displayPageLabel())
        assertEquals("srv-shell", shellContext.serverId)
        assertEquals("portrait", shellContext.orientation)

        val taskContext =
            extractScreenshotFeedbackPageContext(
                pageName = "compose_task_detail",
                pageData =
                    buildJsonObject {
                        put("serverId", "srv-2")
                        put("channelId", "ch-5")
                        put("taskId", "t-1")
                        put("taskNumber", "276")
                    },
                viewportWidthPx = 2400,
                viewportHeightPx = 1080,
                appVersion = "1.29.0",
                buildNumber = "42",
                platform = "android",
            )
        assertEquals("Task", taskContext.displayPageLabel())
        assertEquals("landscape", taskContext.orientation)
        val block = taskContext.formatSubmissionMetadataBlock()
        assertTrue(block.contains("task=t-1"))
        assertTrue(block.contains("taskNumber=276"))
    }
}
