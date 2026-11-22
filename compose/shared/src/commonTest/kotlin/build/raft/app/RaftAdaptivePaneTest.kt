package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import build.raft.app.assistant.threadAssistantPanelWide
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAdaptivePaneTest {
    @Test
    fun wideFlatWindowReducesToDualPane() {
        val state =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 1680,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat
                )
            )

        assertEquals(RaftAdaptivePaneLayout.DualPane, state.layout)
        assertEquals(RaftAdaptivePanePosture.Flat, state.posture)
    }

    @Test
    fun halfFoldedWindowTakesDualPaneWhenWideEnough() {
        // task #180 (artin 2026-10-17): a half-folded posture is no longer excluded from the second
        // pane. 2200px / 2f density = 1100dp, above the 768dp rule, so it is DualPane. This reverses
        // the previous "half-fold never opens a second pane" contract on purpose.
        val state =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.HalfFolded
                )
            )

        assertEquals(RaftAdaptivePaneLayout.DualPane, state.layout)
    }

    @Test
    fun theRailStartsAtTheIpadMinisPortraitWidth() {
        // Direction A (#proj-mobile 10-30): wide from 744dp (artin), not 840 and 24 under the web's
        // `md` 768, so every iPad held upright gets the rail: 744 (iPad mini 6 and later), 768,
        // 810, 820 (Air 11"), 834 (Pro 11"). Phones in portrait keep the phone layout.
        assertEquals(744f, DualPaneMinimumWidthDp)
        fun layoutAt(widthPt: Int, scale: Float = 2f) =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent((widthPt * scale).toInt(), (1100 * scale).toInt(), scale, RaftAdaptivePanePosture.Flat)
            ).layout
        listOf(744, 768, 810, 820, 834, 1024, 1366).forEach { width ->
            assertEquals(RaftAdaptivePaneLayout.DualPane, layoutAt(width), "width $width")
        }
        listOf(402, 700, 743).forEach { width ->
            assertEquals(RaftAdaptivePaneLayout.Compact, layoutAt(width), "width $width")
        }
    }

    @Test
    fun anIpadMiniUprightGetsTheWideAssistantPanelAndCards() {
        // #307: the assistant panel (and so Connect WeChat), App link cards, artifact card
        // screenshots and the DM head's sign-in label were copied from the web's `md` breakpoint,
        // so a 744-767 window (an iPad mini upright) had the rail and two panes but their phone
        // variants. They follow the rail's rule ([raftWindowWide]) now.
        val ipadMini = 750.dp
        assertTrue(threadAssistantPanelWide(ipadMini), "the assistant panel is a popover")
        assertTrue(threadAppLinkBlockCardMaxWidth(ipadMini).isSpecified, "a link card keeps its wide cap")
        assertFalse(artifactScreenshotPhoneLayout(ipadMini), "an artifact card shows its desktop screenshot")
        // A large phone keeps the phone variants.
        val phone = 430.dp
        assertFalse(threadAssistantPanelWide(phone))
        assertTrue(threadAppLinkBlockCardMaxWidth(phone).isUnspecified)
        assertTrue(artifactScreenshotPhoneLayout(phone))
    }

    @Test
    fun unknownPostureStaysCompactUntilHostFactsSettle() {
        val unknown =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Unknown
                )
            )
        val settled =
            reduceRaftAdaptivePane(
                unknown,
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat
                )
            )

        assertEquals(RaftAdaptivePaneLayout.Compact, unknown.layout)
        assertEquals(RaftAdaptivePaneLayout.DualPane, settled.layout)
    }

    @Test
    fun invalidHostEventDoesNotEraseLastKnownWindowFact() {
        val previous =
            RaftAdaptivePaneState(
                widthPx = 1200,
                heightPx = 800,
                density = 2f,
                posture = RaftAdaptivePanePosture.Unknown,
                layout = RaftAdaptivePaneLayout.Compact
            )

        assertEquals(
            previous,
            reduceRaftAdaptivePane(
                previous,
                RaftAdaptivePaneEvent(0, 800, 2f, RaftAdaptivePanePosture.Flat)
            )
        )
    }

    @Test
    fun theDisplayIsParsedFromTheHostEnvelope() {
        // The Duo's two displays are told apart by the host; shared reads which one the window is
        // on so it can drop the top bar on the inner display even in portrait.
        fun displayOf(raw: String?): RaftAdaptiveDisplay {
            val json = JSONObject()
            json.put("widthPx", 2007)
            json.put("heightPx", 2853)
            json.put("density", 3f)
            json.put("posture", "flat")
            if (raw != null) {
                json.put("display", raw)
            }
            val event = json.toRaftAdaptivePaneEventOrNull()
            return event?.display ?: RaftAdaptiveDisplay.Other
        }

        assertEquals(RaftAdaptiveDisplay.DuoOuter, displayOf("duoOuter"))
        assertEquals(RaftAdaptiveDisplay.DuoInner, displayOf("duoInner"))
        // A missing or unrecognised value is "other", not a guess.
        assertEquals(RaftAdaptiveDisplay.Other, displayOf(null))
        assertEquals(RaftAdaptiveDisplay.Other, displayOf("somethingElse"))
    }
}
