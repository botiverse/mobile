package build.raft.app

import build.raft.app.assistant.LocalThreadAssistantOtter
import build.raft.app.assistant.ThreadAssistantContextChip
import build.raft.app.assistant.ThreadAssistantOtterFrameEdge
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.raftToastAvoid
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.app.base.putIfAbsent
import build.raft.app.data.PlatformFile
import build.raft.app.data.map
import build.raft.app.format.RaftFormat
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.model.MemberServerRef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject

internal enum class ThreadPickedAttachmentComposerAction {
    None,
    OpenImageAnnotation,
    UploadToComposer
}

internal fun resolveThreadPickedAttachmentComposerAction(
    action: String,
    fileCount: Int,
    allFilesAreImages: Boolean = true,
): ThreadPickedAttachmentComposerAction {
    return when {
        fileCount <= 0 -> ThreadPickedAttachmentComposerAction.None
        (action == ThreadComposerAttachImageAction || action == ThreadComposerCaptureImageAction) &&
            allFilesAreImages ->
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation
        else -> ThreadPickedAttachmentComposerAction.UploadToComposer
    }
}

/**
 * Decision returned by the composer remove boundary.
 *
 * Removing a tile and retiring its native temporary bytes are two different operations.  The
 * reducer owns the former; this helper only decides whether the request belongs to the currently
 * mounted draft and, when the row is a cache-owned local image, which ref may be retired.  A
 * server-owned/remote row is still removable, but its URL/ref is never handed to the native
 * retirement bridge.
 */
internal data class ThreadComposerAttachmentRemoval(
    val accepted: Boolean,
    val reason: String = "",
    val fileRefToRetire: String? = null
)

private val ThreadComposerImageFileExtensions =
    setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "svg", "webp")

/**
 * Normalize a local file handle for ownership comparisons without turning it into a new source of
 * truth.  Native retirement performs the final cache-root check; this boundary only admits the
 * two representations emitted by our hosts (an absolute path or a host-less file URI) and rejects
 * ambiguous URI/path forms before they can reach that bridge.
 */
private fun normalizeThreadComposerOwnedFileRef(fileRef: String): Pair<String, String>? {
    val raw = fileRef.trim()
    if (raw.isBlank() || raw.any(Char::isISOControl) || '?' in raw || '#' in raw || '\\' in raw) {
        return null
    }
    val path =
        when {
            raw.startsWith("/") -> raw
            raw.startsWith("file://", ignoreCase = true) -> {
                val authorityAndPath = raw.substring(7)
                // `file://host/path` is not local to this app.  A valid file URI has an empty
                // authority and therefore starts with '/'.
                if (!authorityAndPath.startsWith("/")) return null
                authorityAndPath
            }
            else -> return null
        }
    if (path.length <= 1 || path.endsWith('/')) return null
    val lower = path.lowercase()
    // Reject encoded separators/dot segments as well as their literal forms.  Other percent
    // escapes (for example a space in a user filename) remain valid and are left untouched.
    if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return null
    val segments = path.split('/')
    // An absolute path has exactly one leading empty segment.  Any additional empty segment
    // would be a doubled separator which native path canonicalization may collapse; reject it so
    // the ownership comparison cannot be bypassed with an alternate spelling.  Dot segments are
    // rejected for the same reason (the bridge must receive an already-canonical local handle).
    if (segments.drop(1).any { it == ".." || it == "." || it.isEmpty() }) return null
    return raw to path
}

private fun threadComposerAttachmentExtension(value: String): String {
    val candidate = value.trim()
    if (candidate.isBlank()) return ""
    return candidate.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}

private fun threadComposerAttachmentNameIsSafe(fileName: String): Boolean =
    fileName.isNotBlank() &&
        fileName.none(Char::isISOControl) &&
        '/' !in fileName &&
        '\\' !in fileName

internal fun resolveThreadComposerAttachmentRemoval(
    attachments: List<SharedThreadComposerAttachment>,
    removedAttachmentIds: Set<String>,
    attachmentId: String,
    draftKey: String?
): ThreadComposerAttachmentRemoval {
    val normalizedId = attachmentId.trim().takeIf { it.isNotBlank() }
        ?: return ThreadComposerAttachmentRemoval(accepted = false, reason = "blank_attachment_id")
    val normalizedDraftKey = normalizeThreadAttachmentUploadDraftKey(draftKey)
        ?: return ThreadComposerAttachmentRemoval(accepted = false, reason = "missing_draft_key")
    // Keep the draft identity in the decision even though the current return value does not
    // expose it.  This explicit read prevents callers from accidentally removing an unscoped row
    // while migrating to the composite upload identity.
    if (normalizedDraftKey.isBlank()) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "missing_draft_key")
    }
    if (removedAttachmentIds.any { it.trim() == normalizedId }) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "already_removed")
    }
    val attachment = attachments.firstOrNull { it.id.trim() == normalizedId }
        ?: return ThreadComposerAttachmentRemoval(accepted = false, reason = "attachment_not_found")

    val source = attachment.source?.trim().orEmpty()
    val fileRef = attachment.fileRef?.trim()?.takeIf { it.isNotBlank() }
    if (source !in ThreadComposerOwnedTemporarySources) {
        // Removing a visible server/remote/external row is a reducer operation, not a native-file
        // retirement operation.  Those rows are valid to remove from the mounted draft, but this
        // helper must never hand their URL/URI to the local retirement bridge because the composer
        // does not own those bytes.
        return ThreadComposerAttachmentRemoval(accepted = true)
    }
    if (fileRef == null) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "missing_file_ref")
    }
    val normalizedRef =
        normalizeThreadComposerOwnedFileRef(fileRef)
            ?: return ThreadComposerAttachmentRemoval(accepted = false, reason = "invalid_file_ref")
    if (!threadComposerAttachmentNameIsSafe(attachment.fileName.trim())) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "invalid_file_name")
    }

    val normalizedMime = attachment.mimeType.orEmpty().substringBefore(';').trim().lowercase()
    if (normalizedMime.isBlank()) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "missing_mime_type")
    }
    if (normalizedMime.any(Char::isISOControl)) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "invalid_mime_type")
    }
    val nameExtension = threadComposerAttachmentExtension(attachment.fileName)
    val refExtension = threadComposerAttachmentExtension(normalizedRef.second)
    val extensions = listOf(nameExtension, refExtension).filter(String::isNotBlank)
    val hasVideoExtension = extensions.any { it in RaftFilePreviewVideoExtensions }
    val hasImageExtension = extensions.any { it in ThreadComposerImageFileExtensions }
    val hasKnownNonImageExtension =
        extensions.any { it !in ThreadComposerImageFileExtensions && it !in RaftFilePreviewVideoExtensions }
    val isVideoMime = normalizedMime.startsWith("video/")
    val isImageMime = normalizedMime.startsWith("image/")

    // A tuple that disagrees about its media type is not safe to retire.  In particular, do not
    // trust an image MIME/name when the actual ref ends in a video suffix (or the reverse).
    if ((isImageMime && hasVideoExtension) || (isVideoMime && hasImageExtension)) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "media_type_conflict")
    }
    if (
        (isImageMime && hasKnownNonImageExtension) ||
            (isVideoMime && hasKnownNonImageExtension) ||
            (!isImageMime && !isVideoMime && (hasImageExtension || hasVideoExtension))
    ) {
        return ThreadComposerAttachmentRemoval(accepted = false, reason = "media_type_conflict")
    }

    // Videos and non-image files may be removed from the draft, but they never qualify for the
    // image temporary-file retirement bridge.
    if (isVideoMime || hasVideoExtension || !isImageMime) {
        return ThreadComposerAttachmentRemoval(accepted = true)
    }

    // A sibling row can still own the same physical object.  Removing this tile is valid, but
    // retiring the ref would strand the sibling; compare canonical paths rather than raw URI
    // spelling so `/cache/x.jpg` and `file:///cache/x.jpg` cannot bypass the fence.
    val siblingOwnsRef =
        attachments.any { sibling ->
            if (sibling.id.trim() == normalizedId) {
                false
            } else {
                val siblingRawRef = sibling.fileRef?.trim()?.takeIf { it.isNotBlank() }
                // A malformed sibling spelling is conservatively treated as a holder when it is
                // byte-for-byte the same ref.  Otherwise compare canonical paths; URI/path
                // aliases cannot evade the shared-physical-file fence.
                siblingRawRef == normalizedRef.first ||
                    siblingRawRef?.let(::normalizeThreadComposerOwnedFileRef)?.second == normalizedRef.second
            }
        }
    if (siblingOwnsRef) {
        return ThreadComposerAttachmentRemoval(accepted = true)
    }
    return ThreadComposerAttachmentRemoval(accepted = true, fileRefToRetire = normalizedRef.first)
}

