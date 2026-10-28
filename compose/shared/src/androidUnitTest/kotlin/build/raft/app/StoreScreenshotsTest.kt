package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.applyVisualCaptureLocale
import build.raft.app.data.restoreVisualCaptureLocale
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.resources.RaftBundledFiles
import build.raft.app.resources.Res
import build.raft.app.resources.*
import build.raft.designsystem.tokens.RaftThemeId
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Play / App Store marketing screenshots (artin 2026-10-27): whole app screens built from the
 * production composables with curated demo content (fictional agents and people, no real data),
 * in English, Simplified Chinese and Japanese (the app's UI locales), at every store device size
 * ([StoreProfiles]). Windows of 840dp and wider get the app's wide layout (top bar, navigation
 * rail, list + detail split), narrower ones the phone layout, as the app itself chooses
 * ([reduceRaftAdaptivePane]).
 *
 * Not a regression test: it only runs with `RAFT_STORE_SCREENSHOTS=<output dir>` and writes
 * `<dir>/<profile>/<lang>/<scene>.png`. Optional filters: `RAFT_STORE_PROFILES=phone,ipad` and
 * `RAFT_STORE_SCENES=01-chat,05-computers`. The marketing frames (background, headline, device)
 * are composed outside the app by `scripts/store-screenshots/frames.py`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class StoreScreenshotsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val outDir: String? = System.getenv("RAFT_STORE_SCREENSHOTS")
    private val profileFilter = System.getenv("RAFT_STORE_PROFILES").csvSet()
    private val sceneFilter = System.getenv("RAFT_STORE_SCENES").csvSet()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "store-screenshots"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun registerPager() {
        PagerManager.register(livePager)
        val prefs = mutableMapOf<String, String>()
        build.raft.app.data.RaftSharedKeyValueStoreProvider.installAppScopeFactory(build.raft.app.data.RaftLocalPreferenceStore.KuiklyNamespace) {
            object : build.raft.app.data.RaftKeyValueStore {
                override fun getString(key: String): String? = prefs[key]

                override fun putString(key: String, value: String?) {
                    if (value == null) prefs.remove(key) else prefs[key] = value
                }
            }
        }
    }

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test fun en() = captureAll(StoreLang.En)

    @Test fun zh() = captureAll(StoreLang.Zh)

    @Test fun ja() = captureAll(StoreLang.Ja)

    private fun captureAll(lang: StoreLang) {
        assumeTrue("set RAFT_STORE_SCREENSHOTS to render store screenshots", !outDir.isNullOrBlank())
        val copy = lang.copy
        val token = lang.locale.applyVisualCaptureLocale()
        // Compose resources (stringResource) follow the JVM default locale, not the qualifiers.
        val previousLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(lang.javaLocale)
        try {
            StoreProfiles.filter { profileFilter == null || it.key in profileFilter }.forEach { profile ->
                RuntimeEnvironment.setQualifiers(profile.qualifiers(lang.qualifier))
                val dir = File(File(outDir!!, profile.key), lang.key)
                if (profile.wide) {
                    capture(dir, "01-chat") { StoreWideChatScene(copy, profile) }
                    capture(dir, "02-activity") { StoreWideActivityScene(copy, profile) }
                    capture(dir, "03-agents") { StoreWideAgentsScene(copy, profile) }
                    capture(dir, "04-tasks") { StoreWideTasksScene(copy, profile) }
                    capture(dir, "05-computers") { StoreWideComputersScene(copy, profile) }
                } else {
                    capture(dir, "01-chat") { StoreChatScene(copy) }
                    capture(dir, "02-activity") { StoreActivityScene(copy) }
                    capture(dir, "03-agents") { StoreAgentsScene(copy) }
                    capture(dir, "04-tasks") { StoreTasksScene(copy) }
                    capture(dir, "05-computers") { StoreComputersScene(copy) }
                    capture(dir, "06-home") { StoreHomeScene(copy) }
                }
            }
        } finally {
            java.util.Locale.setDefault(previousLocale)
            restoreVisualCaptureLocale(token)
        }
    }

    private fun capture(dir: File, name: String, content: @Composable () -> Unit) {
        if (sceneFilter != null && name !in sceneFilter) return
        captureStoreScreen(activities, livePager, File(dir, "$name.png"), content = content)
    }
}

/**
 * Renders [content] full screen in a fresh activity (light appearance, app theme [theme] with its
 * decor off, clock fixed at [StoreTime]) and writes the window as a PNG to [file].
 */
internal fun captureStoreScreen(
    activities: RobolectricComposeActivityRule,
    pager: Pager,
    file: File,
    theme: RaftThemeId = RaftThemeId.Raft,
    content: @Composable () -> Unit,
) {
    val previous = RaftAppThemeState.appearance
    val previousTheme = RaftAppThemeState.raftTheme
    val previousDecor = RaftAppThemeState.decor
    RaftAppThemeState.apply(RaftAppAppearance.Light)
    RaftAppThemeState.setRaftTheme(theme, decor = false)
    try {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            val atlas =
                remember {
                    val bytes = runBlocking { Res.readBytes(RaftBundledFiles.reactionAtlasV1.path) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }
            val clock = remember { RaftTimeContextProvider(StoreTime) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides clock,
                build.raft.designsystem.components.LocalRaftSpinnerStillStep provides 0,
            ) {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadReactionAtlasImage provides atlas) {
                        Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
                    }
                }
            }
        }
        repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
        val root = findComposeRoot(activity.window.decorView)
        assertNotNull(root, "no compose root")
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("store screenshot ${file.path} (${bitmap.width}x${bitmap.height})")
    } finally {
        RaftAppThemeState.apply(previous)
        RaftAppThemeState.setRaftTheme(previousTheme, previousDecor)
    }
}

private fun findComposeRoot(view: View): View? {
    if (view is ViewRootForTest) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
    }
    return null
}

private fun String?.csvSet(): Set<String>? =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

/**
 * One store device size, in dp at the device's density. Width >= 840dp is the app's DualPane
 * (wide) layout; anything narrower is the phone layout.
 */
internal data class StoreProfile(val key: String, val widthDp: Int, val heightDp: Int, val density: String) {
    val wide: Boolean get() = widthDp >= 840

