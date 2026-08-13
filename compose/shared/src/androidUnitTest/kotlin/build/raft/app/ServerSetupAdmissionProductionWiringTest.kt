package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerSetupAdmissionProductionWiringTest {
    @Test
    fun appShellReadyConsumerIsGatedByAuthoritativeRepositoryHost() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()
        assertTrue(source.contains("rememberServerSetupAdmissionHost("))
        assertTrue(source.contains("setupAdmissionAllowsHome &&"))
        assertTrue(source.contains("readyServerId, setupAdmissionAllowsHome"))
        assertTrue(source.contains("readyServerId != null && setupAdmissionAllowsHome"))
        assertTrue(source.contains("setupAdmissionHost != null && !setupAdmissionHost.allowsHome"))
        assertTrue(source.contains("setupAdmissionHost.content()"))
    }

    @Test
    fun shellServerChooserMutationsUseOriginatingShellAuthority() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()
        assertTrue(source.contains("if (fromShell) sharedStores.authStore(sourceStoreScope) else sharedStores.authStore"))
        assertTrue(
            source.contains(
                "sharedStores.createAuthDataSourceForCurrentEnvironment(sourceStoreScope, avatarNativeCache)"
            )
        )
        assertTrue(source.contains("runCatchingPreservingCancellation { authSessionStore.readSession() }"))
    }

    @Test
    fun onboardingUrlUsesSharedEncoderInsteadOfMissingNativeBridgeMethod() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/ServerSetupAdmissionHost.kt").readText()
        assertTrue(source.contains("serverSetupOnboardingUrl(webBaseUrl, serverSlug, generation)"))
        assertTrue(!source.contains("bridge::urlEncode"))
    }

    @Test
    fun hostedSwitchUsesCanonicalShellSelectionAndOriginGenerationFences() {
        val host = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/ServerSetupAdmissionHost.kt").readText()
        assertTrue(host.contains("serverSetupControlledDocumentMatchesOrigin(currentDocumentUrl, webBaseUrl)"))
        assertTrue(host.contains("serverSetupSwitchTargetId("))
        assertTrue(host.contains("controllerState.claimSwitch(identity)"))
        assertTrue(host.contains("authDataSource.selectServer(targetId)"))
        assertTrue(host.contains("state = ServerSetupAdmissionState.Loading(identity)"))
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot find repository file: $relativePath from ${start.absolutePath}")
    }
}
