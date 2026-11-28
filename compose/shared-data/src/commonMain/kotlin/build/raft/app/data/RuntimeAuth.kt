package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportUnknownTypeDropped
import build.raft.app.model.RuntimeAuthAccountLabel
import build.raft.app.model.RuntimeAuthLoginOutcome
import build.raft.app.model.RuntimeAuthLoginStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Signing a Computer's runtimes in and out from the app, as the web's runtime card does
 * (botiverse/slock staging 853c6998bcef45df5c25e63b1035e2388608721d: docs/contracts/runtime-auth.md,
 * packages/shared/src/runtimeAuth.ts and index.ts, packages/server/src/routes/runtimeAuthRoutes.ts,
 * packages/web/src/utils/runtimeAuthClient.ts).
 *
 * Routes, all under `/servers/:id/machines/:machineId/runtime-auth`:
 * - `GET` → `{status, availability, runtimes, logoutRuntimes, canAct}` (403 to a viewer who may not see it).
 * - `POST /:runtime/login {region?, providerId?}` → `{loginId}`; the link or device code and the result
 *   arrive on this person's sockets (`runtime_auth:login:event|result`).
 * - `POST /:runtime/login/:loginId/code {code}` → 202; `POST /:runtime/login/:loginId/cancel` → 202.
 * - `POST /:runtime/logout {providerId?}` → `{result}`.
 * - `GET /:runtime/providers` → `{result}`; `POST /:runtime/connect-provider {providerId, apiKey}` → `{result}`.
 * A refusal answers `{error, code?}`: 400 `invalid_region` / `invalid_provider` / `invalid_code` /
 * `invalid_api_key`, 403, 404 (`runtime_auth_not_offered`), 409 `computer_offline` /
 * `computer_upgrade_required` / `computer_not_attached` / `login_in_progress`, 429 `rate_limited`,
 * 504 `computer_timeout`.
 *
 * Who may act: the human who attached the Computer, or a server owner or admin (`canAct`).
 * Nothing here logs a pasted code or an API key: each goes into one request body and nowhere else.
 */

@RaftCoreInternalApi enum class RuntimeAuthAction { Login, Logout, ConnectProvider }

/** slock `RUNTIME_AUTH_LOGIN_REGIONS`: where a Kimi Code account lives. */
@RaftCoreInternalApi enum class RuntimeAuthLoginRegion(val wire: String) {
    MainlandCn("mainland-cn"),
    Global("global"),
}

/**
 * slock `RuntimeAuthRuntime`: the runtime whose sign-in Raft drives (OAR's id, not Raft's: Kimi Code
 * `kimi-sdk` signs in as `kimi`), with what it can do (`RUNTIME_AUTH_ACTIONS`), the regions its login
 * asks for (`RUNTIME_AUTH_LOGIN_REGION_CHOICES`), whether it signs in per model provider (Pi) and
 * whether its logout needs a daemon capability beyond its sign-in (`runtimeAuthLogoutNeedsCapability`).
 * A runtime with no action at all (DeepSeek Harness) is here only for its card's status and Install
 * ([statusOnly]).
 */
@RaftCoreInternalApi enum class RuntimeAuthRuntime(
    val wire: String,
    val actions: Set<RuntimeAuthAction>,
    val regionChoices: List<RuntimeAuthLoginRegion> = emptyList(),
    val perProvider: Boolean = false,
    /** slock `runtimeAuthChoosesOAuthMethod`: a provider may offer several OAuth sign-ins; the login names one (OpenCode). */
    val choosesOAuthMethod: Boolean = false,
    val logoutNeedsCapability: Boolean = false,
) {
    Claude("claude", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout)),
    Codex("codex", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout)),
    Kimi("kimi", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout), regionChoices = RuntimeAuthLoginRegion.entries),
    Qoder("qoder", setOf(RuntimeAuthAction.Login)),
    Qwen("qwen", setOf(RuntimeAuthAction.ConnectProvider)),
    Antigravity("antigravity", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout), logoutNeedsCapability = true),
    Pi("pi", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout, RuntimeAuthAction.ConnectProvider), perProvider = true),
    Grok("grok", setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout)),

    /** OpenCode 2, per provider like Pi (slock #14378, contract §6c). */
    Opencode(
        "opencode",
        setOf(RuntimeAuthAction.Login, RuntimeAuthAction.Logout, RuntimeAuthAction.ConnectProvider),
        perProvider = true,
        choosesOAuthMethod = true,
    ),

    /** DeepSeek Harness (slock #15549): no sign-in at all; its DeepSeek key is configured in dsh itself. */
    Dsh("dsh", emptySet());

    /** slock `runtimeAuthLiteralApiKeyFits`: the key-content rule of a per-provider runtime. */
    fun literalApiKeyFits(apiKey: String): Boolean =
        when (this) {
            Pi -> isPiLiteralApiKey(apiKey)
            Opencode -> isOpencodeLiteralApiKey(apiKey)
            else -> true
        }

    fun supports(action: RuntimeAuthAction): Boolean = action in actions

    /**
     * slock `runtimeAuthStatusOnly`: Raft neither signs it in nor connects a provider; its card shows
     * whether it is installed and usable here, Install, and how its credentials are set outside Raft.
     */
    val statusOnly: Boolean
        get() = actions.isEmpty()

    /** A provider's API key instead of a sign-in (Qwen Code): presence only, never "signed out". */
    val hasProviderKey: Boolean
        get() = supports(RuntimeAuthAction.ConnectProvider) && !perProvider

    companion object {
        fun fromWire(value: String?): RuntimeAuthRuntime? = entries.firstOrNull { it.wire == value }

        /**
         * slock `runtimeAuthRuntimeFor`: Raft runtime id to the runtime whose sign-in it uses. The
         * deprecated Kimi CLI (`kimi`) and Built-in Pi (`builtin`) have none.
         */
        fun forRaftRuntime(raftRuntimeId: String?): RuntimeAuthRuntime? =
            when (raftRuntimeId?.trim()) {
                "claude" -> Claude
                "codex" -> Codex
                "kimi-sdk" -> Kimi
                "qoder" -> Qoder
                "qwen" -> Qwen
                "antigravity" -> Antigravity
                "pi" -> Pi
                "grok" -> Grok
                "opencode" -> Opencode
                "dsh" -> Dsh
                else -> null
            }
    }
}

