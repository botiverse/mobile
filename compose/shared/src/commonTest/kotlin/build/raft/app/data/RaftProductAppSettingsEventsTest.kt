package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * `app_settings_snapshot` and `app_setting_changed`, vendored from botiverse/slock
 * `packages/shared/src/productEvents.ts` at [PinnedSlockSha] (#14904): enums and booleans only, a
 * setting the platform does not show left out, and one value list shared by every setting.
 */
class RaftProductAppSettingsEventsTest {
    private companion object {
        const val PinnedSlockSha = "0147fb5a08c5c5b2b289258566ca0e2a034e1309"
    }

    @Test
    fun theSnapshotCarriesTheStoredValuesAndLeavesHiddenSettingsOut() {
        assertEquals(PinnedSlockSha, RAFT_PRODUCT_EVENTS_APP_SETTINGS_SLOCK_SHA)
        val store = InMemoryRaftKeyValueStore()
        val properties =
            raftAppSettingsSnapshotProperties(
                store,
                RaftAppSettingsPlatform(linkOpenMode = true, conversationForwardGesture = false, iosNativeNav = false, multiShellHost = true),
            )
        val encoded =
            RaftProductEvent.AppSettingsSnapshot(properties).encodeProperties()
                .mapValues { (_, value) -> value.jsonPrimitive.content }
        assertEquals(
            mapOf(
                "message_alignment" to "left",
                "link_open_mode" to "system_browser",
                "compact_messages" to "true",
                "long_message_collapse" to RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED.toString(),
                "new_message_menu" to "false",
                "activity_task_status_badges" to "true",
                "composer_enter_sends" to properties.composerEnterSends.toString(),
                "multi_shell_host" to "false",
                "desktop_mode_large_screen" to "true",
                "server_drawer" to "false",
                "home_server_avatar" to "true",
            ),
            encoded,
        )
    }

    @Test
    fun anIphoneSnapshotWaitsUntilTheNativeNavFlagIsKnown() {
        val iphone = RaftAppSettingsPlatform(linkOpenMode = true, conversationForwardGesture = true, iosNativeNav = true, multiShellHost = false)
        val android = iphone.copy(conversationForwardGesture = false, iosNativeNav = false, multiShellHost = true)
        val store = InMemoryRaftKeyValueStore()
        assertEquals(true, raftAppSettingsSnapshotReady(store, android))
        assertEquals(false, raftAppSettingsSnapshotReady(store, iphone))
        build.raft.app.nav.RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, true)
        assertEquals(true, raftAppSettingsSnapshotReady(store, iphone))
        val properties = raftAppSettingsSnapshotProperties(store, iphone)
        assertEquals(true, properties.iosNativeNav)
        assertEquals(false, properties.iosNativeNavChosen)
    }

    @Test
    fun aChangeIsOneEventWithItsSettingValuesAndSurface() = runTest {
        val transport = RecordingProductEventsTransport()
        val client = productEventsClient(transport).also { it.setSignedIn(ServerId("srv-a")) }
        val recorder = RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime })
        recorder.appSettingChanged(
            RaftAppSettingKey.MessageAlignment,
            previous = raftAppSettingValueOf(RaftAppSettingMessageAlignment.Left),
            value = raftAppSettingValueOf(RaftAppSettingMessageAlignment.Right),
            surface = RaftAppSettingSurface.SettingsMobile,
        )
        // The same value again is no change.
        recorder.appSettingChanged(
            RaftAppSettingKey.NewMessageMenu,
            previous = raftAppSettingToggleValue(true),
            value = raftAppSettingToggleValue(true),
            surface = RaftAppSettingSurface.SettingsAppearance,
        )
        client.flushNow()
        runCurrent()
        val events = transport.sentEvents
        assertEquals(listOf("app_setting_changed"), events.map { it.event })
        assertEquals(
            mapOf("setting" to "message_alignment", "value" to "right", "previous_value" to "left", "surface" to "settings_mobile"),
            events.single().properties.mapValues { (_, value) -> value.jsonPrimitive.content },
        )
    }
}
