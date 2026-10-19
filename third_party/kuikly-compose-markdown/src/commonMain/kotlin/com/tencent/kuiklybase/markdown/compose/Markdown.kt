package com.tencent.kuiklybase.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.tencent.kuiklybase.markdown.compose.components.MarkdownComponents
import com.tencent.kuiklybase.markdown.compose.components.markdownComponents
import com.tencent.kuiklybase.markdown.model.ImageLinkHandler
import com.tencent.kuiklybase.markdown.model.ImageLinkHandlerImpl
import com.tencent.kuiklybase.markdown.model.ImageTransformer
import com.tencent.kuiklybase.markdown.model.MarkdownAnimations
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownColors
import com.tencent.kuiklybase.markdown.model.MarkdownDimens
import com.tencent.kuiklybase.markdown.model.MarkdownExtendedSpans
import com.tencent.kuiklybase.markdown.model.MarkdownPadding
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.MarkdownState
import com.tencent.kuiklybase.markdown.model.MarkdownTypography
import com.tencent.kuiklybase.markdown.model.NoOpImageTransformerImpl
import com.tencent.kuiklybase.markdown.model.NoOpTextProvider
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandler
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandlerImpl
import com.tencent.kuiklybase.markdown.model.SLOCK_MARKDOWN_PARSE_DISPATCHER_LABEL
import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.TextProvider
import com.tencent.kuiklybase.markdown.model.markdownAnimations
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownDimens
import com.tencent.kuiklybase.markdown.model.markdownExtendedSpans
import com.tencent.kuiklybase.markdown.model.markdownPadding
import com.tencent.kuiklybase.markdown.model.rememberMarkdownParserState
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser


/**
 * Renders the markdown content.
 *
 * @param content The markdown content to be rendered.
 * @param colors The colors to be used for rendering.
 * @param typography The typography to be used for rendering.
 * @param modifier The modifier to be applied to the container.
 * @param padding The padding to be applied to the container.
 * @param dimens The dimensions to be used for rendering.
 * @param flavour The flavour descriptor for parsing the markdown. By default uses GFM flavour.
 * @param parser The parser to be used for parsing the markdown. By default uses the flavour supplied.
 * @param imageTransformer The image transformer to be used for rendering images.
 * @param imageLinkHandler The image link handler to be used for handling images.
 * @param textProvider The text to be used for rendering text.
 * @param annotator The annotator to be used for rendering annotations.
 * @param extendedSpans The extended spans to be used for rendering.
 * @param components The components to be used for rendering.
 * @param referenceLinkHandler The reference link handler to be used for handling links.
 * @param animations The animations to be used for rendering.
 * @param loading A composable function to be displayed while loading the content.
 * @param success A composable function to be displayed with the markdown content. It receives the modifier, state and components as parameters. By default this is a [Column].
 * @param error A composable function to be displayed in case of an error. Only really possible if assertions are enabled on the parser)
 */
@Composable
fun Markdown(
    content: String,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier.fillMaxSize(),
    padding: MarkdownPadding = markdownPadding(),
    dimens: MarkdownDimens = markdownDimens(),
    flavour: MarkdownFlavourDescriptor = SlockGFMFlavourDescriptor(),
    parser: MarkdownParser = MarkdownParser(flavour),
    imageTransformer: ImageTransformer = NoOpImageTransformerImpl(),
    imageLinkHandler: ImageLinkHandler = ImageLinkHandlerImpl(),
    textProvider: TextProvider = NoOpTextProvider(),
    annotator: MarkdownAnnotator = markdownAnnotator(),
    extendedSpans: MarkdownExtendedSpans = markdownExtendedSpans(),
    components: MarkdownComponents = markdownComponents(),
    animations: MarkdownAnimations = markdownAnimations(),
    referenceLinkHandler: ReferenceLinkHandler? = null,
    loading: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
    success: @Composable (state: MarkdownState, components: MarkdownComponents, modifier: Modifier) -> Unit = { state, components, modifier ->
        MarkdownSuccess(state = state, components = components, modifier = modifier)
    },
    error: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
) {
    val parserContent = remember(content) { MarkdownParserContent.from(content) }
    Markdown(
        content = parserContent,
        colors = colors,
        typography = typography,
        modifier = modifier,
        padding = padding,
        dimens = dimens,
        flavour = flavour,
        parser = parser,
        imageTransformer = imageTransformer,
        imageLinkHandler = imageLinkHandler,
        textProvider = textProvider,
        annotator = annotator,
        extendedSpans = extendedSpans,
        components = components,
        animations = animations,
        referenceLinkHandler = referenceLinkHandler,
        loading = loading,
        success = success,
        error = error
    )
}