/** Identity of one cross-process composer-image event batch. */
internal data class ThreadComposerImageEventBatchKey(
    val sessionId: String,
    val threadKey: String,
    val groupId: String
)

/** Parsed event retained by the batch accumulator until the whole N/N group is present. */
internal data class ThreadComposerImageEventBatchItem(
    val eventData: JSONObject,
    val payload: RaftFilePreviewComposerImageEventPayload,
    val replaceAttachmentId: String? = null
) {
    val actionId: String
        get() = payload.actionId
    val fileRef: String
        get() = payload.fileRef
    val groupIndex: Int
        get() = payload.groupIndex ?: 0

    /** All fields which affect ownership/upload semantics, excluding JSONObject object identity. */
    internal val contentKey: String
        get() =
            listOf(
                payload.previewSessionId,
                payload.threadKey,
                payload.action,
                payload.fileRef,
                payload.previewUri,
                payload.fileName,
                payload.mimeType,
                payload.source,
                payload.compressed.toString(),
                payload.sizeBytes?.toString().orEmpty(),
                payload.groupId.orEmpty(),
                payload.groupIndex?.toString().orEmpty(),
                payload.groupTotal?.toString().orEmpty(),
                replaceAttachmentId.orEmpty()
            ).joinToString("\u0000")
}

internal sealed interface ThreadComposerImageEventBatchOffer {
    data object Added : ThreadComposerImageEventBatchOffer
    data object Duplicate : ThreadComposerImageEventBatchOffer
    data object Rejected : ThreadComposerImageEventBatchOffer
    data class Complete(val events: List<ThreadComposerImageEventBatchItem>) : ThreadComposerImageEventBatchOffer
}

/**
 * Pure N/N event accumulator used by the Thread consumer.
 *
 * It freezes session/thread/group identity and expected count at construction.  No caller may
 * upload or claim a member until [offer] returns [Complete].  Same-index replays with identical
 * content are idempotent; index/action/ref conflicts poison the whole accumulator.
 */
internal class ThreadComposerImageEventBatchAccumulator(
    val key: ThreadComposerImageEventBatchKey,
    val expectedCount: Int
) {
    private val itemsByIndex = linkedMapOf<Int, ThreadComposerImageEventBatchItem>()
    private val actionIds = mutableSetOf<String>()
    private val fileRefs = mutableSetOf<String>()
    private var rejected = false

    val isRejected: Boolean
        get() = rejected

    val isComplete: Boolean
        get() = !rejected && itemsByIndex.size == expectedCount &&
            (0 until expectedCount).all(itemsByIndex::containsKey)

    fun offer(item: ThreadComposerImageEventBatchItem): ThreadComposerImageEventBatchOffer {
        if (rejected || expectedCount <= 0) return ThreadComposerImageEventBatchOffer.Rejected
        val payload = item.payload
        val actualKey =
            ThreadComposerImageEventBatchKey(
                sessionId = payload.previewSessionId.trim(),
                threadKey = payload.threadKey.trim(),
                groupId = payload.groupId.orEmpty().trim()
            )
        val actualTotal = payload.groupTotal ?: 1
        val actualIndex = payload.groupIndex ?: 0
        if (
            actualKey != key ||
                actualTotal != expectedCount ||
                actualIndex !in 0 until expectedCount ||
                item.actionId.isBlank() ||
                item.fileRef.isBlank()
        ) {
            rejected = true
            return ThreadComposerImageEventBatchOffer.Rejected
        }

        val existing = itemsByIndex[actualIndex]
        if (existing != null) {
            return if (existing.contentKey == item.contentKey) {
                ThreadComposerImageEventBatchOffer.Duplicate
            } else {
                rejected = true
                ThreadComposerImageEventBatchOffer.Rejected
            }
        }
        if (item.actionId in actionIds || item.fileRef in fileRefs) {
            rejected = true
            return ThreadComposerImageEventBatchOffer.Rejected
        }
        itemsByIndex[actualIndex] = item
        actionIds += item.actionId
        fileRefs += item.fileRef
        return if (isComplete) {
            ThreadComposerImageEventBatchOffer.Complete(
                events = (0 until expectedCount).mapNotNull(itemsByIndex::get)
            )
        } else {
            ThreadComposerImageEventBatchOffer.Added
        }
    }

    fun eventsInIndexOrder(): List<ThreadComposerImageEventBatchItem> =
        if (isComplete) (0 until expectedCount).mapNotNull(itemsByIndex::get) else emptyList()

    fun abort() {
        rejected = true
        itemsByIndex.clear()
        actionIds.clear()
        fileRefs.clear()
    }
}

