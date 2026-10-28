package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.data.ScreenLoadState
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
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.resources.Res
import build.raft.app.resources.*
import build.raft.designsystem.tokens.RaftThemeId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 8-shot Google Play phone set (Ginny's copy, 2026-10-28): production Compose screens with
 * fictional demo content (team "Northwind Labs"; people Maya Chen and Leo Park; agents Forge,
 * Scout and the fixture agents), the app in the server theme Weather with its pixel decor off,
 * in English, Simplified Chinese, Traditional Chinese and Japanese.
 *
 * The app has no Traditional Chinese UI resources, so `zh-Hant` renders the Simplified Chinese UI
 * with Traditional Chinese demo content.
 *
 * Only runs with `RAFT_STORE_SCREENSHOTS=<output dir>`; writes `<dir>/phone/<lang>/NN-scene.png`
 * at the phone profile (390x844dp, xxhdpi). `RAFT_STORE_SCENES=01-chat,07-swipe` filters scenes.
 * `scripts/store-screenshots/frames.py --set play8` frames them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class StorePlay8ScreenshotsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val outDir: String? = System.getenv("RAFT_STORE_SCREENSHOTS")
    private val sceneFilter =
        System.getenv("RAFT_STORE_SCENES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "store-play8"
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

    @Test fun en() = captureAll(P8Lang.En)

    @Test fun zhHans() = captureAll(P8Lang.ZhHans)

    @Test fun zhHant() = captureAll(P8Lang.ZhHant)

    @Test fun ja() = captureAll(P8Lang.Ja)

    private fun captureAll(lang: P8Lang) {
        assumeTrue("set RAFT_STORE_SCREENSHOTS to render store screenshots", !outDir.isNullOrBlank())
        val copy = lang.copy
        val token = lang.locale.applyVisualCaptureLocale()
        val previousLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(lang.javaLocale)
        try {
            RuntimeEnvironment.setQualifiers("${lang.qualifier}-w390dp-h844dp-port-xxhdpi")
            val dir = File(File(outDir!!, "phone"), lang.key)
            capture(dir, "01-chat") { P8ChatScene(copy) }
            capture(dir, "02-mention") { P8MentionScene(copy) }
            capture(dir, "03-thread") { P8ThreadScene(copy) }
            capture(dir, "04-tasks") { P8TasksScene(copy) }
            capture(dir, "05-agent") { P8AgentScene(copy, "profile") }
            capture(dir, "06-activity") { P8ActivityScene(copy, swipe = false) }
            capture(dir, "07-swipe") { P8ActivityScene(copy, swipe = true) }
            capture(dir, "08-home") { P8HomeScene() }
            // Not in the set: Forge's Workspace tab (MEMORY.md), only when asked for by name.
            if (sceneFilter?.contains("05w-workspace") == true) capture(dir, "05w-workspace") { P8AgentScene(copy, "workspace") }
        } finally {
            java.util.Locale.setDefault(previousLocale)
            restoreVisualCaptureLocale(token)
        }
    }

    private fun capture(dir: File, name: String, content: @Composable () -> Unit) {
        if (sceneFilter != null && name !in sceneFilter) return
        captureStoreScreen(activities, livePager, File(dir, "$name.png"), theme = RaftThemeId.Weather, content = content)
    }
}

internal enum class P8Lang(val key: String, val locale: AppLocale, val qualifier: String, val copy: P8Copy) {
    En("en", AppLocale.English, "en-rUS", P8Copy.En),
    ZhHans("zh-Hans", AppLocale.SimplifiedChinese, "zh-rCN", P8Copy.ZhHans),
    // No Traditional Chinese UI resources: the Simplified UI with Traditional demo content.
    ZhHant("zh-Hant", AppLocale.SimplifiedChinese, "zh-rCN", P8Copy.ZhHant),
    Ja("ja", AppLocale.Japanese, "ja-rJP", P8Copy.Ja);

    val javaLocale: java.util.Locale
        get() = java.util.Locale(qualifier.substringBefore("-r"), qualifier.substringAfter("-r"))
}

// ---- Demo cast ---------------------------------------------------------------------------------

private data class P8Agent(val id: String, val name: String, val status: AgentStatus, val avatarKey: String) {
    val avatarWire: String get() = AvatarReference.Pixel(avatarKey).wireValue
}

private val P8Agents =
    listOf(
        P8Agent("agent-forge", "Forge", AgentStatus.Working, "boxbot"),
        P8Agent("agent-scout", "Scout", AgentStatus.Thinking, "magnifier"),
        P8Agent("agent-atlas", "Atlas", AgentStatus.Online, "owl"),
        P8Agent("agent-nova", "Nova", AgentStatus.Working, "astronaut"),
        P8Agent("agent-pixel", "Pixel", AgentStatus.Online, "catbot"),
        P8Agent("agent-echo", "Echo", AgentStatus.Online, "antenna"),
        P8Agent("agent-sage", "Sage", AgentStatus.Idle, "tree"),
        P8Agent("agent-quill", "Quill", AgentStatus.Offline, "pencil"),
    )

private val P8Humans = mapOf("human-maya" to "Maya Chen", "human-leo" to "Leo Park")

private fun p8Agent(id: String) = P8Agents.firstOrNull { it.id == id }

private fun p8Avatar(id: String): String? = p8Agent(id)?.avatarWire

private fun p8Name(id: String): String = p8Agent(id)?.name ?: P8Humans[id] ?: id

private fun p8Initials(name: String): String = name.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1) }.take(2)

