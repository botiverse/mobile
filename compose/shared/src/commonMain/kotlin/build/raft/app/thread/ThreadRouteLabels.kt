package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * The localized labels, templates and term bundles `SharedThreadRouteContentContent` uses for its
 * toasts and errors, read in the same order as before on every composition of the route. Kept out
 * of the route function to shrink its JVM method (see scripts/ci/check_jvm_method_size.py).
 */
@Immutable
internal data class ThreadRouteLabels(
    val actionCardSuccessLabels: Map<String, String>,
    val actionCardFailureLabels: Map<String, String>,
    val genericActionCardSuccessLabel: String,
    val genericActionCardFailureLabel: String,
    val actionCardReconfirmFailedLabel: String,
    val commentsUnavailableLabel: String,
    val unableToSendCommentLabel: String,
    val unableToResolveCommentLabel: String,
    val unableToOpenAttachmentLabel: String,
    val unableToDownloadAttachmentLabel: String,
    val openAttachmentActionLabel: String,
    val attachmentOpenResultTerms: RaftAttachmentOpenResultTerms,
    val attachmentSavedToDownloadsLabel: String,
    val attachmentSavedToFilesLabel: String,
    val attachmentSavedToPhotosLabel: String,
    val attachmentDownloadedLabel: String,
    val attachmentUploadFailedLabel: String,
    val preparingAttachmentPreviewLabel: String,
    val attachmentUploadFailedDetailTemplate: String,
    val attachmentLimitUnavailableLabel: String,
    val attachmentTooLargeTemplate: String,
    val unableToOpenRepliesLabel: String,
    val unableToOpenParentMessageLabel: String,
    val unableToOpenTaskLabel: String,
    val unableToOpenTargetTemplate: String,
    val unableToOpenTaskNumberTemplate: String,
    val referenceNotFoundTemplate: String,
    val referenceNoAccessTemplate: String,
    val referenceOtherServerTemplate: String,
    val genericThreadErrorLabel: String,
    val pendingMentionActionBatchStrings: PendingMentionActionBatchStrings,
    val attachmentPickerUnavailableLabel: String,
    val fileSourceUnavailableLabel: String,
    val copiedLabel: String,
    val failureDetailsCopiedLabel: String,
    val linksCopiedLabel: String,
    val forwardCompletedTemplate: String,
    val forwardJoinFirstTemplate: String,
    val unableToCopyLinkLabel: String,
    val noTextToSelectLabel: String,
    val followedThreadLabel: String,
    val unableToFollowThreadLabel: String,
    val unfollowedThreadLabel: String,
    val unableToUnfollowThreadLabel: String,
    val searchUnavailableLabel: String,
    val actionUnavailableLabel: String,
    val unableToUpdateReactionLabel: String,
    val commentSourceUnavailableLabel: String,
    val memberNotFoundTemplate: String,
    val memberAddedLabel: String,
    val unableToAddMemberLabel: String,
    val memberRemovedLabel: String,
    val unableToRemoveMemberLabel: String,
    val channelArchivedLabel: String,
    val unableToArchiveChannelLabel: String,
    val channelDeletedLabel: String,
    val unableToDeleteChannelLabel: String,
    val unableToCreateTaskLabel: String,
    val unableToSendTaskMessageLabel: String,
    val unsupportedTaskStatusLabel: String,
    val unableToUpdateTaskLabel: String,
    val unableToUpdateAssigneeLabel: String,
    val unableToJoinChannelLabel: String,
    val joinedChannelLabel: String,
    val attachmentRetryUnavailableLabel: String,
    val mergedForwardModeLabel: String,
    val individualForwardModeLabel: String,
    val imageSavedLabel: String,
    val unableToExportImageLabel: String,
    val unableToForwardLabel: String,
    val unableToSaveMessageLabel: String,
    val unableToRemoveSavedMessageLabel: String,
    val attachmentSkippedOneLabel: String,
    val dropFoldersCantUploadLabel: String,
    val dropFoldersSkippedLabel: String,
    val attachmentSkippedManyTemplate: String,
    val unableToOpenThreadRefLabel: String,
    val externalShareOtherServerTemplate: String,
    val externalShareOtherServerGenericLabel: String,
)

