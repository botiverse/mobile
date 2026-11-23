package build.raft.app

import android.content.pm.ActivityInfo
import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftVideoPlayerLifecycleTest {
    @Test
    fun playbackPausesOnlyWhenTheHostGoesToTheBackground() {
        val pausing = Lifecycle.Event.values().filter(::raftVideoPausesOnLifecycleEvent)
        assertEquals(listOf(Lifecycle.Event.ON_STOP), pausing)
    }

    @Test
    fun fullscreenOrientationFollowsVideoAspectRatio() {
        // Portrait phone screen recording (9:16) stays portrait instead of forcing landscape.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            raftAndroidVideoFullscreenOrientation(width = 1080, height = 1920),
        )
        // Landscape video (16:9) rotates to sensor landscape.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            raftAndroidVideoFullscreenOrientation(width = 1920, height = 1080),
        )
        // Square or unknown dimensions do not force a rotation.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            raftAndroidVideoFullscreenOrientation(width = 1080, height = 1080),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            raftAndroidVideoFullscreenOrientation(width = 0, height = 0),
        )
        // Anamorphic pixel ratio flips effective display orientation when display width > height.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            raftAndroidVideoFullscreenOrientation(width = 720, height = 1080, pixelWidthHeightRatio = 2f),
        )
    }

    @Test
    fun scrubFractionAndTargetClampCleanlyAcrossTrackInsets() {
        assertEquals(0f, raftAndroidVideoScrubFraction(xPx = 0f, widthPx = 220f, insetPx = 10f))
        assertEquals(0f, raftAndroidVideoScrubFraction(xPx = 10f, widthPx = 220f, insetPx = 10f))
        assertEquals(0.5f, raftAndroidVideoScrubFraction(xPx = 110f, widthPx = 220f, insetPx = 10f))
        assertEquals(1f, raftAndroidVideoScrubFraction(xPx = 210f, widthPx = 220f, insetPx = 10f))
        assertEquals(1f, raftAndroidVideoScrubFraction(xPx = 300f, widthPx = 220f, insetPx = 10f))
        assertEquals(0f, raftAndroidVideoScrubFraction(xPx = 50f, widthPx = 10f, insetPx = 10f))
        assertEquals(0f, raftAndroidVideoScrubFraction(xPx = Float.NaN, widthPx = 220f, insetPx = 10f))

        assertEquals(0L, raftAndroidVideoScrubTargetMs(fraction = -0.2f, durationMs = 60_000L))
        assertEquals(30_000L, raftAndroidVideoScrubTargetMs(fraction = 0.5f, durationMs = 60_000L))
        assertEquals(60_000L, raftAndroidVideoScrubTargetMs(fraction = 1.5f, durationMs = 60_000L))
        assertEquals(0L, raftAndroidVideoScrubTargetMs(fraction = 0.5f, durationMs = 0L))
        assertEquals(0L, raftAndroidVideoScrubTargetMs(fraction = Float.NaN, durationMs = 60_000L))
    }

    @Test
    fun horizontalSurfaceDragUsesFullDurationForShortClipsAndCapsLongVideos() {
        // Short 20s clip: half-width swipe right advances 10s.
        assertEquals(
            15_000L,
            raftAndroidVideoHorizontalDragTargetMs(
                startPositionMs = 5_000L,
                dragDeltaPx = 200f,
                surfaceWidthPx = 400f,
                durationMs = 20_000L,
            ),
        )
        // Long 30m video: full-width sweep caps at 90s so half-width moves 45s.
        assertEquals(
            145_000L,
            raftAndroidVideoHorizontalDragTargetMs(
                startPositionMs = 100_000L,
                dragDeltaPx = 200f,
                surfaceWidthPx = 400f,
                durationMs = 1_800_000L,
            ),
        )
        // Never underflows 0 or overflows duration.
        assertEquals(
            0L,
            raftAndroidVideoHorizontalDragTargetMs(
                startPositionMs = 4_000L,
                dragDeltaPx = -400f,
                surfaceWidthPx = 400f,
                durationMs = 20_000L,
            ),
        )
    }

    @Test
    fun horizontalSurfaceDragArbitrationYieldsVerticalScrollsToMessageList() {
        val slop = 24f
        assertTrue(raftAndroidVideoHorizontalDragShouldClaim(dx = 40f, dy = 10f, touchSlopPx = slop))
        assertTrue(raftAndroidVideoHorizontalDragShouldClaim(dx = -40f, dy = 20f, touchSlopPx = slop))
        assertFalse(raftAndroidVideoHorizontalDragShouldClaim(dx = 20f, dy = 0f, touchSlopPx = slop))
        assertFalse(raftAndroidVideoHorizontalDragShouldClaim(dx = 40f, dy = 35f, touchSlopPx = slop))

        assertTrue(raftAndroidVideoHorizontalDragShouldRelease(dx = 10f, dy = 30f, touchSlopPx = slop))
        assertTrue(raftAndroidVideoHorizontalDragShouldRelease(dx = 30f, dy = 30f, touchSlopPx = slop))
        assertFalse(raftAndroidVideoHorizontalDragShouldRelease(dx = 40f, dy = 10f, touchSlopPx = slop))
    }

    @Test
    fun doubleTapZonesAndRelativeSeekAccumulationBehavePredictably() {
        assertEquals(RaftAndroidVideoTapZone.Rewind, raftAndroidVideoDoubleTapZone(xPx = 50f, widthPx = 300f))
        assertEquals(RaftAndroidVideoTapZone.Center, raftAndroidVideoDoubleTapZone(xPx = 150f, widthPx = 300f))
        assertEquals(RaftAndroidVideoTapZone.Forward, raftAndroidVideoDoubleTapZone(xPx = 250f, widthPx = 300f))

        assertEquals(20_000L, raftAndroidVideoAccumulateDoubleTapDelta(10_000L, 10_000L))
        assertEquals(-20_000L, raftAndroidVideoAccumulateDoubleTapDelta(-10_000L, -10_000L))
        assertEquals(-10_000L, raftAndroidVideoAccumulateDoubleTapDelta(20_000L, -10_000L))

        assertEquals(0L, raftAndroidVideoResolveRelativeSeekTarget(basePositionMs = 4_000L, deltaMs = -10_000L, durationMs = 30_000L))
        assertEquals(30_000L, raftAndroidVideoResolveRelativeSeekTarget(basePositionMs = 25_000L, deltaMs = 10_000L, durationMs = 30_000L))
    }

    @Test
    fun speedCycleTimeFormattingAndAutoHideRules() {
        assertEquals(1.25f, raftAndroidVideoNextSpeed(1.0f))
        assertEquals(1.5f, raftAndroidVideoNextSpeed(1.25f))
        assertEquals(2.0f, raftAndroidVideoNextSpeed(1.5f))
        assertEquals(0.5f, raftAndroidVideoNextSpeed(2.0f))
        assertEquals(1.0f, raftAndroidVideoNextSpeed(0.5f))
        assertEquals(1.0f, raftAndroidVideoNextSpeed(0.75f))

        assertEquals("1x", raftAndroidVideoSpeedLabel(1.0f))
        assertEquals("1.25x", raftAndroidVideoSpeedLabel(1.25f))
        assertEquals("1.5x", raftAndroidVideoSpeedLabel(1.5f))
        assertEquals("2x", raftAndroidVideoSpeedLabel(2.0f))
        assertEquals("0.5x", raftAndroidVideoSpeedLabel(0.5f))

        assertEquals("0:05", raftAndroidVideoFormatTime(5_000L, 45_000L))
        assertEquals("1:02:05", raftAndroidVideoFormatTime(3_725_000L, 3_725_000L))
        assertEquals("0:05:09", raftAndroidVideoFormatTime(309_000L, 4_000_000L))
        assertEquals("+10s", raftAndroidVideoFormatSeekDelta(10_000L))
        assertEquals("-10s", raftAndroidVideoFormatSeekDelta(-10_000L))
        assertEquals("+1:05", raftAndroidVideoFormatSeekDelta(65_000L))
        assertEquals("0s", raftAndroidVideoFormatSeekDelta(0L))

        assertTrue(raftAndroidVideoShouldAutoHideControls(isPlaying = true, isScrubbing = false, isAudioOnly = false, hasError = false))
        assertFalse(raftAndroidVideoShouldAutoHideControls(isPlaying = false, isScrubbing = false, isAudioOnly = false, hasError = false))
        assertFalse(raftAndroidVideoShouldAutoHideControls(isPlaying = true, isScrubbing = true, isAudioOnly = false, hasError = false))
        assertFalse(raftAndroidVideoShouldAutoHideControls(isPlaying = true, isScrubbing = false, isAudioOnly = true, hasError = false))
        assertFalse(raftAndroidVideoShouldAutoHideControls(isPlaying = true, isScrubbing = false, isAudioOnly = false, hasError = true))

        assertFalse(raftAndroidVideoShouldShowInlineControls(widthDp = 1f, heightDp = 1f))
        assertTrue(raftAndroidVideoShouldShowInlineControls(widthDp = 320f, heightDp = 180f))
    }
}
