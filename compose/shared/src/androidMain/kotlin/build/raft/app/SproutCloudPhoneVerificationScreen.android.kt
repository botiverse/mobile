package build.raft.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.sprout.compose.SproutHostFrame
import build.raft.sprout.compose.defaultSproutComposeRegistry
import build.raft.sprout.protocol.RAFT_UI_CATALOG
import build.raft.sprout.protocol.ValidationProfile
import build.raft.sprout.runtime.ActionResultReceipt
import build.raft.sprout.runtime.FrameCommit
import build.raft.sprout.runtime.OutboundEffect
import build.raft.sprout.runtime.RuntimeLimits
import build.raft.sprout.runtime.SproutEngine
import build.raft.sprout.runtime.StateReceipt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PREFS_NAME = "sprout_playground_prefs"
private const val PREF_SERVER_URL = "server_url"
private const val PREF_SERVER_TOKEN = "server_token"
private const val PREF_SERVER_SCOPE = "server_scope"
private const val PREF_STREAMING = "chat_streaming"

// IP-allowlisted cloud-phone gateway: zero credentials in APK/git; upstream rotates server-side
private const val DEFAULT_CLOUD_GATEWAY_URL = "https://cables-operates-jennifer-pendant.trycloudflare.com"
private const val DEFAULT_CLOUD_GATEWAY_TOKEN = "cloud-phone-ip-gated"
private const val DEFAULT_CLOUD_SCOPE = "android-playground"

private enum class PlaygroundRoute { CHAT, DEV_TOOLS }
private enum class ViewerMode { YOU, MATE, SPLIT }

internal data class UiChatMessage(
    val id: String,
    val seq: Long,
    val atMs: Long,
    val isUser: Boolean,
    val authorName: String,
    val text: String,
    val status: String = "done", // "queued" | "running" | "done" | "failed" | "cancelled"
    val delivery: String = "stream", // "stream" | "oneshot"
    val turnId: String = "",
    val targetMessageId: String? = null,
    val surfaceId: String? = null,
    val surfaceGeneration: Long = 1L,
    val source: String? = null,
    val sourceRevision: Long = 1L,
    val errorText: String? = null,
)

private val RAFT_INK = Color(0xFF18181B)
private val RAFT_YELLOW = Color(0xFFFFE14D)
private val RAFT_CANVAS = Color(0xFFFAF9F5)
private val RAFT_SURFACE = Color(0xFFFFFFFF)
private val RAFT_SEAM = Color(0xFFE4E4E7)
private val RAFT_MUTED = Color(0xFF71717A)

