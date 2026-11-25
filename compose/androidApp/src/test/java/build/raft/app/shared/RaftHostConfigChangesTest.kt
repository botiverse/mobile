package build.raft.app.shared

import android.content.res.Configuration
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The page hosts keep their state across configuration changes by handling them in place
 * (Compose follows the new Configuration) instead of being recreated. An OS light/dark switch
 * (uiMode) recreated the host, which reset the bottom tab to Home and dropped the open page.
 */
class RaftHostConfigChangesTest {
    private val hostActivities =
        listOf(
            ".MainActivity",
            ".shared.RaftCmpActivity",
            ".shared.RaftLegacyHostActivity",
            ".shared.RaftAppShellActivity",
            ".shared.RaftDocumentShellActivity",
        )

    private val pageHostActivities =
        listOf(
            ".shared.RaftCmpActivity",
            ".shared.RaftLegacyHostActivity",
            ".shared.RaftAppShellActivity",
            ".shared.RaftDocumentShellActivity",
        )

    @Test
    fun everyPageHostHandlesUiModeLikeOrientationAndScreenSize() {
        val activities = manifestActivities()
        hostActivities.forEach { name ->
            val activity = requireNotNull(activities[name]) { "$name missing from the manifest" }
            val handled = activity.getAttribute("android:configChanges").split('|').toSet()
            listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "uiMode").forEach { change ->
                assertTrue("$name must handle $change in place (configChanges=$handled)", change in handled)
            }
        }
    }

    @Test
    fun everyPageHostDeclaresStateHiddenAndAdjustResizeSoftInputMode() {
        val activities = manifestActivities()
        pageHostActivities.forEach { name ->
            val activity = requireNotNull(activities[name]) { "$name missing from the manifest" }
            val softInputFlags =
                activity.getAttribute("android:windowSoftInputMode")
                    .split('|')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            assertTrue(
                "$name must include stateHidden to avoid auto-raising IME on window refocus (flags=$softInputFlags)",
                "stateHidden" in softInputFlags,
            )
            assertTrue(
                "$name must include adjustResize so Compose receives IME insets (flags=$softInputFlags)",
                "adjustResize" in softInputFlags,
            )
        }
    }

    private fun manifestActivities(): Map<String, Element> {
        val root =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(locate("src/main/AndroidManifest.xml"))
                .documentElement
        val nodes = root.getElementsByTagName("activity")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associateBy { it.getAttribute("android:name") }
    }

    private fun locate(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            File(current, "androidApp/$relativePath").takeIf { it.isFile }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
