package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.compat.pager.Page

@Page("compose_thread_forward_bundle_visual", supportInLocal = true)
internal class ComposeThreadForwardBundleVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadForwardBundleVisualScreen() }
    }
}

@Composable
internal fun ThreadForwardBundleVisualScreen() {
    // Pins the forward-bundle metadata with a fixture (task #427). Space Mono is
    // reserved for the per-row timestamp only, matching the react
    // ForwardedBundleCard convention. Five messages so the inline card's
    // three-message preview cap and the "View all N" row both render (task #64);
    // bodies keep markdown so rendered ink stays proven (task #63).
    val bundle = remember { threadForwardBundleVisualFixtureBundle() }
    ThreadForwardBundleCard(
        bundle = bundle,
        messageBodyTextStyle = slockMessageBodyTextStyle("md"),
        onOpenAll = {},
    )
}