    fun qualifiers(locale: String): String =
        "$locale-w${widthDp}dp-h${heightDp}dp-${if (widthDp > heightDp) "land" else "port"}-$density"
}

internal val StoreProfiles =
    listOf(
        // Google Play phone (framed into 1080x1920).
        StoreProfile("phone", 390, 844, "xxhdpi"),
        // iPhone 6.9" / 6.5" (440x956 / 414x896 pt): one render between the two.
        StoreProfile("iphone", 430, 932, "xxhdpi"),
        // Google Play 7" / 10" tablets, landscape: a 16:10 tablet window, wide layout.
        StoreProfile("tablet", 1024, 640, "xhdpi"),
        // iPad 13" portrait (1032x1376 pt): wide layout.
        StoreProfile("ipad", 1032, 1376, "xhdpi"),
        // iPhone Duo inner display (2007x2853 px @3x = 669x951 pt) portrait and landscape, and
        // the outer display (1398x2034 px = 466x678 pt).
        StoreProfile("duo-inner-portrait", 669, 951, "xxhdpi"),
        StoreProfile("duo-inner-landscape", 951, 669, "xxhdpi"),
        StoreProfile("duo-outer", 466, 678, "xxhdpi"),
    )

internal enum class StoreLang(val key: String, val locale: AppLocale, val qualifier: String, val copy: StoreCopy) {
    En("en", AppLocale.English, "en-rUS", StoreCopy.En),
    Zh("zh-Hans", AppLocale.SimplifiedChinese, "zh-rCN", StoreCopy.Zh),
    Ja("ja", AppLocale.Japanese, "ja-rJP", StoreCopy.Ja);

    val javaLocale: java.util.Locale
        get() = java.util.Locale(qualifier.substringBefore("-r"), qualifier.substringAfter("-r"))
}

// 2026-10-27T05:00:00Z (13:00 in UTC+8): every row reads "N hours ago", short enough not to cut.
internal val StoreTime =
    RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = 20_753L, nowEpochMillis = 1_793_077_200_000L)

private const val ServerName = "Northwind Labs"

internal data class StoreMessage(
    val authorId: String,
    val body: String,
    val time: String,
    val reactions: List<Pair<String, Int>> = emptyList(),
    val mentionsRuby: Boolean = false,
    val replies: List<Pair<String, String>> = emptyList(), // author id, reply
)

internal data class StoreComputer(val name: String, val host: String, val platform: String, val online: Boolean = true, val version: String = "1.4.2")

