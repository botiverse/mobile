package build.raft.app.data

import build.raft.app.base.putIfAbsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AuthLossSignal(
    val source: String,
    val requestId: String? = null,
    val tokenFingerprint: String,
    val capturedSession: StoredAuthSession? = null,
    val pendingOpenRequest: AuthPendingOpenRequest? = null
)

internal sealed interface AuthPendingOpenRequest {
    val serverId: String
    val accountPrincipalId: String

    data class Thread(
        override val serverId: String,
        override val accountPrincipalId: String,
        val surfaceKind: String,
        val channelKind: String?,
        val threadId: String?,
        val sourceThreadId: String?,
        val streamChannelId: String?,
        val parentChannelId: String?,
        val parentMessageId: String?,
        val parentSurfaceKind: String?,
        val parentChannelKind: String?,
        val parentNavigationHint: String?,
        val originParentMessageId: String?,
        val focusedMessageId: String?,
        val selectedTab: String
    ) : AuthPendingOpenRequest
}

internal sealed interface AuthLossDecision {
    data class SessionPreserved(val reason: String) : AuthLossDecision
    data class AuthRequired(val reason: String) : AuthLossDecision
}

internal data class AuthLossResolution(
    val signal: AuthLossSignal,
    val decision: AuthLossDecision,
    val pendingOpenRequest: AuthPendingOpenRequest? = null,
)

