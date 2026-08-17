package build.raft.app

import kotlin.test.Test
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
    fun unreadJumpSettlementYieldsToANewerUserGesture() {
        assertTrue(
            shouldCompleteThreadUnreadJump(
                clickUserScrollGeneration = 4,
                currentUserScrollGeneration = 4,
            )
        )
        assertFalse(
            shouldCompleteThreadUnreadJump(
                clickUserScrollGeneration = 4,
                currentUserScrollGeneration = 5,
            )
        )
    }

    @Test
    fun unreadJumpButtonFailsClosedUntilTheLocalPreferenceIsEnabled() {
        assertFalse(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = false,
                routeActive = true,
                hostForeground = true,
                unreadCount = 3,
                targetMessageId = "message-3",
                inlineSearchVisible = false,
            )
        )
        assertTrue(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = true,
                routeActive = true,
                hostForeground = true,
                unreadCount = 3,
                targetMessageId = "message-3",
                inlineSearchVisible = false,
            )
        )
    }

    @Test
    fun unreadJumpButtonRetainsExistingVisibilityGuardsWhenEnabled() {
        assertFalse(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = true,
                routeActive = false,
                hostForeground = true,
                unreadCount = 1,
                targetMessageId = "message-1",
                inlineSearchVisible = false,
            )
        )
        assertFalse(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = true,
                routeActive = true,
                hostForeground = true,
                unreadCount = 1,
                targetMessageId = "message-1",
                inlineSearchVisible = true,
            )
        )
    }
}
