package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Kuikly-era `SlockSandboxWebViewRenderViewTest` (deleted with the Kuikly WebView
 * adapter in 000694012) to the CMP sandbox WebView's fence helpers, plus the origin rule and the
 * bootstrap navigation fence the old render view carried untested.
 */
class RaftSandboxWebViewFenceTest {
    @Test
    fun sameOriginHistoryRouteCannotReuseFencedNativeBridge() {
        val trusted = "https://example.test/feedback/?embed=raft-settings-v1"

        assertTrue(raftIsExactMainDocumentUrl(trusted, "https://example.test/feedback/?embed=raft-settings-v1#draft"))
        assertFalse(raftIsExactMainDocumentUrl(trusted, "https://example.test/account/?embed=raft-settings-v1"))
        assertFalse(raftIsExactMainDocumentUrl(trusted, "https://example.test/feedback/?embed=other"))
    }

    @Test
    fun exactMainDocumentRequiresSameSchemeHostAndPort() {
        val trusted = "https://example.test/feedback/?embed=raft-settings-v1"

        assertFalse(raftIsExactMainDocumentUrl(trusted, "http://example.test/feedback/?embed=raft-settings-v1"))
        assertFalse(raftIsExactMainDocumentUrl(trusted, "https://evil.test/feedback/?embed=raft-settings-v1"))
        assertFalse(raftIsExactMainDocumentUrl(trusted, "https://example.test:8443/feedback/?embed=raft-settings-v1"))
        assertFalse(raftIsExactMainDocumentUrl(null, trusted))
        assertFalse(raftIsExactMainDocumentUrl(trusted, "not a url"))
    }

    @Test
    fun documentStartScriptsAreScopedToTheTargetOrigin() {
        assertEquals("https://example.test", raftDocumentStartScriptOriginRule("https://Example.TEST/feedback/?a=1#x"))
        assertEquals("http://example.test:8080", raftDocumentStartScriptOriginRule("http://example.test:8080/path"))
        assertNull(raftDocumentStartScriptOriginRule("file:///android_asset/index.html"))
        assertNull(raftDocumentStartScriptOriginRule("javascript:alert(1)"))
        assertNull(raftDocumentStartScriptOriginRule("https:///no-host"))
        assertEquals(
            "https://example.test/__raft_mobile_bootstrap__",
            raftInjectedJavaScriptBootstrapBaseUrl("https://example.test/feedback/"),
        )
    }

    @Test
    fun bootstrapDocumentNeverUncoversBeforeTheTargetStarts() {
        val fence = RaftWebViewBootstrapNavigationFence()
        // No navigation in flight: nothing to hold back.
        assertTrue(fence.mayUncover("https://example.test/anything"))

        val target = "https://example.test/feedback/"
        fence.begin(target)
        // The bootstrap document finishing must not uncover the page.
        fence.onMainFrameStarted("https://example.test/__raft_mobile_bootstrap__")
        assertFalse(fence.mayUncover("https://example.test/__raft_mobile_bootstrap__"))
        assertFalse(fence.mayUncover(target))

        fence.onMainFrameStarted(target)
        assertTrue(fence.mayUncover(target))
        assertFalse(fence.mayUncover("https://example.test/other"))

        // A new navigation re-arms the fence.
        fence.begin("https://example.test/next")
        assertFalse(fence.mayUncover("https://example.test/next"))
        fence.reset()
        assertTrue(fence.mayUncover("https://example.test/next"))
    }
}
