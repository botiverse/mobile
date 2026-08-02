package build.raft.app

// App shell layer contract:
//   SlockEnvironmentIndicatorZIndex > SlockFloatingOverlayHostZIndex
//   SlockFloatingOverlayHostZIndex > SlockGlobalModalHostZIndex
//   SlockGlobalModalHostZIndex > SlockAppBottomTabBarHostZIndex
//   SlockAppBottomTabBarHostZIndex > SlockAppContentHostZIndex
//
// Keep every shell layer on a named constant so tab bars, modal hosts, and
// anchored popovers do not reintroduce same-z draw/hit-test ambiguity.
internal const val SlockAppContentHostZIndex = 0f
private const val SlockAppShellLayerZIndexStep = 1f
private const val SlockGlobalModalAboveTabZIndexOffset = SlockAppShellLayerZIndexStep / 2f
internal const val SlockFloatingOverlayHostZIndex = 1000f
internal const val SlockEnvironmentIndicatorZIndex = SlockFloatingOverlayHostZIndex + SlockAppShellLayerZIndexStep
internal const val SlockAppBottomTabBarHostZIndex = SlockFloatingOverlayHostZIndex - SlockAppShellLayerZIndexStep
internal const val SlockGlobalModalHostZIndex = SlockAppBottomTabBarHostZIndex + SlockGlobalModalAboveTabZIndexOffset
