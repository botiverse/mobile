package com.tencent.kuiklybase.markdown.model

import org.intellij.markdown.flavours.gfm.alert.GitHubAlertMarkerProvider
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider

internal actual fun slockGfmLeadingMarkerProviders(): List<MarkerBlockProvider<MarkerProcessor.StateInfo>> =
    listOf(GitHubAlertMarkerProvider())
