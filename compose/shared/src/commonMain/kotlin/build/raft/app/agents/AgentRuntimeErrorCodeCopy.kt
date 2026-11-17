package build.raft.app

import build.raft.app.data.AgentRuntimeErrorCode
import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Web `RUNTIME_ERROR_CODE_MESSAGE_ID` (classifyRuntimeError.ts, botiverse/slock #13985): per code,
 * a short title (status rows and chips), the sentence a member reads (no error text reaches them),
 * and the sentence the agent's managers read above the error's detail.
 */
internal class AgentRuntimeErrorCodeCopy(
    val title: StringResource,
    val member: StringResource,
    val manager: StringResource,
)

internal val AgentRuntimeErrorCode.messages: AgentRuntimeErrorCodeCopy
    get() =
        when (this) {
            AgentRuntimeErrorCode.UsageLimit ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_usage_limit_title,
                    Res.string.agent_runtime_error_code_usage_limit_member,
                    Res.string.agent_runtime_error_code_usage_limit_manager,
                )
            AgentRuntimeErrorCode.Billing ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_billing_title,
                    Res.string.agent_runtime_error_code_billing_member,
                    Res.string.agent_runtime_error_code_billing_manager,
                )
            AgentRuntimeErrorCode.ModelUnavailable ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_model_unavailable_title,
                    Res.string.agent_runtime_error_code_model_unavailable_member,
                    Res.string.agent_runtime_error_code_model_unavailable_manager,
                )
            AgentRuntimeErrorCode.AuthRequired ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_auth_required_title,
                    Res.string.agent_runtime_error_code_auth_required_member,
                    Res.string.agent_runtime_error_code_auth_required_manager,
                )
            AgentRuntimeErrorCode.NetworkTimeout ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_network_timeout_title,
                    Res.string.agent_runtime_error_code_network_timeout_member,
                    Res.string.agent_runtime_error_code_network_timeout_manager,
                )
            AgentRuntimeErrorCode.InputTooLarge ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_input_too_large_title,
                    Res.string.agent_runtime_error_code_input_too_large_member,
                    Res.string.agent_runtime_error_code_input_too_large_manager,
                )
            AgentRuntimeErrorCode.MessageTooLarge ->
                AgentRuntimeErrorCodeCopy(
                    Res.string.agent_runtime_error_code_message_too_large_title,
                    Res.string.agent_runtime_error_code_message_too_large_member,
                    Res.string.agent_runtime_error_code_message_too_large_manager,
                )
        }

/**
 * Web `getActivityTextDescriptor` "error" (#13985): what an Error status reads before its raw
 * detail. A coded error reads as its title for every viewer; an uncoded one a member reads arrives
 * as the server's fixed English line, shown as the catalog's. Null: show the detail as before.
 */
internal fun agentErrorStatusCopy(errorCode: AgentRuntimeErrorCode?, detail: String?): StringResource? =
    errorCode?.messages?.title
        ?: Res.string.member_state_agent_error.takeIf { detail?.trim() == build.raft.app.data.GenericRuntimeErrorMessage }
