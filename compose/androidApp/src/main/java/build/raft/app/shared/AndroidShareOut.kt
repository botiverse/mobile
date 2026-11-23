package build.raft.app.shared

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.chooser.ChooserAction
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import build.raft.app.R
import build.raft.app.RaftShareOutResumeNotice
import build.raft.app.core.data.diagnostics.RaftAppLog
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Share out (share loop, first batch): the system share sheet for one `shareOut` command of the
 * shared module (RaftShareOut.kt, which decides what is sent and which actions the sheet has; this
 * only presents it and reports what the person did).
 *
 *  - `kind` link / text: `ACTION_SEND` `text/plain` with the link or the text; a link's `title` is
 *    the sheet's preview title (`EXTRA_TITLE`). `kind` image: `image/png` with a FileProvider URI.
 *  - `actions`: Raft's own actions in the sheet, `EXTRA_CHOOSER_CUSTOM_ACTIONS` (Android 14+). A
 *    custom action can only close the sheet, so each one is reported back and the shared module
 *    acts on it (copies the link, or asks for a new sheet with the text or the image). Android 13
 *    and older have no such row: the person is asked first in a plain list (`chooseTitle`:
 *    `primaryLabel` opens the sheet, the rest are the actions).
 *  - The target the person picks comes back through the chooser's IntentSender
 *    (`EXTRA_CHOSEN_COMPONENT`). The system says which app, never whether anything was sent, and
 *    says nothing when the sheet is closed without a pick.
 *
 * Answers (each ends the command, `terminal`): `{event: "action", id}`, `{event: "chosen", app}`,
 * `{event: "cancelled"}` (the Android 13 list only), `{event: "failed"}`.
 */
/**
 * Whether Android must ask the person to pick before opening the share sheet: only on Android 13
 * and older (no `EXTRA_CHOOSER_CUSTOM_ACTIONS`) AND only when there is at least one Raft action to
 * choose between. Conversation / task ⋯ menus send no actions so they go straight to the sheet.
 */
internal fun androidShareOutNeedsPreSheetPicker(actionCount: Int, sdkInt: Int): Boolean =
    actionCount > 0 && sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

internal object AndroidShareOut {
    private const val ACTION_CUSTOM = "build.raft.app.share.CUSTOM_ACTION"
    private const val ACTION_CHOSEN = "build.raft.app.share.TARGET_CHOSEN"
    private const val EXTRA_TOKEN = "build.raft.app.share.token"
    private const val EXTRA_ACTION_ID = "build.raft.app.share.actionId"
    private const val SHARE_IMAGE_FILE_NAME = "raft-share.png"

    private class Session(val token: String, val callback: RaftBridgeCallback?)

    private class ShareAction(val id: String, val label: String)

    // Main thread only: the one sheet that may still answer. A new sheet replaces it.
    private var session: Session? = null
    private var nextRequestCode = 0

