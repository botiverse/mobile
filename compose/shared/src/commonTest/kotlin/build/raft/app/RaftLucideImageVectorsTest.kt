package build.raft.app

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaftLucideImageVectorsTest {
    @Test
    fun everyIconNameResolvesToNonEmptyVector() {
        RaftLucideIconName.entries.forEach { name ->
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
    fun fillSelectsFilledBookmarkVariant() {
        assertEquals("filled-bookmark", RaftLucideImageVectors.spec(RaftLucideIconName.Bookmark, filled = true).name)
        val filled = RaftLucideImageVectors.vector(RaftLucideIconName.Bookmark, filled = true).paths().single()
        assertNotNull(filled.fill)
        assertNotNull(filled.stroke)
        // Icons without a filled variant fall back to the outline.
        assertEquals("bell", RaftLucideImageVectors.spec(RaftLucideIconName.Bell, filled = true).name)
    }

    @Test
    fun stringNamesAreNormalized() {
        assertEquals("message-circle-plus", RaftLucideImageVectors.normalizeName(" messageCirclePlus "))
        assertEquals("arrow-up-down", RaftLucideImageVectors.normalizeName("arrow_up_down"))
    }

    private fun ImageVector.paths(): List<VectorPath> = root.collectPaths()

    private fun VectorNode.collectPaths(): List<VectorPath> =
        when (this) {
            is VectorPath -> listOf(this)
            is VectorGroup -> flatMap { it.collectPaths() }
        }
}