internal class SharedAuthLossFunnel(
    private val restoreSession: suspend () -> SharedAuthResult<StoredAuthSession>,
    private val clearSessionIfMatches: suspend (StoredAuthSession, String, String) -> AuthSessionClearOutcome,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    private data class AuthorityKey(
        val accountId: String?,
        val accessToken: String?,
        val refreshToken: String?,
        val fallbackFingerprint: String?,
    )

    private data class InFlight(
        val signal: AuthLossSignal,
        val decision: Deferred<AuthLossDecision>
    )

    private data class PendingOpenRequestRecord(
        val authorityKey: AuthorityKey,
        val request: AuthPendingOpenRequest,
    )

    private val mutex = Mutex()
    private val inFlightByAuthority = mutableMapOf<AuthorityKey, InFlight>()
    private val pendingCandidatesByAuthority = mutableMapOf<AuthorityKey, AuthPendingOpenRequest>()
    private var pendingOpenRequest: PendingOpenRequestRecord? = null
    private val _resolutions = MutableSharedFlow<AuthLossResolution>(extraBufferCapacity = 8)
    val resolutions: SharedFlow<AuthLossResolution> = _resolutions.asSharedFlow()

    fun report(signal: AuthLossSignal): Deferred<AuthLossDecision> =
        scope.async {
            resolve(signal.normalized())
        }

    suspend fun consumePendingOpenRequest(
        serverId: String,
        currentAccountPrincipalId: String,
    ): AuthPendingOpenRequest? =
        mutex.withLock {
            val pending = pendingOpenRequest ?: return@withLock null
            val principalId = currentAccountPrincipalId.trim()
            if (principalId.isBlank() || pending.request.accountPrincipalId != principalId) {
                pendingOpenRequest = null
                return@withLock null
            }
            pending
                .takeIf { it.request.serverId == serverId.trim() }
                ?.also { pendingOpenRequest = null }
                ?.request
        }

    internal suspend fun peekPendingOpenRequest(): AuthPendingOpenRequest? =
        mutex.withLock { pendingOpenRequest?.request }

    internal suspend fun activatePendingOpenRequest(resolution: AuthLossResolution) {
        val request = resolution.pendingOpenRequest ?: return
        if (resolution.decision !is AuthLossDecision.AuthRequired) return
        val expected =
            PendingOpenRequestRecord(
                authorityKey = resolution.signal.authorityKey(),
                request = request,
            )
        mutex.withLock {
            pendingOpenRequest = expected
        }
    }

    internal suspend fun discardPendingOpenRequest(resolution: AuthLossResolution) {
        val request = resolution.pendingOpenRequest ?: return
        val authorityKey = resolution.signal.authorityKey()
        mutex.withLock {
            if (
                pendingOpenRequest?.authorityKey == authorityKey &&
                pendingOpenRequest?.request == request
            ) {
                pendingOpenRequest = null
            }
        }
    }

    private suspend fun resolve(signal: AuthLossSignal): AuthLossDecision {
        val authorityKey = signal.authorityKey()
        val (inFlight, owner) =
            mutex.withLock {
                signal.pendingOpenRequest?.let { request ->
                    pendingCandidatesByAuthority.putIfAbsent(authorityKey, request)
                }
                val existing = inFlightByAuthority[authorityKey]
                if (existing != null) {
                    existing to false
                } else {
                    val created =
                        InFlight(
                            signal = signal,
                            decision = scope.async { resolveOnce(signal) }
                        )
                    inFlightByAuthority[authorityKey] = created
                    created to true
                }
            }
        if (!owner) {
            diagnosticsLogger(
                "auth_loss_join source=${signal.source} token=${signal.tokenFingerprint} " +
                    "ownerSource=${inFlight.signal.source}"
            )
        }
        val decision = inFlight.decision.await()
        if (owner) {
            val selectedPendingOpenRequest =
                mutex.withLock {
                    if (inFlightByAuthority[authorityKey] === inFlight) {
                        inFlightByAuthority.remove(authorityKey)
                    }
                    val candidate = pendingCandidatesByAuthority.remove(authorityKey)
                    candidate.takeIf { decision is AuthLossDecision.AuthRequired }
                }
            _resolutions.emit(
                AuthLossResolution(
                    signal = inFlight.signal,
                    decision = decision,
                    pendingOpenRequest = selectedPendingOpenRequest,
                )
            )
        }
        return decision
    }

    private fun AuthLossSignal.authorityKey(): AuthorityKey {
        val session = capturedSession
        return if (session != null) {
            AuthorityKey(
                accountId = session.user.id,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                fallbackFingerprint = null,
            )
        } else {
            AuthorityKey(
                accountId = null,
                accessToken = null,
                refreshToken = null,
                fallbackFingerprint = tokenFingerprint,
            )
        }
    }

    private suspend fun resolveOnce(signal: AuthLossSignal): AuthLossDecision {
        diagnosticsLogger(
            "auth_loss_restore_start source=${signal.source} " +
                "requestId=${signal.requestId ?: "none"} token=${signal.tokenFingerprint}"
        )
        val result =
            runCatchingPreservingCancellation { restoreSession() }
                .getOrElse { throwable ->
                    diagnosticsLogger(
                        "auth_loss_restore_exception source=${signal.source} " +
                            "error=${throwable::class.simpleName.orEmpty()} decision=session_preserved"
                    )
                    return AuthLossDecision.SessionPreserved("restore_exception")
                }
        return when (result) {
            is SharedAuthResult.Success ->
                AuthLossDecision.SessionPreserved("restore_success")
            is SharedAuthResult.Failure -> {
                diagnosticsLogger(
                    "auth_loss_restore_failure source=${signal.source} code=${result.code} " +
                        "decision=session_preserved"
                )
                AuthLossDecision.SessionPreserved("restore_failure_${result.code.name.lowercase()}")
            }
            SharedAuthResult.Unauthenticated -> {
                val capturedSession = signal.capturedSession
                val authorityStillOwned =
                    if (capturedSession != null) {
                        val cleared =
                            runCatchingPreservingCancellation {
                                clearSessionIfMatches(
                                    capturedSession,
                                    "auth_loss_unavailable",
                                    "auth_loss_funnel:${signal.source}"
                                )
                            }.onFailure { throwable ->
                                diagnosticsLogger(
                                    "auth_loss_clear_exception source=${signal.source} " +
                                        "error=${throwable::class.simpleName.orEmpty()}"
                                )
                            }.getOrNull()
                        cleared != AuthSessionClearOutcome.AuthorityChanged
                    } else {
                        true
                    }
                if (authorityStillOwned) {
                    AuthLossDecision.AuthRequired("restore_unauthenticated")
                } else {
                    AuthLossDecision.SessionPreserved("auth_authority_changed")
                }
            }
        }.also { decision ->
            diagnosticsLogger(
                "auth_loss_restore_result source=${signal.source} " +
                    "decision=${decision.diagnosticsName()}"
            )
        }
    }
}

internal fun String?.authLossTokenFingerprint(): String {
    val token = this?.takeIf { it.isNotBlank() } ?: return "missing"
    val hash = (token.hashCode().toLong() and 0xffffffffL).toString(16)
    return "len=${token.length}:hash=$hash"
}

private fun AuthLossSignal.normalized(): AuthLossSignal =
    copy(
        source = source.diagnosticsToken(),
        requestId = requestId?.diagnosticsToken()?.takeIf { it != "unknown" },
        tokenFingerprint = tokenFingerprint.diagnosticsToken()
    )

private fun String.diagnosticsToken(): String =
    trim()
        .ifBlank { "unknown" }
        .replace(Regex("[^A-Za-z0-9_.:=/-]"), "_")
        .take(128)

private fun AuthLossDecision.diagnosticsName(): String =
    when (this) {
        is AuthLossDecision.SessionPreserved -> "session_preserved:$reason"
        is AuthLossDecision.AuthRequired -> "auth_required:$reason"
    }