private val P8StatusResolver: RaftAgentStatusResolver = { id ->
    flowOf(
        p8Agent(id.orEmpty())?.let {
            raftAgentStatusIndicatorState(
                status = it.status,
                presence = if (it.status == AgentStatus.Offline) Presence.Offline else Presence.Active,
                statusLabel = it.status.name.lowercase(),
                external = false,
            )
        }
    )
}

private val P8AvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { id -> id?.let(::p8Avatar) },
        avatarFlow = { id -> flowOf(id?.let(::p8Avatar)) },
    )

// ---- Copy ----------------------------------------------------------------------------------------

internal data class P8Message(
    val authorId: String,
    val body: String,
    val time: String,
    val reactions: List<Pair<String, Int>> = emptyList(),
    /** Agent ids @mentioned in [body]. */
    val mentions: List<String> = emptyList(),
    val replies: List<Pair<String, String>> = emptyList(), // author id, reply
)

internal data class P8Task(val number: Int, val title: String, val status: String, val assigneeId: String?)

/** Activity rows: a task thread's (number, sender, preview), the rest plain previews. */
internal data class P8ActivityCopy(
    val channelPreview: String,
    val loginThreadPreview: String,
    val dmForgePreview: String,
    val coldStartPreview: String,
    val supportPreview: String,
    val checklistPreview: String,
)

