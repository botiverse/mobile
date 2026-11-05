package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.AppLinkResult
import org.intellij.markdown.ast.ASTNode

/**
 * The App link block cards of the message being rendered (raft-design #1010 "Block cards"): the
 * message's answers and how one is drawn. Provided by the message row beside [LocalRaftAppLinkChips];
 * null where a surface shows only chips (everything but the message itself).
 */
@Immutable
internal class RaftMarkdownBlockCardHost(
    val results: Map<String, AppLinkResult>,
    val draw: @Composable (AppLinkResult.Card) -> Unit,
)

internal val LocalRaftMarkdownBlockCardHost = staticCompositionLocalOf<RaftMarkdownBlockCardHost?> { null }

/** The cards of one body, by the start offset of the paragraph each follows ([build.raft.app.links.planRaftBlockCards]). */
@Immutable
internal class RaftMarkdownBlockCards(
    val byParagraph: Map<Int, List<AppLinkResult.Card>>,
    val draw: @Composable (AppLinkResult.Card) -> Unit,
)

internal val LocalRaftMarkdownBlockCards = staticCompositionLocalOf<RaftMarkdownBlockCards?> { null }

/** The block cards that follow the paragraph starting at [paragraphStart], in link order; nothing for most paragraphs. */
@Composable
internal fun RaftMarkdownBlockCardsAfter(paragraphStart: Int) {
    val cards = LocalRaftMarkdownBlockCards.current ?: return
    val following = cards.byParagraph[paragraphStart] ?: return
    following.forEach { card -> cards.draw(card) }
}

/** The cards after a list item's paragraph ([com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownAfterListParagraph]): inside the item. */
internal fun RaftMarkdownBlockCards.afterListParagraph(): @Composable (ASTNode) -> Unit =
    { node -> byParagraph[node.startOffset]?.forEach { card -> draw(card) } }
