package ai.slock.android.core.notification

import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.Message

fun shouldShowInboxNotification(
    message: Message,
    currentMemberId: MemberId?,
    isVisibleThread: Boolean,
    isAppInForeground: Boolean = false
): Boolean {
    if (isAppInForeground) return false
    if (message.author.id == currentMemberId) return false
    if (isVisibleThread) return false
    return true
}
