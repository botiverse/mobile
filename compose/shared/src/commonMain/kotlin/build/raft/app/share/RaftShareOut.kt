package build.raft.app

import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftShareActionMethod
import build.raft.app.data.RaftShareActionObject
import build.raft.app.data.RaftShareActionResult
import build.raft.app.data.RaftShareActionSelectedProperties
import build.raft.app.data.RaftShareActionSurface
import build.raft.app.data.encodeArtifactShortId
import build.raft.app.model.ConversationKind

/*
 * Share out (share loop, first batch; #proj-mobile:#212 design v10): one "Share" action, app-wide,
 * that opens the SYSTEM share sheet with the item's existing web link. This file is the product
 * logic and has no UI and no platform code: what can be shared from where, the DM rule, what each
 * sheet carries, the host command and the product-event fields. The host (Android
 * `AndroidShareOut`, iOS `HRBridgeModule` `shareOut`) only presents what the command says and
 * reports what the person did.
 *
 * Rules (design section 8, decided 2026-11-20):
 *  - what is sent is picked at share time: link / text / image; a link-type item defaults to its link;
 *  - a DM gives no link: its conversation has no Share / Copy link, its messages share as text or
 *    image only, and a task in a DM has no link. A Lounge shares like a channel;
 *  - the link is the item's web link on the Developer environment's web URL (AGENTS.md
 *    "Environment Web Link URL Rule"); a task's is `/tasks/<short id>` (slock `taskRef.ts`).
 */

/** What one share sheet sends. */
internal enum class RaftShareOutPayloadKind(val wire: String) {
    Link("link"),
    Text("text"),
    Image("image"),
}

/** Raft's own actions inside the system share sheet (Android custom actions, iOS activities). */
internal enum class RaftShareOutAction(val wire: String) {
    CopyLink("copy_link"),
    SendText("send_text"),
    SendImage("send_image"),
    /** Android's image sheet only: its system sheet has no "Save image" (iOS's has). */
    SaveImage("save_image"),
}

internal fun raftShareOutActionOrNull(wire: String?): RaftShareOutAction? =
    RaftShareOutAction.entries.firstOrNull { it.wire == wire }

/** The hosts that have a system share sheet for this; null elsewhere (desktop, web): no Share entry. */
internal enum class RaftShareOutPlatform { Android, Ios }

internal fun raftShareOutPlatform(os: String?): RaftShareOutPlatform? =
    when (os?.trim()?.lowercase()) {
        "android" -> RaftShareOutPlatform.Android
        "ios" -> RaftShareOutPlatform.Ios
        else -> null
    }

/**
 * One item a person asked to share. [link] is null where the item has none (the DM rule, or ids the
 * link builder refuses); [text] is the words a "Send text" sends, exactly as the sheet previews
 * them; [imageAvailable]: the existing long image can be made of it.
 */
internal data class RaftShareOutSubject(
    val obj: RaftShareActionObject,
    val surface: RaftShareActionSurface,
    /** The sheet's preview title. Only the sender sees it, so it may name the channel and author. */
    val title: String,
    val link: String? = null,
    val text: String? = null,
    val imageAvailable: Boolean = false,
) {
    val hasLink: Boolean get() = !link.isNullOrBlank()
    val hasText: Boolean get() = !text.isNullOrBlank()
}

/** What the first sheet for a subject sends, and Raft's actions in it. */
internal data class RaftShareOutPlan(
    val primary: RaftShareOutPayloadKind,
    val actions: List<RaftShareOutAction>,
)

/**
 * The first sheet for [subject]: its link when it has one (with Copy link, Send text, Send image as
 * far as each applies), else its text (a DM message; Send image beside it), else its image; null
 * when there is nothing to share. iOS has no Copy link action: the system's own Copy copies the
 * link the sheet carries.
 */
