package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsBillingWebViewTest {
    @Test
    fun iosShowsBillingViewOnlyWithoutUpgradeOrBrowserLink() {
        assertEquals(IosBillingPresentation.ViewOnly, iosBillingPresentation())
        val target = settingsBillingOpenTarget(appStoreBillingRules = true)
        assertEquals(SettingsBillingOpenTarget.ViewOnly, target)
        assertFalse(settingsBillingOffersUpgrade(target))
        assertFalse(settingsBillingLeavesRaft(target))
    }
}
