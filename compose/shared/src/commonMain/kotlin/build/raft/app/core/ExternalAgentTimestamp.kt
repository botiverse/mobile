package build.raft.app

import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

internal const val ExternalAgentActiveThresholdMillis: Long = 5L * 60L * 1000L

/** Server sends `toISOString()` (UTC, `Z`); the repo parser also accepts offsets and epoch-millis strings. */
internal fun parseExternalAgentTimestampOrNull(iso: String?): Long? =
    parseRaftTimestampEpochMillisOrNull(iso, localOffsetMinutes = 0)