/**
 * Desktop paste: each copied image (the host's clipboard PNG) goes through the host image
 * compressor unless [originalImage] is on, as an editor image does on mobile. Finder files keep
 * their bytes. A failed compression keeps the pasted PNG (the paste still works); a successful
 * one retires the PNG it replaced.
 */
internal suspend fun threadComposerCompressPastedImages(
    files: List<PlatformFile>,
    originalImage: Boolean,
    compress: suspend (PlatformFile) -> PlatformFile?,
    retire: (List<String>) -> Unit,
    log: (String) -> Unit = {},
): List<PlatformFile> {
    if (originalImage) return files
    val replaced = mutableListOf<String>()
    val prepared =
        files.map { file ->
            val sourceRef = file.fileRef?.trim().orEmpty()
            if (file.source != ThreadComposerClipboardImageSource || sourceRef.isEmpty()) return@map file
            val compressed = compress(file)?.takeIf { !it.fileRef.isNullOrBlank() && it.fileRef != sourceRef }
            log("thread_attachment_paste_compress result=${if (compressed == null) "kept_original" else "compressed"}")
            if (compressed == null) {
                file
            } else {
                replaced += sourceRef
                compressed
            }
        }
    if (replaced.isNotEmpty()) retire(replaced)
    return prepared
}

/**
 * File picks never compress images and never surface the original-image toggle. 1.11 added
 * native JPEG compression on the generic attachFile path; product rolled that back so a File
 * JPEG stays its source bytes and hits the shared size gate. Image/camera picks still skip
 * compression here: they enter the editor first and are finalized after confirmation.
 * [action] and [originalImage] stay on the signature so the picker-admission freeze call site
 * does not change; neither argument can enable compression anymore.
 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldCompressThreadAttachmentPickerImages(
    action: String,
    originalImage: Boolean,
): Boolean = false

/**
 * A generic picker can return a readable subset together with a compression error (HTTP-like
 * `206`).  Compression is an all-or-nothing policy: accepting that subset would silently send
 * some images compressed and leave the failed image out of the user's selection.  Keep the
 * response inspection pure so every host envelope (top-level fields or `bodyText`) follows the
 * same fail-closed rule.
 */
internal fun threadAttachmentPickerHasCompressionFailure(response: JSONObject?): Boolean {
    if (response == null) return false

    fun hasCompressionError(entries: JSONArray?): Boolean {
        if (entries == null) return false
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("errorCode").trim() == RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE) {
                return true
            }
        }
        return false
    }

    if (response.optString("errorCode").trim() == RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE ||
        hasCompressionError(response.optJSONArray("errors"))
    ) {
        return true
    }
    val body =
        response.optString("bodyText")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: return false
    return body.optString("errorCode").trim() == RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE ||
        hasCompressionError(body.optJSONArray("errors"))
}

/**
 * Collect every temporary ref a native picker exposed in a failed compression envelope.  Hosts
 * normally put successful subset refs in `files`; a failed item may also carry a ref in `errors`
 * or a compatibility `fileRefs` array.  The native retirement bridge performs the cache-root
 * admission check, so this helper deliberately does not trust the ref's scheme/path here.
 */
internal fun threadAttachmentPickerCompressionFailureFileRefs(response: JSONObject?): Set<String> {
    if (response == null) return emptySet()
    val refs = linkedSetOf<String>()

    fun collectArray(array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
            if (entry != null) {
                listOf("fileRef", "temporaryFileRef", "sourceFileRef")
                    .forEach { key ->
                        entry.optString(key).trim().takeIf { it.isNotBlank() }?.let(refs::add)
                    }
                // A few hosts wrap the actual item under `file`; retain that compatibility shape
                // without recursively walking arbitrary payload objects.
                entry.optJSONObject("file")?.optString("fileRef")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(refs::add)
            } else {
                array.optString(index)?.trim()?.takeIf { it.isNotBlank() }?.let(refs::add)
            }
        }
    }

    fun collectEnvelope(envelope: JSONObject) {
        collectArray(envelope.optJSONArray("files"))
        collectArray(envelope.optJSONArray("errors"))
        collectArray(envelope.optJSONArray("fileRefs"))
        listOf("fileRef", "temporaryFileRef", "sourceFileRef")
            .forEach { key ->
                envelope.optString(key).trim().takeIf { it.isNotBlank() }?.let(refs::add)
            }
    }

    collectEnvelope(response)
    response.optString("bodyText")
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        ?.let(::collectEnvelope)
    return refs
}

internal fun raftComposerImagePickerFilesAreStrictImages(files: List<PlatformFile>): Boolean =
    files.isNotEmpty() &&
        files.all { file ->
            raftComposerImagePickerIsStrictImage(
                fileName = file.fileName,
                mimeType = file.mimeType.orEmpty(),
            )
        }
/**
 * Synchronously ensures [composerDraftViewModel] has started the exact `(instanceId, draftKey)`
 * session before [ThreadComposerRoute] renders its first frame. [SharedThreadComposerDraftStore]'s
 * `Start` reduction and persistence read are synchronous in memory, so calling this during
 * composition (guarded by an O(1) identity equality check) eliminates the Frame-1 blank
 * placeholder flash while staying idempotent across recompositions.
 */
internal fun ensureThreadComposerDraftStarted(
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    composer: RaftThreadComposerPayload,
    draftKey: String?,
) {
    val normalizedDraftKey = draftKey?.takeIf { it.isNotBlank() }
    val current = composerDraftViewModel.state.value
    if (current.instanceId == composer.instanceId && current.draftKey == normalizedDraftKey) {
        return
    }
    composerDraftViewModel.dispatch(
        SharedThreadComposerDraftIntent.Start(
            composer = composer,
            draftKey = normalizedDraftKey,
        )
    )
}

internal class ThreadComposerRouteArgs(
    val baseComposerState: RaftThreadComposerPayload,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val renderedPayload: RaftThreadScreenPayload,
    val timelineSlice: ThreadTimelineSlice,
    val resolvedAuxiliaryThreadId: String?,
    val surface: ThreadComposerSurface,
    val memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    val channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    val agentStatusResolver: RaftAgentStatusResolver,
    val onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    val onDropFiles: (ThreadComposerFileDrop) -> Unit,
    val onSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission,
    val insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    val onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    val onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    val composerSendKeyEnabled: Boolean = false,
    val composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    val onKeyboardHeightChange: (Float) -> Unit = {},
    val adaptiveNativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
    val removedAppBot: RaftAppBotIdentity? = null,
    val autofocusOnOpen: ComposerFocusOnOpen? = null,
    val optimisticPendingAccess: Boolean = false,
)

