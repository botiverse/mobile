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
            String(Files.readAllBytes(resourcesRoot().resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
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
    }
}
