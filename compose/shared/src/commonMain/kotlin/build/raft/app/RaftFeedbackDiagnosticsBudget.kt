package build.raft.app

/** One persistent diagnostics log shard (`launch-<id>/raft-diagnostics.jsonl[.N]`). */
data class RaftFeedbackLogFile(
    val path: String,
    val launchId: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/** A shard picked for a feedback report: all of it, or its newest [includedBytes] (a tail). */
data class RaftFeedbackLogPick(
    val file: RaftFeedbackLogFile,
    val includedBytes: Long,
) {
    val truncated: Boolean
        get() = includedBytes < file.sizeBytes
}

/**
 * Which diagnostics log shards a feedback report attaches. Before: every shard of the last five
 * launches (fb-c04c1199: 31 shards, 29 MB raw / 3 MB zipped, slow to send on 4G). Now at most
 * [DefaultBudgetBytes] raw, newest first:
 *
 * 1. the newest launch (by its `launch-<epochMs>` id), newest shard (rotation order) first, up to the budget less a reserve for the launch
 *    before it (a quarter of the budget, or that launch's newest shard if smaller), so a report
 *    sent after a crash or kill still carries the end of the launch that died;
 * 2. then the newest shard of each earlier launch, newest launch first;
 * 3. then any other shard by recency while budget remains.
 *
 * A shard that does not fit whole contributes its tail when at least [MinTailBytes] remain
 * (the reader skips to the first full line); selection stops at the first shard that does not
 * fit. The newest shard is always included, as a tail if it alone exceeds the budget.
 */
object RaftFeedbackDiagnosticsBudget {
    const val DefaultBudgetBytes: Long = 1024L * 1024L
    const val MinTailBytes: Long = 16L * 1024L
    private const val LaunchPrefix = "launch-"

    /** `raft-diagnostics.jsonl` 0, `raft-diagnostics.jsonl.3` 3; anything else after them. */
    internal fun rotationIndex(path: String): Int {
        val suffix = path.substringAfterLast("jsonl", missingDelimiterValue = "?")
        return when {
            suffix.isEmpty() -> 0
            suffix.startsWith(".") -> suffix.drop(1).toIntOrNull() ?: Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
    }

    fun select(files: List<RaftFeedbackLogFile>, budgetBytes: Long = DefaultBudgetBytes): List<RaftFeedbackLogPick> {
        val candidates = files.filter { it.sizeBytes > 0L }
        if (candidates.isEmpty() || budgetBytes <= 0L) return emptyList()
        // Rotation order, not mtime: the active file is newest, then .1, .2, ... (mtimes can be
        // reset by restores and clock changes).
        val newestFirst =
            compareBy<RaftFeedbackLogFile> { rotationIndex(it.path) }
                .thenByDescending { it.modifiedMillis }
                .thenBy { it.path }
        // `launch-<epochMs>-<id>` sorts by start time; a flat (pre-session) group goes last.
        val launches =
            candidates
                .groupBy { it.launchId }
                .values
                .map { it.sortedWith(newestFirst) }
                .sortedWith(
                    compareByDescending<List<RaftFeedbackLogFile>> { it.first().launchId.startsWith(LaunchPrefix) }
                        .thenByDescending { it.first().launchId }
                        .thenByDescending { group -> group.maxOf { it.modifiedMillis } }
                )
        val newestLaunch = launches.first()
        val earlierLaunches = launches.drop(1)
        val reserve = earlierLaunches.firstOrNull()?.first()?.sizeBytes?.coerceAtMost(budgetBytes / 4) ?: 0L

        val picks = ArrayList<RaftFeedbackLogPick>()
        var used = 0L
        var stopped = false

        /** Adds [file] whole, or its tail; false once the budget is spent (stop adding). */
        fun take(file: RaftFeedbackLogFile, limit: Long): Boolean {
            val remaining = limit - used
            if (file.sizeBytes <= remaining) {
                picks += RaftFeedbackLogPick(file, file.sizeBytes)
                used += file.sizeBytes
                return true
            }
            if (remaining >= MinTailBytes || picks.isEmpty()) {
                val tail = remaining.coerceAtLeast(MinTailBytes).coerceAtMost(file.sizeBytes)
                picks += RaftFeedbackLogPick(file, tail)
                used += tail
            }
            return false
        }

        for (file in newestLaunch) {
            if (!take(file, budgetBytes - reserve)) break
        }
        val picked = picks.mapTo(HashSet()) { it.file.path }
        for (launch in earlierLaunches) {
            if (!take(launch.first(), budgetBytes)) {
                stopped = true
                break
            }
            picked += launch.first().path
        }
        if (!stopped) {
            for (file in launches.flatten().filter { it.path !in picked }) {
                if (!take(file, budgetBytes)) break
            }
        }
        return picks
    }

    /** The `app_logs/feedback-selection.txt` note: what was attached and what was left out. */
    fun describe(files: List<RaftFeedbackLogFile>, picks: List<RaftFeedbackLogPick>, budgetBytes: Long, entryName: (RaftFeedbackLogFile) -> String): String =
        buildString {
            val included = picks.sumOf { it.includedBytes }
            val total = files.sumOf { it.sizeBytes }
            appendLine("Diagnostics log selection: ${picks.size} of ${files.size} shards, $included of $total bytes (budget $budgetBytes, newest first)")
            picks.forEach { pick ->
                val mode = if (pick.truncated) "tail ${pick.includedBytes} of ${pick.file.sizeBytes} bytes" else "${pick.file.sizeBytes} bytes"
                appendLine("included ${entryName(pick.file)} $mode")
            }
            val pickedPaths = picks.mapTo(HashSet()) { it.file.path }
            files.filter { it.path !in pickedPaths }.forEach { file ->
                appendLine("omitted ${entryName(file)} ${file.sizeBytes} bytes")
            }
        }
}