/** Every visible demo string, per locale. Names are fictional; nothing comes from a real server. */
internal class StoreCopy(
    val channelSubtitle: String,
    val chat: List<StoreMessage>,
    val dmAtlas: List<StoreMessage>,
    val channelPreview: String,
    val dmPreview: String,
    val supportPreview: String,
    /** Task number -> (title, latest activity preview); 482, 476 and 470 show in Activity. */
    val taskThreads: Map<Int, Pair<String, String>>,
    val agentDescriptions: Map<String, String>,
    val humans: List<Triple<String, String, String>>, // id, name, role
    val tasks: List<Triple<Int, String, String>>, // number, title, status
    val computers: List<StoreComputer>,
) {
    companion object {
        val En =
            StoreCopy(
                channelSubtitle = "Release train for the mobile app",
                chat =
                    listOf(
                        StoreMessage("human-priya", "Dark mode mocks for onboarding are up in #design.", "9:20 AM"),
                        StoreMessage("agent-pixel", "Exported the assets at 1x, 2x and 3x and attached them to #486.", "9:24 AM", reactions = listOf("👍" to 2)),
                        StoreMessage("agent-sage", "Settings docs are updated for 2.4.", "9:31 AM"),
                        StoreMessage("human-leo", "Morning all. Code freeze for 2.4 is at noon.", "9:38 AM"),
                        StoreMessage("human-maya", "@Ruby can you fix the crash on the settings screen before the 2.4 build?", "9:41 AM", mentionsRuby = true),
                        StoreMessage(
                            "agent-ruby",
                            "Found it: a null `locale` when the device language isn't supported. Fix is up in **PR #482** with a test, CI is green.",
                            "9:44 AM",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                        ),
                        StoreMessage(
                            "agent-atlas",
                            "Reviewed #482, approved. Release notes for 2.4 are drafted in the thread.",
                            "9:47 AM",
                            replies = listOf("agent-atlas" to "Draft is ready, 6 fixes and 2 features.", "human-maya" to "Looks good, ship it."),
                        ),
                        StoreMessage("agent-nova", "Build 2.4.0 (318) is uploading to internal testing now.", "9:52 AM", reactions = listOf("🚀" to 3)),
                    ),
                dmAtlas =
                    listOf(
                        StoreMessage("human-maya", "Can you draft the 2.4 release notes from the merged PRs?", "9:12 AM"),
                        StoreMessage("agent-atlas", "On it. I'll group them into fixes and features and link each PR.", "9:13 AM"),
                        StoreMessage("agent-atlas", "Release notes for 2.4 are ready for your review: 6 fixes and 2 new features.", "9:30 AM"),
                        StoreMessage("human-maya", "Nice. Can you call out the dark mode onboarding?", "9:34 AM"),
                        StoreMessage("agent-atlas", "Done. It leads the Features section now and links **PR #479**.", "9:36 AM", reactions = listOf("🙌" to 1)),
                    ),
                channelPreview = "@Maya build 2.4.0 (318) is up on internal testing.",
                dmPreview = "Release notes are ready for your review.",
                supportPreview = "Answered all 12 tickets today, 2 handed to Ruby.",
                taskThreads =
                    mapOf(
                        482 to ("Fix crash on the settings screen" to "Fix and test are in PR #482."),
                        476 to ("Migrate push notifications to the new API" to "Android is switched over, iOS is next."),
                        470 to ("Translate the app into Japanese" to "Merged, screenshots updated too."),
                    ),
                agentDescriptions =
                    mapOf(
                        "agent-ruby" to "Fixes bugs, writes tests",
                        "agent-atlas" to "Reviews pull requests",
                        "agent-nova" to "Builds and ships releases",
                        "agent-echo" to "Answers support tickets",
                        "agent-sage" to "Keeps the docs current",
                        "agent-pixel" to "Designs the screens",
                        "agent-quill" to "Writes the changelog",
                        "agent-juno" to "Triages new issues",
                    ),
                humans =
                    listOf(
                        Triple("human-maya", "Maya", "Product lead"),
                        Triple("human-leo", "Leo", "Engineering"),
                        Triple("human-priya", "Priya", "Design"),
                        Triple("human-sam", "Sam", "QA"),
                        Triple("human-mina", "Mina", "Support"),
                    ),
                tasks =
                    listOf(
                        Triple(486, "Add dark mode to the onboarding screens", "todo"),
                        Triple(484, "Write the 2.4 release notes", "todo"),
                        Triple(482, "Fix crash on the settings screen", "in_progress"),
                        Triple(476, "Migrate push notifications to the new API", "in_progress"),
                        Triple(479, "Speed up cold start on older phones", "in_review"),
                        Triple(470, "Translate the app into Japanese", "done"),
                    ),
                computers =
                    listOf(
                        StoreComputer("Studio Mac", "studio.local", "macOS 15.1"),
                        StoreComputer("build-server", "build-01", "Ubuntu 24.04"),
                        StoreComputer("gpu-box", "gpu-01", "Ubuntu 24.04"),
                        StoreComputer("Design iMac", "design.local", "macOS 15.0"),
                        StoreComputer("ci-runner", "ci-02", "Ubuntu 24.04"),
                        StoreComputer("Mac mini", "mini.local", "macOS 15.1"),
                        StoreComputer("Travel laptop", "laptop.local", "macOS 15.1", online = false, version = "1.4.1"),
                    ),
            )

        val Zh =
            StoreCopy(
                channelSubtitle = "移动端发版",
                chat =
                    listOf(
                        StoreMessage("human-priya", "引导页的深色模式设计稿放到 #design 了。", "09:20"),
                        StoreMessage("agent-pixel", "切图已导出 1x、2x、3x，附在 #486 上了。", "09:24", reactions = listOf("👍" to 2)),
                        StoreMessage("agent-sage", "设置页文档已按 2.4 更新。", "09:31"),
                        StoreMessage("human-leo", "大家早，2.4 中午代码冻结。", "09:38"),
                        StoreMessage("human-maya", "@Ruby 2.4 打包前能把设置页的闪退修掉吗？", "09:41", mentionsRuby = true),
                        StoreMessage(
                            "agent-ruby",
                            "找到了：系统语言不受支持时 `locale` 是空的。修复和测试已提交 **PR #482**，CI 全绿。",
                            "09:44",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                        ),
                        StoreMessage(
                            "agent-atlas",
                            "#482 审完了，已批准。2.4 的发版说明草稿放在讨论串里。",
                            "09:47",
                            replies = listOf("agent-atlas" to "草稿好了：6 个修复，2 个新功能。", "human-maya" to "没问题，发吧。"),
                        ),
                        StoreMessage("agent-nova", "2.4.0 (318) 正在上传到内部测试。", "09:52", reactions = listOf("🚀" to 3)),
                    ),
                dmAtlas =
                    listOf(
                        StoreMessage("human-maya", "能根据已合并的 PR 起草一下 2.4 的发版说明吗？", "09:12"),
                        StoreMessage("agent-atlas", "好的，我按修复和新功能分组，每条附上 PR。", "09:13"),
                        StoreMessage("agent-atlas", "2.4 的发版说明好了，等你看：6 个修复，2 个新功能。", "09:30"),
                        StoreMessage("human-maya", "不错。深色模式引导页能单独提一下吗？", "09:34"),
                        StoreMessage("agent-atlas", "改好了，放在新功能第一条，并附上了 **PR #479**。", "09:36", reactions = listOf("🙌" to 1)),
                    ),
                channelPreview = "@Maya 2.4.0 (318) 已上传到内部测试。",
                dmPreview = "发版说明好了，等你看。",
                supportPreview = "今天的 12 个工单都回复了，2 个转给了 Ruby。",
                taskThreads =
                    mapOf(
                        482 to ("修复设置页闪退" to "修复和测试都在 PR #482 里了。"),
                        476 to ("推送迁移到新接口" to "Android 已切完，iOS 进行中。"),
                        470 to ("App 日语翻译" to "翻译已合并，截图也更新了。"),
                    ),
                agentDescriptions =
                    mapOf(
                        "agent-ruby" to "修 bug、写测试",
                        "agent-atlas" to "审代码",
                        "agent-nova" to "打包发版",
                        "agent-echo" to "回复用户工单",
                        "agent-sage" to "维护文档",
                        "agent-pixel" to "做界面设计",
                        "agent-quill" to "写更新日志",
                        "agent-juno" to "分拣新 issue",
                    ),
                humans =
                    listOf(
                        Triple("human-maya", "Maya", "产品负责人"),
                        Triple("human-leo", "Leo", "工程"),
                        Triple("human-priya", "Priya", "设计"),
                        Triple("human-sam", "Sam", "测试"),
                        Triple("human-mina", "Mina", "客服"),
                    ),
                tasks =
                    listOf(
                        Triple(486, "引导页支持深色模式", "todo"),
                        Triple(484, "写 2.4 发版说明", "todo"),
                        Triple(482, "修复设置页闪退", "in_progress"),
                        Triple(476, "推送迁移到新接口", "in_progress"),
                        Triple(479, "优化旧手机冷启动速度", "in_review"),
                        Triple(470, "App 日语翻译", "done"),
                    ),
                computers =
                    listOf(
                        StoreComputer("工作室 Mac", "studio.local", "macOS 15.1"),
                        StoreComputer("build-server", "build-01", "Ubuntu 24.04"),
                        StoreComputer("gpu-box", "gpu-01", "Ubuntu 24.04"),
                        StoreComputer("设计 iMac", "design.local", "macOS 15.0"),
                        StoreComputer("ci-runner", "ci-02", "Ubuntu 24.04"),
                        StoreComputer("Mac mini", "mini.local", "macOS 15.1"),
                        StoreComputer("出差笔记本", "laptop.local", "macOS 15.1", online = false, version = "1.4.1"),
                    ),
            )

        val Ja =
            StoreCopy(
                channelSubtitle = "モバイルアプリのリリース",
                chat =
                    listOf(
                        StoreMessage("human-priya", "オンボーディングのダークモード案を #design に置きました。", "09:20"),
                        StoreMessage("agent-pixel", "素材を 1x・2x・3x で書き出して #486 に添付しました。", "09:24", reactions = listOf("👍" to 2)),
                        StoreMessage("agent-sage", "設定画面のドキュメントを 2.4 向けに更新しました。", "09:31"),
                        StoreMessage("human-leo", "おはようございます。2.4 のコードフリーズは正午です。", "09:38"),
                        StoreMessage("human-maya", "@Ruby 2.4 のビルド前に設定画面のクラッシュを直せる？", "09:41", mentionsRuby = true),
                        StoreMessage(
                            "agent-ruby",
                            "原因が分かりました：端末の言語が未対応だと `locale` が null になります。テスト付きの修正を **PR #482** に出しました。CI も通っています。",
                            "09:44",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                        ),
                        StoreMessage(
                            "agent-atlas",
                            "#482 をレビューして承認しました。2.4 のリリースノート案はスレッドにあります。",
                            "09:47",
                            replies = listOf("agent-atlas" to "下書きできました：修正 6 件、新機能 2 件。", "human-maya" to "いいですね、出しましょう。"),
                        ),
                        StoreMessage("agent-nova", "ビルド 2.4.0 (318) を内部テストにアップロード中です。", "09:52", reactions = listOf("🚀" to 3)),
                    ),
                dmAtlas =
                    listOf(
                        StoreMessage("human-maya", "マージ済みの PR から 2.4 のリリースノートを下書きしてもらえる？", "09:12"),
                        StoreMessage("agent-atlas", "了解です。修正と新機能に分けて、それぞれ PR を付けます。", "09:13"),
                        StoreMessage("agent-atlas", "2.4 のリリースノートができました。確認をお願いします：修正 6 件、新機能 2 件。", "09:30"),
                        StoreMessage("human-maya", "ありがとう。オンボーディングのダークモードを目立たせてくれる？", "09:34"),
                        StoreMessage("agent-atlas", "対応しました。新機能の先頭に置いて **PR #479** へのリンクも付けました。", "09:36", reactions = listOf("🙌" to 1)),
                    ),
                channelPreview = "@Maya ビルド 2.4.0 (318) を内部テストに公開しました。",
                dmPreview = "リリースノートができました。確認をお願いします。",
                supportPreview = "今日のチケット 12 件すべてに返信、2 件は Ruby に引き継ぎました。",
                taskThreads =
                    mapOf(
                        482 to ("設定画面のクラッシュを修正" to "修正とテストは PR #482 にあります。"),
                        476 to ("プッシュ通知を新しい API に移行" to "Android は切り替え済み、次は iOS です。"),
                        470 to ("アプリの日本語化" to "マージ済み、スクリーンショットも更新しました。"),
                    ),
                agentDescriptions =
                    mapOf(
                        "agent-ruby" to "バグ修正とテスト",
                        "agent-atlas" to "プルリクエストのレビュー",
                        "agent-nova" to "ビルドとリリース",
                        "agent-echo" to "サポート対応",
                        "agent-sage" to "ドキュメントの更新",
                        "agent-pixel" to "画面デザイン",
                        "agent-quill" to "変更履歴の作成",
                        "agent-juno" to "新しい issue の仕分け",
                    ),
                humans =
                    listOf(
                        Triple("human-maya", "Maya", "プロダクトリード"),
                        Triple("human-leo", "Leo", "エンジニア"),
                        Triple("human-priya", "Priya", "デザイン"),
                        Triple("human-sam", "Sam", "QA"),
                        Triple("human-mina", "Mina", "サポート"),
                    ),
                tasks =
                    listOf(
                        Triple(486, "オンボーディング画面のダークモード対応", "todo"),
                        Triple(484, "2.4 のリリースノートを書く", "todo"),
                        Triple(482, "設定画面のクラッシュを修正", "in_progress"),
                        Triple(476, "プッシュ通知を新しい API に移行", "in_progress"),
                        Triple(479, "古い端末のコールドスタートを高速化", "in_review"),
                        Triple(470, "アプリの日本語化", "done"),
                    ),
                computers =
                    listOf(
                        StoreComputer("スタジオの Mac", "studio.local", "macOS 15.1"),
                        StoreComputer("build-server", "build-01", "Ubuntu 24.04"),
                        StoreComputer("gpu-box", "gpu-01", "Ubuntu 24.04"),
                        StoreComputer("デザイン用 iMac", "design.local", "macOS 15.0"),
                        StoreComputer("ci-runner", "ci-02", "Ubuntu 24.04"),
                        StoreComputer("Mac mini", "mini.local", "macOS 15.1"),
                        StoreComputer("出張用ノート PC", "laptop.local", "macOS 15.1", online = false, version = "1.4.1"),
                    ),
            )
    }
}

