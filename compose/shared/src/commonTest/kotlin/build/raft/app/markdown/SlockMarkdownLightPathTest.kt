package build.raft.app.markdown

import build.raft.app.SlockType
import build.raft.app.ReplyCacheProvenance
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.parseMarkdownTopLevelNodes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #367 single-paragraph light path — RED/GREEN teeth.
 *
 * Honest boundary: Kuikly composables (Markdown / MarkdownSuccess /
 * MarkdownParagraph) cannot execute in commonTest, so full-path vs light-path
 * RENDER equivalence cannot be asserted by running both renderers here. What
 * CAN be locked at this level, and what this file asserts:
 * - eligibility: the dispatcher verdict over a matrix of bodies, decided from
 *   the PARSED node tree (no raw-string heuristics);
 * - AnnotatedString equivalence: both paths build the paragraph string through
 *   the SAME non-composable core (pushStyle(paragraph) +
 *   buildMarkdownAnnotatedString, mirrored verbatim from the vendored
 *   MarkdownParagraph) — here we prove the two parse entry points (renderer
 *   cache parse vs independent canonical parse) yield trees that produce the
 *   SAME string under the SAME settings, so the only remaining difference
 *   between the paths is the skipped scaffold, which does not touch the
 *   string;
 * - cache identity: the light lookup returns the cache-stable node the
 *   renderer's own memo keys depend on;
 * - override gate: default OFF, and the registry decision flow.
 * Listener lambdas are identity-compared nowhere (each settings instance owns
 * its own); annotations are compared by range + kind + url/tag.
 */
class SlockMarkdownLightPathTest {

    // ---------- eligibility: admitted (light path) ----------

    @Test
    fun singleParagraphTextOnlyBodiesAreLightEligible() {
        val bodies =
            listOf(
                "hello world",
                "Re: standup notes: all good!",
                "quotes 'single' \"double\" (parens) done",
                "1 < 2 and 3 > 2",
                "**bold** and *italic* and _underscore_",
                "run `./gradlew test` now",
                "~~struck~~ text",
                "see [the docs](https://example.com/docs)",
                "titled [link](https://example.com \"a title\")",
                "angle <https://example.com/auto>",
                "bare www.example.com link",
                "bare https://example.com/plain link",
                "mail user@host.tld today",
                "soft break\nsecond line",
                "hard break  \nsecond line",
            )
        for (body in bodies) {
            val node = lightNodeFor(body)
            assertNotNull(node, "must be light-eligible: ${body.escaped()}\n${treeDumpFor(body)}")
            assertEquals(MarkdownElementTypes.PARAGRAPH, node.type, body.escaped())
        }
    }

    @Test
    fun mentionChannelAndTaskRefsAreLightEligible() {
        val bodies =
            listOf(
                "ping @Android-App-Integrator now",
                "see #markdown专修 for context",
                "task #15 is done",
                "thread ref #general:008b4967 please",
            )
        for (body in bodies) {
            val node = lightNodeFor(body)
            assertNotNull(node, "must be light-eligible: ${body.escaped()}\n${treeDumpFor(body)}")
        }
    }

    // ---------- eligibility: fallback (full path) ----------

    @Test
    fun nonSingleParagraphOrScaffoldBodiesStayOnFullPath() {
        val bodies =
            listOf(
                "first\n\nsecond",
                "```\ncode block\n```",
                "    indented code",
                "- one\n- two",
                "1. one\n2. two",
                "> quoted line",
                "# heading",
                "---",
                "![alt](https://example.com/i.png)",
                "text with ![inline](https://example.com/i.png) image",
                "| a | b |\n|---|---|\n| 1 | 2 |",
                "para\n\n![alt](https://example.com/i.png)",
                // The flavour emits SHORT_/FULL_REFERENCE_LINK nodes even for
                // undefined references — these are exactly the "link
                // annotations requiring reference resolution" the light path
                // must refuse (conservative fallback, brief requirement).
                "shortcut [label] reference",
                "full [label][ref] reference",
            )
        for (body in bodies) {
            assertNull(
                lightNodeFor(body),
                "must stay on the full path: ${body.escaped()}\n${treeDumpFor(body)}"
            )
        }
    }

