package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.sharedSettingsWebUrl

/*
 * Add Computer's "The terminal shows a code? Enter it here." (web botiverse/slock 329e1700f7,
 * #15612): run over SSH, or anywhere without a browser, `raft-computer setup` shows a device code
 * instead of opening a page. Typed under the command, it is checked and approved in the sheet for
 * the sheet's server, with the device login page's describe / approve, confirm facts, step-up and
 * errors (web `features/computer/addComputerCode.ts`, `useAddComputerCode.ts`,
 * `components/auth/useDeviceLoginApproval.ts`, `deviceLoginApproval.ts`; staging 23ae82825 only
 * adds the pairing around them). A code for anything else (a sign-in, an agent key, another
 * server, a kind this app doesn't know) is not approved here: the device login page is offered.
 * The calls are DeviceLoginApprovalApi.kt; the sheet is computers/AddComputerCodeEntry.kt.
 */

/** The web's device login page: where a code the sheet can't approve is approved. Never with the code in it. */
@RaftCoreInternalApi const val DEVICE_LOGIN_WEB_PATH = "login/device"

/** The device login page on [webBaseUrl], the selected environment's web URL (production when unset). */
@RaftCoreInternalApi fun deviceLoginWebUrl(webBaseUrl: String?): String = sharedSettingsWebUrl(webBaseUrl, DEVICE_LOGIN_WEB_PATH)

/** A typed code as describe takes it; the server folds dashes, spaces and look-alikes itself (web `normalizeUserCode`). */
@RaftCoreInternalApi fun normalizeDeviceLoginUserCode(value: String): String = value.trim().uppercase()

@RaftCoreInternalApi const val DEVICE_LOGIN_GRANT_KIND_UNSUPPORTED = "grant_kind_unsupported"
private const val DEVICE_LOGIN_GRANT_SELECTION_REQUIRED = "grant_selection_required"
private const val DEVICE_LOGIN_GRANT_SELECTION_NOT_ALLOWED = "grant_selection_not_allowed"

/**
 * The sheet's server as describe lists it, when this request adds a computer (computer_attach)
 * or moves one's old connection over (computer_migrate) and the person may approve that for the
 * server [serverId]; else null: a sign-in, an agent key, a kind this app doesn't know, or a
 * server it can't add computers to here (web `codeTargetServer`).
 */
@RaftCoreInternalApi fun DeviceLoginRequest.addComputerTarget(serverId: String?): DeviceLoginServer? {
    val id = serverId?.takeIf { it.isNotBlank() } ?: return null
    val grant = grant ?: return null
    if (grant.kind != DeviceLoginGrantKind.ComputerAttach && grant.kind != DeviceLoginGrantKind.ComputerMigrate) return null
    return grant.servers.firstOrNull { it.id == id }
}

/** Refusals the device login page can still help with (another server, a kind only it explains): it is offered (web `codeErrorOffersDevicePage`). */
@RaftCoreInternalApi fun addComputerCodeOffersDevicePage(serverCode: String?): Boolean =
    serverCode == DEVICE_LOGIN_GRANT_KIND_UNSUPPORTED ||
        serverCode == DEVICE_LOGIN_GRANT_SELECTION_NOT_ALLOWED ||
        serverCode == DEVICE_LOGIN_GRANT_SELECTION_REQUIRED

/** The call the code is waiting on (web `DeviceLoginSubmitting`). */
enum class AddComputerCodeSubmitting { Describe, Approve, Deny }

/** What the approved line says was granted (web `GrantOutcome`, a server grant). */
data class AddComputerCodeOutcome(val migrate: Boolean, val machineName: String?, val serverName: String)

/**
 * Where the code is (web `DeviceLoginStep`). The web's `stepUp` step is the shared "Verify it's
 * you" dialog over the sheet here, opened while [Confirm] approves.
 */
sealed interface AddComputerCodeStep {
    /** The field under the command, closed until asked for. */
    data object Enter : AddComputerCodeStep

    /**
     * The confirm screen in the command's place. [target]: the sheet's server, to approve for;
     * null: not approved here, the device login page is offered.
     */
    data class Confirm(val request: DeviceLoginRequest, val target: DeviceLoginServer?) : AddComputerCodeStep

    /** Approved; the sheet keeps waiting for the computer to connect. */
    data class Approved(val outcome: AddComputerCodeOutcome) : AddComputerCodeStep

    /** Denied: nothing was added to [serverName]. */
    data class Denied(val serverName: String) : AddComputerCodeStep
}

/** What went wrong, as the device login page says it (web `showFailure` and `resolve`). */
enum class AddComputerCodeProblem {
    CodeInvalid,
    CodeExpired,
    CodeAlreadyUsed,
    KindUnsupported,
    ServerSelectionRequired,
    ServerSelectionNotAllowed,

    /** The approve asked for the second step again after it passed: Add to server asks once more. */
    StepUpRequired,

    /** Anything else: the server's own words when it gave some, else the step's fallback. */
    DescribeFailed,
    ApproveFailed,
    DenyFailed,
}

/**
 * [problem] with the server's code ([serverCode], web `errorCode`: some offer the device login
 * page), its words and the refusing response's trace id (null for a local refusal).
 */
data class AddComputerCodeError(
    val problem: AddComputerCodeProblem,
    val serverCode: String? = null,
    val serverMessage: String? = null,
    val traceId: String? = null,
) {
    val offersDevicePage: Boolean
        get() = addComputerCodeOffersDevicePage(serverCode)
}

/**
 * Add Computer's code field (web `useAddComputerCode`): whether it is open, what is typed, and
 * the device login it checks and approves. Held by the sheet, so the field keeps its text and its
 * error while the confirm screen takes the sheet and after it hands back. [token]: the request in
 * flight; an answer for another one (an earlier code, a closed sheet) is dropped.
 */
