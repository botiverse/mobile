package build.raft.sprout.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.sprout.protocol.DiagnosticOrigin
import build.raft.sprout.protocol.DroppedOperationReason
import build.raft.sprout.protocol.NOTICE_TYPE
import build.raft.sprout.protocol.NodeKind
import build.raft.sprout.protocol.PortableV6Spec
import build.raft.sprout.protocol.ProtocolDiagnostic
import build.raft.sprout.protocol.RAFT_UI_SVG_EXTRAS
import build.raft.sprout.protocol.SurfaceFrame
import build.raft.sprout.protocol.unicodeCodePointCount
import build.raft.sprout.protocol.TreeNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Unforgeable host-minted user-gesture token (v5 §6 & §7).
 * Minted ONLY inside authentic Compose pointer/keyboard/input callbacks; guest JS cannot construct one.
 */
class SproutGestureToken internal constructor(
    val surfaceId: String?,
    val runtimeEpoch: Long,
    val frameRevision: Long,
    val nodeId: String,
    val eventName: String,
)

data class SproutDispatchedEvent(
    val surfaceId: String?,
    val runtimeEpoch: Long,
    val frameRevision: Long,
    val nodeId: String,
    val eventName: String,
    val arguments: JsonArray,
    val gestureToken: SproutGestureToken,
)

internal val LocalSproutTextStyle = compositionLocalOf {
    TextStyle(fontSize = 14.sp, color = Color(0xFF1F2933))
}

private val LocalSproutInteractionEnabled = compositionLocalOf { true }
private val LocalSproutFrameSvgLedger = compositionLocalOf<FrameSvgGeometryLedger?> { null }
internal val LocalSproutFrameSvgLedgerObserver = compositionLocalOf<((FrameSvgGeometryLedger) -> Unit)?> { null }

internal data class SproutTabsState(
    val activeValue: String,
    val onSelectTab: (String) -> Unit,
)

private val LocalSproutTabsState = compositionLocalOf<SproutTabsState?> { null }
private val LocalSproutTabsVariant = compositionLocalOf { "default" }
private val LocalSproutTableCellWeight = compositionLocalOf<(@Composable (content: @Composable () -> Unit) -> Unit)?> { null }

/**
 * Neutral user-facing notice text for a withdrawn local change (`DroppedOperationReason.describeForUser`).
 */
fun sproutWithdrawnNoticeText(reasonWire: String?): String =
    DroppedOperationReason.describeForUser(reasonWire)

@Composable
fun SproutWithdrawnNotice(
    reasonWire: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
            .background(Color(0xFFFFFBEB))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("sprout-withdrawn-notice"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = sproutWithdrawnNoticeText(reasonWire),
            style = TextStyle(fontSize = 12.sp, color = Color(0xFF92400E)),
        )
    }
}

class SproutComponentScope(
    val element: TreeNode.Element,
    val frame: SurfaceFrame,
    val interactionEnabled: Boolean,
    private val onEvent: (SproutDispatchedEvent) -> Unit,
    val onOpenLinkRequest: ((String) -> Unit)?,
    val onDiagnostic: (ProtocolDiagnostic) -> Unit = {},
    val renderChildren: @Composable () -> Unit,
    val renderNode: @Composable (TreeNode) -> Unit = {},
) {
    val props: JsonObject get() = element.props

    fun stringProp(name: String, default: String = ""): String =
        (props[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    fun boolProp(name: String, default: Boolean = false): Boolean =
        (props[name] as? JsonPrimitive)?.booleanOrNull ?: default

    fun doubleProp(name: String, default: Double = 0.0): Double =
        (props[name] as? JsonPrimitive)?.doubleOrNull ?: default

    fun textChildrenJoined(): String =
        element.children.filterIsInstance<TreeNode.Text>().joinToString("") { it.text }

    fun hasEvent(eventName: String): Boolean = interactionEnabled && eventName in element.events

    fun emitUserEvent(eventName: String, arguments: JsonArray = JsonArray(emptyList())) {
        if (!interactionEnabled || eventName !in element.events) return
        val token = SproutGestureToken(
            surfaceId = frame.surfaceId,
            runtimeEpoch = frame.runtimeEpoch,
            frameRevision = frame.frameRevision,
            nodeId = element.id,
            eventName = eventName,
        )
        onEvent(
            SproutDispatchedEvent(
                surfaceId = frame.surfaceId,
                runtimeEpoch = frame.runtimeEpoch,
                frameRevision = frame.frameRevision,
                nodeId = element.id,
                eventName = eventName,
                arguments = arguments,
                gestureToken = token,
            ),
        )
    }
}

typealias SproutComposableRenderer = @Composable SproutComponentScope.() -> Unit

class SproutComponentRegistry(
    val entries: Map<String, SproutComposableRenderer>,
) {
    operator fun get(type: String): SproutComposableRenderer? = entries[type]

    operator fun plus(other: SproutComponentRegistry): SproutComponentRegistry =
        SproutComponentRegistry(entries + other.entries)
}

class SproutRegistryBuilder {
    private val map = LinkedHashMap<String, SproutComposableRenderer>()

    fun component(name: String, render: SproutComposableRenderer) {
        require(name !in map) { "Duplicate component '$name' in SproutComponentRegistry" }
        map[name] = render
    }

    internal fun build(): SproutComponentRegistry = SproutComponentRegistry(map.toMap())
}

fun sproutComponentRegistry(block: SproutRegistryBuilder.() -> Unit): SproutComponentRegistry =
    SproutRegistryBuilder().apply(block).build()

/**
 * Trusted host provenance frame (v5 §7 & §9):
 * - Enforces `Modifier.clipToBounds()` layout containment so guest trees cannot overflow or spoof surrounding chat UI.
 * - Renders host-owned provenance header & streaming indicator outside guest control.
 */
@Composable
fun SproutHostFrame(
    frame: SurfaceFrame,
    registry: SproutComponentRegistry = defaultSproutComposeRegistry,
    provenanceLabel: String = "Interactive UI",
    isStreaming: Boolean = false,
    interactionEnabled: Boolean = true,
    maxContentHeight: Dp? = null,
    onEvent: (SproutDispatchedEvent) -> Unit = {},
    onOpenLinkRequest: ((String) -> Unit)? = null,
    onDiagnostic: (ProtocolDiagnostic) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFD6DCE5), RoundedCornerShape(12.dp))
            .background(Color(0xFFFFFFFF))
            .clipToBounds()
            .testTag("sprout-host-frame:${frame.surfaceId ?: "anonymous"}"),
    ) {
        // Trusted host provenance bar (guest cannot hide or overwrite)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F7FA))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = provenanceLabel,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF52606D)),
                modifier = Modifier.testTag("sprout-provenance-label"),
            )
            if (!interactionEnabled) {
                BasicText(
                    text = "Read-only (paused)",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB45309)),
                    modifier = Modifier.testTag("sprout-paused-badge"),
                )
            } else if (isStreaming) {
                BasicText(
                    text = "Generating preview (local state only)…",
                    style = TextStyle(fontSize = 11.sp, color = Color(0xFF2563EB)),
                    modifier = Modifier.testTag("sprout-streaming-badge"),
                )
            }
        }

        val contentModifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (maxContentHeight != null) {
                    base.heightIn(max = maxContentHeight).verticalScroll(scrollState)
                } else {
                    base
                }
            }
            .clipToBounds()
            .padding(12.dp)

        Box(modifier = contentModifier) {
            SproutComposeRenderer(
                frame = frame,
                registry = registry,
                interactionEnabled = interactionEnabled,
                onEvent = onEvent,
                onOpenLinkRequest = onOpenLinkRequest,
                onDiagnostic = onDiagnostic,
            )
        }
    }
}

/**
 * Core Compose renderer over a validated `SurfaceFrame` (`compose.runtime` + `compose.foundation` + `compose.ui`, zero Material coupling).
 */
@Composable
fun SproutComposeRenderer(
    frame: SurfaceFrame,
    registry: SproutComponentRegistry = defaultSproutComposeRegistry,
    interactionEnabled: Boolean = true,
    onEvent: (SproutDispatchedEvent) -> Unit = {},
    onOpenLinkRequest: ((String) -> Unit)? = null,
    onDiagnostic: (ProtocolDiagnostic) -> Unit = {},
) {
    val gatedOnEvent: (SproutDispatchedEvent) -> Unit = { ev ->
        if (interactionEnabled) onEvent(ev)
    }
    val frameSvgLedger = remember(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, frame.root) {
        FrameSvgGeometryLedger()
    }
    LocalSproutFrameSvgLedgerObserver.current?.invoke(frameSvgLedger)
    CompositionLocalProvider(
        LocalSproutInteractionEnabled provides interactionEnabled,
        LocalSproutFrameSvgLedger provides frameSvgLedger,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RenderNodeList(
                nodes = frame.root,
                frame = frame,
                registry = registry,
                onEvent = gatedOnEvent,
                onOpenLinkRequest = if (interactionEnabled) onOpenLinkRequest else null,
                onDiagnostic = onDiagnostic,
            )
        }
    }
}

private fun isInlineTextRunNode(node: TreeNode): Boolean = when (node) {
    is TreeNode.Text -> true
    is TreeNode.Element -> node.kind == NodeKind.PRIMITIVE && node.type == "span" && "click" !in node.events
}

@Composable
private fun RenderNodeList(
    nodes: List<TreeNode>,
    frame: SurfaceFrame,
    registry: SproutComponentRegistry,
    onEvent: (SproutDispatchedEvent) -> Unit,
    onOpenLinkRequest: ((String) -> Unit)?,
    onDiagnostic: (ProtocolDiagnostic) -> Unit,
) {
    val inheritedTextStyle = LocalSproutTextStyle.current
    var i = 0
    while (i < nodes.size) {
        val node = nodes[i]
        if (isInlineTextRunNode(node)) {
            val runStart = i
            val runNodes = ArrayList<TreeNode>()
            while (i < nodes.size && isInlineTextRunNode(nodes[i])) {
                runNodes.add(nodes[i])
                i++
            }
            val annotated = buildInlineAnnotatedRun(runNodes, inheritedTextStyle)
            if (annotated.isNotEmpty()) {
                key("inline-run:$runStart:${annotated.text.hashCode()}") {
                    BasicText(
                        text = annotated,
                        style = inheritedTextStyle,
                    )
                }
            }
            continue
        }
        if (node is TreeNode.Element) {
            key(node.id) {
                RenderElement(
                    element = node,
                    frame = frame,
                    registry = registry,
                    onEvent = onEvent,
                    onOpenLinkRequest = onOpenLinkRequest,
                    onDiagnostic = onDiagnostic,
                )
            }
        }
        i++
    }
}

private fun buildInlineAnnotatedRun(nodes: List<TreeNode>, baseStyle: TextStyle) = buildAnnotatedString {
    fun appendInline(list: List<TreeNode>, activeColor: Color) {
        for (item in list) {
            when (item) {
                is TreeNode.Text -> append(item.text)
                is TreeNode.Element -> {
                    val hidden = (item.props["hidden"] as? JsonPrimitive)?.booleanOrNull == true
                    val styleObj = item.props["style"] as? JsonObject
                    val displayNone = (styleObj?.get("display") as? JsonPrimitive)?.content == "none"
                    if (hidden || displayNone) continue
                    val spanColor = parsePaintColor((styleObj?.get("color") as? JsonPrimitive)?.content, activeColor) ?: activeColor
                    val spanStyle = extractSpanStyle(styleObj, activeColor)
                    if (spanStyle != null) {
                        withStyle(spanStyle) {
                            appendInline(item.children, spanColor)
                        }
                    } else {
                        appendInline(item.children, spanColor)
                    }
                }
            }
        }
    }
    appendInline(nodes, baseStyle.color)
}

private fun extractSpanStyle(styleObj: JsonObject?, currentColor: Color): SpanStyle? {
    if (styleObj == null || styleObj.isEmpty()) return null
    val color = parsePaintColor((styleObj["color"] as? JsonPrimitive)?.content, currentColor)
    val fontSize = (styleObj["fontSize"] as? JsonPrimitive)?.doubleOrNull?.sp
    val fontWeight = parseFontWeight(styleObj["fontWeight"])
    val fontStyle = when ((styleObj["fontStyle"] as? JsonPrimitive)?.content) {
        "italic" -> FontStyle.Italic
        "normal" -> FontStyle.Normal
        else -> null
    }
    val textDeco = when ((styleObj["textDecoration"] as? JsonPrimitive)?.content) {
        "underline" -> TextDecoration.Underline
        "line-through" -> TextDecoration.LineThrough
        "none" -> TextDecoration.None
        else -> null
    }
    return SpanStyle(
        color = color ?: Color.Unspecified,
        fontSize = fontSize ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDeco,
    )
}

private fun mergeBoxTextStyle(base: TextStyle, styleObj: JsonObject?): TextStyle {
    if (styleObj == null || styleObj.isEmpty()) return base
    val nextColor = parsePaintColor((styleObj["color"] as? JsonPrimitive)?.content, base.color) ?: base.color
    val fontSize = (styleObj["fontSize"] as? JsonPrimitive)?.doubleOrNull?.sp ?: base.fontSize
    val lineHeight = (styleObj["lineHeight"] as? JsonPrimitive)?.doubleOrNull?.sp ?: base.lineHeight
    val fontWeight = parseFontWeight(styleObj["fontWeight"]) ?: base.fontWeight
    val fontStyle = when ((styleObj["fontStyle"] as? JsonPrimitive)?.content) {
        "italic" -> FontStyle.Italic
        "normal" -> FontStyle.Normal
        else -> base.fontStyle
    }
    val textDeco = when ((styleObj["textDecoration"] as? JsonPrimitive)?.content) {
        "underline" -> TextDecoration.Underline
        "line-through" -> TextDecoration.LineThrough
        "none" -> TextDecoration.None
        else -> base.textDecoration
    }
    val textAlign = when ((styleObj["textAlign"] as? JsonPrimitive)?.content) {
        "start" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> base.textAlign
    }
    return base.copy(
        color = nextColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDeco,
        textAlign = textAlign,
    )
}

private fun parseFontWeight(el: JsonElement?): FontWeight? {
    val prim = el as? JsonPrimitive ?: return null
    if (prim.isString) {
        return when (prim.content) {
            "bold" -> FontWeight.Bold
            "normal" -> FontWeight.Normal
            else -> prim.content.toIntOrNull()?.takeIf { it in 100..900 }?.let { FontWeight(it) }
        }
    }
    return prim.intOrNull?.takeIf { it in 100..900 }?.let { FontWeight(it) }
}

@Composable
private fun RenderElement(
    element: TreeNode.Element,
    frame: SurfaceFrame,
    registry: SproutComponentRegistry,
    onEvent: (SproutDispatchedEvent) -> Unit,
    onOpenLinkRequest: ((String) -> Unit)?,
    onDiagnostic: (ProtocolDiagnostic) -> Unit,
) {
    // 1. Validator/session reserved `$notice` node (v5 §14 Decision 11)
    if (element.type == NOTICE_TYPE) {
        val code = (element.props["code"] as? JsonPrimitive)?.content ?: "UNSUPPORTED_CAPABILITY"
        val fallback = element.children.filterIsInstance<TreeNode.Text>().joinToString("") { it.text }
            .ifBlank { "Unsupported UI element ($code)" }
        NoticeFallbackBox(nodeId = element.id, code = code, message = fallback)
        return
    }

    when (element.kind) {
        NodeKind.HTML -> {
            // Kotlin native renderer does NOT guess or silently drop Web-only `h` elements (v5 §14 Decision 2 & 11)!
            val msg = "<${element.type}> requires Web HTML extension (web-open/1) and is not supported on native Compose"
            LaunchedEffect(element.id, element.type, frame.frameRevision) {
                onDiagnostic(
                    ProtocolDiagnostic(
                        code = "UNSUPPORTED_CAPABILITY",
                        phase = "render",
                        message = msg,
                        nodeId = element.id,
                        origin = DiagnosticOrigin.HOST,
                    ),
                )
            }
            NoticeFallbackBox(nodeId = element.id, code = "UNSUPPORTED_CAPABILITY", message = msg)
        }

        NodeKind.PRIMITIVE -> {
            RenderPortablePrimitive(
                element = element,
                frame = frame,
                registry = registry,
                onEvent = onEvent,
                onOpenLinkRequest = onOpenLinkRequest,
                onDiagnostic = onDiagnostic,
            )
        }

        NodeKind.COMPONENT -> {
            val comp = registry[element.type]
            if (comp == null) {
                val msg = "Component <${element.type}> is not implemented in this host registry"
                LaunchedEffect(element.id, element.type, frame.frameRevision) {
                    onDiagnostic(
                        ProtocolDiagnostic(
                            code = "UNSUPPORTED_CAPABILITY",
                            phase = "render",
                            message = msg,
                            nodeId = element.id,
                            origin = DiagnosticOrigin.HOST,
                        ),
                    )
                }
                NoticeFallbackBox(nodeId = element.id, code = "UNSUPPORTED_CAPABILITY", message = msg)
                return
            }
            val scope = SproutComponentScope(
                element = element,
                frame = frame,
                interactionEnabled = LocalSproutInteractionEnabled.current,
                onEvent = onEvent,
                onOpenLinkRequest = onOpenLinkRequest,
                onDiagnostic = onDiagnostic,
                renderChildren = {
                    RenderNodeList(
                        nodes = element.children,
                        frame = frame,
                        registry = registry,
                        onEvent = onEvent,
                        onOpenLinkRequest = onOpenLinkRequest,
                        onDiagnostic = onDiagnostic,
                    )
                },
                renderNode = { singleNode ->
                    RenderNodeList(
                        nodes = listOf(singleNode),
                        frame = frame,
                        registry = registry,
                        onEvent = onEvent,
                        onOpenLinkRequest = onOpenLinkRequest,
                        onDiagnostic = onDiagnostic,
                    )
                },
            )
            comp.invoke(scope)
        }
    }
}

@Composable
private fun NoticeFallbackBox(nodeId: String, code: String, message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
            .background(Color(0xFFFFFBEB))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("sprout-notice:$nodeId"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "[$code]",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309)),
        )
        BasicText(
            text = message,
            style = TextStyle(fontSize = 12.sp, color = Color(0xFF92400E)),
        )
    }
}

/**
 * IME-safe controlled text field (v5 §7 & §12):
 * - Preserves active native IME `composition` (`value.composition != null`) and cursor selection across
 *   synchronous guest re-renders; only reconciles external controlled `value` when IME composition finishes!
 */