/** Every visible demo string, per language. Names are fictional; nothing comes from a real server. */
internal class P8Copy(
    val channelSubtitle: String,
    val chat: List<P8Message>,
    val mentionChat: List<P8Message>,
    val composerText: String,
    val threadOrigin: P8Message,
    val threadReplies: List<P8Message>,
    val tasks: List<P8Task>,
    val agentDescriptions: Map<String, String>,
    val forgeBio: String,
    val forgeActivity: String,
    val activity: P8ActivityCopy,
) {
    fun taskTitle(number: Int): String = tasks.first { it.number == number }.title

    companion object {
        // To do, in progress, then done: #512 (Forge's login fix, the newest done) on top of its group.
        private val TaskShape = listOf(515 to "todo", 513 to "in_progress", 512 to "done", 509 to "done")
        private val TaskAssignees = mapOf(515 to null, 513 to "agent-nova", 512 to "agent-forge", 509 to "agent-scout")

        private fun tasks(titles: List<String>): List<P8Task> =
            TaskShape.mapIndexed { index, (number, status) -> P8Task(number, titles[index], status, TaskAssignees[number]) }

        val En =
            P8Copy(
                channelSubtitle = "Release train for the mobile app",
                chat =
                    listOf(
                        P8Message("human-leo", "Morning! Code freeze for 2.4 is at noon.", "9:30 AM"),
                        P8Message("agent-pixel", "The new onboarding screens are exported and attached to #508.", "9:32 AM", reactions = listOf("👍" to 2)),
                        P8Message("human-maya", "@Scout can you run the release checklist on build 319?", "9:35 AM", mentions = listOf("agent-scout")),
                        P8Message("agent-scout", "Done: 46 of 48 checks pass. Both failures are on the login screen.", "9:38 AM"),
                        P8Message(
                            "agent-forge",
                            "I'll take the login failures. Fix and a test are going up in **PR #512**.",
                            "9:40 AM",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                            replies = listOf("agent-atlas" to "Reviewed #512, approved.", "human-maya" to "Thanks, ship it."),
                        ),
                        P8Message("agent-nova", "Build 2.4.0 (320) goes to internal testing as soon as #512 merges.", "9:44 AM", reactions = listOf("🚀" to 3)),
                    ),
                mentionChat =
                    listOf(
                        P8Message("human-leo", "Morning! Code freeze for 2.4 is at noon.", "9:30 AM"),
                        P8Message("human-leo", "Heads up: a few beta testers can't sign in after updating to 2.4.", "9:50 AM"),
                        P8Message("agent-scout", "Reproduced on Android 15 and iOS 18: the session expires when the app resumes.", "9:52 AM"),
                    ),
                composerText = "@Forge can you fix the login bug?",
                threadOrigin = P8Message("human-maya", "@Forge can you fix the login bug?", "9:54 AM", mentions = listOf("agent-forge")),
                threadReplies =
                    listOf(
                        P8Message("agent-forge", "Found it: the refresh token was dropped when the app came back from the background. Fix and a test are up in **PR #512**.", "9:58 AM"),
                        P8Message("agent-scout", "Ran the login suite on Android and iOS: 48 of 48 pass, including the resume case.", "10:03 AM", reactions = listOf("🎉" to 2)),
                        P8Message("agent-atlas", "Reviewed #512 and approved.", "10:05 AM"),
                        P8Message("agent-forge", "Merged. @Nova it's ready for build 320.", "10:06 AM", mentions = listOf("agent-nova")),
                        P8Message("agent-nova", "Build 2.4.0 (320) is uploading to internal testing.", "10:09 AM", reactions = listOf("🚀" to 3)),
                    ),
                tasks =
                    tasks(
                        listOf(
                            "Add dark mode to the settings screen",
                            "Speed up cold start on older phones",
                            "Fix the login bug",
                            "Run the release checklist on build 319",
                        )
                    ),
                agentDescriptions =
                    mapOf(
                        "agent-forge" to "Fixes bugs and writes the tests",
                        "agent-scout" to "Tests every build, finds what broke",
                        "agent-atlas" to "Reviews pull requests",
                        "agent-nova" to "Builds and ships releases",
                        "agent-pixel" to "Designs the screens",
                        "agent-echo" to "Answers support tickets",
                        "agent-sage" to "Keeps the docs current",
                        "agent-quill" to "Writes the changelog",
                    ),
                forgeBio = "Fixes bugs and writes the tests. Remembers how the mobile app is built, what broke before and how the team likes its pull requests.",
                forgeActivity = "Writing a test for the login fix",
                activity =
                    P8ActivityCopy(
                        channelPreview = "@Maya build 2.4.0 (320) is on internal testing.",
                        loginThreadPreview = "All 48 login tests pass on Android and iOS.",
                        dmForgePreview = "The login fix is merged. Want me to take #515 next?",
                        coldStartPreview = "Cold start is 38% faster on older phones.",
                        supportPreview = "Answered all 12 tickets today, 2 handed to Forge.",
                        checklistPreview = "46 of 48 checks pass, both failures filed.",
                    ),
            )

        val ZhHans =
            P8Copy(
                channelSubtitle = "移动端发版",
                chat =
                    listOf(
                        P8Message("human-leo", "大家早，2.4 中午代码冻结。", "09:30"),
                        P8Message("agent-pixel", "新的引导页切图已导出，附在 #508 上了。", "09:32", reactions = listOf("👍" to 2)),
                        P8Message("human-maya", "@Scout 能帮忙在 319 版本上跑一遍发版检查吗？", "09:35", mentions = listOf("agent-scout")),
                        P8Message("agent-scout", "跑完了：48 项检查通过 46 项，两项失败都在登录页。", "09:38"),
                        P8Message(
                            "agent-forge",
                            "登录页的问题我来修，修复和测试马上提交到 **PR #512**。",
                            "09:40",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                            replies = listOf("agent-atlas" to "#512 审完了，已批准。", "human-maya" to "谢谢，发吧。"),
                        ),
                        P8Message("agent-nova", "#512 合并后，2.4.0 (320) 就发到内部测试。", "09:44", reactions = listOf("🚀" to 3)),
                    ),
                mentionChat =
                    listOf(
                        P8Message("human-leo", "大家早，2.4 中午代码冻结。", "09:30"),
                        P8Message("human-leo", "提醒一下：有几位内测用户升级到 2.4 后登录不了。", "09:50"),
                        P8Message("agent-scout", "在 Android 15 和 iOS 18 上复现了：App 从后台回来时会话就过期了。", "09:52"),
                    ),
                composerText = "@Forge 能帮忙修一下登录的 bug 吗？",
                threadOrigin = P8Message("human-maya", "@Forge 能帮忙修一下登录的 bug 吗？", "09:54", mentions = listOf("agent-forge")),
                threadReplies =
                    listOf(
                        P8Message("agent-forge", "找到了：App 从后台回来时把 refresh token 丢了。修复和测试已提交 **PR #512**。", "09:58"),
                        P8Message("agent-scout", "在 Android 和 iOS 上跑了登录测试：48 项全部通过，包括从后台恢复的场景。", "10:03", reactions = listOf("🎉" to 2)),
                        P8Message("agent-atlas", "#512 审完了，已批准。", "10:05"),
                        P8Message("agent-forge", "已合并。@Nova 可以打 320 版本了。", "10:06", mentions = listOf("agent-nova")),
                        P8Message("agent-nova", "2.4.0 (320) 正在上传到内部测试。", "10:09", reactions = listOf("🚀" to 3)),
                    ),
                tasks = tasks(listOf("设置页支持深色模式", "优化旧手机冷启动速度", "修复登录 bug", "在 319 版本上跑发版检查")),
                agentDescriptions =
                    mapOf(
                        "agent-forge" to "修 bug，写测试",
                        "agent-scout" to "测试每个版本，找出问题",
                        "agent-atlas" to "审代码",
                        "agent-nova" to "打包和发版",
                        "agent-pixel" to "设计界面",
                        "agent-echo" to "回复用户工单",
                        "agent-sage" to "维护文档",
                        "agent-quill" to "写更新日志",
                    ),
                forgeBio = "修 bug，写测试。记得移动端是怎么搭起来的、以前出过什么问题，也记得团队喜欢什么样的 PR。",
                forgeActivity = "正在给登录修复写测试",
                activity =
                    P8ActivityCopy(
                        channelPreview = "@Maya 2.4.0 (320) 已经发到内部测试。",
                        loginThreadPreview = "登录测试 48 项在 Android 和 iOS 上全部通过。",
                        dmForgePreview = "登录修复已合并。接下来要我做 #515 吗？",
                        coldStartPreview = "旧手机冷启动快了 38%。",
                        supportPreview = "今天 12 个工单都已回复，2 个转给了 Forge。",
                        checklistPreview = "48 项检查通过 46 项，两个失败已建任务。",
                    ),
            )

        val ZhHant =
            P8Copy(
                channelSubtitle = "行動端發版",
                chat =
                    listOf(
                        P8Message("human-leo", "大家早，2.4 中午程式碼凍結。", "09:30"),
                        P8Message("agent-pixel", "新的導覽頁素材已匯出，附在 #508 上了。", "09:32", reactions = listOf("👍" to 2)),
                        P8Message("human-maya", "@Scout 可以幫忙在 319 版跑一次發版檢查嗎？", "09:35", mentions = listOf("agent-scout")),
                        P8Message("agent-scout", "跑完了：48 項檢查通過 46 項，兩項失敗都在登入頁。", "09:38"),
                        P8Message(
                            "agent-forge",
                            "登入頁的問題我來修，修正和測試馬上提交到 **PR #512**。",
                            "09:40",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                            replies = listOf("agent-atlas" to "#512 審完了，已核准。", "human-maya" to "謝謝，發吧。"),
                        ),
                        P8Message("agent-nova", "#512 合併後，2.4.0 (320) 就發到內部測試。", "09:44", reactions = listOf("🚀" to 3)),
                    ),
                mentionChat =
                    listOf(
                        P8Message("human-leo", "大家早，2.4 中午程式碼凍結。", "09:30"),
                        P8Message("human-leo", "提醒一下：有幾位測試使用者更新到 2.4 後無法登入。", "09:50"),
                        P8Message("agent-scout", "在 Android 15 和 iOS 18 上重現了：App 從背景回來時工作階段就過期了。", "09:52"),
                    ),
                composerText = "@Forge 可以幫忙修一下登入的 bug 嗎？",
                threadOrigin = P8Message("human-maya", "@Forge 可以幫忙修一下登入的 bug 嗎？", "09:54", mentions = listOf("agent-forge")),
                threadReplies =
                    listOf(
                        P8Message("agent-forge", "找到了：App 從背景回來時把 refresh token 弄丟了。修正和測試已提交到 **PR #512**。", "09:58"),
                        P8Message("agent-scout", "在 Android 和 iOS 上跑了登入測試：48 項全部通過，包括從背景恢復的情境。", "10:03", reactions = listOf("🎉" to 2)),
                        P8Message("agent-atlas", "#512 審完了，已核准。", "10:05"),
                        P8Message("agent-forge", "已合併。@Nova 可以打 320 版了。", "10:06", mentions = listOf("agent-nova")),
                        P8Message("agent-nova", "2.4.0 (320) 正在上傳到內部測試。", "10:09", reactions = listOf("🚀" to 3)),
                    ),
                tasks = tasks(listOf("設定頁支援深色模式", "加快舊手機冷啟動速度", "修正登入 bug", "在 319 版跑發版檢查")),
                agentDescriptions =
                    mapOf(
                        "agent-forge" to "修 bug、寫測試",
                        "agent-scout" to "測試每個版本，找出問題",
                        "agent-atlas" to "審查程式碼",
                        "agent-nova" to "打包和發版",
                        "agent-pixel" to "設計介面",
                        "agent-echo" to "回覆客服工單",
                        "agent-sage" to "維護文件",
                        "agent-quill" to "撰寫更新日誌",
                    ),
                forgeBio = "修 bug、寫測試。記得行動 App 是怎麼建起來的、以前出過什麼問題，也記得團隊喜歡什麼樣的 PR。",
                forgeActivity = "正在幫登入修正寫測試",
                activity =
                    P8ActivityCopy(
                        channelPreview = "@Maya 2.4.0 (320) 已經發到內部測試。",
                        loginThreadPreview = "登入測試 48 項在 Android 和 iOS 上全部通過。",
                        dmForgePreview = "登入修正已合併。接下來要我做 #515 嗎？",
                        coldStartPreview = "舊手機冷啟動快了 38%。",
                        supportPreview = "今天 12 張工單都已回覆，2 張轉給了 Forge。",
                        checklistPreview = "48 項檢查通過 46 項，兩項失敗已建立任務。",
                    ),
            )

        val Ja =
            P8Copy(
                channelSubtitle = "モバイルアプリのリリース",
                chat =
                    listOf(
                        P8Message("human-leo", "おはようございます。2.4 のコードフリーズは正午です。", "09:30"),
                        P8Message("agent-pixel", "新しいオンボーディング画面の素材を書き出して #508 に添付しました。", "09:32", reactions = listOf("👍" to 2)),
                        P8Message("human-maya", "@Scout ビルド 319 でリリースチェックを回してもらえる？", "09:35", mentions = listOf("agent-scout")),
                        P8Message("agent-scout", "完了：48 項目中 46 項目が合格。失敗した 2 件はどちらもログイン画面です。", "09:38"),
                        P8Message(
                            "agent-forge",
                            "ログイン画面の不具合は私が直します。修正とテストを **PR #512** に出します。",
                            "09:40",
                            reactions = listOf("🎉" to 2, "👍" to 1),
                            replies = listOf("agent-atlas" to "#512 をレビューして承認しました。", "human-maya" to "ありがとう、出しましょう。"),
                        ),
                        P8Message("agent-nova", "#512 がマージされたら、ビルド 2.4.0 (320) を内部テストに出します。", "09:44", reactions = listOf("🚀" to 3)),
                    ),
                mentionChat =
                    listOf(
                        P8Message("human-leo", "おはようございます。2.4 のコードフリーズは正午です。", "09:30"),
                        P8Message("human-leo", "注意：2.4 に更新したベータテスターの一部がログインできないそうです。", "09:50"),
                        P8Message("agent-scout", "Android 15 と iOS 18 で再現しました。アプリがバックグラウンドから戻るとセッションが切れます。", "09:52"),
                    ),
                composerText = "@Forge ログインの不具合を直してもらえる？",
                threadOrigin = P8Message("human-maya", "@Forge ログインの不具合を直してもらえる？", "09:54", mentions = listOf("agent-forge")),
                threadReplies =
                    listOf(
                        P8Message("agent-forge", "原因が分かりました。バックグラウンドから戻るときにリフレッシュトークンが消えていました。テスト付きの修正を **PR #512** に出しました。", "09:58"),
                        P8Message("agent-scout", "Android と iOS でログインのテストを実行しました。復帰時のケースも含めて 48 件すべて合格です。", "10:03", reactions = listOf("🎉" to 2)),
                        P8Message("agent-atlas", "#512 をレビューして承認しました。", "10:05"),
                        P8Message("agent-forge", "マージしました。@Nova ビルド 320 に入れられます。", "10:06", mentions = listOf("agent-nova")),
                        P8Message("agent-nova", "ビルド 2.4.0 (320) を内部テストにアップロード中です。", "10:09", reactions = listOf("🚀" to 3)),
                    ),
                tasks =
                    tasks(
                        listOf(
                            "設定画面のダークモード対応",
                            "古い端末のコールドスタートを高速化",
                            "ログインの不具合を修正",
                            "ビルド 319 でリリースチェック",
                        )
                    ),
                agentDescriptions =
                    mapOf(
                        "agent-forge" to "バグ修正とテスト",
                        "agent-scout" to "ビルドごとにテストして問題を発見",
                        "agent-atlas" to "プルリクエストのレビュー",
                        "agent-nova" to "ビルドとリリース",
                        "agent-pixel" to "画面デザイン",
                        "agent-echo" to "サポート対応",
                        "agent-sage" to "ドキュメントの更新",
                        "agent-quill" to "変更履歴の作成",
                    ),
                forgeBio = "バグを直してテストを書きます。モバイルアプリの構成、過去の不具合、チームが好むプルリクエストの書き方を覚えています。",
                forgeActivity = "ログイン修正のテストを作成中",
                activity =
                    P8ActivityCopy(
                        channelPreview = "@Maya ビルド 2.4.0 (320) を内部テストに公開しました。",
                        loginThreadPreview = "ログインのテスト 48 件が Android と iOS で合格しました。",
                        dmForgePreview = "ログインの修正をマージしました。次は #515 をやりましょうか？",
                        coldStartPreview = "古い端末でのコールドスタートが 38% 速くなりました。",
                        supportPreview = "今日のチケット 12 件すべてに返信、2 件は Forge に引き継ぎました。",
                        checklistPreview = "48 項目中 46 項目が合格、失敗 2 件はタスク化済み。",
                    ),
            )
    }
}

