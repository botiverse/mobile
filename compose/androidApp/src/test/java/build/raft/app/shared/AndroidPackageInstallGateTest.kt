package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APK attachments on Android 8+: the system installer finishes without any UI when the calling app
 * does not declare REQUEST_INSTALL_PACKAGES (the Google Play build) or has "install unknown apps"
 * off. Raft decides before launching and reports a status the file page explains.
 */
class AndroidPackageInstallGateTest {
    @Test
    fun decisionCoversEveryBuildAndPermissionState() {
        assertEquals(
            AndroidPackageInstallDecision.Launch,
            androidPackageInstallDecision(installPermissionDeclared = true, canRequestPackageInstalls = true)
        )
        assertEquals(
            AndroidPackageInstallDecision.RequestUnknownSourcesPermission,
            androidPackageInstallDecision(installPermissionDeclared = true, canRequestPackageInstalls = false)
        )
        listOf(true, false).forEach { canRequest ->
            assertEquals(
                AndroidPackageInstallDecision.Unsupported,
                androidPackageInstallDecision(installPermissionDeclared = false, canRequestPackageInstalls = canRequest)
            )
        }
    }

    /**
     * Hands 624cfe35: Android kills Raft ("[KILL UID] REQUEST_INSTALL_PACKAGES changed") when the
     * switch flips, so Install first says that and opens the switch's page only from its confirm.
     */
    @Test
    fun installWithoutTheSwitchExplainsTheRestartBeforeOpeningSettings() {
        var settingsOpens = 0
        var confirm: (() -> Unit)? = null
        val lines = mutableListOf<String>()
        val prompt =
            AndroidUnknownSourcesSettingsPrompt(
                showExplanation = { onOpenSettings ->
                    confirm = onOpenSettings
                    true
                },
                openSettings = {
                    settingsOpens += 1
                    true
                },
                record = { lines += it }
            )
        val records = mutableListOf<Pair<AndroidPackageInstallDecision, String?>>()
        val gate =
            AndroidPackageInstallGate(
                installPermissionDeclared = { true },
                canRequestPackageInstalls = { false },
                openUnknownSourcesSettings = prompt::request,
                record = { decision, detail -> records += decision to detail }
            )

        val result = gate.admit(publicApk())

        // The file page stays quiet (the explanation is the feedback); nothing opened yet.
        assertEquals(AndroidAttachmentOpenStatus.InstallPermissionRequired, result?.status)
        assertEquals(0, settingsOpens)
        assertEquals(listOf(AndroidPackageInstallDecision.RequestUnknownSourcesPermission to "settingsRequested=true"), records)
        assertEquals(listOf("attachment_install_permission_explained shown=true"), lines)

        // Open Settings: the switch's page, once.
        confirm!!.invoke()
        assertEquals(1, settingsOpens)
        assertEquals("attachment_install_permission_settings confirmed=true settingsOpened=true", lines.last())
    }

    @Test
    fun playBuildOpensTheFileManagerOnThePublishedApkWithoutInstallerOrSettings() {
        val harness = Harness(declared = false, canRequest = false, filesSurface = "raft_folder")

        val result = harness.launcher().launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)

        assertEquals(AndroidAttachmentOpenStatus.InstallViaFiles, result.status)
        assertEquals("install_via_files", result.toBridgePayload()["status"])
        assertEquals(1, harness.filesOpens)
        assertEquals(0, harness.launches)
        assertEquals(0, harness.settingsOpens)
        assertEquals(listOf(AndroidPackageInstallDecision.Unsupported to "filesOpened=raft_folder"), harness.records)
    }

    private class Harness(
        private val declared: Boolean,
        private val canRequest: Boolean,
        private val settingsOpen: Boolean = true,
        private val filesSurface: String? = "raft_folder",
        private val launch: () -> Unit = {}
    ) {
        var launches = 0
        var settingsOpens = 0
        var filesOpens = 0
        val records = mutableListOf<Pair<AndroidPackageInstallDecision, String?>>()

        fun launcher(
            readable: Boolean = true,
            isActivityNotFound: (Throwable) -> Boolean = { false },
            onFailure: (Throwable) -> Unit = {}
        ): AndroidAttachmentSourceLauncher =
            AndroidAttachmentSourceLauncher(
                sourceIsReadable = { readable },
                hasVisibleHandler = { true },
                runIfOwnerCurrent = { block -> block() },
                launchSource = {
                    launches += 1
                    launch()
                },
                isActivityNotFound = isActivityNotFound,
                onFailure = onFailure,
                packageInstallGate =
                    AndroidPackageInstallGate(
                        installPermissionDeclared = { declared },
                        canRequestPackageInstalls = { canRequest },
                        openUnknownSourcesSettings = {
                            settingsOpens += 1
                            settingsOpen
                        },
                        openDownloadsInFileManager = {
                            filesOpens += 1
                            filesSurface
                        },
                        record = { decision, detail -> records += decision to detail }
                    )
            )
    }

    private fun publicApk(): AndroidAttachmentContentUriSource =
        apk(AndroidAttachmentOpenSourceKind.PublicDownload, "content://media/external/downloads/7")

    private fun apk(kind: AndroidAttachmentOpenSourceKind, uri: String): AndroidAttachmentContentUriSource =
        AndroidAttachmentContentUriSource(
            kind = kind,
            contentUri = uri,
            mimeType = "application/vnd.android.package-archive",
            displayName = "androidApp-alpha.apk",
            sizeBytes = 114L * 1024L * 1024L,
            durableCache = kind == AndroidAttachmentOpenSourceKind.PublicDownload
        )
}
