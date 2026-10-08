package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.Utils
import build.raft.app.base.reduceAttachmentDownloadProgress
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockCheckbox
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.AttachmentPreview
import build.raft.app.data.AttachmentPreviewContent
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.parseSharedAttachmentPreview
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.DataResult
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.PlatformFile
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.data.SlockEventBus
import build.raft.app.data.SlockEventBuses
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockThreadApiClient
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadApiResult
import build.raft.kuiklybase.datetime.Clock
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.model.MemberId
import build.raft.app.model.SlockWorkspaceFilePreview
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.network.binaryDownloadUrlDiagnostics
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.extension.nativeRef
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.coil3.rememberAsyncImageBitmap
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.material3.LinearProgressIndicator
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.ImageBitmap
import com.tencent.kuikly.compose.ui.graphics.StrokeCap
import com.tencent.kuikly.compose.ui.graphics.drawscope.DrawScope
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.core.views.VideoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

internal const val SLOCK_FILE_PREVIEW_PAGE = "compose_file_preview"
internal const val SLOCK_COMPOSER_IMAGE_EDIT_EVENT = "slock.composerImageEdit"
/** Stable fail-closed code shared by every host's editor-output compressor. */
internal const val SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE =
    "attachment_image_compression_failed"

/** Source marker required on a successful native editor-output compression response. */
internal const val SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE = "composer_image_compressor"

/** Source marker for an editor result that was delivered without a JPEG compression pass. */
internal const val SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE = "composer_image_editor"

/**
 * Resolve the source marker carried by a successful composer-image event from the explicit
 * finalization state.  Do not infer this from a MIME string: an original JPEG and a compressor
 * JPEG have the same MIME but different session semantics.
 */
internal fun slockFilePreviewComposerImageEventSource(compressed: Boolean): String =
    if (compressed) {
        SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE
    } else {
        SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE
    }

/**
 * Validate the two fields which identify how an editor result was finalized.  The check is kept
 * in common code so producers and consumers share one closed vocabulary.  A compressor result
 * must be the literal JPEG representation; an uncompressed editor result must still be an image.
 */
internal fun slockFilePreviewComposerImageEventMetadataIsValid(
    source: String,
    compressed: Boolean,
    mimeType: String
): Boolean {
    val cleanSource = source.trim()
    val normalizedMimeType =
        mimeType
            .trim()
            .substringBefore(';')
            .lowercase()
    return when (cleanSource) {
        SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE ->
            // Compression response admission uses the same literal wire value.  Do not let a
            // MIME parameter or case-normalized provider value masquerade as a proven JPEG.
            compressed && mimeType.trim() == "image/jpeg"
        SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE ->
            !compressed && normalizedMimeType.startsWith("image/")
        else -> false
    }
}

/**
 * The wire fields shared by every composer-image event consumer.
 *
 * Event data crosses a native/event-bus boundary, so `optString`/`optBoolean` defaults are not
 * safe here: a missing value must not be indistinguishable from a producer explicitly sending a
 * default.  Keep the type and presence checks in one parser so Thread and Settings cannot drift.
 */
internal data class SlockFilePreviewComposerImageEventPayload(
    val previewSessionId: String,
    val threadKey: String,
    val actionId: String,
    val action: String,
    val fileRef: String,
    val previewUri: String,
    val fileName: String,
    val mimeType: String,
    val source: String,
    val compressed: Boolean,
    val sizeBytes: Long?,
    val groupId: String?,
    val groupIndex: Int?,
    val groupTotal: Int?,
    /** task #286: explicit target slot when action=="replace"; blank for "complete" events. */
    val replaceAttachmentId: String
)

private fun JSONObject.requiredComposerImageEventString(key: String): String? {
    if (!has(key)) return null
    return (opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
}

private fun JSONObject.requiredComposerImageEventBoolean(key: String): Boolean? {
    if (!has(key)) return null
    // Do not use optBoolean: the Kuikly JSON implementation intentionally coerces strings such
    // as "true", while this event contract requires a real JSON Boolean.
    return opt(key) as? Boolean
}

private fun JSONObject.optionalComposerImageEventLong(key: String): Long? {
    if (!has(key)) return null
    val value = opt(key) as? Number ?: return null
    val longValue = value.toLong()
    // Producers only emit a positive integral size. Reject fractional/negative values instead of
    // silently truncating a malformed native envelope.
    if (value.toDouble() != longValue.toDouble() || longValue <= 0L) return null
    return longValue
}

private fun JSONObject.optionalComposerImageEventInt(key: String): Int? {
    if (!has(key)) return null
    val value = opt(key) as? Number ?: return null
    val intValue = value.toInt()
    if (value.toDouble() != intValue.toDouble()) return null
    return intValue
}

/**
 * Parse and validate a composer-image event without applying a consumer-specific ownership
 * policy.  Settings and Thread both call this function before claiming/adopting the file.
 */
internal fun parseSlockFilePreviewComposerImageEvent(
    data: JSONObject,
    requireGroup: Boolean = false
): SlockFilePreviewComposerImageEventPayload? {
    val previewSessionId = data.requiredComposerImageEventString("previewSessionId") ?: return null
    val threadKey = data.requiredComposerImageEventString("threadKey") ?: return null
    val actionId = data.requiredComposerImageEventString("actionId") ?: return null
    val action = data.requiredComposerImageEventString("action") ?: return null
    if (action != "complete" && action != "replace") return null
    val replaceAttachmentId =
        data.requiredComposerImageEventString("replaceAttachmentId").orEmpty()
    if (action == "replace" && replaceAttachmentId.isBlank()) return null
    val fileRef = data.requiredComposerImageEventString("fileRef") ?: return null
    val previewUri = data.requiredComposerImageEventString("previewUri") ?: return null
    val fileName = data.requiredComposerImageEventString("fileName") ?: return null
    val mimeType = data.requiredComposerImageEventString("mimeType") ?: return null
    val source = data.requiredComposerImageEventString("source") ?: return null
    val compressed = data.requiredComposerImageEventBoolean("compressed") ?: return null
    if (!slockFilePreviewComposerImageEventMetadataIsValid(source, compressed, mimeType)) return null

    val sizeBytes =
        if (data.has("sizeBytes")) {
            data.optionalComposerImageEventLong("sizeBytes") ?: return null
        } else {
            null
        }
    val groupId =
        if (data.has("groupId")) {
            data.requiredComposerImageEventString("groupId") ?: return null
        } else {
            null
        }
    val groupIndex =
        if (data.has("groupIndex")) {
            data.optionalComposerImageEventInt("groupIndex") ?: return null
        } else {
            null
        }
    val groupTotal =
        if (data.has("groupTotal")) {
            data.optionalComposerImageEventInt("groupTotal") ?: return null
        } else {
            null
        }
    val hasAnyGroupField = groupId != null || groupIndex != null || groupTotal != null
    if (requireGroup && (groupId == null || groupIndex == null || groupTotal == null)) return null
    if (hasAnyGroupField && (groupId == null || groupIndex == null || groupTotal == null)) return null
    if (groupId != null) {
        val index = groupIndex ?: return null
        val total = groupTotal ?: return null
        if (index < 0 || total <= 0 || index >= total) return null
    }

    return SlockFilePreviewComposerImageEventPayload(
        previewSessionId = previewSessionId,
        threadKey = threadKey,
        actionId = actionId,
        action = action,
        fileRef = fileRef,
        previewUri = previewUri,
        fileName = fileName,
        mimeType = mimeType,
        source = source,
        compressed = compressed,
        sizeBytes = sizeBytes,
        groupId = groupId,
        groupIndex = groupIndex,
        groupTotal = groupTotal,
        replaceAttachmentId = replaceAttachmentId
    )
}

/**
 * A page-local token for an editor/gallery compression operation.
 *
 * The native bridge is asynchronous and a capture callback can outlive the page that requested
 * it.  The generation makes a late callback from an older request unmatchable even when a new
 * request is started with the same action id.  [retire] is permanent for the page instance.
 */
internal data class SlockFilePreviewComposerImageOperationToken(
    val generation: Long,
    val requestId: String
)

internal class SlockFilePreviewComposerImageOperationGate(
    private val requestIdFactory: (Long) -> String = { generation ->
        "file-preview-image-compress-$generation"
    }
) {
    private val lock = SynchronizedObject()
    private var nextGeneration = 0L
    private var active: SlockFilePreviewComposerImageOperationToken? = null
    private var retired = false

    fun begin(requestId: String = ""): SlockFilePreviewComposerImageOperationToken? =
        synchronized(lock) {
            if (retired || active != null) return@synchronized null
            nextGeneration += 1L
            val generation = nextGeneration
            val cleanRequestId = requestId.trim().ifBlank { requestIdFactory(generation) }
            SlockFilePreviewComposerImageOperationToken(generation, cleanRequestId).also {
                active = it
            }
        }

    fun isCurrent(token: SlockFilePreviewComposerImageOperationToken): Boolean =
        synchronized(lock) { !retired && active == token }

    /** Atomically consumes the operation. A duplicate/late completion returns false. */
    fun complete(token: SlockFilePreviewComposerImageOperationToken): Boolean =
        synchronized(lock) {
            if (retired || active != token) return@synchronized false
            active = null
            true
        }

    /** Cancels one operation without retiring the page (the user may retry). */
    fun cancel(token: SlockFilePreviewComposerImageOperationToken): Boolean =
        synchronized(lock) {
            if (retired || active != token) return@synchronized false
            active = null
            true
        }

    /** Invalidates every outstanding callback for this page instance. */
    fun retire() = synchronized(lock) {
        retired = true
        active = null
    }

    fun activeToken(): SlockFilePreviewComposerImageOperationToken? = synchronized(lock) { active }
}

internal sealed interface SlockFilePreviewCompressedImageResult {
    data class Success(val file: PlatformFile) : SlockFilePreviewCompressedImageResult

    data class Failure(
        val errorCode: String = SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE,
        val message: String = "Unable to compress image attachment"
    ) : SlockFilePreviewCompressedImageResult
}

/**
 * Parse the strict shared/native compression response.  A response is accepted only when the
 * native side returned exactly one JPEG produced by the composer compressor.  In particular,
 * an arbitrary picker file or a missing body is never treated as a successful compression.
 */
internal fun parseSlockFilePreviewCompressedImageResult(
    response: JSONObject?
): SlockFilePreviewCompressedImageResult {
    val code = response?.optInt("code", -1) ?: -1
    if (code != 200) {
        return SlockFilePreviewCompressedImageResult.Failure(
            // Keep the product-facing failure taxonomy stable even when a host
            // returns an unknown/malformed provider-specific code.
            errorCode = SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE,
            message =
                response
                    ?.optString("errorBodyText")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unable to compress image attachment"
        )
    }
    val filesJson =
        response?.optJSONArray("files")
            ?: response?.optString("bodyText")
                .orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { bodyText ->
                    runCatching { JSONObject(bodyText).optJSONArray("files") }.getOrNull()
                }
    if (filesJson == null || filesJson.length() != 1) {
        return SlockFilePreviewCompressedImageResult.Failure()
    }
    val rawFile = filesJson.optJSONObject(0) ?: return SlockFilePreviewCompressedImageResult.Failure()
    // parseSharedThreadPickedPlatformFiles supplies compatibility defaults (for example an
    // attachment-N file name).  Compression is stricter: every required field must be present in
    // the native envelope itself, so a malformed response can never be promoted accidentally.
    val rawFileRef = rawFile.optString("fileRef").trim()
    val rawFileName = rawFile.optString("fileName").trim()
    // MIME is an exact wire contract here.  Do not normalize case or strip
    // parameters: a host response with anything other than literal
    // `image/jpeg` is not proof that a JPEG was produced.
    val rawMimeType = rawFile.optString("mimeType").trim()
    val rawSource = rawFile.optString("source").trim()
    if (
        rawFileRef.isBlank() ||
            rawFileName.isBlank() ||
            rawMimeType != "image/jpeg" ||
            rawSource != SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE
    ) {
        return SlockFilePreviewCompressedImageResult.Failure()
    }
    val files = parseSharedThreadPickedPlatformFiles(response)
    if (files.size != 1) {
        return SlockFilePreviewCompressedImageResult.Failure()
    }
    val file = files.single()
    val mimeType = file.mimeType.orEmpty().trim()
    if (
        file.fileRef?.trim() != rawFileRef ||
            file.fileName.trim() != rawFileName ||
            mimeType != "image/jpeg" ||
            file.source.trim() != rawSource
    ) {
        return SlockFilePreviewCompressedImageResult.Failure()
    }
    return SlockFilePreviewCompressedImageResult.Success(
        file.copy(mimeType = "image/jpeg", source = SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE)
    )
}

/** Any file refs in a malformed/failed response are still retired; they are never adopted. */
internal fun slockFilePreviewCompressionResponseFileRefs(response: JSONObject?): Set<String> =
    parseSharedThreadPickedPlatformFiles(response)
        .mapNotNull { it.fileRef?.trim()?.takeIf { ref -> ref.isNotBlank() } }
        .toSet()

// task #113: whole-group edit-session result reconciliation (pure). A consumer
// (e.g. Settings feedback draft) accumulates per-image edit events for a group and
// only releases the whole group when N/N is present and consistent.
//
// The frozen authority lives on the request/session state: expectedGroupId + expectedCount.
// Freshness/generation is proven by the UNIQUE groupId plus the consumer's own
// account/page-generation gate (we intentionally do NOT fake a per-event token field;
// cross-process events carry no authoritative generation, so a token comparison here
// would either reject every legal event or accept spoofed old ones).
//
// `fold` only accepts ACTUAL event fields and must be zero-mutation on any group /
// total / range mismatch. A DUPLICATE index that replays the SAME itemKey is an
// idempotent no-op (exactly-once redelivery must not poison a complete group); a
// duplicate index with a CONFLICTING itemKey is rejected (poison).
internal data class SlockImageEditGroupState(
    val expectedGroupId: String,
    val expectedCount: Int,
    val total: Int? = null,
    val itemsByIndex: Map<Int, String> = emptyMap(),
    val rejects: List<String> = emptyList()
) {
    val isComplete: Boolean
        get() =
            total != null &&
                total == expectedCount &&
                itemsByIndex.size == expectedCount &&
                (0 until expectedCount).none { !itemsByIndex.containsKey(it) }
    /** A group is only committable when it is complete AND no event was ever rejected. */
    val isCommittable: Boolean
        get() = isComplete && rejects.isEmpty()
}

internal object SlockImageEditGroupReconcile {
    const val REJECT_WRONG_GROUP = "wrong_group"
    const val REJECT_TOTAL_MISMATCH = "total_mismatch"
    const val REJECT_OUT_OF_RANGE = "out_of_range"
    const val REJECT_DUPLICATE_CONFLICT = "duplicate_conflict"

    /**
     * Production-used group-total resolution: for a non-blank groupId the total is the
     * gallery size, floored to 1 so a single image with no gallery still emits total=1
     * (single-image N=1 contract, never 0). Blank groupId keeps legacy on-zero semantics.
     */
    fun resolveGroupTotal(groupId: String, gallerySize: Int): Int =
        if (groupId.isBlank()) gallerySize
        else gallerySize.coerceAtLeast(1)
    /**
     * Fold one incoming edit event (actual fields) into a group state. The state
     * already holds the frozen expected group id / count; any group/total/range
     * mismatch returns the state unchanged (zero mutation) plus the rejection reason.
     * A same-index idempotent replay (identical itemKey) is a no-op; only a conflicting
     * itemKey at an occupied index is rejected.
     */
    fun fold(
        state: SlockImageEditGroupState,
        actualGroupId: String,
        index: Int,
        total: Int,
        itemKey: String
    ): SlockImageEditGroupState {
        if (actualGroupId != state.expectedGroupId) {
            return state.withReject(REJECT_WRONG_GROUP)
        }
        if (total != state.expectedCount || total <= 0) {
            return state.withReject(REJECT_TOTAL_MISMATCH)
        }
        if (index < 0 || index >= total) {
            return state.withReject(REJECT_OUT_OF_RANGE)
        }
        val existing = state.itemsByIndex[index]
        if (existing != null) {
            if (existing == itemKey) return state // idempotent replay no-op
            return state.withReject(REJECT_DUPLICATE_CONFLICT)
        }
        return state.copy(
            total = total,
            itemsByIndex = state.itemsByIndex + (index to itemKey)
        )
    }

    private fun SlockImageEditGroupState.withReject(reason: String): SlockImageEditGroupState =
        if (reason in rejects) this
        else copy(rejects = rejects + reason)
}
private const val SlockFilePreviewSaveToPhotosActionId = "save-to-photos"
private const val SlockFilePreviewSaveToFilesActionId = "save-to-files"
private const val SlockFilePreviewOpenWithActionId = "open-with"
private const val SlockFilePreviewDeleteDownloadActionId = "delete-download"
private const val SlockFilePreviewCommentsActionId = "comments"

internal enum class SlockFilePreviewExportIsolationEvent {
    Started,
    Succeeded,
    Cancelled,
    Failed
}

internal data class SlockFilePreviewExportIsolationState(
    val downloadStatus: String,
    val downloadProgress: BridgeAttachmentDownloadProgress?,
    val activeDownloadId: String?,
    val exportInProgress: Boolean
)

internal fun reduceSlockFilePreviewExportIsolationState(
    state: SlockFilePreviewExportIsolationState,
    event: SlockFilePreviewExportIsolationEvent
): SlockFilePreviewExportIsolationState =
    state.copy(exportInProgress = event == SlockFilePreviewExportIsolationEvent.Started)
internal const val SlockFilePreviewSourceThreadAttachment = "thread_attachment"
internal const val SlockFilePreviewSourceAgentWorkspace = "agent_workspace"
internal const val SlockFilePreviewRouteThreadAttachment = "thread_attachment"
internal const val SlockFilePreviewRouteAgentWorkspace = "agent_workspace"
internal const val SlockFilePreviewRouteEphemeral = "ephemeral"
private const val SlockFilePreviewLaunchContractVersion = 1
private const val SlockFilePreviewMarkdownMaxChars = 80_000
private const val SlockFilePreviewTextMaxChars = 96_000
private const val SlockFilePreviewVideoSeekProp = "seekToTimeMs"
private const val SlockFilePreviewImageGalleryMaxInFlightRequests = 2
private const val SlockScopedNavigationLogTag = "slock_scoped_navigation"

private val SlockFilePreviewJson = Json { ignoreUnknownKeys = true }

internal enum class SlockFilePreviewPhotosActionState { Hidden, Save, Saving, Retry, Busy }

internal data class SlockFilePreviewProgressEvent(
    val operation: AttachmentOperationToken,
    val progress: BridgeAttachmentDownloadProgress
)

internal class SlockFilePreviewProgressMailbox {
    private val events = Channel<SlockFilePreviewProgressEvent>(Channel.UNLIMITED)

    fun offer(operation: AttachmentOperationToken, progress: BridgeAttachmentDownloadProgress): Boolean =
        events.trySend(SlockFilePreviewProgressEvent(operation, progress)).isSuccess

    suspend fun nextOrNull(): SlockFilePreviewProgressEvent? = events.receiveCatching().getOrNull()

    fun close() {
        events.close()
    }
}

internal data class SlockFilePreviewExternalOperationIdentity(
    val source: String,
    val attachmentId: String,
    val path: String,
    val owner: AttachmentOwnerLease
)

internal fun slockFilePreviewShouldRetireExternalOperation(
    active: SlockFilePreviewExternalOperationIdentity?,
    visible: SlockFilePreviewExternalOperationIdentity?
): Boolean = active != null && active != visible

internal fun slockFilePreviewPhotosActionState(
    supported: Boolean,
    eligible: Boolean,
    operation: String?,
    status: String
): SlockFilePreviewPhotosActionState =
    when {
        !supported || !eligible -> SlockFilePreviewPhotosActionState.Hidden
        status in setOf("downloading", "paused") && operation == "save_photos" ->
            SlockFilePreviewPhotosActionState.Saving
        status in setOf("downloading", "paused") -> SlockFilePreviewPhotosActionState.Busy
        status == "failed" && operation == "save_photos" -> SlockFilePreviewPhotosActionState.Retry
        else -> SlockFilePreviewPhotosActionState.Save
    }

internal data class SlockFilePreviewVideoSeekRequest(
    val id: Int,
    val timeMs: Int
)

@Serializable
internal data class WireSlockFilePreviewLaunchPayload(
    val version: Int = SlockFilePreviewLaunchContractVersion,
    val sessionId: String = "",
    val routeKind: String = "",
    val attachmentId: String = "",
    val previewKind: String = "",
    val attachmentCommentsEnabled: Boolean = false,
    val commentsInitiallyOpen: Boolean = false,
    val galleryAttachmentIds: List<String> = emptyList(),
    val galleryIndex: Int = 0,
    val agentId: String = "",
    val workspacePath: String = ""
)

internal fun newSlockFilePreviewSessionId(
    randomLong: () -> Long = { Random.nextLong() }
): String =
    "file-preview-${randomLong().toULong().toString(36)}-${randomLong().toULong().toString(36)}"

internal class SlockFilePreviewStore(
    private val maxEntries: Int = 32,
    private val idFactory: () -> String = { newSlockFilePreviewSessionId() }
) {
    private val entries = LinkedHashMap<String, SlockFilePreviewPayload>()

    fun put(payload: SlockFilePreviewPayload): String {
        var id = idFactory()
        while (id in entries) id = idFactory()
        entries[id] = payload
        while (entries.size > maxEntries) {
            val firstKey = entries.keys.firstOrNull() ?: break
            entries.remove(firstKey)
        }
        return id
    }

    fun get(id: String): SlockFilePreviewPayload? =
        entries[id]

    fun retire(id: String): Boolean = entries.remove(id) != null
}

internal data class SlockFilePreviewEphemeralSessionRegistration(
    val sessionId: String,
    val threadKey: String,
    val actionIds: Set<String>,
    val ownedTemporaryFileRefs: Set<String>
)

internal data class SlockFilePreviewEphemeralSessionRetirement(
    val cleanupFileRefs: Set<String> = emptySet(),
    val pendingFileRefs: Set<String> = emptySet(),
    val retiredSession: Boolean = false
)

internal sealed interface SlockFilePreviewEphemeralActionClaim {
    data object Rejected : SlockFilePreviewEphemeralActionClaim

    data class Accepted(val sessionComplete: Boolean) : SlockFilePreviewEphemeralActionClaim
}

/**
 * Process-local admission for composer/editor result events.
 *
 * The launch route is deliberately not an authority for a callback. A result must first move
 * through this registry, which is lost on process death and atomically consumes each action once.
 * A pending delivery survives ordinary page disposal long enough for the destination consumer to
 * claim it, but it cannot be reconstructed from a restored route or an old cross-process replay.
 */
internal class SlockFilePreviewEphemeralActionRegistry(
    private val maxSessions: Int = 64
) {
    private data class ActiveSession(
        val registration: SlockFilePreviewEphemeralSessionRegistration,
        val ownedTemporaryFileRefs: MutableSet<String>
    )

    private data class PendingSession(
        val threadKey: String,
        val expectedFileRefByActionId: Map<String, String>,
        val remainingActionIds: MutableSet<String>,
        val claimedActionIds: MutableSet<String>,
        val transferredActionIds: MutableSet<String>,
        val ownedTemporaryFileRefs: Set<String>
    )

    private val lock = SynchronizedObject()
    private val active = LinkedHashMap<String, ActiveSession>()
    private val pending = LinkedHashMap<String, PendingSession>()
    private val sessionOrder = linkedSetOf<String>()

    fun register(
        sessionId: String,
        threadKey: String,
        actionIds: Set<String>,
        ownedTemporaryFileRefs: Set<String>
    ): SlockFilePreviewEphemeralSessionRetirement {
        val normalizedSessionId = sessionId.trim()
        val normalizedThreadKey = threadKey.trim()
        val normalizedActions = actionIds.map(String::trim).filter(String::isNotBlank).toSet()
        val normalizedRefs = ownedTemporaryFileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        require(normalizedSessionId.isNotBlank()) { "sessionId must not be blank" }
        require(normalizedThreadKey.isNotBlank()) { "threadKey must not be blank" }
        require(normalizedActions.isNotEmpty()) { "actionIds must not be empty" }
        require(normalizedActions.size == actionIds.size) { "actionIds must be unique" }
        val registration =
            SlockFilePreviewEphemeralSessionRegistration(
                sessionId = normalizedSessionId,
                threadKey = normalizedThreadKey,
                actionIds = normalizedActions,
                ownedTemporaryFileRefs = normalizedRefs
        )
        return synchronized(lock) {
            var cleanupRefs =
                active.remove(normalizedSessionId)?.ownedTemporaryFileRefs.orEmpty() +
                    pending.remove(normalizedSessionId)?.cleanupFileRefs().orEmpty()
            sessionOrder.remove(normalizedSessionId)
            active[normalizedSessionId] =
                ActiveSession(
                    registration = registration,
                    ownedTemporaryFileRefs = normalizedRefs.toMutableSet()
                )
            sessionOrder.add(normalizedSessionId)
            while (sessionOrder.size > maxSessions.coerceAtLeast(1)) {
                val evictedId = sessionOrder.firstOrNull() ?: break
                sessionOrder.remove(evictedId)
                cleanupRefs += active.remove(evictedId)?.ownedTemporaryFileRefs.orEmpty()
                cleanupRefs += pending.remove(evictedId)?.cleanupFileRefs().orEmpty()
            }
            SlockFilePreviewEphemeralSessionRetirement(cleanupFileRefs = cleanupRefs)
        }
    }

    fun trackOwnedTemporaryFileRef(
        sessionId: String,
        threadKey: String,
        fileRef: String
    ): Boolean {
        val normalizedSessionId = sessionId.trim()
        val normalizedThreadKey = threadKey.trim()
        val normalizedFileRef = fileRef.trim()
        if (normalizedSessionId.isBlank() || normalizedThreadKey.isBlank() || normalizedFileRef.isBlank()) {
            return false
        }
        return synchronized(lock) {
            val session = active[normalizedSessionId] ?: return@synchronized false
            if (session.registration.threadKey != normalizedThreadKey) return@synchronized false
            session.ownedTemporaryFileRefs += normalizedFileRef
            true
        }
    }

    fun prepareDeliveryBatch(
        sessionId: String,
        threadKey: String,
        fileRefByActionId: Map<String, String>
    ): SlockFilePreviewEphemeralSessionRetirement? {
        val normalizedSessionId = sessionId.trim()
        val normalizedThreadKey = threadKey.trim()
        val normalizedDeliveries =
            fileRefByActionId
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() }
                .filter { (actionId, fileRef) -> actionId.isNotBlank() && fileRef.isNotBlank() }
        if (normalizedDeliveries.size != fileRefByActionId.size) return null
        return synchronized(lock) {
            val activeSession = active[normalizedSessionId] ?: return@synchronized null
            if (
                activeSession.registration.threadKey != normalizedThreadKey ||
                normalizedDeliveries.keys != activeSession.registration.actionIds
            ) {
                return@synchronized null
            }
            val existing = pending[normalizedSessionId]
            if (existing != null) return@synchronized null
            active.remove(normalizedSessionId)
            val deliveredFileRefs = normalizedDeliveries.values.toSet()
            val pendingOwnedFileRefs = activeSession.ownedTemporaryFileRefs intersect deliveredFileRefs
            pending[normalizedSessionId] =
                PendingSession(
                    threadKey = normalizedThreadKey,
                    expectedFileRefByActionId = normalizedDeliveries,
                    remainingActionIds = normalizedDeliveries.keys.toMutableSet(),
                    claimedActionIds = mutableSetOf(),
                    transferredActionIds = mutableSetOf(),
                    ownedTemporaryFileRefs = pendingOwnedFileRefs
                )
            SlockFilePreviewEphemeralSessionRetirement(
                cleanupFileRefs = activeSession.ownedTemporaryFileRefs - deliveredFileRefs,
                pendingFileRefs = pendingOwnedFileRefs
            )
        }
    }

    fun claim(
        sessionId: String,
        threadKey: String,
        actionId: String,
        fileRef: String
    ): SlockFilePreviewEphemeralActionClaim {
        val normalizedSessionId = sessionId.trim()
        val normalizedThreadKey = threadKey.trim()
        val normalizedActionId = actionId.trim()
        val normalizedFileRef = fileRef.trim()
        return synchronized(lock) {
            val session = pending[normalizedSessionId] ?: return@synchronized SlockFilePreviewEphemeralActionClaim.Rejected
            if (
                session.threadKey != normalizedThreadKey ||
                normalizedFileRef.isBlank() ||
                session.expectedFileRefByActionId[normalizedActionId] != normalizedFileRef ||
                normalizedActionId !in session.remainingActionIds
            ) {
                return@synchronized SlockFilePreviewEphemeralActionClaim.Rejected
            }
            session.remainingActionIds.remove(normalizedActionId)
            session.claimedActionIds.add(normalizedActionId)
            val complete = session.remainingActionIds.isEmpty()
            SlockFilePreviewEphemeralActionClaim.Accepted(sessionComplete = complete)
        }
    }

    fun markTransferred(
        sessionId: String,
        threadKey: String,
        actionId: String,
        fileRef: String
    ): Boolean {
        val normalizedSessionId = sessionId.trim()
        val normalizedThreadKey = threadKey.trim()
        val normalizedActionId = actionId.trim()
        val normalizedFileRef = fileRef.trim()
        return synchronized(lock) {
            val session = pending[normalizedSessionId] ?: return@synchronized false
            if (
                session.threadKey != normalizedThreadKey ||
                session.expectedFileRefByActionId[normalizedActionId] != normalizedFileRef ||
                normalizedActionId !in session.claimedActionIds
            ) {
                return@synchronized false
            }
            session.transferredActionIds.add(normalizedActionId)
            true
        }
    }

    fun markAllClaimedTransferred(sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        return synchronized(lock) {
            val session = pending[normalizedSessionId] ?: return@synchronized false
            if (
                session.remainingActionIds.isNotEmpty() ||
                session.claimedActionIds != session.expectedFileRefByActionId.keys
            ) {
                return@synchronized false
            }
            session.transferredActionIds.addAll(session.claimedActionIds)
            true
        }
    }

    fun completePending(sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        return synchronized(lock) {
            val session = pending[normalizedSessionId] ?: return@synchronized false
            if (
                session.remainingActionIds.isNotEmpty() ||
                session.transferredActionIds != session.expectedFileRefByActionId.keys
            ) {
                return@synchronized false
            }
            pending.remove(normalizedSessionId)
            sessionOrder.remove(normalizedSessionId)
            true
        }
    }

    fun retireActive(sessionId: String): SlockFilePreviewEphemeralSessionRetirement {
        val normalizedSessionId = sessionId.trim()
        return synchronized(lock) {
            val activeSession = active.remove(normalizedSessionId)
            if (activeSession != null) sessionOrder.remove(normalizedSessionId)
            if (activeSession == null) {
                SlockFilePreviewEphemeralSessionRetirement()
            } else {
                SlockFilePreviewEphemeralSessionRetirement(
                    cleanupFileRefs = activeSession.ownedTemporaryFileRefs,
                    retiredSession = true
                )
            }
        }
    }

    fun retirePending(sessionId: String): SlockFilePreviewEphemeralSessionRetirement {
        val normalizedSessionId = sessionId.trim()
        return synchronized(lock) {
            val pendingSession = pending.remove(normalizedSessionId)
            if (pendingSession != null) sessionOrder.remove(normalizedSessionId)
            if (pendingSession == null) {
                SlockFilePreviewEphemeralSessionRetirement()
            } else {
                SlockFilePreviewEphemeralSessionRetirement(
                    cleanupFileRefs = pendingSession.cleanupFileRefs(),
                    retiredSession = true
                )
            }
        }
    }

    fun retirePendingIfUnclaimed(sessionId: String): SlockFilePreviewEphemeralSessionRetirement {
        val normalizedSessionId = sessionId.trim()
        return synchronized(lock) {
            val pendingSession = pending[normalizedSessionId]
            if (pendingSession == null || pendingSession.claimedActionIds.isNotEmpty()) {
                SlockFilePreviewEphemeralSessionRetirement()
            } else {
                pending.remove(normalizedSessionId)
                sessionOrder.remove(normalizedSessionId)
                SlockFilePreviewEphemeralSessionRetirement(
                    cleanupFileRefs = pendingSession.cleanupFileRefs(),
                    retiredSession = true
                )
            }
        }
    }

    fun activeSessionIds(): Set<String> =
        synchronized(lock) { active.keys.toSet() + pending.keys }

    fun hasSession(sessionId: String): Boolean =
        synchronized(lock) {
            val normalizedSessionId = sessionId.trim()
            normalizedSessionId in active || normalizedSessionId in pending
        }

    private fun PendingSession.cleanupFileRefs(): Set<String> {
        val transferredFileRefs =
            transferredActionIds.mapNotNullTo(mutableSetOf()) { actionId ->
                expectedFileRefByActionId[actionId]
            }
        return ownedTemporaryFileRefs - transferredFileRefs
    }
}

/** Small persistent ledger for cache-owned refs; it never participates in route serialization. */
internal class SlockFilePreviewEphemeralCleanupLedger(
    private val store: SharedPreferenceStore,
    private val maxEntries: Int = 64
) {
    private val lock = SynchronizedObject()

    fun register(sessionId: String, fileRefs: Set<String>): Set<String> {
        val id = sessionId.trim()
        val refs = fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        if (id.isBlank() || refs.isEmpty()) return emptySet()
        return synchronized(lock) {
            val index = readIndex().toMutableList().apply { remove(id); add(id) }
            store.putString(recordKey(id), encode(refs))
            val evicted = mutableSetOf<String>()
            while (index.size > maxEntries.coerceAtLeast(1)) {
                val evictedId = index.removeAt(0)
                evicted += readRecord(evictedId)
                store.putString(recordKey(evictedId), null)
            }
            writeIndex(index)
            evicted
        }
    }

    fun addFileRefs(sessionId: String, fileRefs: Set<String>): Set<String> {
        val id = sessionId.trim()
        val refs = fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        if (id.isBlank() || refs.isEmpty()) return emptySet()
        return synchronized(lock) {
            registerLocked(id, readRecord(id) + refs)
        }
    }

    fun replace(sessionId: String, fileRefs: Set<String>): Set<String> {
        val id = sessionId.trim()
        return synchronized(lock) {
            if (id.isBlank()) return@synchronized emptySet()
            val refs = fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
            if (refs.isEmpty()) {
                removeLocked(id)
                emptySet()
            } else {
                registerLocked(id, refs)
            }
        }
    }

    fun take(sessionId: String): Set<String> =
        synchronized(lock) {
            val id = sessionId.trim()
            if (id.isBlank()) emptySet() else readRecord(id).also { removeLocked(id) }
        }

    fun discard(sessionId: String, fileRefs: Set<String>) {
        val id = sessionId.trim()
        val refs = fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        if (id.isBlank() || refs.isEmpty()) return
        synchronized(lock) {
            val remaining = readRecord(id) - refs
            if (remaining.isEmpty()) {
                removeLocked(id)
            } else {
                store.putString(recordKey(id), encode(remaining))
            }
        }
    }

    fun takeOrphans(activeSessionIds: Set<String>): Set<String> =
        synchronized(lock) {
            val activeIds = activeSessionIds.map(String::trim).toSet()
            val orphanIds = readIndex().filterNot { it in activeIds }
            val refs = orphanIds.flatMapTo(mutableSetOf(), ::readRecord)
            orphanIds.forEach { removeLocked(it) }
            refs
        }

    private fun readIndex(): List<String> =
        runCatching {
            SlockFilePreviewJson.decodeFromString(
                SlockFilePreviewCleanupIndex.serializer(),
                store.getString(INDEX_KEY).orEmpty()
            ).sessionIds.filter(String::isNotBlank).distinct()
        }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<String>) {
        store.putString(
            INDEX_KEY,
            SlockFilePreviewJson.encodeToString(SlockFilePreviewCleanupIndex.serializer(), SlockFilePreviewCleanupIndex(ids))
        )
    }

    private fun readRecord(sessionId: String): Set<String> =
        runCatching {
            SlockFilePreviewJson.decodeFromString(
                SlockFilePreviewCleanupRecord.serializer(),
                store.getString(recordKey(sessionId)).orEmpty()
            ).fileRefs.map(String::trim).filter(String::isNotBlank).toSet()
        }.getOrDefault(emptySet())

    private fun removeLocked(sessionId: String) {
        store.putString(recordKey(sessionId), null)
        writeIndex(readIndex().filterNot { it == sessionId })
    }

    private fun encode(fileRefs: Set<String>): String =
        SlockFilePreviewJson.encodeToString(
            SlockFilePreviewCleanupRecord.serializer(),
            SlockFilePreviewCleanupRecord(fileRefs = fileRefs.toList())
        )

    private fun registerLocked(sessionId: String, fileRefs: Set<String>): Set<String> {
        val index = readIndex().toMutableList().apply { remove(sessionId); add(sessionId) }
        store.putString(recordKey(sessionId), encode(fileRefs))
        val evicted = mutableSetOf<String>()
        while (index.size > maxEntries.coerceAtLeast(1)) {
            val evictedId = index.removeAt(0)
            evicted += readRecord(evictedId)
            store.putString(recordKey(evictedId), null)
        }
        writeIndex(index)
        return evicted
    }

    private fun recordKey(sessionId: String): String = "$RECORD_KEY_PREFIX$sessionId"

    private companion object {
        const val INDEX_KEY = "slock:file-preview:ephemeral-cleanup:index"
        const val RECORD_KEY_PREFIX = "slock:file-preview:ephemeral-cleanup:"
    }
}

@Serializable
private data class SlockFilePreviewCleanupIndex(val sessionIds: List<String> = emptyList())

@Serializable
private data class SlockFilePreviewCleanupRecord(
    val fileRefs: List<String> = emptyList()
)

private val ProcessSlockFilePreviewEphemeralActionRegistry = SlockFilePreviewEphemeralActionRegistry()
private val ProcessSlockFilePreviewEphemeralCleanupLedger: SlockFilePreviewEphemeralCleanupLedger by lazy {
    SlockFilePreviewEphemeralCleanupLedger(slockSharedPreferenceStore())
}

internal fun slockFilePreviewEphemeralActionRegistry(): SlockFilePreviewEphemeralActionRegistry =
    ProcessSlockFilePreviewEphemeralActionRegistry

internal fun slockFilePreviewEphemeralCleanupLedger(): SlockFilePreviewEphemeralCleanupLedger =
    ProcessSlockFilePreviewEphemeralCleanupLedger

internal fun slockFilePreviewPostComposerImageEvents(
    events: List<JSONObject>,
    eventBus: SlockEventBus = SlockEventBuses.inProcess()
): Boolean {
    var posted = true
    events.forEach { event ->
        val currentPosted =
            runCatching {
                eventBus.post(
                    SLOCK_COMPOSER_IMAGE_EDIT_EVENT,
                    event,
                    crossProcess = false
                )
            }.isSuccess
        posted = currentPosted && posted
    }
    return posted
}

internal sealed interface SlockFilePreviewComposerImageEventAdmission {
    data class Rejected(
        val sessionId: String = "",
        val orphanedTemporaryFileRefs: Set<String> = emptySet()
    ) : SlockFilePreviewComposerImageEventAdmission

    data class Accepted(
        val sessionId: String,
        val threadKey: String,
        val actionId: String,
        val fileRef: String,
        val sessionComplete: Boolean
    ) : SlockFilePreviewComposerImageEventAdmission
}

internal fun slockFilePreviewClaimComposerImageEvent(
    eventData: JSONObject,
    expectedThreadKey: String,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry(),
    cleanupLedger: SlockFilePreviewEphemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger()
): SlockFilePreviewComposerImageEventAdmission {
    val sessionId = eventData.optString("previewSessionId").trim()
    val threadKey = eventData.optString("threadKey").trim()
    val actionId = eventData.optString("actionId").trim()
    val fileRef = eventData.optString("fileRef").trim()
    val expectedKey = expectedThreadKey.trim()
    if (
        sessionId.isBlank() ||
        expectedKey.isBlank() ||
        threadKey != expectedKey ||
        actionId.isBlank() ||
        fileRef.isBlank()
    ) {
        return SlockFilePreviewComposerImageEventAdmission.Rejected(
            sessionId = sessionId,
            orphanedTemporaryFileRefs =
                if (sessionId.isNotBlank() && !registry.hasSession(sessionId)) {
                    cleanupLedger.take(sessionId)
                } else {
                    emptySet()
                }
        )
    }
    return when (val claim = registry.claim(sessionId, threadKey, actionId, fileRef)) {
        SlockFilePreviewEphemeralActionClaim.Rejected ->
            SlockFilePreviewComposerImageEventAdmission.Rejected(
                sessionId = sessionId,
                orphanedTemporaryFileRefs =
                    if (registry.hasSession(sessionId)) {
                        emptySet()
                    } else {
                        cleanupLedger.take(sessionId)
                    }
            )
        is SlockFilePreviewEphemeralActionClaim.Accepted ->
            SlockFilePreviewComposerImageEventAdmission.Accepted(
                sessionId = sessionId,
                threadKey = threadKey,
                actionId = actionId,
                fileRef = fileRef,
                sessionComplete = claim.sessionComplete
            )
    }
}

internal fun slockFilePreviewMarkComposerImageEventTransferred(
    admission: SlockFilePreviewComposerImageEventAdmission.Accepted,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry(),
    cleanupLedger: SlockFilePreviewEphemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger()
): Boolean {
    val recorded =
        registry.markTransferred(
            sessionId = admission.sessionId,
            threadKey = admission.threadKey,
            actionId = admission.actionId,
            fileRef = admission.fileRef
        )
    if (recorded) {
        cleanupLedger.discard(admission.sessionId, setOf(admission.fileRef))
    }
    return recorded
}

/**
 * Finishes a fully claimed batch. Successful transfer drops cleanup ownership without deleting;
 * a rejected transfer returns every cache ref that the host must retire.
 */
internal fun slockFilePreviewFinishComposerImageEventSession(
    sessionId: String,
    transferred: Boolean,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry(),
    cleanupLedger: SlockFilePreviewEphemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger()
): Set<String> {
    return if (transferred) {
        val marked = registry.markAllClaimedTransferred(sessionId)
        val completed = marked && registry.completePending(sessionId)
        if (completed) {
            cleanupLedger.take(sessionId)
            emptySet()
        } else {
            cleanupLedger.take(sessionId) + registry.retirePending(sessionId).cleanupFileRefs
        }
    } else {
        cleanupLedger.take(sessionId) + registry.retirePending(sessionId).cleanupFileRefs
    }
}

internal fun slockFilePreviewAbortComposerImageEventSession(
    sessionId: String,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry(),
    cleanupLedger: SlockFilePreviewEphemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger()
): Set<String> =
    cleanupLedger.take(sessionId) +
        registry.retireActive(sessionId).cleanupFileRefs +
        registry.retirePending(sessionId).cleanupFileRefs

internal object SlockFilePreviewLaunchPayloadStore {
    private val store = SlockFilePreviewStore()

    fun put(payload: SlockFilePreviewPayload): String =
        store.put(payload)

    fun get(id: String): SlockFilePreviewPayload? =
        store.get(id)

    fun retire(id: String): Boolean =
        store.retire(id)
}

/**
 * task #287: process-local memo for signed attachment URLs used by remote
 * File Preview routes. The signed URL is owner-scoped, so the key carries the
 * normalized durable owner; entries expire on a fixed TTL because the wire
 * `expiresAt` is advisory-only today and a conservative clock window is the
 * safer bound. Re-opening the same image inside the TTL skips both the REST
 * round-trip and the re-download (the image cache stays warm on the same URL).
 */
internal data class SlockFilePreviewAttachmentUrlCacheKey(
    val owner: AuthenticatedRouteOwner,
    val attachmentId: String,
    val disposition: String,
)

internal class SlockFilePreviewAttachmentUrlCache(
    private val maxEntries: Int = 64
) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<SlockFilePreviewAttachmentUrlCacheKey, Pair<String, TimeMark>>()

    fun get(key: SlockFilePreviewAttachmentUrlCacheKey): String? {
        val entry =
            synchronized(lock) {
                entries[key] ?: return null
            }
        return if (!entry.second.hasPassedNow()) entry.first else null
    }

    /**
     * @param expiresAtEpochMillis wall-clock expiry parsed from the wire `expiresAt`; when null
     * (absent/unparseable) the URL lifetime is unknown and the entry is NOT stored.
     * @param expirySafetyMarginMillis subtracted from `expiresAt` so a nearly-dead URL is never
     * replayed (default 60 s). Entries that would expire inside the margin are not stored.
     */
    fun put(
        key: SlockFilePreviewAttachmentUrlCacheKey,
        url: String,
        expiresAtEpochMillis: Long? = null,
        expirySafetyMarginMillis: Long = 60_000L
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        // expiresAt absent/unparseable => lifetime unknown => never cache (Argus review on
        // PR #2618): caching a URL of unknown TTL is exactly the case that can 403 later.
        val serverExpiry = expiresAtEpochMillis ?: return
        val lifetimeMillis = serverExpiry - currentEpochMillis() - expirySafetyMarginMillis
        if (lifetimeMillis <= 0) return
        synchronized(lock) {
            entries[key] = trimmed to TimeSource.Monotonic.markNow().plus(lifetimeMillis.milliseconds)
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    internal var currentEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
}

private val ProcessSlockFilePreviewAttachmentUrlCache = SlockFilePreviewAttachmentUrlCache()

internal fun slockFilePreviewAttachmentUrlCache(): SlockFilePreviewAttachmentUrlCache =
    ProcessSlockFilePreviewAttachmentUrlCache

/**
 * File Preview's route-local authenticated operation boundary. The durable
 * owner is identity-only; every network operation captures a fresh transient
 * receipt and drops a late result when environment/account/token/selection
 * authority changes while the request is in flight.
 */
internal class SlockFilePreviewRemoteOperationScope(
    internal val owner: AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway
) {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    suspend fun <T> run(
        block: suspend (SlockNetworkClient) -> DataResult<T>
    ): DataResult<T> =
        runner.run { _, networkClient -> block(networkClient) }
}

private fun filePreviewThreadApi(
    networkClient: SlockNetworkClient,
    diagnosticsLogger: (String) -> Unit
): SlockThreadApiClient =
    SlockThreadApiClient(
        transport = NetworkSlockThreadApi(networkClient),
        diagnosticsLogger = diagnosticsLogger
    )

private fun filePreviewMemberRepository(
    authStore: SlockAuthSessionStore,
    networkClient: SlockNetworkClient,
    warningLogger: (String) -> Unit,
    logger: (String) -> Unit
): RealMemberRepository =
    RealMemberRepository(
        sessionStore = authStore,
        networkClient = networkClient,
        warningLogger = warningLogger,
        logger = logger
    )

private suspend fun <T> SlockFilePreviewRemoteOperationScope.runThreadApi(
    diagnosticsLogger: (String) -> Unit,
    block: suspend (SlockThreadApiClient) -> T
): DataResult<T> =
    run { networkClient ->
        DataResult.Content(
            block(filePreviewThreadApi(networkClient, diagnosticsLogger))
        )
    }

private suspend fun SlockFilePreviewRemoteOperationScope.loadPreview(
    payload: SlockFilePreviewPayload,
    authStore: SlockAuthSessionStore,
    truncatedPreviewHint: String,
    errorStrings: ErrorPresentationStrings,
    diagnosticsLogger: (String) -> Unit,
    memberWarningLogger: (String) -> Unit,
    memberLogger: (String) -> Unit
): DataResult<SlockFilePreviewPayload?> {
    // task #287: reuse the signed image URL inside its TTL so a repeated
    // preview open skips both the REST call and the re-download.
    if (payload.source == SlockFilePreviewSourceThreadAttachment &&
        payload.kind == "loading" &&
        payload.requestedKind == "image"
    ) {
        val cacheKey =
            owner.normalizedOrNull()?.let { normalizedOwner ->
                SlockFilePreviewAttachmentUrlCacheKey(
                    owner = normalizedOwner,
                    attachmentId = payload.attachmentId.trim(),
                    disposition = "inline"
                )
            }
        val cachedUrl =
            cacheKey?.takeIf { it.attachmentId.isNotBlank() }
                ?.let(slockFilePreviewAttachmentUrlCache()::get)
        if (cachedUrl != null) {
            diagnosticsLogger(
                "attachment_url cache=hit attachment=${payload.attachmentId.take(8)}"
            )
            return DataResult.Content(
                payload.copy(
                    kind = "image",
                    titleDesc = MR.strings.file_preview_image.desc(),
                    imageUrl = cachedUrl,
                    message = "",
                    messageDesc = null,
                    error = null
                )
            )
        }
        var resolvedExpiresAt: String? = null
        val result =
            run { networkClient ->
                DataResult.Content(
                    payload.loadThreadAttachmentPreview(
                        threadApi = filePreviewThreadApi(networkClient, diagnosticsLogger),
                        networkClient = networkClient,
                        truncatedPreviewHint = truncatedPreviewHint,
                        expiresAtSink = { resolvedExpiresAt = it }
                    )
                )
            }
        val resolved = (result as? DataResult.Content)?.data
        if (cacheKey != null && resolved?.kind == "image" && !resolved.imageUrl.isNullOrBlank()) {
            slockFilePreviewAttachmentUrlCache().put(
                key = cacheKey,
                url = resolved.imageUrl.orEmpty(),
                expiresAtEpochMillis = parseSlockTimestampEpochMillisOrNull(resolvedExpiresAt)
            )
            diagnosticsLogger(
                "attachment_url cache=store attachment=${payload.attachmentId.take(8)} " +
                    "expiresAt=${resolvedExpiresAt ?: "none"}"
            )
        }
        return result
    }
    return run { networkClient ->
        val next =
            when (payload.source) {
                SlockFilePreviewSourceThreadAttachment ->
                    payload.loadThreadAttachmentPreview(
                        threadApi = filePreviewThreadApi(networkClient, diagnosticsLogger),
                        networkClient = networkClient,
                        truncatedPreviewHint = truncatedPreviewHint
                    )
                SlockFilePreviewSourceAgentWorkspace ->
                    payload.loadAgentWorkspacePreview(
                        memberRepository =
                            filePreviewMemberRepository(
                                authStore = authStore,
                                networkClient = networkClient,
                                warningLogger = memberWarningLogger,
                                logger = memberLogger
                            ),
                        errorStrings = errorStrings
                    )
                else -> null
            }
        DataResult.Content(next)
    }
}

private suspend fun SlockFilePreviewRemoteOperationScope.resolveExternalUrl(
    payload: SlockFilePreviewPayload,
    diagnosticsLogger: (String) -> Unit
): DataResult<String?> =
    runThreadApi(diagnosticsLogger) { threadApi ->
        payload.threadAttachmentExternalUrl(threadApi)
    }

internal data class SlockFilePreviewPayload(
    val kind: String = "unsupported",
    val requestedKind: String = "",
    val title: String = "",
    val titleDesc: StringDesc = MR.strings.file_preview_title.desc(),
    val subtitle: String = "",
    val name: String = "",
    val path: String = "",
    val meta: String = "",
    val content: String = "",
    val markdown: Boolean = false,
    val imageUrl: String? = null,
    /** task #351: serialized [SlockFilePreviewXlsxCarrier] payload for the xlsx preview panel. */
    val xlsxSheetsJson: String = "",
    val message: String = "",
    val messageDesc: StringDesc? = MR.strings.file_preview_no_preview.desc(),
    val error: String? = null,
    val externalUrl: String? = null,
    val videoUrl: String? = null,
    val audioOnly: Boolean = false,
    val externalFileName: String = "",
    val externalMimeType: String = "",
    val source: String = "",
    val attachmentId: String = "",
    val attachmentCommentsEnabled: Boolean = false,
    val commentsInitiallyOpen: Boolean = false,
    val agentId: String = "",
    val imageGallery: List<SlockFilePreviewGalleryImage> = emptyList(),
    val imageGalleryIndex: Int = 0,
    val composerImageThreadKey: String = "",
    val composerImageActionId: String = "",
    // task #113: whole-group edit-session identity. Consumer (e.g. Settings feedback
    // draft) uses this to reconcile an N/N group; blank = legacy single-shot semantics.
    val composerImageGroupId: String = "",
    val composerImageFileRef: String = "",
    val composerImageFileName: String = "",
    val composerImageMimeType: String = "",
    val composerImageSizeBytes: Long? = null,
    val composerImagePreviewUri: String = "",
    /** Cache-owned refs are kept process/persistence-local and never serialized into route data. */
    val composerImageOwnedTemporaryFileRefs: List<String> = emptyList(),
    val composerImageReplaceAttachmentId: String = "",
    /**
     * Whether the editor output must make a second, host-owned JPEG compression pass before it
     * is delivered to the composer.  This is intentionally process-local and defaults to false
     * so non-composer previews cannot accidentally invoke the compressor.
     */
    val composerImageCompressAfterEdit: Boolean = false,
    /**
     * Thread composer confirm screens may expose the Original image policy. Feedback and
     * ordinary file previews leave this false so they cannot inherit a composer-only control.
     */
    val composerImageOriginalOptionEnabled: Boolean = false,
    /** True once the currently selected composer image has a final deliverable. */
    val composerImageEditCompleted: Boolean = false,
    /** True only when that final deliverable is the JPEG returned by the compressor. */
    val composerImageCompressed: Boolean = false,
    val composerImageCanComplete: Boolean = false,
    val composerImageCanEdit: Boolean = false
)

@Serializable
internal data class SlockFilePreviewGalleryImage(
    val attachmentId: String,
    val name: String = "",
    val mimeType: String = "",
    val sizeLabel: String = "",
    val thumbnailUrl: String? = null,
    val composerImageFileRef: String = "",
    val composerImagePreviewUri: String = "",
    val composerImageSizeBytes: Long? = null,
    val composerImageOwnedTemporary: Boolean = false,
    /**
     * Exact existing composer slot targeted by this editor item. Blank means a fresh picker
     * item, whose consumer should replace its synthetic local admission id.
     */
    val composerImageReplaceAttachmentId: String = "",
    /** Explicit process-local finalization state; do not infer this from MIME type. */
    val composerImageEditCompleted: Boolean = false,
    /** True only for a successful composer-image JPEG compression result. */
    val composerImageCompressed: Boolean = false
)

internal fun slockFilePreviewComposerImageGalleryActionId(
    baseActionId: String,
    attachmentId: String,
    index: Int
): String = "$baseActionId-gallery-$index-$attachmentId"

private fun SlockFilePreviewPayload.ephemeralComposerActionIds(): Set<String> {
    val baseActionId = composerImageActionId.trim().takeIf { it.isNotBlank() } ?: return emptySet()
    return if (imageGallery.isEmpty()) {
        setOf(baseActionId)
    } else {
        imageGallery.mapIndexedTo(linkedSetOf()) { index, image ->
            slockFilePreviewComposerImageGalleryActionId(
                baseActionId = baseActionId,
                attachmentId = image.attachmentId,
                index = index
            )
        }
    }
}

private fun SlockFilePreviewPayload.ownedEphemeralComposerFileRefs(): Set<String> =
    buildSet {
        composerImageOwnedTemporaryFileRefs
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(::add)
        imageGallery
            .filter(SlockFilePreviewGalleryImage::composerImageOwnedTemporary)
            .map(SlockFilePreviewGalleryImage::composerImageFileRef)
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(::add)
    }

private fun SlockFilePreviewPayload.toEphemeralSessionRegistrationOrNull(
    sessionId: String
): SlockFilePreviewEphemeralSessionRegistration? {
    val actionIds = ephemeralComposerActionIds()
    val threadKey = composerImageThreadKey.trim()
    if (actionIds.isEmpty() || threadKey.isBlank()) return null
    return SlockFilePreviewEphemeralSessionRegistration(
        sessionId = sessionId.trim(),
        threadKey = threadKey,
        actionIds = actionIds,
        ownedTemporaryFileRefs = ownedEphemeralComposerFileRefs()
    )
}

/**
 * Build composer-image delivery events as one atomic batch.
 *
 * A gallery is an N/N handoff: dropping a malformed member would make the consumer observe a
 * partial edit group and could leave the omitted temporary file without a delivery owner.  The
 * mapper therefore returns an empty list as soon as any gallery member cannot produce an event.
 * The single-image branch keeps the legacy no-group shape while resolving grouped single images
 * to the explicit N=1 contract.
 */
internal fun <T> slockFilePreviewComposerImageEventsForPayload(
    eventPayload: SlockFilePreviewPayload,
    singleEvent: (groupId: String, groupIndex: Int, groupTotal: Int) -> T?,
    galleryEvent: (
        index: Int,
        image: SlockFilePreviewGalleryImage,
        groupId: String,
        groupTotal: Int
    ) -> T?
): List<T> {
    val gallery = eventPayload.imageGallery
    val groupId = eventPayload.composerImageGroupId
    if (gallery.isEmpty()) {
        return listOfNotNull(
            singleEvent(
                groupId,
                eventPayload.imageGalleryIndex.coerceAtLeast(0),
                SlockImageEditGroupReconcile.resolveGroupTotal(groupId, gallery.size)
            )
        )
    }

    val groupTotal = gallery.size
    return gallery.mapIndexed { index, image ->
        galleryEvent(
            index,
            image,
            groupId,
            groupTotal
        ) ?: return emptyList()
    }
}

@Page(SLOCK_FILE_PREVIEW_PAGE, supportInLocal = true)
internal class ComposeSlockFilePreviewPage : AuthenticatedProductPager(AuthenticatedProductRoute.FilePreview) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SlockFilePreviewStandalonePageContent() }
    }
}

@Composable
private fun SlockFilePreviewStandalonePageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val params = pager.pageData.params
    val publicDownloadRepository =
        remember(bridge) { BridgeSlockFilePreviewPublicDownloadRepository(bridge) }
    val supportsPublicDownloadReadback = slockPlatformSupportsPublicAttachmentDownloadReadback()
    val supportsPrivateAttachmentExport = slockPlatformSupportsPrivateAttachmentExport()
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val attachmentOwnerVersion by ProcessAttachmentAuthScopes.version.collectAsState()
    val attachmentOwner = remember(attachmentOwnerVersion, storeScope.shellInstanceId) {
        currentAttachmentOwnerLease(storeScope)
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val coroutineScope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val ephemeralActionRegistry = remember { slockFilePreviewEphemeralActionRegistry() }
    val ephemeralCleanupLedger = remember { slockFilePreviewEphemeralCleanupLedger() }
    val launchPayloadTrace =
        remember(params.toString()) {
            val source = params.optString("payload")
            slockFilePreviewResolveLaunchPayload(
                source = source,
                loadPreviewPayload = SlockFilePreviewLaunchPayloadStore::get,
                retireEphemeralSession = { sessionId ->
                    SlockFilePreviewLaunchPayloadStore.retire(sessionId)
                    val active = ephemeralActionRegistry.retireActive(sessionId)
                    val pending = ephemeralActionRegistry.retirePending(sessionId)
                    active.cleanupFileRefs + pending.cleanupFileRefs
                }
            )
        }
    val routeOwner = remember(params.toString()) { params.authenticatedRouteOwnerOrNull() }
    val liveRouteOwner by
        remember(sharedStores, storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val remoteAuthorityCurrent =
        !launchPayloadTrace.requiresAuthenticatedRouteAuthority() ||
            (routeOwner != null && liveRouteOwner == routeOwner)
    val remoteOperationScope =
        remember(routeOwner, sharedStores, storeScope) {
            routeOwner?.let { owner ->
                SlockFilePreviewRemoteOperationScope(
                    owner = owner,
                    gateway = sharedStores.authenticatedRouteAuthorityGateway(storeScope)
                )
            }
        }
    val ownerScopedLaunchPayload =
        remember(launchPayloadTrace.payload, routeOwner, liveRouteOwner) {
            if (
                launchPayloadTrace.routeKind == SlockFilePreviewRouteThreadAttachment &&
                routeOwner != null &&
                liveRouteOwner == routeOwner
            ) {
                slockFilePreviewAttachmentMetadataStore().hydrate(routeOwner, launchPayloadTrace.payload)
            } else {
                launchPayloadTrace.payload
            }
        }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    LaunchedEffect(launchPayloadTrace.durationMs, launchPayloadTrace.responseBytes, launchPayloadTrace.result) {
        bridge.log(
            "file_preview_launch_payload_parse source=pageData result=${launchPayloadTrace.result} " +
                "responseBytes=${launchPayloadTrace.responseBytes} durationMs=${launchPayloadTrace.durationMs} dispatcher=caller"
        )
    }
    LaunchedEffect(launchPayloadTrace.sessionId, launchPayloadTrace.result) {
        val cleanupRefs =
            launchPayloadTrace.temporaryFileRefsToCleanup +
                ephemeralCleanupLedger.takeOrphans(ephemeralActionRegistry.activeSessionIds())
        if (cleanupRefs.isNotEmpty()) {
            bridge.retireFilePreviewTemporaryFiles(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = cleanupRefs.toList()
            )
        }
    }
    var payload by remember(ownerScopedLaunchPayload, remoteAuthorityCurrent) {
        mutableStateOf(
            if (
                launchPayloadTrace.requiresAuthenticatedRouteAuthority() &&
                !remoteAuthorityCurrent
            ) {
                ownerScopedLaunchPayload.withFilePreviewAuthorityRejected()
            } else {
                ownerScopedLaunchPayload
            }
        )
    }
    var attachmentCommentsState by remember(payload.attachmentId) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var imageGalleryResolutionState by remember(launchPayloadTrace.payload.imageGallery) {
        mutableStateOf(SlockFilePreviewImageGalleryResolutionState())
    }
    val imageGalleryResolutionJobs = remember(launchPayloadTrace.payload.imageGallery) {
        mutableMapOf<String, Job>()
    }
    var composerImageEditMode by remember(payload.composerImageActionId, payload.attachmentId, payload.imageUrl) {
        mutableStateOf(false)
    }
    val composerImageEditorState =
        remember(payload.composerImageActionId, payload.attachmentId, payload.imageUrl, composerImageEditMode) {
            SlockComposerImageEditorState()
        }
    var composerImageEditSaving by remember(payload.composerImageActionId) { mutableStateOf(false) }
    var composerImageFinalizing by remember(payload.composerImageActionId) { mutableStateOf(false) }
    val composerImageOperationGate =
        remember(launchPayloadTrace.sessionId, payload.composerImageActionId) {
            SlockFilePreviewComposerImageOperationGate()
        }
    val activeComposerImageOperationJob = remember { mutableStateOf<Job?>(null) }
    val composerImageEditCaptureOwner =
        SlockComposerImageEditCaptureOwner(
            actionId = payload.composerImageActionId,
            attachmentId = payload.attachmentId,
            imageUrl = payload.imageUrl,
            editMode = composerImageEditMode,
        )
    val composerImageEditCaptureSlot = remember(composerImageEditCaptureOwner) {
        mutableStateOf(SlockComposerImageEditCaptureSlot(composerImageEditCaptureOwner))
    }
    val onComposerImageEditCaptureRef =
        remember(composerImageEditCaptureOwner) {
            { ref: ViewRef<DeclarativeBaseView<*, *>> ->
                composerImageEditCaptureSlot.value =
                    composerImageEditCaptureSlot.value.bind(
                        owner = composerImageEditCaptureOwner,
                        ref = ref,
                    )
            }
        }
    var downloadStatus by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("idle") }
    var downloadMessage by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("") }
    var downloadProgress by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<BridgeAttachmentDownloadProgress?>(null) }
    var externalOperation by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var activeDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var activeAttachmentOperation by remember {
        mutableStateOf<AttachmentOperationToken?>(null)
    }
    var activeExportOperation by remember {
        mutableStateOf<AttachmentOperationToken?>(null)
    }
    var exportInProgress by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf(false) }
    val activeExternalOperationJob = remember { mutableStateOf<Job?>(null) }
    var activeExternalOperationIdentity by remember {
        mutableStateOf<SlockFilePreviewExternalOperationIdentity?>(null)
    }
    var nextDownloadGeneration by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf(0L) }
    var nextExportGeneration by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf(0L) }
    var activeDownloadStartedAt by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<TimeMark?>(null) }
    var firstBridgeDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var firstRenderedDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    fun applyExportIsolationEvent(event: SlockFilePreviewExportIsolationEvent) {
        val next =
            reduceSlockFilePreviewExportIsolationState(
                state =
                    SlockFilePreviewExportIsolationState(
                        downloadStatus = downloadStatus,
                        downloadProgress = downloadProgress,
                        activeDownloadId = activeDownloadId,
                        exportInProgress = exportInProgress
                    ),
                event = event
            )
        downloadStatus = next.downloadStatus
        downloadProgress = next.downloadProgress
        activeDownloadId = next.activeDownloadId
        exportInProgress = next.exportInProgress
    }
    var publicDownloadState by remember(payload.source, payload.attachmentId, payload.path) {
        mutableStateOf<SlockFilePreviewPublicDownloadState>(
            if (supportsPublicDownloadReadback) {
                SlockFilePreviewPublicDownloadState.Checking
            } else {
                SlockFilePreviewPublicDownloadState.NotDownloaded
            }
        )
    }
    var activePublicDownloadQuery by remember {
        mutableStateOf<SlockFilePreviewPublicDownloadQuery?>(null)
    }
    var observedPublicDownloadQuery by remember {
        mutableStateOf<SlockFilePreviewPublicDownloadQuery?>(null)
    }
    var attachmentCacheAttemptToken by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    val downloadProgressMailbox = remember(payload.source, payload.attachmentId, payload.path) {
        SlockFilePreviewProgressMailbox()
    }
    val unableToSendComment = stringResource(MR.strings.file_preview_error_send_comment)
    val unableToResolveComment = stringResource(MR.strings.file_preview_error_resolve_comment)
    val attachmentFallback = stringResource(MR.strings.file_preview_fallback_attachment)
    val actionTerms = slockFilePreviewLocalizedActionTerms()
    val downloadStopped = stringResource(MR.strings.file_preview_download_stopped)
    val downloadFailed = stringResource(MR.strings.file_preview_download_failed)
    val downloadSaved = stringResource(MR.strings.file_preview_download_saved)
    val privateDownloadSaved = stringResource(MR.strings.file_preview_private_download_saved)
    val fileExportSaved = stringResource(MR.strings.file_preview_export_saved)
    val fileExportFailed = stringResource(MR.strings.file_preview_export_failed)
    val unableToDownloadAttachment = stringResource(MR.strings.file_preview_error_download_attachment)
    val noAppCanOpenFile = stringResource(MR.strings.file_preview_error_no_app)
    val storageUnavailable = stringResource(MR.strings.file_preview_error_storage_unavailable)
    val unableToOpenAttachment = stringResource(MR.strings.file_preview_error_open_attachment)
    val downloadedFileDeleted = stringResource(MR.strings.file_preview_download_deleted)
    val unableToDeleteDownload = stringResource(MR.strings.file_preview_error_delete_download)
    val attachmentOpenResultTerms =
        SlockAttachmentOpenResultTerms(
            downloadStopped = downloadStopped,
            unableToDownloadAttachment = unableToDownloadAttachment,
            noAppCanOpenFile = noAppCanOpenFile,
            storageUnavailable = storageUnavailable,
            downloadFailed = downloadFailed,
            unableToOpenAttachment = unableToOpenAttachment
        )
    val imageEditorLoading = stringResource(MR.strings.file_preview_error_editor_loading)
    val composerImageCompressionFailed =
        stringResource(MR.strings.thread_error_reason_attachment_image_compression_failed)
    val composerImageHandoffFailed = stringResource(MR.strings.thread_error_attachment_upload)
    val imageEditorExportFailed = stringResource(MR.strings.file_preview_error_editor_export)
    val imageSavedToPhotos = stringResource(MR.strings.thread_image_preview_saved_to_photos)
    val imagePhotosPermissionDenied = stringResource(MR.strings.thread_image_preview_photos_permission_denied)
    val imagePhotosPermissionRestricted = stringResource(MR.strings.thread_image_preview_photos_permission_restricted)
    val imagePhotosUnsupported = stringResource(MR.strings.thread_image_preview_photos_unsupported)
    val imagePhotosSaveFailed = stringResource(MR.strings.thread_image_preview_photos_save_failed)
    val truncatedPreviewHint = stringResource(MR.strings.file_preview_truncated_open_with_hint)
    val attachmentCacheId = payload.attachmentCacheId()
    val attachmentCacheFileName = payload.attachmentCacheFileName()
    val visibleExternalOperationIdentity =
        attachmentOwner?.let { owner ->
            SlockFilePreviewExternalOperationIdentity(
                source = payload.source,
                attachmentId = payload.attachmentId,
                path = payload.path,
                owner = owner
            )
        }
    LaunchedEffect(visibleExternalOperationIdentity) {
        if (
            slockFilePreviewShouldRetireExternalOperation(
                active = activeExternalOperationIdentity,
                visible = visibleExternalOperationIdentity
            )
        ) {
            val retiringJob = activeExternalOperationJob.value
            activeAttachmentOperation = null
            activeExportOperation = null
            applyExportIsolationEvent(SlockFilePreviewExportIsolationEvent.Cancelled)
            activeExternalOperationIdentity = null
            activeExternalOperationJob.value = null
            activeDownloadId = null
            downloadProgress = null
            downloadStatus = "idle"
            downloadMessage = ""
            externalOperation = null
            retiringJob?.cancel()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val retiringJob = activeExternalOperationJob.value
            activeAttachmentOperation = null
            activeExportOperation = null
            applyExportIsolationEvent(SlockFilePreviewExportIsolationEvent.Cancelled)
            activeExternalOperationIdentity = null
            activeExternalOperationJob.value = null
            retiringJob?.cancel()
        }
    }
    DisposableEffect(downloadProgressMailbox) {
        onDispose { downloadProgressMailbox.close() }
    }
    LaunchedEffect(downloadProgressMailbox) {
        while (true) {
            val event = downloadProgressMailbox.nextOrNull() ?: break
            val operation = event.operation
            val progress = event.progress
            if (!attachmentOperationCommitAllowed(operation, activeAttachmentOperation?.operationId.orEmpty())) continue
            val expectedDownloadId = activeDownloadId ?: continue
            if (progress.downloadedBytes > 0L && firstBridgeDownloadId != expectedDownloadId) {
                firstBridgeDownloadId = expectedDownloadId
                bridge.log(
                    "file_preview_download phase=first_bridge downloadIdHash=${expectedDownloadId.hashCode().toString(16)} " +
                        "sequence=${progress.sequence} downloadedBytes=${progress.downloadedBytes} " +
                        "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}"
                )
            }
            downloadProgress =
                reduceAttachmentDownloadProgress(
                    current = downloadProgress,
                    incoming = progress,
                    expectedDownloadId = expectedDownloadId
                )
            attachmentCacheAttemptToken = progress.attemptToken.takeIf(String::isNotBlank)
        }
    }
    LaunchedEffect(downloadProgress?.downloadId, downloadProgress?.downloadedBytes) {
        val progress = downloadProgress ?: return@LaunchedEffect
        if (progress.downloadedBytes <= 0L || firstRenderedDownloadId == progress.downloadId) return@LaunchedEffect
        withFrameNanos { _ -> }
        firstRenderedDownloadId = progress.downloadId
        bridge.log(
            "file_preview_download phase=first_render downloadIdHash=${progress.downloadId.hashCode().toString(16)} " +
                "sequence=${progress.sequence} downloadedBytes=${progress.downloadedBytes} " +
                "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}"
        )
    }
    fun setAttachmentCommentsState(next: ThreadAttachmentCommentsDialogState) {
        attachmentCommentsState =
            attachmentCommentsState
                ?.takeIf { current -> current.attachment.id == next.attachment.id }
                ?.let { next }
    }
    fun loadAttachmentComments(state: ThreadAttachmentCommentsDialogState) {
        coroutineScope.launch {
            val operationScope =
                remoteOperationScope?.takeIf { remoteAuthorityCurrent }
                    ?: run {
                        payload = payload.withFilePreviewAuthorityRejected()
                        attachmentCommentsState = null
                        return@launch
                    }
            val authorityResult =
                operationScope.runThreadApi(
                    diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") }
                ) { threadApi ->
                    threadApi.attachmentComments(state.attachment.id)
                }
            val next =
                when (val result = (authorityResult as? DataResult.Content)?.data) {
                    is ThreadApiResult.Success ->
                        state.copy(
                            status = "loaded",
                            page = result.data,
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).clearAttachmentCommentsError()
                    is ThreadApiResult.Failure -> {
                        val failure = result.toThreadAttachmentCommentsFailureProjection()
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).withAttachmentCommentsError(
                            errorCode = failure.errorCode,
                            serverMessage = failure.serverMessage
                        )
                    }
                    null -> {
                        payload = payload.withFilePreviewAuthorityRejected()
                        attachmentCommentsState = null
                        return@launch
                    }
                }
            setAttachmentCommentsState(next)
        }
    }
    fun openAttachmentComments() {
        if (!payload.supportsAttachmentComments()) return
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment =
                    KuiklyThreadAttachmentItem(
                        id = payload.attachmentId,
                        name = payload.externalFileName.ifBlank { payload.name.ifBlank { attachmentFallback } },
                        mimeType = payload.externalMimeType
                    ),
                status = "loading",
                pendingAnchorLabel = payload.externalFileName.ifBlank { payload.name.ifBlank { attachmentFallback } }
            )
        attachmentCommentsState = state
        loadAttachmentComments(state)
    }
    fun reloadAttachmentComments() {
        val current = attachmentCommentsState ?: return
        val loading = current.copy(status = "loading").clearAttachmentCommentsError()
        attachmentCommentsState = loading
        loadAttachmentComments(loading)
    }
    fun sendAttachmentComment() {
        val current = attachmentCommentsState ?: return
        val body = current.draft.text.trim()
        if (body.isBlank() || current.sending) return
        attachmentCommentsState = current.copy(sending = true).clearAttachmentCommentsError()
        coroutineScope.launch {
            val operationScope =
                remoteOperationScope?.takeIf { remoteAuthorityCurrent }
                    ?: run {
                        payload = payload.withFilePreviewAuthorityRejected()
                        attachmentCommentsState = null
                        return@launch
                    }
            val authorityResult =
                operationScope.runThreadApi(
                    diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") }
                ) { threadApi ->
                    threadApi.sendAttachmentComment(current.attachment.id, body)
                }
            when (val result = (authorityResult as? DataResult.Content)?.data) {
                is ThreadApiResult.Success -> {
                    val loading =
                        current.withAttachmentCommentDraftProjection("").copy(
                            status = "loading",
                            sending = false,
                            pendingQuoteLabel = null
                        ).clearAttachmentCommentsError()
                    attachmentCommentsState = loading
                    loadAttachmentComments(loading)
                }
                is ThreadApiResult.Failure -> {
                    val failure = result.toThreadAttachmentCommentsFailureProjection()
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false)
                            ?.withAttachmentCommentsError(
                                errorCode = failure.errorCode,
                                serverMessage = failure.serverMessage
                            )
                    bridge.toast(unableToSendComment)
                }
                null -> {
                    payload = payload.withFilePreviewAuthorityRejected()
                    attachmentCommentsState = null
                }
            }
        }
    }
    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) {
        val current = attachmentCommentsState ?: return
        if (comment.id.isBlank() || comment.isThreadAttachmentCommentResolved()) return
        if (comment.id in current.resolvingCommentIds) return
        attachmentCommentsState =
            current.copy(resolvingCommentIds = current.resolvingCommentIds + comment.id)
                .clearAttachmentCommentsError()
        coroutineScope.launch {
            val operationScope =
                remoteOperationScope?.takeIf { remoteAuthorityCurrent }
                    ?: run {
                        payload = payload.withFilePreviewAuthorityRejected()
                        attachmentCommentsState = null
                        return@launch
                    }
            val authorityResult =
                operationScope.runThreadApi(
                    diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") }
                ) { threadApi ->
                    threadApi.addReaction(comment.id, "✅")
                }
            when (val result = (authorityResult as? DataResult.Content)?.data) {
                is ThreadApiResult.Success -> {
                    val loading =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(status = "loading", resolvingCommentIds = emptySet())
                            ?.clearAttachmentCommentsError()
                            ?: return@launch
                    attachmentCommentsState = loading
                    loadAttachmentComments(loading)
                }
                is ThreadApiResult.Failure -> {
                    val failure = result.toThreadAttachmentCommentsFailureProjection()
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id
                                ).withAttachmentCommentsError(
                                    errorCode = failure.errorCode,
                                    serverMessage = failure.serverMessage
                                )
                            }
                    bridge.toast(unableToResolveComment)
                }
                null -> {
                    payload = payload.withFilePreviewAuthorityRejected()
                    attachmentCommentsState = null
                }
            }
        }
    }
    LaunchedEffect(
        payload.source,
        payload.attachmentId,
        payload.agentId,
        payload.path,
        payload.kind,
        remoteAuthorityCurrent,
        remoteOperationScope
    ) {
        if (
            payload.source == SlockFilePreviewSourceThreadAttachment ||
            payload.source == SlockFilePreviewSourceAgentWorkspace
        ) {
            if (!remoteAuthorityCurrent) {
                payload = payload.withFilePreviewAuthorityRejected()
                return@LaunchedEffect
            }
            if (remoteOperationScope == null) {
                payload = payload.withFilePreviewAuthorityRejected()
                return@LaunchedEffect
            }
        }
        if (payload.isPendingThreadImageGalleryResolution()) {
            imageGalleryResolutionState.resolvedPayloadByAttachmentId[payload.attachmentId]
                ?.let(payload::withResolvedImageGalleryPayload)
                ?.let { cachedPayload ->
                    payload = cachedPayload
                    return@LaunchedEffect
                }
            val start = imageGalleryResolutionState.beginResolution(payload)
            imageGalleryResolutionState = start.state
            val requestPayload = start.requestPayload ?: return@LaunchedEffect
            start.cancelAttachmentId?.let { attachmentId ->
                imageGalleryResolutionJobs.remove(attachmentId)?.cancel()
                bridge.log(
                    "file_preview_image_resolve status=cancelled attachment=${attachmentId.take(8)} reason=concurrency_limit"
                )
            }
            bridge.log(
                "file_preview_image_resolve status=start attachment=${requestPayload.attachmentId.take(8)} " +
                    "kind=${requestPayload.kind} thumbnail=${requestPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
            val resolutionOperationScope = remoteOperationScope ?: return@LaunchedEffect
            val resolutionJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val loadResult =
                        resolutionOperationScope.loadPreview(
                            payload = requestPayload,
                            authStore = authStore,
                            truncatedPreviewHint = truncatedPreviewHint,
                            errorStrings = errorStrings,
                            diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") },
                            memberWarningLogger = { message -> bridge.log("file_preview_member_warning $message") },
                            memberLogger = { message -> bridge.log("file_preview_member $message") }
                        )
                    val nextPayload =
                        (loadResult as? DataResult.Content)?.data
                            ?: run {
                                payload = requestPayload.withFilePreviewAuthorityRejected()
                                return@launch
                            }
                    if (
                        !imageGalleryResolutionJobs.ownsImageGalleryResolution(
                            requestPayload.attachmentId,
                            currentCoroutineContext()[Job]
                        )
                    ) {
                        return@launch
                    }
                    imageGalleryResolutionState =
                        imageGalleryResolutionState.completeResolution(
                            requestPayload = requestPayload,
                            resolvedPayload = nextPayload
                        )
                    val visiblePayload = payload.withResolvedImageGalleryPayload(nextPayload)
                    if (visiblePayload != null) {
                        payload = visiblePayload
                    }
                    bridge.log(
                        "file_preview_image_resolve status=committed attachment=${nextPayload.attachmentId.take(8)} " +
                            "visible=${visiblePayload != null} cached=${nextPayload.hasResolvedRemoteImageComparedTo(requestPayload)} " +
                            "kind=${nextPayload.kind} error=${nextPayload.error ?: "none"} " +
                            "primary=${nextPayload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                            "fallback=${nextPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    if (
                        !imageGalleryResolutionJobs.ownsImageGalleryResolution(
                            requestPayload.attachmentId,
                            currentCoroutineContext()[Job]
                        )
                    ) {
                        return@launch
                    }
                    val failedPayload = requestPayload.imageGalleryResolutionFailurePayload()
                    payload.withResolvedImageGalleryPayload(failedPayload)?.let { visiblePayload ->
                        payload = visiblePayload
                    }
                    bridge.log(
                        "file_preview_image_resolve status=failed attachment=${requestPayload.attachmentId.take(8)} " +
                            "type=${throwable::class.simpleName ?: "unknown"}"
                    )
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (imageGalleryResolutionJobs.ownsImageGalleryResolution(requestPayload.attachmentId, currentJob)) {
                        imageGalleryResolutionJobs.remove(requestPayload.attachmentId)
                        imageGalleryResolutionState =
                            imageGalleryResolutionState.finishResolution(requestPayload.attachmentId)
                    }
                }
            }
            imageGalleryResolutionJobs[requestPayload.attachmentId] = resolutionJob
            resolutionJob.start()
            return@LaunchedEffect
        }
        if (
            payload.source == SlockFilePreviewSourceThreadAttachment &&
            payload.kind == "loading" &&
            payload.requestedKind == "image"
        ) {
            bridge.log(
                "file_preview_image_resolve status=start attachment=${payload.attachmentId.take(8)} " +
                    "kind=${payload.kind} thumbnail=${payload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
        }
        val nextPayload =
            when (payload.source) {
                SlockFilePreviewSourceThreadAttachment,
                SlockFilePreviewSourceAgentWorkspace -> {
                    val scope = remoteOperationScope ?: return@LaunchedEffect
                    when (
                        val result =
                            scope.loadPreview(
                                payload = payload,
                                authStore = authStore,
                                truncatedPreviewHint = truncatedPreviewHint,
                                errorStrings = errorStrings,
                                diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") },
                                memberWarningLogger = { message -> bridge.log("file_preview_member_warning $message") },
                                memberLogger = { message -> bridge.log("file_preview_member $message") }
                            )
                    ) {
                        is DataResult.Content -> result.data
                        else -> payload.withFilePreviewAuthorityRejected()
                    }
                }
                else -> null
            }
        if (nextPayload != null) {
            payload = nextPayload
            if (nextPayload.source == SlockFilePreviewSourceThreadAttachment && nextPayload.requestedKind == "image") {
                bridge.log(
                    "file_preview_image_resolve status=committed attachment=${nextPayload.attachmentId.take(8)} " +
                        "kind=${nextPayload.kind} error=${nextPayload.error ?: "none"} " +
                        "primary=${nextPayload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                        "fallback=${nextPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
                )
            }
        }
    }
    LaunchedEffect(payload.commentsInitiallyOpen, payload.attachmentId, payload.source) {
        if (payload.commentsInitiallyOpen && attachmentCommentsState == null) {
            openAttachmentComments()
        }
    }
    LaunchedEffect(payload.kind, payload.requestedKind, payload.attachmentId, payload.imageUrl) {
        if (payload.shouldRenderImagePreview()) {
            bridge.log(
                "file_preview_image_render attachment=${payload.attachmentId.take(8)} kind=${payload.kind} " +
                    "primary=${payload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                    "fallback=${payload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
        }
    }
    val canOpenWith = canOpenPayloadWithExternalViewer(payload)
    val canOpenComments = payload.supportsAttachmentComments()
    val photosActionState =
        slockFilePreviewPhotosActionState(
            supported = slockPlatformSupportsSaveImageToPhotos(),
            eligible = payload.supportsSaveToPhotos(),
            operation = externalOperation,
            status = downloadStatus
        )
    val publicDownloadQuery =
        attachmentOwner
            ?.takeIf { canOpenWith && supportsPublicDownloadReadback }
            ?.let { owner ->
                SlockFilePreviewPublicDownloadQuery(
                    attachmentId = attachmentCacheId,
                    fileName = attachmentCacheFileName,
                    owner = owner,
                    mimeType = payload.externalMimeType
                )
            }
    suspend fun refreshPublicDownloadState(
        query: SlockFilePreviewPublicDownloadQuery
    ): SlockFilePreviewPublicDownloadObservation? {
        val observation = publicDownloadRepository.inspect(query)
        if (!ProcessAttachmentAuthScopes.allows(query.owner)) return null
        val committed =
            slockFilePreviewCommitPublicDownloadObservation(
                request = query,
                current = activePublicDownloadQuery,
                observation = observation
            ) ?: return null
        publicDownloadState = committed.state
        observedPublicDownloadQuery = query
        attachmentCacheAttemptToken = committed.attemptToken
        return committed
    }
    LaunchedEffect(publicDownloadQuery) {
        activePublicDownloadQuery = publicDownloadQuery
        attachmentCacheAttemptToken = null
        if (publicDownloadQuery == null) {
            publicDownloadState = SlockFilePreviewPublicDownloadState.NotDownloaded
            observedPublicDownloadQuery = null
            return@LaunchedEffect
        }
        publicDownloadState = SlockFilePreviewPublicDownloadState.Checking
        observedPublicDownloadQuery = null
        refreshPublicDownloadState(publicDownloadQuery)
    }
    val visiblePublicDownloadState =
        slockFilePreviewVisiblePublicDownloadState(
            current = publicDownloadQuery,
            observedFor = observedPublicDownloadQuery,
            observedState = publicDownloadState
        )
    suspend fun resolveRemoteExternalUrl(
        currentPayload: SlockFilePreviewPayload
    ): String? {
        if (
            currentPayload.source != SlockFilePreviewSourceThreadAttachment ||
            currentPayload.attachmentId.isBlank()
        ) {
            return null
        }
        val operationScope = remoteOperationScope?.takeIf { remoteAuthorityCurrent } ?: return null
        return (
            operationScope.resolveExternalUrl(
                payload = currentPayload,
                diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") }
            ) as? DataResult.Content
        )?.data
    }

    /**
     * task #319: a private staging copy may already exist for an attachment the user
     * already opened/downloaded. Probe the cache before resolving a fresh signed URL —
     * on a hit the caller can open the local file directly and skip the
     * `attachments/{id}/url` round-trip (and the no-op download flow it used to spin up).
     * Returns a `file://` URL for the cached file, or null when there is no usable
     * private copy (or the payload is not a thread attachment).
     */
    suspend fun slockFilePreviewPrivateCachedOpenUrl(
        payload: SlockFilePreviewPayload,
        owner: AttachmentOwnerLease
    ): String? {
        if (payload.source != SlockFilePreviewSourceThreadAttachment) return null
        val status =
            bridge.attachmentCacheStatus(
                attachmentId = payload.attachmentCacheId(),
                fileName = payload.attachmentCacheFileName(),
                owner = owner,
                mimeType = payload.externalMimeType
            ) ?: return null
        if (!status.optBoolean("privateCached", false)) return null
        val path = status.optString("privatePath").takeIf { it.isNotBlank() } ?: return null
        return "file://$path"
    }

    suspend fun openPayloadExternally(
        currentPayload: SlockFilePreviewPayload,
        policy: SlockFilePreviewExternalOpenPolicy,
        operation: AttachmentOperationToken
    ): String? {
        val downloadId =
            if (policy.trackProgress) {
                activeDownloadId ?: currentPayload.filePreviewDownloadId()
            } else {
                null
            }
        val tracedResult =
            runSlockFilePreviewExternalOpenTraceFlow(
                policy = policy,
                resolveUrl = {
                    if (policy.requirePublicDownload) {
                        // Public-only open is identity/journal driven and must remain usable offline.
                        // Native code will fail closed before private materialization if readback misses.
                        currentPayload.externalUrl.orEmpty()
                    } else if (currentPayload.source == SlockFilePreviewSourceThreadAttachment) {
                        // task #319: a private staging copy may already exist for an
                        // attachment the user already opened/downloaded. Probe the cache
                        // before resolving a fresh signed URL — on a hit we can open the
                        // local file directly and skip the attachments/{id}/url round-trip
                        // (and the no-op download flow it used to spin up).
                        slockFilePreviewPrivateCachedOpenUrl(
                            payload = currentPayload,
                            owner = operation.owner
                        ) ?:
                        // A URL hydrated for rendering is not durable action authority. Resolve a
                        // fresh attachment URL inside the authenticated pre/post-operation fence.
                        resolveRemoteExternalUrl(currentPayload)
                    } else {
                        currentPayload.externalUrl?.takeIf { it.isNotBlank() }
                    }
                },
                onUrlResolved = { externalUrl ->
                    if (policy.trackProgress) {
                        activeDownloadId = downloadId
                        bridge.log(
                            "file_preview_download phase=url_resolved " +
                                "downloadIdHash=${downloadId.orEmpty().hashCode().toString(16)} " +
                                "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} " +
                                binaryDownloadUrlDiagnostics(externalUrl)
                        )
                    }
                },
                openHost = { externalUrl ->
                    bridge.openAttachment(
                        url = externalUrl,
                        attachmentId = currentPayload.attachmentCacheId(),
                        fileName = currentPayload.attachmentCacheFileName(),
                        owner = operation.owner,
                        mimeType = currentPayload.externalMimeType,
                        downloadId = downloadId,
                        requirePublicDownload = policy.requirePublicDownload,
                        onProgress =
                            if (policy.trackProgress) {
                                { progress -> downloadProgressMailbox.offer(operation, progress) }
                            } else {
                                null
                            }
                    )
                },
                statusOf = { result -> result?.optString("status") },
                trace = bridge::log
            )
        val result =
            when (tracedResult) {
                SlockFilePreviewExternalOpenTraceResult.UrlResolutionFailed -> return unableToDownloadAttachment
                is SlockFilePreviewExternalOpenTraceResult.HostTerminal -> tracedResult.result
            }
        if (!attachmentOperationCommitAllowed(operation, activeAttachmentOperation?.operationId.orEmpty())) return null
        publicDownloadState = slockFilePreviewPublicDownloadStateAfterOpen(visiblePublicDownloadState, result)
        observedPublicDownloadQuery = publicDownloadQuery
        return when (result?.optString("status").orEmpty()) {
            "opened", "" -> {
                if (result?.optBoolean("cached", false) == true) {
                    publicDownloadQuery?.let { refreshPublicDownloadState(it) }
                }
                activeDownloadId = null
                null
            }
            "cancelled" -> {
                activeDownloadId = null
                slockAttachmentOpenResultMessage("cancelled", attachmentOpenResultTerms)
            }
            "save_cancelled" -> {
                activeDownloadId = null
                null
            }
            "missing" -> {
                attachmentCacheAttemptToken = null
                activeDownloadId = null
                slockAttachmentOpenResultMessage("missing", attachmentOpenResultTerms)
            }
            "no_app" -> slockAttachmentOpenResultMessage("no_app", attachmentOpenResultTerms)
            "storage_failed" -> slockAttachmentOpenResultMessage("storage_failed", attachmentOpenResultTerms)
            "download_failed" -> slockAttachmentOpenResultMessage("download_failed", attachmentOpenResultTerms)
            else -> slockAttachmentOpenResultMessage("failed", attachmentOpenResultTerms)
        }
    }
    suspend fun savePayloadExternally(
        currentPayload: SlockFilePreviewPayload,
        trackProgress: Boolean,
        operation: AttachmentOperationToken,
        toPhotos: Boolean,
        exportPrivateDownload: Boolean
    ): String? {
        val externalUrl =
            if (exportPrivateDownload) {
                null
            } else if (currentPayload.source == SlockFilePreviewSourceThreadAttachment) {
                resolveRemoteExternalUrl(currentPayload)
                    ?: return unableToDownloadAttachment
            } else {
                currentPayload.externalUrl?.takeIf { it.isNotBlank() }
                    ?: return unableToDownloadAttachment
            }
        val downloadId =
            if (trackProgress) {
                activeDownloadId ?: currentPayload.filePreviewDownloadId()
            } else {
                null
            }
        if (trackProgress) {
            activeDownloadId = downloadId
            bridge.log(
                "file_preview_download phase=url_resolved downloadIdHash=${downloadId.orEmpty().hashCode().toString(16)} " +
                    "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} " +
                    externalUrl?.let(::binaryDownloadUrlDiagnostics).orEmpty()
            )
        }
        val progressCallback: ((BridgeAttachmentDownloadProgress) -> Unit)? =
            if (trackProgress) {
                { progress -> downloadProgressMailbox.offer(operation, progress) }
            } else {
                null
            }
        val result =
            if (exportPrivateDownload) {
                bridge.exportDownloadedAttachment(
                    attachmentId = currentPayload.attachmentCacheId(),
                    fileName = currentPayload.attachmentCacheFileName(),
                    owner = operation.owner,
                    mimeType = currentPayload.externalMimeType
                )
            } else if (toPhotos) {
                bridge.saveImageToPhotos(
                    url = externalUrl.orEmpty(),
                    attachmentId = currentPayload.attachmentCacheId(),
                    fileName = currentPayload.attachmentCacheFileName(),
                    owner = operation.owner,
                    mimeType = currentPayload.externalMimeType,
                    downloadId = downloadId,
                    onProgress = progressCallback
                )
            } else {
                bridge.saveAttachment(
                    url = externalUrl.orEmpty(),
                    attachmentId = currentPayload.attachmentCacheId(),
                    fileName = currentPayload.attachmentCacheFileName(),
                    owner = operation.owner,
                    mimeType = currentPayload.externalMimeType,
                    downloadId = downloadId,
                    onProgress = progressCallback
                )
            }
        val currentOperationId =
            if (exportPrivateDownload) {
                activeExportOperation?.operationId.orEmpty()
            } else {
                activeAttachmentOperation?.operationId.orEmpty()
            }
        if (!attachmentOperationCommitAllowed(operation, currentOperationId)) return null
        return when (result?.optString("status").orEmpty()) {
            "saved", "exported" -> {
                if (!toPhotos && !exportPrivateDownload && supportsPublicDownloadReadback) {
                    val query = publicDownloadQuery ?: return null
                    val observation = refreshPublicDownloadState(query) ?: return null
                    if (observation.state !is SlockFilePreviewPublicDownloadState.Downloaded) {
                        return storageUnavailable
                    }
                }
                if (!exportPrivateDownload) activeDownloadId = null
                bridge.toast(
                    slockFilePreviewSavedMessage(
                        exportPrivateDownload = exportPrivateDownload,
                        toPhotos = toPhotos,
                        supportsPublicDownloadReadback = supportsPublicDownloadReadback,
                        supportsPrivateAttachmentExport = supportsPrivateAttachmentExport,
                        downloadSaved = downloadSaved,
                        privateDownloadSaved = privateDownloadSaved,
                        fileExportSaved = fileExportSaved,
                        imageSavedToPhotos = imageSavedToPhotos
                    )
                )
                null
            }
            "cancelled" -> {
                if (!exportPrivateDownload) activeDownloadId = null
                if (exportPrivateDownload) null else downloadStopped
            }
            "storage_failed" ->
                if (exportPrivateDownload) {
                    fileExportFailed
                } else {
                    result?.optString("message").orEmpty().ifBlank { storageUnavailable }
                }
            "download_failed" -> downloadFailed
            "permission_denied" -> imagePhotosPermissionDenied
            "permission_restricted" -> imagePhotosPermissionRestricted
            "unsupported_image" -> imagePhotosUnsupported
            "photos_failed" -> imagePhotosSaveFailed
            else ->
                result?.optString("message").orEmpty().ifBlank {
                    when {
                        exportPrivateDownload -> fileExportFailed
                        toPhotos -> imagePhotosSaveFailed
                        else -> unableToDownloadAttachment
                    }
                }
        }
    }
    /** task #360: hand the short-lived disposition=attachment URL to the system browser, so
     * very large files (or files the in-app exporter cannot materialize) can still be fetched
     * outside the app. Read-only: no download state, MediaStore write, or upload-chain change. */
    fun requestExternalBrowserOpen() {
        if (exportInProgress || !slockFilePreviewCanStartExternalOperation(downloadStatus)) return
        val requestPayload = payload
        externalOperation = "open_browser"
        downloadMessage = ""
        coroutineScope.launch(start = CoroutineStart.LAZY) {
            val url =
                try {
                    resolveRemoteExternalUrl(requestPayload)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
            if (url.isNullOrBlank()) {
                downloadStatus = "failed"
                downloadMessage = downloadFailed
                externalOperation = ""
            } else {
                bridge.openUrl(url)
                downloadStatus = "idle"
                downloadMessage = ""
                externalOperation = ""
            }
        }.start()
    }

    fun requestExternalSave(toPhotos: Boolean = false, exportPrivateDownload: Boolean = false) {
        if (!canOpenWith || exportInProgress || !slockFilePreviewCanStartExternalOperation(downloadStatus)) return
        if (toPhotos && !payload.supportsSaveToPhotos()) return
        if (exportPrivateDownload && (!supportsPrivateAttachmentExport || visiblePublicDownloadState !is SlockFilePreviewPublicDownloadState.Downloaded)) return
        val owner = attachmentOwner ?: return
        // Saving a cached file still performs a potentially slow MediaStore export. Keep the
        // request observable so cache hits show Saving progress and remain cancellable/bounded.
        val trackProgress = !exportPrivateDownload
        externalOperation =
            when {
                toPhotos -> "save_photos"
                exportPrivateDownload -> "export_files"
                else -> "download"
            }
        if (!exportPrivateDownload) downloadMessage = ""
        if (exportPrivateDownload) {
            nextExportGeneration += 1L
            val exportOperationId = "export:${payload.filePreviewDownloadId(nextExportGeneration)}"
            activeExportOperation = AttachmentOperationToken(
                owner = owner,
                operationId = exportOperationId,
                downloadId = exportOperationId
            )
            applyExportIsolationEvent(SlockFilePreviewExportIsolationEvent.Started)
        } else if (trackProgress) {
            nextDownloadGeneration += 1L
            downloadStatus = "downloading"
            activeDownloadId = payload.filePreviewDownloadId(nextDownloadGeneration)
            activeAttachmentOperation = AttachmentOperationToken(
                owner = owner,
                operationId = activeDownloadId.orEmpty(),
                downloadId = activeDownloadId.orEmpty()
            )
            activeDownloadStartedAt = TimeSource.Monotonic.markNow()
            firstBridgeDownloadId = null
            firstRenderedDownloadId = null
            downloadProgress = SlockFilePreviewInitialDownloadProgress.copy(downloadId = activeDownloadId.orEmpty())
        } else {
            downloadStatus = "idle"
            activeDownloadId = null
            downloadProgress = null
        }
        val operation =
            if (exportPrivateDownload) {
                activeExportOperation
            } else {
                activeAttachmentOperation
            } ?: return
        val requestPayload = payload
        activeExternalOperationIdentity = visibleExternalOperationIdentity
        val operationJob =
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val error = savePayloadExternally(requestPayload, trackProgress, operation, toPhotos, exportPrivateDownload)
                    val currentOperationId =
                        if (exportPrivateDownload) {
                            activeExportOperation?.operationId.orEmpty()
                        } else {
                            activeAttachmentOperation?.operationId.orEmpty()
                        }
                    if (!attachmentOperationCommitAllowed(operation, currentOperationId)) return@launch
                    if (exportPrivateDownload) {
                        applyExportIsolationEvent(
                            if (error == null) {
                                SlockFilePreviewExportIsolationEvent.Succeeded
                            } else {
                                SlockFilePreviewExportIsolationEvent.Failed
                            }
                        )
                        activeExportOperation = null
                        if (error != null) bridge.toast(error)
                    } else if (error == null) {
                        downloadStatus = "idle"
                        downloadMessage = ""
                        downloadProgress = null
                        activeDownloadId = null
                        activeAttachmentOperation = null
                    } else {
                        downloadStatus = if (error == downloadStopped) "idle" else "failed"
                        downloadMessage = error
                        downloadProgress = null
                        activeDownloadId = null
                        activeAttachmentOperation = null
                        if (error != downloadStopped) {
                            bridge.toast(error)
                        }
                    }
                } finally {
                    if (exportPrivateDownload && activeExportOperation?.operationId == operation.operationId) {
                        applyExportIsolationEvent(SlockFilePreviewExportIsolationEvent.Cancelled)
                        activeExportOperation = null
                    }
                    if (activeExternalOperationJob.value == currentCoroutineContext()[Job]) {
                        activeExternalOperationJob.value = null
                        activeExternalOperationIdentity = null
                    }
                }
            }
        activeExternalOperationJob.value = operationJob
        operationJob.start()
    }
    fun requestExternalOpen() {
        val policy = slockFilePreviewExternalOpenPolicy(visiblePublicDownloadState)
        val rejectionReason =
            slockFilePreviewExternalOpenRejectionReason(
                canOpenWith = canOpenWith,
                exportInProgress = exportInProgress,
                downloadStatus = downloadStatus,
                hasOwner = attachmentOwner != null,
                hasPolicy = policy != null
            )
        if (!traceSlockFilePreviewExternalOpenAdmission(rejectionReason, policy, bridge::log)) return
        val owner = attachmentOwner ?: return
        val admittedPolicy = policy ?: return
        externalOperation = "open"
        downloadMessage = ""
        nextDownloadGeneration += 1L
        activeDownloadId = payload.filePreviewDownloadId(nextDownloadGeneration)
        activeAttachmentOperation = AttachmentOperationToken(
            owner = owner,
            operationId = activeDownloadId.orEmpty(),
            downloadId = activeDownloadId.orEmpty()
        )
        // Cached opens still cross an irreversible native launch boundary. Keep them non-idle
        // so a second tap cannot supersede this operation token before URI grant/startActivity
        // completes, but use a non-download status so Install/Open never flashes download chrome.
        downloadStatus = slockFilePreviewExternalOpenAdmissionStatus(admittedPolicy.trackProgress)
        activeDownloadStartedAt = TimeSource.Monotonic.markNow()
        if (admittedPolicy.trackProgress) {
            firstBridgeDownloadId = null
            firstRenderedDownloadId = null
            downloadProgress = SlockFilePreviewInitialDownloadProgress.copy(downloadId = activeDownloadId.orEmpty())
        } else {
            downloadProgress = null
        }
        val operation = activeAttachmentOperation ?: return
        val requestPayload = payload
        activeExternalOperationIdentity = visibleExternalOperationIdentity
        val operationJob =
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val error = openPayloadExternally(requestPayload, admittedPolicy, operation)
                    if (!attachmentOperationCommitAllowed(operation, activeAttachmentOperation?.operationId.orEmpty())) return@launch
                    if (error == null) {
                        downloadStatus = "idle"
                        downloadMessage = ""
                        downloadProgress = null
                        activeDownloadId = null
                        activeAttachmentOperation = null
                    } else {
                        downloadStatus = if (error == downloadStopped) "idle" else "failed"
                        downloadMessage = error
                        downloadProgress = null
                        activeDownloadId = null
                        activeAttachmentOperation = null
                        bridge.toast(error)
                    }
                } finally {
                    if (activeExternalOperationJob.value == currentCoroutineContext()[Job]) {
                        activeExternalOperationJob.value = null
                        activeExternalOperationIdentity = null
                    }
                }
            }
        activeExternalOperationJob.value = operationJob
        operationJob.start()
    }
    fun requestDownloadControl(action: String) {
        val downloadId = activeDownloadId ?: return
        val operation = activeAttachmentOperation ?: return
        if (!slockAttachmentDownloadControlAllowed(downloadProgress, action)) {
            // MediaStore export is cancellable but not resumable. Do not optimistically show a
            // fake Paused state while the native copy continues.
            return
        }
        when (action) {
            "pause" -> downloadStatus = "paused"
            "resume" -> downloadStatus = "downloading"
            "stop" -> {
                downloadStatus = "idle"
                downloadMessage = downloadStopped
            }
        }
        coroutineScope.launch {
            bridge.attachmentDownloadControl(
                downloadId = downloadId,
                owner = operation.owner,
                expectedAttemptToken = downloadProgress?.attemptToken,
                expectedRecordVersion = downloadProgress?.recordVersion,
                action = action
            )
        }
    }
    fun requestDeleteDownload() {
        val downloaded = visiblePublicDownloadState as? SlockFilePreviewPublicDownloadState.Downloaded ?: return
        if (!canOpenWith || exportInProgress || !slockFilePreviewCanStartExternalOperation(downloadStatus)) return
        val owner = attachmentOwner ?: return
        coroutineScope.launch {
            val result =
                bridge.deleteAttachmentCache(
                    attachmentId = attachmentCacheId,
                    fileName = attachmentCacheFileName,
                    owner = owner,
                    expectedAttemptToken = attachmentCacheAttemptToken,
                    expectedRecordVersion = downloaded.generation,
                    mimeType = payload.externalMimeType
                )
            if (!ProcessAttachmentAuthScopes.allows(owner)) return@launch
            val status = result?.optString("status").orEmpty()
            if (status == "ok" || status.isBlank()) {
                publicDownloadState = SlockFilePreviewPublicDownloadState.NotDownloaded
                observedPublicDownloadQuery = publicDownloadQuery
                attachmentCacheAttemptToken = null
                downloadStatus = "idle"
                downloadMessage = ""
                downloadProgress = null
                bridge.toast(downloadedFileDeleted)
            } else {
                val error = result?.optString("message").orEmpty().ifBlank { unableToDeleteDownload }
                bridge.toast(error)
            }
        }
    }
    fun selectImageGalleryIndex(index: Int) {
        payload.withImageGalleryIndex(index, imageGalleryResolutionState.resolvedPayloadByAttachmentId)?.let { nextPayload ->
            val retiringJob = activeExternalOperationJob.value
            activeAttachmentOperation = null
            activeExportOperation = null
            applyExportIsolationEvent(SlockFilePreviewExportIsolationEvent.Cancelled)
            activeExternalOperationIdentity = null
            activeExternalOperationJob.value = null
            retiringJob?.cancel()
            payload = nextPayload
            downloadStatus = "idle"
            downloadMessage = ""
            externalOperation = null
            downloadProgress = null
            activeDownloadId = null
        }
    }
    fun retireCurrentEphemeralSession(cleanupPending: Boolean = false) {
        val sessionId = launchPayloadTrace.sessionId
        SlockFilePreviewLaunchPayloadStore.retire(sessionId)
        val activeRetirement = ephemeralActionRegistry.retireActive(sessionId)
        val pendingRetirement =
            if (cleanupPending) {
                // A synchronous listener may already have claimed a file and be suspending while
                // it transfers ownership. Only a wholly unclaimed batch is safe to abort here.
                ephemeralActionRegistry.retirePendingIfUnclaimed(sessionId)
            } else {
                SlockFilePreviewEphemeralSessionRetirement()
            }
        val ledgerRefs =
            if (
                activeRetirement.retiredSession ||
                pendingRetirement.retiredSession ||
                !ephemeralActionRegistry.hasSession(sessionId)
            ) {
                ephemeralCleanupLedger.take(sessionId)
            } else {
                emptySet()
            }
        val cleanupRefs =
            activeRetirement.cleanupFileRefs +
                pendingRetirement.cleanupFileRefs +
                ledgerRefs
        if (cleanupRefs.isNotEmpty()) {
            bridge.retireFilePreviewTemporaryFiles(sessionId, cleanupRefs.toList())
        }
    }
    /**
     * Admit a host-created temporary ref to the currently live preview session.
     *
     * A false result is terminal for the caller: the session was already retired (usually page
     * disposal) or the ref did not satisfy the session identity.  The helper retires the ref on
     * that path so a late native result cannot continue toward delivery as an unowned file.
     */
    fun trackEphemeralTemporaryFileRef(fileRef: String): Boolean {
        val cleanRef = fileRef.trim()
        if (cleanRef.isBlank()) return false
        val tracked =
            ephemeralActionRegistry.trackOwnedTemporaryFileRef(
                sessionId = launchPayloadTrace.sessionId,
                threadKey = payload.composerImageThreadKey,
                fileRef = cleanRef
            )
        if (!tracked) {
            bridge.retireFilePreviewTemporaryFiles(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = listOf(cleanRef)
            )
            return false
        }
        val evictedRefs =
            ephemeralCleanupLedger.addFileRefs(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = setOf(cleanRef)
            )
        if (evictedRefs.isNotEmpty()) {
            bridge.retireFilePreviewTemporaryFiles(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = evictedRefs.toList()
            )
        }
        return true
    }
    fun composerImageEventData(
        fileRef: String,
        previewUri: String?,
        actionId: String = payload.composerImageActionId,
        fileName: String = payload.composerImageFileName.ifBlank { payload.externalFileName.ifBlank { payload.name.ifBlank { "image.png" } } },
        mimeType: String = payload.composerImageMimeType.ifBlank { payload.externalMimeType.ifBlank { "image/png" } },
        compressed: Boolean = payload.composerImageCompressed,
        source: String = slockFilePreviewComposerImageEventSource(compressed),
        sizeBytes: Long? = payload.composerImageSizeBytes,
        replaceAttachmentId: String = payload.composerImageReplaceAttachmentId,
        threadKey: String = payload.composerImageThreadKey,
        // task #113 whole-group contract: groupId + ordinal within the gallery + total count.
        // Blank groupId keeps legacy single-shot semantics (index/total ignored).
        // When groupId is set but this is a single image with no gallery, total must be 1
        // (single-image N=1 contract), never 0.
        groupId: String = payload.composerImageGroupId,
        groupIndex: Int = payload.imageGalleryIndex.coerceAtLeast(0),
        groupTotal: Int = SlockImageEditGroupReconcile.resolveGroupTotal(groupId, payload.imageGallery.size)
    ): JSONObject? {
        if (
            actionId.isBlank() ||
                fileRef.isBlank() ||
                !slockFilePreviewComposerImageEventMetadataIsValid(
                    source = source,
                    compressed = compressed,
                    mimeType = mimeType
                )
        ) {
            return null
        }
        return JSONObject().apply {
            put("previewSessionId", launchPayloadTrace.sessionId)
            put("threadKey", threadKey)
            put("actionId", actionId)
            put("action", if (replaceAttachmentId.isBlank()) "complete" else "replace")
            put("fileRef", fileRef)
            // A local picker/editor may not provide a separate thumbnail URI.  The upload handle
            // is still a valid preview source, and the shared consumer parser requires a
            // non-blank field; never emit an empty previewUri that would make an otherwise valid
            // finalization event undeclarable.
            put(
                "previewUri",
                previewUri?.trim()?.takeIf { it.isNotBlank() } ?: fileRef.trim()
            )
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("source", source.trim())
            put("compressed", compressed)
            sizeBytes?.takeIf { it > 0L }?.let { put("sizeBytes", it) }
            put("replaceAttachmentId", replaceAttachmentId)
            if (groupId.isNotBlank()) {
                put("groupId", groupId)
                put("groupIndex", groupIndex)
                put("groupTotal", groupTotal)
            }
        }
    }
    fun logComposerImageHandoffRejected(reason: String, eventCount: Int) {
        bridge.logWarn(
            "composer_image_handoff_rejected reason=$reason events=$eventCount " +
                "session=${launchPayloadTrace.sessionId.take(12)} " +
                "gallery=${payload.imageGallery.size} compress=${payload.composerImageCompressAfterEdit}"
        )
    }
    fun postComposerImageEvents(
        events: List<JSONObject>,
        closePage: Boolean = true,
        threadKey: String = payload.composerImageThreadKey
    ): Boolean {
        if (events.isEmpty()) {
            logComposerImageHandoffRejected("no_events", events.size)
            return false
        }
        val fileRefByActionId =
            events.associate { event ->
                event.optString("actionId") to event.optString("fileRef")
            }
        if (fileRefByActionId.size != events.size) {
            logComposerImageHandoffRejected("duplicate_action_id", events.size)
            return false
        }
        val retirement =
            ephemeralActionRegistry.prepareDeliveryBatch(
                sessionId = launchPayloadTrace.sessionId,
                threadKey = threadKey,
                fileRefByActionId = fileRefByActionId
            )
        if (retirement == null) {
            // A duplicate/late native callback can arrive after the first delivery
            // already moved this session to pending. Retire only an active session;
            // never abort or delete a batch that a consumer may already own.
            logComposerImageHandoffRejected("delivery_batch_unavailable", events.size)
            retireCurrentEphemeralSession(cleanupPending = false)
            return false
        }
        val ledgerEvictedRefs = ephemeralCleanupLedger.replace(
            sessionId = launchPayloadTrace.sessionId,
            fileRefs = retirement.pendingFileRefs
        )
        val cleanupBeforePost = retirement.cleanupFileRefs + ledgerEvictedRefs
        if (cleanupBeforePost.isNotEmpty()) {
            bridge.retireFilePreviewTemporaryFiles(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = cleanupBeforePost.toList()
            )
        }
        // Editor results are admitted by a process-local exactly-once registry. Relaying them
        // through the platform cannot add a valid consumer, but can replay an already delivered
        // event. Attempt every action even if an unrelated local listener throws on an earlier one.
        val posted = slockFilePreviewPostComposerImageEvents(events)
        if (!posted) {
            logComposerImageHandoffRejected("event_post_failed", events.size)
            retireCurrentEphemeralSession(cleanupPending = true)
            return false
        }
        SlockFilePreviewLaunchPayloadStore.retire(launchPayloadTrace.sessionId)
        if (closePage) {
            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
        return true
    }
    fun postComposerImageResult(
        fileRef: String,
        previewUri: String?,
        actionId: String = payload.composerImageActionId,
        fileName: String = payload.composerImageFileName.ifBlank { payload.externalFileName.ifBlank { payload.name.ifBlank { "image.png" } } },
        mimeType: String = payload.composerImageMimeType.ifBlank { payload.externalMimeType.ifBlank { "image/png" } },
        compressed: Boolean = payload.composerImageCompressed,
        source: String = slockFilePreviewComposerImageEventSource(compressed),
        sizeBytes: Long? = payload.composerImageSizeBytes,
        replaceAttachmentId: String = payload.composerImageReplaceAttachmentId,
        closePage: Boolean = true,
        threadKey: String = payload.composerImageThreadKey,
        groupId: String = payload.composerImageGroupId,
        groupIndex: Int = payload.imageGalleryIndex.coerceAtLeast(0),
        groupTotal: Int = SlockImageEditGroupReconcile.resolveGroupTotal(groupId, payload.imageGallery.size)
    ): Boolean {
        val event =
            composerImageEventData(
                fileRef = fileRef,
                previewUri = previewUri,
                actionId = actionId,
                fileName = fileName,
                mimeType = mimeType,
                compressed = compressed,
                source = source,
                sizeBytes = sizeBytes,
                replaceAttachmentId = replaceAttachmentId,
                threadKey = threadKey,
                groupId = groupId,
                groupIndex = groupIndex,
                groupTotal = groupTotal
            ) ?: return false
        return postComposerImageEvents(
            listOf(event),
            closePage = closePage,
            threadKey = threadKey
        )
    }
    fun cleanupComposerImageTemporaryRefs(fileRefs: Collection<String>) {
        val refs =
            fileRefs
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        if (refs.isNotEmpty()) {
            bridge.retireFilePreviewTemporaryFiles(
                sessionId = launchPayloadTrace.sessionId,
                fileRefs = refs.toList()
            )
        }
    }

    fun composerImageOperationRequestId(
        token: SlockFilePreviewComposerImageOperationToken,
        index: Int
    ): String =
        "${token.requestId}-item-${index.coerceAtLeast(0)}"

    suspend fun compressComposerImageFile(
        source: PlatformFile,
        token: SlockFilePreviewComposerImageOperationToken,
        index: Int
    ): Pair<PlatformFile?, Set<String>> {
        val sourceRef = source.fileRef?.trim()?.takeIf { it.isNotBlank() }
            ?: return null to emptySet()
        bridge.log("composer_image_compress_start index=$index sourceBytes=${source.sizeBytes ?: -1}")
        val response =
            bridge.compressThreadImage(
                fileRef = sourceRef,
                fileName = source.fileName.ifBlank { "image.png" },
                mimeType = source.mimeType?.ifBlank { "image/png" } ?: "image/png",
                requestId = composerImageOperationRequestId(token, index)
            )
        // A malformed host envelope may echo the input ref.  That ref is not a
        // generated JPEG and must remain available for a user retry; only refs
        // other than the source are eligible for cleanup on a failed attempt.
        val responseRefs =
            slockFilePreviewCompressionResponseFileRefs(response)
                .filterNot { it == sourceRef }
                .toSet()
        if (!composerImageOperationGate.isCurrent(token)) {
            bridge.log("composer_image_compress_result index=$index result=stale")
            return null to responseRefs
        }
        val parsed = parseSlockFilePreviewCompressedImageResult(response)
        bridge.log(
            "composer_image_compress_result index=$index sourceBytes=${source.sizeBytes ?: -1} " +
                "result=${if (parsed is SlockFilePreviewCompressedImageResult.Success) "success" else "failure"} " +
                "outputBytes=${(parsed as? SlockFilePreviewCompressedImageResult.Success)?.file?.sizeBytes ?: -1}"
        )
        return when (parsed) {
            is SlockFilePreviewCompressedImageResult.Success -> {
                val outputRef = parsed.file.fileRef?.trim().orEmpty()
                // Native must write a new object.  Reusing the source ref would make a later
                // retry observe a partially overwritten source and is therefore fail-closed.
                if (outputRef.isBlank() || outputRef == sourceRef) {
                    null to (responseRefs + outputRef).filterNot { it == sourceRef }.toSet()
                } else {
                    if (!trackEphemeralTemporaryFileRef(outputRef)) {
                        // The page/session may have been retired while the native bridge was
                        // running.  Do not let an otherwise valid JPEG escape that ownership
                        // fence; the helper has already requested ref retirement.
                        null to (responseRefs + outputRef)
                    } else {
                        parsed.file to emptySet()
                    }
                }
            }
            is SlockFilePreviewCompressedImageResult.Failure -> null to responseRefs
        }
    }

    fun composerImageEventsForPayload(
        eventPayload: SlockFilePreviewPayload
    ): List<JSONObject> {
        return slockFilePreviewComposerImageEventsForPayload(
            eventPayload = eventPayload,
            singleEvent = { groupId, groupIndex, groupTotal ->
                composerImageEventData(
                    fileRef = eventPayload.composerImageFileRef,
                    previewUri = eventPayload.composerImagePreviewUri.ifBlank { eventPayload.imageUrl },
                    actionId = eventPayload.composerImageActionId,
                    fileName = eventPayload.composerImageFileName.ifBlank {
                        eventPayload.externalFileName.ifBlank { eventPayload.name.ifBlank { "image.png" } }
                    },
                    mimeType = eventPayload.composerImageMimeType.ifBlank {
                        eventPayload.externalMimeType.ifBlank { "image/png" }
                    },
                    compressed = eventPayload.composerImageCompressed,
                    source = slockFilePreviewComposerImageEventSource(eventPayload.composerImageCompressed),
                    sizeBytes = eventPayload.composerImageSizeBytes,
                    replaceAttachmentId = eventPayload.composerImageReplaceAttachmentId,
                    threadKey = eventPayload.composerImageThreadKey,
                    groupId = groupId,
                    groupIndex = groupIndex,
                    groupTotal = groupTotal
                )
            },
            galleryEvent = { index, image, groupId, groupTotal ->
                composerImageEventData(
                    fileRef = image.composerImageFileRef,
                    previewUri = image.composerImagePreviewUri.ifBlank { image.thumbnailUrl.orEmpty() },
                    actionId =
                        slockFilePreviewComposerImageGalleryActionId(
                            baseActionId = eventPayload.composerImageActionId,
                            attachmentId = image.attachmentId,
                            index = index
                        ),
                    fileName = image.name.ifBlank { "image.png" },
                    mimeType = image.mimeType.ifBlank { "image/png" },
                    compressed = image.composerImageCompressed,
                    source = slockFilePreviewComposerImageEventSource(image.composerImageCompressed),
                    sizeBytes = image.composerImageSizeBytes,
                    // Fresh picker galleries use a blank target so Settings/Thread consumers
                    // receive the legacy `complete` action.  A Thread editor gallery records an
                    // explicit remote slot in composerImageReplaceAttachmentId; carry that
                    // witness instead of deriving it from the synthetic local id.
                    replaceAttachmentId = image.composerImageReplaceAttachmentId,
                    threadKey = eventPayload.composerImageThreadKey,
                    groupId = groupId,
                    groupIndex = index,
                    groupTotal = groupTotal
                )
            }
        )
    }

    fun postComposerImageSelection() {
        // First statement on purpose: a missing tap log then means the click never reached
        // this handler, independent of every gate below.
        bridge.log("composer_image_done_tap finalizing=$composerImageFinalizing")
        if (composerImageFinalizing) {
            logComposerImageHandoffRejected("finalizing", 0)
            return
        }
        val token =
            composerImageOperationGate.begin(
                requestId = "${payload.composerImageActionId.ifBlank { launchPayloadTrace.sessionId }}-done"
            )
        if (token == null) {
            logComposerImageHandoffRejected("operation_busy", 0)
            return
        }
        bridge.log(
            "composer_image_done_start gallery=${payload.imageGallery.size} " +
                "compress=${payload.composerImageCompressAfterEdit} " +
                "sizeBytes=${payload.composerImageSizeBytes ?: -1}"
        )
        composerImageFinalizing = true
        val operationJob =
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                val generatedRefs = mutableSetOf<String>()
                try {
                    var workingPayload = payload
                    val compressAfterEdit = workingPayload.composerImageCompressAfterEdit
                    if (workingPayload.imageGallery.isNotEmpty()) {
                        for ((index, image) in workingPayload.imageGallery.withIndex()) {
                            if (image.isComposerImageFinalized(compressAfterEdit)) continue
                            val source = image.toComposerImagePlatformFile()
                    val finalFileAndCleanup =
                                if (compressAfterEdit) {
                                    compressComposerImageFile(source, token, index)
                                } else {
                                    source to emptySet()
                                }
                            val finalFile = finalFileAndCleanup.first
                            if (finalFile == null || !composerImageOperationGate.isCurrent(token)) {
                                cleanupComposerImageTemporaryRefs(
                                    generatedRefs + finalFileAndCleanup.second
                                )
                                if (composerImageOperationGate.isCurrent(token)) {
                                    composerImageOperationGate.complete(token)
                                    composerImageFinalizing = false
                                    bridge.toast(composerImageCompressionFailed)
                                }
                                return@launch
                            }
                            if (compressAfterEdit) {
                                finalFile.fileRef?.trim()?.takeIf { it.isNotBlank() }?.let(generatedRefs::add)
                            }
                            workingPayload =
                                workingPayload.withComposerImageGalleryFinalResult(
                                    index = index,
                                    file = finalFile,
                                    compressed = compressAfterEdit
                                )
                        }
                    } else if (
                        !workingPayload.composerImageEditCompleted ||
                            (compressAfterEdit && !workingPayload.composerImageCompressed)
                    ) {
                        val source = workingPayload.toComposerImagePlatformFile()
                        val finalFileAndCleanup =
                            if (compressAfterEdit) {
                                compressComposerImageFile(source, token, 0)
                            } else {
                                source to emptySet()
                            }
                        val finalFile = finalFileAndCleanup.first
                        if (finalFile == null || !composerImageOperationGate.isCurrent(token)) {
                            cleanupComposerImageTemporaryRefs(
                                generatedRefs + finalFileAndCleanup.second
                            )
                            if (composerImageOperationGate.isCurrent(token)) {
                                composerImageOperationGate.complete(token)
                                composerImageFinalizing = false
                                bridge.toast(composerImageCompressionFailed)
                            }
                            return@launch
                        }
                        if (compressAfterEdit) {
                            finalFile.fileRef?.trim()?.takeIf { it.isNotBlank() }?.let(generatedRefs::add)
                        }
                        workingPayload =
                            workingPayload.withComposerImageFinalResult(
                                file = finalFile,
                                compressed = compressAfterEdit
                            )
                    }
                    if (!composerImageOperationGate.isCurrent(token)) {
                        cleanupComposerImageTemporaryRefs(generatedRefs)
                        return@launch
                    }
                    val events = composerImageEventsForPayload(workingPayload)
                    if (
                        events.isEmpty() ||
                            (workingPayload.imageGallery.isNotEmpty() &&
                                events.size != workingPayload.imageGallery.size)
                    ) {
                        cleanupComposerImageTemporaryRefs(generatedRefs)
                        retireCurrentEphemeralSession(cleanupPending = false)
                        composerImageOperationGate.complete(token)
                        composerImageFinalizing = false
                        bridge.toast(composerImageCompressionFailed)
                        return@launch
                    }
                    val posted =
                        postComposerImageEvents(
                            events = events,
                            threadKey = workingPayload.composerImageThreadKey
                        )
                    if (!posted) {
                        composerImageOperationGate.complete(token)
                        composerImageFinalizing = false
                        // postComposerImageEvents already logged the reason; never leave Done
                        // looking like a dead button.
                        bridge.toast(composerImageHandoffFailed)
                        return@launch
                    }
                    // Commit page-local payload only after registry admission and event relay
                    // both succeed. A stale/failed post may retire generated refs; writing the
                    // working snapshot before that point would leave the UI pointing at files
                    // that have already been cleaned up.
                    payload = workingPayload
                    composerImageOperationGate.complete(token)
                    composerImageFinalizing = false
                } catch (cancelled: CancellationException) {
                    composerImageOperationGate.cancel(token)
                    cleanupComposerImageTemporaryRefs(generatedRefs)
                    throw cancelled
                } catch (error: Throwable) {
                    logComposerImageHandoffRejected("exception_${error::class.simpleName.orEmpty()}", 0)
                    composerImageOperationGate.complete(token)
                    cleanupComposerImageTemporaryRefs(generatedRefs)
                    if (composerImageOperationGate.activeToken() == null) {
                        composerImageFinalizing = false
                        bridge.toast(composerImageCompressionFailed)
                    }
                } finally {
                    if (activeComposerImageOperationJob.value === currentCoroutineContext()[Job]) {
                        activeComposerImageOperationJob.value = null
                    }
                }
            }
        activeComposerImageOperationJob.value = operationJob
        operationJob.start()
    }

    fun requestComposerImageEditSave() {
        if (composerImageEditSaving || composerImageFinalizing) return
        val captureView = composerImageEditCaptureSlot.value.ref?.view
        if (captureView == null) {
            bridge.toast(imageEditorLoading)
            return
        }
        val token =
            composerImageOperationGate.begin(
                requestId = "${payload.composerImageActionId.ifBlank { launchPayloadTrace.sessionId }}-save"
            ) ?: return
        composerImageEditSaving = true
        val captureTerminal = atomic(false)
        captureView.toImage(DeclarativeBaseView.ImageType.FILE, 1) { result ->
            if (!captureTerminal.compareAndSet(false, true)) return@toImage
            val fileRef = result?.optString("data")?.trim().orEmpty()
            // `toImage` has no cancellation handle. If the page went away while the renderer
            // was producing its snapshot, retire the late file but never touch page state.
            if (!composerImageOperationGate.isCurrent(token)) {
                fileRef.takeIf { it.isNotBlank() }?.let { ref ->
                    cleanupComposerImageTemporaryRefs(listOf(ref))
                }
                return@toImage
            }
            val code = result?.optInt("code", -1) ?: -1
            if (code != 0 || fileRef.isBlank()) {
                fileRef.takeIf { it.isNotBlank() }?.let { ref ->
                    cleanupComposerImageTemporaryRefs(listOf(ref))
                }
                composerImageOperationGate.complete(token)
                composerImageEditSaving = false
                bridge.toast(result?.optString("message").orEmpty().ifBlank { imageEditorExportFailed })
                return@toImage
            }
            if (!trackEphemeralTemporaryFileRef(fileRef)) {
                // The session can retire between the gate check and registry admission. The
                // helper already requested ref retirement; only reset UI while this token is
                // still live.
                if (composerImageOperationGate.complete(token)) {
                    composerImageEditSaving = false
                    bridge.toast(composerImageCompressionFailed)
                }
                return@toImage
            }
            val editPayload = payload
            val source =
                PlatformFile(
                    id = "composer-editor-${fileRef.hashCode().toUInt().toString(16)}",
                    fileName = editPayload.currentComposerImageFileName().toKuiklySnapshotPngFileName(),
                    mimeType = KuiklySnapshotMimeType,
                    previewUri = fileRef,
                    fileRef = fileRef,
                    source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE
                )
            val operationJob =
                coroutineScope.launch(start = CoroutineStart.LAZY) {
                    val cleanupRefs = mutableSetOf(fileRef)
                    try {
                        val finalFile: PlatformFile
                        val compressed: Boolean
                        if (editPayload.composerImageCompressAfterEdit) {
                            val compressedResult = compressComposerImageFile(source, token, 0)
                            cleanupRefs += compressedResult.second
                            finalFile = compressedResult.first ?: run {
                                cleanupComposerImageTemporaryRefs(cleanupRefs)
                                composerImageOperationGate.complete(token)
                                composerImageEditSaving = false
                                bridge.toast(composerImageCompressionFailed)
                                return@launch
                            }
                            compressed = true
                        } else {
                            finalFile = source
                            compressed = false
                        }
                        // Keep every generated ref in the failure ledger until the delivery
                        // relay has accepted it.  The source PNG is always tracked above; a
                        // successful compressor adds a distinct JPEG that also needs cleanup if
                        // the page goes stale or the immediate-send relay is rejected.
                        finalFile.fileRef
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(cleanupRefs::add)
                        if (!composerImageOperationGate.isCurrent(token)) {
                            cleanupComposerImageTemporaryRefs(cleanupRefs)
                            return@launch
                        }
                        val nextPayload = editPayload.withComposerImageFinalResult(finalFile, compressed)
                        if (!nextPayload.composerImageCanComplete) {
                            // Immediate-send previews have no composer consumer to update after
                            // this page closes.  Relay the exact next-payload fields first; only
                            // after registry admission + event delivery succeeds may page-local
                            // payload state point at the generated file.
                            val posted =
                                postComposerImageResult(
                                    fileRef = nextPayload.composerImageFileRef,
                                    previewUri = nextPayload.composerImagePreviewUri,
                                    actionId = nextPayload.composerImageActionId,
                                    fileName = nextPayload.composerImageFileName,
                                    mimeType = nextPayload.composerImageMimeType,
                                    compressed = compressed,
                                    source = slockFilePreviewComposerImageEventSource(compressed),
                                    sizeBytes = nextPayload.composerImageSizeBytes,
                                    replaceAttachmentId = nextPayload.composerImageReplaceAttachmentId,
                                    closePage = false,
                                    threadKey = nextPayload.composerImageThreadKey,
                                    groupId = nextPayload.composerImageGroupId,
                                    groupIndex = nextPayload.imageGalleryIndex.coerceAtLeast(0),
                                    groupTotal =
                                        SlockImageEditGroupReconcile.resolveGroupTotal(
                                            nextPayload.composerImageGroupId,
                                            nextPayload.imageGallery.size
                                        )
                                )
                            if (!posted) {
                                // postComposerImageEvents owns failure cleanup.  In particular,
                                // it must preserve a pending ref if a synchronous consumer
                                // claimed it before an unrelated listener threw; deleting the
                                // whole cleanup set here would race that consumer's transfer.
                                composerImageOperationGate.complete(token)
                                composerImageEditSaving = false
                                return@launch
                            }
                        }
                        // A completable session keeps the result for the caller's later Done;
                        // an immediate-send session reaches this point only after relay success.
                        payload = nextPayload
                        composerImageEditMode = false
                        composerImageEditSaving = false
                        composerImageOperationGate.complete(token)
                        if (!nextPayload.composerImageCanComplete) {
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    } catch (cancelled: CancellationException) {
                        composerImageOperationGate.cancel(token)
                        cleanupComposerImageTemporaryRefs(cleanupRefs)
                        throw cancelled
                    } catch (_: Throwable) {
                        composerImageOperationGate.complete(token)
                        cleanupComposerImageTemporaryRefs(cleanupRefs)
                        composerImageEditSaving = false
                        bridge.toast(composerImageCompressionFailed)
                    } finally {
                        if (activeComposerImageOperationJob.value === currentCoroutineContext()[Job]) {
                            activeComposerImageOperationJob.value = null
                        }
                    }
                }
            activeComposerImageOperationJob.value = operationJob
            operationJob.start()
        }
    }
    DisposableEffect(launchPayloadTrace.sessionId, launchPayloadTrace.routeKind) {
        onDispose {
            // Invalidate callbacks before retiring the ephemeral registry. Cancellation then
            // reaches BridgeModule's invokeOnCancellation, which asks each host to cancel its
            // request-id keyed codec operation and remove its output.
            composerImageOperationGate.retire()
            activeComposerImageOperationJob.value?.cancel()
            activeComposerImageOperationJob.value = null
            if (launchPayloadTrace.routeKind == SlockFilePreviewRouteEphemeral) {
                retireCurrentEphemeralSession(cleanupPending = false)
            } else {
                SlockFilePreviewLaunchPayloadStore.retire(launchPayloadTrace.sessionId)
            }
        }
    }
    val downloadUiState = slockFilePreviewDownloadUiState(visiblePublicDownloadState, downloadStatus)
    val downloadActive = downloadUiState == SlockFilePreviewDownloadUiState.Downloading
    SlockFilePreviewPageScaffold(
        title = if (composerImageEditMode) stringResource(MR.strings.file_preview_editor_title) else payload.previewHeaderTitle(),
        subtitle = if (composerImageEditMode) null else payload.previewHeaderSubtitle(),
        iconName = if (composerImageEditMode) SlockLucideIconName.Pencil else payload.previewIconName(),
        onBack = {
            if (composerImageEditMode && !composerImageEditSaving) {
                composerImageEditMode = false
            } else if (!composerImageEditMode) {
                if (launchPayloadTrace.routeKind == SlockFilePreviewRouteEphemeral) {
                    retireCurrentEphemeralSession(cleanupPending = false)
                } else {
                    SlockFilePreviewLaunchPayloadStore.retire(launchPayloadTrace.sessionId)
                }
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        },
        contentPadding = 0.dp,
        trailingContent =
            if (composerImageEditMode) {
                {
                    SlockFilePreviewImageEditorHistoryActions(
                        state = composerImageEditorState,
                        saving = composerImageEditSaving,
                    )
                }
            } else {
                null
            },
        overflowMenuItems =
            if (composerImageEditMode) {
                emptyList()
            } else listOfNotNull(
                if (photosActionState != SlockFilePreviewPhotosActionState.Hidden) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewSaveToPhotosActionId,
                        label =
                            stringResource(
                                when (photosActionState) {
                                    SlockFilePreviewPhotosActionState.Saving ->
                                        MR.strings.thread_image_preview_saving_to_photos
                                    SlockFilePreviewPhotosActionState.Retry ->
                                        MR.strings.thread_image_preview_retry_save_to_photos
                                    else -> MR.strings.thread_image_preview_save_to_photos
                                }
                            ),
                        icon =
                            if (photosActionState == SlockFilePreviewPhotosActionState.Saving) {
                                SlockLucideIconName.LoaderCircle
                            } else {
                                SlockLucideIconName.Save
                            },
                        enabled =
                            photosActionState == SlockFilePreviewPhotosActionState.Save ||
                                photosActionState == SlockFilePreviewPhotosActionState.Retry
                    )
                } else {
                    null
                },
                if (canOpenComments) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewCommentsActionId,
                        label = stringResource(MR.strings.file_preview_action_comments),
                        icon = SlockLucideIconName.MessageCircle
                    )
                } else {
                    null
                },
                if (canOpenWith && downloadUiState != null) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewOpenWithActionId,
                        label = slockFilePreviewOverflowActionLabel(payload, downloadUiState, actionTerms),
                        icon = SlockLucideIconName.ExternalLink,
                        enabled = !exportInProgress && slockFilePreviewCanStartExternalOperation(downloadStatus)
                    )
                } else {
                    null
                },
                if (
                    canOpenWith &&
                    supportsPrivateAttachmentExport &&
                    visiblePublicDownloadState is SlockFilePreviewPublicDownloadState.Downloaded
                ) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewSaveToFilesActionId,
                        label =
                            stringResource(
                                if (exportInProgress) {
                                    MR.strings.file_preview_action_saving_to_files
                                } else {
                                    MR.strings.file_preview_action_save_to_files
                                }
                            ),
                        icon =
                            if (exportInProgress) {
                                SlockLucideIconName.LoaderCircle
                            } else {
                                SlockLucideIconName.Download
                            },
                        enabled = !exportInProgress && slockFilePreviewCanStartExternalOperation(downloadStatus)
                    )
                } else {
                    null
                },
                if (canOpenWith && downloadUiState == SlockFilePreviewDownloadUiState.Downloaded) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewDeleteDownloadActionId,
                        label = stringResource(MR.strings.file_preview_action_delete_downloaded),
                        icon = SlockLucideIconName.Trash2
                    )
                } else {
                    null
                }
            ),
        onOverflowMenuSelect = { id ->
            when (id) {
                SlockFilePreviewSaveToPhotosActionId -> requestExternalSave(toPhotos = true)
                SlockFilePreviewCommentsActionId -> openAttachmentComments()
                SlockFilePreviewOpenWithActionId -> requestExternalOpen()
                SlockFilePreviewSaveToFilesActionId -> requestExternalSave(exportPrivateDownload = true)
                SlockFilePreviewDeleteDownloadActionId -> requestDeleteDownload()
            }
        }
    ) {
        val primaryActionEnabled =
            downloadUiState != null &&
                downloadUiState != SlockFilePreviewDownloadUiState.Downloading &&
                !exportInProgress &&
                slockFilePreviewCanStartExternalOperation(downloadStatus)
        if (composerImageEditMode) {
            SlockFilePreviewComposerImageEditor(
                payload = payload,
                state = composerImageEditorState,
                saving = composerImageEditSaving,
                onCaptureRef = onComposerImageEditCaptureRef,
                onDone = ::requestComposerImageEditSave,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SlockFilePreviewPayloadContent(
                    payload = payload,
                    onOpenUrl = {},
                    onExternalBrowserOpen =
                        if (
                            payload.source == SlockFilePreviewSourceThreadAttachment &&
                            payload.attachmentId.isNotBlank()
                        ) {
                            ::requestExternalBrowserOpen
                        } else {
                            null
                        },
                    downloadStatusLabel =
                        when (downloadStatus) {
                            "downloading", "paused" ->
                                slockFilePreviewLocalizedActiveDownloadStatusLabel(downloadProgress, downloadStatus)
                            "failed" -> downloadMessage.ifBlank { downloadFailed }
                            else -> null
                        },
                    downloadInProgress = downloadActive,
                    downloadPaused = downloadStatus == "paused",
                    downloadProgressPercent = downloadProgress?.percent,
                    downloadState = downloadUiState,
                    primaryActionEnabled = primaryActionEnabled,
                    onDownload =
                        if (canOpenWith) {
                            when (slockFilePreviewPrimaryAction(downloadUiState)) {
                                SlockFilePreviewPrimaryAction.Save -> ({ requestExternalSave() })
                                SlockFilePreviewPrimaryAction.Open -> ::requestExternalOpen
                                null -> ({})
                            }
                        } else {
                            null
                        },
                    onPauseDownload = { requestDownloadControl("pause") },
                    onResumeDownload = { requestDownloadControl("resume") },
                    onStopDownload = { requestDownloadControl("stop") },
                    onImageGalleryIndexChange = ::selectImageGalleryIndex,
                    modifier = Modifier.fillMaxSize()
                )
                SlockFilePreviewImageGalleryCacheKeepAlive(
                    sources =
                        payload.imageGalleryKeepAliveSources(
                            imageGalleryResolutionState.resolvedPayloadByAttachmentId
                        )
                )
            }
            SlockFilePreviewComposerImageActionBar(
                payload = payload,
                // Finalization is asynchronous; keep the action visibly disabled while the
                // in-flight operation owns the session, in addition to the function-level
                // duplicate guard.
                enabled = !downloadActive && !composerImageFinalizing,
                onEdit = { composerImageEditMode = true },
                onComplete = ::postComposerImageSelection,
                onOriginalImageChange = { enabled ->
                    payload = payload.withComposerOriginalImage(enabled)
                },
            )
        }
        attachmentCommentsState?.let { state ->
            SlockFilePreviewAttachmentCommentsPanel(
                state = state,
                onClose = { attachmentCommentsState = null },
                onFilterChange = { filter ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.copy(filter = filter)
                },
                onDraftChange = { draft ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.withAttachmentCommentEditorValue(draft)
                },
                onRetry = ::reloadAttachmentComments,
                onSend = ::sendAttachmentComment,
                onResolve = ::resolveAttachmentComment,
                onQuote = { comment ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.withQuotedAttachmentComment(comment)
                }
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewAttachmentCommentsPanel(
    state: ThreadAttachmentCommentsDialogState,
    onClose: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDraftChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(MR.strings.thread_comments_title),
                style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.X,
                onClick = onClose,
                size = 28.dp,
                shadowOffset = 2.dp
            )
        }
        ThreadAttachmentCommentsContent(
            state = state,
            onFilterChange = onFilterChange,
            onDraftChange = onDraftChange,
            onRetry = onRetry,
            onSend = onSend,
            onResolve = onResolve,
            onQuote = onQuote,
            compact = true
        )
    }
}

@Composable
internal fun SlockFilePreviewComposerImageActionBar(
    payload: SlockFilePreviewPayload,
    enabled: Boolean,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onOriginalImageChange: (Boolean) -> Unit = {},
) {
    if (payload.composerImageActionId.isBlank()) return
    if (!payload.composerImageCanEdit && !payload.composerImageCanComplete) return
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val originalImageSelected = payload.composerOriginalImageSelected()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(
                    start = SlockSpacing.Md,
                    top = SlockSpacing.Sm,
                    end = SlockSpacing.Md,
                    bottom = SlockSpacing.Sm + bottomSafeArea,
                ),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slockFilePreviewComposerImageActionSlots(
            canEdit = payload.composerImageCanEdit,
            canComplete = payload.composerImageCanComplete,
            originalImageOptionEnabled = payload.showsComposerOriginalImageOption(),
        ).forEach { slot ->
            when (slot) {
                SlockFilePreviewComposerImageActionSlot.Edit ->
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_edit),
                        onClick = onEdit,
                        enabled = enabled && payload.imageUrl != null,
                        leadingIcon = SlockLucideIconName.Pencil,
                        leadingIconSize = 14.dp,
                        contentSpacing = 8.dp,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        shadowOffset = 2.dp,
                        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp)
                    )
                SlockFilePreviewComposerImageActionSlot.OriginalImage -> {
                    val labelColor =
                        if (enabled) {
                            SlockColor.TextBlack.copy(alpha = 0.6f)
                        } else {
                            SlockColor.TextMutedGray
                        }
                    Row(
                        modifier =
                            Modifier
                                .clickable(enabled = enabled) {
                                    onOriginalImageChange(!originalImageSelected)
                                }
                                .heightIn(min = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SlockCheckbox(
                            checked = originalImageSelected,
                            onCheckedChange = onOriginalImageChange,
                            enabled = enabled,
                            size = 14.dp,
                        )
                        Text(
                            text = stringResource(MR.strings.thread_composer_original_image),
                            style =
                                SlockType.Caption
                                    .withSlockFontWeight(FontWeight.Bold)
                                    .copy(lineHeight = 18.sp, color = labelColor),
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SlockFilePreviewComposerImageActionSlot.FlexibleSpacer ->
                    Box(modifier = Modifier.weight(1f))
                SlockFilePreviewComposerImageActionSlot.Done ->
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_done),
                        onClick = onComplete,
                        enabled = enabled && payload.canCompleteComposerImageSelection(),
                        leadingIcon = SlockLucideIconName.Check,
                        leadingIconSize = 14.dp,
                        contentSpacing = 8.dp,
                        backgroundColor = SlockColor.BrandYellow,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        shadowOffset = 2.dp,
                        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp)
                    )
            }
        }
    }
}

internal enum class SlockFilePreviewComposerImageActionSlot {
    Edit,
    OriginalImage,
    FlexibleSpacer,
    Done,
}

internal fun slockFilePreviewComposerImageActionSlots(
    canEdit: Boolean,
    canComplete: Boolean,
    originalImageOptionEnabled: Boolean = false,
): List<SlockFilePreviewComposerImageActionSlot> =
    buildList {
        if (canEdit) add(SlockFilePreviewComposerImageActionSlot.Edit)
        if (originalImageOptionEnabled && canComplete) {
            add(SlockFilePreviewComposerImageActionSlot.OriginalImage)
        }
        add(SlockFilePreviewComposerImageActionSlot.FlexibleSpacer)
        if (canComplete) add(SlockFilePreviewComposerImageActionSlot.Done)
    }

internal enum class SlockComposerImageEditorTool {
    Pencil,
    Text,
    Mosaic,
    Crop,
}

internal data class SlockComposerImageCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isUncropped: Boolean
        get() = left <= 0.0001f && top <= 0.0001f && right >= 0.9999f && bottom >= 0.9999f

    val widthFraction: Float get() = (right - left).coerceAtLeast(0.01f)
    val heightFraction: Float get() = (bottom - top).coerceAtLeast(0.01f)

    companion object {
        val Uncropped = SlockComposerImageCropRect(0f, 0f, 1f, 1f)
    }
}

private enum class SlockComposerImagePencilMode {
    Ink,
    Eraser,
}

private val SlockComposerImageEditorDefaultPenColor = Color(0xFFFF3B30)
private val SlockComposerImageEditorPenColors =
    listOf(
        Color(0xFFFFFFFF),
        Color(0xFF111111),
        SlockComposerImageEditorDefaultPenColor,
        Color(0xFFFF9500),
        Color(0xFFFFCC00),
        Color(0xFF34C759),
        Color(0xFF32ADE6),
    )
private const val SlockComposerImageMosaicTileSizePx = 18f
private const val SlockComposerImageMosaicBrushRadius = 1

@Composable
private fun SlockFilePreviewImageEditorDoneButton(
    saving: Boolean,
    imageAvailable: Boolean,
    onDone: () -> Unit,
) {
    SlockBrutalActionButton(
        label =
            if (saving) {
                stringResource(MR.strings.file_preview_editor_saving)
            } else {
                stringResource(MR.strings.common_done)
            },
        onClick = onDone,
        enabled = !saving && imageAvailable,
        leadingIcon = if (saving) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Check,
        leadingIconSize = 14.dp,
        contentSpacing = 8.dp,
        backgroundColor = SlockColor.BrandYellow,
        minWidth = 0.dp,
        minHeight = 32.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp),
    )
}

internal data class SlockComposerImageTextAnnotation(
    val id: Int,
    val text: String,
    val position: Offset
)

/**
 * Caller-local text draft for one image-editor identity.
 *
 * Kuikly's String TextField contract expects the newest text to be fed back unchanged. Keep the
 * image identity beside the text so a stale editor callback cannot carry one image's draft into
 * another image, and deliberately avoid applying a hidden length transform at this write edge.
 */
internal data class SlockFilePreviewAnnotationDraft(
    val imageUrl: String?,
    val text: String = "",
) {
    fun updated(imageUrl: String?, nextText: String): SlockFilePreviewAnnotationDraft =
        if (imageUrl == this.imageUrl) {
            copy(text = nextText)
        } else {
            SlockFilePreviewAnnotationDraft(imageUrl = imageUrl, text = nextText)
        }

    fun cleared(): SlockFilePreviewAnnotationDraft = copy(text = "")
}

internal data class SlockComposerImageStroke(
    val points: List<Offset>,
    val color: Color = Color(0xFFFF3B30),
)

internal data class SlockComposerImageMosaicTile(
    val column: Int,
    val row: Int,
)

internal data class SlockComposerImageMosaicSample(
    val sourceX: Int,
    val sourceY: Int,
    val destinationX: Int,
    val destinationY: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
)

internal sealed interface SlockComposerImageDrawOperation {
    data class Mosaic(val tiles: Set<SlockComposerImageMosaicTile>) : SlockComposerImageDrawOperation

    data class Pencil(val stroke: SlockComposerImageStroke) : SlockComposerImageDrawOperation
}

internal data class SlockComposerImageEditorContent(
    val strokes: List<SlockComposerImageStroke> = emptyList(),
    val mosaicTiles: Set<SlockComposerImageMosaicTile> = emptySet(),
    val textAnnotations: List<SlockComposerImageTextAnnotation> = emptyList(),
    val drawOperations: List<SlockComposerImageDrawOperation> = emptyList(),
    val cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
)

internal data class SlockComposerImageEditorHistory(
    val content: SlockComposerImageEditorContent = SlockComposerImageEditorContent(),
    val undoStack: List<SlockComposerImageEditorContent> = emptyList(),
    val redoStack: List<SlockComposerImageEditorContent> = emptyList(),
) {
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun apply(nextContent: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (nextContent == content) {
            this
        } else {
            copy(content = nextContent, undoStack = undoStack + content, redoStack = emptyList())
        }

    fun replaceWithoutHistory(nextContent: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (nextContent == content) this else copy(content = nextContent)

    fun commitTransaction(origin: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (origin == content) this else copy(undoStack = undoStack + origin, redoStack = emptyList())

    fun undo(): SlockComposerImageEditorHistory {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(content = previous, undoStack = undoStack.dropLast(1), redoStack = redoStack + content)
    }

    fun redo(): SlockComposerImageEditorHistory {
        val next = redoStack.lastOrNull() ?: return this
        return copy(content = next, undoStack = undoStack + content, redoStack = redoStack.dropLast(1))
    }
}

internal class SlockComposerImageEditorState(
    initialHistory: SlockComposerImageEditorHistory = SlockComposerImageEditorHistory(),
) {
    var history by mutableStateOf(initialHistory)
        private set

    fun update(nextHistory: SlockComposerImageEditorHistory) {
        history = nextHistory
    }

    fun undo() {
        history = history.undo()
    }

    fun redo() {
        history = history.redo()
    }
}

@Composable
internal fun SlockFilePreviewImageEditorHistoryActions(
    state: SlockComposerImageEditorState,
    saving: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Undo2,
            label = stringResource(MR.strings.common_undo),
            onClick = state::undo,
            enabled = !saving && state.history.canUndo,
            size = 32.dp,
            iconSize = 22.dp,
        )
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Redo2,
            label = stringResource(MR.strings.common_redo),
            onClick = state::redo,
            enabled = !saving && state.history.canRedo,
            size = 32.dp,
            iconSize = 22.dp,
        )
        Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
    }
}

internal fun slockComposerImageEditorCaptureSize(
    imageWidth: Int,
    imageHeight: Int,
    maxWidth: Float,
    maxHeight: Float,
    cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
): Size? {
    if (imageWidth <= 0 || imageHeight <= 0 || maxWidth <= 0f || maxHeight <= 0f) return null
    val effectiveWidth = (imageWidth * cropRect.widthFraction).coerceAtLeast(1f)
    val effectiveHeight = (imageHeight * cropRect.heightFraction).coerceAtLeast(1f)
    val scale = min(maxWidth / effectiveWidth, maxHeight / effectiveHeight)
    if (!scale.isFinite() || scale <= 0f) return null
    return Size(width = effectiveWidth * scale, height = effectiveHeight * scale)
}

internal data class SlockComposerImageEditCaptureOwner(
    val actionId: String,
    val attachmentId: String,
    val imageUrl: String?,
    val editMode: Boolean,
)

internal data class SlockComposerImageEditCaptureSlot(
    val owner: SlockComposerImageEditCaptureOwner,
    val ref: ViewRef<DeclarativeBaseView<*, *>>? = null,
) {
    fun bind(
        owner: SlockComposerImageEditCaptureOwner,
        ref: ViewRef<DeclarativeBaseView<*, *>>,
    ): SlockComposerImageEditCaptureSlot =
        if (owner == this.owner) copy(ref = ref) else this
}

@Composable
internal fun SlockFilePreviewComposerImageEditor(
    payload: SlockFilePreviewPayload,
    state: SlockComposerImageEditorState,
    saving: Boolean,
    onCaptureRef: (ViewRef<DeclarativeBaseView<*, *>>) -> Unit,
    onDone: () -> Unit,
    initialTool: SlockComposerImageEditorTool = SlockComposerImageEditorTool.Pencil,
    initialPencilOptionsExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeStrokePoints = remember(payload.imageUrl) { mutableListOf<Offset>() }
    var activeStrokeRevision by remember(payload.imageUrl) { mutableStateOf(0) }
    val activeMosaicTiles = remember(payload.imageUrl) { mutableSetOf<SlockComposerImageMosaicTile>() }
    var activeMosaicRevision by remember(payload.imageUrl) { mutableStateOf(0) }
    var eraseGestureOrigin by remember(payload.imageUrl) { mutableStateOf<SlockComposerImageEditorContent?>(null) }
    var textGestureOrigin by remember(payload.imageUrl) { mutableStateOf<SlockComposerImageEditorContent?>(null) }
    var activeTextAnnotationId by remember(payload.imageUrl) { mutableStateOf<Int?>(null) }
    var textDraft by remember(payload.imageUrl) {
        mutableStateOf(SlockFilePreviewAnnotationDraft(imageUrl = payload.imageUrl))
    }
    var textPosition by remember(payload.imageUrl) { mutableStateOf(Offset(32f, 32f)) }
    var editorCanvasSize by remember(payload.imageUrl) { mutableStateOf(IntSize(0, 0)) }
    val textAnnotationSizes = remember(payload.imageUrl) { mutableStateMapOf<Int, IntSize>() }
    var nextTextAnnotationId by remember(payload.imageUrl) { mutableStateOf(0) }
    var tool by remember(payload.imageUrl, initialTool) { mutableStateOf(initialTool) }
    var pencilMode by remember(payload.imageUrl) { mutableStateOf(SlockComposerImagePencilMode.Ink) }
    var selectedPenColor by remember(payload.imageUrl) { mutableStateOf(SlockComposerImageEditorDefaultPenColor) }
    var pencilOptionsExpanded by remember(payload.imageUrl, initialPencilOptionsExpanded) {
        mutableStateOf(initialPencilOptionsExpanded)
    }
    var keyboardInsetDp by remember(payload.imageUrl) { mutableStateOf(0f) }
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val keyboardInsetExcludesBottomSafeArea = slockKeyboardInsetExcludesBottomSafeArea()
    val mosaicImage = rememberAsyncImageBitmap(payload.imageUrl)
    val focusManager = LocalFocusManager.current
    val editorDensity = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var stageSize by remember(payload.imageUrl) { mutableStateOf(Size.Zero) }
    var transientCropRect by remember(payload.imageUrl) { mutableStateOf(state.history.content.cropRect) }
    var activeCropHandle by remember(payload.imageUrl) { mutableStateOf(SlockCropDragHandle.None) }
    var cropDragOriginContent by remember(payload.imageUrl) { mutableStateOf<SlockComposerImageEditorContent?>(null) }
        fun selectTool(nextTool: SlockComposerImageEditorTool) {
        if (tool == SlockComposerImageEditorTool.Text && nextTool != SlockComposerImageEditorTool.Text) {
            focusManager.clearFocus(force = true)
            keyboardInsetDp = 0f
        }
        if (tool == SlockComposerImageEditorTool.Crop && nextTool != SlockComposerImageEditorTool.Crop) {
            transientCropRect = state.history.content.cropRect
        } else if (nextTool == SlockComposerImageEditorTool.Crop) {
            transientCropRect = state.history.content.cropRect
        }
        pencilOptionsExpanded =
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = tool,
                selectedTool = nextTool,
                currentlyExpanded = pencilOptionsExpanded,
            )
        tool = nextTool
    }
    fun erasedContentAt(point: Offset): SlockComposerImageEditorContent {
        val content = state.history.content
        val nextStrokes = slockComposerImageEraseStrokes(content.strokes, point)
        val shouldClearActiveStroke = slockComposerImageStrokeHit(activeStrokePoints, point)
        val nextMosaicTiles = slockComposerImageEraseMosaicTiles(content.mosaicTiles, point)
        val nextActiveMosaicTiles = slockComposerImageEraseMosaicTiles(activeMosaicTiles, point)
        val nextTexts =
            slockComposerImageEraseTextAnnotations(
                annotations = content.textAnnotations,
                point = point,
                layoutSizes = textAnnotationSizes,
                densityScale = editorDensity.density,
                fontScale = editorDensity.fontScale,
            )
        if (shouldClearActiveStroke) {
            activeStrokePoints.clear()
            activeStrokeRevision += 1
        }
        if (nextActiveMosaicTiles != activeMosaicTiles) {
            activeMosaicTiles.clear()
            activeMosaicTiles.addAll(nextActiveMosaicTiles)
            activeMosaicRevision += 1
        }
        return content
            .copy(
                strokes = nextStrokes,
                mosaicTiles = nextMosaicTiles,
                textAnnotations = nextTexts,
            )
            .slockComposerImageRetainDrawOperations(
                strokes = nextStrokes,
                mosaicTiles = nextMosaicTiles,
            )
    }
    fun eraseAt(point: Offset, recordHistory: Boolean) {
        val nextContent = erasedContentAt(point)
        state.update(
            if (recordHistory) {
                state.history.apply(nextContent)
            } else {
                state.history.replaceWithoutHistory(nextContent)
            }
        )
    }
    fun addTextAnnotation(): Boolean {
        val nextText = textDraft.text.trim()
        if (nextText.isBlank()) return false
        val nextContent =
            slockComposerImageAddTextDraft(
                content = state.history.content,
                draft = nextText,
                position =
                    slockComposerImageClampTextPosition(
                        position = textPosition,
                        annotationSize =
                            slockComposerImageTextFallbackSize(
                                text = nextText,
                                densityScale = editorDensity.density,
                                fontScale = editorDensity.fontScale,
                            ),
                        canvasSize = editorCanvasSize,
                    ),
                annotationId = nextTextAnnotationId,
            )
        state.update(
            state.history.apply(nextContent)
        )
        nextTextAnnotationId += 1
        textDraft = textDraft.cleared()
        return true
    }
    var editorFinishing by remember(payload.imageUrl) { mutableStateOf(false) }
    LaunchedEffect(saving) {
        if (!saving) {
            editorFinishing = false
        }
    }
    fun applyCurrentCrop(): Boolean {
        var applied = false
        if (mosaicImage != null && transientCropRect != state.history.content.cropRect) {
            val nextContent = slockApplyCropToContent(
                content = state.history.content,
                newCrop = transientCropRect,
                imageWidth = mosaicImage.width,
                imageHeight = mosaicImage.height,
                maxWidth = stageSize.width * editorDensity.density,
                maxHeight = stageSize.height * editorDensity.density,
            )
            state.update(state.history.apply(nextContent))
            applied = true
        }
        selectTool(SlockComposerImageEditorTool.Pencil)
        return applied
    }
    fun requestDone() {
        if (editorFinishing || saving) return
        if (tool == SlockComposerImageEditorTool.Crop) {
            editorFinishing = true
            applyCurrentCrop()
            coroutineScope.launch {
                repeat(2) { withFrameNanos { _ -> } }
                onDone()
            }
            return
        }
        if (!addTextAnnotation()) {
            onDone()
            return
        }
        editorFinishing = true
        coroutineScope.launch {
            repeat(2) { withFrameNanos { _ -> } }
            onDone()
        }
    }
    val editorBottomPadding =
        slockFilePreviewEditorBottomPadding(
            keyboardInsetDp = keyboardInsetDp,
            bottomSafeArea = bottomSafeArea,
            keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea,
        )
    Column(modifier = modifier.fillMaxSize()) {
        // The black stage may letterbox for editing, but nativeRef must cover only source-ratio
        // pixels. Otherwise toImage bakes the stage's black bars into the uploaded attachment.
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF000000))
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            val committedContent = state.history.content
            val isCropTool = tool == SlockComposerImageEditorTool.Crop
            val currentCrop = if (isCropTool) SlockComposerImageCropRect.Uncropped else committedContent.cropRect
            val stageWidth = maxWidth.value
            val stageHeight = maxHeight.value
            SideEffect {
                val currentSize = Size(stageWidth, stageHeight)
                if (stageSize != currentSize) {
                    stageSize = currentSize
                }
            }
            val captureSize =
                mosaicImage?.let { image ->
                    slockComposerImageEditorCaptureSize(
                        imageWidth = image.width,
                        imageHeight = image.height,
                        maxWidth = stageWidth,
                        maxHeight = stageHeight,
                        cropRect = currentCrop,
                    )
                }
            if (captureSize != null) {
                Box(
                    modifier =
                        Modifier
                            .width(captureSize.width.dp)
                            .height(captureSize.height.dp)
                            .clipToBounds()
                            .onGloballyPositioned { coordinates -> editorCanvasSize = coordinates.size }
                            .nativeRef { ref -> onCaptureRef(ref) }
                            .pointerInput(payload.imageUrl, tool, pencilMode, selectedPenColor) {
                        if (tool == SlockComposerImageEditorTool.Crop) return@pointerInput
                        detectTapGestures { position ->
                            if (saving || editorFinishing) return@detectTapGestures
                            when (tool) {
                                SlockComposerImageEditorTool.Pencil ->
                                    if (pencilMode == SlockComposerImagePencilMode.Eraser) {
                                        eraseAt(position, recordHistory = true)
                                    } else {
                                        state.update(
                                            state.history.apply(
                                                state.history.content.slockComposerImageAppendStroke(
                                                    SlockComposerImageStroke(
                                                        points = listOf(position, position),
                                                        color = selectedPenColor,
                                                    )
                                                )
                                            )
                                        )
                                    }
                                SlockComposerImageEditorTool.Text -> textPosition = position
                                SlockComposerImageEditorTool.Mosaic -> {
                                    state.update(
                                        slockComposerImageCommitMosaicGesture(
                                            history = state.history,
                                            tiles = slockComposerImageMosaicTilesAt(position),
                                        )
                                    )
                                }
                                SlockComposerImageEditorTool.Crop -> {}
                            }
                        }
                    }
                    .pointerInput(payload.imageUrl, tool, pencilMode, selectedPenColor) {
                        if (tool == SlockComposerImageEditorTool.Text) return@pointerInput
                        var lastMosaicPoint = Offset.Zero
                        detectDragGestures(
                            onDragStart = { start ->
                                if (saving || editorFinishing) return@detectDragGestures
                                when (tool) {
                                    SlockComposerImageEditorTool.Pencil ->
                                        if (pencilMode == SlockComposerImagePencilMode.Ink) {
                                            activeStrokePoints.clear()
                                            activeStrokePoints.add(start)
                                            activeStrokeRevision += 1
                                        } else {
                                            eraseGestureOrigin = state.history.content
                                            eraseAt(start, recordHistory = false)
                                        }
                                    SlockComposerImageEditorTool.Text -> {
                                        activeTextAnnotationId = null
                                        textPosition = start
                                    }
                                    SlockComposerImageEditorTool.Mosaic -> {
                                        activeMosaicTiles.clear()
                                        lastMosaicPoint = start
                                        slockComposerImageAccumulateMosaicTiles(activeMosaicTiles, start)
                                        activeMosaicRevision += 1
                                    }
                                    SlockComposerImageEditorTool.Crop -> {
                                        val canvasSize =
                                            if (editorCanvasSize.width > 0 && editorCanvasSize.height > 0) {
                                                Size(editorCanvasSize.width.toFloat(), editorCanvasSize.height.toFloat())
                                            } else {
                                                Size(captureSize.width * editorDensity.density, captureSize.height * editorDensity.density)
                                            }
                                        val hitRadiusPx = 36f * editorDensity.density
                                        activeCropHandle = slockCropDetectHandle(start, transientCropRect, canvasSize, hitRadius = hitRadiusPx)
                                        cropDragOriginContent = state.history.content
                                    }
                                }
                            },
                            onDragEnd = {
                                when {
                                    tool == SlockComposerImageEditorTool.Pencil &&
                                        pencilMode == SlockComposerImagePencilMode.Ink &&
                                        activeStrokePoints.isNotEmpty() -> {
                                        val committedPoints =
                                            if (activeStrokePoints.size == 1) {
                                                listOf(activeStrokePoints.first(), activeStrokePoints.first())
                                            } else {
                                                activeStrokePoints.toList()
                                            }
                                        state.update(
                                            state.history.apply(
                                                state.history.content.slockComposerImageAppendStroke(
                                                    SlockComposerImageStroke(
                                                        points = committedPoints,
                                                        color = selectedPenColor,
                                                    )
                                                )
                                            )
                                        )
                                    }
                                    tool == SlockComposerImageEditorTool.Pencil &&
                                        pencilMode == SlockComposerImagePencilMode.Eraser ->
                                        eraseGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                    tool == SlockComposerImageEditorTool.Mosaic && activeMosaicTiles.isNotEmpty() ->
                                        state.update(
                                            slockComposerImageCommitMosaicGesture(
                                                history = state.history,
                                                tiles = activeMosaicTiles,
                                            )
                                        )
                                    tool == SlockComposerImageEditorTool.Text && activeTextAnnotationId != null ->
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                    tool == SlockComposerImageEditorTool.Crop -> {
                                        activeCropHandle = SlockCropDragHandle.None
                                        cropDragOriginContent = null
                                    }
                                }
                                eraseGestureOrigin = null
                                textGestureOrigin = null
                                activeTextAnnotationId = null
                                activeCropHandle = SlockCropDragHandle.None
                                cropDragOriginContent = null
                                activeStrokePoints.clear()
                                activeStrokeRevision += 1
                                if (activeMosaicTiles.isNotEmpty()) {
                                    activeMosaicTiles.clear()
                                    activeMosaicRevision += 1
                                }
                            },
                            onDragCancel = {
                                eraseGestureOrigin?.let { origin ->
                                    state.update(state.history.replaceWithoutHistory(origin))
                                }
                                textGestureOrigin?.let { origin ->
                                    state.update(state.history.replaceWithoutHistory(origin))
                                }
                                eraseGestureOrigin = null
                                textGestureOrigin = null
                                activeTextAnnotationId = null
                                activeCropHandle = SlockCropDragHandle.None
                                cropDragOriginContent = null
                                activeStrokePoints.clear()
                                activeStrokeRevision += 1
                                if (activeMosaicTiles.isNotEmpty()) {
                                    activeMosaicTiles.clear()
                                    activeMosaicRevision += 1
                                }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            if (saving || editorFinishing) return@detectDragGestures
                            when (tool) {
                                SlockComposerImageEditorTool.Pencil ->
                                    if (pencilMode == SlockComposerImagePencilMode.Ink) {
                                        activeStrokePoints.add(change.position)
                                        activeStrokeRevision += 1
                                    } else {
                                        eraseAt(change.position, recordHistory = false)
                                    }
                                SlockComposerImageEditorTool.Text -> textPosition = change.position
                                SlockComposerImageEditorTool.Mosaic -> {
                                    val changed =
                                        slockComposerImageAccumulateMosaicTilesAlongLine(
                                            target = activeMosaicTiles,
                                            start = lastMosaicPoint,
                                            end = change.position,
                                        )
                                    lastMosaicPoint = change.position
                                    if (changed) {
                                        activeMosaicRevision += 1
                                    }
                                }
                                SlockComposerImageEditorTool.Crop -> {
                                    if (activeCropHandle != SlockCropDragHandle.None) {
                                        val canvasSize =
                                            if (editorCanvasSize.width > 0 && editorCanvasSize.height > 0) {
                                                Size(editorCanvasSize.width.toFloat(), editorCanvasSize.height.toFloat())
                                            } else {
                                                Size(captureSize.width * editorDensity.density, captureSize.height * editorDensity.density)
                                            }
                                        transientCropRect = slockCropApplyDrag(
                                            handle = activeCropHandle,
                                            cropRect = transientCropRect,
                                            dragAmount = dragAmount,
                                            canvasSize = canvasSize,
                                        )
                                    }
                                }
                            }
                        }
                            },
                    contentAlignment = Alignment.Center
                ) {
            val committedContent = state.history.content
            key(committedContent, currentCrop) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Kuikly's native Canvas is retained across ordinary snapshot invalidations.
                    // Key the committed surface by immutable editor content so Undo/Redo disposes
                    // the old native node, starts from a blank surface, and replays only the current
                    // operation stack. This mirrors Android View.invalidate() + onDraw() replay.
                    drawSlockComposerBackgroundImage(
                        image = mosaicImage,
                        cropRect = currentCrop,
                    )
                    if (tool != SlockComposerImageEditorTool.Crop) {
                        committedContent.slockComposerImageResolvedDrawOperations().forEach { operation ->
                            when (operation) {
                                is SlockComposerImageDrawOperation.Mosaic ->
                                    drawSlockComposerImageMosaic(
                                        image = mosaicImage,
                                        tiles = operation.tiles,
                                        cropRect = currentCrop,
                                    )
                                is SlockComposerImageDrawOperation.Pencil ->
                                    operation.stroke.points.windowed(2).forEach { segment ->
                                        drawLine(
                                            color = operation.stroke.color,
                                            start = segment[0],
                                            end = segment[1],
                                            strokeWidth = 6f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            if (tool != SlockComposerImageEditorTool.Crop) {
                val activeMosaicSnapshot =
                    slockComposerImageActiveMosaicTiles(
                        touchedTiles = activeMosaicTiles,
                        committedTiles = state.history.content.mosaicTiles,
                    )
                val activeStrokeSnapshot = activeStrokePoints.toList()
                key(activeMosaicRevision, activeStrokeRevision, selectedPenColor, currentCrop) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawSlockComposerImageMosaic(
                            image = mosaicImage,
                            tiles = activeMosaicSnapshot,
                            cropRect = currentCrop,
                        )
                        activeStrokeSnapshot.windowed(2).forEach { segment ->
                            drawLine(
                                color = selectedPenColor,
                                start = segment[0],
                                end = segment[1],
                                strokeWidth = 6f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                state.history.content.textAnnotations.forEach { annotation ->
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    x = annotation.position.x.roundToInt(),
                                    y = annotation.position.y.roundToInt(),
                                )
                            }
                            .widthIn(min = 48.dp, max = 260.dp)
                            .onGloballyPositioned { coordinates ->
                                val actualSize = coordinates.size
                                textAnnotationSizes[annotation.id] = actualSize
                                val clampedPosition =
                                    slockComposerImageClampTextPosition(
                                        position = annotation.position,
                                        annotationSize = actualSize,
                                        canvasSize = editorCanvasSize,
                                    )
                                if (clampedPosition != annotation.position) {
                                    state.update(
                                        state.history.replaceWithoutHistory(
                                            slockComposerImageMoveTextAnnotation(
                                                content = state.history.content,
                                                annotationId = annotation.id,
                                                position = clampedPosition,
                                            )
                                        )
                                    )
                                }
                            }
                            .pointerInput(payload.imageUrl, annotation.id, tool) {
                                if (tool != SlockComposerImageEditorTool.Text) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        textGestureOrigin = state.history.content
                                        activeTextAnnotationId = annotation.id
                                    },
                                    onDragEnd = {
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                        textGestureOrigin = null
                                        activeTextAnnotationId = null
                                    },
                                    onDragCancel = {
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.replaceWithoutHistory(origin))
                                        }
                                        textGestureOrigin = null
                                        activeTextAnnotationId = null
                                    },
                                ) { _, dragAmount ->
                                    val annotationId = activeTextAnnotationId ?: return@detectDragGestures
                                    val current =
                                        state.history.content.textAnnotations.firstOrNull { it.id == annotationId }
                                            ?: return@detectDragGestures
                                    val nextPosition = current.position + dragAmount
                                    state.update(
                                        state.history.replaceWithoutHistory(
                                            slockComposerImageMoveTextAnnotation(
                                                content = state.history.content,
                                                annotationId = annotationId,
                                                position = slockComposerImageClampTextPosition(
                                                    position = nextPosition,
                                                    annotationSize =
                                                        textAnnotationSizes[annotationId]
                                                            ?: slockComposerImageTextFallbackSize(
                                                                text = current.text,
                                                                densityScale = editorDensity.density,
                                                                fontScale = editorDensity.fontScale,
                                                            ),
                                                    canvasSize = editorCanvasSize,
                                                ),
                                            )
                                        )
                                    )
                                }
                            }
                            .background(Color(0xCCFFFFFF))
                            .border(1.dp, SlockColor.BorderBlack)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = annotation.text,
                        style = SlockType.ButtonBold.copy(fontSize = 20.sp, lineHeight = 24.sp, color = Color(0xFFFF3B30)),
                        color = Color(0xFFFF3B30),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                    }
            }
                    if (tool == SlockComposerImageEditorTool.Crop) {
                        key(transientCropRect) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawSlockCropOverlay(
                                    cropRect = transientCropRect,
                                    canvasSize = Size(size.width, size.height),
                                    density = editorDensity.density,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (tool == SlockComposerImageEditorTool.Pencil && pencilOptionsExpanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = true,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                SlockFilePreviewPencilOptions(
                    mode = pencilMode,
                    selectedColor = selectedPenColor,
                    enabled = !saving && !editorFinishing,
                    onSelectEraser = { pencilMode = SlockComposerImagePencilMode.Eraser },
                    onSelectColor = { color ->
                        selectedPenColor = color
                        pencilMode = SlockComposerImagePencilMode.Ink
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                top = 6.dp,
                                end = SlockSpacing.Sm,
                                bottom = SlockSpacing.Sm,
                            ),
                )
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving || editorFinishing,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        } else if (tool == SlockComposerImageEditorTool.Text) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = true,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                end = SlockSpacing.Sm,
                                bottom = SlockSpacing.Sm,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = textDraft.text,
                        onValueChange = { nextText ->
                            textDraft = textDraft.updated(payload.imageUrl, nextText)
                        },
                        placeholder = stringResource(MR.strings.file_preview_placeholder_tap_to_add_text),
                        placeholderColor = SlockColor.TextMutedGray,
                        autoFocus = true,
                        keyboardHeightChange = { params ->
                            keyboardInsetDp = params.height.coerceAtLeast(0f)
                        },
                        maxLines = 1,
                        textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .background(SlockColor.SurfaceCream)
                                .border(SlockStroke.Default, SlockColor.BorderBlack)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Check,
                        contentDescription = stringResource(MR.strings.common_add),
                        onClick = { addTextAnnotation() },
                        enabled = !saving && !editorFinishing && textDraft.text.isNotBlank(),
                        backgroundColor = SlockColor.BrandYellow,
                        shadowOffset = 2.dp
                    )
                    Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
                }
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving || editorFinishing,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        } else if (tool == SlockComposerImageEditorTool.Crop) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = true,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                SlockFilePreviewCropOptions(
                    canReset = !transientCropRect.isUncropped || !state.history.content.cropRect.isUncropped,
                    enabled = !saving && !editorFinishing,
                    onReset = {
                        transientCropRect = SlockComposerImageCropRect.Uncropped
                        if (mosaicImage != null) {
                            val nextContent = slockApplyCropToContent(
                                content = state.history.content,
                                newCrop = SlockComposerImageCropRect.Uncropped,
                                imageWidth = mosaicImage.width,
                                imageHeight = mosaicImage.height,
                                maxWidth = stageSize.width * editorDensity.density,
                                maxHeight = stageSize.height * editorDensity.density,
                            )
                            state.update(state.history.apply(nextContent))
                        }
                    },
                    onApply = {
                        applyCurrentCrop()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                top = 6.dp,
                                end = SlockSpacing.Sm,
                                bottom = SlockSpacing.Sm,
                            ),
                )
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving || editorFinishing,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = false,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving || editorFinishing,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        }
    }
}

@Composable
private fun SlockFilePreviewImageEditorPrimaryControls(
    tool: SlockComposerImageEditorTool,
    saving: Boolean,
    imageAvailable: Boolean,
    onSelectTool: (SlockComposerImageEditorTool) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SlockSpacing.Sm,
                    top = 6.dp,
                    end = SlockSpacing.Sm,
                ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_pen),
            icon = SlockLucideIconName.Pencil,
            selected = tool == SlockComposerImageEditorTool.Pencil,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Pencil) }
        )
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_text),
            icon = SlockLucideIconName.Type,
            selected = tool == SlockComposerImageEditorTool.Text,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Text) }
        )
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_mosaic),
            icon = SlockLucideIconName.Mosaic,
            selected = tool == SlockComposerImageEditorTool.Mosaic,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Mosaic) }
        )
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_crop),
            icon = SlockLucideIconName.Crop,
            selected = tool == SlockComposerImageEditorTool.Crop,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Crop) }
        )
        Box(modifier = Modifier.weight(1f))
        SlockFilePreviewImageEditorDoneButton(
            saving = saving,
            imageAvailable = imageAvailable,
            onDone = onDone,
        )
        Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
    }
}

@Composable
private fun SlockFilePreviewPencilOptions(
    mode: SlockComposerImagePencilMode,
    selectedColor: Color,
    enabled: Boolean,
    onSelectEraser: () -> Unit,
    onSelectColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val penLabel = stringResource(MR.strings.file_preview_toolbar_pen)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Eraser,
            label = stringResource(MR.strings.file_preview_toolbar_erase),
            onClick = onSelectEraser,
            enabled = enabled,
            iconColor = slockFilePreviewEditorToolIconColor(mode == SlockComposerImagePencilMode.Eraser),
            size = SlockSize.IconButton,
            iconSize = 22.dp,
        )
        Box(
            modifier =
                Modifier
                    .size(width = 1.dp, height = 28.dp)
                    .background(SlockColor.TextMutedGray.copy(alpha = 0.45f))
        )
        SlockComposerImageEditorPenColors.forEachIndexed { index, color ->
            SlockFilePreviewColorSwatch(
                color = color,
                selected = mode == SlockComposerImagePencilMode.Ink && selectedColor == color,
                enabled = enabled,
                label = "$penLabel ${index + 1}",
                onClick = { onSelectColor(color) },
            )
        }
    }
}

@Composable
private fun SlockFilePreviewColorSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = false,
        pressOffset = 0.dp,
        modifier =
            Modifier
                .size(28.dp)
                .semantics { contentDescription = label },
    ) {
        Box(
            modifier =
                Modifier
                    .size(if (selected) 26.dp else 22.dp)
                    .borderRadius(20.dp)
                    .background(color)
                    .border(
                        width = if (selected) SlockStroke.Strong else SlockStroke.Thin,
                        color = SlockColor.BorderBlack,
                    )
        )
    }
}

@Composable
private fun SlockFilePreviewCropOptions(
    canReset: Boolean,
    enabled: Boolean,
    onReset: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlockBrutalActionButton(
            label = stringResource(MR.strings.file_preview_crop_reset),
            onClick = onReset,
            enabled = enabled && canReset,
            backgroundColor = SlockColor.SurfaceWhite,
            minWidth = 0.dp,
            minHeight = 32.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp),
            modifier = Modifier.weight(1f),
        )
        SlockBrutalActionButton(
            label = stringResource(MR.strings.file_preview_crop_apply),
            onClick = onApply,
            enabled = enabled,
            backgroundColor = SlockColor.BrandYellow,
            minWidth = 0.dp,
            minHeight = 32.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlockFilePreviewEditorToolButton(
    label: String,
    icon: SlockLucideIconName,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressOffset = 0.dp,
        modifier =
            modifier
                .padding(horizontal = 2.dp)
                .semantics { contentDescription = label },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            SlockLucideIcon(
                name = icon,
                color = if (enabled) slockFilePreviewEditorToolIconColor(selected = selected) else SlockColor.TextMutedGrayArgb,
                modifier = Modifier.size(20.dp),
            )
            Box(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (enabled) {
                    if (selected) SlockColor.TextBlack else SlockColor.TextMutedGray
                } else {
                    SlockColor.TextMutedGray
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SlockFilePreviewBorderlessIconButton(
    icon: SlockLucideIconName,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    iconColor: String = SlockColor.TextBlackArgb,
    size: com.tencent.kuikly.compose.ui.unit.Dp = SlockSize.IconButton,
    iconSize: com.tencent.kuikly.compose.ui.unit.Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressOffset = 0.dp,
        modifier =
            modifier
                .size(size)
                .semantics { contentDescription = label },
    ) {
        SlockLucideIcon(
            name = icon,
            color = if (enabled) iconColor else SlockColor.TextMutedGrayArgb,
            modifier = Modifier.size(iconSize),
        )
    }
}

internal fun slockFilePreviewEditorToolIconColor(selected: Boolean): String =
    if (selected) SlockColor.AccentRedArgb else SlockColor.TextBlackArgb

internal fun slockFilePreviewPencilOptionsExpandedAfterToolSelection(
    currentTool: SlockComposerImageEditorTool,
    selectedTool: SlockComposerImageEditorTool,
    currentlyExpanded: Boolean,
): Boolean =
    when (selectedTool) {
        SlockComposerImageEditorTool.Pencil ->
            if (currentTool == SlockComposerImageEditorTool.Pencil) {
                !currentlyExpanded
            } else {
                true
            }
        SlockComposerImageEditorTool.Text,
        SlockComposerImageEditorTool.Mosaic,
        SlockComposerImageEditorTool.Crop -> false
    }

internal fun slockFilePreviewEditorControlsHeight(
    secondaryControlsVisible: Boolean,
    bottomPadding: com.tencent.kuikly.compose.ui.unit.Dp,
): com.tencent.kuikly.compose.ui.unit.Dp =
    6.dp +
        bottomPadding +
        SlockSize.IconButton +
        if (secondaryControlsVisible) SlockSpacing.Sm + SlockSize.IconButton else 0.dp

internal fun slockFilePreviewEditorBottomPadding(
    keyboardInsetDp: Float,
    bottomSafeArea: com.tencent.kuikly.compose.ui.unit.Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean,
): com.tencent.kuikly.compose.ui.unit.Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    val safeAreaInset =
        if (keyboardInsetDp > 0f && !keyboardInsetExcludesBottomSafeArea) {
            0.dp
        } else {
            bottomSafeArea
        }
    return 6.dp + keyboardInset + safeAreaInset
}

internal fun slockComposerImageTextFallbackSize(
    text: String,
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): IntSize {
    val charactersPerLine = 18
    val lineCount = ceil(text.length.coerceAtLeast(1) / charactersPerLine.toFloat()).toInt().coerceIn(1, 3)
    val safeDensity = densityScale.coerceAtLeast(0.1f)
    val safeFontScale = fontScale.coerceAtLeast(0.1f)
    val widthDp =
        if (text.length > charactersPerLine) {
            260f
        } else {
            (text.length.coerceAtLeast(1) * 13f + 18f).coerceIn(48f, 260f)
        }
    val heightPx = lineCount * 24f * safeDensity * safeFontScale + 10f * safeDensity
    return IntSize(
        width = (widthDp * safeDensity).roundToInt(),
        height = heightPx.roundToInt(),
    )
}

internal fun SlockComposerImageTextAnnotation.hitTest(
    point: Offset,
    layoutSize: IntSize = slockComposerImageTextFallbackSize(text),
): Boolean {
    val width = layoutSize.width.toFloat()
    val height = layoutSize.height.toFloat()
    return point.x >= position.x - 10f &&
        point.x <= position.x + width &&
        point.y >= position.y - 10f &&
        point.y <= position.y + height
}

internal fun slockComposerImageTopmostTextAnnotationAt(
    annotations: List<SlockComposerImageTextAnnotation>,
    point: Offset,
    layoutSizes: Map<Int, IntSize> = emptyMap(),
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): SlockComposerImageTextAnnotation? =
    annotations.lastOrNull { annotation ->
        annotation.hitTest(
            point = point,
            layoutSize =
                layoutSizes[annotation.id]
                    ?: slockComposerImageTextFallbackSize(
                        text = annotation.text,
                        densityScale = densityScale,
                        fontScale = fontScale,
                    ),
        )
    }

internal fun slockComposerImageEraseTextAnnotations(
    annotations: List<SlockComposerImageTextAnnotation>,
    point: Offset,
    layoutSizes: Map<Int, IntSize> = emptyMap(),
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): List<SlockComposerImageTextAnnotation> =
    annotations.filterNot { annotation ->
        annotation.hitTest(
            point = point,
            layoutSize =
                layoutSizes[annotation.id]
                    ?: slockComposerImageTextFallbackSize(
                        text = annotation.text,
                        densityScale = densityScale,
                        fontScale = fontScale,
                    ),
        )
    }

internal fun slockComposerImageClampTextPosition(
    position: Offset,
    annotationSize: IntSize,
    canvasSize: IntSize,
): Offset {
    val maxX =
        if (canvasSize.width > 0) {
            (canvasSize.width - annotationSize.width).coerceAtLeast(0).toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
    val maxY =
        if (canvasSize.height > 0) {
            (canvasSize.height - annotationSize.height).coerceAtLeast(0).toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
    return Offset(
        x = position.x.coerceIn(0f, maxX),
        y = position.y.coerceIn(0f, maxY),
    )
}

internal fun slockComposerImageMoveTextAnnotation(
    content: SlockComposerImageEditorContent,
    annotationId: Int,
    position: Offset,
): SlockComposerImageEditorContent {
    var changed = false
    val nextAnnotations =
        content.textAnnotations.map { annotation ->
            if (annotation.id == annotationId && annotation.position != position) {
                changed = true
                annotation.copy(position = position)
            } else {
                annotation
            }
        }
    return if (changed) content.copy(textAnnotations = nextAnnotations) else content
}

internal fun slockComposerImageAddTextDraft(
    content: SlockComposerImageEditorContent,
    draft: String,
    position: Offset,
    annotationId: Int,
): SlockComposerImageEditorContent {
    val text = draft.trim()
    if (text.isEmpty()) return content
    return content.copy(
        textAnnotations =
            content.textAnnotations +
                SlockComposerImageTextAnnotation(
                    id = annotationId,
                    text = text,
                    position = position,
                )
    )
}

internal fun slockComposerImageEraseStrokes(
    strokes: List<SlockComposerImageStroke>,
    point: Offset,
): List<SlockComposerImageStroke> =
    strokes.filterNot { stroke -> slockComposerImageStrokeHit(stroke.points, point) }

internal fun slockComposerImageMosaicTilesAt(
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    brushRadius: Int = SlockComposerImageMosaicBrushRadius,
): Set<SlockComposerImageMosaicTile> {
    val tiles = mutableSetOf<SlockComposerImageMosaicTile>()
    slockComposerImageAccumulateMosaicTiles(
        target = tiles,
        point = point,
        tileSizePx = tileSizePx,
        brushRadius = brushRadius,
    )
    return tiles
}

internal fun slockComposerImageCommitMosaicGesture(
    history: SlockComposerImageEditorHistory,
    tiles: Collection<SlockComposerImageMosaicTile>,
): SlockComposerImageEditorHistory {
    if (tiles.isEmpty()) return history
    val committedTiles = tiles.toSet() - history.content.mosaicTiles
    if (committedTiles.isEmpty()) return history
    val nextTiles = history.content.mosaicTiles + committedTiles
    return history.apply(
        history.content.copy(
            mosaicTiles = nextTiles,
            drawOperations =
                history.content.slockComposerImageResolvedDrawOperations() +
                    SlockComposerImageDrawOperation.Mosaic(committedTiles),
        )
    )
}

internal fun slockComposerImageActiveMosaicTiles(
    touchedTiles: Set<SlockComposerImageMosaicTile>,
    committedTiles: Set<SlockComposerImageMosaicTile>,
): Set<SlockComposerImageMosaicTile> = touchedTiles - committedTiles

internal fun SlockComposerImageEditorContent.slockComposerImageAppendStroke(
    stroke: SlockComposerImageStroke,
): SlockComposerImageEditorContent =
    copy(
        strokes = strokes + stroke,
        drawOperations =
            slockComposerImageResolvedDrawOperations() +
                SlockComposerImageDrawOperation.Pencil(stroke),
    )

internal fun SlockComposerImageEditorContent.slockComposerImageResolvedDrawOperations():
    List<SlockComposerImageDrawOperation> =
    if (drawOperations.isNotEmpty()) {
        drawOperations
    } else {
        buildList {
            if (mosaicTiles.isNotEmpty()) add(SlockComposerImageDrawOperation.Mosaic(mosaicTiles))
            strokes.forEach { add(SlockComposerImageDrawOperation.Pencil(it)) }
        }
    }

internal fun SlockComposerImageEditorContent.slockComposerImageRetainDrawOperations(
    strokes: List<SlockComposerImageStroke>,
    mosaicTiles: Set<SlockComposerImageMosaicTile>,
): SlockComposerImageEditorContent {
    val retained =
        slockComposerImageResolvedDrawOperations().mapNotNull { operation ->
            when (operation) {
                is SlockComposerImageDrawOperation.Mosaic ->
                    (operation.tiles intersect mosaicTiles)
                        .takeIf { it.isNotEmpty() }
                        ?.let(SlockComposerImageDrawOperation::Mosaic)
                is SlockComposerImageDrawOperation.Pencil ->
                    operation.takeIf { operation.stroke in strokes }
            }
        }
    return copy(drawOperations = retained)
}

internal fun slockComposerImageAccumulateMosaicTiles(
    target: MutableSet<SlockComposerImageMosaicTile>,
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    brushRadius: Int = SlockComposerImageMosaicBrushRadius,
): Boolean {
    if (tileSizePx <= 0f || brushRadius < 0) return false
    val centerColumn = floor(point.x / tileSizePx).toInt()
    val centerRow = floor(point.y / tileSizePx).toInt()
    var changed = false
    for (column in (centerColumn - brushRadius)..(centerColumn + brushRadius)) {
        for (row in (centerRow - brushRadius)..(centerRow + brushRadius)) {
            if (column >= 0 && row >= 0) {
                changed = target.add(SlockComposerImageMosaicTile(column = column, row = row)) || changed
            }
        }
    }
    return changed
}

internal fun slockComposerImageAccumulateMosaicTilesAlongLine(
    target: MutableSet<SlockComposerImageMosaicTile>,
    start: Offset,
    end: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    brushRadius: Int = SlockComposerImageMosaicBrushRadius,
): Boolean {
    if (tileSizePx <= 0f || brushRadius < 0) return false
    val dx = end.x - start.x
    val dy = end.y - start.y
    val distance = sqrt(dx * dx + dy * dy)
    val step = (tileSizePx * 0.5f).coerceAtLeast(4f)
    var changed = slockComposerImageAccumulateMosaicTiles(target, start, tileSizePx, brushRadius)
    if (distance > 0f) {
        val steps = ceil(distance / step).toInt()
        for (i in 1..steps) {
            val t = i.toFloat() / steps.toFloat()
            val point = Offset(start.x + dx * t, start.y + dy * t)
            changed = slockComposerImageAccumulateMosaicTiles(target, point, tileSizePx, brushRadius) || changed
        }
    }
    return changed
}

internal fun slockComposerImageMosaicSample(
    tile: SlockComposerImageMosaicTile,
    imageWidth: Int,
    imageHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
): SlockComposerImageMosaicSample? {
    if (
        imageWidth <= 0 ||
        imageHeight <= 0 ||
        canvasWidth <= 0f ||
        canvasHeight <= 0f ||
        tileSizePx <= 0f
    ) {
        return null
    }
    val effectiveWidth = (imageWidth * cropRect.widthFraction).coerceAtLeast(1f)
    val effectiveHeight = (imageHeight * cropRect.heightFraction).coerceAtLeast(1f)
    val scale = min(canvasWidth / effectiveWidth, canvasHeight / effectiveHeight)
    if (!scale.isFinite() || scale <= 0f) return null
    val renderedWidth = effectiveWidth * scale
    val renderedHeight = effectiveHeight * scale
    val renderedLeft = (canvasWidth - renderedWidth) / 2f
    val renderedTop = (canvasHeight - renderedHeight) / 2f
    val tileLeft = tile.column * tileSizePx
    val tileTop = tile.row * tileSizePx
    val destinationLeft = maxOf(tileLeft, renderedLeft)
    val destinationTop = maxOf(tileTop, renderedTop)
    val destinationRight = minOf(tileLeft + tileSizePx + 0.5f, renderedLeft + renderedWidth)
    val destinationBottom = minOf(tileTop + tileSizePx + 0.5f, renderedTop + renderedHeight)
    if (destinationRight <= destinationLeft || destinationBottom <= destinationTop) return null
    val sampleX =
        (((destinationLeft + destinationRight) / 2f - renderedLeft) / scale + cropRect.left * imageWidth)
            .toInt()
            .coerceIn(0, imageWidth - 1)
    val sampleY =
        (((destinationTop + destinationBottom) / 2f - renderedTop) / scale + cropRect.top * imageHeight)
            .toInt()
            .coerceIn(0, imageHeight - 1)
    val destinationX = floor(destinationLeft).toInt()
    val destinationY = floor(destinationTop).toInt()
    return SlockComposerImageMosaicSample(
        sourceX = sampleX,
        sourceY = sampleY,
        destinationX = destinationX,
        destinationY = destinationY,
        destinationWidth = (ceil(destinationRight).toInt() - destinationX).coerceAtLeast(1),
        destinationHeight = (ceil(destinationBottom).toInt() - destinationY).coerceAtLeast(1),
    )
}

internal fun slockComposerBackgroundImageSrcRect(
    imageWidth: Int,
    imageHeight: Int,
    cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
): Pair<IntOffset, IntSize> {
    if (imageWidth <= 0 || imageHeight <= 0) {
        return Pair(IntOffset.Zero, IntSize.Zero)
    }
    val srcX = (cropRect.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
    val srcY = (cropRect.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
    val srcRight = (cropRect.right * imageWidth).roundToInt().coerceIn(srcX + 1, imageWidth)
    val srcBottom = (cropRect.bottom * imageHeight).roundToInt().coerceIn(srcY + 1, imageHeight)
    val srcWidth = (srcRight - srcX).coerceAtLeast(1)
    val srcHeight = (srcBottom - srcY).coerceAtLeast(1)
    return Pair(IntOffset(srcX, srcY), IntSize(srcWidth, srcHeight))
}

internal fun DrawScope.drawSlockComposerBackgroundImage(
    image: ImageBitmap?,
    cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
) {
    val readyImage = image?.takeIf { it.width > 0 && it.height > 0 } ?: return
    val (srcOffset, srcSize) =
        slockComposerBackgroundImageSrcRect(
            imageWidth = readyImage.width,
            imageHeight = readyImage.height,
            cropRect = cropRect,
        )
    if (srcSize.width <= 0 || srcSize.height <= 0) return
    val dstWidth = size.width.roundToInt().coerceAtLeast(1)
    val dstHeight = size.height.roundToInt().coerceAtLeast(1)
    drawImage(
        image = readyImage,
        srcOffset = srcOffset,
        srcSize = srcSize,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(dstWidth, dstHeight),
    )
}

private fun DrawScope.drawSlockComposerImageMosaic(
    image: ImageBitmap?,
    tiles: Collection<SlockComposerImageMosaicTile>,
    cropRect: SlockComposerImageCropRect = SlockComposerImageCropRect.Uncropped,
) {
    val readyImage = image?.takeIf { it.width > 0 && it.height > 0 } ?: return
    tiles.forEach { tile ->
        val sample =
            slockComposerImageMosaicSample(
                tile = tile,
                imageWidth = readyImage.width,
                imageHeight = readyImage.height,
                canvasWidth = size.width,
                canvasHeight = size.height,
                cropRect = cropRect,
            ) ?: return@forEach
        drawImage(
            image = readyImage,
            srcOffset = IntOffset(sample.sourceX, sample.sourceY),
            srcSize = IntSize(1, 1),
            dstOffset = IntOffset(sample.destinationX, sample.destinationY),
            dstSize = IntSize(sample.destinationWidth, sample.destinationHeight),
        )
    }
}

internal fun slockComposerImageEraseMosaicTiles(
    tiles: Set<SlockComposerImageMosaicTile>,
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
): Set<SlockComposerImageMosaicTile> {
    if (tiles.isEmpty() || tileSizePx <= 0f) return tiles
    val thresholdSquared = 24f * 24f
    return tiles.filterTo(mutableSetOf()) { tile ->
        val center =
            Offset(
                x = (tile.column + 0.5f) * tileSizePx,
                y = (tile.row + 0.5f) * tileSizePx,
            )
        center.slockDistanceSquaredTo(point) > thresholdSquared
    }
}

internal fun slockComposerImageStrokeHit(stroke: List<Offset>, point: Offset): Boolean {
    if (stroke.isEmpty()) return false
    val thresholdSquared = 22f * 22f
    return stroke.any { it.slockDistanceSquaredTo(point) <= thresholdSquared } ||
        stroke.windowed(2).any { segment ->
            slockDistanceToSegmentSquared(point = point, start = segment[0], end = segment[1]) <= thresholdSquared
        }
}

private fun Offset.slockDistanceSquaredTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy
}

private fun slockDistanceToSegmentSquared(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return point.slockDistanceSquaredTo(start)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + t * dx, start.y + t * dy)
    return point.slockDistanceSquaredTo(projection)
}

internal enum class SlockCropDragHandle {
    TopLeft,
    Top,
    TopRight,
    Right,
    BottomRight,
    Bottom,
    BottomLeft,
    Left,
    Center,
    None,
}

internal fun slockCropDetectHandle(
    point: Offset,
    cropRect: SlockComposerImageCropRect,
    canvasSize: Size,
    hitRadius: Float = 36f,
): SlockCropDragHandle {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return SlockCropDragHandle.None
    val rectLeft = cropRect.left * canvasSize.width
    val rectTop = cropRect.top * canvasSize.height
    val rectRight = cropRect.right * canvasSize.width
    val rectBottom = cropRect.bottom * canvasSize.height

    fun distanceSquared(cx: Float, cy: Float): Float {
        val dx = point.x - cx
        val dy = point.y - cy
        return dx * dx + dy * dy
    }
    val effectiveHitRadius = minOf(hitRadius, (rectRight - rectLeft) / 3f, (rectBottom - rectTop) / 3f).coerceAtLeast(8f)
    val r2 = effectiveHitRadius * effectiveHitRadius

    // Check corners first (highest priority)
    if (distanceSquared(rectLeft, rectTop) <= r2) return SlockCropDragHandle.TopLeft
    if (distanceSquared(rectRight, rectTop) <= r2) return SlockCropDragHandle.TopRight
    if (distanceSquared(rectLeft, rectBottom) <= r2) return SlockCropDragHandle.BottomLeft
    if (distanceSquared(rectRight, rectBottom) <= r2) return SlockCropDragHandle.BottomRight

    // Check edges
    if (point.x in (rectLeft + effectiveHitRadius)..(rectRight - effectiveHitRadius) &&
        abs(point.y - rectTop) <= effectiveHitRadius) return SlockCropDragHandle.Top
    if (point.x in (rectLeft + effectiveHitRadius)..(rectRight - effectiveHitRadius) &&
        abs(point.y - rectBottom) <= effectiveHitRadius) return SlockCropDragHandle.Bottom
    if (point.y in (rectTop + effectiveHitRadius)..(rectBottom - effectiveHitRadius) &&
        abs(point.x - rectLeft) <= effectiveHitRadius) return SlockCropDragHandle.Left
    if (point.y in (rectTop + effectiveHitRadius)..(rectBottom - effectiveHitRadius) &&
        abs(point.x - rectRight) <= effectiveHitRadius) return SlockCropDragHandle.Right

    // Check center (moving whole box)
    if (point.x >= rectLeft && point.x <= rectRight && point.y >= rectTop && point.y <= rectBottom) {
        return SlockCropDragHandle.Center
    }

    return SlockCropDragHandle.None
}

internal fun slockCropApplyDrag(
    handle: SlockCropDragHandle,
    cropRect: SlockComposerImageCropRect,
    dragAmount: Offset,
    canvasSize: Size,
    minDimensionFraction: Float = 0.1f,
): SlockComposerImageCropRect {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f || handle == SlockCropDragHandle.None) {
        return cropRect
    }
    val dLeft = dragAmount.x / canvasSize.width
    val dTop = dragAmount.y / canvasSize.height

    var newLeft = cropRect.left
    var newTop = cropRect.top
    var newRight = cropRect.right
    var newBottom = cropRect.bottom

    when (handle) {
        SlockCropDragHandle.TopLeft -> {
            newLeft = (cropRect.left + dLeft).coerceIn(0f, cropRect.right - minDimensionFraction)
            newTop = (cropRect.top + dTop).coerceIn(0f, cropRect.bottom - minDimensionFraction)
        }
        SlockCropDragHandle.Top -> {
            newTop = (cropRect.top + dTop).coerceIn(0f, cropRect.bottom - minDimensionFraction)
        }
        SlockCropDragHandle.TopRight -> {
            newRight = (cropRect.right + dLeft).coerceIn(cropRect.left + minDimensionFraction, 1f)
            newTop = (cropRect.top + dTop).coerceIn(0f, cropRect.bottom - minDimensionFraction)
        }
        SlockCropDragHandle.Right -> {
            newRight = (cropRect.right + dLeft).coerceIn(cropRect.left + minDimensionFraction, 1f)
        }
        SlockCropDragHandle.BottomRight -> {
            newRight = (cropRect.right + dLeft).coerceIn(cropRect.left + minDimensionFraction, 1f)
            newBottom = (cropRect.bottom + dTop).coerceIn(cropRect.top + minDimensionFraction, 1f)
        }
        SlockCropDragHandle.Bottom -> {
            newBottom = (cropRect.bottom + dTop).coerceIn(cropRect.top + minDimensionFraction, 1f)
        }
        SlockCropDragHandle.BottomLeft -> {
            newLeft = (cropRect.left + dLeft).coerceIn(0f, cropRect.right - minDimensionFraction)
            newBottom = (cropRect.bottom + dTop).coerceIn(cropRect.top + minDimensionFraction, 1f)
        }
        SlockCropDragHandle.Left -> {
            newLeft = (cropRect.left + dLeft).coerceIn(0f, cropRect.right - minDimensionFraction)
        }
        SlockCropDragHandle.Center -> {
            val width = cropRect.right - cropRect.left
            val height = cropRect.bottom - cropRect.top
            val clampedDLeft = dLeft.coerceIn(-cropRect.left, 1f - cropRect.right)
            val clampedDTop = dTop.coerceIn(-cropRect.top, 1f - cropRect.bottom)
            newLeft = cropRect.left + clampedDLeft
            newRight = newLeft + width
            newTop = cropRect.top + clampedDTop
            newBottom = newTop + height
        }
        SlockCropDragHandle.None -> {}
    }

    return SlockComposerImageCropRect(
        left = newLeft.coerceIn(0f, 1f),
        top = newTop.coerceIn(0f, 1f),
        right = newRight.coerceIn(0f, 1f),
        bottom = newBottom.coerceIn(0f, 1f),
    )
}

internal fun slockRemapPoint(
    point: Offset,
    oldCrop: SlockComposerImageCropRect,
    newCrop: SlockComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
): Offset {
    if (oldCanvasSize.width <= 0f || oldCanvasSize.height <= 0f ||
        newCanvasSize.width <= 0f || newCanvasSize.height <= 0f) {
        return point
    }
    val normX = oldCrop.left + (point.x / oldCanvasSize.width) * oldCrop.widthFraction
    val normY = oldCrop.top + (point.y / oldCanvasSize.height) * oldCrop.heightFraction
    val newRelX = (normX - newCrop.left) / newCrop.widthFraction
    val newRelY = (normY - newCrop.top) / newCrop.heightFraction
    return Offset(newRelX * newCanvasSize.width, newRelY * newCanvasSize.height)
}

internal fun slockRemapStroke(
    stroke: SlockComposerImageStroke,
    oldCrop: SlockComposerImageCropRect,
    newCrop: SlockComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
): SlockComposerImageStroke {
    val remappedPoints = stroke.points.map { pt ->
        slockRemapPoint(pt, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
    }
    return stroke.copy(points = remappedPoints)
}

internal fun slockRemapTextAnnotations(
    annotations: List<SlockComposerImageTextAnnotation>,
    oldCrop: SlockComposerImageCropRect,
    newCrop: SlockComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
): List<SlockComposerImageTextAnnotation> =
    annotations.map { ann ->
        ann.copy(position = slockRemapPoint(ann.position, oldCrop, newCrop, oldCanvasSize, newCanvasSize))
    }

internal fun slockRemapMosaicTiles(
    tiles: Set<SlockComposerImageMosaicTile>,
    oldCrop: SlockComposerImageCropRect,
    newCrop: SlockComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
): Set<SlockComposerImageMosaicTile> {
    if (tiles.isEmpty() || oldCanvasSize.width <= 0f || oldCanvasSize.height <= 0f ||
        newCanvasSize.width <= 0f || newCanvasSize.height <= 0f) {
        return tiles
    }
    val remapped = mutableSetOf<SlockComposerImageMosaicTile>()
    tiles.forEach { tile ->
        val p1 = Offset(tile.column * tileSizePx, tile.row * tileSizePx)
        val p2 = Offset((tile.column + 1) * tileSizePx, (tile.row + 1) * tileSizePx)
        val r1 = slockRemapPoint(p1, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
        val r2 = slockRemapPoint(p2, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
        val minX = minOf(r1.x, r2.x)
        val maxX = maxOf(r1.x, r2.x)
        val minY = minOf(r1.y, r2.y)
        val maxY = maxOf(r1.y, r2.y)
        val startCol = floor(minX / tileSizePx).toInt().coerceAtLeast(0)
        val endCol = floor(maxX / tileSizePx).toInt().coerceAtLeast(startCol)
        val startRow = floor(minY / tileSizePx).toInt().coerceAtLeast(0)
        val endRow = floor(maxY / tileSizePx).toInt().coerceAtLeast(startRow)
        for (c in startCol..endCol) {
            for (r in startRow..endRow) {
                remapped.add(SlockComposerImageMosaicTile(c, r))
            }
        }
    }
    return remapped
}

internal fun slockRemapContentForCrop(
    content: SlockComposerImageEditorContent,
    newCrop: SlockComposerImageCropRect,
    oldCanvasSize: Size,
    newCanvasSize: Size,
): SlockComposerImageEditorContent {
    val oldCrop = content.cropRect
    if (oldCrop == newCrop) return content
    val newStrokes = content.strokes.map { slockRemapStroke(it, oldCrop, newCrop, oldCanvasSize, newCanvasSize) }
    val newTexts = slockRemapTextAnnotations(content.textAnnotations, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
    val newTiles = slockRemapMosaicTiles(content.mosaicTiles, oldCrop, newCrop, oldCanvasSize, newCanvasSize)
    val newDrawOps = content.slockComposerImageResolvedDrawOperations().map { op ->
        when (op) {
            is SlockComposerImageDrawOperation.Mosaic ->
                SlockComposerImageDrawOperation.Mosaic(slockRemapMosaicTiles(op.tiles, oldCrop, newCrop, oldCanvasSize, newCanvasSize))
            is SlockComposerImageDrawOperation.Pencil ->
                SlockComposerImageDrawOperation.Pencil(slockRemapStroke(op.stroke, oldCrop, newCrop, oldCanvasSize, newCanvasSize))
        }
    }
    return content.copy(
        cropRect = newCrop,
        strokes = newStrokes,
        textAnnotations = newTexts,
        mosaicTiles = newTiles,
        drawOperations = newDrawOps,
    )
}

internal fun slockApplyCropToContent(
    content: SlockComposerImageEditorContent,
    newCrop: SlockComposerImageCropRect,
    imageWidth: Int,
    imageHeight: Int,
    maxWidth: Float,
    maxHeight: Float,
): SlockComposerImageEditorContent {
    val oldCrop = content.cropRect
    if (oldCrop == newCrop) return content
    val oldCapture = slockComposerImageEditorCaptureSize(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        cropRect = oldCrop,
    ) ?: return content.copy(cropRect = newCrop)
    val newCapture = slockComposerImageEditorCaptureSize(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        cropRect = newCrop,
    ) ?: return content.copy(cropRect = newCrop)
    return slockRemapContentForCrop(
        content = content,
        newCrop = newCrop,
        oldCanvasSize = oldCapture,
        newCanvasSize = newCapture,
    )
}

internal fun DrawScope.drawSlockCropOverlay(
    cropRect: SlockComposerImageCropRect,
    canvasSize: Size,
    density: Float,
) {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return
    val rectLeft = cropRect.left * canvasSize.width
    val rectTop = cropRect.top * canvasSize.height
    val rectRight = cropRect.right * canvasSize.width
    val rectBottom = cropRect.bottom * canvasSize.height
    val rectWidth = rectRight - rectLeft
    val rectHeight = rectBottom - rectTop
    if (rectWidth <= 0f || rectHeight <= 0f) return

    val scrimColor = Color(0x99000000)
    if (rectTop > 0f) {
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, 0f),
            size = Size(canvasSize.width, rectTop),
        )
    }
    if (rectBottom < canvasSize.height) {
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, rectBottom),
            size = Size(canvasSize.width, canvasSize.height - rectBottom),
        )
    }
    if (rectLeft > 0f) {
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, rectTop),
            size = Size(rectLeft, rectHeight),
        )
    }
    if (rectRight < canvasSize.width) {
        drawRect(
            color = scrimColor,
            topLeft = Offset(rectRight, rectTop),
            size = Size(canvasSize.width - rectRight, rectHeight),
        )
    }

    val borderWidth = (1.5f * density).coerceAtLeast(1f)
    drawRect(
        color = Color(0xFFFFFFFF),
        topLeft = Offset(rectLeft, rectTop),
        size = Size(rectWidth, rectHeight),
        style = Stroke(width = borderWidth),
    )

    val gridColor = Color(0x66FFFFFF)
    val gridStroke = (1f * density).coerceAtLeast(1f)
    val oneThirdW = rectWidth / 3f
    val twoThirdW = 2f * rectWidth / 3f
    val oneThirdH = rectHeight / 3f
    val twoThirdH = 2f * rectHeight / 3f

    drawLine(
        color = gridColor,
        start = Offset(rectLeft + oneThirdW, rectTop),
        end = Offset(rectLeft + oneThirdW, rectBottom),
        strokeWidth = gridStroke,
    )
    drawLine(
        color = gridColor,
        start = Offset(rectLeft + twoThirdW, rectTop),
        end = Offset(rectLeft + twoThirdW, rectBottom),
        strokeWidth = gridStroke,
    )
    drawLine(
        color = gridColor,
        start = Offset(rectLeft, rectTop + oneThirdH),
        end = Offset(rectRight, rectTop + oneThirdH),
        strokeWidth = gridStroke,
    )
    drawLine(
        color = gridColor,
        start = Offset(rectLeft, rectTop + twoThirdH),
        end = Offset(rectRight, rectTop + twoThirdH),
        strokeWidth = gridStroke,
    )

    val cornerLen = (18f * density).coerceIn(12f, minOf(rectWidth, rectHeight) / 2f)
    val cornerThickness = (3f * density).coerceAtLeast(2f)
    val cornerColor = Color(0xFFFFFFFF)

    drawLine(cornerColor, Offset(rectLeft, rectTop), Offset(rectLeft + cornerLen, rectTop), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectLeft, rectTop), Offset(rectLeft, rectTop + cornerLen), strokeWidth = cornerThickness)

    drawLine(cornerColor, Offset(rectRight, rectTop), Offset(rectRight - cornerLen, rectTop), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectRight, rectTop), Offset(rectRight, rectTop + cornerLen), strokeWidth = cornerThickness)

    drawLine(cornerColor, Offset(rectLeft, rectBottom), Offset(rectLeft + cornerLen, rectBottom), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectLeft, rectBottom), Offset(rectLeft, rectBottom - cornerLen), strokeWidth = cornerThickness)

    drawLine(cornerColor, Offset(rectRight, rectBottom), Offset(rectRight - cornerLen, rectBottom), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectRight, rectBottom), Offset(rectRight, rectBottom - cornerLen), strokeWidth = cornerThickness)

    val edgeBarLen = (14f * density).coerceIn(8f, minOf(rectWidth, rectHeight) / 3f)
    val edgeCenterX = rectLeft + rectWidth / 2f
    val edgeCenterY = rectTop + rectHeight / 2f

    drawLine(cornerColor, Offset(edgeCenterX - edgeBarLen / 2f, rectTop), Offset(edgeCenterX + edgeBarLen / 2f, rectTop), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(edgeCenterX - edgeBarLen / 2f, rectBottom), Offset(edgeCenterX + edgeBarLen / 2f, rectBottom), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectLeft, edgeCenterY - edgeBarLen / 2f), Offset(rectLeft, edgeCenterY + edgeBarLen / 2f), strokeWidth = cornerThickness)
    drawLine(cornerColor, Offset(rectRight, edgeCenterY - edgeBarLen / 2f), Offset(rectRight, edgeCenterY + edgeBarLen / 2f), strokeWidth = cornerThickness)
}

internal data class FilePreviewLaunchPayloadTrace(
    val payload: SlockFilePreviewPayload,
    val responseBytes: Int,
    val durationMs: Long,
    val result: String,
    val routeKind: String = "",
    val sessionId: String = "",
    val temporaryFileRefsToCleanup: Set<String> = emptySet()
)

internal fun slockFilePreviewResolveLaunchPayload(
    source: String,
    loadPreviewPayload: (String) -> SlockFilePreviewPayload?,
    retireEphemeralSession: (String) -> Set<String> = { emptySet() }
): FilePreviewLaunchPayloadTrace {
    val startedAt = TimeSource.Monotonic.markNow()
    val parsedLaunch =
        source
            .takeIf { it.isNotBlank() }
            ?.let {
                runCatching { SlockFilePreviewJson.decodeFromString(WireSlockFilePreviewLaunchPayload.serializer(), it) }
            }
    val launch = parsedLaunch?.getOrNull()?.normalizedOrNull()
    // Remote routes never consume the process-local payload candidate. Even in the
    // opening process it may contain an old signed URL or display snapshot, so the
    // durable identity must reconstruct a loading payload and hydrate canonically.
    // Only an ephemeral route may use its unguessable process-local capability.
    val candidate =
        launch
            ?.takeIf { it.routeKind == SlockFilePreviewRouteEphemeral }
            ?.sessionId
            ?.let(loadPreviewPayload)
    val candidateMatches =
        launch != null && candidate != null && candidate.matchesFilePreviewLaunch(launch)
    var temporaryFileRefsToCleanup = emptySet<String>()
    val restoredPayload =
        when {
            candidateMatches -> candidate
            launch?.routeKind == SlockFilePreviewRouteThreadAttachment ->
                launch.toRestoredThreadAttachmentPayload()
            launch?.routeKind == SlockFilePreviewRouteAgentWorkspace ->
                agentWorkspaceLazyPreviewPayload(
                    agentId = launch.agentId,
                    path = launch.workspacePath
                )
            launch?.routeKind == SlockFilePreviewRouteEphemeral -> {
                temporaryFileRefsToCleanup = retireEphemeralSession(launch.sessionId)
                null
            }
            else -> null
        }
    val result =
        when {
            candidateMatches -> "success"
            restoredPayload != null && launch?.routeKind == SlockFilePreviewRouteThreadAttachment ->
                "restored_thread_attachment"
            restoredPayload != null && launch?.routeKind == SlockFilePreviewRouteAgentWorkspace ->
                "restored_agent_workspace"
            launch?.routeKind == SlockFilePreviewRouteEphemeral -> "ephemeral_expired"
            parsedLaunch?.isSuccess == true -> "invalid_launch"
            else -> "failure"
        }
    return FilePreviewLaunchPayloadTrace(
        payload =
            restoredPayload ?: SlockFilePreviewPayload(
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                messageDesc = MR.strings.file_preview_error_load_payload.desc(),
                error =
                    when {
                        launch?.routeKind == SlockFilePreviewRouteEphemeral ->
                            "ephemeral_preview_expired"
                        parsedLaunch?.isSuccess == true -> "invalid_file_preview_launch"
                        else -> "payload_parse_failed"
                    }
            ),
        responseBytes = source.encodeToByteArray().size,
        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        result = result,
        routeKind = launch?.routeKind.orEmpty(),
        sessionId = launch?.sessionId.orEmpty(),
        temporaryFileRefsToCleanup = temporaryFileRefsToCleanup
    )
}

internal fun FilePreviewLaunchPayloadTrace.requiresAuthenticatedRouteAuthority(): Boolean =
    routeKind == SlockFilePreviewRouteThreadAttachment ||
        routeKind == SlockFilePreviewRouteAgentWorkspace

private fun SlockFilePreviewPayload.withFilePreviewAuthorityRejected(): SlockFilePreviewPayload =
    copy(
        kind = "error",
        titleDesc = MR.strings.file_preview_unavailable.desc(),
        subtitle = "",
        name = "",
        path = "",
        meta = "",
        content = "",
        imageUrl = null,
        externalUrl = null,
        videoUrl = null,
        externalFileName = "",
        externalMimeType = "",
        source = "",
        attachmentId = "",
        attachmentCommentsEnabled = false,
        commentsInitiallyOpen = false,
        agentId = "",
        imageGallery = emptyList(),
        imageGalleryIndex = 0,
        message = "",
        messageDesc = MR.strings.file_preview_error_load_payload.desc(),
        error = "authenticated_route_authority_changed",
        composerImageThreadKey = "",
        composerImageActionId = "",
        composerImageGroupId = "",
        composerImageFileRef = "",
        composerImageFileName = "",
        composerImageMimeType = "",
        composerImageSizeBytes = null,
        composerImagePreviewUri = "",
        composerImageOwnedTemporaryFileRefs = emptyList(),
        composerImageReplaceAttachmentId = "",
        composerImageCompressAfterEdit = false,
        composerImageOriginalOptionEnabled = false,
        composerImageEditCompleted = false,
        composerImageCompressed = false,
        composerImageCanComplete = false,
        composerImageCanEdit = false
    )

private fun WireSlockFilePreviewLaunchPayload.normalizedOrNull(): WireSlockFilePreviewLaunchPayload? {
    if (version != SlockFilePreviewLaunchContractVersion) return null
    val cleanSessionId = sessionId.trim().takeIf { it.isNotBlank() } ?: return null
    return when (routeKind) {
        SlockFilePreviewRouteThreadAttachment -> {
            val cleanAttachmentId = attachmentId.trim().takeIf { it.isNotBlank() } ?: return null
            val cleanPreviewKind = previewKind.normalizedRemotePreviewKindOrNull() ?: return null
            val cleanGalleryIds = galleryAttachmentIds.map(String::trim)
            if (cleanGalleryIds.any(String::isBlank) || cleanGalleryIds.distinct().size != cleanGalleryIds.size) {
                return null
            }
            val cleanGalleryIndex =
                if (cleanGalleryIds.isEmpty()) {
                    if (galleryIndex != 0) return null
                    0
                } else {
                    if (galleryIndex !in cleanGalleryIds.indices) return null
                    if (cleanGalleryIds[galleryIndex] != cleanAttachmentId) return null
                    galleryIndex
                }
            if (agentId.isNotBlank() || workspacePath.isNotBlank()) return null
            if (commentsInitiallyOpen && !attachmentCommentsEnabled) return null
            copy(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteThreadAttachment,
                attachmentId = cleanAttachmentId,
                previewKind = cleanPreviewKind,
                galleryAttachmentIds = cleanGalleryIds,
                galleryIndex = cleanGalleryIndex,
                agentId = "",
                workspacePath = ""
            )
        }
        SlockFilePreviewRouteAgentWorkspace -> {
            val cleanAgentId = agentId.trim().takeIf { it.isNotBlank() } ?: return null
            val cleanPath = workspacePath.trim().takeIf { it.isNotBlank() } ?: return null
            if (
                attachmentId.isNotBlank() ||
                previewKind.isNotBlank() ||
                attachmentCommentsEnabled ||
                commentsInitiallyOpen ||
                galleryAttachmentIds.isNotEmpty() ||
                galleryIndex != 0
            ) {
                return null
            }
            copy(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteAgentWorkspace,
                attachmentId = "",
                previewKind = "",
                galleryAttachmentIds = emptyList(),
                galleryIndex = 0,
                agentId = cleanAgentId,
                workspacePath = cleanPath
            )
        }
        SlockFilePreviewRouteEphemeral -> {
            if (
                attachmentId.isNotBlank() ||
                previewKind.isNotBlank() ||
                attachmentCommentsEnabled ||
                commentsInitiallyOpen ||
                galleryAttachmentIds.isNotEmpty() ||
                galleryIndex != 0 ||
                agentId.isNotBlank() ||
                workspacePath.isNotBlank()
            ) {
                return null
            }
            copy(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteEphemeral,
                attachmentId = "",
                previewKind = "",
                galleryAttachmentIds = emptyList(),
                galleryIndex = 0,
                agentId = "",
                workspacePath = ""
            )
        }
        else -> null
    }
}

private fun String.normalizedRemotePreviewKindOrNull(): String? =
    trim().lowercase().takeIf {
        it in setOf("image", "markdown", "text", "html", "video", "audio", "file")
    }

private fun SlockFilePreviewPayload.matchesFilePreviewLaunch(
    launch: WireSlockFilePreviewLaunchPayload
): Boolean =
    when (launch.routeKind) {
        SlockFilePreviewRouteThreadAttachment ->
            composerImageActionId.isBlank() &&
                source == SlockFilePreviewSourceThreadAttachment &&
                attachmentId.trim() == launch.attachmentId &&
                durableRemotePreviewKind() == launch.previewKind &&
                attachmentCommentsEnabled == launch.attachmentCommentsEnabled &&
                commentsInitiallyOpen == launch.commentsInitiallyOpen &&
                imageGallery.map { it.attachmentId.trim() } == launch.galleryAttachmentIds &&
                (imageGallery.isEmpty() || imageGalleryIndex == launch.galleryIndex)
        SlockFilePreviewRouteAgentWorkspace ->
            composerImageActionId.isBlank() &&
                source == SlockFilePreviewSourceAgentWorkspace &&
                agentId.trim() == launch.agentId &&
                path.trim() == launch.workspacePath
        SlockFilePreviewRouteEphemeral ->
            toFilePreviewLaunchRouteKind() == SlockFilePreviewRouteEphemeral
        else -> false
    }

private fun WireSlockFilePreviewLaunchPayload.toRestoredThreadAttachmentPayload(): SlockFilePreviewPayload {
    val gallery = galleryAttachmentIds.map { SlockFilePreviewGalleryImage(attachmentId = it) }
    return SlockFilePreviewPayload(
        kind = "loading",
        requestedKind = previewKind,
        titleDesc = previewKind.filePreviewTitleDesc(),
        messageDesc = MR.strings.common_loading.desc(),
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = attachmentId,
        attachmentCommentsEnabled = attachmentCommentsEnabled,
        commentsInitiallyOpen = commentsInitiallyOpen,
        imageGallery = gallery,
        imageGalleryIndex = galleryIndex
    )
}

private fun String.filePreviewTitleDesc(): StringDesc =
    when (this) {
        "image" -> MR.strings.file_preview_image.desc()
        "markdown" -> MR.strings.file_preview_markdown.desc()
        "text" -> MR.strings.file_preview_text.desc()
        "html" -> MR.strings.file_preview_html.desc()
        "video" -> MR.strings.file_preview_video.desc()
        "audio" -> MR.strings.file_preview_audio.desc()
        else -> MR.strings.file_preview_title.desc()
    }

internal fun slockFilePreviewLaunchDescriptor(
    sessionId: String,
    payload: SlockFilePreviewPayload
): WireSlockFilePreviewLaunchPayload {
    val cleanSessionId = sessionId.trim()
    return when (payload.toFilePreviewLaunchRouteKind()) {
        SlockFilePreviewRouteThreadAttachment -> {
            val galleryIds = payload.imageGallery.map { it.attachmentId.trim() }
            val matchingGalleryIndex = galleryIds.indexOf(payload.attachmentId.trim())
            val durableGalleryIds =
                galleryIds.takeIf {
                    it.isNotEmpty() &&
                        it.none(String::isBlank) &&
                        it.distinct().size == it.size &&
                        matchingGalleryIndex >= 0
                }.orEmpty()
            WireSlockFilePreviewLaunchPayload(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteThreadAttachment,
                attachmentId = payload.attachmentId.trim(),
                previewKind = payload.durableRemotePreviewKind(),
                attachmentCommentsEnabled = payload.attachmentCommentsEnabled,
                commentsInitiallyOpen = payload.commentsInitiallyOpen,
                galleryAttachmentIds = durableGalleryIds,
                galleryIndex = if (durableGalleryIds.isEmpty()) 0 else matchingGalleryIndex
            )
        }
        SlockFilePreviewRouteAgentWorkspace ->
            WireSlockFilePreviewLaunchPayload(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteAgentWorkspace,
                agentId = payload.agentId.trim(),
                workspacePath = payload.path.trim()
            )
        else ->
            WireSlockFilePreviewLaunchPayload(
                sessionId = cleanSessionId,
                routeKind = SlockFilePreviewRouteEphemeral
            )
    }
}

private fun SlockFilePreviewPayload.toFilePreviewLaunchRouteKind(): String =
    when {
        composerImageActionId.isNotBlank() -> SlockFilePreviewRouteEphemeral
        source == SlockFilePreviewSourceThreadAttachment && attachmentId.isNotBlank() ->
            SlockFilePreviewRouteThreadAttachment
        source == SlockFilePreviewSourceAgentWorkspace && agentId.isNotBlank() && path.isNotBlank() ->
            SlockFilePreviewRouteAgentWorkspace
        else -> SlockFilePreviewRouteEphemeral
    }

private fun SlockFilePreviewPayload.durableRemotePreviewKind(): String =
    when {
        requestedKind == "image" -> "image"
        requestedKind == "html" -> "html"
        requestedKind == "audio" -> "audio"
        slockFilePreviewIsMarkdownAttachment(
            fileName = name.ifBlank { path.ifBlank { externalFileName } },
            mimeType = externalMimeType
        ) -> "markdown"
        slockFilePreviewIsHtmlAttachment(
            fileName = name.ifBlank { path.ifBlank { externalFileName } },
            mimeType = externalMimeType
        ) -> "html"
        slockFilePreviewIsMp3AudioAttachment(
            fileName = name.ifBlank { path.ifBlank { externalFileName } },
            mimeType = externalMimeType
        ) -> "audio"
        requestedKind == "video" -> "video"
        requestedKind == "markdown" -> "markdown"
        requestedKind == "text" -> "text"
        else -> "file"
    }

internal fun Pager.openSlockFilePreviewPage(
    payload: SlockFilePreviewPayload,
    sourceStoreScope: SlockShellStoreScope? = null,
    onEphemeralSessionRegistered: ((String) -> Boolean)? = null
): Boolean {
    val sourceScope = resolveSlockFilePreviewSourceStoreScope(pageData.params, sourceStoreScope)
    if (sourceScope == null) {
        slockLogWarn(
            tag = SlockScopedNavigationLogTag,
            message = "slock_scoped_navigation_rejected reason=missing_source_scope target=$SLOCK_FILE_PREVIEW_PAGE"
        )
        return false
    }
    val sharedStores = SlockSharedStoreRegistry.currentOrNull()
    val storeScope = sharedStores?.currentNavigationStoreScope(sourceScope) ?: sourceScope
    val sessionId = SlockFilePreviewLaunchPayloadStore.put(payload)
    val launch = slockFilePreviewLaunchDescriptor(sessionId, payload)
    val liveOwner = sharedStores?.currentAuthenticatedRouteOwner(storeScope)
    val sourceRouteOwner = pageData.params.authenticatedRouteOwnerOrNull()
    val targetServerId =
        resolveSlockFilePreviewTargetServerId(
            sourcePageData = pageData.params,
            storeScope = storeScope,
            liveAuthorityAvailable = sharedStores != null,
            liveOwner = liveOwner
        )
    if (
        launch.routeKind == SlockFilePreviewRouteThreadAttachment &&
        liveOwner != null &&
        (sourceRouteOwner == null || sourceRouteOwner == liveOwner)
    ) {
        slockFilePreviewAttachmentMetadataStore().capture(liveOwner, payload)
    }
    val ephemeralActionRegistry = slockFilePreviewEphemeralActionRegistry()
    val ephemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger()
    val ephemeralRegistration = payload.toEphemeralSessionRegistrationOrNull(sessionId)
    val registrationCleanupRefs =
        if (ephemeralRegistration != null) {
            val retired =
                ephemeralActionRegistry.register(
                    sessionId = ephemeralRegistration.sessionId,
                    threadKey = ephemeralRegistration.threadKey,
                    actionIds = ephemeralRegistration.actionIds,
                    ownedTemporaryFileRefs = ephemeralRegistration.ownedTemporaryFileRefs
                )
            retired.cleanupFileRefs +
                ephemeralCleanupLedger.register(
                    sessionId = sessionId,
                    fileRefs = ephemeralRegistration.ownedTemporaryFileRefs
                )
        } else {
            emptySet()
        }
    if (registrationCleanupRefs.isNotEmpty()) {
        Utils.bridgeModule(pagerId).retireFilePreviewTemporaryFiles(
            sessionId = sessionId,
            fileRefs = registrationCleanupRefs.toList()
        )
    }

    fun retireEphemeralRegistration(): Set<String> {
        SlockFilePreviewLaunchPayloadStore.retire(sessionId)
        val cleanupRefs =
            ephemeralActionRegistry.retireActive(sessionId).cleanupFileRefs +
                ephemeralActionRegistry.retirePending(sessionId).cleanupFileRefs +
                ephemeralCleanupLedger.take(sessionId)
        if (cleanupRefs.isNotEmpty()) {
            Utils.bridgeModule(pagerId).retireFilePreviewTemporaryFiles(
                sessionId = sessionId,
                fileRefs = cleanupRefs.toList()
            )
        }
        return cleanupRefs
    }

    // Notify the caller only after the registry has accepted this exact session.  A callback
    // before registration could hand a consumer an owner token for a session that was immediately
    // evicted/replaced by the registry's bounded-session policy.
    if (ephemeralRegistration != null) {
        // An ephemeral launch without a live registry row has no authority for a Done callback.
        // This can happen when the bounded registry evicts the row between registration and this
        // check (or when a test/configuration uses a zero-capacity registry).  Never open an
        // ownerless preview: retire the payload, ledger and any remaining refs first.
        if (!ephemeralActionRegistry.hasSession(sessionId)) {
            retireEphemeralRegistration()
            return false
        }
        val ownerAccepted =
            runCatching { onEphemeralSessionRegistered?.invoke(sessionId) ?: true }
                .getOrDefault(false)
        if (!ownerAccepted) {
            // The page-local owner was already disposed (or otherwise rejected this session).
            // Do not open a preview whose callback can never be consumed; retire every registry,
            // ledger, and launch-payload entry synchronously before returning to the caller.
            retireEphemeralRegistration()
            return false
        }
        // A concurrent preview registration may evict this bounded process-local row after the
        // owner callback returns. Recheck immediately before opening the route so no page is
        // launched with a session that cannot produce an admissible Done event.
        if (!ephemeralActionRegistry.hasSession(sessionId)) {
            retireEphemeralRegistration()
            return false
        }
    }
    val launchPageData = slockFilePreviewLaunchPageData(launch, storeScope, targetServerId)
    val opened =
        openSlockPage(
            SLOCK_FILE_PREVIEW_PAGE,
            launchPageData,
            storeScopeOverride = storeScope,
            authenticatedRouteAuthorityRequired = launch.requiresAuthenticatedRouteAuthority()
        )
    if (!opened) {
        if (ephemeralRegistration != null) {
            retireEphemeralRegistration()
        } else {
            SlockFilePreviewLaunchPayloadStore.retire(sessionId)
        }
    }
    return opened
}

internal fun resolveSlockFilePreviewSourceStoreScope(
    sourcePageData: JSONObject,
    sourceStoreScope: SlockShellStoreScope?
): SlockShellStoreScope? =
    sourceStoreScope ?: sourcePageData.slockShellStoreScopeOrNull()

internal fun resolveSlockFilePreviewTargetServerId(
    sourcePageData: JSONObject,
    storeScope: SlockShellStoreScope,
    liveAuthorityAvailable: Boolean,
    liveOwner: AuthenticatedRouteOwner?
): String? =
    if (liveAuthorityAvailable) {
        liveOwner?.serverId
    } else {
        sourcePageData.optString("serverId").trim().takeIf(String::isNotBlank)
            ?: storeScope.initialServerId
    }

internal fun slockFilePreviewLaunchPageData(
    launch: WireSlockFilePreviewLaunchPayload,
    storeScope: SlockShellStoreScope,
    targetServerId: String? = storeScope.initialServerId
): JSONObject =
    JSONObject().apply {
        put("pageName", SLOCK_FILE_PREVIEW_PAGE)
        if (launch.requiresAuthenticatedRouteAuthority()) {
            put("serverId", targetServerId.orEmpty())
        }
        put(
            "payload",
            SlockFilePreviewJson.encodeToString(
                WireSlockFilePreviewLaunchPayload.serializer(),
                launch
            )
        )
    }.withSlockShellStoreScope(storeScope)

internal fun WireSlockFilePreviewLaunchPayload.requiresAuthenticatedRouteAuthority(): Boolean =
    routeKind == SlockFilePreviewRouteThreadAttachment ||
        routeKind == SlockFilePreviewRouteAgentWorkspace

internal fun KuiklyThreadAttachmentItem.toThreadAttachmentLazyPreviewPayload(
    kind: String,
    imageGallery: List<KuiklyThreadAttachmentItem> = emptyList(),
    imageGalleryIndex: Int = 0,
    attachmentCommentsEnabled: Boolean = false
): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        requestedKind = kind,
        titleDesc = when (kind) {
            "image" -> MR.strings.file_preview_image.desc()
            "markdown" -> MR.strings.file_preview_markdown.desc()
            "text" -> MR.strings.file_preview_text.desc()
            "video" -> MR.strings.file_preview_video.desc()
            else -> MR.strings.file_preview_title.desc()
        },
        subtitle = name,
        name = name,
        path = name,
        meta = genericAttachmentMetaLine(),
        imageUrl =
            if (kind == "image") {
                thumbnailUrl?.takeIf { it.isNotBlank() }
            } else {
                null
            },
        messageDesc = MR.strings.common_loading.desc(),
        externalFileName = name.ifBlank { "attachment-${id.take(8)}" },
        externalMimeType = mimeType,
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = id,
        attachmentCommentsEnabled = attachmentCommentsEnabled,
        imageGallery = imageGallery.map { it.toSlockFilePreviewGalleryImage() },
        imageGalleryIndex = imageGalleryIndex.coerceAtLeast(0)
    )

internal fun KuiklyThreadAttachmentItem.toThreadAttachmentCommentsPreviewPayload(
    commentsEnabled: Boolean
): SlockFilePreviewPayload? {
    if (!commentsEnabled) return null
    if (isPreviewableVideoAttachment()) return null
    if (isPreviewableAudioAttachment()) return null
    val kind =
        when {
            isImageAttachment() -> "image"
            isMarkdownAttachment() -> "markdown"
            isTextLikeAttachment() || isHtmlAttachment() -> "text"
            else -> "file"
        }
    return toThreadAttachmentLazyPreviewPayload(
        kind = kind,
        attachmentCommentsEnabled = true
    ).copy(commentsInitiallyOpen = true)
}

internal fun SlockFilePreviewPayload.supportsAttachmentComments(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        attachmentCommentsEnabled &&
        !slockFilePreviewIsPreviewableVideoAttachment(externalFileName, externalMimeType) &&
        !slockFilePreviewIsAudioAttachment(externalFileName, externalMimeType)

private fun SlockFilePreviewPayload.supportsSaveToPhotos(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        (requestedKind == "image" || kind == "image")

internal fun agentWorkspaceLazyPreviewPayload(agentId: String, path: String): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        requestedKind = "workspace",
        titleDesc = MR.strings.file_preview_title.desc(),
        subtitle = path,
        name = path,
        path = path,
        messageDesc = MR.strings.common_loading.desc(),
        source = SlockFilePreviewSourceAgentWorkspace,
        agentId = agentId
    )

private suspend fun SlockFilePreviewPayload.loadThreadAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String,
    expiresAtSink: ((String?) -> Unit)? = null
): SlockFilePreviewPayload? {
    if (source != SlockFilePreviewSourceThreadAttachment || attachmentId.isBlank() || kind != "loading") return null
    val wantsMarkdown =
        requestedKind == "markdown" ||
            slockFilePreviewIsMarkdownAttachment(
                fileName = name.ifBlank { path.ifBlank { externalFileName } },
                mimeType = externalMimeType
            )
    val previewFileName = name.ifBlank { path.ifBlank { externalFileName } }
    val wantsImage =
        requestedKind == "image" ||
            slockFilePreviewIsImageAttachment(fileName = previewFileName, mimeType = externalMimeType)
    val wantsVideo =
        requestedKind == "video" ||
            slockFilePreviewIsPreviewableVideoAttachment(
                fileName = previewFileName,
                mimeType = externalMimeType
            )
    val wantsAudio =
        requestedKind == "audio" ||
            slockFilePreviewIsAudioAttachment(
                fileName = previewFileName,
                mimeType = externalMimeType
            )
    val wantsHtml =
        requestedKind == "html" ||
            slockFilePreviewIsHtmlAttachment(
                fileName = previewFileName,
                mimeType = externalMimeType
            )
    val wantsXlsx =
        requestedKind == "xlsx" ||
            slockFilePreviewIsXlsxAttachment(
                fileName = previewFileName,
                mimeType = externalMimeType
            )
    val wantsText =
        requestedKind == "text" ||
            (!wantsMarkdown &&
            !wantsHtml &&
            !wantsXlsx &&
            slockFilePreviewIsTextLikeAttachment(
                fileName = name.ifBlank { path.ifBlank { externalFileName } },
                mimeType = externalMimeType
            ))
    return when {
        wantsMarkdown -> loadThreadMarkdownAttachmentPreview(threadApi, networkClient, truncatedPreviewHint)
        wantsXlsx -> loadThreadXlsxAttachmentPreview(networkClient)
        wantsImage -> loadThreadImageAttachmentPreview(threadApi, expiresAtSink)
        wantsVideo -> loadThreadVideoAttachmentPreview(threadApi)
        wantsAudio -> loadThreadVideoAttachmentPreview(threadApi, title = MR.strings.file_preview_audio.desc(), audioOnly = true)
        wantsHtml -> loadThreadHtmlAttachmentPreview(threadApi)
        wantsText -> loadThreadTextAttachmentPreview(threadApi, networkClient, truncatedPreviewHint)
        else ->
            copy(
                kind = "unsupported",
                titleDesc = MR.strings.file_preview_title.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_no_preview.desc()
            )
    }
}

private suspend fun SlockFilePreviewPayload.loadThreadImageAttachmentPreview(
    threadApi: SlockThreadApiClient,
    expiresAtSink: ((String?) -> Unit)? = null
): SlockFilePreviewPayload {
    val remoteImageUrl =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> {
                expiresAtSink?.invoke(response.data.expiresAt)
                response.data.url.takeIf { it.isNotBlank() }
            }
            is ThreadApiResult.Failure -> null
        }
    // Kuikly Compose image rendering already caches by source URL through
    // KuiklyImageCacheManager -> MemoryCacheModule and the native image adapter.
    val resolvedImageUrl = remoteImageUrl ?: imageUrl
    return copy(
        kind = "image",
        titleDesc = MR.strings.file_preview_image.desc(),
        imageUrl = resolvedImageUrl,
        message = "",
        messageDesc =
            if (resolvedImageUrl == null) {
                MR.strings.file_preview_error_image_unavailable.desc()
            } else {
                null
            },
        error = if (resolvedImageUrl == null) "image_preview_unavailable" else null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadHtmlAttachmentPreview(
    threadApi: SlockThreadApiClient
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentHtmlPreviewUrl(attachmentId)) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_html.desc(),
            error = "html_preview_url_failed"
        )
    return copy(
        kind = "html",
        titleDesc = MR.strings.file_preview_html.desc(),
        content = "",
        imageUrl = null,
        videoUrl = null,
        externalUrl = target,
        message = "",
        messageDesc = null,
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadVideoAttachmentPreview(
    threadApi: SlockThreadApiClient,
    title: StringDesc = MR.strings.file_preview_video.desc(),
    audioOnly: Boolean = false
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_media.desc(),
            error = "video_preview_url_failed"
        )
    return copy(
        kind = "video",
        titleDesc = title,
        videoUrl = target,
        audioOnly = audioOnly,
        message = "",
        messageDesc = null,
        error = null
    )
}

/**
 * Server-side preview attempt per task #203: `GET attachments/:id/preview`
 * returns text/markdown bodies already truncated to a safe size with an
 * explicit `truncated` flag, so text-like previews stop downloading whole
 * files through signed URLs. Returns null when the endpoint is missing,
 * errors, reports unsupported, or answers with a kind the caller does not
 * render — callers then fall back to the legacy full-body fetch.
 */
private suspend fun fetchServerAttachmentPreview(
    networkClient: SlockNetworkClient,
    attachmentId: String
): AttachmentPreview? {
    val cleanId = attachmentId.trim().takeIf { it.isNotBlank() } ?: return null
    val response =
        try {
            networkClient.request(
                priority = DataRequestPriority.Visible,
                path = "attachments/$cleanId/preview"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return null
        }
    if (response.code !in 200..299) return null
    val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return null
    val preview = parseSharedAttachmentPreview(body) ?: return null
    return preview.takeIf { it.isOk }
}

private suspend fun SlockFilePreviewPayload.loadThreadTextAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String
): SlockFilePreviewPayload {
    val serverPreview = fetchServerAttachmentPreview(networkClient, attachmentId)
    val serverText = (serverPreview?.content as? AttachmentPreviewContent.Text)?.text
    if (serverPreview != null && serverText != null) {
        val previewBody =
            if (serverPreview.truncated) serverText + "\n\n$truncatedPreviewHint" else serverText
        return copy(
            kind = "text",
            titleDesc = MR.strings.file_preview_text.desc(),
            content = previewBody,
            markdown = false,
            message = "",
            messageDesc = null,
            error = null
        )
    }
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_text.desc(),
            error = "text_preview_url_failed"
        )
    val response =
        try {
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Visible,
                url = target
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_load_text.desc(),
                error = "text_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_text.desc(),
            error = "text_preview_fetch_failed"
        )
    }
    val body = response.bodyText.orEmpty()
    val previewBody =
        if (body.length > SlockFilePreviewTextMaxChars) {
            body.take(SlockFilePreviewTextMaxChars) + "\n\n$truncatedPreviewHint"
        } else {
            body
        }
    return copy(
        kind = "text",
        titleDesc = MR.strings.file_preview_text.desc(),
        content = previewBody,
        markdown = false,
        message = "",
        messageDesc = null,
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadMarkdownAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String
): SlockFilePreviewPayload {
    val serverPreview = fetchServerAttachmentPreview(networkClient, attachmentId)
    val serverMarkdown = (serverPreview?.content as? AttachmentPreviewContent.Markdown)?.markdown
    if (serverPreview != null && serverMarkdown != null) {
        val previewBody =
            if (serverPreview.truncated) {
                serverMarkdown + "\n\n$truncatedPreviewHint"
            } else {
                serverMarkdown
            }
        return copy(
            kind = "markdown",
            titleDesc = MR.strings.file_preview_markdown.desc(),
            content = previewBody,
            markdown = true,
            message = "",
            messageDesc = null,
            error = null
        )
    }
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
            error = "markdown_preview_url_failed"
        )
    val response =
        try {
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Visible,
                url = target
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
                error = "markdown_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
            error = "markdown_preview_fetch_failed"
        )
    }
    val body =
        response.bodyText?.takeIf { it.isNotBlank() }
            ?: return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_markdown_empty.desc(),
                error = "markdown_preview_empty"
            )
    val previewBody =
        if (body.length > SlockFilePreviewMarkdownMaxChars) {
            body.take(SlockFilePreviewMarkdownMaxChars) + "\n\n$truncatedPreviewHint"
        } else {
            body
        }
    return copy(
        kind = "markdown",
        titleDesc = MR.strings.file_preview_markdown.desc(),
        content = previewBody,
        markdown = true,
        message = "",
        messageDesc = null,
        error = null
    )
}

/** task #351: server-parsed XLSX preview (web #7168 contract). Reuses the shared
 * `attachments/:id/preview` endpoint; the payload carries the bounded sheets as JSON. */
private suspend fun SlockFilePreviewPayload.loadThreadXlsxAttachmentPreview(
    networkClient: SlockNetworkClient,
): SlockFilePreviewPayload {
    val serverPreview = fetchServerAttachmentPreview(networkClient, attachmentId)
    val xlsx = serverPreview?.content as? AttachmentPreviewContent.Xlsx
    if (xlsx != null) {
        return copy(
            kind = "xlsx",
            titleDesc = MR.strings.file_preview_xlsx.desc(),
            content = "",
            markdown = false,
            message = "",
            messageDesc = null,
            error = null,
            xlsxSheetsJson = encodeSlockFilePreviewXlsx(xlsx),
        )
    }
    return copy(
        kind = "unsupported",
        titleDesc = MR.strings.file_preview_title.desc(),
        message = "",
        messageDesc = MR.strings.file_preview_no_preview.desc(),
        error = null,
    )
}

@Serializable
internal data class SlockFilePreviewXlsxSheetCarrier(
    val name: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val rowCount: Int,
    val columnCount: Int,
    val truncated: Boolean,
)

@Serializable
internal data class SlockFilePreviewXlsxCarrier(
    val sheets: List<SlockFilePreviewXlsxSheetCarrier>,
    val sheetCount: Int,
    val truncated: Boolean,
)

internal fun encodeSlockFilePreviewXlsx(xlsx: AttachmentPreviewContent.Xlsx): String =
    runCatching {
        SlockFilePreviewJson.encodeToString(
            SlockFilePreviewXlsxCarrier.serializer(),
            SlockFilePreviewXlsxCarrier(
                sheets =
                    xlsx.sheets.map { sheet ->
                        SlockFilePreviewXlsxSheetCarrier(
                            name = sheet.name,
                            headers = sheet.headers,
                            rows = sheet.rows,
                            rowCount = sheet.rowCount,
                            columnCount = sheet.columnCount,
                            truncated = sheet.truncated,
                        )
                    },
                sheetCount = xlsx.sheetCount,
                truncated = xlsx.truncated,
            )
        )
    }.getOrDefault("")

internal fun parseSlockFilePreviewXlsx(json: String): SlockFilePreviewXlsxCarrier? =
    runCatching {
        SlockFilePreviewJson.decodeFromString(SlockFilePreviewXlsxCarrier.serializer(), json)
    }.getOrNull()


private suspend fun SlockFilePreviewPayload.threadAttachmentExternalUrl(
    threadApi: SlockThreadApiClient
): String? {
    if (source != SlockFilePreviewSourceThreadAttachment || attachmentId.isBlank()) return null
    return when (val response = threadApi.attachmentUrl(attachmentId, disposition = "attachment")) {
        is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
        is ThreadApiResult.Failure -> null
    }
}

private suspend fun SlockFilePreviewPayload.loadAgentWorkspacePreview(
    memberRepository: RealMemberRepository,
    errorStrings: ErrorPresentationStrings
): SlockFilePreviewPayload? {
    if (source != SlockFilePreviewSourceAgentWorkspace || agentId.isBlank() || path.isBlank() || kind != "loading") return null
    return when (val result = memberRepository.readAgentWorkspaceFile(DataRequestPriority.Visible, MemberId(agentId), path)) {
        is DataResult.Content -> result.data.preview.toSlockFilePreviewPayload(result.data.path).copy(source = source, agentId = agentId)
        is DataResult.Empty ->
            copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_file_unavailable.desc(),
                error = "empty_preview"
            )
        is DataResult.Error -> withAgentWorkspaceLoadError(result.error, errorStrings)
        is DataResult.NoPermission -> withAgentWorkspacePermissionError(result.blocker, errorStrings)
    }
}

internal fun SlockFilePreviewPayload.withAgentWorkspaceLoadError(
    error: ScreenError,
    errorStrings: ErrorPresentationStrings
): SlockFilePreviewPayload =
    copy(
        kind = "error",
        titleDesc = MR.strings.file_preview_unavailable.desc(),
        message = error.userFacingMessage(errorStrings),
        messageDesc = null,
        error = error.code
    )

internal fun SlockFilePreviewPayload.withAgentWorkspacePermissionError(
    blocker: PermissionBlocker,
    errorStrings: ErrorPresentationStrings
): SlockFilePreviewPayload =
    copy(
        kind = "error",
        titleDesc = MR.strings.file_preview_hidden.desc(),
        message = blocker.userFacingMessage(errorStrings),
        messageDesc = null,
        error = blocker.code
    )

internal fun slockFilePreviewIsMarkdownAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    return normalizedMime.equals("text/markdown", ignoreCase = true) ||
        normalizedMime.equals("text/x-markdown", ignoreCase = true) ||
        fileName.endsWith(".md", ignoreCase = true) ||
        fileName.endsWith(".markdown", ignoreCase = true)
}

internal fun slockFilePreviewIsHtmlAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    val normalizedName = fileName.substringBefore('?').substringBefore('#')
    return normalizedMime.equals("text/html", ignoreCase = true) ||
        normalizedName.endsWith(".html", ignoreCase = true) ||
        normalizedName.endsWith(".htm", ignoreCase = true)
}

/** task #351: server-parsed XLSX preview applies to the zip-based xlsx/xlsm family only
 * (web #7168 contract; legacy .xls and ODS stay on the download fallback). */
internal fun slockFilePreviewIsXlsxAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val normalizedName = fileName.substringBefore('?').substringBefore('#')
    return normalizedName.endsWith(".xlsx", ignoreCase = true) ||
        normalizedName.endsWith(".xlsm", ignoreCase = true) ||
        normalizedMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}

internal enum class SlockPreviewableVideoContainer {
    Mp4,
    QuickTime,
    M4v,
    ThreeGpp,
    ThreeGpp2,
    WebM,
    Matroska,
    MpegTs,
    MpegPs,
    Flv
}

internal enum class SlockVideoPlayerFormatProfile {
    AndroidMedia3,
    IosAvPlayer,
    OhosArkUiVideo
}

internal fun slockVideoPlayerFormatProfileSupports(
    profile: SlockVideoPlayerFormatProfile,
    container: SlockPreviewableVideoContainer
): Boolean =
    when (profile) {
        // Media3 ExoPlayer 1.6.1 progressive extractors. QuickTime/M4V/3GPP are
        // ISO-BMFF variants handled by its MP4 extractor; sample codecs remain
        // device-dependent and can still reach the shared playback error state.
        SlockVideoPlayerFormatProfile.AndroidMedia3 -> true
        // AVPlayer's file-backed audiovisual families. WebM/Matroska/TS/PS/FLV
        // are not admitted merely because a particular OS/device might decode
        // their samples through another framework.
        SlockVideoPlayerFormatProfile.IosAvPlayer ->
            when (container) {
                SlockPreviewableVideoContainer.Mp4,
                SlockPreviewableVideoContainer.QuickTime,
                SlockPreviewableVideoContainer.M4v,
                SlockPreviewableVideoContainer.ThreeGpp,
                SlockPreviewableVideoContainer.ThreeGpp2 -> true
                else -> false
            }
        // ArkUI Video's documented file formats are MP4, MKV, and TS.
        SlockVideoPlayerFormatProfile.OhosArkUiVideo ->
            when (container) {
                SlockPreviewableVideoContainer.Mp4,
                SlockPreviewableVideoContainer.Matroska,
                SlockPreviewableVideoContainer.MpegTs -> true
                else -> false
            }
    }

internal fun slockFilePreviewVideoContainer(
    fileName: String,
    mimeType: String
): SlockPreviewableVideoContainer? {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val byMime =
        when (normalizedMime) {
            "video/mp4" -> SlockPreviewableVideoContainer.Mp4
            "video/quicktime" -> SlockPreviewableVideoContainer.QuickTime
            "video/x-m4v" -> SlockPreviewableVideoContainer.M4v
            "video/3gpp" -> SlockPreviewableVideoContainer.ThreeGpp
            "video/3gpp2" -> SlockPreviewableVideoContainer.ThreeGpp2
            "video/webm" -> SlockPreviewableVideoContainer.WebM
            "video/x-matroska", "application/x-matroska" -> SlockPreviewableVideoContainer.Matroska
            "video/mp2t" -> SlockPreviewableVideoContainer.MpegTs
            "video/mpeg" -> SlockPreviewableVideoContainer.MpegPs
            "video/flv", "video/x-flv" -> SlockPreviewableVideoContainer.Flv
            else -> null
        }
    if (byMime != null) return byMime

    val extension =
        fileName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    return when (extension) {
        "mp4" -> SlockPreviewableVideoContainer.Mp4
        "mov", "qt" -> SlockPreviewableVideoContainer.QuickTime
        "m4v" -> SlockPreviewableVideoContainer.M4v
        "3gp", "3gpp" -> SlockPreviewableVideoContainer.ThreeGpp
        "3g2", "3gp2" -> SlockPreviewableVideoContainer.ThreeGpp2
        "webm" -> SlockPreviewableVideoContainer.WebM
        "mkv" -> SlockPreviewableVideoContainer.Matroska
        "ts", "m2ts", "mts" -> SlockPreviewableVideoContainer.MpegTs
        "mpg", "mpeg", "m2p", "ps" -> SlockPreviewableVideoContainer.MpegPs
        "flv" -> SlockPreviewableVideoContainer.Flv
        else -> null
    }
}

internal fun slockFilePreviewIsPreviewableVideoAttachment(fileName: String, mimeType: String): Boolean =
    slockFilePreviewVideoContainer(fileName = fileName, mimeType = mimeType)
        ?.let(::slockPlatformSupportsVideoAttachmentContainer) == true

// task #556: mp3 attachments play through the same Media3-backed player as
// video (audio-only source, blank surface). Scoped to mp3; video is scoped to
// the concrete platform player's documented container matrix, widened deliberately
// for task #134.
internal fun slockFilePreviewIsMp3AudioAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    if (normalizedMime.equals("audio/mpeg", ignoreCase = true)) return true
    if (normalizedMime.equals("audio/mp3", ignoreCase = true)) return true
    return fileName.substringBefore('?').substringBefore('#').endsWith(".mp3", ignoreCase = true)
}

private val SlockFilePreviewAudioMimeTypes =
    setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/aac",
        "audio/mp4",
        "audio/x-m4a",
        "audio/ogg",
        "audio/opus",
        "audio/webm",
        "audio/flac",
        "audio/x-flac"
    )

private val SlockFilePreviewSupportedAudioExtensions =
    setOf(
        "mp3",
        "wav",
        "m4a",
        "aac",
        "ogg",
        "oga",
        "opus",
        "weba",
        "flac"
    )

internal fun slockFilePreviewIsAudioAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    if (normalizedMime in SlockFilePreviewAudioMimeTypes) return true
    val cleanName = fileName.substringBefore('?').substringBefore('#').lowercase()
    val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "")
    return extension in SlockFilePreviewSupportedAudioExtensions
}

internal fun slockFilePreviewIsTextLikeAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    if (normalizedMime.startsWith("text/")) return true
    if (normalizedMime in SlockFilePreviewTextMimeTypes) return true
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in SlockFilePreviewTextFileExtensions
}

private val SlockFilePreviewTextMimeTypes =
    setOf(
        "application/csv",
        "application/ecmascript",
        "application/javascript",
        "application/json",
        "application/ld+json",
        "application/sql",
        "application/toml",
        "application/typescript",
        "application/x-javascript",
        "application/x-ndjson",
        "application/x-python-code",
        "application/x-sh",
        "application/x-yaml",
        "application/xml",
        "image/svg+xml"
    )

private val SlockFilePreviewTextFileExtensions =
    setOf(
        "bash",
        "c",
        "cc",
        "cfg",
        "cjs",
        "conf",
        "cpp",
        "cs",
        "css",
        "csv",
        "diff",
        "env",
        "go",
        "h",
        "hpp",
        "htm",
        "html",
        "ini",
        "java",
        "js",
        "json",
        "jsonl",
        "jsx",
        "kt",
        "kts",
        "less",
        "log",
        "mjs",
        "patch",
        "php",
        "properties",
        "py",
        "rb",
        "rs",
        "sass",
        "scss",
        "sh",
        "sql",
        "svg",
        "swift",
        "text",
        "toml",
        "ts",
        "tsx",
        "tsv",
        "txt",
        "xml",
        "yaml",
        "yml",
        "zsh"
    )

internal fun SlockWorkspaceFilePreview.toSlockFilePreviewPayload(path: String): SlockFilePreviewPayload =
    when (this) {
        is SlockWorkspaceFilePreview.Text ->
            SlockFilePreviewPayload(
                kind = if (markdown) "markdown" else "text",
                titleDesc =
                    if (markdown) {
                        MR.strings.file_preview_markdown.desc()
                    } else {
                        MR.strings.file_preview_text.desc()
                    },
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                content = content,
                markdown = markdown
            )
        is SlockWorkspaceFilePreview.Image ->
            SlockFilePreviewPayload(
                kind = "image",
                titleDesc = MR.strings.file_preview_image.desc(),
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                imageUrl = imageUrl,
                message = description.orEmpty(),
                messageDesc =
                    if (description.isNullOrBlank()) {
                        MR.strings.file_preview_error_image_unavailable.desc()
                    } else {
                        null
                    }
            )
        is SlockWorkspaceFilePreview.Unsupported ->
            SlockFilePreviewPayload(
                kind = "unsupported",
                titleDesc = MR.strings.file_preview_title.desc(),
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                message = reason,
                messageDesc = null
            )
        is SlockWorkspaceFilePreview.Error ->
            SlockFilePreviewPayload(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                subtitle = path,
                name = path,
                path = path,
                message = reason,
                messageDesc = null,
                error = reason
            )
        is SlockWorkspaceFilePreview.Loading ->
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "workspace",
                titleDesc = MR.strings.file_preview_title.desc(),
                subtitle = path,
                name = path,
                path = path,
                message = detail,
                messageDesc = if (detail.isBlank()) MR.strings.common_loading.desc() else null
            )
    }

@Composable
internal fun SlockFilePreviewPayloadContent(
    payload: SlockFilePreviewPayload,
    onOpenUrl: (String) -> Unit,
    downloadStatusLabel: String? = null,
    downloadInProgress: Boolean = false,
    downloadPaused: Boolean = false,
    downloadProgressPercent: Int? = null,
    downloadState: SlockFilePreviewDownloadUiState? = SlockFilePreviewDownloadUiState.NotDownloaded,
    primaryActionEnabled: Boolean = true,
    onDownload: (() -> Unit)? = null,
    onPauseDownload: (() -> Unit)? = null,
    onResumeDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    onImageGalleryIndexChange: ((Int) -> Unit)? = null,
    onExternalBrowserOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val onToggleDownload = if (downloadPaused) onResumeDownload else onPauseDownload
    val actionTerms = slockFilePreviewLocalizedActionTerms()
    val fallbackFile = stringResource(MR.strings.file_preview_fallback_file)
    if (payload.kind == "loading" && payload.shouldRenderImagePreview()) {
        SlockFilePreviewImageGallerySurface(
            payload = payload,
            onImageGalleryIndexChange = onImageGalleryIndexChange,
            modifier = modifier.fillMaxSize()
        ) {
            SlockFilePreviewImagePanel(
                source = payload.imageUrl,
                fallbackSource = payload.currentImageThumbnailUrl(),
                contentDescription = payload.name.ifBlank { payload.path },
                modifier = Modifier.fillMaxSize(),
                backgroundColor = SlockColor.SurfaceWhite,
                borderWidth = 0.dp,
                zoomable = true
            )
        }
        return
    }
    when (payload.kind) {
        "markdown" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewMarkdownPanel(
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    statusLabel = null,
                    status = "content",
                    body = payload.content,
                    error = null,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.fillMaxSize(),
                    performanceKey = "preview:${payload.path}",
                    textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    showChrome = false
                )
            }
        "text" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewTextPanel(
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    content = payload.content,
                    modifier = Modifier.fillMaxSize(),
                    showChrome = false
                )
            }
        "xlsx" -> {
            val xlsxCarrier = remember(payload.xlsxSheetsJson) { parseSlockFilePreviewXlsx(payload.xlsxSheetsJson) }
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                if (xlsxCarrier != null && xlsxCarrier.sheets.isNotEmpty()) {
                    SlockFilePreviewXlsxPanel(
                        name = payload.name.ifBlank { payload.path },
                        meta = payload.meta,
                        sheets = xlsxCarrier.sheets,
                        truncated = xlsxCarrier.truncated,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SlockFilePreviewGenericCard(
                        iconName = SlockLucideIconName.FileSpreadsheet,
                        name = payload.name.ifBlank { payload.path },
                        meta = payload.meta,
                        message = payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_no_preview) },
                        secondaryActionLabel =
                            onExternalBrowserOpen?.let { stringResource(MR.strings.file_preview_action_open_in_browser) },
                        secondaryActionIcon = SlockLucideIconName.ExternalLink,
                        secondaryActionEnabled = !downloadInProgress,
                        onSecondaryAction = onExternalBrowserOpen,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        "image" ->
            SlockFilePreviewImageGallerySurface(
                payload = payload,
                onImageGalleryIndexChange = onImageGalleryIndexChange,
                modifier = modifier.fillMaxSize()
            ) {
                if (payload.imageUrl != null) {
                    SlockFilePreviewDownloadStatusOverlay(
                        statusLabel = downloadStatusLabel,
                        showProgress = downloadInProgress,
                        progressPercent = downloadProgressPercent,
                        downloadPaused = downloadPaused,
                        onToggleDownload = onToggleDownload,
                        onStopDownload = onStopDownload,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        SlockFilePreviewImagePanel(
                            source = payload.imageUrl,
                            fallbackSource = payload.currentImageThumbnailUrl(),
                            contentDescription = payload.name.ifBlank { payload.path },
                            modifier = Modifier.fillMaxSize(),
                            backgroundColor = SlockColor.SurfaceWhite,
                            borderWidth = 0.dp,
                            zoomable = true
                        )
                    }
                } else {
                    SlockFilePreviewGenericCard(
                        iconName = SlockLucideIconName.Image,
                        name = payload.name.ifBlank { payload.path },
                        meta = payload.meta,
                        message = payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_error_image_unavailable) },
                        statusLabel = downloadStatusLabel,
                        showProgress = downloadInProgress,
                        progressPercent = downloadProgressPercent,
                        secondaryActionLabel =
                            onExternalBrowserOpen?.let { stringResource(MR.strings.file_preview_action_open_in_browser) },
                        secondaryActionIcon = SlockLucideIconName.ExternalLink,
                        secondaryActionEnabled = !downloadInProgress,
                        onSecondaryAction = onExternalBrowserOpen,
                        actionLabel =
                            onDownload
                                ?.takeIf { !downloadInProgress }
                                ?.let { slockFilePreviewPrimaryActionLabel(payload, downloadState, actionTerms) },
                        actionIcon =
                            if (downloadInProgress) {
                                null
                            } else {
                                slockFilePreviewPrimaryActionIcon(payload, downloadState)
                            },
                        actionEnabled = primaryActionEnabled,
                        onAction = onDownload,
                        downloadPaused = downloadPaused,
                        onToggleDownload = onToggleDownload,
                        onStopDownload = onStopDownload,
                        modifier = Modifier.fillMaxSize().padding(SlockSpacing.Md)
                    )
                }
            }
        "html" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                val previewUrl = payload.externalUrl.orEmpty()
                if (previewUrl.isNotBlank()) {
                    SlockSandboxWebView(
                        url = previewUrl,
                        allowScripts = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SlockFilePreviewMessage(
                        iconName = SlockLucideIconName.TriangleAlert,
                        title = stringResource(MR.strings.file_preview_unavailable),
                        detail = payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_error_load_html) },
                        modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md)
                    )
                }
            }
        "video" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewVideoPanel(
                    source = payload.videoUrl.orEmpty(),
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    fallbackMessage = payload.localizedMessage(),
                    audioOnly = payload.audioOnly,
                    modifier = Modifier.fillMaxSize()
                )
            }
        "loading" -> {
            SlockFilePreviewImageGallerySurface(
                payload = payload,
                onImageGalleryIndexChange = onImageGalleryIndexChange,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewLoadingIndicator(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        "error" ->
            SlockFilePreviewMessage(
                iconName = SlockLucideIconName.TriangleAlert,
                title = stringResource(MR.strings.file_preview_unavailable),
                detail =
                    slockFilePreviewVisibleErrorDetail(
                        localizedMessage = payload.localizedMessage(),
                        fallbackMessage = stringResource(MR.strings.file_preview_error_load_generic)
                    ),
                modifier = modifier.fillMaxWidth().padding(SlockSpacing.Md)
            )
        else ->
            SlockFilePreviewGenericCard(
                iconName = slockFilePreviewFileIconName(payload),
                name = payload.name.ifBlank { payload.path.ifBlank { fallbackFile } },
                meta = payload.meta,
                message = slockFilePreviewUnsupportedFileMessage(payload, downloadable = onDownload != null).localized(),
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                secondaryActionLabel =
                    onExternalBrowserOpen?.let { stringResource(MR.strings.file_preview_action_open_in_browser) },
                secondaryActionIcon = SlockLucideIconName.ExternalLink,
                secondaryActionEnabled = !downloadInProgress,
                onSecondaryAction = onExternalBrowserOpen,
                actionLabel =
                    onDownload
                        ?.takeIf { !downloadInProgress }
                        ?.let { slockFilePreviewPrimaryActionLabel(payload, downloadState, actionTerms) },
                actionIcon =
                    if (downloadInProgress) {
                        null
                    } else {
                        slockFilePreviewPrimaryActionIcon(payload, downloadState)
                    },
                actionEnabled = primaryActionEnabled,
                onAction = onDownload,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize().padding(SlockSpacing.Md)
            )
    }
}

@Composable
private fun SlockFilePreviewImageGallerySurface(
    payload: SlockFilePreviewPayload,
    onImageGalleryIndexChange: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progressLabel = payload.imageGalleryProgressLabel()
    var dragOffsetPx by remember(payload.imageGalleryIndex, payload.imageGallery.size) { mutableStateOf(0f) }
    val swipeThresholdPx =
        with(LocalDensity.current) {
            56.dp.toPx()
        }
    val galleryModifier =
        if (progressLabel != null && onImageGalleryIndexChange != null) {
            Modifier.pointerInput(payload.imageGalleryIndex, payload.imageGallery.size) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragDistance = 0f
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        val nextIndex = payload.nextImageGalleryIndex(dragDistance, swipeThresholdPx)
                        dragDistance = 0f
                        dragOffsetPx = 0f
                        nextIndex?.let(onImageGalleryIndexChange)
                    },
                    onDragCancel = {
                        dragDistance = 0f
                        dragOffsetPx = 0f
                    }
                ) { _, dragAmount ->
                    dragDistance += dragAmount
                    dragOffsetPx = payload.imageGalleryDragOffsetWithResistance(dragDistance)
                }
            }
        } else {
            Modifier
        }
    Box(modifier = modifier.clipToBounds().then(galleryModifier)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
        ) {
            content()
        }
        if (progressLabel != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = progressLabel,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Kuikly's image cache is reference-counted: when the last painter for a source is forgotten,
 * the native memory-cache entry is cleared. The gallery renders one visible item at a time, so
 * keep tiny invisible painters for only the current item and its immediate neighbors.
 */
@Composable
private fun SlockFilePreviewImageGalleryCacheKeepAlive(
    sources: Set<String>,
    modifier: Modifier = Modifier
) {
    if (sources.isEmpty()) return
    Box(
        modifier =
            modifier
                .size(1.dp)
                .alpha(0f)
                .clipToBounds()
    ) {
        sources.sorted().forEach { source ->
            key(source) {
                SlockCachedImage(
                    source = source,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

internal fun SlockFilePreviewPayload.previewIconName(): SlockLucideIconName =
    when (kind) {
        "markdown", "text" -> SlockLucideIconName.FileText
        "image" -> SlockLucideIconName.Image
        "video" -> SlockLucideIconName.Film
        "loading" -> SlockLucideIconName.LoaderCircle
        "error" -> SlockLucideIconName.TriangleAlert
        else -> slockFilePreviewFileIconName(this)
    }

@Composable
private fun SlockFilePreviewVideoPanel(
    source: String,
    name: String,
    meta: String,
    fallbackMessage: String,
    audioOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    var playControl by remember(source) { mutableStateOf(VideoPlayControl.PREPLAY) }
    var playState by remember(source) { mutableStateOf(PlayState.NONE) }
    var firstFrameVisible by remember(source) { mutableStateOf(false) }
    var currentTimeMs by remember(source) { mutableStateOf(0) }
    var totalTimeMs by remember(source) { mutableStateOf(0) }
    var seekRequest by remember(source) { mutableStateOf<SlockFilePreviewVideoSeekRequest?>(null) }
    val canPlay = source.isNotBlank()
    val isPlaying = playState == PlayState.PLAYING
    val nativeController = slockPlatformUsesNativeVideoController()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .heightIn(max = 420.dp)
                    .background(Color(0xFF000000))
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (canPlay) {
                SlockFilePreviewKuiklyVideo(
                    source = source,
                    playControl = playControl,
                    seekRequest = seekRequest,
                    onPlayStateChanged = { playState = it },
                    onPlayTimeChanged = { current, total ->
                        currentTimeMs = current
                        totalTimeMs = total
                    },
                    onFirstFrame = {
                        firstFrameVisible = true
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!canPlay) {
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.TriangleAlert,
                    title = fallbackMessage.ifBlank { stringResource(MR.strings.file_preview_error_load_media) },
                    detail = name.ifBlank { meta }
                )
            } else if (audioOnly && !nativeController) {
                // Audio sources render no frames — keep a persistent music
                // visual over the surface instead of a black box (task #556).
                // With the native controller the card is dropped entirely
                // (task #15): its play/seek cluster owns the surface and the
                // page header already shows the file name and meta.
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.FileMusic,
                    title = name.ifBlank { stringResource(MR.strings.file_preview_audio) },
                    detail = meta
                )
            } else if (!nativeController && !firstFrameVisible && !isPlaying) {
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.Play,
                    title = name.ifBlank { stringResource(MR.strings.file_preview_video) },
                    detail = meta
                )
            }
            if (!nativeController) {
                SlockFilePreviewVideoControls(
                    isPlaying = isPlaying,
                    canPlay = canPlay,
                    currentTimeMs = currentTimeMs,
                    totalTimeMs = totalTimeMs,
                    playState = playState,
                    onTogglePlay = {
                        playControl = if (isPlaying) VideoPlayControl.PAUSE else VideoPlayControl.PLAY
                    },
                    onSeek = { seekTimeMs ->
                        val nextTime = seekTimeMs.coerceIn(0, totalTimeMs.coerceAtLeast(0))
                        currentTimeMs = nextTime
                        if (playState == PlayState.PLAY_END) {
                            playState = PlayState.PAUSED
                        }
                        seekRequest = SlockFilePreviewVideoSeekRequest(
                            id = (seekRequest?.id ?: 0) + 1,
                            timeMs = nextTime
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun SlockFilePreviewVideoControls(
    isPlaying: Boolean,
    canPlay: Boolean,
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xDD000000))
                .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
                .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockBrutalIconButton(
                iconName = if (isPlaying) SlockLucideIconName.Pause else SlockLucideIconName.Play,
                onClick = { if (canPlay) onTogglePlay() },
                enabled = canPlay,
                size = 30.dp,
                shadowOffset = 2.dp,
                iconSize = 16.dp,
                backgroundColor = SlockColor.SurfaceWhite
            )
            Text(
                text = slockFilePreviewLocalizedVideoTimeLabel(currentTimeMs, totalTimeMs, playState),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.SurfaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        SlockFilePreviewVideoProgressBar(
            progressPercent = slockFilePreviewVideoProgressPercent(currentTimeMs, totalTimeMs),
            totalTimeMs = totalTimeMs,
            onSeek = onSeek.takeIf { canPlay && totalTimeMs > 0 }
        )
    }
}

@Composable
private fun SlockFilePreviewVideoProgressBar(
    progressPercent: Int?,
    totalTimeMs: Int,
    onSeek: ((Int) -> Unit)? = null
) {
    val percent = progressPercent?.coerceIn(0, 100)
    var widthPx by remember { mutableStateOf(0f) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .onGloballyPositioned { coordinates ->
                    widthPx = coordinates.size.width.toFloat()
                }
                .then(
                    if (onSeek != null) {
                        Modifier.pointerInput(totalTimeMs, onSeek, widthPx) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val target = slockFilePreviewVideoSeekTimeFromTap(
                                        tapX = offset.x,
                                        widthPx = widthPx,
                                        totalTimeMs = totalTimeMs
                                    )
                                    if (target != null) {
                                        onSeek.invoke(target)
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(Color(0x66000000))
                    .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
        ) {
            when (percent) {
                null -> {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                    )
                }
                else -> {
                    if (percent > 0) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(percent.toFloat())
                                    .fillMaxSize()
                                    .background(SlockColor.AccentCyan)
                        )
                    }
                    if (percent < 100) {
                        Box(
                            modifier =
                                Modifier
                                    .weight((100 - percent).toFloat())
                                    .fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlockFilePreviewVideoOverlayText(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .background(Color(0xCC000000))
                .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(34.dp), color = "#FFFFFFFF")
        Text(
            text = title,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.SurfaceWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = SlockType.Caption,
                color = SlockColor.SurfaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewKuiklyVideo(
    source: String,
    playControl: VideoPlayControl,
    seekRequest: SlockFilePreviewVideoSeekRequest?,
    onPlayStateChanged: (PlayState) -> Unit,
    onPlayTimeChanged: (Int, Int) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    MakeKuiklyComposeNode(
        factory = { VideoView() },
        modifier = modifier,
        viewUpdate = { view ->
            view.getViewAttr().apply {
                src(source)
                resizeModeToContain()
                muted(false)
                rate(1f)
                playControl(playControl)
                seekRequest?.let { seekToTimeMs(it) }
            }
            view.getViewEvent().apply {
                playStateDidChanged { state, _ -> onPlayStateChanged(state) }
                playTimeDidChanged { current, total -> onPlayTimeChanged(current, total) }
                firstFrameDidDisplay { onFirstFrame() }
            }
        }
    )
}

private fun com.tencent.kuikly.core.views.VideoAttr.seekToTimeMs(request: SlockFilePreviewVideoSeekRequest) {
    setProp(SlockFilePreviewVideoSeekProp, "${request.id}:${request.timeMs}")
}

internal data class SlockFilePreviewVideoStateTerms(
    val playing: String,
    val buffering: String,
    val paused: String,
    val ended: String,
    val playbackFailed: String,
    val ready: String
)

internal fun slockFilePreviewVideoTimeLabel(
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState,
    terms: SlockFilePreviewVideoStateTerms
): String {
    val stateLabel =
        when (playState) {
            PlayState.PLAYING -> terms.playing
            PlayState.BUFFERING -> terms.buffering
            PlayState.PAUSED -> terms.paused
            PlayState.PLAY_END -> terms.ended
            PlayState.ERROR -> terms.playbackFailed
            PlayState.NONE -> terms.ready
        }
    val current = currentTimeMs.slockFilePreviewVideoDurationLabel()
    val total = totalTimeMs.takeIf { it > 0 }?.slockFilePreviewVideoDurationLabel()
    return if (total == null) {
        "$stateLabel · $current"
    } else {
        "$stateLabel · $current / $total"
    }
}

@Composable
private fun slockFilePreviewLocalizedVideoTimeLabel(
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState
): String =
    slockFilePreviewVideoTimeLabel(
        currentTimeMs = currentTimeMs,
        totalTimeMs = totalTimeMs,
        playState = playState,
        terms =
            SlockFilePreviewVideoStateTerms(
                playing = stringResource(MR.strings.file_preview_media_playing),
                buffering = stringResource(MR.strings.file_preview_media_buffering),
                paused = stringResource(MR.strings.file_preview_media_paused),
                ended = stringResource(MR.strings.file_preview_media_ended),
                playbackFailed = stringResource(MR.strings.file_preview_media_playback_failed),
                ready = stringResource(MR.strings.file_preview_media_ready)
            )
    )

internal fun slockFilePreviewVideoProgressPercent(currentTimeMs: Int, totalTimeMs: Int): Int? {
    if (totalTimeMs <= 0) return null
    return ((currentTimeMs.coerceAtLeast(0).toFloat() / totalTimeMs.toFloat()) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
}

internal fun slockFilePreviewVideoSeekTimeFromTap(tapX: Float, widthPx: Float, totalTimeMs: Int): Int? {
    if (widthPx <= 0f || totalTimeMs <= 0) return null
    return ((tapX.coerceIn(0f, widthPx) / widthPx) * totalTimeMs.toFloat())
        .roundToInt()
        .coerceIn(0, totalTimeMs)
}

private fun Int.slockFilePreviewVideoDurationLabel(): String {
    val totalSeconds = (coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun SlockFilePreviewPayload.imageGalleryProgressLabel(): String? {
    val size = imageGallery.size
    if (size <= 1) return null
    val index = imageGalleryIndex.coerceIn(0, size - 1)
    return "${index + 1}/$size"
}

internal fun SlockFilePreviewPayload.canCompleteComposerImageSelection(): Boolean =
    if (imageGallery.isNotEmpty()) {
        imageGallery.all { image -> image.composerImageFileRef.isNotBlank() }
    } else {
        composerImageFileRef.isNotBlank()
    }

internal fun SlockFilePreviewPayload.showsComposerOriginalImageOption(): Boolean =
    composerImageOriginalOptionEnabled &&
        composerImageCanComplete &&
        composerImageActionId.isNotBlank()

internal fun SlockFilePreviewPayload.composerOriginalImageSelected(): Boolean =
    showsComposerOriginalImageOption() && !composerImageCompressAfterEdit

internal fun SlockFilePreviewPayload.withComposerOriginalImage(enabled: Boolean): SlockFilePreviewPayload =
    copy(composerImageCompressAfterEdit = !enabled)

/**
 * Returns whether at least one composer image still needs its final delivery representation.
 *
 * The source ref is deliberately not used as the completion signal: a picker ref may point at
 * an uncompressed source, and a PNG editor snapshot may need a JPEG pass.  Callers use this
 * predicate to start the asynchronous finalization workflow from the Done action; it must not be
 * used to disable that action (a pending item is exactly what Done has to finalize).
 */
internal fun SlockFilePreviewPayload.hasPendingComposerImageFinalization(): Boolean {
    if (!composerImageCanComplete) return false
    return if (imageGallery.isNotEmpty()) {
        imageGallery.any { image ->
            !image.composerImageEditCompleted ||
                (composerImageCompressAfterEdit && !image.composerImageCompressed)
        }
    } else {
        !composerImageEditCompleted ||
            (composerImageCompressAfterEdit && !composerImageCompressed)
    }
}

/** Alias with a noun-oriented name for call sites that describe a gallery workflow. */
internal fun SlockFilePreviewPayload.composerImageGalleryNeedsFinalization(): Boolean =
    hasPendingComposerImageFinalization()

internal fun SlockFilePreviewGalleryImage.isComposerImageFinalized(
    compressAfterEdit: Boolean
): Boolean =
    composerImageEditCompleted && (!compressAfterEdit || composerImageCompressed)

/**
 * Marks an existing source ref as the final representation without changing its ownership.  This
 * is used for the explicit "Original image" policy: the picker/editor ref is accepted as-is, but
 * its completion state is still recorded so a later Done tap cannot deliver it twice.
 */
internal fun SlockFilePreviewPayload.markComposerImageFinalized(
    compressed: Boolean
): SlockFilePreviewPayload {
    val currentIndex = imageGallery.takeIf { it.isNotEmpty() }?.let {
        imageGalleryIndex.coerceIn(0, it.lastIndex)
    }
    val updatedGallery =
        currentIndex?.let { index ->
            imageGallery.mapIndexed { itemIndex, image ->
                if (itemIndex == index) {
                    image.copy(
                        composerImageEditCompleted = true,
                        composerImageCompressed = compressed
                    )
                } else {
                    image
                }
            }
        } ?: imageGallery
    val selectedImage = currentIndex?.let(updatedGallery::get)
    return copy(
        composerImageEditCompleted = true,
        composerImageCompressed = compressed,
        imageGallery = updatedGallery,
        composerImageFileRef = selectedImage?.composerImageFileRef ?: composerImageFileRef,
        composerImageFileName = selectedImage?.name ?: composerImageFileName,
        composerImageMimeType = selectedImage?.mimeType ?: composerImageMimeType,
        composerImageSizeBytes = selectedImage?.composerImageSizeBytes ?: composerImageSizeBytes,
        composerImagePreviewUri =
            selectedImage?.composerImagePreviewUri?.ifBlank { selectedImage.thumbnailUrl.orEmpty() }
                ?: composerImagePreviewUri
    )
}

/** Mark one arbitrary gallery item final while preserving the current selection. */
internal fun SlockFilePreviewPayload.markComposerImageGalleryItemFinalized(
    index: Int,
    compressed: Boolean
): SlockFilePreviewPayload {
    if (index !in imageGallery.indices) return this
    val updatedGallery =
        imageGallery.mapIndexed { itemIndex, image ->
            if (itemIndex == index) {
                image.copy(
                    composerImageEditCompleted = true,
                    composerImageCompressed = compressed
                )
            } else {
                image
            }
        }
    val selectedIndex = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    val selectedImage = updatedGallery[selectedIndex]
    return copy(
        imageGallery = updatedGallery,
        composerImageEditCompleted = selectedImage.composerImageEditCompleted,
        composerImageCompressed = selectedImage.composerImageCompressed,
        composerImageFileRef = selectedImage.composerImageFileRef,
        composerImageFileName = selectedImage.name,
        composerImageMimeType = selectedImage.mimeType,
        composerImageSizeBytes = selectedImage.composerImageSizeBytes,
        composerImagePreviewUri = selectedImage.composerImagePreviewUri
    )
}

/** Convert an explicitly tracked gallery item back into a bridge-ready local file descriptor. */
internal fun SlockFilePreviewGalleryImage.toComposerImagePlatformFile(): PlatformFile =
    PlatformFile(
        id = attachmentId.ifBlank { composerImageFileRef.ifBlank { "composer-image" } },
        fileName = name.ifBlank { "image.png" },
        mimeType = mimeType.takeIf { it.isNotBlank() },
        sizeBytes = composerImageSizeBytes,
        previewUri = composerImagePreviewUri.ifBlank { thumbnailUrl.orEmpty() }
            .takeIf { it.isNotBlank() },
        fileRef = composerImageFileRef.takeIf { it.isNotBlank() },
        source = "composer_image_gallery"
    )

/** Convert a single-image payload into the same descriptor used by gallery finalization. */
internal fun SlockFilePreviewPayload.toComposerImagePlatformFile(): PlatformFile =
    PlatformFile(
        id = composerImageActionId.ifBlank { composerImageFileRef.ifBlank { "composer-image" } },
        fileName = composerImageFileName.ifBlank {
            composerImageFileRef.substringAfterLast('/').ifBlank { "image.png" }
        },
        mimeType = composerImageMimeType.ifBlank { externalMimeType }.takeIf { it.isNotBlank() },
        sizeBytes = composerImageSizeBytes,
        previewUri = composerImagePreviewUri.ifBlank { imageUrl.orEmpty() }
            .takeIf { it.isNotBlank() },
        fileRef = composerImageFileRef.takeIf { it.isNotBlank() },
        source = "composer_image_payload"
    )

/**
 * Applies one final editor/bypass result to the process-local payload.  The caller must have
 * already admitted the file through the native response parser when [compressed] is true.
 * Invalid/blank refs are rejected by returning the original payload unchanged.
 */
internal fun SlockFilePreviewPayload.withComposerImageFinalResult(
    file: PlatformFile,
    compressed: Boolean
): SlockFilePreviewPayload {
    val fileRef = file.fileRef?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val mimeType =
        file.mimeType
            ?.trim()
            ?.substringBefore(';')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: if (compressed) "image/jpeg" else KuiklySnapshotMimeType
    if (compressed && mimeType != "image/jpeg") return this
    val currentIndex = imageGallery.takeIf { it.isNotEmpty() }?.let {
        imageGalleryIndex.coerceIn(0, it.lastIndex)
    }
    val currentImage = currentIndex?.let(imageGallery::get)
    val fallbackName =
        currentImage?.name
            .orEmpty()
            .ifBlank { currentComposerImageFileName() }
            .ifBlank { "image.png" }
    val fileName = file.fileName.trim().ifBlank {
        if (compressed) fallbackName.toJpegFileName() else fallbackName.toKuiklySnapshotPngFileName()
    }
    val previewUri = file.previewUri?.trim()?.takeIf { it.isNotBlank() } ?: fileRef
    val sizeBytes = file.sizeBytes?.takeIf { it > 0L }
    val updatedGallery =
        currentIndex?.let { index ->
            imageGallery.mapIndexed { itemIndex, image ->
                if (itemIndex != index) {
                    image
                } else {
                    image.copy(
                        name = fileName,
                        mimeType = mimeType,
                        sizeLabel = sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(),
                        thumbnailUrl = previewUri,
                        composerImageFileRef = fileRef,
                        composerImagePreviewUri = previewUri,
                        composerImageSizeBytes = sizeBytes,
                        composerImageOwnedTemporary = true,
                        composerImageEditCompleted = true,
                        composerImageCompressed = compressed
                    )
                }
            }
        } ?: imageGallery
    val ownedRefs =
        (composerImageOwnedTemporaryFileRefs + fileRef)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    return copy(
        name = fileName,
        imageUrl = previewUri,
        externalFileName = fileName,
        externalMimeType = mimeType,
        composerImageFileRef = fileRef,
        composerImageFileName = fileName,
        composerImageMimeType = mimeType,
        composerImageSizeBytes = sizeBytes,
        composerImagePreviewUri = previewUri,
        composerImageOwnedTemporaryFileRefs = ownedRefs,
        composerImageEditCompleted = true,
        composerImageCompressed = compressed,
        imageGallery = updatedGallery
    )
}

/** String-field convenience overload for native/event call sites. */
internal fun SlockFilePreviewPayload.withComposerImageFinalResult(
    fileRef: String,
    fileName: String,
    mimeType: String,
    previewUri: String? = fileRef,
    sizeBytes: Long? = null,
    compressed: Boolean = false
): SlockFilePreviewPayload =
    withComposerImageFinalResult(
        file =
            PlatformFile(
                id = "composer-final-${fileRef.hashCode().toUInt().toString(16)}",
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                previewUri = previewUri,
                fileRef = fileRef,
                source = slockFilePreviewComposerImageEventSource(compressed)
            ),
        compressed = compressed
    )

/** Applies a result to an arbitrary gallery ordinal, preserving the selected index. */
internal fun SlockFilePreviewPayload.withComposerImageGalleryFinalResult(
    index: Int,
    file: PlatformFile,
    compressed: Boolean
): SlockFilePreviewPayload {
    if (imageGallery.isEmpty() || index !in imageGallery.indices) return this
    val fileRef = file.fileRef?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val mimeType =
        file.mimeType
            ?.trim()
            ?.substringBefore(';')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: if (compressed) "image/jpeg" else KuiklySnapshotMimeType
    if (compressed && mimeType != "image/jpeg") return this
    val sourceImage = imageGallery[index]
    val fallbackName =
        sourceImage.name.ifBlank {
            if (compressed) "image.jpg" else "image.png"
        }
    val fileName = file.fileName.trim().ifBlank {
        if (compressed) fallbackName.toJpegFileName() else fallbackName.toKuiklySnapshotPngFileName()
    }
    val previewUri = file.previewUri?.trim()?.takeIf { it.isNotBlank() } ?: fileRef
    val sizeBytes = file.sizeBytes?.takeIf { it > 0L }
    val updatedImage =
        sourceImage.copy(
            name = fileName,
            mimeType = mimeType,
            sizeLabel = sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(),
            thumbnailUrl = previewUri,
            composerImageFileRef = fileRef,
            composerImagePreviewUri = previewUri,
            composerImageSizeBytes = sizeBytes,
            composerImageOwnedTemporary = true,
            composerImageEditCompleted = true,
            composerImageCompressed = compressed
        )
    val updatedGallery = imageGallery.toMutableList().also { it[index] = updatedImage }
    val nextOwnedRefs =
        (composerImageOwnedTemporaryFileRefs + fileRef)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    val selectedIndex = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    val selectedImage = updatedGallery[selectedIndex]
    return copy(
        name = selectedImage.name,
        imageUrl = selectedImage.composerImagePreviewUri.ifBlank { selectedImage.thumbnailUrl.orEmpty() }
            .takeIf { it.isNotBlank() },
        externalFileName = selectedImage.name,
        externalMimeType = selectedImage.mimeType,
        composerImageFileRef = selectedImage.composerImageFileRef,
        composerImageFileName = selectedImage.name,
        composerImageMimeType = selectedImage.mimeType,
        composerImageSizeBytes = selectedImage.composerImageSizeBytes,
        composerImagePreviewUri = selectedImage.composerImagePreviewUri,
        composerImageOwnedTemporaryFileRefs = nextOwnedRefs,
        composerImageEditCompleted = selectedImage.composerImageEditCompleted,
        composerImageCompressed = selectedImage.composerImageCompressed,
        imageGallery = updatedGallery
    )
}

internal fun SlockFilePreviewPayload.withComposerImageEditResult(fileRef: String): SlockFilePreviewPayload {
    val cleanRef = fileRef.trim().takeIf { it.isNotBlank() } ?: return this
    val editedFileName =
        imageGallery.getOrNull(imageGalleryIndex.coerceIn(0, imageGallery.lastIndex.coerceAtLeast(0)))?.name
            .orEmpty()
            .ifBlank { currentComposerImageFileName() }
            .toKuiklySnapshotPngFileName()
    return withComposerImageFinalResult(
        file =
            PlatformFile(
                id = "composer-editor-${cleanRef.hashCode().toUInt().toString(16)}",
                fileName = editedFileName,
                mimeType = KuiklySnapshotMimeType,
                previewUri = cleanRef,
                fileRef = cleanRef,
                source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE
            ),
        compressed = false
    )
}

private const val KuiklySnapshotMimeType = "image/png"

private fun SlockFilePreviewPayload.currentComposerImageFileName(): String =
    composerImageFileName.ifBlank { externalFileName.ifBlank { name } }

private fun String.toKuiklySnapshotPngFileName(): String {
    val leaf = substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
    val stem = leaf.substringBeforeLast('.', missingDelimiterValue = leaf).ifBlank { "image" }
    return "$stem.png"
}

private fun String.toJpegFileName(): String {
    val leaf = substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
    val stem = leaf.substringBeforeLast('.', missingDelimiterValue = leaf).ifBlank { "image" }
    return "$stem.jpg"
}

internal data class SlockFilePreviewImageGalleryResolutionState(
    val resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap(),
    val inFlightAttachmentIds: List<String> = emptyList()
) {
    internal fun beginResolution(payload: SlockFilePreviewPayload): SlockFilePreviewImageGalleryResolutionStart {
        val attachmentId = payload.attachmentId
        if (
            !payload.isPendingThreadImageGalleryResolution() ||
            attachmentId in resolvedPayloadByAttachmentId
        ) {
            return SlockFilePreviewImageGalleryResolutionStart(
                state = this,
                requestPayload = null,
                cancelAttachmentId = null
            )
        }
        if (attachmentId in inFlightAttachmentIds) {
            return SlockFilePreviewImageGalleryResolutionStart(
                state = copy(inFlightAttachmentIds = (inFlightAttachmentIds - attachmentId) + attachmentId),
                requestPayload = null,
                cancelAttachmentId = null
            )
        }
        val cancelAttachmentId =
            inFlightAttachmentIds
                .takeIf { it.size >= SlockFilePreviewImageGalleryMaxInFlightRequests }
                ?.firstOrNull()
        val nextInFlightAttachmentIds =
            (cancelAttachmentId?.let(inFlightAttachmentIds::minus) ?: inFlightAttachmentIds) + attachmentId
        return SlockFilePreviewImageGalleryResolutionStart(
            state = copy(inFlightAttachmentIds = nextInFlightAttachmentIds),
            requestPayload = payload,
            cancelAttachmentId = cancelAttachmentId
        )
    }

    internal fun completeResolution(
        requestPayload: SlockFilePreviewPayload,
        resolvedPayload: SlockFilePreviewPayload
    ): SlockFilePreviewImageGalleryResolutionState {
        val attachmentId = requestPayload.attachmentId
        val nextResolvedPayloads =
            if (
                resolvedPayload.attachmentId == attachmentId &&
                resolvedPayload.isTerminalThreadImageGalleryPayload() &&
                resolvedPayload.hasResolvedRemoteImageComparedTo(requestPayload)
            ) {
                resolvedPayloadByAttachmentId + (attachmentId to resolvedPayload)
            } else {
                resolvedPayloadByAttachmentId
            }
        return copy(
            resolvedPayloadByAttachmentId = nextResolvedPayloads,
            inFlightAttachmentIds = inFlightAttachmentIds - attachmentId
        )
    }

    internal fun finishResolution(attachmentId: String): SlockFilePreviewImageGalleryResolutionState =
        copy(inFlightAttachmentIds = inFlightAttachmentIds - attachmentId)
}

internal data class SlockFilePreviewImageGalleryResolutionStart(
    val state: SlockFilePreviewImageGalleryResolutionState,
    val requestPayload: SlockFilePreviewPayload?,
    val cancelAttachmentId: String?
)

internal fun Map<String, Job>.ownsImageGalleryResolution(
    attachmentId: String,
    job: Job?
): Boolean =
    job != null && this[attachmentId] === job

private fun SlockFilePreviewPayload.isPendingThreadImageGalleryResolution(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        imageGallery.isNotEmpty() &&
        kind == "loading" &&
        requestedKind == "image"

private fun SlockFilePreviewPayload.isTerminalThreadImageGalleryPayload(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        imageGallery.isNotEmpty() &&
        kind != "loading" &&
        requestedKind == "image"

internal fun SlockFilePreviewPayload.withResolvedImageGalleryPayload(
    resolvedPayload: SlockFilePreviewPayload
): SlockFilePreviewPayload? {
    if (!isPendingThreadImageGalleryResolution()) return null
    if (attachmentId != resolvedPayload.attachmentId || !resolvedPayload.isTerminalThreadImageGalleryPayload()) return null
    return resolvedPayload.copy(
        imageGallery = imageGallery,
        imageGalleryIndex = imageGalleryIndex
    )
}

private fun SlockFilePreviewPayload.hasResolvedRemoteImageComparedTo(
    requestPayload: SlockFilePreviewPayload
): Boolean {
    val resolvedSource = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val initialSource = requestPayload.imageUrl?.trim()?.takeIf { it.isNotBlank() }
    return resolvedSource != initialSource
}

private fun SlockFilePreviewPayload.imageGalleryResolutionFailurePayload(): SlockFilePreviewPayload =
    copy(
        kind = "image",
        titleDesc = MR.strings.file_preview_image.desc(),
        message = "",
        messageDesc =
            if (imageUrl.isNullOrBlank()) {
                MR.strings.file_preview_error_image_unavailable.desc()
            } else {
                null
            },
        error = "image_preview_resolve_failed"
    )

internal fun SlockFilePreviewPayload.imageGalleryKeepAliveSources(
    resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap()
): Set<String> {
    if (imageGallery.isEmpty()) return emptySet()
    val currentIndex = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    val retainedIndices =
        (currentIndex - 1..currentIndex + 1)
            .filter(imageGallery.indices::contains)
    return buildSet {
        retainedIndices.forEach { index ->
            val galleryImage = imageGallery[index]
            galleryImage.thumbnailUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            resolvedPayloadByAttachmentId[galleryImage.attachmentId]
                ?.imageUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            if (index == currentIndex) {
                imageUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                currentImageThumbnailUrl()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }
}

internal fun SlockFilePreviewPayload.withImageGalleryIndex(
    index: Int,
    resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap()
): SlockFilePreviewPayload? {
    if (imageGallery.isEmpty()) return null
    val nextIndex = index.coerceIn(0, imageGallery.lastIndex)
    val nextImage = imageGallery.getOrNull(nextIndex) ?: return null
    resolvedPayloadByAttachmentId[nextImage.attachmentId]?.let { resolvedPayload ->
        return resolvedPayload.copy(
            imageGallery = imageGallery,
            imageGalleryIndex = nextIndex
        )
    }
    return nextImage.toPreviewPayload(previous = this, imageGalleryIndex = nextIndex)
}

internal fun SlockFilePreviewPayload.nextImageGalleryIndex(
    dragDistancePx: Float,
    thresholdPx: Float
): Int? {
    if (imageGallery.size <= 1) return null
    return when {
        dragDistancePx <= -thresholdPx && imageGalleryIndex < imageGallery.lastIndex -> imageGalleryIndex + 1
        dragDistancePx >= thresholdPx && imageGalleryIndex > 0 -> imageGalleryIndex - 1
        else -> null
    }
}

private fun SlockFilePreviewPayload.imageGalleryDragOffsetWithResistance(dragDistancePx: Float): Float {
    if (imageGallery.size <= 1) return 0f
    val index = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    return when {
        dragDistancePx > 0f && index == 0 -> dragDistancePx * 0.35f
        dragDistancePx < 0f && index == imageGallery.lastIndex -> dragDistancePx * 0.35f
        else -> dragDistancePx
    }
}

@Composable
internal fun SlockFilePreviewPayload.previewHeaderTitle(): String {
    val fallbackFile = stringResource(MR.strings.file_preview_fallback_file)
    return slockFilePreviewHeaderTitle(
        fallbackFile = fallbackFile,
        localizedTitle = titleDesc.localized(),
    )
}

internal fun SlockFilePreviewPayload.slockFilePreviewHeaderTitle(
    fallbackFile: String,
    localizedTitle: String,
): String {
    val titleSource =
        name
            .ifBlank { path }
            .ifBlank { externalFileName }
            .ifBlank { title }
            .ifBlank {
                if (source == SlockFilePreviewSourceThreadAttachment) fallbackFile else localizedTitle
            }
            .ifBlank { fallbackFile }
    return titleSource.slockFilePreviewFileName(fallbackFile)
}

@Composable
internal fun SlockFilePreviewPayload.previewHeaderSubtitle(): String? {
    val headerTitle = previewHeaderTitle()
    return slockFilePreviewVisibleSubtitle(headerTitle)
}

internal fun SlockFilePreviewPayload.slockFilePreviewVisibleSubtitle(headerTitle: String): String? =
    listOf(meta.withoutUserVisibleMimeType(), path, subtitle.withoutUserVisibleMimeType())
        .asSequence()
        .map { it.trim() }
        .firstOrNull { candidate ->
            candidate.isNotBlank() &&
                candidate != headerTitle &&
                candidate.slockFilePreviewFileName(fallback = "") != headerTitle &&
                !candidate.endsWith("preview", ignoreCase = true)
        }

internal fun String.withoutUserVisibleMimeType(): String =
    split('·')
        .asSequence()
        .map { it.trim() }
        .filterNot { it.isMimeTypePresentationSegment() }
        .filter { it.isNotBlank() }
        .joinToString(" · ")

private fun String.isMimeTypePresentationSegment(): Boolean {
    val normalized = substringBefore(';').trim().lowercase()
    val topLevel = normalized.substringBefore('/', missingDelimiterValue = "")
    if (topLevel !in SlockPresentationMimeTopLevels) return false
    val subtype = normalized.substringAfter('/', missingDelimiterValue = "")
    return subtype.isNotBlank() && subtype.none { it.isWhitespace() }
}

@Composable
private fun SlockFilePreviewPayload.localizedMessage(): String =
    message.ifBlank { messageDesc?.localized().orEmpty() }

internal fun slockFilePreviewVisibleErrorDetail(
    localizedMessage: String,
    fallbackMessage: String
): String =
    localizedMessage.takeIf { it.isNotBlank() }
        ?: fallbackMessage

private fun String.slockFilePreviewFileName(fallback: String): String {
    val normalized = trim().replace('\\', '/')
    return normalized.substringAfterLast('/').ifBlank { normalized.ifBlank { fallback } }
}

private fun KuiklyThreadAttachmentItem.toSlockFilePreviewGalleryImage(): SlockFilePreviewGalleryImage =
    SlockFilePreviewGalleryImage(
        attachmentId = id,
        name = name,
        mimeType = mimeType,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl
    )

private fun SlockFilePreviewGalleryImage.toPreviewPayload(
    previous: SlockFilePreviewPayload,
    imageGalleryIndex: Int
): SlockFilePreviewPayload {
    val title = name
    val externalFileName = name.ifBlank { "attachment-${attachmentId.take(8)}" }
    val meta = sizeLabel
    if (previous.composerImageActionId.isNotBlank() && previous.composerImageCanComplete) {
        return previous.copy(
            kind = "image",
            requestedKind = "image",
            title = "",
            titleDesc = MR.strings.file_preview_image.desc(),
            subtitle = title,
            name = title,
            path = title,
            meta = meta,
            imageUrl = composerImagePreviewUri.ifBlank { thumbnailUrl.orEmpty() }.takeIf { it.isNotBlank() },
            message = "",
            messageDesc = null,
            error = null,
            externalFileName = externalFileName,
            externalMimeType = mimeType,
            source = "",
            attachmentId = "",
            composerImageReplaceAttachmentId = composerImageReplaceAttachmentId,
            composerImageFileRef = composerImageFileRef,
            composerImageFileName = title,
            composerImageMimeType = mimeType,
            composerImageSizeBytes = composerImageSizeBytes,
            composerImagePreviewUri = composerImagePreviewUri.ifBlank { thumbnailUrl.orEmpty() },
            composerImageCompressAfterEdit = previous.composerImageCompressAfterEdit,
            composerImageEditCompleted = composerImageEditCompleted,
            composerImageCompressed = composerImageCompressed,
            imageGalleryIndex = imageGalleryIndex
        )
    }
    return previous.copy(
        kind = "loading",
        requestedKind = "image",
        title = "",
        titleDesc = MR.strings.file_preview_image.desc(),
        subtitle = title,
        name = title,
        path = title,
        meta = meta,
        content = "",
        markdown = false,
        imageUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
        message = "",
        messageDesc = MR.strings.common_loading.desc(),
        error = null,
        externalUrl = null,
        videoUrl = null,
        externalFileName = externalFileName,
        externalMimeType = mimeType,
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = attachmentId,
        imageGalleryIndex = imageGalleryIndex
    )
}

@Composable
internal fun SlockFilePreviewOverflowMenuButton(
    onOpenMenu: (SlockPopoverAnchor) -> Unit,
    modifier: Modifier = Modifier
) {
    val menuAnchor = remember { SlockLiveRootAnchorRef(identity = "file_preview_overflow") }
    SlockBrutalIconButton(
        iconName = SlockLucideIconName.Ellipsis,
        onClick = {
            menuAnchor.resolve(SlockPopoverLocalPoint.BottomEnd)?.let(onOpenMenu)
        },
        size = 30.dp,
        shadowOffset = 2.dp,
        modifier = modifier.slockCaptureLiveRootAnchor(menuAnchor)
    )
}

private fun SlockWorkspaceFilePreview.Text.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Image.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Unsupported.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun Long?.slockFilePreviewSizeLabel(): String? {
    val bytes = this ?: return null
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

private val SlockPresentationMimeTopLevels =
    setOf("application", "audio", "chemical", "example", "font", "image", "message", "model", "multipart", "text", "video")

internal data class SlockFilePreviewDownloadStatusTerms(
    val downloading: String,
    val paused: String,
    val saving: String
)

internal data class SlockAttachmentOpenResultTerms(
    val downloadStopped: String,
    val unableToDownloadAttachment: String,
    val noAppCanOpenFile: String,
    val storageUnavailable: String,
    val downloadFailed: String,
    val unableToOpenAttachment: String
)

internal fun slockAttachmentOpenResultMessage(
    status: String,
    terms: SlockAttachmentOpenResultTerms
): String? =
    // Native messages are diagnostic-only: shared semantic status owns user-visible copy.
    when (status) {
        "opened", "" -> null
        "save_cancelled" -> null
        "cancelled" -> terms.downloadStopped
        "missing" -> terms.unableToDownloadAttachment
        "no_app" -> terms.noAppCanOpenFile
        "storage_failed" -> terms.storageUnavailable
        "download_failed" -> terms.downloadFailed
        else -> terms.unableToOpenAttachment
    }

internal fun slockAttachmentOpenDiagnosticStatus(status: String?): String {
    val normalized = status?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "opened", "cancelled", "missing", "no_app", "storage_failed", "download_failed", "failed" -> normalized
        "" -> "none"
        else -> "other"
    }
}

internal fun slockFilePreviewExternalOpenRejectionReason(
    canOpenWith: Boolean,
    exportInProgress: Boolean,
    downloadStatus: String,
    hasOwner: Boolean,
    hasPolicy: Boolean
): String? =
    when {
        !canOpenWith -> "unsupported"
        exportInProgress -> "export_busy"
        !slockFilePreviewCanStartExternalOperation(downloadStatus) -> "operation_busy"
        !hasOwner -> "owner_missing"
        !hasPolicy -> "state_checking"
        else -> null
    }

internal fun traceSlockFilePreviewExternalOpenAdmission(
    rejectionReason: String?,
    policy: SlockFilePreviewExternalOpenPolicy?,
    trace: (String) -> Unit
): Boolean {
    if (rejectionReason != null) {
        trace("file_preview_open stage=rejected reason=$rejectionReason")
        return false
    }
    val admittedPolicy = policy ?: return false
    trace(
        "file_preview_open stage=admitted " +
            "committedRequired=${admittedPolicy.requirePublicDownload} trackProgress=${admittedPolicy.trackProgress}"
    )
    return true
}

internal sealed interface SlockFilePreviewExternalOpenTraceResult<out T> {
    data object UrlResolutionFailed : SlockFilePreviewExternalOpenTraceResult<Nothing>

    data class HostTerminal<T>(val result: T) : SlockFilePreviewExternalOpenTraceResult<T>
}

internal suspend fun <T> runSlockFilePreviewExternalOpenTraceFlow(
    policy: SlockFilePreviewExternalOpenPolicy,
    resolveUrl: suspend () -> String?,
    onUrlResolved: suspend (String) -> Unit,
    openHost: suspend (String) -> T,
    statusOf: (T) -> String?,
    trace: (String) -> Unit
): SlockFilePreviewExternalOpenTraceResult<T> {
    val resolvedUrl = resolveUrl()
    val url =
        if (policy.requirePublicDownload) {
            // A committed public copy is identity/journal driven. Its host must receive the
            // request even when the remote URL is absent so it can resolve the offline copy.
            resolvedUrl.orEmpty()
        } else {
            resolvedUrl?.takeIf { it.isNotBlank() }
        }
    if (url == null) {
        trace(
            "file_preview_open stage=url_resolution_failed " +
                "committedRequired=${policy.requirePublicDownload} trackProgress=${policy.trackProgress}"
        )
        return SlockFilePreviewExternalOpenTraceResult.UrlResolutionFailed
    }
    onUrlResolved(url)
    trace(
        "file_preview_open stage=host_handoff " +
            "committedRequired=${policy.requirePublicDownload} trackProgress=${policy.trackProgress}"
    )
    val result = openHost(url)
    trace("file_preview_open stage=host_terminal status=${slockAttachmentOpenDiagnosticStatus(statusOf(result))}")
    return SlockFilePreviewExternalOpenTraceResult.HostTerminal(result)
}

internal fun slockFilePreviewDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    terms: SlockFilePreviewDownloadStatusTerms
): String {
    val percent = progress?.percent
    val speedLabel = progress?.bytesPerSecond.slockFilePreviewSpeedLabel()
    val downloadedLabel = progress?.downloadedBytes?.takeIf { it > 0L }.slockFilePreviewSizeLabel()
    val verb =
        when {
            progress?.status == "paused" -> terms.paused
            progress?.phase?.startsWith("export_") == true -> terms.saving
            else -> terms.downloading
        }
    val primary =
        when {
            percent != null -> "$verb $percent%"
            downloadedLabel != null -> "$verb $downloadedLabel"
            else -> "$verb..."
        }
    return listOfNotNull(primary, speedLabel).joinToString(" · ")
}

internal fun slockAttachmentDownloadControlAllowed(
    progress: BridgeAttachmentDownloadProgress?,
    action: String
): Boolean =
    progress?.phase?.startsWith("export_") != true || (action != "pause" && action != "resume")

internal fun slockFilePreviewCanStartExternalOperation(status: String): Boolean =
    status !in setOf("downloading", "paused", "opening")

internal fun slockFilePreviewExternalOpenAdmissionStatus(trackProgress: Boolean): String =
    if (trackProgress) "downloading" else "opening"

internal fun slockFilePreviewShowsDownloadProgress(status: String): Boolean =
    status == "downloading" || status == "paused"

internal fun slockFilePreviewSavedMessage(
    exportPrivateDownload: Boolean,
    toPhotos: Boolean,
    supportsPublicDownloadReadback: Boolean,
    supportsPrivateAttachmentExport: Boolean,
    downloadSaved: String,
    privateDownloadSaved: String,
    fileExportSaved: String,
    imageSavedToPhotos: String
): String =
    when {
        exportPrivateDownload -> fileExportSaved
        toPhotos -> imageSavedToPhotos
        supportsPrivateAttachmentExport -> privateDownloadSaved
        supportsPublicDownloadReadback -> downloadSaved
        else -> fileExportSaved
    }

internal fun slockFilePreviewActiveDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    status: String,
    terms: SlockFilePreviewDownloadStatusTerms
): String {
    val activeProgress =
        progress
            ?: SlockFilePreviewInitialDownloadProgress.copy(
                status = if (status == "paused") "paused" else "progress"
            )
    return slockFilePreviewDownloadStatusLabel(activeProgress, terms)
}

@Composable
internal fun slockFilePreviewLocalizedActiveDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    status: String
): String =
    slockFilePreviewActiveDownloadStatusLabel(
        progress = progress,
        status = status,
        terms =
            SlockFilePreviewDownloadStatusTerms(
                downloading = stringResource(MR.strings.file_preview_download_downloading),
                paused = stringResource(MR.strings.file_preview_download_paused),
                saving = stringResource(MR.strings.file_preview_download_saving)
            )
    )

internal val SlockFilePreviewInitialDownloadProgress =
    BridgeAttachmentDownloadProgress(
        downloadId = "",
        sequence = 0L,
        phase = "preparing",
        status = "progress",
        percent = null,
        downloadedBytes = 0L,
        totalBytes = null,
        bytesPerSecond = null
    )

internal data class SlockFilePreviewActionTerms(
    val download: String,
    val install: String,
    val open: String,
    val openWith: String
)

@Composable
internal fun slockFilePreviewLocalizedActionTerms(): SlockFilePreviewActionTerms =
    SlockFilePreviewActionTerms(
        download = stringResource(MR.strings.file_preview_action_download),
        install = stringResource(MR.strings.file_preview_action_install),
        open = stringResource(MR.strings.file_preview_action_open),
        openWith = stringResource(MR.strings.file_preview_action_open_with)
    )

internal fun slockFilePreviewPrimaryActionLabel(
    payload: SlockFilePreviewPayload,
    downloadState: SlockFilePreviewDownloadUiState?,
    terms: SlockFilePreviewActionTerms
): String? =
    when (downloadState) {
        SlockFilePreviewDownloadUiState.NotDownloaded -> terms.download
        SlockFilePreviewDownloadUiState.Downloaded ->
            if (payload.isAndroidPackageArchive()) terms.install else terms.open
        SlockFilePreviewDownloadUiState.Downloading,
        null -> null
    }

internal enum class SlockFilePreviewPrimaryAction {
    Save,
    Open
}

internal fun slockFilePreviewPrimaryAction(
    downloadState: SlockFilePreviewDownloadUiState?
): SlockFilePreviewPrimaryAction? =
    when (downloadState) {
        SlockFilePreviewDownloadUiState.NotDownloaded -> SlockFilePreviewPrimaryAction.Save
        SlockFilePreviewDownloadUiState.Downloaded -> SlockFilePreviewPrimaryAction.Open
        SlockFilePreviewDownloadUiState.Downloading,
        null -> null
    }

internal fun slockFilePreviewUnsupportedFileMessage(payload: SlockFilePreviewPayload, downloadable: Boolean): StringDesc =
    when {
        downloadable -> MR.strings.file_preview_download_to_open.desc()
        payload.message.isNotBlank() -> payload.message.desc()
        else -> payload.messageDesc ?: MR.strings.file_preview_no_preview.desc()
    }

internal fun slockFilePreviewOverflowActionLabel(
    payload: SlockFilePreviewPayload,
    downloadState: SlockFilePreviewDownloadUiState,
    terms: SlockFilePreviewActionTerms
): String =
    when {
        downloadState == SlockFilePreviewDownloadUiState.Downloaded && payload.isAndroidPackageArchive() -> terms.install
        else -> terms.openWith
    }

internal fun slockFilePreviewFileIconName(payload: SlockFilePreviewPayload): SlockLucideIconName {
    val mime = payload.externalMimeType.substringBefore(';').trim().lowercase()
    val fileName = payload.attachmentCacheFileName().substringBefore('?').substringBefore('#')
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        payload.isAndroidPackageArchive() -> SlockLucideIconName.Package
        slockFilePreviewIsImageAttachment(fileName = fileName, mimeType = mime) ->
            SlockLucideIconName.FileImage
        mime.startsWith("video/") || extension in SlockFilePreviewVideoExtensions ->
            SlockLucideIconName.FileVideoCamera
        mime.startsWith("audio/") || extension in SlockFilePreviewAudioExtensions ->
            SlockLucideIconName.FileMusic
        mime in SlockFilePreviewArchiveMimeTypes || extension in SlockFilePreviewArchiveExtensions ->
            SlockLucideIconName.FileArchive
        mime in SlockFilePreviewSpreadsheetMimeTypes || extension in SlockFilePreviewSpreadsheetExtensions ->
            SlockLucideIconName.FileSpreadsheet
        mime in SlockFilePreviewPresentationMimeTypes || extension in SlockFilePreviewPresentationExtensions ->
            SlockLucideIconName.Presentation
        mime in SlockFilePreviewJsonMimeTypes || extension in SlockFilePreviewJsonExtensions ->
            SlockLucideIconName.FileBraces
        mime in SlockFilePreviewTerminalMimeTypes || extension in SlockFilePreviewTerminalExtensions ->
            SlockLucideIconName.FileTerminal
        mime in SlockFilePreviewCodeMimeTypes || extension in SlockFilePreviewCodeExtensions ->
            SlockLucideIconName.FileCode
        extension in SlockFilePreviewTypedTextExtensions ->
            SlockLucideIconName.FileType
        mime.startsWith("text/") ||
            extension in SlockFilePreviewDocumentExtensions ->
            SlockLucideIconName.FileText
        else -> SlockLucideIconName.File
    }
}

internal fun slockFilePreviewIsImageAttachment(fileName: String, mimeType: String): Boolean {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val extension = fileName.substringBefore('?').substringBefore('#').substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mime.startsWith("image/") || extension in SlockFilePreviewImageExtensions
}

private val SlockFilePreviewImageExtensions =
    setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "svg", "webp")

internal fun slockComposerImagePickerIsStrictImage(fileName: String, mimeType: String): Boolean {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val extension =
        fileName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    return mime.startsWith("image/") && extension in SlockFilePreviewImageExtensions
}

internal val SlockFilePreviewVideoExtensions =
    setOf("3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")

private val SlockFilePreviewAudioExtensions =
    setOf("aac", "aiff", "flac", "m4a", "mid", "midi", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma")

private val SlockFilePreviewArchiveExtensions =
    setOf("7z", "aar", "bz2", "dmg", "gz", "jar", "rar", "tar", "tgz", "war", "xz", "zip")

private val SlockFilePreviewSpreadsheetExtensions =
    setOf("csv", "numbers", "ods", "tsv", "xls", "xlsm", "xlsx")

private val SlockFilePreviewPresentationExtensions =
    setOf("key", "odp", "pps", "ppsx", "ppt", "pptx")

private val SlockFilePreviewJsonExtensions =
    setOf("geojson", "json", "json5")

private val SlockFilePreviewTerminalExtensions =
    setOf("bash", "bat", "cmd", "fish", "ps1", "sh", "zsh")

private val SlockFilePreviewCodeExtensions =
    setOf(
        "c", "cc", "cpp", "cs", "css", "dart", "go", "h", "hpp", "html", "java", "js", "jsx",
        "kt", "kts", "lua", "m", "mm", "php", "py", "rb", "rs", "sql", "swift", "toml", "ts",
        "tsx", "vue", "xml", "yaml", "yml"
    )

private val SlockFilePreviewTypedTextExtensions =
    setOf("log", "text", "txt")

private val SlockFilePreviewDocumentExtensions =
    setOf("doc", "docx", "md", "markdown", "odt", "pages", "pdf", "rtf")

private val SlockFilePreviewArchiveMimeTypes =
    setOf(
        "application/gzip",
        "application/java-archive",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/x-bzip2",
        "application/x-gzip",
        "application/x-rar-compressed",
        "application/x-tar",
        "application/zip"
    )

private val SlockFilePreviewSpreadsheetMimeTypes =
    setOf(
        "application/vnd.ms-excel",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/csv",
        "text/tab-separated-values"
    )

private val SlockFilePreviewPresentationMimeTypes =
    setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

private val SlockFilePreviewJsonMimeTypes =
    setOf("application/geo+json", "application/json", "application/ld+json")

private val SlockFilePreviewTerminalMimeTypes =
    setOf("application/x-sh", "application/x-shellscript", "text/x-shellscript")

private val SlockFilePreviewCodeMimeTypes =
    setOf(
        "application/javascript",
        "application/typescript",
        "application/x-javascript",
        "application/xml",
        "text/css",
        "text/html",
        "text/javascript",
        "text/xml"
    )

private fun slockFilePreviewPrimaryActionIcon(
    payload: SlockFilePreviewPayload,
    downloadState: SlockFilePreviewDownloadUiState?
): SlockLucideIconName? =
    when (downloadState) {
        SlockFilePreviewDownloadUiState.NotDownloaded -> SlockLucideIconName.Download
        SlockFilePreviewDownloadUiState.Downloaded ->
            if (payload.isAndroidPackageArchive()) SlockLucideIconName.Package else SlockLucideIconName.ExternalLink
        SlockFilePreviewDownloadUiState.Downloading,
        null -> null
    }

private fun canOpenPayloadWithExternalViewer(payload: SlockFilePreviewPayload): Boolean =
    payload.externalUrl?.isNotBlank() == true ||
        (payload.source == SlockFilePreviewSourceThreadAttachment && payload.attachmentId.isNotBlank())

private fun SlockFilePreviewPayload.attachmentCacheId(): String =
    attachmentId.ifBlank { path.ifBlank { name.ifBlank { externalFileName.ifBlank { "attachment" } } } }

private fun SlockFilePreviewPayload.attachmentCacheFileName(): String =
    externalFileName
        .ifBlank { name }
        .ifBlank { path }
        .ifBlank { "attachment" }

private fun SlockFilePreviewPayload.filePreviewDownloadId(generation: Long? = null): String =
    listOfNotNull(generation?.let { "attempt-$it" }, source, attachmentCacheId(), attachmentCacheFileName())
        .joinToString(":")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
        .take(160)
        .ifBlank { "file-preview-download" }

private fun SlockFilePreviewPayload.isAndroidPackageArchive(): Boolean {
    val mime = externalMimeType.substringBefore(';').trim()
    if (mime.equals("application/vnd.android.package-archive", ignoreCase = true)) return true
    val name = attachmentCacheFileName().substringBefore('?').substringBefore('#')
    return name.endsWith(".apk", ignoreCase = true)
}

private fun Long?.slockFilePreviewSpeedLabel(): String? {
    val bytesPerSecond = this?.takeIf { it > 0L } ?: return null
    return "${bytesPerSecond.slockFilePreviewSizeLabel()}/s"
}

@Composable
internal fun SlockFilePreviewPageScaffold(
    title: String,
    subtitle: String? = null,
    iconName: SlockLucideIconName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Md,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    overflowMenuItems: List<SlockRaftUiMenuItem> = emptyList(),
    onOverflowMenuSelect: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    SlockInputFocusDismissHost { focusDismissModifier ->
        Box(modifier = modifier.then(focusDismissModifier).fillMaxSize().background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                SlockTopPageHeader(
                title = title,
                subtitle = subtitle,
                iconName = iconName,
                backgroundColor = SlockColor.SurfaceWhite,
                leadingContent = {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.ArrowLeft,
                        onClick = onBack,
                        size = 30.dp,
                        shadowOffset = 2.dp
                    )
                },
                trailingContent =
                    trailingContent
                        ?: if (overflowMenuItems.isNotEmpty()) {
                            {
                                SlockFilePreviewOverflowMenuButton(
                                    onOpenMenu = { anchor ->
                                        menuAnchor = anchor
                                        menuOpen = true
                                    }
                                )
                            }
                        } else {
                            null
                        }
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
                ) {
                    content()
                }
            }
            if (menuOpen && overflowMenuItems.isNotEmpty() && menuAnchor != null) {
                SlockRaftUiControlledMenu(
                    expanded = menuOpen,
                    anchor = menuAnchor!!,
                    items = overflowMenuItems,
                    onSelect = { id ->
                        menuOpen = false
                        onOverflowMenuSelect(id)
                    },
                    onDismiss = { menuOpen = false },
                    width = 180.dp
                )
            }
        }
    }
}

@Composable
internal fun SlockFilePreviewGenericCard(
    iconName: SlockLucideIconName,
    name: String,
    meta: String,
    message: String,
    statusLabel: String? = null,
    showProgress: Boolean = false,
    progressPercent: Int? = null,
    actionLabel: String? = null,
    actionIcon: SlockLucideIconName? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    secondaryActionIcon: SlockLucideIconName? = null,
    secondaryActionEnabled: Boolean = true,
    onSecondaryAction: (() -> Unit)? = null,
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .slockSquareCorners()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(72.dp), color = "#FF000000")
        Box(modifier = Modifier.height(SlockSpacing.Md))
        Text(
            text = name.ifBlank { stringResource(MR.strings.file_preview_fallback_file) },
            style = SlockType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (meta.isNotBlank()) {
            Box(modifier = Modifier.height(4.dp))
            Text(
                text = meta,
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Box(modifier = Modifier.height(SlockSpacing.Md))
        Text(
            text = message,
            style = SlockType.Body,
            color = SlockColor.TextMutedGray,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (showProgress) {
            Box(modifier = Modifier.height(SlockSpacing.Md))
            SlockFilePreviewDownloadStatusBar(
                statusLabel =
                    statusLabel
                        ?: slockFilePreviewLocalizedActiveDownloadStatusLabel(
                            progress = SlockFilePreviewInitialDownloadProgress.copy(percent = progressPercent),
                            status = if (downloadPaused) "paused" else "downloading"
                        ),
                showProgress = true,
                progressPercent = progressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload
            )
        } else if (statusLabel != null) {
            Box(modifier = Modifier.height(SlockSpacing.Sm))
            Text(
                text = statusLabel,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (!showProgress && actionLabel != null && onAction != null) {
            Box(modifier = Modifier.height(SlockSpacing.Lg))
            SlockBrutalActionButton(
                label = actionLabel,
                onClick = onAction,
                enabled = actionEnabled,
                leadingIcon = actionIcon,
                minWidth = 132.dp,
                minHeight = 40.dp
            )
        }
        if (!showProgress && secondaryActionLabel != null && onSecondaryAction != null) {
            Box(modifier = Modifier.height(SlockSpacing.Sm))
            SlockBrutalActionButton(
                label = secondaryActionLabel,
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                leadingIcon = secondaryActionIcon,
                minWidth = 132.dp,
                minHeight = 40.dp
            )
        }
    }
}

@Composable
private fun SlockFilePreviewProgressBar(progressPercent: Int?) {
    val percent = progressPercent?.coerceIn(0, 100)
    if (percent == null) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = SlockColor.AccentCyan,
            trackColor = SlockColor.SurfaceCream
        )
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
    ) {
        if (percent > 0) {
            Box(
                modifier =
                    Modifier
                        .weight(percent.toFloat())
                        .fillMaxSize()
                        .background(SlockColor.AccentCyan)
            )
        }
        if (percent < 100) {
            Box(
                modifier =
                    Modifier
                        .weight((100 - percent).toFloat())
                        .fillMaxSize()
            )
        }
    }
}

@Composable
private fun SlockFilePreviewDownloadStatusOverlay(
    statusLabel: String?,
    showProgress: Boolean,
    progressPercent: Int?,
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (statusLabel != null || showProgress) {
            SlockFilePreviewDownloadStatusBar(
                statusLabel =
                    statusLabel
                        ?: slockFilePreviewLocalizedActiveDownloadStatusLabel(
                            progress = null,
                            status = if (downloadPaused) "paused" else "downloading"
                        ),
                showProgress = showProgress,
                progressPercent = progressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun SlockFilePreviewDownloadStatusBar(
    statusLabel: String,
    showProgress: Boolean,
    progressPercent: Int?,
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = statusLabel,
            style = SlockType.Caption,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showProgress) {
            SlockFilePreviewProgressBar(progressPercent = progressPercent)
        }
        if (showProgress && onToggleDownload != null && onStopDownload != null) {
            SlockFilePreviewDownloadControls(
                paused = downloadPaused,
                onToggle = onToggleDownload,
                onStop = onStopDownload
            )
        }
    }
}

@Composable
private fun SlockFilePreviewDownloadControls(
    paused: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockBrutalActionButton(
            label =
                stringResource(
                    if (paused) MR.strings.file_preview_download_resume else MR.strings.file_preview_download_pause
                ),
            onClick = onToggle,
            leadingIcon = if (paused) SlockLucideIconName.Play else SlockLucideIconName.Pause,
            minWidth = 92.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
        )
        SlockBrutalActionButton(
            label = stringResource(MR.strings.common_stop),
            onClick = onStop,
            leadingIcon = SlockLucideIconName.Square,
            minWidth = 82.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
        )
    }
}

@Composable
internal fun SlockFilePreviewMarkdownPanel(
    name: String,
    meta: String,
    statusLabel: String?,
    status: String,
    body: String?,
    error: String?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    performanceKey: String? = null,
    textStyle: TextStyle? = null,
    showChrome: Boolean = true
) {
    if (!showChrome && status == "content") {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            item {
                SlockMarkdownBody(
                    body = body.orEmpty(),
                    textStyle = textStyle ?: SlockType.Body,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.fillMaxWidth(),
                    softLineBreaksAsNewLines = false,
                    performanceKey = performanceKey
                )
            }
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { stringResource(MR.strings.file_preview_fallback_markdown) },
            meta = meta,
            statusLabel = statusLabel
        )
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        when (status) {
            "content" ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(SlockSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    item {
                        SlockMarkdownBody(
                            body = body.orEmpty(),
                            textStyle = textStyle ?: SlockType.Body,
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                            softLineBreaksAsNewLines = false,
                            performanceKey = performanceKey
                        )
                    }
                }
            "error" ->
                SlockFilePreviewMessage(
                    iconName = SlockLucideIconName.FileText,
                    title = stringResource(MR.strings.file_preview_markdown_unavailable),
                    detail = error.orEmpty().ifBlank { stringResource(MR.strings.file_preview_markdown_open_with_hint) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            else ->
                SlockFilePreviewLoadingIndicator(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
        }
    }
}

@Composable
internal fun SlockFilePreviewTextPanel(
    name: String,
    meta: String,
    content: String,
    modifier: Modifier = Modifier,
    showChrome: Boolean = true
) {
    if (!showChrome) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(SlockSpacing.Md)
        ) {
            item {
                Text(
                    text = content,
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip
                )
            }
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { stringResource(MR.strings.file_preview_fallback_text_file) },
            meta = meta,
            statusLabel = null
        )
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .background(SlockColor.SurfaceCream)
                .padding(SlockSpacing.Sm)
        ) {
            Text(
                text = content,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 28,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewXlsxPanel(
    name: String,
    meta: String,
    sheets: List<SlockFilePreviewXlsxSheetCarrier>,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedSheetName by remember(sheets) { mutableStateOf(sheets.firstOrNull()?.name.orEmpty()) }
    val selectedSheet =
        remember(sheets, selectedSheetName) {
            sheets.firstOrNull { it.name == selectedSheetName } ?: sheets.firstOrNull()
        }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { stringResource(MR.strings.file_preview_fallback_file) },
            meta = meta,
            statusLabel = null
        )
        if (sheets.size > 1) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
            ) {
                sheets.forEach { sheet ->
                    val selected = sheet.name == selectedSheet?.name
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .background(if (selected) SlockColor.SurfaceWhite else SlockColor.SurfaceCream)
                                .border(
                                    width = if (selected) SlockStroke.Default else SlockStroke.Thin,
                                    color = SlockColor.BorderBlack
                                )
                                .clickable { selectedSheetName = sheet.name }
                                .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Sm)
                    ) {
                        Text(
                            text = sheet.name,
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        if (selectedSheet == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 560.dp)
                        .background(SlockColor.SurfaceCream)
                        .padding(SlockSpacing.Sm)
            ) {
                Text(
                    text = stringResource(MR.strings.file_preview_no_preview),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray
                )
            }
        } else {
            val columnCount = selectedSheet.columnCount.coerceIn(1, 20)
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 560.dp)
                        .background(SlockColor.SurfaceCream),
                contentPadding = PaddingValues(SlockSpacing.Sm)
            ) {
                item(key = "xlsx-header") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        repeat(columnCount) { column ->
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .background(SlockColor.SurfaceWhite)
                                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                        .padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Sm)
                            ) {
                                Text(
                                    text = selectedSheet.headers.getOrNull(column).orEmpty(),
                                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withSlockFontWeight(FontWeight.Bold),
                                    color = SlockColor.TextBlack,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                items(selectedSheet.rows.size, key = { index -> "xlsx-row-$index" }) { rowIndex ->
                    val row = selectedSheet.rows.getOrNull(rowIndex).orEmpty()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        repeat(columnCount) { column ->
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .background(SlockColor.SurfaceWhite)
                                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                        .padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Sm)
                            ) {
                                Text(
                                    text = row.getOrNull(column).orEmpty(),
                                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                    color = SlockColor.TextBlack,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (truncated || selectedSheet.truncated) {
                    item(key = "xlsx-truncated-hint") {
                        Text(
                            text = stringResource(MR.strings.file_preview_truncated_open_with_hint),
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier.padding(top = SlockSpacing.Sm)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SlockFilePreviewImagePanel(
    source: String?,
    fallbackSource: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String? = null,
    backgroundColor: com.tencent.kuikly.compose.ui.graphics.Color = SlockColor.SurfaceWhite,
    borderWidth: com.tencent.kuikly.compose.ui.unit.Dp = SlockStroke.Default,
    zoomable: Boolean = true
) {
    SlockAspectFitImagePreviewSurface(
        source = source,
        fallbackSource = fallbackSource,
        contentDescription = contentDescription,
        placeholderLabel = placeholderLabel ?: stringResource(MR.strings.file_preview_image_placeholder),
        modifier = modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        borderWidth = borderWidth,
        zoomable = zoomable,
        showLoadingIndicator = true
    )
}

internal fun SlockFilePreviewPayload.shouldRenderImagePreview(): Boolean =
    kind == "image" || (kind == "loading" && requestedKind == "image" && !imageUrl.isNullOrBlank())

internal fun SlockFilePreviewPayload.currentImageThumbnailUrl(): String? =
    imageGallery
        .takeIf { it.isNotEmpty() }
        ?.let { gallery -> gallery[imageGalleryIndex.coerceIn(gallery.indices)] }
        ?.thumbnailUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: imageUrl?.trim()?.takeIf { kind == "loading" && requestedKind == "image" && it.isNotBlank() }

@Composable
internal fun SlockFilePreviewMessage(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(SlockSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(34.dp), color = "#FF000000")
        Text(
            text = title,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SlockFilePreviewLoadingIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(SlockSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        SlockHeaderRefreshIndicator()
    }
}

@Composable
private fun SlockFilePreviewPanelHeader(
    name: String,
    meta: String,
    statusLabel: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = name,
            style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        statusLabel?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
