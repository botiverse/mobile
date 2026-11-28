package build.raft.app.core.data.auth

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The provider ships in every build (main manifest) enabled (Android 14+ shell cannot enable a
 * component of a signed release), DUMP-protected and without an intent filter, and no build type
 * or flavor changes it. The QA session bootstrap entry
 * points stay off outside alpha/connectedTest. The merged release/play manifests are checked by
 * `verify{Release,Play}IdentityReceiptProvider` in PR CI.
 */
class AppIdentityReceiptProviderManifestTest {
    private val src = locateRepoFile("compose/androidApp/src/main/AndroidManifest.xml").parentFile.parentFile

    @Test
    fun qaSessionBootstrapEntryPointsStayDisabledInStoreBuilds() {
        val main = parse(File(src, "main/AndroidManifest.xml"))
        val gate = "@bool/qa_session_bootstrap_enabled"
        val bootstrap = elements(main, "activity").single { it.getAttribute("android:name") == ".core.data.auth.QaSessionBootstrapActivity" }
        val boundary = elements(main, "receiver").single { it.getAttribute("android:name") == ".core.data.auth.QaCaptureBoundaryReceiver" }
        for (component in listOf(bootstrap, boundary)) {
            assertEquals(gate, component.getAttribute("android:enabled"))
            assertEquals(gate, component.getAttribute("android:exported"))
        }
        assertEquals("false", boolValue(File(src, "main/res/values/booleans.xml"), "qa_session_bootstrap_enabled"))
        val overriding =
            src.walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.path.contains("/res/") && !it.path.contains("/main/") }
                .filter { it.readText().contains("name=\"qa_session_bootstrap_enabled\"") }
                .map { it.relativeTo(src).path.substringBefore('/') }
                .toSet()
        assertEquals(setOf("alpha", "connectedTest"), overriding)
    }

    @Test
    fun mainManifestDeclaresProfileableShellForReleasePerfettoProfiling() {
        val main = parse(File(src, "main/AndroidManifest.xml"))
        val profileable = elements(main, "profileable").single()
        assertEquals("true", profileable.getAttribute("android:shell"))
        assertEquals("true", profileable.getAttribute("android:enabled"))
    }

    private fun boolValue(file: File, name: String): String =
        elements(parse(file), "bool").single { it.getAttribute("name") == name }.textContent.trim()

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file).documentElement

    private fun elements(root: Element, tag: String): List<Element> =
        root.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
}
