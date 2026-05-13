package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.slock.compose.base.Utils
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Brush
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val LOGIN_PAGE = "compose_login"
private const val REGISTER_PAGE = "compose_register"
private const val FORGOT_PASSWORD_PAGE = "compose_forgot_password"

private val SurfaceCream = Color(0xFFFFF6E6)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val TextBlack = Color(0xFF000000)
private val TextMuted = Color(0xFF777777)
private val BrandYellow = Color(0xFFFFD84D)
private val AccentPink = Color(0xFFFF7AB6)
private val AccentCyan = Color(0xFF35C5D8)
private val Disabled = Color(0xFFE8E3DA)

@Page(LOGIN_PAGE, supportInLocal = true)
internal class ComposeLoginPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { LoginScreen() }
    }
}

@Page(REGISTER_PAGE, supportInLocal = true)
internal class ComposeRegisterPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RegisterScreen() }
    }
}

@Page(FORGOT_PASSWORD_PAGE, supportInLocal = true)
internal class ComposeForgotPasswordPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ForgotPasswordScreen() }
    }
}

@Composable
private fun LoginScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var status by remember { mutableStateOf("") }

    AuthFrame {
        AuthBrand()
        Spacer(Modifier.height(78.dp))
        Text(
            text = "Sign in",
            color = TextBlack,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        AuthTextField(
            label = "Email",
            value = state.email,
            required = true,
            enabled = !state.submitting,
            onValueChange = { state = reducer.reduce(state, LoginFormEvent.EmailChanged(it)).state }
        )
        AuthTextField(
            label = "Password",
            value = state.password,
            required = true,
            enabled = !state.submitting,
            onValueChange = { state = reducer.reduce(state, LoginFormEvent.PasswordChanged(it)).state }
        )
        AuthError(state.errorMessage)
        AuthButton(
            text = if (state.submitting) "Signing in..." else "Sign in",
            enabled = state.canSubmit,
            background = Disabled
        ) {
            val update = reducer.reduce(state, LoginFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is LoginFormEffect.SubmitLogin -> {
                    status = "submit ${effect.email}"
                    Utils.bridgeModule(pager.pagerId).toast("KMP login submit: ${effect.email}")
                    state = state.copy(submitting = false)
                }
                LoginFormEffect.FocusEmail -> status = "validation required"
                null -> Unit
            }
        }
        AuthDivider()
        AuthTextLink("Forgot password?") { pager.openAuthPage(FORGOT_PASSWORD_PAGE) }
        AuthInlineAction("No account?", "Create one") { pager.openAuthPage(REGISTER_PAGE) }
        AuthStatus(status.ifBlank { "Ready for Harmony render smoke test" })
    }
}

@Composable
private fun RegisterScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val reducer = remember { CreateAccountFormReducer() }
    var state by remember { mutableStateOf(CreateAccountFormState()) }
    var status by remember { mutableStateOf("") }

    AuthFrame {
        AuthBrand()
        Spacer(Modifier.height(64.dp))
        Text("Create account", color = TextBlack, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Set up a Slock workspace identity for human and AI collaboration.",
            color = TextBlack,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        AuthTextField("Name", state.name, required = true, onValueChange = {
            state = reducer.reduce(state, CreateAccountFormEvent.NameChanged(it)).state
        })
        AuthTextField("Email", state.email, required = true, onValueChange = {
            state = reducer.reduce(state, CreateAccountFormEvent.EmailChanged(it)).state
        })
        AuthTextField("Password", state.password, required = true, onValueChange = {
            state = reducer.reduce(state, CreateAccountFormEvent.PasswordChanged(it)).state
        })
        Text("Use at least 8 characters.", color = TextMuted, fontSize = 13.sp)
        AuthError(state.errorMessage)
        AuthButton(
            text = if (state.submitting) "Creating account..." else "Create account",
            enabled = state.canSubmit,
            background = AccentPink
        ) {
            val update = reducer.reduce(state, CreateAccountFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is CreateAccountFormEffect.SubmitCreateAccount -> {
                    status = "create ${effect.email}"
                    Utils.bridgeModule(pager.pagerId).toast("KMP create account: ${effect.email}")
                    state = state.copy(submitting = false)
                }
                CreateAccountFormEffect.FocusName -> status = "validation required"
                null -> Unit
            }
        }
        AuthInlineAction("Already have an account?", "Sign in") { pager.openAuthPage(LOGIN_PAGE) }
        AuthStatus(status.ifBlank { "Ready for create account smoke test" })
    }
}

