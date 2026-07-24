package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class SettingsRemoteTransportContractTest {
    @Test
    fun mobileSettingsDoesNotExposeALocalTransportOverride() {
        val source = source("commonMain/kotlin/build/raft/app/SettingsHomePage.kt")
        val transportPreferences = source("commonMain/kotlin/build/raft/app/data/NetworkTransportPreferences.kt")
        val provider = source("commonMain/kotlin/build/raft/app/data/SlockSharedStoreProvider.kt")

        assertFalse("SettingsHttp3Card(" in source)
        assertFalse("onHttp3Change" in source)
        assertFalse("SharedSettingsIntent.SetHttp3Enabled" in source)
        assertFalse("network:http3_enabled" in transportPreferences)
        assertFalse("KeyValueNetworkTransportPreferenceStore" in provider)
    }

    private fun source(relativePath: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(10) {
            val candidate = File(cursor, "src/$relativePath")
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate source: $relativePath")
    }
}
