package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Process-wide refresh-token execution owner.
 *
 * Every caller still owns its auth-loss policy, but refresh transport and the
 * rotating-token commit are single-flight by the old refresh token. The
 * coordinator scope is independent from page/lifecycle/connect jobs: caller
 * cancellation only stops awaiting the result. Once [authApi] has parsed a
 * successful response, committing its successor to [sessionStore] is the only
 * explicitly non-cancellable window.
 */
internal class SharedAuthRefreshCoordinator(
    private val sessionStore: RaftAuthSessionStore,
    private val authApi: RaftAuthApi,
    private val clockMillis: () -> Long,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {},
    private val criticalLogger: (String) -> Unit = warningLogger,
    private val refreshAttemptTimeoutMillis: Long = AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS,
    private val attemptIdFactory: () -> String = ::newAuthRefreshAttemptId,
    private val installationIdFactory: () -> String = ::newAuthInstallationId,
    private val beforeFlightRetirement: suspend (resultAlreadyPublished: Boolean) -> Unit = {},
) {
    suspend fun refreshSession(
        failedAccessToken: String? = null,
        failedRefreshToken: String? = null,
        clearOnUnauthorized: Boolean = true,
        clearSource: String = "token_refresh",
        reason: String = "unknown",
        caller: String = "unknown"
    ): SharedAuthRefreshResult {
        val safeReason = reason.toSafeAuthRefreshDiagnosticsToken()
        val safeCaller = caller.toSafeAuthRefreshDiagnosticsToken()
        val current = sessionStore.readSession() ?: return SharedAuthRefreshResult.Unavailable
        if (current.isNewerThan(failedAccessToken, failedRefreshToken)) {
            logAlreadyUpdated(current, failedAccessToken, failedRefreshToken, safeCaller, safeReason)
            return SharedAuthRefreshResult.Success(current)
        }
        val refreshToken = current.refreshToken.takeIf { it.isNotBlank() }
            ?: return handleMissingRefreshToken(
                current = current,
                clearOnUnauthorized = clearOnUnauthorized,
                clearSource = clearSource,
                safeCaller = safeCaller,
                safeReason = safeReason
            )

        val selection =
            coordinationMutex.withLock {
                val recentUnauthorized =
                    recentUnauthorizedByRefreshToken[refreshToken]
                        ?.takeIf { it.matches(current, clockMillis()) }
                if (recentUnauthorized != null) {
                    FlightSelection.Completed(recentUnauthorized.exchange)
                } else {
                    val existing = inFlightByRefreshToken[refreshToken]
                    if (existing != null) {
                        FlightSelection.Join(existing)
                    } else {
                        val result = CompletableDeferred<RefreshExchange>()
                        val flight =
                            InFlightRefresh(
                                originalSession = current,
                                result = result,
                            )
                        inFlightByRefreshToken[refreshToken] = flight
                        FlightSelection.Owner(flight)
                    }
                }
            }

        if (selection is FlightSelection.Owner) {
            coordinatorScope.launch {
                executeOwnerRefresh(
                    flight = selection.flight,
                    refreshToken = refreshToken,
                    safeCaller = safeCaller,
                    safeReason = safeReason
                )
            }
        }

        val exchange =
            when (selection) {
                is FlightSelection.Completed -> {
                    criticalLogger(
                        "refresh_skip reason=recent_unauthorized_session_preserved " +
                            "caller=$safeCaller requestReason=$safeReason"
                    )
                    selection.exchange
                }
                is FlightSelection.Join -> {
                    logger(
                        "refresh_join caller=$safeCaller requestReason=$safeReason " +
                            "refresh=${refreshToken.authTokenDiagnosticsFingerprint()}"
                    )
                    selection.flight.result.await()
                }
                is FlightSelection.Owner -> selection.flight.result.await()
            }
        return applyExchange(
            exchange = exchange,
            clearOnUnauthorized = clearOnUnauthorized,
            clearSource = clearSource,
            safeCaller = safeCaller,
            safeReason = safeReason
        )
    }

    private suspend fun executeOwnerRefresh(
        flight: InFlightRefresh,
        refreshToken: String,
        safeCaller: String,
        safeReason: String
    ) {
        val exchange =
            try {
                val latest = sessionStore.readSession()
                if (latest == null) {
                    RefreshExchange.Unavailable
                } else if (!latest.sameTokensAs(flight.originalSession)) {
                    RefreshExchange.AlreadyUpdated(latest)
                } else {
                    val prepared = prepareDurableAttempt(latest)
                    if (prepared == null) {
                        val reconciled = sessionStore.readSession()
                        if (reconciled == null) {
                            RefreshExchange.Unavailable
                        } else {
                            RefreshExchange.AlreadyUpdated(reconciled)
                        }
                    } else {
                        val attempt = prepared.attempt
                        val replayedAgeMillis = prepared.reusedAttemptAgeMillis
                        if (replayedAgeMillis != null) {
                            // The same attempt id is replayed. Past the server's replay receipt TTL the
                            // replay can no longer return the successor, so a 401 means the rotation
                            // was lost (for example after the OS froze the app mid-refresh).
                            criticalLogger(
                                "refresh_attempt_reused reason=$safeReason caller=$safeCaller " +
                                    "attempt=${attempt.attemptId.authTokenDiagnosticsFingerprint()} " +
                                    refreshReplayAgeDiagnosticsFields(replayedAgeMillis)
                            )
                        }
                        logger(
                            "refresh_start reason=$safeReason caller=$safeCaller " +
                                "access=${latest.accessToken.authTokenDiagnosticsFingerprint()} " +
                                "refresh=${refreshToken.authTokenDiagnosticsFingerprint()} " +
                                "attempt=${attempt.attemptId.authTokenDiagnosticsFingerprint()} " +
                                "replayed=${replayedAgeMillis != null}"
                        )
                        val response =
                            refreshWithAmbiguousTimeoutRecovery(
                                refreshToken = refreshToken,
                                attempt = attempt.toApiContext(),
                                safeCaller = safeCaller,
                                safeReason = safeReason,
                            )
                        when (response) {
                            is AuthApiResult.Success ->
                                commitSuccessfulRefresh(
                                    original = flight.originalSession,
                                    attempt = attempt,
                                    response = response.data,
                                    safeCaller = safeCaller,
                                    safeReason = safeReason
                                )
                            is AuthApiResult.Failure -> {
                                val diagnosis =
                                    RefreshFailureDiagnosis(
                                        unauthorized = response.code == 401 || response.code == 403,
                                        serverCode = parseAuthRefreshErrorCode(response.errorBodyText),
                                        replayed = replayedAgeMillis != null,
                                        attemptAgeMillis = attempt.refreshAttemptAgeMillis(clockMillis()),
                                    )
                                criticalLogger(
                                    "refresh_failed status=${response.code} " +
                                        "reason=$safeReason caller=$safeCaller" +
                                        diagnosis.diagnosticsFields()
                                )
                                logUnauthorizedDiagnosis(
                                    status = response.code,
                                    diagnosis = diagnosis,
                                    attempt = attempt,
                                    safeCaller = safeCaller,
                                    safeReason = safeReason,
                                )
                                RefreshExchange.Failure(
                                    originalSession = flight.originalSession,
                                    response = response,
                                    failedAtMillis = clockMillis(),
                                    diagnosis = diagnosis,
                                )
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                cleanupFlight(refreshToken, flight, exchange = null)
                flight.result.cancel(cancellation)
                throw cancellation
            } catch (throwable: Throwable) {
                RefreshExchange.Exception(throwable)
            }

        // Retire the single-flight owner and publish its durable terminal consequence before
        // waking callers. Otherwise an immediate retry can rejoin a completed ambiguous flight
        // and suppress the next authoritative unauthorized response.
        cleanupFlight(refreshToken, flight, exchange)
        flight.result.complete(exchange)
    }

    private suspend fun refreshWithAmbiguousTimeoutRecovery(
        refreshToken: String,
        attempt: AuthRefreshAttemptContext,
        safeCaller: String,
        safeReason: String,
    ): AuthApiResult<AuthRefreshResponse> {
        val attemptStartedAtMillis = clockMillis()
        val first = refreshAttempt(refreshToken, attempt)
        if (first != null && !first.isAmbiguousTransportFailure()) return first
        val elapsedMillis = (clockMillis() - attemptStartedAtMillis).coerceAtLeast(0L)
        if (elapsedMillis > AUTH_REFRESH_REPLAY_RETRY_WINDOW_MILLIS) {
            criticalLogger(
                "refresh_attempt_ambiguous action=preserve_session reason=replay_window_elapsed " +
                    "requestReason=$safeReason caller=$safeCaller elapsedMs=$elapsedMillis"
            )
            return ambiguousRefreshFailure("refresh_replay_window_elapsed")
        }
        criticalLogger(
            "refresh_attempt_ambiguous action=retry_same_token " +
                "reason=$safeReason caller=$safeCaller elapsedMs=$elapsedMillis"
        )
        val retry = refreshAttempt(refreshToken, attempt)
            ?: return ambiguousRefreshFailure("refresh_retry_timeout")
        return retry
    }

    private suspend fun refreshAttempt(
        refreshToken: String,
        attempt: AuthRefreshAttemptContext,
    ): AuthApiResult<AuthRefreshResponse>? {
        val result = CompletableDeferred<AuthApiResult<AuthRefreshResponse>>()
        val request =
            coordinatorScope.launch {
                runCatching { authApi.refreshWithAttempt(refreshToken, attempt) }
                    .onSuccess(result::complete)
                    .onFailure(result::completeExceptionally)
            }
        val response = withTimeoutOrNull(refreshAttemptTimeoutMillis) { result.await() }
        if (response == null) request.cancel()
        return response
    }

    private fun AuthApiResult<AuthRefreshResponse>.isAmbiguousTransportFailure(): Boolean =
        this is AuthApiResult.Failure && code == 0

    private fun ambiguousRefreshFailure(contractCode: String): AuthApiResult.Failure =
        AuthApiResult.Failure(
            code = 0,
            errorBodyText = """{"error":"network_timeout","message":"refresh result is ambiguous"}""",
            contractCode = contractCode,
        )

    private suspend fun cleanupFlight(
        refreshToken: String,
        flight: InFlightRefresh,
        exchange: RefreshExchange?
    ) {
        beforeFlightRetirement(flight.result.isCompleted)
        coordinationMutex.withLock {
            if (inFlightByRefreshToken[refreshToken] === flight) {
                inFlightByRefreshToken.remove(refreshToken)
            }
            when (exchange) {
                is RefreshExchange.Success -> {
                    recentUnauthorizedByRefreshToken.remove(exchange.originalSession.refreshToken)
                }
                is RefreshExchange.Failure -> {
                    if (exchange.response.code == 401 || exchange.response.code == 403) {
                        recentUnauthorizedByRefreshToken[exchange.originalSession.refreshToken] =
                            RecentUnauthorizedRefresh(exchange)
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun commitSuccessfulRefresh(
        original: StoredAuthSession,
        attempt: StoredAuthRefreshAttempt,
        response: AuthRefreshResponse,
        safeCaller: String,
        safeReason: String
    ): RefreshExchange =
        withContext(NonCancellable) {
            // #466: atomic compare-and-swap. Rotate only if the store's LATEST tokens are still the
            // ones this refresh was issued against — closing the read-latest→save TOCTOU that let a
            // metadata writer (or a concurrent rotation) clobber the committed token. The store
            // returns the newly-persisted session (latest metadata + new tokens) on success.
            val updated =
                sessionStore.rotateTokensAndClearRefreshAttemptIfMatch(
                    expectedAccountId = original.user.id,
                    expectedAccessToken = original.accessToken,
                    expectedRefreshToken = original.refreshToken,
                    expectedAttemptId = attempt.attemptId,
                    newAccessToken = response.accessToken,
                    newRefreshToken = response.refreshToken
                )
            if (updated == null) {
                // Not applied: either no session (logged out) or another writer already rotated.
                val latest = sessionStore.readSession()
                    ?: return@withContext RefreshExchange.Unavailable
                logger(
                    "refresh_commit_skip reason=token_already_updated caller=$safeCaller " +
                        "requestReason=$safeReason"
                )
                return@withContext RefreshExchange.AlreadyUpdated(latest)
            }
            logger(
                "refresh_success reason=$safeReason caller=$safeCaller " +
                    "accessRotated=${original.accessToken != updated.accessToken} " +
                    "refreshRotated=${original.refreshToken != updated.refreshToken} " +
                    "oldAccess=${original.accessToken.authTokenDiagnosticsFingerprint()} " +
                    "newAccess=${updated.accessToken.authTokenDiagnosticsFingerprint()} " +
                    "oldRefresh=${original.refreshToken.authTokenDiagnosticsFingerprint()} " +
                    "newRefresh=${updated.refreshToken.authTokenDiagnosticsFingerprint()}"
            )
            RefreshExchange.Success(originalSession = original, session = updated)
        }

    private fun logUnauthorizedDiagnosis(
        status: Int,
        diagnosis: RefreshFailureDiagnosis,
        attempt: StoredAuthRefreshAttempt,
        safeCaller: String,
        safeReason: String,
    ) {
        if (status != 401 && status != 403) return
        val attemptFingerprint = attempt.attemptId.authTokenDiagnosticsFingerprint()
        val attemptAgeMillis = diagnosis.attemptAgeMillis ?: attempt.refreshAttemptAgeMillis(clockMillis())
        if (diagnosis.replayed) {
            val lostRotation = attemptAgeMillis >= AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS
            criticalLogger(
                "refresh_replay_rejected status=$status reason=$safeReason caller=$safeCaller " +
                    "attempt=$attemptFingerprint attemptAgeMs=$attemptAgeMillis " +
                    "replayReceiptLikelyExpired=$lostRotation " +
                    "outcome=${if (lostRotation) "lost_rotation_after_freeze" else "replay_rejected"}" +
                    (diagnosis.serverCode?.let { " serverCode=${it.toSafeAuthRefreshDiagnosticsToken()}" } ?: "")
            )
        }
        if (diagnosis.serverCode == AUTH_REFRESH_ROTATION_SUPERSEDED_CODE) {
            criticalLogger(
                "refresh_rotation_superseded status=$status reason=$safeReason caller=$safeCaller " +
                    "attempt=$attemptFingerprint replayed=${diagnosis.replayed} attemptAgeMs=$attemptAgeMillis " +
                    "replayReceiptLikelyExpired=${attemptAgeMillis >= AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS}"
            )
        }
    }

    private suspend fun prepareDurableAttempt(session: StoredAuthSession): PreparedRefreshAttempt? {
        val installationId =
            sessionStore.readOrCreateAuthInstallationId(installationIdFactory())
        val candidate =
            StoredAuthRefreshAttempt(
                attemptId = attemptIdFactory().validatedAuthRefreshAttemptId(),
                installationId = installationId,
                accountId = session.user.id,
                predecessorAccessToken = session.accessToken,
                predecessorRefreshToken = session.refreshToken,
                createdAtMillis = clockMillis(),
            )
        val attempt =
            sessionStore.prepareRefreshAttemptIfMatch(
                expectedAccountId = session.user.id,
                expectedAccessToken = session.accessToken,
                expectedRefreshToken = session.refreshToken,
                candidate = candidate,
            ) ?: return null
        return PreparedRefreshAttempt(
            attempt = attempt,
            reusedAttemptAgeMillis =
                attempt.takeIf { it.attemptId != candidate.attemptId }
                    ?.refreshAttemptAgeMillis(candidate.createdAtMillis),
        )
    }

    private suspend fun applyExchange(
        exchange: RefreshExchange,
        clearOnUnauthorized: Boolean,
        clearSource: String,
        safeCaller: String,
        safeReason: String
    ): SharedAuthRefreshResult =
        when (exchange) {
            is RefreshExchange.Success -> SharedAuthRefreshResult.Success(exchange.session)
            is RefreshExchange.AlreadyUpdated -> {
                logAlreadyUpdated(
                    current = exchange.session,
                    failedAccessToken = null,
                    failedRefreshToken = null,
                    safeCaller = safeCaller,
                    safeReason = safeReason
                )
                SharedAuthRefreshResult.Success(exchange.session)
            }
            is RefreshExchange.Failure ->
                applyFailure(
                    exchange = exchange,
                    clearOnUnauthorized = clearOnUnauthorized,
                    clearSource = clearSource,
                    safeCaller = safeCaller,
                    safeReason = safeReason
                )
            is RefreshExchange.Exception -> throw exchange.throwable
            RefreshExchange.Unavailable -> SharedAuthRefreshResult.Unavailable
        }

    private suspend fun applyFailure(
        exchange: RefreshExchange.Failure,
        clearOnUnauthorized: Boolean,
        clearSource: String,
        safeCaller: String,
        safeReason: String
    ): SharedAuthRefreshResult {
        val code = exchange.response.code
        if (code != 401 && code != 403) {
            return SharedAuthRefreshResult.Failure(exchange.response)
        }
        if (clearOnUnauthorized) {
            val cleared =
                sessionStore.clearSessionIfMatches(
                    expectedAccountId = exchange.originalSession.user.id,
                    expectedAccessToken = exchange.originalSession.accessToken,
                    expectedRefreshToken = exchange.originalSession.refreshToken,
                    reason = "refresh_http_$code",
                    source = clearSource,
                )
            if (!cleared) {
                val successor = sessionStore.readSession()
                if (successor != null && !successor.sameTokensAs(exchange.originalSession)) {
                    // The rejected refresh used tokens this app has already replaced. The newer
                    // session stays; record why the 401 did not sign the user out.
                    criticalLogger(
                        "refresh_unauthorized_ignored status=$code reason=$safeReason caller=$safeCaller " +
                            "decision=newer_session_held " +
                            "currentAccess=${successor.accessToken.authTokenDiagnosticsFingerprint()} " +
                            "currentRefresh=${successor.refreshToken.authTokenDiagnosticsFingerprint()}" +
                            exchange.diagnosis.diagnosticsFields()
                    )
                    logAlreadyUpdated(successor, null, null, safeCaller, safeReason)
                    return SharedAuthRefreshResult.Success(successor)
                }
            }
            coordinationMutex.withLock {
                recentUnauthorizedByRefreshToken.remove(exchange.originalSession.refreshToken)
            }
            if (cleared) {
                criticalLogger(
                    "refresh_unauthorized_session_cleared status=$code " +
                        "reason=$safeReason caller=$safeCaller" +
                        exchange.diagnosis.diagnosticsFields()
                )
            }
        } else {
            criticalLogger(
                "refresh_unauthorized_session_preserved status=$code " +
                    "reason=$safeReason caller=$safeCaller" +
                    exchange.diagnosis.diagnosticsFields()
            )
        }
        return SharedAuthRefreshResult.Failure(exchange.response)
    }

    private suspend fun handleMissingRefreshToken(
        current: StoredAuthSession,
        clearOnUnauthorized: Boolean,
        clearSource: String,
        safeCaller: String,
        safeReason: String
    ): SharedAuthRefreshResult {
        warningLogger(
            "refresh_skip reason=missing_refresh_token caller=$safeCaller requestReason=$safeReason"
        )
        if (clearOnUnauthorized) {
            val cleared =
                sessionStore.clearSessionIfMatches(
                    expectedAccountId = current.user.id,
                    expectedAccessToken = current.accessToken,
                    expectedRefreshToken = current.refreshToken,
                    reason = "missing_refresh_token",
                    source = clearSource,
                )
            if (!cleared) {
                val successor = sessionStore.readSession()
                if (successor != null && !successor.sameTokensAs(current)) {
                    logAlreadyUpdated(successor, null, null, safeCaller, safeReason)
                    return SharedAuthRefreshResult.Success(successor)
                }
            }
        } else {
            warningLogger(
                "refresh_missing_refresh_token_session_preserved " +
                    "caller=$safeCaller requestReason=$safeReason"
            )
        }
        return SharedAuthRefreshResult.Unavailable
    }

    private fun logAlreadyUpdated(
        current: StoredAuthSession,
        failedAccessToken: String?,
        failedRefreshToken: String?,
        safeCaller: String,
        safeReason: String
    ) {
        logger(
            "refresh_skip reason=token_already_updated caller=$safeCaller requestReason=$safeReason " +
                "failedAccess=${failedAccessToken.authTokenDiagnosticsFingerprint()} " +
                "currentAccess=${current.accessToken.authTokenDiagnosticsFingerprint()} " +
                "failedRefresh=${failedRefreshToken.authTokenDiagnosticsFingerprint()} " +
                "currentRefresh=${current.refreshToken.authTokenDiagnosticsFingerprint()}"
        )
    }

    private companion object {
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinationMutex = Mutex()
        val inFlightByRefreshToken = mutableMapOf<String, InFlightRefresh>()
        val recentUnauthorizedByRefreshToken = mutableMapOf<String, RecentUnauthorizedRefresh>()
    }
}

private fun StoredAuthRefreshAttempt.toApiContext(): AuthRefreshAttemptContext =
    AuthRefreshAttemptContext(attemptId = attemptId, installationId = installationId)

private val AuthRefreshAttemptIdRegex = Regex("^arf_[0-9a-f]{16}$")

internal fun String.isValidAuthRefreshAttemptId(): Boolean = AuthRefreshAttemptIdRegex.matches(this)

private fun String.validatedAuthRefreshAttemptId(): String {
    require(isValidAuthRefreshAttemptId()) { "invalid auth refresh attempt id" }
    return this
}

internal fun newAuthRefreshAttemptId(randomLong: () -> Long = { Random.nextLong() }): String =
    "arf_${randomLong().toULong().toString(16).padStart(16, '0')}"

internal fun newAuthInstallationId(randomLong: () -> Long = { Random.nextLong() }): String =
    "ari_${randomLong().toULong().toString(16).padStart(16, '0')}" +
        randomLong().toULong().toString(16).padStart(16, '0')

internal sealed interface SharedAuthRefreshResult {
    data class Success(val session: StoredAuthSession) : SharedAuthRefreshResult
    data class Failure(val response: AuthApiResult.Failure) : SharedAuthRefreshResult
    data object Unavailable : SharedAuthRefreshResult
}

private data class PreparedRefreshAttempt(
    val attempt: StoredAuthRefreshAttempt,
    /** Age of a persisted attempt that is being replayed; null when a fresh attempt was minted. */
    val reusedAttemptAgeMillis: Long?,
)

/** Diagnostics-only context for a failed refresh. Never changes the clear/preserve decision. */
private data class RefreshFailureDiagnosis(
    val unauthorized: Boolean = false,
    val serverCode: String? = null,
    val replayed: Boolean = false,
    /** Attempt age when the failure arrived (createdAtMillis to response); covers OS freezes. */
    val attemptAgeMillis: Long? = null,
) {
    private val cause: String?
        get() =
            when {
                !unauthorized -> null
                serverCode == AUTH_REFRESH_ROTATION_SUPERSEDED_CODE -> AUTH_REFRESH_ROTATION_SUPERSEDED_CODE
                replayed -> "refresh_replay_rejected"
                else -> null
            }

    fun diagnosticsFields(): String =
        buildString {
            cause?.let { append(" cause=").append(it) }
            serverCode?.let { append(" serverCode=").append(it.toSafeAuthRefreshDiagnosticsToken()) }
            if (replayed) append(" replayed=true")
            attemptAgeMillis?.let { append(" attemptAgeMs=").append(it) }
        }
}

private data class InFlightRefresh(
    val originalSession: StoredAuthSession,
    val result: CompletableDeferred<RefreshExchange>,
)

private sealed interface FlightSelection {
    data class Owner(val flight: InFlightRefresh) : FlightSelection
    data class Join(val flight: InFlightRefresh) : FlightSelection
    data class Completed(val exchange: RefreshExchange.Failure) : FlightSelection
}

private sealed interface RefreshExchange {
    data class Success(
        val originalSession: StoredAuthSession,
        val session: StoredAuthSession
    ) : RefreshExchange

    data class AlreadyUpdated(val session: StoredAuthSession) : RefreshExchange

    data class Failure(
        val originalSession: StoredAuthSession,
        val response: AuthApiResult.Failure,
        val failedAtMillis: Long,
        val diagnosis: RefreshFailureDiagnosis = RefreshFailureDiagnosis(),
    ) : RefreshExchange

    data class Exception(val throwable: Throwable) : RefreshExchange
    data object Unavailable : RefreshExchange
}

private data class RecentUnauthorizedRefresh(
    val exchange: RefreshExchange.Failure
) {
    fun matches(session: StoredAuthSession, nowMillis: Long): Boolean =
        exchange.originalSession.sameTokensAs(session) &&
            nowMillis - exchange.failedAtMillis in 0..PRESERVED_UNAUTHORIZED_BACKOFF_MILLIS
}

private fun StoredAuthSession.isNewerThan(
    failedAccessToken: String?,
    failedRefreshToken: String?
): Boolean =
    failedAccessToken?.takeIf { it.isNotBlank() }?.let { accessToken != it } == true ||
        failedRefreshToken?.takeIf { it.isNotBlank() }?.let { refreshToken != it } == true

private fun StoredAuthSession.sameTokensAs(other: StoredAuthSession): Boolean =
    accessToken == other.accessToken && refreshToken == other.refreshToken

private const val PRESERVED_UNAUTHORIZED_BACKOFF_MILLIS = 30_000L
// task #788: internal so the streaming-upload scheduler backstop can be
// checked-derived from the authoritative refresh budget instead of a magic
// number. Value unchanged.
internal const val AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS = 4_000L

/**
 * task #788: declared ceiling for refresh attempts a single middleware wait
 * can consume — the first attempt plus at most one ambiguous-transport retry
 * inside [AUTH_REFRESH_REPLAY_RETRY_WINDOW_MILLIS] (see
 * refreshWithAmbiguousTimeoutRecovery: no loop exists). A behavior test pins
 * the implementation to this constant.
 */
internal const val AUTH_REFRESH_MAX_ATTEMPTS = 2
private const val AUTH_REFRESH_REPLAY_RETRY_WINDOW_MILLIS = 8_000L

/**
 * How long the server keeps a refresh replay receipt (`X-Slock-Auth-Refresh-Attempt-Id`). A replay
 * of an older attempt most likely finds no receipt, so the server answers 401. Diagnostics only.
 */
internal const val AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS = 15L * 60L * 1_000L

/**
 * `code` the server returns with a 401 from `POST /auth/refresh` when a replayed refresh's receipt
 * is gone because the presented refresh token has already rotated to a successor.
 */
internal const val AUTH_REFRESH_ROTATION_SUPERSEDED_CODE = "refresh_rotation_superseded"

internal fun StoredAuthRefreshAttempt.refreshAttemptAgeMillis(nowMillis: Long): Long =
    (nowMillis - createdAtMillis).coerceAtLeast(0L)

internal fun refreshReplayAgeDiagnosticsFields(ageMillis: Long): String {
    val likelyExpired = ageMillis >= AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS
    return "attemptAgeMs=$ageMillis replayReceiptTtlMs=$AUTH_REFRESH_REPLAY_RECEIPT_TTL_MILLIS " +
        "replayReceiptLikelyExpired=$likelyExpired" +
        if (likelyExpired) " diagnosis=likely_expired_server_replay_receipt" else ""
}

/** Error body of `POST /auth/refresh`: `{ error, code? }` like the server's other auth errors. */
@Serializable
internal data class AuthRefreshErrorBodyDto(
    val error: String? = null,
    val code: String? = null,
)

private val authRefreshErrorJson = Json { ignoreUnknownKeys = true }

internal fun parseAuthRefreshErrorCode(errorBodyText: String?): String? {
    val body = errorBodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto =
        try {
            authRefreshErrorJson.decodeFromString(AuthRefreshErrorBodyDto.serializer(), body)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: plain-text or foreign bodies.
            return null
        }
    return dto.code?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun String?.authTokenDiagnosticsFingerprint(): String {
    if (this.isNullOrBlank()) return "blank"
    val tail = takeLast(4).toSafeAuthRefreshDiagnosticsToken()
    val hash = (hashCode().toLong() and 0xffffffffL).toString(16)
    return "len=${length}:tail4=$tail:hash=$hash"
}

internal fun String.toSafeAuthRefreshDiagnosticsToken(): String =
    trim()
        .ifBlank { "unknown" }
        .replace(Regex("""[^A-Za-z0-9_.:/-]"""), "_")
        .take(96)
