package build.raft.app

/**
 * #192 trace scope extension: phase names for Inbox panel rendering.
 *
 * The Inbox (Activity / @ mentions / unread filter) is the main scrollable
 * panel on Home — its render cost during scroll is a potential jank source.
 * Marker names are literal constants — no allocations in the hot path.
 */
internal object RaftInboxTraceNames {
    const val PANEL_RENDER = "inbox.panel_render"
}