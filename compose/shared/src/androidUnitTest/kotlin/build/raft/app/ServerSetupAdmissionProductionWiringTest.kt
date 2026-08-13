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
    fun onboardingWaitsForCanonicalSlugAndCommitsItBeforeNavigation() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/ServerSetupAdmissionHost.kt").readText()
        assertTrue(source.contains("val currentSession = authSnapshot.session"))
        assertTrue(source.contains("val canonicalServerSlug = currentSession?.canonicalServerSlugFor(serverId)"))
        assertTrue(source.contains("shellAuthStore.updateSelectedServer(serverId, canonicalServerSlug)"))
        assertTrue(source.contains("?.takeIf { selectedSlugCommitted }"))
        assertTrue(source.contains("currentSession == null ||"))
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot find repository file: $relativePath from ${start.absolutePath}")
    }
}
