package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.math.truncate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Web `runningWorkersOf` (`packages/web/src/store/events/activityTypedCarriers.ts`,
 * botiverse/slock #16819): parses an untrusted `runningWorkers` carrier from REST or realtime JSON.
 *
 * Only a finite positive whole count (`1..Int.MAX_VALUE`) is kept; `0`, negative numbers,
 * non-integers (`1.5`), strings (`"2"`), booleans, null, or objects/arrays return null (none).
 */
@RaftCoreInternalApi fun parseAgentRunningWorkers(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.longOrNull?.let { value ->
        return if (value in 1L..Int.MAX_VALUE.toLong()) value.toInt() else null
    }
    val doubleValue = primitive.doubleOrNull ?: return null
    if (!doubleValue.isFinite() || doubleValue < 1.0 || doubleValue > Int.MAX_VALUE.toDouble()) return null
    if (truncate(doubleValue) != doubleValue) return null
    return doubleValue.toInt()
}

/** Keeps a positive running-Worker count, normalizing `<= 0` or null to null. */
@RaftCoreInternalApi fun normalizeAgentRunningWorkers(count: Int?): Int? =
    count?.takeIf { it > 0 }
