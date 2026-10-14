package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthAboutDestinationTest {
    @Test
    fun thePrivateDeploymentEntryOpensTheApiEnvironmentEditor() {
        assertEquals(
            AuthAboutDestination.ApiEnvironment,
            authAboutInitialDestination(AUTH_DESTINATION_API_ENVIRONMENT),
        )
    }

    @Test
    fun theDiagnosticsShortcutAndUnknownDestinationsOpenAbout() {
        assertEquals(AuthAboutDestination.About, authAboutInitialDestination(null))
        assertEquals(AuthAboutDestination.About, authAboutInitialDestination(""))
        assertEquals(AuthAboutDestination.About, authAboutInitialDestination("somethingElse"))
    }
}
