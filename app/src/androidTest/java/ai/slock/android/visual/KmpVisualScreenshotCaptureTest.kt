package ai.slock.android.visual

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Build
import android.app.Activity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import ai.slock.android.shared.SlockKuiklyActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test

class KmpVisualScreenshotCaptureTest {
    @Test
    fun captureHomeServerSwitcherOpen() {
        captureKmpHomeCase("home.server-switcher.open", homeServerSwitcherPageData())
    }

    @Test
    fun captureNavigationTabbarStates() {
        captureKmpComponentCase(
            caseId = "navigation.tabbar.states",
            pageName = "compose_bottom_tabbar_visual",
            crop = VisualCrop(leftDp = 0, topDp = 72, widthDp = 342, heightDp = 53)
        )
    }

    @Test
    fun captureThreadComposerStates() {
        captureKmpComponentCase(
            caseId = "thread.composer.states",
            pageName = "compose_thread_composer",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-composer")
                    .put("payload", threadComposerPayload().toString()),
            crop = VisualCrop(leftDp = 24, topDp = 0, widthDp = 342, heightDp = 100)
        )
    }

    @Test
    fun captureThreadMessageRow() {
        captureKmpComponentCase(
            caseId = "thread.message.row",
            pageName = "compose_thread_message_visual",
            pageData =
                JSONObject()
                    .put("instanceId", "visual-thread-message-row")
                    .put("payload", threadMessageRowPayload().toString()),
            crop = VisualCrop(leftDp = 0, topDp = 0, widthDp = 390, heightDp = 180)
        )
    }

    @Test
    fun captureHomeChannelRowStates() {
        captureKmpComponentCase(
            caseId = "home.channel-row.states",
            pageName = "compose_home_channel_row_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 154)
        )
    }

    @Test
    fun captureHomeTitlebarStates() {
        captureKmpComponentCase(
            caseId = "home.titlebar.states",
            pageName = "compose_home_titlebar_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 56)
        )
    }

    @Test
    fun captureHomeNotificationCenterStates() {
        captureKmpComponentCase(
            caseId = "home.notification-center.states",
            pageName = "compose_home_notification_center_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 276)
        )
    }

    @Test
    fun captureHomeTopActionsStates() {
        captureKmpComponentCase(
            caseId = "home.top-actions.states",
            pageName = "compose_home_top_actions_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 128)
        )
    }

    @Test
    fun captureSettingsRowStates() {
        captureKmpComponentCase(
            caseId = "settings.row.states",
            pageName = "compose_settings_row_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 332)
        )
    }

    @Test
    fun captureSettingsPageStates() {
        captureKmpComponentCase(
            caseId = "settings.page.states",
            pageName = "compose_settings_page_visual",
            crop = VisualCrop(leftDp = 0, topDp = 48, widthDp = 342, heightDp = 520)
        )
    }

    @Test
    fun captureAuthLoginInputs() {
        captureKmpComponentCase(
            caseId = "auth.login.inputs",
            pageName = "compose_auth_login_inputs",
            crop = VisualCrop(leftDp = 24, topDp = 0, widthDp = 342, heightDp = 620),
            settleMs = 4_500
        )
    }

    @Test
    fun captureAuthRegisterInputs() {
        captureKmpComponentCase(
            caseId = "auth.register.inputs",
            pageName = "compose_auth_register_inputs",
            crop = VisualCrop(leftDp = 24, topDp = 0, widthDp = 342, heightDp = 700),
            settleMs = 4_500
        )
    }

