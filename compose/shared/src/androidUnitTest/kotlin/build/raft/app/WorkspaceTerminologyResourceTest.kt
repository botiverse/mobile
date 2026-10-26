package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN Workspace terminology split (task #103, #proj-i18n:257f5b7b, AngLee ruling).
 *
 * English "workspace" has two distinct meanings in the app:
 * - the Raft server/scope concept (navigation, notifications, auth, connected apps) -> 工作空间
 * - an agent's local files/directories (member workspace tabs, computer workspace scans) -> 工作区
 * The scan below checks every string in every strings.xml (shared MR and androidApp native) by
 * the key's domain, so a new key is covered without being listed here.
 */
class WorkspaceTerminologyResourceTest {

    // Workspace keys that carry placeholders, for base/zh token parity (one per side).
    private val placeholderParityKeys =
        listOf(
            "settings_connected_apps_uninstall_detail",
            "member_error_workspace_hidden"
        )

    @Test
    fun everyCatalogKeepsTheWorkspaceTermSplit() {
        val catalogs = allStringCatalogs()
        val zhCatalogs = catalogs.keys.filter(::isZhCatalog)
        assertTrue(zhCatalogs.size >= 2, "expected the shared MR and androidApp zh-rCN catalogs, found: $zhCatalogs")
        assertTrue(catalogs.size > zhCatalogs.size, "expected base/ja catalogs as well, found: ${catalogs.keys}")
        val violations =
            catalogs.flatMap { (name, strings) ->
                workspaceTermViolations(strings, zh = isZhCatalog(name)).map { "$name: $it" }
            }
        assertEquals(emptyList(), violations, "workspace term split (工作空间 = server, 工作区 = agent files)")
    }

    @Test
    fun workspaceTermScanFlagsEveryKindOfViolation() {
        val clean =
            mapOf(
                "settings_notifications_workspace" to "工作空间通知",
                "member_tab_workspace" to "工作区",
                "settings_computers_section_workspaces" to "工作区",
                "settings_computers_install_daemon_hint" to "将其连接到此工作空间。"
            )
        assertEquals(emptyList(), workspaceTermViolations(clean, zh = true))

        val brokenZh =
            mapOf(
                "settings_notifications_workspace" to "工作区通知",
                "member_tab_workspace" to "工作空间",
                "agent_migration_note" to "工作空间和工作区",
                "auth_hint" to "workspace"
            )
        assertEquals(
            listOf("settings_notifications_workspace", "member_tab_workspace", "agent_migration_note"),
            workspaceTermViolations(brokenZh, zh = true).map { it.substringBefore(" ") }
        )
        assertEquals(
            listOf("auth_hint"),
            workspaceTermViolations(mapOf("auth_hint" to "工作空间"), zh = false).map { it.substringBefore(" ") }
        )
    }

    @Test
    fun placeholderParityAcrossWorkspaceKeys() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        placeholderParityKeys.forEach { key ->
            assertTrue(key in base, "base missing $key")
            assertTrue(key in zh, "zh-rCN missing $key")
        }
        assertEquals(
            emptyList(),
            workspacePlaceholderMismatches(base, zh, placeholderParityKeys),
            "every workspace key must keep base/zh placeholder tokens identical"
        )
    }

    @Test
    fun placeholderParityDetectsAccidentalPlaceholderMutation() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")

        val victim = "settings_connected_apps_uninstall_detail"
        assertEquals(emptyList(), workspacePlaceholderMismatches(base, zh, listOf(victim)))
        val mutatedZh = zh.toMutableMap().also { it[victim] = it.getValue(victim) + " %2\$s" }
        assertEquals(
            listOf(victim),
            workspacePlaceholderMismatches(base, mutatedZh, listOf(victim)),
            "adding a stray placeholder to a zh value must be flagged"
        )

        val zhDropped = zh.toMutableMap().also { it[victim] = "从此工作空间卸载并撤销其访问权限。" }
        assertEquals(
            listOf(victim),
            workspacePlaceholderMismatches(base, zhDropped, listOf(victim)),
            "dropping a placeholder from a zh value must be flagged"
        )
    }

    private fun workspacePlaceholderMismatches(
        base: Map<String, String>,
        zh: Map<String, String>,
        keys: List<String>
    ): List<String> =
        keys.filter { key ->
            placeholderTokens(base.getValue(key)) != placeholderTokens(zh.getValue(key))
        }

    private fun placeholderTokens(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toList()

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * The zh rule: a key in an agent-filesystem domain (an agent's local files and directories)
     * says 工作区; every other key means the Raft server/scope and says 工作空间. No value uses
     * both. Other locales carry neither Chinese term.
     */
    private fun workspaceTermViolations(strings: Map<String, String>, zh: Boolean): List<String> =
        strings.mapNotNull { (key, value) ->
            val server = value.contains(SERVER_TERM)
            val files = value.contains(FILES_TERM)
            when {
                !zh && (server || files) -> "$key has a zh workspace term in a non-zh catalog: $value"
                server && files -> "$key mixes 工作空间 and 工作区: $value"
                isAgentFilesystemKey(key) && server -> "$key (agent files) must use 工作区, got: $value"
                !isAgentFilesystemKey(key) && files -> "$key (server/scope) must use 工作空间, got: $value"
                else -> null
            }
        }

    private fun isAgentFilesystemKey(key: String): Boolean =
        key !in SERVER_SCOPE_KEYS_IN_AGENT_DOMAINS && AGENT_FILESYSTEM_KEY_PREFIXES.any(key::startsWith)

    private fun isZhCatalog(name: String): Boolean =
        name.substringAfterLast('/').let { it.startsWith("zh") || it.startsWith("values-zh") }

    // Every strings.xml in the shared moko-resources catalog (base and each locale dir) and the
    // androidApp native res catalog (values and each values-* dir), keyed "<root>/<dir>".
    private fun allStringCatalogs(): Map<String, Map<String, String>> {
        val roots = mapOf("MR" to resourcesRoot(), "androidApp/res" to androidResRoot())
        return roots.flatMap { (rootName, root) ->
            Files.list(root).use { dirs ->
                dirs.toList()
                    .map { it.resolve("strings.xml") }
                    .filter(Files::isRegularFile)
                    .map { file -> "$rootName/${file.parent.fileName}" to parseStrings(file) }
            }
        }.toMap()
    }

    private fun parseStrings(file: Path): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun androidResRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("../androidApp/src/main/res"),
                workingDirectory.resolve("androidApp/src/main/res"),
                workingDirectory.resolve("compose/androidApp/src/main/res")
            )
        return candidates.firstOrNull(Files::isDirectory)?.normalize()
            ?: error("Unable to locate androidApp res from $workingDirectory")
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/composeResources"),
                workingDirectory.resolve("shared/src/commonMain/composeResources"),
                workingDirectory.resolve("compose/shared/src/commonMain/composeResources")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
        const val SERVER_TERM = "工作空间"
        const val FILES_TERM = "工作区"

        // Key domains about an agent's local files: member pages, agent migration, and computers.
        val AGENT_FILESYSTEM_KEY_PREFIXES =
            listOf(
                "member_",
                "agent_migration_",
                "raft_pro_migration_",
                "settings_computers_",
                "settings_computer_detail_"
            )

        // Keys inside those domains that still mean the Raft server ("connect the daemon to this
        // workspace").
        val SERVER_SCOPE_KEYS_IN_AGENT_DOMAINS = setOf("settings_computers_install_daemon_hint")
    }
}
