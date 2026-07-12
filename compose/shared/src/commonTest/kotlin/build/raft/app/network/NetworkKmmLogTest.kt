package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NetworkKmmLogTest {
    @Test
    fun transportDebugAndInfoDoNotEnterAppDiagnostics() {
        val logs = mutableListOf<String>()
        val logger = createNetworkKmmLog(logs::add)

        logger.d("NXNetwork_CurlWrapper", "request header[0]")
        logger.i("NXNetwork_CurlWrapper", "DebugCallback: [HTTP/2] OPENED stream")
        logger.i("NXNetwork_CurlWrapper", "request statistics totalTime:463")

        assertEquals(emptyList(), logs)
    }

    @Test
    fun transportErrorsRemainInAppDiagnostics() {
        val logs = mutableListOf<String>()
        val logger = createNetworkKmmLog(logs::add)

        logger.e("NXNetwork_CurlWrapper", "transport failed", IllegalStateException("boom"))

        assertEquals(1, logs.size)
        assertContains(logs.single(), "slock_networkkmm_error")
        assertContains(logs.single(), "tag=NXNetwork_CurlWrapper")
        assertContains(logs.single(), "transport failed")
    }
}