    fun present(context: Context, activity: Activity?, request: JSONObject, callback: RaftBridgeCallback?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { present(context, activity, request, callback) }
            return
        }
        val actions =
            request.optJSONArray("actions")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val label = item.optString("label")
                    if (id.isBlank() || label.isBlank()) null else ShareAction(id, label)
                }
            }.orEmpty()
        val token = UUID.randomUUID().toString()
        session = Session(token, callback)
        val customActionsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val liveActivity = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (androidShareOutNeedsPreSheetPicker(actions.size, Build.VERSION.SDK_INT) && liveActivity != null) {
            chooseFirst(context, liveActivity, request, actions, token)
            return
        }
        openSheet(context, liveActivity, request, if (customActionsSupported) actions else emptyList(), token)
    }

    /** Android 13 and older: the sheet cannot hold Raft's actions, so the person picks before it opens. */
    private fun chooseFirst(context: Context, activity: Activity, request: JSONObject, actions: List<ShareAction>, token: String) {
        val labels = (listOf(request.optString("primaryLabel")) + actions.map { it.label }).toTypedArray<CharSequence>()
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle(request.optString("chooseTitle"))
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        openSheet(context, activity, request, emptyList(), token)
                    } else {
                        actions.getOrNull(which - 1)?.let { answer(token, mapOf("event" to "action", "id" to it.id)) }
                    }
                }
                .setOnCancelListener { answer(token, mapOf("event" to "cancelled")) }
                .show()
        }.onFailure { throwable ->
            RaftAppLog.record("SlockShare", "share_out_choose_failed error=${throwable.javaClass.simpleName}")
            openSheet(context, activity, request, emptyList(), token)
        }
    }

    private fun openSheet(context: Context, activity: Activity?, request: JSONObject, actions: List<ShareAction>, token: String) {
        val kind = request.optString("kind")
        val opened =
            runCatching {
                val send = sendIntent(context, kind, request)
                val chosen =
                    PendingIntent.getBroadcast(
                        context,
                        nextRequestCode++,
                        Intent(context, RaftShareOutReceiver::class.java).setAction(ACTION_CHOSEN).putExtra(EXTRA_TOKEN, token),
                        // Mutable: the system adds EXTRA_CHOSEN_COMPONENT. The Intent names this app's receiver.
                        // (Before Android 12 a PendingIntent is mutable unless it says otherwise.)
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    )
                val chooser = Intent.createChooser(send, null, chosen.intentSender)
                if (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                    chooser.clipData = send.clipData
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Raft's own "Share to Raft" target is for other apps' content, not for Raft's own.
                val own =
                    context.packageManager
                        .queryIntentActivities(Intent(send).setPackage(context.packageName), 0)
                        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                if (own.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
                if (actions.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, chooserActions(context, actions, token))
                }
                RaftShareOutResumeNotice.installClock(SystemClock::elapsedRealtime)
                RaftShareOutResumeNotice.onShareSheetOpened(activity?.let(System::identityHashCode))
                if (activity != null) {
                    activity.startActivity(chooser)
                } else {
                    context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        opened.onFailure { throwable ->
            RaftShareOutResumeNotice.onShareSheetOpened(null)
            RaftAppLog.record("SlockShare", "share_out_open_failed kind=$kind error=${throwable.javaClass.simpleName}")
            answer(token, mapOf("event" to "failed"))
        }
        if (opened.isSuccess) RaftAppLog.record("SlockShare", "share_out_opened kind=$kind actions=${actions.size}")
    }

    private fun sendIntent(context: Context, kind: String, request: JSONObject): Intent =
        Intent(Intent.ACTION_SEND).apply {
            when (kind) {
                "image" -> {
                    val uri = imageUri(context, request.optString("imagePath"))
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, SHARE_IMAGE_FILE_NAME, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                "text" -> {
                    val text = request.optString("text")
                    require(text.isNotBlank()) { "share text missing" }
                    type = "text/plain"
                    // No title: the sheet previews the text itself, which is exactly what is sent.
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                else -> {
                    val link = request.optString("link")
                    require(link.isNotBlank()) { "share link missing" }
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                    request.optString("title").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TITLE, it) }
                }
            }
        }

    /** The image as a FileProvider URI: in place when its folder is shared, else a copy in the share staging folder. */
    private fun imageUri(context: Context, path: String): Uri {
        val source = File(path)
        require(path.isNotBlank() && source.isFile && source.length() > 0L) { "share image missing" }
        val authority = "${context.packageName}.fileprovider"
        runCatching { FileProvider.getUriForFile(context, authority, source) }.getOrNull()?.let { return it }
        val shareRoot = File(context.cacheDir, ANDROID_ATTACHMENT_SHARE_DIR_NAME)
        sweepAndroidAttachmentShareStaging(shareRoot)
        val entry = File(shareRoot, UUID.randomUUID().toString())
        check(entry.mkdirs() || entry.isDirectory) { "share staging unavailable" }
        val target = File(entry, SHARE_IMAGE_FILE_NAME)
        source.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, authority, target)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun chooserActions(context: Context, actions: List<ShareAction>, token: String): Array<ChooserAction> {
        val night =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // The sheet draws the icon as given: ink that reads on its light or dark surface.
        val tint = if (night) 0xFFE6E1E5.toInt() else 0xFF1D1B20.toInt()
        return actions.map { action ->
            val icon =
                Icon.createWithResource(
                    context,
                    when (action.id) {
                        "copy_link" -> R.drawable.raft_share_action_link
                        "send_text" -> R.drawable.raft_share_action_text
                        "save_image" -> R.drawable.raft_share_action_save
                        else -> R.drawable.raft_share_action_image
                    }
                ).setTint(tint)
            val pick =
                PendingIntent.getBroadcast(
                    context,
                    nextRequestCode++,
                    Intent(context, RaftShareOutReceiver::class.java)
                        .setAction(ACTION_CUSTOM)
                        .putExtra(EXTRA_TOKEN, token)
                        .putExtra(EXTRA_ACTION_ID, action.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ChooserAction.Builder(icon, action.label, pick).build()
        }.toTypedArray()
    }

    fun onBroadcast(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        when (intent.action) {
            ACTION_CUSTOM -> answer(token, mapOf("event" to "action", "id" to intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()))
            ACTION_CHOSEN -> {
                val component = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
                answer(token, mapOf("event" to "chosen", "app" to appLabel(context, component)))
            }
        }
    }

    /** The picked app's name, or blank when the system will not say (then no "Opened <App>" is shown). */
    private fun appLabel(context: Context, component: ComponentName?): String {
        component ?: return ""
        val packageManager = context.packageManager
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0)).toString()
        }.getOrNull()?.trim().orEmpty()
    }

    private fun answer(token: String, payload: Map<String, Any>) {
        val run = {
            val current = session
            if (current != null && current.token == token) {
                session = null
                RaftAppLog.record("SlockShare", "share_out_answer event=${payload["event"]}")
                current.callback?.invoke(payload + ("terminal" to true))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else Handler(Looper.getMainLooper()).post { run() }
    }
}

/** The share sheet's answers for [AndroidShareOut]: a custom action, or the target the person picked. */
class RaftShareOutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent?.let { AndroidShareOut.onBroadcast(context.applicationContext, it) }
    }
}
