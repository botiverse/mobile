package build.raft.app.members

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.data.DescriptionLinkFix
import build.raft.app.data.DescriptionLinkSubject
import build.raft.app.data.DescriptionLinkWarning
import build.raft.app.data.DescriptionLinkWarningCode
import build.raft.app.data.byLink
import build.raft.app.data.descriptionLinkFixesOf
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus

/**
 * After a description is saved, the links in it some of its readers can't open (web
 * `DescriptionLinkWarnings.tsx`, botiverse/slock #15236): a warning banner titled with how many
 * links, then per link its problem (a person's description: one sentence per server under its
 * link) and every fix that applies once, in the shared order (widest audience last), then who reads
 * the description. The save succeeded; it stays until dismissed. Nothing when [warnings] is empty.
 */
@Composable
internal fun RaftDescriptionLinkWarnings(
    warnings: List<DescriptionLinkWarning>,
    subject: DescriptionLinkSubject,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = warnings.byLink()
    if (links.isEmpty()) return
    val title =
        if (links.size == 1) {
            stringResource(Res.string.profile_link_warnings_title_one)
        } else {
            stringResource(Res.string.profile_link_warnings_title_other, links.size)
        }
    val blocks =
        links.map { (url, about) ->
            val problems = about.map { descriptionLinkProblem(it) }
            val fixes =
                descriptionLinkFixesOf(about.map { it.code }, subject).map { fix -> "• " + descriptionLinkFixText(fix, subject) }
            (problems + fixes).joinToString("\n")
        }
    val reminder =
        stringResource(
            if (subject == DescriptionLinkSubject.Agent) Res.string.profile_link_warnings_reminder_agent
            else Res.string.profile_link_warnings_reminder
        )
    RaftBanner(
        description = (blocks + reminder).joinToString("\n\n"),
        status = RaftBannerStatus.Warning,
        title = title,
        size = RaftBannerSize.Sm,
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.profile_link_warnings_dismiss),
        modifier = modifier.testTag(RaftDescriptionLinkWarningsTag),
    )
}

/** One warning's sentence (web `PROBLEM_ID`); an unknown code reads as "can't open". */
@Composable
private fun descriptionLinkProblem(warning: DescriptionLinkWarning): String {
    val url = warning.url
    val server = warning.serverName
    return when (warning.code) {
        DescriptionLinkWarningCode.Unavailable, DescriptionLinkWarningCode.Unknown ->
            stringResource(Res.string.profile_link_warnings_unavailable, url)
        DescriptionLinkWarningCode.OtherServer ->
            if (server != null) stringResource(Res.string.profile_link_warnings_other_server_in_server, url, server)
            else stringResource(Res.string.profile_link_warnings_other_server, url)
        DescriptionLinkWarningCode.Conversation ->
            if (server != null) stringResource(Res.string.profile_link_warnings_conversation_in_server, url, server)
            else stringResource(Res.string.profile_link_warnings_conversation, url)
        DescriptionLinkWarningCode.ServerPartial -> {
            // Web `whoCantRead`: guests unless only other servers are named; both when both.
            val both = warning.unreadableByGuests && warning.unreadableByOtherServers
            val othersOnly = warning.unreadableByOtherServers && !warning.unreadableByGuests
            when {
                both && server != null -> stringResource(Res.string.profile_link_warnings_server_partial_guests_and_other_servers_in_server, url, server)
                both -> stringResource(Res.string.profile_link_warnings_server_partial_guests_and_other_servers, url)
                othersOnly && server != null -> stringResource(Res.string.profile_link_warnings_server_partial_other_servers_in_server, url, server)
                othersOnly -> stringResource(Res.string.profile_link_warnings_server_partial_other_servers, url)
                server != null -> stringResource(Res.string.profile_link_warnings_server_partial_guests_in_server, url, server)
                else -> stringResource(Res.string.profile_link_warnings_server_partial_guests, url)
            }
        }
    }
}

/** A fix in the reader's words (web `FIX_ID`; an agent's separate version only needs to reach its server). */
@Composable
private fun descriptionLinkFixText(fix: DescriptionLinkFix, subject: DescriptionLinkSubject): String =
    stringResource(
        when (fix) {
            DescriptionLinkFix.PublicCopy ->
                if (subject == DescriptionLinkSubject.Agent) Res.string.profile_link_warnings_fix_public_copy_server
                else Res.string.profile_link_warnings_fix_public_copy
            DescriptionLinkFix.LeaveOut -> Res.string.profile_link_warnings_fix_leave_out
            DescriptionLinkFix.MakePublic -> Res.string.profile_link_warnings_fix_make_public
            DescriptionLinkFix.WidenToServer -> Res.string.profile_link_warnings_fix_widen_to_server
            DescriptionLinkFix.ReplaceLink -> Res.string.profile_link_warnings_fix_replace_link
        }
    )

internal const val RaftDescriptionLinkWarningsTag = "description-link-warnings"
