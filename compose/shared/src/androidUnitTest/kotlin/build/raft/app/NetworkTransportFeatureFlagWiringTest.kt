package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkTransportFeatureFlagWiringTest {
    @Test
    fun providerForwardsVariantEnvironmentAndServerToProcessController() {
        val source = source("commonMain/kotlin/build/raft/app/data/SlockSharedStoreProvider.kt")

        assertTrue("key == MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY" in source)
        assertTrue("networkTransportController.applyRemoteEvaluation(" in source)
        assertTrue("variant = variant" in source)
        assertTrue("updateRemoteActiveSelection(" in source)
        assertTrue("updateRemoteEnvironment(environmentId)" in source)
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
