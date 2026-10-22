package build.raft.app.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import build.raft.app.markdown.RaftMathFormulas.MAX_HEIGHT_EM
import build.raft.app.markdown.RaftMathFormulas.MAX_WIDTH_EM
import build.raft.designsystem.theme.RaftKatexFontBytes
import build.raft.designsystem.theme.RaftKatexFonts
import build.raft.designsystem.theme.loadRaftKatexFontBytes
import build.raft.designsystem.theme.rememberRaftKatexFonts
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexDimensions
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.LatexTheme
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.atomicfu.locks.synchronized

/*
 * Task #775: LaTeX in messages, as the web renders it (botiverse/slock #10813).
 *
 * The Markdown parser decides what is math (INLINE_MATH / BLOCK_MATH, see MathParser and
 * MathFenceProvider in third_party/markdown-parser). This file lays a formula out with
 * huarangmeng/latex (pure Kotlin, KaTeX metrics and the KaTeX 0.19.0 fonts the web uses):
 *  - like KaTeX's `.katex { font-size: 1.21em }`, a formula is 1.21x the text around it;
 *  - inline math sits on the text baseline; display math is centred and scrolls sideways when
 *    wider than the bubble;
 *  - it is drawn in the text colour, so it follows the theme;
 *  - a formula KaTeX would reject shows its source in KaTeX's errorColor (#cc0000), as the web
 *    does with `throwOnError: false`; until it is laid out it shows in the code style;
 *  - nothing loads until a formula is shown: the fonts are composed, and their bytes read, with
 *    the first formula; until then it shows as its source in the code style (web: KaTeX loading).
 */

internal const val RAFT_INLINE_MATH_CONTENT_ID_PREFIX = "raft.markdown.math:"

/** The inline-content id of an inline formula; the id carries its source. */
internal fun raftInlineMathContentId(source: String): String = RAFT_INLINE_MATH_CONTENT_ID_PREFIX + source

/** KaTeX's default `errorColor`; the web uses it in both themes. */
internal val RaftMathErrorColor = Color(0xFFCC0000) // raft-color: KaTeX errorColor, theme-independent on the web

/** KaTeX's `.katex { font-size: 1.21em }`. */
private const val RAFT_MATH_FONT_SCALE = 1.21f

/** Whether [source] is a formula KaTeX (with the web's settings) would render. */
internal fun raftMathFormulaRenders(source: String): Boolean = RaftMathFormulas.renders(source)

internal object RaftMathFormulas {
    /** No formula is longer than this (the web has no limit; this bounds layout work). */
    const val MAX_SOURCE_LENGTH = 4000

    /** Brace and `\left` nesting; bounds the parser's and layout's recursion. */
    const val MAX_NESTING = 64

    /** The web's `maxSize: 50` (em) caps any one size; here the formula's height. */
    const val MAX_HEIGHT_EM = 50f

    /** A formula wider than this is not drawn (it scrolls on the web; this bounds the canvas). */
    const val MAX_WIDTH_EM = 500f

    // Same as the web's `trust: false`: no links, images or HTML attributes from a message.
    private val untrustedCommands = setOf("href", "url", "includegraphics")

    private const val CACHE_LIMIT = 500
    private val lock = SynchronizedObject()
    private val verdicts = LinkedHashMap<String, Boolean>()

    fun renders(source: String): Boolean {
        synchronized(lock) { verdicts[source] }?.let { return it }
        val verdict = check(source)
        synchronized(lock) {
            if (verdicts.size >= CACHE_LIMIT) verdicts.remove(verdicts.keys.first())
            verdicts[source] = verdict
        }
        return verdict
    }

    private fun check(source: String): Boolean {
        if (source.length > MAX_SOURCE_LENGTH) return false
        if (!passesCommandScan(source)) return false
        val result =
            try {
                LatexParser().parseWithDiagnostics(source)
            } catch (_: Exception) {
                return false
            }
        if (result.errors.isNotEmpty()) return false
        // An unknown command parses as a generic Command; KaTeX says "Undefined control sequence".
        return !result.document.fold(false) { found, node -> found || node is LatexNode.Command || node is LatexNode.Hyperlink }
    }

