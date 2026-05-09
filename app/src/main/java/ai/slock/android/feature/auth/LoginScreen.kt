package ai.slock.android.feature.auth

import ai.slock.android.R
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
        onLogin: (email: String, password: String) -> Unit,
        modifier: Modifier = Modifier,
        onForgotPassword: () -> Unit = {},
        onCreateAccount: () -> Unit = {},
        onAuthProviderUnavailable: (String) -> Unit = {},
        isSubmitting: Boolean = false,
        errorMessage: String? = null
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !isSubmitting
    val submitLogin = {
        if (canSubmit) {
            focusManager.clearFocus(force = true)
            onLogin(email, password)
        }
    }

    AuthScaffold(modifier = modifier) {
        AuthBrandBlock()
        Spacer(modifier = Modifier.height(22.dp))
        Text(
                text = stringResource(R.string.auth_sign_in),
                style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
                color = SlockColor.TextBlack
        )
        AuthFormColumn {
            AuthTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = stringResource(R.string.auth_email_label),
                    required = true,
                    enabled = !isSubmitting,
                    keyboardOptions =
                            KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                            )
            )
            AuthTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.auth_password_label),
                    required = true,
                    enabled = !isSubmitting,
                    keyboardOptions =
                            KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                            ),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                    onEnterKey = submitLogin,
                    visualTransformation = PasswordVisualTransformation()
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                        text = errorMessage,
                        style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = SlockColor.AccentRed
                )
            }
            AuthActionButton(
                    text =
                            if (isSubmitting) {
                                stringResource(R.string.auth_signing_in)
                            } else {
                                stringResource(R.string.auth_sign_in)
                            },
                    onClick = submitLogin,
                    background =
                            if (isSubmitting) {
                                SlockColor.AccentPink.copy(alpha = 0.72f)
                            } else {
                                SlockColor.AccentPink
                            },
                    contentColor = SlockColor.TextBlack,
                    enabled = canSubmit,
                    loading = isSubmitting,
                    preserveDisabledVisual = isSubmitting
            )
            Box(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .height(SlockStroke.Default)
                                    .background(SlockColor.BorderBlack)
            )
            AuthActionButton(
                    text = stringResource(R.string.auth_continue_google),
                    onClick = { onAuthProviderUnavailable("Google") },
                    background = SlockColor.SurfaceWhite,
                    contentColor = SlockColor.TextBlack,
                    enabled = false,
                    preserveDisabledVisual = true,
                    leadingContent = {
                        Image(
                                painter = painterResource(R.drawable.ic_google),
                                contentDescription = null,
                                modifier = Modifier.height(18.dp)
                        )
                    }
            )
            AuthActionButton(
                    text = stringResource(R.string.auth_continue_github),
                    onClick = { onAuthProviderUnavailable("GitHub") },
                    background = SlockColor.TextBlack,
                    contentColor = SlockColor.SurfaceWhite,
                    enabled = false,
                    preserveDisabledVisual = true,
                    leadingContent = {
                        Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                tint = SlockColor.SurfaceWhite,
                                modifier = Modifier.height(18.dp)
                        )
                    }
            )
        }
        Text(
                text = stringResource(R.string.auth_forgot_password),
                style =
                        SlockType.Body.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                textDecoration = TextDecoration.Underline
                        ),
                color = SlockColor.TextMutedGray,
                modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                                .clickable(onClick = onForgotPassword)
        )
        Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = stringResource(R.string.auth_no_account),
                    style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    color = SlockColor.TextBlack
            )
            Text(
                    text = stringResource(R.string.auth_create_one),
                    style =
                            SlockType.Body.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    lineHeight = 24.sp,
                                    textDecoration = TextDecoration.Underline
                            ),
                    color = SlockColor.AccentPink,
                    modifier = Modifier.clickable(onClick = onCreateAccount)
            )
        }
    }
}

@Composable
private fun ColumnScope.AuthFormColumn(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
) {
    Column(
            modifier =
                    modifier.align(Alignment.CenterHorizontally)
                            .fillMaxWidth()
                            .widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
            content = content
    )
}

@Composable
fun AuthScaffold(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val previousMode = window.attributes.softInputMode
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        onDispose {
            window.setSoftInputMode(previousMode)
        }
    }
    Box(
            modifier =
                    modifier.fillMaxSize()
                            .background(SlockColor.SurfaceCream)
                            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Lg),
            contentAlignment = Alignment.Center
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .widthIn(max = 620.dp)
                                .padding(
                                        end = SlockShadow.Default.offsetX,
                                        bottom = SlockShadow.Default.offsetY
                                )
                                .slockHardShadow(SlockShadow.Default)
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Default, SlockColor.BorderBlack)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 42.dp, vertical = 44.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start,
                content = content
        )
    }
}