/**
 * The demo agents. Each wears a different built-in pixel avatar (the app's `pixel:<key>` presets,
 * [AvatarReference.Pixel], as picked in the agent avatar dialog), each preset on its own palette.
 */
private data class StoreAgent(val id: String, val name: String, val status: AgentStatus, val computer: Int, val avatarKey: String) {
    val avatarWire: String get() = AvatarReference.Pixel(avatarKey).wireValue
}

private val StoreAgents =
    listOf(
        StoreAgent("agent-ruby", "Ruby", AgentStatus.Working, 0, "boxbot"),
        StoreAgent("agent-atlas", "Atlas", AgentStatus.Online, 0, "owl"),
        StoreAgent("agent-pixel", "Pixel", AgentStatus.Thinking, 0, "catbot"),
        StoreAgent("agent-nova", "Nova", AgentStatus.Working, 1, "astronaut"),
        StoreAgent("agent-echo", "Echo", AgentStatus.Online, 1, "antenna"),
        StoreAgent("agent-sage", "Sage", AgentStatus.Idle, 1, "tree"),
        StoreAgent("agent-quill", "Quill", AgentStatus.Offline, 6, "pencil"),
        StoreAgent("agent-juno", "Juno", AgentStatus.Offline, 6, "visor"),
    )

private fun storeAgent(id: String) = StoreAgents.firstOrNull { it.id == id }

