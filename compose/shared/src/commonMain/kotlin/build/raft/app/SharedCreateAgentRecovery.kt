package build.raft.app

import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_FAILED
import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_REQUIRED
import build.raft.app.data.CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedResolvedAgentCreateForm
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig

/**
 * task #186 (artin): "表单落后就自动更新表单" — the user's single tap must
 * complete whenever it safely can.
 *
 * After a runtime-form 409 the caller refreshes the canonical contract; this
 * check decides whether the original dialog submit is still exactly valid
 * against the refreshed contract. If every original input survives, the submit
 * is rebound to the refreshed definition ref and may be resent once. If the
 * contract materially changed (provider gone, model gone, kind changed), this
 * returns null and the caller falls back to the explicit-confirm path — never
 * silently create something the user has not reviewed.
 */
internal fun SharedCreateAgentDialogSubmit.revalidatedAgainstRefreshedForm(
    refreshed: SharedResolvedAgentCreateForm
): SharedCreateAgentDialogSubmit? =
    revalidatedAgainstRefreshedSources(
        refreshedRef = refreshed.definition.ref,
        providerSource = refreshed.optionSources["provider"] as? SharedAgentCreateFormOptionSource.Select,
        modelSource = refreshed.optionSources["model"] as? SharedAgentCreateFormOptionSource.DependentSelect
    )

internal fun SharedCreateAgentDialogSubmit.revalidatedAgainstRefreshedSources(
    refreshedRef: SharedRuntimeFormDefinitionRef,
    providerSource: SharedAgentCreateFormOptionSource.Select?,
    modelSource: SharedAgentCreateFormOptionSource.DependentSelect?
): SharedCreateAgentDialogSubmit? {
    if (external) return null
    val config = runtimeConfig ?: return null
    if (config.runtime != "builtin") return null
    if (!refreshedRef.isSupportedBuiltInPi) return null
    if (providerSource == null || modelSource == null) return null
    val providerId =
        when (val provider = config.provider) {
            is RuntimeProviderConfig.Preset -> provider.providerId
            is RuntimeProviderConfig.Gateway -> provider.providerId
            else -> return null
        }
    val providerOption = providerSource.options.singleOrNull { it.value == providerId } ?: return null
    when (providerOption.providerKind) {
        "preset" -> {
            if (config.provider !is RuntimeProviderConfig.Preset) return null
            val modelId = (config.model as? RuntimeModelConfig.Preset)?.id ?: return null
            if (modelSource.optionsByValue[providerId].orEmpty().none { it.value == modelId }) return null
        }
        "gateway" -> {
            if (config.provider !is RuntimeProviderConfig.Gateway) return null
            if (modelSource.customValueAllowedByValue[providerId] != true) return null
        }
        else -> return null
    }
    return copy(formDefinitionRef = refreshedRef)
}

/**
 * Runtime-form 409 recovery with the one-tap contract:
 *
 * 1. refresh the canonical Built-in form contract (force);
 * 2. if the original inputs are still exactly valid against the refreshed
 *    contract, resend the create once with the refreshed ref — the user sees
 *    a single successful tap;
 * 3. otherwise (refresh failed / contract changed / second rejection) surface
 *    the explicit-confirm codes. Never retry more than once, and never replay
 *    when the outcome could differ from what the user reviewed.
 */
internal suspend fun <T> DataResult<T>.recoverRejectedCreateAgentFormOrRetry(
    formStore: SharedCreateAgentFormStore?,
    serverId: String?,
    submit: SharedCreateAgentDialogSubmit,
    retry: suspend (SharedCreateAgentDialogSubmit) -> DataResult<T>
): DataResult<T> =
    // Runtime form v2 carries no pinned schemaVersion, so there is no v1 form to refresh.
    if (submit.runtimeFormV2 != null) this else recoverRejectedCreateAgentFormOrRetryCore(
        refreshForm = { cleanServerId, cleanMachineId ->
            formStore?.loadSupportedBuiltInForm(cleanServerId, cleanMachineId, force = true)
        },
        revalidate = { refreshed -> submit.revalidatedAgainstRefreshedForm(refreshed) },
        serverId = serverId,
        machineId = submit.machineId,
        retry = retry
    )

internal suspend fun <T> DataResult<T>.recoverRejectedCreateAgentFormOrRetryCore(
    refreshForm: suspend (String, String) -> DataResult<SharedResolvedAgentCreateForm>?,
    revalidate: (SharedResolvedAgentCreateForm) -> SharedCreateAgentDialogSubmit?,
    serverId: String?,
    machineId: String?,
    retry: suspend (SharedCreateAgentDialogSubmit) -> DataResult<T>
): DataResult<T> {
    val error = (this as? DataResult.Error)?.error ?: return this
    if (error.code !in CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES) return this
    val cleanServerId = serverId?.trim().orEmpty()
    val cleanMachineId = machineId?.trim().orEmpty()
    if (cleanServerId.isBlank() || cleanMachineId.isBlank()) {
        return DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_FAILED, retryable = true))
    }
    val refreshed = refreshForm(cleanServerId, cleanMachineId)
    if (refreshed !is DataResult.Content) {
        return DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_FAILED, retryable = true))
    }
    val revalidated =
        revalidate(refreshed.data)
            ?: return DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_REQUIRED, retryable = true))
    val second = retry(revalidated)
    return if (second is DataResult.Error && second.error.code in CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES) {
        DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_REQUIRED, retryable = true))
    } else {
        second
    }
}
