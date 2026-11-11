package build.raft.app.data

import build.raft.app.model.TaskSummary
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Editing a task's title and description: `PATCH /api/tasks/:taskId` (botiverse/slock
 * `packages/server/src/routes/tasks.ts`, `taskRouter.patch("/:taskId")`, since #12233; the web's
 * editor is #14114, `packages/web/src/components/task/TaskHeadEditor.tsx`, at v1.21.64
 * 4a60ff938b6b9c44c65a48aa7df8ea40663f61fe).
 *
 * Body `{ title?, description?, clearDescription?, expectedRevision? }`: at least one of title /
 * description / clearDescription. The server trims the title and refuses an empty one (400); a
 * description blank after trimming clears it, as `clearDescription: true` does; `description` and
 * `clearDescription` together are a 400. Limits: [TaskTitleMaxLength], [TaskDescriptionMaxLength]
 * (slock `TASK_TITLE_MAX_LENGTH` / `TASK_DESCRIPTION_MAX_LENGTH`).
 *
 * Who may: a member of the task's channel who may post there (never a Guest, never on an
 * archived channel, a deleted channel's task or a historical joint task). 403 with the server's
 * sentence otherwise. `expectedRevision` is the OCC token: a stale one is a 409 with
 * `{ error, currentRevision }` and writes nothing. 200 answers `{ task }` (the projected task);
 * saving the text unchanged is a 200 without a revision bump. The server broadcasts the edit as
 * `task:updated` and writes the line in the task's discussion.
 */
@Serializable
internal data class TaskAmendRequestBody(
    val title: String? = null,
    val description: String? = null,
    val clearDescription: Boolean? = null,
    val expectedRevision: Long? = null,
)

/** slock `TASK_TITLE_MAX_LENGTH` (packages/shared/src/taskPermissions.ts). */
const val TaskTitleMaxLength = 10_000

/** slock `TASK_DESCRIPTION_MAX_LENGTH` (packages/shared/src/taskPermissions.ts). */
const val TaskDescriptionMaxLength = 50_000

/** What an edit of a task's wording sends (web `TaskAmendInput`): one field at a time from the head. */
sealed interface TaskAmendPatch {
    data class Title(val title: String) : TaskAmendPatch

    /** A blank [description] removes it (`clearDescription`), as the web's editor sends it. */
    data class Description(val description: String) : TaskAmendPatch
}

private val taskAmendJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** The PATCH body for [patch] (web taskStore `amendTask`). */
fun buildSharedTaskAmendBody(patch: TaskAmendPatch, expectedRevision: Long?): String {
    val body =
        when (patch) {
            is TaskAmendPatch.Title -> TaskAmendRequestBody(title = patch.title.trim(), expectedRevision = expectedRevision)
            is TaskAmendPatch.Description -> {
                val description = patch.description.trim()
                if (description.isEmpty()) {
                    TaskAmendRequestBody(clearDescription = true, expectedRevision = expectedRevision)
                } else {
                    TaskAmendRequestBody(description = description, expectedRevision = expectedRevision)
                }
            }
        }
    return taskAmendJson.encodeToString(body)
}

/** The answer to an edit, as the head's editor needs it. */
sealed interface TaskAmendResult {
    /** Saved (or nothing to change): the task as the server has it now. */
    data class Saved(val task: TaskSummary) : TaskAmendResult

    /**
     * Someone else changed the task first (409 with `currentRevision`): nothing was written. Saving
     * again goes against [currentRevision] (null: the server could not read it back).
     */
    data class Stale(val currentRevision: Long?) : TaskAmendResult

    /** Refused or failed: [error] carries the server's sentence and the request's trace ID. */
    data class Failed(val error: ScreenError) : TaskAmendResult
}

@Serializable
private data class TaskAmendErrorEnvelope(
    val error: String? = null,
    val currentRevision: Long? = null,
)

private val taskAmendErrorJson = Json { ignoreUnknownKeys = true }

/** Maps the `PATCH /tasks/:taskId` answer ([TaskAmendResult]). */
internal fun HomeNetworkResponse.toTaskAmendResult(
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): TaskAmendResult {
    if (!isSuccessful) {
        val payload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText?.takeIf { it.isNotBlank() }
        val raw = payload?.let { runCatching { taskAmendErrorJson.parseToJsonElement(it) }.getOrNull() }
        val envelope =
            payload?.let { runCatching { taskAmendErrorJson.decodeFromString(TaskAmendErrorEnvelope.serializer(), it) }.getOrNull() }
        // Only a lost revision race carries `currentRevision` (even as null); any other 409 (a
        // legacy card, an archived channel) is a refusal with its sentence (web `staleRevisionOf`).
        val stale = code == 409 && (raw as? kotlinx.serialization.json.JsonObject)?.containsKey("currentRevision") == true
        if (stale) return TaskAmendResult.Stale(envelope?.currentRevision)
        return TaskAmendResult.Failed(
            ScreenError(
                code = "tasks_amend_$code",
                retryable = code >= 500,
                serverMessage = envelope?.error?.trim()?.takeIf { it.isNotEmpty() },
                traceId = traceId ?: build.raft.app.network.RaftFailedRequestTraceIds.recent(),
            )
        )
    }
    val task =
        bodyText
            ?.let { runCatching { taskAmendErrorJson.decodeFromString(TaskEnvelope.serializer(), it) }.getOrNull() }
            ?.task
            ?.toTaskSummary(diagnostics)
            ?: return TaskAmendResult.Failed(ScreenError(code = "tasks_amend_contract_mismatch", retryable = false))
    return TaskAmendResult.Saved(task)
}
