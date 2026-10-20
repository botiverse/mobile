package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * Single canonical byte-size formatter used at UI/presentation assembly boundaries.
 * Wire/domain mappers preserve raw `sizeBytes` and must not call this formatter.
 *
 * `0` (or negative) renders as the empty string — every call site currently
 * relies on that behaviour to skip "0 B" labels for missing sizes.
 */
@RaftCoreInternalApi
fun formatAttachmentSizeLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIdx = 0
    while (value >= 1024.0 && unitIdx < units.lastIndex) {
        value /= 1024.0
        unitIdx++
    }
    return if (unitIdx == 0) {
        "$bytes B"
    } else {
        "${(value * 10).toInt() / 10.0} ${units[unitIdx]}"
    }
}