internal fun raftShareOutPlan(subject: RaftShareOutSubject, platform: RaftShareOutPlatform): RaftShareOutPlan? =
    when {
        subject.hasLink ->
            RaftShareOutPlan(
                primary = RaftShareOutPayloadKind.Link,
                actions =
                    buildList {
                        if (platform == RaftShareOutPlatform.Android) add(RaftShareOutAction.CopyLink)
                        if (subject.hasText) add(RaftShareOutAction.SendText)
                        if (subject.imageAvailable) add(RaftShareOutAction.SendImage)
                    },
            )
        subject.hasText ->
            RaftShareOutPlan(
                primary = RaftShareOutPayloadKind.Text,
                actions = if (subject.imageAvailable) listOf(RaftShareOutAction.SendImage) else emptyList(),
            )
        subject.imageAvailable -> RaftShareOutPlan(primary = RaftShareOutPayloadKind.Image, actions = emptyList())
        else -> null
    }

/** The sheet an image file is shared in: Android adds Save image, which iOS's sheet has itself. */
internal fun raftShareOutImageActions(platform: RaftShareOutPlatform): List<RaftShareOutAction> =
    if (platform == RaftShareOutPlatform.Android) listOf(RaftShareOutAction.SaveImage) else emptyList()

/** The DM rule: a conversation only its two people can open gives no link. */
internal fun raftShareOutLinkAllowed(directMessage: Boolean): Boolean = !directMessage

/**
 * A task's canonical link, `{web}/tasks/<22-character Base58 short id>` (slock `taskPagePath`; the
 * id unchanged where it is not a UUID, as `taskLinkId`), on [webBaseUrl], the Developer
 * environment's web URL. Null for a blank id or a task in a DM ([directMessage]).
 */
internal fun raftTaskShareLink(webBaseUrl: String?, taskId: String?, directMessage: Boolean): String? {
    if (!raftShareOutLinkAllowed(directMessage)) return null
    val id = taskId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val linkId = encodeArtifactShortId(id) ?: id
    if (linkId.any { it == '/' || it == '?' || it == '#' || it.isWhitespace() }) return null
    return "${sharedSettingsResolvedWebBaseUrl(webBaseUrl)}/tasks/$linkId"
}

/**
 * The link of a channel or Lounge (`/s/{slug}/channel/{cid}`) or of a reply thread
 * (`…?thread={cid}%3A{pid}` on its parent channel); null in a DM, a thread in a DM, or without
 * the ids [buildRaftMessageLink] needs.
 */
internal fun raftConversationShareLink(
    webBaseUrl: String?,
    serverSlug: String?,
    channelId: String?,
    threadParentMessageId: String?,
    directMessage: Boolean,
): String? {
    if (!raftShareOutLinkAllowed(directMessage)) return null
    val slug = serverSlug?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val channel = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return buildRaftMessageLink(
        RaftMessageLink(
            serverSlug = slug,
            routeKind = ConversationKind.Channel,
            channelId = channel,
            threadParentMessageId = threadParentMessageId?.trim()?.takeIf { it.isNotEmpty() },
        ),
        webBaseUrl,
    )
}

/**
 * The words "Send text" sends for one message: its text, then [attribution] ("— Kirby, from Raft")
 * on its own line. What the sheet previews is what is sent: nothing is added later. Null for a
 * message without text.
 */
internal fun raftShareOutMessageText(body: String?, attribution: String?): String? {
    val text = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val line = attribution?.trim()?.takeIf { it.isNotEmpty() } ?: return text
    return "$text\n$line"
}

/** One host action of a `shareOut` command: its id and its label in the person's language. */
internal data class RaftShareOutHostAction(val action: RaftShareOutAction, val label: String)

/**
 * The host command for one sheet (`settingsCapability` action `shareOut`, a stream: the host answers
 * with [RaftShareOutHostEvent]s until a terminal one).
 *  - `kind`: link | text | image; `link` / `text` / `imagePath` is the payload of that kind;
 *  - `title`: the preview title of a link sheet (a text or image sheet previews its own payload);
 *  - `actions`: Raft's actions in the sheet, in order (`id`, `label`);
 *  - `chooseTitle`, `primaryLabel`: where the system sheet cannot hold actions (Android 13 and
 *    older), the host asks first, in a list titled `chooseTitle`: `primaryLabel` (open the sheet)
 *    or one of the actions.
 */
