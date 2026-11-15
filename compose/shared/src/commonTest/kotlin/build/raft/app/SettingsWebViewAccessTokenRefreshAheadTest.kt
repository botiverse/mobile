package build.raft.app

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

    private fun jwt(expMs: Long, sub: String = "user-maya"): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = b64.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload = b64.encode("""{"sub":"$sub","type":"access","exp":${expMs / 1_000}}""".encodeToByteArray())
        return "$header.$payload.signature"
    }

    /** A fake session: its token, and a refresh that mints one valid for [lifetimeMs] from now. */
    private inner class Session(
        private val scope: TestScope,
        var token: String,
        var refreshSucceeds: Boolean = true,
    ) {
        val refreshes = mutableListOf<Pair<Long, String>>()
        val logs = mutableListOf<String>()
        private var minted = 0

        fun now() = epochMs + scope.currentTime

        suspend fun refresh(reason: String, expiring: String): Boolean {
            refreshes += scope.currentTime to reason
            assertEquals(token, expiring, "refresh names the token it replaces")
            if (!refreshSucceeds) return false
            token = jwt(now() + lifetimeMs, sub = "user-maya-${++minted}")
            return true
        }

        fun run(lead: Long = SETTINGS_WEBVIEW_REFRESH_LEAD_MS) =
            scope.launch {
                runSettingsWebViewAccessTokenRefreshAhead(
                    surface = "artifact-viewer",
                    readAccessToken = { token },
                    nowMillis = ::now,
                    refresh = ::refresh,
                    logger = { logs += it },
                    leadMillis = lead,
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
