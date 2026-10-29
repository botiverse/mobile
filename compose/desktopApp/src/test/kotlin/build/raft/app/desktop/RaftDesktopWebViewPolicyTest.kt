package build.raft.app.desktop

import build.raft.app.RaftDesktopWebViewChannelMessage
import build.raft.app.RaftDesktopWebViewNavigation
import build.raft.app.RaftDesktopWebViewPolicy
import build.raft.app.RaftDesktopWebViewTopLevelCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftDesktopWebViewPolicyTest {
    private val web = "https://raft.example"
    private val artifactUrl = "$web/s/srv/artifacts/a1?embed=1"

    private fun artifactViewer(secret: String = "secret-1") =
        RaftDesktopWebViewPolicy(
            url = artifactUrl,
            allowNavigation = true,
            allowedNavigationSchemes = setOf("http", "https"),
            mainFrameOrigin = web,
            hostEventsEnabled = false,
            injectedJavaScript = "localStorage.setItem('slock_access_token', 't');",
            frameHosts = setOf("raftusercontent.com"),
            channelSecret = secret,
        )

    private fun feedback(secret: String = "secret-1", hostEvents: Boolean = true) =
        RaftDesktopWebViewPolicy(
            url = "$web/feedback?embed=1",
            allowNavigation = false,
            allowedNavigationSchemes = emptySet(),
            mainFrameOrigin = null,
            hostEventsEnabled = hostEvents,
            injectedJavaScript = "",
            channelSecret = secret,
        )

    @Test fun `origin lock keeps top-level documents on the web origin and loads only declared frames`() {
        val policy = artifactViewer()
        assertEquals(RaftDesktopWebViewNavigation.Allow, policy.navigation("$web/s/srv/artifacts/a2?shown=2"))
        // The declared content frame: Wry cannot tell it from a top-level navigation, so it loads and
        // the committed top-level address is checked afterwards.
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("https://w1.raftusercontent.com/f?ticket=x"))
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("https://raftusercontent.com/f"))
        assertFalse(policy.isTrustedTopLevel("https://w1.raftusercontent.com/f?ticket=x"))
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("about:srcdoc"))
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("blob:$web/1"))
        // Anything else off the fence is refused before it loads (a page that never answers the
        // after-the-fact check can't stay).
        listOf(
            "https://content.example/f",
            "https://raftusercontent.com.attacker.example/f",
            "https://attacker.example/raftusercontent.com",
            "https://raftusercontent.com@attacker.example/f",
            "http://w1.raftusercontent.com/f",
            "http://raft.example/s/srv/artifacts/a1",
            "mailto:a@b.c",
            "file:///etc/passwd",
        ).forEach { assertEquals(RaftDesktopWebViewNavigation.Block, policy.navigation(it), it) }
    }

    @Test fun `a surface without declared frames is as strict as iOS`() {
        val policy = feedback()
        assertTrue(policy.isTrustedTopLevel("$web/feedback?embed=1"))
        assertTrue(policy.isTrustedTopLevel("$web/feedback?embed=1#top"))
        assertFalse(policy.isTrustedTopLevel("$web/feedback?embed=2"))
        assertEquals(RaftDesktopWebViewNavigation.Block, policy.navigation("$web/settings"))
        assertEquals(RaftDesktopWebViewNavigation.Block, policy.navigation("https://w1.raftusercontent.com/f"))
    }

    @Test fun `the first load is refused when the configured address fails the fence`() {
        assertEquals(artifactUrl, artifactViewer().initialUrl())
        val offOrigin =
            RaftDesktopWebViewPolicy(
                url = "https://elsewhere.example/a",
                allowNavigation = true,
                allowedNavigationSchemes = setOf("http", "https"),
                mainFrameOrigin = web,
                hostEventsEnabled = false,
                injectedJavaScript = "",
            )
        assertNull(offOrigin.initialUrl())
        val badScheme =
            RaftDesktopWebViewPolicy(
                url = "ftp://raft.example/a",
                allowNavigation = true,
                allowedNavigationSchemes = setOf("http", "https"),
                mainFrameOrigin = null,
                hostEventsEnabled = false,
                injectedJavaScript = "",
            )
        assertNull(badScheme.initialUrl())
    }

    @Test fun `runtime scripts reach only the trusted top-level document`() {
        val policy = artifactViewer()
        assertTrue(policy.canRunRuntimeScript("$web/s/srv/artifacts/a2"))
        assertFalse(policy.canRunRuntimeScript("https://content.example/f"))
        assertFalse(policy.canRunRuntimeScript("about:blank"))
        assertFalse(feedback().canRunRuntimeScript("$web/settings"))
        assertTrue(policy.guardRuntimeScript("x()").startsWith("if (window.location.origin === \"$web\")"))
    }

    @Test fun `the session script only runs in a top-level document of the web origin`() {
        val script = artifactViewer().documentStartScript()
        val guard = "if (window.top === window && (window.location.origin === \"$web\")) {"
        val guardAt = script.indexOf(guard)
        assertTrue(guardAt >= 0, script)
        assertTrue(script.indexOf("slock_access_token") > guardAt)
        // No host bridge when host events are off.
        assertFalse("raftHostPort" in script)
    }

    @Test fun `host messages need the WebView's secret and host events`() {
        val policy = feedback(secret = "s1")
        val body = """{"kind":"ready","payload":{}}"""
        val raw = """{"s":"s1","k":"host","v":${kotlinx.serialization.json.JsonPrimitive(body)}}"""
        assertEquals(RaftDesktopWebViewChannelMessage.Host(body), policy.unwrapChannelMessage(raw))
        assertNull(feedback(secret = "s2").unwrapChannelMessage(raw))
        assertNull(feedback(secret = "s1", hostEvents = false).unwrapChannelMessage(raw))
        assertNull(policy.unwrapChannelMessage("""{"k":"host","v":"x"}"""))
        assertNull(policy.unwrapChannelMessage("not json"))
        assertEquals(
            RaftDesktopWebViewChannelMessage.OpenWindow("https://stripe.example/portal"),
            policy.unwrapChannelMessage("""{"s":"s1","k":"open","v":"https://stripe.example/portal"}"""),
        )
        assertTrue("raftHostPort" in policy.documentStartScript())
    }

    @Test fun `a foreign top-level document has no code path to the secret`() {
        listOf(artifactViewer(secret = "SECRET-123"), feedback(secret = "SECRET-123")).forEach { policy ->
            val script = policy.documentStartScript()
            val originGuard = "if (!(window.location.origin === \"$web\""
            val guardAt = script.indexOf(originGuard)
            assertTrue(guardAt >= 0, script)
            // Nothing but the frame check precedes the origin guard; the secret, the post function,
            // window.open and the click handler all come after it.
            assertEquals(script.indexOf("if (window.top !== window) return;"), script.indexOf("\n") + 1)
            listOf("SECRET-123", "ipcPost", "window.open", "addEventListener").forEach { needle ->
                assertTrue(script.indexOf(needle) > guardAt, needle)
            }
            assertEquals(1, Regex("SECRET-123").findAll(script).count())
            // The engine's post function is captured at document start, never looked up again.
            assertFalse("window.ipc.postMessage(" in script)
        }
        // No web origin (a file preview): no channel at all.
        val file =
            RaftDesktopWebViewPolicy(
                url = "file:///tmp/a.html",
                allowNavigation = false,
                allowedNavigationSchemes = emptySet(),
                mainFrameOrigin = null,
                hostEventsEnabled = true,
                injectedJavaScript = "",
                channelSecret = "SECRET-123",
            ).documentStartScript()
        assertTrue("if (!(false)) return;" in file)
        assertTrue(file.indexOf("SECRET-123") > file.indexOf("if (!(false)) return;"))
    }

    @Test fun `an unanswered check after a declared-frame navigation fails closed`() {
        val check = RaftDesktopWebViewTopLevelCheck(timeoutMs = 500, settleMs = 1_000)
        // Nothing pending: a slow page is just slow.
        val first = assertNotNull(check.beginRequest(nowMs = 1_000))
        assertFalse(check.timedOut(nowMs = 5_000))
        assertEquals(RaftDesktopWebViewTopLevelCheck.Verdict.Trusted, check.answer(first, trusted = true, idle = true, nowMs = 5_000))

        check.markUnverified(nowMs = 6_000)
        val pending = assertNotNull(check.beginRequest(nowMs = 6_050))
        assertNull(check.beginRequest(nowMs = 6_100))
        assertFalse(check.timedOut(nowMs = 6_500))
        assertTrue(check.timedOut(nowMs = 6_600))
        check.reset()
        assertFalse(check.armed)
        // The hung page's late answer is ignored.
        assertEquals(RaftDesktopWebViewTopLevelCheck.Verdict.Stale, check.answer(pending, trusted = true, idle = true, nowMs = 9_000))
    }

    @Test fun `the check stands down only once the trusted page settled`() {
        val check = RaftDesktopWebViewTopLevelCheck(timeoutMs = 500, settleMs = 1_000)
        check.markUnverified(nowMs = 1_000)
        val early = assertNotNull(check.beginRequest(nowMs = 1_050))
        assertEquals(RaftDesktopWebViewTopLevelCheck.Verdict.Trusted, check.answer(early, trusted = true, idle = true, nowMs = 1_060))
        assertTrue(check.armed)
        val loading = assertNotNull(check.beginRequest(nowMs = 2_100))
        check.answer(loading, trusted = true, idle = false, nowMs = 2_110)
        assertTrue(check.armed)
        val settled = assertNotNull(check.beginRequest(nowMs = 2_200))
        check.answer(settled, trusted = true, idle = true, nowMs = 2_210)
        assertFalse(check.armed)
        check.markUnverified(nowMs = 3_000)
        val foreign = assertNotNull(check.beginRequest(nowMs = 3_050))
        assertEquals(RaftDesktopWebViewTopLevelCheck.Verdict.Untrusted, check.answer(foreign, trusted = false, idle = true, nowMs = 3_060))
    }

    @Test fun `the evaluated address is a JSON string`() {
        assertEquals("$web/a?shown=2", RaftDesktopWebViewPolicy.decodeEvaluatedString("\"$web/a?shown=2\""))
        assertNull(RaftDesktopWebViewPolicy.decodeEvaluatedString(""))
        assertNull(RaftDesktopWebViewPolicy.decodeEvaluatedString("{}"))
    }

    @Test fun `the native view's bounds sync reaches the pinned Wry panel`() {
        // RaftDesktopWryNativeView moves the native view by reflection into WryWebViewPanel; a
        // different jar must fail here, not leave web content on screen.
        assertTrue(build.raft.app.RaftDesktopWryPanelAccess.available)
    }

    @Test fun `the macOS arm64 engine bridge ships on the app classpath`() {
        // JNA loads it from here; the packaged app's jar is the same artifact.
        assertNotNull(javaClass.classLoader.getResource("darwin-aarch64/libcomposewebview_wry.dylib"))
    }
}
