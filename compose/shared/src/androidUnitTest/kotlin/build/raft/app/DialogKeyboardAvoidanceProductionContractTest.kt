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
    fun bothEditorOverloadsSubscribeToAndForwardNativeKeyboardHeight() {
        val basicWidget = commonMainSource("BasicWidget.kt")

        assertTrue(
            basicWidget.contains("LocalSlockDialogKeyboardHeightChange.current"),
            "TextField must read the hosting dialog's keyboard-height sink"
        )

        val overloads = basicWidget.split("internal fun TextField(").drop(1)
        assertTrue(
            overloads.size >= 2,
            "expected both the String and TextFieldValue TextField overloads to be present"
        )
        overloads.forEach { overload ->
            val label =
                if (overload.substringBefore("\n) {").contains("value: TextFieldValue")) {
                    "TextFieldValue"
                } else {
                    "String"
                }
            assertTrue(
                overload.contains(".keyboardHeightChange {"),
                "TextField($label) must subscribe to native keyboardHeightChange events; dropping " +
                    "the whole registration strands every editor built on that overload"
            )
            assertTrue(
                overload.substringAfter(".keyboardHeightChange {")
                    .substringBefore("}")
                    .contains("dialogKeyboardHeightChange(params)"),
                "TextField($label) must forward its keyboard params to the hosting dialog"
            )
        }
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
    fun runtimeApiKeyActionRowIsFixedOutsideTheWeightedScrollBody() {
        val runtimeDialog = runtimeConfigDialog()
        val (scrollBody, afterScrollBody) = runtimeDialog.splitAroundTrailingLambda("LazyColumn(")

        assertTrue(
            runtimeDialog.contains("Modifier.fillMaxWidth().weight(1f)"),
            "Runtime/API-key scroll body must take its height by weight, not a fixed cap"
        )
        assertTrue(
            !runtimeDialog.contains("Modifier.fillMaxWidth().heightIn("),
            "A capped scroll body is measured before the trailing action row and can consume the " +
                "whole keyboard-reduced viewport; it must not come back"
        )
        assertTrue(
            !scrollBody.contains(SAVE_ACTION_LABEL),
            "Save/Cancel must not live inside the LazyColumn; buttons placed in the list scroll " +
                "away with the form instead of staying reachable above the keyboard"
        )
        assertTrue(
            afterScrollBody.contains("Row(") && afterScrollBody.contains(SAVE_ACTION_LABEL),
            "Save/Cancel must be a fixed Row after the LazyColumn so it is measured before the list"
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
    /**
     * Splits the trailing lambda of [marker]'s call into its contents and everything after it,
     * so "inside the list" and "after the list" can be told apart instead of compared by index.
     */
    private fun String.splitAroundTrailingLambda(marker: String): Pair<String, String> {
        val markerAt = indexOf(marker)
        require(markerAt >= 0) { "marker not found: $marker" }
        val open = indexOf(") {", markerAt)
        require(open >= 0) { "no trailing lambda found for: $marker" }
        var depth = 0
        var i = open + 2
        while (i < length) {
            when (this[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(open + 3, i) to substring(i + 1)
                }
            }
            i++
        }
        error("unbalanced trailing lambda for: $marker")
    }

    private companion object {
        const val SAVE_ACTION_LABEL = "MR.strings.member_action_save_runtime_config"
    }
}
