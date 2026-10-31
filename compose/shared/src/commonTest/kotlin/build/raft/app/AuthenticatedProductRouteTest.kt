package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticatedProductRouteTest {
    @Test
    fun independentAuthenticatedProductionRouteInventoryIsCompleteAndUnique() {
        val expected =
            setOf(
                THREAD_PAGE,
                THREAD_MEMBERS_PAGE,
                CHANNEL_SETTINGS_PAGE,
                JOINT_CHANNEL_CONNECTIONS_PAGE,
                THREAD_FORWARD_BUNDLE_PAGE,
                HOME_SEARCH_PAGE,
                HOME_SAVED_PAGE,
                // Drafts (drafts_section_v0): the phone's pushed Drafts page.
                HOME_DRAFTS_PAGE,
                HOME_ACTIVITY_PAGE,
                MEMBERS_PAGE,
                MEMBER_DETAIL_PAGE,
                RAFT_FILE_PREVIEW_PAGE,
                SETTINGS_ACCOUNT_PAGE,
                SETTINGS_HANDS_FEEDBACK_PAGE,
                SETTINGS_NATIVE_DETAIL_PAGE,
                SETTINGS_WEB_VIEW_PAGE,
                SETTINGS_STORAGE_PAGE,
                SETTINGS_COMPUTERS_PAGE,
                RELEASE_NOTES_PAGE,
                // The in-app Artifact viewer: its own authenticated page (sign-out replaces it with login).
                RAFT_ARTIFACT_VIEWER_PAGE,
            )

        assertEquals(expected, authenticatedProductRoutePageNames)
        assertEquals(
            AuthenticatedProductRoute.entries.size,
            authenticatedProductRoutePageNames.size,
        )
    }

    @Test
    fun appShellOwnedAuthenticatedRoutesAreExplicitlyExemptAndDoNotOverlap() {
        val exemptions = AuthenticatedRouteExemption.entries
        assertEquals(exemptions.size, exemptions.map { it.pageName }.toSet().size)
        assertTrue(exemptions.all { it.authorityOwner.isNotBlank() })
        assertTrue(exemptions.none { it.pageName in authenticatedProductRoutePageNames })
    }
}