@Composable
internal fun threadRouteLabels(): ThreadRouteLabels {
    val actionCardSuccessLabels =
        mapOf(
            "channel:create" to stringResource(Res.string.thread_action_card_success_channel),
            "agent:create" to stringResource(Res.string.thread_action_card_success_agent),
            "channel:add_member" to stringResource(Res.string.thread_action_card_success_members),
            "integration:approve_agent_login" to stringResource(Res.string.thread_action_card_login_approved)
        )
    val actionCardFailureLabels =
        mapOf(
            "channel:create" to stringResource(Res.string.thread_action_card_failure_channel),
            "agent:create" to stringResource(Res.string.thread_action_card_failure_agent),
            "channel:add_member" to stringResource(Res.string.thread_action_card_failure_members),
            "integration:approve_agent_login" to stringResource(Res.string.thread_action_card_failure_login)
        )
    val genericActionCardSuccessLabel = stringResource(Res.string.thread_action_card_success_action)
    val genericActionCardFailureLabel = stringResource(Res.string.thread_action_card_failure_action)
    val actionCardReconfirmFailedLabel = stringResource(Res.string.thread_action_card_reconfirm_failed)
    val commentsUnavailableLabel = stringResource(Res.string.thread_error_comments_unavailable)
    val unableToSendCommentLabel = stringResource(Res.string.thread_error_send_comment)
    val unableToResolveCommentLabel = stringResource(Res.string.thread_error_resolve_comment)
    val unableToOpenAttachmentLabel = stringResource(Res.string.thread_error_open_attachment)
    val unableToDownloadAttachmentLabel = stringResource(Res.string.file_preview_error_download_attachment)
    val openAttachmentActionLabel = stringResource(Res.string.file_preview_action_open)
    val attachmentOpenResultTerms =
        RaftAttachmentOpenResultTerms(
            downloadStopped = stringResource(Res.string.file_preview_download_stopped),
            unableToDownloadAttachment = unableToDownloadAttachmentLabel,
            noAppCanOpenFile = stringResource(Res.string.file_preview_error_no_app),
            storageUnavailable = stringResource(Res.string.file_preview_error_storage_unavailable),
            downloadFailed = stringResource(Res.string.file_preview_download_failed),
            unableToOpenAttachment = unableToOpenAttachmentLabel
        )
    val attachmentSavedToDownloadsLabel = stringResource(Res.string.file_preview_download_saved)
    val attachmentSavedToFilesLabel = stringResource(Res.string.file_preview_export_saved)
    val attachmentSavedToPhotosLabel = stringResource(Res.string.thread_image_preview_saved_to_photos)
    val attachmentDownloadedLabel = stringResource(Res.string.file_preview_private_download_saved)
    val attachmentUploadFailedLabel = stringResource(Res.string.thread_error_attachment_upload)
    val preparingAttachmentPreviewLabel = stringResource(Res.string.thread_state_preparing_attachment)
    val attachmentUploadFailedDetailTemplate =
        stringResource(Res.string.thread_error_attachment_upload_detail, ThreadLocalizedFeedbackPlaceholder)
    val attachmentLimitUnavailableLabel = stringResource(Res.string.thread_error_attachment_limit_unavailable)
    val attachmentTooLargeTemplate =
        stringResource(
            Res.string.thread_error_attachment_too_large_detail,
            ThreadLocalizedFeedbackPlaceholder,
            "__RAFT_ATTACHMENT_LIMIT__"
        )
    val unableToOpenRepliesLabel = stringResource(Res.string.thread_error_open_replies)
    val unableToOpenParentMessageLabel = stringResource(Res.string.thread_error_open_parent_message)
    val unableToOpenTaskLabel = stringResource(Res.string.thread_error_open_task)
    val unableToOpenTargetTemplate =
        stringResource(Res.string.thread_error_open_target, ThreadLocalizedFeedbackPlaceholder)
    val unableToOpenTaskNumberTemplate =
        stringResource(Res.string.thread_error_open_task_number, ThreadLocalizedFeedbackPlaceholder)
    val referenceNotFoundTemplate =
        stringResource(Res.string.thread_error_ref_not_found, ThreadLocalizedFeedbackPlaceholder)
    val referenceNoAccessTemplate =
        stringResource(Res.string.thread_error_ref_no_access, ThreadLocalizedFeedbackPlaceholder)
    val referenceOtherServerTemplate =
        stringResource(Res.string.thread_error_ref_other_server, ThreadLocalizedFeedbackPlaceholder)
    val genericThreadErrorLabel = stringResource(Res.string.common_error)
    val pendingMentionActionBatchStrings =
        PendingMentionActionBatchStrings(
            resultTemplate =
                stringResource(
                    Res.string.thread_composer_mention_batch_result,
                    "__RAFT_ADDED__",
                    "__RAFT_TOTAL__",
                    "__RAFT_ERROR__"
                ),
            resultSummaryTemplate =
                stringResource(
                    Res.string.thread_composer_mention_batch_result_summary,
                    "__RAFT_ADDED__",
                    "__RAFT_TOTAL__"
                ),
            missing = stringResource(Res.string.thread_composer_mention_batch_error_missing),
            deliveryUnavailable = stringResource(Res.string.thread_composer_mention_batch_error_delivery_unavailable),
            dropped = stringResource(Res.string.thread_composer_mention_batch_error_dropped),
            notifyDropped = stringResource(Res.string.thread_composer_mention_action_error_notify_dropped),
            senderLacksChannelAccess = stringResource(Res.string.thread_composer_mention_batch_error_sender_access),
            targetLacksReadAccess = stringResource(Res.string.thread_composer_mention_batch_error_target_access),
            addNotAllowedForSurface = stringResource(Res.string.thread_composer_mention_batch_error_surface),
            addRequiresHumanAuthority = stringResource(Res.string.thread_composer_mention_batch_error_human_authority),
            addRequiresMemberManagementAuthority =
                stringResource(Res.string.thread_composer_mention_batch_error_member_authority),
            noPermission = stringResource(Res.string.thread_composer_mention_batch_error_permission),
            targetAlreadyMember = stringResource(Res.string.thread_composer_mention_batch_error_already_member),
            targetUnavailable = stringResource(Res.string.thread_composer_mention_batch_error_target_unavailable),
            stale = stringResource(Res.string.thread_composer_mention_batch_error_stale),
            expired = stringResource(Res.string.thread_composer_mention_batch_error_expired),
            ambiguous = stringResource(Res.string.thread_composer_mention_batch_error_ambiguous),
            generic = stringResource(Res.string.thread_composer_mention_batch_error_generic),
            requestConnection = stringResource(Res.string.thread_composer_mention_request_error_connection),
            requestAuthentication =
                stringResource(Res.string.thread_composer_mention_request_error_authentication),
            requestPermission = stringResource(Res.string.thread_composer_mention_request_error_permission),
            requestRateLimited = stringResource(Res.string.thread_composer_mention_request_error_rate_limited),
            requestServer = stringResource(Res.string.thread_composer_mention_request_error_server),
            requestUnexpectedResponse =
                stringResource(Res.string.thread_composer_mention_request_error_unexpected_response),
            requestGeneric = stringResource(Res.string.thread_composer_mention_request_error_generic)
        )
    val attachmentPickerUnavailableLabel = stringResource(Res.string.thread_error_attachment_picker)
    val fileSourceUnavailableLabel = stringResource(Res.string.thread_error_file_source)
    val copiedLabel = stringResource(Res.string.thread_feedback_copied)
    val failureDetailsCopiedLabel = stringResource(Res.string.thread_send_failure_details_copied)
    val linksCopiedLabel = stringResource(Res.string.thread_feedback_links_copied)
    val forwardCompletedTemplate =
        stringResource(
            Res.string.thread_forward_completed,
            ThreadLocalizedFeedbackPlaceholder,
            "__RAFT_TARGET__"
        )
    val forwardJoinFirstTemplate =
        stringResource(Res.string.thread_forward_join_first, ThreadLocalizedFeedbackPlaceholder)
    val unableToCopyLinkLabel = stringResource(Res.string.thread_error_copy_link)
    val noTextToSelectLabel = stringResource(Res.string.thread_error_no_text_select)
    val followedThreadLabel = stringResource(Res.string.thread_feedback_followed)
    val unableToFollowThreadLabel = stringResource(Res.string.thread_error_follow)
    val unfollowedThreadLabel = stringResource(Res.string.thread_feedback_unfollowed)
    val unableToUnfollowThreadLabel = stringResource(Res.string.thread_error_unfollow)
    val searchUnavailableLabel = stringResource(Res.string.thread_error_search_unavailable)
    val actionUnavailableLabel = stringResource(Res.string.thread_error_action_unavailable)
    val unableToUpdateReactionLabel = stringResource(Res.string.thread_error_update_reaction)
    val commentSourceUnavailableLabel = stringResource(Res.string.thread_error_comment_source)
    val memberNotFoundTemplate =
        stringResource(Res.string.thread_error_member_not_found, ThreadLocalizedFeedbackPlaceholder)
    val memberAddedLabel = stringResource(Res.string.thread_feedback_member_added)
    val unableToAddMemberLabel = stringResource(Res.string.thread_error_add_member)
    val memberRemovedLabel = stringResource(Res.string.thread_feedback_member_removed)
    val unableToRemoveMemberLabel = stringResource(Res.string.thread_error_remove_member)
    val channelArchivedLabel = stringResource(Res.string.thread_feedback_channel_archived)
    val unableToArchiveChannelLabel = stringResource(Res.string.thread_error_archive_channel)
    val channelDeletedLabel = stringResource(Res.string.thread_feedback_channel_deleted)
    val unableToDeleteChannelLabel = stringResource(Res.string.thread_error_delete_channel)
    val unableToCreateTaskLabel = stringResource(Res.string.tasks_create_failed)
    val unableToSendTaskMessageLabel = stringResource(Res.string.task_conversation_send_failed)
    val unsupportedTaskStatusLabel = stringResource(Res.string.thread_error_task_status_unsupported)
    val unableToUpdateTaskLabel = stringResource(Res.string.thread_error_update_task)
    val unableToUpdateAssigneeLabel = stringResource(Res.string.thread_error_update_assignee)
    val unableToJoinChannelLabel = stringResource(Res.string.thread_error_join_channel)
    val joinedChannelLabel = stringResource(Res.string.thread_feedback_joined_channel)
    val attachmentRetryUnavailableLabel = stringResource(Res.string.thread_error_attachment_retry)
    val mergedForwardModeLabel = stringResource(Res.string.thread_forward_mode_merged)
    val individualForwardModeLabel = stringResource(Res.string.thread_forward_mode_individual)
    val imageSavedLabel = stringResource(Res.string.thread_feedback_image_saved)
    val unableToExportImageLabel = stringResource(Res.string.thread_error_export_image)
    val unableToForwardLabel = stringResource(Res.string.thread_error_forward)
    val unableToSaveMessageLabel = stringResource(Res.string.thread_error_save_message)
    val unableToRemoveSavedMessageLabel = stringResource(Res.string.thread_error_remove_saved_message)
    val attachmentSkippedOneLabel = stringResource(Res.string.thread_attachment_skipped_one)
    val dropFoldersCantUploadLabel = stringResource(Res.string.thread_composer_drop_folders_cant_upload)
    val dropFoldersSkippedLabel = stringResource(Res.string.thread_composer_drop_folders_skipped)
    val attachmentSkippedManyTemplate =
        stringResource(Res.string.thread_attachment_skipped_many, ThreadLocalizedFeedbackPlaceholder)
    val unableToOpenThreadRefLabel = stringResource(Res.string.thread_error_open_thread_ref)
    val externalShareOtherServerTemplate =
        stringResource(Res.string.thread_composer_external_share_other_server, ThreadLocalizedFeedbackPlaceholder)
    val externalShareOtherServerGenericLabel =
        stringResource(Res.string.thread_composer_external_share_other_server_generic)
    return ThreadRouteLabels(
        actionCardSuccessLabels = actionCardSuccessLabels,
        actionCardFailureLabels = actionCardFailureLabels,
        genericActionCardSuccessLabel = genericActionCardSuccessLabel,
        genericActionCardFailureLabel = genericActionCardFailureLabel,
        actionCardReconfirmFailedLabel = actionCardReconfirmFailedLabel,
        commentsUnavailableLabel = commentsUnavailableLabel,
        unableToSendCommentLabel = unableToSendCommentLabel,
        unableToResolveCommentLabel = unableToResolveCommentLabel,
        unableToOpenAttachmentLabel = unableToOpenAttachmentLabel,
        unableToDownloadAttachmentLabel = unableToDownloadAttachmentLabel,
        openAttachmentActionLabel = openAttachmentActionLabel,
        attachmentOpenResultTerms = attachmentOpenResultTerms,
        attachmentSavedToDownloadsLabel = attachmentSavedToDownloadsLabel,
        attachmentSavedToFilesLabel = attachmentSavedToFilesLabel,
        attachmentSavedToPhotosLabel = attachmentSavedToPhotosLabel,
        attachmentDownloadedLabel = attachmentDownloadedLabel,
        attachmentUploadFailedLabel = attachmentUploadFailedLabel,
        preparingAttachmentPreviewLabel = preparingAttachmentPreviewLabel,
        attachmentUploadFailedDetailTemplate = attachmentUploadFailedDetailTemplate,
        attachmentLimitUnavailableLabel = attachmentLimitUnavailableLabel,
        attachmentTooLargeTemplate = attachmentTooLargeTemplate,
        unableToOpenRepliesLabel = unableToOpenRepliesLabel,
        unableToOpenParentMessageLabel = unableToOpenParentMessageLabel,
        unableToOpenTaskLabel = unableToOpenTaskLabel,
        unableToOpenTargetTemplate = unableToOpenTargetTemplate,
        unableToOpenTaskNumberTemplate = unableToOpenTaskNumberTemplate,
        referenceNotFoundTemplate = referenceNotFoundTemplate,
        referenceNoAccessTemplate = referenceNoAccessTemplate,
        referenceOtherServerTemplate = referenceOtherServerTemplate,
        genericThreadErrorLabel = genericThreadErrorLabel,
        pendingMentionActionBatchStrings = pendingMentionActionBatchStrings,
        attachmentPickerUnavailableLabel = attachmentPickerUnavailableLabel,
        fileSourceUnavailableLabel = fileSourceUnavailableLabel,
        copiedLabel = copiedLabel,
        failureDetailsCopiedLabel = failureDetailsCopiedLabel,
        linksCopiedLabel = linksCopiedLabel,
        forwardCompletedTemplate = forwardCompletedTemplate,
        forwardJoinFirstTemplate = forwardJoinFirstTemplate,
        unableToCopyLinkLabel = unableToCopyLinkLabel,
        noTextToSelectLabel = noTextToSelectLabel,
        followedThreadLabel = followedThreadLabel,
        unableToFollowThreadLabel = unableToFollowThreadLabel,
        unfollowedThreadLabel = unfollowedThreadLabel,
        unableToUnfollowThreadLabel = unableToUnfollowThreadLabel,
        searchUnavailableLabel = searchUnavailableLabel,
        actionUnavailableLabel = actionUnavailableLabel,
        unableToUpdateReactionLabel = unableToUpdateReactionLabel,
        commentSourceUnavailableLabel = commentSourceUnavailableLabel,
        memberNotFoundTemplate = memberNotFoundTemplate,
        memberAddedLabel = memberAddedLabel,
        unableToAddMemberLabel = unableToAddMemberLabel,
        memberRemovedLabel = memberRemovedLabel,
        unableToRemoveMemberLabel = unableToRemoveMemberLabel,
        channelArchivedLabel = channelArchivedLabel,
        unableToArchiveChannelLabel = unableToArchiveChannelLabel,
        channelDeletedLabel = channelDeletedLabel,
        unableToDeleteChannelLabel = unableToDeleteChannelLabel,
        unableToCreateTaskLabel = unableToCreateTaskLabel,
        unableToSendTaskMessageLabel = unableToSendTaskMessageLabel,
        unsupportedTaskStatusLabel = unsupportedTaskStatusLabel,
        unableToUpdateTaskLabel = unableToUpdateTaskLabel,
        unableToUpdateAssigneeLabel = unableToUpdateAssigneeLabel,
        unableToJoinChannelLabel = unableToJoinChannelLabel,
        joinedChannelLabel = joinedChannelLabel,
        attachmentRetryUnavailableLabel = attachmentRetryUnavailableLabel,
        mergedForwardModeLabel = mergedForwardModeLabel,
        individualForwardModeLabel = individualForwardModeLabel,
        imageSavedLabel = imageSavedLabel,
        unableToExportImageLabel = unableToExportImageLabel,
        unableToForwardLabel = unableToForwardLabel,
        unableToSaveMessageLabel = unableToSaveMessageLabel,
        unableToRemoveSavedMessageLabel = unableToRemoveSavedMessageLabel,
        attachmentSkippedOneLabel = attachmentSkippedOneLabel,
        dropFoldersCantUploadLabel = dropFoldersCantUploadLabel,
        dropFoldersSkippedLabel = dropFoldersSkippedLabel,
        attachmentSkippedManyTemplate = attachmentSkippedManyTemplate,
        unableToOpenThreadRefLabel = unableToOpenThreadRefLabel,
        externalShareOtherServerTemplate = externalShareOtherServerTemplate,
        externalShareOtherServerGenericLabel = externalShareOtherServerGenericLabel
    )
}