/**
 * Route-scoped movable composer instance shared between the cold LoadingShell and FeatureSurfaces
 * so transitioning from LoadingShell to FeatureSurfaces keeps the live editor mounted without
 * dropping focus, keyboard state, or typed draft.
 */
internal val LocalThreadMovableComposer =
    compositionLocalOf<(@Composable (ThreadComposerRouteArgs) -> Unit)?> { null }

@Composable
internal fun ThreadComposerRouteFromArgs(args: ThreadComposerRouteArgs) {
    ThreadComposerRoute(
        baseComposerState = args.baseComposerState,
        composerDraftViewModel = args.composerDraftViewModel,
        renderedPayload = args.renderedPayload,
        timelineSlice = args.timelineSlice,
        resolvedAuxiliaryThreadId = args.resolvedAuxiliaryThreadId,
        surface = args.surface,
        memberSuggestionsFlow = args.memberSuggestionsFlow,
        channelSuggestionsFlow = args.channelSuggestionsFlow,
        agentStatusResolver = args.agentStatusResolver,
        onEvent = args.onEvent,
        onDropFiles = args.onDropFiles,
        onSendRequest = args.onSendRequest,
        insertMentionRequest = args.insertMentionRequest,
        onInsertMentionConsumed = args.onInsertMentionConsumed,
        onSuggestionDemandChanged = args.onSuggestionDemandChanged,
        composerSendKeyEnabled = args.composerSendKeyEnabled,
        composerSendKey = args.composerSendKey,
        onKeyboardHeightChange = args.onKeyboardHeightChange,
        adaptiveNativeEditingStateReader = args.adaptiveNativeEditingStateReader,
        removedAppBot = args.removedAppBot,
        autofocusOnOpen = args.autofocusOnOpen,
        optimisticPendingAccess = args.optimisticPendingAccess,
    )
}

