package build.raft.app.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `#proj-desktop:#54` (Condition #4): foreground-only non-blocking background access-token rotation
 * by token age (`iat`), decoupling refresh-token rotation cadence (~13m) from longer access-token
 * `exp` (up to 24h). Virtual time via `runTest` + `backgroundScope` + `runCurrent()`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class SharedAccessTokenAgeRotatorTest {
    private val epochMs = 1_800_000_000_000L
    private val ttl15m = 15 * 60_000L
    private val ttl24h = 24 * 60 * 60_000L

    private fun jwt(expMs: Long, iatMs: Long?, sub: String = "user-1"): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = b64.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val iat = iatMs?.let { ""","iat":${it / 1_000}""" }.orEmpty()
        val payload = b64.encode("""{"sub":"$sub","type":"access"$iat,"exp":${expMs / 1_000}}""".encodeToByteArray())
        return "$header.$payload.signature"
    }

    private inner class LoopHarness(
        private val scope: TestScope,
        var token: String?,
        private val mintLifetimeMs: Long = ttl24h,
        private val deviceAheadMs: Long = 0L,
        var outcomeOverride: (() -> AccessTokenAgeRotationOutcome)? = null,
    ) {
        val foreground = MutableStateFlow(true)
        val authEvents = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 16)
        val networkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val clock = RaftAccessTokenClock()
        val rotations = mutableListOf<Pair<Long, String>>()
        val logs = mutableListOf<String>()
        private var minted = 0

        fun deviceNow(): Long = epochMs + scope.currentTime + deviceAheadMs

        fun serverNow(): Long = epochMs + scope.currentTime

        suspend fun rotate(reason: String, currentAccessToken: String): AccessTokenAgeRotationOutcome {
            rotations += scope.currentTime to reason
            assertEquals(token, currentAccessToken)
            outcomeOverride?.invoke()?.let { return it }
            token =
                jwt(
                    expMs = serverNow() + mintLifetimeMs,
                    iatMs = serverNow(),
                    sub = "user-rot-${++minted}",
                )
            return AccessTokenAgeRotationOutcome.Rotated
        }

        fun start() =
            scope.backgroundScope.launch {
                runAccessTokenAgeRotationLoop(
                    readAccessToken = { token },
                    rotate = ::rotate,
                    foreground = foreground,
                    authSessionEvents = authEvents,
                    networkRestored = networkRestored,
                    nowMillis = ::deviceNow,
                    clock = clock,
                    logger = { logs += it },
                )
            }
    }

    @Test
    fun rotatesAt13MinutesUnderBoth15mAnd24hServerTtls() = runTest {
        for (ttl in listOf(ttl15m, ttl24h)) {
            val startAt = epochMs + currentTime
            val harness =
                LoopHarness(
                    scope = this,
                    token = jwt(expMs = startAt + ttl, iatMs = startAt),
                    mintLifetimeMs = ttl,
                )
            val baseTime = currentTime
            val job = harness.start()
            runCurrent()

            advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS - 1L)
            runCurrent()
            assertTrue(harness.rotations.isEmpty(), "ttl=$ttl should not rotate before 13m")

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(
                listOf((baseTime + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS) to "token_age_rotation"),
                harness.rotations,
                "ttl=$ttl first rotation at 13m",
            )

            advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS)
            runCurrent()
            assertEquals(
                listOf(
                    baseTime + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
                    baseTime + 2L * RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
                ),
                harness.rotations.map { it.first },
                "ttl=$ttl second rotation 13m later",
            )
            assertTrue(harness.logs.all { "outcome=rotated" in it })
            job.cancel()
        }
    }

    @Test
    fun shorterTtlRotatesAheadOfExpiryAndMisconfiguredShortTokenNeverStorms() = runTest {
        // 5m TTL rotates at 3m (5m - 2m lead).
        val ttl5m = 5 * 60_000L
        val fiveMin =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs + ttl5m, iatMs = epochMs),
                mintLifetimeMs = ttl5m,
            )
        val job5m = fiveMin.start()
        runCurrent()
        advanceTimeBy(3 * 60_000L - 1L)
        runCurrent()
        assertTrue(fiveMin.rotations.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(3 * 60_000L), fiveMin.rotations.map { it.first })
        job5m.cancel()

        // Misconfigured 90s TTL (born inside 2m lead) rotates once and enforces at least 60s minGapMillis.
        val ttl90s = 90_000L
        val startAt = epochMs + currentTime
        val baseTime = currentTime
        val shortHarness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = startAt + ttl90s, iatMs = startAt),
                mintLifetimeMs = ttl90s,
            )
        val jobShort = shortHarness.start()
        runCurrent()
        assertEquals(listOf(baseTime), shortHarness.rotations.map { it.first })

        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_MIN_GAP_MS - 1L)
        runCurrent()
        assertEquals(listOf(baseTime), shortHarness.rotations.map { it.first })

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            listOf(baseTime, baseTime + RAFT_ACCESS_TOKEN_ROTATE_MIN_GAP_MS),
            shortHarness.rotations.map { it.first },
        )
        jobShort.cancel()
    }

    @Test
    fun pausesWhileBackgroundedAndRotatesAsynchronouslyOnForegroundReturnWhenOverdue() = runTest {
        val harness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs + ttl24h, iatMs = epochMs),
                mintLifetimeMs = ttl24h,
            )
        val job = harness.start()
        runCurrent()

        // Move to background at 5m, stay hidden for 60m -> zero rotations while backgrounded.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        harness.foreground.value = false
        runCurrent()

        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty(), "must not rotate in background")

        // Return to foreground at 65m (token is 65m old >= 13m) -> rotates immediately asynchronously.
        harness.foreground.value = true
        runCurrent()
        assertEquals(listOf(65 * 60_000L), harness.rotations.map { it.first })

        // Next rotation runs 13m later while still in foreground.
        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS)
        runCurrent()
        assertEquals(
            listOf(65 * 60_000L, 65 * 60_000L + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS),
            harness.rotations.map { it.first },
        )
        job.cancel()
    }

    @Test
    fun parksWhenSignedOutOrTokenLacksIatAndResumesOnSignIn() = runTest {
        // Signed out initially: zero rotations across 60m.
        val harness = LoopHarness(scope = this, token = null, mintLifetimeMs = ttl24h)
        val job = harness.start()
        runCurrent()

        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty())

        // Token without `iat` (or opaque): never force-rotates or spins.
        harness.token = jwt(expMs = epochMs + currentTime + ttl24h, iatMs = null)
        harness.authEvents.emit(AuthSessionEvent.Authenticated(1L))
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty())
        assertTrue(harness.logs.any { "outcome=no_iat" in it })

        // Sign in with a normal 24h JWT carrying `iat` -> rotates 13m later.
        val signedInAt = epochMs + currentTime
        val signedInVirtualTime = currentTime
        harness.token = jwt(expMs = signedInAt + ttl24h, iatMs = signedInAt)
        harness.authEvents.emit(AuthSessionEvent.Authenticated(2L))
        runCurrent()

        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS - 1L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(signedInVirtualTime + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS), harness.rotations.map { it.first })
        job.cancel()
    }

    @Test
    fun fastDeviceClockCalibratesFromFreshTokenIatAndDoesNotSpin() = runTest {
        // Device clock is 30m ahead of server clock.
        val harness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs + ttl24h, iatMs = epochMs),
                mintLifetimeMs = ttl24h,
                deviceAheadMs = 30 * 60_000L,
            )
        val job = harness.start()
        runCurrent()

        // Unknown offset at first: rotates once immediately, calibrating clock from fresh token's iat.
        assertEquals(listOf(0L), harness.rotations.map { it.first })
        assertTrue(harness.clock.offsetKnown)

        // Calibrated: waits full 13m before the next rotation (never loops).
        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS - 1L)
        runCurrent()
        assertEquals(listOf(0L), harness.rotations.map { it.first })

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(0L, RAFT_ACCESS_TOKEN_ROTATE_AGE_MS), harness.rotations.map { it.first })
        job.cancel()
    }

    @Test
    fun slowDeviceClockStillRotatesAfter13MinutesAndCalibrates() = runTest {
        // Device clock is 30m behind server clock.
        val harness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs + ttl24h, iatMs = epochMs),
                mintLifetimeMs = ttl24h,
                deviceAheadMs = -30 * 60_000L,
            )
        val job = harness.start()
        runCurrent()

        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS - 1L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS), harness.rotations.map { it.first })
        assertTrue(harness.clock.offsetKnown)

        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS)
        runCurrent()
        assertEquals(
            listOf(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS, 2L * RAFT_ACCESS_TOKEN_ROTATE_AGE_MS),
            harness.rotations.map { it.first },
        )
        job.cancel()
    }

    @Test
    fun exponentialBackoffOnFailureResetsOnNetworkRestoredAndForegroundReturn() = runTest {
        assertEquals(60_000L, accessTokenAgeBackoffMillis(1))
        assertEquals(120_000L, accessTokenAgeBackoffMillis(2))
        assertEquals(240_000L, accessTokenAgeBackoffMillis(3))
        assertEquals(480_000L, accessTokenAgeBackoffMillis(4))
        assertEquals(15 * 60_000L, accessTokenAgeBackoffMillis(5))
        assertEquals(15 * 60_000L, accessTokenAgeBackoffMillis(10))

        // Start with an already-overdue 24h token (20m old) whose rotation fails.
        val harness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs - 20 * 60_000L + ttl24h, iatMs = epochMs - 20 * 60_000L),
                mintLifetimeMs = ttl24h,
                outcomeOverride = { AccessTokenAgeRotationOutcome.Failed },
            )
        val job = harness.start()
        runCurrent()
        assertEquals(listOf(0L), harness.rotations.map { it.first })

        // 1st failure -> 60s backoff.
        advanceTimeBy(60_000L - 1L)
        runCurrent()
        assertEquals(listOf(0L), harness.rotations.map { it.first })
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(0L, 60_000L), harness.rotations.map { it.first })

        // 2nd failure -> 120s backoff (at 180s).
        advanceTimeBy(120_000L - 1L)
        runCurrent()
        assertEquals(listOf(0L, 60_000L), harness.rotations.map { it.first })
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(0L, 60_000L, 180_000L), harness.rotations.map { it.first })

        // 3rd failure sets 240s backoff; networkRestored 10s later resets backoff and retries at once.
        advanceTimeBy(10_000L)
        runCurrent()
        harness.networkRestored.emit(Unit)
        runCurrent()
        assertEquals(listOf(0L, 60_000L, 180_000L, 190_000L), harness.rotations.map { it.first })

        // Background + foreground return also resets backoff and retries immediately.
        harness.foreground.value = false
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        harness.foreground.value = true
        runCurrent()
        assertEquals(listOf(0L, 60_000L, 180_000L, 190_000L, 195_000L), harness.rotations.map { it.first })
        job.cancel()
    }

    @Test
    fun externalTokenRotationReschedulesWithoutDuplicateRefresh() = runTest {
        val harness =
            LoopHarness(
                scope = this,
                token = jwt(expMs = epochMs + ttl24h, iatMs = epochMs),
                mintLifetimeMs = ttl24h,
            )
        val job = harness.start()
        runCurrent()

        // Another caller (e.g. 401 or WebView) rotates the token at 5m.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        val rotatedAt = epochMs + currentTime
        harness.token = jwt(expMs = rotatedAt + ttl24h, iatMs = rotatedAt, sub = "external-rot")
        harness.authEvents.emit(AuthSessionEvent.TokensRotated(1L))
        runCurrent()

        // Original 13m mark passes with zero rotations.
        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS - 5 * 60_000L)
        runCurrent()
        assertTrue(harness.rotations.isEmpty())

        // Rotates at 5m + 13m = 18m.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(listOf(5 * 60_000L + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS), harness.rotations.map { it.first })
        job.cancel()
    }

    @Test
    fun sharedRotatorPersistsTokensEmitsTokensRotatedDeduplicatesAndNeverSignsOutOnFailure() = runTest {
        val initialAccess = jwt(expMs = epochMs + ttl24h, iatMs = epochMs, sub = "user-1")
        val rotatedAccess =
            jwt(
                expMs = epochMs + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS + ttl24h,
                iatMs = epochMs + RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
                sub = "user-1-next",
            )
        val kv = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(kv)
        store.saveSession(authRefreshFixtureSession(initialAccess, "refresh-1"))

        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "refresh-1",
                responses = listOf(AuthApiResult.Success(AuthRefreshResponse(rotatedAccess, "refresh-2"))),
            )
        val authDataSource = SharedAuthDataSource(api = api, sessionStore = store)

        val observedEvents = mutableListOf<AuthSessionEvent>()
        val eventCollector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                store.authSessionEvents.collect { observedEvents += it }
            }

        val foreground = MutableStateFlow(true)
        val clock = RaftAccessTokenClock()
        val rotator =
            SharedAccessTokenAgeRotator(
                sessionStore = store,
                authDataSource = authDataSource,
                authSessionEvents = store.authSessionEvents,
                foreground = foreground,
                nowMillis = { epochMs + currentTime },
                clock = clock,
            )
        val rotatorJob = backgroundScope.launch { rotator.run() }
        runCurrent()

        // At 13m, rotates via single-flight coordinator, updates store, and emits TokensRotated
        // (which SettingsWebViewAccessTokenSyncEffect observes to push into open WebViews).
        advanceTimeBy(RAFT_ACCESS_TOKEN_ROTATE_AGE_MS)
        runCurrent()
        assertEquals(1, api.refreshCalls)
        assertEquals(rotatedAccess, store.readSession()?.accessToken)
        assertEquals("refresh-2", store.readSession()?.refreshToken)
        assertTrue(observedEvents.any { it is AuthSessionEvent.TokensRotated })
        rotatorJob.cancel()
        eventCollector.cancel()

        // Single-flight / already-rotated dedup: calling with stale token issues 0 HTTP refresh calls.
        val deduped = authDataSource.rotateAccessTokenByAge(currentAccessToken = initialAccess)
        assertTrue(deduped is SharedAuthResult.Success)
        assertEquals(rotatedAccess, deduped.data.accessToken)
        assertEquals(1, api.refreshCalls)

        // Non-destructive on transient 503, 403 refusal, and 401 failure (never clears session).
        for (status in listOf(503, 403, 401)) {
            val failStore = InMemoryRaftAuthSessionStore(authRefreshFixtureSession(rotatedAccess, "keep-refresh"))
            val failApi =
                FakeRefreshAuthApi(
                    refreshTokenAssertion = "keep-refresh",
                    responses = listOf(AuthApiResult.Failure(code = status, errorBodyText = """{"error":"refused"}""")),
                )
            val failSource = SharedAuthDataSource(api = failApi, sessionStore = failStore)
            val result = failSource.rotateAccessTokenByAge(currentAccessToken = rotatedAccess)
            assertTrue(result is SharedAuthResult.Failure, "status=$status expected Failure, got $result")
            assertNotNull(failStore.readSession(), "status=$status must preserve session")
            assertEquals(rotatedAccess, failStore.readSession()?.accessToken)
            assertEquals("keep-refresh", failStore.readSession()?.refreshToken)
        }
    }

    @Test
    fun parseRaftAccessTokenTimesHandlesValidAndInvalidTokens() {
        val valid = jwt(expMs = epochMs + ttl24h, iatMs = epochMs)
        val times = parseRaftAccessTokenTimes(valid)
        assertNotNull(times)
        assertEquals(epochMs + ttl24h, times.expiresAtMs)
        assertEquals(epochMs, times.issuedAtMs)
        assertEquals(epochMs + ttl24h, parseRaftAccessTokenExpiryMillis(valid))

        assertNull(parseRaftAccessTokenTimes("opaque"))
        assertNull(parseRaftAccessTokenTimes("a.%%%.c"))
        assertNull(
            raftAccessTokenAgeSchedule(
                token = jwt(expMs = epochMs + ttl24h, iatMs = null),
                receivedAtDeviceMs = epochMs,
                clock = RaftAccessTokenClock(),
            )
        )
        assertFalse(RaftAccessTokenClock().offsetKnown)
    }
}