    @Test
    fun escapedHtmlTextIsLiteralAndLightEligible() {
        // The parser-input normalization escapes HTML-looking text before the
        // parse, so this reaches the tree as a literal-text paragraph — the
        // same rendering both paths already produce today.
        val node = lightNodeFor("<b>not really bold</b>")
        assertNotNull(node, "escaped-literal html text stays a plain paragraph\n${treeDumpFor("<b>not really bold</b>")}")
    }

    // ---------- cache identity / same-parse contract ----------

    @Test
    fun lightLookupReturnsCacheStableNodeIdentity() {
        val parserContent = MarkdownParserContent.from("identity check `x`").parserContent
        val first = slockMarkdownSingleParagraphLightNodeOrNull(parserContent)
        val second = slockMarkdownSingleParagraphLightNodeOrNull(parserContent)
        assertNotNull(first)
        assertSame(
            first,
            second,
            "repeated lookups must return the cache-shared node instance " +
                "(the AnnotatedString memo keys on node identity)"
        )
        assertSame(
            first,
            parseMarkdownTopLevelNodes(parserContent).single(),
            "the light lookup must consume the renderer's own parse cache, not a second one"
        )
    }

    // ---------- AnnotatedString equivalence ----------

    @Test
    fun lightAndCanonicalParseProduceTheSameAnnotatedString() {
        val bodies =
            listOf(
                "hello world",
                "**bold** and *italic*",
                "run `./gradlew test` now",
                "~~struck~~ text",
                "see [the docs](https://example.com/docs)",
                "angle <https://example.com/auto>",
                "bare www.example.com link",
                "soft break\nsecond line",
                "1 < 2 and 3 > 2",
            )
        for (body in bodies) {
            assertSameAnnotatedString(body, slockMarkdownAnnotator(body))
        }
    }

    @Test
    fun mentionAnnotatedStringMatchesBetweenParseEntries() {
        val body = "ping @Android-App-Integrator in #markdown专修"
        val annotator =
            slockMarkdownAnnotator(
                body,
                isTagValid = { tag ->
                    when (tag.kind) {
                        ThreadMarkdownTagKind.UserMention ->
                            tag.label.removePrefix("@").lowercase() == "android-app-integrator"
                        ThreadMarkdownTagKind.Channel ->
                            (tag.channelName ?: tag.label.removePrefix("#")).lowercase() == "markdown专修"
                        else -> false
                    }
                },
                resolveTagLabel = { tag -> tag.label },
            )
        assertSameAnnotatedString(body, annotator)
    }