@Composable
fun Markdown(
    content: MarkdownParserContent,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier.fillMaxSize(),
    padding: MarkdownPadding = markdownPadding(),
    dimens: MarkdownDimens = markdownDimens(),
    flavour: MarkdownFlavourDescriptor = SlockGFMFlavourDescriptor(),
    parser: MarkdownParser = MarkdownParser(flavour),
    imageTransformer: ImageTransformer = NoOpImageTransformerImpl(),
    imageLinkHandler: ImageLinkHandler = ImageLinkHandlerImpl(),
    textProvider: TextProvider = NoOpTextProvider(),
    annotator: MarkdownAnnotator = markdownAnnotator(),
    extendedSpans: MarkdownExtendedSpans = markdownExtendedSpans(),
    components: MarkdownComponents = markdownComponents(),
    animations: MarkdownAnimations = markdownAnimations(),
    referenceLinkHandler: ReferenceLinkHandler? = null,
    loading: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
    success: @Composable (state: MarkdownState, components: MarkdownComponents, modifier: Modifier) -> Unit = { state, components, modifier ->
        MarkdownSuccess(state = state, components = components, modifier = modifier)
    },
    error: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
) {
    val parserContent = content.parserContent
    val state = rememberMarkdownParserState(initialContent = content)
    LaunchedEffect(parserContent) {
        if (state.isContentApplied(parserContent)) {
            return@LaunchedEffect
        }
        val parseStartMark = TimeSource.Monotonic.markNow()
        val cacheHit = state.parse(parserContent, false)
        val parseDurationUs = slockMarkdownMonotonicNanosSince(parseStartMark) / 1_000L
        if (parseDurationUs >= SlockMarkdownVendorTraceThresholdUs) {
            slockMarkdownLogSubTrace {
                "phase=markdown.layout.parse_tree durationUs=$parseDurationUs " +
                    "bodyLength=${content.sourceContent.length} parserLength=${parserContent.length} cacheHit=$cacheHit " +
                    "dispatcher=$SLOCK_MARKDOWN_PARSE_DISPATCHER_LABEL"
            }
        }
    }

    Markdown(
        state = state,
        colors = colors,
        typography = typography,
        modifier = modifier,
        padding = padding,
        dimens = dimens,
        imageTransformer = imageTransformer,
        imageLinkHandler = imageLinkHandler,
        textProvider = textProvider,
        annotator = annotator,
        extendedSpans = extendedSpans,
        components = components,
        animations = animations,
        referenceLinkHandler = referenceLinkHandler,
        loading = loading,
        success = success,
        error = error
    )
}

/**
 * Renders the markdown content.
 *
 * @param state The markdown state to be rendered.
 * @param colors The colors to be used for rendering.
 * @param typography The typography to be used for rendering.
 * @param modifier The modifier to be applied to the container.
 * @param padding The padding to be applied to the container.
 * @param dimens The dimensions to be used for rendering.
 * @param imageTransformer The image transformer to be used for rendering images.
 * @param imageLinkHandler The image link handler to be used for handling images.
 * @param textProvider The text to be used for rendering text.
 * @param annotator The annotator to be used for rendering annotations.
 * @param extendedSpans The extended spans to be used for rendering.
 * @param components The components to be used for rendering.
 * @param animations The animations to be used for rendering.
 * @param loading A composable function to be displayed while loading the content.
 * @param success A composable function to be displayed with the markdown content. It receives the modifier, state and components as parameters. By default this is a [Column].
 * @param error A composable function to be displayed in case of an error. Only really possible if assertions are enabled on the parser)
 */
