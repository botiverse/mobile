package ai.slock.android.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockKuiklyHostCloseActionTest {

    @Test
    fun appShellCloseMovesTaskToBack() {
        assertEquals(
                SlockKuiklyHostCloseAction.MoveTaskToBack,
                slockKuiklyHostCloseAction(SlockKuiklyPages.APP_SHELL)
        )
    }

    @Test
    fun standalonePagesStillFinish() {
        assertEquals(SlockKuiklyHostCloseAction.Finish, slockKuiklyHostCloseAction(SlockKuiklyPages.THREAD))
        assertEquals(SlockKuiklyHostCloseAction.Finish, slockKuiklyHostCloseAction(SlockKuiklyPages.LOGIN))
        assertEquals(SlockKuiklyHostCloseAction.Finish, slockKuiklyHostCloseAction(null))
    }
}
