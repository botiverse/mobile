package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Recent activity rows' shared time column on a real composition ([MembersAgentRecentTurnRows]):
 * with a turn of today ("00:18") above two of the day before ("06-18 23:57"), every row's text
 * starts at the same x, right of the widest time label, and no label is cut or wrapped. Also at a
 * 2x font scale and with the 12-hour clock, where the labels are wider.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class MembersAgentRecentTurnRowsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun remember() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.rows(case: AgentRecentActivityCase, fontScale: Float = 1f, timeFormat: String? = null) {
        setContent {
            MigrationFixtureHost {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    // The card's inner width on a 390dp phone.
                    Box(Modifier.width(318.dp)) {
                        MembersAgentRecentTurnRows(
                            rows = case.finished,
                            timeContext = timeFormat?.let { case.timeContext.copy(timeFormat = it) } ?: case.timeContext,
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): List<Rect> =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun ComposeUiTest.assertOneTextColumn() {
        val times = bounds(MembersAgentRecentTurnTimeTag)
        val texts = bounds(MembersAgentRecentTurnTextTag)
        assertEquals(3, times.size, "times=$times")
        assertEquals(3, texts.size, "texts=$texts")
        // The labels differ in width (one has no date), and all start at the column's left.
        assertTrue(times.map { it.width }.distinct().size > 1, "the labels should differ in width: $times")
        assertEquals(1, times.map { it.left }.distinct().size, "times=$times")
        // Every text starts at one x, right of the widest label, and ends at the rows' right edge.
        assertEquals(1, texts.map { it.left }.distinct().size, "texts=$texts")
        assertEquals(1, texts.map { it.right }.distinct().size, "texts=$texts")
        assertTrue(texts.first().left > times.maxOf { it.right }, "times=$times texts=$texts")
        // Rows do not overlap: each starts below the one before, time and text on the same top.
        texts.zipWithNext { above, below -> assertTrue(below.top >= above.bottom, "texts=$texts") }
        times.zip(texts) { time, text -> assertTrue(time.top >= text.top && time.top < text.bottom, "times=$times texts=$texts") }
    }

    @Test
    fun textsStartAtOneXWhenTurnsCrossADay() =
        runRobolectricComposeUiTest {
            rows(agentRecentActivityAcrossDays())
            assertOneTextColumn()
        }
}
