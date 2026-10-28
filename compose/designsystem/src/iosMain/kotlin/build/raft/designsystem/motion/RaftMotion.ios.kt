package build.raft.designsystem.motion

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun rememberRaftReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
