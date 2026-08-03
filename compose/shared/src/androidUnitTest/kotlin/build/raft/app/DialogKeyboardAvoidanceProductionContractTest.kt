package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DialogKeyboardAvoidanceProductionContractTest {
    @Test
    fun realAgentApiKeyDialogsEnableKeyboardAvoidanceAndRetainScrollableBodies() {
        val createAgent = commonMainSource("SharedCreateAgentDialog.kt")
        val members = commonMainSource("MembersPage.kt")

        val createDialog = createAgent.substringAfter("internal fun SharedCreateAgentDialog(")
            .substringBefore("private fun CreateAgentDialog")
        assertTrue(createDialog.contains("keyboardAvoidanceEnabled = true"))
        assertTrue(createDialog.contains("LazyColumn("))

        val runtimeDialog = members.substringAfter("private fun MembersAgentRuntimeConfigDialog(")
            .substringBefore("internal sealed interface MembersAgentRuntimeConfigResolution")
        assertTrue(runtimeDialog.contains("keyboardAvoidanceEnabled = true"))
        assertTrue(runtimeDialog.contains("LazyColumn("))
        assertTrue(runtimeDialog.contains("providerApiKey"))
    }

    private fun commonMainSource(fileName: String): String =
        listOf(
            File("src/commonMain/kotlin/build/raft/app/$fileName"),
            File("shared/src/commonMain/kotlin/build/raft/app/$fileName")
        ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate commonMain source: $fileName")
}
