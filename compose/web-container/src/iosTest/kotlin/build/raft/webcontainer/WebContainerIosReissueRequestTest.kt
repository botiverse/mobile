package build.raft.webcontainer

import platform.Foundation.NSURL
import platform.Foundation.valueForHTTPHeaderField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebContainerIosReissueRequestTest {
    @Test
    fun reissueWithHeaderPreservesSanitizedRefererWithoutFabricating() {
        val url = NSURL.URLWithString("https://stamp.build/auth-check")!!

        val withReferer =
            buildWebContainerIosRequest(
                url = url,
                cacheEnabled = true,
                header = "Raft-Host" to "ios",
                referer = "https://stamp.build/sign-in",
            )
        assertEquals("ios", withReferer.valueForHTTPHeaderField("Raft-Host"))
        assertEquals("https://stamp.build/sign-in", withReferer.valueForHTTPHeaderField("Referer"))

        val withoutReferer =
            buildWebContainerIosRequest(
                url = url,
                cacheEnabled = false,
                header = "Raft-Host" to "ios",
                referer = null,
            )
        assertEquals("ios", withoutReferer.valueForHTTPHeaderField("Raft-Host"))
        assertNull(withoutReferer.valueForHTTPHeaderField("Referer"))

        val rejectedCrLf =
            buildWebContainerIosRequest(
                url = url,
                cacheEnabled = true,
                header = "Raft-Host" to "ios",
                referer = "https://stamp.build/\r\nX-Injected: 1",
            )
        assertEquals("ios", rejectedCrLf.valueForHTTPHeaderField("Raft-Host"))
        assertNull(rejectedCrLf.valueForHTTPHeaderField("Referer"))

        val strippedCrossOrigin =
            buildWebContainerIosRequest(
                url = NSURL.URLWithString("https://other.example/step")!!,
                cacheEnabled = true,
                header = null,
                referer = null,
            )
        assertNull(strippedCrossOrigin.valueForHTTPHeaderField("Raft-Host"))
        assertNull(strippedCrossOrigin.valueForHTTPHeaderField("Referer"))
    }
}
