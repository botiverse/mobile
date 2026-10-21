package build.raft.app

import build.raft.app.data.RaftAppOpenSignal
import build.raft.app.data.RaftProductEvents
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

private var raftIosProductEventsLifecycleInstalled = false
private var raftIosProductEventsActivated = false

/**
 * Product analytics app lifecycle on iOS: the first activation is the cold open, a return after
 * the 30-minute session gap is a foreground open ([RaftProductEvents.onAppForeground] ignores
 * inactive -> active blips that never reached the background). Main thread, installed once.
 *
 * `app_opened.via`: a notification response reported through [RaftIosProductEventsHost] before
 * the activation is `notification`; a first activation with no other launch reason is a plain
 * launch (`launcher`); otherwise `via` is omitted.
 */
internal fun installRaftIosProductEventsLifecycle() {
    if (raftIosProductEventsLifecycleInstalled) return
    raftIosProductEventsLifecycleInstalled = true
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
        if (!raftIosProductEventsActivated) {
            raftIosProductEventsActivated = true
            RaftProductEvents.onDefaultAppOpenSignal(RaftAppOpenSignal.Launcher)
        }
        RaftProductEvents.onAppForeground()
    }
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        RaftProductEvents.onAppBackground()
    }
}

/** The Swift host's open reasons for `app_opened.via` (main thread). */
object RaftIosProductEventsHost {
    /** A push notification response (cold launch or return): the app opens via the notification. */
    fun noteNotificationOpen() {
        RaftProductEvents.onAppOpenSignal(RaftAppOpenSignal.Notification)
    }

    /** A launch for another reason (a URL, a universal link): not a plain launcher open. */
    fun noteOtherLaunch() {
        RaftProductEvents.onDefaultAppOpenSignal(RaftAppOpenSignal.Unknown)
    }
}
