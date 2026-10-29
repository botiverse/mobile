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
    fun sideloadBuildWithUnknownSourcesOffOpensRaftsOwnSettingsPage() {
        installSelf(requested = arrayOf(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        packageManager.setCanRequestPackageInstalls(false)

        val result = AndroidPackageInstallPermission.gate(context, "att").admit(apk())

        assertEquals(AndroidAttachmentOpenStatus.InstallPermissionRequired, result?.status)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
    }

    @Test
    fun sideloadBuildWithUnknownSourcesAllowedAdmitsTheInstaller() {
        installSelf(requested = arrayOf(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        packageManager.setCanRequestPackageInstalls(true)

        assertTrue(AndroidPackageInstallPermission.isDeclared(context))
        assertNull(AndroidPackageInstallPermission.gate(context, "att").admit(apk()))
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun playBuildOpensRaftsDownloadsFolderInTheFileManager() {
        installSelf(requested = arrayOf(Manifest.permission.INTERNET))
        packageManager.setCanRequestPackageInstalls(false)

        assertFalse(AndroidPackageInstallPermission.isDeclared(context))
        val result = AndroidPackageInstallPermission.gate(context, "att").admit(apk())

        assertEquals(AndroidAttachmentOpenStatus.InstallViaFiles, result?.status)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, started.type)
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FRaft",
            started.dataString
        )
        // Never the installer and never a settings page without a switch for Raft.
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

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

    private fun installSelf(requested: Array<String>) {
        packageManager.installPackage(
            PackageInfo().apply {
                packageName = context.packageName
                requestedPermissions = requested
            }
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
