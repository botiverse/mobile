package build.raft.app

/**
 * Trace phases for Home tab-switch jank attribution.
 *
 * These share the existing markdown trace infrastructure so Android emits both
 * Perfetto sections and `SlockMarkdownSpike` logcat rows, while non-Android
 * targets keep the same no-op/lightweight actuals.
 */
internal object RaftHomeTraceNames {
    const val TITLEBAR_RENDER = "home.titlebar_render"
    const val LIST_RENDER = "home.list_render"
    const val CHANNEL_ROW_RENDER = "home.channel_row_render"
    const val DIRECT_MESSAGE_ROW_RENDER = "home.dm_row_render"
}
