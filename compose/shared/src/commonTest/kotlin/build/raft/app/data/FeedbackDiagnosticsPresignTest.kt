package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeedbackDiagnosticsPresignTest {
    @Test
    fun requestBuilderUsesCanonicalHandsWireFields() {
        val encoded = encodeSharedFeedbackDiagnosticsPresignRequest(
            filename = "raft-feedback.zip",
            contentType = FEEDBACK_DIAGNOSTICS_CONTENT_TYPE,
            size = 42L,
        )
        val file = Json.parseToJsonElement(encoded).jsonObject["files"]!!
            .jsonArray.single().jsonObject
        assertEquals("raft-feedback.zip", file["filename"]!!.jsonPrimitive.content)
        assertEquals("application/zip", file["content_type"]!!.jsonPrimitive.content)
        assertEquals("42", file["size"]!!.jsonPrimitive.content)
    }

    @Test
    fun responseParserReturnsTheSinglePresignedUpload() {
        val parsed = parseSharedFeedbackDiagnosticsPresignResponse(
            """
            {
              "uploads": [
                {
                  "attachment_id": "attachment-1",
                  "r2_key": "feedback/app/presigned/attachment-1-raft-feedback.zip",
                  "upload_url": "https://objects.example/upload?signature=redacted",
                  "expires_at": 1790000000000
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("attachment-1", parsed.attachmentId)
        assertEquals("feedback/app/presigned/attachment-1-raft-feedback.zip", parsed.r2Key)
        assertEquals("https://objects.example/upload?signature=redacted", parsed.uploadUrl)
        assertEquals(1790000000000L, parsed.expiresAt)
    }

    @Test
    fun responseParserRejectsAnAmbiguousUploadList() {
        assertFailsWith<IllegalArgumentException> {
            parseSharedFeedbackDiagnosticsPresignResponse("""{"uploads": []}""")
        }
        assertFailsWith<IllegalArgumentException> {
            parseSharedFeedbackDiagnosticsPresignResponse(
                """{"uploads":[{"attachment_id":"a","r2_key":"k","upload_url":"https://u","expires_at":1},{"attachment_id":"b","r2_key":"k2","upload_url":"https://u2","expires_at":1}]}""",
            )
        }
    }

    @Test
    fun requestBuilderRejectsUnsupportedArchives() {
        assertFailsWith<IllegalArgumentException> {
            encodeSharedFeedbackDiagnosticsPresignRequest("diag.zip", "application/octet-stream", 4L)
        }
        assertFailsWith<IllegalArgumentException> {
            encodeSharedFeedbackDiagnosticsPresignRequest("diag.zip", FEEDBACK_DIAGNOSTICS_CONTENT_TYPE, 0L)
        }
    }
}
