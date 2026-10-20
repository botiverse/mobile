package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.SettingsServerJoinLinkSnapshot
import build.raft.app.data.SharedMembersDataSource
import build.raft.app.model.ServerSummary
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.kuiklybase.datetime.Clock
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// region Pure model (web `InviteHumanDialog.tsx`, botiverse/slock staging)

internal enum class InviteHumanTab { Email, Link }

internal const val InviteHumanRoleMember = "member"
internal const val InviteHumanRoleGuest = "guest"

/** Server feature flag that lets an inviter offer the Guest role (`server_guest_v0`). */
internal const val InviteHumanGuestFeatureFlagKey = "server_guest_v0"

internal data class InviteHumanInvitee(
    val id: Int,
    val email: String = "",
    val role: String = InviteHumanRoleMember,
)

/** Link-limit select values: "keep" = the current link's limit, else unlimited/never or a number. */
internal object InviteHumanLinkChoice {
    const val Keep = "keep"
    const val Unlimited = "unlimited"
    const val Never = "never"
    val MaxUses = listOf("1", "5", "10", "25")
    val ExpiryDays = listOf("1", "7", "30")
}

internal data class InviteHumanLinkLimits(val maxUses: Int?, val expiresAt: String?) {
    companion object {
        val None = InviteHumanLinkLimits(maxUses = null, expiresAt = null)

        fun of(link: SettingsServerJoinLinkSnapshot?): InviteHumanLinkLimits =
            InviteHumanLinkLimits(maxUses = link?.maxUses, expiresAt = link?.expiresAt)
    }
}

/** "Keep current" is offered only once the current link already carries that limit. */
internal fun inviteHumanMaxUsesChoices(current: InviteHumanLinkLimits): List<String> =
    listOfNotNull(InviteHumanLinkChoice.Keep.takeIf { current.maxUses != null }) +
        InviteHumanLinkChoice.Unlimited + InviteHumanLinkChoice.MaxUses

internal fun inviteHumanExpiryChoices(current: InviteHumanLinkLimits): List<String> =
    listOfNotNull(InviteHumanLinkChoice.Keep.takeIf { current.expiresAt != null }) +
        InviteHumanLinkChoice.Never + InviteHumanLinkChoice.ExpiryDays

internal fun inviteHumanDefaultMaxUsesChoice(current: InviteHumanLinkLimits): String =
    if (current.maxUses != null) InviteHumanLinkChoice.Keep else InviteHumanLinkChoice.Unlimited

internal fun inviteHumanDefaultExpiryChoice(current: InviteHumanLinkLimits): String =
    if (current.expiresAt != null) InviteHumanLinkChoice.Keep else InviteHumanLinkChoice.Never

/**
 * The limits "Update link" creates the replacement link with. "keep" resolves to the
 * current link's value, so changing one control never loosens the other.
 */
internal fun resolveInviteHumanLinkLimits(
    maxUsesChoice: String,
    expiryChoice: String,
    current: InviteHumanLinkLimits,
    nowEpochMillis: Long,
): InviteHumanLinkLimits =
    InviteHumanLinkLimits(
        maxUses =
            when (maxUsesChoice) {
                InviteHumanLinkChoice.Keep -> current.maxUses
                InviteHumanLinkChoice.Unlimited -> null
                else -> maxUsesChoice.toIntOrNull()
            },
        expiresAt =
            when (expiryChoice) {
                InviteHumanLinkChoice.Keep -> current.expiresAt
                InviteHumanLinkChoice.Never -> null
                else ->
                    expiryChoice.toIntOrNull()?.let { days ->
                        inviteHumanIsoUtc(nowEpochMillis + days * 24L * 60L * 60L * 1000L)
                    }
            },
    )

