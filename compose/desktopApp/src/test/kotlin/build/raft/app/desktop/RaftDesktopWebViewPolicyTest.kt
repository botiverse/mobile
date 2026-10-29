package build.raft.app.desktop

import build.raft.app.RaftDesktopWebViewChannelMessage
import build.raft.app.RaftDesktopWebViewNavigation
import build.raft.app.RaftDesktopWebViewPolicy
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

    @Test fun `origin lock keeps top-level documents on the web origin and lets frames load`() {
        val policy = artifactViewer()
        assertEquals(RaftDesktopWebViewNavigation.Allow, policy.navigation("$web/s/srv/artifacts/a2?shown=2"))
        // The content frame: Wry cannot tell it from a top-level navigation, so it loads and the
        // committed top-level address is checked afterwards.
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("https://content.example/f?ticket=x"))
        assertFalse(policy.isTrustedTopLevel("https://content.example/f?ticket=x"))
        assertFalse(policy.isTrustedTopLevel("http://raft.example/s/srv/artifacts/a1"))
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("about:srcdoc"))
        assertEquals(RaftDesktopWebViewNavigation.Block, policy.navigation("mailto:a@b.c"))
        assertEquals(RaftDesktopWebViewNavigation.Block, policy.navigation("file:///etc/passwd"))
    }

    @Test fun `a fenced surface trusts only its own document`() {
        val policy = feedback()
        assertTrue(policy.isTrustedTopLevel("$web/feedback?embed=1"))
        assertTrue(policy.isTrustedTopLevel("$web/feedback?embed=1#top"))
        assertFalse(policy.isTrustedTopLevel("$web/feedback?embed=2"))
        assertFalse(policy.isTrustedTopLevel("$web/settings"))
        assertEquals(RaftDesktopWebViewNavigation.AllowUnverifiedFrame, policy.navigation("$web/settings"))
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

    @Test fun `the evaluated address is a JSON string`() {
        assertEquals("$web/a?shown=2", RaftDesktopWebViewPolicy.decodeEvaluatedString("\"$web/a?shown=2\""))
        assertNull(RaftDesktopWebViewPolicy.decodeEvaluatedString(""))
        assertNull(RaftDesktopWebViewPolicy.decodeEvaluatedString("{}"))
    }

    @Test fun `the macOS arm64 engine bridge ships on the app classpath`() {
        // JNA loads it from here; the packaged app's jar is the same artifact.
        assertNotNull(javaClass.classLoader.getResource("darwin-aarch64/libcomposewebview_wry.dylib"))
    }
}
