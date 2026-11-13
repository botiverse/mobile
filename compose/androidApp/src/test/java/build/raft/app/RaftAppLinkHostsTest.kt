package build.raft.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The web hosts the app claims at the OS level. On Android 11 and earlier, an autoVerify intent filter makes
 * the system verify every https host named by any of the app's web intent filters, and one host that fails
 * fails them all. app.slock.ai's assetlinks.json is a redirect to app.raft.build, which Android verification
 * does not follow, so claiming it stopped app.raft.build links from opening the app.
 * Old slock.ai links inside messages are still recognised in-app (RaftMessageLinks), not here.
 */
class RaftAppLinkHostsTest {
    private val sourceSets = listOf("main", "alpha", "play", "debug", "release")

    @Test
    fun noManifestClaimsASlockAiHost() {
        requireNotNull(manifest("main")) { "main manifest missing" }
        val claimed =
            sourceSets.flatMap { set ->
                manifest(set)?.let { root -> webIntentFilters(root).flatMap { hosts(it) }.map { "$set:$it" } }.orEmpty()
            }
        val slock = claimed.filter { it.substringAfter(':').let { host -> host == "slock.ai" || host.endsWith(".slock.ai") } }
        assertTrue("slock.ai hosts must not be claimed as App Links: $slock", slock.isEmpty())
    }

    @Test
    fun verifiedAppLinksStayOnTheRaftHosts() {
        val root = requireNotNull(manifest("main")) { "main manifest missing" }
        val verified = webIntentFilters(root).filter { it.getAttribute("android:autoVerify") == "true" }
        assertEquals(1, verified.size)
        assertEquals(setOf("app.raft.build", "open.raft.build"), hosts(verified.single()).toSet())
    }

    @Test
    fun theWebRootFilterKeepsAppRaftBuild() {
        val root = requireNotNull(manifest("main")) { "main manifest missing" }
        val rootFilters =
            webIntentFilters(root).filter { filter ->
                dataElements(filter).any { it.hasAttribute("android:path") && it.getAttribute("android:path") == "/" }
            }
        assertEquals(1, rootFilters.size)
        assertEquals(setOf("app.raft.build"), hosts(rootFilters.single()).toSet())
    }

    private fun webIntentFilters(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("intent-filter")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { filter ->
                val actions = filter.getElementsByTagName("action")
                val isView =
                    (0 until actions.length).any {
                        (actions.item(it) as Element).getAttribute("android:name") == "android.intent.action.VIEW"
                    }
                isView && dataElements(filter).any { it.getAttribute("android:scheme").startsWith("http") }
            }
    }

    private fun dataElements(filter: Element): List<Element> {
        val nodes = filter.getElementsByTagName("data")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun hosts(filter: Element): List<String> =
        dataElements(filter).map { it.getAttribute("android:host") }.filter { it.isNotEmpty() }

    private fun manifest(sourceSet: String): Element? =
        locate("src/$sourceSet/AndroidManifest.xml")?.let { file ->
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        }

    private fun locate(relativePath: String): File? {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            File(current, "androidApp/$relativePath").takeIf { it.isFile }?.let { return it }
            current = current.parentFile ?: return null
        }
        return null
    }
}
