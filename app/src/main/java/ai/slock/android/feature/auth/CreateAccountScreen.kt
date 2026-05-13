package ai.slock.android.feature.auth

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.R
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType

enum class CreateAccountStatus {
    Default,
    Saving,
    Error,
    Disabled
}

@Composable
fun CreateAccountScreen(
    modifier: Modifier = Modifier,
    initialName: String = "",
    initialEmail: String = "",
    status: CreateAccountStatus = CreateAccountStatus.Default,
    errorMessage: String? = null,
    onCreateAccount: (name: String, email: String, password: String) -> Unit = { _, _, _ -> },
    onBackToSignIn: () -> Unit = {},
    onOpenSharedAuth: (() -> Unit)? = null
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var email by rememberSaveable(initialEmail) { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    val saving = status == CreateAccountStatus.Saving
    val disabled = status == CreateAccountStatus.Disabled
    val isError = status == CreateAccountStatus.Error
    val formEnabled = !saving && !disabled

    AuthScaffold(modifier = modifier) {
        AuthBrandBlock()
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.auth_create_title),
            style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
            color = SlockColor.TextBlack
        )
        Text(
            text = stringResource(R.string.auth_create_description),
            style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp),
            color = SlockColor.TextBlack
        )
        RequiredAuthTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.auth_name_label),
            placeholder = stringResource(R.string.auth_name_placeholder),
            forceFocused = status == CreateAccountStatus.Default,
            enabled = formEnabled,
            isError = isError && name.isBlank(),
            errorText = if (isError && name.isBlank()) stringResource(R.string.auth_create_name_required_error) else null
        )
        RequiredAuthTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(R.string.auth_email_label),
            placeholder = stringResource(R.string.auth_email_placeholder),
            enabled = formEnabled,
            isError = isError,
            errorText = if (isError) stringResource(R.string.auth_create_invalid_email_error) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        RequiredAuthTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.auth_password_label),
            placeholder = stringResource(R.string.auth_password_placeholder_min),
            enabled = formEnabled,
            isError = isError && password.length in 1..7,
            errorText = if (isError && password.length in 1..7) stringResource(R.string.auth_create_password_length_error) else null,
            visualTransformation = PasswordVisualTransformation()
        )
        Text(
            text = stringResource(R.string.auth_create_password_helper),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        if (isError && !errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = SlockColor.AccentRed
            )
        }
        AuthActionButton(
            text = if (saving) stringResource(R.string.auth_create_creating_account) else stringResource(R.string.auth_create_title),
            onClick = { onCreateAccount(name, email, password) },
            background = SlockColor.AccentPink,
            contentColor = SlockColor.TextBlack,
            enabled = formEnabled && name.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
            loading = saving
        )
        if (onOpenSharedAuth != null) {
            AuthActionButton(
                text = stringResource(R.string.auth_open_shared_auth),
                onClick = onOpenSharedAuth,
                background = SlockColor.AccentCyan,
                contentColor = SlockColor.TextBlack,
                enabled = !saving
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.auth_already_have_account),
                style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = stringResource(R.string.auth_sign_in_link),
                style = SlockType.Body.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    textDecoration = TextDecoration.Underline
                ),
                color = SlockColor.AccentPink,
                modifier = Modifier.clickable(enabled = !saving, onClick = onBackToSignIn)
            )
        }
    }
}

@Composable
private fun RequiredAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    forceFocused: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    AuthTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        required = true,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        errorText = errorText,
        forceFocused = forceFocused,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun CreateAccountPreview() {
    SlockTheme {
        CreateAccountScreen()
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun CreateAccountStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            AuthStateCard(title = stringResource(R.string.auth_state_error)) {
                CreateAccountMiniForm(status = CreateAccountStatus.Error)
            }
            AuthStateCard(title = stringResource(R.string.auth_state_focused)) {
                CreateAccountMiniForm(status = CreateAccountStatus.Default)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                AuthStateCard(
                    title = stringResource(R.string.auth_state_saving),
                    modifier = Modifier.weight(1f)
                ) {
                    CreateAccountMiniForm(status = CreateAccountStatus.Saving)
                }
                AuthStateCard(
                    title = stringResource(R.string.auth_state_disabled),
                    modifier = Modifier.weight(1f)
                ) {
                    CreateAccountMiniForm(status = CreateAccountStatus.Disabled)
                }
            }
        }
    }
}

@Composable
private fun AuthStateCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
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
private fun CreateAccountMiniForm(status: CreateAccountStatus) {
    val saving = status == CreateAccountStatus.Saving
    val disabled = status == CreateAccountStatus.Disabled

    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        RequiredAuthTextField(
            value = "Very Long Name With 中文 Mixed",
            onValueChange = {},
            label = stringResource(R.string.auth_name_label),
            placeholder = stringResource(R.string.auth_name_placeholder),
            enabled = !saving && !disabled,
            forceFocused = status == CreateAccountStatus.Default
        )
        RequiredAuthTextField(
            value = if (status == CreateAccountStatus.Error) "not-an-email" else "new@slock.ai",
            onValueChange = {},
            label = stringResource(R.string.auth_email_label),
            placeholder = stringResource(R.string.auth_email_placeholder),
            enabled = !saving && !disabled,
            isError = status == CreateAccountStatus.Error,
            errorText = if (status == CreateAccountStatus.Error) stringResource(R.string.auth_create_invalid_email_short_error) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        AuthActionButton(
            text = if (saving) stringResource(R.string.auth_create_creating_account) else stringResource(R.string.auth_create_title),
            onClick = {},
            background = SlockColor.AccentPink,
            contentColor = SlockColor.TextBlack,
            enabled = !disabled,
            loading = saving
        )
        if (disabled) {
            Text(
                text = stringResource(R.string.auth_create_disabled_sample),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        Text(
            text = stringResource(R.string.auth_already_have_account_sign_in),
            style = SlockType.Caption.copy(textAlign = TextAlign.Center),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
