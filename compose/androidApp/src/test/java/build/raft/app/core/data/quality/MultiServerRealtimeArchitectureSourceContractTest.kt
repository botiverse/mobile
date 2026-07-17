package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiServerRealtimeArchitectureSourceContractTest {
    @Test
    fun sharedAndAndroidUseProcessDesiredSetInsteadOfSelectedShellOwnership() {
        val root = repositoryRoot()
        val settings =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()
        val provider =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/data/SlockSharedStoreProvider.kt").readText()
        val android =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAppScopeRealtimeOwner.kt").readText()

        assertTrue(provider.contains("ProcessRealtimeAuthorityScopeKey"))
        assertTrue(provider.contains("fun processRealtimeAuthority()"))
        assertTrue(settings.contains("sharedStores.processRealtimeAuthority()"))
        assertTrue(settings.contains("bridge.realtimeReconcile("))
        assertTrue(android.contains("fun reconcileFromSharedRequest("))
        assertTrue(android.contains("private fun multiServerRealtimeEnabled(): Boolean = true"))
        assertFalse(android.contains("releaseOwnerFromOtherServers("))
    }

    @Test
    fun iosAndOhosPartitionRuntimesFenceOldCallbacksAndRouteRawIdentity() {
        val root = repositoryRoot()
        val ios = File(root, "compose/iosApp/iosApp/SlockIosRealtimeHost.swift").readText()
        val ohos =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeHostContext.ets").readText()
        val ohosSocket =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/OhosSocketIoClient.ets").readText()

        assertTrue(ios.contains("private var runtimes: [String: Runtime] = [:]"))
        assertTrue(ios.contains("currentRuntime(serverId: serverId, generation: generation)"))
        assertTrue(ios.contains("payloadServerGate(connectionServerId: params.serverId"))
        assertTrue(ios.contains("\"authorityConnectionFamilyId\": params.authorityConnectionFamilyId"))
        assertTrue(ios.contains("\"notification:push\""))
        assertTrue(ios.contains("\"read_state:updated\""))

        assertTrue(ohos.contains("private readonly runtimes: Map<string, SlockOhosRealtimeRuntime>"))
        assertTrue(ohos.contains("if (!this.isCurrent(capturedGeneration)) return"))
        assertTrue(ohos.contains("rawDecision("))
        assertTrue(ohos.contains("this.runtimeId,"))
        assertTrue(ohosSocket.contains("if (this.socket !== socket)"))
        assertTrue(ohos.contains("ambiguous_or_missing_server"))
    }

    private fun repositoryRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (File(current, "compose").isDirectory && File(current, ".git").exists()) return current
            current = current.parentFile ?: error("repository root not found")
        }
    }
}