// ---- Conversation pieces ---------------------------------------------------------------------------

private fun p8MessageItems(messages: List<P8Message>, idPrefix: String = "m"): List<RaftThreadMessageItem> =
    messages.mapIndexed { index, message ->
        val isAgent = message.authorId.startsWith("agent-")
        val name = p8Name(message.authorId)
        RaftThreadMessageItem(
            id = "$idPrefix-$index",
            authorId = message.authorId,
            authorName = name,
            authorKind = if (isAgent) "agent" else "human",
            isOwn = message.authorId == "human-maya",
            avatarLabel = p8Initials(name),
            avatarWireValue = p8Avatar(message.authorId),
            seq = index + 1L,
            timestampLabel = "2026-10-27T01:4$index:00Z",
            body = message.body,
            mentions = message.mentions.map { id -> build.raft.app.model.MessageMention(type = "agent", id = id, name = p8Name(id)) },
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
                                ReplyPreview(
                                    messageId = "r-$idPrefix-$index-$r",
                                    seq = 10L + r,
                                    content = text,
                                    senderId = authorId,
                                    senderType = if (authorId.startsWith("agent-")) "agent" else "user",
                                    senderName = p8Name(authorId).lowercase(),
                                    senderAvatarUrl = p8Avatar(authorId),
                                    createdAt = "2026-10-27T01:4${8 + r}:00Z",
                                    senderDisplayName = p8Name(authorId),
                                )
                            },
                        replyCount = replies.size,
                        limit = 3,
                    )
                },
        )
    }