    private fun passesCommandScan(source: String): Boolean {
        var index = 0
        var braces = 0
        var lefts = 0
        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' -> {
                    val nameStart = index + 1
                    var nameEnd = nameStart
                    while (nameEnd < source.length && source[nameEnd].isLetter()) nameEnd++
                    if (nameEnd == nameStart) {
                        // `\{`, `\\` and the like: one escaped character.
                        index = nameStart + 1
                        continue
                    }
                    val name = source.substring(nameStart, nameEnd)
                    if (name in untrustedCommands || name.startsWith("html")) return false
                    when (name) {
                        "left" -> if (++lefts > MAX_NESTING) return false
                        "right" -> lefts--
                    }
                    index = nameEnd
                }
                char == '{' -> {
                    if (++braces > MAX_NESTING) return false
                    index++
                }
                char == '}' -> {
                    braces--
                    index++
                }
                else -> index++
            }
        }
        return true
    }
}

/** Laid-out sizes per (formula, mode, font size), shared by every message. */
private object RaftMathLayouts {
    private data class Key(val source: String, val display: Boolean, val fontSizePx: Float)

    private const val LIMIT = 500
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, LatexDimensions?>()

    fun dimensions(
        source: String,
        display: Boolean,
        fontSizePx: Float,
        measure: () -> LatexDimensions?,
    ): LatexDimensions? {
        val key = Key(source, display, fontSizePx)
        synchronized(lock) {
            if (entries.containsKey(key)) return entries[key]
        }
        val measured =
            measure()?.takeIf { dims ->
                dims.widthPx > 0f &&
                    dims.heightPx > 0f &&
                    dims.widthPx <= MAX_WIDTH_EM * fontSizePx &&
                    dims.heightPx <= MAX_HEIGHT_EM * fontSizePx
            }
        synchronized(lock) {
            if (entries.size >= LIMIT) entries.remove(entries.keys.first())
            entries[key] = measured
        }
        return measured
    }
}

/** TeX for KaTeX's inline mode (text style) or display mode. */
private fun raftMathTex(source: String, display: Boolean): String =
    if (display) "\\displaystyle $source" else "\\textstyle $source"

private fun RaftKatexFonts.toLatexFontFamilies(bytes: RaftKatexFontBytes): LatexFontFamilies =
    LatexFontFamilies(
        main = main,
        math = math,
        ams = ams,
        sansSerif = sansSerif,
        monospace = typewriter,
        caligraphic = caligraphic,
        fraktur = fraktur,
        script = script,
        size1 = size1,
        size2 = size2,
        size3 = size3,
        size4 = size4,
        mainBytes = bytes.main,
        mathBytes = bytes.math,
        amsBytes = bytes.ams,
        size1Bytes = bytes.size1,
        size2Bytes = bytes.size2,
        size3Bytes = bytes.size3,
        size4Bytes = bytes.size4,
    )

/**
 * The KaTeX font bytes, read once per process when the first formula is shown (the web loads
 * KaTeX on the first formula too). Layout waits for them: without the outlines the library
 * guesses glyph bounds and draws `\sqrt{2}` with a radical far too tall.
 */
private object RaftMathFontBytes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var loaded: RaftKatexFontBytes? by mutableStateOf(null)
        private set

    private val load: Deferred<Unit> by lazy {
        scope.async { loaded = loadRaftKatexFontBytes() }
    }

    fun start() {
        load
    }

    suspend fun await() {
        load.await()
    }
}

/** Starts the KaTeX font read and waits for it (tests and warm-up; rendering never waits). */
internal suspend fun awaitRaftMathFonts() {
    RaftMathFontBytes.await()
}

/** The formula config, or null until the KaTeX fonts are ready (the formula shows as code). */
@Composable
private fun rememberRaftMathConfig(textSize: TextUnit, color: Color): LatexConfig? {
    val fonts = rememberRaftKatexFonts()
    LaunchedEffect(Unit) { RaftMathFontBytes.start() }
    val bytes = RaftMathFontBytes.loaded ?: return null
    return remember(fonts, bytes, textSize, color) {
        LatexConfig(
            fontSize = textSize * RAFT_MATH_FONT_SCALE,
            theme = LatexTheme.light(color = color, backgroundColor = Color.Transparent),
            fontFamilies = fonts.toLatexFontFamilies(bytes),
        )
    }
}

