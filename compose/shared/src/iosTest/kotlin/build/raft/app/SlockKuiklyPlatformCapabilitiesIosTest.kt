package build.raft.app

import build.raft.app.markdown.SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
import build.raft.app.markdown.slockRichTextPlatformCaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SlockKuiklyPlatformCapabilitiesIosTest {
    @Test
    fun liquidGlassDefaultIsRuntimeGatedAcrossRouteRebuilds() {
        assertEquals("sharedCompose", slockIosAppTabBarRendererForMajorVersion(18L))
        assertEquals("sharedCompose", slockIosAppTabBarRendererForMajorVersion(25L))
        assertEquals("iosLiquidGlass", slockIosAppTabBarRendererForMajorVersion(26L))
        assertEquals("iosLiquidGlass", slockIosAppTabBarRendererForMajorVersion(27L))
    }

    @Test
    fun inlineCodeUsesSharedMetadataAndWrappingEdgeChrome() {
        assertEquals(
            "raft.build.markdown.inlineCodeMetadata",
            SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
        )
        assertNotEquals("raft.build.markdown.inlineCode", SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG)
        assertEquals(null, slockInlineCodeCharWrapBridgeThreshold())
        assertTrue(slockInlineCodeUsesEdgePaddingBridge())
        assertTrue(slockRichTextPlatformCaps().inlineCodeChrome)
        assertFalse(
            slockRichTextPlatformCaps().declaredDivergences.any {
                it.id == "ios-inline-code-char-wrap-bridge"
            }
        )
    }
}
