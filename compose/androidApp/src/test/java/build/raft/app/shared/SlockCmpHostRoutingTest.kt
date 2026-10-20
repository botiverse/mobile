package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every page opens in [SlockCmpActivity]; the app shell keeps its task-owning subclass. */
class SlockCmpHostRoutingTest {
    @Test
    fun everyPageButTheAppShellOpensInSlockCmpActivity() {
        for (pageName in listOf(SlockCmpActivity.PAGE_THREAD, SlockCmpActivity.PAGE_SETTINGS_STORAGE, "compose_cmp_hello", "")) {
            assertEquals(pageName, SlockCmpActivity::class.java, slockKuiklyHostActivityClass(pageName))
        }
        assertEquals(SlockAppShellActivity::class.java, slockKuiklyHostActivityClass(SlockCmpActivity.PAGE_APP_SHELL))
    }
}
