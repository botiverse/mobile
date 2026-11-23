package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One close for every head (artin, mobile #244: "close buttons are in different places; wide screens must follow one
 * design"). The web puts a panel's × last, at the outer end of its head, after the panel's own actions, ⋯ and
 * Expand (raft-design panel.md "Close placement"; slock PanelHeader, ThreadPanelHeader, ChannelSideCard,
 * ArtifactViewer, ProfilePanel), a dialog's at its top right, and Back (never ×) where a panel covers the page. In
 * the app every head draws its × with the design system's `RaftCloseButton`, which carries that rule; a head that
 * drew an × of its own (an icon button holding the × glyph) is how the profile, the details and the Artifact column
 * each ended up with theirs in a different place.
 *
 * So the × glyph (`RaftGlyph.X`, `RaftLucideIconName.X`) appears in the app's and the design system's UI code only
 * inside `RaftCloseButton` and in the controls listed in [NotAHeadsClose]: an × that is not a head's close (a field's
 * Clear, a chip's remove, a banner's dismiss, a toolbar's Clear all, a menu row, a text button's icon, a viewer's
 * toolbar). Each file there is listed with how many lines use the glyph, so a new × in one of them fails too.
 */
class RaftCloseButtonRulesTest {
    @Test
    fun aHeadsCloseIsAlwaysRaftCloseButton() {
        val found = sortedMapOf<String, MutableList<String>>()
        for ((label, rootPath) in SourceRoots) {
            val root = repositoryFile(rootPath)
            if (label in RequiredRoots) assertTrue(root.isDirectory, "sources not found at ${root.path}")
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val key = "$label/${file.relativeTo(root).invariantSeparatorsPath}"
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        // KDoc and comments may name the glyph.
                        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
                        if (CloseGlyph.containsMatchIn(line.substringBefore("//"))) {
                            found.getOrPut(key) { mutableListOf() } += "$key:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        val problems = mutableListOf<String>()
        found.forEach { (key, lines) ->
            if (key == CloseButtonFile) return@forEach
            val allowed = NotAHeadsClose[key]
            when {
                allowed == null -> problems += lines.joinToString("\n") { "$it\n    an × outside RaftCloseButton" }
                lines.size != allowed.lines ->
                    problems += lines.joinToString("\n") + "\n    $key is listed with ${allowed.lines} × (${allowed.why}), it has ${lines.size}"
            }
        }
        (NotAHeadsClose.keys - found.keys).forEach { key -> problems += "$key is listed but draws no × any more: take it off the list" }
        if (problems.isNotEmpty()) {
            fail(
                "A head's × (a panel, a column, a page, a dialog, a sheet, a drawer) is RaftCloseButton " +
                    "(compose/designsystem RaftBackButton.kt): the head's last control, at its end (a phone " +
                    "sheet's: its first), named Close. An × that is not a head's close (a field's Clear, a chip's " +
                    "remove) is listed in RaftCloseButtonRulesTest.NotAHeadsClose with why.\n" +
                    problems.joinToString("\n"),
            )
        }
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() }
            ?: File(start, relativePath)
    }

    private class Allowed(val lines: Int, val why: String)

    private companion object {
        /** The UI code, by a short label: the app's (`app`) and the design system's (`ds`) main source sets. */
        private val SourceRoots =
            listOf(
                "app" to "compose/shared/src/commonMain/kotlin/build/raft/app",
                "app.android" to "compose/shared/src/androidMain/kotlin/build/raft/app",
                "app.desktop" to "compose/shared/src/desktopMain/kotlin/build/raft/app",
                "app.ios" to "compose/shared/src/iosMain/kotlin/build/raft/app",
                "app.jvm" to "compose/shared/src/jvmCommonMain/kotlin/build/raft/app",
                "app.native" to "compose/shared/src/nativeMain/kotlin/build/raft/app",
                "app.js" to "compose/shared/src/jsMain/kotlin/build/raft/app",
                "ds" to "compose/designsystem/src/commonMain/kotlin/build/raft/designsystem",
                "ds.android" to "compose/designsystem/src/androidMain/kotlin/build/raft/designsystem",
                "ds.desktop" to "compose/designsystem/src/desktopMain/kotlin/build/raft/designsystem",
                "ds.ios" to "compose/designsystem/src/iosMain/kotlin/build/raft/designsystem",
            )

        private val RequiredRoots = setOf("app", "ds")

        private val CloseGlyph = Regex("""\bRaftGlyph\.X\b|\bRaftLucideIconName\.X\b""")

        /** The component itself: RaftCloseButton lives beside RaftBackButton. */
        private const val CloseButtonFile = "ds/components/RaftBackButton.kt"

        private const val Clear = "clears what was typed or filtered"
        private const val Remove = "removes one item or chip"
        private const val Dismiss = "dismisses a notice in the content"
        private const val TextButton = "the icon of a text button (Close, Cancel, Remove)"
        private const val MenuRow = "the icon of a menu row"
        private const val Viewer = "a full-screen viewer's toolbar (raft-ui LightboxClose)"

        /** An × that is not a head's close, by file, with how many lines draw one. */
        private val NotAHeadsClose: Map<String, Allowed> =
            mapOf(
                // The glyph's own drawing.
                "app/RaftLucideIconPaths.kt" to Allowed(1, "the × glyph's path"),
                // Design system controls that are not heads.
                "ds/components/RaftBanner.kt" to Allowed(1, "BannerClose, $Dismiss"),
                "ds/components/RaftCombobox.kt" to Allowed(1, "a chip's remove, $Remove"),
                "ds/components/RaftComposerAttachment.kt" to Allowed(1, "an attachment's remove, $Remove"),
                "ds/components/RaftMessageListJump.kt" to Allowed(1, "the jump pill's dismiss, $Dismiss"),
                "ds/components/RaftPaneGridHead.kt" to Allowed(1, "Close as a row of ⋯ in a head too narrow for it, $MenuRow"),
                "ds/components/RaftSelectionToolbar.kt" to Allowed(1, "SelectionToolbarClose ends a selection, not a head"),
                "ds/components/RaftTextField.kt" to Allowed(1, "a field's Clear, $Clear"),
                // App controls that are not heads.
                "app/InAppBrowserPage.kt" to Allowed(1, TextButton),
                "app/agents/AgentMigrationSection.kt" to Allowed(1, "a failed move's dismiss, $Dismiss"),
                "app/agents/SharedCreateAgentDialog.kt" to Allowed(1, "an environment variable row's remove, $Remove"),
                "app/artifact/ArtifactsHomeScreen.kt" to Allowed(1, "Clear filters on a phone, $Clear"),
                "app/artifact/RaftArtifactViewerPage.kt" to Allowed(1, TextButton),
                "app/assistant/ThreadAssistantContext.kt" to Allowed(1, "a context chip's remove, $Remove"),
                "app/channel/ChannelAddMemberPanel.kt" to Allowed(1, "a picked member's remove, $Remove"),
                "app/computers/ComputerRuntimeUsageChip.kt" to Allowed(1, "a usage card's ×, the web's CloseButton with a 16px × in the card"),
                "app/computers/RaftComputerAgentsBulk.kt" to Allowed(2, "$TextButton; a failed row's status icon"),
                "app/computers/RaftComputersRackToolbar.kt" to Allowed(1, "Clear all, $Clear"),
                "app/files/ExternalShareHost.kt" to Allowed(1, TextButton),
                "app/files/RaftAttachmentCommentsPanel.kt" to Allowed(1, "a comment chip's remove, $Remove"),
                "app/files/RaftDownloadCenterSection.kt" to Allowed(1, "a download's cancel in its row"),
                "app/files/RaftFilePreviewDownloadPanel.kt" to Allowed(1, TextButton),
                "app/files/RaftImageLightbox.kt" to Allowed(1, Viewer),
                "app/home/RaftHomeComponents.kt" to Allowed(2, "Close Chat, $MenuRow"),
                "app/markdown/RaftMermaidCard.kt" to Allowed(1, Viewer),
                "app/server/ExternalInvitesUi.kt" to Allowed(1, "an invite's withdraw in its row"),
                "app/settings/SettingsHomeSearchPanel.kt" to Allowed(1, "a recent search's remove, $Remove"),
                "app/settings/SettingsStandaloneWebViewHost.kt" to Allowed(1, TextButton),
                "app/shell/RaftForegroundBannerHost.kt" to Allowed(1, "an in-app banner's dismiss, $Dismiss"),
                "app/tasks/SharedTasksPage.kt" to Allowed(1, "Clear all, $Clear"),
                "app/tasks/SharedTasksQueryToolbar.kt" to Allowed(1, "Clear all, $Clear"),
                "app/tasks/TaskCreateFromMessageDialog.kt" to Allowed(1, "a title row's remove, $Remove"),
                "app/thread/RaftEmojiPicker.kt" to Allowed(1, TextButton),
                "app/thread/ThreadAgentFollowers.kt" to Allowed(1, "a follower's remove, $Remove"),
                "app/thread/ThreadFind.kt" to Allowed(1, "the find bar's close, a bar over the messages (web ThreadSearchBar)"),
                "app/thread/ThreadForwarding.kt" to Allowed(1, "a picked destination's remove, $Remove"),
                "app/thread/ThreadMembersPage.kt" to Allowed(1, TextButton),
                "app/thread/ThreadMessageMenuModel.kt" to Allowed(1, "Cancel, $MenuRow"),
                "app/thread/ThreadPageHeader.kt" to Allowed(1, "Close as a row of ⋯ in a head too narrow for it, $MenuRow"),
                "app/thread/ThreadSettingsDrawer.kt" to Allowed(1, TextButton),
                "app.desktop/RaftDesktopDownloadBar.kt" to Allowed(1, "the download bar's dismiss, $Dismiss"),
            )
    }
}