/** `Date.prototype.toISOString()`: `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC. */
internal fun inviteHumanIsoUtc(epochMillis: Long): String {
    val millisOfDay = epochMillis.mod(86_400_000L)
    val epochDay = epochMillis.floorDiv(86_400_000L)
    // Howard Hinnant's civil_from_days.
    val z = epochDay + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = doy - (153L * mp + 2L) / 5L + 1L
    val month = if (mp < 10L) mp + 3L else mp - 9L
    val year = yoe + era * 400L + if (month <= 2L) 1L else 0L
    fun Long.pad(width: Int) = toString().padStart(width, '0')
    val hours = millisOfDay / 3_600_000L
    val minutes = millisOfDay / 60_000L % 60L
    val seconds = millisOfDay / 1_000L % 60L
    val millis = millisOfDay % 1_000L
    return "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}T${hours.pad(2)}:${minutes.pad(2)}:${seconds.pad(2)}.${millis.pad(3)}Z"
}

/** botiverse/slock `packages/shared` `isValidEmailAddress`. */
internal fun isValidInviteEmailAddress(value: String): Boolean {
    val email = value.trim()
    if (email.isEmpty() || email.length > 254) return false
    if (email.any { it.isWhitespace() }) return false
    val at = email.indexOf('@')
    if (at <= 0 || at != email.lastIndexOf('@')) return false
    val local = email.substring(0, at)
    val domain = email.substring(at + 1)
    if (local.length > 64 || domain.isEmpty()) return false
    if (domain.startsWith(".") || domain.endsWith(".") || !domain.contains(".")) return false
    if (domain.contains("..")) return false
    return Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)
}

/** The non-blank invitees to send, in order; blank rows are ignored like web. */
internal fun inviteHumanTargets(invitees: List<InviteHumanInvitee>): List<InviteHumanInvitee> =
    invitees.map { it.copy(email = it.email.trim()) }.filter { it.email.isNotEmpty() }

// endregion

private const val DefaultInviteHumanWebUrl = "https://app.raft.build"

internal fun normalizeInviteHumanJoinUrl(rawUrl: String?, webBaseUrl: String? = null): String? {
    val trimmed = rawUrl?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val baseUrl = webBaseUrl?.trim()?.trimEnd('/').orEmpty().ifBlank { DefaultInviteHumanWebUrl }
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("/") -> "$baseUrl$trimmed"
        else -> "$baseUrl/$trimmed"
    }
}

internal fun inviteHumanJoinUrlCopyPayload(rawUrl: String?, webBaseUrl: String? = null): String? =
    normalizeInviteHumanJoinUrl(rawUrl, webBaseUrl)

/**
 * Whether the server switcher shows "Invite Human". Web `ServerSwitcherMenu`
 * gates the entry on `capabilities.inviteMembers` (owner/admin), the same
 * capability the server enforces on `/servers/:id/invites` and join links.
 */
internal fun serverSwitcherShowsInviteHuman(server: ServerSummary?): Boolean =
    server?.permissions?.canInviteMember == true

/**
 * The Invite Human dialog, opened by the Members humans "+" and the server switcher's
 * "Invite Human". Mirrors web `packages/web/src/components/member/InviteHumanDialog.tsx`:
 * the agent-reach notice, an Email / Link segmented switch, per-row emails (with a
 * Member / Guest role when [canOfferGuest]) sent through `POST /servers/{id}/invites`,
 * and the shareable link with its usage cap / expiry, Update (revoke + create) and
 * Revoke (`GET/POST/DELETE /servers/{id}/join-links`). The link is built from the
 * Developer environment web URL ([loadWebBaseUrl]).
 */
