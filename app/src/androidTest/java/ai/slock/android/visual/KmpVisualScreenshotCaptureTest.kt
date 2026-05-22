package ai.slock.android.visual

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ai.slock.android.shared.SlockKuiklyActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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

    private fun captureKmpHomeCase(caseId: String, pageData: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyActivity.PAGE_HOME)
                .putExtra("pageData", pageData.toString())
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(2_500)
            val screenshot =
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                    "Unable to capture KMP visual screenshot for $caseId"
                }
            val cropped = screenshot.cropHomeServerSwitcher()
            val normalized = cropped.normalizeToDpPixels(context)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                """
                {
                  "provider": "android",
                  "providerType": "android-kuikly-shared-page",
                  "source": "kmp-shared",
                  "caseId": "$caseId",
                  "image": "${imageFile.absolutePath}",
                  "viewport": { "width": ${normalized.width}, "height": ${normalized.height}, "density": 1 },
                  "crop": {
                    "mode": "manual-rect",
                    "contract": "component-bounds",
                    "rect": { "x": 24, "y": 60, "width": 342, "height": 238 }
                  },
                  "capturedAt": "${System.currentTimeMillis()}"
                }
                """.trimIndent() + "\n"
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

    private fun captureKmpComponentCase(caseId: String, pageName: String, crop: VisualCrop) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", pageName)
        ActivityScenario.launch<SlockKuiklyActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1_500)
            val screenshot =
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                    "Unable to capture KMP visual screenshot for $caseId"
                }
            val cropped = screenshot.cropDp(crop)
            val normalized = cropped.normalizeToDpPixels(context)
            val outputDir = visualOutputDir(context)
            outputDir.mkdirs()
            val imageFile = File(outputDir, "$caseId.png")
            imageFile.outputStream().use { out ->
                normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val metadataFile = File(outputDir, "$caseId.metadata.json")
            metadataFile.writeText(
                """
                {
                  "provider": "android",
                  "providerType": "android-kuikly-shared-page",
                  "source": "kmp-shared",
                  "caseId": "$caseId",
                  "image": "${imageFile.absolutePath}",
                  "viewport": { "width": ${normalized.width}, "height": ${normalized.height}, "density": 1 },
                  "crop": {
                    "mode": "manual-rect",
                    "contract": "component-bounds",
                    "rect": { "x": ${crop.leftDp}, "y": ${crop.topDp}, "width": ${crop.widthDp}, "height": ${crop.heightDp} }
                  },
                  "capture": {
                    "method": "full-screen-takeScreenshot-manual-crop-density-resize"
                  },
                  "capturedAt": "${System.currentTimeMillis()}"
                }
                """.trimIndent() + "\n"
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

    private fun Bitmap.cropHomeServerSwitcher(): Bitmap {
        return cropDp(VisualCrop(leftDp = 24, topDp = 60, widthDp = 342, heightDp = 238))
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

    private fun visualOutputDir(context: Context): File =
        File(context.filesDir, "visual-testing/android")

    private fun mirrorFileForAdbPull(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath =
                "${Environment.DIRECTORY_DOWNLOADS}/slock-visual-testing/android/"
            val resolver = context.contentResolver
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(file.name, relativePath)
            )
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                    "Unable to create visual screenshot artifact ${file.name}"
                }
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Unable to open visual screenshot artifact ${file.name}" }
                file.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val outputDir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "slock-visual-testing/android"
                )
            outputDir.mkdirs()
            file.copyTo(File(outputDir, file.name), overwrite = true)
        }
    }

    private fun mimeTypeFor(file: File): String =
        when (file.extension) {
            "png" -> "image/png"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    private data class VisualCrop(
        val leftDp: Int,
        val topDp: Int,
        val widthDp: Int,
        val heightDp: Int
    )
}