internal fun raftShareOutHostCommand(
    requestId: String,
    kind: RaftShareOutPayloadKind,
    title: String,
    link: String?,
    text: String?,
    imagePath: String?,
    actions: List<RaftShareOutHostAction>,
    chooseTitle: String,
    primaryLabel: String,
): JSONObject {
    val command =
        JSONObject()
            .put("action", RAFT_SHARE_OUT_HOST_ACTION)
            .put("requestId", requestId)
            .put("kind", kind.wire)
            .put("title", title)
            .put("chooseTitle", chooseTitle)
            .put("primaryLabel", primaryLabel)
    when (kind) {
        RaftShareOutPayloadKind.Link -> command.put("link", link.orEmpty())
        RaftShareOutPayloadKind.Text -> command.put("text", text.orEmpty())
        RaftShareOutPayloadKind.Image -> command.put("imagePath", imagePath.orEmpty())
    }
    val list = JSONArray()
    actions.forEach { list.put(JSONObject().put("id", it.action.wire).put("label", it.label)) }
    command.put("actions", list)
    return command
}

internal const val RAFT_SHARE_OUT_HOST_ACTION = "shareOut"

/** What the host reports for one sheet. */
internal sealed interface RaftShareOutHostEvent {
    /** One of Raft's actions was picked; the sheet is gone. */
    data class ActionPicked(val action: RaftShareOutAction) : RaftShareOutHostEvent

    /**
     * Android: the person picked a target ([appLabel]; blank when the system would not name it). The
     * system does not say whether anything was sent, hence "Opened <App>".
     */
    data class TargetOpened(val appLabel: String) : RaftShareOutHostEvent

    /**
     * iOS: the share finished in [activityType]. [copied]: it was the system's Copy.
     */
    data class Completed(val activityType: String, val copied: Boolean) : RaftShareOutHostEvent

    /** iOS: the sheet was closed without sharing. Android never says. */
    data object Cancelled : RaftShareOutHostEvent

    /** The sheet could not open, or the host has none. */
    data object Failed : RaftShareOutHostEvent
}

/** The host's answer as an event; null for one that is not terminal news (`presented`). */
internal fun raftShareOutHostEvent(payload: JSONObject?): RaftShareOutHostEvent? {
    if (payload == null) return RaftShareOutHostEvent.Failed
    return when (payload.optString("event")) {
        "action" -> raftShareOutActionOrNull(payload.optString("id"))?.let(RaftShareOutHostEvent::ActionPicked)
        "chosen" -> RaftShareOutHostEvent.TargetOpened(payload.optString("app").trim())
        "completed" ->
            RaftShareOutHostEvent.Completed(
                activityType = payload.optString("activityType").trim(),
                copied = payload.optBoolean("copied", false),
            )
        "cancelled" -> RaftShareOutHostEvent.Cancelled
        "presented" -> null
        else -> RaftShareOutHostEvent.Failed
    }
}

/**
 * The app an iOS share went to, for "Shared to <App>". iOS reports the activity's type (a share
 * extension's bundle id), never an app name, and has no public way from one to the other, so only
 * these well-known ones are named; any other gets the plain "Shared".
 */
internal fun raftShareOutIosTargetName(activityType: String): String? {
    val type = activityType.trim()
    if (type.isEmpty()) return null
    return RaftShareOutIosTargets.firstOrNull { (prefix, _) -> type == prefix || type.startsWith("$prefix.") }?.second
}

private val RaftShareOutIosTargets: List<Pair<String, String>> =
    listOf(
        "com.apple.UIKit.activity.Message" to "Messages",
        "com.apple.UIKit.activity.Mail" to "Mail",
        "com.apple.UIKit.activity.AirDrop" to "AirDrop",
        "com.tencent.xin" to "WeChat",
        "com.tencent.mqq" to "QQ",
        "com.tencent.ww" to "WeCom",
        "com.bytedance.ee.lark" to "Lark",
        "com.bytedance.ee.feishu" to "Feishu",
        "com.laiwang.DingTalk" to "DingTalk",
        "com.tinyspeck.chatlyio" to "Slack",
        "ph.telegra.Telegraph" to "Telegram",
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "com.hammerandchisel.discord" to "Discord",
        "com.microsoft.skype.teams" to "Teams",
    )

