package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Target age (`serverNow - iat`) at which the foreground app rotates the access token in the
 * background (#proj-desktop:#54, Condition #4). Keeps refresh-token rotation cadence ~13–15m even
 * when server `full`-session access-token `exp` is extended up to 24h.
 */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_ROTATE_AGE_MS = 13 * 60_000L

/** Lead before `exp` when server access-token TTL is shorter than 15m. */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_EXPIRY_LEAD_MS = 2 * 60_000L

/** Minimum cooldown after a successful rotation so short/skewed tokens can never create a storm. */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_ROTATE_MIN_GAP_MS = 60_000L

/** Longest single wait step so deep sleep cannot delay rotation past its wall-clock due time. */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_ROTATE_MAX_STEP_MS = 60_000L

/** Initial exponential backoff after a failed background age rotation (60s -> 120s -> 240s -> ... -> 15m). */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_INITIAL_MS = 60_000L

/** Ceiling on exponential backoff after consecutive background rotation failures. */
@RaftCoreInternalApi const val RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_MAX_MS = 15 * 60_000L

/** Result of one background age-based rotation attempt. */
@RaftCoreInternalApi enum class AccessTokenAgeRotationOutcome {
    /** A fresh refresh succeeded and stored a new access token. */
    Rotated,

    /** Another caller already rotated the token before this attempt ran; no duplicate refresh sent. */
    AlreadyRotated,

    /** Transient or server failure (offline, 5xx, 403, or preserved 401); session stays intact. */
    Failed,

    /** No signed-in session (or no refresh token); pause until auth state changes. */
    SignedOut,
}

/** Exponential retry backoff for failed background age rotations: 60s, 120s, 240s, 480s, capped at 15m. */
@RaftCoreInternalApi fun accessTokenAgeBackoffMillis(
    consecutiveFailures: Int,
    initialBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_INITIAL_MS,
    maxBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_MAX_MS,
): Long {
    if (consecutiveFailures <= 1) return initialBackoffMillis.coerceAtMost(maxBackoffMillis)
    var backoff = initialBackoffMillis
    repeat((consecutiveFailures - 1).coerceAtMost(20)) {
        if (backoff >= maxBackoffMillis) return maxBackoffMillis
        backoff = if (backoff > maxBackoffMillis / 2) maxBackoffMillis else backoff * 2
    }
    return backoff.coerceAtMost(maxBackoffMillis)
}

/**
 * Foreground-only, non-blocking background access-token rotation loop (#proj-desktop:#54).
 *
 * - Runs only while signed in and [foreground] is true. While backgrounded or signed out, parks
 *   silently with no timers or network traffic.
 * - Rotates asynchronously once token age (`serverNow - iat`) reaches [maxAgeMillis] (default 13m)
 *   or `lifetime - expiryLeadMillis` for shorter TTLs. Safe under both 15m and 24h server TTLs.
 * - Tokens without `iat` (or opaque tokens) never spin or force-rotate; the loop waits for the next
 *   session mutation.
 * - After a successful rotation, calibrates [clock] from the freshly minted token's `iat` and
 *   enforces a cooldown of `max(minGapMillis, min(nextLifetime / 2, maxAgeMillis / 2))`.
 * - After a failure, backs off exponentially (`60s -> 120s -> 240s -> ... -> 15m`), resetting on
 *   success, token change, network restoration, or foreground return. Never signs the user out.
 */
@RaftCoreInternalApi suspend fun runAccessTokenAgeRotationLoop(
    readAccessToken: suspend () -> String?,
    rotate: suspend (reason: String, currentAccessToken: String) -> AccessTokenAgeRotationOutcome,
    foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    authSessionEvents: Flow<AuthSessionEvent> = emptyFlow(),
    networkRestored: Flow<Any> = emptyFlow(),
    nowMillis: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
    clock: RaftAccessTokenClock = ProcessRaftAccessTokenClock,
    logger: (String) -> Unit = {},
    maxAgeMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
    expiryLeadMillis: Long = RAFT_ACCESS_TOKEN_EXPIRY_LEAD_MS,
    minGapMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_MIN_GAP_MS,
    maxStepMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_MAX_STEP_MS,
    initialBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_INITIAL_MS,
    maxBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_MAX_MS,
) {
    require(maxAgeMillis > 0L) { "maxAgeMillis must be positive" }
    require(minGapMillis >= 0L) { "minGapMillis must be non-negative" }
    require(maxStepMillis > 0L) { "maxStepMillis must be positive" }
    require(initialBackoffMillis > 0L) { "initialBackoffMillis must be positive" }
    require(maxBackoffMillis >= initialBackoffMillis) { "maxBackoffMillis must be >= initialBackoffMillis" }

    coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val resetBackoffPending = atomic(false)

        val foregroundWatcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                foreground.drop(1).collect { isForeground ->
                    if (isForeground) resetBackoffPending.value = true
                    wake.trySend(Unit)
                }
            }
        val authWatcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                authSessionEvents.collect {
                    wake.trySend(Unit)
                }
            }
        val networkWatcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                networkRestored.collect {
                    resetBackoffPending.value = true
                    wake.trySend(Unit)
                }
            }

        try {
            var seenToken: String? = null
            var schedule: RaftAccessTokenAgeSchedule? = null
            var notBeforeMs = Long.MIN_VALUE
            var consecutiveFailures = 0
            var backoffUntilMs = Long.MIN_VALUE

            while (true) {
                if (!foreground.value) {
                    foreground.first { it }
                    consecutiveFailures = 0
                    backoffUntilMs = Long.MIN_VALUE
                    resetBackoffPending.value = false
                    wake.tryReceive()
                }
                if (resetBackoffPending.getAndSet(false)) {
                    consecutiveFailures = 0
                    backoffUntilMs = Long.MIN_VALUE
                }

                val token =
                    runCatchingPreservingCancellation { readAccessToken() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (token == null) {
                    seenToken = null
                    schedule = null
                    notBeforeMs = Long.MIN_VALUE
                    consecutiveFailures = 0
                    backoffUntilMs = Long.MIN_VALUE
                    wake.receive()
                    continue
                }

                if (token != seenToken) {
                    val seenAt = nowMillis()
                    clock.noteSeen(token, seenAt)
                    schedule =
                        raftAccessTokenAgeSchedule(
                            token = token,
                            receivedAtDeviceMs = seenAt,
                            clock = clock,
                            maxAgeMillis = maxAgeMillis,
                            expiryLeadMillis = expiryLeadMillis,
                        )
                    seenToken = token
                    consecutiveFailures = 0
                    backoffUntilMs = Long.MIN_VALUE
                    notBeforeMs = Long.MIN_VALUE
                    if (schedule == null) {
                        logger("auth_token_age_rotate outcome=no_iat")
                    }
                }

                val currentSchedule = schedule
                if (currentSchedule == null) {
                    wake.receive()
                    continue
                }

                val now = nowMillis()
                val dueAt = maxOf(currentSchedule.rotateAtDeviceMs, notBeforeMs, backoffUntilMs)
                if (now < dueAt) {
                    val step = minOf(dueAt - now, maxStepMillis)
                    withTimeoutOrNull(step) { wake.receive() }
                    continue
                }

                if (!foreground.value) continue

                val latestBeforeRotate =
                    runCatchingPreservingCancellation { readAccessToken() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (latestBeforeRotate != token) continue

                val outcome =
                    runCatchingPreservingCancellation {
                        rotate("token_age_rotation", token)
                    }.getOrElse { AccessTokenAgeRotationOutcome.Failed }

                when (outcome) {
                    AccessTokenAgeRotationOutcome.Rotated -> {
                        consecutiveFailures = 0
                        backoffUntilMs = Long.MIN_VALUE
                        val at = nowMillis()
                        val next =
                            runCatchingPreservingCancellation { readAccessToken() }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                        if (next != null && next != token) {
                            clock.learnFromFreshToken(next, at)
                            val nextSchedule =
                                raftAccessTokenAgeSchedule(
                                    token = next,
                                    receivedAtDeviceMs = at,
                                    clock = clock,
                                    maxAgeMillis = maxAgeMillis,
                                    expiryLeadMillis = expiryLeadMillis,
                                )
                            seenToken = next
                            schedule = nextSchedule
                            val nextLifetime = nextSchedule?.lifetimeMs ?: 0L
                            val floorGap = maxOf(minGapMillis, minOf(nextLifetime / 2, maxAgeMillis / 2))
                            notBeforeMs = at + floorGap
                        } else {
                            notBeforeMs = at + maxOf(minGapMillis, maxAgeMillis / 2)
                        }
                        logger("auth_token_age_rotate outcome=rotated")
                    }
                    AccessTokenAgeRotationOutcome.AlreadyRotated -> {
                        consecutiveFailures = 0
                        backoffUntilMs = Long.MIN_VALUE
                        val next =
                            runCatchingPreservingCancellation { readAccessToken() }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                        if (next != null && next != token) {
                            seenToken = null
                            schedule = null
                        } else {
                            notBeforeMs = nowMillis() + minGapMillis
                        }
                        logger("auth_token_age_rotate outcome=already_rotated")
                    }
                    AccessTokenAgeRotationOutcome.SignedOut -> {
                        seenToken = null
                        schedule = null
                        notBeforeMs = Long.MIN_VALUE
                        consecutiveFailures = 0
                        backoffUntilMs = Long.MIN_VALUE
                        logger("auth_token_age_rotate outcome=signed_out")
                        wake.receive()
                    }
                    AccessTokenAgeRotationOutcome.Failed -> {
                        consecutiveFailures += 1
                        val backoff =
                            accessTokenAgeBackoffMillis(
                                consecutiveFailures = consecutiveFailures,
                                initialBackoffMillis = initialBackoffMillis,
                                maxBackoffMillis = maxBackoffMillis,
                            )
                        backoffUntilMs = nowMillis() + backoff
                        logger(
                            "auth_token_age_rotate outcome=failed failures=$consecutiveFailures retryInMs=$backoff"
                        )
                    }
                }
            }
        } finally {
            foregroundWatcher.cancel()
            authWatcher.cancel()
            networkWatcher.cancel()
        }
    }
}

