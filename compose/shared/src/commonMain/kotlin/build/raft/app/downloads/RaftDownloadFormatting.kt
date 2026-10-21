package build.raft.app.downloads

/** Byte count for download UI: `512 B`, `12.4 KB`, `3.0 MB`, `1.2 GB` (binary units, one decimal). */
fun raftDownloadSizeLabel(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var unitIndex = 0
    var scaled = value.toDouble() / 1024.0
    while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
        scaled /= 1024.0
        unitIndex += 1
    }
    val tenths = (scaled * 10.0 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unitIndex]}"
}

/** `850.0 KB/s`, or null when the speed is unknown. */
fun raftDownloadSpeedLabel(bytesPerSecond: Long?): String? =
    bytesPerSecond?.takeIf { it > 0L }?.let { "${raftDownloadSizeLabel(it)}/s" }