@Composable
fun Markdown(
    state: MarkdownState,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier.fillMaxSize(),
    padding: MarkdownPadding = markdownPadding(),
    dimens: MarkdownDimens = markdownDimens(),
    imageTransformer: ImageTransformer = NoOpImageTransformerImpl(),
    imageLinkHandler: ImageLinkHandler = ImageLinkHandlerImpl(),
    textProvider: TextProvider = NoOpTextProvider(),
    annotator: MarkdownAnnotator = markdownAnnotator(),
    extendedSpans: MarkdownExtendedSpans = markdownExtendedSpans(),
    components: MarkdownComponents = markdownComponents(),
    animations: MarkdownAnimations = markdownAnimations(),
    referenceLinkHandler: ReferenceLinkHandler? = null,
    loading: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
    success: @Composable (state: MarkdownState, components: MarkdownComponents, modifier: Modifier) -> Unit = { state, components, modifier ->
        MarkdownSuccess(state = state, components = components, modifier = modifier)
    },
    error: @Composable (modifier: Modifier) -> Unit = { Box(modifier = modifier) },
) {
    // Document-scoped default handler. The old default allocated a fresh
    // ReferenceLinkHandlerImpl() on every recomposition, which made every
    // downstream AnnotatorSettings identity-unequal and defeated skipping.
    // It must NOT be hoisted globally either: the impl carries a mutable
    // stored map with no clear, so a shared instance would leak reference
    // definitions across documents. remember(state) resets it per document
    // (rememberMarkdownParserState is keyed on content, so a document switch
    // produces a new state) while pure recompositions reuse one instance.
    val resolvedReferenceLinkHandler = referenceLinkHandler ?: remember(state) { ReferenceLinkHandlerImpl() }
    CompositionLocalProvider(
        LocalReferenceLinkHandler provides resolvedReferenceLinkHandler,
        LocalMarkdownPadding provides padding,
        LocalMarkdownDimens provides dimens,
        LocalMarkdownColors provides colors,
        LocalMarkdownTypography provides typography,
        LocalImageTransformer provides imageTransformer,
        LocalImageLinkHandler provides imageLinkHandler,
        LocalTextProvider provides textProvider,
        LocalMarkdownAnnotator provides annotator,
        LocalMarkdownExtendedSpans provides extendedSpans,
        LocalMarkdownComponents provides components,
        LocalMarkdownAnimations provides animations,
    ) {
        success(state, components, modifier)
    }

}

/**
 * Renders the parsed markdown content.
 *
 * @param state The success markdown state.
 * @param components The MarkdownComponents instance containing the components to use.
 * @param modifier The modifier to be applied to the container.
 */
@Composable
fun MarkdownSuccess(
    state: MarkdownState,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
) {
    val content = { state.getContent() }
    val skipLinkDefinition = state.skipLinkDefinition()
    val rememberedComponents = remember { components }
    // t6-a node timing (diagnostics only): fresh per-execution handle only when
    // the host installed a sink; null keeps the pre-existing untimed path (the
    // null check is the only added work — no clock read, no allocation).
    val nodeTiming = slockMarkdownRenderTimingOrNull()
    // Row census cost sink: read the Local ONLY when node timing is active —
    // with diagnostics OFF generic vendor consumers perform no tracked Local
    // read here at all (reviewed OFF-path requirement).
    val rowRenderCostSink =
        if (nodeTiming != null) LocalSlockMarkdownRowRenderCostSink.current else null
    val renderStartMark = TimeSource.Monotonic.markNow()
    slockMarkdownLogSubTrace {
        "phase=markdown.success.compose contentHash=${content().hashCode().toString(16)} " +
            "contentLength=${content().length} nodeListSize=${state.nodeListState.size}"
    }
    CompositionLocalProvider(LocalSlockMarkdownRenderTiming provides nodeTiming) {
        Column(modifier = modifier) {
            state.nodeListState.forEachIndexed { index, result ->
                handleElement(
                    result,
                    rememberedComponents,
                    content,
                    includeSpacer = false,
                    skipLinkDefinition = skipLinkDefinition
                )
            }
        }
    }
    val renderDurationUs = slockMarkdownMonotonicNanosSince(renderStartMark) / 1_000L
    if (renderDurationUs >= SlockMarkdownVendorTraceThresholdUs) {
        slockMarkdownLogSubTrace {
            "phase=markdown.layout.render_nodes durationUs=$renderDurationUs nodeCount=${state.nodeListState.size}"
        }
    }
    val renderValid = nodeTiming?.finishRender(renderDurationUs)
    // Row census cost forwarding (task #20 Option B P1 v2): STRICTLY on the
    // valid path — aborted renders forward nothing. The host token buffers
    // this as pending until the composition itself commits.
    if (renderValid == true) {
        rowRenderCostSink?.recordValidRender(renderDurationUs)
    }
}

internal const val SlockMarkdownVendorTraceThresholdUs: Long = 200L

internal fun slockMarkdownMonotonicNanosSince(mark: TimeMark): Long =
    mark.elapsedNow().inWholeNanoseconds

/**
 * Injectable diagnostics gate for the SlockMarkdownSpike println channel. This
 * channel used to be an unconditional raw println, so every text composition
 * paid message interpolation + annotation scans + a System.out logcat
 * round-trip even in production. The host app assigns this alongside the node
 * timing sink (see SlockMarkdownNodeTrace); default OFF so a bare vendor
 * consumer pays nothing until diagnostics are explicitly enabled.
 */
object SlockMarkdownSubTrace {
    var enabled: Boolean = false
}

internal inline fun slockMarkdownLogSubTrace(message: () -> String) {
    if (!SlockMarkdownSubTrace.enabled) return
    println("SlockMarkdownSpike ${message()}")
}
