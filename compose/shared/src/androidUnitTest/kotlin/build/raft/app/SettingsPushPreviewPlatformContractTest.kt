package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPushPreviewPlatformContractTest {
    @Test
    fun previewSettingIsRenderedOnlyBehindPlatformCapability() {
        val source = source("commonMain/kotlin/build/raft/app/SettingsHomePage.kt")
        assertTrue(
            "if (slockPlatformSupportsPushPreviewPrivacySetting() && snapshot.pushMessagePreviews.available)" in source
        )
        assertTrue("SettingsPushPreviewModeCard(" in source)
    }

    @Test
    fun onlyIosEnablesPreviewPrivacySetting() {
        val ios = source("iosMain/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.ios.kt")
        val android = source("androidMain/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.android.kt")
        val ohos = source("ohosArm64Main/kotlin/build/raft/app/SlockKuiklyPlatformCapabilities.ohos.kt")

        assertTrue("slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = true" in ios)
        assertTrue("slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = false" in android)
        assertTrue("slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = false" in ohos)
        assertFalse("slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = true" in android)
        assertFalse("slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = true" in ohos)
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
