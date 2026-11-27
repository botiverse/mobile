package build.raft.app

import build.raft.app.data.ServerLab
import build.raft.app.data.ServerLabState
import build.raft.app.data.ServerLabsFailure
import build.raft.app.data.ServerLabsLoadResult
import build.raft.app.data.ServerLabsMutationOutcome
import build.raft.app.data.ServerLabsMutationRequest
import build.raft.app.data.ServerLabsReadback
import build.raft.app.data.ServerLabsRepository
import build.raft.app.thread.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Settings > Labs, web LabsSection + serverLabsSettingsStore + serverLabsSettings
 * (botiverse/slock staging aa270b415d44 packages/web/src/features/settings/labs/LabsSection.tsx,
 * store/serverLabsSettingsStore.ts, utils/serverLabsSettings.ts):
 *  - a load shows the skeleton; 404 is "unavailable"; any other failure is "Failed to load".
 *  - one write at a time: every switch is disabled while one is on its way (web `mutationPending`).
 *  - a write sends the readback's `version` as `expectedVersion`; its answer replaces the readback.
 *    A failed write (a 409 version conflict included) keeps the readback and says "Failed to
 *    update Labs settings." (web `failServerLabsMutation(..., "settings.labs.failedUpdate")`).
 *  - an answer for another server is "did not match the active server"; an older readback never
 *    replaces a newer one of the same server (web `shouldPublishReadback`).
 */

/** Who may change what (web `getServerLabSettingsAuthority`). */
internal data class SettingsLabsAuthority(
    val canSetMasterAccess: Boolean,
    val canSetEnrollments: Boolean,
)

/**
 * The readback's own permissions when the server sent them, else the member's [role]: the owner
 * sets the master gate, the owner and admins the enrollments (web `isOwnerRole`, `isAdminOrOwner`).
 */
internal fun settingsLabsAuthority(role: String?, readback: ServerLabsReadback?): SettingsLabsAuthority {
    val normalized = role?.trim()?.lowercase()
    return SettingsLabsAuthority(
        canSetMasterAccess = readback?.canManageAccess ?: (normalized == "owner"),
        canSetEnrollments = readback?.canManageEnrollments ?: (normalized == "owner" || normalized == "admin"),
    )
}

/** web `isServerLabEnrollmentEditable`. */
internal fun settingsLabEnrollmentEditable(lab: ServerLab, authority: SettingsLabsAuthority, masterEnabled: Boolean): Boolean =
    masterEnabled && authority.canSetEnrollments && lab.state == ServerLabState.Open

/** Why a lab's switch is read-only (web `getServerLabEnrollmentDisabledReasonMessageId`); null: editable. */
internal enum class SettingsLabDisabledReason { MasterDisabled, AdminOnly, LifecycleReadOnly }

internal fun settingsLabDisabledReason(lab: ServerLab, authority: SettingsLabsAuthority, masterEnabled: Boolean): SettingsLabDisabledReason? =
    when {
        !masterEnabled -> SettingsLabDisabledReason.MasterDisabled
        !authority.canSetEnrollments -> SettingsLabDisabledReason.AdminOnly
        lab.state != ServerLabState.Open -> SettingsLabDisabledReason.LifecycleReadOnly
        else -> null
    }

/** The page's error line (web `errorId`). */
internal enum class SettingsLabsErrorKind { FailedLoad, FailedUpdate, ServerMismatch }

internal data class SettingsLabsError(
    val kind: SettingsLabsErrorKind,
    /** The failed response's trace id; null for a local check (server mismatch) or no response. */
    val traceId: String?,
)

internal data class SettingsLabsState(
    val serverId: String? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val readback: ServerLabsReadback? = null,
    val error: SettingsLabsError? = null,
    /** The write on its way: `master` or `lab:<key>` (web `savingTarget`); null: none. */
    val savingTarget: String? = null,
) {
    val mutationPending: Boolean
        get() = savingTarget != null
}

internal const val SettingsLabsMasterTarget = "master"

internal fun settingsLabTarget(labKey: String): String = "lab:$labKey"