private fun formatShortClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)) }.getOrDefault("--:--")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SproutCloudPhoneVerificationScreen(
    imeTick: Int,
    imeOp: Pair<String, String>?,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val clockOrigin = remember { TimeSource.Monotonic.markNow() }
    fun nowMs(): Long = clockOrigin.elapsedNow().toLong(DurationUnit.MILLISECONDS)

    val scope = rememberCoroutineScope()
    val hostView = LocalView.current

    var route by remember { mutableStateOf(PlaygroundRoute.CHAT) }
    var streamingEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_STREAMING, true)) }
    var dualViewerEnabled by remember { mutableStateOf(false) }
    var viewerMode by remember { mutableStateOf(ViewerMode.YOU) }
    var serverSheetOpen by remember { mutableStateOf(false) }
    var pendingLinkUrl by remember { mutableStateOf<String?>(null) }

    // IME automation hook preserved for cloud-phone regression tests
    var activeIc by remember { mutableStateOf<InputConnection?>(null) }
    LaunchedEffect(imeTick) {
        if (imeTick > 0 && imeOp != null) {
            if (imeOp.first == "reset_ic") activeIc = null
            val target = hostView.findFocus() ?: hostView
            val ic = activeIc ?: target.onCreateInputConnection(EditorInfo())?.also { activeIc = it }
            if (ic != null) {
                when (imeOp.first) {
                    "composing" -> ic.setComposingText(imeOp.second, 1)
                    "commit" -> ic.commitText(imeOp.second, 1)
                    "finish" -> ic.finishComposingText()
                }
            }
        }
    }

    // Shared QuickJS Engine across surfaces (mirrors ChatApp.tsx MAX_GUESTS)
    var engineBootError by remember { mutableStateOf<String?>(null) }
    val engine = remember {
        runCatching {
            SproutEngine.prepare(
                limits = RuntimeLimits(
                    compilerBudgetMs = 600L,
                    sourceUpdateBudgetMs = 300L,
                    dispatchBudgetMs = 150L,
                    tickBudgetMs = 50L,
                ),
            )
        }.onFailure { err ->
            engineBootError = "${err::class.simpleName}: ${err.message}"
        }.getOrNull()
    }

    DisposableEffect(engine) {
        onDispose { runCatching { engine?.close() } }
    }

    val stateHub = remember { LocalSharedStateHub() }
    val sessionTag = remember { UUID.randomUUID().toString().replace("-", "").take(6) }
    val youViewerId = remember { "android-you-$sessionTag" }
    val mateViewerId = remember { "android-mate-$sessionTag" }

    // Cloud demo-server connection state: connects to real model by default via IP-gated gateway!
    var savedServerConfig by remember {
        val url = prefs.getString(PREF_SERVER_URL, null)
        val tok = prefs.getString(PREF_SERVER_TOKEN, null)
        val sc = prefs.getString(PREF_SERVER_SCOPE, DEFAULT_CLOUD_SCOPE) ?: DEFAULT_CLOUD_SCOPE
        mutableStateOf(
            if (!url.isNullOrBlank() && !tok.isNullOrBlank()) {
                DemoServerConfig(serverUrl = url, token = tok, scopeId = sc)
            } else {
                DemoServerConfig(
                    serverUrl = DEFAULT_CLOUD_GATEWAY_URL,
                    token = DEFAULT_CLOUD_GATEWAY_TOKEN,
                    scopeId = sc,
                )
            },
        )
    }
    val serverClient = remember(savedServerConfig) {
        savedServerConfig?.let { SproutDemoServerClient(it) }
    }
    var serverHealth by remember { mutableStateOf<ServerHealthInfo?>(null) }
    var serverNotice by remember { mutableStateOf<String?>(null) }

    // Local built-in channel messages (pre-populated with 1 welcome interactive poll!)
    val localMessages = remember {
        val welcomeSample = SproutPlaygroundCatalog.SAMPLES[0]
        val fullWelcomeText = buildString {
            appendLine(welcomeSample.introProse)
            appendLine("```genui id=${welcomeSample.surfaceId}")
            appendLine(welcomeSample.source)
            appendLine("```")
            append(welcomeSample.outroProse)
        }
        val now = System.currentTimeMillis()
        mutableStateListOf(
            UiChatMessage(
                id = "local-u-1",
                seq = 1L,
                atMs = now - 60_000L,
                isUser = true,
                authorName = "Artea",
                text = welcomeSample.defaultPrompt,
            ),
            UiChatMessage(
                id = "local-a-1",
                seq = 2L,
                atMs = now - 58_000L,
                isUser = false,
                authorName = "Sprout",
                text = fullWelcomeText,
                status = "done",
                delivery = "stream",
                surfaceId = welcomeSample.surfaceId,
                surfaceGeneration = 1L,
                source = welcomeSample.source,
                sourceRevision = 1L,
            ),
        )
    }

    // Remote demo-server channel messages
    val remoteMessages = remember { mutableStateListOf<UiChatMessage>() }
    val activeMessages = if (serverClient != null) remoteMessages else localMessages
    val activeScopeId = savedServerConfig?.scopeId ?: "local-generative-ui"

    var draftText by remember { mutableStateOf("") }
    var targetMessageId by remember { mutableStateOf<String?>(null) }
    var localGenJob by remember { mutableStateOf<Job?>(null) }

    fun applyNewServerConfig(cfg: DemoServerConfig?) {
        localGenJob?.cancel()
        targetMessageId = null
        if (cfg != null) {
            savedServerConfig = cfg
            if (cfg.serverUrl == DEFAULT_CLOUD_GATEWAY_URL && cfg.token == DEFAULT_CLOUD_GATEWAY_TOKEN) {
                prefs.edit()
                    .remove(PREF_SERVER_URL)
                    .remove(PREF_SERVER_TOKEN)
                    .putString(PREF_SERVER_SCOPE, cfg.scopeId)
                    .apply()
            } else {
                prefs.edit()
                    .putString(PREF_SERVER_URL, cfg.serverUrl)
                    .putString(PREF_SERVER_TOKEN, cfg.token)
                    .putString(PREF_SERVER_SCOPE, cfg.scopeId)
                    .apply()
            }
            serverNotice = "正在连接真模型 (${cfg.scopeId})…"
        } else {
            val resetCfg = DemoServerConfig(
                serverUrl = DEFAULT_CLOUD_GATEWAY_URL,
                token = DEFAULT_CLOUD_GATEWAY_TOKEN,
                scopeId = DEFAULT_CLOUD_SCOPE,
            )
            savedServerConfig = resetCfg
            prefs.edit()
                .remove(PREF_SERVER_URL)
                .remove(PREF_SERVER_TOKEN)
                .putString(PREF_SERVER_SCOPE, DEFAULT_CLOUD_SCOPE)
                .apply()
            serverNotice = "已恢复默认真模型网关 (${resetCfg.scopeId})…"
        }
    }

    // File picker for `sprout-live-server.json` pushed via `testbed verify push-file`
    val configFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            val parsed = text?.let { DemoServerConfig.parse(it) }
            if (parsed != null) {
                applyNewServerConfig(parsed)
                serverSheetOpen = false
            } else {
                serverNotice = "文件格式不匹配：请选择 sprout-live-server.json"
            }
        }
    }

    // Poll loop when connected to live `demo-server`
    LaunchedEffect(serverClient, activeScopeId) {
        val client = serverClient ?: return@LaunchedEffect
        remoteMessages.clear()
        val health = client.checkHealth()
        serverHealth = health
        if (!health.ok) {
            serverNotice = "云端连接异常：${health.error ?: "不可达"}"
            return@LaunchedEffect
        }
        serverNotice = null

        var cursor: Long? = null
        var currentLogId: String? = null

        fun mapRemote(m: RemoteChatMessage): UiChatMessage {
            val author = if (m.kind == "user") {
                val raw = m.actorId.removePrefix("viewer:")
                if (raw.startsWith("android-mate")) "队友" else "你"
            } else {
                "Sprout"
            }
            return UiChatMessage(
                id = m.id,
                seq = m.seq,
                atMs = m.atMs,
                isUser = m.kind == "user",
                authorName = author,
                text = m.text,
                status = m.status,
                delivery = m.delivery,
                turnId = m.turnId,
                targetMessageId = m.targetMessageId,
                surfaceId = m.surfaceId,
                surfaceGeneration = m.surfaceGeneration,
                source = m.source,
                sourceRevision = m.sourceRevision.coerceAtLeast(1L),
                errorText = m.errorText,
            )
        }

        fun upsertRemote(m: RemoteChatMessage) {
            // If this agent reply revised an earlier message's surface, demo-server also emits an updated `message` event for that earlier message.
            val mapped = mapRemote(m)
            val idx = remoteMessages.indexOfFirst { it.id == mapped.id }
            if (idx >= 0) {
                remoteMessages[idx] = mapped
            } else {
                val insertAt = remoteMessages.indexOfFirst { it.seq > mapped.seq }
                if (insertAt >= 0) remoteMessages.add(insertAt, mapped) else remoteMessages.add(mapped)
            }
        }

        while (isActive) {
            val pollRes = runCatching {
                client.pollEvents(scopeId = activeScopeId, viewerId = youViewerId, after = cursor)
            }.getOrElse { err ->
                serverNotice = "轮询重连中：${err.message?.take(48)}"
                delay(2_000L)
                continue
            }
            serverNotice = null
            if (pollRes.resync) {
                cursor = null
                remoteMessages.clear()
                continue
            }
            cursor = pollRes.next
            for (ev in pollRes.events) {
                when (ev) {
                    is RemotePollEvent.Hello -> {
                        if (ev.logId != null && currentLogId != null && ev.logId != currentLogId) {
                            remoteMessages.clear()
                            targetMessageId = null
                        }
                        if (ev.logId != null) currentLogId = ev.logId
                    }
                    is RemotePollEvent.Messages -> {
                        if (ev.logId != null) currentLogId = ev.logId
                        remoteMessages.clear()
                        for (m in ev.list.sortedBy { it.seq }) {
                            remoteMessages.add(mapRemote(m))
                        }
                    }
                    is RemotePollEvent.MessageUpsert -> upsertRemote(ev.message)
                    is RemotePollEvent.MessageTextDelta -> {
                        val idx = remoteMessages.indexOfFirst { it.id == ev.messageId }
                        if (idx >= 0) {
                            val cur = remoteMessages[idx]
                            if (ev.offset <= cur.text.length) {
                                val nextText = cur.text.substring(0, ev.offset) + ev.text
                                remoteMessages[idx] = cur.copy(text = nextText)
                            }
                        }
                    }
                    is RemotePollEvent.SnapshotPush -> {
                        val key = SharedSurfaceKey(
                            scopeId = ev.scopeId,
                            surfaceId = ev.surfaceId,
                            surfaceGeneration = ev.surfaceGeneration,
                        )
                        stateHub.applyServerSnapshot(key, ev.revision, ev.state, ev.originOpId)
                    }
                    is RemotePollEvent.Halted -> {
                        serverNotice = "服务端暂停写入：${ev.message}"
                    }
                }
            }
        }
    }

    val agentRunning = activeMessages.any { !it.isUser && (it.status == "running" || it.status == "queued") }
    val activeRunningTurnId = activeMessages.lastOrNull { !it.isUser && it.status == "running" }?.turnId

    fun stopActiveTurn() {
        val client = serverClient
        if (client != null && !activeRunningTurnId.isNullOrBlank()) {
            scope.launch { client.cancelTurn(activeRunningTurnId) }
        } else {
            localGenJob?.cancel()
            localGenJob = null
            val idx = localMessages.indexOfLast { !it.isUser && it.status == "running" }
            if (idx >= 0) {
                localMessages[idx] = localMessages[idx].copy(status = "cancelled")
            }
        }
    }

    fun sendPrompt(rawPrompt: String) {
        val clean = rawPrompt.trim()
        if (clean.isEmpty() || agentRunning) return
        val chosenTargetId = targetMessageId
        val deliveryMode = if (streamingEnabled) "stream" else "oneshot"
        draftText = ""
        targetMessageId = null

        val client = serverClient
        if (client != null) {
            val clientMsgId = "m-" + UUID.randomUUID().toString().replace("-", "").take(24)
            scope.launch {
                runCatching {
                    val (uMsg, aMsg) = client.sendChatMessage(
                        viewerId = if (viewerMode == ViewerMode.MATE) mateViewerId else youViewerId,
                        scopeId = activeScopeId,
                        clientMessageId = clientMsgId,
                        text = clean,
                        delivery = deliveryMode,
                        targetMessageId = chosenTargetId,
                    )
                    if (remoteMessages.none { it.id == uMsg.id }) {
                        remoteMessages.add(
                            UiChatMessage(
                                id = uMsg.id,
                                seq = uMsg.seq,
                                atMs = uMsg.atMs,
                                isUser = true,
                                authorName = if (viewerMode == ViewerMode.MATE) "队友" else "你",
                                text = uMsg.text,
                                targetMessageId = uMsg.targetMessageId,
                            ),
                        )
                    }
                    if (remoteMessages.none { it.id == aMsg.id }) {
                        remoteMessages.add(
                            UiChatMessage(
                                id = aMsg.id,
                                seq = aMsg.seq,
                                atMs = aMsg.atMs,
                                isUser = false,
                                authorName = "Sprout",
                                text = aMsg.text,
                                status = aMsg.status,
                                delivery = aMsg.delivery,
                                turnId = aMsg.turnId,
                                surfaceId = aMsg.surfaceId,
                                surfaceGeneration = aMsg.surfaceGeneration,
                                source = aMsg.source,
                                sourceRevision = aMsg.sourceRevision.coerceAtLeast(1L),
                            ),
                        )
                    }
                }.onFailure { err ->
                    serverNotice = "发送失败：${err.message}"
                }
            }
            return
        }

        // Built-in smart agent path (instant zero-setup local mode!)
        val now = System.currentTimeMillis()
        val nextSeqBase = (localMessages.maxOfOrNull { it.seq } ?: 0L) + 1L
        val targetMsg = chosenTargetId?.let { tid -> localMessages.firstOrNull { it.id == tid } }
        val userAuthor = if (viewerMode == ViewerMode.MATE) "队友" else "Artea"

        localMessages.add(
            UiChatMessage(
                id = "local-u-$nextSeqBase",
                seq = nextSeqBase,
                atMs = now,
                isUser = true,
                authorName = userAuthor,
                text = clean,
                targetMessageId = chosenTargetId,
            ),
        )

        val generated = SproutPlaygroundCatalog.matchOrSynthesize(
            prompt = clean,
            targetSurfaceId = targetMsg?.surfaceId,
            existingSource = targetMsg?.source,
        )

        // Revise existing surface in-place vs create a fresh surface on this reply
        if (targetMsg != null && targetMsg.surfaceId != null) {
            val agentMsgId = "local-a-${nextSeqBase + 1L}"
            localMessages.add(
                UiChatMessage(
                    id = agentMsgId,
                    seq = nextSeqBase + 1L,
                    atMs = now + 10L,
                    isUser = false,
                    authorName = "Sprout",
                    text = if (streamingEnabled) "" else "${generated.introProse}\n\n${generated.outroProse}",
                    status = if (streamingEnabled) "running" else "done",
                    delivery = deliveryMode,
                ),
            )
            localGenJob = scope.launch {
                val targetIdx = localMessages.indexOfFirst { it.id == targetMsg.id }
                val replyIdx = localMessages.indexOfFirst { it.id == agentMsgId }
                if (streamingEnabled) {
                    val replyFull = "${generated.introProse}\n\n${generated.outroProse}"
                    var off = 0
                    while (off < replyFull.length) {
                        val end = (off + 18).coerceAtMost(replyFull.length)
                        if (replyIdx in localMessages.indices) {
                            localMessages[replyIdx] = localMessages[replyIdx].copy(text = replyFull.substring(0, end))
                        }
                        off = end
                        delay(25L)
                    }
                } else {
                    delay(260L)
                }
                if (targetIdx in localMessages.indices) {
                    val old = localMessages[targetIdx]
                    val nextRev = old.sourceRevision + 1L
                    val fullUpdatedText = buildString {
                        appendLine(generated.introProse)
                        appendLine("```genui id=${old.surfaceId}")
                        appendLine(generated.source)
                        appendLine("```")
                        append(generated.outroProse)
                    }
                    localMessages[targetIdx] = old.copy(
                        text = fullUpdatedText,
                        source = generated.source,
                        sourceRevision = nextRev,
                    )
                }
                if (replyIdx in localMessages.indices) {
                    localMessages[replyIdx] = localMessages[replyIdx].copy(status = "done")
                }
            }
            return
        }

        val uniqueSurfaceId = "${generated.surfaceId}-${nextSeqBase + 1L}"
        val fullWireText = buildString {
            appendLine(generated.introProse)
            appendLine("```genui id=$uniqueSurfaceId")
            appendLine(generated.source)
            appendLine("```")
            append(generated.outroProse)
        }
        val agentMsgId = "local-a-${nextSeqBase + 1L}"
        localMessages.add(
            UiChatMessage(
                id = agentMsgId,
                seq = nextSeqBase + 1L,
                atMs = now + 10L,
                isUser = false,
                authorName = "Sprout",
                text = if (streamingEnabled) "" else fullWireText,
                status = "running",
                delivery = deliveryMode,
                surfaceId = uniqueSurfaceId,
                surfaceGeneration = 1L,
                source = if (streamingEnabled) null else generated.source,
                sourceRevision = 1L,
            ),
        )

        localGenJob = scope.launch {
            val idx = localMessages.indexOfFirst { it.id == agentMsgId }
            if (idx < 0) return@launch
            if (streamingEnabled) {
                var offset = 0
                val chunkSize = 54
                while (offset < fullWireText.length) {
                    val end = (offset + chunkSize).coerceAtMost(fullWireText.length)
                    val curIdx = localMessages.indexOfFirst { it.id == agentMsgId }
                    if (curIdx >= 0) {
                        localMessages[curIdx] = localMessages[curIdx].copy(
                            text = fullWireText.substring(0, end),
                        )
                    }
                    offset = end
                    delay(32L)
                }
                val finalIdx = localMessages.indexOfFirst { it.id == agentMsgId }
                if (finalIdx >= 0) {
                    localMessages[finalIdx] = localMessages[finalIdx].copy(
                        text = fullWireText,
                        status = "done",
                        source = generated.source,
                    )
                }
            } else {
                // Non-streaming (oneshot): wait until generation completes, then deliver whole in 1 frame!
                delay(380L)
                val finalIdx = localMessages.indexOfFirst { it.id == agentMsgId }
                if (finalIdx >= 0) {
                    localMessages[finalIdx] = localMessages[finalIdx].copy(
                        text = fullWireText,
                        status = "done",
                        source = generated.source,
                    )
                }
            }
        }
    }

    fun handleLocalSurfaceAction(
        surfaceKey: SharedSurfaceKey,
        act: OutboundEffect.ActionRequest,
    ): ActionResultReceipt {
        val record = stateHub.read(surfaceKey)
        val curState = record?.state ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val (replySentence, patchedState) = SproutPlaygroundCatalog.summarizeSharedState(
            surfaceId = surfaceKey.surfaceId,
            actionName = act.action,
            payload = act.payload,
            currentState = curState,
        )
        val updated = stateHub.forcePatchFromAgent(surfaceKey, patchedState)
        val nextSeq = (localMessages.maxOfOrNull { it.seq } ?: 0L) + 1L
        localMessages.add(
            UiChatMessage(
                id = "local-act-$nextSeq",
                seq = nextSeq,
                atMs = System.currentTimeMillis(),
                isUser = false,
                authorName = "Sprout",
                text = "已根据界面「${surfaceKey.surfaceId}」的实时状态完成汇总：\n$replySentence",
                status = "done",
                delivery = "oneshot",
            ),
        )
        return ActionResultReceipt(
            requestId = act.requestId,
            status = "queued",
            committedStateRevision = updated.revision,
            state = updated.state,
            surfaceId = surfaceKey.surfaceId,
            surfaceGeneration = surfaceKey.surfaceGeneration,
        )
    }

    if (route == PlaygroundRoute.DEV_TOOLS) {
        SproutDeveloperToolsScreen(
            engine = engine,
            nowMs = ::nowMs,
            onBackToChat = { route = PlaygroundRoute.CHAT },
        )
        return
    }

    val channelTitle = if (serverClient != null) {
        activeScopeId.removePrefix("channel-")
    } else {
        "generative-ui"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RAFT_CANVAS)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { testTagsAsResourceId = true },
    ) {
        // 1. Raft-styled Channel Top Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RAFT_SURFACE)
                .border(width = 0.5.dp, color = RAFT_SEAM),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(RAFT_YELLOW)
                            .border(1.5.dp, RAFT_INK, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = "#",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
                        )
                    }
                    Column {
                        BasicText(
                            text = channelTitle,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (serverClient != null && serverHealth?.ok == true) {
                                            Color(0xFF16A34A)
                                        } else {
                                            Color(0xFF2563EB)
                                        },
                                    ),
                            )
                            BasicText(
                                text = if (serverClient != null) {
                                    if (serverHealth?.ok == true) {
                                        "云端已连 · ${serverHealth?.model ?: serverHealth?.provider ?: "live"}"
                                    } else {
                                        "云端连接中…"
                                    }
                                } else {
                                    "内置智能体 · 状态实时共享"
                                },
                                style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED),
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderPillButton(
                        label = if (dualViewerEnabled) "双视角: 开" else "双视角",
                        active = dualViewerEnabled,
                        onClick = {
                            dualViewerEnabled = !dualViewerEnabled
                            viewerMode = if (dualViewerEnabled) ViewerMode.SPLIT else ViewerMode.YOU
                        },
                    )
                    HeaderPillButton(
                        label = "频道 / 模型",
                        active = serverSheetOpen,
                        onClick = { serverSheetOpen = !serverSheetOpen },
                    )
                    HeaderPillButton(
                        label = "开发工具",
                        active = false,
                        onClick = { route = PlaygroundRoute.DEV_TOOLS },
                    )
                }
            }

            if (dualViewerEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF4F4F5))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "多人同步预览：",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RAFT_MUTED),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeaderPillButton("👤 你", viewerMode == ViewerMode.YOU) { viewerMode = ViewerMode.YOU }
                        HeaderPillButton("👥 队友", viewerMode == ViewerMode.MATE) { viewerMode = ViewerMode.MATE }
                        HeaderPillButton("▤ 同屏双栏", viewerMode == ViewerMode.SPLIT) { viewerMode = ViewerMode.SPLIT }
                    }
                }
            }

            if (serverSheetOpen) {
                ServerConnectionPanel(
                    currentConfig = savedServerConfig,
                    health = serverHealth,
                    notice = serverNotice,
                    onPickConfigFile = {
                        configFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onPasteFromClipboard = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                        val parsed = DemoServerConfig.parse(clipText)
                        if (parsed != null) {
                            applyNewServerConfig(parsed)
                            serverSheetOpen = false
                        } else {
                            serverNotice = "剪贴板未检测到含 #token=... 的演示链接或 JSON 配置"
                        }
                    },
                    onSwitchScope = { nextScope ->
                        savedServerConfig?.let { cur ->
                            applyNewServerConfig(cur.copy(scopeId = nextScope))
                        }
                    },
                    onDisconnect = {
                        applyNewServerConfig(null)
                        serverSheetOpen = false
                    },
                    onClose = { serverSheetOpen = false },
                )
            }
        }

        if (engineBootError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEE2E2))
                    .padding(10.dp),
            ) {
                BasicText(
                    text = "QuickJS 启动异常：$engineBootError",
                    style = TextStyle(fontSize = 12.sp, color = Color(0xFF991B1B)),
                )
            }
        }

        if (serverNotice != null && !serverSheetOpen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEF3C7))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                BasicText(
                    text = serverNotice!!,
                    style = TextStyle(fontSize = 11.sp, color = Color(0xFF92400E)),
                )
            }
        }

        // 2. Channel Timeline Area (Single Viewer or Split Dual-Viewer!)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (dualViewerEnabled && viewerMode == ViewerMode.SPLIT) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ViewerTimelinePane(
                        titleBanner = "👤 视角 A：你 ($youViewerId)",
                        bannerColor = Color(0xFFDBEAFE),
                        messages = activeMessages,
                        engine = engine,
                        scopeId = activeScopeId,
                        viewerId = youViewerId,
                        stateHub = stateHub,
                        serverClient = serverClient,
                        nowMs = ::nowMs,
                        onLocalAction = ::handleLocalSurfaceAction,
                        onSelectTargetMessage = { targetMessageId = it },
                        onOpenExternalLink = { pendingLinkUrl = it },
                        onStopTurn = ::stopActiveTurn,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(RAFT_INK))
                    ViewerTimelinePane(
                        titleBanner = "👥 视角 B：队友 ($mateViewerId) · 同一频道实时同步",
                        bannerColor = Color(0xFFFEF9C3),
                        messages = activeMessages,
                        engine = engine,
                        scopeId = activeScopeId,
                        viewerId = mateViewerId,
                        stateHub = stateHub,
                        serverClient = serverClient,
                        nowMs = ::nowMs,
                        onLocalAction = ::handleLocalSurfaceAction,
                        onSelectTargetMessage = { targetMessageId = it },
                        onOpenExternalLink = { pendingLinkUrl = it },
                        onStopTurn = ::stopActiveTurn,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else {
                val isMate = dualViewerEnabled && viewerMode == ViewerMode.MATE
                ViewerTimelinePane(
                    titleBanner = if (isMate) "👥 当前正在以「队友」身份查看（与「你」实时同步状态）" else null,
                    bannerColor = Color(0xFFFEF9C3),
                    messages = activeMessages,
                    engine = engine,
                    scopeId = activeScopeId,
                    viewerId = if (isMate) mateViewerId else youViewerId,
                    stateHub = stateHub,
                    serverClient = serverClient,
                    nowMs = ::nowMs,
                    onLocalAction = ::handleLocalSurfaceAction,
                    onSelectTargetMessage = { targetMessageId = it },
                    onOpenExternalLink = { pendingLinkUrl = it },
                    onStopTurn = ::stopActiveTurn,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // External Link Confirmation Banner (host security boundary, matching Web hostbar)
        if (pendingLinkUrl != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RAFT_INK)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "界面请求打开外部链接：${pendingLinkUrl!!.take(42)}",
                    style = TextStyle(fontSize = 11.sp, color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RAFT_YELLOW)
                            .clickable {
                                val url = pendingLinkUrl
                                pendingLinkUrl = null
                                if (url != null) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        BasicText("打开", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RAFT_INK))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3F3F46))
                            .clickable { pendingLinkUrl = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        BasicText("取消", style = TextStyle(fontSize = 11.sp, color = Color.White))
                    }
                }
            }
        }

        // 3. Bottom Raft Chat Composer (with Quick Suggestion Chips + Streaming/Oneshot Switch!)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RAFT_SURFACE)
                .border(width = 1.dp, color = RAFT_SEAM)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (targetMessageId != null) {
                val targetMsg = activeMessages.firstOrNull { it.id == targetMessageId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFFFEF9C3))
                        .border(1.dp, Color(0xFFFACC15), RoundedCornerShape(7.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "✎ 正在修改界面「${targetMsg?.surfaceId ?: targetMessageId}」（保留已填数据）",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RAFT_INK),
                    )
                    BasicText(
                        text = "取消 ×",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309)),
                        modifier = Modifier.clickable { targetMessageId = null },
                    )
                }
            }

            // 1-Tap Prompt Suggestion Chips
            val chipScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (targetMessageId != null) {
                    listOf(
                        "加一个加急开关和补充备注框",
                        "增加优先级标记并保留原有数据",
                    ).forEach { editIdea ->
                        SuggestionChip(label = "✨ $editIdea", enabled = !agentRunning) {
                            sendPrompt(editIdea)
                        }
                    }
                } else {
                    SuggestionChip(label = "📱 平板对比表格", enabled = !agentRunning) {
                        sendPrompt("对比一下 OPPO pad mini 和 华为 padmini 给我一个对比表格")
                    }
                    SproutPlaygroundCatalog.SAMPLES.forEach { s ->
                        SuggestionChip(label = s.chipLabel, enabled = !agentRunning) {
                            sendPrompt(s.defaultPrompt)
                        }
                    }
                }
            }

            // Text Input + Send / Stop Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp, max = 96.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFFF4F4F5))
                        .border(1.dp, Color(0xFFD4D4D8), RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draftText.isEmpty()) {
                        BasicText(
                            text = if (targetMessageId != null) {
                                "描述你想怎么改这个界面（或直接点上方提示词）…"
                            } else {
                                "发消息到 #$channelTitle 让 Agent 生成交互界面…"
                            },
                            style = TextStyle(fontSize = 13.sp, color = RAFT_MUTED),
                        )
                    }
                    BasicTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        textStyle = TextStyle(fontSize = 13.sp, color = RAFT_INK),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sprout-chat-composer-input"),
                    )
                }

                if (agentRunning) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFFDC2626))
                            .border(1.5.dp, RAFT_INK, RoundedCornerShape(9.dp))
                            .clickable { stopActiveTurn() }
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                            .testTag("sprout-chat-stop-btn"),
                    ) {
                        BasicText(
                            text = "停止",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White),
                        )
                    }
                } else {
                    val canSend = draftText.isNotBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (canSend) RAFT_YELLOW else Color(0xFFE4E4E7))
                            .border(1.5.dp, if (canSend) RAFT_INK else Color(0xFFD4D4D8), RoundedCornerShape(9.dp))
                            .clickable(enabled = canSend) { sendPrompt(draftText) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                            .testTag("sprout-chat-send-btn"),
                    ) {
                        BasicText(
                            text = "发送",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canSend) RAFT_INK else RAFT_MUTED,
                            ),
                        )
                    }
                }
            }

            // Composer Footer Toolbar: Streaming / Non-streaming Switch (1:1 with Web ChatApp!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val next = !streamingEnabled
                            streamingEnabled = next
                            prefs.edit().putBoolean(PREF_STREAMING, next).apply()
                        }
                        .padding(vertical = 2.dp)
                        .testTag("sprout-streaming-switch"),
                ) {
                    // Custom crisp Raft Toggle Switch
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(if (streamingEnabled) Color(0xFF16A34A) else Color(0xFFD4D4D8))
                            .border(1.dp, RAFT_INK, CircleShape)
                            .padding(horizontal = 2.dp),
                        contentAlignment = if (streamingEnabled) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                    BasicText(
                        text = if (streamingEnabled) {
                            "流式渲染：开启（边生成边长出界面）"
                        } else {
                            "流式渲染：关闭（生成完一次性渲染 1 帧）"
                        },
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RAFT_INK),
                    )
                }

                if (serverClient == null && localMessages.size > 2 && !agentRunning) {
                    BasicText(
                        text = "清空对话",
                        style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED),
                        modifier = Modifier.clickable {
                            stateHub.clearScope(activeScopeId)
                            val keepFirstTwo = localMessages.take(2)
                            localMessages.clear()
                            localMessages.addAll(keepFirstTwo)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerTimelinePane(
    titleBanner: String?,
    bannerColor: Color,
    messages: List<UiChatMessage>,
    engine: SproutEngine?,
    scopeId: String,
    viewerId: String,
    stateHub: LocalSharedStateHub,
    serverClient: SproutDemoServerClient?,
    nowMs: () -> Long,
    onLocalAction: (SharedSurfaceKey, OutboundEffect.ActionRequest) -> ActionResultReceipt,
    onSelectTargetMessage: (String) -> Unit,
    onOpenExternalLink: (String) -> Unit,
    onStopTurn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val tailProgressKey = messages.lastOrNull()?.let { "${it.id}:${it.text.length}:${it.status}:${it.sourceRevision}" } ?: ""

    LaunchedEffect(messages.size, tailProgressKey) {
        if (messages.isNotEmpty()) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    Column(modifier = modifier) {
        if (titleBanner != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                BasicText(
                    text = titleBanner,
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RAFT_INK),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RAFT_SURFACE)
                        .border(1.dp, RAFT_SEAM, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                ) {
                    BasicText(
                        text = "已默认直连 Claude Opus 真模型。在下方输入任意需求（例如：对比一下 OPPO pad mini 和 华为 padmini 给我一个对比表格），或点击快捷提示词，Agent 会流式生成可交互原生界面。",
                        style = TextStyle(fontSize = 13.sp, color = RAFT_MUTED),
                    )
                }
            }

            messages.forEach { msg ->
                key(msg.id, viewerId) {
                    ChatMessageRow(
                        msg = msg,
                        engine = engine,
                        scopeId = scopeId,
                        viewerId = viewerId,
                        stateHub = stateHub,
                        serverClient = serverClient,
                        nowMs = nowMs,
                        onLocalAction = onLocalAction,
                        onSelectTargetMessage = onSelectTargetMessage,
                        onOpenExternalLink = onOpenExternalLink,
                        onStopTurn = onStopTurn,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    msg: UiChatMessage,
    engine: SproutEngine?,
    scopeId: String,
    viewerId: String,
    stateHub: LocalSharedStateHub,
    serverClient: SproutDemoServerClient?,
    nowMs: () -> Long,
    onLocalAction: (SharedSurfaceKey, OutboundEffect.ActionRequest) -> ActionResultReceipt,
    onSelectTargetMessage: (String) -> Unit,
    onOpenExternalLink: (String) -> Unit,
    onStopTurn: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(if (msg.isUser) 15.dp else 8.dp))
                .background(if (msg.isUser) Color(0xFFE0E7FF) else RAFT_INK)
                .border(1.dp, RAFT_INK, RoundedCornerShape(if (msg.isUser) 15.dp else 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = if (msg.isUser) msg.authorName.take(1).uppercase() else "S",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (msg.isUser) Color(0xFF1E1B4B) else RAFT_YELLOW,
                ),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    text = msg.authorName,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
                )
                if (!msg.isUser) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(RAFT_YELLOW)
                            .border(0.8.dp, RAFT_INK, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        BasicText("AGENT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RAFT_INK))
                    }
                }
                BasicText(
                    text = formatShortClock(msg.atMs),
                    style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED, fontFamily = FontFamily.Monospace),
                )
                if (msg.targetMessageId != null) {
                    BasicText(
                        text = "· 修改界面",
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFFB45309)),
                    )
                }
            }

            if (msg.isUser) {
                BasicText(
                    text = msg.text,
                    style = TextStyle(fontSize = 14.sp, color = RAFT_INK),
                )
            } else {
                val parsed = remember(msg.text) { SproutFenceParser.parseWhole(msg.text) }
                val firstBlock = parsed.blocks.firstOrNull()
                val effectiveSurfaceId = msg.surfaceId ?: firstBlock?.id
                val effectiveSource = when {
                    msg.status == "done" && !msg.source.isNullOrBlank() -> msg.source
                    firstBlock != null -> firstBlock.source
                    else -> null
                }
                val isBlockComplete = msg.status == "done" || (firstBlock?.complete == true && msg.status != "running")

                if (parsed.leadingProse.isNotEmpty()) {
                    BasicText(
                        text = parsed.leadingProse,
                        style = TextStyle(fontSize = 13.sp, color = RAFT_INK),
                    )
                } else if (msg.status == "running" && effectiveSource == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText(
                            text = if (msg.delivery == "oneshot") {
                                "Sprout 正在完整生成界面（非流式：完成后一次性渲染）…"
                            } else {
                                "Sprout 正在思考并流式输出界面…"
                            },
                            style = TextStyle(fontSize = 12.sp, color = Color(0xFF2563EB)),
                        )
                        BasicText(
                            text = "[停止]",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)),
                            modifier = Modifier.clickable { onStopTurn() },
                        )
                    }
                }

                if (effectiveSurfaceId != null && effectiveSource != null && engine != null) {
                    ChatSurfaceBlock(
                        engine = engine,
                        scopeId = scopeId,
                        viewerId = viewerId,
                        messageId = msg.id,
                        surfaceId = effectiveSurfaceId,
                        surfaceGeneration = msg.surfaceGeneration,
                        source = effectiveSource,
                        sourceRevision = msg.sourceRevision,
                        isComplete = isBlockComplete,
                        delivery = msg.delivery,
                        messageStatus = msg.status,
                        stateHub = stateHub,
                        serverClient = serverClient,
                        nowMs = nowMs,
                        onLocalAction = onLocalAction,
                        onChangeThisInterface = { onSelectTargetMessage(msg.id) },
                        onOpenExternalLink = onOpenExternalLink,
                    )
                }

                if (parsed.trailingProse.isNotEmpty()) {
                    BasicText(
                        text = parsed.trailingProse,
                        style = TextStyle(fontSize = 13.sp, color = RAFT_INK),
                    )
                }

                if (msg.status == "cancelled") {
                    BasicText(
                        text = "已手动停止生成（上方为未完成的只读预览，不会保存状态）。",
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFFB45309)),
                    )
                } else if (msg.status == "failed") {
                    BasicText(
                        text = "生成中断：${msg.errorText ?: "未知错误"}",
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFFDC2626)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSurfaceBlock(
    engine: SproutEngine,
    scopeId: String,
    viewerId: String,
    messageId: String,
    surfaceId: String,
    surfaceGeneration: Long,
    source: String,
    sourceRevision: Long,
    isComplete: Boolean,
    delivery: String,
    messageStatus: String,
    stateHub: LocalSharedStateHub,
    serverClient: SproutDemoServerClient?,
    nowMs: () -> Long,
    onLocalAction: (SharedSurfaceKey, OutboundEffect.ActionRequest) -> ActionResultReceipt,
    onChangeThisInterface: () -> Unit,
    onOpenExternalLink: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val surfaceKey = remember(scopeId, surfaceId, surfaceGeneration) {
        SharedSurfaceKey(scopeId = scopeId, surfaceId = surfaceId, surfaceGeneration = surfaceGeneration)
    }
    val sessionInstanceId = remember(messageId, viewerId, surfaceId) {
        "sess-$surfaceId-$viewerId".take(60)
    }

    val surface = remember(engine, surfaceId, surfaceGeneration, viewerId) {
        val snap = stateHub.read(surfaceKey)
        runCatching {
            engine.createSurface(
                surfaceId = surfaceId,
                surfaceGeneration = surfaceGeneration,
                catalog = RAFT_UI_CATALOG,
                profile = ValidationProfile.PORTABLE_V1,
                initialState = snap?.state ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                initialStateRevision = snap?.revision ?: 0L,
            )
        }.getOrNull()
    }

    var commit by remember { mutableStateOf<FrameCommit?>(null) }
    var renderedFrameCount by remember { mutableIntStateOf(0) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var fedRev by remember { mutableLongStateOf(0L) }
    var fedChars by remember { mutableIntStateOf(0) }
    var finishedRev by remember { mutableLongStateOf(0L) }

    fun handleOutboundEffects(frameCommit: FrameCommit) {
        val s = surface ?: return
        if (frameCommit.effects.isEmpty()) return

        val initEff = frameCommit.effects.filterIsInstance<OutboundEffect.InitState>().firstOrNull()
        val proposeEff = frameCommit.effects.filterIsInstance<OutboundEffect.ProposeState>().lastOrNull()
        val actionEff = frameCommit.effects.filterIsInstance<OutboundEffect.ActionRequest>().firstOrNull()
        val navEff = frameCommit.effects.filterIsInstance<OutboundEffect.Navigate>().firstOrNull()

        if (navEff != null) {
            onOpenExternalLink(navEff.url)
        }

        val client = serverClient
        if (client != null) {
            coroutineScope.launch {
                if (initEff != null) {
                    val rcpt = client.proposeInit(viewerId, scopeId, initEff)
                    runCatching { s.applyStateReceipt(rcpt, nowMs()) }.onSuccess { updated ->
                        if (updated.changed || updated.frame.root.isNotEmpty()) commit = updated
                    }
                }
                if (actionEff != null) {
                    val (stateRcpt, actRcpt) = client.emitAction(
                        viewerId = viewerId,
                        scopeId = scopeId,
                        act = actionEff,
                        coalescedWrite = proposeEff,
                        sessionInstanceId = sessionInstanceId,
                        runtimeEpoch = frameCommit.frame.runtimeEpoch,
                        frameRevision = frameCommit.frame.frameRevision,
                    )
                    if (stateRcpt != null) {
                        runCatching { s.applyStateReceipt(stateRcpt, nowMs()) }.onSuccess { commit = it }
                    }
                    runCatching { s.applyActionResult(actRcpt, nowMs()) }.onSuccess { commit = it }
                } else if (proposeEff != null) {
                    val rcpt = client.proposeWrite(viewerId, scopeId, proposeEff)
                    runCatching { s.applyStateReceipt(rcpt, nowMs()) }.onSuccess { updated ->
                        commit = updated
                    }
                }
            }
        } else {
            // Local shared-state coordinator (syncs Viewer A & Viewer B immediately!)
            if (initEff != null) {
                val rcpt = stateHub.initIfAbsent(surfaceKey, initEff.operationId, initEff.defaults)
                runCatching { s.applyStateReceipt(rcpt, nowMs()) }.onSuccess { updated ->
                    commit = updated
                    handleOutboundEffects(updated)
                }
            }
            if (proposeEff != null) {
                val rcpt = stateHub.propose(surfaceKey, proposeEff.operationId, proposeEff.baseRevision, proposeEff.operations)
                runCatching { s.applyStateReceipt(rcpt, nowMs()) }.onSuccess { updated ->
                    commit = updated
                }
            }
            if (actionEff != null) {
                val actRcpt = onLocalAction(surfaceKey, actionEff)
                runCatching { s.applyActionResult(actRcpt, nowMs()) }.onSuccess { updated ->
                    commit = updated
                }
            }
        }
    }

    // Subscribe to peer viewer / server state pushes
    DisposableEffect(surface, surfaceKey) {
        val existing = stateHub.read(surfaceKey)
        if (existing != null && surface != null && existing.revision > 0L) {
            runCatching {
                surface.applyStateReceipt(
                    StateReceipt.Snapshot(
                        revision = existing.revision,
                        state = existing.state,
                        surfaceId = surfaceKey.surfaceId,
                        surfaceGeneration = surfaceKey.surfaceGeneration,
                    ),
                    nowMs(),
                )
            }.onSuccess { updated ->
                if (updated.frame.root.isNotEmpty()) commit = updated
            }
        }

        val unsubscribe = stateHub.subscribe { changedKey, record, _ ->
            if (changedKey == surfaceKey && surface != null) {
                coroutineScope.launch {
                    runCatching {
                        surface.applyStateReceipt(
                            StateReceipt.Snapshot(
                                revision = record.revision,
                                state = record.state,
                                surfaceId = surfaceKey.surfaceId,
                                surfaceGeneration = surfaceKey.surfaceGeneration,
                            ),
                            nowMs(),
                        )
                    }.onSuccess { updated ->
                        if (updated.frame.root.isNotEmpty()) commit = updated
                    }
                }
            }
        }
        onDispose {
            unsubscribe()
            runCatching { surface?.close() }
        }
    }

    // Drive incremental streaming (`appendSource` -> `finishSource`) OR atomic `setSource`
    LaunchedEffect(surface, sourceRevision, source, isComplete, messageStatus) {
        val s = surface ?: return@LaunchedEffect
        if (sourceRevision != fedRev) {
            fedRev = sourceRevision
            fedChars = 0
            finishedRev = 0L
        }

        if (!isComplete) {
            if (source.length > fedChars) {
                val delta = source.substring(fedChars)
                runCatching {
                    s.appendSource(sourceRevision, fedChars, delta, nowMs = nowMs())
                }.onSuccess { res ->
                    fedChars = source.length
                    if (res.frame.root.isNotEmpty()) {
                        commit = res
                        if (res.changed) renderedFrameCount += 1
                    }
                }
            }
        } else if (finishedRev != sourceRevision) {
            if (fedChars > 0 && delivery == "stream") {
                if (source.length > fedChars) {
                    val tail = source.substring(fedChars)
                    runCatching { s.appendSource(sourceRevision, fedChars, tail, nowMs = nowMs()) }
                    fedChars = source.length
                }
                runCatching { s.finishSource(sourceRevision, nowMs = nowMs()) }.onSuccess { fin ->
                    finishedRev = sourceRevision
                    commit = fin
                    renderedFrameCount += 1
                    handleOutboundEffects(fin)
                }
            } else {
                runCatching {
                    s.setSource(
                        revision = sourceRevision,
                        source = source,
                        nowMs = nowMs(),
                        finished = true,
                    )
                }.onSuccess { whole ->
                    fedChars = source.length
                    finishedRev = sourceRevision
                    commit = whole
                    renderedFrameCount = if (delivery == "oneshot" && sourceRevision == 1L) 1 else (renderedFrameCount + 1)
                    handleOutboundEffects(whole)
                }
            }
        }
    }

    // Sandbox timer & 60fps animation frame pump (`setInterval` / `setTimeout` / `requestAnimationFrame`)
    val activeCommit = commit
    LaunchedEffect(surface, activeCommit?.nextTimerDueMs, activeCommit?.hasAnimationFrame, isComplete) {
        val s = surface ?: return@LaunchedEffect
        val c = activeCommit ?: return@LaunchedEffect
        if (!isComplete) return@LaunchedEffect
        val dueMs = c.nextTimerDueMs
        if (c.hasAnimationFrame) {
            delay(16L)
            runCatching { s.tick(nowMs()) }.getOrNull()?.let { ticked ->
                commit = ticked
            }
        } else if (dueMs != null) {
            val waitMs = (dueMs - nowMs()).coerceIn(10L, 2_000L)
            delay(waitMs)
            runCatching { s.tick(nowMs()) }.getOrNull()?.let { ticked ->
                commit = ticked
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (activeCommit != null && activeCommit.frame.root.isNotEmpty()) {
            SproutHostFrame(
                frame = activeCommit.frame,
                registry = defaultSproutComposeRegistry,
                provenanceLabel = if (!isComplete) {
                    "流式预览中 · v$sourceRevision"
                } else {
                    "Sprout 原生界面 · #$surfaceId (v$sourceRevision)"
                },
                isStreaming = !isComplete,
                interactionEnabled = activeCommit.interactionEnabled && isComplete,
                onOpenLinkRequest = onOpenExternalLink,
                onEvent = { ev ->
                    val s = surface ?: return@SproutHostFrame
                    runCatching {
                        s.dispatch(
                            nodeId = ev.nodeId,
                            eventName = ev.eventName,
                            targetFrameRevision = ev.frameRevision,
                            targetRuntimeEpoch = ev.runtimeEpoch,
                            arguments = ev.arguments,
                            nowMs = nowMs(),
                        )
                    }.onSuccess { updated ->
                        commit = updated
                        renderedFrameCount += 1
                        handleOutboundEffects(updated)
                    }
                },
            )
        } else if (!isComplete) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(RAFT_SURFACE)
                    .border(1.dp, RAFT_SEAM, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                BasicText(
                    text = "正在编译首批流式 JSX 片段…",
                    style = TextStyle(fontSize = 12.sp, color = RAFT_MUTED),
                )
            }
        }

        // Action & Details bar beneath every generated interface (mirrors Web ChatBlock.tsx!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isComplete) {
                    BasicText(
                        text = "✎ 修改这个界面",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D4ED8)),
                        modifier = Modifier.clickable { onChangeThisInterface() },
                    )
                }
                BasicText(
                    text = (if (detailsExpanded) "▴ 收起详情" else "▾ 详情") +
                        " · ${if (delivery == "oneshot") "非流式" else "流式"} · ${renderedFrameCount.coerceAtLeast(1)} 帧",
                    style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.clickable { detailsExpanded = !detailsExpanded },
                )
            }

            if (activeCommit != null) {
                BasicText(
                    text = "state r${activeCommit.frame.stateRevision}",
                    style = TextStyle(fontSize = 10.sp, color = RAFT_MUTED, fontFamily = FontFamily.Monospace),
                )
            }
        }

        if (detailsExpanded && activeCommit != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF4F4F5))
                    .border(1.dp, RAFT_SEAM, RoundedCornerShape(8.dp))
                    .padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                BasicText(
                    text = "交付方式: ${if (delivery == "oneshot") "oneshot (整块一次性渲染)" else "stream (增量容错流式编译)"} · 累计渲染帧: $renderedFrameCount",
                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RAFT_INK),
                )
                BasicText(
                    text = "耗时: compile=${"%.2f".format(activeCommit.compileMs)}ms · exec=${"%.2f".format(activeCommit.executeMs)}ms · validate=${"%.2f".format(activeCommit.validateMs)}ms",
                    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RAFT_MUTED),
                )
                BasicText(
                    text = "表面身份: id=$surfaceId · sourceRev=$sourceRevision · frameRev=${activeCommit.frame.frameRevision} · stateRev=${activeCommit.frame.stateRevision} · ABI=${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}",
                    style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = RAFT_MUTED),
                )
                if (activeCommit.diagnostics.isNotEmpty()) {
                    BasicText(
                        text = "诊断: " + activeCommit.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
                        style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB45309)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionPanel(
    currentConfig: DemoServerConfig?,
    health: ServerHealthInfo?,
    notice: String?,
    onPickConfigFile: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onSwitchScope: (String) -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC))
            .border(width = 0.5.dp, color = RAFT_SEAM)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "连接真实模型服务端 (Sprout demo-server)",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
            )
            BasicText(
                text = "收起 ×",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RAFT_MUTED),
                modifier = Modifier.clickable { onClose() },
            )
        }

        if (currentConfig != null) {
            BasicText(
                text = "当前已连：${currentConfig.serverUrl} (${health?.model ?: health?.provider ?: "校验中"})",
                style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF15803D)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderPillButton(
                    label = "#generative-ui (与 Web 互通)",
                    active = currentConfig.scopeId == "channel-generative-ui",
                    onClick = { onSwitchScope("channel-generative-ui") },
                )
                HeaderPillButton(
                    label = "#android-playground (独立新频道)",
                    active = currentConfig.scopeId == "android-playground",
                    onClick = { onSwitchScope("android-playground") },
                )
            }
        } else {
            BasicText(
                text = "云手机下载目录已预置会话配置文件 sprout-live-server.json，点下方按钮一键导入即可直连真实 Claude 模型与 Web 端共享频道：",
                style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED),
            )
        }

        if (notice != null) {
            BasicText(
                text = notice,
                style = TextStyle(fontSize = 11.sp, color = Color(0xFFB45309)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(RAFT_YELLOW)
                    .border(1.2.dp, RAFT_INK, RoundedCornerShape(7.dp))
                    .clickable { onPickConfigFile() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                BasicText(
                    text = "📂 导入云手机预置配置",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(RAFT_SURFACE)
                    .border(1.dp, RAFT_SEAM, RoundedCornerShape(7.dp))
                    .clickable { onPasteFromClipboard() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                BasicText(
                    text = "📋 从剪贴板粘贴链接",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RAFT_INK),
                )
            }
            if (currentConfig != null && currentConfig.serverUrl != DEFAULT_CLOUD_GATEWAY_URL) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFFFEE2E2))
                        .clickable { onDisconnect() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    BasicText(
                        text = "恢复默认真模型网关",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF991B1B)),
                    )
                }
            }
        }
    }
}

