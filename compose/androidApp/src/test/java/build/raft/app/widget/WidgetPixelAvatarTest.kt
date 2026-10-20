package build.raft.app.widget

import build.raft.app.raftPixelAvatarRaster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPixelAvatarTest {
    // The widget's former hard-coded table (pre shared-data migration). Resolving these keys
    // through the shared table must stay pixel-identical.
    private val legacyPalette =
        mapOf(
            '_' to 0x00000000,
            'K' to 0xFF141111.toInt(),
            'W' to 0xFFFFFFFF.toInt(),
            'P' to 0xFFFE7DA8.toInt(),
            'O' to 0xFFF8A16F.toInt(),
            'Y' to 0xFFFFD440.toInt(),
            'L' to 0xFFA9D877.toInt(),
            'C' to 0xFF27CCF3.toInt(),
            'V' to 0xFFBBAFE6.toInt(),
            'B' to 0xFF576574.toInt(),
            'R' to 0xFFF97264.toInt(),
            'G' to 0xFF9DCAAA.toInt()
        )

    private val legacySpecs =
        mapOf(
            "robot" to ('C' to listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
            "ghost" to ('V' to listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")),
            "alien" to ('L' to listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")),
            "cat" to ('Y' to listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")),
            "flame" to ('O' to listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___"))
        )

    @Test
    fun legacyWidgetKeysRenderPixelIdenticalFromSharedTable() {
        legacySpecs.forEach { (key, legacy) ->
            val (backgroundKey, grid) = legacy
            val raster = requireNotNull(raftPixelAvatarRaster("pixel:$key")) { key }
            assertEquals(key, legacyPalette.getValue(backgroundKey), raster.backgroundArgb)
            grid.forEachIndexed { rowIndex, row ->
                val expected = IntArray(row.length) { legacyPalette.getValue(row[it]) }
                assertArrayEquals("$key row $rowIndex", expected, raster.cellArgb[rowIndex])
            }
            assertArrayEquals(key, legacyRender(legacyPalette.getValue(backgroundKey), grid), raster.widgetPixelArgb())
        }
    }

    @Test
    fun unknownKeysFallBackToDefaultRobot() {
        val robot = raftPixelAvatarRaster("pixel:robot")!!.widgetPixelArgb()
        assertArrayEquals(robot, raftPixelAvatarRaster("pixel:not-a-real-avatar")!!.widgetPixelArgb())
        assertArrayEquals(robot, raftPixelAvatarRaster("pixel:")!!.widgetPixelArgb())
    }

    @Test
    fun keysBeyondTheLegacyTableResolve() {
        // e.g. `star` was not in the widget's old table and used to render as robot.
        val robot = raftPixelAvatarRaster("pixel:robot")!!.widgetPixelArgb()
        val star = raftPixelAvatarRaster("pixel:star")!!.widgetPixelArgb()
        assertFalse(robot.contentEquals(star))
    }

    @Test
    fun randomSeedsResolveDeterministically() {
        val first = requireNotNull(raftPixelAvatarRaster("pixel:random:agent-1")).widgetPixelArgb()
        val again = raftPixelAvatarRaster("pixel:random:agent-1")!!.widgetPixelArgb()
        assertArrayEquals(first, again)
        // Legacy bare `random:` maps to the canonical `pixel:random:` form.
        assertEquals("pixel:random:agent-1", "random:agent-1".toSharedPixelAvatarWireValue())
        assertEquals("pixel:cat", "pixel:cat".toSharedPixelAvatarWireValue())
        assertNull(raftPixelAvatarRaster("pixel:random:"))
        assertNull(raftPixelAvatarRaster("https://example.com/a.png"))
    }

    // Mirrors the former Canvas path: background fill, then each opaque cell as a rect with
    // edges rounded from extent * index / 8.
    private fun legacyRender(background: Int, grid: List<String>, size: Int = 48): IntArray {
        fun edge(index: Int): Int = Math.round(size.toFloat() * index / 8)
        val pixels = IntArray(size * size) { background }
        grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = legacyPalette.getValue(key)
                if (color != 0) {
                    for (y in edge(rowIndex) until edge(rowIndex + 1)) {
                        for (x in edge(columnIndex) until edge(columnIndex + 1)) pixels[y * size + x] = color
                    }
                }
            }
        }
        return pixels
    }
}