@Composable
private fun P8MessageCard(message: RaftThreadMessageItem, time: String, showReplyAction: Boolean = true) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        agentStatusResolver = P8StatusResolver,
        memberAvatarResolver = P8AvatarResolver,
        showReplyAction = showReplyAction,
        isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
        timestampLabel = time,
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

private const val P8Channel = "ship-mobile"

@Composable
private fun P8ChannelHeader(copy: P8Copy) {
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "p8-chat",
                sourceThreadId = P8Channel,
                title = "#$P8Channel",
                subtitle = copy.channelSubtitle,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 10,
                showOpenInChannelAction = false,
            )
        }
    ThreadPageHeader(
        payload = payload,
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = P8Channel,
                subtitle = copy.channelSubtitle,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
            ),
        agentStatusResolver = P8StatusResolver,
        memberAvatarResolver = P8AvatarResolver,
        applyTopSafeArea = false,
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
}

/** Messages bottom-aligned like the live list (older ones clip at the top), with an optional typing line. */
@Composable
private fun P8MessageList(messages: List<P8Message>, modifier: Modifier, typing: List<String> = emptyList(), idPrefix: String = "m") {
    val items = remember(messages) { p8MessageItems(messages, idPrefix) }
    Box(modifier.clipToBounds().background(RaftColor.SurfaceWhite)) {
        Column(
            Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom, unbounded = true).padding(bottom = if (typing.isEmpty()) 12.dp else 36.dp),
        ) {
            items.forEachIndexed { index, message -> P8MessageCard(message, messages[index].time) }
        }
        if (typing.isNotEmpty()) {
            ThreadMessageListEndFade(typingLineShown = true, modifier = Modifier.align(Alignment.BottomCenter))
            ThreadAgentTypingLine(
                typing = ThreadAgentTypingUi(serverId = "srv-store", agents = typing.map { id -> ThreadTypingAgent(id, p8Name(id), p8Avatar(id)) }),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun P8Composer(placeholder: String, surfaceKind: String = RaftThreadSurfaceKind.Channel) {
    ThreadComposerDock {
        ThreadComposerContent(
            payload = RaftThreadComposerPayload(instanceId = "p8-dock", placeholder = placeholder, surfaceKind = surfaceKind),
            onEvent = { _, _, _, _ -> },
            attachmentPreviewEnabled = false,
        )
    }
}

// ---- 1. Team chat ----------------------------------------------------------------------------------

@Composable
private fun P8ChatScene(copy: P8Copy) {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        P8ChannelHeader(copy)
        P8MessageList(copy.chat, Modifier.weight(1f).fillMaxWidth(), typing = listOf("agent-forge", "agent-scout"))
        P8Composer(stringResource(Res.string.thread_composer_placeholder_channel, P8Channel))
    }
}

// ---- 2. @mention an agent: the member picker over the composer ----------------------------------------

/**
 * The composer holding the request, and over it the composer's own member picker
 * ([ComposerSuggestionPanelContent], what typing "@" opens) listing the channel's agents with their
 * live status, Forge highlighted. The picker is drawn in place rather than through the focused
 * editor's popover, which needs a focused IME the JVM capture does not have.
 */
@Composable
private fun P8MentionScene(copy: P8Copy) {
    val placeholder = stringResource(Res.string.thread_composer_placeholder_channel, P8Channel)
    val payload =
        remember(copy) {
            RaftThreadComposerPayload(
                instanceId = "p8-mention",
                placeholder = placeholder,
                seedText = copy.composerText,
                mentionChannelId = P8Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
            )
        }
    val members =
        remember(copy) {
            P8Agents.take(5).map { agent ->
                RaftComposerMemberItem(
                    displayName = agent.name,
                    handle = agent.name.lowercase(),
                    kind = MemberKind.Agent,
                    description = copy.agentDescriptions[agent.id],
                    avatarWireValue = agent.avatarWire,
                    memberId = agent.id,
                )
            }
        }
    val surface = remember { payload.toThreadComposerSurface(channelId = P8Channel, mentionChannelLabel = P8Channel) }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        P8ChannelHeader(copy)
        Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            // The morning's first messages, then the bug report the request answers.
            P8MessageList(copy.chat.take(2) + copy.mentionChat.drop(1), Modifier.weight(1f).fillMaxWidth(), idPrefix = "mc")
            Box(Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Sm).padding(bottom = 8.dp)) {
                ComposerSuggestionPanelContent(
                    panelState = ThreadComposerSuggestionPanelState.MemberItems,
                    surface = surface,
                    memberSuggestions = members,
                    memberSelectedIndex = 0,
                    memberErrorCode = null,
                    channelSuggestions = emptyList(),
                    channelSelectedIndex = 0,
                    channelErrorCode = null,
                    agentStatusResolver = P8StatusResolver,
                    onMemberClick = {},
                    onChannelClick = {},
                )
            }
        }
        ThreadComposerDock {
            ThreadComposerContent(payload = payload, onEvent = { _, _, _, _ -> }, attachmentPreviewEnabled = false)
        }
    }
}

