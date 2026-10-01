package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadAttachmentPickerCompressionFailureTest {
    @Test
    fun compressionFailureRejectsMixedBatchAndCollectsEveryReturnedTemporaryRef() {
        val files =
            JSONArray().apply {
                put(JSONObject().put("fileRef", "file:///cache/ok.jpg"))
            }
        val errors =
            JSONArray().apply {
                put(
                    JSONObject()
                        .put("errorCode", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE)
                        .put("fileRef", "file:///cache/failed.jpg")
                )
            }
        val response =
            JSONObject()
                .put("code", 206)
                .put("errorCode", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE)
                .put("files", files)
                .put("errors", errors)
                .put(
                    "fileRefs",
                    JSONArray().apply { put("file:///cache/compatibility-ref.jpg") }
                )

        assertTrue(threadAttachmentPickerHasCompressionFailure(response))
        assertEquals(
            setOf(
                "file:///cache/ok.jpg",
                "file:///cache/failed.jpg",
                "file:///cache/compatibility-ref.jpg"
            ),
            threadAttachmentPickerCompressionFailureFileRefs(response)
        )
    }

    @Test
    fun bodyTextCompressionFailureIsDetectedAndRefsAreCollected() {
        val body =
            JSONObject()
                .put("errorCode", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE)
                .put(
                    "files",
                    JSONArray().apply {
                        put(JSONObject().put("fileRef", "file:///cache/body-good.jpg"))
                    }
                )
                .put(
                    "errors",
                    JSONArray().apply {
                        put(
                            JSONObject()
                                .put("errorCode", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE)
                                .put("temporaryFileRef", "file:///cache/body-failed.jpg")
                        )
                    }
                )
        val response = JSONObject().put("code", 206).put("bodyText", body.toString())

        assertTrue(threadAttachmentPickerHasCompressionFailure(response))
        assertEquals(
            setOf("file:///cache/body-good.jpg", "file:///cache/body-failed.jpg"),
            threadAttachmentPickerCompressionFailureFileRefs(response)
        )
    }

    @Test
    fun ordinaryPartialReadDoesNotTriggerCompressionFailClosed() {
        val response =
            JSONObject()
                .put("code", 206)
                .put("errorCode", "attachment_read_partial")
                .put(
                    "errors",
                    JSONArray().apply {
                        put(
                            JSONObject()
                                .put("errorCode", "attachment_read_failed")
                                .put("fileRef", "file:///cache/read-failed.jpg")
                        )
                    }
                )

        assertFalse(threadAttachmentPickerHasCompressionFailure(response))
    }

    @Test
    fun nullOrMalformedBodyDoesNotBecomeCompressionFailure() {
        assertFalse(threadAttachmentPickerHasCompressionFailure(null))
        assertFalse(threadAttachmentPickerHasCompressionFailure(JSONObject().put("bodyText", "not-json")))
        assertTrue(
            threadAttachmentPickerCompressionFailureFileRefs(
                JSONObject().put("bodyText", "not-json")
            ).isEmpty()
        )
    }
}
