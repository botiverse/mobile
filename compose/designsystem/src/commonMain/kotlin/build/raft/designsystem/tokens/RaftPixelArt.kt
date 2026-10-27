package build.raft.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Pixel art made of solid rectangles, in its own pixels (1 px = 1dp): raft-ui's theme decor
 * SVGs (`shape-rendering="crispEdges"`), as [RaftThemeDecorArt] holds them. [layers] paint in
 * order, as the SVG's paths do.
 */
@Immutable
internal class RaftPixelArt(val width: Int, val height: Int, val layers: List<Layer>) {
    /** One SVG path: its [color] and its rectangles, `x y w h` four ints apiece in [rects]. */
    @Immutable
    class Layer(argb: Long, rects: String) {
        val color: Color = Color(argb)
        val rects: IntArray = rects.split(' ').map { it.toInt() }.toIntArray()

        init {
            require(this.rects.isNotEmpty() && this.rects.size % 4 == 0) { "rects are x y w h quadruples" }
        }
    }
}
