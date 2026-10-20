package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadComposerLocalPreviewReferenceTest {
    @Test
    fun localPreviewReferencePrefersRendererUriAndFallsBackOnlyWhenMissing() {
        val cases =
            listOf(
                Triple(
                    PlatformFile(
                        id = "separated",
                        fileName = "photo.jpg",
                        previewUri = "file:///data/storage/el2/base/cache/photo.jpg",
                        fileRef = "file://build.raft.mobile/data/storage/el2/base/cache/photo.jpg"
                    ),
                    "file:///data/storage/el2/base/cache/photo.jpg",
                    "separated producer references"
                ),
                Triple(
                    PlatformFile(
                        id = "preview-only",
                        fileName = "photo.jpg",
                        previewUri = "content://picker/photo.jpg"
                    ),
                    "content://picker/photo.jpg",
                    "preview-only reference"
                ),
                Triple(
                    PlatformFile(
                        id = "legacy-file-ref",
                        fileName = "photo.jpg",
                        fileRef = "content://legacy/photo.jpg"
                    ),
                    "content://legacy/photo.jpg",
                    "legacy fileRef fallback"
                )
            )

        cases.forEach { (file, expected, label) ->
            assertEquals(expected, file.composerLocalPreviewUri(), label)
        }
        assertNull(
            PlatformFile(
                id = "blank",
                fileName = "photo.jpg",
                previewUri = " ",
                fileRef = ""
            ).composerLocalPreviewUri()
        )
    }

    @Test
    fun pickedImageGalleryKeepsRendererUriSeparateFromUploadHandle() {
        val previewUri = "file:///data/storage/el2/base/cache/photo.jpg"
        val fileRef = "file://build.raft.mobile/data/storage/el2/base/cache/photo.jpg"
        val response =
            JSONObject().apply {
                put(
                    "files",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("id", "picked-1")
                                put("fileName", "photo.jpg")
                                put("mimeType", "image/jpeg")
                                put("sizeBytes", 42L)
                                put("previewUri", previewUri)
                                put("fileRef", fileRef)
                            }
                        )
                    }
                )
            }

        val file = parseSharedThreadPickedPlatformFiles(response).single()
        val galleryImage = file.toComposerImageGalleryImage()

        assertEquals(previewUri, file.previewUri)
        assertEquals(fileRef, file.fileRef)
        assertEquals(previewUri, galleryImage.thumbnailUrl)
        assertEquals(previewUri, galleryImage.composerImagePreviewUri)
        assertEquals(fileRef, galleryImage.composerImageFileRef)
    }
}