    private fun captureKmpHomeCase(caseId: String, pageData: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_HOME)
                .putExtra("pageData", pageData.toString())
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(2_500)
            val screenshot =
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                    "Unable to capture KMP visual screenshot for $caseId"
                }
            val crop = VisualCrop(leftDp = 24, topDp = 60, widthDp = 342, heightDp = 238)
            validateCropBounds(caseId, screenshot, crop)
            val cropped = screenshot.cropDp(crop)
            val normalized = cropped.normalizeToDpPixels(context)
            validateVisualBitmap(context, caseId, normalized, crop)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            clearMirroredCaseArtifacts(context, caseId)
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                visualMetadata(
                    context = context,
                    caseId = caseId,
                    imageFile = imageFile,
                    bitmap = normalized,
                    crop = crop,
                    captureMethod = "full-screen-takeScreenshot-manual-crop-density-resize"
                )
            )
            mirrorFileForAdbPull(context, imageFile)
            mirrorFileForAdbPull(context, metadataFile)
            if (normalized !== cropped) {
                normalized.recycle()
            }
            if (cropped !== screenshot) {
                cropped.recycle()
            }
            screenshot.recycle()
        }
    }

    private fun captureKmpComponentCase(
        caseId: String,
        pageName: String,
        crop: VisualCrop,
        pageData: JSONObject? = null,
        settleMs: Long = 1_500
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", pageName)
                .apply {
                    if (pageData != null) {
                        putExtra("pageData", pageData.toString())
                    }
                }
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { applyVisualFullscreen(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(settleMs)
            val screenshot =
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                    "Unable to capture KMP visual screenshot for $caseId"
                }
            validateCropBounds(caseId, screenshot, crop)
            val cropped = screenshot.cropDp(crop)
            val normalized = cropped.normalizeToDpPixels(context)
            validateVisualBitmap(context, caseId, normalized, crop)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            clearMirroredCaseArtifacts(context, caseId)
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                visualMetadata(
                    context = context,
                    caseId = caseId,
                    imageFile = imageFile,
                    bitmap = normalized,
                    crop = crop,
                    captureMethod = "full-screen-takeScreenshot-manual-crop-density-resize"
                )
            )
            mirrorFileForAdbPull(context, imageFile)
            mirrorFileForAdbPull(context, metadataFile)
            if (normalized !== cropped) {
                normalized.recycle()
            }
            if (cropped !== screenshot) {
                cropped.recycle()
            }
            screenshot.recycle()
        }
    }

    private fun applyVisualFullscreen(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun homeServerSwitcherPageData(): JSONObject {
        val payload =
            JSONObject()
                .put("instanceId", "visual-home-server-switcher")
                .put("currentServerName", "Slock Design")
                .put("currentServerId", "srv_design")
                .put("serverUnreadCount", 24)
                .put("inboxUnreadCount", 7)
                .put("savedCount", 3)
                .put("serverSwitcherExpanded", true)
                .put("applyTopSafeArea", false)
                .put("contentRefreshEnabled", false)
                .put("bottomContentPaddingDp", 24)
                .put(
                    "servers",
                    JSONArray()
                        .put(server("srv_design", "Slock Design", "slock-design", 24))
                        .put(server("srv_android", "Android Artifacts", "android-artifacts", 12))
                        .put(server("srv_feedback", "Feedback", "feedback", 3))
                )
                .put(
                    "channels",
                    JSONArray()
                        .put(conversation("ch_design", "design", 3, joined = true))
                        .put(conversation("ch_android", "android-artifacts", 0, joined = true))
                )
                .put(
                    "directMessages",
                    JSONArray()
                        .put(conversation("dm_android", "Android Developer", 8, avatarLabel = "AD", presence = "Online"))
                        .put(conversation("dm_product", "Product UX Designer", 0, avatarLabel = "PU", presence = "Offline", hasDraft = true))
                )
        return JSONObject()
            .put("instanceId", "visual-home-server-switcher")
            .put("payload", payload.toString())
    }

    private fun server(id: String, name: String, slug: String, unreadCount: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("slug", slug)
            .put("unreadCount", unreadCount)

    private fun threadComposerPayload(): JSONObject =
        JSONObject()
            .put("instanceId", "visual-thread-composer")
            .put("seedText", "Review the Android composer crop before release.")
            .put("seedAsTask", false)
            .put("syncNonce", 1)
            .put("focusRequestNonce", 0)
            .put("placeholder", "Message design")
            .put("enabled", true)
            .put("showAsTaskToggle", true)
            .put("composerMode", "idle")
            .put(
                "memberSuggestions",
                JSONArray()
                    .put(JSONObject().put("displayName", "Artin").put("handle", "artin"))
                    .put(JSONObject().put("displayName", "Android Developer").put("handle", "Android-Developer"))
            )
            .put(
                "channelSuggestions",
                JSONArray()
                    .put(JSONObject().put("name", "design").put("subtitle", "Product and UI decisions"))
                    .put(JSONObject().put("name", "android-artifacts").put("subtitle", "Builds and screenshots"))
            )

    private fun threadMessageRowPayload(): JSONObject =
        JSONObject()
            .put("instanceId", "visual-thread-message-row")
            .put("title", "#design")
            .put("subtitle", "Thread visual fixture")
            .put("status", "content")
            .put("selectedTab", "chat")
            .put("showModeTabs", false)
            .put("showComposer", false)
            .put("joined", true)
            .put("showReplyActions", true)
            .put("showMessageSaveAction", false)
            .put("showTaskOpenActions", true)
            .put("showTaskStatusActions", false)
            .put("messages", JSONArray().put(threadMessageRow()))

    private fun threadMessageRow(): JSONObject =
        JSONObject()
            .put("id", "msg_agent_reply")
            .put("authorId", "agent_android")
            .put("authorName", "Android Developer")
            .put("isOwn", false)
            .put("avatarLabel", "AD")
            .put("timestampLabel", "10:48")
            .put("messageType", "Chat")
            .put(
                "body",
                "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。关联 #design 和 @Product-UX-Designer。"
            )
            .put("replyCount", 5)
            .put("taskId", "task_msg_10")
            .put("taskNumber", 10)
            .put("taskStatus", "in_review")

    private fun conversation(
        id: String,
        name: String,
        unreadCount: Int,
        avatarLabel: String = name.take(2).uppercase(),
        presence: String? = null,
        hasDraft: Boolean = false,
        joined: Boolean = true
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("unreadCount", unreadCount)
            .put("avatarLabel", avatarLabel)
            .put("hasDraft", hasDraft)
            .put("joined", joined)
            .apply {
                if (presence != null) put("presence", presence)
            }

    private fun Bitmap.cropDp(crop: VisualCrop): Bitmap {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val left = (crop.leftDp * density).toInt().coerceIn(0, width - 1)
        val top = (crop.topDp * density).toInt().coerceIn(0, height - 1)
        val desiredWidth = (crop.widthDp * density).toInt()
        val desiredHeight = (crop.heightDp * density).toInt()
        val cropWidth = desiredWidth.coerceAtMost(width - left)
        val cropHeight = desiredHeight.coerceAtMost(height - top)
        return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    }

    private fun Bitmap.normalizeToDpPixels(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val targetWidth = (width / density).toInt().coerceAtLeast(1)
        val targetHeight = (height / density).toInt().coerceAtLeast(1)
        if (targetWidth == width && targetHeight == height) return this
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun validateCropBounds(caseId: String, screenshot: Bitmap, crop: VisualCrop) {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val left = (crop.leftDp * density).toInt()
        val top = (crop.topDp * density).toInt()
        val desiredWidth = (crop.widthDp * density).toInt()
        val desiredHeight = (crop.heightDp * density).toInt()
        require(left >= 0 && top >= 0) {
            "$caseId visual crop starts outside screenshot: crop=$crop screenshot=${screenshot.width}x${screenshot.height}"
        }
        require(left + desiredWidth <= screenshot.width && top + desiredHeight <= screenshot.height) {
            "$caseId visual crop is clipped: crop=$crop px=($left,$top ${desiredWidth}x$desiredHeight) screenshot=${screenshot.width}x${screenshot.height}"
        }
    }

    private fun validateVisualBitmap(context: Context, caseId: String, bitmap: Bitmap, crop: VisualCrop) {
        val stats = bitmap.visualStats()
        val problems = mutableListOf<String>()
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            problems += "bitmap is too small: ${bitmap.width}x${bitmap.height}"
        }
        if (stats.uniqueColors <= 2) {
            problems += "bitmap has only ${stats.uniqueColors} unique color(s)"
        }
        if (stats.dominantRatio >= 0.985f) {
            problems += "bitmap is ${"%.2f".format(stats.dominantRatio * 100)}% one color"
        }
        if (problems.isNotEmpty()) {
            writeFailureArtifacts(context, caseId, bitmap, crop, stats, problems)
            throw AssertionError("$caseId visual capture failed validation: ${problems.joinToString("; ")}")
        }
    }

    private fun Bitmap.visualStats(): VisualBitmapStats {
        val counts = linkedMapOf<Int, Int>()
        val pixels = IntArray(width)
        for (y in 0 until height) {
            getPixels(pixels, 0, width, 0, y, width, 1)
            for (pixel in pixels) {
                val opaque = Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                counts[opaque] = (counts[opaque] ?: 0) + 1
            }
        }
        val total = width * height
        val dominant = counts.values.maxOrNull() ?: 0
        return VisualBitmapStats(
            uniqueColors = counts.size,
            dominantRatio = if (total == 0) 1f else dominant.toFloat() / total.toFloat()
        )
    }

    private fun writeFailureArtifacts(
        context: Context,
        caseId: String,
        bitmap: Bitmap,
        crop: VisualCrop,
        stats: VisualBitmapStats,
        problems: List<String>
    ) {
        val outputDir = visualOutputDir(context)
        outputDir.mkdirs()
        clearMirroredCaseArtifacts(context, caseId)
        val imageFile = File(outputDir, "$caseId.failure.png")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val metadataFile = File(outputDir, "$caseId.failure.json")
        metadataFile.writeText(
            """
            {
              "provider": "android",
              "providerType": "android-kuikly-shared-page",
              "source": "kmp-shared",
              "caseId": "$caseId",
              "status": "failed",
              "image": "${imageFile.absolutePath}",
              "systemBars": "hidden",
              "crop": {
                "mode": "manual-rect",
                "contract": "component-bounds",
                "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
              },
              "validation": {
                "uniqueColors": ${stats.uniqueColors},
                "dominantRatio": ${stats.dominantRatio},
                "errors": ${JSONArray(problems)}
              },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
        )
        mirrorFileForAdbPull(context, imageFile)
        mirrorFileForAdbPull(context, metadataFile)
    }

    private fun visualMetadata(
        context: Context,
        caseId: String,
        imageFile: File,
        bitmap: Bitmap,
        crop: VisualCrop,
        captureMethod: String
    ): String {
        val stats = bitmap.visualStats()
        return """
            {
              "provider": "android",
              "providerType": "android-kuikly-shared-page",
              "source": "kmp-shared",
              "caseId": "$caseId",
              "image": "${imageFile.absolutePath}",
              "viewport": { "width": ${bitmap.width}, "height": ${bitmap.height}, "density": 1 },
              "systemBars": "hidden",
              "crop": {
                "mode": "manual-rect",
                "contract": "component-bounds",
                "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
              },
              "capture": {
                "method": "$captureMethod",
                "fullscreen": true,
                "systemBars": "hidden",
                "validation": {
                  "uniqueColors": ${stats.uniqueColors},
                  "dominantRatio": ${stats.dominantRatio}
                }
              },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
    }

    private fun visualOutputDir(context: Context): File =
        File(context.filesDir, "visual-testing/android")

    private fun mirrorFileForAdbPull(context: Context, file: File) {
        VisualAndroidArtifacts.mirrorFile(file)
    }

    private fun clearMirroredCaseArtifacts(context: Context, caseId: String) {
        File(visualOutputDir(context), "$caseId.png").delete()
        File(visualOutputDir(context), "$caseId.metadata.json").delete()
        File(visualOutputDir(context), "$caseId.failure.png").delete()
        File(visualOutputDir(context), "$caseId.failure.json").delete()
        deletePublicDownloadArtifactsWithShell(caseId)
    }

    private fun deletePublicDownloadArtifactsWithShell(caseId: String) {
        VisualAndroidArtifacts.deleteCaseArtifacts(caseId)
    }


    private data class VisualCrop(
        val leftDp: Int,
        val topDp: Int,
        val widthDp: Int,
        val heightDp: Int
    )

    private data class VisualBitmapStats(
        val uniqueColors: Int,
        val dominantRatio: Float
    )

    companion object {
        @BeforeClass
        @JvmStatic
        fun clearVisualOutputDirectory() {
            VisualAndroidArtifacts.resetPublicOutput()
        }
    }
}
