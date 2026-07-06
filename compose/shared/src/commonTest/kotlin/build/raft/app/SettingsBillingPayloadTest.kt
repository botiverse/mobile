package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingActionSnapshot
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingPlanSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SharedSettingsActionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsBillingPayloadTest {
    @Test
    fun billingPayloadThreadsActionBusyAndErrorState() {
        val snapshot =
            SettingsBillingSnapshot(
                currentPlan =
                    SettingsBillingCurrentPlanSnapshot(
                        planName = "Team",
                        usageRows = emptyList()
                    ),
                plans =
                    listOf(
                        SettingsBillingPlanSnapshot(
                            name = "Team",
                            price = "$20/mo",
                            features = emptyList(),
                            current = true
                        )
                    ),
                actions =
                    listOf(
                        SettingsBillingActionSnapshot(
                            key = "manageBilling",
                            label = "Manage billing",
                            detail = "Open customer portal.",
                            enabled = true
                        )
                    )
            )

        val loadingPayload =
            ScreenLoadState.Content(snapshot).toBillingPayload(
                session = null,
                timeContext = null,
                actionState = SharedSettingsActionState("manageBilling", inProgress = true)
            )
        val loadingAction = loadingPayload.actions.single()
        assertEquals("Loading", loadingAction.status)
        assertTrue(loadingAction.inProgress)
        assertFalse(loadingAction.enabled)
        assertNull(loadingAction.errorCode)

        val errorPayload =
            ScreenLoadState.Content(snapshot).toBillingPayload(
                session = null,
                timeContext = null,
                actionState = SharedSettingsActionState("manageBilling", inProgress = false, errorCode = "billing_portal_failed")
            )
        val errorAction = errorPayload.actions.single()
        assertEquals("Error", errorAction.status)
        assertEquals("billing_portal_failed", errorAction.errorCode)
        assertTrue(errorAction.enabled)
    }
}
