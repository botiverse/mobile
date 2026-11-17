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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val SPROUT_CLOUD_SHOWCASE_TSX = """
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
                  count={String(count)} · svg={svgHit} · note={note || "(empty)"}
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
              <TabsTab value="vector">SVG & Mask</TabsTab>
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
                      Tap Transformed (+1): {String(count)}
                    </Button>
                    <Badge variant="success">{liveSync ? "QuickJS ARM64" : "Paused"}</Badge>
                  </Row>
                </Box>

                <Input
                  label="Chinese IME & Preedit Input"
                  placeholder="Type or compose Chinese..."
                  value={note}
                  onChange={(e) => setNote(e.value)}
                />

                <Checkbox
                  label="Verify TalkBack Checkbox Role"
                  checked={agree}
                  onCheckedChange={(v) => setAgree(v)}
                />

                <Switch
                  label="Realtime State Sync"
                  checked={liveSync}
                  onCheckedChange={(v) => setLiveSync(v)}
                />
              </Stack>
            </TabsPanel>

            <TabsPanel value="vector">
              <Stack gap="sm">
                <Text size="small" tone="muted">
                  Hollow evenodd ring + cubic Bezier + luminance mask:
                </Text>
                <svg width={260} height={120} viewBox="0 0 260 120">
                  <defs>
                    <linearGradient id="ringGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stopColor="#2563eb" />
                      <stop offset="100%" stopColor="#7c3aed" />
                    </linearGradient>
                    <mask id="fadeMask" x={0} y={0} width={260} height={120}>
                      <rect x={0} y={0} width={260} height={120} fill="#ffffff" />
                      <circle cx={200} cy={60} r={18} fill="#333333" />
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
                    d="M 70 15 A 45 45 0 1 0 70 105 A 45 45 0 1 0 70 15 Z M 70 38 A 22 22 0 1 1 70 82 A 22 22 0 1 1 70 38 Z"
                    fill="url(#ringGrad)"
                    fillRule="evenodd"
                    onClick={() => setSvgHit("hollow-ring")}
                  />

                  <ellipse
                    cx={200}
                    cy={60}
                    rx={38}
                    ry={30}
                    fill="#059669"
                    mask="url(#fadeMask)"
                    onClick={() => setSvgHit("masked-ellipse")}
                  />
                </svg>
                <Badge variant="primary">Last SVG Hit: {svgHit}</Badge>
              </Stack>
            </TabsPanel>
          </Tabs>
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

    val hostView = LocalView.current
    var eventSeq by remember { mutableLongStateOf(0L) }
    var lastEventStampMs by remember { mutableLongStateOf(0L) }
    var imeStatus by remember { mutableStateOf("idle") }
    var bootError by remember { mutableStateOf<String?>(null) }

    val engine = remember {
        runCatching {
            SproutEngine.prepare(
                limits = RuntimeLimits(
                    compilerBudgetMs = 500L,
                    sourceUpdateBudgetMs = 250L,
                    dispatchBudgetMs = 100L,
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
            runCatching { surface?.close() }
            runCatching { engine?.close() }
        }
    }

    var commit by remember { mutableStateOf<FrameCommit?>(null) }

    LaunchedEffect(surface) {
        if (surface != null) {
            runCatching {
                surface.setSource(
                    revision = 1L,
                    source = SPROUT_CLOUD_SHOWCASE_TSX,
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
                text = "RAFT ALPHA · SPROUT CLOUD-PHONE VERIFY",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFACC15), fontFamily = FontFamily.Monospace),
            )
            BasicText(
                text = "ABI=$abi · SDK=${Build.VERSION.SDK_INT} · Model=${Build.MODEL}",
                style = TextStyle(fontSize = 11.sp, color = Color(0xFFE2E8F0), fontFamily = FontFamily.Monospace),
                modifier = Modifier.testTag("sprout-cloud-device-meta"),
            )
            BasicText(
                text = "Liveness: seq=$eventSeq · frameRev=${commit?.frame?.frameRevision ?: -1L} · t=${lastEventStampMs}ms · ime=$imeStatus",
                style = TextStyle(fontSize = 11.sp, color = Color(0xFF34D399), fontFamily = FontFamily.Monospace),
                modifier = Modifier.testTag("sprout-cloud-liveness-line"),
            )
        }

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
                BasicText("1. IME Preedit (zhongwen)", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E1B4B)))
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
                BasicText("2. IME Commit (中文已上屏)", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF14532D)))
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
                provenanceLabel = "Sprout GenUI · ARM64 QuickJS Sandbox",
                isStreaming = false,
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
