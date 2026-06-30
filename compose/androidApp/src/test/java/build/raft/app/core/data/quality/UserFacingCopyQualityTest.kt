package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingCopyQualityTest {
    @Test
    fun productionUserFacingCopyDoesNotLeakImplementationDetails() {
        val root = findRepoRoot()
        val scannedFiles =
                listOf(
                        "compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt",
                        "compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsBillingPage.kt",
                        "compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SettingsRepositoryDataSource.kt"
                )
        val bannedPhrases =
                listOf(
                        "shared kmp repository",
                        "shared settings mvi",
                        "shared app shell",
                        "typed settings snapshot",
                        "typed repository boundary",
                        "react sends",
                        "local android preview support",
                        "preparing local sample state",
                        "push api is connected",
                        "loading billing sample",
                        "not available in shared shell"
                )

        val offenders =
                scannedFiles.flatMap { path ->
                    val file = File(root, path)
                    file.readLines().flatMapIndexed { index, line ->
                        val normalized = line.lowercase()
                        bannedPhrases.mapNotNull { phrase ->
                            if (normalized.contains(phrase)) {
                                "${file.relativeTo(root).path}:${index + 1}: $phrase :: ${line.trim()}"
                            } else {
                                null
                            }
                        }
                    }
                }

        assertTrue(
                "Production-facing copy must describe product behavior, not implementation details.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    private fun findRepoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        while (current.parentFile != null) {
            if (File(current, "AGENTS.md").isFile && File(current, "compose/settings.gradle.kts").isFile) {
                return current
            }
            current = requireNotNull(current.parentFile)
        }
        error("Could not find repo root from $userDir")
    }
}
