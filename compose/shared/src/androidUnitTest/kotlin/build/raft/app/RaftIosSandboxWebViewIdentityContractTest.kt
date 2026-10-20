package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Production-wiring check for the iOS WKWebView host (no iOS test runner on the JVM lane).
 *
 * Kotlin/Native does not guarantee that an Objective-C object coming back from Objective-C (a
 * WKNavigationDelegate argument, `configuration.userContentController`) is the same Kotlin
 * reference Kotlin already holds, so `source !== webView` cancelled the main document's navigation
 * policy and left the HTML attachment preview blank white. WebKit objects must be compared with
 * [raftIsSameObjCObject] (Objective-C pointer identity), never with `===` / `!==`.
 */
class RaftIosSandboxWebViewIdentityContractTest {
    private val source =
        repositoryFile("compose/shared/src/iosMain/kotlin/build/raft/app/RaftSandboxWebView.ios.kt").readText()

    @Test
    fun webKitObjectsAreNeverComparedByKotlinReference() {
        val referenceComparisons =
            source.lines()
                .filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") } }
                .map { line -> line.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"") }
                .filter { line -> line.contains("===") || line.contains("!==") }
                .map(String::trim)
        // The only reference comparison left is between two Kotlin RaftWebViewController objects.
        assertEquals(listOf("if (boundController === controller) return"), referenceComparisons)
    }

    @Test
    fun everyDelegateCallbackChecksThePointerIdentityOfItsWebView() {
        listOf(
            "decidePolicy" to "if (!raftIsSameObjCObject(source, webView)) return false",
            "didStartProvisional" to "if (!raftIsSameObjCObject(source, webView)) return",
            "didCommit" to "if (!raftIsSameObjCObject(source, webView)) return",
            "didFinish" to "if (!raftIsSameObjCObject(source, webView)) return",
            "didFail" to "if (!raftIsSameObjCObject(source, webView) ||",
            "contentProcessDidTerminate" to "if (!raftIsSameObjCObject(source, webView)) return",
            "createWindowBlocked" to "if (!raftIsSameObjCObject(source, webView)) return",
            "didReceiveScriptMessage" to
                "if (!raftIsSameObjCObject(controller, view.configuration.userContentController) ||",
        ).forEach { (callback, guard) ->
            val body = source.substringAfter("internal fun $callback(", missingDelimiterValue = "")
            assertTrue(body.isNotEmpty(), "missing callback $callback")
            assertTrue(body.substringBefore("internal fun ").contains(guard), "$callback must start with `$guard`")
        }
        assertTrue(source.contains("a.objcPtr() == b.objcPtr()"), "identity must compare Objective-C pointers")
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}