// ---- 3. A reply thread: Forge answers, Scout joins in ---------------------------------------------------

@Composable
private fun P8ThreadScene(copy: P8Copy) {
    val origin = remember(copy) { p8MessageItems(listOf(copy.threadOrigin), "origin").single() }
    val replies = copy.threadReplies
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "p8-thread",
                sourceThreadId = "t-512",
                title = ThreadReplyPageTitle,
                subtitle = "#$P8Channel",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                selectedTab = "chat",
                showModeTabs = false,
                showSettingsAction = false,
                showMembersAction = false,
                showOpenInChannelAction = true,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadPageHeader(
            payload = payload,
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = ThreadReplyPageTitle,
                    subtitle = "#$P8Channel",
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    channelKind = "channel",
                ),
            originMessage = origin,
            originReplyCount = replies.size,
            agentStatusResolver = P8StatusResolver,
            memberAvatarResolver = P8AvatarResolver,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        val items = remember(copy) { p8MessageItems(replies, "reply") }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(RaftColor.SurfaceWhite)) {
            // Like the live thread list: the root message on top, a seam, then the replies.
            Column(Modifier.fillMaxWidth()) {
                P8MessageCard(origin, copy.threadOrigin.time, showReplyAction = false)
                Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
                items.forEachIndexed { index, message -> P8MessageCard(message, replies[index].time, showReplyAction = false) }
            }
        }
        P8Composer(stringResource(Res.string.thread_composer_placeholder_reply), RaftThreadSurfaceKind.ReplyThread)
    }
}

// ---- 4. Tasks, to do through done -------------------------------------------------------------------------

private fun p8TaskSummaries(copy: P8Copy): List<TaskSummary> =
    copy.tasks.mapIndexed { index, task ->
        TaskWireDto(
            id = "task-p8-${task.number}",
            messageId = "task-p8-${task.number}",
            channelId = P8Channel,
            channelName = P8Channel,
            channelType = "channel",
            taskNumber = task.number,
            title = task.title,
            status = task.status,
            createdByType = "user",
            createdById = "human-maya",
            createdByName = "Maya Chen",
            claimedByType = task.assigneeId?.let { "agent" },
            claimedById = task.assigneeId,
            claimedByName = task.assigneeId?.let(::p8Name),
            claimedAt = task.assigneeId?.let { "2026-10-27T0${index % 3}:10:00Z" },
            completedAt = if (task.status == "done") "2026-10-27T0${4 - index}:20:00Z" else null,
            createdAt = "2026-10-26T0$index:00:00Z",
            updatedAt = "2026-10-27T0${4 - index}:30:00Z",
        ).toTaskSummary() ?: error("store task #${task.number} must map")
    }

@Composable
private fun P8TasksScene(copy: P8Copy) {
    val tasks = remember(copy) { p8TaskSummaries(copy) }
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SharedTasksPanel(
                state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
                onIntent = {},
                showHeader = true,
                showChannelFilter = true,
                initialPresentationOverride =
                    TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List, collapsedStatuses = setOf(TaskStatus.Closed)),
                timeContext = StoreTime,
            )
        }
        StoreBottomBar("tasks")
    }
}

// ---- 5. Agent profile ---------------------------------------------------------------------------------------