@Composable
internal fun ThreadComposerRoute(
    baseComposerState: RaftThreadComposerPayload,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    renderedPayload: RaftThreadScreenPayload,
    timelineSlice: ThreadTimelineSlice,
    resolvedAuxiliaryThreadId: String?,
    surface: ThreadComposerSurface,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    agentStatusResolver: RaftAgentStatusResolver,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    /** Files dropped on the composer (its drop zone): the page reads and admits them. */
    onDropFiles: (ThreadComposerFileDrop) -> Unit,
    onSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    composerSendKeyEnabled: Boolean = false,
    composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    onKeyboardHeightChange: (Float) -> Unit,
    adaptiveNativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
    /** A removed App Bot's DM (or a thread in it): notice above a disabled composer. */
    removedAppBot: RaftAppBotIdentity? = null,
    /** Focus the composer when the conversation opens (ComposerAutofocusPolicy.kt). */
    autofocusOnOpen: ComposerFocusOnOpen? = null,
    /** True while rendering optimistically before access resolution finishes. */
    optimisticPendingAccess: Boolean = false,
) {
    if (removedAppBot != null) {
        // The editor never mounts, so the saved draft stays in the store untouched.
        ThreadRemovedAppBotReadOnlyComposer(handle = removedAppBot.handle)
        return
    }
    val composerDraftState by composerDraftViewModel.state.collectAsState()
    // The editor mounts as soon as its draft is loaded, also while the push slide runs, so the
    // composer slides in with the page (artin 11-22, 1.28). Holding it back until the slide
    // settled (#5320) left an empty strip sliding in and the input box popping in last; a static
    // at-rest copy during the slide is the 1.29 follow-up (#proj-mobile:#277).
    val expectedDraftKey = remember(surface) { surface.threadComposerDraftKey() }
    val restingHeightKey = surface.threadComposerRestingHeightKey()
    if (
        !threadComposerEditorReadyToMount(
            composerInstanceId = baseComposerState.instanceId,
            expectedDraftKey = expectedDraftKey,
            draftState = composerDraftState,
        )
    ) {
        // Reserve the editor's resting height while it waits to mount, so the message list above
        // is laid out at its final height on the first frame. Returning nothing here made the
        // bottom-pinned list jump up ~76pt one frame later on every channel entry.
        ThreadComposerRestingHeightPlaceholder(surface)
        return
    }
    val composerState =
        remember(
            baseComposerState,
            composerDraftState,
            renderedPayload.canSend,
            resolvedAuxiliaryThreadId,
            optimisticPendingAccess,
        ) {
            baseComposerState.withThreadComposerDraftState(
                draftState = composerDraftState,
                renderedPayload = renderedPayload,
                resolvedAuxiliaryThreadId = resolvedAuxiliaryThreadId,
                optimisticPendingAccess = optimisticPendingAccess,
            )
        }
    val composerUiState = composerState.toThreadComposerUiState()
    val composerAtRest = composerUiState.isThreadComposerAtRest()
    // The personal assistant's otter on this composer's frame edge, when the conversation has one.
    val assistantOtter = LocalThreadAssistantOtter.current
    val frameEdge: (@Composable (Boolean, Boolean, Dp) -> Unit)? =
        remember(assistantOtter) {
            assistantOtter?.let { otter ->
                val edge: @Composable (Boolean, Boolean, Dp) -> Unit = { composing, focused, stackedAbove ->
                    ThreadAssistantOtterFrameEdge(otter, composing, focused, stackedAbove)
                }
                edge
            }
        }
    // The assistant's DM in its panel: the conversation it was opened from, a chip above the
    // composer (web MessageInput's accessory row, `gap-1.5`), until × or the next message with text.
    Column(Modifier.fillMaxWidth()) {
        surface.assistantContext?.let { attachment ->
            attachment.context?.let { context ->
                ThreadAssistantContextChip(
                    context = context,
                    onRemove = attachment::detach,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        // Web MessageInput's drop zone: files dragged over the composer light it up; a drop attaches
        // them as the attach button does.
        ThreadComposerFileDropZone(
            enabled = composerUiState.enabled && composerUiState.attachmentActionsEnabled && surface.sendChannelId != null,
            onDropFiles = onDropFiles,
            modifier =
                Modifier.fillMaxWidth().onSizeChangedDp { size ->
                    if (composerAtRest) ThreadComposerRestingHeight.record(restingHeightKey, size.height)
                }
        ) {
        ThreadComposerContentWithSuggestions(
            surface = surface,
            state = composerUiState,
            threadMessages = timelineSlice.messages,
            originMessage = renderedPayload.originMessage,
            payloadMembers = renderedPayload.members,
            payloadMemberCandidates = renderedPayload.memberCandidates,
            prioritizeThreadParticipants = renderedPayload.shouldPrioritizeThreadMentionParticipants(),
            memberSuggestionsFlow = memberSuggestionsFlow,
            channelSuggestionsFlow = channelSuggestionsFlow,
            agentStatusResolver = agentStatusResolver,
            onEvent = onEvent,
            onSendRequest = onSendRequest,
            insertMentionRequest = insertMentionRequest,
            onInsertMentionConsumed = onInsertMentionConsumed,
            onSuggestionDemandChanged = onSuggestionDemandChanged,
            composerSendKeyEnabled = composerSendKeyEnabled,
            composerSendKey = composerSendKey,
            onKeyboardHeightChange = onKeyboardHeightChange,
            contentPaddingTop = 0.dp,
            contentPaddingBottom = 0.dp,
            adaptiveNativeEditingStateReader = adaptiveNativeEditingStateReader,
            frameEdge = frameEdge,
            autofocusOnOpen = autofocusOnOpen,
        )
        }
    }
}

/**
 * The DM with an App Bot removed from this server, and threads in it (botiverse/slock #11303
 * `RemovedAppBotDmNotice` over #11321 `ReadOnlyComposer`): a raft-ui warning Banner that says why
 * and where an admin adds the Bot back, 8dp above the one-line read-only composer ("Chat is
 * unavailable"; no typing, attachments, As Task or send). Web links "Settings › Applications" for
 * people who can manage Apps; the phone has no server Apps settings, so everyone reads plain text.
 */
@Composable
internal fun ThreadRemovedAppBotReadOnlyComposer(handle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftBanner(
            description = stringResource(Res.string.thread_app_bot_removed_notice, handle.removePrefix("@")),
            status = RaftBannerStatus.Warning,
        )
        RaftReadOnlyComposer(text = stringResource(Res.string.thread_composer_chat_unavailable))
    }
}

/**
 * The composer's measured height at rest (empty draft, no attachments/actions/suggestions), per
 * composer surface kind, for the process lifetime. The pre-mount placeholder uses it so the
 * first frame already has the final list height; [DEFAULT_DP] covers the first open.
 */
internal object ThreadComposerRestingHeight {
    const val DEFAULT_DP = 76
    private val lock = SynchronizedObject()
    private val byKey = mutableMapOf<String, Int>()

    fun get(key: String): Int = synchronized(lock) { byKey[key] } ?: DEFAULT_DP

    fun record(key: String, heightDp: Int) {
        if (heightDp <= 0) return
        synchronized(lock) { byKey[key] = heightDp }
    }

    internal fun clearForTest() {
        synchronized(lock) { byKey.clear() }
    }
}

internal fun ThreadComposerSurface.threadComposerRestingHeightKey(): String = "$kind|$showTaskButton"

/**
 * The server a page's composer uploads belong to: the page's own (its shell scope's, else the
 * server its reply cache was captured for), the session's selection only when neither is known.
 */
internal fun threadComposerUploadOwnerServerId(
    shellServerId: String?,
    pageServerId: String?,
    sessionServerId: String?
): String? =
    listOf(shellServerId, pageServerId, sessionServerId).firstNotNullOfOrNull { id ->
        id?.trim()?.takeIf(String::isNotBlank)
    }

/**
 * The server a thread page reads per-server state for (feature flags, member directory): the
 * session's once captured, the shell's before, so the first frames already have a server.
 */
internal fun threadPageServerId(sessionServerId: String?, shellServerId: String?): String? =
    sessionServerId?.trim()?.takeIf { it.isNotBlank() } ?: shellServerId?.trim()?.takeIf { it.isNotBlank() }

/** The composer's slot at its resting height, before the editor itself can mount. */
@Composable
internal fun ThreadComposerRestingHeightPlaceholder(surface: ThreadComposerSurface) {
    Spacer(
        Modifier.fillMaxWidth()
            .height(ThreadComposerRestingHeight.get(surface.threadComposerRestingHeightKey()).dp)
            .testTag(ThreadComposerRestingHeightPlaceholderTestTag)
    )
}

internal const val ThreadComposerRestingHeightPlaceholderTestTag = "thread-composer-resting-placeholder"

/**
 * Whether the composer's dock keeps its slot while the footer is not decided yet: the timeline can
 * show (a cached frame while access refreshes) before the composer may mount, and an empty footer
 * then let the bottom-pinned list jump up by the composer's height a few frames after it appeared.
 * Only while a composer is still expected — never under an access error, a closed conversation or
 * another tab.
 */
internal fun threadReservesPendingComposerSlot(
    accessResolution: ThreadAccessResolution,
    payloadStatus: String,
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?,
    composerLocked: Boolean
): Boolean =
    accessResolution != ThreadAccessResolution.Error &&
        accessResolution != ThreadAccessResolution.NoPermission &&
        payloadStatus != "error" &&
        !composerLocked &&
        shouldShowThreadComposerForTab(joined = joined, showComposer = showComposer, selectedTab = selectedTab)

internal fun ThreadComposerUiState.isThreadComposerAtRest(): Boolean =
    seedText.isBlank() &&
        pendingAttachments.isEmpty() &&
        pendingMentionActions.isEmpty() &&
        unresolvedMentionHandles.isEmpty() &&
        statusLabel == null &&
        memberSuggestions.isEmpty() &&
        channelSuggestions.isEmpty()

/**
 * A restored draft is read synchronously by the persistence slice during Start, but Start itself
 * is dispatched from a launch effect. Mounting the native editor before that transition lets its
 * initial empty callback race the restored String projection and can leave native placeholder
 * drawing on top of restored text. Keep the editor unmounted until the store owns the exact
 * conversation draft session; later programmatic edits continue through editor commands.
 */
internal fun threadComposerEditorReadyToMount(
    composerInstanceId: String,
    expectedDraftKey: String?,
    draftState: SharedThreadComposerDraftState,
    pageInMotion: Boolean = false,
): Boolean =
    !pageInMotion &&
        composerInstanceId.isNotBlank() &&
        expectedDraftKey != null &&
        draftState.instanceId == composerInstanceId &&
        draftState.draftKey == expectedDraftKey

@Composable
private fun ThreadComposerContentWithSuggestions(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    threadMessages: List<RaftThreadMessageItem>,
    originMessage: RaftThreadMessageItem?,
    payloadMembers: List<RaftThreadMemberItem>,
    payloadMemberCandidates: List<RaftThreadMemberItem>,
    prioritizeThreadParticipants: Boolean,
    memberSuggestionsFlow: Flow<SharedMembersMemberSuggestionSlice>,
    channelSuggestionsFlow: Flow<SharedMembersChannelSuggestionSlice>,
    agentStatusResolver: RaftAgentStatusResolver,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onSendRequest: (ThreadComposerSendRequest) -> ThreadComposerSendAdmission,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {},
    composerSendKeyEnabled: Boolean = false,
    composerSendKey: build.raft.app.data.RaftComposerSendKey = build.raft.app.data.RaftComposerSendKey.Default,
    onKeyboardHeightChange: (Float) -> Unit,
    contentPaddingTop: Dp,
    contentPaddingBottom: Dp,
    adaptiveNativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
    frameEdge: (@Composable (composing: Boolean, focused: Boolean, stackedAbove: Dp) -> Unit)? = null,
    autofocusOnOpen: ComposerFocusOnOpen? = null,
) {
    val memberSuggestionSlice by memberSuggestionsFlow.collectAsState(SharedMembersMemberSuggestionSlice())
    val channelSuggestionSlice by channelSuggestionsFlow.collectAsState(SharedMembersChannelSuggestionSlice())
    val channelJointServers = LocalRaftChannelJointServers.current
    val loadedMemberSuggestions =
        remember(memberSuggestionSlice.suggestions, threadMessages, originMessage, prioritizeThreadParticipants, channelJointServers) {
            val threadParticipantRanks =
                threadMentionParticipantRanks(
                    threadMessages = threadMessages,
                    originMessage = originMessage,
                    prioritizeThreadParticipants = prioritizeThreadParticipants
                )
            memberSuggestionSlice.suggestions
                .sortForThreadMentionParticipants(threadParticipantRanks)
                .map { suggestion ->
                    suggestion.toRaftComposerMemberItem(
                        inThread = suggestion.isThreadMentionParticipant(threadParticipantRanks),
                        knownServers = channelJointServers,
                    )
                }
        }
    val loadedChannelSuggestions =
        remember(channelSuggestionSlice.suggestions) {
            channelSuggestionSlice.suggestions.map { suggestion ->
                RaftComposerChannelItem(
                    name = suggestion.name,
                    subtitle = suggestion.subtitle,
                    channelId = suggestion.channelId,
                    channelType = suggestion.channelType,
                    archived = suggestion.archived,
                )
            }
        }
    var retainedMemberSuggestions by remember(surface) { mutableStateOf(emptyList<RaftComposerMemberItem>()) }
    var retainedChannelSuggestions by remember(surface) { mutableStateOf(emptyList<RaftComposerChannelItem>()) }
    LaunchedEffect(loadedMemberSuggestions, memberSuggestionSlice.status) {
        if (loadedMemberSuggestions.isNotEmpty() || memberSuggestionSlice.status == SharedMembersLoadStatus.Loaded) {
            retainedMemberSuggestions = loadedMemberSuggestions
        }
    }
    LaunchedEffect(loadedChannelSuggestions, channelSuggestionSlice.status) {
        if (loadedChannelSuggestions.isNotEmpty() || channelSuggestionSlice.status == SharedMembersLoadStatus.Loaded) {
            retainedChannelSuggestions = loadedChannelSuggestions
        }
    }
    val renderedMemberSuggestions =
        threadComposerRenderedSuggestions(
            loadedSuggestions = loadedMemberSuggestions,
            retainedSuggestions = retainedMemberSuggestions,
            fallbackSuggestions = state.memberSuggestions,
            status = memberSuggestionSlice.status
        )
    val renderedChannelSuggestions =
        threadComposerRenderedSuggestions(
            loadedSuggestions = loadedChannelSuggestions,
            retainedSuggestions = retainedChannelSuggestions,
            fallbackSuggestions = state.channelSuggestions,
            status = channelSuggestionSlice.status
        )
    val pendingMentionTargets =
        remember(state.pendingMentionActions.isNotEmpty(), payloadMembers, payloadMemberCandidates) {
            if (state.pendingMentionActions.isNotEmpty()) {
                buildPendingMentionTargetMap(
                    payloadMembers = payloadMembers,
                    payloadMemberCandidates = payloadMemberCandidates,
                )
            } else {
                PendingMentionTargetMap.Empty
            }
        }
    val renderedState =
        remember(
            state,
            pendingMentionTargets,
            memberSuggestionSlice,
            renderedMemberSuggestions,
            channelSuggestionSlice,
            renderedChannelSuggestions,
        ) {
            state.copy(
                pendingMentionTargets = pendingMentionTargets,
                memberSuggestions = renderedMemberSuggestions,
                memberSuggestionsStatus = memberSuggestionSlice.status,
                memberSuggestionsErrorCode = memberSuggestionSlice.errorCode,
                channelSuggestions = renderedChannelSuggestions,
                channelSuggestionsStatus = channelSuggestionSlice.status,
                channelSuggestionsErrorCode = channelSuggestionSlice.errorCode
            )
        }
    ThreadComposerContent(
        attachmentPreviewEnabled = true,
        surface = surface,
        state = renderedState,
        onEvent = onEvent,
        onSendRequest = onSendRequest,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom,
        agentStatusResolver = agentStatusResolver,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        onSuggestionDemandChanged = onSuggestionDemandChanged,
        composerSendKeyEnabled = composerSendKeyEnabled,
        composerSendKey = composerSendKey,
        externalNativeEditingStateReader = adaptiveNativeEditingStateReader,
        frameEdge = frameEdge,
        autofocusOnOpen = autofocusOnOpen,
    )
}

internal fun RaftThreadScreenPayload.shouldPrioritizeThreadMentionParticipants(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        parentChannelId.nonBlank() != null

internal fun List<SharedMembersMemberSuggestion>.sortForThreadMentionParticipants(
    threadMessages: List<RaftThreadMessageItem>,
    originMessage: RaftThreadMessageItem? = null,
    prioritizeThreadParticipants: Boolean
): List<SharedMembersMemberSuggestion> {
    val ranks =
        threadMentionParticipantRanks(
            threadMessages = threadMessages,
            originMessage = originMessage,
            prioritizeThreadParticipants = prioritizeThreadParticipants
        )
    return sortForThreadMentionParticipants(ranks)
}

private fun List<SharedMembersMemberSuggestion>.sortForThreadMentionParticipants(
    ranks: Map<String, Int>
): List<SharedMembersMemberSuggestion> {
    if (ranks.isEmpty()) return this
    fun List<SharedMembersMemberSuggestion>.sortGroup(): List<SharedMembersMemberSuggestion> =
        withIndex()
            .sortedWith(
                compareBy<IndexedValue<SharedMembersMemberSuggestion>>(
                    { indexed -> indexed.value.threadParticipantRank(ranks) },
                    { indexed -> indexed.index }
                )
            )
            .map { indexed -> indexed.value }
    val inThread = filter { it.isThreadMentionParticipant(ranks) }.sortGroup()
    val inThreadKeys = inThread.mapTo(mutableSetOf()) { it.threadMentionSuggestionKey() }
    val inChannel =
        filter { suggestion -> suggestion.inMentionChannel && suggestion.threadMentionSuggestionKey() !in inThreadKeys }
            .sortGroup()
    val inChannelKeys = inChannel.mapTo(mutableSetOf()) { it.threadMentionSuggestionKey() }
    val notInChannel =
        filter { suggestion ->
            val key = suggestion.threadMentionSuggestionKey()
            key !in inThreadKeys && key !in inChannelKeys
        }.sortGroup()
    return inThread + inChannel + notInChannel
}

private fun threadMentionParticipantRanks(
    threadMessages: List<RaftThreadMessageItem>,
    originMessage: RaftThreadMessageItem?,
    prioritizeThreadParticipants: Boolean
): Map<String, Int> {
    if (!prioritizeThreadParticipants || (threadMessages.isEmpty() && originMessage == null)) return emptyMap()
    return (listOfNotNull(originMessage) + threadMessages).recentThreadParticipantRanks()
}

private fun List<RaftThreadMessageItem>.recentThreadParticipantRanks(): Map<String, Int> {
    val ranks = LinkedHashMap<String, Int>()
    val seenAuthorIds = mutableSetOf<String>()
    var nextRank = 0
    for (message in asReversed()) {
        val authorId = message.authorId.trim().takeIf { it.isNotBlank() } ?: continue
        if (!seenAuthorIds.add(authorId)) continue
        val rank = nextRank
        nextRank += 1
        ranks[authorId.threadParticipantIdKey()] = rank
        message.authorHandle.threadParticipantHandleKey()?.let { handleKey ->
            ranks.putIfAbsent(handleKey, rank)
        }
    }
    return ranks
}

private fun SharedMembersMemberSuggestion.threadParticipantRank(ranks: Map<String, Int>): Int =
    threadParticipantKeys()
        .asSequence()
        .mapNotNull { key -> ranks[key] }
        .minOrNull()
        ?: Int.MAX_VALUE

private fun SharedMembersMemberSuggestion.isThreadMentionParticipant(ranks: Map<String, Int>): Boolean =
    threadParticipantRank(ranks) != Int.MAX_VALUE

private fun SharedMembersMemberSuggestion.threadMentionSuggestionKey(): String =
    memberId?.trim()?.takeIf { it.isNotBlank() }
        ?: handle.trim().lowercase().takeIf { it.isNotBlank() }
        ?: displayName.trim().lowercase()

private fun SharedMembersMemberSuggestion.threadParticipantKeys(): List<String> =
    buildList {
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.threadParticipantIdKey()) }
        handle.threadParticipantHandleKey()?.let { add(it) }
    }

private fun String.threadParticipantIdKey(): String = "id:${trim()}"

private fun String?.threadParticipantHandleKey(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.let { "handle:$it" }

/**
 * The dock under a conversation's messages (channel, thread, DM) that holds its composer: the
 * page canvas with the composer 8dp under the list (web `COMPOSER_FULL_EDGE_CLASS` `pt-2`) and
 * [RaftAppSpacing.Sm] from the sides. No seam above it: on the web the composer's own box sits
 * on the canvas.
 */
@Composable
internal fun ThreadComposerDock(
    composerModifier: Modifier = Modifier,
    edgePadding: Dp = RaftAppSpacing.Sm,
    keyboardLiftPx: Density.() -> Float = { 0f },
    composer: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // The whole dock belongs to the focused composer: its margins and the keyboard lift
                // under the card (composerModifier) are outside the composer's own region, and a tap
                // there counted as outside and closed the keyboard (artin, dm 59d04ff4).
                .retainInputFocusForInteractions()
                .background(RaftColor.Canvas)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    // The dock's canvas runs edge to edge; the composer box keeps out of the
                    // horizontal safe area.
                    .raftSafeAreaHorizontalPadding()
                    .padding(start = edgePadding, top = 8.dp, end = edgePadding)
                    .then(composerModifier)
                    // raft-ui: on a conversation page the window's toasts sit just above the composer.
                    // Gated by LocalRaftToastAvoidanceActive (immediately active on the top stack
                    // page, false on covered pages and inside RaftWindowDialog).
                    .raftToastAvoid(
                        keyboardPx = { with(density) { keyboardLiftPx() } },
                    )
        ) { composer() }
    }
}

