package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.ui.raftLoadImageBitmap
import build.raft.app.compat.ui.rememberAsyncImageBitmap
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftEventBuses
import build.raft.app.resources.*
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.designsystem.components.RaftSpinner
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Edit an image of the conversation (artin, task #32): the image viewer's Edit opens the image in
 * the editor; Done puts the edited copy into this conversation's composer as a new attachment
 * (the user can add text before sending), closes the viewer and focuses the composer. The message
 * that holds the original is untouched.
 *
 * Phones open images on the file preview page (a separate page): its ⋯ > Edit posts
 * [RAFT_VIEWER_IMAGE_EDIT_EVENT] with the attachment id and closes; the conversation page that
 * shows that attachment opens the editor. Desktop / wide windows open images in the lightbox,
 * which the conversation page hosts, so its Edit calls the page directly.
 */

/**
 * The conversation that last opened the phone image viewer: only it answers the viewer's Edit, so
 * a channel and a thread kept alive with the same message never both open an editor.
 */
internal object RaftViewerImageEditOpener {
    var threadKey: String = ""
}

/** In-process request from the phone image viewer: edit attachment `attachmentId`. */
internal const val RAFT_VIEWER_IMAGE_EDIT_EVENT = "slock.viewerImageEdit"

internal fun raftViewerImageEditEvent(attachmentId: String): JSONObject =
    JSONObject().apply { put("attachmentId", attachmentId) }

internal fun raftViewerImageEditAttachmentId(eventData: Any?): String? =
    runCatching { JSONObject(eventData?.toString().orEmpty()).optString("attachmentId").trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

internal fun raftPostViewerImageEditRequest(
    attachmentId: String,
    eventBus: RaftEventBus = RaftEventBuses.inProcess(),
): Boolean =
    runCatching { eventBus.post(RAFT_VIEWER_IMAGE_EDIT_EVENT, raftViewerImageEditEvent(attachmentId), crossProcess = false) }.isSuccess

/** Whether the phone viewer offers ⋯ > Edit for [payload]: an image from a conversation. */
internal fun RaftFilePreviewPayload.offersViewerImageEdit(): Boolean =
    kind == "image" &&
        source == RaftFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        composerImageActionId.isBlank() &&
        !imageUrl.isNullOrBlank()

/** File name of the edited copy: `photo.jpg` -> `photo-edited.png` (the editor writes PNG). */
internal fun raftViewerEditedImageFileName(originalName: String): String {
    val base = originalName.trim().substringBeforeLast('.').ifBlank { "image" }
    return "$base-edited.png"
}

/**
 * The edited image as a composer upload file, like a pasted image: an app-owned temporary PNG
 * that the composer may compress and then uploads.
 */
internal fun raftViewerEditedImageFile(fileRef: String, originalName: String, sequence: Long): PlatformFile =
    PlatformFile(
        id = "viewer-edit-$sequence",
        fileName = raftViewerEditedImageFileName(originalName),
        mimeType = "image/png",
        previewUri = fileRef,
        fileRef = fileRef,
        source = RAFT_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
    )

/**
 * The editor over the window for [attachment]. [desktop]: the viewer-style overlay with the top
 * toolbar; otherwise the phone editor page. [resolveFullUrl] returns the image's full-size URL;
 * the editor decodes it no larger than the window to work on and once at full size for the
 * export. [onEdited] gets the PNG file ref of the result.
 */
@Composable
internal fun RaftViewerImageEditor(
    attachment: RaftThreadAttachmentItem,
    desktop: Boolean,
    resolveFullUrl: suspend (attachmentId: String) -> String?,
    onCancel: () -> Unit,
    onEdited: (fileRef: String) -> Unit,
    onSaveFailed: () -> Unit,
) {
    var fullUrl by remember(attachment.id) { mutableStateOf<String?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    var saving by remember(attachment.id) { mutableStateOf(false) }
    var captureRef by remember(attachment.id) { mutableStateOf<RaftViewCaptureRef?>(null) }
    val state = remember(attachment.id) { RaftComposerImageEditorState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attachment.id) {
        val url = resolveFullUrl(attachment.id)?.trim()?.takeIf { it.isNotBlank() }
        if (url == null) failed = true else fullUrl = url
    }
    val cancel = { if (!saving) onCancel() }
    // One editor on every platform; only its chrome differs (desktop: top bar and popovers).
    val editor: @Composable (Modifier) -> Unit = { editorModifier ->
        val url = fullUrl
        if (url == null) {
            Box(modifier = editorModifier, contentAlignment = Alignment.Center) {
                if (failed) {
                    build.raft.designsystem.text.RaftText(text = stringResource(Res.string.file_preview_image_full_load_failed))
                } else {
                    RaftSpinner()
                }
            }
        } else {
            RaftFilePreviewComposerImageEditor(
                payload = RaftFilePreviewPayload(kind = "image", name = attachment.name, imageUrl = url),
                state = state,
                saving = saving,
                onCaptureRef = { captureRef = it },
                onDone = done@{
                    // The capture re-renders the edits over the photo decoded at full size.
                    val ref = captureRef ?: return@done
                    saving = true
                    scope.launch {
                        val result = runCatching { ref.captureToPngFile() }.getOrNull()
                        saving = false
                        val fileRef = result?.fileRef
                        if (fileRef.isNullOrBlank()) onSaveFailed() else onEdited(fileRef)
                    }
                },
                desktopChrome = if (desktop) RaftComposerImageEditorDesktopChrome(onCancel = cancel) else null,
                modifier = editorModifier,
            )
        }
    }
    Modal(onWillDismiss = { cancel() }) {
        if (desktop) {
            Box(modifier = Modifier.fillMaxSize().background(RaftComposerImageDesktopBackdrop)) {
                editor(Modifier.fillMaxSize())
            }
        } else {
            RaftFilePreviewPageScaffold(
                title = stringResource(Res.string.file_preview_editor_title),
                iconName = RaftLucideIconName.Pencil,
                onBack = cancel,
                contentPadding = 0.dp,
                trailingContent = { RaftFilePreviewImageEditorHistoryActions(state = state, saving = saving) },
            ) {
                editor(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private const val RaftViewerImageEditFilePrefix = "raft_viewer_edit_"

/** The attachment shown now: the gallery's current image, else the payload's. */
internal fun RaftFilePreviewPayload.viewerShownAttachmentId(): String =
    imageGallery.getOrNull(imageGalleryIndex)?.attachmentId?.trim()?.takeIf { it.isNotBlank() }
        ?: attachmentId.trim()

internal const val RaftViewerImageMenuEditId = "viewer_image_edit"

/** The phone image viewer's menu (⋯ at the bottom right): Edit now; Save / Forward can join. */
@Composable
internal fun raftViewerImageMenuItems(): List<RaftUiMenuItem> =
    listOf(
        RaftUiMenuItem(
            id = RaftViewerImageMenuEditId,
            label = stringResource(Res.string.common_edit),
            icon = RaftLucideIconName.Pencil,
        )
    )

/**
 * ⋯ at the bottom right of the phone image viewer, opening [raftViewerImageMenuItems].
 * [onSelect] gets the chosen item's id.
 */
@Composable
internal fun RaftViewerImageMoreButton(
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "viewer_image_more") }
    var anchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var open by remember { mutableStateOf(false) }
    val items = raftViewerImageMenuItems()
    build.raft.designsystem.components.RaftIconButton(
        onClick = {
            anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd)?.let {
                anchor = it
                open = true
            }
        },
        tooltip = stringResource(Res.string.tab_more),
        modifier = modifier.raftCaptureLiveRootAnchor(anchorRef),
        variant = build.raft.designsystem.components.RaftButtonVariant.Default,
        size = build.raft.designsystem.components.RaftIconButtonSize.Lg,
    ) {
        build.raft.app.components.RaftSlotIcon(RaftLucideIconName.Ellipsis)
    }
    val shownAnchor = anchor
    if (open && shownAnchor != null) {
        RaftUiControlledMenu(
            expanded = true,
            anchor = shownAnchor,
            items = items,
            onSelect = { id ->
                open = false
                onSelect(id)
            },
            onDismiss = { open = false },
            width = 180.dp,
        )
    }
}
