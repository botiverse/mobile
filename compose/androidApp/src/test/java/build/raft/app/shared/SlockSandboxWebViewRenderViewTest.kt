package build.raft.app.shared

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockSandboxWebViewRenderViewTest {
    @Test
    fun sameOriginHistoryRouteCannotReuseFencedNativeBridge() {
        val trusted = Uri.parse("https://example.test/feedback/?embed=raft-settings-v1")

        assertTrue(
            isExactMainDocumentUrl(
                trusted,
                "https://example.test/feedback/?embed=raft-settings-v1#draft",
            ),
        )
        assertFalse(
            isExactMainDocumentUrl(
                trusted,
                "https://example.test/account/?embed=raft-settings-v1",
            ),
        )
        assertFalse(
            isExactMainDocumentUrl(
                trusted,
                "https://example.test/feedback/?embed=other",
            ),
        )
    }
}
