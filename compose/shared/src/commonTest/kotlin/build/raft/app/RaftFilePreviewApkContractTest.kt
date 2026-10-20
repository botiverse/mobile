package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftFilePreviewApkContractTest {
    private val actionTerms =
        RaftFilePreviewActionTerms(
            download = "Download",
            install = "Install",
            open = "Open",
            openWith = "Open with..."
        )

    @Test
    fun apkExtensionKeepsInstallContractWhenServerMimeIsMissingOrGeneric() {
        listOf("", "application/octet-stream", "application/zip").forEach { mimeType ->
            val payload =
                RaftFilePreviewPayload(
                    externalFileName = "androidApp-alpha.apk",
                    externalMimeType = mimeType
                )

            assertEquals(
                "Install",
                raftFilePreviewPrimaryActionLabel(
                    payload,
                    downloadState = RaftFilePreviewDownloadUiState.Downloaded,
                    terms = actionTerms
                ),
                "mimeType=$mimeType"
            )
            assertEquals(RaftLucideIconName.Package, raftFilePreviewFileIconName(payload), "mimeType=$mimeType")
        }
    }

    @Test
    fun uncachedApkAlwaysUsesExplicitDownloadBeforeInstall() {
        val payload =
            RaftFilePreviewPayload(
                externalFileName = "androidApp-alpha.apk",
                externalMimeType = "application/octet-stream"
            )

        assertEquals(
            "Download",
            raftFilePreviewPrimaryActionLabel(
                payload,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                terms = actionTerms
            )
        )
        assertEquals(
            RaftFilePreviewPrimaryAction.Save,
            raftFilePreviewPrimaryAction(RaftFilePreviewDownloadUiState.NotDownloaded)
        )
    }
}
