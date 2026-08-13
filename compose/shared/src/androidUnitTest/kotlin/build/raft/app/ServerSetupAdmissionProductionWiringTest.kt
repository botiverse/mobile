package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerSetupAdmissionProductionWiringTest {
    @Test
    fun appShellReadyConsumerIsGatedByAuthoritativeRepositoryHost() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()
        assertTrue(source.contains("rememberServerSetupAdmissionHost("))
        assertTrue(source.contains("setupAdmissionHost.state !is ServerSetupAdmissionState.Admitted"))
        assertTrue(source.contains("setupAdmissionHost.content()"))
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot find repository file: $relativePath from ${start.absolutePath}")
    }
}
