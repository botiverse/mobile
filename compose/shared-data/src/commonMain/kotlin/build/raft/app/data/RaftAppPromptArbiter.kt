package build.raft.app.data

/**
 * Global modal prompt arbitration slot for the shared app shell.
 *
 * At most one modal prompt holds the foreground slot at a time, strictly ordered by priority:
 * P0 [MinBuildGateBlock] > P1 [UserInitiatedDialog] > P2 [Announcement] > P3 [AppUpdateStrongPrompt].
 */
enum class RaftAppModalPromptSlot {
    None,
    MinBuildGateBlock,
    UserInitiatedDialog,
    Announcement,
    AppUpdateStrongPrompt,
}

/** Pure arbitration deciding which modal prompt slot may display right now. */
fun arbitrateRaftAppModalPrompt(
    minBuildGateBlocking: Boolean,
    userDialogActive: Boolean,
    announcementReady: Boolean,
    appUpdateStrongPromptReady: Boolean,
): RaftAppModalPromptSlot =
    when {
        minBuildGateBlocking -> RaftAppModalPromptSlot.MinBuildGateBlock
        userDialogActive -> RaftAppModalPromptSlot.UserInitiatedDialog
        announcementReady -> RaftAppModalPromptSlot.Announcement
        appUpdateStrongPromptReady -> RaftAppModalPromptSlot.AppUpdateStrongPrompt
        else -> RaftAppModalPromptSlot.None
    }
