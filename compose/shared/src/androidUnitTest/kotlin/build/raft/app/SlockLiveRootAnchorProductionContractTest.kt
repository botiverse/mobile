package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockLiveRootAnchorProductionContractTest {
    @Test
    fun popupCallsitesUseLiveCoordinatesInsteadOfFrozenRootOffsets() {
        val migratedFiles =
            listOf(
                "build/raft/app/BasicWidget.kt",
                "build/raft/app/SlockHomeComponents.kt",
                "build/raft/app/SlockSectionHeader.kt",
                "build/raft/app/SharedInboxPanel.kt",
                "build/raft/app/SharedTasksPage.kt",
                "build/raft/app/ThreadPage.kt",
                "build/raft/app/MembersPage.kt",
                "build/raft/app/SettingsHomePage.kt",
                "build/raft/app/SlockFilePreview.kt",
                "build/raft/app/components/SlockBrutalComponents.kt",
                "build/raft/app/components/SlockRaftUiMenuPrimitives.kt",
                "build/raft/app/components/SlockBrutalSelect.kt"
            )

        migratedFiles.forEach { path ->
            val source = source(path)
            assertContains(
                charSequence = source,
                other = "SlockLiveRootAnchorRef",
                message = "Missing live anchor in $path"
            )
            assertFalse("SlockMutableOffsetRef" in source, "Frozen root-offset holder survived in $path")
        }
    }

    @Test
    fun remainingPositionInRootSnapshotsAreNonPopoverMeasurements() {
        val allowedOccurrences =
            mapOf(
                "build/raft/app/ReleaseNotesPage.kt" to
                    listOf("positionY = coordinates.positionInRoot().y"),
                "build/raft/app/SettingsHomePage.kt" to
                    listOf("coordinates.positionInRoot().y + coordinates.size.height"),
                "build/raft/app/SlockFloatingOverlay.kt" to
                    listOf("origin = coordinates.positionInRoot()"),
                "build/raft/app/ThreadPage.kt" to
                    listOf(
                        "shareCaptureViewportBottomPx = coordinates.positionInRoot().y",
                        "val pos = coordinates.positionInRoot()"
                    )
            )
        val filesUsingPositionInRoot =
            commonMain()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "positionInRoot()" in it.readText() }
                .map { it.relativeTo(commonMain()).invariantSeparatorsPath }
                .toSet()

        assertEquals(
            allowedOccurrences.keys,
            filesUsingPositionInRoot,
            "A popup trigger must resolve through SlockLiveRootAnchorRef at gesture time"
        )
        allowedOccurrences.forEach { (path, needles) ->
            val source = source(path)
            assertEquals(
                needles.size,
                source.count("positionInRoot()"),
                "Unexpected root-position snapshot added to $path"
            )
            needles.forEach { needle -> assertContains(source, needle) }
        }
    }

    @Test
    fun helperChecksAttachmentAndDelegatesTheLocalPointToLocalToRoot() {
        val helper = source("build/raft/app/SlockLiveRootAnchorRef.kt")

        assertContains(helper, "takeIf { it.isAttached }")
        assertTrue(
            helper.count("current.localToRoot(") == 2,
            "Both direct and size-derived anchors must use the live coordinate transform"
        )
    }

    private fun String.count(needle: String): Int = split(needle).size - 1

    private fun source(path: String): String {
        val file = File(commonMain(), path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }

    private fun commonMain(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")
}
