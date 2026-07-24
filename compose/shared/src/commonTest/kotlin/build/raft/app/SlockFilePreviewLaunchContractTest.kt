package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalSerializationApi::class)
class SlockFilePreviewLaunchContractTest {
    @Test
    fun filePreviewLaunchPayloadOnlyCarriesPreviewIdentity() {
        val descriptor = WireSlockFilePreviewLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)

        assertEquals(listOf("previewId"), names)
    }

    @Test
    fun scopedFilePreviewPageDataKeepsPayloadIdentityOnly() {
        val pageData =
            slockFilePreviewLaunchPageData(
                previewId = "preview-1",
                storeScope = SlockShellStoreScope.create("shell-a", "server-a")
            )
        val encodedPayload = pageData.optString("payload")

        assertEquals(SLOCK_FILE_PREVIEW_PAGE, pageData.optString("pageName"))
        assertEquals("shell-a", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-a", pageData.optString(SLOCK_SHELL_SERVER_ID))
        listOf(
            "title",
            "subtitle",
            "name",
            "path",
            "meta",
            "content",
            "imageUrl",
            "videoUrl",
            "externalUrl",
            "externalFileName",
            "externalMimeType",
            "source",
            "attachmentId",
            "agentId",
            "imageGallery"
        ).forEach { forbidden ->
            assertFalse(encodedPayload.contains(forbidden), "$forbidden must stay outside compose_file_preview payload")
        }
    }

    @Test
    fun filePreviewLaunchPayloadResolvesFromProcessHandoffStore() {
        val previewId =
            SlockFilePreviewLaunchPayloadStore.put(
                SlockFilePreviewPayload(
                    kind = "image",
                    title = "Picked image",
                    imageUrl = "file:///tmp/picked.png"
                )
            )
        val pageData =
            slockFilePreviewLaunchPageData(
                previewId = previewId,
                storeScope = SlockShellStoreScope.create("shell-a", "server-a")
            )

        val trace =
            slockFilePreviewResolveLaunchPayload(
                source = pageData.optString("payload"),
                loadPreviewPayload = SlockFilePreviewLaunchPayloadStore::get
            )

        assertEquals("success", trace.result)
        assertEquals("Picked image", trace.payload.title)
        assertEquals("file:///tmp/picked.png", trace.payload.imageUrl)
    }

    @Test
    fun filePreviewLaunchPayloadReportsMissingPreviewWhenStoreMisses() {
        val pageData =
            slockFilePreviewLaunchPageData(
                previewId = "missing-preview",
                storeScope = SlockShellStoreScope.create("shell-a", "server-a")
            )

        val trace =
            slockFilePreviewResolveLaunchPayload(
                source = pageData.optString("payload"),
                loadPreviewPayload = { null }
            )

        assertEquals("missing_preview", trace.result)
        assertEquals(MR.strings.file_preview_unavailable.desc(), trace.payload.titleDesc)
        assertEquals("preview_not_found", trace.payload.error)
    }
}
