package build.raft.app.data

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `code` of the 403 a person gets when an organization requires single sign-on and this session isn't one. */
internal const val SSO_REQUIRED_ERROR_CODE: String = "SSO_REQUIRED"

/**
 * The 403 body of botiverse/slock `requireAuth` (`packages/server/src/middleware/auth.ts`,
 * `respondSsoRequired`) when a Server that requires SSO manages this person and the session was
 * not signed in with its SSO, or the sign-in is older than its max age (rfcs/083-server-sso.md
 * §6.2): `{ error, code: "SSO_REQUIRED", signInUrl }`. Every API but sign-out answers it. The
 * socket handshake fails with the message `SSO_REQUIRED` too.
 */
@Serializable
internal data class SsoRequiredErrorBody(
    val error: String? = null,
    val code: String? = null,
    val signInUrl: String? = null,
)

private val ssoRequiredJson = Json { ignoreUnknownKeys = true }

/** The SSO-required body of a 403, or null for any other response. */
internal fun parseSsoRequiredError(code: Int, errorBodyText: String?): SsoRequiredErrorBody? {
    if (code != 403) return null
    val text = errorBodyText?.takeIf { it.contains(SSO_REQUIRED_ERROR_CODE) } ?: return null
    val body =
        runCatching { ssoRequiredJson.decodeFromString(SsoRequiredErrorBody.serializer(), text) }
            .getOrNull() ?: return null
    return body.takeIf { it.code == SSO_REQUIRED_ERROR_CODE }
}

/**
 * Global auth-state signal: the person's organization requires single sign-on and this session
 * doesn't satisfy it, so the app signs out and asks them to sign in again. Signing in goes through
 * the Raft web sign-in page, which offers "Continue with SSO" and hands the app a session that
 * carries the SSO sign-in. Fed by the shared HTTP layer ([SharedRaftNetworkClient] and the auth
 * transport) for every response; consumed by the app shell.
 *
 * Once only, like [RaftAccountDeletionPendingSignal]: the first 403 queues one sign-in request and
 * later ones are ignored until [reset] (the session was cleared). Delivered to a single collector.
 */
internal class RaftSsoRequiredSignal {
    private val latched = atomic(false)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** One element per sign-in request; each is received by one collector only. */
    val signInRequests: Flow<Unit> = requests.receiveAsFlow()

    /** Returns true when this response queued the sign-in request. */
    fun observe(code: Int, errorBodyText: String?): Boolean {
        if (code != 403 || latched.value) return false
        parseSsoRequiredError(code, errorBodyText) ?: return false
        if (!latched.compareAndSet(expect = false, update = true)) return false
        requests.trySend(Unit)
        return true
    }

    /** The session ended: a later sign-in may ask again; a queued request is dropped. */
    fun reset() {
        requests.tryReceive()
        latched.value = false
    }

    companion object {
        /** The process signal every shared network client reports to. */
        val Process: RaftSsoRequiredSignal = RaftSsoRequiredSignal()
    }
}

/** A socket handshake error that means SSO is required: never a reason to refresh the token and retry. */
fun isSsoRequiredSocketError(message: String?): Boolean = message?.contains(SSO_REQUIRED_ERROR_CODE) == true
