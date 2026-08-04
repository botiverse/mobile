package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the production wiring that keeps agent/API-key dialog action rows reachable while the
 * native keyboard is open.
 *
 * Kuikly exposes no `WindowInsets.ime` / `imePadding`, so the behavior is assembled from links no
 * unit test can exercise end to end: the editor forwards native `keyboardHeightChange` events, the
 * dialog turns them into an effective bottom clearance, each production caller opts in, and the
 * runtime/API-key body reserves its action row instead of letting a capped scroll area consume the
 * keyboard-reduced viewport.
 *
 * These are structural gates, not behavioral coverage. They go red when a link is deleted from the
 * source; they do not prove the keyboard geometry is correct on a device. The geometry itself is
 * asserted against `slockUnifiedDialogEffectiveBottomInset` in `SlockModalComponentsTest`, which
 * production actually calls. Device behavior is out of scope here.
 */
class DialogKeyboardAvoidanceProductionContractTest {
    @Test
    fun editorsForwardNativeKeyboardHeightToTheHostingDialog() {
        val basicWidget = commonMainSource("BasicWidget.kt")

        assertTrue(
            basicWidget.contains("LocalSlockDialogKeyboardHeightChange.current"),
            "TextField must read the hosting dialog's keyboard-height sink"
        )
        val keyboardHeightBlocks = basicWidget.split(".keyboardHeightChange {").drop(1)
        assertTrue(
            keyboardHeightBlocks.isNotEmpty(),
            "TextField must subscribe to native keyboardHeightChange events"
        )
        assertTrue(
            keyboardHeightBlocks.all {
                it.substringBefore("}").contains("dialogKeyboardHeightChange(params)")
            },
            "every editor keyboardHeightChange block must forward its params to the hosting " +
                "dialog; a single overload dropping the forward is enough to strand an editor"
        )
    }

    @Test
    fun dialogConsumesKeyboardHeightAsEffectiveBottomClearance() {
        val modal = commonMainSource("SlockModalComponents.kt")

        assertTrue(
            modal.contains("LocalSlockDialogKeyboardHeightChange provides"),
            "The dialog surface must publish a keyboard-height sink to its content"
        )
        assertTrue(
            modal.contains("keyboardHeightDp = params.height"),
            "The dialog surface must record the native keyboard height it receives"
        )
        assertTrue(
            modal.contains("bottomInset = effectiveBottomInset"),
            "The dialog outer clearance must consume the keyboard-aware effective bottom inset"
        )
    }

    @Test
    fun createAgentDialogOptsIntoKeyboardAvoidance() {
        assertTrue(
            createAgentDialog().contains("keyboardAvoidanceEnabled = true"),
            "Create Agent dialog must opt into keyboard avoidance"
        )
    }

    @Test
    fun runtimeApiKeyDialogOptsIntoKeyboardAvoidance() {
        val runtimeDialog = runtimeConfigDialog()

        assertTrue(
            runtimeDialog.contains("keyboardAvoidanceEnabled = true"),
            "Runtime/API-key dialog must opt into keyboard avoidance"
        )
        assertTrue(
            runtimeDialog.contains("providerApiKey"),
            "Runtime/API-key dialog must still host the API key editor this contract protects"
        )
    }

    @Test
    fun addComputerDialogOptsIntoKeyboardAvoidance() {
        assertTrue(
            commonMainSource("AddComputerDialog.kt").contains("keyboardAvoidanceEnabled = true"),
            "Add Computer dialog must opt into keyboard avoidance"
        )
    }

    @Test
    fun runtimeApiKeyBodyFillsTheKeyboardReducedDialogHeight() {
        assertTrue(
            runtimeConfigDialog().contains("Modifier.fillMaxWidth().fillMaxHeight()"),
            "Runtime/API-key body must fill the dialog height so its children share a bounded viewport"
        )
    }

    @Test
    fun runtimeApiKeyScrollIsWeightedAndTheActionRowTrailsIt() {
        val runtimeDialog = runtimeConfigDialog()
        val weightedScroll = runtimeDialog.indexOf("Modifier.fillMaxWidth().weight(1f)")
        val actionRow = runtimeDialog.indexOf("MR.strings.member_action_save_runtime_config")

        assertTrue(
            weightedScroll >= 0,
            "Runtime/API-key scroll body must take its height by weight, not a fixed cap"
        )
        assertTrue(
            !runtimeDialog.contains("Modifier.fillMaxWidth().heightIn("),
            "A capped scroll body is measured before the trailing action row and can consume the " +
                "whole keyboard-reduced viewport; it must not come back"
        )
        assertTrue(
            actionRow >= 0 && actionRow > weightedScroll,
            "The Save/Cancel row must trail the weighted scroll body so it is measured first"
        )
    }

    private fun createAgentDialog(): String =
        commonMainSource("SharedCreateAgentDialog.kt")
            .substringAfter("internal fun SharedCreateAgentDialog(")
            .substringBefore("private fun CreateAgentDialog")

    private fun runtimeConfigDialog(): String =
        commonMainSource("MembersPage.kt")
            .substringAfter("private fun MembersAgentRuntimeConfigDialog(")
            .substringBefore("internal sealed interface MembersAgentRuntimeConfigResolution")

    private fun commonMainSource(fileName: String): String =
        listOf(
            File("src/commonMain/kotlin/build/raft/app/$fileName"),
            File("shared/src/commonMain/kotlin/build/raft/app/$fileName")
        ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate commonMain source: $fileName")
}
