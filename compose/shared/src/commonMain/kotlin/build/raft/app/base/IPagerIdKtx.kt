package build.raft.app.base

import build.raft.app.compat.pager.IPagerId
import build.raft.app.compat.pager.pagerId

/**
 * 老的方式:，需要显式传递 pagerId
 * ```kotlin
 * Utils.bridgeModule(pagerId).reportPageCostTimeForError()
 * ```
 *
 * 新方式：无需显式传递 pagerId
 * ```kotlin
 * bridgeModule.reportPageCostTimeForError()
 * ```
 */
internal val IPagerId.bridgeModule: BridgeModule by pagerId {
    Utils.bridgeModule(it)
}

internal fun IPagerId.setTimeout(delay: Int, callback: () -> Unit): String {
    return build.raft.app.compat.pager.setTimeout(pagerId, delay, callback)
}