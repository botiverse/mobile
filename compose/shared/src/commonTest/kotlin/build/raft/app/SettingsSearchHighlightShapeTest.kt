package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import build.raft.designsystem.tokens.RaftRadius
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The located highlight's geometry (SettingsSearchLocate.kt): on a section that starts with its
 * own header it reaches [SettingsSearchSectionOutset] outside the section, so the header's icon and
 * title are not flush with its edge; on a row or a framed card it stays on the bounds.
 */
class SettingsSearchHighlightShapeTest {
    private val density = Density(density = 3f)

    @Test
    fun outsetBoundsGrowEverySide() {
        assertEquals(Rect(-24f, -24f, 324f, 224f), settingsSearchOutsetBounds(Size(300f, 200f), 24f))
        assertEquals(Rect(0f, 0f, 300f, 200f), settingsSearchOutsetBounds(Size(300f, 200f), 0f))
    }

    @Test
    fun aSectionsHighlightReachesOutsideItWithAConcentricCorner() {
        val outline = SettingsSearchSectionShape.createOutline(Size(300f, 200f), LayoutDirection.Ltr, density)
        val rounded = assertIs<Outline.Rounded>(outline)
        val outset = SettingsSearchSectionOutset.value * 3f
        assertEquals(Rect(-outset, -outset, 300f + outset, 200f + outset), rounded.bounds)
        // The card's corner plus the outset, so the tint's corner follows the card's inside it.
        assertEquals((RaftRadius.Container.value + SettingsSearchSectionOutset.value) * 3f, rounded.roundRect.topLeftCornerRadius.x)
    }

    @Test
    fun aRowOrCardHighlightStaysOnItsBounds() {
        for (shape in listOf(SettingsSearchRowShape, SettingsSearchCardShape)) {
            assertEquals(Rect(0f, 0f, 300f, 200f), shape.createOutline(Size(300f, 200f), LayoutDirection.Ltr, density).bounds)
        }
    }
}
