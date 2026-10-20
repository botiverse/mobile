package com.tencent.kuiklybase.markdown.annotator

import androidx.compose.foundation.text.InlineTextContent

interface InlineContentProvider {
    val inlineContent: Map<String, InlineTextContent>
}