package build.raft.app

import build.raft.app.data.RaftProductEvents
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

private var raftIosProductEventsLifecycleInstalled = false

/**
 * Product analytics app lifecycle on iOS: the first activation is the cold open, a return after
 * the 30-minute session gap is a foreground open ([RaftProductEvents.onAppForeground] ignores
 * inactive -> active blips that never reached the background). Main thread, installed once.
 */
internal fun installRaftIosProductEventsLifecycle() {
    if (raftIosProductEventsLifecycleInstalled) return
    raftIosProductEventsLifecycleInstalled = true
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
        RaftProductEvents.onAppForeground()
    }
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        RaftProductEvents.onAppBackground()
    }
}
