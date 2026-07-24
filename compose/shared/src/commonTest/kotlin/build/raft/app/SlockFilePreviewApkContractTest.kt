package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockFilePreviewApkContractTest {
    private val actionTerms =
        SlockFilePreviewActionTerms(
            download = "Download",
            install = "Install",
            open = "Open",
            openWith = "Open with..."
        )

    @Test
    fun apkExtensionKeepsInstallContractWhenServerMimeIsMissingOrGeneric() {
        listOf("", "application/octet-stream", "application/zip").forEach { mimeType ->
            val payload =
                SlockFilePreviewPayload(
                    externalFileName = "androidApp-alpha.apk",
                    externalMimeType = mimeType
                )

            assertEquals(
                "Install",
                slockFilePreviewPrimaryActionLabel(payload, cached = true, terms = actionTerms),
                "mimeType=$mimeType"
            )
            assertEquals(SlockLucideIconName.Package, slockFilePreviewFileIconName(payload), "mimeType=$mimeType")
        }
    }

    @Test
    fun uncachedApkAlwaysUsesExplicitDownloadBeforeInstall() {
        val payload =
            SlockFilePreviewPayload(
                externalFileName = "androidApp-alpha.apk",
                externalMimeType = "application/octet-stream"
            )

        assertEquals("Download", slockFilePreviewPrimaryActionLabel(payload, cached = false, terms = actionTerms))
        assertEquals(SlockFilePreviewPrimaryAction.Save, slockFilePreviewPrimaryAction(cached = false))
    }
}
