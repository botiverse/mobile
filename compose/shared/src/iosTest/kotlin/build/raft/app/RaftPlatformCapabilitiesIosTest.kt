package build.raft.app

import build.raft.app.markdown.RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
import build.raft.app.markdown.raftRichTextPlatformCaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RaftPlatformCapabilitiesIosTest {
    @Test
    fun kuiklyRemainsTheSnapshotConsumerWithoutAndroidxCompose() {
        assertTrue(raftPlatformConsumesSnapshotsFromKuikly())
    }

    @Test
    fun threadImeRestoresAutomaticViewportCorrection() {
        assertEquals(
            ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
            raftPlatformThreadImeProgrammaticViewportPolicy(),
        )
    }

    @Test
    fun liquidGlassDefaultIsRuntimeGatedAcrossRouteRebuilds() {
        assertEquals("sharedCompose", raftIosAppTabBarRendererForMajorVersion(18L))
        assertEquals("sharedCompose", raftIosAppTabBarRendererForMajorVersion(25L))
        assertEquals("iosLiquidGlass", raftIosAppTabBarRendererForMajorVersion(26L))
        assertEquals("iosLiquidGlass", raftIosAppTabBarRendererForMajorVersion(27L))
    }

    @Test
    fun inlineCodeUsesSharedMetadataAndWrappingEdgeChrome() {
        assertEquals(
            "raft.build.markdown.inlineCodeMetadata",
            RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
        )
        assertNotEquals("raft.build.markdown.inlineCode", RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG)
        assertEquals(null, raftInlineCodeCharWrapBridgeThreshold())
        assertTrue(raftInlineCodeUsesEdgePaddingBridge())
        assertTrue(raftRichTextPlatformCaps().inlineCodeChrome)
        assertFalse(
            raftRichTextPlatformCaps().declaredDivergences.any {
                it.id == "ios-inline-code-char-wrap-bridge"
            }
        )
    }
}
