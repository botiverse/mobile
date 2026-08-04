package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MembersComputersProductionContractTest {
    @Test
    fun createAgentProductionEffectsAndManualRefreshUseSnapshotAdmissionExecutors() {
        val source = commonMainSource("build/raft/app/SharedCreateAgentDialog.kt")
        assertRuntimeProductionWiring(source)
    }

    @Test
    fun productionContractRejectsEachRuntimeAdmissionBypassMutation() {
        val source = commonMainSource("build/raft/app/SharedCreateAgentDialog.kt")
        val automaticModels =
            source
                .substringAfter("LaunchedEffect(availableSelectedMachineId, runtime, external) {")
                .substringBefore("\n    LaunchedEffect(")
        val builtInForm =
            source
                .substringAfter("val formStore = createAgentFormStore ?: return@LaunchedEffect")
                .substringBefore("\n    LaunchedEffect(builtInForm)")
        val manualModels =
            source
                .substringAfter("onRuntimeModelsRefresh = {")
                .substringBefore("\n                            machineRuntimeIds =")

        val automaticBypass =
            source.replaceFirst(
                automaticModels,
                automaticModels.replaceFirst(
                    "executeCreateAgentRuntimeModelsRequestIfAvailable(",
                    "onRuntimeModelsRequest("
                )
            )
        val builtInBypass =
            source.replaceFirst(
                builtInForm,
                builtInForm.replaceFirst(
                    "executeCreateAgentBuiltInFormRefreshIfAvailable(",
                    "refreshCreateAgentDialogBuiltInForm("
                )
            )
        val manualBypass =
            source.replaceFirst(
                manualModels,
                manualModels.replaceFirst(
                    "executeCreateAgentRuntimeModelsRequestIfAvailable(",
                    "onRuntimeModelsRequest("
                )
            )

        assertFailsWith<AssertionError> { assertRuntimeProductionWiring(automaticBypass) }
        assertFailsWith<AssertionError> { assertRuntimeProductionWiring(builtInBypass) }
        assertFailsWith<AssertionError> { assertRuntimeProductionWiring(manualBypass) }
    }

    private fun assertRuntimeProductionWiring(source: String) {
        val admission =
            source.substringBetween(
                "// CREATE_AGENT_RUNTIME_REQUEST_ADMISSION_START",
                "// CREATE_AGENT_RUNTIME_REQUEST_ADMISSION_END"
            )
        val production = source.replace(admission, "")

        assertEquals(
            2,
            production.countOccurrences("executeCreateAgentRuntimeModelsRequestIfAvailable("),
            "automatic load and manual refresh must both cross the executable snapshot admission boundary"
        )
        assertEquals(
            1,
            production.countOccurrences("executeCreateAgentBuiltInFormRefreshIfAvailable("),
            "Built-in form loading must cross the executable snapshot admission boundary"
        )

        val automaticModels =
            source
                .substringAfter("LaunchedEffect(availableSelectedMachineId, runtime, external) {")
                .substringBefore("\n    LaunchedEffect(")
        assertContains(automaticModels, "executeCreateAgentRuntimeModelsRequestIfAvailable(")
        assertContains(automaticModels, "machines = machines")
        assertContains(automaticModels, "selectedMachineId = selectedMachineId")
        assertContains(automaticModels, "force = false")

        val builtInForm =
            source
                .substringAfter("val formStore = createAgentFormStore ?: return@LaunchedEffect")
                .substringBefore("\n    LaunchedEffect(builtInForm)")
        assertContains(builtInForm, "executeCreateAgentBuiltInFormRefreshIfAvailable(")
        assertContains(builtInForm, "machines = machines")
        assertContains(builtInForm, "selectedMachineId = selectedMachineId")

        val manualModels =
            source
                .substringAfter("onRuntimeModelsRefresh = {")
                .substringBefore("\n                            machineRuntimeIds =")
        assertContains(manualModels, "executeCreateAgentRuntimeModelsRequestIfAvailable(")
        assertContains(manualModels, "machines = machines")
        assertContains(manualModels, "selectedMachineId = selectedMachineId")
        assertContains(manualModels, "force = true")
    }

    @Test
    fun appShellComputersPrewarmStaysBehindHomeIdleLeaseAndUsesBackgroundPriority() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val prewarm =
            source.substringBetween(
                "// MEMBERS_COMPUTERS_IDLE_PREWARM_START",
                "// MEMBERS_COMPUTERS_IDLE_PREWARM_END"
            )

        assertContains(prewarm, "computersPrewarmServerId")
        assertContains(prewarm, "appShellComputersStore.reconcileOwner()")
        assertContains(prewarm, "awaitStablePrewarmGeneration(prewarmAdmissionGate)")
        assertContains(prewarm, "withKuiklyIdleContext(pager.pagerId)")
        assertContains(prewarm, "prewarmAdmissionGate.tryAcquire(stableGeneration)")
        assertContains(prewarm, "lease.commit {")
        assertContains(prewarm, "appShellComputersStore.dispatch(")
        assertContains(prewarm, "SharedComputersIntent.EnsureComputerPicker(")
        assertContains(prewarm, "priority = DataRequestPriority.Background")
        assertFalse("DataRequestPriority.Visible" in prewarm)

        assertTrue(prewarm.indexOf("awaitStablePrewarmGeneration") < prewarm.indexOf("withKuiklyIdleContext"))
        assertTrue(prewarm.indexOf("withKuiklyIdleContext") < prewarm.indexOf("tryAcquire"))
        assertTrue(prewarm.indexOf("tryAcquire") < prewarm.indexOf("lease.commit"))
        assertTrue(prewarm.indexOf("lease.commit") < prewarm.indexOf("appShellComputersStore.dispatch"))
    }

    @Test
    fun standaloneAndAppShellMembersWireInitialErrorThroughVisibleRetry() {
        assertMembersErrorRetryWiring(
            commonMainSource("build/raft/app/MembersPage.kt"),
            surface = "standalone Members"
        )
        assertMembersErrorRetryWiring(
            commonMainSource("build/raft/app/SettingsHomePage.kt"),
            surface = "AppShell Members"
        )
    }

    @Test
    fun membersErrorAndRetryReachTheDialogMachinePicker() {
        val members = commonMainSource("build/raft/app/MembersPage.kt")
        val dialog = commonMainSource("build/raft/app/SharedCreateAgentDialog.kt")

        assertContains(members, "createAgentMachinesErrorMessage = createAgentMachinesErrorMessage")
        assertContains(members, "createAgentRetryMachines = createAgentRetryMachines")
        assertContains(members, "machinesErrorMessage = createAgentMachinesErrorMessage")
        assertContains(members, "onMachinesRetry = createAgentRetryMachines")
        assertContains(dialog, "machinesErrorMessage = machinesErrorMessage")
        assertContains(dialog, "onRetry = onMachinesRetry")

        val pickerError =
            dialog
                .substringAfter("!machinesErrorMessage.isNullOrBlank() ->")
                .substringBefore("\n            machines.isEmpty() ->")
        assertContains(pickerError, "text = machinesErrorMessage")
        assertContains(pickerError, "label = stringResource(MR.strings.common_retry)")
        assertContains(pickerError, "onClick = onRetry")
    }

    private fun assertMembersErrorRetryWiring(source: String, surface: String) {
        val wiring =
            source.substringBetween(
                "// MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_START",
                "// MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_END"
            )
        assertContains(wiring, "SharedComputersIntent.RefreshComputerPicker(", message = surface)
        assertContains(wiring, "priority = DataRequestPriority.Visible", message = surface)
        assertContains(wiring, "createAgentMachinesLoading = computersSlice.initialLoadInFlight", message = surface)
        assertContains(
            wiring,
            "createAgentMachinesErrorMessage = computersSlice.initialLoadError?.userFacingMessage(errorStrings)",
            message = surface
        )
        assertContains(wiring, "createAgentRetryMachines = retryMachines", message = surface)
    }

    private fun commonMainSource(relativePath: String): String =
        File(commonMainRoot(), relativePath).readText().replace("\r\n", "\n")

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "production contract markers missing" }
        return substring(startIndex, endIndex + end.length)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, step = 1).count { it == needle }
}
