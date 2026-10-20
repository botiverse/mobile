package build.raft.app.core.data.auth

import build.raft.app.data.RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE
import build.raft.app.data.RAFT_APP_LOGIN_MMKV_NAMESPACE
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.SharedApiEnvironmentStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Backup and device transfer must never copy sign-in state to another install: a cloned refresh
 * token and installation id make two devices share one session family, and one of them is signed
 * out after the other refreshes. Parses the shipped rule files and checks every section excludes
 * the auth, push and account-bound stores.
 */
class RaftBackupRulesTest {
    private data class Rule(val domain: String, val path: String)

    private val requiredExclusions: Set<Rule> =
        buildSet {
            listOf(
                RAFT_AUTH_SESSION_MMKV_NAMESPACE,
                RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
                RAFT_APP_LOGIN_MMKV_NAMESPACE,
                RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
                "slockMobileOAuth",
            ).forEach { namespace ->
                add(Rule("file", "mmkv/$namespace"))
                add(Rule("file", "mmkv/$namespace.crc"))
            }
            val database = RaftAndroidSqlDatabaseContext.DatabaseFileName
            listOf("", "-journal", "-wal", "-shm").forEach { suffix -> add(Rule("database", "$database$suffix")) }
            add(Rule("file", "qa-bootstrap/"))
            add(Rule("file", "raft_diagnostics_logs/"))
        }

    /** App-scope namespaces that hold nothing account- or install-bound and may be backed up. */
    private val backedUpNamespaces = setOf(RaftLocalPreferenceStore.KuiklyNamespace, SharedApiEnvironmentStore.Namespace)

    @Test
    fun legacyFullBackupContentExcludesAuthStores() {
        val root = parse("backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertExcludesAuthStores("full-backup-content", root)
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeAuthStores() {
        val root = parse("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        val sections = listOf("cloud-backup", "device-transfer").map { name -> name to section(root, name) }
        sections.forEach { (name, section) -> assertExcludesAuthStores(name, section) }
        // Both API 31+ sections and the legacy file stay identical, so no path can drift.
        val legacy = rules(parse("backup_rules.xml"))
        sections.forEach { (name, section) -> assertEquals("$name rules differ from backup_rules.xml", legacy, rules(section)) }
    }

    @Test
    fun everySharedAppScopeNamespaceIsExcludedUnlessKnownHarmless() {
        val excluded = rules(parse("backup_rules.xml")).filter { it.domain == "file" }.map { it.path }.toSet()
        androidAppScopeSharedKeyValueNamespaces
            .filterNot { it in backedUpNamespaces }
            .forEach { namespace ->
                assertTrue("mmkv/$namespace is not excluded from backup", "mmkv/$namespace" in excluded)
            }
    }

    @Test
    fun manifestPointsAtBothRuleFiles() {
        val manifest = locate("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    private fun assertExcludesAuthStores(section: String, element: Element) {
        val rules = rules(element)
        val missing = requiredExclusions - rules
        assertTrue("$section misses exclusions: $missing", missing.isEmpty())
        val included = element.getElementsByTagName("include").length
        assertEquals("$section must not use include rules (they would back up only the listed paths)", 0, included)
    }

    private fun rules(element: Element): Set<Rule> {
        val nodes = element.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { index -> nodes.item(index) as Element }
            .map { rule -> Rule(rule.getAttribute("domain"), rule.getAttribute("path")) }
            .toSet()
    }

    private fun section(root: Element, name: String): Element {
        val nodes = root.getElementsByTagName(name)
        assertEquals("expected exactly one <$name>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    private fun parse(fileName: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(locate("src/main/res/xml/$fileName"))
            .documentElement

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
