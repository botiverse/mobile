package build.raft.app.data

import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.host.HostSettingsCapability
import build.raft.app.optString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Settings form's send through the Raft server: one diagnostics upload per feedback, kept with
 * its submission id across retries (web createDiagnosticsSubmission), refused diagnostics omitted
 * once, the native form when the server cannot file, and the host's diagnostics stream.
 */
class SettingsFeedbackProxySubmitterTest {
    private val ref =
        FeedbackDiagnosticsReference(
            r2Key = "feedback/raft-ios/presigned/11111111-2222-4333-8444-555555555555-slock-feedback.zip",
            filename = "slock-feedback.zip",
            contentType = "application/zip",
            size = 2048L,
        )

    private class FakeRepository(private val answers: MutableList<FeedbackResult<String>>) : SettingsFeedbackSubmitRepository {
        val requests = mutableListOf<SettingsFeedbackSubmitRequest>()

        override suspend fun submit(request: SettingsFeedbackSubmitRequest): FeedbackResult<String> {
            requests += request
            return answers.removeAt(0)
        }
    }

    private fun failed(kind: FeedbackErrorKind, code: String, status: Int) =
        FeedbackResult.Failed(FeedbackFailure(kind, code, status))

    private fun submitter(repository: FakeRepository, uploads: MutableList<String>, answer: FeedbackDiagnosticsReference? = ref): SettingsFeedbackProxySubmitter {
        var ids = 0
        return SettingsFeedbackProxySubmitter(
            repository = repository,
            diagnostics = SettingsFeedbackDiagnosticsUploader { accountId -> uploads += accountId; answer },
            accountId = "user-1",
            locale = { "ja" },
            newSubmissionId = { "submission-${++ids}" },
        )
    }

    private val image = PlatformFile(id = "a", fileName = "shot.webp", mimeType = "image/webp", fileRef = "draft://a")

    @Test
    fun filesWithTheUploadedDiagnostics() = runTest {
        val repository = FakeRepository(mutableListOf(FeedbackResult.Ok("ticket-1")))
        val uploads = mutableListOf<String>()
        val submitter = submitter(repository, uploads)

        val outcome = submitter.submit(submitter.newAttempt("It froze", listOf(image)))

        assertEquals(SettingsFeedbackProxyOutcome.Filed("ticket-1"), outcome)
        assertEquals(listOf("user-1"), uploads)
        val sent = repository.requests.single()
        assertEquals("submission-1", sent.submissionId)
        assertEquals(ref, sent.diagnostics)
        assertEquals("ja", sent.locale)
        assertEquals(listOf(image), sent.attachments)
    }

    @Test
    fun theFormsTypeChoiceIsSentWithTheAttempt() = runTest {
        val repository = FakeRepository(mutableListOf(FeedbackResult.Ok("ticket-1")))
        val submitter = submitter(repository, mutableListOf())

        submitter.submit(submitter.newAttempt("Dark mode for the composer", emptyList(), SettingsFeedbackType.Idea))

        assertEquals(SettingsFeedbackType.Idea, repository.requests.single().type)
    }

    @Test
    fun aRetryKeepsTheSubmissionIdAndNeverUploadsAgain() = runTest {
        val repository =
            FakeRepository(
                mutableListOf(
                    failed(FeedbackErrorKind.Unavailable, "feedback_transport", 0),
                    FeedbackResult.Ok("ticket-1"),
                )
            )
        val uploads = mutableListOf<String>()
        val submitter = submitter(repository, uploads)

        val first = assertIs<SettingsFeedbackProxyOutcome.Failed>(submitter.submit(submitter.newAttempt("It froze", emptyList())))
        assertEquals("settings_feedback_network_failed", first.code)
        assertTrue(first.retryable)
        val retry = first.retry ?: error("a network failure can be retried")
        assertEquals(SettingsFeedbackProxyOutcome.Filed("ticket-1"), submitter.submit(retry))

        assertEquals(1, uploads.size)
        assertEquals(listOf("submission-1", "submission-1"), repository.requests.map { it.submissionId })
        assertEquals(listOf(SettingsFeedbackType.Problem, SettingsFeedbackType.Problem), repository.requests.map { it.type })
        assertEquals(listOf(ref, ref), repository.requests.map { it.diagnostics })
    }