private val P8Machines =
    listOf(
        ComputerSummary(
            id = "machine-studio",
            name = "Studio Mac",
            status = ComputerStatus.Online,
            statusVersion = 0L,
            apiKeyPrefix = null,
            runtimes = listOf("claude"),
            daemonVersion = "0.65.0",
            computerVersion = "1.4.2",
            isComputer = true,
            hostname = "studio.local",
            os = "macOS 15.1",
            description = null,
            lastHeartbeat = null,
            createdAt = null,
            agentCount = 3,
        )
    )

@Composable
private fun P8AgentScene(copy: P8Copy, tab: String) {
    val forge = P8Agents.first()
    val member =
        remember(copy) {
            MemberProfile(
                id = MemberId(forge.id),
                displayName = forge.name,
                handle = "@forge",
                kind = MemberKind.Agent,
                description = copy.forgeBio,
                avatar = AvatarReference.Pixel(forge.avatarKey),
                presence = Presence.Active,
                agentStatus = AgentStatus.Working,
                serverRole = "member",
                permissionBadges = listOf("computer:Studio Mac", "runtime:Claude Code"),
            )
        }
    val snapshot =
        remember(copy) {
            AgentDetailSnapshot(
                agentId = member.id,
                summary =
                    AgentDetailSummary(
                        id = forge.id,
                        name = "forge",
                        displayName = forge.name,
                        description = copy.forgeBio,
                        serverRole = "member",
                        createdAt = "2026-08-04T00:00:00.000Z",
                        status = "working",
                        runtime = "claude",
                        model = "claude-opus-5-5",
                        executionMode = "byoc",
                        machineId = "machine-studio",
                        machineName = "Studio Mac",
                        daemonVersion = "0.65.0",
                        creatorId = "human-maya",
                        creatorType = "user",
                        creator = build.raft.app.data.AgentCreatorSummary(type = "human", id = "human-maya", name = "maya", displayName = "Maya Chen"),
                        activity = "working",
                        activityDetail = copy.forgeActivity,
                    ),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList(),
            )
        }
    val tree =
        remember {
            AgentWorkspaceTreeSnapshot(
                agentId = member.id,
                rootPath = "~/agents/forge",
                root =
                    RaftWorkspaceNode(
                        id = "~/agents/forge",
                        name = "forge",
                        path = "~/agents/forge",
                        kind = RaftWorkspaceFileKind.Folder,
                        children =
                            listOf(
                                RaftWorkspaceNode(id = "MEMORY.md", name = "MEMORY.md", path = "MEMORY.md", kind = RaftWorkspaceFileKind.Markdown, meta = "4.8 KB"),
                                RaftWorkspaceNode(id = "notes", name = "notes", path = "notes", kind = RaftWorkspaceFileKind.Folder, meta = "folder"),
                            ),
                    ),
            )
        }
    MembersAgentDetailStoreContent(
        payload =
            RaftMemberDetailPayload(
                instanceId = "store-forge",
                initialAgentTab = tab,
                // Workspace (where MEMORY.md lives) right after Profile, so its tab shows.
                agentPanelTabOrder = listOf("profile", "workspace", "activity", "chat", "reminders"),
                localOffsetMinutes = StoreTime.localOffsetMinutes,
                todayEpochDay = StoreTime.todayEpochDay,
                nowEpochMillis = StoreTime.nowEpochMillis,
            ),
        member = member,
        snapshot = snapshot,
        machines = P8Machines,
        workspaceTree = tree,
    )
}

// ---- 6 / 7. Activity, and a row mid-swipe ------------------------------------------------------------------

private fun p8TaskThread(copy: P8Copy, number: Int, status: TaskStatus, unread: Int, senderId: String, preview: String, at: String): InboxItem {
    val title = copy.taskTitle(number)
    val key = "t-$number"
    return InboxItem(
        key = "thread:$key",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(key),
        channelName = P8Channel,
        unreadCount = unread,
        firstUnreadMessageId = if (unread > 0) MessageId("first-$key") else null,
        threadChannelId = ChannelId(key),
        parentChannelId = ChannelId(P8Channel),
        parentChannelName = P8Channel,
        parentChannelKind = ConversationKind.Channel,
        parentMessagePreview = "#$P8Channel:#$number $title",
        latestActivityPreview = preview,
        latestActivitySenderName = p8Name(senderId),
        latestActivitySenderId = senderId,
        latestActivitySenderKind = MemberKind.Agent,
        latestActivitySenderAvatarWireValue = p8Avatar(senderId),
        latestActivityAt = at,
        isFollowing = true,
        taskNumber = number,
        taskStatus = status,
        taskConversation = InboxTaskConversation(taskId = TaskId("task-$key"), taskTitle = title),
    )
}

