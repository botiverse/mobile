package build.raft.app

import build.raft.app.markdown.normalizeThreadMarkdownBody

internal const val ThreadLongMessagePreviewLineLimit = 8
internal const val ThreadLongMessagePreviewCharLimit = 1_200

internal data class ThreadLongMessageRenderPlan(
    val collapsible: Boolean,
    val collapsedBody: String,
    val collapsedDisplayText: String,
    val lineCount: Int
)

internal fun threadLongMessageRenderPlan(
    body: String,
    lineLimit: Int = ThreadLongMessagePreviewLineLimit,
    charLimit: Int = ThreadLongMessagePreviewCharLimit
): ThreadLongMessageRenderPlan {
    val normalizedBody = body.normalizeThreadMarkdownBody()
    if (normalizedBody.isEmpty()) {
        return ThreadLongMessageRenderPlan(
            collapsible = false,
            collapsedBody = normalizedBody,
            collapsedDisplayText = normalizedBody,
            lineCount = 0
        )
    }
    val safeLineLimit = lineLimit.coerceAtLeast(1)
    val safeCharLimit = charLimit.coerceAtLeast(1)
    var lineCount = 1
    var lineCutIndex = normalizedBody.length
    val lineScanLimit = minOf(normalizedBody.length, safeCharLimit)
    for (index in 0 until lineScanLimit) {
        if (normalizedBody[index] == '\n') {
            lineCount += 1
            if (lineCount == safeLineLimit + 1 && lineCutIndex == normalizedBody.length) {
                lineCutIndex = index
                break
            }
        }
    }
    val collapsible = lineCutIndex < normalizedBody.length || normalizedBody.length > safeCharLimit
    if (!collapsible) {
        return ThreadLongMessageRenderPlan(
            collapsible = false,
            collapsedBody = normalizedBody,
            collapsedDisplayText = normalizedBody,
            lineCount = lineCount
        )
    }
    val targetCutIndex = minOf(lineCutIndex, safeCharLimit, normalizedBody.length)
    val safeCutIndex =
        normalizedBody.threadLongMessageSafeCutIndex(
            targetCutIndex = targetCutIndex,
            preferWhitespaceBoundary = targetCutIndex == safeCharLimit && safeCharLimit < lineCutIndex
        )
    val preview = normalizedBody.substring(0, safeCutIndex).trimEnd()
    val openFence = preview.threadOpenMarkdownFence()
    return ThreadLongMessageRenderPlan(
        collapsible = true,
        collapsedBody =
            if (openFence == null || preview.isEmpty()) {
                preview
            } else {
                "$preview\n${openFence.first.toString().repeat(openFence.second)}"
            },
        collapsedDisplayText = preview.trimEnd() + "…",
        lineCount = lineCount
    )
}

private fun String.threadLongMessageSafeCutIndex(
    targetCutIndex: Int,
    preferWhitespaceBoundary: Boolean
): Int {
    var cutIndex = targetCutIndex.coerceIn(0, length)
    if (preferWhitespaceBoundary) {
        val preferredBoundaryStart = maxOf((cutIndex * 3) / 4, cutIndex - 160)
        for (index in cutIndex downTo (preferredBoundaryStart + 1)) {
            if (this[index - 1].isWhitespace()) {
                cutIndex = index - 1
                break
            }
        }
    }
    if (
        cutIndex > 0 &&
        cutIndex < length &&
        this[cutIndex - 1].isHighSurrogate() &&
        this[cutIndex].isLowSurrogate()
    ) {
        cutIndex -= 1
    }
    return cutIndex
}

private fun String.threadOpenMarkdownFence(): Pair<Char, Int>? {
    var openFence: Pair<Char, Int>? = null
    lines().forEach { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return@forEach
        val markerLength = trimmed.takeWhile { it == marker }.length
        if (markerLength < 3) return@forEach
        val currentFence = openFence
        if (currentFence == null) {
            openFence = marker to markerLength
        } else if (
            marker == currentFence.first &&
            markerLength >= currentFence.second &&
            trimmed.drop(markerLength).isBlank()
        ) {
            openFence = null
        }
    }
    return openFence
}

internal fun threadExpandedLongMessageIdsAfterToggle(
    expandedMessageIds: Set<String>,
    messageId: String
): Set<String> =
    if (messageId in expandedMessageIds) {
        expandedMessageIds - messageId
    } else {
        expandedMessageIds + messageId
    }

internal fun threadLongMessageCollapseAnchorOffset(
    firstVisibleItemIndex: Int,
    toggledItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    collapsedItemHeight: Int
): Int? {
    if (
        firstVisibleItemIndex != toggledItemIndex ||
        firstVisibleItemScrollOffset <= 0 ||
        collapsedItemHeight <= 0
    ) {
        return null
    }
    return firstVisibleItemScrollOffset.coerceAtMost(collapsedItemHeight - 1)
}
