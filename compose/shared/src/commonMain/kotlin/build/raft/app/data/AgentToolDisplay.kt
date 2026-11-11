package build.raft.app.data

// The Activity log's name for a tool call, ported from botiverse/slock
// 853c6998bcef45df5c25e63b1035e2388608721d packages/shared/src/toolDisplay.ts
// (`TOOL_DISPLAY_METADATA[...].logLabel`, `KNOWN_TOOL_ALIASES`, `getToolLogLabel`,
// `shouldHideToolStartInActivityLog`). Since slock #13446 / #13461 the daemon names every OAR
// runtime's commands, file reads and edits, and searches by OAR's `classifyTool` (`bash`,
// `read_file`, `edit_file`, `grep`), so these labels read the same on every runtime.

/** Each known tool's semantic and its log label (`TOOL_DISPLAY_METADATA[semantic].logLabel`). */
private val AgentToolLogLabels: Map<String, String> =
    mapOf(
        "send_message" to "Sending message",
        "check_messages" to "Checking messages",
        "wait_for_message" to "Waiting for messages",
        "receive_message" to "Checking messages",
        "read_history" to "Reading history",
        "search_messages" to "Searching messages",
        "list_server" to "Viewing server info",
        "list_tasks" to "Listing tasks",
        "create_tasks" to "Creating tasks",
        "claim_tasks" to "Claiming tasks",
        "unclaim_task" to "Unclaiming task",
        "update_task_status" to "Updating task status",
        "add_channel_member" to "Adding channel member",
        "join_channel" to "Joining channel",
        "leave_channel" to "Leaving channel",
        "upload_file" to "Uploading file",
        "view_file" to "Viewing file",
        "read_file" to "Reading file",
        "write_file" to "Writing file",
        "edit_file" to "Editing file",
        "bash" to "Running command",
        "glob" to "Searching files",
        "grep" to "Searching code",
        "web_fetch" to "Fetching web",
        "web_search" to "Searching web",
        "todo_write" to "Updating tasks",
        "schedule_reminder" to "Scheduling reminder",
        "list_reminders" to "Listing reminders",
        "cancel_reminder" to "Canceling reminder",
        "collab_tool_call" to "Collaborating",
        "spawn_subagent" to "Starting sub-agent",
    )

/** `KNOWN_TOOL_ALIASES`: a runtime's tool name and the semantic it stands for (case-sensitive). */
private val AgentToolAliases: Map<String, String> =
    AgentToolLogLabels.keys.associateWith { it } +
        mapOf(
            "Read" to "read_file",
            "ReadFile" to "read_file",
            "file_read" to "read_file",
            "Write" to "write_file",
            "WriteFile" to "write_file",
            "file_write" to "write_file",
            "Edit" to "edit_file",
            "EditFile" to "edit_file",
            "file_change" to "edit_file",
            "StrReplaceFile" to "edit_file",
            "Bash" to "bash",
            "shell" to "bash",
            "Shell" to "bash",
            "command_execution" to "bash",
            "run_shell_command" to "bash",
            "run_terminal_command" to "bash",
            "Glob" to "glob",
            "search_files" to "glob",
            "Grep" to "grep",
            "WebFetch" to "web_fetch",
            "fetch_url" to "web_fetch",
            "FetchURL" to "web_fetch",
            "WebSearch" to "web_search",
            "SearchWeb" to "web_search",
            "TodoWrite" to "todo_write",
            "SetTodoList" to "todo_write",
            "Agent" to "spawn_subagent",
            "Task" to "spawn_subagent",
        ) - setOf("todo_write", "spawn_subagent")

private val AgentToolChatNamespacePrefixes = listOf("mcp__chat__", "mcp_chat_")

private val AgentToolMcpNamespace = Regex("^mcp__\\w+__")

private fun String.withoutChatToolNamespace(): String {
    for (prefix in AgentToolChatNamespacePrefixes) {
        if (startsWith(prefix)) return removePrefix(prefix)
    }
    return this
}

/** `resolveToolSemantic`: the semantic a tool name stands for, or null for a tool Raft doesn't know. */
internal fun agentToolSemantic(toolName: String): String? = AgentToolAliases[toolName.withoutChatToolNamespace()]

/** `getToolLogLabel`: a known tool's log label; any other keeps its own name, without an MCP namespace. */
internal fun agentToolLogLabel(toolName: String): String =
    agentToolSemantic(toolName)?.let(AgentToolLogLabels::getValue)
        ?: toolName.withoutChatToolNamespace().replaceFirst(AgentToolMcpNamespace, "")

/** `shouldHideToolStartInActivityLog`: a `send_message` start says nothing its message doesn't. */
internal fun agentToolStartHiddenInActivityLog(toolName: String): Boolean = agentToolSemantic(toolName) == "send_message"
