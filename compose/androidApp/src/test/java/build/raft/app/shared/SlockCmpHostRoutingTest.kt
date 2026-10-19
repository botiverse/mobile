package build.raft.app.shared

import build.raft.app.cmp.SlockCmpPages
import build.raft.app.cmp.SlockCmpRouter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android side of the Kuikly/CMP router split: [slockKuiklyHostActivityClass]. */
class SlockCmpHostRoutingTest {
    @After
    fun reset() {
        SlockCmpRouter.configure(debugBuild = false)
    }

    @Test
    fun releaseBuildKeepsEveryPageOnItsKuiklyHost() {
        SlockCmpRouter.configure(debugBuild = false)
        assertEquals(SlockKuiklyActivity::class.java, slockKuiklyHostActivityClass(SlockCmpPages.CMP_HELLO))
        assertEquals(SlockKuiklyActivity::class.java, slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_THREAD))
        assertEquals(SlockAppShellActivity::class.java, slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_APP_SHELL))
    }

    @Test
    fun debugBuildHostsTheCmpDemoPageInSlockCmpActivityOnly() {
        SlockCmpRouter.configure(debugBuild = true)
        assertEquals(SlockCmpActivity::class.java, slockKuiklyHostActivityClass(SlockCmpPages.CMP_HELLO))
        assertEquals(SlockKuiklyActivity::class.java, slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_SETTINGS_STORAGE))
        assertEquals(SlockAppShellActivity::class.java, slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_APP_SHELL))
    }

    @Test
    fun remoteRollbackSendsAPageBackToKuikly() {
        SlockCmpRouter.configure(debugBuild = true) { false }
        assertEquals(SlockKuiklyActivity::class.java, slockKuiklyHostActivityClass(SlockCmpPages.CMP_HELLO))
    }
}
