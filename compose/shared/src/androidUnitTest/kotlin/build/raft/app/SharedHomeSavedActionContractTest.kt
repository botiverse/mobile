package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeSavedActionContractTest {
    @Test
    fun savedRowUsesOneCoordinateOwnerForOpenUnsaveAndContextMenu() {
        val pageSource = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val resultCard =
            pageSource.substringAfter("private fun SharedHomeSavedResultCard(")
                .substringBefore("private fun SharedHomeSavedUnsaveButton(")
        val unsaveButton =
            pageSource.substringAfter("private fun SharedHomeSavedUnsaveButton(")
                .substringBefore("private fun SharedHomeSavedSenderMeta(")

        assertTrue(".pointerInput(openGestureKey)" in resultCard)
        assertTrue("sharedHomeSavedGestureOwner(" in resultCard)
        assertTrue("dispatchSavedGesture(" in resultCard)
        assertTrue("source = \"tap\"" in resultCard)
        assertTrue("owner=${'$'}{owner.name.lowercase()} action=${'$'}{owner.name.lowercase()}" in resultCard)
        assertTrue("source=long_press" in resultCard)
        assertTrue("owner=context_menu action=context_menu" in resultCard)
        assertTrue("owner=ignored action=none" in resultCard)
        assertTrue("reason=unsave_zone_long_press" in resultCard)
        assertTrue("generation=${'$'}generation" in resultCard)
        assertTrue("messageId=${'$'}{savedMessageId.take(80)}" in resultCard)
        assertFalse(".pointerInput(" in unsaveButton)
        assertFalse(".clickable(" in unsaveButton)
        assertTrue(".slockButtonAccessibility(enabled = true) { currentOnClick() }" in unsaveButton)
    }

    @Test
    fun savedMutationIdentityUsesCanonicalSavedKeyBeforeNavigationAnchor() {
        val source = commonMainSource("build/raft/app/data/SharedHomeSearchSavedStore.kt")
        val resolver =
            source.substringAfter("fun InboxItem.savedMessageIdOrNull(): MessageId? =")
                .substringBefore("enum class SearchTimeRange")

        assertTrue("it.startsWith(SavedInboxItemKeyPrefix)" in resolver)
        assertTrue("?.removePrefix(SavedInboxItemKeyPrefix)" in resolver)
        assertTrue("?: resolveInboxAnchorMessageId()" in resolver)
        assertTrue(
            resolver.indexOf("removePrefix(SavedInboxItemKeyPrefix)") <
                resolver.indexOf("resolveInboxAnchorMessageId()")
        )
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }
}
