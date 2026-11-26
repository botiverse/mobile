package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import build.raft.app.base.BridgeModule
import build.raft.app.data.ChannelMemberStillInChannel
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastIntent

private const val StillInChannelFirstPlaceholder = "\u0001"
private const val StillInChannelSecondPlaceholder = "\u0002"

@Immutable
internal data class StillInChannelToastStrings(
    val throughTemplate: String,
    val listTwoTemplate: String,
    val listMoreTemplate: String,
    val listEndTemplate: String,
)

@Composable
internal fun stillInChannelToastStrings(): StillInChannelToastStrings =
    StillInChannelToastStrings(
        throughTemplate =
            stringResource(
                Res.string.channel_members_still_in_channel_through,
                StillInChannelFirstPlaceholder,
                StillInChannelSecondPlaceholder,
            ),
        listTwoTemplate =
            stringResource(
                Res.string.member_removal_impact_list_two,
                StillInChannelFirstPlaceholder,
                StillInChannelSecondPlaceholder,
            ),
        listMoreTemplate =
            stringResource(
                Res.string.member_removal_impact_list_more,
                StillInChannelFirstPlaceholder,
                StillInChannelSecondPlaceholder,
            ),
        listEndTemplate =
            stringResource(
                Res.string.member_removal_impact_list_end,
                StillInChannelFirstPlaceholder,
                StillInChannelSecondPlaceholder,
            ),
    )

/**
 * Display name for the `stillInChannel` info toast when removing a human member removes only the
 * viewed server's side of their joint-channel membership. Returns null when neither display name
 * nor handle is present so [formatStillInChannelToastMessage] falls back to the server's text
 * instead of interpolating a raw member id.
 */
internal fun RaftThreadMemberItem.stillInChannelPersonName(): String? =
    displayName.trim().takeIf { it.isNotEmpty() }
        ?: handle.trim().removePrefix("@").trim().takeIf { it.isNotEmpty() }

/**
 * Formats the info toast shown when removing a human member from a joint channel removes only the
 * viewed server's side of their membership and they stay in the channel through other connected
 * servers (parity with `botiverse/slock` #17097 + #17102).
 *
 * - When [personName] is non-blank and [stillInChannel] lists at least one non-blank server name,
 *   formats [strings.throughTemplate] with `(personName, localizedServerList)`.
 * - Otherwise falls back to [stillInChannel.text] when non-blank.
 * - Otherwise returns null.
 */
internal fun formatStillInChannelToastMessage(
    stillInChannel: ChannelMemberStillInChannel?,
    personName: String?,
    strings: StillInChannelToastStrings,
): String? {
    if (stillInChannel == null) return null
    val cleanPersonName = personName?.trim()?.takeIf { it.isNotEmpty() }
    val serverNames = stillInChannel.through.mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
    if (cleanPersonName != null && serverNames.isNotEmpty()) {
        val formattedServers =
            memberRemovalImpactJoin(
                items = serverNames,
                two = { a, b -> fillStillInChannelPlaceholders(strings.listTwoTemplate, a, b) },
                more = { a, b -> fillStillInChannelPlaceholders(strings.listMoreTemplate, a, b) },
                end = { a, b -> fillStillInChannelPlaceholders(strings.listEndTemplate, a, b) },
            )
        return fillStillInChannelPlaceholders(strings.throughTemplate, cleanPersonName, formattedServers)
    }
    return stillInChannel.text?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Posts the toast for a succeeded channel member removal:
 * - When [stillInChannel] is present, shows an 8s info toast explaining why the person remains in
 *   the joint channel (or nothing if neither localized inputs nor server fallback text are usable).
 * - Otherwise preserves the existing plain [memberRemovedLabel] toast.
 */
internal fun BridgeModule.postChannelMemberRemovedToast(
    stillInChannel: ChannelMemberStillInChannel?,
    personName: String?,
    stillInChannelStrings: StillInChannelToastStrings,
    memberRemovedLabel: String,
) {
    if (stillInChannel != null) {
        val message = formatStillInChannelToastMessage(stillInChannel, personName, stillInChannelStrings)
        if (message != null) {
            toastWithIntent(
                content = message,
                intent = RaftToastIntent.Info,
                durationMillis = RaftToastActionTimeoutMillis,
            )
        }
    } else {
        toast(memberRemovedLabel)
    }
}

private fun fillStillInChannelPlaceholders(template: String, first: String, second: String): String {
    val firstIndex = template.indexOf(StillInChannelFirstPlaceholder)
    val secondIndex = template.indexOf(StillInChannelSecondPlaceholder)
    if (firstIndex < 0 || secondIndex < 0) {
        return template
            .replace(StillInChannelFirstPlaceholder, first)
            .replace(StillInChannelSecondPlaceholder, second)
    }
    return if (firstIndex < secondIndex) {
        template.substring(0, firstIndex) +
            first +
            template.substring(firstIndex + StillInChannelFirstPlaceholder.length, secondIndex) +
            second +
            template.substring(secondIndex + StillInChannelSecondPlaceholder.length)
    } else {
        template.substring(0, secondIndex) +
            second +
            template.substring(secondIndex + StillInChannelSecondPlaceholder.length, firstIndex) +
            first +
            template.substring(firstIndex + StillInChannelFirstPlaceholder.length)
    }
}
