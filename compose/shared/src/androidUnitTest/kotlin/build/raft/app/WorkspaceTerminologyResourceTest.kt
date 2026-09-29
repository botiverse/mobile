package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN Workspace terminology split (task #103, #proj-i18n:257f5b7b, AngLee ruling).
 *
 * English "workspace" has two distinct meanings in the app:
 * - the Raft server/scope concept (navigation, notifications, auth, connected apps) -> 工作空间
 * - an agent's local files/directories (member workspace tabs, computer workspace scans) -> 工作区
 * The zh-rCN catalog had flattened both to 工作区; this locks the semantic split and protects
 * the agent-filesystem 工作区 copy.
 */
class WorkspaceTerminologyResourceTest {

    // Server/scope-semantics keys that must read 工作空间 and carry no 工作区 residue.
    private val serverScopeKeys =
        listOf(
            "settings_navigation_section_workspace",
            "settings_notifications_subtitle",
            "auth_server_create_hint",
            "home_error_timeout_detail",
            "home_error_network_detail",
            "search_error_permission_detail",
            "settings_workspace_translation",
            "settings_mobile_multi_shell_host_detail",
            "settings_mobile_home_server_avatar",
            "settings_mobile_home_server_avatar_detail",
            "settings_notifications_workspace",
            "settings_notifications_mute_workspace",
            "settings_notifications_mode_all_detail",
            "settings_notifications_mode_none_detail",
            "settings_computers_install_daemon_hint",
            "settings_connected_apps_no_installed_marketplace",
            "settings_connected_apps_unavailable",
            "settings_connected_apps_uninstall_detail",
            "settings_server_profile_subtitle",
            "settings_server_placeholder_name"
        )

    // Agent local-filesystem keys that must keep 工作区 and never become 工作空间.
    private val agentFilesystemKeys =
        listOf(
            // task #374: settings_computers_subtitle removed — AngLee's 2026-09-01 ruling
            // (已连接计算机和守护进程状态) drops the workspace concept entirely.

            "member_state_loading_workspace",
            "member_tab_workspace",
            "member_section_workspace",
            "member_state_no_workspace_skills",
            "member_state_workspace_loading",
            "member_dialog_full_reset_warning",
            "member_dialog_restart_description",
            "member_dialog_reset_session_description",
            "member_dialog_full_reset_description",
            "member_error_workspace_hidden",
            "settings_computers_error_load_workspaces",
            "settings_computers_empty_workspaces",
            "settings_computers_empty_workspaces_detail",
            "settings_computers_error_offline",
            "settings_computers_section_workspaces",
            "settings_computers_workspace_scan_title",
            "settings_computers_workspace_scan_detail"
        )

    // Workspace keys that carry placeholders, for base/zh token parity (one per side).
    private val placeholderParityKeys =
        listOf(
            "settings_connected_apps_uninstall_detail",
            "member_error_workspace_hidden"
        )

    // Android native catalog (compose/androidApp values-zh-rCN): server/scope keys that must
    // read 工作空间. The native catalog is a SEPARATE copy from the shared MR catalog and was
    // caught by review (task #267) — both copies must stay aligned.
    private val nativeServerScopeKeys =
        listOf(
            "auth_create_description",
            "settings_billing_trial_detail",
            "settings_billing_subtitle"
            // task #374: the two widget descriptions were removed — AngLee's ruled copy
            // (未读动态和收藏计数 / 实时 Agent 动态) drops the 选中的 Raft 工作空间的 prefix.
        )

    // Android native catalog keys whose workspaces are the agent-local directories on a
    // computer; they keep 工作区.
    private val nativeAgentFilesystemKeys =
        listOf(
            "settings_computer_detail_workspaces_section",
            "settings_computer_detail_no_workspaces",
            "settings_computer_detail_no_workspaces_detail",
            "settings_computer_detail_workspaces_error_title"
        )

    @Test
    fun zhServerScopeKeysUseWorkspaceConceptTerm() {
        val zh = readStrings("zh-rCN")
        serverScopeKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("工作空间"), "zh-rCN $key (server/scope) must use 工作空间, got: $value")
            assertTrue(!value.contains("工作区"), "zh-rCN $key (server/scope) must not contain 工作区, got: $value")
        }
    }

    @Test
    fun zhAgentFilesystemKeysKeepWorkspaceDirTerm() {
        val zh = readStrings("zh-rCN")
        agentFilesystemKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("工作区"), "zh-rCN $key (agent filesystem) must keep 工作区, got: $value")
            assertTrue(!value.contains("工作空间"), "zh-rCN $key (agent filesystem) must not become 工作空间, got: $value")
        }
    }

    @Test
    fun androidNativeServerScopeKeysUseWorkspaceConceptTerm() {
        val zh = readAndroidNativeStrings()
        nativeServerScopeKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("工作空间"), "androidApp zh-rCN $key (server/scope) must use 工作空间, got: $value")
            assertTrue(!value.contains("工作区"), "androidApp zh-rCN $key (server/scope) must not contain 工作区, got: $value")
        }
    }

    @Test
    fun androidNativeAgentFilesystemKeysKeepWorkspaceDirTerm() {
        val zh = readAndroidNativeStrings()
        nativeAgentFilesystemKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("工作区"), "androidApp zh-rCN $key (agent filesystem) must keep 工作区, got: $value")
            assertTrue(!value.contains("工作空间"), "androidApp zh-rCN $key (agent filesystem) must not become 工作空间, got: $value")
        }
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
            String(Files.readAllBytes(resourcesRoot().resolve(locale).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun readAndroidNativeStrings(): Map<String, String> {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("../androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("compose/androidApp/src/main/res/values-zh-rCN/strings.xml")
            )
        val path =
            candidates.firstOrNull(Files::isRegularFile)
                ?: error("Unable to locate androidApp values-zh-rCN/strings.xml from $workingDirectory")
        return STRING_ENTRY.findAll(String(Files.readAllBytes(path), Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/resources/MR"),
                workingDirectory.resolve("shared/src/commonMain/resources/MR"),
                workingDirectory.resolve("compose/shared/src/commonMain/resources/MR")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared MR resources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
