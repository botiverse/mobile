package build.raft.app

import androidx.compose.runtime.Composable

/** Deletes an app-private temporary screenshot feedback file (best effort, safe-path guarded). */
internal expect fun raftDeleteScreenshotFeedbackTempFile(fileRef: String)

/**
 * Installs platform screenshot observation while [enabled] is true on the active foreground page.
 * - iOS: observes `UIApplicationUserDidTakeScreenshotNotification`, snapshots key `UIWindow`,
 *   writes JPEG off main thread to `NSTemporaryDirectory()/screenshot-feedback/<uuid>.jpg`.
 * - Android: handled directly by `RaftCmpActivity` (`Android14ScreenCaptureObserver` + `PixelCopy`).
 * - Desktop: no-op.
 */
@Composable
internal expect fun RaftPlatformScreenshotFeedbackObserverEffect(
    enabled: Boolean,
    pageContextProvider: () -> RaftScreenshotFeedbackPageContext,
)
