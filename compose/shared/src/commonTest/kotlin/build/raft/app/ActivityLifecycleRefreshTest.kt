package build.raft.app

import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityLifecycleRefreshTest {
    @Test
    fun standaloneLifecycleRequiresExactPageShellAndVisibility() {
        val data = JSONObject()
            .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionAppLifecycle)
            .put(SlockRealtimeNotifyContract.KeyPageName, HOME_ACTIVITY_PAGE)
            .put(SlockRealtimeNotifyContract.KeyShellInstanceId, "shell-1")
        for (event in listOf(SlockRealtimeNotifyContract.LifecycleVisibleResume, SlockRealtimeNotifyContract.LifecycleVisibleShow)) {
            data.put(SlockRealtimeNotifyContract.KeyLifecycleEvent, event)
            assertTrue(activityLifecycleShouldRefresh(data, "shell-1", true))
            assertFalse(activityLifecycleShouldRefresh(data, "shell-1", false))
            assertFalse(activityLifecycleShouldRefresh(data, "shell-2", true))
        }
        data.put(SlockRealtimeNotifyContract.KeyLifecycleEvent, SlockRealtimeNotifyContract.LifecycleHidden)
        assertFalse(activityLifecycleShouldRefresh(data, "shell-1", true))
        data.put(SlockRealtimeNotifyContract.KeyLifecycleEvent, SlockRealtimeNotifyContract.LifecycleVisibleResume)
        data.put(SlockRealtimeNotifyContract.KeyPageName, SLOCK_APP_SHELL_PAGE)
        assertFalse(activityLifecycleShouldRefresh(data, "shell-1", true))
        data.put(SlockRealtimeNotifyContract.KeyPageName, HOME_ACTIVITY_PAGE)
        data.put(SlockRealtimeNotifyContract.KeyAction, "other")
        assertFalse(activityLifecycleShouldRefresh(data, "shell-1", true))
    }
}