@Composable
internal fun SharedServerInviteHumanDialogHost(
    serverId: String,
    membersDataSource: SharedMembersDataSource,
    canOfferGuest: Boolean,
    loadWebBaseUrl: suspend () -> String?,
    copyToPasteboard: (String) -> Unit,
    onDismiss: () -> Unit,
    nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val scope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val failedPrepareLink = stringResource(MR.strings.invite_human_failed_prepare_link)
    val failedSend = stringResource(MR.strings.invite_human_failed_send)
    val enterEmailOrCopyLink = stringResource(MR.strings.invite_human_enter_email_or_copy_link)
    val invalidEmail = stringResource(MR.strings.invite_human_invalid_email)
    val manageInvitesForbidden = stringResource(MR.strings.member_error_manage_invites_forbidden)
    val sendInvitesForbidden = stringResource(MR.strings.member_error_send_invites_forbidden)

    var tab by remember(serverId) { mutableStateOf(InviteHumanTab.Email) }
    var invitees by remember(serverId) { mutableStateOf(listOf(InviteHumanInvitee(id = 0))) }
    var nextInviteeId by remember(serverId) { mutableStateOf(1) }
    var webBaseUrl by remember(serverId) { mutableStateOf<String?>(null) }
    var joinLink by remember(serverId) { mutableStateOf<SettingsServerJoinLinkSnapshot?>(null) }
    // Stable snapshot of the current link's limits: only a load, a successful create or a
    // revoke reseeds it, so a retry after a failed create resends the chosen limits.
    var linkLimits by remember(serverId) { mutableStateOf(InviteHumanLinkLimits.None) }
    var maxUsesChoice by remember(serverId) { mutableStateOf(InviteHumanLinkChoice.Unlimited) }
    var expiryChoice by remember(serverId) { mutableStateOf(InviteHumanLinkChoice.Never) }
    var linkLoading by remember(serverId) { mutableStateOf(true) }
    var regenerating by remember(serverId) { mutableStateOf(false) }
    var confirmingRevoke by remember(serverId) { mutableStateOf(false) }
    var copied by remember(serverId) { mutableStateOf(false) }
    var sending by remember(serverId) { mutableStateOf(false) }
    var error by remember(serverId) { mutableStateOf<String?>(null) }

    fun adoptLink(link: SettingsServerJoinLinkSnapshot?) {
        joinLink = link
        val limits = InviteHumanLinkLimits.of(link)
        linkLimits = limits
        maxUsesChoice = inviteHumanDefaultMaxUsesChoice(limits)
        expiryChoice = inviteHumanDefaultExpiryChoice(limits)
    }

    fun <T> DataResult<T>.failureMessage(fallback: String, forbidden: String): String? =
        when (this) {
            is DataResult.Content -> null
            is DataResult.NoPermission -> forbidden
            is DataResult.Error -> "$fallback: ${this.error.userFacingMessage(errorStrings)}"
            is DataResult.Empty -> fallback
        }

    LaunchedEffect(serverId, membersDataSource) {
        linkLoading = true
        error = null
        webBaseUrl = loadWebBaseUrl()
        val existing = (membersDataSource.loadJoinLinks(serverId) as? DataResult.Content)?.data?.firstOrNull()
        if (existing != null) {
            adoptLink(existing)
        } else {
            val created = membersDataSource.ensureJoinLink(serverId)
            if (created is DataResult.Content) {
                adoptLink(created.data)
            } else {
                error = created.failureMessage(failedPrepareLink, manageInvitesForbidden)
            }
        }
        linkLoading = false
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val joinUrl = normalizeInviteHumanJoinUrl(joinLink?.url, webBaseUrl)

    fun regenerateLink() {
        if (regenerating) return
        regenerating = true
        error = null
        val target = resolveInviteHumanLinkLimits(maxUsesChoice, expiryChoice, linkLimits, nowEpochMillis())
        scope.launch {
            val current = joinLink
            if (current != null) {
                val revoked = membersDataSource.revokeJoinLink(serverId, current.id)
                val revokeError = revoked.failureMessage(failedPrepareLink, manageInvitesForbidden)
                if (revokeError != null) {
                    error = revokeError
                    regenerating = false
                    return@launch
                }
                // Hide the revoked token but keep the draft for a retry.
                joinLink = null
            }
            val created = membersDataSource.createJoinLink(serverId, target.maxUses, target.expiresAt)
            if (created is DataResult.Content) {
                adoptLink(created.data)
                copied = false
            } else {
                error = created.failureMessage(failedPrepareLink, manageInvitesForbidden)
            }
            regenerating = false
        }
    }

    fun revokeLink() {
        val current = joinLink ?: return
        if (regenerating) return
        regenerating = true
        error = null
        scope.launch {
            val revoked = membersDataSource.revokeJoinLink(serverId, current.id)
            val revokeError = revoked.failureMessage(failedPrepareLink, manageInvitesForbidden)
            if (revokeError == null) {
                adoptLink(null)
                copied = false
            } else {
                error = revokeError
            }
            regenerating = false
            confirmingRevoke = false
        }
    }

    fun submit() {
        val targets = inviteHumanTargets(invitees)
        if (targets.isEmpty()) {
            error = enterEmailOrCopyLink
            return
        }
        if (targets.any { !isValidInviteEmailAddress(it.email) }) {
            error = invalidEmail
            return
        }
        error = null
        sending = true
        scope.launch {
            val results = targets.map { membersDataSource.sendInvite(serverId, it.email, it.role) }
            val failure =
                results.firstOrNull { it !is DataResult.Content }
                    ?.failureMessage(failedSend, sendInvitesForbidden)
            sending = false
            if (failure == null) onDismiss() else error = failure
        }
    }

    RaftTheme(appearance = if (RaftAppThemeState.isDark) RaftAppearance.Dark else RaftAppearance.Light) {
        InviteHumanDialogContent(
            tab = tab,
            onTabChange = { tab = it },
            error = error,
            invitees = invitees,
            canOfferGuest = canOfferGuest,
            onInviteeChange = { updated ->
                invitees = invitees.map { if (it.id == updated.id) updated else it }
            },
            onAddInvitee = {
                invitees = invitees + InviteHumanInvitee(id = nextInviteeId)
                nextInviteeId += 1
            },
            onRemoveInvitee = { id -> invitees = invitees.filterNot { it.id == id } },
            joinUrl = joinUrl,
            joinLink = joinLink,
            linkLoading = linkLoading,
            regenerating = regenerating,
            copied = copied,
            onCopy = {
                joinUrl?.let(copyToPasteboard)
                copied = true
            },
            linkLimits = linkLimits,
            maxUsesChoice = maxUsesChoice,
            onMaxUsesChoice = { maxUsesChoice = it },
            expiryChoice = expiryChoice,
            onExpiryChoice = { expiryChoice = it },
            onRegenerate = ::regenerateLink,
            onRevoke = { confirmingRevoke = true },
            sending = sending,
            onSubmit = ::submit,
            onDismiss = { if (!sending) onDismiss() },
        )
        if (confirmingRevoke) {
            RaftDialog(
                onDismissRequest = { if (!regenerating) confirmingRevoke = false },
                title = stringResource(MR.strings.invite_human_revoke_confirm_title),
                description = stringResource(MR.strings.invite_human_revoke_confirm_message),
                size = RaftDialogSize.Sm,
                closeLabel = stringResource(MR.strings.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(MR.strings.common_cancel),
                        onClick = { confirmingRevoke = false },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = !regenerating,
                    )
                    RaftButton(
                        text = stringResource(MR.strings.invite_human_revoke_confirm_label),
                        onClick = ::revokeLink,
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                        loading = regenerating,
                        loadingLabel = stringResource(MR.strings.invite_human_revoking),
                    )
                },
            )
        }
    }
}