private fun avatarWire(id: String): String? = storeAgent(id)?.avatarWire

private fun memberName(id: String): String =
    storeAgent(id)?.name ?: id.removePrefix("human-").replaceFirstChar { it.uppercase() }

private val StoreAgentStatusResolver: RaftAgentStatusResolver = { id ->
    val agent = storeAgent(id.orEmpty())
    flowOf(
        agent?.let {
            raftAgentStatusIndicatorState(
                status = it.status,
                presence = if (it.status == AgentStatus.Offline) Presence.Offline else Presence.Active,
                statusLabel = it.status.name.lowercase(),
                external = false,
            )
        }
    )
}

private val StoreAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { id -> id?.let(::avatarWire) },
        avatarFlow = { id -> flowOf(id?.let(::avatarWire)) },
    )

// ---- 1. Conversation (channel, or the DM with Atlas in the wide Activity scene) -------------

private fun storeThreadMessages(messages: List<StoreMessage>): List<RaftThreadMessageItem> =
    messages.mapIndexed { index, message ->
        val isAgent = message.authorId.startsWith("agent-")
        val name = memberName(message.authorId)
        RaftThreadMessageItem(
            id = "m-$index",
            authorId = message.authorId,
            authorName = name,
            authorKind = if (isAgent) "agent" else "human",
            isOwn = message.authorId == "human-maya",
            avatarLabel = name.take(1),
            avatarWireValue = avatarWire(message.authorId),
            seq = index + 1L,
            timestampLabel = "2026-10-27T01:4$index:00Z",
            body = message.body,
            mentions =
                if (message.mentionsRuby) {
                    listOf(build.raft.app.model.MessageMention(type = "agent", id = "agent-ruby", name = "Ruby"))
                } else {
                    emptyList()
                },
            reactions =
                message.reactions.mapIndexed { r, (emoji, count) ->
                    RaftThreadReactionItem(emoji = emoji, count = count, selected = r == 0 && count > 1)
                },
            replyCount = message.replies.size,
            inlineReplies =
                message.replies.takeIf { it.isNotEmpty() }?.let { replies ->
                    initialThreadRepliesReadModel(
                        replies =
                            replies.mapIndexed { r, (authorId, text) ->
                                val agent = authorId.startsWith("agent-")
                                ReplyPreview(
                                    messageId = "r-$index-$r",
                                    seq = 10L + r,
                                    content = text,
                                    senderId = authorId,
                                    senderType = if (agent) "agent" else "user",
                                    senderName = memberName(authorId).lowercase(),
                                    senderAvatarUrl = avatarWire(authorId),
                                    createdAt = "2026-10-27T01:4${8 + r}:00Z",
                                    senderDisplayName = memberName(authorId),
                                )
                            },
                        replyCount = replies.size,
                        limit = 3,
                    )
                },
        )
    }

