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

    @Test fun `the session script only runs in a top-level document of the web origin`() {
        val script = artifactViewer().documentStartScript()
        val guard = "if (window.top === window && (window.location.origin === \"$web\")) {"
        val guardAt = script.indexOf(guard)
        assertTrue(guardAt >= 0, script)
        assertTrue(script.indexOf("slock_access_token") > guardAt)
        // No host bridge when host events are off.
        assertFalse("raftHostPort" in script)

        // Unlocked Settings surfaces (allowNavigation = true, mainFrameOrigin = null) also guard
        // injectedJavaScript to the configured web origin so external redirects never run it.
        val unlockedSettingsScript =
            RaftDesktopWebViewPolicy(
                url = "$web/s/srv/settings/billing?embed=raft-settings-v1&shell=host",
                allowNavigation = true,
                allowedNavigationSchemes = setOf("http", "https"),
                mainFrameOrigin = null,
                hostEventsEnabled = false,
                injectedJavaScript = "localStorage.setItem('slock_access_token', 't');",
                channelSecret = "secret-2",
            ).documentStartScript()
        val unlockedGuardAt = unlockedSettingsScript.indexOf(guard)
        assertTrue(unlockedGuardAt >= 0, unlockedSettingsScript)
        assertTrue(unlockedSettingsScript.indexOf("slock_access_token") > unlockedGuardAt)
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

    @Test fun `the macOS arm64 engine bridge ships on the app classpath`() {
        // JNA loads it from here; the packaged app's jar is the same artifact.
        assertNotNull(javaClass.classLoader.getResource("darwin-aarch64/libcomposewebview_wry.dylib"))
    }
}