@Composable
private fun InviteHumanDialogContent(
    tab: InviteHumanTab,
    onTabChange: (InviteHumanTab) -> Unit,
    error: String?,
    invitees: List<InviteHumanInvitee>,
    canOfferGuest: Boolean,
    onInviteeChange: (InviteHumanInvitee) -> Unit,
    onAddInvitee: () -> Unit,
    onRemoveInvitee: (Int) -> Unit,
    joinUrl: String?,
    joinLink: SettingsServerJoinLinkSnapshot?,
    linkLoading: Boolean,
    regenerating: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    linkLimits: InviteHumanLinkLimits,
    maxUsesChoice: String,
    onMaxUsesChoice: (String) -> Unit,
    expiryChoice: String,
    onExpiryChoice: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRevoke: () -> Unit,
    sending: Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val linkChoiceLabels = inviteHumanLinkChoiceLabels()
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(MR.strings.agent_invite_title),
        size = RaftDialogSize.Lg,
        // A form: a stray tap outside must not lose the typed emails.
        dismissOnClickOutside = false,
        closeLabel = stringResource(MR.strings.common_close),
        footer = {
            RaftButton(
                text = stringResource(MR.strings.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            if (tab == InviteHumanTab.Email) {
                RaftButton(
                    text = stringResource(MR.strings.invite_human_send_invites),
                    onClick = onSubmit,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    loading = sending,
                    loadingLabel = stringResource(MR.strings.invite_human_sending),
                    leadingIcon = { InviteHumanIcon(RaftLucideIconName.Mail, 18.dp) },
                )
            }
        },
    ) {
        if (!error.isNullOrBlank()) {
            RaftBanner(description = error, status = RaftBannerStatus.Warning)
        }
        RaftBanner(
            description = stringResource(MR.strings.invite_human_agent_reach_hint),
            status = RaftBannerStatus.Info,
        )
        RaftSegmentedControl(Modifier.fillMaxWidth()) {
            RaftSegment(
                text = stringResource(MR.strings.invite_human_tab_email),
                selected = tab == InviteHumanTab.Email,
                onClick = { onTabChange(InviteHumanTab.Email) },
                modifier = Modifier.weight(1f),
            )
            RaftSegment(
                text = stringResource(MR.strings.invite_human_tab_link),
                selected = tab == InviteHumanTab.Link,
                onClick = { onTabChange(InviteHumanTab.Link) },
                modifier = Modifier.weight(1f),
            )
        }
        when (tab) {
            InviteHumanTab.Email ->
                RaftField(label = stringResource(MR.strings.invite_human_by_email)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        invitees.forEachIndexed { index, invitee ->
                            InviteHumanInviteeRow(
                                invitee = invitee,
                                index = index,
                                canOfferGuest = canOfferGuest,
                                canRemove = invitees.size > 1,
                                enabled = !sending,
                                onChange = onInviteeChange,
                                onRemove = { onRemoveInvitee(invitee.id) },
                            )
                        }
                        RaftButton(
                            text = stringResource(MR.strings.invite_human_add_invitee),
                            onClick = onAddInvitee,
                            variant = RaftButtonVariant.Ghost,
                            enabled = !sending,
                            leadingIcon = { InviteHumanIcon(RaftLucideIconName.Plus, 16.dp) },
                        )
                    }
                }
            InviteHumanTab.Link -> {
                RaftField(label = stringResource(MR.strings.invite_human_link_label)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftTextField(
                            value =
                                when {
                                    linkLoading -> stringResource(MR.strings.invite_human_preparing_link)
                                    joinUrl == null -> stringResource(MR.strings.invite_human_link_unavailable)
                                    else -> joinUrl
                                },
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.weight(1f),
                        )
                        RaftIconButton(
                            onClick = onCopy,
                            tooltip = stringResource(MR.strings.invite_human_copy_link),
                            size = RaftIconButtonSize.Lg,
                            enabled = joinUrl != null && !linkLoading && !regenerating,
                        ) {
                            InviteHumanIcon(if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy, 20.dp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftField(
                        label = stringResource(MR.strings.invite_human_max_uses_label),
                        modifier = Modifier.weight(1f),
                    ) {
                        RaftSelect(
                            value = maxUsesChoice,
                            options = inviteHumanMaxUsesChoices(linkLimits),
                            onValueChange = onMaxUsesChoice,
                            optionLabel = linkChoiceLabels::maxUses,
                        )
                    }
                    RaftField(
                        label = stringResource(MR.strings.invite_human_expires_label),
                        modifier = Modifier.weight(1f),
                    ) {
                        RaftSelect(
                            value = expiryChoice,
                            options = inviteHumanExpiryChoices(linkLimits),
                            onValueChange = onExpiryChoice,
                            optionLabel = linkChoiceLabels::expiry,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftButton(
                        text = stringResource(MR.strings.invite_human_apply_link_options),
                        onClick = onRegenerate,
                        enabled = !regenerating && !linkLoading,
                        loading = regenerating,
                    )
                    if (joinLink != null) {
                        RaftTextLink(
                            text = stringResource(MR.strings.invite_human_revoke_link),
                            onClick = onRevoke,
                            enabled = !regenerating,
                            style = typography.footnote,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (joinLink != null) {
                        RaftText(
                            stringResource(MR.strings.invite_human_update_replaces_hint),
                            style = typography.caption,
                            color = colors.foregroundHint,
                        )
                        inviteHumanLinkStatus(joinLink)?.let { status ->
                            RaftText(status, style = typography.caption, color = colors.foregroundHint)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InviteHumanIcon(RaftLucideIconName.Link2, 14.dp, colors.foregroundHint)
                        RaftText(
                            stringResource(MR.strings.invite_human_send_link_hint),
                            style = typography.caption,
                            color = colors.foregroundHint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteHumanInviteeRow(
    invitee: InviteHumanInvitee,
    index: Int,
    canOfferGuest: Boolean,
    canRemove: Boolean,
    enabled: Boolean,
    onChange: (InviteHumanInvitee) -> Unit,
    onRemove: () -> Unit,
) {
    val roleMember = stringResource(MR.strings.invite_human_role_member)
    val roleGuest = stringResource(MR.strings.invite_human_role_guest)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftTextField(
            value = invitee.email,
            onValueChange = { onChange(invitee.copy(email = it)) },
            placeholder = "name@company.com",
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        if (canOfferGuest) {
            RaftSelect(
                value = invitee.role,
                options = listOf(InviteHumanRoleMember, InviteHumanRoleGuest),
                onValueChange = { onChange(invitee.copy(role = it)) },
                optionLabel = { if (it == InviteHumanRoleGuest) roleGuest else roleMember },
                label = stringResource(MR.strings.invite_human_role_label),
                enabled = enabled,
                modifier = Modifier.width(112.dp),
            )
        }
        if (canRemove) {
            RaftIconButton(
                onClick = onRemove,
                tooltip = stringResource(MR.strings.invite_human_remove_invitee, index + 1),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg,
                enabled = enabled,
            ) {
                InviteHumanIcon(RaftLucideIconName.Trash2, 18.dp)
            }
        }
    }
}

/** Localized labels for the link usage-cap / expiry select values. */
private class InviteHumanLinkChoiceLabels(
    private val keepCurrent: String,
    private val unlimited: String,
    private val never: String,
    private val days: Map<String, String>,
) {
    fun maxUses(choice: String): String =
        when (choice) {
            InviteHumanLinkChoice.Keep -> keepCurrent
            InviteHumanLinkChoice.Unlimited -> unlimited
            else -> choice
        }

    fun expiry(choice: String): String =
        when (choice) {
            InviteHumanLinkChoice.Keep -> keepCurrent
            InviteHumanLinkChoice.Never -> never
            else -> days[choice] ?: choice
        }
}

@Composable
private fun inviteHumanLinkChoiceLabels(): InviteHumanLinkChoiceLabels =
    InviteHumanLinkChoiceLabels(
        keepCurrent = stringResource(MR.strings.invite_human_keep_current),
        unlimited = stringResource(MR.strings.invite_human_unlimited),
        never = stringResource(MR.strings.invite_human_never),
        days =
            InviteHumanLinkChoice.ExpiryDays.associateWith { choice ->
                val count = choice.toInt()
                if (count == 1) {
                    stringResource(MR.strings.invite_human_days_one, count)
                } else {
                    stringResource(MR.strings.invite_human_days_many, count)
                }
            },
    )

/** "Used 2/5 · Expires 2026-11-01" for a limited link, else null (web `invite-link-status`). */
@Composable
private fun inviteHumanLinkStatus(link: SettingsServerJoinLinkSnapshot): String? {
    val used = link.maxUses?.let { max -> stringResource(MR.strings.invite_human_used_of, link.useCount ?: 0, max) }
    val expires = link.expiresAt?.let { stringResource(MR.strings.invite_human_expires_on, it.substringBefore('T')) }
    return listOfNotNull(used, expires).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** A Lucide icon in the enclosing design-system slot's ink. */
@Composable
private fun InviteHumanIcon(
    name: RaftLucideIconName,
    size: Dp,
    color: androidx.compose.ui.graphics.Color = LocalRaftContentColor.current,
) {
    val ink = color.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}