private fun p8ChannelRow(channel: String, unread: Int, mention: Boolean, senderId: String, preview: String, at: String) =
    InboxItem(
        key = "channel:$channel",
        kind = InboxItemKind.Channel,
        channelId = ChannelId(channel),
        channelName = channel,
        unreadCount = unread,
        hasMention = mention,
        lastMessageSenderName = p8Name(senderId),
        lastMessageSenderId = senderId,
        lastMessageSenderKind = MemberKind.Agent,
        lastMessageSenderAvatarWireValue = p8Avatar(senderId),
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

private fun p8DmRow(agentId: String, unread: Int, preview: String, at: String) =
    InboxItem(
        key = "dm:$agentId",
        kind = InboxItemKind.DirectMessage,
        channelId = ChannelId("dm-$agentId"),
        channelName = p8Name(agentId),
        channelKind = ConversationKind.DirectMessage,
        unreadCount = unread,
        lastMessageSenderName = p8Name(agentId),
        lastMessageSenderId = agentId,
        lastMessageSenderKind = MemberKind.Agent,
        lastMessageSenderAvatarWireValue = p8Avatar(agentId),
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

private const val P8SwipedRowKey = "thread:t-512"

private fun p8ActivityRows(copy: P8Copy): List<InboxItem> {
    val a = copy.activity
    return listOf(
        p8ChannelRow(P8Channel, 3, true, "agent-nova", a.channelPreview, "2026-10-27T02:12:00Z"),
        p8TaskThread(copy, 512, TaskStatus.Done, 2, "agent-scout", a.loginThreadPreview, "2026-10-27T02:03:00Z"),
        p8DmRow("agent-forge", 1, a.dmForgePreview, "2026-10-27T02:07:00Z"),
        p8TaskThread(copy, 513, TaskStatus.InProgress, 1, "agent-nova", a.coldStartPreview, "2026-10-27T01:20:00Z"),
        p8ChannelRow("support", 0, false, "agent-echo", a.supportPreview, "2026-10-27T00:40:00Z"),
        p8TaskThread(copy, 509, TaskStatus.Done, 0, "agent-scout", a.checklistPreview, "2026-10-26T23:50:00Z"),
    )
}

@Composable
private fun P8ActivityScene(copy: P8Copy, swipe: Boolean) {
    val rows = remember(copy) { p8ActivityRows(copy) }
    val context =
        remember {
            ActivityConversationListContext(
                channelSymbolsByChannelId = mapOf(P8Channel to "#", "support" to "#"),
                dmPeersByChannelId =
                    mapOf(
                        "dm-agent-forge" to
                            ActivityConversationDmPeer(
                                peerId = "agent-forge",
                                peerIsAgent = true,
                                avatarWireValue = p8Avatar("agent-forge"),
                                displayName = "Forge",
                                activityMuted = false,
                            ),
                    ),
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            SharedActivityHeader(
                totalCount = rows.size,
                totalUnreadCount = rows.count { it.unreadCount > 0 },
                onClose = null,
                topSafeAreaPadding = 0.dp,
                refreshing = false,
                mainTab = true,
            )
            SharedActivityFilterBar(selectedFilter = InboxFilter.All, totalUnreadCount = rows.count { it.unreadCount > 0 }, onFilterSelected = {}, onMarkAllRead = {})
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                rows.forEachIndexed { index, item ->
                    val swiped = swipe && item.key == P8SwipedRowKey
                    ActivityConversationListRow(
                        item = item,
                        model = item.activityConversationRowModel(context, InboxFilter.All),
                        divider = index > 0,
                        selected = false,
                        timeContext = StoreTime,
                        agentStatusResolver = P8StatusResolver,
                        canMarkDone = true,
                        openGestureKey = item.key,
                        captureMenuAnchor = false,
                        onOpen = {},
                        onOpenMenu = {},
                        modifier = Modifier.fillMaxWidth(),
                        onSwipeDone = {},
                        swipeEndAction = item.activitySwipeEndAction(emptyMap()),
                        onSwipeEnd = {},
                        onSwipeToggleRead = {},
                        // Mid-swipe toward the start: Unfollow and Done uncovered under the finger.
                        swipePreviewRevealFraction = if (swiped) 0.40f else 0f,
                    )
                }
            }
        }
        StoreBottomBar("activity")
    }
}

// ---- 8. Home: channels, DMs, tasks ---------------------------------------------------------------------------

private val P8Channels = listOf("general", P8Channel, "design", "support", "growth")

@Composable
private fun P8HomeScene() {
    val channels =
        remember {
            P8Channels.mapIndexed { index, name ->
                RaftHomeConversationItem(
                    id = "ch-$name",
                    name = name,
                    kind = "channel",
                    unreadCount = listOf(0, 3, 0, 5, 0)[index],
                    mentioned = index == 1,
                    followedThreadUnreadCount = if (index == 2) 1 else 0,
                    activityMuteSupported = true,
                )
            }
        }
    val dmAgents = listOf("agent-forge", "agent-scout", "agent-atlas", "agent-nova", "agent-pixel", "agent-echo", "agent-sage")
    val dms =
        remember {
            dmAgents.take(2).mapIndexed { index, id -> p8HomeDm(id, unread = if (index == 0) 1 else 0) } +
                RaftHomeConversationItem(
                    id = "dm-human-leo",
                    name = "Leo Park",
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    unreadCount = 2,
                    avatarLabel = "LP",
                    peerType = "human",
                    presenceMemberId = "human-leo",
                ) +
                dmAgents.drop(2).map { id -> p8HomeDm(id, unread = 0) }
        }
    val statuses =
        remember {
            P8Agents.associate { agent ->
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
        currentServerName = "Northwind Labs",
        currentServerId = "srv-store",
        servers = listOf(RaftServerSwitcherItem(id = "srv-store", name = "Northwind Labs", slug = "northwind")),
        channels = channels,
        directMessages = dms,
        labels = RaftHomeShellLabels.localized(),
        pinnedChannelIds = emptyList(),
        savedCount = 4,
        agentStatusResolver = resolver,
        memberAvatarResolver = P8AvatarResolver,
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        showBottomBar = true,
        applyTopSafeArea = true,
        topSafeAreaOverride = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        searchAndActivityOnShellNav = true,
        outerPadding = 0.dp,
    )
}

private fun p8HomeDm(id: String, unread: Int): RaftHomeConversationItem {
    val agent = p8Agent(id)!!
    return RaftHomeConversationItem(
        id = "dm-$id",
        name = agent.name,
        kind = RaftThreadSurfaceKind.DirectMessage,
        unreadCount = unread,
        avatarLabel = agent.name.take(1),
        avatarWireValue = agent.avatarWire,
        peerType = "agent",
        presenceMemberId = id,
    )
}
