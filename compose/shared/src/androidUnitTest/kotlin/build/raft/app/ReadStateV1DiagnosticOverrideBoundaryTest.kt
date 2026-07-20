package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadStateV1DiagnosticOverrideBoundaryTest {
    private val repoRoot: File = locateRepoRoot()

    @Test
    fun `release build entitlement is false while debug alpha and connected test opt in`() {
        val gradle = repoRoot.resolve("compose/androidApp/build.gradle.kts").readText()

        assertTrue(gradle.contains("buildConfigField(\"Boolean\", \"READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED\", \"false\")"))
        assertTrue(gradle.split("READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED").size - 1 >= 4)
        val releaseBlock = gradle.substringAfter("release {").substringBefore("}")
        assertFalse(releaseBlock.contains("READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED\", \"true"))
    }

    @Test
    fun `override is process local and not route or preference backed`() {
        val policy =
            repoRoot.resolve(
                "compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/ReadStateV1DiagnosticOverride.kt"
            ).readText()

        assertFalse(policy.contains("SharedPreference"))
        assertFalse(policy.contains("pageData"))
        assertFalse(policy.contains("route"))
        assertTrue(policy.contains("object ProcessReadStateV1DiagnosticOverride"))
    }

    @Test
    fun `diagnostic UI writes only through settings store intent`() {
        val settings =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()

        assertTrue(settings.contains("SharedSettingsIntent.SetReadStateV1DiagnosticOverride(mode)"))
        assertFalse(settings.contains("ProcessReadStateV1DiagnosticOverride"))
    }

    private fun locateRepoRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (current.resolve("compose/shared/src/commonMain").isDirectory) return current
            val parent = current.parentFile ?: error("Unable to locate repository root")
            current = parent
        }
        error("Unable to locate repository root")
    }
}