@Composable
fun ImeSafeTextField(
    controlledValue: String,
    nodeId: String,
    enabled: Boolean = true,
    placeholder: String = "",
    onValueCommitted: (String) -> Unit,
    /** Testing/platform hook allowing unit tests to inject raw `TextFieldValue` with active `composition` ranges. */
    externalValueState: androidx.compose.runtime.MutableState<TextFieldValue>? = null,
    inputType: String = "text",
    isControlled: Boolean = true,
) {
    val internalState = externalValueState ?: remember(nodeId) {
        mutableStateOf(TextFieldValue(text = controlledValue, selection = TextRange(controlledValue.length)))
    }
    var tfv by internalState

    // Sync guest controlled value ONLY when controlled and no active IME preedit composition is in flight!
    if (isControlled && tfv.composition == null && tfv.text != controlledValue) {
        val clampedCursor = tfv.selection.end.coerceIn(0, controlledValue.length)
        tfv = TextFieldValue(text = controlledValue, selection = TextRange(clampedCursor))
    }

    val visualTransformation = if (inputType == "password") {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }

    BasicTextField(
        value = tfv,
        enabled = enabled,
        visualTransformation = visualTransformation,
        onValueChange = { next ->
            tfv = next
            if (next.composition == null) {
                onValueCommitted(next.text)
            }
        },
        textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF111827)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
            .background(if (enabled) Color(0xFFFFFFFF) else Color(0xFFF1F5F9))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("sprout-input:$nodeId"),
        decorationBox = { innerTextField ->
            Box {
                if (tfv.text.isEmpty() && placeholder.isNotEmpty()) {
                    BasicText(placeholder, style = TextStyle(fontSize = 14.sp, color = Color(0xFF94A3B8)))
                }
                innerTextField()
            }
        },
    )
}

// ---- Portable Primitives (`portable-primitives/1`: div, span, svg, path, circle, rect, g, text, linearGradient, stop) ----

