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
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.createPlatformRaftHttpExecutor
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.KeyboardParams
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.withTimeoutOrNull

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
    // The sheet sits outside AuthScaffold, whose keyboard provider the fields would otherwise fall back to (a
    // no-op), so the fields report here and the sheet lifts above the keyboard like RaftUnifiedDialog does.
    var keyboardHeightDp by remember(visible) { mutableStateOf(0f) }
    val onKeyboardHeightChange: (KeyboardParams) -> Unit = { keyboardHeightDp = it.height.coerceAtLeast(0f) }

    val webProbe = remember { createPlatformRaftHttpExecutor {} }
    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        // The server check below only covers the API; web login opens the web address, so it must answer too
        // (HuaTuo: a bad web address with a good server saved fine and broke sign-in).
        if (request.key == SharedApiEnvironmentStore.CustomKey && !privateDeploymentWebReachable(webProbe, request.webUrl)) {
            pending = null
            errorCode = PRIVATE_DEPLOYMENT_WEB_UNREACHABLE
            return@LaunchedEffect
        }
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
            PRIVATE_DEPLOYMENT_WEB_UNREACHABLE -> stringResource(MR.strings.auth_private_deployment_web_unreachable)
            "settings_environment_server_unreachable" -> stringResource(MR.strings.auth_private_deployment_unreachable)
            "settings_environment_server_outdated" -> stringResource(MR.strings.auth_private_deployment_outdated)
            else -> stringResource(MR.strings.auth_private_deployment_failed)
        }
    val saving = pending != null

    if (!visible) return
    // Read before entering Modal: its content is rehosted in a native window where the pager's locals are gone.
    val bottomInset = raftBottomSafeAreaPadding()
    // Not Kuikly's ModalBottomSheet: its Dialog hard-codes dismissOnClickOutside, which on iOS turns the whole
    // window into one accessibility leaf and hides the fields (see raftModalDialogPropertiesWithoutScrim).
    Modal(onWillDismiss = { if (!saving) onDismiss() }) {
        Box(modifier = Modifier.fillMaxSize()) {
            RaftModalDismissBarrier(overlayAlpha = 0.35f, onDismiss = onDismiss, dismissEnabled = !saving)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            bottom =
                                if (keyboardHeightDp > 0f) {
                                    // Android's reported height leaves the suggestion strip over the buttons (HuaTuo).
                                    keyboardHeightDp.dp + PRIVATE_DEPLOYMENT_KEYBOARD_GAP
                                } else {
                                    bottomInset + RaftAppSpacing.Sm
                                },
                        )
                        .raftDialogContentPointerBarrier()
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(RaftColor.SurfaceCream)
                            .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                            .padding(RaftAppSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
                ) {
                    Text(text = stringResource(MR.strings.auth_private_deployment_title), style = RaftType.PageTitle)
                    Text(
                        text = stringResource(MR.strings.auth_private_deployment_hint),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
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
                        keyboardHeightChange = onKeyboardHeightChange,
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
                        keyboardHeightChange = onKeyboardHeightChange,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                    AuthError(errorMessage)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
                        AuthActionButton(
                            text = stringResource(MR.strings.auth_private_deployment_save),
                            tone = AuthButtonTone.Pink,
                            enabled = !saving,
                            loading = saving,
                            variant = AuthActionButtonVariant.Default,
                            // AuthActionButton fills the width; weights share the row instead of pushing Cancel out.
                            modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
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
}

private const val PRIVATE_DEPLOYMENT_INVALID = "private_deployment_invalid"
private const val PRIVATE_DEPLOYMENT_SAVE_FAILED = "private_deployment_save_failed"
private const val PRIVATE_DEPLOYMENT_WEB_UNREACHABLE = "private_deployment_web_unreachable"
private val PRIVATE_DEPLOYMENT_KEYBOARD_GAP = 48.dp
private const val PRIVATE_DEPLOYMENT_WEB_PROBE_TIMEOUT_MS = 10_000L

/** GET the web address: a 2xx/3xx answer is reachable; another status, a transport failure or a timeout is not. */
internal suspend fun privateDeploymentWebReachable(executor: RaftHttpExecutor, webUrl: String): Boolean {
    val response =
        runCatchingPreservingCancellation {
            withTimeoutOrNull(PRIVATE_DEPLOYMENT_WEB_PROBE_TIMEOUT_MS) {
                executor.execute(
                    priority = RaftRequestPriority.Foreground,
                    url = webUrl,
                    method = "GET",
                    headers = emptyMap(),
                    bodyText = null,
                    jsonBody = false,
                )
            }
        }.getOrNull() ?: return false
    return response.code in 200..399
}

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
        if (!isIpv6Literal(host)) return false
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
        val number = port.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c in '0'..'9' } }?.toInt() ?: return false
        if (number !in 1..65535) return false
    }
    return true
}

/** RFC 4291 text form: hex groups of 1-4 digits, at most one `::`, optional trailing IPv4; no zone id. */
private fun isIpv6Literal(host: String): Boolean {
    if (host.isEmpty()) return false
    var groups = host
    if ('.' in host) {
        val lastColon = host.lastIndexOf(':')
        if (lastColon < 0 || !isIpv4Literal(host.substring(lastColon + 1))) return false
        // The IPv4 tail fills two groups; keep the separator so "::1.2.3.4" still splits around its "::".
        groups = host.substring(0, lastColon + 1) + "0:0"
    }
    val doubleColon = groups.indexOf("::")
    if (doubleColon >= 0 && groups.indexOf("::", doubleColon + 1) >= 0) return false
    fun validGroups(part: String): List<String>? {
        if (part.isEmpty()) return emptyList()
        val items = part.split(':')
        return items.takeIf { all -> all.all { it.length in 1..4 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } } }
    }
    if (doubleColon < 0) {
        val all = validGroups(groups) ?: return false
        return all.size == 8
    }
    val head = validGroups(groups.substring(0, doubleColon)) ?: return false
    val tail = validGroups(groups.substring(doubleColon + 2)) ?: return false
    return head.size + tail.size <= 7
}

private fun isIpv4Literal(text: String): Boolean {
    val parts = text.split('.')
    return parts.size == 4 && parts.all { part ->
        part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255 &&
            (part.length == 1 || part[0] != '0')
    }
}

private fun httpOrigin(url: String): String {
    val scheme = url.substringBefore("://")
    return "$scheme://" + url.substringAfter("://").substringBefore('/')
}