/** slock `RUNTIME_AUTH_V1_RUNTIMES`: what a server that omits `runtimes` signs in. */
@RaftCoreInternalApi val RUNTIME_AUTH_V1_RUNTIMES: List<RuntimeAuthRuntime> = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex)

/**
 * Web `RUNTIME_INSTALL_COMMANDS` (packages/web/src/components/machine/runtimeInstallCommands.ts), by
 * Raft runtime id: the command that installs a runtime's own CLI on macOS or Linux, for a card whose
 * sign-in needs it.
 */
@RaftCoreInternalApi val RUNTIME_INSTALL_COMMANDS: Map<String, String> =
    mapOf(
        "claude" to "npm install -g @anthropic-ai/claude-code",
        "codex" to "npm install -g @openai/codex",
        "kimi-sdk" to "curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash",
        // OAR's documented method for DeepSeek Harness: its official npm package (slock #15549).
        "dsh" to "npm install -g @deepseek-ai/dsh",
    )

/** slock `RuntimeAuthAvailability` of the runtime-auth read. */
@RaftCoreInternalApi enum class RuntimeAuthAvailability(val wire: String) {
    Ok("ok"),
    ComputerOffline("computer_offline"),
    ComputerUpgradeRequired("computer_upgrade_required"),
    ComputerNotAttached("computer_not_attached");

    companion object {
        fun fromWire(value: String?): RuntimeAuthAvailability? = entries.firstOrNull { it.wire == value }
    }
}

/** Why the runtime cannot be signed in from here (`unavailable.reason`; also an `unsupported` result's). */
@RaftCoreInternalApi enum class RuntimeAuthUnavailableReason(val wire: String) {
    NotInstalled("not_installed"),
    VersionUnsupported("version_unsupported"),
    UnsupportedInstallation("unsupported_installation");

