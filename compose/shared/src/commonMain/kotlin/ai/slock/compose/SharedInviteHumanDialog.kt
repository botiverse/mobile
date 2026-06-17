package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalSectionLabel
import ai.slock.compose.components.SlockBrutalTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

/**
 * KMP-side invite-human dialog. Mirrors React
 * `third_party/slock-source/packages/web/src/components/member/InviteHumanDialog.tsx`
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
    onCopyJoinLink: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (emails: List<String>) -> Unit
) {
    var email1 by remember { mutableStateOf("") }
    var email2 by remember { mutableStateOf("") }
    var email3 by remember { mutableStateOf("") }
    val emails = listOf(email1, email2, email3)
    val anyFilled = emails.any { it.isNotBlank() }
    val canSubmit = !submitting && anyFilled && !joinLinkLoading

    SlockUnifiedDialog(
        title = "INVITE HUMAN",
        dismissEnabled = !submitting,
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    text = err,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                        .copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SlockBrutalSectionLabel(label = "Join link", required = false)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val linkText = when {
                        joinLinkLoading -> "Preparing join link…"
                        joinLinkUrl.isNullOrBlank() -> "Join link unavailable"
                        else -> joinLinkUrl
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .background(SlockColor.SurfaceWhite)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = linkText,
                            style = SlockType.Caption,
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SlockBrutalActionButton(
                        label = if (copied) "Copied" else "Copy",
                        enabled = !joinLinkLoading && !joinLinkUrl.isNullOrBlank(),
                        backgroundColor = SlockColor.SurfaceWhite,
                        onClick = onCopyJoinLink
                    )
                }
                Text(
                    text = "Anyone with this link can join this server.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SlockBrutalSectionLabel(label = "Emails", required = false)
                SlockBrutalTextField(
                    label = "Email 1",
                    required = false,
                    value = email1,
                    onValueChange = { email1 = it },
                    placeholder = "teammate@example.com",
                    enabled = !submitting
                )
                SlockBrutalTextField(
                    label = "Email 2",
                    required = false,
                    value = email2,
                    onValueChange = { email2 = it },
                    placeholder = "optional",
                    enabled = !submitting
                )
                SlockBrutalTextField(
                    label = "Email 3",
                    required = false,
                    value = email3,
                    onValueChange = { email3 = it },
                    placeholder = "optional",
                    enabled = !submitting
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                SlockBrutalActionButton(
                    label = "Cancel",
                    enabled = !submitting,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label = if (submitting) "Sending..." else "Send invites",
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    backgroundColor = SlockColor.AccentPink,
                    onClick = {
                        onSubmit(emails.map { it.trim() }.filter { it.isNotBlank() })
                    }
                )
            }
        }
    }
}
