package build.raft.app

/**
 * The iPad app is running on a Mac ("Designed for iPad", `NSProcessInfo.isiOSAppOnMac`), where it
 * behaves like the desktop app (RaftIosAppOnMac.kt). False on every other host.
 */
internal expect fun raftPlatformIsiOSAppOnMac(): Boolean

/**
 * The "Enter sends" default: on with a Mac's keyboard, like desktop, and on an iPad or iPhone while
 * a hardware keyboard is connected (RaftIosHardwareKeyboardState). A choice saved in Settings wins.
 */
internal fun raftIosComposerEnterSendsByDefault(onMac: Boolean, hardwareKeyboard: Boolean): Boolean =
    onMac || hardwareKeyboard

internal expect fun raftPlatformSupportsMultiShellHost(): Boolean

/** iOS is currently the only host with a native right-edge Forward recognizer. */
internal expect fun raftPlatformSupportsConversationForwardGesture(): Boolean

/**
 * Default of the "Enter sends" composer preference (an explicit choice in settings wins). Desktop:
 * on, like the web on a fine pointer (Enter / Cmd+Enter send, Shift+Enter inserts a newline, Enter
 * while an IME composes commits the conversion). Phones keep Enter as a newline.
 */
internal expect fun raftPlatformComposerEnterSendsByDefault(): Boolean

/**
 * An in-app browser page can show a web page. Desktop has no embedded web view yet
 * (RaftDesktopActuals `RaftSandboxWebViewHost`), so its web links always open in the default
 * browser and Settings does not offer the "Open web links" choice.
 */
internal expect fun raftPlatformSupportsInAppBrowser(): Boolean
