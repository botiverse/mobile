package build.raft.app.thread

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadArtifactMessageCardPictureHoldTest {
    private val idA = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
    private val idB = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f"

    @Test
    fun drawsPictureBoxMatchesShownPendingAndUnreadableOnly() {
        assertTrue(ArtifactCardScreenshotView.Shown(idA, "of_1", byteArrayOf(1)).drawsPictureBox())
        assertTrue(ArtifactCardScreenshotView.Pending.drawsPictureBox())
        assertTrue(ArtifactCardScreenshotView.Unreadable.drawsPictureBox())
        assertFalse(ArtifactCardScreenshotView.None.drawsPictureBox())
        assertFalse(ArtifactCardScreenshotView.Held.drawsPictureBox())
    }

    @Test
    fun holdsPictureBoxOnlyForSameArtifactWithoutRawScreenshotExcerptOrLapse() {
        assertTrue(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.None,
                hasExcerpt = false,
                cardId = idA,
                boxFor = idA,
                lapsedFor = null,
            )
        )
        assertFalse(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.None,
                hasExcerpt = true,
                cardId = idA,
                boxFor = idA,
                lapsedFor = null,
            ),
            "Markdown excerpt replaces the picture box without holding"
        )
        assertFalse(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.None,
                hasExcerpt = false,
                cardId = idA,
                boxFor = null,
                lapsedFor = null,
            ),
            "card that never drew a picture box does not hold"
        )
        assertFalse(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.None,
                hasExcerpt = false,
                cardId = idB,
                boxFor = idA,
                lapsedFor = null,
            ),
            "switching to another Artifact does not inherit the previous card's box"
        )
        assertFalse(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.None,
                hasExcerpt = false,
                cardId = idA,
                boxFor = idA,
                lapsedFor = idA,
            ),
            "after 10 minutes elapse the held box is dropped"
        )
        assertFalse(
            artifactMessageCardHoldsPictureBox(
                raw = ArtifactCardScreenshotView.Pending,
                hasExcerpt = false,
                cardId = idA,
                boxFor = idA,
                lapsedFor = null,
            ),
            "a live loading screenshot renders Pending directly rather than Held"
        )
    }
}
