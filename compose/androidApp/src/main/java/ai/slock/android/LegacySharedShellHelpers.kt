package ai.slock.android

import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.compose.data.AttachmentUploadFile

data class ComposerSubmissionDraft(
        val value: String,
        val asTask: Boolean,
        val attachments: List<AttachmentUploadFile>,
        val failedAttachmentIds: Set<String>
) {
    val body: String
        get() = value.trim()

    val hasContent: Boolean
        get() = body.isNotEmpty() || attachments.isNotEmpty()

    fun withAllAttachmentsFailed(): ComposerSubmissionDraft =
            copy(failedAttachmentIds = attachments.map { it.id }.toSet())
}

fun TaskSummary.mergeTaskReferenceCacheUpdate(incomingTask: TaskSummary): TaskSummary =
        incomingTask.withTaskReferenceFieldsFrom(this)

fun TaskSummary.withTaskReferenceFieldsFrom(referenceTask: TaskSummary): TaskSummary =
        copy(
                title = title.ifBlank { referenceTask.title },
                status = status,
                taskNumber = taskNumber ?: referenceTask.taskNumber,
                messageId = messageId ?: referenceTask.messageId,
                parentMessageId = parentMessageId ?: referenceTask.parentMessageId,
                channelId = channelId ?: referenceTask.channelId,
                threadId = threadId ?: referenceTask.threadId,
                isThreadTask = isThreadTask || referenceTask.isThreadTask,
                assignee = assignee ?: referenceTask.assignee,
                creator = creator ?: referenceTask.creator,
                channelName = channelName ?: referenceTask.channelName,
                updatedAtLabel = updatedAtLabel.ifBlank { referenceTask.updatedAtLabel },
                canClaim = canClaim
        )

fun TaskSummary.taskReferenceChooserTitle(): String =
        listOfNotNull(taskNumber?.let { "#$it" }, title.takeIf { it.isNotBlank() })
                .joinToString(" ")

fun TaskSummary.taskReferenceChooserMetaLine(): String =
        listOf(status.taskReferenceLabel, updatedAtLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

fun TaskSummary.taskReferenceChooserContextLine(): String =
        listOfNotNull(
                        channelName?.takeIf { it.isNotBlank() }?.let { "#$it" },
                        assignee?.let { member ->
                            member.handle.takeIf { it.isNotBlank() } ?: member.displayName
                        },
                        messageId?.value?.take(8)?.let { "msg $it" }
                )
                .joinToString(" · ")

private val TaskStatus.taskReferenceLabel: String
    get() =
            when (this) {
                TaskStatus.Todo -> "Todo"
                TaskStatus.InProgress -> "In progress"
                TaskStatus.InReview -> "In review"
                TaskStatus.Done -> "Done"
                TaskStatus.Closed -> "Closed"
            }

fun List<MemberProfile>.replaceMemberPreservingOrder(member: MemberProfile):
        List<MemberProfile> {
    var replaced = false
    val updated =
            map { existing ->
                if (existing.id == member.id) {
                    replaced = true
                    member
                } else {
                    existing
                }
            }
    return if (replaced) updated else updated + member
}
