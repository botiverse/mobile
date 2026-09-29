package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN Computer terminology to 「计算机」 (task #103, #proj-i18n:d7a3610b).
 *
 * artin/AngLee unified the Raft Computer concept to 计算机. English computer/computers/machine
 * (the Raft Computer concept) -> 计算机; English device (a physical device) -> keep 设备.
 * The Computer concept had been scattered across both 电脑 and 设备; this locks the unification
 * and protects genuine physical-device 设备 copy.
 */
class ComputerTerminologyResourceTest {

    // Raft Computer-concept keys (mix of formerly-电脑 and formerly-misused-设备) that must
    // read 计算机 and carry no 电脑/设备 residue.
    private val computerConceptKeys =
        listOf(
            "settings_computers_title",
            "settings_computers_add_computer",
            "settings_computers_cloud_computer",
            "settings_computers_your_computer",
            "settings_computers_delete_title",
            "settings_computers_detail_subtitle",
            "settings_computers_kind_computer",
            "settings_section_computer_detail_title",
            "member_label_computer",
            "agent_create_label_computer",
            "agent_create_placeholder_select_computer",
            "agent_create_state_computers_empty",
            "thread_action_card_detail_required_computer",
            "thread_action_card_detail_suggested_computer",
            "home_notification_machine_offline_body_one"
        )

    // Genuine physical-device keys that must keep 设备 and never become 计算机.
    private val physicalDeviceKeys =
        listOf(
            "settings_account_logout_detail",
            "settings_account_logout_confirm",
            "settings_notifications_on_device",
            "settings_notifications_test_detail",
            "settings_notifications_permission_allowed_detail",
            "settings_storage_row_database_detail",
            "settings_diagnostics_label_device_facts",
            "settings_diagnostics_state_device",
            "thread_image_preview_photos_permission_restricted",
            "host_feedback_prompt_message"
        )

    // Touched Computer-concept keys that carry placeholders, for base/zh token parity.
    private val placeholderParityKeys =
        listOf(
            "agent_create_hint_required_computer",
            "agent_create_hint_suggested_computer",
            "member_dialog_confirm_unassign",
            "thread_action_card_detail_required_computer",
            "thread_action_card_detail_suggested_computer",
            "thread_action_card_error_load_computers",
            "settings_computers_create_agent_error_load_computers"
        )

    // Android native catalog (compose/androidApp values-zh-rCN) Computer-concept keys; the
    // native catalog is a SEPARATE copy from the shared MR catalog and carried residue of the
    // merged Computer batch (caught by the #267 review census).
    private val nativeComputerConceptKeys =
        listOf(
            "settings_computers_title",
            "settings_computers_add",
            "settings_computers_empty_title",
            "settings_computers_empty_detail",
            "settings_computers_subtitle",
            "settings_computer_detail_title",
            "settings_computer_detail_empty",
            "settings_computer_detail_info_section",
            "settings_computer_detail_no_workspaces_detail",
            "settings_computer_detail_no_agents_detail"
        )

    // Android native genuine physical-device keys that must keep 设备.
    private val nativePhysicalDeviceKeys =
        listOf(
            "auth_reset_description",
            "settings_about_device",
            "settings_language_restart_hint",
            "settings_account_chat_layout_detail",
            "settings_account_logout_detail"
        )

    @Test
    fun zhCatalogHasNoLegacyComputerTermResidue() {
        val zh = readStrings("zh-rCN")
        val residue = zh.filterValues { it.contains("电脑") }
        assertTrue(
            residue.isEmpty(),
            "zh-rCN must not contain 「电脑」 for the Raft Computer concept; found: ${residue.keys}"
        )
    }

    @Test
    fun raftComputerConceptKeysUseComputerTerm() {
        val zh = readStrings("zh-rCN")
        computerConceptKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("计算机"), "zh-rCN $key must use 计算机, got: $value")
            assertTrue(!value.contains("电脑"), "zh-rCN $key must not contain 电脑, got: $value")
            assertTrue(!value.contains("设备"), "zh-rCN $key must not contain 设备, got: $value")
        }
    }

    @Test
    fun physicalDeviceKeysKeepDeviceTerm() {
        val zh = readStrings("zh-rCN")
        physicalDeviceKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("设备"), "zh-rCN $key (physical device) must keep 设备, got: $value")
            assertTrue(!value.contains("计算机"), "zh-rCN $key (physical device) must not become 计算机, got: $value")
        }
    }

    @Test
    fun placeholderParityAcrossTouchedComputerKeys() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        placeholderParityKeys.forEach { key ->
            assertTrue(key in base, "base missing $key")
            assertTrue(key in zh, "zh-rCN missing $key")
        }
        assertEquals(
            emptyList(),
            computerPlaceholderMismatches(base, zh, placeholderParityKeys),
            "every touched Computer key must keep base/zh placeholder tokens identical"
        )
    }

    @Test
    fun placeholderParityDetectsAccidentalPlaceholderMutation() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")

        val victim = "thread_action_card_detail_required_computer"
        assertEquals(emptyList(), computerPlaceholderMismatches(base, zh, listOf(victim)))
        val mutatedZh = zh.toMutableMap().also { it[victim] = it.getValue(victim) + " %2\$s" }
        assertEquals(
            listOf(victim),
            computerPlaceholderMismatches(base, mutatedZh, listOf(victim)),
            "adding a stray placeholder to a zh value must be flagged"
        )

        val dropVictim = "agent_create_hint_required_computer"
        val zhDropped = zh.toMutableMap().also { it[dropVictim] = "必须使用的计算机" }
        assertEquals(
            listOf(dropVictim),
            computerPlaceholderMismatches(base, zhDropped, listOf(dropVictim)),
            "dropping a placeholder from a zh value must be flagged"
        )
    }

    @Test
    fun androidNativeCatalogHasNoLegacyComputerTermResidue() {
        val zh = readAndroidNativeStrings()
        val residue = zh.filterValues { it.contains("电脑") }
        assertTrue(
            residue.isEmpty(),
            "androidApp zh-rCN must not contain 「电脑」 for the Raft Computer concept; found: ${residue.keys}"
        )
    }

    @Test
    fun androidNativeComputerConceptKeysUseComputerTerm() {
        val zh = readAndroidNativeStrings()
        nativeComputerConceptKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("计算机"), "androidApp zh-rCN $key must use 计算机, got: $value")
            assertTrue(!value.contains("电脑"), "androidApp zh-rCN $key must not contain 电脑, got: $value")
        }
        // The subtitle also carried a double miss: misused 设备 for machines + untranslated daemon.
        val subtitle = zh.getValue("settings_computers_subtitle")
        assertTrue(subtitle.contains("守护进程"), "androidApp zh-rCN settings_computers_subtitle must use 守护进程, got: $subtitle")
        assertTrue(!subtitle.contains("设备"), "androidApp zh-rCN settings_computers_subtitle must not misuse 设备, got: $subtitle")
        assertTrue(!subtitle.contains("daemon"), "androidApp zh-rCN settings_computers_subtitle must not keep daemon untranslated, got: $subtitle")
    }

    @Test
    fun androidNativePhysicalDeviceKeysKeepDeviceTerm() {
        val zh = readAndroidNativeStrings()
        nativePhysicalDeviceKeys.forEach { key ->
            val value = zh.getValue(key)
            assertTrue(value.contains("设备"), "androidApp zh-rCN $key (physical device) must keep 设备, got: $value")
            assertTrue(!value.contains("计算机"), "androidApp zh-rCN $key (physical device) must not become 计算机, got: $value")
            assertTrue(!value.contains("电脑"), "androidApp zh-rCN $key (physical device) must not contain 电脑, got: $value")
        }
    }

    private fun computerPlaceholderMismatches(
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
