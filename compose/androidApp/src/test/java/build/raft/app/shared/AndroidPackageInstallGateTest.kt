package build.raft.app.shared

import org.junit.Assert.assertEquals
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

    @Test
    fun grantedPermissionLaunchesTheInstallerOnce() {
        val harness = Harness(declared = true, canRequest = true)

        val result = harness.launcher().launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenAction.Install, result.action)
        assertEquals(1, harness.launches)
        assertEquals(0, harness.settingsOpens)
        assertEquals(listOf(AndroidPackageInstallDecision.Launch to null), harness.records)
    }

    @Test
    fun missingUnknownSourcesPermissionOpensSettingsInsteadOfTheInstaller() {
        listOf(true, false).forEach { settingsOpened ->
            val harness = Harness(declared = true, canRequest = false, settingsOpen = settingsOpened)

            val result = harness.launcher().launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)

            assertEquals(AndroidAttachmentOpenStatus.InstallPermissionRequired, result.status)
            assertEquals("install_permission_required", result.toBridgePayload()["status"])
            assertTrue(result.retryable)
            assertEquals(0, harness.launches)
            assertEquals(1, harness.settingsOpens)
            assertEquals(
                listOf(AndroidPackageInstallDecision.RequestUnknownSourcesPermission to "settingsOpened=$settingsOpened"),
                harness.records
            )
        }
    }

    @Test
    fun settingsLaunchCrashStillReportsPermissionRequired() {
        val gate =
            AndroidPackageInstallGate(
                installPermissionDeclared = { true },
                canRequestPackageInstalls = { false },
                openUnknownSourcesSettings = { throw SecurityException("no settings") }
            )

        assertEquals(AndroidAttachmentOpenStatus.InstallPermissionRequired, gate.admit(publicApk())?.status)
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

    @Test
    fun playBuildFailsVisiblyWhenNoFileManagerOpensOrTheApkIsNotInDownloads() {
        mapOf(
            publicApk() to Harness(declared = false, canRequest = false, filesSurface = null),
            privateApk() to Harness(declared = false, canRequest = false, filesSurface = "raft_folder")
        ).forEach { (source, harness) ->
            val result = harness.launcher().launch(source, AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)

            assertEquals(AndroidAttachmentOpenStatus.InstallUnsupported, result.status)
            assertEquals("install_unsupported", result.toBridgePayload()["status"])
            assertEquals(0, harness.launches)
            assertEquals(0, harness.settingsOpens)
            assertEquals(listOf(AndroidPackageInstallDecision.Unsupported to "filesOpened=none"), harness.records)
        }
        // A private cache copy is never shown in a file manager: it is not in Downloads.
    }

    @Test
    fun installerMissingAndInstallerFailureStayVisibleResults() {
        val notFound = RuntimeException("no installer")
        val noInstaller =
            Harness(declared = true, canRequest = true, launch = { throw notFound })
                .launcher(isActivityNotFound = { it === notFound })
                .launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)
        // NoApp routes to the Save As picker; Failed shows "Unable to open attachment".
        assertEquals(AndroidAttachmentOpenStatus.NoApp, noInstaller.status)

        val failures = mutableListOf<Throwable>()
        val failed =
            Harness(declared = true, canRequest = true, launch = { throw SecurityException("denied") })
                .launcher(onFailure = { failures += it })
                .launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)
        assertEquals(AndroidAttachmentOpenStatus.Failed, failed.status)
        assertTrue(failures.single() is SecurityException)
    }

    @Test
    fun gateIsNotConsultedForOrdinaryOpen() {
        val harness = Harness(declared = false, canRequest = false)

        val result = harness.launcher().launch(publicApk(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(1, harness.launches)
        assertEquals(emptyList<Any>(), harness.records)
    }

    @Test
    fun unreadableSourceIsMissingBeforeAnyPermissionPrompt() {
        val harness = Harness(declared = true, canRequest = false)

        val result =
            harness.launcher(readable = false)
                .launch(publicApk(), AndroidAttachmentOpenAction.Install, requiresVisibleHandler = false)

        assertEquals(AndroidAttachmentOpenStatus.Missing, result.status)
        assertEquals(0, harness.settingsOpens)
        assertNull(harness.records.firstOrNull())
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

    private fun privateApk(): AndroidAttachmentContentUriSource =
        apk(AndroidAttachmentOpenSourceKind.PrivateCache, "content://build.raft.app.fileprovider/a.apk")

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
