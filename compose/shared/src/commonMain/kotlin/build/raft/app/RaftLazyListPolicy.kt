package build.raft.app

import kotlin.math.ceil

internal fun raftViewportBeyondBoundsItemCount(
    itemCount: Int,
    viewportHeightDp: Float,
    representativeRowStepDp: Float,
    minimumItems: Int,
    maximumItems: Int,
    viewportFraction: Float = 1f
): Int {
    val safeItemCount = itemCount.coerceAtLeast(0)
    if (safeItemCount == 0) return 0

    val safeMinimum = minimumItems.coerceAtLeast(0)
    val safeMaximum = maximumItems.coerceAtLeast(safeMinimum)
    val measuredTarget =
        if (
            viewportHeightDp.isFinite() && viewportHeightDp > 0f &&
            representativeRowStepDp.isFinite() && representativeRowStepDp > 0f &&
            viewportFraction.isFinite() && viewportFraction > 0f
        ) {
            ceil(viewportHeightDp * viewportFraction / representativeRowStepDp).toInt()
        } else {
            safeMinimum
        }
    val boundedTarget = measuredTarget.coerceIn(safeMinimum, safeMaximum)
    return safeItemCount.coerceAtMost(boundedTarget)
}
