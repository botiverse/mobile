package build.raft.app.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** #proj-mobile:#202: an API download's session is used once, for its own address only. */
class AttachmentDownloadSessionHeadersTest {
    private val url = "https://app.raft.build/api/artifacts/pt_1/download.zip?version=3"
    private val headers = mapOf(HEADER_AUTHORIZATION to "Bearer t", HEADER_SERVER_ID to "s1")

    @AfterTest
    fun reset() {
        AttachmentDownloadSessionHeaders.clearForTest()
        AttachmentDownloadSessionHeaders.timeSource = TimeSource.Monotonic
    }

    @Test
    fun anotherUrlOrDownloadGetsNothingAndDropsTheEntry() {
        AttachmentDownloadSessionHeaders.register("d1", url, headers)
        assertNull(AttachmentDownloadSessionHeaders.take("d2", url))
        assertNull(AttachmentDownloadSessionHeaders.take("d1", "https://evil.example/x"))
        assertNull(AttachmentDownloadSessionHeaders.take("d1", url))
    }

    @Test
    fun expiresAfterTheTtl() {
        val time = TestTimeSource()
        AttachmentDownloadSessionHeaders.timeSource = time
        AttachmentDownloadSessionHeaders.register("d1", url, headers)
        time += (AttachmentDownloadSessionHeaders.ttlMillis + 1).milliseconds
        assertNull(AttachmentDownloadSessionHeaders.take("d1", url))
    }
}
