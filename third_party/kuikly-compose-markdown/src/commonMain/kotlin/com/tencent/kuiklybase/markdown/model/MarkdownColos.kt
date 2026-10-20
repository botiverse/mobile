package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.tencent.kuiklybase.markdown.compose.Markdown


@Stable
interface MarkdownColors {
    /** Represents the color used for the text of this [Markdown] component. */
    val text: Color

    /** Represents the color used for the text of code. */
    @Deprecated("Set color via `codeText` textStyle via `MarkdownTypography` instead")
    val codeText: Color

    /** Represents the color used for the text of code. */
    @Deprecated("Set color via `inlineCode` textStyle via `MarkdownTypography` instead")
    val inlineCodeText: Color

    /** Represents the color used for the text of links. */
    @Deprecated("Set color via `linkText` textStyle via `MarkdownTypography` instead")
    val linkText: Color

    /** Represents the color used for the background of code. */
    val codeBackground: Color

    /** Represents the color used for the inline background of code. */
    val inlineCodeBackground: Color

    /** Represents the color used for the color of dividers. */
    val dividerColor: Color

    /** Represents the color used for the text of tables. */
    @Deprecated("Set color via `tableText` textStyle via `MarkdownTypography` instead")
    val tableText: Color

    /** Represents the color used for the background of tables. */
    val tableBackground: Color

    /** Represents the color used for the background of table headers.*/
    val tableHeaderBackground: Color

    /** Represents the color used for the stroke of tables. */
    val tableStroke: Color
}

@Immutable
class DefaultMarkdownColors(
    override val text: Color,
    @Deprecated("Set color via `codeText` textStyle via `MarkdownTypography` instead")
    override val codeText: Color,
    @Deprecated("Set color via `inlineCode` textStyle via `MarkdownTypography` instead")
    override val inlineCodeText: Color,
    @Deprecated("Set color via `linkText` textStyle via `MarkdownTypography` instead")
    override val linkText: Color,
    override val codeBackground: Color,
    override val inlineCodeBackground: Color,
    override val dividerColor: Color,
    @Deprecated("Set color via `tableText` textStyle via `MarkdownTypography` instead")
    override val tableText: Color,
    override val tableBackground: Color,
    override val tableHeaderBackground: Color,
    override val tableStroke: Color
) : MarkdownColors