    @Test
    fun refusedDiagnosticsAreOmittedUnderANewSubmissionId() = runTest {
        val repository =
            FakeRepository(
                mutableListOf(
                    failed(FeedbackErrorKind.Unavailable, "feedback_diagnostics_rejected", 400),
                    FeedbackResult.Ok("ticket-2"),
                )
            )
        val submitter = submitter(repository, mutableListOf())

        val outcome = submitter.submit(submitter.newAttempt("It froze", emptyList()))

        assertEquals(SettingsFeedbackProxyOutcome.Filed("ticket-2"), outcome)
        assertEquals(listOf(ref, null), repository.requests.map { it.diagnostics })
        assertNotEquals(repository.requests[0].submissionId, repository.requests[1].submissionId)
    }

    @Test
    fun aServerThatCannotFileHandsOverToTheNativeForm() = runTest {
        listOf(
            failed(FeedbackErrorKind.Unavailable, "feedback_integration_unavailable", 503),
            failed(FeedbackErrorKind.NotSupported, "feedback_http_404", 404),
        ).forEach { answer ->
            val submitter = submitter(FakeRepository(mutableListOf(answer)), mutableListOf())

            assertSame(SettingsFeedbackProxyOutcome.UseNativeForm, submitter.submit(submitter.newAttempt("x", emptyList())))
        }
    }

    private val card =
        SettingsFeedbackCardDraft(
            messageId = "0b6f3c8e-5d1a-4c2e-9f7a-1e2d3c4b5a69",
            serverId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            type = SettingsFeedbackType.Problem,
            message = "Search is slow",
            confirmationVersion = 2L,
        )

    @Test
    fun aCardsAttemptIsSentAsTheCardAndItsRefusalsAreFinal() = runTest {
        val repository = FakeRepository(mutableListOf(FeedbackResult.Ok("ticket-1")))
        val submitter = submitter(repository, mutableListOf())
        assertEquals(SettingsFeedbackProxyOutcome.Filed("ticket-1"), submitter.submit(submitter.newAttempt("Search is slow", emptyList(), card.type, card)))
        assertEquals(card, repository.requests.single().card)

        // web FeedbackCardForm REFUSALS, by the server's code; none can be retried.
        mapOf(
            failed(FeedbackErrorKind.Unavailable, "feedback_card_already_sent", 409) to SETTINGS_FEEDBACK_CARD_ALREADY_SENT,
            failed(FeedbackErrorKind.Unavailable, "STATE_MISMATCH", 409) to SETTINGS_FEEDBACK_CARD_NOT_WAITING,
            failed(FeedbackErrorKind.Unavailable, "CONFIRMATION_VERSION_MISMATCH", 409) to SETTINGS_FEEDBACK_CARD_CHANGED,
            failed(FeedbackErrorKind.Unavailable, "action_card_conversion_frozen", 409) to SETTINGS_FEEDBACK_CARD_CHANGED,
            failed(FeedbackErrorKind.Unauthorized, "FEATURE_DISABLED", 403) to SETTINGS_FEEDBACK_CARD_UNAVAILABLE,
            failed(FeedbackErrorKind.NotSupported, "NOT_FEEDBACK_CARD", 404) to SETTINGS_FEEDBACK_CARD_UNAVAILABLE,
            failed(FeedbackErrorKind.Unauthorized, "NOT_A_MEMBER", 403) to SETTINGS_FEEDBACK_CARD_NOT_ALLOWED,
        ).forEach { (answer, code) ->
            val cardSubmitter = submitter(FakeRepository(mutableListOf(answer)), mutableListOf())
            val outcome = assertIs<SettingsFeedbackProxyOutcome.Failed>(cardSubmitter.submit(cardSubmitter.newAttempt("x", emptyList(), card = card)))
            assertEquals(code, outcome.code, answer.failure.code)
            assertNull(outcome.retry, answer.failure.code)
        }
    }

