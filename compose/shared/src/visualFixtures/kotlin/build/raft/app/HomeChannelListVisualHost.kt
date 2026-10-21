package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.Page

/**
 * The whole phone Home root (`screens.home.channel-list` / `.dark`): the production
 * [RaftStructuralHomeShell] with its top bar (workspace pill + notification bell), the Saved
 * entry row, the Pinned, Joint Channels, Channels and Direct Messages sections and the phone
 * tab bar, from typed fixture state (no session, no auth).
 *
 * The content is [HomeChannelListVisualContent] (src/visualFixtureData), shared with the JVM
 * screenshot test. Every row comes from [VisualFixtureData.HomeChannelList], generated from the
 * `homeChannelList` block of slock's `packages/visual-testing/shared/fixtureData.json`, the
 * one source the React case seeds from too (`slock-visual generate-fixture-data`). This file
 * only maps those rows onto the Home models, including `mention` (the @ sticker) and
 * `followedThreadsWithNewReplies` (the `[thread] N` segment), which production Home does not
 * load from the server yet.
 *
 * No row is selected: a phone Home root never shows a selected row (opening a conversation
 * pushes it over Home, and the web hides the tab bar on those routes). Selected rows are
 * covered by `components.home.sidebar.row-states`.
 *
 * `pageData.appearance` = `dark` | `light` (default light) pins the app theme for the capture
 * and restores the previous appearance when the page leaves, so later cases in the same
 * instrumentation process keep their own theme. It is not persisted.
 */
@Page("compose_home_channel_list_visual", supportInLocal = true)
internal class ComposeHomeChannelListVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val appearance =
            if (pageData.params.optString("appearance") == "dark") RaftAppAppearance.Dark else RaftAppAppearance.Light
        setContent {
            HomeChannelListVisualAppearance(appearance) { HomeChannelListVisualContent() }
        }
    }
}

/**
 * Pins the process-wide app theme ([RaftAppThemeState], which every `RaftColor` token and the
 * page root's `RaftTheme` read) while the fixture is on screen. Applied after the first
 * composition, so the first frame may still be light; the capture settles well past it.
 */
@Composable
private fun HomeChannelListVisualAppearance(appearance: RaftAppAppearance, content: @Composable () -> Unit) {
    DisposableEffect(appearance) {
        val previous = RaftAppThemeState.appearance
        RaftAppThemeState.apply(appearance)
        onDispose { RaftAppThemeState.apply(previous) }
    }
    content()
}
