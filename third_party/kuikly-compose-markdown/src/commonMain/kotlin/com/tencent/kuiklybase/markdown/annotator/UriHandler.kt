package com.tencent.kuiklybase.markdown.annotator

import androidx.compose.runtime.staticCompositionLocalOf

interface UriHandler {
    /**
     * Open given URL in browser
     *
     * @throws IllegalArgumentException when given [uri] is invalid and/or can't be handled by the
     * system
     */
    suspend fun openUri(uri: String)

    suspend fun openRef(ref: List<String>, x: Float, y: Float)
}

val LocalUriHandler = staticCompositionLocalOf<UriHandler> {
    object : UriHandler {
        override suspend fun openUri(uri: String) {}

        override suspend fun openRef(ref: List<String>, x: Float, y: Float) {}
    }
}