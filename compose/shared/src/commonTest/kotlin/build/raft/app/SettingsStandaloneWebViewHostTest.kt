package build.raft.app

import build.raft.app.accepts
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.thread.accepts
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStandaloneWebViewHostTest {
    @Test
    fun everySettingsWebSurfaceUsesTheSinglePageRoute() {
        SettingsWebViewSurface.entries.forEach { surface ->
            val pageData =
                settingsWebViewPageData(
                    surface = surface,
                    storeScope =
                        RaftShellStoreScope.create(
                            shellInstanceId = "shell-${surface.diagnosticsName}",
                            initialServerId = "server-1",
                        ),
                )

            assertEquals(SETTINGS_WEB_VIEW_PAGE, pageData.optString("pageName"))
            assertEquals(surface.diagnosticsName, pageData.optString(SETTINGS_WEB_VIEW_SURFACE))
            assertEquals(surface, settingsWebViewSurfaceFromPageData(pageData))
        }
    }

    @Test
    fun unknownOrMissingSurfaceFailsClosed() {
        assertNull(settingsWebViewSurfaceFromPageData(JSONObject()))
        assertNull(
            settingsWebViewSurfaceFromPageData(
                JSONObject().put(SETTINGS_WEB_VIEW_SURFACE, "unknown"),
            ),
        )
    }

    @Test
    fun backUsesWebHistoryBeforeClosingThePage() {
        assertEquals(
            SettingsWebViewBackAction.NavigateWebHistory,
            settingsWebViewBackAction(canGoBack = true),
        )
        assertEquals(
            SettingsWebViewBackAction.ClosePage,
            settingsWebViewBackAction(canGoBack = false),
        )
    }

    @Test
    fun legacyComputersSurfaceStillResolvesSoOldRoutesRenderTheNativePage() {
        val pageData =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.Computers,
                storeScope = RaftShellStoreScope.create(shellInstanceId = "shell-legacy-computers"),
            ).put(SETTINGS_COMPUTER_ID, "machine-42")

        assertEquals(SettingsWebViewSurface.Computers, settingsWebViewSurfaceFromPageData(pageData))
        assertEquals("machine-42", pageData.optString(SETTINGS_COMPUTER_ID))
    }

    @Test
    fun detachedWebViewGenerationCannotPublishALateTitle() {
        val authority = SettingsWebViewNavigationAuthority()
        val oldOwner = authority.acquire()
        val oldLoadingState =
            SettingsWebViewHeaderTitleState()
                .accept(
                    owner = oldOwner,
                    candidate = "Old Agent",
                    navigationIsLoading = true,
                )
        authority.release(oldOwner)

        assertEquals(false, authority.accepts(oldOwner))

        val currentOwner = authority.acquire()
        val currentCommittedState =
            oldLoadingState.accept(
                owner = currentOwner,
                candidate = "",
                navigationIsLoading = false,
            )

        assertEquals(false, authority.accepts(oldOwner))
        assertEquals(true, authority.accepts(currentOwner))
        assertEquals("Computers", currentCommittedState.displayTitle("Computers"))
    }
}