/** Group identity for the initial Thread image editor's N/N event stream. */
internal fun threadComposerImageAnnotationGroupId(actionId: String, fileCount: Int): String =
    actionId.trim().takeIf { it.isNotBlank() && fileCount > 1 }?.let { "$it-group" }.orEmpty()

/**
 * Builds the production initial-picker preview payload. Keeping this construction
 * in a pure helper makes the group identity and the gallery cardinality directly
 * testable without mounting a pager or an event bus.
 */
internal fun threadComposerImageAnnotationPayload(
    threadInstanceKey: String,
    actionId: String,
    files: List<PlatformFile>,
    originalImage: Boolean,
): RaftFilePreviewPayload? {
    val firstFile = files.firstOrNull() ?: return null
    val previewUri = firstFile.composerLocalPreviewUri().orEmpty()
    return RaftFilePreviewPayload(
        kind = "image",
        // Shown only when the image has no name; resolved in the app language like the gallery's.
        titleDesc = Res.string.file_preview_image.desc(),
        subtitle = firstFile.fileName,
        name = firstFile.fileName,
        path = firstFile.fileName,
        meta =
            listOf(
                firstFile.sizeBytes?.takeIf { it > 0L }?.let { RaftFormat.bytes(it) }.orEmpty(),
                firstFile.mimeType.orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" · "),
        imageUrl = previewUri.takeIf { it.isNotBlank() },
        messageDesc =
            if (previewUri.isBlank()) {
                Res.string.file_preview_error_image_unavailable.desc()
            } else {
                Res.string.file_preview_no_preview.desc()
            },
        externalFileName = firstFile.fileName,
        externalMimeType = firstFile.mimeType.orEmpty(),
        imageGallery = files.map { file -> file.toComposerImageGalleryImage() },
        composerImageThreadKey = threadInstanceKey,
        composerImageActionId = actionId,
        composerImageGroupId = threadComposerImageAnnotationGroupId(actionId, files.size),
        composerImageFileRef = firstFile.fileRef.orEmpty(),
        composerImageFileName = firstFile.fileName,
        composerImageMimeType = firstFile.mimeType.orEmpty(),
        composerImageSizeBytes = firstFile.sizeBytes,
        composerImagePreviewUri = previewUri,
        composerImageCompressAfterEdit = !originalImage,
        composerImageOriginalOptionEnabled = true,
        composerImageOwnedTemporaryFileRefs =
            files.filter(PlatformFile::isComposerImageOwnedTemporary)
                .mapNotNull(PlatformFile::fileRef),
        composerImageCanComplete = true,
        composerImageCanEdit = true
    )
}