    companion object {
        fun fromWire(value: String?): RuntimeAuthUnavailableReason? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `runtimeAuthSignedInProviderSchema`: one provider Pi holds a stored sign-in for. Never a key. */
@RaftCoreInternalApi data class RuntimeAuthSignedInProvider(
    val id: String,
    val label: String,
    val apiKey: Boolean,
    /** Null when the runtime does not say (OpenCode): neither a subscription nor a plain account is claimed. */
    val subscription: Boolean? = null,
)

/** slock `runtimeAuthStatusSchema`. */
@RaftCoreInternalApi sealed interface RuntimeAuthStatus {
    val wire: String

    data class LoggedIn(
        val account: RuntimeAuthAccountLabel? = null,
        /** A per-provider runtime's (Pi's) stored providers. */
        val providers: List<RuntimeAuthSignedInProvider> = emptyList(),
    ) : RuntimeAuthStatus {
        override val wire = "logged_in"
    }

    /**
     * [lastAccount]: who was signed in last (slock #15931, OAR `lastAccount` on an expired sign-in):
     * the masked email, or the provider's display name when there is no email. Null when the
     * runtime remembers none, or the server or Computer predates it. Grants nothing.
     */
    data class LoggedOut(val lastAccount: String? = null) : RuntimeAuthStatus {
        override val wire = "logged_out"
    }

    /** The query failed: never a guess either way. */
    data object Unknown : RuntimeAuthStatus { override val wire = "unknown" }

    data class Unavailable(val reason: RuntimeAuthUnavailableReason) : RuntimeAuthStatus { override val wire = "unavailable" }
}

/**
 * `GET …/runtime-auth` (web `RuntimeAuthView`). [runtimes] / [logoutRuntimes] are null from a server
 * that predates them: Claude Code and Codex then (web `runtimeAuthComputerSupports` / `…SignsOut`).
 */
@RaftCoreInternalApi data class RuntimeAuthView(
    val canAct: Boolean,
    val availability: RuntimeAuthAvailability,
    val status: Map<RuntimeAuthRuntime, RuntimeAuthStatus> = emptyMap(),
    val runtimes: List<RuntimeAuthRuntime>? = null,
    val logoutRuntimes: List<RuntimeAuthRuntime>? = null,
    /**
     * Claude limit resets (slock #14336): sent only while the server's `claude_limit_reset_v2` is on (slock #15564), for a person
     * who may act, on an online Computer; null otherwise (nothing is offered).
     */
    val claudeLimitResets: ClaudeLimitResetsAvailability? = null,
    /** Who may spend them (slock #14375); null from a server that predates it (offered as usable). */
    val claudeLimitResetAccess: ClaudeLimitResetAccess? = null,
    /**
     * Upgrade (slock #15117, `runtimeUpgrade: true` + `upgradableRuntimes`): the runtimes this
     * Computer last reported it can upgrade, sent only while `runtime_upgrade_button` is on for a
     * viewer who may act on a Computer that runs a runtime's updater; null: no Upgrade here.
     */
    val upgradableRuntimes: List<RuntimeAuthRuntime>? = null,
    /**
     * Upgrade's update checks (slock #15429, `runtimeUpdates`, RuntimeUpgradeInstall.kt): the
     * Computer's read-only check for those of [upgradableRuntimes] it checked; null from an older
     * server or Computer, and no entry for a runtime not checked yet. Read through [updateCheckOf].
     */
    val runtimeUpdates: Map<RuntimeAuthRuntime, RuntimeUpdateCheck>? = null,
    /**
     * Install (slock #15214, `runtimeInstall: true` + `installableRuntimes`): the runtimes this
     * Computer does not have and can install, under the same conditions behind
     * `runtime_install_button`; null: no Install here.
     */
    val installableRuntimes: List<RuntimeAuthRuntime>? = null,
) {
    /** Each reported runtime's status `kind` by its wire id. */
    val statusKinds: Map<String, String>
        get() = status.entries.associate { (runtime, value) -> runtime.wire to value.wire }

    fun statusOf(runtime: RuntimeAuthRuntime): RuntimeAuthStatus? = status[runtime]

    /** Web `runtimeAuthComputerSupports`: this Computer signs the runtime in and out from here. */
    fun computerSupports(runtime: RuntimeAuthRuntime): Boolean = runtime in (runtimes ?: RUNTIME_AUTH_V1_RUNTIMES)

    /** Web `runtimeAuthComputerSignsOut`: an older Computer may sign a runtime in but not out (Antigravity). */
    fun computerSignsOut(runtime: RuntimeAuthRuntime): Boolean {
        if (!runtime.supports(RuntimeAuthAction.Logout)) return false
        logoutRuntimes?.let { return runtime in it }
        return !runtime.logoutNeedsCapability && computerSupports(runtime)
    }

    /** Web `runtimeUpgradeOffered`: the server's say-so, a viewer who may act, and a runtime the Computer can upgrade. */
    fun upgradeOffered(runtime: RuntimeAuthRuntime?): Boolean =
        canAct && runtime != null && upgradableRuntimes?.contains(runtime) == true

    /**
     * Web `runtimeUpdateOf`: the Computer's update check for a runtime Upgrade is offered for, or null
     * when it reported none (an older Computer or server, a runtime OAR has no check for) or the check
     * is over a day old ([RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS]); the card then offers Upgrade as before.
     */
    fun updateCheckOf(runtime: RuntimeAuthRuntime?, nowEpochMillis: Long): RuntimeUpdateCheck? {
        if (runtime == null || !upgradeOffered(runtime)) return null
        val update = runtimeUpdates?.get(runtime) ?: return null
        return update.takeIf { nowEpochMillis - it.checkedAtMillis <= RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS }
    }

    /** Web `runtimeInstallOffered`: the server's say-so, a viewer who may act, and a runtime the Computer can install. */
    fun installOffered(runtime: RuntimeAuthRuntime?): Boolean =
        canAct && runtime != null && installableRuntimes?.contains(runtime) == true

    /** The person may act and the Computer can be reached (web `canActOnRuntimes`). */
    val canActNow: Boolean
        get() = canAct && availability == RuntimeAuthAvailability.Ok
}

/**
 * Null when the answer has no boolean `canAct` or no known `availability`. A runtime key or status
 * kind this app does not know is skipped, never the whole read (the server adds runtimes over time).
 */
@RaftCoreInternalApi fun JsonElement.toRuntimeAuthView(): RuntimeAuthView? {
    val envelope = this as? JsonObject ?: return null
    val canAct = (envelope["canAct"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    val availability = RuntimeAuthAvailability.fromWire(envelope.plainString("availability")) ?: return null
    val reported = ((envelope["status"] as? JsonObject)?.get("runtimes") as? JsonObject).orEmpty()
    val status =
        reported.mapNotNull { (key, value) ->
            val runtime =
                RuntimeAuthRuntime.fromWire(key)
                    ?: run {
                        reportUnknownTypeDropped("runtime_auth_status", "runtime", key)
                        return@mapNotNull null
                    }
            value.toRuntimeAuthStatus()?.let { runtime to it }
        }.toMap()
    return RuntimeAuthView(
        canAct = canAct,
        availability = availability,
        status = status,
        runtimes = envelope.runtimeList("runtimes"),
        logoutRuntimes = envelope.runtimeList("logoutRuntimes"),
        claudeLimitResets = ClaudeLimitResetsAvailability.fromWire(envelope.plainString("claudeLimitResets")),
        claudeLimitResetAccess = envelope["claudeLimitResetAccess"]?.toClaudeLimitResetAccess(),
        upgradableRuntimes = envelope.offeredRuntimeList("runtimeUpgrade", "upgradableRuntimes"),
        runtimeUpdates = envelope["runtimeUpdates"]?.toRuntimeUpdates(),
        installableRuntimes = envelope.offeredRuntimeList("runtimeInstall", "installableRuntimes"),
    )
}

/** [listKey]'s runtimes when [flagKey] is `true` (the server offers the action here, maybe for none); null otherwise. */
private fun JsonObject.offeredRuntimeList(flagKey: String, listKey: String): List<RuntimeAuthRuntime>? {
    val offered = (this[flagKey] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    return if (offered) runtimeList(listKey).orEmpty() else null
}

private fun JsonObject.runtimeList(key: String): List<RuntimeAuthRuntime>? {
    val array = this[key] as? JsonArray ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.let(RuntimeAuthRuntime::fromWire) }
}

@RaftCoreInternalApi fun JsonElement.toRuntimeAuthStatus(): RuntimeAuthStatus? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "logged_in" ->
            RuntimeAuthStatus.LoggedIn(
                account = obj["account"]?.toRuntimeAuthAccount(),
                providers = (obj["providers"] as? JsonArray)?.mapNotNull { it.toRuntimeAuthSignedInProvider() }.orEmpty(),
            )
        "logged_out" -> RuntimeAuthStatus.LoggedOut(lastAccount = obj["lastAccount"]?.toRuntimeAuthLastAccount())
        "unknown" -> RuntimeAuthStatus.Unknown
        "unavailable" -> RuntimeAuthUnavailableReason.fromWire(obj.plainString("reason"))?.let(RuntimeAuthStatus::Unavailable)
        else -> {
            reportUnknownTypeDropped("runtime_auth_status", "status_kind", obj.plainString("kind"))
            null
        }
    }
}

/**
 * slock `runtimeAuthLastAccountSchema` (#15931), read tolerantly: the masked email (with its mask
 * marker) or else the display name, each up to 80 characters with no control characters. Anything
 * else, or neither, is no last account; the status itself is kept.
 */
private fun JsonElement.toRuntimeAuthLastAccount(): String? {
    val obj = this as? JsonObject ?: return null
    val masked = obj.safeText("maskedLabel", 80)?.takeIf { label -> label.any { it == '*' || it == '•' || it == '…' } }
    val displayName = obj.safeText("displayName", 80)?.takeIf { name -> name.none { it.code < 0x20 } }
    return masked ?: displayName
}

private fun JsonElement.toRuntimeAuthAccount(): RuntimeAuthAccountLabel? {
    val obj = this as? JsonObject ?: return null
    return RuntimeAuthAccountLabel(
        maskedLabel = obj.safeText("maskedLabel", 80),
        planLabel = obj.safeText("planLabel", 80),
        methodLabel = obj.safeText("methodLabel", 40),
    )
}

private fun JsonElement.toRuntimeAuthSignedInProvider(): RuntimeAuthSignedInProvider? {
    val obj = this as? JsonObject ?: return null
    val id = obj.plainString("id")?.takeIf(::isRuntimeAuthProviderId) ?: return null
    val label = obj.safeText("label", 80) ?: return null
    val apiKey =
        when (obj.plainString("method")) {
            "api_key" -> true
            "oauth" -> false
            else -> return null
        }
    val subscription = (obj["subscription"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return RuntimeAuthSignedInProvider(id = id, label = label, apiKey = apiKey, subscription = subscription)
}

private val RuntimeAuthLoginIdShape = Regex("^rtl_[A-Za-z0-9_-]{16,64}$")
private val RuntimeAuthProviderIdShape = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

/** slock `asRuntimeAuthLoginId`: a login id is checked before anyone may use it. */
@RaftCoreInternalApi fun isRuntimeAuthLoginId(value: String?): Boolean = value != null && RuntimeAuthLoginIdShape.matches(value)

@RaftCoreInternalApi fun isRuntimeAuthProviderId(value: String?): Boolean = value != null && RuntimeAuthProviderIdShape.matches(value)

/** slock `isRuntimeAuthPastedCode`: one line, no control characters, at most 1024 characters. */
@RaftCoreInternalApi fun isRuntimeAuthPastedCode(value: String): Boolean =
    value.isNotEmpty() && value.length <= 1024 && value.none { it.code < 0x20 || it.code == 0x7f }

/**
 * slock `isPiLiteralApiKey`: Pi runs a stored key starting with `!` as a command and fills in a `$`
 * name, and sends it in an HTTP header (one byte per character), so such a key is never sent.
 */
@RaftCoreInternalApi fun isPiLiteralApiKey(apiKey: String): Boolean = !apiKey.startsWith("!") && '$' !in apiKey && apiKey.all { it.code <= 0xff }

private val OpencodeKeyReference = Regex("\\{(?:env|file):", RegexOption.IGNORE_CASE)

/** slock `isOpencodeLiteralApiKey`: one byte per character, and no `{env:}` / `{file:}` reference. */
@RaftCoreInternalApi fun isOpencodeLiteralApiKey(apiKey: String): Boolean = !OpencodeKeyReference.containsMatchIn(apiKey) && apiKey.all { it.code <= 0xff }

/** slock `isRuntimeAuthOAuthMethodLabel`: one untrimmed short line with no control characters. */
@RaftCoreInternalApi fun isRuntimeAuthOAuthMethodLabel(value: String?): Boolean =
    value != null && value.trim() == value && value.isNotEmpty() && value.length <= 80 && value.none { it.code < 0x20 || it.code == 0x7f }

/**
 * `runtime_auth:login:event`'s `event` (slock `runtimeAuthLoginEventSchema`, strict): null for an
 * unknown kind, a missing or malformed field, a link that is not https, or an extra field.
 */
@RaftCoreInternalApi fun JsonElement.toRuntimeAuthLoginStep(): RuntimeAuthLoginStep? {
    val obj = this as? JsonObject ?: return null
    fun only(vararg keys: String): Boolean = (obj.keys - "kind").all { it in keys }
    return when (obj.plainString("kind")) {
        "auth_url" -> {
            val url = obj.plainString("url")?.takeIf(::isRuntimeAuthHttpsUrl) ?: return null
            if (obj.containsKey("instructions") && obj.safeText("instructions", 500) == null) return null
            RuntimeAuthLoginStep.AuthUrl(url).takeIf { only("url", "instructions") }
        }
        "device_code" -> {
            val userCode = obj.safeText("userCode", 32) ?: return null
            val uri = obj.plainString("verificationUri")?.takeIf(::isRuntimeAuthHttpsUrl) ?: return null
            val expires =
                obj["expiresInSeconds"]?.let { element ->
                    (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..3600 } ?: return null
                }
            RuntimeAuthLoginStep.DeviceCode(userCode, uri, expires).takeIf { only("userCode", "verificationUri", "expiresInSeconds") }
        }
        "info" -> obj.safeText("message", 500)?.let(RuntimeAuthLoginStep::Info)?.takeIf { only("message") }
        "code_prompt" -> {
            if (obj.containsKey("message") && obj.safeText("message", 500) == null) return null
            val retry =
                obj["retry"]?.let { element -> (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null } ?: false
            RuntimeAuthLoginStep.CodePrompt(obj.safeText("message", 500), retry).takeIf { only("message", "retry") }
        }
        else -> null
    }
}

private val RuntimeAuthLoginFailureReasons = setOf("timed_out", "rejected", "not_logged_in", "interaction_failed", "process_failed", "loopback_required")

/** `runtime_auth:login:result`'s `result` (slock `runtimeAuthLoginResultSchema`); null when it does not check out. */
@RaftCoreInternalApi fun JsonElement.toRuntimeAuthLoginOutcome(): RuntimeAuthLoginOutcome? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "logged_in" -> RuntimeAuthLoginOutcome.LoggedIn(obj["account"]?.toRuntimeAuthAccount())
        "failed" -> obj.plainString("reason")?.takeIf { it in RuntimeAuthLoginFailureReasons }?.let(RuntimeAuthLoginOutcome::Failed)
        "cancelled" -> RuntimeAuthLoginOutcome.Cancelled
        "unsupported" -> RuntimeAuthUnavailableReason.fromWire(obj.plainString("reason"))?.let { RuntimeAuthLoginOutcome.Unsupported(it.wire) }
        "busy" -> RuntimeAuthLoginOutcome.Busy
        else -> null
    }
}

/** slock `runtimeAuthLogoutResultSchema`. */
@RaftCoreInternalApi sealed interface RuntimeAuthLogoutResult {
    data object LoggedOut : RuntimeAuthLogoutResult

    /** [reason]: `timed_out`, `rejected`, `process_failed`, `still_logged_in`. */
    data class Failed(val reason: String) : RuntimeAuthLogoutResult

    data class Unsupported(val reason: RuntimeAuthUnavailableReason) : RuntimeAuthLogoutResult
}

private val RuntimeAuthLogoutFailureReasons = setOf("timed_out", "rejected", "process_failed", "still_logged_in", "busy")

@RaftCoreInternalApi fun JsonElement.toRuntimeAuthLogoutResult(): RuntimeAuthLogoutResult? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "logged_out" -> RuntimeAuthLogoutResult.LoggedOut
        "failed" -> obj.plainString("reason")?.takeIf { it in RuntimeAuthLogoutFailureReasons }?.let(RuntimeAuthLogoutResult::Failed)
        "unsupported" -> RuntimeAuthUnavailableReason.fromWire(obj.plainString("reason"))?.let(RuntimeAuthLogoutResult::Unsupported)
        else -> null
    }
}

/** slock `RUNTIME_AUTH_PROVIDER_OAUTH_FLOWS`: how a Pi provider's account sign-in runs from here. */
@RaftCoreInternalApi enum class RuntimeAuthProviderOAuthFlow(val wire: String) {
    DeviceCode("device_code"),
    PasteCode("paste_code"),
    PasteAddress("paste_address"),
    /** OpenCode: the provider's sign-in page; what follows is known once the attempt starts. */
    Link("link"),
    ComputerOnly("computer_only");