/**
 * Process-scoped foreground access-token age rotator wired to [RaftAuthSessionStore] and
 * [SharedAuthDataSource]. Rotated tokens are persisted through [SharedAuthRefreshCoordinator],
 * which updates [RaftAuthSessionStore] and emits [AuthSessionEvent.TokensRotated] so open WebViews
 * (`SettingsWebViewAccessTokenSyncEffect`), REST requests, and realtime owners observe the new token.
 */
@RaftCoreInternalApi class SharedAccessTokenAgeRotator(
    private val sessionStore: RaftAuthSessionStore,
    private val authDataSource: SharedAuthDataSource,
    private val authSessionEvents: Flow<AuthSessionEvent> = emptyFlow(),
    private val foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    private val networkRestored: Flow<Any> =
        RaftFreshnessSignal.events.filter { it.trigger == RaftFreshnessTrigger.NetworkRestored },
    private val nowMillis: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
    private val clock: RaftAccessTokenClock = ProcessRaftAccessTokenClock,
    private val logger: (String) -> Unit = {},
) {
    suspend fun run(
        maxAgeMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
        expiryLeadMillis: Long = RAFT_ACCESS_TOKEN_EXPIRY_LEAD_MS,
        minGapMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_MIN_GAP_MS,
        maxStepMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_MAX_STEP_MS,
        initialBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_INITIAL_MS,
        maxBackoffMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_BACKOFF_MAX_MS,
    ) {
        runAccessTokenAgeRotationLoop(
            readAccessToken = {
                runCatchingPreservingCancellation { sessionStore.readSession()?.accessToken }.getOrNull()
            },
            rotate = { reason, currentAccessToken ->
                val storedBefore =
                    runCatchingPreservingCancellation { sessionStore.readSession() }.getOrNull()
                when {
                    storedBefore == null -> AccessTokenAgeRotationOutcome.SignedOut
                    storedBefore.accessToken != currentAccessToken -> AccessTokenAgeRotationOutcome.AlreadyRotated
                    else ->
                        when (
                            val result =
                                authDataSource.rotateAccessTokenByAge(
                                    reason = reason,
                                    currentAccessToken = currentAccessToken,
                                )
                        ) {
                            is SharedAuthResult.Success ->
                                if (result.data.accessToken != currentAccessToken) {
                                    AccessTokenAgeRotationOutcome.Rotated
                                } else {
                                    AccessTokenAgeRotationOutcome.AlreadyRotated
                                }
                            SharedAuthResult.Unauthenticated -> AccessTokenAgeRotationOutcome.SignedOut
                            is SharedAuthResult.Failure -> AccessTokenAgeRotationOutcome.Failed
                        }
                }
            },
            foreground = foreground,
            authSessionEvents = authSessionEvents,
            networkRestored = networkRestored,
            nowMillis = nowMillis,
            clock = clock,
            logger = logger,
            maxAgeMillis = maxAgeMillis,
            expiryLeadMillis = expiryLeadMillis,
            minGapMillis = minGapMillis,
            maxStepMillis = maxStepMillis,
            initialBackoffMillis = initialBackoffMillis,
            maxBackoffMillis = maxBackoffMillis,
        )
    }
}
