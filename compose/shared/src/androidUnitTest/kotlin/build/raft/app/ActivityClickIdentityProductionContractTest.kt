package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production wiring gate for Activity DOWN-key -> opened-key atomicity on release-1.6.0. */
class ActivityClickIdentityProductionContractTest {

    @Test
    fun activityRowsFreezeOneImmutableSnapshotForLazyKeyContentAndCallbacks() {
        val source = source()

        assertTrue("val renderedItems = snapshot.items" in source)
        assertTrue("val renderedOrderIdentity = activityPresentationOrderIdentity(renderedItems)" in source)
        assertTrue("val renderedPresentationOwnerIdentity = presentationOwnerIdentity" in source)
        assertTrue("val renderedPresentationEpoch = presentationEpoch" in source)
        assertTrue("val currentItemsState = rememberUpdatedState(snapshot.items)" in source)
        assertTrue("items(items = renderedItems, key = { item -> item.key }) { item ->" in source)
        assertFalse(
            "items(snapshot.items.size, key = { index -> snapshot.items[index].key })" in source,
            "A mutable count/index source can bind a new lazy key to old row content during reorder.",
        )
    }

    @Test
    fun activityPointerCallbackCannotBeRetargetedBeforeOldDownIsCancelled() {
        val source = source()
        val gestureBody =
            source.substringAfter("private fun Modifier.activityRowGestures(")
                .substringBefore("internal fun InboxItem.inboxItemOpenGestureKey()")

        assertFalse("rememberUpdatedState(onClick)" in gestureBody)
        assertTrue("onTap = { onClick() }" in gestureBody)
        assertTrue("activityItemForCommittedGesture(" in source)
        assertTrue("currentItems = currentItemsState.value" in source)
        assertTrue("currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value" in source)
        assertTrue("currentPresentationEpoch = currentPresentationEpochState.value" in source)
        assertTrue("renderedPresentationOwnerIdentity != currentPresentationOwnerIdentity" in source)
        assertTrue("renderedPresentationEpoch != currentPresentationEpoch" in source)
        assertTrue("renderedOrderIdentity != activityPresentationOrderIdentity(currentItems)" in source)
        assertTrue("item.key == renderedItemKey" in source)
        assertTrue("presentationOwnerIdentity = renderedPresentationOwnerIdentity" in source)
    }

    @Test
    fun activityStoreOwnsAMonotonicPresentationEpoch() {
        val source = storeSource()

        assertTrue("val presentationEpoch: Long = 0L" in source)
        assertTrue(".withActivityPresentationEpoch(previousState)" in source)
        assertTrue("previous.presentationEpoch + 1L" in source)
        assertTrue("ownerChanged || filterChanged || orderChanged" in source)
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

    private fun storeSource(): String {
        val commonMain =
            requireNotNull(
                listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                    .firstOrNull(File::isDirectory),
            ) { "Could not locate shared commonMain Kotlin sources" }
        val file = File(commonMain, "build/raft/app/data/SharedActivityStore.kt")
        assertTrue(file.isFile, "Missing production source ${file.path}")
        return file.readText().replace("\r\n", "\n")
    }
}
