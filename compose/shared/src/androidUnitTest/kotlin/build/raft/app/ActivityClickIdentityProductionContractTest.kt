package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production wiring gate for Activity DOWN-key -> opened-key atomicity. */
class ActivityClickIdentityProductionContractTest {

    @Test
    fun releaseActivityUsesServerDefaultDecisionWithoutLiveDiagnosticOverride() {
        val source = source()

        assertTrue("val stableOrderEnabledAtRouteEntry =" in source)
        assertTrue(
            "remember(activityStore, state.ownerUserId, activitySummaryServerId)" in source,
            "The release decision must be rebound when the Activity owner/server identity changes.",
        )
        assertTrue("sharedStores.serverFeatureFlags.isEnabled(" in source)
        assertFalse(
            "ProcessDiagnosticFeatureFlagOverrides" in source,
            "release-1.6.0 intentionally excludes the live diagnostic override control plane.",
        )
        assertFalse(
            "stableOrderDecisionRevision" in source,
            "A dormant revision parameter would falsely imply live override support in this release port.",
        )
    }

    @Test
    fun activityRowsFreezeOneImmutableItemSnapshotForLazyKeyContentAndCallbacks() {
        val source = source()

        assertTrue("val renderedItems = presentedItems" in source)
        assertTrue("val renderedPresentationGeneration = presentationGeneration" in source)
        assertTrue("val renderedPresentationOwnerIdentity = presentationOwnerIdentity" in source)
        assertTrue("val presentedItemsState =" in source)
        assertTrue("var presentedItems by presentedItemsState" in source)
        assertTrue("val presentationGenerationState =" in source)
        assertTrue("var presentationGeneration by presentationGenerationState" in source)
        assertTrue("items(items = renderedItems, key = { item -> item.key }) { item ->" in source)
        assertFalse(
            "items(presentedItems.size, key = { index -> presentedItems[index].key })" in source,
            "A mutable count/index source can bind a new lazy key to old row content during reorder.",
        )
    }

    @Test
    fun activityPointerCallbackCannotBeRetargetedBeforeTheOldDownIsCancelled() {
        val source = source()
        val gestureBody =
            source.substringAfter("private fun Modifier.activityRowGestures(")
                .substringBefore("internal fun InboxItem.inboxItemOpenGestureKey()")

        assertFalse(
            "rememberUpdatedState(onClick)" in gestureBody,
            "The pointer generation must capture its callback; dynamic replacement can retarget an in-flight DOWN.",
        )
        assertTrue("onTap = { onClick() }" in gestureBody)
        assertTrue("activityItemForCommittedGesture(" in source)
        assertTrue("currentItems = presentedItemsState.value" in source)
        assertTrue("currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value" in source)
        assertTrue("currentPresentationGeneration = presentationGenerationState.value" in source)
        assertTrue("renderedPresentationOwnerIdentity != currentPresentationOwnerIdentity" in source)
        assertTrue("renderedPresentationGeneration != currentPresentationGeneration" in source)
        assertTrue("item.key == renderedItemKey" in source)
        assertTrue("presentationOwnerIdentity = presentationOwnerIdentity" in source)
    }

    private fun source(): String {
        val commonMain =
            requireNotNull(
                listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                    .firstOrNull(File::isDirectory),
            ) { "Could not locate shared commonMain Kotlin sources" }
        val file = File(commonMain, "build/raft/app/SharedInboxPanel.kt")
        assertTrue(file.isFile, "Missing production source ${file.path}")
        return file.readText().replace("\r\n", "\n")
    }
}