@Composable
private fun RenderPortablePrimitive(
    element: TreeNode.Element,
    frame: SurfaceFrame,
    registry: SproutComponentRegistry,
    onEvent: (SproutDispatchedEvent) -> Unit,
    onOpenLinkRequest: ((String) -> Unit)?,
    onDiagnostic: (ProtocolDiagnostic) -> Unit,
) {
    val interactionEnabled = LocalSproutInteractionEnabled.current
    when (element.type) {
        "div", "span" -> {
            val props = element.props
            if ((props["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return
            val styleObj = props["style"] as? JsonObject
            if ((styleObj?.get("display") as? JsonPrimitive)?.content == "none") return

            val ariaLabel = (props["ariaLabel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val inheritedTextStyle = LocalSproutTextStyle.current
            val mergedTextStyle = mergeBoxTextStyle(inheritedTextStyle, styleObj)

            val direction = (styleObj?.get("flexDirection") as? JsonPrimitive)?.content
                ?: (props["direction"] as? JsonPrimitive)?.content
                ?: if (element.type == "span") "row" else "column"
            val alignItems = (styleObj?.get("alignItems") as? JsonPrimitive)?.content ?: "stretch"
            val justifyContent = (styleObj?.get("justifyContent") as? JsonPrimitive)?.content ?: "start"

            val gapVal = (styleObj?.get("gap") as? JsonPrimitive)?.doubleOrNull
                ?: (props["gap"] as? JsonPrimitive)?.doubleOrNull
                ?: 0.0
            val gap = gapVal.dp

            val padAll = (styleObj?.get("padding") as? JsonPrimitive)?.doubleOrNull
                ?: (props["padding"] as? JsonPrimitive)?.doubleOrNull
                ?: 0.0
            val padHFallback = (props["paddingHorizontal"] as? JsonPrimitive)?.doubleOrNull ?: padAll
            val padVFallback = (props["paddingVertical"] as? JsonPrimitive)?.doubleOrNull ?: padAll
            val padLeft = ((styleObj?.get("paddingLeft") as? JsonPrimitive)?.doubleOrNull ?: padHFallback).dp
            val padRight = ((styleObj?.get("paddingRight") as? JsonPrimitive)?.doubleOrNull ?: padHFallback).dp
            val padTop = ((styleObj?.get("paddingTop") as? JsonPrimitive)?.doubleOrNull ?: padVFallback).dp
            val padBottom = ((styleObj?.get("paddingBottom") as? JsonPrimitive)?.doubleOrNull ?: padVFallback).dp

            val radius = (
                (styleObj?.get("borderRadius") as? JsonPrimitive)?.doubleOrNull
                    ?: (props["borderRadius"] as? JsonPrimitive)?.doubleOrNull
                    ?: 0.0
                ).dp
            val opacity = (
                (styleObj?.get("opacity") as? JsonPrimitive)?.doubleOrNull
                    ?: (props["opacity"] as? JsonPrimitive)?.doubleOrNull
                    ?: 1.0
                ).toFloat().coerceIn(0f, 1f)

            val bgRaw = (styleObj?.get("backgroundColor") as? JsonPrimitive)?.content
                ?: (props["background"] as? JsonPrimitive)?.content
            val borderColRaw = (styleObj?.get("borderColor") as? JsonPrimitive)?.content
                ?: (props["borderColor"] as? JsonPrimitive)?.content
            val bg = parsePaintColor(bgRaw, mergedTextStyle.color)
            val borderCol = parsePaintColor(borderColRaw, mergedTextStyle.color)
            val borderW = (
                (styleObj?.get("borderWidth") as? JsonPrimitive)?.doubleOrNull
                    ?: (props["borderWidth"] as? JsonPrimitive)?.doubleOrNull
                    ?: 0.0
                ).dp

            val widthDp = ((styleObj?.get("width") as? JsonPrimitive)?.doubleOrNull ?: (props["width"] as? JsonPrimitive)?.doubleOrNull)?.dp
            val heightDp = ((styleObj?.get("height") as? JsonPrimitive)?.doubleOrNull ?: (props["height"] as? JsonPrimitive)?.doubleOrNull)?.dp
            val minWidthDp = (styleObj?.get("minWidth") as? JsonPrimitive)?.doubleOrNull?.dp
            val maxWidthDp = (styleObj?.get("maxWidth") as? JsonPrimitive)?.doubleOrNull?.dp
            val minHeightDp = (styleObj?.get("minHeight") as? JsonPrimitive)?.doubleOrNull?.dp
            val maxHeightDp = (styleObj?.get("maxHeight") as? JsonPrimitive)?.doubleOrNull?.dp

            var mod: Modifier = Modifier.testTag("sprout-node:${element.id}").alpha(opacity)
            if (!ariaLabel.isNullOrBlank()) {
                mod = mod.semantics { contentDescription = ariaLabel }
            }
            if (widthDp != null) mod = mod.width(widthDp)
            if (heightDp != null) mod = mod.height(heightDp)
            if (minWidthDp != null || maxWidthDp != null) {
                mod = mod.widthIn(min = minWidthDp ?: Dp.Unspecified, max = maxWidthDp ?: Dp.Unspecified)
            }
            if (minHeightDp != null || maxHeightDp != null) {
                mod = mod.heightIn(min = minHeightDp ?: Dp.Unspecified, max = maxHeightDp ?: Dp.Unspecified)
            }
            if (radius > 0.dp) mod = mod.clip(RoundedCornerShape(radius))
            if (bg != null) mod = mod.background(bg)
            if (borderCol != null && borderW > 0.dp) mod = mod.border(borderW, borderCol, RoundedCornerShape(radius))
            if (interactionEnabled && "click" in element.events) {
                mod = mod.semantics { role = Role.Button }.clickable {
                    val token = SproutGestureToken(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, element.id, "click")
                    onEvent(SproutDispatchedEvent(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, element.id, "click", JsonArray(emptyList()), token))
                }
            }
            mod = mod.padding(start = padLeft, top = padTop, end = padRight, bottom = padBottom)

            CompositionLocalProvider(LocalSproutTextStyle provides mergedTextStyle) {
                if (direction == "row") {
                    val hArrange: Arrangement.Horizontal = when (justifyContent) {
                        "space-between" -> Arrangement.SpaceBetween
                        "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                        "end" -> Arrangement.spacedBy(gap, Alignment.End)
                        else -> Arrangement.spacedBy(gap, Alignment.Start)
                    }
                    val vAlign: Alignment.Vertical = when (alignItems) {
                        "start" -> Alignment.Top
                        "end" -> Alignment.Bottom
                        else -> Alignment.CenterVertically
                    }
                    Row(
                        modifier = mod,
                        horizontalArrangement = hArrange,
                        verticalAlignment = vAlign,
                    ) {
                        RenderNodeList(element.children, frame, registry, onEvent, onOpenLinkRequest, onDiagnostic)
                    }
                } else {
                    val vArrange: Arrangement.Vertical = when (justifyContent) {
                        "space-between" -> Arrangement.SpaceBetween
                        "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
                        "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
                        else -> Arrangement.spacedBy(gap, Alignment.Top)
                    }
                    val hAlign: Alignment.Horizontal = when (alignItems) {
                        "center" -> Alignment.CenterHorizontally
                        "end" -> Alignment.End
                        else -> Alignment.Start
                    }
                    Column(
                        modifier = mod,
                        verticalArrangement = vArrange,
                        horizontalAlignment = hAlign,
                    ) {
                        RenderNodeList(element.children, frame, registry, onEvent, onOpenLinkRequest, onDiagnostic)
                    }
                }
            }
        }

        "svg" -> RenderPortableSvg(element, frame, interactionEnabled, onEvent, onDiagnostic)

        else -> {
            val msg = "Primitive <${element.type}> cannot appear outside <svg> or is unsupported"
            LaunchedEffect(element.id, element.type, frame.frameRevision) {
                onDiagnostic(ProtocolDiagnostic("UNSUPPORTED_CAPABILITY", "render", msg, nodeId = element.id, origin = DiagnosticOrigin.HOST))
            }
            NoticeFallbackBox(element.id, "UNSUPPORTED_CAPABILITY", msg)
        }
    }
}

private data class SvgHitTarget(
    val nodeId: String,
    val hitTest: (Offset) -> Boolean,
)

/**
 * Exact IEEE-754 single-precision (`Float`, used by Compose/Skia `Path` and `Offset`) integer precision ceiling (`2^24`).
 * Above `16,777,216px`, `Float` ULP >= `2px` (so `0.25px` screen-space flatness cannot even be represented) and
 * extreme composed `<g transform="scale(1000000)">` chains would otherwise overflow `Float` to `Infinity`/`NaN`.
 *
 * `DEFAULT_MAX_SVG_FLATTEN_STEPS` and `DEFAULT_MAX_SVG_STROKE_SEGMENTS` bound aggregate geometry subdivision work and
 * retained hit-test segments across the ENTIRE logical `SurfaceFrame` (including sibling `<svg>` roots, `<clipPath>`s,
 * and `<mask>`s) via [FrameSvgGeometryLedger], so splitting curves across hundreds of sibling `<svg>` elements cannot
 * replenish or multiply the frame's allowance, while unchanged-frame Compose repaints replay cached geometry in O(1).
 */
internal const val MAX_SAFE_SCREEN_COORD_PX: Double = 16_777_216.0
internal const val DEFAULT_MAX_SVG_FLATTEN_STEPS: Int = 16_384
internal const val DEFAULT_MAX_SVG_STROKE_SEGMENTS: Int = 8_192
internal const val DEFAULT_MAX_SUBDIV_DEPTH: Int = 12

internal class SvgGeometryBudget(
    val maxFlattenSteps: Int = DEFAULT_MAX_SVG_FLATTEN_STEPS,
    val maxStrokeSegments: Int = DEFAULT_MAX_SVG_STROKE_SEGMENTS,
    val maxSubdivDepth: Int = DEFAULT_MAX_SUBDIV_DEPTH,
) {
    var visitedSteps: Int = 0
        private set
    var emittedSegments: Int = 0
        internal set

    fun tryConsumeStep(): Boolean {
        if (visitedSteps >= maxFlattenSteps) return false
        visitedSteps += 1
        return true
    }

    fun tryEmitSegment(): Boolean {
        if (emittedSegments >= maxStrokeSegments) return false
        emittedSegments += 1
        return true
    }
}

internal class CachedSvgGeometry(
    val svgEl: TreeNode.Element,
    val widthPx: Float,
    val heightPx: Float,
    val stepsConsumed: Int,
    val retainedSegments: Int,
    val pathResults: List<ParsedCanvasPath>,
    val clipResults: List<ClipBuildResult>,
)

internal class SvgGeometryPass(
    val budget: SvgGeometryBudget,
    private val cached: CachedSvgGeometry?,
) {
    private var pathIdx: Int = 0
    private var clipIdx: Int = 0
    val recordedPaths = ArrayList<ParsedCanvasPath>()
    val recordedClips = ArrayList<ClipBuildResult>()

    val isReplay: Boolean get() = cached != null

    fun nextPath(compute: (SvgGeometryBudget) -> ParsedCanvasPath): ParsedCanvasPath {
        if (cached != null && pathIdx < cached.pathResults.size) {
            return cached.pathResults[pathIdx++]
        }
        val res = compute(budget)
        recordedPaths.add(res)
        return res
    }

    fun nextClip(compute: (SvgGeometryBudget) -> ClipBuildResult): ClipBuildResult {
        if (cached != null && clipIdx < cached.clipResults.size) {
            return cached.clipResults[clipIdx++]
        }
        val res = compute(budget)
        recordedClips.add(res)
        return res
    }
}

internal class FrameSvgGeometryLedger(
    val maxFlattenSteps: Int = DEFAULT_MAX_SVG_FLATTEN_STEPS,
    val maxStrokeSegments: Int = DEFAULT_MAX_SVG_STROKE_SEGMENTS,
    val maxSubdivDepth: Int = DEFAULT_MAX_SUBDIV_DEPTH,
) {
    private val slots = LinkedHashMap<Any, CachedSvgGeometry>()
    private var consumedStepsTotal: Int = 0
    private var retainedSegmentsTotal: Int = 0

    var drawPassCount: Int = 0
        private set
    var cacheHitCount: Int = 0
        private set
    var cumulativeStepsExecuted: Int = 0
        private set

    val activeConsumedSteps: Int get() = consumedStepsTotal
    val activeRetainedSegments: Int get() = retainedSegmentsTotal

    fun beginSvgPass(svgInstanceKey: Any, svgEl: TreeNode.Element, widthPx: Float, heightPx: Float): SvgGeometryPass {
        drawPassCount += 1
        val existing = slots[svgInstanceKey]
        if (existing != null && existing.svgEl === svgEl && existing.widthPx == widthPx && existing.heightPx == heightPx) {
            cacheHitCount += 1
            return SvgGeometryPass(
                budget = SvgGeometryBudget(maxFlattenSteps = 0, maxStrokeSegments = 0, maxSubdivDepth = maxSubdivDepth),
                cached = existing,
            )
        }
        if (existing != null) {
            slots.remove(svgInstanceKey)
            consumedStepsTotal = (consumedStepsTotal - existing.stepsConsumed).coerceAtLeast(0)
            retainedSegmentsTotal = (retainedSegmentsTotal - existing.retainedSegments).coerceAtLeast(0)
        }
        val remainingSteps = (maxFlattenSteps - consumedStepsTotal).coerceAtLeast(0)
        val remainingSegments = (maxStrokeSegments - retainedSegmentsTotal).coerceAtLeast(0)
        return SvgGeometryPass(
            budget = SvgGeometryBudget(
                maxFlattenSteps = remainingSteps,
                maxStrokeSegments = remainingSegments,
                maxSubdivDepth = maxSubdivDepth,
            ),
            cached = null,
        )
    }

    fun endSvgPass(
        svgInstanceKey: Any,
        svgEl: TreeNode.Element,
        widthPx: Float,
        heightPx: Float,
        pass: SvgGeometryPass,
    ) {
        if (pass.isReplay) return
        val visited = pass.budget.visitedSteps
        val retainedSegs = pass.budget.emittedSegments
        cumulativeStepsExecuted += visited
        consumedStepsTotal += visited
        retainedSegmentsTotal += retainedSegs
        slots[svgInstanceKey] = CachedSvgGeometry(
            svgEl = svgEl,
            widthPx = widthPx,
            heightPx = heightPx,
            stepsConsumed = visited,
            retainedSegments = retainedSegs,
            pathResults = pass.recordedPaths,
            clipResults = pass.recordedClips,
        )
    }

    fun release(svgInstanceKey: Any) {
        val removed = slots.remove(svgInstanceKey) ?: return
        consumedStepsTotal = (consumedStepsTotal - removed.stepsConsumed).coerceAtLeast(0)
        retainedSegmentsTotal = (retainedSegmentsTotal - removed.retainedSegments).coerceAtLeast(0)
    }
}

private fun isFiniteAffine(m: PortableV6Spec.Affine2D): Boolean =
    m.a.isFinite() && m.b.isFinite() && m.c.isFinite() && m.d.isFinite() && m.e.isFinite() && m.f.isFinite()

private fun isSafeScreenPoint(x: Double, y: Double): Boolean =
    x.isFinite() && y.isFinite() && abs(x) <= MAX_SAFE_SCREEN_COORD_PX && abs(y) <= MAX_SAFE_SCREEN_COORD_PX

private fun hasAnySvgClickHandler(node: TreeNode.Element): Boolean {
    if ("click" in node.events) return true
    val nonInteractiveContainers = setOf("defs", "linearGradient", "radialGradient", "clipPath", "mask", "title", "stop")
    for (child in node.children) {
        if (child is TreeNode.Element && child.type !in nonInteractiveContainers && hasAnySvgClickHandler(child)) {
            return true
        }
    }
    return false
}

@Composable
private fun RenderPortableSvg(
    svgEl: TreeNode.Element,
    frame: SurfaceFrame,
    interactionEnabled: Boolean,
    onEvent: (SproutDispatchedEvent) -> Unit,
    onDiagnostic: (ProtocolDiagnostic) -> Unit = {},
) {
    val frameSvgLedger = LocalSproutFrameSvgLedger.current
        ?: remember(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, svgEl) { FrameSvgGeometryLedger() }
    val svgInstanceKey = remember(frameSvgLedger) { Any() }
    DisposableEffect(frameSvgLedger, svgInstanceKey) {
        onDispose {
            frameSvgLedger.release(svgInstanceKey)
        }
    }
    if ((svgEl.props["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return
    val currentColor = LocalSproutTextStyle.current.color
    val textMeasurer = rememberTextMeasurer()
    val widthVal = parseSvgLengthPx(svgEl.props["width"], 300f)
    val heightVal = parseSvgLengthPx(svgEl.props["height"], 150f)
    val viewBoxNums = (svgEl.props["viewBox"] as? JsonPrimitive)?.content
        ?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
    val vbX = viewBoxNums?.getOrNull(0) ?: 0.0
    val vbY = viewBoxNums?.getOrNull(1) ?: 0.0
    val vbW = (viewBoxNums?.getOrNull(2) ?: widthVal.toDouble()).coerceAtLeast(1e-6)
    val vbH = (viewBoxNums?.getOrNull(3) ?: heightVal.toDouble()).coerceAtLeast(1e-6)
    val resolvedW = when {
        "width" in svgEl.props -> widthVal
        "height" in svgEl.props && viewBoxNums != null && viewBoxNums.size >= 4 -> (heightVal.toDouble() * (vbW / vbH)).toFloat()
        else -> widthVal
    }
    val resolvedH = when {
        "height" in svgEl.props -> heightVal
        "width" in svgEl.props && viewBoxNums != null && viewBoxNums.size >= 4 -> (widthVal.toDouble() * (vbH / vbW)).toFloat()
        else -> heightVal
    }
    if (!resolvedW.isFinite() || !resolvedH.isFinite() || resolvedW < 0f || resolvedH < 0f ||
        resolvedW > PortableV6Spec.MAX_BOX_LENGTH.toFloat() || resolvedH > PortableV6Spec.MAX_BOX_LENGTH.toFloat()
    ) {
        val msg = "SVG <svg> (${svgEl.id}): resolved viewport dimensions exceed safe finite range"
        LaunchedEffect(svgEl.id, frame.frameRevision) {
            onDiagnostic(ProtocolDiagnostic("LIMIT_EXCEEDED", "render", msg, nodeId = svgEl.id, origin = DiagnosticOrigin.HOST))
        }
        NoticeFallbackBox(svgEl.id, "LIMIT_EXCEEDED", msg)
        return
    }
    val preserveAspectRatio = (svgEl.props["preserveAspectRatio"] as? JsonPrimitive)?.content ?: "xMidYMid meet"
    val svgOpacity = ((svgEl.props["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).toFloat().coerceIn(0f, 1f)
    val svgDefs = remember(svgEl, currentColor) { collectSvgDefinitions(svgEl.children, currentColor) }
    val ariaLabel = (svgEl.props["ariaLabel"] as? JsonPrimitive)?.content ?: svgDefs.titleText
    val hitTargets = remember { ArrayList<SvgHitTarget>() }
    val reportedNodeIds = remember(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, svgEl) { HashSet<String>() }
    val reportGeometryFailure: (String, String, String) -> Unit = { nodeId, nodeType, reason ->
        if (reportedNodeIds.add(nodeId)) {
            onDiagnostic(
                ProtocolDiagnostic(
                    code = "LIMIT_EXCEEDED",
                    phase = "render",
                    message = "SVG <$nodeType> ($nodeId): $reason",
                    nodeId = nodeId,
                    origin = DiagnosticOrigin.HOST,
                ),
            )
        }
    }
    val hasClickAnywhere = remember(svgEl) { hasAnySvgClickHandler(svgEl) }

    var mod = Modifier
        .size(resolvedW.dp, resolvedH.dp)
        .alpha(svgOpacity)
        .clipToBounds()
        .testTag("sprout-svg:${svgEl.id}")
    if (!ariaLabel.isNullOrBlank()) {
        mod = mod.semantics { contentDescription = ariaLabel }
    }
    if (interactionEnabled && hasClickAnywhere) {
        mod = mod
            .semantics { role = Role.Button }
            .pointerInput(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, svgEl, interactionEnabled) {
                if (!interactionEnabled) return@pointerInput
                detectTapGestures { tapOffset ->
                    val hit = hitTargets.asReversed().firstOrNull { it.hitTest(tapOffset) }
                    val targetNodeId = hit?.nodeId ?: ("click".takeIf { it in svgEl.events }?.let { svgEl.id })
                    if (targetNodeId != null) {
                        val token = SproutGestureToken(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, targetNodeId, "click")
                        onEvent(SproutDispatchedEvent(frame.surfaceId, frame.runtimeEpoch, frame.frameRevision, targetNodeId, "click", JsonArray(emptyList()), token))
                    }
                }
            }
    }

    val inheritedFill = (svgEl.props["fill"] as? JsonPrimitive)?.content
    val inheritedStroke = (svgEl.props["stroke"] as? JsonPrimitive)?.content
    val inheritedStrokeW = parseSvgLengthPx(svgEl.props["strokeWidth"], 1f).toDouble()
    val rootClickId = if ("click" in svgEl.events) svgEl.id else null

    Canvas(modifier = mod) {
        hitTargets.clear()
        val coords = if (preserveAspectRatio == "none") {
            val scaleX = size.width.toDouble() / vbW
            val scaleY = size.height.toDouble() / vbH
            doubleArrayOf(scaleX, scaleY, 0.0, 0.0)
        } else {
            val uniform = min(size.width.toDouble() / vbW, size.height.toDouble() / vbH)
            val offsetX = (size.width.toDouble() - vbW * uniform) * 0.5
            val offsetY = (size.height.toDouble() - vbH * uniform) * 0.5
            doubleArrayOf(uniform, uniform, offsetX, offsetY)
        }
        val sx = coords[0]
        val sy = coords[1]
        val ox = coords[2]
        val oy = coords[3]
        val rootMatrix = PortableV6Spec.Affine2D(
            a = sx,
            d = sy,
            e = ox - vbX * sx,
            f = oy - vbY * sy,
        )
        if (!isFiniteAffine(rootMatrix)) {
            reportGeometryFailure(svgEl.id, "svg", "non-finite SVG viewBox transform")
            return@Canvas
        }
        val svgPass = frameSvgLedger.beginSvgPass(svgInstanceKey, svgEl, size.width, size.height)
        try {
            drawSvgChildren(
                nodes = svgEl.children,
                parentMatrix = rootMatrix,
                currentColor = currentColor,
                inheritedFill = inheritedFill,
                inheritedStroke = inheritedStroke,
                inheritedStrokeWidth = inheritedStrokeW,
                inheritedOpacity = 1.0,
                defs = svgDefs,
                textMeasurer = textMeasurer,
                vbX = vbX,
                vbY = vbY,
                vbW = vbW,
                vbH = vbH,
                inheritedClickableId = rootClickId,
                hitTargetsOut = hitTargets,
                activeMaskStack = emptySet(),
                pass = svgPass,
                reportGeometryFailure = reportGeometryFailure,
            )
        } finally {
            frameSvgLedger.endSvgPass(svgInstanceKey, svgEl, size.width, size.height, svgPass)
        }
    }
}

private sealed class GradientDef {
    abstract val stops: List<PortableV6Spec.ResolvedGradientStop>
    abstract val gradientUnits: String
    abstract val gradientTransform: PortableV6Spec.Affine2D

    data class Linear(
        override val stops: List<PortableV6Spec.ResolvedGradientStop>,
        override val gradientUnits: String,
        val x1: String?,
        val y1: String?,
        val x2: String?,
        val y2: String?,
        override val gradientTransform: PortableV6Spec.Affine2D,
    ) : GradientDef()

    data class Radial(
        override val stops: List<PortableV6Spec.ResolvedGradientStop>,
        override val gradientUnits: String,
        val cx: String?,
        val cy: String?,
        val r: String?,
        override val gradientTransform: PortableV6Spec.Affine2D,
    ) : GradientDef()
}

private data class SvgDefinitions(
    val gradients: Map<String, GradientDef>,
    val clipPaths: Map<String, List<TreeNode.Element>>,
    val masks: Map<String, TreeNode.Element>,
    val titleText: String?,
)

private fun parseGradientStops(children: List<TreeNode>, currentColor: Color): List<PortableV6Spec.ResolvedGradientStop> =
    children.filterIsInstance<TreeNode.Element>()
        .filter { it.type == "stop" }
        .map { stopEl ->
            val offPrim = stopEl.props["offset"] as? JsonPrimitive
            val frac = if (offPrim != null && offPrim.isString && offPrim.content.endsWith("%")) {
                ((offPrim.content.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            } else {
                (offPrim?.doubleOrNull ?: 0.0).coerceIn(0.0, 1.0)
            }
            val stopOpacity = ((stopEl.props["stopOpacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.0, 1.0)
            val col = parsePaintColor((stopEl.props["stopColor"] as? JsonPrimitive)?.content, currentColor) ?: Color.Black
            val srgb = PortableV6Spec.SrgbRgba(
                r = (col.red * 255f).toInt().coerceIn(0, 255),
                g = (col.green * 255f).toInt().coerceIn(0, 255),
                b = (col.blue * 255f).toInt().coerceIn(0, 255),
                a = (col.alpha.toDouble() * stopOpacity).coerceIn(0.0, 1.0),
            )
            PortableV6Spec.ResolvedGradientStop(offset = frac, color = srgb)
        }

private fun collectSvgDefinitions(nodes: List<TreeNode>, currentColor: Color): SvgDefinitions {
    val gradients = LinkedHashMap<String, GradientDef>()
    val clipPaths = LinkedHashMap<String, List<TreeNode.Element>>()
    val masks = LinkedHashMap<String, TreeNode.Element>()
    var titleText: String? = null

    fun scanDefList(list: List<TreeNode>) {
        for (n in list) {
            if (n !is TreeNode.Element) continue
            when (n.type) {
                "defs" -> scanDefList(n.children)
                "title" -> {
                    if (titleText == null) {
                        val joined = n.children.filterIsInstance<TreeNode.Text>().joinToString("") { it.text }.trim()
                        if (joined.isNotEmpty()) titleText = joined
                    }
                }
                "linearGradient" -> {
                    val id = (n.props["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: continue
                    val gradUnits = (n.props["gradientUnits"] as? JsonPrimitive)?.content ?: "objectBoundingBox"
                    val gradMatrix = PortableV6Spec.parseSvgTransform(n.props["gradientTransform"])?.matrix ?: PortableV6Spec.Affine2D.IDENTITY
                    gradients[id] = GradientDef.Linear(
                        stops = parseGradientStops(n.children, currentColor),
                        gradientUnits = gradUnits,
                        x1 = (n.props["x1"] as? JsonPrimitive)?.content,
                        y1 = (n.props["y1"] as? JsonPrimitive)?.content,
                        x2 = (n.props["x2"] as? JsonPrimitive)?.content,
                        y2 = (n.props["y2"] as? JsonPrimitive)?.content,
                        gradientTransform = gradMatrix,
                    )
                }
                "radialGradient" -> {
                    val id = (n.props["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: continue
                    val gradUnits = (n.props["gradientUnits"] as? JsonPrimitive)?.content ?: "objectBoundingBox"
                    val gradMatrix = PortableV6Spec.parseSvgTransform(n.props["gradientTransform"])?.matrix ?: PortableV6Spec.Affine2D.IDENTITY
                    gradients[id] = GradientDef.Radial(
                        stops = parseGradientStops(n.children, currentColor),
                        gradientUnits = gradUnits,
                        cx = (n.props["cx"] as? JsonPrimitive)?.content,
                        cy = (n.props["cy"] as? JsonPrimitive)?.content,
                        r = (n.props["r"] as? JsonPrimitive)?.content,
                        gradientTransform = gradMatrix,
                    )
                }
                "clipPath" -> {
                    val id = (n.props["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: continue
                    clipPaths[id] = n.children.filterIsInstance<TreeNode.Element>()
                }
                "mask" -> {
                    val id = (n.props["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: continue
                    masks[id] = n
                }
            }
        }
    }

    scanDefList(nodes)
    return SvgDefinitions(
        gradients = gradients,
        clipPaths = clipPaths,
        masks = masks,
        titleText = titleText,
    )
}

private fun buildPremulGradientBrush(
    grad: GradientDef,
    bboxX: Double,
    bboxY: Double,
    bboxW: Double,
    bboxH: Double,
    matrix: PortableV6Spec.Affine2D,
    alphaMul: Double,
): Brush? {
    if (grad.stops.isEmpty()) return null
    fun resolveCoord(raw: String?, defaultFrac: Double, origin: Double, span: Double): Double {
        if (raw == null) {
            return if (grad.gradientUnits == "userSpaceOnUse") origin + defaultFrac * span else origin + defaultFrac * span
        }
        val trimmed = raw.trim()
        val isPct = trimmed.endsWith("%")
        val clean = if (isPct) trimmed.dropLast(1) else trimmed.removeSuffix("px")
        val num = clean.toDoubleOrNull() ?: defaultFrac
        return if (grad.gradientUnits == "userSpaceOnUse") {
            if (isPct) origin + (num / 100.0) * span else num
        } else {
            val frac = if (isPct) num / 100.0 else num
            origin + frac * span
        }
    }
    if (grad.stops.size == 1) {
        val c = grad.stops[0].color
        val solid = Color(c.r, c.g, c.b, ((c.a * alphaMul) * 255.0).toInt().coerceIn(0, 255))
        return Brush.linearGradient(colors = listOf(solid, solid))
    }
    val sampleCount = 32
    val sampledStops = Array(sampleCount + 1) { idx ->
        val t = idx.toDouble() / sampleCount.toDouble()
        val c = PortableV6Spec.evalGradientSrgbPremul(grad.stops, t) ?: PortableV6Spec.SrgbRgba(0, 0, 0, 0.0)
        t.toFloat() to Color(c.r, c.g, c.b, ((c.a * alphaMul) * 255.0).toInt().coerceIn(0, 255))
    }
    val combined = matrix.times(grad.gradientTransform)
    if (!isFiniteAffine(combined)) return null
    return when (grad) {
        is GradientDef.Linear -> {
            val lx1 = if (grad.x1 == null && grad.gradientUnits == "userSpaceOnUse") 0.0 else resolveCoord(grad.x1, 0.0, bboxX, bboxW)
            val ly1 = if (grad.y1 == null && grad.gradientUnits == "userSpaceOnUse") 0.0 else resolveCoord(grad.y1, 0.0, bboxY, bboxH)
            val lx2 = if (grad.x2 == null && grad.gradientUnits == "userSpaceOnUse") bboxW else resolveCoord(grad.x2, 1.0, bboxX, bboxW)
            val ly2 = if (grad.y2 == null && grad.gradientUnits == "userSpaceOnUse") 0.0 else resolveCoord(grad.y2, 0.0, bboxY, bboxH)
            val (sx1, sy1) = combined.mapPoint(lx1, ly1)
            val (sx2, sy2) = combined.mapPoint(lx2, ly2)
            if (!isSafeScreenPoint(sx1, sy1) || !isSafeScreenPoint(sx2, sy2)) return null
            val endOffset = if (hypot(sx2 - sx1, sy2 - sy1) < 1e-4) {
                Offset((sx1 + 1.0).toFloat(), sy1.toFloat())
            } else {
                Offset(sx2.toFloat(), sy2.toFloat())
            }
            Brush.linearGradient(
                colorStops = sampledStops,
                start = Offset(sx1.toFloat(), sy1.toFloat()),
                end = endOffset,
            )
        }
        is GradientDef.Radial -> {
            val lcx = resolveCoord(grad.cx, 0.5, bboxX, bboxW)
            val lcy = resolveCoord(grad.cy, 0.5, bboxY, bboxH)
            val spanAvg = max(bboxW, bboxH)
            val lr = resolveCoord(grad.r, 0.5, 0.0, spanAvg).coerceAtLeast(0.5)
            val (scx, scy) = combined.mapPoint(lcx, lcy)
            val (sex, sey) = combined.mapPoint(lcx + lr, lcy)
            if (!isSafeScreenPoint(scx, scy) || !isSafeScreenPoint(sex, sey)) return null
            val radDist = hypot(sex - scx, sey - scy)
            if (!radDist.isFinite() || radDist > MAX_SAFE_SCREEN_COORD_PX) return null
            val screenRadius = radDist.toFloat().coerceAtLeast(1f)
            Brush.radialGradient(
                colorStops = sampledStops,
                center = Offset(scx.toFloat(), scy.toFloat()),
                radius = screenRadius,
            )
        }
    }
}

private fun parseSvgPointsList(raw: String): List<Pair<Double, Double>> {
    val nums = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        .findAll(raw)
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()
    val pts = ArrayList<Pair<Double, Double>>(nums.size / 2)
    var idx = 0
    while (idx + 1 < nums.size) {
        pts.add(nums[idx] to nums[idx + 1])
        idx += 2
    }
    return pts
}

private fun buildPointsCanvasPath(
    pts: List<Pair<Double, Double>>,
    matrix: PortableV6Spec.Affine2D,
    closed: Boolean,
    fillRule: PathFillType = PathFillType.NonZero,
    budget: SvgGeometryBudget = SvgGeometryBudget(),
): ParsedCanvasPath {
    if (!isFiniteAffine(matrix)) {
        return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, failureReason = "non-finite SVG transform matrix")
    }
    val path = Path().apply { this.fillType = fillRule }
    if (pts.isEmpty()) return ParsedCanvasPath(path, 0.0, 0.0, 0.0, 0.0)
    val savedEmitted = budget.emittedSegments
    val strokeSegs = ArrayList<Pair<Offset, Offset>>(pts.size)
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var firstOffset = Offset.Zero
    var prevOffset = Offset.Zero
    for ((idx, pt) in pts.withIndex()) {
        if (!budget.tryConsumeStep()) {
            budget.emittedSegments = savedEmitted
            return ParsedCanvasPath(
                Path(), 0.0, 0.0, 0.0, 0.0,
                failureReason = "SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted",
            )
        }
        val (lx, ly) = pt
        if (!lx.isFinite() || !ly.isFinite()) {
            budget.emittedSegments = savedEmitted
            return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, failureReason = "non-finite SVG point coordinate")
        }
        val (tx, ty) = matrix.mapPoint(lx, ly)
        if (!isSafeScreenPoint(tx, ty)) {
            budget.emittedSegments = savedEmitted
            return ParsedCanvasPath(
                Path(), 0.0, 0.0, 0.0, 0.0,
                failureReason = "transformed SVG points exceed safe finite screen range (±${MAX_SAFE_SCREEN_COORD_PX.toLong()}px)",
            )
        }
        if (lx < minX) minX = lx
        if (ly < minY) minY = ly
        if (lx > maxX) maxX = lx
        if (ly > maxY) maxY = ly
        val cur = Offset(tx.toFloat(), ty.toFloat())
        if (idx == 0) {
            path.moveTo(cur.x, cur.y)
            firstOffset = cur
        } else {
            if (!budget.tryEmitSegment()) {
                budget.emittedSegments = savedEmitted
                return ParsedCanvasPath(
                    Path(), 0.0, 0.0, 0.0, 0.0,
                    failureReason = "SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted",
                )
            }
            path.lineTo(cur.x, cur.y)
            strokeSegs.add(prevOffset to cur)
        }
        prevOffset = cur
    }
    if (closed) {
        path.close()
        if (pts.size >= 2 && prevOffset != firstOffset) {
            if (!budget.tryEmitSegment()) {
                budget.emittedSegments = savedEmitted
                return ParsedCanvasPath(
                    Path(), 0.0, 0.0, 0.0, 0.0,
                    failureReason = "SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted",
                )
            }
            strokeSegs.add(prevOffset to firstOffset)
        }
    }
    return ParsedCanvasPath(path, minX, minY, maxX, maxY, strokeSegs)
}

internal data class ClipBuildResult(
    val path: Path?,
    val failureReason: String? = null,
)

private fun buildClipCanvasPath(
    elements: List<TreeNode.Element>,
    matrix: PortableV6Spec.Affine2D,
    budget: SvgGeometryBudget = SvgGeometryBudget(),
): ClipBuildResult {
    if (!isFiniteAffine(matrix)) {
        return ClipBuildResult(path = null, failureReason = "non-finite clipPath transform matrix")
    }
    val savedEmitted = budget.emittedSegments
    val combined = Path()
    var added = false
    for (el in elements) {
        val localT = PortableV6Spec.parseSvgTransform(el.props["transform"])?.matrix ?: PortableV6Spec.Affine2D.IDENTITY
        val m = matrix.times(localT)
        if (!isFiniteAffine(m)) {
            budget.emittedSegments = savedEmitted
            return ClipBuildResult(path = null, failureReason = "non-finite clipPath child transform matrix")
        }
        val parsedSub: ParsedCanvasPath? = when (el.type) {
            "rect" -> {
                val x = parseSvgLengthPx(el.props["x"], 0f).toDouble()
                val y = parseSvgLengthPx(el.props["y"], 0f).toDouble()
                val w = parseSvgLengthPx(el.props["width"], 0f).toDouble()
                val h = parseSvgLengthPx(el.props["height"], 0f).toDouble()
                if (w > 0.0 && h > 0.0) {
                    val rx = parseSvgLengthPx(el.props["rx"], 0f).toDouble().coerceAtMost(w * 0.5)
                    val ry = parseSvgLengthPx(el.props["ry"], rx.toFloat()).toDouble().coerceAtMost(h * 0.5)
                    buildTransformedRectParsed(x, y, w, h, rx, ry, m, budget)
                } else null
            }
            "circle" -> {
                val cx = parseSvgLengthPx(el.props["cx"], 0f).toDouble()
                val cy = parseSvgLengthPx(el.props["cy"], 0f).toDouble()
                val r = parseSvgLengthPx(el.props["r"], 0f).toDouble()
                if (r > 0.0) buildTransformedRectParsed(cx - r, cy - r, r * 2.0, r * 2.0, r, r, m, budget) else null
            }
            "ellipse" -> {
                val cx = parseSvgLengthPx(el.props["cx"], 0f).toDouble()
                val cy = parseSvgLengthPx(el.props["cy"], 0f).toDouble()
                val rx = parseSvgLengthPx(el.props["rx"], 0f).toDouble()
                val ry = parseSvgLengthPx(el.props["ry"], 0f).toDouble()
                if (rx > 0.0 && ry > 0.0) buildTransformedRectParsed(cx - rx, cy - ry, rx * 2.0, ry * 2.0, rx, ry, m, budget) else null
            }
            "polygon" -> {
                val pts = parseSvgPointsList((el.props["points"] as? JsonPrimitive)?.content.orEmpty())
                if (pts.size >= 3) buildPointsCanvasPath(pts, m, closed = true, budget = budget) else null
            }
            "path" -> {
                val d = (el.props["d"] as? JsonPrimitive)?.content.orEmpty()
                parseSvgPathWithMatrix(d, m, PathFillType.NonZero, budget)
            }
            else -> null
        }
        if (parsedSub != null) {
            if (parsedSub.failureReason != null) {
                budget.emittedSegments = savedEmitted
                return ClipBuildResult(path = null, failureReason = "clipPath <${el.type}>: ${parsedSub.failureReason}")
            }
            if (!parsedSub.path.isEmpty) {
                combined.addPath(parsedSub.path)
                added = true
            }
        }
    }
    return ClipBuildResult(path = if (added) combined else null, failureReason = null)
}

private fun extractUrlRefId(raw: String?): String? =
    raw?.trim()?.takeIf { it.startsWith("url(#") && it.endsWith(")") }?.removePrefix("url(#")?.removeSuffix(")")

private val SVG_LUMINANCE_TO_ALPHA_MATRIX = ColorMatrix(
    floatArrayOf(
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
    ),
)

private fun pathContainsOffset(path: Path, pt: Offset, radiusPx: Float = 0.75f): Boolean {
    val probe = Path().apply {
        addRect(Rect(pt.x - radiusPx, pt.y - radiusPx, pt.x + radiusPx, pt.y + radiusPx))
    }
    return !Path().apply { op(path, probe, PathOperation.Intersect) }.isEmpty
}

private fun distSqPointToSegmentDouble(
    px: Double,
    py: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    val lenSq = dx * dx + dy * dy
    if (!lenSq.isFinite()) return Double.NaN
    if (lenSq <= 1e-12) {
        val ex = px - x1
        val ey = py - y1
        return ex * ex + ey * ey
    }
    val t = (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0.0, 1.0)
    val projX = x1 + t * dx
    val projY = y1 + t * dy
    val rx = px - projX
    val ry = py - projY
    return rx * rx + ry * ry
}

/**
 * Tests whether [pt] lies within the rendered stroke outline [segments] (independently of interior fill!),
 * across all subpaths (`M ... Z M ...`), line/curve segments, and open vs closed contours.
 * Evaluated in `Double` so squared distances remain exact and overflow-free across the full `±2^24` canvas range.
 */
private fun strokeSegmentsContainOffset(
    segments: List<Pair<Offset, Offset>>,
    pt: Offset,
    halfStrokePx: Float,
): Boolean {
    if (segments.isEmpty()) return false
    val tol = (halfStrokePx.toDouble() + 1.5).coerceAtLeast(2.5)
    val tolSq = tol * tol
    val px = pt.x.toDouble()
    val py = pt.y.toDouble()
    for ((a, b) in segments) {
        val dSq = distSqPointToSegmentDouble(px, py, a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble())
        if (dSq.isFinite() && dSq <= tolSq) {
            return true
        }
    }
    return false
}

private fun gateHitByClipAndMask(
    clipCanvasPath: Path?,
    maskRegionPath: Path?,
    rawHit: (Offset) -> Boolean,
): (Offset) -> Boolean = { pt ->
    (clipCanvasPath == null || pathContainsOffset(clipCanvasPath, pt)) &&
        (maskRegionPath == null || pathContainsOffset(maskRegionPath, pt)) &&
        rawHit(pt)
}

private fun DrawScope.drawSvgChildren(
    nodes: List<TreeNode>,
    parentMatrix: PortableV6Spec.Affine2D,
    currentColor: Color,
    inheritedFill: String?,
    inheritedStroke: String?,
    inheritedStrokeWidth: Double,
    inheritedOpacity: Double,
    defs: SvgDefinitions,
    textMeasurer: TextMeasurer,
    vbX: Double,
    vbY: Double,
    vbW: Double,
    vbH: Double,
    inheritedClickableId: String?,
    hitTargetsOut: MutableList<SvgHitTarget>?,
    activeMaskStack: Set<String>,
    pass: SvgGeometryPass,
    reportGeometryFailure: (nodeId: String, nodeType: String, reason: String) -> Unit,
) {
    val nonRenderableTypes = setOf("defs", "linearGradient", "radialGradient", "clipPath", "mask", "title", "stop")
    for (n in nodes) {
        if (n !is TreeNode.Element || n.type in nonRenderableTypes) continue
        val localTransform = PortableV6Spec.parseSvgTransform(n.props["transform"])?.matrix ?: PortableV6Spec.Affine2D.IDENTITY
        val matrix = parentMatrix.times(localTransform)
        if (!isFiniteAffine(matrix)) {
            reportGeometryFailure(n.id, n.type, "composed SVG transform overflowed finite floating-point range")
            continue
        }
        val nodeOpacity = (inheritedOpacity * ((n.props["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0)).coerceIn(0.0, 1.0)
        val fillRaw = (n.props["fill"] as? JsonPrimitive)?.content ?: inheritedFill
        val strokeRaw = (n.props["stroke"] as? JsonPrimitive)?.content ?: inheritedStroke
        val strokeWidthRaw = n.props["strokeWidth"]?.let { parseSvgLengthPx(it, inheritedStrokeWidth.toFloat()).toDouble() }
            ?: inheritedStrokeWidth
        val selfClickableId = if ("click" in n.events) n.id else inheritedClickableId

        val clipId = extractUrlRefId((n.props["clipPath"] as? JsonPrimitive)?.content)
        val clipDefs = clipId?.let { defs.clipPaths[it] }
        val clipResult = clipDefs?.let { pass.nextClip { budget -> buildClipCanvasPath(it, matrix, budget) } }
        if (clipResult?.failureReason != null) {
            reportGeometryFailure(n.id, n.type, clipResult.failureReason)
            continue
        }
        val clipCanvasPath = clipResult?.path
        if (clipDefs != null && clipCanvasPath == null) {
            // Empty `<clipPath>` clips everything out per SVG spec; fail closed rather than drawing unclipped.
            continue
        }

        val maskId = extractUrlRefId((n.props["mask"] as? JsonPrimitive)?.content)
        val maskEl = maskId?.takeIf { it !in activeMaskStack }?.let { defs.masks[it] }
        val maskRegionPath: Path? = if (maskEl != null) {
            val mx = maskEl.props["x"]?.let { parseSvgLengthPx(it, 0f).toDouble() } ?: (vbX - 0.1 * vbW)
            val my = maskEl.props["y"]?.let { parseSvgLengthPx(it, 0f).toDouble() } ?: (vbY - 0.1 * vbH)
            val mw = maskEl.props["width"]?.let { parseSvgLengthPx(it, 0f).toDouble() } ?: (1.2 * vbW)
            val mh = maskEl.props["height"]?.let { parseSvgLengthPx(it, 0f).toDouble() } ?: (1.2 * vbH)
            if (mw <= 0.0 || mh <= 0.0) continue
            val maskParsed = pass.nextPath { budget -> buildTransformedRectParsed(mx, my, mw, mh, 0.0, 0.0, matrix, budget) }
            if (maskParsed.failureReason != null) {
                reportGeometryFailure(n.id, n.type, "mask region: ${maskParsed.failureReason}")
                continue
            }
            maskParsed.path
        } else {
            null
        }

        val drawBody: DrawScope.() -> Unit = body@{
            if (n.type == "g") {
                drawSvgChildren(
                    nodes = n.children,
                    parentMatrix = matrix,
                    currentColor = currentColor,
                    inheritedFill = fillRaw,
                    inheritedStroke = strokeRaw,
                    inheritedStrokeWidth = strokeWidthRaw,
                    inheritedOpacity = nodeOpacity,
                    defs = defs,
                    textMeasurer = textMeasurer,
                    vbX = vbX,
                    vbY = vbY,
                    vbW = vbW,
                    vbH = vbH,
                    inheritedClickableId = selfClickableId,
                    hitTargetsOut = hitTargetsOut,
                    activeMaskStack = activeMaskStack,
                    pass = pass,
                    reportGeometryFailure = reportGeometryFailure,
                )
                return@body
            }

            val fillOpacity = (nodeOpacity * ((n.props["fillOpacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0)).coerceIn(0.0, 1.0)
            val strokeOpacity = (nodeOpacity * ((n.props["strokeOpacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0)).coerceIn(0.0, 1.0)
            val scaleXAxis = hypot(matrix.a, matrix.b)
            val scaleYAxis = hypot(matrix.c, matrix.d)
            val maxAxisScale = max(scaleXAxis, scaleYAxis)
            if (!maxAxisScale.isFinite()) {
                reportGeometryFailure(n.id, n.type, "composed SVG scale overflowed finite floating-point range")
                return@body
            }
            val scaleApprox = scaleXAxis.takeIf { it > 1e-6 } ?: (scaleYAxis.takeIf { it > 1e-6 } ?: 1.0)
            val strokeWidthScaled = strokeWidthRaw * maxAxisScale
            if (strokeRaw != null && strokeRaw != "none" && strokeWidthRaw > 0.0 &&
                (!strokeWidthScaled.isFinite() || strokeWidthScaled > MAX_SAFE_SCREEN_COORD_PX)
            ) {
                reportGeometryFailure(
                    n.id,
                    n.type,
                    "transformed strokeWidth exceeds safe finite screen range (±${MAX_SAFE_SCREEN_COORD_PX.toLong()}px)",
                )
                return@body
            }
            val strokeW = (strokeWidthRaw * scaleApprox).toFloat()
            val strokeCap = when ((n.props["strokeLinecap"] as? JsonPrimitive)?.content) {
                "round" -> StrokeCap.Round
                "square" -> StrokeCap.Square
                else -> StrokeCap.Butt
            }
            val strokeJoin = when ((n.props["strokeLinejoin"] as? JsonPrimitive)?.content) {
                "round" -> StrokeJoin.Round
                "bevel" -> StrokeJoin.Bevel
                else -> StrokeJoin.Miter
            }
            val dashRaw = (n.props["strokeDasharray"] as? JsonPrimitive)?.content
            val dashIntervals = dashRaw
                ?.let { Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)").findAll(it).mapNotNull { m -> m.value.toDoubleOrNull() }.filter { v -> v > 0.0 }.toList() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { rawList ->
                    val evenList = if (rawList.size % 2 == 1) rawList + rawList else rawList
                    if (evenList.any { v -> !(v * scaleApprox).isFinite() || v * scaleApprox > MAX_SAFE_SCREEN_COORD_PX }) {
                        null
                    } else {
                        FloatArray(evenList.size) { idx -> (evenList[idx] * scaleApprox).toFloat().coerceAtLeast(1f) }
                    }
                }
            val dashOffsetDouble = parseSvgLengthPx(n.props["strokeDashoffset"], 0f).toDouble() * scaleApprox
            val dashOffset = if (dashOffsetDouble.isFinite() && abs(dashOffsetDouble) <= MAX_SAFE_SCREEN_COORD_PX) {
                dashOffsetDouble.toFloat()
            } else {
                0f
            }
            val pathEffect = dashIntervals?.let { PathEffect.dashPathEffect(it, dashOffset) }
            val strokeStyle = Stroke(width = strokeW, cap = strokeCap, join = strokeJoin, pathEffect = pathEffect)

            val fillGradId = extractUrlRefId(fillRaw)
            val fillGrad = fillGradId?.let { defs.gradients[it] }
            val fillColor = when {
                fillGradId != null -> null
                fillRaw == "none" -> null
                fillRaw == null -> currentColor.copy(alpha = fillOpacity.toFloat())
                else -> parsePaintColor(fillRaw, currentColor)?.let { it.copy(alpha = (it.alpha * fillOpacity.toFloat()).coerceIn(0f, 1f)) }
            }

            val strokeGradId = extractUrlRefId(strokeRaw)
            val strokeGrad = strokeGradId?.let { defs.gradients[it] }
            val strokeColor = when {
                strokeGradId != null -> null
                strokeRaw == null || strokeRaw == "none" -> null
                else -> parsePaintColor(strokeRaw, currentColor)?.let { it.copy(alpha = (it.alpha * strokeOpacity.toFloat()).coerceIn(0f, 1f)) }
            }

            fun registerPathHitTarget(parsedShape: ParsedCanvasPath, hasFill: Boolean, hasStroke: Boolean) {
                if (selfClickableId == null || hitTargetsOut == null) return
                if (!hasFill && !hasStroke) return
                val halfStroke = strokeW * 0.5f
                hitTargetsOut.add(
                    SvgHitTarget(
                        nodeId = selfClickableId,
                        hitTest = gateHitByClipAndMask(clipCanvasPath, maskRegionPath) { pt ->
                            (hasFill && pathContainsOffset(parsedShape.path, pt)) ||
                                (hasStroke && strokeSegmentsContainOffset(parsedShape.strokeSegments, pt, halfStroke))
                        },
                    ),
                )
            }

            when (n.type) {
                "rect" -> {
                    val x = parseSvgLengthPx(n.props["x"], 0f).toDouble()
                    val y = parseSvgLengthPx(n.props["y"], 0f).toDouble()
                    val w = parseSvgLengthPx(n.props["width"], 0f).toDouble()
                    val h = parseSvgLengthPx(n.props["height"], 0f).toDouble()
                    if (w > 0.0 && h > 0.0) {
                        val rx = parseSvgLengthPx(n.props["rx"], 0f).toDouble().coerceAtMost(w * 0.5)
                        val ry = parseSvgLengthPx(n.props["ry"], rx.toFloat()).toDouble().coerceAtMost(h * 0.5)
                        val rectParsed = pass.nextPath { budget -> buildTransformedRectParsed(x, y, w, h, rx, ry, matrix, budget) }
                        if (rectParsed.failureReason != null) {
                            reportGeometryFailure(n.id, n.type, rectParsed.failureReason)
                            return@body
                        }
                        val fillBrush = fillGrad?.let { buildPremulGradientBrush(it, x, y, w, h, matrix, fillOpacity) }
                        if (fillBrush != null) drawPath(rectParsed.path, brush = fillBrush)
                        else if (fillColor != null) drawPath(rectParsed.path, color = fillColor)
                        val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, x, y, w, h, matrix, strokeOpacity) }
                        if (strokeBrush != null && strokeW > 0f) drawPath(rectParsed.path, brush = strokeBrush, style = strokeStyle)
                        else if (strokeColor != null && strokeW > 0f) drawPath(rectParsed.path, color = strokeColor, style = strokeStyle)
                        registerPathHitTarget(rectParsed, fillBrush != null || fillColor != null, (strokeBrush != null || strokeColor != null) && strokeW > 0f)
                    }
                }
                "circle" -> {
                    val cx = parseSvgLengthPx(n.props["cx"], 0f).toDouble()
                    val cy = parseSvgLengthPx(n.props["cy"], 0f).toDouble()
                    val r = parseSvgLengthPx(n.props["r"], 0f).toDouble()
                    if (r > 0.0) {
                        val circleParsed = pass.nextPath { budget -> buildTransformedRectParsed(cx - r, cy - r, r * 2.0, r * 2.0, r, r, matrix, budget) }
                        if (circleParsed.failureReason != null) {
                            reportGeometryFailure(n.id, n.type, circleParsed.failureReason)
                            return@body
                        }
                        val fillBrush = fillGrad?.let { buildPremulGradientBrush(it, cx - r, cy - r, r * 2.0, r * 2.0, matrix, fillOpacity) }
                        if (fillBrush != null) drawPath(circleParsed.path, brush = fillBrush)
                        else if (fillColor != null) drawPath(circleParsed.path, color = fillColor)
                        val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, cx - r, cy - r, r * 2.0, r * 2.0, matrix, strokeOpacity) }
                        if (strokeBrush != null && strokeW > 0f) drawPath(circleParsed.path, brush = strokeBrush, style = strokeStyle)
                        else if (strokeColor != null && strokeW > 0f) drawPath(circleParsed.path, color = strokeColor, style = strokeStyle)
                        registerPathHitTarget(circleParsed, fillBrush != null || fillColor != null, (strokeBrush != null || strokeColor != null) && strokeW > 0f)
                    }
                }
                "ellipse" -> {
                    val cx = parseSvgLengthPx(n.props["cx"], 0f).toDouble()
                    val cy = parseSvgLengthPx(n.props["cy"], 0f).toDouble()
                    val rx = parseSvgLengthPx(n.props["rx"], 0f).toDouble()
                    val ry = parseSvgLengthPx(n.props["ry"], 0f).toDouble()
                    if (rx > 0.0 && ry > 0.0) {
                        val ellipseParsed = pass.nextPath { budget -> buildTransformedRectParsed(cx - rx, cy - ry, rx * 2.0, ry * 2.0, rx, ry, matrix, budget) }
                        if (ellipseParsed.failureReason != null) {
                            reportGeometryFailure(n.id, n.type, ellipseParsed.failureReason)
                            return@body
                        }
                        val fillBrush = fillGrad?.let { buildPremulGradientBrush(it, cx - rx, cy - ry, rx * 2.0, ry * 2.0, matrix, fillOpacity) }
                        if (fillBrush != null) drawPath(ellipseParsed.path, brush = fillBrush)
                        else if (fillColor != null) drawPath(ellipseParsed.path, color = fillColor)
                        val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, cx - rx, cy - ry, rx * 2.0, ry * 2.0, matrix, strokeOpacity) }
                        if (strokeBrush != null && strokeW > 0f) drawPath(ellipseParsed.path, brush = strokeBrush, style = strokeStyle)
                        else if (strokeColor != null && strokeW > 0f) drawPath(ellipseParsed.path, color = strokeColor, style = strokeStyle)
                        registerPathHitTarget(ellipseParsed, fillBrush != null || fillColor != null, (strokeBrush != null || strokeColor != null) && strokeW > 0f)
                    }
                }
                "line" -> {
                    val x1 = parseSvgLengthPx(n.props["x1"], 0f).toDouble()
                    val y1 = parseSvgLengthPx(n.props["y1"], 0f).toDouble()
                    val x2 = parseSvgLengthPx(n.props["x2"], 0f).toDouble()
                    val y2 = parseSvgLengthPx(n.props["y2"], 0f).toDouble()
                    val pts = listOf(x1 to y1, x2 to y2)
                    val parsed = pass.nextPath { budget -> buildPointsCanvasPath(pts, matrix, closed = false, budget = budget) }
                    if (parsed.failureReason != null) {
                        reportGeometryFailure(n.id, n.type, parsed.failureReason)
                        return@body
                    }
                    val bw = (parsed.maxX - parsed.minX).coerceAtLeast(1e-6)
                    val bh = (parsed.maxY - parsed.minY).coerceAtLeast(1e-6)
                    val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, parsed.minX, parsed.minY, bw, bh, matrix, strokeOpacity) }
                    val hasStroke = (strokeBrush != null || strokeColor != null) && strokeW > 0f
                    if (strokeBrush != null && strokeW > 0f) drawPath(parsed.path, brush = strokeBrush, style = strokeStyle)
                    else if (strokeColor != null && strokeW > 0f) drawPath(parsed.path, color = strokeColor, style = strokeStyle)
                    registerPathHitTarget(parsed, hasFill = false, hasStroke = hasStroke)
                }
                "polyline", "polygon" -> {
                    val fillRule = if ((n.props["fillRule"] as? JsonPrimitive)?.content == "evenodd") {
                        PathFillType.EvenOdd
                    } else {
                        PathFillType.NonZero
                    }
                    val pts = parseSvgPointsList((n.props["points"] as? JsonPrimitive)?.content.orEmpty())
                    val isClosed = n.type == "polygon"
                    if (pts.size >= 2) {
                        val parsed = pass.nextPath { budget -> buildPointsCanvasPath(pts, matrix, closed = isClosed, fillRule = fillRule, budget = budget) }
                        if (parsed.failureReason != null) {
                            reportGeometryFailure(n.id, n.type, parsed.failureReason)
                            return@body
                        }
                        val bw = (parsed.maxX - parsed.minX).coerceAtLeast(1e-6)
                        val bh = (parsed.maxY - parsed.minY).coerceAtLeast(1e-6)
                        val fillBrush = fillGrad?.let { buildPremulGradientBrush(it, parsed.minX, parsed.minY, bw, bh, matrix, fillOpacity) }
                        if (fillBrush != null) drawPath(parsed.path, brush = fillBrush)
                        else if (fillColor != null) drawPath(parsed.path, color = fillColor)
                        val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, parsed.minX, parsed.minY, bw, bh, matrix, strokeOpacity) }
                        if (strokeBrush != null && strokeW > 0f) drawPath(parsed.path, brush = strokeBrush, style = strokeStyle)
                        else if (strokeColor != null && strokeW > 0f) drawPath(parsed.path, color = strokeColor, style = strokeStyle)
                        val hasFill = fillBrush != null || fillColor != null
                        val hasStroke = (strokeBrush != null || strokeColor != null) && strokeW > 0f
                        if ((hasFill || hasStroke) && selfClickableId != null && hitTargetsOut != null) {
                            val closedFillPath = if (!isClosed && hasFill) {
                                Path().apply {
                                    fillType = fillRule
                                    addPath(parsed.path)
                                    close()
                                }
                            } else {
                                parsed.path
                            }
                            hitTargetsOut.add(
                                SvgHitTarget(
                                    nodeId = selfClickableId,
                                    hitTest = gateHitByClipAndMask(clipCanvasPath, maskRegionPath) { pt ->
                                        (hasFill && pathContainsOffset(closedFillPath, pt)) ||
                                            (hasStroke && strokeSegmentsContainOffset(parsed.strokeSegments, pt, strokeW * 0.5f))
                                    },
                                ),
                            )
                        }
                    }
                }
                "path" -> {
                    val d = (n.props["d"] as? JsonPrimitive)?.content.orEmpty()
                    val fillRule = if ((n.props["fillRule"] as? JsonPrimitive)?.content == "evenodd") {
                        PathFillType.EvenOdd
                    } else {
                        PathFillType.NonZero
                    }
                    val parsed = pass.nextPath { budget -> parseSvgPathWithMatrix(d, matrix, fillRule, budget) }
                    if (parsed.failureReason != null) {
                        reportGeometryFailure(n.id, n.type, parsed.failureReason)
                        return@body
                    }
                    if (!parsed.path.isEmpty) {
                        val bw = (parsed.maxX - parsed.minX).coerceAtLeast(1e-6)
                        val bh = (parsed.maxY - parsed.minY).coerceAtLeast(1e-6)
                        val fillBrush = fillGrad?.let { buildPremulGradientBrush(it, parsed.minX, parsed.minY, bw, bh, matrix, fillOpacity) }
                        if (fillBrush != null) drawPath(parsed.path, brush = fillBrush)
                        else if (fillColor != null) drawPath(parsed.path, color = fillColor)
                        val strokeBrush = strokeGrad?.let { buildPremulGradientBrush(it, parsed.minX, parsed.minY, bw, bh, matrix, strokeOpacity) }
                        if (strokeBrush != null && strokeW > 0f) drawPath(parsed.path, brush = strokeBrush, style = strokeStyle)
                        else if (strokeColor != null && strokeW > 0f) drawPath(parsed.path, color = strokeColor, style = strokeStyle)
                        registerPathHitTarget(parsed, fillBrush != null || fillColor != null, (strokeBrush != null || strokeColor != null) && strokeW > 0f)
                    }
                }
                "text", "tspan" -> {
                    val baseX = parseSvgLengthPx(n.props["x"], 0f).toDouble()
                    val baseY = parseSvgLengthPx(n.props["y"], 12f).toDouble()
                    val baseFontPxDouble = (parseSvgLengthPx(n.props["fontSize"], 12f).toDouble() * maxAxisScale).coerceAtLeast(6.0)
                    if (!baseFontPxDouble.isFinite() || baseFontPxDouble > 8192.0) {
                        reportGeometryFailure(n.id, n.type, "transformed SVG fontSize exceeds safe finite screen range")
                        return@body
                    }
                    val baseFontPx = (parseSvgLengthPx(n.props["fontSize"], 12f) * scaleApprox.toFloat()).coerceAtLeast(6f)
                    val baseWeight = parseFontWeight(n.props["fontWeight"]) ?: FontWeight.Normal
                    val anchor = (n.props["textAnchor"] as? JsonPrimitive)?.content ?: "start"
                    val baseTextColor = fillColor ?: currentColor.copy(alpha = fillOpacity.toFloat())
                    val textBoxes = ArrayList<Rect>()
                    var textOverflowed = false

                    fun drawSingleRun(runText: String, lx: Double, ly: Double, fontPx: Float, weight: FontWeight, col: Color, runAnchor: String) {
                        if (runText.isEmpty() || textOverflowed) return
                        val (sxPos, syPos) = matrix.mapPoint(lx, ly)
                        if (!isSafeScreenPoint(sxPos, syPos) || !fontPx.isFinite() || fontPx > 8192f) {
                            textOverflowed = true
                            reportGeometryFailure(
                                n.id,
                                n.type,
                                "transformed SVG text coordinates exceed safe finite screen range (±${MAX_SAFE_SCREEN_COORD_PX.toLong()}px)",
                            )
                            return
                        }
                        val measured = textMeasurer.measure(
                            text = runText,
                            style = TextStyle(
                                color = col,
                                fontSize = (fontPx / density).sp,
                                fontWeight = weight,
                            ),
                        )
                        val anchorDx = when (runAnchor) {
                            "middle" -> -measured.size.width * 0.5f
                            "end" -> -measured.size.width.toFloat()
                            else -> 0f
                        }
                        val baselineDy = -measured.firstBaseline
                        val left = sxPos.toFloat() + anchorDx
                        val top = syPos.toFloat() + baselineDy
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(left, top),
                        )
                        textBoxes.add(Rect(left, top, left + measured.size.width, top + measured.size.height))
                    }

                    var cursorX = baseX
                    for (child in n.children) {
                        if (textOverflowed) break
                        when (child) {
                            is TreeNode.Text -> {
                                drawSingleRun(child.text, cursorX, baseY, baseFontPx, baseWeight, baseTextColor, anchor)
                                cursorX += child.text.length * (baseFontPx / scaleApprox) * 0.55
                            }
                            is TreeNode.Element -> if (child.type == "tspan") {
                                val tx = child.props["x"]?.let { parseSvgLengthPx(it, cursorX.toFloat()).toDouble() } ?: cursorX
                                val ty = child.props["y"]?.let { parseSvgLengthPx(it, baseY.toFloat()).toDouble() } ?: baseY
                                val tFontPxDouble = child.props["fontSize"]?.let { (parseSvgLengthPx(it, 12f).toDouble() * maxAxisScale).coerceAtLeast(6.0) } ?: baseFontPxDouble
                                if (!tFontPxDouble.isFinite() || tFontPxDouble > 8192.0) {
                                    textOverflowed = true
                                    reportGeometryFailure(child.id, child.type, "transformed SVG fontSize exceeds safe finite screen range")
                                    break
                                }
                                val tFontPx = child.props["fontSize"]?.let { (parseSvgLengthPx(it, 12f) * scaleApprox.toFloat()).coerceAtLeast(6f) } ?: baseFontPx
                                val tWeight = parseFontWeight(child.props["fontWeight"]) ?: baseWeight
                                val tAnchor = (child.props["textAnchor"] as? JsonPrimitive)?.content ?: anchor
                                val tOpacity = (fillOpacity * ((child.props["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0)).coerceIn(0.0, 1.0)
                                val tFillRaw = (child.props["fill"] as? JsonPrimitive)?.content
                                val tCol = if (tFillRaw != null && tFillRaw != "none") {
                                    parsePaintColor(tFillRaw, currentColor)?.copy(alpha = tOpacity.toFloat()) ?: baseTextColor
                                } else {
                                    baseTextColor.copy(alpha = tOpacity.toFloat())
                                }
                                val spanText = child.children.filterIsInstance<TreeNode.Text>().joinToString("") { it.text }
                                drawSingleRun(spanText, tx, ty, tFontPx, tWeight, tCol, tAnchor)
                                cursorX = tx + spanText.length * (tFontPx / scaleApprox) * 0.55
                            }
                        }
                    }
                    if (!textOverflowed && textBoxes.isNotEmpty() && selfClickableId != null && hitTargetsOut != null) {
                        hitTargetsOut.add(
                            SvgHitTarget(
                                nodeId = selfClickableId,
                                hitTest = gateHitByClipAndMask(clipCanvasPath, maskRegionPath) { pt ->
                                    textBoxes.any { box -> box.contains(pt) }
                                },
                            ),
                        )
                    }
                }
            }
        }

        if (maskEl != null && maskRegionPath != null) {
            val layerBounds = Rect(0f, 0f, size.width, size.height)
            drawIntoCanvas { canvas -> canvas.saveLayer(layerBounds, Paint()) }
            if (clipCanvasPath != null) {
                clipPath(clipCanvasPath) {
                    clipPath(maskRegionPath) {
                        drawBody()
                    }
                }
            } else {
                clipPath(maskRegionPath) {
                    drawBody()
                }
            }
            val maskPaint = Paint().apply {
                blendMode = BlendMode.DstIn
                colorFilter = ColorFilter.colorMatrix(SVG_LUMINANCE_TO_ALPHA_MATRIX)
            }
            drawIntoCanvas { canvas -> canvas.saveLayer(layerBounds, maskPaint) }
            // Opaque black backdrop guarantees unpainted/black regions evaluate to luminance 0 (alpha 0)
            // and semi-transparent mask fills composite to `alpha * luminance` per SVG 1.1 §14.4.
            drawRect(color = Color.Black, topLeft = Offset.Zero, size = size)
            clipPath(maskRegionPath) {
                drawSvgChildren(
                    nodes = maskEl.children,
                    parentMatrix = matrix,
                    currentColor = Color.Black,
                    inheritedFill = null,
                    inheritedStroke = null,
                    inheritedStrokeWidth = 1.0,
                    inheritedOpacity = 1.0,
                    defs = defs,
                    textMeasurer = textMeasurer,
                    vbX = vbX,
                    vbY = vbY,
                    vbW = vbW,
                    vbH = vbH,
                    inheritedClickableId = null,
                    hitTargetsOut = null,
                    activeMaskStack = activeMaskStack + maskId,
                    pass = pass,
                    reportGeometryFailure = reportGeometryFailure,
                )
            }
            drawIntoCanvas { canvas ->
                canvas.restore()
                canvas.restore()
            }
        } else if (clipCanvasPath != null) {
            clipPath(clipCanvasPath) {
                drawBody()
            }
        } else {
            drawBody()
        }
    }
}

private fun buildTransformedRectParsed(
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    rx: Double,
    ry: Double,
    matrix: PortableV6Spec.Affine2D,
    budget: SvgGeometryBudget = SvgGeometryBudget(),
): ParsedCanvasPath {
    if (!isFiniteAffine(matrix)) {
        return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, failureReason = "non-finite SVG transform matrix")
    }
    if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || !rx.isFinite() || !ry.isFinite()) {
        return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, failureReason = "non-finite SVG shape geometry")
    }
    if (rx <= 1e-6 || ry <= 1e-6) {
        if (!budget.tryConsumeStep()) {
            return ParsedCanvasPath(
                Path(), 0.0, 0.0, 0.0, 0.0,
                failureReason = "SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted",
            )
        }
        val (x0, y0) = matrix.mapPoint(x, y)
        val (x1, y1) = matrix.mapPoint(x + w, y)
        val (x2, y2) = matrix.mapPoint(x + w, y + h)
        val (x3, y3) = matrix.mapPoint(x, y + h)
        if (!isSafeScreenPoint(x0, y0) || !isSafeScreenPoint(x1, y1) ||
            !isSafeScreenPoint(x2, y2) || !isSafeScreenPoint(x3, y3)
        ) {
            return ParsedCanvasPath(
                Path(), 0.0, 0.0, 0.0, 0.0,
                failureReason = "transformed SVG rectangle coordinates exceed safe finite screen range (±${MAX_SAFE_SCREEN_COORD_PX.toLong()}px)",
            )
        }
        val savedEmitted = budget.emittedSegments
        repeat(4) {
            if (!budget.tryEmitSegment()) {
                budget.emittedSegments = savedEmitted
                return ParsedCanvasPath(
                    Path(), 0.0, 0.0, 0.0, 0.0,
                    failureReason = "SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted",
                )
            }
        }
        val p = Path()
        val o0 = Offset(x0.toFloat(), y0.toFloat())
        val o1 = Offset(x1.toFloat(), y1.toFloat())
        val o2 = Offset(x2.toFloat(), y2.toFloat())
        val o3 = Offset(x3.toFloat(), y3.toFloat())
        p.moveTo(o0.x, o0.y)
        p.lineTo(o1.x, o1.y)
        p.lineTo(o2.x, o2.y)
        p.lineTo(o3.x, o3.y)
        p.close()
        return ParsedCanvasPath(
            path = p,
            minX = x,
            minY = y,
            maxX = x + w,
            maxY = y + h,
            strokeSegments = listOf(o0 to o1, o1 to o2, o2 to o3, o3 to o0),
        )
    }
    val d = "M ${x + rx} $y L ${x + w - rx} $y A $rx $ry 0 0 1 ${x + w} ${y + ry} " +
        "L ${x + w} ${y + h - ry} A $rx $ry 0 0 1 ${x + w - rx} ${y + h} " +
        "L ${x + rx} ${y + h} A $rx $ry 0 0 1 $x ${y + h - ry} " +
        "L $x ${y + ry} A $rx $ry 0 0 1 ${x + rx} $y Z"
    return parseSvgPathWithMatrix(d, matrix, PathFillType.NonZero, budget)
}

internal data class ParsedCanvasPath(
    val path: Path,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val strokeSegments: List<Pair<Offset, Offset>> = emptyList(),
    val failureReason: String? = null,
)

internal fun parseSvgPathWithMatrix(
    d: String,
    matrix: PortableV6Spec.Affine2D,
    fillType: PathFillType,
    budget: SvgGeometryBudget = SvgGeometryBudget(),
): ParsedCanvasPath {
    if (!isFiniteAffine(matrix)) {
        return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, failureReason = "non-finite SVG transform matrix")
    }
    val savedEmitted = budget.emittedSegments
    val path = Path().apply { this.fillType = fillType }
    val strokeSegs = ArrayList<Pair<Offset, Offset>>()
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var failureReason: String? = null

    fun fail(reason: String) {
        if (failureReason == null) failureReason = reason
    }

    fun recordLocalScreen(lx: Double, ly: Double): Pair<Double, Double>? {
        if (!lx.isFinite() || !ly.isFinite()) {
            fail("non-finite SVG path coordinate")
            return null
        }
        val (tx, ty) = matrix.mapPoint(lx, ly)
        if (!isSafeScreenPoint(tx, ty)) {
            fail("transformed SVG coordinates exceed safe finite screen range (±${MAX_SAFE_SCREEN_COORD_PX.toLong()}px)")
            return null
        }
        if (lx < minX) minX = lx
        if (ly < minY) minY = ly
        if (lx > maxX) maxX = lx
        if (ly > maxY) maxY = ly
        return tx to ty
    }

    var curScreenX = 0.0
    var curScreenY = 0.0
    var startScreenX = 0.0
    var startScreenY = 0.0
    var hasSubpath = false

    fun ensureSubpathOrigin(): Boolean {
        if (hasSubpath) return true
        val origin = recordLocalScreen(0.0, 0.0) ?: return false
        curScreenX = origin.first
        curScreenY = origin.second
        startScreenX = origin.first
        startScreenY = origin.second
        hasSubpath = true
        return true
    }

    fun addLineScreen(px: Double, py: Double) {
        if (!budget.tryConsumeStep()) {
            fail("SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted")
            return
        }
        if (hasSubpath) {
            if (!budget.tryEmitSegment()) {
                fail("SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted")
                return
            }
            strokeSegs.add(Offset(curScreenX.toFloat(), curScreenY.toFloat()) to Offset(px.toFloat(), py.toFloat()))
        } else {
            startScreenX = px
            startScreenY = py
            hasSubpath = true
        }
        curScreenX = px
        curScreenY = py
    }

    // Adaptive screen-space De Casteljau subdivision in `Double`:
    // - Every transformed point is validated against `±MAX_SAFE_SCREEN_COORD_PX` (`±2^24`) BEFORE native `Path`
    //   building or subdivision, eliminating `Float`/`Double` overflow and `NaN` recursion.
    // - Chord flatness bound `0.20px` (`maxFlatnessSq = 0.04`) guarantees <= `0.20px` error for quadratic/cubic
    //   Beziers and <= `0.25px` total error for elliptical arcs (`<= 0.05px` arc-to-cubic + `<= 0.20px` chord).
    // - Flatness is checked BEFORE `depth >= budget.maxSubdivDepth`; if either `maxSubdivDepth` or `SvgGeometryBudget`
    //   is exhausted before meeting flatness, parsing fails closed with a `LIMIT_EXCEEDED` diagnostic rather than
    //   silently accepting an unverified coarse chord.
    val maxChordFlatnessPx = 0.20
    val maxFlatnessSq = maxChordFlatnessPx * maxChordFlatnessPx

    fun subdivideQuadScreen(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        depth: Int,
    ) {
        if (failureReason != null) return
        if (!budget.tryConsumeStep()) {
            fail("SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted")
            return
        }
        // Convex-hull segment distance bound: for all t in [0, 1],
        // dist(Q(t), segment[p0, p2]) <= 0.5 * dist(p1, segment[p0, p2]).
        val d1Sq = distSqPointToSegmentDouble(x1, y1, x0, y0, x2, y2)
        if (d1Sq.isFinite() && 0.25 * d1Sq <= maxFlatnessSq) {
            if (!budget.tryEmitSegment()) {
                fail("SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted")
                return
            }
            strokeSegs.add(Offset(x0.toFloat(), y0.toFloat()) to Offset(x2.toFloat(), y2.toFloat()))
            return
        }
        if (depth >= budget.maxSubdivDepth) {
            fail("SVG curve exceeded max subdivision depth (${budget.maxSubdivDepth}) before meeting 0.25px screen flatness")
            return
        }
        val q0x = (x0 + x1) * 0.5
        val q0y = (y0 + y1) * 0.5
        val q1x = (x1 + x2) * 0.5
        val q1y = (y1 + y2) * 0.5
        val midX = (q0x + q1x) * 0.5
        val midY = (q0y + q1y) * 0.5
        subdivideQuadScreen(x0, y0, q0x, q0y, midX, midY, depth + 1)
        if (failureReason != null) return
        subdivideQuadScreen(midX, midY, q1x, q1y, x2, y2, depth + 1)
    }

    fun subdivideCubicScreen(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        x3: Double, y3: Double,
        depth: Int,
    ) {
        if (failureReason != null) return
        if (!budget.tryConsumeStep()) {
            fail("SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted")
            return
        }
        // Convex-hull segment distance bound: for all t in [0, 1],
        // dist(B(t), segment[p0, p3]) <= 0.75 * max(dist(p1, segment[p0, p3]), dist(p2, segment[p0, p3])).
        val d1Sq = distSqPointToSegmentDouble(x1, y1, x0, y0, x3, y3)
        val d2Sq = distSqPointToSegmentDouble(x2, y2, x0, y0, x3, y3)
        val worstSq = max(d1Sq, d2Sq)
        if (d1Sq.isFinite() && d2Sq.isFinite() && 0.5625 * worstSq <= maxFlatnessSq) {
            if (!budget.tryEmitSegment()) {
                fail("SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted")
                return
            }
            strokeSegs.add(Offset(x0.toFloat(), y0.toFloat()) to Offset(x3.toFloat(), y3.toFloat()))
            return
        }
        if (depth >= budget.maxSubdivDepth) {
            fail("SVG curve exceeded max subdivision depth (${budget.maxSubdivDepth}) before meeting 0.25px screen flatness")
            return
        }
        val q0x = (x0 + x1) * 0.5
        val q0y = (y0 + y1) * 0.5
        val q1x = (x1 + x2) * 0.5
        val q1y = (y1 + y2) * 0.5
        val q2x = (x2 + x3) * 0.5
        val q2y = (y2 + y3) * 0.5
        val r0x = (q0x + q1x) * 0.5
        val r0y = (q0y + q1y) * 0.5
        val r1x = (q1x + q2x) * 0.5
        val r1y = (q1y + q2y) * 0.5
        val midX = (r0x + r1x) * 0.5
        val midY = (r0y + r1y) * 0.5
        subdivideCubicScreen(x0, y0, q0x, q0y, r0x, r0y, midX, midY, depth + 1)
        if (failureReason != null) return
        subdivideCubicScreen(midX, midY, r1x, r1y, q2x, q2y, x3, y3, depth + 1)
    }

    fun addQuadScreen(c1x: Double, c1y: Double, px: Double, py: Double) {
        if (!ensureSubpathOrigin()) return
        subdivideQuadScreen(curScreenX, curScreenY, c1x, c1y, px, py, 0)
        curScreenX = px
        curScreenY = py
    }

    fun addCubicScreen(c1x: Double, c1y: Double, c2x: Double, c2y: Double, px: Double, py: Double) {
        if (!ensureSubpathOrigin()) return
        subdivideCubicScreen(curScreenX, curScreenY, c1x, c1y, c2x, c2y, px, py, 0)
        curScreenX = px
        curScreenY = py
    }

    val s = d.trim()
    var i = 0
    val n = s.length
    fun isCmd(c: Char): Boolean = c in "MmLlHhVvCcSsQqTtAaZz"
    fun skipSep() {
        var comma = false
        while (i < n) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++
            else if (c == ',' && !comma) { comma = true; i++ }
            else break
        }
    }
    fun skipWs() {
        while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }
    fun readNum(): Double? {
        skipSep()
        if (i >= n) return null
        val start = i
        if (s[i] == '+' || s[i] == '-') i++
        var digits = 0
        while (i < n && s[i] in '0'..'9') { i++; digits++ }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') { i++; digits++ }
        }
        if (digits == 0) { i = start; return null }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            val ep = i; i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            var ed = 0
            while (i < n && s[i] in '0'..'9') { i++; ed++ }
            if (ed == 0) { i = ep; return null }
        }
        return s.substring(start, i).toDoubleOrNull()
    }
    fun readFlag(): Int? {
        skipSep()
        if (i >= n) return null
        return when (s[i]) {
            '0' -> { i++; 0 }
            '1' -> { i++; 1 }
            else -> null
        }
    }

    var curX = 0.0
    var curY = 0.0
    var startX = 0.0
    var startY = 0.0
    var lastCtrlX = 0.0
    var lastCtrlY = 0.0
    var lastCmd = ' '

    while (i < n && failureReason == null) {
        skipWs()
        if (i >= n) break
        val rawCmd = s[i]
        if (!isCmd(rawCmd)) break
        i++
        if (rawCmd == 'Z' || rawCmd == 'z') {
            path.close()
            if (hasSubpath && (curScreenX != startScreenX || curScreenY != startScreenY)) {
                if (!budget.tryEmitSegment()) {
                    fail("SVG stroke segment budget (${budget.maxStrokeSegments} segments) exhausted")
                    break
                }
                strokeSegs.add(
                    Offset(curScreenX.toFloat(), curScreenY.toFloat()) to Offset(startScreenX.toFloat(), startScreenY.toFloat()),
                )
            }
            curScreenX = startScreenX
            curScreenY = startScreenY
            curX = startX
            curY = startY
            lastCtrlX = curX
            lastCtrlY = curY
            lastCmd = rawCmd
            continue
        }
        var cmd = rawCmd
        while (failureReason == null) {
            skipWs()
            if (i >= n || isCmd(s[i])) break
            if (s[i] == ',') i++
            when (cmd) {
                'M', 'm' -> {
                    val nx = readNum() ?: break
                    val ny = readNum() ?: break
                    if (!budget.tryConsumeStep()) {
                        fail("SVG curve flattening work budget (${budget.maxFlattenSteps} steps) exhausted")
                        break
                    }
                    curX = if (cmd == 'm') curX + nx else nx
                    curY = if (cmd == 'm') curY + ny else ny
                    startX = curX; startY = curY
                    val (px, py) = recordLocalScreen(curX, curY) ?: break
                    path.moveTo(px.toFloat(), py.toFloat())
                    curScreenX = px
                    curScreenY = py
                    startScreenX = px
                    startScreenY = py
                    hasSubpath = true
                    lastCtrlX = curX; lastCtrlY = curY
                    lastCmd = cmd
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val nx = readNum() ?: break
                    val ny = readNum() ?: break
                    if (!ensureSubpathOrigin()) break
                    curX = if (cmd == 'l') curX + nx else nx
                    curY = if (cmd == 'l') curY + ny else ny
                    val (px, py) = recordLocalScreen(curX, curY) ?: break
                    path.lineTo(px.toFloat(), py.toFloat())
                    addLineScreen(px, py)
                    lastCtrlX = curX; lastCtrlY = curY
                    lastCmd = cmd
                }
                'H', 'h' -> {
                    val nx = readNum() ?: break
                    if (!ensureSubpathOrigin()) break
                    curX = if (cmd == 'h') curX + nx else nx
                    val (px, py) = recordLocalScreen(curX, curY) ?: break
                    path.lineTo(px.toFloat(), py.toFloat())
                    addLineScreen(px, py)
                    lastCtrlX = curX; lastCtrlY = curY
                    lastCmd = cmd
                }
                'V', 'v' -> {
                    val ny = readNum() ?: break
                    if (!ensureSubpathOrigin()) break
                    curY = if (cmd == 'v') curY + ny else ny
                    val (px, py) = recordLocalScreen(curX, curY) ?: break
                    path.lineTo(px.toFloat(), py.toFloat())
                    addLineScreen(px, py)
                    lastCtrlX = curX; lastCtrlY = curY
                    lastCmd = cmd
                }
                'C', 'c' -> {
                    val x1 = (readNum() ?: break).let { if (cmd == 'c') curX + it else it }
                    val y1 = (readNum() ?: break).let { if (cmd == 'c') curY + it else it }
                    val x2 = (readNum() ?: break).let { if (cmd == 'c') curX + it else it }
                    val y2 = (readNum() ?: break).let { if (cmd == 'c') curY + it else it }
                    val x = (readNum() ?: break).let { if (cmd == 'c') curX + it else it }
                    val y = (readNum() ?: break).let { if (cmd == 'c') curY + it else it }
                    if (!ensureSubpathOrigin()) break
                    val (p1x, p1y) = recordLocalScreen(x1, y1) ?: break
                    val (p2x, p2y) = recordLocalScreen(x2, y2) ?: break
                    val (px, py) = recordLocalScreen(x, y) ?: break
                    path.cubicTo(p1x.toFloat(), p1y.toFloat(), p2x.toFloat(), p2y.toFloat(), px.toFloat(), py.toFloat())
                    addCubicScreen(p1x, p1y, p2x, p2y, px, py)
                    curX = x; curY = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastCmd = cmd
                }
                'S', 's' -> {
                    val x1 = if (lastCmd in "CcSs") 2.0 * curX - lastCtrlX else curX
                    val y1 = if (lastCmd in "CcSs") 2.0 * curY - lastCtrlY else curY
                    val x2 = (readNum() ?: break).let { if (cmd == 's') curX + it else it }
                    val y2 = (readNum() ?: break).let { if (cmd == 's') curY + it else it }
                    val x = (readNum() ?: break).let { if (cmd == 's') curX + it else it }
                    val y = (readNum() ?: break).let { if (cmd == 's') curY + it else it }
                    if (!ensureSubpathOrigin()) break
                    val (p1x, p1y) = recordLocalScreen(x1, y1) ?: break
                    val (p2x, p2y) = recordLocalScreen(x2, y2) ?: break
                    val (px, py) = recordLocalScreen(x, y) ?: break
                    path.cubicTo(p1x.toFloat(), p1y.toFloat(), p2x.toFloat(), p2y.toFloat(), px.toFloat(), py.toFloat())
                    addCubicScreen(p1x, p1y, p2x, p2y, px, py)
                    curX = x; curY = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastCmd = cmd
                }
                'Q', 'q' -> {
                    val x1 = (readNum() ?: break).let { if (cmd == 'q') curX + it else it }
                    val y1 = (readNum() ?: break).let { if (cmd == 'q') curY + it else it }
                    val x = (readNum() ?: break).let { if (cmd == 'q') curX + it else it }
                    val y = (readNum() ?: break).let { if (cmd == 'q') curY + it else it }
                    if (!ensureSubpathOrigin()) break
                    val (p1x, p1y) = recordLocalScreen(x1, y1) ?: break
                    val (px, py) = recordLocalScreen(x, y) ?: break
                    path.quadraticTo(p1x.toFloat(), p1y.toFloat(), px.toFloat(), py.toFloat())
                    addQuadScreen(p1x, p1y, px, py)
                    curX = x; curY = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastCmd = cmd
                }
                'T', 't' -> {
                    val x1 = if (lastCmd in "QqTt") 2.0 * curX - lastCtrlX else curX
                    val y1 = if (lastCmd in "QqTt") 2.0 * curY - lastCtrlY else curY
                    val x = (readNum() ?: break).let { if (cmd == 't') curX + it else it }
                    val y = (readNum() ?: break).let { if (cmd == 't') curY + it else it }
                    if (!ensureSubpathOrigin()) break
                    val (p1x, p1y) = recordLocalScreen(x1, y1) ?: break
                    val (px, py) = recordLocalScreen(x, y) ?: break
                    path.quadraticTo(p1x.toFloat(), p1y.toFloat(), px.toFloat(), py.toFloat())
                    addQuadScreen(p1x, p1y, px, py)
                    curX = x; curY = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastCmd = cmd
                }
                'A', 'a' -> {
                    val rx = readNum() ?: break
                    val ry = readNum() ?: break
                    val xRot = readNum() ?: break
                    val largeArc = readFlag() ?: break
                    val sweep = readFlag() ?: break
                    val x = (readNum() ?: break).let { if (cmd == 'a') curX + it else it }
                    val y = (readNum() ?: break).let { if (cmd == 'a') curY + it else it }
                    if (!ensureSubpathOrigin()) break
                    appendArcAsCubics(
                        x0 = curX,
                        y0 = curY,
                        rxIn = rx,
                        ryIn = ry,
                        xAxisRotDeg = xRot,
                        largeArc = largeArc == 1,
                        sweep = sweep == 1,
                        x1 = x,
                        y1 = y,
                        matrix = matrix,
                        maxSegmentsCap = budget.maxFlattenSteps,
                        onFail = ::fail,
                    ) { c1x, c1y, c2x, c2y, ex, ey ->
                        if (failureReason != null) return@appendArcAsCubics
                        val (p1x, p1y) = recordLocalScreen(c1x, c1y) ?: return@appendArcAsCubics
                        val (p2x, p2y) = recordLocalScreen(c2x, c2y) ?: return@appendArcAsCubics
                        val (px, py) = recordLocalScreen(ex, ey) ?: return@appendArcAsCubics
                        path.cubicTo(p1x.toFloat(), p1y.toFloat(), p2x.toFloat(), p2y.toFloat(), px.toFloat(), py.toFloat())
                        addCubicScreen(p1x, p1y, p2x, p2y, px, py)
                    }
                    if (failureReason != null) break
                    curX = x; curY = y
                    lastCtrlX = curX; lastCtrlY = curY
                    lastCmd = cmd
                }
                else -> break
            }
        }
    }
    if (failureReason != null) {
        budget.emittedSegments = savedEmitted
        return ParsedCanvasPath(Path(), 0.0, 0.0, 0.0, 0.0, emptyList(), failureReason = failureReason)
    }
    if (!minX.isFinite()) {
        minX = 0.0; minY = 0.0; maxX = 0.0; maxY = 0.0
    }
    return ParsedCanvasPath(path, minX, minY, maxX, maxY, strokeSegs)
}

private fun appendArcAsCubics(
    x0: Double,
    y0: Double,
    rxIn: Double,
    ryIn: Double,
    xAxisRotDeg: Double,
    largeArc: Boolean,
    sweep: Boolean,
    x1: Double,
    y1: Double,
    matrix: PortableV6Spec.Affine2D,
    maxSegmentsCap: Int,
    onFail: (String) -> Unit,
    emitCubic: (Double, Double, Double, Double, Double, Double) -> Unit,
) {
    if (!x0.isFinite() || !y0.isFinite() || !rxIn.isFinite() || !ryIn.isFinite() ||
        !xAxisRotDeg.isFinite() || !x1.isFinite() || !y1.isFinite()
    ) {
        onFail("non-finite SVG arc parameters")
        return
    }
    if (x0 == x1 && y0 == y1) return
    var rx = abs(rxIn)
    var ry = abs(ryIn)
    if (rx < 1e-9 || ry < 1e-9) {
        emitCubic(x0, y0, x1, y1, x1, y1)
        return
    }
    val phi = xAxisRotDeg * kotlin.math.PI / 180.0
    val cosPhi = kotlin.math.cos(phi)
    val sinPhi = kotlin.math.sin(phi)
    val dx2 = (x0 - x1) * 0.5
    val dy2 = (y0 - y1) * 0.5
    val x1p = cosPhi * dx2 + sinPhi * dy2
    val y1p = -sinPhi * dx2 + cosPhi * dy2

    val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if (!lambda.isFinite()) {
        onFail("SVG arc radius scaling overflowed finite range")
        return
    }
    if (lambda > 1.0) {
        val s = kotlin.math.sqrt(lambda)
        rx *= s
        ry *= s
    }
    val rxSq = rx * rx
    val rySq = ry * ry
    val x1pSq = x1p * x1p
    val y1pSq = y1p * y1p
    val num = (rxSq * rySq - rxSq * y1pSq - rySq * x1pSq).coerceAtLeast(0.0)
    val den = (rxSq * y1pSq + rySq * x1pSq).coerceAtLeast(1e-12)
    val sign = if (largeArc == sweep) -1.0 else 1.0
    val coef = sign * kotlin.math.sqrt(num / den)
    val cxp = coef * (rx * y1p / ry)
    val cyp = coef * (-ry * x1p / rx)
    val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) * 0.5
    val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) * 0.5

    fun vecAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = (hypot(ux, uy) * hypot(vx, vy)).coerceAtLeast(1e-12)
        val ang = kotlin.math.acos((dot / len).coerceIn(-1.0, 1.0))
        return if (ux * vy - uy * vx < 0.0) -ang else ang
    }

    val theta1 = vecAngle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var dTheta = vecAngle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if (!sweep && dTheta > 0.0) dTheta -= 2.0 * kotlin.math.PI
    if (sweep && dTheta < 0.0) dTheta += 2.0 * kotlin.math.PI

    val maxScale = max(hypot(matrix.a, matrix.b), hypot(matrix.c, matrix.d))
    val screenR = max(rx, ry) * maxScale
    if (!cx.isFinite() || !cy.isFinite() || !theta1.isFinite() || !dTheta.isFinite() || !screenR.isFinite()) {
        onFail("transformed SVG arc geometry overflowed finite floating-point range")
        return
    }
    // Bound arc-to-cubic radial approximation error `(4/27) * (step/4)^6 * screenR <= 0.05px` so that
    // combined with `0.20px` cubic chord flattening, total screen-space arc error never exceeds `0.25px`.
    val maxStepAngle = if (screenR <= 5_000.0) {
        kotlin.math.PI * 0.25
    } else {
        (4.0 * (0.05 * 27.0 / (4.0 * screenR)).pow(1.0 / 6.0)).coerceAtMost(kotlin.math.PI * 0.25)
    }
    val rawSegments = kotlin.math.ceil(abs(dTheta) / maxStepAngle)
    if (!rawSegments.isFinite() || rawSegments > maxSegmentsCap.toDouble()) {
        onFail("SVG arc subdivision exceeded work budget ($maxSegmentsCap steps)")
        return
    }
    val segments = rawSegments.toInt().coerceAtLeast(1)
    val step = dTheta / segments
    val alpha = (4.0 / 3.0) * kotlin.math.tan(step * 0.25)

    for (seg in 0 until segments) {
        val t0 = theta1 + seg * step
        val t1 = t0 + step
        val cos0 = kotlin.math.cos(t0); val sin0 = kotlin.math.sin(t0)
        val cos1 = kotlin.math.cos(t1); val sin1 = kotlin.math.sin(t1)
        val e1x = cos0 - alpha * sin0; val e1y = sin0 + alpha * cos0
        val e2x = cos1 + alpha * sin1; val e2y = sin1 - alpha * cos1
        val c1x = cx + cosPhi * rx * e1x - sinPhi * ry * e1y
        val c1y = cy + sinPhi * rx * e1x + cosPhi * ry * e1y
        val c2x = cx + cosPhi * rx * e2x - sinPhi * ry * e2y
        val c2y = cy + sinPhi * rx * e2x + cosPhi * ry * e2y
        val ex = cx + cosPhi * rx * cos1 - sinPhi * ry * sin1
        val ey = cy + sinPhi * rx * cos1 + cosPhi * ry * sin1
        emitCubic(c1x, c1y, c2x, c2y, ex, ey)
    }
}

private fun parseSvgLengthPx(el: kotlinx.serialization.json.JsonElement?, defaultPx: Float): Float {
    val prim = el as? JsonPrimitive ?: return defaultPx
    if (!prim.isString) return prim.doubleOrNull?.toFloat() ?: defaultPx
    val clean = prim.content.trim().removeSuffix("px")
    return clean.toFloatOrNull() ?: defaultPx
}

private fun parsePaintColor(raw: String?, currentColor: Color = Color(0xFF1F2933)): Color? {
    if (raw.isNullOrBlank() || raw == "none") return null
    val trimmed = raw.trim()
    val s = trimmed.lowercase()
    if (s == "currentcolor") return currentColor
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                Color(r * 17, g * 17, b * 17, 255)
            }
            4 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                val a = hex[3].digitToIntOrNull(16) ?: return null
                Color(r * 17, g * 17, b * 17, a * 17)
            }
            6 -> {
                val rgb = hex.toLongOrNull(16) ?: return null
                Color((0xFF000000L or rgb))
            }
            8 -> {
                val rgba = hex.toLongOrNull(16) ?: return null
                val r = ((rgba shr 24) and 0xFF).toInt()
                val g = ((rgba shr 16) and 0xFF).toInt()
                val b = ((rgba shr 8) and 0xFF).toInt()
                val a = (rgba and 0xFF).toInt()
                Color(r, g, b, a)
            }
            else -> null
        }
    }
    return when (s) {
        "black" -> Color.Black
        "white" -> Color.White
        "transparent" -> Color.Transparent
        "red", "crimson", "tomato" -> Color(0xFFDC2626)
        "green", "lime" -> Color(0xFF16A34A)
        "blue", "royalblue", "steelblue" -> Color(0xFF2563EB)
        "orange", "gold", "yellow" -> Color(0xFFD97706)
        "gray", "grey", "slategray", "dimgray" -> Color(0xFF64748B)
        "teal", "cyan" -> Color(0xFF0D9488)
        "purple", "violet", "indigo" -> Color(0xFF7C3AED)
        else -> null
    }
}

private fun resolveGapDp(raw: String): Dp = when (raw) {
    "none" -> 0.dp
    "xs" -> 4.dp
    "sm" -> 8.dp
    "md" -> 12.dp
    "lg" -> 20.dp
    else -> 8.dp
}

internal sealed class CssTransformStep {
    data class Translate(val dxDp: Float, val dyDp: Float) : CssTransformStep()
    data class Rotate(val degrees: Float) : CssTransformStep()
    data class Scale(val sx: Float, val sy: Float) : CssTransformStep()
}

internal fun parseCssTransformSteps(transformRaw: String?): List<CssTransformStep> {
    if (transformRaw.isNullOrBlank()) return emptyList()
    val out = ArrayList<CssTransformStep>()
    for (match in Regex("([a-zA-Z]+)\\s*\\(([^()]+)\\)").findAll(transformRaw)) {
        val fn = match.groupValues[1]
        val args = match.groupValues[2].trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        when (fn) {
            "translate" -> {
                val dx = args.getOrNull(0)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                val dy = args.getOrNull(1)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                out.add(CssTransformStep.Translate(dx, dy))
            }
            "translateX" -> {
                val dx = args.getOrNull(0)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                out.add(CssTransformStep.Translate(dx, 0f))
            }
            "translateY" -> {
                val dy = args.getOrNull(0)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                out.add(CssTransformStep.Translate(0f, dy))
            }
            "rotate" -> {
                val deg = args.getOrNull(0)?.removeSuffix("deg")?.toFloatOrNull() ?: 0f
                if (args.size == 3) {
                    val cx = args.getOrNull(1)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                    val cy = args.getOrNull(2)?.removeSuffix("px")?.toFloatOrNull() ?: 0f
                    out.add(CssTransformStep.Translate(cx, cy))
                    out.add(CssTransformStep.Rotate(deg))
                    out.add(CssTransformStep.Translate(-cx, -cy))
                } else {
                    out.add(CssTransformStep.Rotate(deg))
                }
            }
            "scale" -> {
                val s0 = args.getOrNull(0)?.toFloatOrNull() ?: 1f
                val s1 = args.getOrNull(1)?.toFloatOrNull() ?: s0
                out.add(CssTransformStep.Scale(s0, s1))
            }
            "scaleX" -> {
                val sx = args.getOrNull(0)?.toFloatOrNull() ?: 1f
                out.add(CssTransformStep.Scale(sx, 1f))
            }
            "scaleY" -> {
                val sy = args.getOrNull(0)?.toFloatOrNull() ?: 1f
                out.add(CssTransformStep.Scale(1f, sy))
            }
        }
    }
    return out
}

/**
 * Evaluates an ordered CSS 2D `transform` function list around the CSS default `transform-origin: 50% 50%`
 * (`T(cx, cy) * F_1 * F_2 * ... * F_n * T(-cx, -cy)`), preserving exact non-commutative matrix multiplication order.
 */
internal fun evaluateCssTransformMatrix(
    steps: List<CssTransformStep>,
    widthPx: Double,
    heightPx: Double,
    density: Double = 1.0,
): PortableV6Spec.Affine2D {
    if (steps.isEmpty()) return PortableV6Spec.Affine2D.IDENTITY
    val cx = widthPx * 0.5
    val cy = heightPx * 0.5
    var m = PortableV6Spec.Affine2D(1.0, 0.0, 0.0, 1.0, cx, cy)
    for (step in steps) {
        val stepMatrix = when (step) {
            is CssTransformStep.Translate -> PortableV6Spec.Affine2D(1.0, 0.0, 0.0, 1.0, step.dxDp * density, step.dyDp * density)
            is CssTransformStep.Scale -> PortableV6Spec.Affine2D(step.sx.toDouble(), 0.0, 0.0, step.sy.toDouble(), 0.0, 0.0)
            is CssTransformStep.Rotate -> {
                val rad = step.degrees.toDouble() * kotlin.math.PI / 180.0
                val c = kotlin.math.cos(rad)
                val s = kotlin.math.sin(rad)
                PortableV6Spec.Affine2D(c, s, -s, c, 0.0, 0.0)
            }
        }
        m = m.times(stepMatrix)
    }
    return m.times(PortableV6Spec.Affine2D(1.0, 0.0, 0.0, 1.0, -cx, -cy))
}

private fun Modifier.applyCatalogMotionAndSize(props: JsonObject): Modifier {
    var m = this
    val w = (props["width"] as? JsonPrimitive)?.doubleOrNull
    val h = (props["height"] as? JsonPrimitive)?.doubleOrNull
    val minH = (props["minHeight"] as? JsonPrimitive)?.doubleOrNull
    if (w != null) m = m.width(w.dp)
    if (h != null) m = m.height(h.dp)
    if (minH != null) m = m.heightIn(min = minH.dp)
    val opacityVal = (props["opacity"] as? JsonPrimitive)?.doubleOrNull
    if (opacityVal != null) {
        m = m.alpha(opacityVal.toFloat().coerceIn(0f, 1f))
    }
    val transformSteps = parseCssTransformSteps((props["transform"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    // Apply each step as its own `Modifier.graphicsLayer` around `TransformOrigin.Center` (`50% 50%`).
    // Because `graphicsLayer` preserves child layout `(width, height)`, every layer in the chain shares the exact
    // same pivot `C = T(cx, cy)`, so `(C * F_1 * C^-1) * ... * (C * F_k * C^-1)` telescopes to `C * F_1 * ... * F_k * C^-1`
    // while driving Compose `NodeCoordinator` pointer hit-testing, focus, semantics bounds, and Skia rendering identically.
    for (step in transformSteps) {
        m = m.graphicsLayer {
            transformOrigin = TransformOrigin.Center
            when (step) {
                is CssTransformStep.Translate -> {
                    translationX = step.dxDp * density
                    translationY = step.dyDp * density
                }
                is CssTransformStep.Rotate -> {
                    rotationZ = step.degrees
                }
                is CssTransformStep.Scale -> {
                    scaleX = step.sx
                    scaleY = step.sy
                }
            }
        }
    }
    return m
}

private fun initialsFromName(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(2).uppercase()
    return (parts[0].take(1) + parts[1].take(1)).uppercase()
}

// ---- Default Pure-Foundation Catalog Registry (full 32 `RAFT_UI_COMPONENTS` + 10 `RAFT_UI_SVG_EXTRAS`) ----

@OptIn(ExperimentalLayoutApi::class)
val defaultSproutComposeRegistry: SproutComponentRegistry = sproutComponentRegistry {
    component("Stack") {
        val gapDp = resolveGapDp(stringProp("gap", "md"))
        val align = stringProp("align", "stretch")
        val hAlign = when (align) {
            "start" -> Alignment.Start
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        }
        var mod: Modifier = Modifier
        if ("width" !in props && align == "stretch") {
            mod = mod.fillMaxWidth()
        }
        mod = mod.applyCatalogMotionAndSize(props)
        Column(
            modifier = mod.testTag("sprout-stack:${element.id}"),
            verticalArrangement = Arrangement.spacedBy(gapDp),
            horizontalAlignment = hAlign,
        ) {
            renderChildren()
        }
    }

    component("Row") {
        val gapDp = resolveGapDp(stringProp("gap", "sm"))
        val align = stringProp("align", "center")
        val justify = stringProp("justify", "start")
        val wrap = boolProp("wrap", false)
        val vAlign = when (align) {
            "start" -> Alignment.Top
            "end" -> Alignment.Bottom
            else -> Alignment.CenterVertically
        }
        val hArrange: Arrangement.Horizontal = when (justify) {
            "between" -> Arrangement.SpaceBetween
            "center" -> Arrangement.spacedBy(gapDp, Alignment.CenterHorizontally)
            "end" -> Arrangement.spacedBy(gapDp, Alignment.End)
            else -> Arrangement.spacedBy(gapDp, Alignment.Start)
        }
        var mod: Modifier = Modifier
        if ("width" !in props) mod = mod.fillMaxWidth()
        mod = mod.applyCatalogMotionAndSize(props)
        if (wrap) {
            FlowRow(
                modifier = mod.testTag("sprout-row:${element.id}"),
                horizontalArrangement = hArrange,
                verticalArrangement = Arrangement.spacedBy(gapDp),
            ) {
                renderChildren()
            }
        } else {
            Row(
                modifier = mod.testTag("sprout-row:${element.id}"),
                horizontalArrangement = hArrange,
                verticalAlignment = vAlign,
            ) {
                renderChildren()
            }
        }
    }

    component("Grid") {
        val cols = doubleProp("columns", 2.0).toInt().coerceIn(1, 4)
        val gapDp = resolveGapDp(stringProp("gap", "sm"))
        val childElements = element.children
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .applyCatalogMotionAndSize(props)
                .testTag("sprout-grid:${element.id}"),
            verticalArrangement = Arrangement.spacedBy(gapDp),
        ) {
            for (rowNodes in childElements.chunked(cols)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapDp),
                ) {
                    for (cellNode in rowNodes) {
                        Box(modifier = Modifier.weight(1f)) {
                            renderNode(cellNode)
                        }
                    }
                    repeat(cols - rowNodes.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    component("Box") {
        val clip = boolProp("clip", false)
        var mod = Modifier.applyCatalogMotionAndSize(props)
        if (clip) mod = mod.clipToBounds()
        Box(
            modifier = mod.testTag("sprout-box:${element.id}"),
        ) {
            renderChildren()
        }
    }

    component("Text") {
        val fontSize = when (stringProp("size", "body")) {
            "small" -> 12.sp
            "title" -> 16.sp
            else -> 14.sp
        }
        val color = when (stringProp("tone", "default")) {
            "muted" -> Color(0xFF64748B)
            "danger" -> Color(0xFFDC2626)
            "success" -> Color(0xFF16A34A)
            else -> Color(0xFF1E293B)
        }
        val weight = if (stringProp("weight") == "bold") FontWeight.Bold else FontWeight.Normal
        val style = TextStyle(fontSize = fontSize, color = color, fontWeight = weight)
        val allInline = element.children.all { isInlineTextRunNode(it) }
        if (allInline) {
            val annotated = buildInlineAnnotatedRun(element.children, style)
            BasicText(
                text = annotated,
                style = style,
                modifier = Modifier.testTag("sprout-text:${element.id}"),
            )
        } else {
            CompositionLocalProvider(LocalSproutTextStyle provides style) {
                Box(modifier = Modifier.testTag("sprout-text:${element.id}")) {
                    renderChildren()
                }
            }
        }
    }

    component("Heading") {
        val level = doubleProp("level", 2.0).toInt().coerceIn(1, 3)
        val size = when (level) {
            1 -> 20.sp
            2 -> 17.sp
            else -> 15.sp
        }
        val style = TextStyle(fontSize = size, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        val annotated = buildInlineAnnotatedRun(element.children, style)
        BasicText(
            text = if (annotated.isNotEmpty()) annotated else androidx.compose.ui.text.AnnotatedString(textChildrenJoined()),
            style = style,
            modifier = Modifier
                .semantics { heading() }
                .testTag("sprout-heading:${element.id}"),
        )
    }

    component("Divider") {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFE2E8F0))
                .testTag("sprout-divider:${element.id}"),
        )
    }

    component("Button") {
        val label = stringProp("label")
        val disabled = boolProp("disabled", false)
        val variant = stringProp("variant", "default")
        val size = stringProp("size", "md")
        val (padH, padV, fontSp) = when (size) {
            "xs" -> Triple(8.dp, 4.dp, 11.sp)
            "sm" -> Triple(10.dp, 5.dp, 12.sp)
            "lg" -> Triple(16.dp, 9.dp, 14.sp)
            "xl" -> Triple(20.dp, 11.dp, 15.sp)
            else -> Triple(12.dp, 7.dp, 13.sp)
        }
        val (bg, fg, borderCol) = when {
            disabled -> Triple(Color(0xFFE2E8F0), Color(0xFF94A3B8), null)
            variant == "primary" -> Triple(Color(0xFF2563EB), Color.White, null)
            variant == "accent" -> Triple(Color(0xFF7C3AED), Color.White, null)
            variant == "success" -> Triple(Color(0xFF16A34A), Color.White, null)
            variant == "warning" -> Triple(Color(0xFFD97706), Color.White, null)
            variant == "danger" -> Triple(Color(0xFFDC2626), Color.White, null)
            variant == "information" -> Triple(Color(0xFF0284C7), Color.White, null)
            variant == "danger-secondary" -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), null)
            variant == "danger-outline" -> Triple(Color.Transparent, Color(0xFFDC2626), Color(0xFFFCA5A5))
            variant == "outline" -> Triple(Color.White, Color(0xFF1E293B), Color(0xFFCBD5E1))
            variant == "ghost" -> Triple(Color.Transparent, Color(0xFF1E293B), null)
            variant == "muted" -> Triple(Color(0xFFF1F5F9), Color(0xFF64748B), null)
            else -> Triple(Color(0xFFF1F5F9), Color(0xFF1E293B), null)
        }
        val shape = RoundedCornerShape(7.dp)
        var mod = Modifier
            .clip(shape)
            .background(bg)
        if (borderCol != null) {
            mod = mod.border(1.dp, borderCol, shape)
        }
        val ariaLabel = stringProp("ariaLabel")
        if (ariaLabel.isNotEmpty()) {
            mod = mod.semantics { contentDescription = ariaLabel }
        }
        mod = mod
            .clickable(enabled = !disabled && hasEvent("click"), role = Role.Button) {
                emitUserEvent("click")
            }
            .padding(horizontal = padH, vertical = padV)
            .testTag("sprout-button:${element.id}")

        val textStyle = TextStyle(fontSize = fontSp, fontWeight = FontWeight.Medium, color = fg)
        Box(modifier = mod, contentAlignment = Alignment.Center) {
            if (label.isNotEmpty() || element.children.all { it is TreeNode.Text }) {
                BasicText(
                    text = label.ifEmpty { textChildrenJoined().ifEmpty { "Button" } },
                    style = textStyle,
                )
            } else {
                CompositionLocalProvider(LocalSproutTextStyle provides textStyle) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        renderChildren()
                    }
                }
            }
        }
    }

    component("Badge") {
        val variant = stringProp("variant", "default")
        val appearance = stringProp("appearance", "soft")
        val (solidBg, softBg, toneText, outlineBorder) = when (variant) {
            "primary" -> listOf(Color(0xFF2563EB), Color(0xFFDBEAFE), Color(0xFF1D4ED8), Color(0xFF93C5FD))
            "information" -> listOf(Color(0xFF0284C7), Color(0xFFE0F2FE), Color(0xFF0369A1), Color(0xFF7DD3FC))
            "accent" -> listOf(Color(0xFF7C3AED), Color(0xFFEDE9FE), Color(0xFF6D28D9), Color(0xFFC4B5FD))
            "success" -> listOf(Color(0xFF16A34A), Color(0xFFDCFCE7), Color(0xFF15803D), Color(0xFF86EFAC))
            "warning" -> listOf(Color(0xFFD97706), Color(0xFFFEF3C7), Color(0xFFB45309), Color(0xFFFCD34D))
            "danger" -> listOf(Color(0xFFDC2626), Color(0xFFFEE2E2), Color(0xFFB91C1C), Color(0xFFFCA5A5))
            "muted" -> listOf(Color(0xFF64748B), Color(0xFFF1F5F9), Color(0xFF475569), Color(0xFFCBD5E1))
            else -> listOf(Color(0xFF334155), Color(0xFFE2E8F0), Color(0xFF1E293B), Color(0xFFCBD5E1))
        }
        val bg = when (appearance) {
            "solid" -> solidBg
            "outline" -> Color.Transparent
            else -> softBg
        }
        val fg = if (appearance == "solid") Color.White else toneText
        val shape = RoundedCornerShape(999.dp)
        var mod = Modifier.clip(shape).background(bg)
        if (appearance == "outline") {
            mod = mod.border(1.dp, outlineBorder, shape)
        }
        Box(
            modifier = mod
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("sprout-badge:${element.id}"),
        ) {
            val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = fg)
            if (element.children.all { isInlineTextRunNode(it) }) {
                BasicText(buildInlineAnnotatedRun(element.children, style), style = style)
            } else {
                CompositionLocalProvider(LocalSproutTextStyle provides style) {
                    renderChildren()
                }
            }
        }
    }

    component("Card") {
        val title = stringProp("title")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                .background(Color(0xFFFAFBFD))
                .padding(12.dp)
                .testTag("sprout-card:${element.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title.isNotEmpty()) {
                BasicText(
                    text = title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)),
                    modifier = Modifier.testTag("sprout-card-title:${element.id}"),
                )
            }
            renderChildren()
        }
    }

    component("CardHeader") {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("sprout-card-header:${element.id}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            renderChildren()
        }
    }

    component("CardTitle") {
        val style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        CompositionLocalProvider(LocalSproutTextStyle provides style) {
            Box(modifier = Modifier.fillMaxWidth().testTag("sprout-card-title-slot:${element.id}")) {
                renderChildren()
            }
        }
    }

    component("CardDescription") {
        val style = TextStyle(fontSize = 13.sp, color = Color(0xFF64748B))
        CompositionLocalProvider(LocalSproutTextStyle provides style) {
            Box(modifier = Modifier.fillMaxWidth().testTag("sprout-card-description:${element.id}")) {
                renderChildren()
            }
        }
    }

    component("CardContent") {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("sprout-card-content:${element.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            renderChildren()
        }
    }

    component("CardFooter") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("sprout-card-footer:${element.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            renderChildren()
        }
    }

    component("Input") {
        val label = stringProp("label")
        val value = stringProp("value")
        val placeholder = stringProp("placeholder")
        val inputType = stringProp("type", "text")
        val disabled = boolProp("disabled", false)
        var overLimitCodePoints by remember(element.id) { mutableStateOf<Int?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label.isNotEmpty()) {
                BasicText(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF475569)))
            }
            ImeSafeTextField(
                controlledValue = value,
                nodeId = element.id,
                enabled = !disabled,
                placeholder = placeholder,
                onValueCommitted = { nextText ->
                    val cpLen = nextText.unicodeCodePointCount()
                    if (cpLen > 4_000) {
                        overLimitCodePoints = cpLen
                        onDiagnostic(
                            ProtocolDiagnostic(
                                code = "LIMIT_EXCEEDED",
                                phase = "event",
                                message = "Input length $cpLen exceeds 4000 Unicode code points; kept as unsaved native draft",
                                nodeId = element.id,
                                origin = DiagnosticOrigin.HOST,
                            ),
                        )
                    } else {
                        overLimitCodePoints = null
                        if (hasEvent("valueChange")) {
                            emitUserEvent("valueChange", buildJsonArray { add(JsonPrimitive(nextText)) })
                        } else if (hasEvent("change")) {
                            emitUserEvent(
                                "change",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "\$event",
                                                buildJsonObject {
                                                    put("type", "change")
                                                    put("value", nextText)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                },
                inputType = inputType,
                isControlled = "value" in props,
            )
            if (overLimitCodePoints != null) {
                BasicText(
                    text = "Input longer than 4000 characters ($overLimitCodePoints); draft not sent",
                    style = TextStyle(fontSize = 11.sp, color = Color(0xFFB91C1C)),
                    modifier = Modifier.testTag("sprout-input-overlimit:${element.id}"),
                )
            }
        }
    }

    component("Checkbox") {
        val checked = boolProp("checked", false)
        val disabled = boolProp("disabled", false)
        val label = stringProp("label")
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .toggleable(
                    value = checked,
                    enabled = !disabled && hasEvent("checkedChange"),
                    role = Role.Checkbox,
                    onValueChange = { next ->
                        emitUserEvent(
                            "checkedChange",
                            buildJsonArray {
                                add(JsonPrimitive(next))
                            },
                        )
                    },
                )
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("sprout-checkbox:${element.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) Color(0xFF2563EB) else Color(0xFFFFFFFF))
                    .border(1.5.dp, if (checked) Color(0xFF2563EB) else Color(0xFF94A3B8), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    BasicText("✓", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White))
                }
            }
            if (label.isNotEmpty()) {
                BasicText(label, style = TextStyle(fontSize = 14.sp, color = Color(0xFF1E293B)))
            }
        }
    }

    component("Switch") {
        val checked = boolProp("checked", false)
        val disabled = boolProp("disabled", false)
        val label = stringProp("label")
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .toggleable(
                    value = checked,
                    enabled = !disabled && hasEvent("checkedChange"),
                    role = Role.Switch,
                    onValueChange = { next ->
                        emitUserEvent(
                            "checkedChange",
                            buildJsonArray {
                                add(JsonPrimitive(next))
                            },
                        )
                    },
                )
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("sprout-switch:${element.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (checked) Color(0xFF2563EB) else Color(0xFFCBD5E1))
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White),
                )
            }
            if (label.isNotEmpty()) {
                BasicText(label, style = TextStyle(fontSize = 14.sp, color = Color(0xFF1E293B)))
            }
        }
    }

    component("Tabs") {
        val isControlled = "value" in props
        val controlledVal = stringProp("value")
        val defaultVal = stringProp("defaultValue")
        var uncontrolledVal by remember(element.id) { mutableStateOf(defaultVal) }
        val activeVal = if (isControlled) controlledVal else uncontrolledVal
        val tabsState = SproutTabsState(
            activeValue = activeVal,
            onSelectTab = { nextVal ->
                if (!isControlled) uncontrolledVal = nextVal
                if (hasEvent("valueChange")) {
                    emitUserEvent("valueChange", buildJsonArray { add(JsonPrimitive(nextVal)) })
                }
            },
        )
        CompositionLocalProvider(LocalSproutTabsState provides tabsState) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("sprout-tabs:${element.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                renderChildren()
            }
        }
    }

    component("TabsList") {
        val variant = stringProp("variant", "default")
        val mod = if (variant == "underline") {
            Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        } else {
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF1F5F9))
                .padding(3.dp)
        }
        CompositionLocalProvider(LocalSproutTabsVariant provides variant) {
            Row(
                modifier = mod.testTag("sprout-tabs-list:${element.id}"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                renderChildren()
            }
        }
    }

    component("TabsTab") {
        val tabVal = stringProp("value")
        val disabled = boolProp("disabled", false)
        val tabsState = LocalSproutTabsState.current
        val variant = LocalSproutTabsVariant.current
        val isSelected = tabsState?.activeValue == tabVal
        val bg = when {
            isSelected && variant == "underline" -> Color(0xFFEFF6FF)
            isSelected -> Color.White
            else -> Color.Transparent
        }
        val fg = when {
            disabled -> Color(0xFF94A3B8)
            isSelected -> Color(0xFF2563EB)
            else -> Color(0xFF475569)
        }
        val style = TextStyle(fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = fg)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .selectable(
                    selected = isSelected,
                    enabled = !disabled && interactionEnabled && tabsState != null,
                    role = Role.Tab,
                    onClick = { tabsState?.onSelectTab?.invoke(tabVal) },
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("sprout-tabs-tab:${element.id}"),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalSproutTextStyle provides style) {
                renderChildren()
            }
        }
    }

    component("TabsPanel") {
        val panelVal = stringProp("value")
        val tabsState = LocalSproutTabsState.current
        if (tabsState == null || tabsState.activeValue == panelVal) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("sprout-tabs-panel:${element.id}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                renderChildren()
            }
        }
    }

    component("Progress") {
        val value = doubleProp("value", 0.0)
        val max = doubleProp("max", 100.0).coerceAtLeast(1.0)
        val ratio = (value / max).toFloat().coerceIn(0f, 1f)
        val label = stringProp("label")
        val showValue = boolProp("showValue", false)
        val variant = stringProp("variant", "primary")
        val barHeight = when (stringProp("size", "md")) {
            "sm" -> 5.dp
            "lg" -> 12.dp
            else -> 8.dp
        }
        val barColor = when (variant) {
            "information" -> Color(0xFF0284C7)
            "accent" -> Color(0xFF7C3AED)
            "success" -> Color(0xFF16A34A)
            "warning" -> Color(0xFFD97706)
            "danger" -> Color(0xFFDC2626)
            else -> Color(0xFF2563EB)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label.isNotEmpty() || showValue) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(label, style = TextStyle(fontSize = 12.sp, color = Color(0xFF475569)))
                    if (showValue) {
                        BasicText("${(ratio * 100f).toInt()}%", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF334155)))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE2E8F0))
                    .testTag("sprout-progress:${element.id}"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }
    }

    component("Table") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                .background(Color.White)
                .testTag("sprout-table:${element.id}"),
        ) {
            renderChildren()
        }
    }

    component("TableHeader") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8FAFC))
                .testTag("sprout-table-header:${element.id}"),
        ) {
            renderChildren()
        }
    }

    component("TableBody") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sprout-table-body:${element.id}"),
        ) {
            renderChildren()
        }
    }

    component("TableRow") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Color(0xFFF1F5F9))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("sprout-table-row:${element.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val weightWrapper: @Composable (@Composable () -> Unit) -> Unit = { cellContent ->
                Box(modifier = Modifier.weight(1f)) {
                    cellContent()
                }
            }
            CompositionLocalProvider(LocalSproutTableCellWeight provides weightWrapper) {
                renderChildren()
            }
        }
    }

    component("TableHead") {
        val isRight = stringProp("align", "left") == "right"
        val style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF475569),
            textAlign = if (isRight) TextAlign.End else TextAlign.Start,
        )
        val wrapper = LocalSproutTableCellWeight.current
        val body: @Composable () -> Unit = {
            Box(
                modifier = Modifier.fillMaxWidth().testTag("sprout-table-head:${element.id}"),
                contentAlignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                CompositionLocalProvider(LocalSproutTextStyle provides style) {
                    renderChildren()
                }
            }
        }
        if (wrapper != null) wrapper(body) else body()
    }

    component("TableCell") {
        val isRight = stringProp("align", "left") == "right"
        val style = TextStyle(
            fontSize = 13.sp,
            color = Color(0xFF1E293B),
            textAlign = if (isRight) TextAlign.End else TextAlign.Start,
        )
        val wrapper = LocalSproutTableCellWeight.current
        val body: @Composable () -> Unit = {
            Box(
                modifier = Modifier.fillMaxWidth().testTag("sprout-table-cell:${element.id}"),
                contentAlignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                CompositionLocalProvider(LocalSproutTextStyle provides style) {
                    renderChildren()
                }
            }
        }
        if (wrapper != null) wrapper(body) else body()
    }

    component("Avatar") {
        val name = stringProp("name")
        val avatarType = stringProp("type", "human")
        val dim = when (stringProp("size", "md")) {
            "xs" -> 22.dp
            "sm" -> 28.dp
            "lg" -> 42.dp
            else -> 34.dp
        }
        val fontSp = when (stringProp("size", "md")) {
            "xs" -> 9.sp
            "sm" -> 11.sp
            "lg" -> 15.sp
            else -> 13.sp
        }
        val (bg, fg, shape) = when (avatarType) {
            "agent" -> Triple(Color(0xFFEDE9FE), Color(0xFF6D28D9), RoundedCornerShape(8.dp))
            "server" -> Triple(Color(0xFFE0F2FE), Color(0xFF0369A1), RoundedCornerShape(6.dp))
            else -> Triple(Color(0xFFE2E8F0), Color(0xFF334155), CircleShape)
        }
        Box(
            modifier = Modifier
                .size(dim)
                .clip(shape)
                .background(bg)
                .semantics { contentDescription = name.ifBlank { avatarType } }
                .testTag("sprout-avatar:${element.id}"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = initialsFromName(name),
                style = TextStyle(fontSize = fontSp, fontWeight = FontWeight.Bold, color = fg),
            )
        }
    }

    component("Separator") {
        val vertical = stringProp("orientation", "horizontal") == "vertical"
        if (vertical) {
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color(0xFFE2E8F0))
                    .testTag("sprout-separator:${element.id}"),
            )
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFE2E8F0))
                    .testTag("sprout-separator:${element.id}"),
            )
        }
    }

    component("Link") {
        val href = stringProp("href")
        val variant = stringProp("variant")
        val (fg, weight, deco) = when (variant) {
            "muted" -> Triple(Color(0xFF64748B), FontWeight.Normal, TextDecoration.None)
            "strong" -> Triple(Color(0xFF1D4ED8), FontWeight.SemiBold, TextDecoration.Underline)
            else -> Triple(Color(0xFF2563EB), FontWeight.Normal, TextDecoration.Underline)
        }
        BasicText(
            text = textChildrenJoined().ifEmpty { href },
            style = TextStyle(fontSize = 14.sp, color = fg, fontWeight = weight, textDecoration = deco),
            modifier = Modifier
                .clickable(enabled = interactionEnabled && (href.isNotEmpty() || hasEvent("click"))) {
                    if (hasEvent("click")) {
                        emitUserEvent("click")
                    }
                    if (href.isNotEmpty() && onOpenLinkRequest != null) {
                        onOpenLinkRequest.invoke(href)
                    }
                }
                .testTag("sprout-link:${element.id}"),
        )
    }

    // Register SVG extras in registry as well (inside `<svg>` they are drawn directly by `RenderPortableSvg`)
    for (svgTag in RAFT_UI_SVG_EXTRAS.keys) {
        component(svgTag) {
            // SVG child elements are rendered inside `<svg>` Canvas; standalone invocation outside `<svg>` is a safe no-op
        }
    }
}