internal class SettingsLabsStore(
    private val repository: ServerLabsRepository,
    private val scope: CoroutineScope,
    /** A write went through: the server's flags may evaluate differently now (web `refreshServerFeatureFlags`). */
    private val onApplied: (serverId: String) -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SettingsLabsState())
    val state: StateFlow<SettingsLabsState> = mutableState.asStateFlow()

    private var loadToken = 0L
    private var mutationToken = 0L

    /** Loads [serverId]'s Labs (web `loadServerLabsSettings`); a new server drops the old one's state. */
    fun load(serverId: String?) {
        val id = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val token = ++loadToken
        if (id == null) {
            mutationToken++
            mutableState.value = SettingsLabsState()
            return
        }
        mutableState.update { current ->
            SettingsLabsState(
                serverId = id,
                loading = true,
                savingTarget = current.savingTarget.takeIf { current.serverId == id },
            )
        }
        scope.launch {
            val result = repository.load(id)
            if (token != loadToken || mutableState.value.serverId != id) return@launch
            when (result) {
                is ServerLabsLoadResult.Loaded -> {
                    if (result.readback.serverId != id) {
                        logger("settings_labs_load_server_mismatch")
                        fail(id, SettingsLabsError(SettingsLabsErrorKind.ServerMismatch, traceId = null))
                    } else {
                        publish(id, result.readback)
                    }
                }
                ServerLabsLoadResult.Unavailable -> {
                    logger("settings_labs_unavailable")
                    mutableState.update { it.copy(loading = false, unavailable = true, readback = null, error = null) }
                }
                is ServerLabsLoadResult.Failed -> {
                    logger("settings_labs_load_failed code=${result.failure.error.code} serverCode=${result.failure.serverCode}")
                    fail(id, SettingsLabsError(SettingsLabsErrorKind.FailedLoad, result.failure.error.traceId))
                }
            }
        }
    }

    /** The owner's master gate (web `handleMasterChange`). */
    fun setMasterAccess(enabled: Boolean, role: String?) {
        val current = mutableState.value
        val readback = current.readback ?: return
        if (current.mutationPending || !settingsLabsAuthority(role, readback).canSetMasterAccess) return
        mutate(current, readback, SettingsLabsMasterTarget) { serverId, expectedVersion ->
            repository.setAccess(serverId, ServerLabsMutationRequest(enabled = enabled, expectedVersion = expectedVersion))
        }
    }

    /** One lab's enrollment (web `handleEnrollmentChange`). */
    fun setEnrollment(labKey: String, enabled: Boolean, role: String?) {
        val current = mutableState.value
        val readback = current.readback ?: return
        if (current.mutationPending) return
        val lab = readback.labs.firstOrNull { it.key == labKey } ?: return
        if (!settingsLabEnrollmentEditable(lab, settingsLabsAuthority(role, readback), readback.masterEnabled)) return
        mutate(current, readback, settingsLabTarget(labKey)) { serverId, expectedVersion ->
            repository.setEnrollment(serverId, labKey, ServerLabsMutationRequest(enabled = enabled, expectedVersion = expectedVersion))
        }
    }

    private fun mutate(
        current: SettingsLabsState,
        readback: ServerLabsReadback,
        target: String,
        request: suspend (serverId: String, expectedVersion: Long) -> ServerLabsMutationOutcome,
    ) {
        val serverId = current.serverId ?: return
        if (readback.serverId != serverId) return
        val token = ++mutationToken
        mutableState.value = current.copy(savingTarget = target)
        scope.launch {
            val outcome = request(serverId, readback.version)
            if (token != mutationToken || mutableState.value.serverId != serverId) return@launch
            when (outcome) {
                is ServerLabsMutationOutcome.Done -> {
                    val next = outcome.result.readback
                    if (next.serverId != serverId) {
                        logger("settings_labs_mutation_server_mismatch target=$target")
                        fail(serverId, SettingsLabsError(SettingsLabsErrorKind.ServerMismatch, traceId = null))
                    } else if (publish(serverId, next)) {
                        logger("settings_labs_mutation_done target=$target applied=${outcome.result.applied} version=${next.version}")
                        onApplied(serverId)
                    }
                }
                is ServerLabsMutationOutcome.Failed -> {
                    val failure: ServerLabsFailure = outcome.failure
                    logger(
                        "settings_labs_mutation_failed target=$target code=${failure.error.code} " +
                            "serverCode=${failure.serverCode} versionConflict=${failure.versionConflict}",
                    )
                    fail(serverId, SettingsLabsError(SettingsLabsErrorKind.FailedUpdate, failure.error.traceId))
                }
            }
            mutableState.update { if (it.serverId == serverId && token == mutationToken) it.copy(savingTarget = null) else it }
        }
    }

    /** web `shouldPublishReadback`: never an older version of the same server over a newer one. */
    private fun publish(serverId: String, readback: ServerLabsReadback): Boolean {
        var published = false
        mutableState.update { current ->
            published = false
            if (current.serverId != serverId) return@update current
            val existing = current.readback
            if (existing != null && existing.serverId == serverId && existing.version > readback.version) return@update current
            published = true
            current.copy(loading = false, unavailable = false, readback = readback, error = null)
        }
        return published
    }

    private fun fail(serverId: String, error: SettingsLabsError) {
        mutableState.update { current ->
            if (current.serverId != serverId) current else current.copy(loading = false, unavailable = false, error = error)
        }
    }
}
