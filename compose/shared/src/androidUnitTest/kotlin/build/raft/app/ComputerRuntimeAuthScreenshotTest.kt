package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthProvider
import build.raft.app.data.RuntimeAuthProviderKeyEntry
import build.raft.app.data.RuntimeAuthProviderOAuthFlow
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.model.RuntimeAuthLoginStep
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Signing a Computer's runtimes in and out from their cards (web RuntimeUsageSurface.tsx,
 * RuntimeSignInDialog.tsx, RuntimeSignOutDialog.tsx, PiSignInDialog.tsx at botiverse/slock staging
 * 853c6998b): a signed-out Claude Code card (no usage, only Sign In), Codex signed in with its quiet
 * Sign Out under the usage, Codex's CLI not installed (why, and the install command to copy), Pi's
 * stored providers with Add Provider; the device-code step (Grok Build), Claude's sign-in page and
 * pasted code, the sign-out confirm naming the agents, and Pi's Add Provider list in its two groups.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w520dp-h900dp-xxhdpi")
class ComputerRuntimeAuthScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun cardsLight() = capture("settings.computer.runtime_auth.cards", RaftAppAppearance.Light) { RuntimeAuthCards() }

    @Test
    fun cardsDark() = capture("settings.computer.runtime_auth.cards.dark", RaftAppAppearance.Dark) { RuntimeAuthCards() }

    @Test
    fun signInDeviceCodeLight() =
        capture("settings.computer.runtime_auth.sign_in_device_code", RaftAppAppearance.Light) {
            SignInDialog(
                "Grok Build",
                ComputerRuntimeSignInState(started = true, deviceCode = RuntimeAuthLoginStep.DeviceCode("QX7B-K2MD", "https://accounts.x.ai/oauth2/device?user_code=QX7B-K2MD", 900)),
                RuntimeAuthRuntime.Grok,
            )
        }

    @Test
    fun signInDeviceCodeDark() =
        capture("settings.computer.runtime_auth.sign_in_device_code.dark", RaftAppAppearance.Dark) {
            SignInDialog(
                "Grok Build",
                ComputerRuntimeSignInState(started = true, deviceCode = RuntimeAuthLoginStep.DeviceCode("QX7B-K2MD", "https://accounts.x.ai/oauth2/device?user_code=QX7B-K2MD", 900)),
                RuntimeAuthRuntime.Grok,
            )
        }

    @Test
    fun signInPastedCodeLight() =
        capture("settings.computer.runtime_auth.sign_in_code", RaftAppAppearance.Light) {
            SignInDialog(
                "Claude Code",
                ComputerRuntimeSignInState(
                    started = true,
                    authUrl = "https://claude.ai/oauth/authorize?code=true",
                    codePrompt = RuntimeAuthLoginStep.CodePrompt(),
                    code = "ab12cd34#ef56",
                ),
                RuntimeAuthRuntime.Claude,
            )
        }

    @Test
    fun signInStatesLight() =
        capture("settings.computer.runtime_auth.sign_in_states", RaftAppAppearance.Light) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SignInDialog("Kimi Code", ComputerRuntimeSignInState(choosingRegion = true), RuntimeAuthRuntime.Kimi, footer = false)
                SignInDialog(
                    "Claude Code",
                    ComputerRuntimeSignInState(
                        started = true,
                        failure = ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.Busy, retryable = true, traceId = "0123456789abcdef0123456789abcdef"),
                    ),
                    RuntimeAuthRuntime.Claude,
                    footer = false,
                )
                SignInDialog("Codex CLI", ComputerRuntimeSignInState(started = true, done = true), RuntimeAuthRuntime.Codex, footer = false)
            }
        }

    @Test
    fun signOutLight() =
        capture("settings.computer.runtime_auth.sign_out", RaftAppAppearance.Light) {
            RaftDialogSurface(title = "Sign out of Codex CLI?", size = RaftDialogSize.Sm, onClose = {}, footer = {
                RaftButton("Cancel", onClick = {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                RaftButton("Sign out", onClick = {}, variant = RaftButtonVariant.Warning, size = RaftButtonSize.Lg)
            }) {
                ComputerRuntimeSignOutBody(
                    ComputerRuntimeSignOutState(),
                    RuntimeAuthRuntime.Codex,
                    "Codex CLI",
                    "Studio Mac",
                    agents = listOf("Reviewer", "Release Bot"),
                )
            }
        }

    @Test
    fun piProvidersLight() =
        capture("settings.computer.runtime_auth.pi_providers", RaftAppAppearance.Light) {
            RaftDialogSurface(title = "Add a provider to Pi", onClose = {}) {
                ComputerRuntimePiProviderChoices(
                    ComputerRuntimePiProviderList.Listed(PI_PROVIDERS),
                    chosen = ComputerRuntimePiChoice(account = true, providerId = "openai-codex"),
                    onChoose = {},
                    runtimeName = "Pi",
                )
            }
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    RaftComputersTheme {
                        Column(
                            Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).verticalScroll(rememberScrollState()).padding(16.dp),
                        ) { content() }
                    }
                }
            }
        }
}

