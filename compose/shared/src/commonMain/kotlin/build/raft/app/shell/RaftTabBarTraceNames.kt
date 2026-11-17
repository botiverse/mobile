package build.raft.app

/**
 * #192 trace scope extension: phase names for top / bottom tab bar rendering.
 *
 * Tab switches were a known jank source (#191 retained tabs work). These
 * markers measure the per-tab render cost on the UI thread so we can confirm
 * whether tab-bar render itself is the cost driver or something downstream
 * (route transition / scene enter).
 */
internal object RaftTabBarTraceNames {
    const val TOP_TAB_RENDER = "tabbar.top_render"
    const val BOTTOM_TAB_RENDER = "tabbar.bottom_render"
}