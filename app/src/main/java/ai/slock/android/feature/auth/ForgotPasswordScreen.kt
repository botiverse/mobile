package ai.slock.android.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.R
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType

enum class ForgotPasswordStatus {
    Default,
    Sending,
    Success,
    Error,
    Disabled
}

@Composable
fun ForgotPasswordScreen(
    modifier: Modifier = Modifier,
    initialEmail: String = "",
    status: ForgotPasswordStatus = ForgotPasswordStatus.Default,
    errorMessage: String? = null,
    onSendResetLink: (String) -> Unit = {},
    onBackToSignIn: () -> Unit = {},
    onOpenSharedAuth: (() -> Unit)? = null
) {
    var email by rememberSaveable(initialEmail) { mutableStateOf(initialEmail) }
    val sending = status == ForgotPasswordStatus.Sending
    val disabled = status == ForgotPasswordStatus.Disabled
    val isError = status == ForgotPasswordStatus.Error

    AuthScaffold(modifier = modifier) {
            AuthBrandBlock(onLogoTripleClick = onOpenSharedAuth)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.auth_reset_title),
                style = SlockType.PageTitle.copy(fontSize = 34.sp, lineHeight = 40.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = stringResource(R.string.auth_reset_description),
                style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 25.sp),
                color = SlockColor.TextBlack
            )
            SlockTextField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.auth_email_label),
                enabled = !sending && !disabled,
                isError = isError,
                errorText = if (isError) errorMessage ?: stringResource(R.string.auth_reset_invalid_email_error) else null,
                forceFocused = status == ForgotPasswordStatus.Default,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            if (status == ForgotPasswordStatus.Success) {
                ForgotPasswordSuccessNotice(email = email.ifBlank { stringResource(R.string.auth_email_placeholder) })
            }
            SlockButton(
                text = if (sending) stringResource(R.string.auth_reset_sending_link) else stringResource(R.string.auth_reset_send_link),
                onClick = { onSendResetLink(email) },
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Pink,
                enabled = email.isNotBlank() && !disabled && status != ForgotPasswordStatus.Success,
                loading = sending
            )
            Text(
                text = stringResource(R.string.auth_back_to_sign_in),
                style = SlockType.Body.copy(
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    textDecoration = TextDecoration.Underline
                ),
                color = SlockColor.TextMutedGray,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(enabled = !sending, onClick = onBackToSignIn)
            )
    }
}

@Composable
private fun ForgotPasswordSuccessNotice(
    email: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.AccentCyan)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = stringResource(R.string.auth_reset_success_title),
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = stringResource(R.string.auth_reset_success_detail, email),
            style = SlockType.Body,
            color = SlockColor.TextBlack
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ForgotPasswordPreview() {
    SlockTheme {
        ForgotPasswordScreen()
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun ForgotPasswordStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                AuthStateCard(
                    title = stringResource(R.string.auth_state_error),
                    modifier = Modifier.weight(1f)
                ) {
                    ForgotPasswordMiniForm(
                        email = "artin",
                        status = ForgotPasswordStatus.Error
                    )
                }
                AuthStateCard(
                    title = stringResource(R.string.auth_state_sending),
                    modifier = Modifier.weight(1f)
                ) {
                    ForgotPasswordMiniForm(
                        email = "artin@slock.ai",
                        status = ForgotPasswordStatus.Sending
                    )
                }
            }
            AuthStateCard(title = stringResource(R.string.auth_state_success)) {
                ForgotPasswordMiniForm(
                    email = "very.long.email.address+中文-team@slock.ai",
                    status = ForgotPasswordStatus.Success
                )
            }
            AuthStateCard(title = stringResource(R.string.auth_state_disabled)) {
                ForgotPasswordMiniForm(
                    email = "locked@slock.ai",
                    status = ForgotPasswordStatus.Disabled
                )
            }
        }
    }
}

@Composable
private fun AuthStateCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = title.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray
        )
        content()
    }
}

@Composable
private fun ForgotPasswordMiniForm(
    email: String,
    status: ForgotPasswordStatus
) {
    val sending = status == ForgotPasswordStatus.Sending
    val disabled = status == ForgotPasswordStatus.Disabled
    val isError = status == ForgotPasswordStatus.Error

    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        SlockTextField(
            value = email,
            onValueChange = {},
            label = stringResource(R.string.auth_email_label),
            placeholder = stringResource(R.string.auth_email_placeholder),
            enabled = !sending && !disabled,
            isError = isError,
            errorText = if (isError) stringResource(R.string.auth_create_invalid_email_short_error) else null,
            forceFocused = status == ForgotPasswordStatus.Default,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        if (status == ForgotPasswordStatus.Success) {
            ForgotPasswordSuccessNotice(email = email)
        }
        SlockButton(
            text = if (sending) stringResource(R.string.auth_reset_sending_link) else stringResource(R.string.auth_reset_send_link),
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Pink,
            enabled = !disabled && email.isNotBlank() && status != ForgotPasswordStatus.Success,
            loading = sending
        )
        Text(
            text = stringResource(R.string.auth_back_to_sign_in),
            style = SlockType.Caption.copy(textDecoration = TextDecoration.Underline),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