private fun TextStyle.raftMathTextSize(): TextUnit = if (fontSize.isSpecified) fontSize else 15.sp

/**
 * The inline content for every formula in [text] (placeholders from the annotator's
 * INLINE_MATH branch), in [style]'s size and colour. Empty, and composes nothing, for text
 * without math.
 */
@Composable
internal fun raftInlineMathContent(
    text: AnnotatedString,
    style: TextStyle,
    fallbackColor: Color,
): Map<String, InlineTextContent> {
    val sources =
        remember(text) {
            text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
                .map { it.item }
                .filter { it.startsWith(RAFT_INLINE_MATH_CONTENT_ID_PREFIX) }
                .distinct()
                .map { it.removePrefix(RAFT_INLINE_MATH_CONTENT_ID_PREFIX) }
        }
    if (sources.isEmpty()) return emptyMap()
    val color = style.color.takeOrElse { fallbackColor }
    val config = rememberRaftMathConfig(style.raftMathTextSize(), color) ?: return emptyMap()
    val measurer = rememberLatexMeasurer(config)
    val density = LocalDensity.current
    val fontSizePx = with(density) { config.fontSize.toPx() }
    return remember(sources, config, measurer, density) {
        buildMap {
            for (source in sources) {
                val tex = raftMathTex(source, display = false)
                val dims =
                    RaftMathLayouts.dimensions(source, display = false, fontSizePx) {
                        measurer.measure(tex, config)
                    } ?: continue
                put(raftInlineMathContentId(source), raftInlineMathTextContent(tex, dims, config, density))
            }
        }
    }
}

/**
 * Baseline-aligned: the placeholder is the part of the formula above its baseline and sits on
 * the text baseline; the part below the baseline (depth) hangs under it, as in a line of TeX.
 */
private fun raftInlineMathTextContent(
    tex: String,
    dims: LatexDimensions,
    config: LatexConfig,
    density: Density,
): InlineTextContent =
    InlineTextContent(
        placeholder =
            with(density) {
                Placeholder(
                    width = dims.widthPx.toSp(),
                    height = dims.baselinePx.coerceAtLeast(1f).toSp(),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                )
            }
    ) {
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
            Latex(latex = tex, config = config, isDarkTheme = false)
        }
    }

/** A `$$` block: centred, scrolling sideways when wider than the message (web `.katex-display`). */
@Composable
internal fun RaftDisplayMath(source: String, style: TextStyle) {
    val color = style.color.takeOrElse { LocalMarkdownColors.current.text }
    val renders = remember(source) { raftMathFormulaRenders(source) }
    // web DisplayMath: `my-2 overflow-x-auto`.
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (source.isBlank()) return@Box
        if (!renders) {
            RaftMathSourceText(source, style, RaftMathErrorColor, fontFamily = null)
            return@Box
        }
        val config = rememberRaftMathConfig(style.raftMathTextSize(), color)
        if (config == null) {
            // web: before KaTeX loads, the source in a mono `pre`.
            RaftMathSourceText(source, style, color, LocalMarkdownTypography.current.code.fontFamily)
            return@Box
        }
        val measurer = rememberLatexMeasurer(config)
        val fontSizePx = with(LocalDensity.current) { config.fontSize.toPx() }
        val tex = raftMathTex(source, display = true)
        val dims = remember(tex, config, measurer) {
            RaftMathLayouts.dimensions(source, display = true, fontSizePx) { measurer.measure(tex, config) }
        }
        if (dims == null) {
            // web: before KaTeX renders, the source in a mono `pre`.
            RaftMathSourceText(source, style, color, LocalMarkdownTypography.current.code.fontFamily)
            return@Box
        }
        // Centred when it fits; otherwise it starts at the edge and scrolls (no subcomposition).
        Layout(
            content = {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Latex(latex = tex, config = config, isDarkTheme = false)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val formula = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else formula.width
            layout(width, formula.height) {
                formula.place(((width - formula.width) / 2).coerceAtLeast(0), 0)
            }
        }
    }
}

@Composable
private fun RaftMathSourceText(source: String, style: TextStyle, color: Color, fontFamily: FontFamily?) {
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MarkdownBasicText(
            text = source,
            style = style.copy(color = color, fontFamily = fontFamily ?: style.fontFamily),
            modifier = Modifier,
        )
    }
}

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"
