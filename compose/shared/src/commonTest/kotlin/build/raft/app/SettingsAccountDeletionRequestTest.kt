package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAccountDeletionRequestTest {
    private val details =
        raftAccountDeletionRequestDetails(
            user = StoredAuthUser(id = "user-42", email = "artin@raft.build", name = "artin", displayName = "Artin"),
            about = SharedSettingsAboutSnapshot(versionLabel = "1.18.0", buildLabel = "180", platformLabel = "Android 15"),
            platform = "android",
            requestedAt = "2026-10-31T04:00:00Z",
        )

    @Test
    fun messageStartsWithHeaderAndCarriesTheAccount() {
        val lines = buildRaftAccountDeletionRequestMessage(details).lines()

        assertEquals(RAFT_ACCOUNT_DELETION_REQUEST_HEADER, lines.first())
        val fields = lines.mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        assertEquals("user-42", fields["User ID"])
        assertEquals("artin@raft.build", fields["Email"])
        assertEquals("artin", fields["Username"])
        assertEquals("1.18.0 (180)", fields["App version"])
        assertEquals("android / Android 15", fields["Platform"])
        assertEquals("2026-10-31T04:00:00Z", fields["Requested at"])
    }

    @Test
    fun missingFactsAreMarkedNotDropped() {
        val sparse =
            raftAccountDeletionRequestDetails(
                user = StoredAuthUser(id = "user-1", email = "a@b.c"),
                about = null,
                platform = "iOS",
                requestedAt = "t",
            )
        val fields = buildRaftAccountDeletionRequestMessage(sparse).lines().associate { line ->
            line.substringBefore(": ") to line.substringAfter(": ", "")
        }
        assertEquals("--", fields["Username"])
        assertEquals("--", fields["App version"])
        assertEquals("iOS", fields["Platform"])
    }

    @Test
    fun requestIsSingleFlightAndSuccessClosesTheDialogOnce() = runTest {
        val gate = CompletableDeferred<DataResult<SettingsHandsFeedbackSubmission>>()
        val sent = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        val store = store(this, submitted) { message -> sent += message; gate.await() }

        store.open()
        assertTrue(store.request())
        assertFalse(store.request(), "a second tap while sending is ignored")
        store.dismiss()
        assertEquals(RaftAccountDeletionRequestState.Submitting, store.state.value, "Cancel/Back can't drop a running request")
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertTrue(sent.single().startsWith(RAFT_ACCOUNT_DELETION_REQUEST_HEADER))

        gate.complete(DataResult.Content(SettingsHandsFeedbackSubmission(ticketId = "ticket-1", shareText = "")))
        advanceUntilIdle()
        assertEquals(RaftAccountDeletionRequestState.Idle, store.state.value)
        assertEquals(listOf("ticket-1"), submitted)
    }

    @Test
    fun failureKeepsTheDialogOpenAndRetrySendsAgain() = runTest {
        val results =
            ArrayDeque<DataResult<SettingsHandsFeedbackSubmission>>(
                listOf(
                    DataResult.Error(ScreenError("network_unreachable", retryable = true)),
                    DataResult.Content(SettingsHandsFeedbackSubmission(ticketId = "ticket-2", shareText = "")),
                )
            )
        var sends = 0
        val submitted = mutableListOf<String>()
        val store = store(this, submitted) { sends += 1; results.removeFirst() }

        store.open()
        store.request()
        advanceUntilIdle()
        assertEquals(RaftAccountDeletionRequestState.Failed("network_unreachable"), store.state.value)
        assertTrue(submitted.isEmpty())

        assertTrue(store.request(), "the same button retries")
        advanceUntilIdle()
        assertEquals(2, sends)
        assertEquals(RaftAccountDeletionRequestState.Idle, store.state.value)
        assertEquals(listOf("ticket-2"), submitted)
    }

    @Test
    fun requestWithTheDialogClosedSendsNothing() = runTest {
        var sends = 0
        val store = store(this, mutableListOf()) { sends += 1; error("not expected") }

        assertFalse(store.request())
        advanceUntilIdle()
        assertEquals(0, sends)
        assertEquals(RaftAccountDeletionRequestState.Idle, store.state.value)
    }

    private fun store(
        scope: TestScope,
        submitted: MutableList<String>,
        submit: suspend (String) -> DataResult<SettingsHandsFeedbackSubmission>,
    ) = RaftAccountDeletionRequestStore(
        scope = scope,
        loadDetails = { details },
        submit = submit,
        onSubmitted = { submitted += it },
    )
}
