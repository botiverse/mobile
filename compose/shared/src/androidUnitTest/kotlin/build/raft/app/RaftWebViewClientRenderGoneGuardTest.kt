package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every Android `WebView(...)` the app constructs must get a client that handles
 * `onRenderProcessGone` (and returns true): all of an app's WebViews share one renderer, and when
 * it dies Chromium kills the app unless every WebView on it claims the death. A WebView without
 * such a client (the startup warm-up used to be one) turns any page crash into an app crash.
 *
 * Narrow static check over the Android production sources: each construction site must be one
 * of the [RegisteredSites], whose file wires the handler ([RegisteredSite.handler]); the
 * behaviour of those handlers is covered by `RaftWebViewRendererGoneTest` and androidApp's
 * `RaftAndroidWebViewWarmupRendererGoneTest`. A new site: give it a client that handles
 * `onRenderProcessGone` (reuse `RaftAndroidSandboxWebView`, or `RaftDetachedWebViewClient` for a
 * WebView with no page), test it, then register it here.
 */
class RaftWebViewClientRenderGoneGuardTest {
    private data class RegisteredSite(val sites: Int, val handler: String)

    @Test
    fun everyWebViewConstructionSiteIsRegisteredWithItsRenderGoneHandler() {
        val compose = repositoryFile("compose")
        assertTrue(compose.isDirectory, "compose sources not found at ${compose.path}")
        val found =
            compose.listFiles().orEmpty()
                .filter { it.isDirectory }
                .flatMap { module -> File(module, "src").listFiles().orEmpty().filter(::isProductionSourceSet) }
                .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
                .mapNotNull { file ->
                    val count = file.readLines().count { line -> WebViewConstruction.containsMatchIn(code(line)) }
                    if (count == 0) null else file.relativeTo(compose.parentFile).invariantSeparatorsPath to count
                }
                .toMap()

        val unregistered = found.filterKeys { it !in RegisteredSites }
        if (unregistered.isNotEmpty()) {
            fail(
                "New Android WebView construction site(s) ${unregistered}. Each WebView needs a client whose " +
                    "onRenderProcessGone cleans it up and returns true, or one renderer crash kills the app. " +
                    "Use RaftAndroidSandboxWebView or RaftDetachedWebViewClient, test it, and register it in " +
                    "RaftWebViewClientRenderGoneGuardTest.RegisteredSites."
            )
        }
        RegisteredSites.forEach { (path, site) ->
            assertEquals(site.sites, found[path] ?: 0, "WebView construction sites in $path (update RegisteredSites)")
            val source = repositoryFile(path).readText()
            assertTrue(source.contains(site.handler), "$path must wire `${site.handler}` for its WebViews")
        }
    }

    private fun isProductionSourceSet(dir: File): Boolean =
        dir.isDirectory && !dir.name.contains("test", ignoreCase = true)

    /** The code part of a line: comment lines and trailing `//` comments dropped. */
    private fun code(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
        return line.substringBefore("//")
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() }
            ?: File(start, relativePath)
    }

    private companion object {
        /** `WebView(` / `android.webkit.WebView(` as a call or supertype; not `RaftWebView(`, `WKWebView(`. */
        val WebViewConstruction = Regex("""(?<![\w.])(?:android\.webkit\.)?WebView\s*\(""")

        const val SandboxPath = "compose/shared/src/androidMain/kotlin/build/raft/app/RaftSandboxWebView.android.kt"

        val RegisteredSites =
            mapOf(
                // Every product page (in-app browser, settings pages, Artifact viewer, file preview,
                // kept-alive and prewarmed views): its WebViewClient's onRenderProcessGone rebuilds it.
                SandboxPath to RegisteredSite(sites = 2, handler = "override fun onRenderProcessGone"),
                // The startup warm-up: let go when its renderer dies.
                "compose/androidApp/src/main/java/build/raft/app/shared/RaftAndroidWebViewWarmup.kt" to
                    RegisteredSite(sites = 1, handler = "RaftDetachedWebViewClient("),
                // Standalone cross-platform web container module (:web-container): handles renderer
                // process termination through WebContainerWatchdog circuit breaker and returns true.
                "compose/web-container/src/androidMain/kotlin/build/raft/webcontainer/WebContainerHost.android.kt" to
                    RegisteredSite(sites = 2, handler = "override fun onRenderProcessGone"),
                // Ephemeral window.open transport WebView (:web-container): destroys transport cleanly
                // on renderer process termination and returns true.
                "compose/web-container/src/androidMain/kotlin/build/raft/webcontainer/WebContainerAndroidChromeSupport.kt" to
                    RegisteredSite(sites = 1, handler = "override fun onRenderProcessGone"),
            )
    }
}
