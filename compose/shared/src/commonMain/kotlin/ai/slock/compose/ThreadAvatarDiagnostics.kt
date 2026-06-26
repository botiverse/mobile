package ai.slock.compose

import ai.slock.compose.model.MemberKind

private const val ThreadAvatarDiagnosticsTag = "SlockThreadAvatar"
private const val ThreadAvatarDiagnosticsEnabled = false

internal fun logThreadAvatarDiagnostic(
    reason: String,
    message: KuiklyThreadMessageItem,
    resolvedAvatarWireValue: String?,
    profileKind: MemberKind?,
    profileAvatarWireValue: String?,
    profileAvatarUrl: String?,
    profileGravatarHash: String?,
    homeAvatarWireValue: String?
) {
    if (!ThreadAvatarDiagnosticsEnabled) return
    slockLogWarn(
        tag = ThreadAvatarDiagnosticsTag,
        message = buildString {
            append("reason=").append(reason)
            append(" messageId=").append(message.id.redactAvatarLogValue())
            append(" authorId=").append(message.authorId.redactAvatarLogValue())
            append(" authorName=").append(message.authorName.redactAvatarLogValue())
            append(" authorKind=").append(message.authorKind.redactAvatarLogValue())
            append(" isOwn=").append(message.isOwn)
            append(" messageType=").append(message.messageType.redactAvatarLogValue())
            append(" systemEventType=").append(message.systemEventType.redactAvatarLogValue())
            append(" deliveryState=").append(message.deliveryState.redactAvatarLogValue())
            append(" rawAvatarWireValue=").append(message.avatarWireValue.redactAvatarLogValue())
            append(" rawGravatarHash=").append(message.gravatarHash.redactAvatarLogValue())
            append(" resolvedAvatarWireValue=").append(resolvedAvatarWireValue.redactAvatarLogValue())
            append(" profileKind=").append(profileKind?.name ?: "null")
            append(" profileAvatarWireValue=").append(profileAvatarWireValue.redactAvatarLogValue())
            append(" profileAvatarUrl=").append(profileAvatarUrl.redactAvatarLogValue())
            append(" profileGravatarHash=").append(profileGravatarHash.redactAvatarLogValue())
            append(" homeAvatarWireValue=").append(homeAvatarWireValue.redactAvatarLogValue())
            append(" metadataKeys=").append(message.metadata.keys.sorted().take(16).joinToString(prefix = "[", postfix = "]"))
            append(" attachmentCount=").append(message.attachments.size)
            append(" reactionCount=").append(message.reactions.size)
        }
    )
}

private fun String?.redactAvatarLogValue(): String =
    this
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(180)
        ?: "null"
