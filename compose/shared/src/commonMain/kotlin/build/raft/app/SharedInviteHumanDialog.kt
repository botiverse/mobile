package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalReadOnlyValueField
import build.raft.app.components.SlockBrutalSectionLabel
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.ui.unit.sp

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

    SlockUnifiedDialog(
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
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                            .copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_invite_label_join_link), required = false)
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
                        SlockBrutalReadOnlyValueField(
                            value = linkText,
                            modifier = Modifier
                                .weight(1f),
                            fieldMinHeight = 42.dp,
                            horizontalPadding = 8.dp,
                            verticalPadding = 8.dp,
                            backgroundColor = SlockColor.SurfaceWhite,
                            borderColor = SlockColor.BorderBlack,
                            textStyle = SlockType.Caption.copy(fontFamily = SlockMonoFamily),
                            textColor = SlockColor.TextBlack
                        )
                        SlockBrutalIconButton(
                            iconName = if (copied) SlockLucideIconName.Check else SlockLucideIconName.Copy,
                            enabled = !joinLinkLoading && copyableJoinLink != null,
                            backgroundColor = SlockColor.SurfaceWhite,
                            size = 42.dp,
                            iconSize = 16.dp,
                            onClick = { copyableJoinLink?.let(onCopyJoinLink) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SlockLucideIcon(
                            SlockLucideIconName.Link2,
                            modifier = Modifier.size(12.dp),
                            color = SlockColor.TextMutedGrayArgb
                        )
                        Text(
                            text = stringResource(MR.strings.agent_invite_hint_join_link),
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_invite_label_emails), required = false)
                    SlockBrutalInputField(
                        value = email1,
                        onValueChange = { email1 = it },
                        placeholder = stringResource(MR.strings.agent_invite_placeholder_email),
                        enabled = !submitting
                    )
                    SlockBrutalInputField(
                        value = email2,
                        onValueChange = { email2 = it },
                        placeholder = stringResource(MR.strings.agent_invite_label_optional),
                        enabled = !submitting
                    )
                    SlockBrutalInputField(
                        value = email3,
                        onValueChange = { email3 = it },
                        placeholder = stringResource(MR.strings.agent_invite_label_optional),
                        enabled = !submitting
                    )
                }
            }
        }
        SlockDialogFooter {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    enabled = !submitting,
                    backgroundColor = SlockColor.SurfaceWhite,
                    textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label =
                        if (submitting) {
                            stringResource(MR.strings.agent_invite_action_sending)
                        } else {
                            stringResource(MR.strings.agent_invite_action_send)
                        },
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    backgroundColor = SlockColor.AccentRed,
                    leadingIcon = SlockLucideIconName.Mail,
                    textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
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