@Composable
fun AuthBrandBlock(modifier: Modifier = Modifier) {
    Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
    ) {
        AuthLogoBadge(text = stringResource(R.string.auth_logo))
        Text(
                text = stringResource(R.string.auth_tagline),
                style =
                        SlockType.SectionLabel.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 1.4.sp
                        ),
                color = SlockColor.TextBlack.copy(alpha = 0.52f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AuthLogoBadge(text: String, modifier: Modifier = Modifier) {
    val shadow = SlockShadow.Default
    Box(
            modifier =
                    modifier.padding(end = shadow.offsetX, bottom = shadow.offsetY)
                            .rotate(-2f)
                            .slockHardShadow(shadow)
                            .background(SlockColor.TextBlack)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = 22.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
    ) {
        Text(
                text = text,
                style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
                color = SlockColor.BrandYellow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AuthTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        modifier: Modifier = Modifier,
        placeholder: String = "",
        required: Boolean = false,
        optional: Boolean = false,
        enabled: Boolean = true,
        isError: Boolean = false,
        errorText: String? = null,
        forceFocused: Boolean = false,
        keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
        keyboardActions: KeyboardActions = KeyboardActions.Default,
        onEnterKey: (() -> Unit)? = null,
        visualTransformation: VisualTransformation = VisualTransformation.None
) {
    var isFocused by rememberSaveable { mutableStateOf(false) }
    Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        AuthFieldLabel(label = label, required = required, optional = optional)
        val focused = enabled && (isFocused || forceFocused)
        val shadow = if (focused) SlockShadow.Default else SlockShadow.Small
        val shadowPadding = if (focused) SlockShadow.Default else SlockShadow.Small
        val borderColor = if (isError) SlockColor.AccentRed else SlockColor.BorderBlack
        BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle =
                        SlockType.Body.copy(
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                color = SlockColor.TextBlack
                        ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(SlockColor.TextBlack),
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(
                                        end = shadowPadding.offsetX,
                                        bottom = shadowPadding.offsetY
                                )
                                .slockHardShadow(shadow)
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Default, borderColor)
                                .then(
                                        if (enabled) Modifier
                                        else Modifier.background(SlockColor.EmptyStateGray)
                                )
                                .onFocusChanged { isFocused = it.isFocused }
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .then(
                                        if (onEnterKey == null) {
                                            Modifier
                                        } else {
                                            Modifier.onPreviewKeyEvent { event ->
                                                if (event.key == Key.Enter &&
                                                                event.type == KeyEventType.KeyUp
                                                ) {
                                                    onEnterKey()
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                        }
                                ),
                decorationBox = { innerTextField ->
                    Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            Text(
                                    text = placeholder,
                                    style =
                                            SlockType.Body.copy(
                                                    fontSize = 16.sp,
                                                    lineHeight = 20.sp
                                            ),
                                    color = SlockColor.TextMutedGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
        )
        if (isError && !errorText.isNullOrBlank()) {
            Text(text = errorText, style = SlockType.Caption, color = SlockColor.AccentRed)
        }
    }
}

@Composable
fun AuthFieldLabel(
        label: String,
        modifier: Modifier = Modifier,
        required: Boolean = false,
        optional: Boolean = false
) {
    Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = label,
                style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack
        )
        if (required) {
            Text(
                    text = "*",
                    style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = SlockColor.AccentPink
            )
        } else if (optional) {
            Text(
                    text = stringResource(R.string.auth_optional),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
fun AuthActionButton(
        text: String,
        onClick: () -> Unit,
        background: Color,
        contentColor: Color,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        loading: Boolean = false,
        preserveDisabledVisual: Boolean = false,
        leadingContent: (@Composable () -> Unit)? = null
) {
    val buttonBackground =
            if (enabled || preserveDisabledVisual) background else SlockColor.EmptyStateGray
    Box(
            modifier =
                    modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(buttonBackground)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .clickable(enabled = enabled && !loading, onClick = onClick)
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
    ) {
        Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                Box(
                        modifier = Modifier.padding(end = SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                ) { leadingContent() }
            }
            Text(
                    text = if (loading && !text.endsWith("...")) "$text..." else text,
                    style = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = contentColor,
                    textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LoginScreenPreview() {
    SlockTheme { LoginScreen(onLogin = { _, _ -> }) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LoginScreenFilledPreview() {
    SlockTheme {
        AuthScaffold {
            AuthBrandBlock()
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                    text = stringResource(R.string.auth_sign_in),
                    style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
                    color = SlockColor.TextBlack
            )
            AuthTextField(
                    value = "artin@slock.ai",
                    onValueChange = {},
                    label = stringResource(R.string.auth_email_label),
                    placeholder = stringResource(R.string.auth_email_placeholder)
            )
            AuthTextField(
                    value = "••••••••••••",
                    onValueChange = {},
                    label = stringResource(R.string.auth_password_label),
                    visualTransformation = VisualTransformation.None
            )
            AuthActionButton(
                    text = stringResource(R.string.auth_sign_in),
                    onClick = {},
                    background = SlockColor.AccentPink,
                    contentColor = SlockColor.TextBlack
            )
        }
    }
}
