package build.raft.app

import build.raft.app.resources.*
import build.raft.app.settings.IosBillingPresentation
import build.raft.app.settings.SettingsBillingOpenTarget
import build.raft.app.settings.iosBillingPresentation
import build.raft.app.settings.settingsBillingLeavesRaft
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.settings.settingsBillingOpenTarget
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
