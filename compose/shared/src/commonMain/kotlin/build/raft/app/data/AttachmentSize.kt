package build.raft.app.data

/**
 * Single canonical byte-size formatter shared by every parser that converts a
 * `sizeBytes: Long` wire field into the `Attachment.sizeLabel` / file meta
 * label shown in the UI. Keep this internal: it is an implementation detail
 * of the shared parsers, not part of the public domain model.
 *
 * `0` (or negative) renders as the empty string — every call site currently
 * relies on that behaviour to skip "0 B" labels for missing sizes.
 */
internal fun formatAttachmentSizeLabel(bytes: Long): String {
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
