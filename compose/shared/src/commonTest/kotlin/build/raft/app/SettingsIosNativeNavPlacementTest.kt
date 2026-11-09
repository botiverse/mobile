package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.nav.RaftIosNativeNavFlag
import build.raft.app.resources.Res
import build.raft.app.resources.settings_mobile_ios_native_nav
import build.raft.app.resources.settings_mobile_ios_native_nav_detail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * artin, task #98: the iOS native navigation switch moved from the hidden Developer page to
 * Settings > Mobile (Beta, off by default), on the same stored key so a choice made there stays.
 */
class SettingsIosNativeNavPlacementTest {
    @BeforeTest @AfterTest fun reset() = RaftIosNativeNavFlag.resetForTests()

    @Test
    fun mobileShowsTheSwitchOnAnIPhoneOnly() {
        assertTrue(settingsAppPreferenceVisibility(finePointer = false, supportsIosNativeNav = true).iosNativeNav)
        assertFalse(settingsAppPreferenceVisibility(finePointer = false, supportsIosNativeNav = false).iosNativeNav)
        // The default follows the host: off until the iOS app says it is an iPhone.
        assertFalse(settingsAppPreferenceVisibility(finePointer = false).iosNativeNav)
        RaftIosNativeNavFlag.hostSupported = true
        assertTrue(settingsAppPreferenceVisibility(finePointer = false).iosNativeNav)
    }

    @Test
    fun developerNoLongerListsIt() {
        RaftIosNativeNavFlag.hostSupported = true
        val payload =
            SharedSettingsDeveloperSnapshot().toDeveloperPayload(
                state = ScreenLoadState.Content(SharedSettingsDeveloperSnapshot()),
                actionState = null,
                errorStrings = englishErrorPresentationStringsForTest()
            )
        val keys = payload.sections.flatMap { section -> section.rows.map { it.key } }
        assertFalse(keys.any { it.contains("iosNativeNav", ignoreCase = true) }, keys.toString())
    }

    @Test
    fun theSwitchKeepsTheStoredKeySoEarlierChoicesStay() {
        // The key the Developer switch wrote in 1.22.
        assertEquals("mobile:ios_native_nav_v1:local_override", RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE)
        RaftIosNativeNavFlag.hostSupported = true
        val store = InMemoryRaftKeyValueStore()
        assertFalse(RaftIosNativeNavFlag.stored(store), "off by default")
        store.putString(RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE, "true")
        assertTrue(RaftIosNativeNavFlag.stored(store), "a 1.22 Developer choice reads on in Mobile")
        RaftIosNativeNavFlag.setLocalOverride(store, false)
        assertEquals("false", store.getString(RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE))
    }

    @Test
    fun theRowHasItsMobileStrings() {
        assertNotNull(Res.string.settings_mobile_ios_native_nav)
        assertNotNull(Res.string.settings_mobile_ios_native_nav_detail)
    }
}