/** The 2-second note after a share: what it says, or null for none (cancelled, failed, an action). */
internal sealed interface RaftShareOutNotice {
    /** Android "Opened <App>". */
    data class Opened(val app: String) : RaftShareOutNotice

    /** iOS "Shared to <App>". */
    data class SharedTo(val app: String) : RaftShareOutNotice

    /** iOS, an app it cannot name: "Shared". */
    data object Shared : RaftShareOutNotice

    /** The link was copied (Raft's Copy link, or iOS's own Copy on a link sheet). */
    data object Copied : RaftShareOutNotice
}

internal fun raftShareOutNotice(event: RaftShareOutHostEvent, kind: RaftShareOutPayloadKind): RaftShareOutNotice? =
    when (event) {
        is RaftShareOutHostEvent.TargetOpened -> event.appLabel.takeIf { it.isNotBlank() }?.let(RaftShareOutNotice::Opened)
        is RaftShareOutHostEvent.Completed ->
            when {
                event.copied -> RaftShareOutNotice.Copied
                else -> raftShareOutIosTargetName(event.activityType)?.let(RaftShareOutNotice::SharedTo) ?: RaftShareOutNotice.Shared
            }
        is RaftShareOutHostEvent.ActionPicked ->
            if (event.action == RaftShareOutAction.CopyLink && kind == RaftShareOutPayloadKind.Link) RaftShareOutNotice.Copied else null
        RaftShareOutHostEvent.Cancelled, RaftShareOutHostEvent.Failed -> null
    }

internal const val RaftShareOutNoticeMillis: Long = 2_000

/**
 * `share_action_selected` for what the host reported about a sheet of [kind] for [subject]; null
 * when nothing is recorded: Send text / Send image / Save image only lead to the next step, which
 * reports itself. Only enums leave: no link, no text, no app name.
 */
internal fun raftShareOutEventProperties(
    subject: RaftShareOutSubject,
    kind: RaftShareOutPayloadKind,
    event: RaftShareOutHostEvent,
): RaftShareActionSelectedProperties? {
    val sheetMethod =
        when (kind) {
            RaftShareOutPayloadKind.Link -> RaftShareActionMethod.Link
            RaftShareOutPayloadKind.Text -> RaftShareActionMethod.Text
            RaftShareOutPayloadKind.Image -> RaftShareActionMethod.Image
        }
    val (method, result) =
        when (event) {
            is RaftShareOutHostEvent.TargetOpened -> sheetMethod to RaftShareActionResult.Opened
            is RaftShareOutHostEvent.Completed ->
                if (event.copied && kind == RaftShareOutPayloadKind.Link) {
                    RaftShareActionMethod.CopyLink to RaftShareActionResult.Copied
                } else {
                    sheetMethod to RaftShareActionResult.Shared
                }
            is RaftShareOutHostEvent.ActionPicked ->
                if (event.action == RaftShareOutAction.CopyLink) {
                    RaftShareActionMethod.CopyLink to RaftShareActionResult.Copied
                } else {
                    return null
                }
            RaftShareOutHostEvent.Cancelled -> sheetMethod to RaftShareActionResult.Cancelled
            RaftShareOutHostEvent.Failed -> sheetMethod to RaftShareActionResult.Failed
        }
    return RaftShareActionSelectedProperties(obj = subject.obj, method = method, surface = subject.surface, result = result)
}

/** A menu's own "Copy link" beside Share (a conversation's or a task's ⋯): the same event, method copy_link. */
internal fun raftShareOutCopyLinkEventProperties(
    obj: RaftShareActionObject,
    surface: RaftShareActionSurface,
): RaftShareActionSelectedProperties =
    RaftShareActionSelectedProperties(
        obj = obj,
        method = RaftShareActionMethod.CopyLink,
        surface = surface,
        result = RaftShareActionResult.Copied,
    )
