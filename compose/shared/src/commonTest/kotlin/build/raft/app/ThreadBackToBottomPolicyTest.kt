package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadBackToBottomPolicyTest {
    @Test
    fun renderedWindowTailStillShowsButtonWhenCanonicalTailIsMissing() {
        assertTrue(
            shouldShowThreadBackToBottomButton(
                ThreadBackToBottomButtonState(
                    initialBottomAnchorReady = true,
                    pendingKeyboardBottomAnchor = false,
                    canScrollTowardBottom = false,
                    canonicalTailMissing = true,
                    latestItemOffBottom = false
                )
            )
        )
    }

    @Test
    fun delayedTailLoadYieldsToANewerUserGesture() {
        assertTrue(
            shouldCompleteThreadBackToBottom(
                canonicalTailLoaded = true,
                clickUserScrollGeneration = 7,
                currentUserScrollGeneration = 7
            )
        )
        assertFalse(
            shouldCompleteThreadBackToBottom(
                canonicalTailLoaded = true,
                clickUserScrollGeneration = 7,
                currentUserScrollGeneration = 8
            )
        )
        assertFalse(
            shouldCompleteThreadBackToBottom(
                canonicalTailLoaded = false,
                clickUserScrollGeneration = 7,
                currentUserScrollGeneration = 7
            )
        )
    }

    @Test
    fun visibleReadTailIgnoresBottomPaddingAndKeepsLastMessage() {
        // Messages occupy list indexes 1..3; index 4 is the bottom scroll padding.
        assertEquals(
            2,
            threadVisibleReadTailMessageIndex(
                visibleListItemIndexes = listOf(1, 2, 3, 4),
                firstMessageListItemIndex = 1,
                messageCount = 3,
            ),
        )
    }
}
