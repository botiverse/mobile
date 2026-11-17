package build.raft.app

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import build.raft.sprout.runtime.FrameCommit
import build.raft.sprout.runtime.RuntimeLimits
import build.raft.sprout.runtime.SproutEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val PRESET_CONTROLS_AND_SVG = """
export default function CloudPhoneShowcase() {
  const [tab, setTab] = useSharedState("tab", "controls");
  const [count, setCount] = useSharedState("count", 0);
  const [note, setNote] = useSharedState("note", "");
  const [agree, setAgree] = useSharedState("agree", false);
  const [liveSync, setLiveSync] = useSharedState("liveSync", true);
  const [svgHit, setSvgHit] = useSharedState("svgHit", "none");

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center" wrap={true}>
          <Row gap="sm" align="center">
            <Avatar name="Sprout SDK" size="md" type="agent" />
            <Stack gap="xs">
              <CardTitle><Heading level={2}>Sprout Native Android</Heading></CardTitle>
              <CardDescription>
                <Text size="small" tone="muted">
                  {"count=" + String(count) + " · svg=" + svgHit + " · note=" + (note || "(empty)")}
                </Text>
              </CardDescription>
            </Stack>
          </Row>
          <Badge variant="primary" appearance="solid">ABI v2</Badge>
        </Row>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Tabs value={tab} onValueChange={(v) => setTab(v)}>
            <TabsList variant="underline">
              <TabsTab value="controls">Controls & IME</TabsTab>
              <TabsTab value="vector">SVG & Gradients</TabsTab>
            </TabsList>

            <TabsPanel value="controls">
              <Stack gap="sm">
                <Box transform="translateX(8px) rotate(-2deg)">
                  <Row gap="sm" align="center" wrap={true}>
                    <Button
                      variant="primary"
                      size="md"
                      onClick={() => setCount(count + 1)}
                    >
                      {"Tap Transformed (+1): " + String(count)}
                    </Button>
                    <Badge variant="success">{liveSync ? "QuickJS ARM64" : "Paused"}</Badge>
                  </Row>
                </Box>

                <Input
                  label="Chinese IME & Preedit Input"
                  placeholder="Tap here to type or test IME..."
                  value={note}
                  onChange={(e) => setNote(e.value)}
                />

                <Checkbox
                  label="Accessibility Checkbox Role"
                  checked={agree}
                  onCheckedChange={(v) => setAgree(v)}
                />

                <Switch
                  label="Realtime Shared State Sync"
                  checked={liveSync}
                  onCheckedChange={(v) => setLiveSync(v)}
                />
              </Stack>
            </TabsPanel>

            <TabsPanel value="vector">
              <Stack gap="sm">
                <Text size="small" tone="muted">
                  Tap the hollow ring, center hole, radial badge, or masked ellipse:
                </Text>
                <svg width={260} height={120} viewBox="0 0 260 120">
                  <defs>
                    <linearGradient id="ringGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stopColor="#2563eb" />
                      <stop offset="100%" stopColor="#7c3aed" />
                    </linearGradient>
                    <radialGradient id="radBadge" cx="50%" cy="50%" r="50%">
                      <stop offset={0} stopColor="#34d399" />
                      <stop offset={1} stopColor="#047857" />
                    </radialGradient>
                    <mask id="fadeMask" x={0} y={0} width={260} height={120}>
                      <rect x={0} y={0} width={260} height={120} fill="#ffffff" />
                      <circle cx={205} cy={60} r={16} fill="#333333" />
                    </mask>
                  </defs>

                  <rect
                    x={4}
                    y={4}
                    width={252}
                    height={112}
                    rx={10}
                    fill="#f8fafc"
                    stroke="#cbd5e1"
                    onClick={() => setSvgHit("backdrop-hole")}
                  />

                  <path
                    d="M 62 16 A 44 44 0 1 0 62 104 A 44 44 0 1 0 62 16 Z M 62 38 A 22 22 0 1 1 62 82 A 22 22 0 1 1 62 38 Z"
                    fill="url(#ringGrad)"
                    fillRule="evenodd"
                    onClick={() => setSvgHit("hollow-ring")}
                  />

                  <circle
                    cx={136}
                    cy={60}
                    r={22}
                    fill="url(#radBadge)"
                    onClick={() => setSvgHit("radial-circle")}
                  />

                  <ellipse
                    cx={205}
                    cy={60}
                    rx={34}
                    ry={28}
                    fill="#059669"
                    mask="url(#fadeMask)"
                    onClick={() => setSvgHit("masked-ellipse")}
                  />
                </svg>
                <Badge variant="primary">{"Last SVG Hit: " + svgHit}</Badge>
              </Stack>
            </TabsPanel>
          </Tabs>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

private val PRESET_POLL_DASHBOARD = """
export default function ArchitecturePollCard() {
  const [qjsVotes, setQjsVotes] = useState(18);
  const [webVotes, setWebVotes] = useState(4);
  const [sduiVotes, setSduiVotes] = useState(7);
  const [picked, setPicked] = useState("none");
  const total = qjsVotes + webVotes + sduiVotes;
  const pct = (n) => Math.round((n / Math.max(1, total)) * 100);

  return (
    <Card>
      <CardHeader>
        <Row justify="between" align="center">
          <CardTitle><Heading level={2}>Mobile GenUI Poll</Heading></CardTitle>
          <Badge variant="success">{String(total) + " votes"}</Badge>
        </Row>
        <CardDescription>
          <Text size="small" tone="muted">Tap any option to vote in real time inside QuickJS:</Text>
        </CardDescription>
      </CardHeader>
      <Divider />
      <CardContent>
        <Stack gap="md">
          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="semibold">1. QuickJS + Native Compose</Text>
              <Badge variant="primary">{String(pct(qjsVotes)) + "% (" + String(qjsVotes) + ")"}</Badge>
            </Row>
            <Progress value={pct(qjsVotes)} />
            <Button size="sm" variant={picked === "qjs" ? "primary" : "secondary"} onClick={() => { setQjsVotes(qjsVotes + 1); setPicked("qjs"); }}>
              Vote Native QuickJS (+1)
            </Button>
          </Stack>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="semibold">2. Embedded WebView</Text>
              <Badge variant="neutral">{String(pct(webVotes)) + "% (" + String(webVotes) + ")"}</Badge>
            </Row>
            <Progress value={pct(webVotes)} />
            <Button size="sm" variant={picked === "web" ? "primary" : "secondary"} onClick={() => { setWebVotes(webVotes + 1); setPicked("web"); }}>
              Vote WebView (+1)
            </Button>
          </Stack>

          <Stack gap="xs">
            <Row justify="between" align="center">
              <Text weight="semibold">3. Static JSON Templates</Text>
              <Badge variant="neutral">{String(pct(sduiVotes)) + "% (" + String(sduiVotes) + ")"}</Badge>
            </Row>
            <Progress value={pct(sduiVotes)} />
            <Button size="sm" variant={picked === "sdui" ? "primary" : "secondary"} onClick={() => { setSduiVotes(sduiVotes + 1); setPicked("sdui"); }}>
              Vote Static JSON (+1)
            </Button>
          </Stack>

          <Callout variant="info" title="Zero WebView Overhead">
            <Text size="small">{"Current pick: " + picked + " — rendered 100% as native Android Compose nodes."}</Text>
          </Callout>
        </Stack>
      </CardContent>
    </Card>
  );
}
""".trimIndent()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SproutCloudPhoneVerificationScreen(
    imeTick: Int,
    imeOp: Pair<String, String>?,
) {
    val clockOrigin = remember { TimeSource.Monotonic.markNow() }
    fun nowMs(): Long = clockOrigin.elapsedNow().toLong(DurationUnit.MILLISECONDS)

    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    var eventSeq by remember { mutableLongStateOf(0L) }
    var lastEventStampMs by remember { mutableLongStateOf(0L) }
    var imeStatus by remember { mutableStateOf("idle") }
    var bootError by remember { mutableStateOf<String?>(null) }
    var sourceRev by remember { mutableLongStateOf(1L) }
    var activePreset by remember { mutableIntStateOf(0) }
    var isStreamingPreview by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editableSource by remember { mutableStateOf(PRESET_CONTROLS_AND_SVG) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    val engine = remember {
        runCatching {
            SproutEngine.prepare(
                limits = RuntimeLimits(
                    compilerBudgetMs = 600L,
                    sourceUpdateBudgetMs = 300L,
                    dispatchBudgetMs = 120L,
                    tickBudgetMs = 50L,
                ),
            )
        }.onFailure { err ->
            bootError = "${err::class.simpleName}: ${err.message}"
        }.getOrNull()
    }

    val surface = remember(engine) {
        engine?.let { eng ->
            runCatching {
                eng.createSurface(
                    surfaceId = "alpha-cloud-verify",
                    catalog = RAFT_UI_CATALOG,
                    profile = ValidationProfile.PORTABLE_V1,
                )
            }.onFailure { err ->
                bootError = "${err::class.simpleName}: ${err.message}"
            }.getOrNull()
        }
    }

    DisposableEffect(surface, engine) {
        onDispose {
            streamJob?.cancel()
            runCatching { surface?.close() }
            runCatching { engine?.close() }
        }
    }

    var commit by remember { mutableStateOf<FrameCommit?>(null) }

    fun loadWholeSource(src: String, presetIndex: Int) {
        val s = surface ?: return
        streamJob?.cancel()
        isStreamingPreview = false
        activePreset = presetIndex
        editableSource = src
        bootError = null
        val nextRev = sourceRev + 1L
        sourceRev = nextRev
        runCatching {
            s.setSource(
                revision = nextRev,
                source = src,
                nowMs = nowMs(),
                finished = true,
            )
        }.onSuccess { res ->
            commit = res
            eventSeq += 1L
            lastEventStampMs = nowMs()
        }.onFailure { err ->
            bootError = "${err::class.simpleName}: ${err.message}"
        }
    }

    fun runStreamingDemo(src: String) {
        val s = surface ?: return
        streamJob?.cancel()
        activePreset = 2
        editableSource = src
        bootError = null
        val nextRev = sourceRev + 1L
        sourceRev = nextRev
        isStreamingPreview = true
        streamJob = scope.launch {
            var offset = 0
            val chunkSize = 72
            while (offset < src.length) {
                val end = (offset + chunkSize).coerceAtMost(src.length)
                val chunk = src.substring(offset, end)
                runCatching {
                    s.appendSource(nextRev, offset, chunk, nowMs = nowMs())
                }.onSuccess { res ->
                    if (res.frame.root.isNotEmpty()) {
                        commit = res
                    }
                    eventSeq += 1L
                    lastEventStampMs = nowMs()
                }
                offset = end
                delay(45L)
            }
            runCatching {
                s.finishSource(nextRev, nowMs = nowMs())
            }.onSuccess { fin ->
                commit = fin
                isStreamingPreview = false
                eventSeq += 1L
                lastEventStampMs = nowMs()
            }.onFailure { err ->
                isStreamingPreview = false
                bootError = "${err::class.simpleName}: ${err.message}"
            }
        }
    }

    LaunchedEffect(surface) {
        if (surface != null) {
            runCatching {
                surface.setSource(
                    revision = 1L,
                    source = PRESET_CONTROLS_AND_SVG,
                    nowMs = nowMs(),
                    finished = true,
                )
            }.onSuccess { initial ->
                commit = initial
                lastEventStampMs = nowMs()
            }.onFailure { err ->
                bootError = "${err::class.simpleName}: ${err.message}"
            }
        }
    }

    var activeIc by remember { mutableStateOf<InputConnection?>(null) }

    fun driveInputConnection(op: String, text: String) {
        if (op == "reset_ic") {
            activeIc = null
        }
        val target = hostView.findFocus() ?: hostView
        val ic = activeIc ?: target.onCreateInputConnection(EditorInfo())?.also { activeIc = it }
        if (ic == null) {
            imeStatus = "no_input_connection(focus_input_first)"
        } else {
            when (op) {
                "composing" -> {
                    val ok = ic.setComposingText(text, 1)
                    imeStatus = "composing($text)=$ok"
                }
                "commit" -> {
                    val ok = ic.commitText(text, 1)
                    imeStatus = "committed($text)=$ok"
                }
                "finish" -> {
                    val ok = ic.finishComposingText()
                    imeStatus = "finished=$ok"
                }
                "reset_ic" -> {
                    imeStatus = "ic_rebound=true"
                }
            }
        }
        eventSeq += 1L
        lastEventStampMs = nowMs()
    }

    LaunchedEffect(imeTick) {
        if (imeTick > 0 && imeOp != null) {
            driveInputConnection(imeOp.first, imeOp.second)
        }
    }

    val abi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { testTagsAsResourceId = true }
            .verticalScroll(scroll)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F172A))
                .padding(12.dp)
                .testTag("sprout-cloud-liveness-card"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(
                text = "SPROUT GENUI · NATIVE ANDROID PLAYGROUND",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFACC15), fontFamily = FontFamily.Monospace),
            )
            BasicText(
                text = "ABI=$abi · SDK=${Build.VERSION.SDK_INT} · Model=${Build.MODEL}",
                style = TextStyle(fontSize = 11.sp, color = Color(0xFFE2E8F0), fontFamily = FontFamily.Monospace),
                modifier = Modifier.testTag("sprout-cloud-device-meta"),
            )
            BasicText(
                text = "Liveness: seq=$eventSeq · frameRev=${commit?.frame?.frameRevision ?: -1L} · compile=${"%.1f".format(commit?.compileMs ?: 0.0)}ms · exec=${"%.1f".format(commit?.executeMs ?: 0.0)}ms",
                style = TextStyle(fontSize = 11.sp, color = Color(0xFF34D399), fontFamily = FontFamily.Monospace),
                modifier = Modifier.testTag("sprout-cloud-liveness-line"),
            )
        }

        // Scene Preset Switcher + Live TSX Editor Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            @Composable
            fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1D4ED8) else Color(0xFFE2E8F0))
                        .clickable { onClick() }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    BasicText(
                        text = label,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color.White else Color(0xFF1E293B),
                        ),
                    )
                }
            }

            PresetChip("1. 控件 & SVG", activePreset == 0) {
                loadWholeSource(PRESET_CONTROLS_AND_SVG, 0)
            }
            PresetChip("2. 实时投票", activePreset == 1) {
                loadWholeSource(PRESET_POLL_DASHBOARD, 1)
            }
            PresetChip("3. 流式生成演示", activePreset == 2) {
                runStreamingDemo(PRESET_POLL_DASHBOARD)
            }
            PresetChip(if (editorOpen) "收起源码" else "改 TSX", editorOpen) {
                editorOpen = !editorOpen
            }
        }

        if (editorOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        text = "Live React TSX (QuickJS Sandbox)",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF059669))
                            .clickable { loadWholeSource(editableSource, activePreset) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        BasicText(
                            text = "立即编译重绘",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                    }
                }
                BasicTextField(
                    value = editableSource,
                    onValueChange = { editableSource = it },
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF0F172A)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                )
            }
        }

        if (activePreset == 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE0E7FF))
                        .border(1.dp, Color(0xFF818CF8), RoundedCornerShape(6.dp))
                        .clickable { driveInputConnection("composing", "zhongwen") }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("sprout-ime-btn-composing"),
                ) {
                    BasicText("IME 拼音预编辑 (zhongwen)", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E1B4B)))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFDCFCE7))
                        .border(1.dp, Color(0xFF4ADE80), RoundedCornerShape(6.dp))
                        .clickable { driveInputConnection("commit", "中文已上屏") }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("sprout-ime-btn-commit"),
                ) {
                    BasicText("IME 确认上屏 (中文已上屏)", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF14532D)))
                }
            }
        }

        if (bootError != null) {
            BasicText(
                text = "BOOT ERROR: $bootError",
                style = TextStyle(fontSize = 13.sp, color = Color(0xFFDC2626)),
                modifier = Modifier.testTag("sprout-cloud-boot-error"),
            )
        } else if (commit != null && surface != null) {
            SproutHostFrame(
                frame = commit!!.frame,
                registry = defaultSproutComposeRegistry,
                provenanceLabel = if (isStreamingPreview) "Sprout GenUI · Streaming..." else "Sprout GenUI · ARM64 QuickJS Sandbox",
                isStreaming = isStreamingPreview,
                interactionEnabled = commit!!.interactionEnabled,
                onEvent = { ev ->
                    runCatching {
                        surface.dispatch(
                            nodeId = ev.nodeId,
                            eventName = ev.eventName,
                            targetFrameRevision = ev.frameRevision,
                            targetRuntimeEpoch = ev.runtimeEpoch,
                            arguments = ev.arguments,
                            nowMs = nowMs(),
                        )
                    }.onSuccess { updated ->
                        commit = updated
                        eventSeq += 1L
                        lastEventStampMs = nowMs()
                    }
                },
            )
        }
    }
}
