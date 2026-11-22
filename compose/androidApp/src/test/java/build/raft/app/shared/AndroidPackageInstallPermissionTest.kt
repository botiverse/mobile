package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The platform half of the APK install gate, per build variant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidPackageInstallPermissionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(context.packageManager)

    @Test
    fun manifestsKeepTheVariantSplitThisGateReliesOn() {
        // Unit tests run from the androidApp module directory.
        val main = File("src/main/AndroidManifest.xml").readText()
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue(main.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(
            Regex(
                """android:name="android\.permission\.REQUEST_INSTALL_PACKAGES"\s+tools:node="remove""""
            ).containsMatchIn(play)
        )
    }

    private fun apk(): AndroidAttachmentContentUriSource =
        AndroidAttachmentContentUriSource(
            kind = AndroidAttachmentOpenSourceKind.PublicDownload,
            contentUri = "content://media/external/downloads/7",
            mimeType = "application/vnd.android.package-archive",
            displayName = "androidApp-alpha.apk",
            sizeBytes = 1L,
            durableCache = true
        )
}
