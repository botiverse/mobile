package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/** The summary read made when the list stops owning the badge (SharedActivityStore `onBadgeReleased`). */
@RaftCoreInternalApi const val ActivityBadgeReleasedSummaryReason = "activity_badge_released"
