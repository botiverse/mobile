package com.tencent.kuiklybase.markdown.annotator

import com.tencent.kuikly.compose.foundation.text.InlineTextContent

interface InlineContentProvider {
    val inlineContent: Map<String, InlineTextContent>
}