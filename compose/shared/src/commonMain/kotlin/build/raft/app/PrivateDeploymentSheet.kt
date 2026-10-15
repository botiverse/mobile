package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.DataResult
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.ModalBottomSheet
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.KeyboardType
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * The login pages' "Sign in to a private deployment" sheet (artin, 2026-10-15): the web (frontend) address and
 * the server (API) address of a self-hosted Raft, nothing else. A web URL alone cannot locate the API (the web
 * app bakes its API origin in at build time and there is no discovery endpoint yet), so both are asked for.
 * The full API environment editor stays an internal debugging page.
 */
@Composable
internal fun PrivateDeploymentSheet(
    visible: Boolean,
    environmentStore: SharedApiEnvironmentStore,
    settingsDataSource: SharedSettingsDataSource,
    onDismiss: () -> Unit,
) {
    val current = remember(visible) { environmentStore.current() }
    val isCustom = current.key == SharedApiEnvironmentStore.CustomKey
    var webValue by remember(visible) { mutableStateOf(TextFieldValue(if (isCustom) current.webUrl else "")) }
    var apiValue by remember(visible) { mutableStateOf(TextFieldValue(if (isCustom) current.apiBaseUrl else "")) }
    var pending by remember(visible) { mutableStateOf<SharedSettingsApiEnvironmentRequest?>(null) }
    var errorCode by remember(visible) { mutableStateOf<String?>(null) }

    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { settingsDataSource.saveApiEnvironment(request) }
                .getOrNull()
        pending = null
        when (result) {
            is DataResult.Content -> onDismiss()
            is DataResult.Error -> errorCode = result.error.code
            else -> errorCode = PRIVATE_DEPLOYMENT_SAVE_FAILED
        }
    }

    val errorMessage =
        when (errorCode) {
            null -> null
            PRIVATE_DEPLOYMENT_INVALID -> stringResource(MR.strings.auth_private_deployment_invalid)
            "settings_environment_server_unreachable" -> stringResource(MR.strings.auth_private_deployment_unreachable)
            "settings_environment_server_outdated" -> stringResource(MR.strings.auth_private_deployment_outdated)
            else -> stringResource(MR.strings.auth_private_deployment_failed)
        }
    val saving = pending != null

    ModalBottomSheet(
        visible = visible,
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.padding(horizontal = 12.dp),
        containerColor = Color.Transparent,
        dismissOnDrag = !saving,
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(SlockSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            ) {
                Text(text = stringResource(MR.strings.auth_private_deployment_title), style = SlockType.PageTitle)
                Text(
                    text = stringResource(MR.strings.auth_private_deployment_hint),
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                )
                AuthTextField(
                    value = webValue,
                    onValueChange = {
                        webValue = it
                        errorCode = null
                    },
                    label = stringResource(MR.strings.auth_private_deployment_web_label),
                    placeholder = stringResource(MR.strings.auth_private_deployment_web_placeholder),
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                AuthTextField(
                    value = apiValue,
                    onValueChange = {
                        apiValue = it
                        errorCode = null
                    },
                    label = stringResource(MR.strings.auth_private_deployment_api_label),
                    placeholder = stringResource(MR.strings.auth_private_deployment_api_placeholder),
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                AuthError(errorMessage)
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    AuthActionButton(
                        text = stringResource(MR.strings.auth_private_deployment_save),
                        tone = AuthButtonTone.Pink,
                        enabled = !saving,
                        loading = saving,
                        variant = AuthActionButtonVariant.Default,
                        onClick = {
                            val request = privateDeploymentRequest(webValue.text, apiValue.text)
                            if (request == null) errorCode = PRIVATE_DEPLOYMENT_INVALID else pending = request
                        },
                    )
                    AuthActionButton(
                        text = stringResource(MR.strings.auth_private_deployment_cancel),
                        tone = AuthButtonTone.White,
                        enabled = !saving,
                        variant = AuthActionButtonVariant.Default,
                        onClick = onDismiss,
                    )
                }
                if (isCustom) {
                    AuthTextLink(stringResource(MR.strings.auth_private_deployment_reset)) {
                        if (!saving) {
                            errorCode = null
                            pending = SharedSettingsApiEnvironmentRequest(
                                key = SharedApiEnvironmentStore.Production.key,
                                label = SharedApiEnvironmentStore.Production.label,
                                apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
                                socketBaseUrl = SharedApiEnvironmentStore.Production.socketBaseUrl,
                                webUrl = SharedApiEnvironmentStore.Production.webUrl,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val PRIVATE_DEPLOYMENT_INVALID = "private_deployment_invalid"
private const val PRIVATE_DEPLOYMENT_SAVE_FAILED = "private_deployment_save_failed"

/**
 * The custom environment for a web address and a server address as typed. A missing scheme means https;
 * anything but http(s) with a host is rejected. REST is `<server>/api/` (kept as typed when it already ends in
 * `/api`) and Socket.IO is the server origin, the web app's own convention for `VITE_API_URL`.
 */
internal fun privateDeploymentRequest(web: String, api: String): SharedSettingsApiEnvironmentRequest? {
    val webUrl = normalizedHttpUrl(web) ?: return null
    val serverUrl = normalizedHttpUrl(api) ?: return null
    val apiBaseUrl = if (serverUrl.endsWith("/api")) "$serverUrl/" else "$serverUrl/api/"
    val socketBaseUrl = httpOrigin(serverUrl)
    return SharedSettingsApiEnvironmentRequest(
        key = SharedApiEnvironmentStore.CustomKey,
        label = webUrl.substringAfter("://").substringBefore('/'),
        apiBaseUrl = apiBaseUrl,
        socketBaseUrl = socketBaseUrl,
        webUrl = webUrl,
    )
}

/**
 * Trimmed, scheme defaulted to https, no trailing slash; null unless it is http(s) with a valid authority.
 * A query or fragment is dropped (a pasted link keeps working). The authority must be a host name (letters,
 * digits, `-`, `.` with no empty label) or a bracketed IPv6 literal, optionally `:port` (1-65535); user info
 * (`user@`) and anything else is rejected.
 */
private fun normalizedHttpUrl(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    if (!text.contains("://")) text = "https://$text"
    val scheme = text.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = text.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
    val authority = rest.substringBefore('/')
    if (!isValidAuthority(authority)) return null
    return "$scheme://$rest"
}

private fun isValidAuthority(authority: String): Boolean {
    if (authority.isEmpty() || '@' in authority) return false
    val host: String
    val port: String?
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return false
        host = authority.substring(1, close)
        val after = authority.substring(close + 1)
        port = if (after.isEmpty()) null else if (after.startsWith(':')) after.substring(1) else return false
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == ':' || it == '.') } || ':' !in host) {
            return false
        }
    } else {
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else null
        val labels = host.split('.')
        if (host.isEmpty() || labels.any { label ->
                label.isEmpty() || label.startsWith('-') || label.endsWith('-') ||
                    label.any { !(it.isLetterOrDigit() || it == '-') }
            }
        ) {
            return false
        }
    }
    if (port != null) {
        val number = port.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return false
        if (number !in 1..65535) return false
    }
    return true
}

private fun httpOrigin(url: String): String {
    val scheme = url.substringBefore("://")
    return "$scheme://" + url.substringAfter("://").substringBefore('/')
}
