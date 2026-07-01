package build.raft.app.data

import build.raft.app.network.MultipartFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class KuiklySlockNetworkClientMultipartTest {
    @Test
    fun multipartBodySerializesFilesFieldAndSanitizedFilename() {
        val body =
            buildMultipartBody(
                boundary = "test-boundary",
                fields = mapOf("channelId" to "channel-1"),
                files =
                    listOf(
                        MultipartFile(
                            name = "files",
                            fileName = "picked\"photo\r\n.png",
                            mimeType = "image/png",
                            bytes = byteArrayOf(1, 2, 3)
                        )
                    )
            ).decodeToString()

        assertContains(body, "Content-Disposition: form-data; name=\"channelId\"")
        assertContains(body, "Content-Disposition: form-data; name=\"files\"; filename=\"picked'photo.png\"")
        assertContains(body, "Content-Type: image/png")
        assertFalse(body.contains("form-data; name=\"files\"; form-data"))
    }
}
