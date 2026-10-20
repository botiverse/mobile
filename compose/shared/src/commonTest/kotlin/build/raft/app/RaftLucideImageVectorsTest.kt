package build.raft.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftLucideImageVectorsTest {
    @Test
    fun everyIconNameResolvesToNonEmptyVector() {
        SlockLucideIconName.entries.forEach { name ->
            val spec = RaftLucideIconPaths.spec(name)
            assertEquals(name.wireName, spec.name, "spec name for $name")
            val vector = RaftLucideImageVectors.vector(name)
            assertEquals(24f, vector.viewportWidth, "viewport width for $name")
            assertEquals(24f, vector.viewportHeight, "viewport height for $name")
            val paths = vector.paths()
            assertEquals(spec.paths.size, paths.size, "path count for $name")
            assertTrue(paths.isNotEmpty(), "no paths for $name")
            paths.forEach { path ->
                assertTrue(path.pathData.isNotEmpty(), "empty path nodes in $name")
                assertTrue(path.fill != null || path.stroke != null, "invisible path in $name")
            }
        }
    }

    @Test
    fun defaultLucideStrokeModel() {
        val path = RaftLucideImageVectors.vector(SlockLucideIconName.Check).paths().single()
        assertEquals(2f, path.strokeLineWidth)
        assertEquals(StrokeCap.Round, path.strokeLineCap)
        assertEquals(StrokeJoin.Round, path.strokeLineJoin)
        assertNull(path.fill)
        assertEquals(SolidColor(Color.Black), path.stroke)
    }

    @Test
    fun strokeWidthOverrideAppliesToStrokedPaths() {
        val path =
            RaftLucideImageVectors.vector(SlockLucideIconName.Check, strokeWidthOverride = 4f).paths().single()
        assertEquals(4f, path.strokeLineWidth)
        val ignored =
            RaftLucideImageVectors.vector(SlockLucideIconName.Check, strokeWidthOverride = 0f).paths().single()
        assertEquals(2f, ignored.strokeLineWidth)
    }

    @Test
    fun vectorsAreCached() {
        assertSame(
            RaftLucideImageVectors.vector(SlockLucideIconName.Bell),
            RaftLucideImageVectors.vector(SlockLucideIconName.Bell)
        )
    }

    @Test
    fun sourceFillsArePreserved() {
        val mosaic = RaftLucideImageVectors.vector(SlockLucideIconName.Mosaic).paths().single()
        assertNotNull(mosaic.fill)
        assertNull(mosaic.stroke)

        val keyRound = RaftLucideImageVectors.vector(SlockLucideIconName.KeyRound).paths()
        assertTrue(keyRound.any { it.fill != null && it.stroke != null })
    }

    @Test
    fun fillSelectsFilledBookmarkVariant() {
        assertEquals("filled-bookmark", RaftLucideImageVectors.spec(SlockLucideIconName.Bookmark, filled = true).name)
        val filled = RaftLucideImageVectors.vector(SlockLucideIconName.Bookmark, filled = true).paths().single()
        assertNotNull(filled.fill)
        assertNotNull(filled.stroke)
        // Icons without a filled variant fall back to the outline.
        assertEquals("bell", RaftLucideImageVectors.spec(SlockLucideIconName.Bell, filled = true).name)
    }

    @Test
    fun stringNamesAreNormalized() {
        assertEquals("message-circle-plus", RaftLucideImageVectors.normalizeName(" messageCirclePlus "))
        assertEquals("arrow-up-down", RaftLucideImageVectors.normalizeName("arrow_up_down"))
    }

    @Test
    fun directPaintsResolve() {
        assertEquals(Color(0xFF112233), RaftLucideImageVectors.resolvePaint("#112233", Color.Red))
        assertEquals(Color(0xFFAABBCC), RaftLucideImageVectors.resolvePaint("#abc", Color.Red))
        assertEquals(Color.Red, RaftLucideImageVectors.resolvePaint("currentColor", Color.Red))
    }

    private fun ImageVector.paths(): List<VectorPath> = root.collectPaths()

    private fun VectorNode.collectPaths(): List<VectorPath> =
        when (this) {
            is VectorPath -> listOf(this)
            is VectorGroup -> flatMap { it.collectPaths() }
        }
}
