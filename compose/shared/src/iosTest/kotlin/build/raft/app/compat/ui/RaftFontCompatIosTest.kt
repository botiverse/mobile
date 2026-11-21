package build.raft.app.compat.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.PlatformFont
import androidx.compose.ui.text.platform.SystemFont
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTextApi::class)
class RaftFontCompatIosTest {
    @BeforeTest
    fun setUp() {
        resetRaftBundledFontPathCacheForTests()
    }

    @AfterTest
    fun tearDown() {
        resetRaftBundledFontPathCacheForTests()
    }

    @Test
    fun kuiklyFontCachesPositiveBundlePathLookups() {
        val lookups = mutableMapOf<String, Int>()
        raftBundleFontPathLookupForTests = { resourceName ->
            lookups[resourceName] = (lookups[resourceName] ?: 0) + 1
            if (resourceName == "space_grotesk_bold") "/bundle/space_grotesk_bold.ttf" else null
        }

        val first = KuiklyFont("SpaceGrotesk-Bold", FontWeight.Bold, FontStyle.Normal)
        val second = KuiklyFont("SpaceGrotesk-Bold", FontWeight.Bold, FontStyle.Normal)
        val third = KuiklyFont("SpaceGrotesk-Bold", FontWeight.Normal, FontStyle.Normal)

        assertEquals(1, lookups["space_grotesk_bold"])
        assertFalse(first is SystemFont)
        assertFalse(second is SystemFont)
        assertFalse(third is SystemFont)
        assertEquals("SpaceGrotesk-Bold", (first as? PlatformFont)?.identity)
        assertEquals("SpaceGrotesk-Bold", (second as? PlatformFont)?.identity)
        assertEquals("SpaceGrotesk-Bold", (third as? PlatformFont)?.identity)
    }

    @Test
    fun kuiklyFontCachesNegativeLookupsAndFallbackPath() {
        val lookups = mutableMapOf<String, Int>()
        raftBundleFontPathLookupForTests = { resourceName ->
            lookups[resourceName] = (lookups[resourceName] ?: 0) + 1
            if (resourceName == "space_grotesk_regular") "/bundle/space_grotesk_regular.ttf" else null
        }

        repeat(3) {
            val font = KuiklyFont("UnknownFace", FontWeight.Normal, FontStyle.Normal)
            assertFalse(font is SystemFont)
            assertEquals("UnknownFace", (font as? PlatformFont)?.identity)
        }

        assertEquals(1, lookups["unknown_face"])
        assertEquals(1, lookups["space_grotesk_regular"])
    }

    @Test
    fun kuiklyFontCachesMissWhenFallbackIsAlsoAbsent() {
        val lookups = mutableMapOf<String, Int>()
        raftBundleFontPathLookupForTests = { resourceName ->
            lookups[resourceName] = (lookups[resourceName] ?: 0) + 1
            null
        }

        repeat(3) {
            val font = KuiklyFont("MissingFace", FontWeight.Normal, FontStyle.Normal)
            assertTrue(font is SystemFont)
            assertEquals("MissingFace", (font as? PlatformFont)?.identity)
        }

        assertEquals(1, lookups["missing_face"])
        assertEquals(1, lookups["space_grotesk_regular"])
    }
}
