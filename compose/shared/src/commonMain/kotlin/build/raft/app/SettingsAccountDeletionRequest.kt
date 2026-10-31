package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.StoredAuthUser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings › Account › Delete account (App Store Guideline 5.1.1(v)).
 *
 * Interim flow until the server deletion endpoint exists: the request goes out as a Hands
 * feedback ticket that support handles by hand. The user stays signed in.
 */

/** Days until a requested deletion is done. The dialog and the success toast both read it. */
internal const val RAFT_ACCOUNT_DELETION_DAYS = 30

/** First line of every deletion ticket, so support can grep for them. */
internal const val RAFT_ACCOUNT_DELETION_REQUEST_HEADER = "[ACCOUNT DELETION REQUEST]"

internal data class RaftAccountDeletionRequestDetails(
    val userId: String,
    val email: String,
    val username: String,
    val displayName: String,
    val appVersion: String,
    val platform: String,
    val requestedAt: String,
)

/** Reads only what the device already holds: the stored session user and the host's About facts. */
internal fun raftAccountDeletionRequestDetails(
    user: StoredAuthUser,
    about: SharedSettingsAboutSnapshot?,
    platform: String,
    requestedAt: String,
): RaftAccountDeletionRequestDetails =
    RaftAccountDeletionRequestDetails(
        userId = user.id,
        email = user.email,
        username = user.name.orEmpty(),
        displayName = user.displayName.orEmpty(),
        appVersion =
            listOfNotNull(
                about?.versionLabel?.takeIf(String::isNotBlank),
                about?.buildLabel?.takeIf(String::isNotBlank)?.let { "($it)" },
            ).joinToString(" "),
        platform =
            listOfNotNull(
                platform.takeIf(String::isNotBlank),
                about?.platformLabel?.takeIf { it.isNotBlank() && !it.equals(platform, ignoreCase = true) },
            ).joinToString(" / "),
        requestedAt = requestedAt,
    )

internal fun buildRaftAccountDeletionRequestMessage(details: RaftAccountDeletionRequestDetails): String =
    buildString {
        appendLine(RAFT_ACCOUNT_DELETION_REQUEST_HEADER)
        appendLine()
        appendLine("The signed-in user asked to delete their account from Settings › Account.")
        appendLine("Complete within $RAFT_ACCOUNT_DELETION_DAYS days and email the user when done.")
        appendLine()
        appendDeletionLine("User ID", details.userId)
        appendDeletionLine("Email", details.email)
        appendDeletionLine("Username", details.username)
        appendDeletionLine("Display name", details.displayName)
        appendDeletionLine("App version", details.appVersion)
        appendDeletionLine("Platform", details.platform)
        appendDeletionLine("Requested at", details.requestedAt)
    }.trimEnd()

private fun StringBuilder.appendDeletionLine(label: String, value: String) {
    append(label)
    append(": ")
    appendLine(value.trim().ifBlank { "--" })
}

/** The confirm dialog's state; [Idle] means it is closed. */
internal sealed interface RaftAccountDeletionRequestState {
    data object Idle : RaftAccountDeletionRequestState

    data object Confirming : RaftAccountDeletionRequestState

    data object Submitting : RaftAccountDeletionRequestState

    data class Failed(val code: String) : RaftAccountDeletionRequestState
}

/**
 * Single-flight sender for the deletion ticket. [request] while one is running is ignored;
 * a failure keeps the dialog open with an error and the same button retries. Success closes
 * the dialog and calls [onSubmitted] once (the toast).
 */
internal class RaftAccountDeletionRequestStore(
    private val scope: CoroutineScope,
    private val loadDetails: suspend () -> RaftAccountDeletionRequestDetails?,
    private val submit: suspend (message: String) -> DataResult<SettingsHandsFeedbackSubmission>,
    private val onSubmitted: (ticketId: String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<RaftAccountDeletionRequestState>(RaftAccountDeletionRequestState.Idle)
    val state: StateFlow<RaftAccountDeletionRequestState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun open() {
        if (mutableState.value == RaftAccountDeletionRequestState.Idle) {
            mutableState.value = RaftAccountDeletionRequestState.Confirming
        }
    }

    /** Returns false when the tap is ignored: a request is already running or the dialog is closed. */
    fun request(): Boolean {
        val current = mutableState.value
        if (job?.isActive == true || current == RaftAccountDeletionRequestState.Submitting) {
            log("settings_account_deletion_request_ignored reason=busy")
            return false
        }
        if (current == RaftAccountDeletionRequestState.Idle) return false
        mutableState.value = RaftAccountDeletionRequestState.Submitting
        log("settings_account_deletion_request_start")
        job =
            scope.launch {
                val outcome: Result<String> =
                    try {
                        val details = loadDetails()
                        if (details == null || details.userId.isBlank()) {
                            Result.failure(DeletionRequestFailure("settings_account_session_missing"))
                        } else {
                            when (val result = submit(buildRaftAccountDeletionRequestMessage(details))) {
                                is DataResult.Content -> Result.success(result.data.ticketId)
                                is DataResult.Error -> Result.failure(DeletionRequestFailure(result.error.code))
                                is DataResult.Empty -> Result.failure(DeletionRequestFailure("settings_feedback_unsupported"))
                                is DataResult.NoPermission -> Result.failure(DeletionRequestFailure("settings_feedback_no_permission"))
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        mutableState.value = RaftAccountDeletionRequestState.Confirming
                        throw cancellation
                    } catch (throwable: Throwable) {
                        Result.failure(DeletionRequestFailure(throwable::class.simpleName ?: "settings_account_deletion_failed"))
                    }
                outcome.fold(
                    onSuccess = { ticketId ->
                        log("settings_account_deletion_request_result status=ok ticket=${ticketId.take(8)}")
                        mutableState.value = RaftAccountDeletionRequestState.Idle
                        onSubmitted(ticketId)
                    },
                    onFailure = { failure ->
                        val code = (failure as? DeletionRequestFailure)?.code ?: "settings_account_deletion_failed"
                        log("settings_account_deletion_request_result status=failed code=$code")
                        mutableState.value = RaftAccountDeletionRequestState.Failed(code)
                    }
                )
            }
        return true
    }

    /** Cancel / Back. Ignored while the request runs, so the result is never lost. */
    fun dismiss() {
        if (mutableState.value != RaftAccountDeletionRequestState.Submitting) {
            mutableState.value = RaftAccountDeletionRequestState.Idle
        }
    }

    private class DeletionRequestFailure(val code: String) : Exception(code)
}
