package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.tencent.kuiklybase.markdown.utils.lookupLinkDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * A [MarkdownState] that executes the parsing of the markdown content with the [MarkdownParser] asynchronously.
 *
 * @param content The markdown content to parse.
 * @param lookupLinks Whether to lookup links in the parsed tree or not.
 * @param flavour The [MarkdownFlavourDescriptor] to use for parsing.
 * @param parser The [MarkdownParser] to use for parsing.
 * @param referenceLinkHandler The [ReferenceLinkHandler] to use for storing links.
 * @param immediate Whether to parse the content immediately or not. (WARNING: This is not advices, as it will block the composition!)
 */
@Composable
fun rememberMarkdownState(lookupLinks: Boolean = true, isShareMode: Boolean = false): MarkdownState {
    val state = remember {
        val flavour = GFMFlavourDescriptor()
        MarkdownState(
            Input(
                content = "",
                shareModel = isShareMode,
                lookupLinks = lookupLinks,
                flavour = flavour,
                parser = MarkdownParser(flavour),
                referenceLinkHandler = ReferenceLinkHandlerImpl(),
            )
        )
    }
    return state
}

/**
 * A [MarkdownState] that that executes the parsing of the markdown content with the [MarkdownParser] asynchronously.
 */
@Stable
class MarkdownState internal constructor(
    private val input: Input,
) {
    private val stateFlow: MutableStateFlow<State?> = MutableStateFlow(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    private val linkStateFlow: MutableStateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())
    val links: StateFlow<Map<String, String?>> = linkStateFlow.asStateFlow()

    val nodeListState: SnapshotStateList<ParseResult> = mutableStateListOf()

    private val mutex = Mutex()

    internal fun getContent() = input.content

    fun skipLinkDefinition() = input.lookupLinks

    fun shareMode() = input.shareModel

    suspend fun parse(content: String, immediate: Boolean) {
        if (content.length < input.content.length) {
            nodeListState.clear()
        }
        input.content = content
        if (immediate) {
            parseBlocking()
        } else {
            parse()
        }
    }


    /**
     * Parses the markdown content asynchronously using the Default dispatcher.
     * When a result is available it will be emitted to the [state] flow.
     */
    suspend fun parse() = withContext(Dispatchers.Default) {
        mutex.withLock {
            parseBlocking()
        }
    }

    /**
     * Parses the markdown content synchronously.
     */
    private fun parseBlocking() {
        try {
            // 每次只解析最后一个节点
            val parsedResult: ASTNode = input.parser.buildMarkdownTreeFromString(input.content)

            if (input.lookupLinks) {
                val links = mutableMapOf<String, String?>()
                lookupLinkDefinition(links, parsedResult, input.content, recursive = true)
                links.onEach { (key, value) -> input.referenceLinkHandler.store(key, value) }
                linkStateFlow.value = links
            }

            parsedResult.children
                .map { ParseResult(it.startOffset, it.endOffset).apply { this.node = it } }
                .forEachIndexed { index, result ->
                    if (index < nodeListState.size) {
                        nodeListState.set(index, result)
                    } else {
                        nodeListState.add(result)
                    }
                }

            if (parsedResult.children.size < nodeListState.size) {
                nodeListState.removeRange(parsedResult.children.size, nodeListState.size)
            }
        } catch (error: Throwable) {
            State.Error(error, input.referenceLinkHandler)
        }
    }
}

/**
 * The input for the [MarkdownState].
 *
 * @param content The markdown content to parse.
 * @param lookupLinks Whether to lookup links in the parsed tree or not.
 * @param flavour The [MarkdownFlavourDescriptor] to use for parsing.
 * @param parser The [MarkdownParser] to use for parsing.
 * @param referenceLinkHandler The [ReferenceLinkHandler] to use for storing links.
 */
data class Input(
    var content: String,
    val lookupLinks: Boolean,
    val shareModel: Boolean,
    val flavour: MarkdownFlavourDescriptor,
    val parser: MarkdownParser,
    val referenceLinkHandler: ReferenceLinkHandler,
)

/**
 * The current state of the [MarkdownState].
 */
@Stable
sealed interface State {

    /** The [ReferenceLinkHandler] to store links in. */
    val referenceLinkHandler: ReferenceLinkHandler

    /** The parsing is in-progress. */
//    data class Loading(
//        override val referenceLinkHandler: ReferenceLinkHandler,
//    ) : State

    /** The parsing was successful. */
    data class Success(
        val node: SnapshotStateList<ParseResult>,
        val content: String,
        val linksLookedUp: Boolean,
        val cost: Long,
        override val referenceLinkHandler: ReferenceLinkHandler,
    ) : State

    /** The parsing failed due to [Throwable]. */
    data class Error(
        val result: Throwable,
        override val referenceLinkHandler: ReferenceLinkHandler,
    ) : State
}

@Stable
data class ParseResult(
    val startIndex: Int,
    val endIndex: Int
) {
    lateinit var node: ASTNode
}
