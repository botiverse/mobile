package build.raft.app

internal actual fun raftPlatformIsiOSAppOnMac(): Boolean = false

internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = true

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = false

internal actual fun raftPlatformComposerEnterSendsByDefault(): Boolean = false

internal actual fun raftPlatformSupportsInAppBrowser(): Boolean = true