/**
 * Mirrors Web `#/dev` Developer Tools: Sample library, Hostile sandbox defense tests,
 * live TSX source editor, Send whole vs Offline chunked stream replay.
 */
@Composable
private fun SproutDeveloperToolsScreen(
    engine: SproutEngine?,
    nowMs: () -> Long,
    onBackToChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var editorSource by remember { mutableStateOf(SproutPlaygroundCatalog.SAMPLES[0].source) }
    var selectedSampleTitle by remember { mutableStateOf(SproutPlaygroundCatalog.SAMPLES[0].title) }
    var expectedBanner by remember { mutableStateOf<String?>(null) }
    var chunkSize by remember { mutableIntStateOf(64) }
    var speedMs by remember { mutableLongStateOf(30L) }
    var sourceRev by remember { mutableLongStateOf(1L) }
    var isStreaming by remember { mutableStateOf(false) }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    var commit by remember { mutableStateOf<FrameCommit?>(null) }
    var frameCounter by remember { mutableIntStateOf(0) }

    val devSurface = remember(engine) {
        engine?.let {
            runCatching {
                it.createSurface(
                    surfaceId = "dev-workbench",
                    catalog = RAFT_UI_CATALOG,
                    profile = ValidationProfile.PORTABLE_V1,
                )
            }.getOrNull()
        }
    }

    DisposableEffect(devSurface) {
        onDispose {
            replayJob?.cancel()
            runCatching { devSurface?.close() }
        }
    }

    fun sendWhole(src: String) {
        val s = devSurface ?: return
        replayJob?.cancel()
        isStreaming = false
        val nextRev = sourceRev + 1L
        sourceRev = nextRev
        runCatching {
            s.setSource(revision = nextRev, source = src, nowMs = nowMs(), finished = true)
        }.onSuccess { res ->
            commit = res
            frameCounter = 1
        }
    }

    fun replayStream(src: String) {
        val s = devSurface ?: return
        replayJob?.cancel()
        val nextRev = sourceRev + 1L
        sourceRev = nextRev
        isStreaming = true
        frameCounter = 0
        replayJob = scope.launch {
            var off = 0
            while (off < src.length) {
                val end = (off + chunkSize).coerceAtMost(src.length)
                val piece = src.substring(off, end)
                runCatching { s.appendSource(nextRev, off, piece, nowMs = nowMs()) }.onSuccess { res ->
                    commit = res
                    if (res.changed) frameCounter += 1
                }
                off = end
                delay(speedMs)
            }
            runCatching { s.finishSource(nextRev, nowMs = nowMs()) }.onSuccess { fin ->
                commit = fin
                frameCounter += 1
                isStreaming = false
            }.onFailure {
                isStreaming = false
            }
        }
    }

    LaunchedEffect(devSurface) {
        if (devSurface != null) sendWhole(editorSource)
    }

    // Timer pump for Clock sample inside Developer Tools
    LaunchedEffect(devSurface, commit?.nextTimerDueMs, commit?.hasAnimationFrame, isStreaming) {
        val s = devSurface ?: return@LaunchedEffect
        val c = commit ?: return@LaunchedEffect
        if (isStreaming) return@LaunchedEffect
        val dueMs = c.nextTimerDueMs
        if (c.hasAnimationFrame) {
            delay(16L)
            runCatching { s.tick(nowMs()) }.getOrNull()?.let { commit = it }
        } else if (dueMs != null) {
            delay((dueMs - nowMs()).coerceIn(10L, 2_000L))
            runCatching { s.tick(nowMs()) }.getOrNull()?.let { commit = it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RAFT_CANVAS)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RAFT_INK)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "← 返回聊天频道 (#generative-ui)",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RAFT_YELLOW),
                modifier = Modifier.clickable { onBackToChat() },
            )
            BasicText(
                text = "开发工具 (#/dev)",
                style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = "1. 标准组件样例库",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SproutPlaygroundCatalog.SAMPLES.forEach { s ->
                    HeaderPillButton(
                        label = s.chipLabel,
                        active = selectedSampleTitle == s.title,
                        onClick = {
                            selectedSampleTitle = s.title
                            expectedBanner = s.note
                            editorSource = s.source
                            sendWhole(s.source)
                        },
                    )
                }
            }

            BasicText(
                text = "2. 恶意与失控代码沙箱防御样例 (Hostile Samples)",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SproutPlaygroundCatalog.HOSTILE_SAMPLES.forEach { h ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selectedSampleTitle == h.title) Color(0xFFFEE2E2) else RAFT_SURFACE)
                            .border(1.dp, if (selectedSampleTitle == h.title) Color(0xFFEF4444) else RAFT_SEAM, RoundedCornerShape(7.dp))
                            .clickable {
                                selectedSampleTitle = h.title
                                expectedBanner = h.expect
                                editorSource = h.source
                                sendWhole(h.source)
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        BasicText(
                            text = h.title,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RAFT_INK),
                        )
                    }
                }
            }

            if (expectedBanner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF3C7))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    BasicText(
                        text = "预期行为：$expectedBanner",
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFF78350F)),
                    )
                }
            }

            // Live Rendered Block + Diagnostics
            if (commit != null) {
                if (commit!!.frame.root.isNotEmpty()) {
                    SproutHostFrame(
                        frame = commit!!.frame,
                        registry = defaultSproutComposeRegistry,
                        provenanceLabel = "$selectedSampleTitle · $frameCounter 帧",
                        isStreaming = isStreaming,
                        interactionEnabled = commit!!.interactionEnabled && !isStreaming,
                        onEvent = { ev ->
                            val s = devSurface ?: return@SproutHostFrame
                            runCatching {
                                s.dispatch(
                                    nodeId = ev.nodeId,
                                    eventName = ev.eventName,
                                    targetFrameRevision = ev.frameRevision,
                                    targetRuntimeEpoch = ev.runtimeEpoch,
                                    arguments = ev.arguments,
                                    nowMs = nowMs(),
                                )
                            }.onSuccess {
                                commit = it
                                frameCounter += 1
                            }
                        },
                    )
                }
                if (commit!!.diagnostics.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BasicText(
                            text = "沙箱拦截与诊断输出 (status=${commit!!.status.wireName}, interactive=${commit!!.interactionEnabled}):",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RAFT_YELLOW, fontFamily = FontFamily.Monospace),
                        )
                        commit!!.diagnostics.forEach { d ->
                            BasicText(
                                text = "• [${d.code}] (${d.phase}): ${d.message}",
                                style = TextStyle(fontSize = 11.sp, color = Color(0xFFF8FAFC), fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }

            // Source Editor & Replay Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(RAFT_SURFACE)
                    .border(1.dp, RAFT_SEAM, RoundedCornerShape(9.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "TSX 源码 (${editorSource.length} chars)",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RAFT_INK),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeaderPillButton("整块发送", !isStreaming) { sendWhole(editorSource) }
                        HeaderPillButton("分片流式回放", isStreaming) { replayStream(editorSource) }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText("分片:", style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED))
                    listOf(16, 64, 256).forEach { sz ->
                        HeaderPillButton("${sz}字", chunkSize == sz) { chunkSize = sz }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    BasicText("速率:", style = TextStyle(fontSize = 11.sp, color = RAFT_MUTED))
                    listOf(5L to "快", 30L to "中", 150L to "慢").forEach { (ms, lbl) ->
                        HeaderPillButton(lbl, speedMs == ms) { speedMs = ms }
                    }
                }

                BasicTextField(
                    value = editorSource,
                    onValueChange = { editorSource = it },
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RAFT_INK),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp, max = 240.dp)
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                        .border(1.dp, RAFT_SEAM, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderPillButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) RAFT_YELLOW else RAFT_SURFACE)
            .border(1.dp, if (active) RAFT_INK else Color(0xFFD4D4D8), RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = RAFT_INK,
            ),
        )
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(RAFT_SURFACE)
            .border(1.dp, Color(0xFFD4D4D8), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) RAFT_INK else RAFT_MUTED,
            ),
        )
    }
}