@Composable
private fun ForgotPasswordScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val reducer = remember { ForgotPasswordFormReducer() }
    var state by remember { mutableStateOf(ForgotPasswordFormState()) }
    var status by remember { mutableStateOf("") }

    AuthFrame {
        AuthBrand()
        Spacer(Modifier.height(64.dp))
        Text("Reset password", color = TextBlack, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Enter the email linked to your Slock account. We will send a secure reset link you can open on this device.",
            color = TextBlack,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        AuthTextField("Email", state.email, required = true, enabled = !state.sent, onValueChange = {
            state = reducer.reduce(state, ForgotPasswordFormEvent.EmailChanged(it)).state
        })
        AuthError(state.errorMessage)
        if (state.sent) {
            Text(
                text = "Reset link queued for ${state.normalizedEmail}.",
                color = TextBlack,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(AccentCyan).padding(12.dp)
            )
        }
        AuthButton(
            text = if (state.submitting) "Sending link..." else "Send reset link",
            enabled = state.canSubmit,
            background = AccentPink
        ) {
            val update = reducer.reduce(state, ForgotPasswordFormEvent.Submit)
            state = update.state
            when (val effect = update.effect) {
                is ForgotPasswordFormEffect.SubmitForgotPassword -> {
                    status = "reset ${effect.email}"
                    Utils.bridgeModule(pager.pagerId).toast("KMP reset password: ${effect.email}")
                    state = state.copy(submitting = false, sent = true)
                }
                ForgotPasswordFormEffect.FocusEmail -> status = "validation required"
                null -> Unit
            }
        }
        AuthTextLink("Back to sign in") { pager.openAuthPage(LOGIN_PAGE) }
        AuthStatus(status.ifBlank { "Ready for reset password smoke test" })
    }
}

@Composable
private fun AuthFrame(content: @Composable ColumnScope.() -> Unit) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    Box(
        modifier = Modifier.fillMaxSize().background(SurfaceCream).padding(
            start = 18.dp,
            top = (statusBarHeight + 18).dp,
            end = 18.dp,
            bottom = 28.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(SurfaceWhite)
                .border(3.dp, TextBlack)
                .padding(start = 34.dp, top = 52.dp, end = 34.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.Start,
            content = content
        )
    }
}

@Composable
private fun AuthBrand() {
    Box(
        modifier = Modifier.width(164.dp).height(70.dp)
    ) {
        Box(Modifier.size(width = 160.dp, height = 64.dp).offset(x = 6.dp, y = 6.dp).background(TextBlack))
        Box(
            modifier = Modifier.size(width = 160.dp, height = 64.dp).background(TextBlack),
            contentAlignment = Alignment.Center
        ) {
            Text("SLOCK", color = BrandYellow, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
    }
    Text(
        text = "WHERE HUMANS AND AI AGENTS COLLABORATE",
        color = TextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 22.dp)
    )
}

@Composable
private fun AuthTextField(
    label: String,
    value: String,
    required: Boolean,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    Row(modifier = Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextBlack, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (required) {
            Text("*", color = AccentPink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(58.dp).padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 4.dp, y = 4.dp).background(TextBlack))
        Box(
            modifier = Modifier.fillMaxSize().background(if (enabled) SurfaceWhite else Disabled)
                .border(3.dp, TextBlack)
                .padding(start = 12.dp, end = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            TextField(
                value = value,
                placeholder = "",
                autoFocus = false,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = TextBlack, fontSize = 18.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AuthButton(
    text: String,
    enabled: Boolean,
    background: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp).padding(top = 18.dp, end = 4.dp, bottom = 4.dp)
    ) {
        Box(Modifier.fillMaxSize().offset(x = 4.dp, y = 4.dp).background(TextBlack))
        Box(
            modifier = Modifier.fillMaxSize().background(if (enabled) background else Disabled).border(3.dp, TextBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = TextBlack, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AuthDivider() {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth().height(3.dp).background(TextBlack))
}

@Composable
private fun AuthTextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp).clickable(onClick = onClick)
    )
}

@Composable
private fun AuthInlineAction(leading: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(leading, color = TextBlack, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(
            action,
            color = AccentPink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun AuthError(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(message, color = Color(0xFFD73A49), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun AuthStatus(text: String) {
    Text(text, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
}

private fun Pager.openAuthPage(pageName: String) {
    val pageData = JSONObject().apply { put("pageName", pageName) }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}
