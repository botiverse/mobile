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
        val legacyPolicy =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/ReadStateV1DiagnosticOverride.kt").readText()
        val registry =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/DiagnosticFeatureFlagOverrideRegistry.kt").readText()

        listOf(legacyPolicy, registry).forEach { policy ->
            assertFalse(policy.contains("SharedPreference"))
            assertFalse(policy.contains("pageData"))
            assertFalse(policy.contains("route"))
        }
        assertTrue(legacyPolicy.contains("object ProcessReadStateV1DiagnosticOverride"))
        assertTrue(registry.contains("object ProcessDiagnosticFeatureFlagOverrides"))
    }

    @Test
    fun `diagnostic UI writes only through settings store intent`() {
        val settings =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()

        assertTrue(settings.contains("SharedSettingsIntent.SetDiagnosticFeatureFlagOverride(key, mode)"))
        assertFalse(settings.contains("ProcessDiagnosticFeatureFlagOverrides"))
        assertFalse(settings.contains("ProcessReadStateV1DiagnosticOverride"))
    }

    @Test
    fun `generic host boundary is key driven and release entitlement remains fail closed`() {
        val host =
            repoRoot.resolve("compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt").readText()

        assertTrue(host.contains("\"setDiagnosticFeatureFlagOverride\" -> setDiagnosticFeatureFlagOverride(request)"))
        assertTrue(host.contains("val key = request.optString(\"key\").trim()"))
        assertTrue(host.contains("BuildConfig.READ_STATE_DIAGNOSTIC_OVERRIDE_ENABLED"))
        assertTrue(host.contains("ProcessDiagnosticFeatureFlagOverrides.registry.configureEntitlement"))
        assertTrue(host.contains("diagnosticFeatureFlagOverridesJson"))
    }

    @Test
    fun `both initial allowlist keys have production consumers`() {
        val provider =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/data/SlockSharedStoreProvider.kt").readText()
        val activity =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/SharedInboxPanel.kt").readText()
        val registry =
            repoRoot.resolve("compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/DiagnosticFeatureFlagOverrideRegistry.kt").readText()

        assertTrue(provider.contains("ProcessReadStateV1DiagnosticOverride.controller.runPublicationIfEffective"))
        assertTrue(activity.contains("resolveActivityStableOrderEnabled("))
        assertTrue(activity.contains("revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)"))
        assertTrue(registry.contains("SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY"))
        assertTrue(registry.contains("MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY"))
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