    companion object {
        fun fromWire(value: String?): RuntimeAuthProviderOAuthFlow? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `RUNTIME_AUTH_PROVIDER_KEY_ENTRIES`: whether a Pi provider's key can be entered here. */
@RaftCoreInternalApi enum class RuntimeAuthProviderKeyEntry(val wire: String) {
    Key("key"),
    MoreFields("more_fields"),
    Outside("outside");

    companion object {
        fun fromWire(value: String?): RuntimeAuthProviderKeyEntry? = entries.firstOrNull { it.wire == value }
    }
}

/** One provider group of a provider-key runtime (slock `runtimeAuthProviderGroupSchema`). */
@RaftCoreInternalApi data class RuntimeAuthProviderGroup(val id: String, val label: String? = null)

/** One fixed endpoint choice offered by a provider (slock `runtimeAuthProviderEndpointOptionSchema`). */
@RaftCoreInternalApi data class RuntimeAuthProviderEndpointOption(val id: String, val label: String, val url: String)

/** How a provider's base URL is decided (slock `runtimeAuthProviderEndpointSchema`). */
@RaftCoreInternalApi sealed interface RuntimeAuthProviderEndpoint {
    data object Fixed : RuntimeAuthProviderEndpoint

    data class Choice(val options: List<RuntimeAuthProviderEndpointOption>) : RuntimeAuthProviderEndpoint

    data class Custom(val placeholder: String? = null) : RuntimeAuthProviderEndpoint
}

/**
 * One provider of a runtime's own list (slock `runtimeAuthProviderSchema`):
 * its id and name, its grouping/description/endpoint/model metadata (Qwen Code), its account
 * sign-in ([oauthLabel], [oauthFlow], [oauthMethodList]) and API key ([apiKeyEntry]), and which
 * of them is stored (Pi / OpenCode).
 */
@RaftCoreInternalApi data class RuntimeAuthProvider(
    val id: String,
    val label: String,
    val oauthLabel: String? = null,
    val oauthFlow: RuntimeAuthProviderOAuthFlow? = null,
    /** OpenCode: every OAuth sign-in the provider offers (`signIn.oauthMethods`), device code first; [oauthLabel] is then null. */
    val oauthMethodList: List<RuntimeAuthOAuthMethod> = emptyList(),
    val apiKeyEntry: RuntimeAuthProviderKeyEntry? = null,
    /** `oauth` or `api_key`: how its stored sign-in authenticates. */
    val stored: String? = null,
    /** Qwen Code: short provider description under its name. */
    val description: String? = null,
    /** Qwen Code: menu group (`third-party`, `custom`, or named group). */
    val group: RuntimeAuthProviderGroup? = null,
    /** Qwen Code: whether this provider is already configured on this Computer. */
    val configured: Boolean = false,
    /** Qwen Code: fixed endpoint, choice of endpoints, or custom base URL. */
    val endpoint: RuntimeAuthProviderEndpoint = RuntimeAuthProviderEndpoint.Fixed,
    /** Qwen Code: whether connecting requires a model ID. */
    val needsModelIds: Boolean = false,
)

/** One OAuth sign-in of a provider, by its label as the runtime lists it. */
@RaftCoreInternalApi data class RuntimeAuthOAuthMethod(val label: String, val flow: RuntimeAuthProviderOAuthFlow)

/** Web `oauthMethodsOf`: OpenCode's methods, or Pi's one. */
@RaftCoreInternalApi val RuntimeAuthProvider.oauthMethods: List<RuntimeAuthOAuthMethod>
    get() =
        oauthMethodList.ifEmpty {
            val flow = oauthFlow ?: return emptyList()
            listOf(RuntimeAuthOAuthMethod(oauthLabel ?: label, flow))
        }

/** slock `runtimeAuthProvidersResultSchema`. */
@RaftCoreInternalApi sealed interface RuntimeAuthProvidersResult {
    data class Listed(val providers: List<RuntimeAuthProvider>) : RuntimeAuthProvidersResult

    /** [reason]: `rejected`, `timed_out`, `process_failed`, `invalid_response`, `relay_failed`. */
    data class Failed(val reason: String) : RuntimeAuthProvidersResult

    data class Unsupported(val reason: RuntimeAuthUnavailableReason) : RuntimeAuthProvidersResult
}

private val RuntimeAuthProviderFailureReasons = setOf("rejected", "timed_out", "process_failed", "invalid_response", "relay_failed")

@RaftCoreInternalApi fun JsonElement.toRuntimeAuthProvidersResult(): RuntimeAuthProvidersResult? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "listed" -> {
            val providers = obj["providers"] as? JsonArray ?: return null
            RuntimeAuthProvidersResult.Listed(providers.map { it.toRuntimeAuthProvider() ?: return null })
        }
        "failed" -> obj.plainString("reason")?.takeIf { it in RuntimeAuthProviderFailureReasons }?.let(RuntimeAuthProvidersResult::Failed)
        "unsupported" -> RuntimeAuthUnavailableReason.fromWire(obj.plainString("reason"))?.let(RuntimeAuthProvidersResult::Unsupported)
        else -> null
    }
}

private const val RuntimeAuthProviderEndpointChoiceLimit = 10

private fun JsonElement.toRuntimeAuthProviderGroup(): RuntimeAuthProviderGroup? {
    val obj = this as? JsonObject ?: return null
    val id = obj.plainString("id")?.takeIf(::isRuntimeAuthProviderId) ?: return null
    if (obj.containsKey("label") && obj["label"] !is JsonNull && obj.safeText("label", 80) == null) return null
    return RuntimeAuthProviderGroup(id = id, label = obj.safeText("label", 80))
}

private fun JsonElement.toRuntimeAuthProviderEndpoint(): RuntimeAuthProviderEndpoint? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "fixed" -> RuntimeAuthProviderEndpoint.Fixed
        "choice" -> {
            val rawOptions = obj["options"] as? JsonArray ?: return null
            if (rawOptions.isEmpty() || rawOptions.size > RuntimeAuthProviderEndpointChoiceLimit) return null
            val options =
                rawOptions.map { element ->
                    val opt = element as? JsonObject ?: return null
                    val id = opt.plainString("id")?.takeIf(::isRuntimeAuthProviderId) ?: return null
                    val label = opt.safeText("label", 80) ?: return null
                    val url = opt.plainString("url")?.takeIf(::isRuntimeAuthHttpsUrl) ?: return null
                    RuntimeAuthProviderEndpointOption(id = id, label = label, url = url)
                }
            RuntimeAuthProviderEndpoint.Choice(options)
        }
        "custom" -> {
            if (obj.containsKey("placeholder") && obj["placeholder"] !is JsonNull && obj.safeText("placeholder", 200) == null) return null
            RuntimeAuthProviderEndpoint.Custom(placeholder = obj.safeText("placeholder", 200))
        }
        else -> null
    }
}

