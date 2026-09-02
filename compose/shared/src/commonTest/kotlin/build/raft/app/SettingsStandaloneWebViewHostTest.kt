package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStandaloneWebViewHostTest {
    @Test
    fun everySettingsWebSurfaceUsesTheSinglePageRoute() {
        SettingsWebViewSurface.entries.forEach { surface ->
            val pageData =
                settingsWebViewPageData(
                    surface = surface,
                    storeScope =
                        SlockShellStoreScope.create(
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
    fun releaseNotesRouteCarriesOnlySanitizedVersionConfiguration() {
        val pageData =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.ReleaseNotes,
                storeScope = SlockShellStoreScope.create(shellInstanceId = "shell-release-notes"),
                versionCode = " 1010000 ",
            )

        assertEquals(SETTINGS_WEB_VIEW_PAGE, pageData.optString("pageName"))
        assertEquals("release-notes", pageData.optString(SETTINGS_WEB_VIEW_SURFACE))
        assertEquals("1010000", pageData.optString(SETTINGS_WEB_VIEW_VERSION_CODE))
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
    fun everySurfaceExecutesExactlyOneHistoryOrCloseSideEffect() {
        SettingsWebViewSurface.entries.forEach { surface ->
            var goBackCount = 0
            var closeCount = 0
            assertEquals(
                SettingsWebViewBackAction.NavigateWebHistory,
                performSettingsWebViewBack(
                    canGoBack = true,
                    goBack = { goBackCount += 1 },
                    closePage = { closeCount += 1 },
                ),
                surface.diagnosticsName,
            )
            assertEquals(1, goBackCount, surface.diagnosticsName)
            assertEquals(0, closeCount, surface.diagnosticsName)

            goBackCount = 0
            closeCount = 0
            assertEquals(
                SettingsWebViewBackAction.ClosePage,
                performSettingsWebViewBack(
                    canGoBack = false,
                    goBack = { goBackCount += 1 },
                    closePage = { closeCount += 1 },
                ),
                surface.diagnosticsName,
            )
            assertEquals(0, goBackCount, surface.diagnosticsName)
            assertEquals(1, closeCount, surface.diagnosticsName)
        }
    }

    @Test
    fun opaqueCoverRemainsUntilContentCommitAndFailureUsesErrorState() {
        assertEquals(
            SettingsWebViewPresentation.LoadingCover,
            settingsWebViewPresentation(contentVisible = false, hasLoadError = false),
        )
        assertEquals(
            SettingsWebViewPresentation.Error,
            settingsWebViewPresentation(contentVisible = false, hasLoadError = true),
        )
        assertEquals(
            SettingsWebViewPresentation.Content,
            settingsWebViewPresentation(contentVisible = true, hasLoadError = false),
        )
    }

    @Test
    fun fullScreenFrameAtRootAttachesWhileInvalidOrUnmeasuredFramesStayCovered() {
        assertTrue(settingsWebViewFrameIsReady(positionY = 0f, width = 1080, height = 2400))
        assertTrue(settingsWebViewFrameIsReady(positionY = 64f, width = 1080, height = 2200))

        assertFalse(settingsWebViewFrameIsReady(positionY = -1f, width = 1080, height = 2400))
        assertFalse(settingsWebViewFrameIsReady(positionY = Float.NaN, width = 1080, height = 2400))
        assertFalse(settingsWebViewFrameIsReady(positionY = 0f, width = 0, height = 2400))
        assertFalse(settingsWebViewFrameIsReady(positionY = 0f, width = 1080, height = 0))
    }

    @Test
    fun headerLayerIsAboveTheNativeWebViewBodySibling() {
        assertTrue(SETTINGS_WEB_VIEW_HEADER_Z_INDEX > SETTINGS_WEB_VIEW_BODY_Z_INDEX)
    }

    @Test
    fun computersHeaderUsesFallbackUntilTheWebDocumentPublishesATitle() {
        val state =
            SettingsWebViewHeaderTitleState()
                .accept(candidate = "Raft", navigationIsLoading = true)

        assertTrue(settingsComputersWebViewSpec.headerFollowsDocumentTitle)
        assertEquals("Computers", state.displayTitle("Computers"))
    }

    @Test
    fun computersHeaderTracksAgentDocumentTitle() {
        val loadingState =
            SettingsWebViewHeaderTitleState()
                .accept(candidate = "  Jony  ", navigationIsLoading = true)
        val committedState =
            loadingState.accept(candidate = "", navigationIsLoading = false)

        assertEquals("Computers", loadingState.displayTitle("Computers"))
        assertEquals("Jony", committedState.displayTitle("Computers"))
    }

    @Test
    fun emptyNavigationTitleKeepsTheLastCommittedAgentTitle() {
        val state =
            SettingsWebViewHeaderTitleState()
                .accept("Jony")
                .accept("   ")

        assertEquals("Jony", state.displayTitle("Computers"))
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
