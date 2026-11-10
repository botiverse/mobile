package build.raft.app

import build.raft.app.data.AdaptiveSideThread
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a conversation focuses its composer on desktop and with a hardware keyboard, as the web
 * does on a hover-capable pointer (ComposerAutofocusPolicy.kt); the reply thread beside a channel
 * takes the focus over the channel's composer.
 */
class ComposerAutofocusPolicyTest {
    @Test
    fun focusesOnDesktopOrWithHardwareKeyboardOnly() {
        // (desktop host, hardware keyboard) -> focus on open
        val cases =
            listOf(
                Triple(true, false, true), // Compose desktop app
                Triple(true, true, true),
                Triple(false, true, true), // tablet / phone with a keyboard attached
                Triple(false, false, false), // touch only: no focus, so no soft keyboard by itself
            )
        cases.forEach { (desktop, keyboard, expected) ->
            assertEquals(
                expected,
                composerAutofocusOnOpen(desktopHost = desktop, hardwareKeyboardConnected = keyboard),
                "desktop=$desktop hardwareKeyboard=$keyboard",
            )
        }
    }

    @Test
    fun channelYieldsToTheThreadPanelBesideIt() {
        val session = sessionWithSideThread()
        val side = session.sideThread!!
        val sidePage = RaftAdaptiveDetailCloseRequest("shell-1", side.identity.key, side.generation)
        val mainPage = RaftAdaptiveDetailCloseRequest("shell-1", channel.identity.key, channel.generation)

        assertTrue(session.composerAutofocusYieldsToSideThread(mainPage), "channel composer must leave focus to the thread panel")
        assertFalse(session.composerAutofocusYieldsToSideThread(sidePage), "thread panel composer takes the focus")
    }

    @Test
    fun channelTakesFocusBackWhenThePanelClosesAndPagesOutsideTheDetailNeverYield() {
        val withPanel = sessionWithSideThread()
        val mainPage = RaftAdaptiveDetailCloseRequest("shell-1", channel.identity.key, channel.generation)

        assertFalse(withPanel.copy(sideThread = null).composerAutofocusYieldsToSideThread(mainPage), "panel closed")
        assertFalse(withPanel.composerAutofocusYieldsToSideThread(null), "a pushed page (phone) is not in the wide detail")
        // A side thread left over from another selection is not shown, so nothing yields to it.
        val otherSelection = channel.copy(generation = 2L)
        val stale = withPanel.copy(workspace = withPanel.workspace!!.copy(selection = otherSelection))
        assertFalse(
            stale.composerAutofocusYieldsToSideThread(
                RaftAdaptiveDetailCloseRequest("shell-1", otherSelection.identity.key, otherSelection.generation)
            ),
            "side thread of an older selection",
        )
    }

    @Test
    fun onlyASideThreadClosingAsksForARestore() {
        val focus = ComposerFocusOnOpen(enabled = true, shown = true, yieldsToSideThread = false)
        // A keyboard reconnected, the page shown again: no restore, and no new open either.
        focus.update(enabled = false, shown = true, yieldsToSideThread = false)
        focus.update(enabled = true, shown = false, yieldsToSideThread = false)
        focus.update(enabled = true, shown = true, yieldsToSideThread = false)
        assertEquals(0, focus.restoreGeneration)

        focus.update(enabled = false, shown = true, yieldsToSideThread = true)
        assertEquals(0, focus.restoreGeneration, "the thread opening asks for nothing")
        focus.update(enabled = true, shown = true, yieldsToSideThread = false)
        assertEquals(1, focus.restoreGeneration, "the thread closing asks for one restore")
        focus.update(enabled = true, shown = true, yieldsToSideThread = false)
        assertEquals(1, focus.restoreGeneration)
    }

    private val channel =
        RaftAdaptiveDetailSelection(
            identity = RaftAdaptiveDetailIdentity(serverId = "srv-1", threadId = "ch-1", surfaceKind = "channel", channelId = "ch-1"),
            generation = 1L,
        )

    private fun sessionWithSideThread(): SharedAdaptiveWorkspaceSession =
        SharedAdaptiveWorkspaceSession(
            workspace =
                RaftAdaptiveWorkspaceState(
                    shellInstanceId = "shell-1",
                    serverId = "srv-1",
                    selection = channel,
                    pane =
                        RaftAdaptivePaneState(
                            widthPx = 2400,
                            heightPx = 1600,
                            density = 1f,
                            posture = RaftAdaptivePanePosture.Flat,
                            layout = RaftAdaptivePaneLayout.DualPane,
                        ),
                ),
            detailPaneWidthDp = 1600f,
            sideThread =
                AdaptiveSideThread(
                    owner = channel,
                    identity =
                        RaftAdaptiveDetailIdentity(
                            serverId = "srv-1",
                            threadId = "th-1",
                            surfaceKind = "replyThread",
                            parentChannelId = "ch-1",
                        ),
                    payload = WireThreadLaunchPayload(threadId = "th-1"),
                    generation = 1L,
                ),
        )
}
