package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftProductEvents
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Presents share sheets through the host (`settingsCapability` `shareOut`, a stream) and acts on
 * what the host reports: Raft's own actions (Copy link, Send text, Send image, Save image), the
 * 2-second note and `share_action_selected`. All decisions are RaftShareOut.kt's; this only runs
 * them. One sheet at a time: a new one retires the callback of the one before (Android never says
 * that a sheet was closed without a pick).
 */
internal class RaftShareOutLauncher(
    private val bridge: BridgeModule,
    private val platform: RaftShareOutPlatform,
    private val scope: CoroutineScope,
) {
    private var retireActive: (() -> Unit)? = null
    private var sequence = 0

    /**
     * Opens the first sheet for [subject] ([raftShareOutPlan]). [onImage] makes the long image
     * (Send image, or a subject that only has an image); it shares the file itself through
     * [shareImageFile] when it is ready.
     */
    fun share(subject: RaftShareOutSubject, onImage: (() -> Unit)? = null) {
        val offered = if (onImage == null) subject.copy(imageAvailable = false) else subject
        val plan = raftShareOutPlan(offered, platform)
        if (plan == null) {
            bridge.toast(Res.string.share_open_failed.desc().resolve())
            return
        }
        if (plan.primary == RaftShareOutPayloadKind.Image) {
            onImage?.invoke()
            return
        }
        present(offered, plan.primary, plan.actions, imagePath = null, onImage = onImage)
    }

    /** Shares the image file at [path] (the long image) for [subject]. */
    fun shareImageFile(subject: RaftShareOutSubject, path: String) {
        present(subject, RaftShareOutPayloadKind.Image, raftShareOutImageActions(platform), imagePath = path, onImage = null)
    }

    /** A menu's own Copy link: the clipboard, the brief "Copied", and the event. */
    fun copyLink(subject: RaftShareOutSubject) {
        val link = subject.link?.takeIf { it.isNotBlank() } ?: return
        bridge.copyToPasteboard(link)
        bridge.briefToast(Res.string.common_copied.desc().resolve())
        RaftProductEvents.shareActionSelected(raftShareOutCopyLinkEventProperties(subject.obj, subject.surface))
    }

    private fun present(
        subject: RaftShareOutSubject,
        kind: RaftShareOutPayloadKind,
        actions: List<RaftShareOutAction>,
        imagePath: String?,
        onImage: (() -> Unit)?,
    ) {
        retireActive?.invoke()
        retireActive = null
        if (platform == RaftShareOutPlatform.Android) RaftShareOutResumeNotice.clear()
        sequence += 1
        val command =
            raftShareOutHostCommand(
                requestId = "share-out-$sequence",
                kind = kind,
                title = subject.title,
                link = subject.link,
                text = subject.text,
                imagePath = imagePath,
                actions = actions.map { RaftShareOutHostAction(it, actionLabel(it)) },
                chooseTitle = Res.string.common_share.desc().resolve(),
                primaryLabel = primaryLabel(kind),
            )
        bridge.log("share_out_present object=${subject.obj.wire} surface=${subject.surface.wire} kind=${kind.wire} actions=${actions.size}")
        val presented = sequence
        retireActive =
            bridge.settingsCapabilityStream(command) { payload ->
                val event = raftShareOutHostEvent(payload) ?: return@settingsCapabilityStream
                // The host answers on its own thread; everything below belongs to the page's.
                scope.launch {
                    if (presented == sequence) retireActive = null
                    onHostEvent(subject, kind, imagePath, onImage, event)
                }
            }
    }

    private fun onHostEvent(
        subject: RaftShareOutSubject,
        kind: RaftShareOutPayloadKind,
        imagePath: String?,
        onImage: (() -> Unit)?,
        event: RaftShareOutHostEvent,
    ) {
        bridge.log("share_out_event object=${subject.obj.wire} kind=${kind.wire} event=${event::class.simpleName}")
        raftShareOutEventProperties(subject, kind, event)?.let(RaftProductEvents::shareActionSelected)
        if (event is RaftShareOutHostEvent.ActionPicked) {
            when (event.action) {
                RaftShareOutAction.CopyLink -> subject.link?.takeIf { it.isNotBlank() }?.let(bridge::copyToPasteboard)
                // The text itself becomes the payload: the sheet shows exactly what is sent.
                RaftShareOutAction.SendText ->
                    present(subject, RaftShareOutPayloadKind.Text, emptyList(), imagePath = null, onImage = null)
                RaftShareOutAction.SendImage -> onImage?.invoke()
                RaftShareOutAction.SaveImage -> imagePath?.let(::saveImage)
            }
        }
        if (event == RaftShareOutHostEvent.Failed) {
            bridge.toast(Res.string.share_open_failed.desc().resolve())
            return
        }
        val note = raftShareOutNotice(event, kind) ?: return
        val notice =
            when (note) {
                is RaftShareOutNotice.Opened -> Res.string.share_out_opened.format(note.app).resolve()
                is RaftShareOutNotice.SharedTo -> Res.string.share_out_shared_to.format(note.app).resolve()
                RaftShareOutNotice.Shared -> Res.string.share_out_shared.desc().resolve()
                RaftShareOutNotice.Copied -> Res.string.common_copied.desc().resolve()
            }
        if (raftShareOutShouldDeferNotice(platform, note)) {
            // Android reports the picked target while the other app is opening: hold the notice
            // until Raft's activity resumes so the 2-second timer does not run out in the background.
            RaftShareOutResumeNotice.onOpenedNotice(notice)?.let { bridge.timedToast(it, RaftShareOutNoticeMillis) }
        } else {
            bridge.timedToast(notice, RaftShareOutNoticeMillis)
        }
    }

    private fun saveImage(path: String) {
        scope.launch {
            val status = runCatching { bridge.saveThreadShareImage(path) }.getOrNull()?.optString("status")
            when (status) {
                "ok" -> bridge.toast(Res.string.thread_feedback_image_saved.desc().resolve())
                "cancelled" -> Unit
                else -> bridge.toast(Res.string.thread_error_export_image.desc().resolve())
            }
        }
    }

    private fun actionLabel(action: RaftShareOutAction): String =
        when (action) {
            RaftShareOutAction.CopyLink -> Res.string.thread_link_menu_copy_link
            RaftShareOutAction.SendText -> Res.string.share_out_send_text
            // iOS lists it among the sheet's actions, where it reads as what it does there.
            RaftShareOutAction.SendImage ->
                if (platform == RaftShareOutPlatform.Ios) Res.string.thread_share_generate_image else Res.string.share_out_send_image
            RaftShareOutAction.SaveImage -> Res.string.share_out_save_image
        }.desc().resolve()

    private fun primaryLabel(kind: RaftShareOutPayloadKind): String =
        when (kind) {
            RaftShareOutPayloadKind.Link -> Res.string.share_out_share_link
            RaftShareOutPayloadKind.Text -> Res.string.share_out_send_text
            RaftShareOutPayloadKind.Image -> Res.string.share_out_send_image
        }.desc().resolve()
}

/**
 * Only Android's "Opened <App>" waits for the next Activity resume; Android "Copied" and all iOS
 * completion notices fire after control is already back in Raft.
 */
internal fun raftShareOutShouldDeferNotice(platform: RaftShareOutPlatform, note: RaftShareOutNotice): Boolean =
    platform == RaftShareOutPlatform.Android && note is RaftShareOutNotice.Opened
