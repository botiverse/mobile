package build.raft.app.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `code` of the 403 a person with a scheduled account deletion gets from every other API. */
internal const val ACCOUNT_DELETION_PENDING_ERROR_CODE: String = "account_deletion_pending"

/**
 * The 403 body of botiverse/slock `requireAuth` (`packages/server/src/middleware/auth.ts`) while the
 * account is scheduled for deletion: `{ error, code: "account_deletion_pending", scheduledFor }`.
 * Only GET/DELETE `/api/me/account/deletion` (read and cancel) still pass.
 */
@Serializable
internal data class AccountDeletionPendingErrorBody(
    val error: String? = null,
    val code: String? = null,
    val scheduledFor: String? = null,
)

private val accountDeletionPendingJson = Json { ignoreUnknownKeys = true }

/** The pending-deletion body of a 403, or null for any other response. */
internal fun parseAccountDeletionPendingError(code: Int, errorBodyText: String?): AccountDeletionPendingErrorBody? {
    if (code != 403) return null
    val text = errorBodyText?.takeIf { it.contains(ACCOUNT_DELETION_PENDING_ERROR_CODE) } ?: return null
    val body =
        runCatching { accountDeletionPendingJson.decodeFromString(AccountDeletionPendingErrorBody.serializer(), text) }
            .getOrNull() ?: return null
    return body.takeIf { it.code == ACCOUNT_DELETION_PENDING_ERROR_CODE }
}

/**
 * Global auth-state signal: the account is scheduled for deletion, so the app should open the web
 * deletion page (Settings > Account > Delete account), which then shows the pending state and its
 * Cancel. Fed by the shared HTTP layer ([SharedRaftNetworkClient] and the auth transport) for every
 * response; consumed by the app shell.
 *
 * Once only: the first pending 403 queues one open request and every later one is ignored until
 * [reset] (the session was cleared), so a screen full of failing requests opens the page once and
 * closing it does not bring it back. The open request is delivered to a single collector.
 */
internal class RaftAccountDeletionPendingSignal {
    private val latched = atomic(false)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** One element per open request; each is received by one collector only. */
    val openRequests: Flow<Unit> = requests.receiveAsFlow()

    /** Returns true when this response queued the open request. */
    fun observe(code: Int, errorBodyText: String?): Boolean {
        if (code != 403 || latched.value) return false
        parseAccountDeletionPendingError(code, errorBodyText) ?: return false
        if (!latched.compareAndSet(expect = false, update = true)) return false
        requests.trySend(Unit)
        return true
    }

    /** The session ended: a later sign-in may open the page again; a queued request is dropped. */
    fun reset() {
        requests.tryReceive()
        latched.value = false
    }

    companion object {
        /** The process signal every shared network client reports to. */
        val Process: RaftAccountDeletionPendingSignal = RaftAccountDeletionPendingSignal()
    }
}
