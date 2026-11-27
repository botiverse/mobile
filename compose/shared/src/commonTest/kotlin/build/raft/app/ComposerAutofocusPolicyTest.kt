package build.raft.app

import build.raft.app.data.AdaptiveSideThread
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.thread.key
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
    fun anOpenOnAMessageSkipsTheFocusOnPhonesAndTabletsOnly() {
        // A message link, an Activity row, a search result: the soft keyboard would push the
        // message off the screen. Desktop has none and keeps the web's focus on open.
        assertTrue(composerAutofocusSkipsMessageOpen(desktopHost = false, opensOnMessage = true))
        assertFalse(composerAutofocusSkipsMessageOpen(desktopHost = true, opensOnMessage = true))
        assertFalse(composerAutofocusSkipsMessageOpen(desktopHost = false, opensOnMessage = false))
        assertFalse(composerAutofocusSkipsMessageOpen(desktopHost = true, opensOnMessage = false))
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
