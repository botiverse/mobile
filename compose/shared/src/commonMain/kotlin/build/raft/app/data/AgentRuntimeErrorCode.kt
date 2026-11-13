package build.raft.app.data

import kotlinx.serialization.Serializable

/**
 * Shared `RuntimeErrorCode` (botiverse/slock packages/shared/src/runtimeErrorVisibility.ts,
 * #13949 / #wg-rbac:#57): the closed list of runtime error codes. The server sends every viewer
 * the code (`lastRuntimeError.code`, `activityErrorCode`, the `agent:activity` `errorCode`) and
 * sends members no error text, only the code's English sentence or [GenericRuntimeErrorMessage].
 * The app shows its own copy per code (web #13985 `RUNTIME_ERROR_CODE_MESSAGE_ID`).
 */
@Serializable
enum class AgentRuntimeErrorCode(val wireValue: String) {
    UsageLimit("usage_limit"),
    Billing("billing"),
    ModelUnavailable("model_unavailable"),
    AuthRequired("auth_required"),
    NetworkTimeout("network_timeout"),
    InputTooLarge("input_too_large"),
    MessageTooLarge("message_too_large");

    companion object {
        /** Web `classifyRuntimeErrorCode`: a known code, else null (a newer server's code is no code). */
        fun fromWire(value: String?): AgentRuntimeErrorCode? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Shared `GENERIC_RUNTIME_ERROR_MESSAGE`: what the server sends a member in place of an uncoded
 * error's text. The app shows its own catalog line for it (web `activity.status.agentErrorFallback`).
 */
internal const val GenericRuntimeErrorMessage = "Agent encountered an error"
