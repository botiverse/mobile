@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebKit's form accessory bar (#proj-mobile:#194): the app's own pages show none, so on iOS 26 it
 * cannot float over a bottom sheet kept above the keyboard; the in-app browser keeps it.
 */
class RaftIosWebViewFormAccessoryIosTest {
    private val hosts = mutableListOf<RaftIosSandboxWebView>()

    @AfterTest
    fun disposeHosts() {
        hosts.forEach { it.dispose() }
    }

    private fun config(showsFormAccessoryBar: Boolean? = null): RaftWebViewConfig {
        val base =
            RaftWebViewConfig(
                url = "https://app.raft.build/",
                allowScripts = true,
                allowNavigation = false,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = false,
                ephemeral = true,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )
        return if (showsFormAccessoryBar == null) base else base.copy(showsFormAccessoryBar = showsFormAccessoryBar)
    }

    private fun hostedWebView(config: RaftWebViewConfig): Pair<RaftIosSandboxWebView, RaftIosWebView> {
        val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val host = RaftIosSandboxWebView(container, RaftWebViewEvents())
        hosts += host
        host.applyConfig(config)
        return host to assertIs<RaftIosWebView>(container.subviews.single())
    }

    @Test
    fun theWebViewLeavesTheFormAccessoryBarOutUnlessAsked() {
        val view = RaftIosWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = WKWebViewConfiguration())
        assertFalse(view.showsFormAccessoryBar)
        assertNull(view.inputAccessoryView())
    }

    @Test
    fun appPagesHideTheBarAndTheInAppBrowserKeepsIt() {
        assertFalse(config().showsFormAccessoryBar)
        val (_, appPage) = hostedWebView(config())
        assertFalse(appPage.showsFormAccessoryBar)
        assertNull(appPage.inputAccessoryView())

        val (_, browser) = hostedWebView(config(showsFormAccessoryBar = true))
        assertTrue(browser.showsFormAccessoryBar)
    }

    @Test
    fun aConfigChangeReachesTheShownWebViewWithoutARebuild() {
        val (host, view) = hostedWebView(config())
        host.applyConfig(config(showsFormAccessoryBar = true))
        val container = view.superview
        assertTrue(view.showsFormAccessoryBar)
        // Same WKWebView: the flag alone does not rebuild the page.
        assertTrue(raftIsSameObjCObject(container?.subviews?.single(), view))
        host.applyConfig(config(showsFormAccessoryBar = false))
        assertFalse(view.showsFormAccessoryBar)
        assertNull(view.inputAccessoryView())
    }
}