/** Renderer-facing URI; [PlatformFile.fileRef] remains the platform upload handle. */
internal fun PlatformFile.composerLocalPreviewUri(): String? =
    previewUri?.takeIf { it.isNotBlank() }
        ?: fileRef?.takeIf { it.isNotBlank() }

/**
 * Rehydrate a persisted composer attachment into the platform-file descriptor used by the
 * editable preview.  The preview URI and upload handle are deliberately kept as separate fields:
 * a remote/local thumbnail must never be promoted to a fileRef merely because it is renderable.
 */
internal fun SharedThreadComposerAttachment.toComposerImagePlatformFile(): PlatformFile =
    PlatformFile(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes.takeIf { it > 0L },
        previewUri = previewUri?.trim()?.takeIf { it.isNotBlank() }
            ?: fileRef?.trim()?.takeIf { it.isNotBlank() },
        fileRef = fileRef?.trim()?.takeIf { it.isNotBlank() },
        source = source.orEmpty()
    )

/**
 * Rehydrate only a still-sendable local draft row into the platform-file descriptor used by the
 * upload/retry path. Durable draft rows may also contain server-owned ready attachments and
 * render-only projections; those are deliberately not promoted to a local upload handle.
 *
 * `previewUri` is presentation-only. A remote thumbnail must never be copied into
 * [PlatformFile.fileRef] just because it is non-empty. The original source and fileRef are kept
 * (apart from surrounding whitespace) so a process-restored retry uses the same provider handle
 * that was persisted by the owning picker/editor bridge.
 */
