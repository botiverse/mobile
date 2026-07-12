package build.raft.app.shared

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidShellTaskReuseConnectedTest {
    @Test
    fun defaultAppShellHostUsesStandardTaskManifestPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, SlockAppShellActivity::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_NONE, activityInfo.documentLaunchMode)
    }
}