    @Test
    fun aCardsSendNeverHandsOverToTheAnonymousNativeForm() = runTest {
        // The native Hands form cannot complete the card: the server is unavailable, retry later.
        listOf(
            failed(FeedbackErrorKind.Unavailable, "feedback_integration_unavailable", 503),
            failed(FeedbackErrorKind.NotSupported, "feedback_http_404", 404),
        ).forEach { answer ->
            val submitter = submitter(FakeRepository(mutableListOf(answer)), mutableListOf())
            val outcome = assertIs<SettingsFeedbackProxyOutcome.Failed>(submitter.submit(submitter.newAttempt("x", emptyList(), card = card)))
            assertEquals("settings_feedback_server_unavailable", outcome.code)
            assertEquals(card, outcome.retry?.card)
        }
    }

    @Test
    fun aNonImageAttachmentBlocksBeforeAnyUpload() = runTest {
        val repository = FakeRepository(mutableListOf())
        val uploads = mutableListOf<String>()
        val submitter = submitter(repository, uploads)
        val pdf = PlatformFile(id = "b", fileName = "report.pdf", mimeType = "application/pdf", fileRef = "draft://b")

        val outcome = assertIs<SettingsFeedbackProxyOutcome.Failed>(submitter.submit(submitter.newAttempt("x", listOf(image, pdf))))

        assertEquals(SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID, outcome.code)
        assertNull(outcome.retry)
        assertTrue(uploads.isEmpty())
        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun mapsServerFailuresToTheFormsCodes() = runTest {
        data class Case(val answer: FeedbackResult.Failed, val code: String, val retryable: Boolean)
        listOf(
            Case(FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.RateLimited, "feedback_rate_limited", 429, retryAfterSeconds = 20)), "settings_feedback_rate_limited", true),
            Case(failed(FeedbackErrorKind.Invalid, "feedback_attachment_too_large", 413), "settings_feedback_attachment_too_large", false),
            Case(failed(FeedbackErrorKind.Invalid, "feedback_attachment_type_invalid", 400), SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID, false),
            Case(failed(FeedbackErrorKind.Unauthorized, "feedback_http_403", 403), "settings_feedback_auth_failed", false),
            Case(failed(FeedbackErrorKind.Unavailable, "feedback_submit_failed", 502), "settings_feedback_server_unavailable", true),
            Case(failed(FeedbackErrorKind.Conflict, "feedback_submission_conflict", 409), "settings_feedback_submit_failed", true),
        ).forEach { case ->
            val submitter = submitter(FakeRepository(mutableListOf(case.answer)), mutableListOf())
            val outcome = assertIs<SettingsFeedbackProxyOutcome.Failed>(submitter.submit(submitter.newAttempt("x", emptyList())))
            assertEquals(case.code, outcome.code, "for ${case.answer.failure.code}")
            assertEquals(case.retryable, outcome.retryable, "for ${case.answer.failure.code}")
            if (case.answer.failure.kind == FeedbackErrorKind.RateLimited) assertEquals(20L, outcome.retryAfterSeconds)
            if (case.answer.failure.kind == FeedbackErrorKind.Conflict) {
                // Hands holds the old id for another payload; a retry is a new submission.
                assertNotEquals("submission-1", outcome.retry?.submissionId)
            }
        }
    }

    @Test
    fun noArchiveStillFilesTheFeedback() = runTest {
        val repository = FakeRepository(mutableListOf(FeedbackResult.Ok("ticket-3")))
        val submitter = submitter(repository, mutableListOf(), answer = null)

        assertEquals(SettingsFeedbackProxyOutcome.Filed("ticket-3"), submitter.submit(submitter.newAttempt("x", emptyList())))
        assertNull(repository.requests.single().diagnostics)
    }

    /** The host's `feedbackDiagnosticsStart` stream (Android RaftCmpActivity / iOS HRBridgeModule). */
    private class FakeDiagnosticsHost(private val events: (JsonObject) -> List<JsonObject>) : HostSettingsCapability {
        val commands = mutableListOf<JsonObject>()

        override suspend fun settingsCapability(command: JsonObject): JsonObject? = error("diagnostics use the stream")

        override fun settingsCapabilityStream(command: JsonObject, onEvent: (JsonObject?) -> Unit): () -> Unit {
            commands += command
            if (command.optString("action") == "feedbackDiagnosticsStart") events(command).forEach(onEvent)
            return {}
        }
    }

    private fun event(command: JsonObject, type: String, terminal: Boolean, requestId: String = command.optString("requestId"), extra: JsonObject? = null) =
        buildJsonObject {
            put("status", "ok")
            put("requestId", requestId)
            put("binding", buildJsonObject {
                put("accountId", command.optString("accountId"))
                put("sessionGeneration", command["sessionGeneration"]!!)
                put("webViewGeneration", command["webViewGeneration"]!!)
            })
            put("type", type)
            put("terminal", terminal)
            extra?.let { put("ref", it) }
        }

    private val refJson =
        buildJsonObject {
            put("r2_key", ref.r2Key)
            put("filename", ref.filename)
            put("content_type", ref.contentType)
            put("size", ref.size)
        }

    @Test
    fun theHostStreamAnswersItsReferenceAndIgnoresOtherRequests() = runTest {
        val host =
            FakeDiagnosticsHost { command ->
                listOf(
                    event(command, "done", terminal = true, requestId = "someone-else", extra = refJson),
                    event(command, "progress", terminal = false),
                    event(command, "done", terminal = true, extra = refJson),
                )
            }

        val answer = HostSettingsFeedbackDiagnosticsUploader(host).upload("user-1")

        assertEquals(ref, answer)
        val start = host.commands.single()
        assertEquals("feedbackDiagnosticsStart", start.optString("action"))
        assertEquals("user-1", start.optString("accountId"))
    }

    @Test
    fun aFailedOrStalledUploadAttachesNothing() = runTest {
        val failedHost = FakeDiagnosticsHost { command -> listOf(event(command, "failed", terminal = true)) }
        assertNull(HostSettingsFeedbackDiagnosticsUploader(failedHost).upload("user-1"))
        assertEquals(listOf("feedbackDiagnosticsStart"), failedHost.commands.map { it.optString("action") })

        val stalledHost = FakeDiagnosticsHost { command -> listOf(event(command, "progress", terminal = false)) }
        assertNull(HostSettingsFeedbackDiagnosticsUploader(stalledHost, stallMillis = 1_000L).upload("user-1"))
        // A stalled upload is stopped, as the web skips it.
        assertEquals(listOf("feedbackDiagnosticsStart", "feedbackDiagnosticsCancel"), stalledHost.commands.map { it.optString("action") })
    }

    @Test
    fun aHostWithoutTheDiagnosticsActionAttachesNothingAtOnce() = runTest {
        // The Compose desktop bridge answers an unknown settings action with `{code: -1, message}`.
        val desktopHost =
            FakeDiagnosticsHost {
                listOf(buildJsonObject { put("code", -1); put("message", "Method not implemented") })
            }
        assertNull(HostSettingsFeedbackDiagnosticsUploader(desktopHost, stallMillis = Long.MAX_VALUE).upload("user-1"))
        assertEquals(listOf("feedbackDiagnosticsStart"), desktopHost.commands.map { it.optString("action") })
        assertEquals(0L, testScheduler.currentTime)
    }
}