internal fun SharedThreadComposerAttachment.toRestoredThreadComposerPlatformFile(): PlatformFile? {
    val normalizedStatus = status.trim().lowercase()
    if (normalizedStatus !in setOf("queued", "uploading", "failed")) return null

    val normalizedId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedRef = fileRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedSource = source?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (
        normalizedSource != "external_share" &&
            normalizedSource !in ThreadComposerOwnedTemporarySources
    ) {
        return null
    }

    val normalizedName = fileName.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedPreview = previewUri?.trim()?.takeIf { it.isNotBlank() }
    val normalizedMime = mimeType?.trim()?.takeIf { it.isNotBlank() }
    return PlatformFile(
        id = normalizedId,
        fileName = normalizedName,
        mimeType = normalizedMime,
        sizeBytes = sizeBytes.takeIf { it > 0L },
        previewUri = normalizedPreview,
        fileRef = normalizedRef,
        source = normalizedSource
    )
}

internal fun PlatformFile.toComposerImageGalleryImage(): RaftFilePreviewGalleryImage =
    RaftFilePreviewGalleryImage(
        attachmentId = id,
        name = fileName,
        mimeType = mimeType.orEmpty(),
        sizeLabel = sizeBytes?.takeIf { it > 0L }?.let { RaftFormat.bytes(it) }.orEmpty(),
        thumbnailUrl = composerLocalPreviewUri(),
        composerImageFileRef = fileRef.orEmpty(),
        composerImagePreviewUri = composerLocalPreviewUri().orEmpty(),
        composerImageSizeBytes = sizeBytes,
        composerImageOwnedTemporary = isComposerImageOwnedTemporary()
    )

internal fun PlatformFile.isComposerImageOwnedTemporary(): Boolean {
    if (fileRef.isNullOrBlank() || source !in ThreadComposerOwnedTemporarySources) return false
    val normalizedMime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
    val hasVideoExtension =
        sequenceOf(fileName, fileRef.orEmpty()).any { candidate ->
            val extension =
                candidate
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
            extension in RaftFilePreviewVideoExtensions
        }
    return !normalizedMime.startsWith("video/") && !hasVideoExtension
}

internal fun SharedMembersMemberSuggestion.toRaftComposerMemberItem(
    inThread: Boolean,
    knownServers: List<MemberServerRef> = emptyList(),
): RaftComposerMemberItem {
    val resolvedOriginServers =
        if (isLocalServer) {
            emptyList()
        } else {
            resolveExternalMentionOriginServers(
                originServers = originServers,
                sourceServerId = sourceServerId,
                knownServers = knownServers,
            )
        }
    val resolvedServerLabel =
        if (isLocalServer) {
            sourceServerLabel
        } else {
            sourceServerLabel?.trim()?.takeIf { it.isNotEmpty() }
                ?: resolvedOriginServers.firstOrNull()?.originLabel()?.takeIf { it.isNotEmpty() }
        }
    return RaftComposerMemberItem(
        displayName = displayName,
        handle = handle,
        inMentionChannel = inMentionChannel,
        inThread = inThread,
        sourceServerId = sourceServerId,
        isLocalServer = isLocalServer,
        sourceServerLabel = resolvedServerLabel,
        kind = kind,
        description = description,
        avatarWireValue = avatarWireValue,
        avatarLabel = avatarLabel,
        memberId = memberId,
        originServers = resolvedOriginServers,
    )
}

private fun resolveExternalMentionOriginServers(
    originServers: List<MemberServerRef>,
    sourceServerId: String?,
    knownServers: List<MemberServerRef>,
): List<MemberServerRef> {
    val knownById =
        buildMap {
            for (server in knownServers) {
                val id = server.id.trim().takeIf { it.isNotEmpty() } ?: continue
                if (!containsKey(id)) put(id, server)
            }
        }
    if (originServers.isNotEmpty()) {
        return originServers.map { server ->
            val known = knownById[server.id.trim()] ?: return@map server
            server.copy(
                name = server.name.ifBlank { known.name },
                slug = server.slug.ifBlank { known.slug },
                avatarUrl = server.avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: known.avatarUrl,
            )
        }
    }
    val cleanId = sourceServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val known = knownById[cleanId] ?: return emptyList()
    if (known.originLabel().isEmpty()) return emptyList()
    return listOf(known)
}
