package build.raft.app

import androidx.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockImagePreviewSurfaceTest {
    @Test
    fun aspectFitLetterboxUsesNeutralSurfaceInsteadOfBrandYellow() {
        assertEquals(SlockColor.SurfaceWhite, SlockImagePreviewLetterboxColor)
        assertFalse(SlockImagePreviewLetterboxColor == SlockColor.SurfaceCream)
        assertFalse(SlockImagePreviewLetterboxColor == SlockColor.BrandYellow)
    }

    @Test
    fun thumbnailPreservesRatioWhileFillingItsFrame() {
        assertEquals(ContentScale.Crop, SlockImageThumbnailContentScale)
    }

    @Test
    fun fullImageLoadKeepsThumbnailVisibleWithoutLoadingOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )

        assertEquals("https://cdn.example/thumb.jpg", presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun initialImageWithoutThumbnailStillShowsLoadingOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )

        assertNull(presentation.fallbackUnderlaySource)
        assertTrue(presentation.showLoadingOverlay)
    }

    @Test
    fun successfulFullImageReplacesThumbnailWithoutOverlay() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = true,
                activeFailed = false
            )

        assertNull(presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun hydrationSwapBeforeFirstSuccessKeepsDepartedSourceAsUnderlay() {
        // task #287: thumbnail still loading when the signed full URL commits
        // (lastSucceededSource == null, single image so no fallback). Without
        // the departed-source underlay the spinner flashes a second time.
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false,
                previousRenderedSource = null,
                previousPresentableSource = "https://cdn.example/thumb.jpg"
            )

        assertEquals("https://cdn.example/thumb.jpg", presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun succeededFrameStillWinsOverDepartedPendingSource() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/full.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false,
                previousRenderedSource = "https://cdn.example/thumb.jpg",
                previousPresentableSource = "https://cdn.example/intermediate.jpg"
            )

        assertEquals("https://cdn.example/thumb.jpg", presentation.fallbackUnderlaySource)
        assertFalse(presentation.showLoadingOverlay)
    }

    @Test
    fun departedSourceEqualToActiveIsNotUnderlaid() {
        val presentation =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = null,
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false,
                previousPresentableSource = "https://cdn.example/thumb.jpg"
            )

        assertNull(presentation.fallbackUnderlaySource)
        assertTrue(presentation.showLoadingOverlay)
    }

    @Test
    fun cachedFallbackReloadDoesNotFlashSpinnerButCanSurfaceTerminalFailure() {
        val loadingFallback =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = false
            )
        val failedFallback =
            slockImagePreviewLoadingPresentation(
                activeSource = "https://cdn.example/thumb.jpg",
                fallbackSource = "https://cdn.example/thumb.jpg",
                showLoadingIndicator = true,
                activeSucceeded = false,
                activeFailed = true
            )

        assertNull(loadingFallback.fallbackUnderlaySource)
        assertFalse(loadingFallback.showLoadingOverlay)
        assertTrue(failedFallback.showLoadingOverlay)
    }

    @Test
    fun coldDecodeCompletionChangesNativeImageRenderIdentity() {
        val loading =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/image.jpg",
                decodedWidth = 0,
                decodedHeight = 0
            )
        val decoded =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/image.jpg",
                decodedWidth = 1280,
                decodedHeight = 720
            )

        assertFalse(loading == decoded)
    }

    @Test
    fun resolvedSourceChangesNativeImageRenderIdentity() {
        val thumbnail =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/thumb.jpg",
                decodedWidth = 320,
                decodedHeight = 180
            )
        val fullImage =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/full.jpg",
                decodedWidth = 320,
                decodedHeight = 180
            )

        assertFalse(thumbnail == fullImage)
    }

    @Test
    fun invalidDecodeDimensionsRemainOneStableLoadingIdentity() {
        assertEquals(
            slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 0, 0),
            slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", -1, -1)
        )
    }

    @Test
    fun resolveIdentityPreservesExistingIdentityWhenAlreadySucceeded() {
        val initialIdentity =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/image.jpg",
                decodedWidth = 0,
                decodedHeight = 0
            )

        // When image has already succeeded, late cold decode must NOT alter the render identity
        val resolved =
            resolveSlockImagePreviewRenderIdentity(
                currentIdentity = initialIdentity,
                source = "https://cdn.example/image.jpg",
                decodedWidth = 606,
                decodedHeight = 508,
                activeSucceeded = true
            )

        assertEquals(initialIdentity, resolved)
    }

    @Test
    fun resolveIdentityUpdatesWhenColdDecodeCompletesBeforeSuccess() {
        val initialIdentity =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/image.jpg",
                decodedWidth = 0,
                decodedHeight = 0
            )

        // Cold decode finishes while image is still loading
        val resolved =
            resolveSlockImagePreviewRenderIdentity(
                currentIdentity = initialIdentity,
                source = "https://cdn.example/image.jpg",
                decodedWidth = 1280,
                decodedHeight = 720,
                activeSucceeded = false
            )

        assertEquals(
            slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 1280, 720),
            resolved
        )
    }

    @Test
    fun resolveIdentityPreservesDecodedIdentityOnceUpdated() {
        val decodedIdentity =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/image.jpg",
                decodedWidth = 1280,
                decodedHeight = 720
            )

        val resolved =
            resolveSlockImagePreviewRenderIdentity(
                currentIdentity = decodedIdentity,
                source = "https://cdn.example/image.jpg",
                decodedWidth = 1280,
                decodedHeight = 720,
                activeSucceeded = true
            )

        assertEquals(decodedIdentity, resolved)
    }

    @Test
    fun resolveIdentityResetsWhenSourceChanges() {
        val oldIdentity =
            slockImagePreviewRenderIdentity(
                source = "https://cdn.example/old.jpg",
                decodedWidth = 1280,
                decodedHeight = 720
            )

        val resolved =
            resolveSlockImagePreviewRenderIdentity(
                currentIdentity = oldIdentity,
                source = "https://cdn.example/new.jpg",
                decodedWidth = 0,
                decodedHeight = 0,
                activeSucceeded = true
            )

        assertEquals(
            slockImagePreviewRenderIdentity("https://cdn.example/new.jpg", 0, 0),
            resolved
        )
    }
}
