package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftAppPromptArbiterTest {
    @Test
    fun arbitrateRespectsStrictPriorityAcrossAllCombinations() {
        val booleans = listOf(false, true)
        for (minBuildBlock in booleans) {
            for (userDialog in booleans) {
                for (announcement in booleans) {
                    for (appUpdateStrong in booleans) {
                        val expected =
                            when {
                                minBuildBlock -> RaftAppModalPromptSlot.MinBuildGateBlock
                                userDialog -> RaftAppModalPromptSlot.UserInitiatedDialog
                                announcement -> RaftAppModalPromptSlot.Announcement
                                appUpdateStrong -> RaftAppModalPromptSlot.AppUpdateStrongPrompt
                                else -> RaftAppModalPromptSlot.None
                            }
                        val actual =
                            arbitrateRaftAppModalPrompt(
                                minBuildGateBlocking = minBuildBlock,
                                userDialogActive = userDialog,
                                announcementReady = announcement,
                                appUpdateStrongPromptReady = appUpdateStrong,
                            )
                        assertEquals(
                            expected,
                            actual,
                            "minBuildBlock=$minBuildBlock userDialog=$userDialog announcement=$announcement appUpdateStrong=$appUpdateStrong",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun lowerPrioritySlotPromotesImmediatelyWhenHigherPriorityClears() {
        assertEquals(
            RaftAppModalPromptSlot.MinBuildGateBlock,
            arbitrateRaftAppModalPrompt(
                minBuildGateBlocking = true,
                userDialogActive = true,
                announcementReady = true,
                appUpdateStrongPromptReady = true,
            ),
        )
        assertEquals(
            RaftAppModalPromptSlot.UserInitiatedDialog,
            arbitrateRaftAppModalPrompt(
                minBuildGateBlocking = false,
                userDialogActive = true,
                announcementReady = true,
                appUpdateStrongPromptReady = true,
            ),
        )
        assertEquals(
            RaftAppModalPromptSlot.Announcement,
            arbitrateRaftAppModalPrompt(
                minBuildGateBlocking = false,
                userDialogActive = false,
                announcementReady = true,
                appUpdateStrongPromptReady = true,
            ),
        )
        assertEquals(
            RaftAppModalPromptSlot.AppUpdateStrongPrompt,
            arbitrateRaftAppModalPrompt(
                minBuildGateBlocking = false,
                userDialogActive = false,
                announcementReady = false,
                appUpdateStrongPromptReady = true,
            ),
        )
        assertEquals(
            RaftAppModalPromptSlot.None,
            arbitrateRaftAppModalPrompt(
                minBuildGateBlocking = false,
                userDialogActive = false,
                announcementReady = false,
                appUpdateStrongPromptReady = false,
            ),
        )
    }
}