data class AddComputerCodeState(
    val open: Boolean = false,
    val code: String = "",
    val step: AddComputerCodeStep = AddComputerCodeStep.Enter,
    val submitting: AddComputerCodeSubmitting? = null,
    val error: AddComputerCodeError? = null,
    val token: Long = 0L,
) {
    /** Continue can check the typed code. */
    val canSubmit: Boolean
        get() = submitting == null && normalizeDeviceLoginUserCode(code).isNotEmpty()

    /** The confirm screen, while it takes the sheet's place. */
    val review: AddComputerCodeStep.Confirm?
        get() = step as? AddComputerCodeStep.Confirm
}

@RaftCoreInternalApi fun AddComputerCodeState.toggled(): AddComputerCodeState = copy(open = !open)

@RaftCoreInternalApi fun AddComputerCodeState.typed(text: String): AddComputerCodeState = if (text == code) this else copy(code = text)

/**
 * Enter a different code: back to an empty, open field, the old request gone (web
 * `differentCode`). Not while a call is in flight (the web's button is disabled then).
 */
@RaftCoreInternalApi fun AddComputerCodeState.differentCode(): AddComputerCodeState =
    if (submitting != null) this else copy(open = true, code = "", step = AddComputerCodeStep.Enter, error = null)

@RaftCoreInternalApi fun AddComputerCodeState.describing(token: Long): AddComputerCodeState =
    copy(submitting = AddComputerCodeSubmitting.Describe, error = null, token = token)

/** describe's answer for the sheet's server [serverId] (web `describe`). */
@RaftCoreInternalApi fun AddComputerCodeState.described(result: DataResult<DeviceLoginRequest>, serverId: String?): AddComputerCodeState {
    val request =
        (result as? DataResult.Content)?.data
            ?: return copy(step = AddComputerCodeStep.Enter, submitting = null, error = result.addComputerCodeError(AddComputerCodeProblem.DescribeFailed))
    if (request.grant != null && request.grant.kind == null) {
        // A kind the confirm screen can't show (web isKnownGrant): refused before any approve.
        return copy(
            step = AddComputerCodeStep.Enter,
            submitting = null,
            error = AddComputerCodeError(AddComputerCodeProblem.KindUnsupported, serverCode = DEVICE_LOGIN_GRANT_KIND_UNSUPPORTED),
        )
    }
    return copy(step = AddComputerCodeStep.Confirm(request, request.addComputerTarget(serverId)), submitting = null, error = null)
}

@RaftCoreInternalApi fun AddComputerCodeState.resolving(token: Long, approve: Boolean): AddComputerCodeState =
    copy(submitting = if (approve) AddComputerCodeSubmitting.Approve else AddComputerCodeSubmitting.Deny, error = null, token = token)

/** "Verify it's you" was closed: the confirm screen it came from, nothing approved (web `cancelStepUp`). */
@RaftCoreInternalApi fun AddComputerCodeState.stepUpClosed(): AddComputerCodeState = copy(submitting = null)

/** approve's (or deny's) answer for [request] on the sheet's server [target] (web `resolve`). */
@RaftCoreInternalApi fun AddComputerCodeState.resolved(
    result: DataResult<Unit>,
    approve: Boolean,
    request: DeviceLoginRequest,
    target: DeviceLoginServer,
): AddComputerCodeState {
    if (result is DataResult.Content) {
        val step =
            if (approve) {
                val grant = request.grant
                AddComputerCodeStep.Approved(
                    AddComputerCodeOutcome(migrate = grant?.kind == DeviceLoginGrantKind.ComputerMigrate, machineName = grant?.machineName, serverName = target.name)
                )
            } else {
                AddComputerCodeStep.Denied(target.name)
            }
        return copy(step = step, submitting = null, error = null)
    }
    val error = (result as? DataResult.Error)?.error
    val pending =
        when (error?.serverCode) {
            DEVICE_LOGIN_GRANT_SELECTION_REQUIRED -> AddComputerCodeProblem.ServerSelectionRequired
            DEVICE_LOGIN_GRANT_SELECTION_NOT_ALLOWED -> AddComputerCodeProblem.ServerSelectionNotAllowed
            SECOND_FACTOR_STEP_UP_REQUIRED_CODE -> AddComputerCodeProblem.StepUpRequired
            else -> null
        }
    if (pending != null) {
        // The code is still pending: the confirm screen stays, with why.
        return copy(submitting = null, error = AddComputerCodeError(pending, error?.serverCode, error?.serverMessage, error?.traceId))
    }
    // The code can't be approved any more (expired, used): back to typing one.
    return copy(
        step = AddComputerCodeStep.Enter,
        submitting = null,
        error = result.addComputerCodeError(if (approve) AddComputerCodeProblem.ApproveFailed else AddComputerCodeProblem.DenyFailed),
    )
}

/** A refused describe or approve as the device login page words it; [fallback] for any other refusal. */
private fun DataResult<*>.addComputerCodeError(fallback: AddComputerCodeProblem): AddComputerCodeError {
    val error = (this as? DataResult.Error)?.error
    val problem =
        when (error?.serverCode) {
            "user_code_invalid" -> AddComputerCodeProblem.CodeInvalid
            "expired" -> AddComputerCodeProblem.CodeExpired
            "already_resolved" -> AddComputerCodeProblem.CodeAlreadyUsed
            DEVICE_LOGIN_GRANT_KIND_UNSUPPORTED -> AddComputerCodeProblem.KindUnsupported
            else -> fallback
        }
    return AddComputerCodeError(problem, error?.serverCode, error?.serverMessage, error?.traceId)
}