private fun JsonElement.toRuntimeAuthProvider(): RuntimeAuthProvider? {
    val obj = this as? JsonObject ?: return null
    val id = obj.plainString("id")?.takeIf(::isRuntimeAuthProviderId) ?: return null
    val label = obj.safeText("label", 80) ?: return null
    if (obj.containsKey("description") && obj["description"] !is JsonNull && obj.safeText("description", 200) == null) return null
    val group =
        if (obj.containsKey("group") && obj["group"] !is JsonNull) {
            obj["group"]?.toRuntimeAuthProviderGroup() ?: return null
        } else {
            null
        }
    val configured =
        if (obj.containsKey("configured") && obj["configured"] !is JsonNull) {
            (obj["configured"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        } else {
            false
        }
    val endpoint =
        if (obj.containsKey("endpoint") && obj["endpoint"] !is JsonNull) {
            obj["endpoint"]?.toRuntimeAuthProviderEndpoint() ?: return null
        } else {
            RuntimeAuthProviderEndpoint.Fixed
        }
    val needsModelIds =
        if (obj.containsKey("needsModelIds") && obj["needsModelIds"] !is JsonNull) {
            (obj["needsModelIds"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        } else {
            false
        }
    val signIn = obj["signIn"] as? JsonObject
    val oauth = signIn?.get("oauth") as? JsonObject
    val apiKey = signIn?.get("apiKey") as? JsonObject
    // One odd method label drops that method only (the daemon filters with the same rule).
    val methods =
        (signIn?.get("oauthMethods") as? JsonArray)?.mapNotNull { element ->
            val method = element as? JsonObject ?: return@mapNotNull null
            val methodLabel = method.plainString("label")?.takeIf(::isRuntimeAuthOAuthMethodLabel) ?: return@mapNotNull null
            RuntimeAuthProviderOAuthFlow.fromWire(method.plainString("flow"))?.let { RuntimeAuthOAuthMethod(methodLabel, it) }
        }.orEmpty()
    return RuntimeAuthProvider(
        id = id,
        label = label,
        oauthLabel = oauth?.safeText("label", 80),
        oauthFlow = oauth?.let { RuntimeAuthProviderOAuthFlow.fromWire(it.plainString("flow")) ?: return null },
        oauthMethodList = methods,
        apiKeyEntry = apiKey?.let { RuntimeAuthProviderKeyEntry.fromWire(it.plainString("entry")) ?: return null },
        stored = signIn?.plainString("stored")?.takeIf { it == "oauth" || it == "api_key" },
        description = obj.safeText("description", 200),
        group = group,
        configured = configured,
        endpoint = endpoint,
        needsModelIds = needsModelIds,
    )
}

/** slock `runtimeAuthProviderConnectResultSchema`. */
@RaftCoreInternalApi sealed interface RuntimeAuthProviderConnectResult {
    data object Connected : RuntimeAuthProviderConnectResult

    data class Failed(val reason: String) : RuntimeAuthProviderConnectResult

    data class Unsupported(val reason: RuntimeAuthUnavailableReason) : RuntimeAuthProviderConnectResult

    data object Busy : RuntimeAuthProviderConnectResult
}

@RaftCoreInternalApi fun JsonElement.toRuntimeAuthProviderConnectResult(): RuntimeAuthProviderConnectResult? {
    val obj = this as? JsonObject ?: return null
    return when (obj.plainString("kind")) {
        "connected" -> RuntimeAuthProviderConnectResult.Connected
        "failed" -> obj.plainString("reason")?.takeIf { it in RuntimeAuthProviderFailureReasons }?.let(RuntimeAuthProviderConnectResult::Failed)
        "unsupported" -> RuntimeAuthUnavailableReason.fromWire(obj.plainString("reason"))?.let(RuntimeAuthProviderConnectResult::Unsupported)
        "busy" -> RuntimeAuthProviderConnectResult.Busy
        else -> null
    }
}

/** `POST …/:runtime/login` body: the region for Kimi, the provider for Pi, nothing for the others. */
@Serializable
@RaftCoreInternalApi data class RuntimeAuthLoginStartBody(
    val region: String? = null,
    val providerId: String? = null,
    /** OpenCode: the OAuth sign-in the person chose, by its label. */
    val oauthMethod: String? = null,
)

/** `POST …/login/:loginId/code` body. The code is never logged. */
@Serializable
@RaftCoreInternalApi data class RuntimeAuthLoginCodeBody(val code: String)

/**
 * `POST …/:runtime/logout` body: the provider for Pi, nothing else for the others. [respondAsync]:
 * the sign-out may outlive the edge's 30 s, so its answer may come as a machine operation
 * (slock #14843, MachineOperations.kt); no default, so it is always written.
 */
@Serializable
@RaftCoreInternalApi data class RuntimeAuthLogoutBody(val providerId: String? = null, val respondAsync: Boolean)

/** `POST …/:runtime/connect-provider` body (Pi / OpenCode / Qwen Code). The key is never logged. */
@Serializable
@RaftCoreInternalApi data class RuntimeAuthConnectProviderBody(
    val providerId: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val modelIds: List<String>? = null,
)

/** Why a runtime-auth request got no answer to read: the HTTP status, the route's `code`, trace id, and refusal message. */
@RaftCoreInternalApi data class RuntimeAuthRequestError(
    val status: Int?,
    val code: String?,
    val traceId: String? = null,
    val serverMessage: String? = null,
)

/** A runtime-auth request's answer, or why there is none. */
@RaftCoreInternalApi sealed interface RuntimeAuthCall<out T> {
    data class Done<T>(val value: T) : RuntimeAuthCall<T>

    data class Refused(val error: RuntimeAuthRequestError) : RuntimeAuthCall<Nothing>

    /** The signed-in identity changed while it ran: never shown. */
    data object Superseded : RuntimeAuthCall<Nothing>
}

private fun isRuntimeAuthHttpsUrl(value: String): Boolean =
    value.length <= 4096 && value.startsWith("https://") && value.length > "https://".length && value.none { it.isWhitespace() }

/** slock `safeTextSchema(max)`: trimmed, 1..max characters, no control characters but tab and newline. */
private fun JsonObject.safeText(key: String, max: Int): String? {
    val value = plainString(key)?.trim() ?: return null
    if (value.isEmpty() || value.length > max) return null
    if (value.any { (it.code < 0x20 && it != '\t' && it != '\n') || it.code == 0x7f }) return null
    return value
}

private fun JsonObject.plainString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}