    /**
     * Builds the paragraph string through BOTH parse entry points and asserts
     * structural equality: the light dispatcher's cached parse
     * ([slockMarkdownSingleParagraphLightNodeOrNull]) vs an independent
     * canonical parse of the same parser content (what MarkdownState would
     * build its node list from on a cold cache). The construction below
     * mirrors the vendored MarkdownParagraph verbatim (pushStyle(paragraph) +
     * buildMarkdownAnnotatedString).
     */
    private fun assertSameAnnotatedString(
        body: String,
        annotator: MarkdownAnnotator,
    ) {
        val parserContent = MarkdownParserContent.from(body).parserContent
        val lightNode =
            assertNotNull(
                slockMarkdownSingleParagraphLightNodeOrNull(parserContent),
                "fixture body must be light-eligible: ${body.escaped()}\n${treeDumpFor(body)}"
            )
        val canonicalNode =
            MarkdownParser(SlockGFMFlavourDescriptor())
                .buildMarkdownTreeFromString(parserContent)
                .children
                .single()
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = slockMarkdownLinkSpanStyle(SlockType.Body)),
                codeSpanStyle = SpanStyle(),
                annotator = annotator,
            )
        val paragraphStyle =
            slockMarkdownTypography(slockMarkdownTextStyleWithInlineLineHeight(SlockType.Body)).paragraph

        val fromLight = buildParagraphAnnotatedString(parserContent, lightNode, settings, paragraphStyle)
        val fromCanonical = buildParagraphAnnotatedString(parserContent, canonicalNode, settings, paragraphStyle)

        assertEquals(fromCanonical.text, fromLight.text, "text diverged: ${body.escaped()}")
        assertEquals(
            fromCanonical.spanStyles,
            fromLight.spanStyles,
            "spanStyles diverged: ${body.escaped()}"
        )
        assertEquals(
            fromCanonical.stringAnnotationSummary(),
            fromLight.stringAnnotationSummary(),
            "stringAnnotations diverged: ${body.escaped()}"
        )
        assertEquals(
            fromCanonical.linkAnnotationSummary(),
            fromLight.linkAnnotationSummary(),
            "linkAnnotations diverged: ${body.escaped()}"
        )
    }

    // Mirrors MarkdownParagraph's AnnotatedString build exactly.
    private fun buildParagraphAnnotatedString(
        content: String,
        node: ASTNode,
        settings: AnnotatorSettings,
        style: TextStyle,
    ): AnnotatedString =
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = settings)
            pop()
        }

    private fun AnnotatedString.stringAnnotationSummary(): List<String> =
        getStringAnnotations(0, length).map { "${it.tag}:${it.item}@${it.start}..${it.end}" }

    private fun AnnotatedString.linkAnnotationSummary(): List<String> =
        getLinkAnnotations(0, length).map { range ->
            val kind =
                when (val item = range.item) {
                    is LinkAnnotation.Url -> "url:${item.url}"
                    is LinkAnnotation.Clickable -> "click:${item.tag}"
                    else -> item::class.simpleName.orEmpty()
                }
            "$kind@${range.start}..${range.end}"
        }

    // ---------- override gate ----------

    @Test
    fun renderConfigDefaultsLightPathOff() {
        assertFalse(SlockMarkdownRenderConfig().singleParagraphLightPath)
    }

    @Test
    fun lightPathOverrideFollowsRegistryDecision() {
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val decision = { serverEffective: Boolean ->
            resolveThreadMarkdownSingleParagraphLightPathEnabled(
                registry = registry,
                serverEffective = serverEffective,
                environmentId = "https://api.example/",
                accountId = "account-a",
                serverId = "server-a",
            )
        }

        // Default OFF: unresolved server, no latch.
        assertFalse(decision(false))
        registry.setMode(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(decision(false))
        registry.setMode(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(decision(true))
        registry.setMode(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(decision(true))
        assertFalse(decision(false))
    }

    @Test
    fun lightPathOverrideRejectedWithoutEntitlement() {
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.setMode(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertFalse(
            resolveThreadMarkdownSingleParagraphLightPathEnabled(
                registry = registry,
                serverEffective = false,
                environmentId = "https://api.example/",
                accountId = "account-a",
                serverId = "server-a",
            ),
            "a non-entitled ForceOn latch must not enable the light path"
        )
    }

    @Test
    fun gateInputsComeFromProvenanceNeverSessionCurrentServer() {
        // Review round 1 tooth: the route renders the provenance's server
        // (shell-selected B), which may differ from the session current (A).
        // Gate inputs must be built from the provenance alone — a helper that
        // silently fell back to the session's server would open/close the
        // experiment on the wrong server.
        val provenanceB =
            ReplyCacheProvenance(
                environmentId = "https://api-b.example/",
                principalId = "principal-b",
                serverId = "server-b",
                generation = 7L,
            )
        assertEquals(
            ThreadMarkdownLightGateInputs(
                environmentId = "https://api-b.example/",
                accountId = "principal-b",
                serverId = "server-b",
                authorityEpoch = 7L,
            ),
            threadMarkdownLightGateInputsOrNull(provenanceB),
        )
    }

    @Test
    fun sameTripleDifferentGenerationFormsNewGateEpoch() {
        // Round 2 tooth: with an identical environment/principal/server
        // triple, a generation rotation must produce DIFFERENT gate inputs —
        // otherwise the route-level remember(overrideRevision, gateInputs)
        // keeps a stale decision across an authority rotation. The remember
        // itself is Compose machinery; this locks the key it consumes.
        val gen7 =
            threadMarkdownLightGateInputsOrNull(
                ReplyCacheProvenance(
                    environmentId = "https://api-b.example/",
                    principalId = "principal-b",
                    serverId = "server-b",
                    generation = 7L,
                )
            )
        val gen8 =
            threadMarkdownLightGateInputsOrNull(
                ReplyCacheProvenance(
                    environmentId = "https://api-b.example/",
                    principalId = "principal-b",
                    serverId = "server-b",
                    generation = 8L,
                )
            )
        assertNotNull(gen7)
        assertNotNull(gen8)
        assertTrue(gen7 != gen8, "generation rotation must change the gate epoch key")
        assertEquals(7L, gen7!!.authorityEpoch)
        assertEquals(8L, gen8!!.authorityEpoch)
        assertEquals(gen7.environmentId, gen8.environmentId)
        assertEquals(gen7.accountId, gen8.accountId)
        assertEquals(gen7.serverId, gen8.serverId)
    }

    @Test
    fun gateInputsFailClosedWhenProvenanceMissingOrIncomplete() {
        assertNull(threadMarkdownLightGateInputsOrNull(null))
        assertNull(
            threadMarkdownLightGateInputsOrNull(
                ReplyCacheProvenance(
                    environmentId = "https://api-b.example/",
                    principalId = "principal-b",
                    serverId = "",
                    generation = 7L,
                )
            ),
            "blank serverId must fail closed — a ForceOn latch must not fire with an empty scope"
        )
        assertNull(
            threadMarkdownLightGateInputsOrNull(
                ReplyCacheProvenance(
                    environmentId = "https://api-b.example/",
                    principalId = "",
                    serverId = "server-b",
                    generation = 7L,
                )
            )
        )
    }

    @Test
    fun gateDecisionBindsProvenanceScopeAndNeverInheritsAcrossServers() {
        // Round 1 tooth at the DECISION level (not just the DTO helper):
        // provenance-derived inputs drive the resolver, and the registry
        // observes exactly that server scope — a later A-scoped decision does
        // not leak into B's.
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val provenanceB =
            ReplyCacheProvenance(
                environmentId = "https://api-b.example/",
                principalId = "principal-b",
                serverId = "server-b",
                generation = 7L,
            )
        val inputsB = threadMarkdownLightGateInputsOrNull(provenanceB)!!
        resolveThreadMarkdownSingleParagraphLightPathEnabled(
            registry = registry,
            serverEffective = true,
            environmentId = inputsB.environmentId,
            accountId = inputsB.accountId,
            serverId = inputsB.serverId,
        )
        val observed = registry.snapshot(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY).observedScope
        assertEquals("principal-b", observed?.accountId)
        assertEquals("server-b", observed?.serverId)

        // An A-scoped decision on the same registry replaces the observed
        // scope rather than mixing with B's — no cross-server inheritance.
        resolveThreadMarkdownSingleParagraphLightPathEnabled(
            registry = registry,
            serverEffective = false,
            environmentId = "https://api-a.example/",
            accountId = "principal-a",
            serverId = "server-a",
        )
        val observedAfter = registry.snapshot(THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY).observedScope
        assertEquals("principal-a", observedAfter?.accountId)
        assertEquals("server-a", observedAfter?.serverId)
    }

    // ---------- helpers ----------

    private fun lightNodeFor(body: String): ASTNode? =
        slockMarkdownSingleParagraphLightNodeOrNull(MarkdownParserContent.from(body).parserContent)

    private fun treeDumpFor(body: String): String {
        val parserContent = MarkdownParserContent.from(body).parserContent
        val tree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        return "tree for ${body.escaped()}:\n" + tree.dumpTypes(parserContent)
    }

    private fun ASTNode.dumpTypes(content: String, indent: String = ""): String {
        val snippet =
            content.substring(startOffset.coerceAtMost(content.length), endOffset.coerceAtMost(content.length))
                .replace("\n", "\\n")
                .take(24)
        return "$indent$type [$snippet]\n" + children.joinToString("") { it.dumpTypes(content, "$indent  ") }
    }

    private fun String.escaped(): String = replace("\n", "\\n")
}
