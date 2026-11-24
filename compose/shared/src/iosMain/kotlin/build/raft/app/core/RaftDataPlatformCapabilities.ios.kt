package build.raft.app

import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.valueForKey

// Read once: an app's process never moves between an iPad and a Mac. Read by its getter name: the Kotlin/Native
// Foundation bindings have no accessor for `-[NSProcessInfo isiOSAppOnMac]` (iOS 14+; we target 15).
internal val raftIosIsAppOnMac: Boolean =
    (NSProcessInfo.processInfo.valueForKey("isiOSAppOnMac") as? NSNumber)?.boolValue == true

internal actual fun raftPlatformIsiOSAppOnMac(): Boolean = raftIosIsAppOnMac

// Not offered on iOS yet. The Compose host opens a "new window" shell in the same window
// (RaftPageStack.open); nothing asks UIKit for a scene (RaftIosShellWindowBridge.activateShell is
// only reached from the retired bridge-module path). With the setting on, a server switch rebuilt
// the shell on the server it was already showing (issue #4484: iPhone Duo reports
// supportsMultipleScenes, iPad takes the same path). Off here, a stored "on" is ignored and every
// switch happens in place. Return to supportsMultipleScenes once the host activates a scene.
internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = false

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = true

// Read at every call, not once: the keyboard state is snapshot state, so a page reading the
// preference (ThreadPage reads it in composition) follows a keyboard being attached or removed.
// LocalPreferences only falls back to this while the user has not chosen in Settings.
internal actual fun raftPlatformComposerEnterSendsByDefault(): Boolean =
    raftIosComposerEnterSendsByDefault(
        onMac = raftIosIsAppOnMac,
        hardwareKeyboard = RaftIosHardwareKeyboardState.connected,
    )

internal actual fun raftPlatformSupportsInAppBrowser(): Boolean = true