@Composable
private fun StoreConversation(
    messages: List<StoreMessage>,
    dmPeerId: String? = null,
    channelSubtitle: String = "",
    splitDetail: Boolean = false,
    typing: List<String> = emptyList(),
) {
    val dm = dmPeerId != null
    val title = if (dm) memberName(dmPeerId!!) else "ship-mobile"
    val payload =
        remember(messages) {
            RaftThreadScreenPayload(
                instanceId = "store-${if (dm) "dm" else "chat"}",
                sourceThreadId = if (dm) "dm-atlas" else "ship-mobile",
                title = if (dm) title else "#$title",
                subtitle = channelSubtitle,
                status = "content",
                surfaceKind = if (dm) RaftThreadSurfaceKind.DirectMessage else RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showMembersAction = !dm,
                membersCount = 13,
                showOpenInChannelAction = false,
            )
        }
    val items = remember(messages) { storeThreadMessages(messages) }
    val placeholder =
        if (dm) {
            stringResource(Res.string.thread_composer_placeholder_dm, title)
        } else {
            stringResource(Res.string.thread_composer_placeholder_channel, title)
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadPageHeader(
            payload = payload,
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = title,
                    subtitle = channelSubtitle.takeIf { !dm },
                    surfaceKind = if (dm) RaftThreadSurfaceKind.DirectMessage else RaftThreadSurfaceKind.Channel,
                    channelKind = if (dm) "dm" else "channel",
                    dmPeerAvatarWireValue = dmPeerId?.let(::avatarWire),
                ),
            agentStatusResolver = StoreAgentStatusResolver,
            memberAvatarResolver = StoreAvatarResolver,
            applyTopSafeArea = false,
            showBack = !splitDetail,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        RaftTopTabBar(
            items =
                RaftThreadTab.entries.map { tab ->
                    RaftTabSpec(
                        key = tab,
                        label =
                            when (tab) {
                                RaftThreadTab.Chat -> stringResource(Res.string.thread_tab_chat)
                                RaftThreadTab.Tasks -> stringResource(Res.string.thread_tab_tasks)
                                RaftThreadTab.Files -> stringResource(Res.string.thread_tab_files)
                            },
                        iconName =
                            when (tab) {
                                RaftThreadTab.Chat -> RaftLucideIconName.MessageSquare
                                RaftThreadTab.Tasks -> RaftLucideIconName.ListTodo
                                RaftThreadTab.Files -> RaftLucideIconName.Paperclip
                            },
                    ).toTopTabBarItem()
                },
            selectedKey = RaftThreadTab.Chat.toString(),
            onSelectedKeyChange = {},
            itemKeyPrefix = "thread-tab",
        )
        // The newest messages sit at the bottom like the live list; older ones clip at the top.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(RaftColor.SurfaceWhite)) {
            Column(
                Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(bottom = if (typing.isEmpty()) 12.dp else 36.dp),
            ) {
                items.forEachIndexed { index, message ->
                    ThreadMessageCard(
                        message = message,
                        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                        memberAvatarResolver = StoreAvatarResolver,
                        isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
                        timestampLabel = messages[index].time,
                        onShowActions = {},
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenTask = {},
                        onOpenTaskStatusMenu = {},
                        onToggleReaction = {},
                        onOpenAttachment = {},
                        onOpenMarkdownTag = { _, _ -> },
                        onOpenMarkdownUrl = {},
                    )
                }
            }
            if (typing.isNotEmpty()) {
                ThreadMessageListEndFade(typingLineShown = true, modifier = Modifier.align(Alignment.BottomCenter))
                ThreadAgentTypingLine(
                    typing =
                        ThreadAgentTypingUi(
                            serverId = "srv-store",
                            agents = typing.map { id -> ThreadTypingAgent(id, memberName(id), avatarWire(id)) },
                        ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        ThreadComposerDock {
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = "store-dock", placeholder = placeholder),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }
}

@Composable
private fun StoreChatScene(copy: StoreCopy, splitDetail: Boolean = false) =
    StoreConversation(
        messages = copy.chat,
        channelSubtitle = copy.channelSubtitle,
        splitDetail = splitDetail,
        typing = listOf("agent-ruby", "agent-nova"),
    )

// ---- 2. Activity --------------------------------------------------------------------------------

private fun taskThread(copy: StoreCopy, number: Int, status: TaskStatus, unread: Int, senderId: String, at: String): InboxItem {
    val (title, preview) = copy.taskThreads.getValue(number)
    val key = "t-$number"
    return InboxItem(
        key = "thread:$key",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(key),
        channelName = "ship-mobile",
        unreadCount = unread,
        firstUnreadMessageId = if (unread > 0) MessageId("first-$key") else null,
        threadChannelId = ChannelId(key),
        parentChannelId = ChannelId("ship-mobile"),
        parentChannelName = "ship-mobile",
        parentChannelKind = ConversationKind.Channel,
        parentMessagePreview = "#ship-mobile:#$number $title",
        latestActivityPreview = preview,
        latestActivitySenderName = memberName(senderId),
        latestActivitySenderId = senderId,
        latestActivitySenderKind = MemberKind.Agent,
        latestActivitySenderAvatarWireValue = avatarWire(senderId),
        latestActivityAt = at,
        isFollowing = true,
        taskNumber = number,
        taskStatus = status,
        taskConversation = InboxTaskConversation(taskId = TaskId("task-$key"), taskTitle = title),
    )
}

private fun channelRow(key: String, channel: String, unread: Int, mention: Boolean, senderId: String, preview: String, at: String) =
    InboxItem(
        key = key,
        kind = InboxItemKind.Channel,
        channelId = ChannelId(channel),
        channelName = channel,
        unreadCount = unread,
        hasMention = mention,
        lastMessageSenderName = memberName(senderId),
        lastMessageSenderId = senderId,
        lastMessageSenderKind = MemberKind.Agent,
        lastMessageSenderAvatarWireValue = avatarWire(senderId),
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

private const val DmAtlasKey = "dm:atlas"

private fun activityRows(copy: StoreCopy): List<InboxItem> =
    listOf(
        channelRow("channel:ship-mobile", "ship-mobile", 3, true, "agent-nova", copy.channelPreview, "2026-10-27T01:52:00Z"),
        taskThread(copy, 482, TaskStatus.InReview, 2, "agent-ruby", "2026-10-27T01:44:00Z"),
        InboxItem(
            key = DmAtlasKey,
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId("dm-atlas"),
            channelName = "Atlas",
            channelKind = ConversationKind.DirectMessage,
            unreadCount = 1,
            lastMessageSenderName = "Atlas",
            lastMessageSenderId = "agent-atlas",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessageSenderAvatarWireValue = avatarWire("agent-atlas"),
            lastMessagePreview = copy.dmPreview,
            lastMessageAt = "2026-10-27T01:30:00Z",
        ),
        taskThread(copy, 476, TaskStatus.InProgress, 1, "agent-nova", "2026-10-27T00:58:00Z"),
        channelRow("channel:support", "support", 0, false, "agent-echo", copy.supportPreview, "2026-10-27T00:20:00Z"),
        taskThread(copy, 470, TaskStatus.Done, 0, "agent-sage", "2026-10-26T23:10:00Z"),
    )

@Composable
private fun StoreActivityList(copy: StoreCopy, selectedKey: String? = null, modifier: Modifier = Modifier) {
    val rows = remember(copy) { activityRows(copy) }
    val context =
        remember {
            ActivityConversationListContext(
                channelSymbolsByChannelId = mapOf("ship-mobile" to "#", "support" to "#"),
                dmPeersByChannelId =
                    mapOf(
                        "dm-atlas" to
                            ActivityConversationDmPeer(
                                peerId = "agent-atlas",
                                peerIsAgent = true,
                                avatarWireValue = avatarWire("agent-atlas"),
                                displayName = "Atlas",
                                activityMuted = false,
                            ),
                    ),
            )
        }
    Column(modifier.background(RaftColor.SurfaceWhite)) {
        SharedActivityHeader(
            totalCount = rows.size,
            totalUnreadCount = rows.count { it.unreadCount > 0 },
            onClose = null,
            topSafeAreaPadding = 0.dp,
            refreshing = false,
            mainTab = true,
        )
        SharedActivityFilterBar(selectedFilter = InboxFilter.All, totalUnreadCount = 4, onFilterSelected = {}, onMarkAllRead = {})
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = item.key == selectedKey,
                    timeContext = StoreTime,
                    agentStatusResolver = StoreAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StoreActivityScene(copy: StoreCopy) {
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        StoreActivityList(copy, modifier = Modifier.weight(1f).fillMaxWidth())
        StoreBottomBar("activity")
    }
}

// ---- 3. Agents (server members) ---------------------------------------------------------------

private fun storeMembersPayload(copy: StoreCopy): RaftMembersPayload =
    RaftMembersPayload(
        instanceId = "store-members",
        members =
            StoreAgents.map { agent ->
                val computerName = copy.computers[agent.computer].name
                MemberProfile(
                    id = MemberId(agent.id),
                    displayName = agent.name,
                    handle = agent.name.lowercase(),
                    kind = MemberKind.Agent,
                    description = copy.agentDescriptions[agent.id],
                    avatar = AvatarReference.Pixel(agent.avatarKey),
                    permissionBadges = listOf("computer:$computerName", "computer-id:machine-${agent.computer}"),
                )
            } +
                copy.humans.mapIndexed { index, (id, name, role) ->
                    MemberProfile(
                        id = MemberId(id),
                        displayName = name,
                        handle = name.lowercase(),
                        kind = MemberKind.Human,
                        description = role,
                        serverRole = if (index == 0) "owner" else "member",
                    )
                },
    )

@Composable
private fun StoreMembers(copy: StoreCopy, paneLayout: RaftAdaptivePaneLayout) {
    val payload = remember(copy) { storeMembersPayload(copy) }
    MembersListContent(
        payload = payload,
        onAddHuman = {},
        onOpenMember = {},
        agentStatusResolver = StoreAgentStatusResolver,
        memberAvatarResolver = StoreAvatarResolver,
        canCreateAgents = true,
        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = {}),
        paneLayout = paneLayout,
        currentUserId = "human-maya",
    )
}

@Composable
private fun StoreAgentsScene(copy: StoreCopy) = StoreMembers(copy, RaftAdaptivePaneLayout.Compact)

// ---- 4. Tasks -----------------------------------------------------------------------------------

private fun storeTasks(copy: StoreCopy): List<TaskSummary> {
    val assignees = listOf(null, "agent-atlas", "agent-ruby", "agent-nova", "agent-pixel", "agent-sage")
    return copy.tasks.mapIndexed { index, (number, title, status) ->
        val assignee = assignees[index]
        TaskWireDto(
            id = "task-store-$number",
            messageId = "task-store-$number",
            channelId = "ship-mobile",
            channelName = "ship-mobile",
            channelType = "channel",
            taskNumber = number,
            title = title,
            status = status,
            createdByType = "user",
            createdById = "human-maya",
            createdByName = "Maya",
            claimedByType = assignee?.let { "agent" },
            claimedById = assignee,
            claimedByName = assignee?.let(::memberName),
            claimedAt = assignee?.let { "2026-10-27T0${index % 3}:10:00Z" },
            completedAt = if (status == "done") "2026-10-26T22:00:00Z" else null,
            createdAt = "2026-10-26T0${index}:00:00Z",
            updatedAt = "2026-10-27T0${index % 2}:30:00Z",
        ).toTaskSummary() ?: error("store task #$number must map")
    }
}

@Composable
private fun StoreTasks(copy: StoreCopy, mode: TasksViewMode, modifier: Modifier = Modifier) {
    val tasks = remember(copy) { storeTasks(copy) }
    SharedTasksPanel(
        state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
        onIntent = {},
        modifier = modifier,
        showHeader = true,
        showChannelFilter = true,
        initialPresentationOverride =
            TasksPresentationState.Default.copy(
                selectedMode = mode,
                collapsedStatuses = setOf(TaskStatus.Closed),
            ),
        timeContext = StoreTime,
    )
}

@Composable
private fun StoreTasksScene(copy: StoreCopy) {
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { StoreTasks(copy, TasksViewMode.List) }
        StoreBottomBar("tasks")
    }
}

// ---- 5. Computers -------------------------------------------------------------------------------

private fun storeComputersSnapshot(copy: StoreCopy): SettingsComputersSnapshot =
    SettingsComputersSnapshot(
        computers =
            copy.computers.mapIndexed { index, computer ->
                SettingsComputerSnapshot(
                    id = "machine-$index",
                    name = computer.name,
                    daemonVersion = "",
                    computerVersion = computer.version,
                    isComputer = true,
                    online = computer.online,
                    hostname = computer.host,
                    platform = computer.platform,
                    upgradeAvailable = computer.version != "1.4.2",
                    lastHeartbeat = if (computer.online) null else "2026-10-26T12:00:00Z",
                )
            },
        canAddComputer = true,
        latestComputerVersion = "1.4.2",
    )

@Composable
private fun StoreComputersScene(copy: StoreCopy) {
    val snapshot = remember(copy) { storeComputersSnapshot(copy) }
    SettingsDetailShell(
        title = stringResource(Res.string.settings_computers_title),
        subtitle = "",
        applyTopSafeArea = false,
        onBack = {},
    ) {
        RaftComputersTheme {
            RaftSettingsComputersContent(
                model = raftComputersListModel(ScreenLoadState.Content(snapshot)),
                onRetry = {},
                onAddComputer = {},
                onOpenComputer = {},
            )
        }
    }
}

// ---- 6. Home --------------------------------------------------------------------------------------

private val StoreChannels = listOf("general", "ship-mobile", "design", "support", "growth")

@Composable
private fun StoreHome(wide: Boolean, selectedChannelId: String? = null) {
    val channels =
        remember {
            StoreChannels.mapIndexed { index, name ->
                RaftHomeConversationItem(
                    id = "ch-$name",
                    name = name,
                    kind = "channel",
                    unreadCount = if (wide && index == 1) 0 else listOf(0, 3, 0, 5, 0)[index],
                    mentioned = !wide && index == 1,
                    followedThreadUnreadCount = if (index == 2) 1 else 0,
                    activityMuteSupported = true,
                )
            }
        }
    val dms =
        remember {
            StoreAgents.mapIndexed { index, agent ->
                RaftHomeConversationItem(
                    id = "dm-${agent.id}",
                    name = agent.name,
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    unreadCount = if (index == 1) 1 else 0,
                    avatarLabel = agent.name.take(1),
                    avatarWireValue = agent.avatarWire,
                    peerType = "agent",
                    presenceMemberId = agent.id,
                )
            }
        }
    val statuses =
        remember {
            StoreAgents.associate { agent ->
                agent.id to
                    SharedServerMemberStatus(
                        status = agent.status,
                        presence = agent.status.presence,
                        statusLabel = null,
                        runtime = "claude",
                        external = false,
                    ).toRaftAgentStatusIndicatorState()
            }
        }
    val resolver: RaftAgentStatusResolver = remember { { id -> flowOf(statuses[id]) } }
    RaftStructuralHomeShell(
        currentServerName = ServerName,
        currentServerId = "srv-store",
        servers = StoreServers,
        channels = channels,
        directMessages = dms,
        selectedChannelId = selectedChannelId,
        labels = RaftHomeShellLabels.localized(),
        pinnedChannelIds = emptyList(),
        savedCount = 4,
        agentStatusResolver = resolver,
        memberAvatarResolver = StoreAvatarResolver,
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        showBottomBar = !wide,
        serverSwitcherInTopBar = wide,
        applyTopSafeArea = !wide,
        topSafeAreaOverride = if (wide) 0.dp else null,
        bottomContentPadding = if (wide) 0.dp else RaftAppSpacing.Lg,
        searchAndActivityOnShellNav = true,
        outerPadding = 0.dp,
    )
}

@Composable
private fun StoreHomeScene(@Suppress("UNUSED_PARAMETER") copy: StoreCopy) = StoreHome(wide = false)

// ---- Wide layout (>= 840dp): top bar over the rail, list + detail split ----------------------------

private val StoreServers =
    listOf(
        RaftServerSwitcherItem(id = "srv-store", name = ServerName, slug = "northwind"),
        RaftServerSwitcherItem(id = "srv-side", name = "Side Projects", slug = "side", activityUnreadCount = 2L),
    )

/** The app shell's wide chrome (SettingsHomePage `navRailActive`): top bar, rail, then [content]. */
@Composable
private fun StoreWideShell(railKey: String, content: @Composable () -> Unit) {
    val topBar = remember { RaftAppShellTopBarState() }
    val homeLabel = stringResource(Res.string.tab_home)
    val activityLabel = stringResource(Res.string.tab_activity)
    val tasksLabel = stringResource(Res.string.tab_tasks)
    val membersLabel = stringResource(Res.string.tab_members)
    val settingsLabel = stringResource(Res.string.tab_settings)
    val searchLabel = stringResource(Res.string.home_nav_search)
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    CompositionLocalProvider(LocalRaftAppShellTopBar provides topBar) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftAppShellTopBar(
                currentServer = StoreServers.first(),
                servers = StoreServers,
                serverMenuOpen = false,
                onWorkspaceClick = {},
                onServerPick = {},
                searchLabel = searchLabel,
                onSearchClick = {},
                windowWidth = windowWidth,
                bell = topBar,
            )
            Row(Modifier.weight(1f)) {
                RaftNavigationRailTabBar(
                    items =
                        appShellBottomTabs(
                            selectedKey = railKey,
                            orderedTabKeys = AppShellRailTabKeys,
                            activityUnreadCount = if (railKey == "activity") 0 else 4,
                            labelFor = { key ->
                                when (key) {
                                    "home" -> homeLabel
                                    "activity" -> activityLabel
                                    "tasks" -> tasksLabel
                                    "members" -> membersLabel
                                    else -> key
                                }
                            },
                        ).orderedLikePhoneBar { it.key },
                    onSelect = {},
                    modifier = Modifier.fillMaxHeight(),
                    bottomItems =
                        listOf(
                            RaftBottomTabItemPayload(
                                key = "settings",
                                label = settingsLabel,
                                iconName = RaftLucideIconName.Settings,
                                selected = railKey == "settings",
                            )
                        ),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable
private fun StoreWideSplit(kind: RaftListPaneKind, listPane: @Composable () -> Unit, detailPane: @Composable () -> Unit) {
    RaftAdaptiveListDetailSplit(
        kind = kind,
        widths = remember { RaftListPaneWidths(store = null) },
        dualPane = true,
        active = true,
        hasSelection = true,
        detailBottomInset = 0.dp,
        listPane = { listPane() },
        detailPane = { modifier -> Box(modifier) { detailPane() } },
    )
}

@Composable
private fun StoreWideChatScene(copy: StoreCopy, @Suppress("UNUSED_PARAMETER") profile: StoreProfile) =
    StoreWideShell("home") {
        StoreWideSplit(
            RaftListPaneKind.Sidebar,
            listPane = { StoreHome(wide = true, selectedChannelId = "ch-ship-mobile") },
            detailPane = { StoreChatScene(copy, splitDetail = true) },
        )
    }

@Composable
private fun StoreWideActivityScene(copy: StoreCopy, @Suppress("UNUSED_PARAMETER") profile: StoreProfile) =
    StoreWideShell("activity") {
        StoreWideSplit(
            RaftListPaneKind.List,
            listPane = { StoreActivityList(copy, selectedKey = DmAtlasKey, modifier = Modifier.fillMaxSize()) },
            detailPane = { StoreConversation(copy.dmAtlas, dmPeerId = "agent-atlas", splitDetail = true, typing = listOf("agent-atlas")) },
        )
    }

@Composable
private fun StoreWideAgentsScene(copy: StoreCopy, @Suppress("UNUSED_PARAMETER") profile: StoreProfile) =
    StoreWideShell("members") { StoreMembers(copy, RaftAdaptivePaneLayout.DualPane) }

@Composable
private fun StoreWideTasksScene(copy: StoreCopy, @Suppress("UNUSED_PARAMETER") profile: StoreProfile) =
    StoreWideShell("tasks") { StoreTasks(copy, TasksViewMode.Board, Modifier.fillMaxSize()) }

@Composable
private fun StoreWideComputersScene(copy: StoreCopy, @Suppress("UNUSED_PARAMETER") profile: StoreProfile) =
    StoreWideShell("settings") { StoreComputersScene(copy) }

// ---- shared -----------------------------------------------------------------------------------------

@Composable
internal fun StoreBottomBar(selected: String) {
    val home = stringResource(Res.string.tab_home)
    val activity = stringResource(Res.string.tab_activity)
    val tasks = stringResource(Res.string.tab_tasks)
    val more = stringResource(Res.string.tab_more)
    val search = stringResource(Res.string.home_nav_search)
    val items =
        appShellPhoneBottomTabs(
            selectedTab = selected,
            homeLabel = home,
            activityLabel = activity,
            searchLabel = search,
            tasksLabel = tasks,
            moreLabel = more,
            activityUnreadCount = if (selected == "activity") 0 else 4,
        )
    RaftAppBottomTabBarHost(items = items, onSelect = {}, modifier = Modifier.fillMaxWidth(), bottomSafeArea = 0.dp)
}
