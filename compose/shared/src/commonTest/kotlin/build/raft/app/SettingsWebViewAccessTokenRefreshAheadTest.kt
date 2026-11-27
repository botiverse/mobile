package build.raft.app

import build.raft.app.settings.SETTINGS_WEBVIEW_REFRESH_LEAD_MS
import build.raft.app.settings.SETTINGS_WEBVIEW_REFRESH_RETRY_MS
import build.raft.app.settings.SettingsWebViewTokenClock
import build.raft.app.settings.runSettingsWebViewAccessTokenRefreshAhead
import build.raft.app.settings.settingsWebViewAccessTokenExpiryMillis
import build.raft.app.settings.settingsWebViewTokenSchedule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * #300: a signed-in WebView (the Artifact viewer, the settings pages) holds only the 15-minute
 * access token, so the App refreshes it about 2 minutes before it expires while the surface is on
 * screen and the App is in the foreground. Virtual time; the wall clock starts at [epochMs].
 */
@OptIn(ExperimentalEncodingApi::class)
class SettingsWebViewAccessTokenRefreshAheadTest {
    private val epochMs = 1_800_000_000_000L
    private val lifetimeMs = 15 * 60_000L

    private fun jwt(expMs: Long, sub: String = "user-maya", iatMs: Long? = null): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = b64.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val iat = iatMs?.let { ""","iat":${it / 1_000}""" }.orEmpty()
        val payload = b64.encode("""{"sub":"$sub","type":"access"$iat,"exp":${expMs / 1_000}}""".encodeToByteArray())
        return "$header.$payload.signature"
    }

    /**
     * A fake session: its token, and a refresh that mints one (with `iat`) valid for [mintLifetimeMs]
     * by the server's clock. The device clock runs [deviceAheadMs] ahead of the server's.
     */
    private inner class Session(
        private val scope: TestScope,
        var token: String,
        var refreshSucceeds: Boolean = true,
        private val deviceAheadMs: Long = 0L,
        private val mintLifetimeMs: Long = lifetimeMs,
    ) {
        val refreshes = mutableListOf<Pair<Long, String>>()
        val logs = mutableListOf<String>()
        val clock = SettingsWebViewTokenClock()
        private var minted = 0

        fun now() = epochMs + scope.currentTime + deviceAheadMs

        fun serverNow() = epochMs + scope.currentTime

        suspend fun refresh(reason: String, expiring: String): Boolean {
            refreshes += scope.currentTime to reason
            assertEquals(token, expiring, "refresh names the token it replaces")
            if (!refreshSucceeds) return false
            token = jwt(serverNow() + mintLifetimeMs, sub = "user-maya-${++minted}", iatMs = serverNow())
            return true
        }

        fun run() =
            scope.launch {
                runSettingsWebViewAccessTokenRefreshAhead(
                    surface = "artifact-viewer",
                    readAccessToken = { token },
                    nowMillis = ::now,
                    refresh = ::refresh,
                    logger = { logs += it },
                    clock = clock,
                )
            }
    }

    @Test
    fun readsTheExpiryOfTheAccessToken() {
        assertEquals(epochMs + lifetimeMs, settingsWebViewAccessTokenExpiryMillis(jwt(epochMs + lifetimeMs)))
        assertNull(settingsWebViewAccessTokenExpiryMillis("opaque-token"))
        assertNull(settingsWebViewAccessTokenExpiryMillis("a.%%%.c"))
    }

    @Test
    fun refreshesTwoMinutesBeforeExpiryAndAgainForEachNewToken() = runTest {
        val session = Session(this, jwt(epochMs + lifetimeMs))
        val job = session.run()
        val firstAt = lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS
        advanceTimeBy(firstAt - 1)
        runCurrent()
        assertTrue(session.refreshes.isEmpty(), "nothing before exp - 2 min, got ${session.refreshes}")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(firstAt to "webview_token_expiring"), session.refreshes)
        // The new token (15 min from then) is refreshed 13 min later.
        advanceTimeBy(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS)
        runCurrent()
        assertEquals(listOf(firstAt, 2 * firstAt), session.refreshes.map { it.first })
        assertTrue(session.logs.all { "outcome=refreshed" in it }, "logs: ${session.logs}")
        job.cancel()
    }

    @Test
    fun stopsWhenTheSurfaceLeavesOrTheAppGoesToTheBackground() = runTest {
        val session = Session(this, jwt(epochMs + lifetimeMs))
        // The caller cancels the loop (no surface on screen, or the lifecycle drops below STARTED).
        val job = session.run()
        advanceTimeBy(5 * 60_000L)
        job.cancel()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertTrue(session.refreshes.isEmpty(), "no refresh after the loop stopped, got ${session.refreshes}")
    }

    @Test
    fun backInTheForegroundAnExpiredOrNearlyExpiredTokenIsRefreshedAtOnce() = runTest {
        // Expired while in the background: the loop starts again on return and refreshes first.
        val expired = Session(this, jwt(epochMs - 60_000L))
        val first = expired.run()
        runCurrent()
        assertEquals(listOf(0L to "webview_token_expired"), expired.refreshes)
        first.cancel()
        // One minute left (inside the 2 min lead): refreshed at once too.
        val nearly = Session(this, jwt(epochMs + currentTime + 60_000L))
        val second = nearly.run()
        runCurrent()
        assertEquals(listOf(currentTime to "webview_token_expiring"), nearly.refreshes)
        second.cancel()
    }

    @Test
    fun aFailedRefreshIsRetriedLaterNotInALoop() = runTest {
        val session = Session(this, jwt(epochMs + 60_000L), refreshSucceeds = false)
        val job = session.run()
        runCurrent()
        advanceTimeBy(SETTINGS_WEBVIEW_REFRESH_RETRY_MS - 1)
        runCurrent()
        assertEquals(listOf(0L), session.refreshes.map { it.first })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, SETTINGS_WEBVIEW_REFRESH_RETRY_MS), session.refreshes.map { it.first })
        assertTrue(session.logs.all { "outcome=failed" in it }, "logs: ${session.logs}")
        job.cancel()
    }

    @Test
    fun aTokenRotatedElsewhereMovesTheScheduleWithoutARefresh() = runTest {
        val session = Session(this, jwt(epochMs + lifetimeMs))
        val job = session.run()
        // The App's own request refreshed after 5 min: the next refresh is 13 min after that.
        advanceTimeBy(5 * 60_000L)
        session.token = jwt(epochMs + currentTime + lifetimeMs, sub = "rotated")
        advanceTimeBy(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS - 5 * 60_000L)
        runCurrent()
        assertTrue(session.refreshes.isEmpty(), "the old token's time passed without a refresh, got ${session.refreshes}")
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(listOf(5 * 60_000L + lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS), session.refreshes.map { it.first })
        job.cancel()
    }

    @Test
    fun aNewTokenBornInsideTheLeadWindowIsNotRefreshedAgainAtOnce() = runTest {
        // A 90 s token (shorter than the 2 min lead): every new one is "due" at once by its exp.
        val shortMs = 90_000L
        val session = Session(this, jwt(epochMs + shortMs, iatMs = epochMs), mintLifetimeMs = shortMs)
        val job = session.run()
        runCurrent()
        assertEquals(listOf(0L), session.refreshes.map { it.first })
        // Not again before half its lifetime (45 s, under the 60 s floor).
        advanceTimeBy(shortMs / 2 - 1)
        runCurrent()
        assertEquals(listOf(0L), session.refreshes.map { it.first })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, shortMs / 2), session.refreshes.map { it.first })
        job.cancel()
    }

    @Test
    fun aFastDeviceClockRefreshesOnceThenOnScheduleNotInALoop() = runTest {
        // The device runs 20 min ahead: by its clock every new token has already expired.
        val ahead = 20 * 60_000L
        val session = Session(this, jwt(epochMs + lifetimeMs, iatMs = epochMs), deviceAheadMs = ahead)
        val job = session.run()
        runCurrent()
        // Its offset is unknown at first: one refresh at once, which teaches it.
        assertEquals(listOf(0L), session.refreshes.map { it.first })
        advanceTimeBy(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS - 1)
        runCurrent()
        assertEquals(listOf(0L), session.refreshes.map { it.first }, "no loop: ${session.logs}")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS), session.refreshes.map { it.first })
        job.cancel()
    }

    @Test
    fun aSlowDeviceClockStillRefreshesBeforeTheTokenExpires() = runTest {
        // The device runs 20 min behind: by its clock the token would last 35 min.
        val session = Session(this, jwt(epochMs + lifetimeMs, iatMs = epochMs), deviceAheadMs = -20 * 60_000L)
        val job = session.run()
        advanceTimeBy(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS - 1)
        runCurrent()
        assertTrue(session.refreshes.isEmpty(), "got ${session.refreshes}")
        advanceTimeBy(1)
        runCurrent()
        // Received + lifetime − 2 min, whatever the device clock says.
        assertEquals(listOf(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS), session.refreshes.map { it.first })
        // And the next one on the same schedule.
        advanceTimeBy(lifetimeMs - SETTINGS_WEBVIEW_REFRESH_LEAD_MS)
        runCurrent()
        assertEquals(2, session.refreshes.size, "got ${session.refreshes}")
        job.cancel()
    }

    @Test
    fun anUnknownOffsetIsNoEvidenceFromTheDeviceClock() {
        val clock = SettingsWebViewTokenClock()
        val ahead = 20 * 60_000L
        val token = jwt(epochMs + lifetimeMs, iatMs = epochMs)
        // A device 20 min ahead, no refresh yet in this run: not "expired 5 min ago" (the device clock
        // against `exp`), but its lifetime from when it was first seen.
        assertEquals(false, clock.offsetKnown)
        assertEquals(lifetimeMs, clock.remainingMillis(token, epochMs + ahead))
        assertEquals(lifetimeMs - 5 * 60_000L, clock.remainingMillis(token, epochMs + ahead + 5 * 60_000L))
        // Seen first by the refresh loop: counted from then.
        val other = jwt(epochMs + lifetimeMs, sub = "user-maya-2", iatMs = epochMs)
        clock.noteSeen(other, epochMs + ahead - 60_000L)
        assertEquals(lifetimeMs - 60_000L, clock.remainingMillis(other, epochMs + ahead))
        // Without `iat` and no offset: unknown, never the device clock.
        assertNull(clock.remainingMillis(jwt(epochMs + lifetimeMs, sub = "user-maya-3"), epochMs + ahead))
        // Learned from a token minted just now: the server's clock from then on, for every token.
        val fresh = jwt(epochMs + 60_000L + lifetimeMs, sub = "user-maya-4", iatMs = epochMs + 60_000L)
        clock.learnFromFreshToken(fresh, epochMs + ahead + 60_000L)
        assertEquals(true, clock.offsetKnown)
        assertEquals(lifetimeMs, clock.remainingMillis(fresh, epochMs + ahead + 60_000L))
        assertEquals(lifetimeMs - 60_000L, clock.remainingMillis(token, epochMs + ahead + 60_000L))
        assertEquals(lifetimeMs - 60_000L, clock.remainingMillis(jwt(epochMs + lifetimeMs, sub = "user-maya-3"), epochMs + ahead + 60_000L))
        // The schedule reads the server's clock too once the offset is known.
        assertEquals(SETTINGS_WEBVIEW_REFRESH_LEAD_MS, settingsWebViewTokenSchedule(fresh, epochMs + ahead + 60_000L, clock)?.let {
            epochMs + ahead + 60_000L + lifetimeMs - it.refreshAtDeviceMs
        })
    }

    @Test
    fun aTokenWithoutExpiryOrNoSessionEndsTheLoop() = runTest {
        val logs = mutableListOf<String>()
        var refreshed = 0
        runSettingsWebViewAccessTokenRefreshAhead(
            surface = "billing",
            readAccessToken = { "opaque" },
            nowMillis = { epochMs },
            refresh = { _, _ -> refreshed++; true },
            logger = { logs += it },
        )
        runSettingsWebViewAccessTokenRefreshAhead(
            surface = "billing",
            readAccessToken = { null },
            nowMillis = { epochMs },
            refresh = { _, _ -> refreshed++; true },
        )
        assertEquals(0, refreshed)
        assertEquals(listOf("settings_webview_refresh_ahead surface=billing outcome=no_expiry"), logs)
    }
}
