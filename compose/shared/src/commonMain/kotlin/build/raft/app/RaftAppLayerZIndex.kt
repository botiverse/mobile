package build.raft.app

// App shell layer contract:
//   RaftEnvironmentIndicatorZIndex > RaftFloatingOverlayHostZIndex
//   RaftFloatingOverlayHostZIndex > RaftGlobalModalHostZIndex
//   RaftGlobalModalHostZIndex > RaftAppBottomTabBarHostZIndex
//   RaftAppBottomTabBarHostZIndex > RaftAppContentHostZIndex
//
// Keep every shell layer on a named constant so tab bars, modal hosts, and
// anchored popovers do not reintroduce same-z draw/hit-test ambiguity.
internal const val RaftAppContentHostZIndex = 0f
private const val RaftAppShellLayerZIndexStep = 1f
private const val RaftGlobalModalAboveTabZIndexOffset = RaftAppShellLayerZIndexStep / 2f
internal const val RaftFloatingOverlayHostZIndex = 1000f
internal const val RaftEnvironmentIndicatorZIndex = RaftFloatingOverlayHostZIndex + RaftAppShellLayerZIndexStep
internal const val RaftAppBottomTabBarHostZIndex = RaftFloatingOverlayHostZIndex - RaftAppShellLayerZIndexStep
internal const val RaftGlobalModalHostZIndex = RaftAppBottomTabBarHostZIndex + RaftGlobalModalAboveTabZIndexOffset