@Composable
private fun SignInDialog(name: String, state: ComputerRuntimeSignInState, runtime: RuntimeAuthRuntime, footer: Boolean = true) {
    RaftDialogSurface(
        title = "Sign in to $name",
        onClose = {},
        footer =
            if (footer) {
                { RaftButton("Cancel", onClick = {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg) }
            } else {
                null
            },
    ) {
        ComputerRuntimeSignInBody(session = null, state = state, runtimeName = name, computerName = "Studio Mac", openUrl = {}, runtime = runtime)
    }
}

@Composable
private fun RuntimeAuthCards() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Signed out: no usage, only the way to sign in.
        AuthCard("Claude Code", RuntimeAuthRuntime.Claude, "claude", RuntimeAuthStatus.LoggedOut, usage = CODEX_USAGE)
        // Signed in: the usage names the account; Sign Out is the quiet action under it.
        AuthCard("Codex CLI", RuntimeAuthRuntime.Codex, "codex", RuntimeAuthStatus.LoggedIn(), usage = CODEX_USAGE)
        // Its CLI is missing on the Computer: why, and the command to install it.
        AuthCard("Codex CLI", RuntimeAuthRuntime.Codex, "codex", RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled), usage = null)
        // Pi: its stored providers, each with its own Sign Out or Remove, and Add Provider.
        AuthCard(
            "Pi",
            RuntimeAuthRuntime.Pi,
            "pi",
            RuntimeAuthStatus.LoggedIn(
                providers =
                    listOf(
                        RuntimeAuthSignedInProvider("anthropic", "Anthropic", apiKey = false, subscription = true),
                        RuntimeAuthSignedInProvider("deepseek", "DeepSeek", apiKey = true),
                    )
            ),
            usage = null,
        )
    }
}

@Composable
private fun AuthCard(name: String, runtime: RuntimeAuthRuntime, raftRuntimeId: String, status: RuntimeAuthStatus, usage: String?) {
    val auth = ComputerRuntimeAuthCardState(raftRuntimeId, runtime, AUTH_VIEW.copy(status = mapOf(runtime to status)))
    RaftPreviewCardSurface(width = RaftPreviewCardDefaults.WideWidth) {
        if (usage != null) {
            val read = remember(usage) { Json.parseToJsonElement(usage).toRuntimeAccountUsageRead()!! }
            ComputerRuntimeUsageCard(
                runtimeLabel = name,
                state =
                    ComputerRuntimeUsageSheetState(
                        load = RuntimeAccountUsageLoad.Loaded(read),
                        loading = false,
                        refresh = ComputerRuntimeUsageRefreshStatus.Idle,
                        cooldownLeftMillis = 0L,
                        runtimeVersion = "0.98.0",
                    ),
                timeContext = null,
                onRefresh = {},
                onClose = {},
                auth = auth,
                authRuntimeName = name,
            )
        } else {
            ComputerRuntimeAuthOnlyCard(name, runtimeVersion = "1.5.0", auth = auth, onClose = {}, onSignIn = {}, onSignOut = {}, onSignOutProvider = {})
        }
    }
}

private val AUTH_VIEW =
    RuntimeAuthView(
        canAct = true,
        availability = RuntimeAuthAvailability.Ok,
        runtimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Pi, RuntimeAuthRuntime.Grok),
        logoutRuntimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Pi, RuntimeAuthRuntime.Grok),
    )

// slock packages/shared/src/runtimeAccountUsage.ts (protocol 2) read envelope.
private const val CODEX_USAGE =
    """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-10-22T02:04:00.000Z","accounts":[{"accountKey":"acct-2","maskedLabel":"ar***@example.com","planLabel":"Pro","health":"ok","windows":[{"id":"primary","label":"5-hour limit","status":"ok","usedRatio":0.2}]}]}}"""

private val PI_PROVIDERS =
    listOf(
        RuntimeAuthProvider("anthropic", "Anthropic", oauthLabel = "Anthropic (Claude Pro/Max)", oauthFlow = RuntimeAuthProviderOAuthFlow.PasteCode, apiKeyEntry = RuntimeAuthProviderKeyEntry.Key, stored = "oauth"),
        RuntimeAuthProvider("openai-codex", "OpenAI Codex", oauthLabel = "ChatGPT Plus/Pro (Codex Subscription)", oauthFlow = RuntimeAuthProviderOAuthFlow.DeviceCode),
        RuntimeAuthProvider("google-antigravity", "Antigravity", oauthLabel = "Antigravity (Gemini 3, Claude, GPT-OSS)", oauthFlow = RuntimeAuthProviderOAuthFlow.ComputerOnly),
        RuntimeAuthProvider("deepseek", "DeepSeek", apiKeyEntry = RuntimeAuthProviderKeyEntry.Key),
        RuntimeAuthProvider("amazon-bedrock", "Amazon Bedrock", apiKeyEntry = RuntimeAuthProviderKeyEntry.MoreFields),
    )
