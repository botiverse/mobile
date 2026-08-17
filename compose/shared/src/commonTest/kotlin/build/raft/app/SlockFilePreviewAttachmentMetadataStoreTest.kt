package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockFilePreviewAttachmentMetadataStoreTest {
    @Test
    fun sameOwnerAndAttachmentIdRestoreCanonicalFilenameAndGalleryMetadata() {
        val store = SlockFilePreviewAttachmentMetadataStore()
        val owner = owner()
        store.capture(
            owner = owner,
            payload =
                SlockFilePreviewPayload(
                    kind = "loading",
                    requestedKind = "image",
                    name = "raft-ohos-ohosApp-default-signed.hap",
                    path = "raft-ohos-ohosApp-default-signed.hap",
                    meta = "87 MB",
                    externalFileName = "raft-ohos-ohosApp-default-signed.hap",
                    externalMimeType = "application/x-hap",
                    source = SlockFilePreviewSourceThreadAttachment,
                    attachmentId = "attachment-1",
                    imageGallery =
                        listOf(
                            SlockFilePreviewGalleryImage(
                                attachmentId = "attachment-1",
                                name = "raft-ohos-ohosApp-default-signed.hap",
                                mimeType = "application/x-hap",
                                sizeLabel = "87 MB",
                            ),
                            SlockFilePreviewGalleryImage(
                                attachmentId = "attachment-2",
                                name = "release-notes.png",
                                mimeType = "image/png",
                                sizeLabel = "42 KB",
                            ),
                        ),
                ),
        )

        val hydrated =
            store.hydrate(
                owner = owner,
                payload = restoredPayload("attachment-1", galleryIds = listOf("attachment-1", "attachment-2")),
            )

        assertEquals("raft-ohos-ohosApp-default-signed.hap", hydrated.name)
        assertEquals("raft-ohos-ohosApp-default-signed.hap", hydrated.path)
        assertEquals("raft-ohos-ohosApp-default-signed.hap", hydrated.subtitle)
        assertEquals("raft-ohos-ohosApp-default-signed.hap", hydrated.externalFileName)
        assertEquals("application/x-hap", hydrated.externalMimeType)
        assertEquals("87 MB", hydrated.meta)
        assertEquals(
            "raft-ohos-ohosApp-default-signed.hap",
            hydrated.slockFilePreviewHeaderTitle(
                fallbackFile = "文件",
                localizedTitle = "文件预览",
            ),
        )
        assertEquals("release-notes.png", hydrated.imageGallery[1].name)
        assertEquals("image/png", hydrated.imageGallery[1].mimeType)
        assertEquals("42 KB", hydrated.imageGallery[1].sizeLabel)
    }

    @Test
    fun candidateOnlyFillsBlankFields() {
        val store = SlockFilePreviewAttachmentMetadataStore()
        val owner = owner()
        store.capture(
            owner,
            SlockFilePreviewPayload(
                name = "candidate.txt",
                meta = "1 KB",
                externalMimeType = "text/plain",
                source = SlockFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1",
            ),
        )
        val canonical =
            restoredPayload("attachment-1").copy(
                subtitle = "canonical subtitle",
                name = "canonical.txt",
                path = "canonical/path.txt",
                meta = "2 KB",
                externalFileName = "canonical-external.txt",
                externalMimeType = "text/canonical",
            )

        assertEquals(canonical, store.hydrate(owner, canonical))
    }

    @Test
    fun environmentAccountAndServerOwnerMismatchesFailClosed() {
        val store = SlockFilePreviewAttachmentMetadataStore()
        store.capture(
            owner(),
            SlockFilePreviewPayload(
                name = "private.txt",
                source = SlockFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1",
            ),
        )
        val restored = restoredPayload("attachment-1")
        val mismatchedOwners =
            listOf(
                owner(environmentId = "https://staging.raft.build/api"),
                owner(accountId = "account-b"),
                owner(serverId = "server-b"),
            )

        mismatchedOwners.forEach { mismatchedOwner ->
            assertEquals(
                restored,
                store.hydrate(mismatchedOwner, restored),
                "candidate must not cross owner $mismatchedOwner",
            )
        }
    }

    @Test
    fun mismatchedAttachmentIdIsIgnored() {
        val store = SlockFilePreviewAttachmentMetadataStore()
        val owner = owner()
        store.capture(
            owner,
            SlockFilePreviewPayload(
                name = "one.txt",
                source = SlockFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1",
            ),
        )
        val restored = restoredPayload("attachment-2")

        assertEquals(restored, store.hydrate(owner, restored))
    }

    @Test
    fun blankCanonicalFilenameKeepsLocalizedFileFallback() {
        val store = SlockFilePreviewAttachmentMetadataStore()
        val owner = owner()
        store.capture(
            owner,
            SlockFilePreviewPayload(
                externalFileName = "attachment-attachm",
                source = SlockFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1",
            ),
        )

        val hydrated = store.hydrate(owner, restoredPayload("attachment-1"))

        assertEquals("", hydrated.name)
        assertEquals("", hydrated.path)
        assertEquals("", hydrated.externalFileName)
        assertEquals(
            "文件",
            hydrated.slockFilePreviewHeaderTitle(
                fallbackFile = "文件",
                localizedTitle = "文件预览",
            ),
        )
    }

    private fun restoredPayload(
        attachmentId: String,
        galleryIds: List<String> = emptyList(),
    ): SlockFilePreviewPayload =
        SlockFilePreviewPayload(
            kind = "loading",
            requestedKind = "file",
            source = SlockFilePreviewSourceThreadAttachment,
            attachmentId = attachmentId,
            imageGallery = galleryIds.map { id -> SlockFilePreviewGalleryImage(attachmentId = id) },
        )

    private fun owner(
        environmentId: String = "https://api.raft.build/api",
        accountId: String = "account-a",
        serverId: String = "server-a",
    ): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner(
            environmentId = environmentId,
            accountId = accountId,
            serverId = serverId,
        )
}
