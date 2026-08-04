package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the production links around the behavioral LayoutNode tests. The runtime failure requires a
 * very specific combination: a lazy-row composition is recreated for the same message, its capture
 * node moves to a new LiveRef, and Kuikly retains an equal pointer-input element whose old handler
 * still closes over the invalidated ref. The JVM lifecycle tests exercise the node and element
 * contracts; this test prevents production from silently bypassing those contracts.
 */
class ThreadLongPressAnchorProductionContractTest {
    @Test
    fun threadRowsUseRouteScopedGestureOwnersAndOwnerKeyedPointerHandlers() {
        val source = threadPageSource()
        val ownerHelper =
            source
                .substringAfter("internal fun rememberThreadMessageLongPressGestureOwner(")
                .substringBefore("@Composable\ninternal fun Modifier.threadMessageLongPressActions")
        val longPressModifier =
            source
                .substringAfter("internal fun Modifier.threadMessageLongPressActions(")
                .substringBefore("internal data class ThreadMessageTapMovementDecision")
        val pointerHelper =
            source
                .substringAfter("internal fun Modifier.threadMessagePointerInput(")
                .substringBefore("@Composable\ninternal fun Modifier.threadMessageLongPressActions")

        assertTrue(
            source.contains("LocalThreadMessageLongPressGestureOwnerStore provides") &&
                source.contains("threadMessageLongPressGestureOwnerStore"),
            "the Thread route must provide one gesture-owner store across row recreation"
        )
        assertTrue(
            source.contains("message.messageListItemKey(duplicateMessageRandomIds)"),
            "each lazy row must bind the store to its actual list-item identity"
        )
        assertTrue(
            ownerHelper.contains("effectiveOwnerStore.ownerFor(ownerKey)"),
            "row recreation must reacquire the same gesture owner instead of a new callback holder"
        )
        assertTrue(
            ownerHelper.contains("effectiveOwnerStore.retain(ownerKey, gestureOwner)") &&
                ownerHelper.contains("effectiveOwnerStore.release(ownerKey, gestureOwner)"),
            "route retention must protect composed rows and evict only inactive gesture owners"
        )
        assertTrue(
            longPressModifier.contains("gestureOwner.updateCallbacks(") &&
                longPressModifier.contains("gestureOwner.showPressFeedback(compositionLease, downPosition)") &&
                longPressModifier.contains("gestureOwner.hidePressFeedback(compositionLease)"),
            "the reused handler must read callbacks and press state through its stable owner"
        )
        assertTrue(
            longPressModifier.contains("gestureOwner.captureActiveLease(") &&
                longPressModifier.contains("gestureOwner.callbacksSnapshot(compositionLease)") &&
                longPressModifier.contains("withFrameNanos { }") &&
                longPressModifier.indexOf("withFrameNanos { }") <
                longPressModifier.lastIndexOf("gestureOwner.callbacksSnapshot(compositionLease)"),
            "deferred tap and gesture writes must revalidate the active composition lease"
        )
        assertFalse(
            longPressModifier.contains("rememberUpdatedState("),
            "a retained pointer block must not close over composition-local updated-state holders"
        )
        assertFalse(
            longPressModifier.contains("val tapMovementGate ="),
            "the retained pointer blocks must reach movement state only through the stable owner"
        )
        assertTrue(
            longPressModifier.contains("gestureOwner.tapMovementGate.begin(") &&
                longPressModifier.contains("gestureOwner.tapMovementGate.decision("),
            "movement state must remain owned by the same semantic gesture authority"
        )
        assertTrue(
            source.contains("val pressed = longPressGestureOwner.pressed") &&
                source.contains("val pressRippleOrigin = longPressGestureOwner.pressRippleOrigin"),
            "replacement rows must draw the route-owned in-flight press state"
        )
        assertTrue(
            source.contains("yield()") &&
                source.contains("currentEntry.activeOwnerCount == 0") &&
                source.contains("currentEntry.lifecycleRevision == expectedLifecycleRevision") &&
                source.contains("owner.finalizeInactiveCompositionAuthority()"),
            "zero-count release must finalize after the apply stack unless a same-row retain reclaims it"
        )
        assertEquals(
            2,
            Regex(Regex.escape("threadMessagePointerInput(")).findAll(longPressModifier).count(),
            "movement tracking and action detection must share the same pointer owner generation"
        )
        assertTrue(
            pointerHelper.contains(
                "pointerInput(role, messageId, pointerSessionKey, gestureOwner, block = block)"
            ),
            "a genuine gesture-owner replacement must restart both pointer handlers"
        )
    }

    private fun threadPageSource(): String =
        listOf(
            File("src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
            File("shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
            File("compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
        ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("ThreadPage.kt source not found")
}
