package build.raft.app

import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalInputField
import build.raft.app.components.RaftBrutalReadOnlyValueField
import build.raft.app.components.RaftBrutalSectionLabel
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.ui.unit.sp

/**
 * KMP-side invite-human dialog. Mirrors React
 * external `<slock-source>/packages/web/src/components/member/InviteHumanDialog.tsx`
 * (task #43 spec).
 *
 * Flow:
 *   1. On open, fetches the server's existing join links. If any exist,
 *      shows the first one's URL. Otherwise creates a new join link
 *      with no expiry / no max-uses.
 *   2. Three email input rows (matches React v0 fixed-three-row layout).
 *   3. Submit validates emails, then POSTs `/servers/{id}/invites` per
 *      non-empty email. On success, closes the dialog and the caller
 *      decides whether to navigate (React navigates to settings/server;
 *      KMP caller can do its own post-success navigation).
 *
 * State machine:
 *   - linkLoading → joined URL set or error
 *   - sending → success (caller closes) or error (retryable)
 *   - localError → blocks submit and shows banner
 *
 * Permission: handled upstream by the data source
 * (`SettingsRepository.listServerJoinLinks` / `createServerJoinLinkForInvite` /
 * `createServerInvite` all gate on `canManageServer`); the dialog
 * just surfaces NoPermission as a visible error.
 */
@Composable
internal fun SharedInviteHumanDialog(
    submitting: Boolean,
    errorMessage: String?,
    joinLinkLoading: Boolean,
    joinLinkUrl: String?,
    copied: Boolean,
    onCopyJoinLink: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (emails: List<String>) -> Unit
) {
    // Caller-local TextFieldValue is the sole editor owner for each email box;
    // the submit path keeps a plain String projection (task #130 Phase D).
    var email1 by remember { mutableStateOf(TextFieldValue()) }
    var email2 by remember { mutableStateOf(TextFieldValue()) }
    var email3 by remember { mutableStateOf(TextFieldValue()) }
    val emails = listOf(email1.text, email2.text, email3.text)
    val anyFilled = emails.any { it.isNotBlank() }
    val canSubmit = !submitting && anyFilled && !joinLinkLoading
    val copyableJoinLink = inviteHumanJoinUrlCopyPayload(joinLinkUrl)

    RaftUnifiedDialog(
        title = stringResource(MR.strings.agent_invite_title),
        dismissEnabled = !submitting,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                item {
                    Text(
                        text = err,
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold)
                            .copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.AccentRed),
                        color = RaftColor.AccentRed
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftBrutalSectionLabel(label = stringResource(MR.strings.agent_invite_label_join_link), required = false)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val linkText = when {
                            joinLinkLoading -> stringResource(MR.strings.agent_invite_state_join_link_loading)
                            copyableJoinLink == null -> stringResource(MR.strings.agent_invite_state_join_link_unavailable)
                            else -> copyableJoinLink
                        }
                        RaftBrutalReadOnlyValueField(
                            value = linkText,
                            modifier = Modifier
                                .weight(1f),
                            fieldMinHeight = 42.dp,
                            horizontalPadding = 8.dp,
                            verticalPadding = 8.dp,
                            backgroundColor = RaftColor.SurfaceWhite,
                            borderColor = RaftColor.BorderBlack,
                            textStyle = RaftType.Caption.copy(fontFamily = RaftMonoFamily),
                            textColor = RaftColor.TextBlack
                        )
                        RaftBrutalIconButton(
                            iconName = if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                            enabled = !joinLinkLoading && copyableJoinLink != null,
                            backgroundColor = RaftColor.SurfaceWhite,
                            size = 42.dp,
                            iconSize = 16.dp,
                            onClick = { copyableJoinLink?.let(onCopyJoinLink) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RaftLucideIcon(
                            RaftLucideIconName.Link2,
                            modifier = Modifier.size(12.dp),
                            color = RaftColor.TextMutedGrayArgb
                        )
                        Text(
                            text = stringResource(MR.strings.agent_invite_hint_join_link),
                            style = RaftType.Caption,
                            color = RaftColor.TextMutedGray
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftBrutalSectionLabel(label = stringResource(MR.strings.agent_invite_label_emails), required = false)
                    RaftBrutalInputField(
                        value = email1,
                        onValueChange = { email1 = it },
                        placeholder = stringResource(MR.strings.agent_invite_placeholder_email),
                        enabled = !submitting
                    )
                    RaftBrutalInputField(
                        value = email2,
                        onValueChange = { email2 = it },
                        placeholder = stringResource(MR.strings.agent_invite_label_optional),
                        enabled = !submitting
                    )
                    RaftBrutalInputField(
                        value = email3,
                        onValueChange = { email3 = it },
                        placeholder = stringResource(MR.strings.agent_invite_label_optional),
                        enabled = !submitting
                    )
                }
            }
        }
        RaftDialogFooter {
                RaftBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    enabled = !submitting,
                    backgroundColor = RaftColor.SurfaceWhite,
                    textStyle = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    onClick = onDismiss
                )
                RaftBrutalActionButton(
                    label =
                        if (submitting) {
                            stringResource(MR.strings.agent_invite_action_sending)
                        } else {
                            stringResource(MR.strings.agent_invite_action_send)
                        },
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    // Web InviteHumanDialog sends with the pink primary action.
                    backgroundColor = RaftColor.AccentPink,
                    leadingIcon = RaftLucideIconName.Mail,
                    textStyle = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    onClick = {
                        onSubmit(emails.map { it.trim() }.filter { it.isNotBlank() })
                    }
                )
            }
        }
    }

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
